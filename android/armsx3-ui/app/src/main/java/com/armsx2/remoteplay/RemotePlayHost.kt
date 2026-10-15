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
import org.webrtc.ArmsxVideoEncoderFactory
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.YuvHelper
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val TAG = "RemotePlay"

/** Player 2 is pad port 1. Pushing data to it is what plugs the controller in (see overlayPadData). */
private const val PLAYER_2_PORT = 1

/** After the friend's answer arrives, how long the connection gets to come up. */
private const val CONNECT_TIMEOUT_MS = 60_000L

/**
 * The hosting side. Takes one viewer at a time, streams the running game to it and feeds the
 * viewer's pad in as player 2. A viewer arrives one of two ways: on the same Wi-Fi it finds this
 * device by itself ([startWifi]); online, it joins with an invite ([createInvite]): a six-character
 * code through the lobby service, or, when that cannot be reached, a pasted code with a pasted
 * reply ([acceptReply]).
 *
 * Process-wide rather than tied to a screen, so closing the in-game menu, or leaving for a chat
 * app to send an invite, does not end anything. Hosting ends when it is stopped or the game
 * closes. Main thread unless a comment says otherwise.
 */
object RemotePlayHost {
    sealed interface Status {
        data object Off : Status
        /** Hosting on this Wi-Fi. [address] is what a viewer can type if this device is not listed. */
        data class Waiting(val address: String?) : Status
        /**
         * An online invite: [lobbyCode] is the short code to give the friend; [code] is the long
         * pasted one used when the lobby service cannot be reached. Both null while preparing.
         */
        data class Inviting(val code: String? = null, val lobbyCode: String? = null) : Status
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

    /** Host on this Wi-Fi: listen, and announce this device so viewers list it. */
    fun startWifi(context: Context) {
        if (server != null) return
        val game = runningGame() ?: return
        val app = context.applicationContext
        appContext = app

        val listener = SignalingServer { socket -> main.post { onWifiViewer(socket) } }
        if (!listener.start()) {
            _status.value = Status.Failed(I18n.get("remoteplay.host.noNetwork"))
            return
        }
        server = listener
        advertiser = HostAdvertiser(app).also { it.register(Build.MODEL, listener.port, game) }
        if (session == null) _status.value = Status.Waiting(address(app, listener.port))
        watchGame()
    }

    /** Online: prepare an offer and put it up under a short code for the friend to type. */
    fun createInvite(context: Context) {
        if (session != null) return
        val game = runningGame() ?: return
        val app = context.applicationContext
        appContext = app

        lateinit var created: HostSession
        val link = LobbyHostLink(
            onCode = { code -> main.post { if (session === created) _status.value = Status.Inviting(lobbyCode = code) } },
            onFallback = { pasted -> main.post { if (session === created) _status.value = Status.Inviting(code = pasted) } },
            onAnswer = { answer -> main.post { if (session === created) acceptAnswer(created, answer) } },
            onExpired = { main.post { if (session === created) sessionEnded(created, I18n.get("remoteplay.invite.expired")) } },
        )
        created = HostSession(
            app, link, game, RemotePlayProtocol.ONLINE_BITRATE,
            onFinished = { ended, message -> main.post { if (session === ended) sessionEnded(ended, message) } },
        )
        session = created
        _status.value = Status.Inviting()
        watchGame()
        created.offer()
    }

    /**
     * The friend's reply to an invite, as pasted (the whole chat message is fine). False when it
     * holds no reply code; the invite stays open so the player can try again.
     */
    fun acceptReply(text: String): Boolean {
        val pending = session ?: return false
        val message = RemotePlayProtocol.decodeCode(text) ?: return false
        if (message.optString("t") != "answer") return false
        acceptAnswer(pending, message)
        return true
    }

    private fun acceptAnswer(pending: HostSession, message: JSONObject) {
        if (message.optInt("v") != RemotePlayProtocol.VERSION) {
            sessionEnded(pending, I18n.get("remoteplay.bye.version"))
            return
        }
        pending.onSignal(message)
    }

