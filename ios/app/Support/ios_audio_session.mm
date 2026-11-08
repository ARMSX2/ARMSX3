// SPDX-License-Identifier: GPL-2.0-only
// The AVAudioSession contract, implemented. The non-iOS arm is a named refusal, never a fallback.

// Included explicitly: an undefined TARGET_OS_IPHONE would silently take the refusal arm on iOS.
#include <TargetConditionals.h>

#include "ios_audio_session.h"
#include "../../core/ios_stderr_witness.h"

#include <mutex>
#include <stdio.h>
#include <string.h>

namespace
{
	// Non-recursive on purpose: re-entry deadlocks visibly instead of leaving a stream on a deactivated session.
	std::mutex g_lock;

	// Wanted versus running, converged by the idempotent apply_locked(), so notification order cannot start a stream twice.
	bool g_wanted = false;
	bool g_running = false;
	bool g_interrupted = false;
	bool g_configured = false;
	bool g_observers = false;

	armsx3_audio_stream_controller_t g_controller = { nullptr, nullptr, nullptr };

	armsx3_audio_session_state_t g_state = ARMSX3_AUDIO_SESSION_UNCONFIGURED;
	long g_code = 0;
	char g_error[192] = { 0 };
	char g_branch[64] = ARMSX3_AUDIO_BRANCH_NONE;
	unsigned long g_starts = 0;
	unsigned long g_stops = 0;

	void set_branch_locked(const char* branch)
	{
		snprintf(g_branch, sizeof(g_branch), "%s", branch);
		armsx3_stderr_witness_printf("@@AUDIO@@ branch=%s wanted=%d running=%d\n", branch, (int)g_wanted, (int)g_running);
	}

	void set_error_locked(long code, const char* text)
	{
		g_code = code;
		snprintf(g_error, sizeof(g_error), "%s", text);
		g_state = ARMSX3_AUDIO_SESSION_FAILED;
		armsx3_stderr_witness_printf("@@AUDIO@@ error code=%ld %s\n", code, text);
	}

	void clear_error_locked()
	{
		g_code = 0;
		g_error[0] = '\0';
	}
}

#if TARGET_OS_IPHONE

// The iOS arm.

#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>

namespace
{
	// Playback without mixWithOthers: game audio is primary, plays on silent, and mixing would hide interruptions.
	const AVAudioSessionCategoryOptions k_category_options = kNilOptions;

	AVAudioSession* session()
	{
		return [AVAudioSession sharedInstance];
	}

	// A port-type word only, never a name or UID that could identify a device.
	const char* output_word()
	{
		NSString* type = [[[[session() currentRoute] outputs] firstObject] portType];

		if (type == nil)
		{
			return "other";
		}
		if ([type isEqualToString:AVAudioSessionPortBuiltInSpeaker])
		{
			return "speaker";
		}
		if ([type isEqualToString:AVAudioSessionPortBuiltInReceiver])
		{
			return "receiver";
		}
		if ([type isEqualToString:AVAudioSessionPortHeadphones])
		{
			return "headphones";
		}
		if ([type isEqualToString:AVAudioSessionPortBluetoothA2DP] ||
		    [type isEqualToString:AVAudioSessionPortBluetoothLE] ||
		    [type isEqualToString:AVAudioSessionPortBluetoothHFP])
		{
			return "bluetooth";
		}
		return "other";
	}

	id g_interruption_token = nil;
	id g_route_token = nil;

	// The handlers and apply path call each other's neighbours.
	void apply_locked();
	void handle_interruption(NSNotification* note);
	void handle_route_change(NSNotification* note);

	// Domain and code only: a route error's userInfo can carry a user-chosen port name.
	void record_ns_error_locked(NSError* err, const char* attempting)
	{
		char text[192];
		snprintf(text, sizeof(text), "%s: domain=%s code=%ld",
		         attempting,
		         err != nil ? [[err domain] UTF8String] : "none",
		         err != nil ? (long)[err code] : 0L);
		set_error_locked(err != nil ? (long)[err code] : -1L, text);
	}

