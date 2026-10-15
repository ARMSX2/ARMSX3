package com.armsx2.remoteplay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "RemotePlay"

/** On the same Wi-Fi the host answers at once; give up after this. */
private const val WIFI_CONNECT_TIMEOUT_MS = 30_000L

/** Online, the friend still has to receive the reply and paste it. */
private const val INVITE_CONNECT_TIMEOUT_MS = 5 * 60_000L

/** How a viewer reaches its host. */
internal sealed interface ViewerMode {
    data class Wifi(val host: String, val port: Int) : ViewerMode

    /** From an invite code; [offer] is the decoded invite. */
    data class Invite(val offer: JSONObject) : ViewerMode
}

/**
 * The viewer: a friend's game on this screen, played as player 2 with this device's controller.
 * Nothing is emulated here, so it needs no BIOS, firmware or game, only a way to reach the host:
 * the same Wi-Fi, or an invite code.
 */
class RemotePlayViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_OFFER = "offer"

        /** Join a host on this Wi-Fi. */
        fun start(context: Context, host: String, port: Int, name: String) {
            launch(
                context,
                Intent(context, RemotePlayViewerActivity::class.java)
                    .putExtra(EXTRA_HOST, host)
                    .putExtra(EXTRA_PORT, port)
                    .putExtra(EXTRA_NAME, name),
            )
        }

        /** Join from an invite. [offer] is the decoded invite code (RemotePlayProtocol.decodeCode). */
        fun startWithInvite(context: Context, offer: JSONObject) {
            launch(context, Intent(context, RemotePlayViewerActivity::class.java).putExtra(EXTRA_OFFER, offer.toString()))
        }

        private fun launch(context: Context, intent: Intent) {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var renderer: SurfaceViewRenderer
    private var session: ViewerSession? = null
    private val main = Handler(Looper.getMainLooper())

    // Shown over the black screen until the first frame arrives.
    private val status = mutableStateOf("")
    private val playing = mutableStateOf(false)
    private val hintVisible = mutableStateOf(false)
    private val leaveAsked = mutableStateOf(false)

    // Online: the reply code the player sends back, once it is ready.
    private val inviteMode = mutableStateOf(false)
    private val reply = mutableStateOf<String?>(null)
    private val connected = mutableStateOf(false)

    private val input = ViewerInput { pad -> session?.sendPad(pad) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val offer = intent.getStringExtra(EXTRA_OFFER)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val host = intent.getStringExtra(EXTRA_HOST)
        val mode = when {
            offer != null -> ViewerMode.Invite(offer)
            host != null -> ViewerMode.Wifi(host, intent.getIntExtra(EXTRA_PORT, RemotePlayProtocol.PORT))
            else -> {
                finish()
                return
            }
        }
        inviteMode.value = mode is ViewerMode.Invite

        renderer = SurfaceViewRenderer(this).apply {
            init(RemotePlayRtc.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    main.post {
                        playing.value = true
                        hintVisible.value = true
                        main.postDelayed({ hintVisible.value = false }, 6_000)
                    }
                }

                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {}
            })
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize())

                    if (!playing.value) {
                        if (inviteMode.value && !connected.value) {
                            ReplyPanel()
                        } else if (status.value.isNotEmpty()) {
                            Text(
                                status.value,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        }
                    }

                    if (playing.value && hintVisible.value && !leaveAsked.value) {
                        Text(
                            str("remoteplay.viewer.hint"),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }

                    if (leaveAsked.value) {
                        LeavePrompt()
                    }
                }
            }
        }

        status.value = when (mode) {
            is ViewerMode.Wifi -> I18n.get("remoteplay.viewer.connecting").format(intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { mode.host })
            is ViewerMode.Invite -> ""
        }
        session = ViewerSession(
            context = this,
            mode = mode,
            renderer = renderer,
            onReply = { code -> reply.value = code },
            onConnected = {
                connected.value = true
                status.value = I18n.get("remoteplay.viewer.waitingVideo")
            },
            onEnded = { message ->
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            },
        ).also { it.start() }
    }

    private fun shareReply() {
        reply.value?.let { shareText(this, I18n.get("remoteplay.reply.share").format(it)) }
    }

    private fun copyReply() {
        reply.value?.let { copyText(this, it) }
    }

    @Composable
    private fun BoxScope.ReplyPanel() {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 520.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val code = reply.value
            if (code == null) {
                Text(str("remoteplay.viewer.preparing"), color = Color.White)
            } else {
                Text(str("remoteplay.viewer.sendReply"), color = Color.White, textAlign = TextAlign.Center)
                Text(
                    code.take(32) + "...",
                    color = Color.White.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { shareReply() }) { Text(str("remoteplay.code.share") + " (A)") }
                    OutlinedButton(onClick = { copyReply() }) { Text(str("remoteplay.code.copy") + " (Y)") }
                }
                Text(
                    str("remoteplay.viewer.waitingHost"),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    @Composable
    private fun BoxScope.LeavePrompt() {
        Column(
            Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(str("remoteplay.viewer.leaveQuestion"), color = Color.White)
            Text(str("remoteplay.viewer.leaveButtons"), color = Color.White.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { leaveAsked.value = false }) { Text(str("remoteplay.viewer.stay")) }
                Button(onClick = { finish() }) { Text(str("remoteplay.viewer.leave")) }
            }
        }
    }

    override fun onDestroy() {
        session?.end(null)
        session = null
        if (::renderer.isInitialized) renderer.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromPad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)

        if (leaveAsked.value) {
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finish()
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> leaveAsked.value = false
                }
            }
            return true
        }

        // Home/PS on the pad, or the system back gesture, asks to leave. Neither reaches the host:
        // player 2's PS button would open the host's own home menu.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || (event.keyCode == KeyEvent.KEYCODE_BACK && !fromPad)) {
            if (event.action == KeyEvent.ACTION_UP) {
                input.releaseAll()
                leaveAsked.value = true
            }
            return true
        }

        // While the reply waits to be sent, the pad works the panel: A shares, Y copies.
        if (inviteMode.value && !connected.value && reply.value != null && fromPad) {
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> shareReply()
                    KeyEvent.KEYCODE_BUTTON_Y -> copyReply()
                }
            }
            return true
        }

        if (fromPad && input.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!leaveAsked.value && input.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

/**
 * This device's controller as player 2's pad. Face buttons are positional: the bottom button
 * (Android's A) is Cross, as on a DualShock.
 */
internal class ViewerInput(private val send: (RemotePlayProtocol.PadState) -> Unit) {
    private var keyButtons = 0
    private var hatButtons = 0
    private var lx = 128
    private var ly = 128
    private var rx = 128
    private var ry = 128
    private var l2Axis = 0
    private var r2Axis = 0
    private var l2Key = false
    private var r2Key = false
    private var last = RemotePlayProtocol.PadState()

    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> l2Key = down
            KeyEvent.KEYCODE_BUTTON_R2 -> r2Key = down
            else -> {
                val bit = when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> RemotePlayProtocol.PAD_CROSS
                    KeyEvent.KEYCODE_BUTTON_B -> RemotePlayProtocol.PAD_CIRCLE
                    KeyEvent.KEYCODE_BUTTON_X -> RemotePlayProtocol.PAD_SQUARE
                    KeyEvent.KEYCODE_BUTTON_Y -> RemotePlayProtocol.PAD_TRIANGLE
                    KeyEvent.KEYCODE_BUTTON_L1 -> RemotePlayProtocol.PAD_L1
                    KeyEvent.KEYCODE_BUTTON_R1 -> RemotePlayProtocol.PAD_R1
                    KeyEvent.KEYCODE_BUTTON_THUMBL -> RemotePlayProtocol.PAD_L3
                    KeyEvent.KEYCODE_BUTTON_THUMBR -> RemotePlayProtocol.PAD_R3
                    KeyEvent.KEYCODE_BUTTON_START -> RemotePlayProtocol.PAD_START
                    // Many pads send Back for their Select/View button.
                    KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> RemotePlayProtocol.PAD_SELECT
                    KeyEvent.KEYCODE_DPAD_UP -> RemotePlayProtocol.PAD_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> RemotePlayProtocol.PAD_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> RemotePlayProtocol.PAD_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> RemotePlayProtocol.PAD_RIGHT
                    else -> return false
                }
                keyButtons = if (down) keyButtons or bit else keyButtons and bit.inv()
            }
        }
        publish()
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false

        val device = event.device
        fun axis(id: Int) = event.getAxisValue(id)
        fun has(id: Int) = device?.getMotionRange(id, event.source) != null

        val (x, y) = shape(axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
        // Most pads put the right stick on Z/RZ; some use RX/RY.
        val rightOnZ = has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)
        val (zx, zy) = shape(
            axis(if (rightOnZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX),
            axis(if (rightOnZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY),
        )
        lx = RemotePlayProtocol.axisByte(x)
        ly = RemotePlayProtocol.axisByte(y)
        rx = RemotePlayProtocol.axisByte(zx)
        ry = RemotePlayProtocol.axisByte(zy)

        l2Axis = (max(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)) * 255f).roundToInt().coerceIn(0, 255)
        r2Axis = (max(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)) * 255f).roundToInt().coerceIn(0, 255)

        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        hatButtons = (if (hatX < -0.5f) RemotePlayProtocol.PAD_LEFT else 0) or
            (if (hatX > 0.5f) RemotePlayProtocol.PAD_RIGHT else 0) or
            (if (hatY < -0.5f) RemotePlayProtocol.PAD_UP else 0) or
            (if (hatY > 0.5f) RemotePlayProtocol.PAD_DOWN else 0)

        publish()
        return true
    }

    /** Drops everything held, so leaving or a prompt never strands player 2 mid-press. */
    fun releaseAll() {
        keyButtons = 0
        hatButtons = 0
        lx = 128; ly = 128; rx = 128; ry = 128
        l2Axis = 0; r2Axis = 0
        l2Key = false; r2Key = false
        publish()
    }

    /**
     * Radial deadzone, then expanded to the square like the host's own sticks (Square gate, on by
     * default): a full diagonal reaches the end of both axes, as a DualShock's does.
     */
    private fun shape(x: Float, y: Float): Pair<Float, Float> {
        val magnitude = hypot(x, y)
        if (magnitude < DEADZONE) return 0f to 0f
        val out = ((magnitude - DEADZONE) / (1f - DEADZONE)).coerceAtMost(1f)
        val scale = out / max(abs(x), abs(y))
        return (x * scale).coerceIn(-1f, 1f) to (y * scale).coerceIn(-1f, 1f)
    }

    private fun publish() {
        val pad = RemotePlayProtocol.PadState(
            buttons = keyButtons or hatButtons,
            lx = lx, ly = ly, rx = rx, ry = ry,
            l2 = max(l2Axis, if (l2Key) 255 else 0),
            r2 = max(r2Axis, if (r2Key) 255 else 0),
        )
        if (pad != last) {
            last = pad
            send(pad)
        }
    }

    private companion object {
        const val DEADZONE = 0.1f
    }
}