    fun stop() {
        main.removeCallbacks(gameWatch)
        session?.end("stopped")
        session = null
        stopWifi()
        _status.value = Status.Off
    }

    private fun stopWifi() {
        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
    }

    private fun runningGame(): String? {
        val game = MainActivityRuntime.currentGame.value
        if (game == null) {
            _status.value = Status.Failed(I18n.get("remoteplay.host.noGame"))
            return null
        }
        return game.title
    }

    private fun watchGame() {
        main.removeCallbacks(gameWatch)
        main.postDelayed(gameWatch, 1_000)
    }

    private fun address(context: Context, port: Int): String? {
        val ip = localIpv4(context) ?: return null
        return if (port == RemotePlayProtocol.PORT) ip else "$ip:$port"
    }

    private fun onWifiViewer(socket: Socket) {
        val app = appContext
        val game = MainActivityRuntime.currentGame.value?.title
        if (session != null || server == null || app == null || game == null) {
            // One player 2 at a time.
            SignalingConnection(socket, {}, {}).also { it.start() }.sendAndClose(RemotePlayRtc.bye("busy"))
            return
        }

        lateinit var created: HostSession
        val connection = SignalingConnection(
            socket,
            onMessage = { message -> main.post { if (session === created) created.onSignal(message) } },
            onClosed = { main.post { if (session === created) created.linkClosed() } },
        )
        created = HostSession(
            app, TcpLink(connection), game, RemotePlayProtocol.WIFI_BITRATE,
            onFinished = { ended, message -> main.post { if (session === ended) sessionEnded(ended, message) } },
        )
        session = created
        connection.start()
    }

    /** [message] is shown to the player when the session ended on a problem, not by choice. */
    private fun sessionEnded(ended: HostSession, message: String?) {
        ended.end("ended")
        session = null
        val listener = server
        _status.value = when {
            message != null -> Status.Failed(message)
            listener != null -> Status.Waiting(appContext?.let { address(it, listener.port) })
            else -> Status.Off
        }
        if (message != null) {
            // A failure ends hosting entirely, so the message is not replaced by a list nobody asked for.
            main.removeCallbacks(gameWatch)
            stopWifi()
        } else if (listener == null) {
            main.removeCallbacks(gameWatch)
        }
    }

