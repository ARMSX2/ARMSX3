package com.armsx2.remoteplay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.Rpcs3Bridge
import net.rpcsx.RPCSX
import org.json.JSONObject
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

private const val TAG = "RemotePlay"

/** Player 2 is pad port 1. Pushing data to it is what plugs the controller in (see overlayPadData). */
private const val PLAYER_2_PORT = 1

/**
 * The hosting side. Listens on this Wi-Fi, takes one viewer at a time, streams the running game
 * to it and feeds the viewer's pad in as player 2.
 *
 * Process-wide rather than tied to a screen, so closing the in-game menu does not end a session.
 * It ends when hosting is stopped or the game closes. Everything here runs on the main thread
 * unless a comment says otherwise.
 */
object RemotePlayHost {
    sealed interface Status {
        data object Off : Status
        /** [address] is what a viewer can type when the host does not show up in its list. */
        data class Waiting(val address: String?) : Status
        data class Connecting(val viewer: String) : Status
        data class Playing(val viewer: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = mutableStateOf<Status>(Status.Off)
    val status: State<Status> get() = _status

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var server: SignalingServer? = null
    private var advertiser: HostAdvertiser? = null
    private var session: HostSession? = null

    // Hosting belongs to the running game: a closed game ends it, whichever path closed it.
    private val gameWatch = object : Runnable {
        override fun run() {
            if (MainActivityRuntime.currentGame.value == null) {
                stop()
                return
            }
            main.postDelayed(this, 1_000)
        }
    }

    fun start(context: Context) {
        val current = _status.value
        if (current !is Status.Off && current !is Status.Failed) return

        val game = MainActivityRuntime.currentGame.value
        if (game == null) {
            _status.value = Status.Failed(I18n.get("remoteplay.host.noGame"))
            return
        }

        val app = context.applicationContext
        appContext = app

        val listener = SignalingServer { socket -> main.post { onViewer(socket) } }
        if (!listener.start()) {
            _status.value = Status.Failed(I18n.get("remoteplay.host.noNetwork"))
            return
        }
        server = listener
        advertiser = HostAdvertiser(app).also { it.register(Build.MODEL, listener.port, game.title) }
        _status.value = Status.Waiting(address(app, listener.port))

        main.removeCallbacks(gameWatch)
        main.postDelayed(gameWatch, 1_000)
    }

    fun stop() {
        main.removeCallbacks(gameWatch)
        session?.end("stopped")
        session = null
        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        _status.value = Status.Off
    }

    private fun address(context: Context, port: Int): String? {
        val ip = localIpv4(context) ?: return null
        return if (port == RemotePlayProtocol.PORT) ip else "$ip:$port"
    }

    private fun onViewer(socket: Socket) {
        val app = appContext
        if (session != null || server == null || app == null) {
            // One player 2 at a time.
            SignalingConnection(socket, {}, {}).also { it.start() }.sendAndClose(RemotePlayRtc.bye("busy"))
            return
        }

        lateinit var created: HostSession
        val connection = SignalingConnection(
            socket,
            onMessage = { message -> main.post { if (session === created) created.onSignal(message) } },
            onClosed = { main.post { if (session === created) sessionEnded(created) } },
        )
        created = HostSession(app, connection, onFinished = { ended -> main.post { if (session === ended) sessionEnded(ended) } })
        session = created
        connection.start()
    }

    private fun sessionEnded(ended: HostSession) {
        ended.end("ended")
        session = null
        val listener = server ?: return
        _status.value = Status.Waiting(appContext?.let { address(it, listener.port) })
    }

    internal fun report(status: Status) {
        main.post { if (server != null) _status.value = status }
    }
}

/** One viewer, from its hello to its bye. */
private class HostSession(
    private val context: Context,
    private val connection: SignalingConnection,
    private val onFinished: (HostSession) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var viewer = "?"
    private var rtc: RemotePlayRtc.Factory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var inputChannel: DataChannel? = null

    @Volatile
    private var audioChannel: DataChannel? = null

    @Volatile
    private var streaming = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var ended = false

    private var appliedPad = RemotePlayProtocol.PadState()
    private var lastPadSeq = 0
    private var hasPadSeq = false
    private var lastPadAt = 0L

    // Releases player 2's buttons when its packets stop arriving, so a dropped connection does not
    // leave a direction held until it recovers.
    private val padWatch = object : Runnable {
        override fun run() {
            if (ended) return
            if (SystemClock.uptimeMillis() - lastPadAt > 1_000) applyPad(RemotePlayProtocol.PadState())
            main.postDelayed(this, 250)
        }
    }

    fun onSignal(message: JSONObject) {
        if (ended) return
        when (message.optString("t")) {
            "hello" -> {
                if (message.optInt("v") != RemotePlayProtocol.VERSION) {
                    connection.sendAndClose(RemotePlayRtc.bye("version"))
                    return
                }
                viewer = message.optString("name").ifBlank { "?" }.take(40)
                RemotePlayHost.report(RemotePlayHost.Status.Connecting(viewer))
                createPeer()
            }
            "answer" -> peer?.setRemoteDescription(
                SdpAdapter(::fail),
                SessionDescription(SessionDescription.Type.ANSWER, message.optString("sdp")),
            )
            "ice" -> peer?.addIceCandidate(RemotePlayRtc.iceFromMessage(message))
            "bye" -> finish()
        }
    }

    private fun createPeer() {
        val factory = RemotePlayRtc.createFactory(context).also { rtc = it }.factory

        val pc = factory.createPeerConnection(RemotePlayRtc.configuration(), object : PeerObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                connection.send(RemotePlayRtc.iceMessage(candidate))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                main.post {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> startStreaming()
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> finish()
                        else -> Unit
                    }
                }
            }
        })
        if (pc == null) {
            fail("no peer connection")
            return
        }
        peer = pc

