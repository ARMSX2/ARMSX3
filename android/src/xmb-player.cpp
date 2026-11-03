// XMB mode's video player for what Android's own player cannot play: AVI (DivX, Xvid), WMV, MPEG-1
// and MPEG-2 program streams, AC3 sound and the like. The core's FFmpeg decodes; the picture goes to
// the TextureView's surface and the sound to AAudio, and the picture keeps to the sound's clock.
//
// Three threads: one reads and decodes, one shows each picture at its time, and AAudio's own pulls
// the sound. The app drives it through _armsx3_xmbPlayer with a command and polls it the same way.

#include <aaudio/AAudio.h>
#include <android/native_window.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include "xmb-io.h"

namespace {

using Clock = std::chrono::steady_clock;

// Pictures decoded ahead of the one showing.
constexpr std::size_t kMaxFrames = 4;
// Pictures bigger than this are scaled down to it: software decoding is for the formats the
// device cannot play itself, which come no bigger than DVDs and 1080p.
constexpr int kMaxWidth = 1920;
constexpr int kMaxHeight = 1088;

enum Command : int {
  kPlay = 1,
  kPause = 2,
  kSeek = 3,
  kPosition = 4,
  kDuration = 5,
  kState = 6,
  kWidth = 7,
  kHeight = 8,
};
enum State : int { kPlaying = 0, kPaused = 1, kEnded = 2, kFailed = 3 };

// Interleaved 16-bit stereo, one writer (the decoder) and one reader (AAudio's callback), counted
// in frames that only ever increase.
struct Ring {
  std::vector<std::int16_t> data;
  std::atomic<std::uint64_t> head{0};
  std::atomic<std::uint64_t> tail{0};

  std::uint64_t frames() const { return data.size() / 2; }
  std::uint64_t used() const { return head.load(std::memory_order_acquire) - tail.load(std::memory_order_acquire); }

  std::uint64_t write(const std::int16_t* src, std::uint64_t n) {
    const std::uint64_t h = head.load(std::memory_order_relaxed);
    n = std::min(n, frames() - (h - tail.load(std::memory_order_acquire)));
    for (std::uint64_t i = 0; i < n; ++i) {
      const std::uint64_t at = ((h + i) % frames()) * 2;
      data[at] = src[i * 2];
      data[at + 1] = src[i * 2 + 1];
    }
    head.store(h + n, std::memory_order_release);
    return n;
  }

  std::uint64_t read(std::int16_t* dst, std::uint64_t n) {
    const std::uint64_t t = tail.load(std::memory_order_relaxed);
    n = std::min(n, head.load(std::memory_order_acquire) - t);
    for (std::uint64_t i = 0; i < n; ++i) {
      const std::uint64_t at = ((t + i) % frames()) * 2;
      dst[i * 2] = data[at];
      dst[i * 2 + 1] = data[at + 1];
    }
    tail.store(t + n, std::memory_order_release);
    return n;
  }
};

struct Picture {
  AVFrame* frame;
  std::int64_t pts;  // ms
};

struct Player {
  AVFormatContext* fmt = nullptr;
  AVCodecContext* video = nullptr;
  AVCodecContext* audio = nullptr;
  int video_index = -1;
  int audio_index = -1;
  SwsContext* sws = nullptr;
  SwrContext* swr = nullptr;
  ANativeWindow* window = nullptr;
  AAudioStream* stream = nullptr;
  int rate = 48000;
  int out_w = 0, out_h = 0;          // the picture's size in the surface
  int display_w = 16, display_h = 9;  // its shape on screen (pixel aspect applied)
  std::int64_t duration = 0;          // ms
  std::int64_t start = 0;             // ms: the file's first time, which shows as 0

  std::thread decoder;
  std::thread presenter;
  std::mutex m;
  std::condition_variable cv;
  std::deque<Picture> pictures;
  Ring ring;

  std::atomic<bool> quit{false};
  std::atomic<bool> paused{false};
  std::atomic<bool> eof{false};
  std::atomic<bool> failed{false};
  std::atomic<std::int64_t> seek_to{-1};
  std::atomic<std::int64_t> shown{0};  // the last picture's time, ms

  // The sound's clock: frame `base_frame` of the ring is at `base_pts` ms.
  std::atomic<bool> base_set{false};
  std::atomic<std::uint64_t> base_frame{0};
  std::atomic<std::int64_t> base_pts{0};
  std::int64_t latency = 0;  // ms of sound queued in AAudio past the ring
  // The sound has run out before the picture (a soundtrack a little short): the wall clock goes on.
  std::atomic<bool> sound_done{false};

