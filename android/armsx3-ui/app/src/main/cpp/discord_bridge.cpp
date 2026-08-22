// Discord Social SDK bridge for ARMSX3.
//
// Produces libarmsx3_discord.so, which DiscordNative.java loads. It runs in its
// own process (android:process=":discord" in the manifest) on purpose, not for
// performance: the Social SDK is proprietary with no published licence, and the
// emulator core is GPL. Keeping it in a separate process keeps the two out of
// one address space.
//
// THREADING: the SDK is not thread-safe. Every call here must come from the
// thread that created the Client, and Discord_RunCallbacks() must be pumped from
// that same thread -- DiscordService owns a single thread and calls pump() on it.
// Nothing here takes a lock, because the contract is "one thread", not "locked".

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// discordpp.h is a header-only C++ wrapper over the SDK's C API (the .so
// exports 513 Discord_* symbols and zero discordpp:: ones). Its method bodies
// only compile when DISCORDPP_IMPLEMENTATION is defined, in exactly one
// translation unit -- without it every wrapper call is an undefined symbol at
// link time.
#define DISCORDPP_IMPLEMENTATION
#include <discordpp.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ARMSX3-Discord", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ARMSX3-Discord", __VA_ARGS__)

namespace {

/// ARMSX3's Discord application id. Must match the redirect scheme in the
/// manifest (discord-1534624714989764829) or the OAuth callback lands nowhere.
constexpr uint64_t kApplicationId = 1534624714989764829ULL;

/// Field separator for the string lists handed back to Kotlin. \x1F (US) is used
/// rather than a comma because display names and game titles routinely contain
/// punctuation, and a delimiter that can appear in the data is a parsing bug
/// waiting to happen.
constexpr char kFS = '\x1F';
/// Record separator between friends.
constexpr char kRS = '\x1E';

/// DiscordPresence's BridgeStatus values. These are NOT the SDK's enum, and the
/// two do not line up -- SDK Ready is 3, which is DiscordPresence.CONNECTING.
/// Returning the raw SDK value made a fully connected client report "connecting"
/// forever, so the mapping has to be explicit.
constexpr int kDisconnected = 1;
constexpr int kConnecting = 3;
constexpr int kConnected = 4;
constexpr int kFailed = 5;

int toBridgeStatus(discordpp::Client::Status s) {
    switch (s) {
    case discordpp::Client::Status::Disconnected:
        return kDisconnected;
    case discordpp::Client::Status::Connecting:
    case discordpp::Client::Status::Connected:
    case discordpp::Client::Status::Reconnecting:
    case discordpp::Client::Status::HttpWait:
        // Connected is NOT ready: relationships and presence are only usable
        // once the SDK reaches Ready, so anything short of that is "connecting"
        // as far as the UI is concerned.
        return kConnecting;
    case discordpp::Client::Status::Ready:
        return kConnected;
    case discordpp::Client::Status::Disconnecting:
        return kDisconnected;
    default:
        return kFailed;
    }
}

struct State {
    std::shared_ptr<discordpp::Client> client;

    // Written by SDK callbacks (on the pump thread) and read by JNI getters
    // (which DiscordService also calls from that thread). Atomic anyway so a
    // stray read from elsewhere cannot tear.
    std::atomic<int> status{kDisconnected};
    std::mutex text_mutex;
    std::string token;            // pending OAuth token, taken once
    std::string error;

    // ready is written from the SDK status callback and read from setPlaying,
    // which DiscordService calls from its message handler -- a DIFFERENT thread
    // from the pump. Atomic, not a plain bool.
    std::atomic<bool> ready{false};