        val source = factory.createVideoSource(false).also { videoSource = it }
        val track = factory.createVideoTrack("game", source).also { videoTrack = it }
        val transceiver = pc.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("armsx3")),
        )
        RemotePlayRtc.preferH264(factory, transceiver)

        // Unordered and never retransmitted: a late pad state is worse than a missing one, since
        // the next packet carries the whole pad anyway, and late audio is just a delay.
        fun lossy() = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        inputChannel = pc.createDataChannel(RemotePlayProtocol.CHANNEL_INPUT, lossy())?.also { channel ->
            channel.registerObserver(MessageObserver { buffer -> onPadPacket(buffer.data) })
        }
        audioChannel = pc.createDataChannel(RemotePlayProtocol.CHANNEL_AUDIO, lossy())

        pc.createOffer(object : SdpAdapter(::fail) {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(SdpAdapter(::fail), description)
                connection.send(RemotePlayRtc.sdpMessage("offer", description))
            }
        }, MediaConstraints())
    }

    private fun startStreaming() {
        if (streaming || ended) return
        val pc = peer ?: return
        val source = videoSource ?: return

        if (!RPCSX.instance.remotePlayStart(RemotePlayProtocol.MAX_WIDTH, RemotePlayProtocol.MAX_HEIGHT)) {
            fail("capture did not start")
            return
        }
        streaming = true

        // Smooth motion over sharpness: a game that stutters is worse than one that softens.
        pc.senders.firstOrNull { it.track()?.kind() == "video" }?.let { sender ->
            val parameters = sender.parameters
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            parameters.encodings.forEach {
                it.maxBitrateBps = RemotePlayProtocol.MAX_BITRATE_BPS
                it.maxFramerate = 60
            }
            sender.setParameters(parameters)
        }

        RemotePlayHost.report(RemotePlayHost.Status.Playing(viewer))
        val observer = source.capturerObserver
        observer.onCapturerStarted(true)
        videoThread = thread(name = "rp-video") { videoLoop(observer) }
        audioThread = thread(name = "rp-audio") { audioLoop() }
        lastPadAt = SystemClock.uptimeMillis()
        main.postDelayed(padWatch, 250)
    }

    // rp-video thread. Pulls frames from the core and hands them to the encoder.
    private fun videoLoop(observer: CapturerObserver) {
        val frameBytes = RemotePlayProtocol.MAX_WIDTH * RemotePlayProtocol.MAX_HEIGHT * 3 / 2
        // The encoder holds a buffer until it is done with it; three cover the frame in flight.
        val pool = ArrayBlockingQueue<ByteBuffer>(3).apply { repeat(3) { add(ByteBuffer.allocateDirect(frameBytes)) } }
        // Polled into when the encoder still holds every buffer, so that frame is dropped here
        // instead of piling up in the core.
        val overflow = ByteBuffer.allocateDirect(frameBytes)
        val info = IntArray(4)

        while (streaming) {
            val buffer = pool.poll()
            val result = RPCSX.instance.remotePlayPollVideo(buffer ?: overflow, info, 50)
            if (result != 1 || buffer == null) {
                buffer?.let(pool::offer)
                if (result == -1) break
                continue
            }

            val width = info[0]
            val height = info[1]
            val luma = width * height
            val chroma = luma / 4
            val i420 = JavaI420Buffer.wrap(
                width, height,
                buffer.region(0, luma), width,
                buffer.region(luma, chroma), width / 2,
                buffer.region(luma + chroma, chroma), width / 2,
            ) { pool.offer(buffer) }
            val frame = VideoFrame(i420, 0, System.nanoTime())
            observer.onFrameCaptured(frame)
            frame.release()
        }
        observer.onCapturerStopped()
    }

    // rp-audio thread. Pulls 10 ms chunks from the core and sends each as one packet.
    private fun audioLoop() {
        val pcm = ByteBuffer.allocateDirect(RemotePlayProtocol.AUDIO_CHUNK_FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN)
        var seq = 0
        while (streaming) {
            val frames = RPCSX.instance.remotePlayPollAudio(pcm, RemotePlayProtocol.AUDIO_CHUNK_FRAMES, 50)
            if (frames < 0) break
            if (frames == 0) continue
            val channel = audioChannel ?: break
            // Behind on the network: skip a chunk rather than build up delay.
            if (channel.state() != DataChannel.State.OPEN || channel.bufferedAmount() > 64 * 1024) continue
            channel.send(DataChannel.Buffer(RemotePlayProtocol.encodeAudio(seq++, pcm, frames), true))
        }
    }

    // WebRTC's thread.
    private fun onPadPacket(data: ByteBuffer) {
        val (seq, pad) = RemotePlayProtocol.decodePad(data) ?: return
        main.post {
            if (ended || !streaming) return@post
            // The channel is unordered: a late packet must not undo a newer one.
            if (hasPadSeq && seq - lastPadSeq <= 0) return@post
            hasPadSeq = true
            lastPadSeq = seq
            lastPadAt = SystemClock.uptimeMillis()
            applyPad(pad)
        }
    }

    private fun applyPad(pad: RemotePlayProtocol.PadState) {
        val old = appliedPad
        if (pad == old) return
        val changed = pad.buttons xor old.buttons
        for (bit in RemotePlayProtocol.PAD_KEYCODES.indices) {
            val mask = 1 shl bit
            if (changed and mask != 0) {
                Rpcs3Bridge.setPadButton(PLAYER_2_PORT, RemotePlayProtocol.PAD_KEYCODES[bit], 0, pad.buttons and mask != 0)
            }
        }
        if (pad.l2 != old.l2) Rpcs3Bridge.setPadButton(PLAYER_2_PORT, KeyEvent.KEYCODE_BUTTON_L2, pad.l2 * 32767 / 255, pad.l2 > 0)
        if (pad.r2 != old.r2) Rpcs3Bridge.setPadButton(PLAYER_2_PORT, KeyEvent.KEYCODE_BUTTON_R2, pad.r2 * 32767 / 255, pad.r2 > 0)
        if (pad.lx != old.lx || pad.ly != old.ly) Rpcs3Bridge.setStick(PLAYER_2_PORT, true, pad.lx, pad.ly)
        if (pad.rx != old.rx || pad.ry != old.ry) Rpcs3Bridge.setStick(PLAYER_2_PORT, false, pad.rx, pad.ry)
        appliedPad = pad
    }

    private fun fail(reason: String) {
        Log.w(TAG, "host session failed: $reason")
        finish()
    }

    /** Any thread. Asks the host to end this session. */
    private fun finish() {
        main.post { onFinished(this) }
    }

    /** Main thread. Idempotent. */
    fun end(reason: String) {
        if (ended) return
        ended = true
        main.removeCallbacks(padWatch)

        if (streaming) {
            streaming = false
            // Unblocks both loops at once: their polls return -1.
            RPCSX.instance.remotePlayStop()
        }
        videoThread?.join(500)
        audioThread?.join(500)
        applyPad(RemotePlayProtocol.PadState())

        connection.sendAndClose(RemotePlayRtc.bye(reason))
        runCatching { peer?.close() }
        runCatching { inputChannel?.unregisterObserver() }
        runCatching { inputChannel?.dispose() }
        runCatching { audioChannel?.dispose() }
        runCatching { peer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        rtc?.dispose()
        peer = null
        audioChannel = null
        inputChannel = null
    }
}

/** A standalone view of [length] bytes at [offset], for handing one plane to the encoder. */
private fun ByteBuffer.region(offset: Int, length: Int): ByteBuffer {
    val view = duplicate()
    view.position(offset)
    view.limit(offset + length)
    return view.slice()
}
