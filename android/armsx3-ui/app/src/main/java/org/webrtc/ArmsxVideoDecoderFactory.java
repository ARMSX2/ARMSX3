package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Remote play (com.armsx2.remoteplay): the hardware H.264 decoder in low-latency mode, everything
 * else as WebRTC's DefaultVideoDecoderFactory.
 *
 * Left in its default mode a decoder may hold several frames before it shows the first, which is
 * fine for a film and adds a few frames of lag to a game. The decoder is asked for low latency and
 * real-time priority, plus each vendor's own switch for the same; one that refuses is configured
 * again exactly as WebRTC would have.
 *
 * It lives in org.webrtc because AndroidVideoDecoder and the codec helpers are package-private.
 */
public class ArmsxVideoDecoderFactory implements VideoDecoderFactory {
  private static final String TAG = "ArmsxVideoDecoder";

  private final @Nullable EglBase.Context sharedContext;
  private final VideoDecoderFactory stock;
  private final @Nullable MediaCodecInfo h264;

  public ArmsxVideoDecoderFactory(@Nullable EglBase.Context sharedContext) {
    this.sharedContext = sharedContext;
    this.stock = new DefaultVideoDecoderFactory(sharedContext);
    this.h264 = findH264Decoder();
  }

  private static @Nullable MediaCodecInfo findH264Decoder() {
    String mime = VideoCodecMimeType.H264.mimeType();
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (info.isEncoder() || info.isAlias() || !info.isHardwareAccelerated()) continue;
      if (!MediaCodecUtils.codecSupportsType(info, VideoCodecMimeType.H264)) continue;
      if (MediaCodecUtils.selectColorFormat(
              MediaCodecUtils.DECODER_COLOR_FORMATS, info.getCapabilitiesForType(mime)) == null) {
        continue;
      }
      return info;
    }
    return null;
  }

  /** The hardware decoder's name, for logs; null when H.264 decodes the stock way. */
  public @Nullable String hardwareH264Name() {
    return h264 != null ? h264.getName() : null;
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    return stock.getSupportedCodecs();
  }

  @Override
  public @Nullable VideoDecoder createDecoder(VideoCodecInfo info) {
    if (h264 != null && VideoCodecMimeType.H264.name().equalsIgnoreCase(info.name)) {
      Integer colorFormat = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS,
          h264.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType()));
      if (colorFormat != null) {
        return new AndroidVideoDecoder(new LowLatencyCodecs(), h264.getName(),
            VideoCodecMimeType.H264, colorFormat, sharedContext);
      }
    }
    return stock.createDecoder(info);
  }

  /** Opens codecs for AndroidVideoDecoder, adding the low-latency settings when it configures. */
  private static class LowLatencyCodecs implements MediaCodecWrapperFactory {
    @Override
    public MediaCodecWrapper createByCodecName(String name) throws IOException {
      return new LowLatencyCodec(MediaCodec.createByCodecName(name));
    }
  }

  private static class LowLatencyCodec implements MediaCodecWrapper {
    private final MediaCodec codec;

    LowLatencyCodec(MediaCodec codec) {
      this.codec = codec;
    }

    @Override
    public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) != 0) {
        codec.configure(format, surface, crypto, flags);
        return;
      }
      try {
        codec.configure(lowLatency(format, codec.getName()), surface, crypto, flags);
        Logging.d(TAG, codec.getName() + " in low-latency mode");
      } catch (IllegalArgumentException | IllegalStateException e) {
        Logging.w(TAG, "Low-latency setup refused, configuring the plain one: " + e);
        codec.reset();
        codec.configure(format, surface, crypto, flags);
      }
    }

    private static MediaFormat lowLatency(MediaFormat original, String name) {
      MediaFormat format = new MediaFormat(original);
      format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      format.setInteger(MediaFormat.KEY_PRIORITY, 0);
      // Each vendor's own switch. A codec ignores keys from another vendor.
      if (name.startsWith("c2.qti.") || name.startsWith("OMX.qcom.")) {
        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
      } else if (name.startsWith("c2.exynos.") || name.startsWith("OMX.Exynos.")) {
        format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
      } else if (name.startsWith("c2.mtk.") || name.startsWith("OMX.MTK.")) {
        format.setInteger("vdec-lowlatency", 1);
      } else if (name.startsWith("c2.amlogic.") || name.startsWith("OMX.amlogic.")) {
        format.setInteger("vendor.low-latency.enable", 1);
      }
      return format;
    }

    @Override
    public void start() {
      codec.start();
    }

    @Override
    public void flush() {
      codec.flush();
    }

    @Override
    public void stop() {
      codec.stop();
    }

    @Override
    public void release() {
      codec.release();
    }

    @Override
    public int dequeueInputBuffer(long timeoutUs) {
      return codec.dequeueInputBuffer(timeoutUs);
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
    }

    @Override
    public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) {
      return codec.dequeueOutputBuffer(info, timeoutUs);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      codec.releaseOutputBuffer(index, render);
    }

    @Override
    public MediaFormat getInputFormat() {
      return codec.getInputFormat();
    }

    @Override
    public MediaFormat getOutputFormat() {
      return codec.getOutputFormat();
    }

    @Override
    public MediaFormat getOutputFormat(int index) {
      return codec.getOutputFormat(index);
    }

    @Override
    public ByteBuffer getInputBuffer(int index) {
      return codec.getInputBuffer(index);
    }

    @Override
    public ByteBuffer getOutputBuffer(int index) {
      return codec.getOutputBuffer(index);
    }

    @Override
    public Surface createInputSurface() {
      return codec.createInputSurface();
    }

    @Override
    public void setParameters(Bundle params) {
      codec.setParameters(params);
    }

    @Override
    public MediaCodecInfo getCodecInfo() {
      return codec.getCodecInfo();
    }
  }
}
