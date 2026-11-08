// SPDX-License-Identifier: GPL-2.0-only
// The recording writer: AVAssetWriter with H.264 and AAC in an .mp4, compiled with -fobjc-arc, which the stop below checks.

#if !__has_feature(objc_arc)
#error "ios_video_writer.mm needs ARC: ios/bridge/CMakeLists.txt sets -fobjc-arc on it."
#endif

#import <AVFoundation/AVFoundation.h>
#import <Accelerate/Accelerate.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>

#include "ios_video_writer.h"

#include <cstdint>
#include <limits>
#include <vector>

namespace
{
	// How long finish waits for AVAssetWriter to close the file before reporting failure.
	constexpr std::int64_t k_finish_wait_seconds = 10;

	// At most this much media is lost when the file is cut off.
	constexpr double k_fragment_seconds = 5.0;

	// Destination channel i takes source channel k_rgba_to_bgra[i].
	constexpr std::uint8_t k_rgba_to_bgra[4] = {2, 1, 0, 3};

	struct av_writer
	{
		AVAssetWriter* writer = nil;
		AVAssetWriterInput* video = nil;
		AVAssetWriterInput* audio = nil;
		AVAssetWriterInputPixelBufferAdaptor* adaptor = nil;
		unsigned sample_rate = 0;
		bool started = false;
		// AVAssetWriter fails the whole file on a timestamp that does not move forward, so such a sample is dropped.
		CMTime last_video = kCMTimeInvalid;
		CMTime last_audio = kCMTimeInvalid;
		// -1 until the first block.
		std::int64_t next_sample = -1;
		CMAudioFormatDescriptionRef audio_format = nullptr;
		unsigned audio_format_channels = 0;
		std::vector<std::uint8_t> permuted; // an RGBA frame turned BGRA, before scaling

		~av_writer()
		{
			if (audio_format)
			{
				CFRelease(audio_format);
			}
		}

		bool writing() const
		{
			return writer.status == AVAssetWriterStatusWriting;
		}

		// The session starts at the first sample's time, audio or video, so a recording has no leading gap.
		void start_at(CMTime time)
		{
			if (!started)
			{
				[writer startSessionAtSourceTime:time];
				started = true;
			}
		}

		static bool moves_forward(CMTime last, CMTime time)
		{
			return !CMTIME_IS_VALID(last) || CMTimeCompare(time, last) > 0;
		}

		bool append_video(const std::uint8_t* pixels, unsigned pitch, unsigned frame_width, unsigned frame_height, bool bgra,
			std::int64_t pts_ms)
		{
			// A pitch shorter than a row of pixels would read past each row.
			if (!pixels || !frame_width || !frame_height || pitch < frame_width * 4u || !writing())
			{
				return false;
			}
			// An encoder that has fallen behind drops the frame; nothing queues here.
			if (![video isReadyForMoreMediaData])
			{
				return false;
			}
			const CMTime time = CMTimeMake(pts_ms, 1000);
			if (!moves_forward(last_video, time))
			{
				return false;
			}
			start_at(time);

			CVPixelBufferPoolRef pool = adaptor.pixelBufferPool;
			CVPixelBufferRef buffer = nullptr;
			if (!pool || CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer) != kCVReturnSuccess)
			{
				return false;
			}

			vImage_Buffer source{const_cast<std::uint8_t*>(pixels), frame_height, frame_width, pitch};
			if (!bgra)
			{
				permuted.resize(std::size_t{frame_width} * 4u * frame_height);
				vImage_Buffer swapped{permuted.data(), frame_height, frame_width, std::size_t{frame_width} * 4u};
				if (vImagePermuteChannels_ARGB8888(&source, &swapped, k_rgba_to_bgra, kvImageNoFlags) != kvImageNoError)
				{
					CVPixelBufferRelease(buffer);
					return false;
				}
				source = swapped;
			}

			CVPixelBufferLockBaseAddress(buffer, 0);
			vImage_Buffer target{CVPixelBufferGetBaseAddress(buffer), CVPixelBufferGetHeight(buffer),
				CVPixelBufferGetWidth(buffer), CVPixelBufferGetBytesPerRow(buffer)};
			const vImage_Error scaled = vImageScale_ARGB8888(&source, &target, nullptr, kvImageNoFlags);
			CVPixelBufferUnlockBaseAddress(buffer, 0);

