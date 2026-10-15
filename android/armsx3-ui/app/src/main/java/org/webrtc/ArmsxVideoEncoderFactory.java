package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote play (com.armsx2.remoteplay): hardware H.264 from any encoder Android itself reports as
 * hardware.
 *
 * WebRTC's HardwareVideoEncoderFactory only trusts H.264 encoders named OMX.qcom.* or OMX.Exynos.*.
 * Current Snapdragons name theirs c2.qti.*, so it fell back to software VP8: slow to encode on a
 * host that is already running a game, and slow to decode on a small handheld. This factory picks
 * the AVC encoder by MediaCodecInfo.isHardwareAccelerated() instead and leaves every other codec
 * to the stock factories.
 *
 * It lives in org.webrtc because HardwareVideoEncoder and its helpers are package-private.
 */
public class ArmsxVideoEncoderFactory implements VideoEncoderFactory {
  // WebRTC's own default: key frames come on request (packet loss), not on a timer.
  private static final int PERIODIC_KEY_FRAME_INTERVAL_S = 3600;

  private final @Nullable EglBase14.Context sharedContext;
  private final VideoEncoderFactory hardware;
  private final VideoEncoderFactory software = new SoftwareVideoEncoderFactory();
  private final @Nullable MediaCodecInfo h264;

  public ArmsxVideoEncoderFactory(@Nullable EglBase.Context sharedContext) {
    this.sharedContext =
        sharedContext instanceof EglBase14.Context ? (EglBase14.Context) sharedContext : null;
    this.hardware = new HardwareVideoEncoderFactory(
        sharedContext, /* enableIntelVp8Encoder= */ true, /* enableH264HighProfile= */ false);
    this.h264 = findH264Encoder();
  }

  /** The first hardware AVC encoder. Android 10 and later say which codecs are hardware. */
  private static @Nullable MediaCodecInfo findH264Encoder() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
    String mime = VideoCodecMimeType.H264.mimeType();
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (!info.isEncoder() || info.isAlias() || !info.isHardwareAccelerated()) continue;
      if (!MediaCodecUtils.codecSupportsType(info, VideoCodecMimeType.H264)) continue;
      if (MediaCodecUtils.selectColorFormat(
              MediaCodecUtils.ENCODER_COLOR_FORMATS, info.getCapabilitiesForType(mime)) == null) {
        continue;
      }
      return info;
    }
    return null;
  }

  /** The hardware encoder's name, for logs; null when H.264 falls back to the stock factories. */
  public @Nullable String hardwareH264Name() {
    return h264 != null ? h264.getName() : null;
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    Map<String, VideoCodecInfo> codecs = new LinkedHashMap<>();
    if (h264 != null) {
      // Constrained Baseline: every decoder takes it, and it has no B-frames to add delay.
      codecs.put(VideoCodecMimeType.H264.name(), new VideoCodecInfo(VideoCodecMimeType.H264.name(),
          MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, /* highProfile= */ false),
          /* scalabilityModes= */ new ArrayList<>()));
    }
    for (VideoCodecInfo info : hardware.getSupportedCodecs()) codecs.putIfAbsent(info.name, info);
    for (VideoCodecInfo info : software.getSupportedCodecs()) codecs.putIfAbsent(info.name, info);
    return codecs.values().toArray(new VideoCodecInfo[0]);
  }

  @Override
  public @Nullable VideoEncoder createEncoder(VideoCodecInfo info) {
    if (h264 != null && VideoCodecMimeType.H264.name().equalsIgnoreCase(info.name)) {
      MediaCodecInfo.CodecCapabilities capabilities =
          h264.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType());
      return new HardwareVideoEncoder(new MediaCodecWrapperFactoryImpl(), h264.getName(),
          VideoCodecMimeType.H264,
          MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, capabilities),
          MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, capabilities),
          info.params, PERIODIC_KEY_FRAME_INTERVAL_S, /* forceKeyFrameIntervalMs= */ 0,
          new BaseBitrateAdjuster(), sharedContext);
    }
    VideoEncoder hardwareEncoder = hardware.createEncoder(info);
    VideoEncoder softwareEncoder = software.createEncoder(info);
    if (hardwareEncoder != null && softwareEncoder != null) {
      return new VideoEncoderFallback(/* fallback= */ softwareEncoder, /* primary= */ hardwareEncoder);
    }
    return hardwareEncoder != null ? hardwareEncoder : softwareEncoder;
  }
}