	// A refusal that is not a session failure, so a working session still reads ACTIVE.
	void warn_ns_error_locked(NSError* err, const char* attempting)
	{
		armsx3_stderr_witness_printf("@@AUDIO@@ warning %s: domain=%s code=%ld\n",
		        attempting,
		        err != nil ? [[err domain] UTF8String] : "none",
		        err != nil ? (long)[err code] : 0L);
	}

	bool activate_locked()
	{
		NSError* err = nil;

		if ([session() setActive:YES error:&err])
		{
			return true;
		}

		// Common, since the category does not mix, and a swallowed activation failure is silence with nothing logged.
		if (err != nil && [err code] == AVAudioSessionErrorCodeCannotInterruptOthers)
		{
			set_error_locked((long)AVAudioSessionErrorCodeCannotInterruptOthers,
			                 "setActive:YES refused: another application holds a non-mixable "
			                 "session (AVAudioSessionErrorCodeCannotInterruptOthers)");
			return false;
		}

		record_ns_error_locked(err, "setActive:YES failed");
		return false;
	}

	void deactivate_locked()
	{
		NSError* err = nil;

		// A session that took audio away tells the others it is finished.
		if (![session() setActive:NO
		              withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
		                    error:&err])
		{
			record_ns_error_locked(err, "setActive:NO failed");
		}
	}

	// The one path that changes the stream, so activation order is a property of one function.
	void apply_locked()
	{
		const bool want = g_wanted && !g_interrupted;

		if (want && !g_running)
		{
			if (!activate_locked())
			{
				return; /* state is FAILED and the reason is named; nothing was started. */
			}

			if (g_controller.create_and_start == nullptr)
			{
				// No controller: the session is active with nothing to start, so it does not report ACTIVE.
				g_state = ARMSX3_AUDIO_SESSION_CONFIGURED;
				return;
			}

			const int rc = g_controller.create_and_start(g_controller.ctx);

			if (rc != 0)
			{
				set_error_locked((long)rc, "stream create_and_start failed after the session "
				                           "activated; the session is left active and the stream "
				                           "is not running");
				return;
			}

			g_running = true;
			g_starts++;
			clear_error_locked();
			g_state = ARMSX3_AUDIO_SESSION_ACTIVE;
			return;
		}

		if (!want && g_running)
		{
			if (g_controller.stop_and_destroy != nullptr)
			{
				const int rc = g_controller.stop_and_destroy(g_controller.ctx);

				if (rc != 0)
				{
					// Treated as stopped anyway, since believing a failed stop left it running produces a double start.
					set_error_locked((long)rc, "stream stop_and_destroy reported failure; treated "
					                           "as stopped so no second start can be issued");
				}
			}

			g_running = false;
			g_stops++;

			// Deactivate only after the stream is down.
			deactivate_locked();
		}
	}

	void handle_interruption(NSNotification* note)
	{
		NSNumber* raw = [[note userInfo] objectForKey:AVAudioSessionInterruptionTypeKey];

		if (raw == nil)
		{
			return;
		}

		const AVAudioSessionInterruptionType type =
			(AVAudioSessionInterruptionType)[raw unsignedIntegerValue];

		std::lock_guard<std::mutex> guard(g_lock);

		if (type == AVAudioSessionInterruptionTypeBegan)
		{
			// Destroy, do not pause: iOS stops the audio unit under cubeb, and a restart is not assumed to recover it.
			g_interrupted = true;
			set_branch_locked(ARMSX3_AUDIO_BRANCH_INTERRUPTION_BEGAN);
			apply_locked();
			g_state = ARMSX3_AUDIO_SESSION_INTERRUPTED;
			return;
		}

		if (type == AVAudioSessionInterruptionTypeEnded)
		{
			// The resume hint is honoured; reactivating unconditionally would take audio back from whatever took it.
			NSNumber* opts = [[note userInfo] objectForKey:AVAudioSessionInterruptionOptionKey];
			const AVAudioSessionInterruptionOptions options =
				opts != nil ? (AVAudioSessionInterruptionOptions)[opts unsignedIntegerValue]
				            : (AVAudioSessionInterruptionOptions)0;

			if ((options & AVAudioSessionInterruptionOptionShouldResume) == 0)
			{
				set_branch_locked(ARMSX3_AUDIO_BRANCH_INTERRUPTION_NO_RESUME);
				g_state = ARMSX3_AUDIO_SESSION_INTERRUPTED;
				return;
			}

			g_interrupted = false;
			set_branch_locked(ARMSX3_AUDIO_BRANCH_INTERRUPTION_RESUME);
			apply_locked();
		}
	}