			const bool appended = scaled == kvImageNoError && [adaptor appendPixelBuffer:buffer withPresentationTime:time];
			CVPixelBufferRelease(buffer);
			if (appended)
			{
				last_video = time;
			}
			return appended;
		}

		bool audio_format_for(unsigned channels)
		{
			if (audio_format && audio_format_channels == channels)
			{
				return true;
			}
			if (audio_format)
			{
				CFRelease(audio_format);
				audio_format = nullptr;
			}
			AudioStreamBasicDescription lpcm{};
			lpcm.mSampleRate = sample_rate;
			lpcm.mFormatID = kAudioFormatLinearPCM;
			lpcm.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
			lpcm.mBytesPerPacket = 4 * channels;
			lpcm.mFramesPerPacket = 1;
			lpcm.mBytesPerFrame = 4 * channels;
			lpcm.mChannelsPerFrame = channels;
			lpcm.mBitsPerChannel = 32;
			if (CMAudioFormatDescriptionCreate(kCFAllocatorDefault, &lpcm, 0, nullptr, 0, nullptr, nullptr, &audio_format) != noErr)
			{
				audio_format = nullptr;
				return false;
			}
			audio_format_channels = channels;
			return true;
		}

		bool append_audio(const float* interleaved, unsigned frames, unsigned channels, std::int64_t ts_us)
		{
			// The AAC track is stereo and the sink always hands two channels.
			if (!interleaved || !frames || channels != 2 || !writing())
			{
				return false;
			}
			// An encoder that has fallen behind drops the block; nothing queues here.
			if (![audio isReadyForMoreMediaData])
			{
				return false;
			}
			// A contiguous sample count, moved forward by the wall clock only across a real gap, so blocks never overlap.
			const std::int64_t wall = ts_us * sample_rate / 1000000;
			if (next_sample < 0 || wall > next_sample + frames)
			{
				next_sample = wall;
			}
			const CMTime time = CMTimeMake(next_sample, static_cast<std::int32_t>(sample_rate));
			if (!moves_forward(last_audio, time) || !audio_format_for(channels))
			{
				return false;
			}

			const std::size_t bytes = std::size_t{frames} * channels * sizeof(float);
			CMBlockBufferRef block = nullptr;
			if (CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, nullptr, bytes, kCFAllocatorDefault, nullptr, 0, bytes,
					kCMBlockBufferAssureMemoryNowFlag, &block) != kCMBlockBufferNoErr)
			{
				return false;
			}
			CMSampleBufferRef sample = nullptr;
			const bool built = CMBlockBufferReplaceDataBytes(interleaved, block, 0, bytes) == kCMBlockBufferNoErr &&
				CMAudioSampleBufferCreateReadyWithPacketDescriptions(kCFAllocatorDefault, block, audio_format, frames, time,
					nullptr, &sample) == noErr;
			CFRelease(block);
			if (!built)
			{
				return false;
			}

			start_at(time);
			const bool appended = [audio appendSampleBuffer:sample];
			CFRelease(sample);
			if (appended)
			{
				last_audio = time;
				next_sample += frames;
			}
			return appended;
		}

		// Called only by the sink's drain thread: it blocks for up to ten seconds.
		bool finish()
		{
			if (!started)
			{
				[writer cancelWriting]; // nothing was written: no empty file is left behind
				return false;
			}
			if (!writing())
			{
				return false; // the writer failed: the fragments already on disk are kept, and the stop reports failure
			}
			[video markAsFinished];
			[audio markAsFinished];

			dispatch_semaphore_t done = dispatch_semaphore_create(0);
			AVAssetWriter* held = writer;
			[writer finishWritingWithCompletionHandler:^{
				(void)held; // keeps the writer alive until it has closed the file, even past a timeout
				dispatch_semaphore_signal(done);
			}];
			// A writer that never completes is reported failed after ten seconds.
			if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, k_finish_wait_seconds * NSEC_PER_SEC)) != 0)
			{
				return false;
			}
			return writer.status == AVAssetWriterStatusCompleted;
		}
	};

	NSData* stereo_layout()
	{
		AudioChannelLayout layout{};
		layout.mChannelLayoutTag = kAudioChannelLayoutTag_Stereo;
		return [NSData dataWithBytes:&layout length:sizeof(layout)];
	}
}

