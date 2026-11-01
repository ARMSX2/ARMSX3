// XMB mode's media: the console's boot sound from the user's firmware (AC3), and a game's own
// music (SND0.AT3, ATRAC3 in a RIFF WAVE) and animated icon (ICON1.PAM, H.264 in PAMF), decoded with
// the FFmpeg the core already carries. Android itself has no ATRAC3 decoder and no PAMF demuxer, and
// AC3 only on some devices.

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <string>
#include <string_view>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace {
// Four minutes of 48 kHz stereo: far past any SND0 or the boot sound, short of a runaway file.
constexpr std::size_t kMaxSamples = 48000u * 2u * 240u;
// A PAMF file is a 2048-byte header before its MPEG program stream.
constexpr const char* kPamfHeaderBytes = "2048";

bool write_wav(const std::string& path, const std::vector<std::int16_t>& pcm, int rate) {
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) return false;
  const std::uint32_t data = static_cast<std::uint32_t>(pcm.size() * sizeof(std::int16_t));
  auto u32 = [&](std::uint32_t v) {
    const unsigned char b[4] = {static_cast<unsigned char>(v), static_cast<unsigned char>(v >> 8),
                                static_cast<unsigned char>(v >> 16), static_cast<unsigned char>(v >> 24)};
    std::fwrite(b, 1, 4, f);
  };
  auto u16 = [&](std::uint16_t v) {
    const unsigned char b[2] = {static_cast<unsigned char>(v), static_cast<unsigned char>(v >> 8)};
    std::fwrite(b, 1, 2, f);
  };
  std::fwrite("RIFF", 1, 4, f);
  u32(36 + data);
  std::fwrite("WAVEfmt ", 1, 8, f);
  u32(16);
  u16(1);  // PCM
  u16(2);  // stereo
  u32(static_cast<std::uint32_t>(rate));
  u32(static_cast<std::uint32_t>(rate) * 4);
  u16(4);
  u16(16);
  std::fwrite("data", 1, 4, f);
  u32(data);
  const bool ok = std::fwrite(pcm.data(), 1, data, f) == data;
  return std::fclose(f) == 0 && ok;
}
}  // namespace

// The first audio stream of in_path as a 16-bit stereo WAV at out_path, at its own sample rate.
extern "C" bool _armsx3_xmbDecodeAudio(std::string_view in_path, std::string_view out_path) {
  const std::string in(in_path);
  AVFormatContext* fmt = nullptr;
  if (avformat_open_input(&fmt, in.c_str(), nullptr, nullptr) < 0) return false;
  AVCodecContext* ctx = nullptr;
  SwrContext* swr = nullptr;
  AVPacket* pkt = av_packet_alloc();
  AVFrame* frame = av_frame_alloc();
  std::vector<std::int16_t> pcm;
  int rate = 0;
  bool ok = false;

  do {
    if (!pkt || !frame || avformat_find_stream_info(fmt, nullptr) < 0) break;
    const int index = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (index < 0) break;
    const AVCodec* codec = avcodec_find_decoder(fmt->streams[index]->codecpar->codec_id);
    if (!codec) break;
    ctx = avcodec_alloc_context3(codec);
    if (!ctx || avcodec_parameters_to_context(ctx, fmt->streams[index]->codecpar) < 0 ||
        avcodec_open2(ctx, codec, nullptr) < 0) {
      break;
    }
    rate = ctx->sample_rate;
    if (rate <= 0 || ctx->ch_layout.nb_channels <= 0) break;
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
    if (swr_alloc_set_opts2(&swr, &stereo, AV_SAMPLE_FMT_S16, rate, &ctx->ch_layout, ctx->sample_fmt, rate, 0, nullptr) < 0 ||
        swr_init(swr) < 0) {
      break;
    }
    auto drain = [&]() {
      while (avcodec_receive_frame(ctx, frame) == 0) {
        const int room = swr_get_out_samples(swr, frame->nb_samples);
        if (room > 0 && pcm.size() < kMaxSamples) {
          const std::size_t base = pcm.size();
          pcm.resize(base + static_cast<std::size_t>(room) * 2);
          std::uint8_t* out[1] = {reinterpret_cast<std::uint8_t*>(pcm.data() + base)};
          const int got = swr_convert(swr, out, room, const_cast<const std::uint8_t**>(frame->extended_data), frame->nb_samples);
          pcm.resize(base + static_cast<std::size_t>(std::max(got, 0)) * 2);
        }
        av_frame_unref(frame);
      }
    };
    while (av_read_frame(fmt, pkt) >= 0) {
      if (pkt->stream_index == index && avcodec_send_packet(ctx, pkt) == 0) drain();
      av_packet_unref(pkt);
      if (pcm.size() >= kMaxSamples) break;
    }
    avcodec_send_packet(ctx, nullptr);
    drain();
    ok = !pcm.empty() && write_wav(std::string(out_path), pcm, rate);
  } while (false);

  swr_free(&swr);
  avcodec_free_context(&ctx);
  av_frame_free(&frame);
  av_packet_free(&pkt);
  avformat_close_input(&fmt);
  return ok;
}