	void handle_route_change(NSNotification* note)
	{
		NSNumber* raw = [[note userInfo] objectForKey:AVAudioSessionRouteChangeReasonKey];

		if (raw == nil)
		{
			return;
		}

		const AVAudioSessionRouteChangeReason reason =
			(AVAudioSessionRouteChangeReason)[raw unsignedIntegerValue];

		// Only the count is taken from the route; no port name is copied.
		const NSUInteger outputs = [[[session() currentRoute] outputs] count];

		std::lock_guard<std::mutex> guard(g_lock);

		// No output first: restarting against no device looks like a hang. It gets its own branch label.
		if (outputs == 0)
		{
			g_wanted = false;
			set_branch_locked(ARMSX3_AUDIO_BRANCH_ROUTE_NO_OUTPUT);
			apply_locked();
			g_state = ARMSX3_AUDIO_SESSION_ROUTE_PAUSED;
			return;
		}

		if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable)
		{
			// A pause, not a stop: pulled headphones must not blast the speaker, and a later start() brings it back.
			g_wanted = false;
			set_branch_locked(ARMSX3_AUDIO_BRANCH_ROUTE_OLD_DEVICE_GONE);
			apply_locked();
			g_state = ARMSX3_AUDIO_SESSION_ROUTE_PAUSED;
			return;
		}

		// Recreate against the new route once; the idempotent apply makes a running stream's stop/start collapse.
		if (g_wanted)
		{
			set_branch_locked(ARMSX3_AUDIO_BRANCH_ROUTE_RESTART);
			g_wanted = false;
			apply_locked();  /* tear the stream down off the old route */
			g_wanted = true;
			apply_locked();  /* and build it again on the new one */
		}
	}

	void register_observers_locked()
	{
		if (g_observers)
		{
			return;
		}

		// Both on the main queue, so one event's two notifications cannot interleave.
		NSNotificationCenter* centre = [NSNotificationCenter defaultCenter];

		g_interruption_token =
			[centre addObserverForName:AVAudioSessionInterruptionNotification
			                    object:session()
			                     queue:[NSOperationQueue mainQueue]
			                usingBlock:^(NSNotification* note) { handle_interruption(note); }];

		g_route_token =
			[centre addObserverForName:AVAudioSessionRouteChangeNotification
			                    object:session()
			                     queue:[NSOperationQueue mainQueue]
			                usingBlock:^(NSNotification* note) { handle_route_change(note); }];

		g_observers = (g_interruption_token != nil && g_route_token != nil);
	}
}

int armsx3_audio_session_configure(void)
{
	std::lock_guard<std::mutex> guard(g_lock);

	if (g_configured)
	{
		return 0;
	}

	NSError* err = nil;

	if (![session() setCategory:AVAudioSessionCategoryPlayback
	                       mode:AVAudioSessionModeDefault
	                    options:k_category_options
	                      error:&err])
	{
		record_ns_error_locked(err, "setCategory:AVAudioSessionCategoryPlayback failed");
		return -1;
	}

	register_observers_locked();

	if (!g_observers)
	{
		set_error_locked(-1, "the category was set but at least one notification observer failed "
		                     "to register; interruption and route-change handling would be silently "
		                     "absent, so configure() reports failure rather than partial success");
		return -1;
	}

	g_configured = true;
	clear_error_locked();
	g_state = ARMSX3_AUDIO_SESSION_CONFIGURED;
	return 0;
}