/** The viewer's connection: signaling, the peer, audio playback and the pad. Main thread. */
internal class ViewerSession(
    private val context: Context,
    private val mode: ViewerMode,
    private val renderer: SurfaceViewRenderer,
    private val onReply: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onEnded: (String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = AudioOut()

    @Volatile
    private var link: SignalLink? = null
    private var rtc: RemotePlayRtc.Factory? = null
    private var peer: PeerConnection? = null
    private var gather: GatherWait? = null

    @Volatile
    private var inputChannel: DataChannel? = null
    private var audioChannel: DataChannel? = null
    private var pad = RemotePlayProtocol.PadState()
    private var seq = 0
    private var connectedOnce = false
    private var ended = false

    // The pad goes out on every change and also every 50 ms, which fixes a lost packet within
    // one beat and tells the host the viewer is still there.
    private val heartbeat = object : Runnable {
        override fun run() {
            if (ended) return
            sendNow()
            main.postDelayed(this, 50)
        }
    }

    private val connectTimeout = Runnable {
        if (connectedOnce) return@Runnable
        end(I18n.get(if (mode is ViewerMode.Invite) "remoteplay.timeout.viewer" else "remoteplay.error.connect"))
    }

    fun start() {
        when (mode) {
            is ViewerMode.Wifi -> connectWifi(mode.host, mode.port)
            is ViewerMode.Invite -> {
                if (mode.offer.optInt("v") != RemotePlayProtocol.VERSION) {
                    end(I18n.get("remoteplay.bye.version"))
                    return
                }
                link = CodeLink { code -> main.post { if (!ended) onReply(code) } }
                answer(mode.offer.optString("sdp"))
                main.postDelayed(connectTimeout, INVITE_CONNECT_TIMEOUT_MS)
            }
        }
    }

    private fun connectWifi(host: String, port: Int) {
        thread(name = "rp-connect", isDaemon = true) {
            val connection = try {
                SignalingConnection.connect(
                    host, port,
                    onMessage = { message -> main.post { onSignal(message) } },
                    // Once the stream is up the link has done its job.
                    onClosed = { main.post { if (!connectedOnce) end(I18n.get("remoteplay.viewer.lost")) } },
                )
            } catch (e: IOException) {
                Log.w(TAG, "connect to $host:$port failed", e)
                main.post { end(I18n.get("remoteplay.error.connect")) }
                return@thread
            }
            main.post {
                if (ended) {
                    connection.close()
                    return@post
                }
                link = TcpLink(connection)
                connection.start()
                connection.send(
                    JSONObject()
                        .put("t", "hello")
                        .put("v", RemotePlayProtocol.VERSION)
                        .put("name", Build.MODEL),
                )
                main.postDelayed(connectTimeout, WIFI_CONNECT_TIMEOUT_MS)
            }
        }
    }

    fun sendPad(state: RemotePlayProtocol.PadState) {
        pad = state
        sendNow()
    }

    private fun sendNow() {
        val channel = inputChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(RemotePlayProtocol.encodePad(seq++, pad), true))
    }

    private fun onSignal(message: JSONObject) {
        if (ended) return
        when (message.optString("t")) {
            "offer" -> answer(message.optString("sdp"))
            "bye" -> end(byeMessage(message.optString("reason")))
        }
    }

    /** Answers the host's offer; the answer goes out once complete, candidates included. */
    private fun answer(offer: String) {
        if (peer != null || ended) return
        val factory = RemotePlayRtc.createFactory(context).also { rtc = it }.factory

        val wait = GatherWait(main, 4_000) {
            val description = peer?.localDescription ?: return@GatherWait
            link?.send(
                RemotePlayRtc.sdpMessage("answer", description)
                    .put("v", RemotePlayProtocol.VERSION)
                    .put("name", Build.MODEL),
            )
        }
        gather = wait

        val pc = factory.createPeerConnection(RemotePlayRtc.configuration(), object : PeerObserverAdapter() {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) wait.gatheringComplete()
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.addSink(renderer)
            }

            override fun onDataChannel(channel: DataChannel) {
                when (channel.label()) {
                    RemotePlayProtocol.CHANNEL_INPUT -> inputChannel = channel
                    RemotePlayProtocol.CHANNEL_AUDIO -> {
                        channel.registerObserver(MessageObserver { buffer ->
                            if (RemotePlayProtocol.isBye(buffer.data)) {
                                main.post { end(I18n.get("remoteplay.bye.stopped")) }
                            } else {
                                RemotePlayProtocol.decodeAudio(buffer.data)?.let(audio::enqueue)
                            }
                        })
                        main.post { audioChannel = channel }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                main.post { onConnectionState(newState) }
            }
        })
        if (pc == null) {
            end(I18n.get("remoteplay.viewer.lost"))
            return
        }
        peer = pc

        val failed: (String) -> Unit = { error ->
            Log.w(TAG, "viewer negotiation failed: $error")
            main.post { end(I18n.get("remoteplay.viewer.lost")) }
        }
        pc.setRemoteDescription(object : SdpAdapter(failed) {
            override fun onSetSuccess() {
                pc.createAnswer(object : SdpAdapter(failed) {
                    override fun onCreateSuccess(description: SessionDescription) {
                        pc.setLocalDescription(object : SdpAdapter(failed) {
                            override fun onSetSuccess() = wait.localDescriptionSet()
                        }, description)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offer))
    }

    private fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        if (ended) return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                if (!connectedOnce) {
                    connectedOnce = true
                    main.removeCallbacks(connectTimeout)
                    onConnected()
                    main.removeCallbacks(heartbeat)
                    main.post(heartbeat)
                }
            }
            // Before the first connection a failure can still recover: online, the host has not
            // necessarily pasted the reply yet. The connect timeout decides. After it, failed means gone.
            PeerConnection.PeerConnectionState.FAILED -> if (connectedOnce) end(I18n.get("remoteplay.viewer.lost"))
            PeerConnection.PeerConnectionState.CLOSED -> end(I18n.get("remoteplay.viewer.lost"))
            else -> Unit
        }
    }

    private fun byeMessage(reason: String): String = when (reason) {
        "busy" -> I18n.get("remoteplay.bye.busy")
        "stopped" -> I18n.get("remoteplay.bye.stopped")
        "version" -> I18n.get("remoteplay.bye.version")
        else -> I18n.get("remoteplay.bye.ended")
    }

    /** Idempotent. [message] is shown to the player; null when they left on purpose. */
    fun end(message: String?) {
        if (ended) return
        ended = true
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(connectTimeout)
        gather?.cancel()

        // A few times, since the channel drops what it cannot deliver.
        inputChannel?.takeIf { it.state() == DataChannel.State.OPEN }?.let { channel ->
            repeat(3) { channel.send(DataChannel.Buffer(RemotePlayProtocol.encodeBye(), true)) }
        }
        link?.close(RemotePlayRtc.bye("left"))
        audio.stop()

        runCatching { peer?.close() }
        runCatching { audioChannel?.unregisterObserver() }
        runCatching { audioChannel?.dispose() }
        runCatching { inputChannel?.dispose() }
        runCatching { peer?.dispose() }
        rtc?.dispose()
        peer = null
        inputChannel = null
        audioChannel = null
        onEnded(message)
    }
}

/**
 * Plays the host's audio packets. Holds about 40 ms before starting so small network wobbles do
 * not underrun, and drops the oldest audio beyond about 120 ms so the sound never drifts behind
 * the picture.
 */
internal class AudioOut {
    private val queue = LinkedBlockingDeque<ByteArray>()
    private val queuedBytes = AtomicInteger(0)

    @Volatile
    private var running = false
    private var worker: Thread? = null

    @Synchronized
    fun enqueue(pcm: ByteArray) {
        if (!running) {
            running = true
            worker = thread(name = "rp-audio-out", isDaemon = true) { play() }
        }
        queue.offerLast(pcm)
        queuedBytes.addAndGet(pcm.size)
        while (queuedBytes.get() > MAX_QUEUED_BYTES) {
            val dropped = queue.pollFirst() ?: break
            queuedBytes.addAndGet(-dropped.size)
        }
    }

    fun stop() {
        running = false
        worker?.join(300)
        worker = null
        queue.clear()
        queuedBytes.set(0)
    }

    private fun play() {
        val minimum = AudioTrack.getMinBufferSize(
            RemotePlayProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RemotePlayProtocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimum, START_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrElse {
            Log.w(TAG, "no audio output", it)
            return
        }

        try {
            var primed = false
            track.play()
            while (running) {
                if (!primed) {
                    if (queuedBytes.get() < START_BYTES) {
                        Thread.sleep(5)
                        continue
                    }
                    primed = true
                }
                val chunk = queue.pollFirst(100, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // Ran dry: wait for a fresh cushion instead of stuttering chunk by chunk.
                    primed = false
                    continue
                }
                queuedBytes.addAndGet(-chunk.size)
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val BYTES_PER_MS = RemotePlayProtocol.SAMPLE_RATE * 4 / 1000
        const val START_BYTES = BYTES_PER_MS * 40
        const val MAX_QUEUED_BYTES = BYTES_PER_MS * 120
    }
}
