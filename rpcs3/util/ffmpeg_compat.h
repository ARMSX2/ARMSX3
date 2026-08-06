#pragma once

// ARMSX3 -- ffmpeg back-compat shim.
//
// WHY THIS EXISTS
// Upstream RPCS3 is written against ffmpeg 7.1+: util/media_utils.cpp calls
// avcodec_get_supported_config() with the AVCodecConfig enum, and passes a
// `const AVChannelLayout*` to swr_alloc_set_opts2().
//
// The only prebuilt ffmpeg that exists for Android is RPCS3-Android's 5.1
// (libavcodec 59, published 2025-03-09, never updated). RPCS3's own ffmpeg-core
// releases cover linux/macos/mingw/windows but NOT Android. So on Android we
// are two majors behind what upstream assumes, and media_utils.cpp is the one
// and only file that notices.
//
// THIS IS A STOPGAP. The real fix is to cross-compile ffmpeg 7.1 for Android
// arm64 and publish it, at which point every #if below evaluates false, this
// header becomes dead weight, and it should be deleted outright. It is written
// so that happens automatically rather than silently rotting: nothing here
// takes effect on a modern ffmpeg.
//
// Include AFTER the ffmpeg headers.

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/version.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

// avcodec_get_supported_config() + AVCodecConfig arrived in ffmpeg 7.1
// (libavcodec 61.13.100). Before that the same information lived in plain
// null/zero-terminated arrays hanging off AVCodec.
#if LIBAVCODEC_VERSION_INT < AV_VERSION_INT(61, 13, 100)

enum AVCodecConfig
{
	AV_CODEC_CONFIG_PIX_FORMAT,
	AV_CODEC_CONFIG_FRAME_RATE,
	AV_CODEC_CONFIG_SAMPLE_RATE,
	AV_CODEC_CONFIG_SAMPLE_FORMAT,
	AV_CODEC_CONFIG_CHANNEL_LAYOUT,
	AV_CODEC_CONFIG_COLOR_RANGE,
	AV_CODEC_CONFIG_COLOR_SPACE,
};

// Mirrors the 7.1 contract: on success writes a pointer to the codec's config
// array into *out_configs (or nullptr meaning "everything supported") and the
// element count into *out_num_configs, returning 0. Returns a negative AVERROR
// on bad input.
//
// Note the terminator differs per config type, which is exactly the messiness
// the 7.1 API was introduced to hide:
//   sample formats   -> AV_SAMPLE_FMT_NONE (-1)
//   sample rates     -> 0
//   channel layouts  -> an all-zero AVChannelLayout
//   pixel formats    -> AV_PIX_FMT_NONE (-1)
static inline int armsx3_avcodec_get_supported_config(
	const AVCodecContext* /*avctx*/,
	const AVCodec* codec,
	enum AVCodecConfig config,
	unsigned /*flags*/,
	const void** out_configs,
	int* out_num_configs)
{
	if (!codec || !out_configs)
		return AVERROR(EINVAL);

	const void* list = nullptr;
	int count = 0;

	switch (config)
	{
	case AV_CODEC_CONFIG_SAMPLE_FORMAT:
	{
		list = codec->sample_fmts;
		if (codec->sample_fmts)
			while (codec->sample_fmts[count] != AV_SAMPLE_FMT_NONE) count++;
		break;
	}
	case AV_CODEC_CONFIG_SAMPLE_RATE:
	{
		list = codec->supported_samplerates;
		if (codec->supported_samplerates)
			while (codec->supported_samplerates[count] != 0) count++;
		break;
	}
	case AV_CODEC_CONFIG_CHANNEL_LAYOUT:
	{
		// ffmpeg 5.1 exposes ch_layouts; terminator is a zeroed struct.
		list = codec->ch_layouts;
		if (codec->ch_layouts)
		{
			static const AVChannelLayout zero_layout = {};
			while (__builtin_memcmp(&codec->ch_layouts[count], &zero_layout, sizeof(AVChannelLayout)) != 0) count++;
		}
		break;
	}
	case AV_CODEC_CONFIG_PIX_FORMAT:
	{
		list = codec->pix_fmts;
		if (codec->pix_fmts)
			while (codec->pix_fmts[count] != AV_PIX_FMT_NONE) count++;
		break;
	}
	default:
		// Nothing on the Android path asks for frame rate / colour range /
		// colour space. Report "no constraint" rather than inventing data.
		list = nullptr;
		count = 0;
		break;
	}

	*out_configs = list;
	if (out_num_configs)
		*out_num_configs = count;

	return 0;
}

#define avcodec_get_supported_config armsx3_avcodec_get_supported_config

// ffmpeg 5.1 declares out_ch_layout non-const; 6.0+ made it const. Upstream
// passes a const pointer. Forward through a const_cast -- swr_alloc_set_opts2
// does not mutate the layout, it copies it.
static inline int armsx3_swr_alloc_set_opts2(
	struct SwrContext** ps,
	const AVChannelLayout* out_ch_layout, enum AVSampleFormat out_sample_fmt, int out_sample_rate,
	const AVChannelLayout* in_ch_layout, enum AVSampleFormat in_sample_fmt, int in_sample_rate,
	int log_offset, void* log_ctx)
{
	return swr_alloc_set_opts2(ps,
		const_cast<AVChannelLayout*>(out_ch_layout), out_sample_fmt, out_sample_rate,
		const_cast<AVChannelLayout*>(in_ch_layout), in_sample_fmt, in_sample_rate,
		log_offset, log_ctx);
}

#define swr_alloc_set_opts2 armsx3_swr_alloc_set_opts2

#endif // LIBAVCODEC_VERSION_INT < 61.13.100