    internal fun report(status: Status) {
        main.post { _status.value = status }
    }
}

/**
 * One viewer, from the offer to the bye. [bitrate] suits the way the viewer came: the same Wi-Fi
 * or online. Main thread unless a comment says otherwise.
 */
private class HostSession(
    private val context: Context,
    private val link: SignalLink,
    private val game: String,
    private val bitrate: RemotePlayProtocol.Bitrate,
    private val onFinished: (HostSession, String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var viewer = "?"
    private var rtc: RemotePlayRtc.Factory? = null
    private var peer: PeerConnection? = null
    private var gather: GatherWait? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var inputChannel: DataChannel? = null

    @Volatile
    private var audioChannel: DataChannel? = null

    @Volatile
    private var streaming = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var offered = false
    private var answered = false
    private var connectedOnce = false
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

    private val connectTimeout = Runnable {
        if (!connectedOnce) finish(I18n.get("remoteplay.timeout.host"))
    }

    // What the stream is doing, in logcat (tag RemotePlay) every few seconds while it runs.
    private val stats = RemotePlayStats(sending = true)
    private val statsTick = object : Runnable {
        override fun run() {
            if (ended || !streaming) return
            val host = hostStats?.let(::describeHost)
            peer?.let { pc -> stats.sample(pc) { line -> Log.i(TAG, "host stream: $line" + (host?.let { " · $it" } ?: "")) } }
            main.postDelayed(this, 5_000)
        }
    }

    // The host's side of the stream, sent to the viewer once a second for its stats: what the game
    // drew, what was captured and encoded, and what was dropped on the way. A few bytes a second.
    private var hostStats: RemotePlayProtocol.HostStats? = null
    private var lastCore: IntArray? = null
    private var lastEncoder: IntArray? = null
    private val hostStatsTick = object : Runnable {
        override fun run() {
            if (ended || !streaming) return
            sendHostStats()
            main.postDelayed(this, 1_000)
        }
    }

    fun onSignal(message: JSONObject) {
        if (ended) return
        when (message.optString("t")) {
            "hello" -> {
                if (message.optInt("v") != RemotePlayProtocol.VERSION) {
                    link.close(RemotePlayRtc.bye("version"))
                    finish(null)
                    return
                }
                viewer = message.optString("name").ifBlank { "?" }.take(40)
                RemotePlayHost.report(RemotePlayHost.Status.Connecting(viewer))
                offer()
            }
            "answer" -> {
                val pc = peer ?: return
                if (answered) return
                answered = true
                viewer = message.optString("name").ifBlank { viewer }.take(40)
                RemotePlayHost.report(RemotePlayHost.Status.Connecting(viewer))
                pc.setRemoteDescription(
                    SdpAdapter { error -> fail("answer: $error") },
                    SessionDescription(SessionDescription.Type.ANSWER, message.optString("sdp")),
                )
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            }
            "bye" -> finish(null)
        }
    }

    /** The Wi-Fi link dropped. Before the stream is up that is the end; after, the stream carries on. */
    fun linkClosed() {
        if (!connectedOnce) finish(null)
    }

    /** Builds the peer and sends the offer once it is complete, candidates included. */
    fun offer() {
        if (offered || ended) return
        offered = true

        val created = RemotePlayRtc.createFactory(context).also { rtc = it }
        val factory = created.factory
        Log.i(TAG, "video encoder: ${created.h264Encoder ?: "no hardware H.264, stock factories"}")
        val wait = GatherWait(main, 4_000) {
            val description = peer?.localDescription ?: return@GatherWait
            link.send(
                RemotePlayRtc.sdpMessage("offer", description)
                    .put("v", RemotePlayProtocol.VERSION)
                    .put("game", game.take(80))
                    .put("host", Build.MODEL),
            )
        }
        gather = wait

        val pc = factory.createPeerConnection(RemotePlayRtc.configuration(), object : PeerObserverAdapter() {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) wait.gatheringComplete()
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                main.post { onConnectionState(newState) }
            }
        })
        if (pc == null) {
            fail("no peer connection")
            return
        }
        peer = pc
        // Start near where a game stream settles instead of ramping up from a few hundred kbps,
        // which left the first seconds blurry and stuttering.
        pc.setBitrate(bitrate.min, bitrate.start, bitrate.max)

        val source = factory.createVideoSource(false).also { videoSource = it }
        val track = factory.createVideoTrack("game", source).also { videoTrack = it }
        val transceiver = pc.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("armsx3")),
        )
        RemotePlayRtc.restrictCodecs(factory, transceiver)

        // Unordered and never retransmitted: a late pad state is worse than a missing one, since
        // the next packet carries the whole pad anyway, and late audio is just a delay.
        fun lossy() = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        inputChannel = pc.createDataChannel(RemotePlayProtocol.CHANNEL_INPUT, lossy())?.also { channel ->
            channel.registerObserver(MessageObserver { buffer -> onInputPacket(buffer.data) })
        }
        audioChannel = pc.createDataChannel(RemotePlayProtocol.CHANNEL_AUDIO, lossy())

        pc.createOffer(object : SdpAdapter({ error -> fail("offer: $error") }) {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SdpAdapter({ error -> fail("local: $error") }) {
                    override fun onSetSuccess() = wait.localDescriptionSet()
                }, description)
            }
        }, MediaConstraints())
    }

    private fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        if (ended) return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                connectedOnce = true
                main.removeCallbacks(connectTimeout)
                startStreaming()
            }
            // Before the first connection, a failure can still recover once the friend's side
            // starts answering; the connect timeout decides. After it, failed means gone.
            PeerConnection.PeerConnectionState.FAILED -> if (connectedOnce) finish(null)
            PeerConnection.PeerConnectionState.CLOSED -> finish(null)
            else -> Unit
        }
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
                it.maxBitrateBps = bitrate.max
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
        main.postDelayed(statsTick, 5_000)
        main.postDelayed(hostStatsTick, 1_000)
    }

    private fun sendHostStats() {
        val core = IntArray(4)
        if (!RPCSX.instance.remotePlayCounters(core)) return
        val encoder = ArmsxVideoEncoderFactory.encoderCounters()
        val previousCore = lastCore
        val previousEncoder = lastEncoder
        lastCore = core
        lastEncoder = encoder
        if (previousCore == null || previousEncoder == null) return

        // Counters are running totals; the stats are the last second's share of them.
        fun coreDelta(index: Int) = core[index] - previousCore[index]
        fun encoderDelta(index: Int) = encoder[index] - previousEncoder[index]
        val current = RemotePlayProtocol.HostStats(
            gameFps = coreDelta(0),
            capturedFps = coreDelta(2),
            encodedFps = encoderDelta(1),
            // Not read back (GPU busy), replaced before the app took them, refused by the encoder.
            dropped = coreDelta(1) + coreDelta(3) + encoderDelta(2),
            encoder = ArmsxVideoEncoderFactory.encoderName(),
        )
        hostStats = current
        audioChannel?.takeIf { it.state() == DataChannel.State.OPEN }
            ?.send(DataChannel.Buffer(RemotePlayProtocol.encodeHostStats(current), true))
    }

    // rp-video thread. Pulls frames from the core and hands them to the encoder.
    private fun videoLoop(observer: CapturerObserver) {
        val rgba = ByteBuffer.allocateDirect(RemotePlayProtocol.MAX_WIDTH * RemotePlayProtocol.MAX_HEIGHT * 4)
        val info = IntArray(4)

        while (streaming) {
            val result = RPCSX.instance.remotePlayPollVideo(rgba, info, 50)
            if (result == -1) break
            if (result != 1) continue

            val width = info[0]
            val height = info[1]
            // libyuv, NEON-optimized on ARM, rather than the plain C loop the core used to run.
            // The encoder releases the buffer when it is done with the frame.
            val i420 = JavaI420Buffer.allocate(width, height)
            YuvHelper.ABGRToI420(
                rgba, width * 4,
                i420.dataY, i420.strideY,
                i420.dataU, i420.strideU,
                i420.dataV, i420.strideV,
                width, height,
            )
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
    private fun onInputPacket(data: ByteBuffer) {
        if (RemotePlayProtocol.isBye(data)) {
            main.post { finish(null) }
            return
        }
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
        main.post { finish(I18n.get("remoteplay.host.failed")) }
    }

    /** Asks the host to end this session; [message] is shown when it ended on a problem. */
    private fun finish(message: String?) {
        if (ended) return
        onFinished(this, message)
    }

    /** Idempotent. */
    fun end(reason: String) {
        if (ended) return
        ended = true
        main.removeCallbacks(padWatch)
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(statsTick)
        main.removeCallbacks(hostStatsTick)
        gather?.cancel()

        if (streaming) {
            streaming = false
            // Unblocks both loops at once: their polls return -1.
            RPCSX.instance.remotePlayStop()
        }
        videoThread?.join(500)
        audioThread?.join(500)
        applyPad(RemotePlayProtocol.PadState())

        // A few times, since the channel drops what it cannot deliver.
        audioChannel?.takeIf { it.state() == DataChannel.State.OPEN }?.let { channel ->
            repeat(3) { channel.send(DataChannel.Buffer(RemotePlayProtocol.encodeBye(), true)) }
        }
        link.close(RemotePlayRtc.bye(reason))

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