    // Last presence requested. The app pushes presence as soon as it sees the
    // CONNECTED status, which can land before the SDK reports Ready; dropping it
    // then meant presence was never set at all for the whole session. Hold it and
    // replay on Ready instead.
    std::string want_serial;
    std::string want_title;
    std::string want_cover;
    std::string want_ra;
    bool want_pending = false;
};

void applyPresence(const std::string& serial, const std::string& title,
                   const std::string& cover, const std::string& ra);

State& state() {
    static State s;
    return s;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char* buf = env->GetStringUTFChars(s, nullptr);
    std::string out(buf ? buf : "");
    if (buf) {
        env->ReleaseStringUTFChars(s, buf);
    }
    return out;
}

/// Discord hands back an avatar HASH, not a URL. The app feeds these straight to
/// an image loader, so the CDN URL has to be built here or nothing renders.
/// Users with no custom avatar have no hash at all and get a default, which is
/// keyed off the account id.
std::string avatarUrl(uint64_t userId, const std::optional<std::string>& hash) {
    if (hash.has_value() && !hash->empty()) {
        return "https://cdn.discordapp.com/avatars/" + std::to_string(userId) + "/" +
               *hash + ".png?size=128";
    }
    return "https://cdn.discordapp.com/embed/avatars/" +
           std::to_string((userId >> 22) % 6) + ".png";
}

jstring jout(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_armsx2_discord_DiscordNative_available(JNIEnv*, jclass) {
    // Reaching this at all means libdiscord_partner_sdk.so resolved, since this
    // library links against it.
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_armsx2_discord_DiscordNative_start(JNIEnv* env, jclass, jstring jtoken) {
    State& s = state();
    if (s.client) {
        return;
    }

    s.client = std::make_shared<discordpp::Client>();
    s.client->SetApplicationId(kApplicationId);

    s.client->SetStatusChangedCallback(
        [](discordpp::Client::Status status, discordpp::Client::Error, int32_t code) {
            State& st = state();
            st.status.store(toBridgeStatus(status));

            if (status == discordpp::Client::Status::Ready) {
                st.ready.store(true);
                LOGI("connected");

                // Replay presence that arrived before we were ready.
                bool replay = false;
                std::string serial, title, cover, ra;
                {
                    std::lock_guard<std::mutex> lock(st.text_mutex);
                    if (st.want_pending) {
                        replay = true;
                        serial = st.want_serial;
                        title = st.want_title;
                        cover = st.want_cover;
                        ra = st.want_ra;
                        st.want_pending = false;
                    }
                }
                if (replay) {
                    LOGI("replaying queued presence");
                    applyPresence(serial, title, cover, ra);
                }
            } else if (status == discordpp::Client::Status::Disconnected && code != 0) {
                std::lock_guard<std::mutex> lock(st.text_mutex);
                st.error = "disconnected (" + std::to_string(code) + ")";
                LOGE("%s", st.error.c_str());
            }
        });

    // A saved token skips the whole OAuth round trip, which is the normal path
    // after the first sign-in.
    const std::string saved = jstr(env, jtoken);
    if (!saved.empty()) {
        s.client->UpdateToken(discordpp::AuthorizationTokenType::Bearer, saved,
                              [](discordpp::ClientResult result) {
                                  if (result.Successful()) {
                                      state().client->Connect();
                                  } else {
                                      LOGE("saved token rejected: %s",
                                           result.Error().c_str());
                                      // Not fatal: the UI can offer sign-in again.
                                      std::lock_guard<std::mutex> lock(state().text_mutex);
                                      state().error = "token expired";
                                  }
                              });
    }
}

JNIEXPORT void JNICALL
Java_com_armsx2_discord_DiscordNative_authorize(JNIEnv*, jclass) {
    State& s = state();
    if (!s.client) {
        return;
    }

    // PKCE: the SDK generates the verifier/challenge pair for us. Skipping it
    // would make the authorization code interceptable by any app that can claim
    // the redirect scheme.
    auto verifier = s.client->CreateAuthorizationCodeVerifier();

    discordpp::AuthorizationArgs args{};
    args.SetClientId(kApplicationId);
    args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
    args.SetCodeChallenge(verifier.Challenge());

    s.client->Authorize(
        args,
        [verifier](discordpp::ClientResult result, const std::string& code,
                   const std::string& redirectUri) {
            if (!result.Successful()) {
                LOGE("authorize failed: %s", result.Error().c_str());
                std::lock_guard<std::mutex> lock(state().text_mutex);
                state().error = result.Error();
                return;
            }

            state().client->GetToken(
                kApplicationId, code, verifier.Verifier(), redirectUri,
                [](discordpp::ClientResult tokenResult, const std::string& accessToken,
                   const std::string&, discordpp::AuthorizationTokenType, int32_t,
                   const std::string&) {
                    if (!tokenResult.Successful()) {
                        LOGE("token exchange failed: %s", tokenResult.Error().c_str());
                        std::lock_guard<std::mutex> lock(state().text_mutex);
                        state().error = tokenResult.Error();
                        return;
                    }

                    {
                        // Handed to Kotlin via takeToken() so it can persist it;
                        // this process does not own storage.
                        std::lock_guard<std::mutex> lock(state().text_mutex);
                        state().token = accessToken;
                    }

                    state().client->UpdateToken(
                        discordpp::AuthorizationTokenType::Bearer, accessToken,
                        [](discordpp::ClientResult r) {
                            if (r.Successful()) {
                                state().client->Connect();
                            }
                        });
                });
        });
}

JNIEXPORT jstring JNICALL
Java_com_armsx2_discord_DiscordNative_takeToken(JNIEnv* env, jclass) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.text_mutex);
    std::string out;
    // Take-once: the caller persists it, and leaving a bearer token sitting in
    // memory longer than necessary is pointless exposure.
    out.swap(s.token);
    return jout(env, out);
}

JNIEXPORT jint JNICALL
Java_com_armsx2_discord_DiscordNative_status(JNIEnv*, jclass) {
    return state().status.load();
}

JNIEXPORT jstring JNICALL
Java_com_armsx2_discord_DiscordNative_error(JNIEnv* env, jclass) {
    State& s = state();
    std::lock_guard<std::mutex> lock(s.text_mutex);
    return jout(env, s.error);
}

JNIEXPORT void JNICALL
Java_com_armsx2_discord_DiscordNative_setPlaying(JNIEnv* env, jclass, jstring jserial,
                                                 jstring jtitle, jstring jcoverUrl,
                                                 jstring jraPresence) {
    State& s = state();
    const std::string serial = jstr(env, jserial);
    const std::string title = jstr(env, jtitle);
    const std::string cover = jstr(env, jcoverUrl);
    const std::string ra = jstr(env, jraPresence);

    if (!s.client || !s.ready.load()) {
        // Queue rather than drop: the app pushes on the CONNECTED edge, which can
        // beat the SDK's Ready callback.
        std::lock_guard<std::mutex> lock(s.text_mutex);
        s.want_serial = serial;
        s.want_title = title;
        s.want_cover = cover;
        s.want_ra = ra;
        s.want_pending = true;
        LOGI("presence queued until ready");
        return;
    }

    applyPresence(serial, title, cover, ra);
}

}  // extern "C"