namespace {
struct PamDecoder {
  AVFormatContext* fmt = nullptr;
  AVCodecContext* ctx = nullptr;
  SwsContext* sws = nullptr;
  AVPacket* pkt = nullptr;
  AVFrame* frame = nullptr;
  int stream = -1;
  bool flushed = false;

  ~PamDecoder() {
    sws_freeContext(sws);
    avcodec_free_context(&ctx);
    av_frame_free(&frame);
    av_packet_free(&pkt);
    avformat_close_input(&fmt);
  }
};
}  // namespace

// An ICON1.PAM opened for playback: a handle for the calls below, or 0.
extern "C" std::int64_t _armsx3_xmbPamOpen(std::string_view path) {
  auto* d = new PamDecoder();
  const std::string file(path);
  AVDictionary* opts = nullptr;
  av_dict_set(&opts, "skip_initial_bytes", kPamfHeaderBytes, 0);
  const AVInputFormat* ps = av_find_input_format("mpegps");
  const int opened = avformat_open_input(&d->fmt, file.c_str(), ps, &opts);
  av_dict_free(&opts);
  if (opened < 0) {
    delete d;
    return 0;
  }
  d->pkt = av_packet_alloc();
  d->frame = av_frame_alloc();
  if (!d->pkt || !d->frame || avformat_find_stream_info(d->fmt, nullptr) < 0) {
    delete d;
    return 0;
  }
  d->stream = av_find_best_stream(d->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  const AVCodec* codec = d->stream >= 0 ? avcodec_find_decoder(d->fmt->streams[d->stream]->codecpar->codec_id) : nullptr;
  if (!codec) {
    delete d;
    return 0;
  }
  d->ctx = avcodec_alloc_context3(codec);
  if (!d->ctx || avcodec_parameters_to_context(d->ctx, d->fmt->streams[d->stream]->codecpar) < 0 ||
      avcodec_open2(d->ctx, codec, nullptr) < 0 || d->ctx->width <= 0 || d->ctx->height <= 0 ||
      d->ctx->width > 1024 || d->ctx->height > 1024) {
    delete d;
    return 0;
  }
  return reinterpret_cast<std::int64_t>(d);
}

// width << 16 | height of an open icon.
extern "C" std::int32_t _armsx3_xmbPamSize(std::int64_t handle) {
  auto* d = reinterpret_cast<PamDecoder*>(handle);
  if (!d || !d->ctx) return 0;
  return (d->ctx->width << 16) | d->ctx->height;
}

// The next frame as RGBA into dst; its time in milliseconds, or -1 at the end or on an error.
extern "C" std::int64_t _armsx3_xmbPamFrame(std::int64_t handle, std::uint8_t* dst, std::int64_t capacity) {
  auto* d = reinterpret_cast<PamDecoder*>(handle);
  if (!d || !d->ctx || !dst) return -1;
  const int w = d->ctx->width;
  const int h = d->ctx->height;
  if (capacity < static_cast<std::int64_t>(w) * h * 4) return -1;
  for (;;) {
    const int got = avcodec_receive_frame(d->ctx, d->frame);
    if (got == 0) {
      if (d->frame->width != w || d->frame->height != h) {
        av_frame_unref(d->frame);
        continue;
      }
      d->sws = sws_getCachedContext(d->sws, w, h, static_cast<AVPixelFormat>(d->frame->format), w, h, AV_PIX_FMT_RGBA,
                                    SWS_BILINEAR, nullptr, nullptr, nullptr);
      if (!d->sws) return -1;
      std::uint8_t* planes[4] = {dst, nullptr, nullptr, nullptr};
      int strides[4] = {w * 4, 0, 0, 0};
      sws_scale(d->sws, d->frame->data, d->frame->linesize, 0, h, planes, strides);
      const AVRational tb = d->fmt->streams[d->stream]->time_base;
      const std::int64_t ts = d->frame->best_effort_timestamp;
      av_frame_unref(d->frame);
      return ts == AV_NOPTS_VALUE ? 0 : av_rescale_q(ts, tb, AVRational{1, 1000});
    }
    if (got == AVERROR_EOF || d->flushed) return -1;
    if (got != AVERROR(EAGAIN)) return -1;
    // Feed it until it has a frame to give.
    bool sent = false;
    while (!sent) {
      if (av_read_frame(d->fmt, d->pkt) < 0) {
        avcodec_send_packet(d->ctx, nullptr);
        d->flushed = true;
        sent = true;
        break;
      }
      if (d->pkt->stream_index == d->stream) sent = avcodec_send_packet(d->ctx, d->pkt) == 0;
      av_packet_unref(d->pkt);
    }
  }
}

extern "C" void _armsx3_xmbPamClose(std::int64_t handle) {
  delete reinterpret_cast<PamDecoder*>(handle);
}