// After configure(), whose setCategory: the test reads first. The call-quality Bluetooth option would drop to a call route.
int armsx3_audio_session_set_recording(int on)
{
	std::lock_guard<std::mutex> guard(g_lock);

	NSError* err = nil;
	const BOOL ok = on
		? [session() setCategory:AVAudioSessionCategoryPlayAndRecord
		                    mode:AVAudioSessionModeDefault
		                 options:AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetoothA2DP
		                   error:&err]
		: [session() setCategory:AVAudioSessionCategoryPlayback
		                    mode:AVAudioSessionModeDefault
		                 options:k_category_options
		                   error:&err];

	if (!ok)
	{
		record_ns_error_locked(err, on ? "set_recording: the play-and-record category failed"
		                               : "set_recording: the playback category failed");
		return -1;
	}

	// The phone's rumble needs haptics while recording; a refusal is only a warning and keeps the switch.
	if (on && ![session() setAllowHapticsAndSystemSoundsDuringRecording:YES error:&err])
	{
		warn_ns_error_locked(err, "set_recording: allowing haptics while recording failed");
	}

	// A category word and a port-type word, nothing that names a device.
	armsx3_stderr_witness_printf("ARMSX3: audio session category=%s output=%s\n", on ? "playAndRecord" : "playback", output_word());
	return 0;
}

int armsx3_audio_session_start(void)
{
	std::lock_guard<std::mutex> guard(g_lock);

	if (!g_configured)
	{
		set_error_locked(-1, "start() before a successful configure(): the category is unset and "
		                     "no observer is registered");
		return -1;
	}

	g_wanted = true;
	g_interrupted = false;
	set_branch_locked(ARMSX3_AUDIO_BRANCH_START);
	apply_locked();

	return (g_state == ARMSX3_AUDIO_SESSION_ACTIVE ||
	        (g_state == ARMSX3_AUDIO_SESSION_CONFIGURED && g_controller.create_and_start == nullptr))
	       ? 0 : -1;
}

int armsx3_audio_session_stop(void)
{
	std::lock_guard<std::mutex> guard(g_lock);

	g_wanted = false;
	set_branch_locked(ARMSX3_AUDIO_BRANCH_STOP);
	apply_locked();

	if (g_configured && !g_running)
	{
		// A stop() with no stream still owes the deactivation a controller-less start() activated.
		deactivate_locked();
	}

	g_state = ARMSX3_AUDIO_SESSION_STOPPED;
	return 0;
}

#else /* !TARGET_OS_IPHONE */

// The non-iOS arm: a named refusal only.

// AVAudioSession does not exist on macOS, and a success here would be a false pass.

int armsx3_audio_session_configure(void)
{
	std::lock_guard<std::mutex> guard(g_lock);
	set_error_locked(-1, "AVAudioSession does not exist on this platform; there is no audio "
	                     "session to configure and this is not a fallback");
	return -1;
}

int armsx3_audio_session_start(void)
{
	std::lock_guard<std::mutex> guard(g_lock);
	set_error_locked(-1, "AVAudioSession does not exist on this platform; nothing was activated "
	                     "and nothing was started");
	return -1;
}

int armsx3_audio_session_stop(void)
{
	std::lock_guard<std::mutex> guard(g_lock);
	set_error_locked(-1, "AVAudioSession does not exist on this platform");
	return -1;
}

int armsx3_audio_session_set_recording(int on)
{
	(void)on;
	std::lock_guard<std::mutex> guard(g_lock);
	set_error_locked(-1, "AVAudioSession does not exist on this platform; there is no category "
	                     "to switch to recording");
	return -1;
}

#endif /* TARGET_OS_IPHONE */

// Shared by both arms.

void armsx3_audio_session_status(armsx3_audio_session_status_t* out)
{
	if (out == nullptr)
	{
		return;
	}

	std::lock_guard<std::mutex> guard(g_lock);

	memset(out, 0, sizeof(*out));
	out->state = g_state;
	out->code = g_code;
	snprintf(out->error, sizeof(out->error), "%s", g_error);
	snprintf(out->last_branch, sizeof(out->last_branch), "%s", g_branch);
	out->starts = g_starts;
	out->stops = g_stops;
	out->observers_registered = g_observers ? 1 : 0;
	out->stream_running = g_running ? 1 : 0;
	out->controller_present = (g_controller.create_and_start != nullptr) ? 1 : 0;
}