namespace {

void applyPresence(const std::string& serial, const std::string& title,
                   const std::string& cover, const std::string& ra) {
    State& s = state();
    if (!s.client) {
        return;
    }

    discordpp::Activity activity{};
    activity.SetType(discordpp::ActivityTypes::Playing);
    activity.SetName("ARMSX3");

    // Details is the line rendered UNDER the application name on the activity
    // card -- it is where the game name belongs. The title was only ever going
    // into LargeText, which is the hover tooltip on the cover, so the card read
    // "ARMSX3" with artwork and no idea what was being played.
    if (!title.empty()) {
        activity.SetDetails(title);
    }

    // Second line. RetroAchievements rich presence describes what you are doing
    // right now ("Street: 12 of 40 challenges"); without it there is nothing
    // meaningful to put here, so the line is left off rather than padded.
    if (!ra.empty()) {
        activity.SetState(ra);
    }

    // Assets are OPTIONAL and must be set carefully: an asset KEY (like "icon")
    // only resolves if that name has been uploaded under Rich Presence -> Art
    // Assets in the Discord developer portal. Referencing a key that does not
    // exist makes Discord reject the WHOLE presence update -- which is why
    // nothing appeared at all rather than just appearing without artwork.
    //
    // So: use the cover URL when we have one (the API accepts an https URL here,
    // no upload required), and otherwise send no assets rather than a key we
    // cannot guarantee exists.
    // The app icon, by URL rather than by asset key, for the reason above: a key that is not in
    // the portal makes Discord reject the whole update, while an https URL always resolves. It is
    // the launcher icon out of this repository, so it cannot drift from the app it represents.
    static constexpr const char* kLogoUrl =
        "https://raw.githubusercontent.com/ARMSX2/ARMSX3/master/android/armsx3-ui/app/src/main/"
        "res/mipmap-xxxhdpi/ic_launcher.png";

    discordpp::ActivityAssets assets{};

    if (!cover.empty()) {
        // Cover art large, emulator small -- the shape ARMSX2 uses, and the one that reads as
        // "playing this, on this" rather than just naming a game.
        assets.SetLargeImage(cover);
        if (!title.empty()) {
            assets.SetLargeText(title);
        }
        assets.SetSmallImage(kLogoUrl);
        assets.SetSmallText("ARMSX3");
    } else {
        // No cover: the logo takes the large slot instead of sending no assets at all, which is
        // what used to happen and left the card blank.
        assets.SetLargeImage(kLogoUrl);
        assets.SetLargeText("ARMSX3");
    }

    activity.SetAssets(assets);

    LOGI("presence: title='%s' serial='%s' cover=%s ra=%s", title.c_str(), serial.c_str(),
         cover.empty() ? "none" : "yes", ra.empty() ? "none" : "yes");

    s.client->UpdateRichPresence(activity, [](discordpp::ClientResult result) {
        if (result.Successful()) {
            LOGI("presence accepted");
        } else {
            LOGE("presence update failed: %s", result.Error().c_str());
        }
    });
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_armsx2_discord_DiscordNative_friends(JNIEnv* env, jclass) {
    State& s = state();
    if (!s.client || !s.ready.load()) {
        return jout(env, "");
    }

    std::string out;
    for (const auto& rel : s.client->GetRelationships()) {
        const auto user = rel.User();
        if (!user.has_value()) {
            continue;
        }

        // Only friends who are actually in ARMSX3 -- the point of the tab is
        // "who can I play with", not a full contact list.
        const auto activity = user->GameActivity();
        if (!activity.has_value() || activity->ApplicationId() != kApplicationId) {
            continue;
        }

        if (!out.empty()) {
            out += kRS;
        }
        out += user->DisplayName();
        out += kFS;
        out += activity->Details().value_or("");
        out += kFS;
        out += activity->State().value_or("");
        out += kFS;
        out += avatarUrl(user->Id(), user->Avatar());
    }

    return jout(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_armsx2_discord_DiscordNative_self(JNIEnv* env, jclass) {
    State& s = state();
    if (!s.client || !s.ready.load()) {
        return jout(env, "");
    }

    const auto me = s.client->GetCurrentUser();
    std::string out = me.DisplayName();
    out += kFS;
    out += avatarUrl(me.Id(), me.Avatar());
    return jout(env, out);
}

JNIEXPORT void JNICALL
Java_com_armsx2_discord_DiscordNative_pump(JNIEnv*, jclass) {
    // Must run on the thread that created the Client. DiscordService guarantees
    // that; calling it from anywhere else is undefined behaviour in the SDK.
    discordpp::RunCallbacks();
}

JNIEXPORT void JNICALL
Java_com_armsx2_discord_DiscordNative_stop(JNIEnv*, jclass) {
    State& s = state();
    if (!s.client) {
        return;
    }

    s.client->Disconnect();
    s.client.reset();
    s.ready.store(false);
    s.status.store(kDisconnected);
    LOGI("stopped");
}

}  // extern "C"
