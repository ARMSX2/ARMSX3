package com.armsx2.remoteplay

import android.content.Context
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
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

    class Factory(val factory: PeerConnectionFactory, private val audio: JavaAudioDeviceModule) {
        fun dispose() {
            runCatching { factory.dispose() }
            runCatching { audio.release() }
        }
    }

    /**
     * A factory with hardware video codecs. The audio module exists because the factory needs
     * one, but it never starts: no audio track is ever added, and the game's sound travels on a
     * data channel instead, so the microphone is never touched.
     */
    @Synchronized
    fun createFactory(context: Context): Factory {
        val app = context.applicationContext
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            initialized = true
        }
        val audio = JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        return Factory(factory, audio)
    }

    /** Same-Wi-Fi only for now: local candidates are all it needs, so no STUN server is asked. */
    fun configuration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

    /** H.264 first: every phone has a hardware encoder and decoder for it. */
    fun preferH264(factory: PeerConnectionFactory, transceiver: RtpTransceiver) {
        val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        val (h264, rest) = codecs.partition { it.name.equals("H264", ignoreCase = true) }
        if (h264.isNotEmpty()) runCatching { transceiver.setCodecPreferences(h264 + rest) }
    }

    fun sdpMessage(type: String, sdp: SessionDescription): JSONObject =
        JSONObject().put("t", type).put("sdp", sdp.description)

    fun iceMessage(candidate: IceCandidate): JSONObject =
        JSONObject().put("t", "ice").put("mid", candidate.sdpMid).put("i", candidate.sdpMLineIndex).put("c", candidate.sdp)

    fun iceFromMessage(message: JSONObject): IceCandidate =
        IceCandidate(message.optString("mid"), message.optInt("i"), message.optString("c"))

    fun bye(reason: String): JSONObject = JSONObject().put("t", "bye").put("reason", reason)
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