namespace armsx3::bridge
{
	std::shared_ptr<video_writer> make_avfoundation_video_writer(const video_writer_settings& settings)
	{
		if (settings.path.empty() || !settings.width || !settings.height || !settings.sample_rate ||
			settings.sample_rate > static_cast<unsigned>(std::numeric_limits<std::int32_t>::max()))
		{
			return nullptr;
		}

		@autoreleasepool
		{
			NSURL* url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:settings.path.c_str()]];
			if (!url)
			{
				return nullptr;
			}
			// A stale file of the same name would make AVAssetWriter refuse to start.
			[[NSFileManager defaultManager] removeItemAtURL:url error:nil];

			auto state = std::make_shared<av_writer>();
			state->sample_rate = settings.sample_rate;

			NSError* error = nil;
			state->writer = [AVAssetWriter assetWriterWithURL:url fileType:AVFileTypeMPEG4 error:&error];
			if (!state->writer)
			{
				return nullptr;
			}

			NSDictionary* video_settings = @{
				AVVideoCodecKey: AVVideoCodecTypeH264,
				AVVideoWidthKey: @(settings.width),
				AVVideoHeightKey: @(settings.height),
				AVVideoCompressionPropertiesKey: @{AVVideoAverageBitRateKey: @(settings.video_bps)},
			};
			state->video = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo outputSettings:video_settings];
			state->video.expectsMediaDataInRealTime = YES;

			NSDictionary* pixel_settings = @{
				(NSString*)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
				(NSString*)kCVPixelBufferWidthKey: @(settings.width),
				(NSString*)kCVPixelBufferHeightKey: @(settings.height),
			};
			state->adaptor = [AVAssetWriterInputPixelBufferAdaptor
				assetWriterInputPixelBufferAdaptorWithAssetWriterInput:state->video sourcePixelBufferAttributes:pixel_settings];

			NSDictionary* audio_settings = @{
				AVFormatIDKey: @(kAudioFormatMPEG4AAC),
				AVNumberOfChannelsKey: @2,
				AVSampleRateKey: @(settings.sample_rate),
				AVEncoderBitRateKey: @(settings.audio_bps),
				AVChannelLayoutKey: stereo_layout(),
			};
			state->audio = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeAudio outputSettings:audio_settings];
			state->audio.expectsMediaDataInRealTime = YES;

			if (![state->writer canAddInput:state->video] || ![state->writer canAddInput:state->audio])
			{
				return nullptr;
			}
			[state->writer addInput:state->video];
			[state->writer addInput:state->audio];
			// Movie fragments keep the file playable up to its last fragment if the app is killed or the writer fails.
			state->writer.movieFragmentInterval = CMTimeMakeWithSeconds(k_fragment_seconds, 600);
			if (![state->writer startWriting])
			{
				return nullptr;
			}

			auto writer = std::make_shared<video_writer>();
			writer->append_video = [state](const std::uint8_t* pixels, unsigned pitch, unsigned width, unsigned height,
				bool bgra, std::int64_t pts_ms)
			{
				@autoreleasepool
				{
					return state->append_video(pixels, pitch, width, height, bgra, pts_ms);
				}
			};
			writer->append_audio = [state](const float* interleaved, unsigned frames, unsigned channels, std::int64_t ts_us)
			{
				@autoreleasepool
				{
					return state->append_audio(interleaved, frames, channels, ts_us);
				}
			};
			writer->finish = [state]
			{
				@autoreleasepool
				{
					return state->finish();
				}
			};
			// A failed or cancelled writer refuses every append from here on, and the sink ends the recording on it.
			writer->failed = [state]
			{
				const AVAssetWriterStatus status = state->writer.status;
				return status == AVAssetWriterStatusFailed || status == AVAssetWriterStatusCancelled;
			};
			return writer;
		}
	}
}