  // The wall clock, with no sound: `wall_pts` ms at `wall_at`, or held at `wall_pts` while paused.
  std::mutex wall_m;
  Clock::time_point wall_at = Clock::now();
  std::int64_t wall_pts = 0;

  std::int64_t clock() {
    if (stream != nullptr && !sound_done.load()) {
      if (!base_set.load()) return shown.load();
      const std::uint64_t consumed = ring.tail.load() - base_frame.load();
      const std::int64_t t = base_pts.load() + static_cast<std::int64_t>(consumed * 1000 / static_cast<std::uint64_t>(rate)) - latency;
      if (eof.load() && ring.used() == 0) {
        set_wall(t + latency);
        sound_done.store(true);
      }
      return t;
    }
    std::lock_guard lock(wall_m);
    if (paused.load()) return wall_pts;
    return wall_pts + std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - wall_at).count();
  }

  void set_wall(std::int64_t pts) {
    std::lock_guard lock(wall_m);
    wall_pts = pts;
    wall_at = Clock::now();
  }
};

std::int64_t to_ms(std::int64_t ts, AVRational tb) {
  if (ts == AV_NOPTS_VALUE) return AV_NOPTS_VALUE;
  return av_rescale_q(ts, tb, AVRational{1, 1000});
}

// A frame's time in ms from the file's start, or AV_NOPTS_VALUE.
std::int64_t frame_ms(const Player* p, const AVFrame* f, int index) {
  const std::int64_t t = to_ms(f->best_effort_timestamp, p->fmt->streams[index]->time_base);
  return t == AV_NOPTS_VALUE ? t : t - p->start;
}

aaudio_data_callback_result_t on_audio(AAudioStream*, void* user, void* data, int32_t frames) {
  auto* p = static_cast<Player*>(user);
  auto* out = static_cast<std::int16_t*>(data);
  const std::uint64_t got = p->paused.load() ? 0 : p->ring.read(out, static_cast<std::uint64_t>(frames));
  if (got < static_cast<std::uint64_t>(frames)) {
    std::memset(out + got * 2, 0, (static_cast<std::uint64_t>(frames) - got) * 2 * sizeof(std::int16_t));
  }
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void close_audio(Player* p) {
  if (p->stream == nullptr) return;
  AAudioStream_requestStop(p->stream);
  AAudioStream_close(p->stream);
  p->stream = nullptr;
}

bool open_audio(Player* p) {
  AAudioStreamBuilder* b = nullptr;
  if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
  AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setChannelCount(b, 2);
  AAudioStreamBuilder_setSampleRate(b, p->rate);
  AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
  AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MOVIE);
  AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
  AAudioStreamBuilder_setDataCallback(b, on_audio, p);
  const bool ok = AAudioStreamBuilder_openStream(b, &p->stream) == AAUDIO_OK;
  AAudioStreamBuilder_delete(b);
  if (!ok) {
    p->stream = nullptr;
    return false;
  }
  p->rate = AAudioStream_getSampleRate(p->stream);
  p->latency = static_cast<std::int64_t>(AAudioStream_getBufferSizeInFrames(p->stream)) * 1000 / std::max(p->rate, 1);
  return true;
}

