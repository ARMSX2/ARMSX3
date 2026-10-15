package com.armsx2.remoteplay

import android.content.Context
import android.os.Handler
import org.json.JSONObject
import org.webrtc.ArmsxVideoOutput
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/** WebRTC setup shared by the host and the viewer. */
internal object RemotePlayRtc {
    @Volatile
    private var initialized = false

    /** One EGL context for the process: the viewer's renderer and the codecs share it. */
    val eglBase: EglBase by lazy { EglBase.create() }

    /**
     * [h264Encoder] and [h264Decoder] name the hardware H.264 codecs in use, or are null when
     * there is none. [textureInput]: the encoder can take frames as GL textures.
     */
    class Factory(
        val factory: PeerConnectionFactory,
        private val audio: JavaAudioDeviceModule,
        val h264Encoder: String?,
        val h264Decoder: String?,
        val textureInput: Boolean,
    ) {
        fun dispose() {
            runCatching { factory.dispose() }
            runCatching { audio.release() }
        }
    }

    /**
     * WebRTC is built for calls, where a steady picture matters more than a fast one. A game is
     * the other way round:
     *  - Low-latency playout: the viewer decodes each frame as soon as it is complete instead of
     *    holding frames in a jitter buffer to even out their timing (min 0; a max at or under
     *    500 ms is what switches the mode on). Set on both sides, since the host's half travels in
     *    the video packets.
     *  - Decodes at least 12 ms apart. Frames that arrive bunched up on Wi-Fi went to the decoder
     *    back to back, and a screen shows one frame per refresh: of two done within the same
     *    refresh only the newer is shown. Spaced out they each get shown; a steady 60 fps
     *    (16.7 ms apart) is never held back.
     *  - More than two frames waiting, after a hiccup, go to the decoder at once instead, so the
     *    picture jumps to the newest. Spaced out, the backlog played faster than the game ran
     *    (up to 83 fps) until it had caught up.
     *  - No frame dropper: it skips frames whenever the encoder runs over its bitrate, which fast
     *    game footage often does. The bitrate still follows the network; frames are simply not
     *    thrown away on top.
     *  - The same for the congestion window: with more data in flight than it allows, WebRTC's
     *    default skips every few frames before they are encoded (a 54 fps second with the game at
     *    60, in test 10, when moving again after standing still). DropFrame:false lowers the
     *    bitrate for that moment instead, so every frame still goes out, a little softer.
     */
    private const val FIELD_TRIALS =
        "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:100/" +
            "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:100/" +
            "WebRTC-ZeroPlayoutDelay/min_pacing:12ms,max_decode_queue_size:2/" +
            "WebRTC-FrameDropper/Disabled/" +
            "WebRTC-CongestionWindow/QueueSize:350,MinBitrate:30000,DropFrame:false/"

    /**
     * A factory with hardware video codecs. The audio module exists because the factory needs
     * one, but it never starts: no audio track is ever added, and the game's sound travels on a
     * data channel instead, so the microphone is never touched. [videoOutput]: the viewer's screen,
     * which its decoder then draws onto directly.
     */
    @Synchronized
    fun createFactory(context: Context, videoOutput: ArmsxVideoOutput? = null): Factory {
        val app = context.applicationContext
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app)
                    .setFieldTrials(FIELD_TRIALS)
                    .createInitializationOptions(),
            )
            initialized = true
        }
        val audio = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        // Not WebRTC's default factories: their H.264 encoder check misses current Snapdragons,
        // and neither sets its codec up for low latency (see the classes).
        val encoders = org.webrtc.ArmsxVideoEncoderFactory(eglBase.eglBaseContext)
        val decoders = org.webrtc.ArmsxVideoDecoderFactory(eglBase.eglBaseContext, videoOutput)
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(decoders)
            .createPeerConnectionFactory()
        return Factory(factory, audio, encoders.hardwareH264Name(), decoders.hardwareH264Name(), encoders.textureInput())
    }

    /**
     * Public STUN servers tell each side the address the internet sees for it, which is what a
     * friend online connects to. They only answer "what is my address"; no game traffic goes near
     * them, and there is no relay: a pair that cannot connect directly does not connect.
     */
    private val stunServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    )

    fun configuration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(stunServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // The host is always busy running the game, which this detector reads as the encoder
            // falling behind and answers by shrinking the picture. The encoder is a separate
            // hardware block, so that load says nothing about it.
            enableCpuOveruseDetection = false
        }

    /**
     * H.264 first, since every phone has a hardware encoder and decoder for it, and VP8 as the
     * fallback a software decoder always covers. Offering nothing else also keeps a connection
     * code short: every listed codec is several lines of the offer.
     */
    fun restrictCodecs(factory: PeerConnectionFactory, transceiver: RtpTransceiver) {
        val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        fun rank(name: String) = when (name.uppercase()) {
            "H264" -> 0
            "VP8" -> 1
            "RTX", "RED", "ULPFEC" -> 2
            else -> -1
        }
        val kept = codecs.filter { rank(it.name) >= 0 }.sortedBy { rank(it.name) }
        if (kept.any { rank(it.name) <= 1 }) runCatching { transceiver.setCodecPreferences(kept) }
    }

    fun sdpMessage(type: String, sdp: SessionDescription): JSONObject =
        JSONObject().put("t", type).put("sdp", sdp.description)

    fun bye(reason: String): JSONObject = JSONObject().put("t", "bye").put("reason", reason)
}

/**
 * Waits for a local description to be complete. Offers and answers go out whole, candidates
 * included, so they wait for ICE gathering; STUN usually answers within a second, and after
 * [timeoutMs] whatever was gathered goes out anyway. Main thread; [onReady] runs at most once.
 */
internal class GatherWait(private val main: Handler, private val timeoutMs: Long, private val onReady: () -> Unit) {
    private var localSet = false
    private var gathered = false
    private var timedOut = false
    private var fired = false
    private val timeout = Runnable {
        timedOut = true
        check()
    }

    fun localDescriptionSet() {
        main.post {
            localSet = true
            main.postDelayed(timeout, timeoutMs)
            check()
        }
    }

    fun gatheringComplete() {
        main.post {
            gathered = true
            check()
        }
    }

    fun cancel() {
        main.post {
            fired = true
            main.removeCallbacks(timeout)
        }
    }

    private fun check() {
        if (fired || !localSet || !(gathered || timedOut)) return
        fired = true
        main.removeCallbacks(timeout)
        onReady()
    }
}

/** SdpObserver with nothing to do by default; failures go to [onFailure]. */
internal open class SdpAdapter(private val onFailure: (String) -> Unit) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) = onFailure(error ?: "create failed")
    override fun onSetFailure(error: String?) = onFailure(error ?: "set failed")
}

/** PeerConnection.Observer with nothing to do by default. */
internal open class PeerObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
}

/** DataChannel.Observer that only cares about messages. */
internal class MessageObserver(private val handler: (DataChannel.Buffer) -> Unit) : DataChannel.Observer {
    override fun onBufferedAmountChange(previousAmount: Long) {}
    override fun onStateChange() {}
    override fun onMessage(buffer: DataChannel.Buffer) = handler(buffer)
}
