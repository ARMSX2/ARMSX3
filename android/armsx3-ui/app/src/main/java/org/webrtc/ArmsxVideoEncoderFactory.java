package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remote play (com.armsx2.remoteplay): hardware H.264 from any encoder Android itself reports as
 * hardware, run by {@link ArmsxH264Encoder} for low latency.
 *
 * WebRTC's HardwareVideoEncoderFactory only trusts H.264 encoders named OMX.qcom.* or OMX.Exynos.*.
 * Current Snapdragons name theirs c2.qti.*, so it fell back to software VP8: slow to encode on a
 * host that is already running a game, and slow to decode on a small handheld. This factory picks
 * the AVC encoder Android reports as hardware instead (before Android 10, which does not say, the
 * one not named as Android's own software codec) and leaves every other codec to the stock
 * factories.
 *
 * It lives in org.webrtc because the codec helpers it builds on are package-private.
 */
public class ArmsxVideoEncoderFactory implements VideoEncoderFactory {
  // WebRTC's own default: key frames come on request (packet loss), not on a timer.
  private static final int PERIODIC_KEY_FRAME_INTERVAL_S = 3600;

  private final @Nullable EglBase14.Context sharedContext;
  private final VideoEncoderFactory hardware;
  private final VideoEncoderFactory software = new SoftwareVideoEncoderFactory();
  private final @Nullable MediaCodecInfo h264;
  // The AVC level for High profile when the encoder can do it for 720p at 60 fps; else null.
  private final @Nullable Integer h264HighLevel;

  public ArmsxVideoEncoderFactory(@Nullable EglBase.Context sharedContext) {
    this.sharedContext =
        sharedContext instanceof EglBase14.Context ? (EglBase14.Context) sharedContext : null;
    this.hardware = new HardwareVideoEncoderFactory(
        sharedContext, /* enableIntelVp8Encoder= */ true, /* enableH264HighProfile= */ false);
    this.h264 = findH264Encoder();
    this.h264HighLevel = h264 != null
        ? highProfileLevel(h264.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType()))
        : null;
  }

  /**
   * The level to use H.264 High profile at, when the codec lists High at 3.2 or above (720p at 60
   * fps needs 3.2): its highest, capped at 4; else null. Used by the decoder factory too.
   */
  static @Nullable Integer highProfileLevel(MediaCodecInfo.CodecCapabilities capabilities) {
    int best = 0;
    for (MediaCodecInfo.CodecProfileLevel profileLevel : capabilities.profileLevels) {
      if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
        best = Math.max(best, profileLevel.level);
      }
    }
    if (best < MediaCodecInfo.CodecProfileLevel.AVCLevel32) {
      return null;
    }
    return Math.min(best, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
  }

  /** Whether an H.264 codec description names High profile (profile-level-id 64xxxx). */
  static boolean isHighProfile(VideoCodecInfo info) {
    String id = info.params.get(VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID);
    return id != null && id.regionMatches(true, 0, "64", 0, 2);
  }

  /** The first hardware AVC encoder that takes a YUV layout WebRTC can fill. */
  private static @Nullable MediaCodecInfo findH264Encoder() {
    String mime = VideoCodecMimeType.H264.mimeType();
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (!info.isEncoder() || ArmsxVideoDecoderFactory.isAlias(info)
          || !MediaCodecUtils.isHardwareAccelerated(info)) {
        continue;
      }
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

  /**
   * Running totals of frames into the encoder, frames out of it and frames it dropped, for the
   * stream's stats.
   */
  public static int[] encoderCounters() {
    return new int[] {ArmsxH264Encoder.framesIn.get(), ArmsxH264Encoder.framesOut.get(),
        ArmsxH264Encoder.framesDropped.get()};
  }

  /**
   * The slowest frame through the encoder (ms, from capture) and the largest (bytes) since the
   * last call, which starts both over.
   */
  public static int[] takeEncoderPeaks() {
    return new int[] {ArmsxH264Encoder.peakEncodeMs.getAndSet(0),
        ArmsxH264Encoder.peakFrameBytes.getAndSet(0)};
  }

  /** The encoder running now, as its stats name it, or empty when H.264 has not started. */
  public static String encoderName() {
    return ArmsxH264Encoder.activeName;
  }

  /**
   * Whether frames can reach the encoder as GL textures, drawn into its input surface without
   * passing through the CPU: a hardware H.264 encoder with an input surface, a shared EGL context,
   * and no failed attempt so far.
   */
  public boolean textureInput() {
    if (h264 == null || sharedContext == null || ArmsxH264Encoder.surfaceFailed) {
      return false;
    }
    return MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS,
               h264.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType()))
        != null;
  }

  /** True once the encoder could not set up its input surface; send byte frames from then on. */
  public static boolean textureInputFailed() {
    return ArmsxH264Encoder.surfaceFailed;
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    List<VideoCodecInfo> codecs = new ArrayList<>();
    if (h264 != null) {
      // Constrained High where the encoder can (no B-frames, so no added delay): CABAC and 8x8
      // transforms give a cleaner picture for the same bitrate, which counts most online. Then
      // Constrained Baseline, which every decoder takes. The decoder side offers High only where
      // its codec has it, so a pair that cannot both do it settles on Baseline.
      if (h264HighLevel != null) {
        codecs.add(new VideoCodecInfo(VideoCodecMimeType.H264.name(),
            MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, /* highProfile= */ true),
            /* scalabilityModes= */ new ArrayList<>()));
      }
      codecs.add(new VideoCodecInfo(VideoCodecMimeType.H264.name(),
          MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, /* highProfile= */ false),
          /* scalabilityModes= */ new ArrayList<>()));
    }
    Set<String> names = new HashSet<>();
    for (VideoCodecInfo info : codecs) names.add(info.name);
    for (VideoCodecInfo info : hardware.getSupportedCodecs()) {
      if (names.add(info.name)) codecs.add(info);
    }
    for (VideoCodecInfo info : software.getSupportedCodecs()) {
      if (names.add(info.name)) codecs.add(info);
    }
    return codecs.toArray(new VideoCodecInfo[0]);
  }

  // NV12 first: remote play's frames arrive in it, so the encoder copies them without converting.
  private static final int[] COLOR_FORMATS = {
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
      0x7FA30C00, // OMX_QCOM_COLOR_FormatYUV420SemiPlanar
      MediaCodecUtils.COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m,
  };

  @Override
  public @Nullable VideoEncoder createEncoder(VideoCodecInfo info) {
    if (h264 != null && VideoCodecMimeType.H264.name().equalsIgnoreCase(info.name)) {
      MediaCodecInfo.CodecCapabilities capabilities =
          h264.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType());
      Integer colorFormat = MediaCodecUtils.selectColorFormat(COLOR_FORMATS, capabilities);
      if (colorFormat != null) {
        // The input-surface format, for frames that arrive as GL textures.
        Integer surfaceColorFormat =
            MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, capabilities);
        return new ArmsxH264Encoder(h264.getName(), colorFormat, surfaceColorFormat,
            isHighProfile(info) ? h264HighLevel : null, PERIODIC_KEY_FRAME_INTERVAL_S, sharedContext);
      }
    }
    VideoEncoder hardwareEncoder = hardware.createEncoder(info);
    VideoEncoder softwareEncoder = software.createEncoder(info);
    if (hardwareEncoder != null && softwareEncoder != null) {
      return new VideoEncoderFallback(/* fallback= */ softwareEncoder, /* primary= */ hardwareEncoder);
    }
    return hardwareEncoder != null ? hardwareEncoder : softwareEncoder;
  }
}