// The picture onto the surface, scaled and converted to RGBA.
void show(Player* p, AVFrame* f) {
  if (p->window == nullptr) return;
  p->sws = sws_getCachedContext(p->sws, f->width, f->height, static_cast<AVPixelFormat>(f->format), p->out_w, p->out_h,
                                AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
  if (p->sws == nullptr) return;
  ANativeWindow_Buffer buf;
  if (ANativeWindow_lock(p->window, &buf, nullptr) != 0) return;
  std::uint8_t* dst[4] = {static_cast<std::uint8_t*>(buf.bits), nullptr, nullptr, nullptr};
  int stride[4] = {buf.stride * 4, 0, 0, 0};
  sws_scale(p->sws, f->data, f->linesize, 0, f->height, dst, stride);
  ANativeWindow_unlockAndPost(p->window);
}

void clear_pictures(Player* p) {
  for (auto& pic : p->pictures) av_frame_free(&pic.frame);
  p->pictures.clear();
}

void present(Player* p) {
  while (!p->quit.load()) {
    AVFrame* frame = nullptr;
    std::int64_t pts = 0;
    {
      std::unique_lock lock(p->m);
      p->cv.wait_for(lock, std::chrono::milliseconds(20), [&] { return p->quit.load() || (!p->pictures.empty() && !p->paused.load()); });
      if (p->quit.load()) break;
      if (p->pictures.empty() || p->paused.load()) continue;
      pts = p->pictures.front().pts;
      const std::int64_t now = p->clock();
      if (pts > now + 4) {
        // Early: wait for its time, a little at a time, so a seek or a pause is not missed.
        lock.unlock();
        std::this_thread::sleep_for(std::chrono::milliseconds(std::min<std::int64_t>(pts - now, 15)));
        continue;
      }
      // Late by more than a few pictures while more are waiting: skip it to catch up.
      if (pts < now - 120 && p->pictures.size() > 1) {
        av_frame_free(&p->pictures.front().frame);
        p->pictures.pop_front();
        p->cv.notify_all();
        continue;
      }
      frame = p->pictures.front().frame;
      p->pictures.pop_front();
    }
    p->cv.notify_all();
    show(p, frame);
    av_frame_free(&frame);
    p->shown.store(pts);
  }
}

// Sound for the ring, at the stream's rate, 16-bit stereo; frames before `skip` ms dropped.
void push_audio(Player* p, AVFrame* f, std::int64_t skip) {
  const std::int64_t pts = frame_ms(p, f, p->audio_index);
  const int room = swr_get_out_samples(p->swr, f->nb_samples);
  if (room <= 0) return;
  std::vector<std::int16_t> pcm(static_cast<std::size_t>(room) * 2);
  std::uint8_t* out[1] = {reinterpret_cast<std::uint8_t*>(pcm.data())};
  const int got = swr_convert(p->swr, out, room, const_cast<const std::uint8_t**>(f->extended_data), f->nb_samples);
  if (got <= 0) return;
  std::uint64_t first = 0;
  std::uint64_t count = static_cast<std::uint64_t>(got);
  if (pts != AV_NOPTS_VALUE && pts < skip) {
    const std::uint64_t cut = static_cast<std::uint64_t>((skip - pts) * p->rate / 1000);
    if (cut >= count) return;
    first = cut;
    count -= cut;
  }
  if (!p->base_set.load()) {
    p->base_frame.store(p->ring.head.load());
    p->base_pts.store(pts == AV_NOPTS_VALUE ? skip : pts + static_cast<std::int64_t>(first * 1000 / static_cast<std::uint64_t>(p->rate)));
    p->base_set.store(true);
  }
  const std::int16_t* src = pcm.data() + first * 2;
  while (count > 0 && !p->quit.load() && p->seek_to.load() < 0) {
    const std::uint64_t wrote = p->ring.write(src, count);
    src += wrote * 2;
    count -= wrote;
    if (count > 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
}

void push_picture(Player* p, AVFrame* f, std::int64_t skip) {
  std::int64_t pts = frame_ms(p, f, p->video_index);
  if (pts == AV_NOPTS_VALUE) pts = p->shown.load();
  if (pts < skip) return;
  AVFrame* keep = av_frame_clone(f);
  if (keep == nullptr) return;
  std::unique_lock lock(p->m);
  p->cv.wait(lock, [&] { return p->quit.load() || p->seek_to.load() >= 0 || p->pictures.size() < kMaxFrames; });
  if (p->quit.load() || p->seek_to.load() >= 0) {
    av_frame_free(&keep);
    return;
  }
  p->pictures.push_back({keep, pts});
  p->cv.notify_all();
}

void receive(Player* p, AVCodecContext* ctx, AVFrame* f, std::int64_t skip) {
  while (avcodec_receive_frame(ctx, f) == 0) {
    if (ctx == p->video) push_picture(p, f, skip);
    else push_audio(p, f, skip);
    av_frame_unref(f);
  }
}

void decode(Player* p) {
  AVPacket* pkt = av_packet_alloc();
  AVFrame* f = av_frame_alloc();
  std::int64_t skip = 0;
  while (!p->quit.load()) {
    const std::int64_t target = p->seek_to.exchange(-1);
    if (target >= 0) {
      // Silence and empty everything, go to the key frame before, and decode on to the time.
      const bool was_paused = p->paused.load();
      if (p->stream != nullptr) {
        AAudioStream_requestPause(p->stream);
        aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
        AAudioStream_waitForStateChange(p->stream, AAUDIO_STREAM_STATE_PAUSING, &next, 200'000'000);
        AAudioStream_requestFlush(p->stream);
      }
      {
        std::lock_guard lock(p->m);
        clear_pictures(p);
      }
      p->ring.tail.store(p->ring.head.load());
      p->base_set.store(false);
      p->sound_done.store(false);
      av_seek_frame(p->fmt, -1, av_rescale(target + p->start, AV_TIME_BASE, 1000), AVSEEK_FLAG_BACKWARD);
      if (p->video) avcodec_flush_buffers(p->video);
      if (p->audio) avcodec_flush_buffers(p->audio);
      skip = target;
      p->shown.store(target);
      p->set_wall(target);
      p->eof.store(false);
      if (p->stream != nullptr && !was_paused) AAudioStream_requestStart(p->stream);
      p->cv.notify_all();
    }
    if (p->eof.load()) {
      std::unique_lock lock(p->m);
      p->cv.wait_for(lock, std::chrono::milliseconds(50), [&] { return p->quit.load() || p->seek_to.load() >= 0; });
      continue;
    }
    const int read = av_read_frame(p->fmt, pkt);
    if (read < 0) {
      // The end: what the decoders still hold comes out, then nothing more.
      for (AVCodecContext* ctx : {p->video, p->audio}) {
        if (ctx == nullptr) continue;
        avcodec_send_packet(ctx, nullptr);
        receive(p, ctx, f, skip);
      }
      p->eof.store(true);
      continue;
    }
    AVCodecContext* ctx = pkt->stream_index == p->video_index ? p->video : pkt->stream_index == p->audio_index ? p->audio : nullptr;
    if (ctx != nullptr) {
      if (avcodec_send_packet(ctx, pkt) == AVERROR(EAGAIN)) {
        receive(p, ctx, f, skip);
        avcodec_send_packet(ctx, pkt);
      }
      receive(p, ctx, f, skip);
    }
    av_packet_unref(pkt);
  }
  av_frame_free(&f);
  av_packet_free(&pkt);
}

AVCodecContext* open_codec(AVStream* s, bool threads) {
  const AVCodec* codec = avcodec_find_decoder(s->codecpar->codec_id);
  if (codec == nullptr) return nullptr;
  AVCodecContext* ctx = avcodec_alloc_context3(codec);
  if (ctx == nullptr) return nullptr;
  if (threads) {
    ctx->thread_count = 0;
    ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  }
  if (avcodec_parameters_to_context(ctx, s->codecpar) < 0 || avcodec_open2(ctx, codec, nullptr) < 0) {
    avcodec_free_context(&ctx);
    return nullptr;
  }
  return ctx;
}

void destroy(Player* p) {
  p->quit.store(true);
  p->cv.notify_all();
  if (p->decoder.joinable()) p->decoder.join();
  if (p->presenter.joinable()) p->presenter.join();
  close_audio(p);
  clear_pictures(p);
  sws_freeContext(p->sws);
  swr_free(&p->swr);
  avcodec_free_context(&p->video);
  avcodec_free_context(&p->audio);
  xmb_io::close_input(&p->fmt);
  if (p->window != nullptr) ANativeWindow_release(p->window);
  delete p;
}

}  // namespace

// Open path (a file, or /proc/self/fd/N for a document) to play on window from start_ms. The window
// is the player's from here (it releases it). 0 when nothing in it can be played.
extern "C" std::int64_t _armsx3_xmbPlayerOpen(std::string_view path, ANativeWindow* window, std::int64_t start_ms) {
  auto* p = new Player();
  p->window = window;
  const std::string file(path);
  if (xmb_io::open_input(&p->fmt, file) < 0 || avformat_find_stream_info(p->fmt, nullptr) < 0) {
    destroy(p);
    return 0;
  }
  p->video_index = av_find_best_stream(p->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  p->audio_index = av_find_best_stream(p->fmt, AVMEDIA_TYPE_AUDIO, -1, p->video_index, nullptr, 0);
  if (p->video_index >= 0) p->video = open_codec(p->fmt->streams[p->video_index], true);
  if (p->audio_index >= 0) p->audio = open_codec(p->fmt->streams[p->audio_index], false);
  if (p->video == nullptr) {
    destroy(p);
    return 0;
  }
  const int w = p->video->width, h = p->video->height;
  if (w <= 0 || h <= 0) {
    destroy(p);
    return 0;
  }
  // The surface's size: the picture's own, scaled down to fit the largest size kept.
  const double scale = std::min({1.0, static_cast<double>(kMaxWidth) / w, static_cast<double>(kMaxHeight) / h});
  p->out_w = std::max(2, static_cast<int>(w * scale) & ~1);
  p->out_h = std::max(2, static_cast<int>(h * scale) & ~1);
  const AVRational sar = av_guess_sample_aspect_ratio(p->fmt, p->fmt->streams[p->video_index], nullptr);
  p->display_w = sar.num > 0 && sar.den > 0 ? static_cast<int>(static_cast<std::int64_t>(w) * sar.num / sar.den) : w;
  p->display_h = h;
  if (p->window != nullptr) ANativeWindow_setBuffersGeometry(p->window, p->out_w, p->out_h, WINDOW_FORMAT_RGBA_8888);

  if (p->fmt->start_time != AV_NOPTS_VALUE && p->fmt->start_time > 0) p->start = p->fmt->start_time / (AV_TIME_BASE / 1000);
  if (p->fmt->duration > 0) p->duration = p->fmt->duration / (AV_TIME_BASE / 1000);
  else if (p->fmt->streams[p->video_index]->duration > 0) p->duration = to_ms(p->fmt->streams[p->video_index]->duration, p->fmt->streams[p->video_index]->time_base);

  if (p->audio != nullptr) {
    // The sound at its own rate where AAudio takes it, else at the rate AAudio gives.
    const int source = p->audio->sample_rate;
    p->rate = source >= 8000 && source <= 48000 ? source : 48000;
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
    const bool ok = open_audio(p) &&
                    swr_alloc_set_opts2(&p->swr, &stereo, AV_SAMPLE_FMT_S16, p->rate, &p->audio->ch_layout, p->audio->sample_fmt, source, 0, nullptr) >= 0 &&
                    swr_init(p->swr) >= 0;
    if (!ok) {
      // Picture without sound is better than nothing: the wall clock keeps the time.
      close_audio(p);
      swr_free(&p->swr);
      avcodec_free_context(&p->audio);
      p->audio_index = -1;
    }
  }
  if (p->stream != nullptr) {
    p->ring.data.assign(static_cast<std::size_t>(p->rate) * 2, 0);  // a second of sound
    AAudioStream_requestStart(p->stream);
  }
  if (start_ms > 0) p->seek_to.store(start_ms);
  p->set_wall(std::max<std::int64_t>(start_ms, 0));
  p->decoder = std::thread(decode, p);
  p->presenter = std::thread(present, p);
  return reinterpret_cast<std::int64_t>(p);
}

extern "C" std::int64_t _armsx3_xmbPlayer(std::int64_t handle, std::int32_t command, std::int64_t arg) {
  auto* p = reinterpret_cast<Player*>(handle);
  if (p == nullptr) return -1;
  switch (command) {
    case kPlay:
      if (p->paused.load()) {
        // The time held while paused, going on from now.
        p->set_wall(p->clock());
        p->paused.store(false);
        if (p->stream != nullptr) AAudioStream_requestStart(p->stream);
        p->cv.notify_all();
      }
      return 0;
    case kPause:
      if (!p->paused.load()) {
        p->set_wall(p->clock());
        p->paused.store(true);
        if (p->stream != nullptr) AAudioStream_requestPause(p->stream);
      }
      return 0;
    case kSeek:
      p->seek_to.store(std::clamp<std::int64_t>(arg, 0, std::max<std::int64_t>(p->duration - 500, 0)));
      p->cv.notify_all();
      return 0;
    case kPosition:
      return std::clamp<std::int64_t>(p->clock(), 0, p->duration > 0 ? p->duration : INT64_MAX);
    case kDuration:
      return p->duration;
    case kState: {
      if (p->failed.load()) return kFailed;
      std::lock_guard lock(p->m);
      if (p->eof.load() && p->pictures.empty() && p->ring.used() == 0 && p->seek_to.load() < 0) return kEnded;
      return p->paused.load() ? kPaused : kPlaying;
    }
    case kWidth:
      return p->display_w;
    case kHeight:
      return p->display_h;
    default:
      return -1;
  }
}

extern "C" void _armsx3_xmbPlayerClose(std::int64_t handle) {
  if (handle != 0) destroy(reinterpret_cast<Player*>(handle));
}
