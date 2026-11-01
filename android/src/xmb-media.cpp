// XMB mode's media: the console's boot sound from the user's firmware (AC3), and a game's own
// music (SND0.AT3, ATRAC3 in a RIFF WAVE) and animated icon (ICON1.PAM, H.264 and ATRAC3plus in PAMF), decoded with
// the FFmpeg the core already carries. Android itself has no ATRAC3 decoder and no PAMF demuxer, and
// AC3 only on some devices.

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
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

// The audio track of an ICON1.PAM as a 16-bit stereo WAV at out_path: ATRAC3plus in the PAMF's
// private stream 1, which the console plays with the animated icon. Some games have no SND0 and this
// is their music (THE iDOLM@STER 2). FFmpeg's program stream demuxer does not know Sony's sub-stream,
// so the packets are taken out here: each payload is the sub-stream id (0 for the first ATRAC3plus
// stream), three bytes, then the stream; the stream is frames of 0x0FD0, two bytes of attributes
// (sample rate, channels, frame size), four of padding, and the frame.
extern "C" bool _armsx3_xmbDecodePamAudio(std::string_view in_path, std::string_view out_path) {
  std::vector<std::uint8_t> file;
  {
    FILE* f = std::fopen(std::string(in_path).c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (size < 2048 + 16 || size > (64L << 20)) {
      std::fclose(f);
      return false;
    }
    file.resize(static_cast<std::size_t>(size));
    const bool read = std::fread(file.data(), 1, file.size(), f) == file.size();
    std::fclose(f);
    if (!read || std::memcmp(file.data(), "PAMF", 4) != 0) return false;
  }
  auto be16 = [&](std::size_t at) { return static_cast<std::uint32_t>(file[at] << 8 | file[at + 1]); };
  const std::uint32_t blocks = be16(8) << 16 | be16(10);
  std::size_t pos = static_cast<std::size_t>(blocks == 0 || blocks > 64 ? 1 : blocks) * 2048;
  std::vector<std::uint8_t> es;
  while (pos + 6 <= file.size()) {
    if (file[pos] != 0 || file[pos + 1] != 0 || file[pos + 2] != 1) {
      pos++;
      continue;
    }
    const std::uint8_t id = file[pos + 3];
    if (id == 0xBA) {
      if (pos + 14 > file.size()) break;
      pos += 14 + (file[pos + 13] & 7);
      continue;
    }
    if (id < 0xBB) {
      pos++;
      continue;
    }
    const std::size_t end = pos + 6 + be16(pos + 4);
    if (end > file.size()) break;
    if (id == 0xBD && pos + 9 <= end) {
      const std::size_t payload = pos + 9 + file[pos + 8];
      if (payload + 4 <= end && file[payload] == 0x00) es.insert(es.end(), file.begin() + payload + 4, file.begin() + end);
    }
    pos = end;
  }
  // The first frame's attributes describe the stream.
  std::size_t at = 0;
  while (at + 8 <= es.size() && !(es[at] == 0x0F && es[at + 1] == 0xD0)) at++;
  if (at + 8 > es.size()) return false;
  const std::uint32_t attributes = static_cast<std::uint32_t>(es[at + 2] << 8 | es[at + 3]);
  static constexpr int kRates[8] = {32000, 44100, 48000, 88200, 96000, 0, 0, 0};
  static constexpr int kChannels[8] = {0, 1, 2, 3, 4, 6, 7, 8};
  const int rate = kRates[(attributes >> 13) & 7];
  const int channels = kChannels[(attributes >> 10) & 7];
  const std::size_t frame_size = ((attributes & 0x3FF) + 1) * 8;
  if (rate == 0 || channels == 0) return false;

  const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_ATRAC3P);
  if (!codec) return false;
  AVCodecContext* ctx = avcodec_alloc_context3(codec);
  SwrContext* swr = nullptr;
  AVPacket* pkt = av_packet_alloc();
  AVFrame* frame = av_frame_alloc();
  std::vector<std::int16_t> pcm;
  bool ok = false;
  do {
    if (!ctx || !pkt || !frame) break;
    ctx->sample_rate = rate;
    av_channel_layout_default(&ctx->ch_layout, channels);
    ctx->block_align = static_cast<int>(frame_size);
    if (avcodec_open2(ctx, codec, nullptr) < 0) break;
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
    while (at + 8 + frame_size <= es.size() && pcm.size() < kMaxSamples) {
      if (es[at] != 0x0F || es[at + 1] != 0xD0) {
        at++;
        continue;
      }
      if (av_new_packet(pkt, static_cast<int>(frame_size)) < 0) break;
      std::memcpy(pkt->data, es.data() + at + 8, frame_size);
      if (avcodec_send_packet(ctx, pkt) == 0) drain();
      av_packet_unref(pkt);
      at += 8 + frame_size;
    }
    avcodec_send_packet(ctx, nullptr);
    drain();
    ok = !pcm.empty() && write_wav(std::string(out_path), pcm, rate);
  } while (false);
  swr_free(&swr);
  avcodec_free_context(&ctx);
  av_frame_free(&frame);
  av_packet_free(&pkt);
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
