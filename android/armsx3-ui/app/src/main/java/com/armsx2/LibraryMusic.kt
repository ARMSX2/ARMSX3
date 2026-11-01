package com.armsx2

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import java.io.File

/**
 * Ambient background music for the game library — the "console dashboard" feel.
 *
 * DELIBERATELY LIBRARY-ONLY. It plays while no VM is running and stops the moment a
 * game boots, which is both what a PS2/Xbox dashboard does and what keeps it clear of
 * the emulator's own audio: SPU2 output goes through Oboe, and Android has been
 * observed reclaiming that stream when the VM pauses (issue #333). A second long-lived
 * stream competing over gameplay would land straight on top of that.
 *
 * Track: "Calm Ambient 1 (Synthwave 4k)" by cynicmusic (The Cynic Project), released
 * CC0 / public domain on OpenGameArt. CC0 imposes no attribution requirement, but the
 * author asks for credit and we give it — see the About screen.
 */
object LibraryMusic {
    private const val TAG = "LibraryMusic"
    private const val EnabledKey = "ui.libraryMusic"

    /** Default of 15% — the shipped 55% was reported as far too loud. Background
     *  music should sit well under the UI, not command it. User-adjustable via the
     *  slider in App settings; 0 = silent (the toggle is the real off switch). */
    private const val VolumeKey = "ui.libraryMusic.volume"
    private const val CustomNameKey = "ui.libraryMusic.customName"
    private const val QueueKey = "ui.libraryMusic.queue"
    private const val QueueIndexKey = "ui.libraryMusic.queueIndex"
    private const val DefaultVolumePercent = 15

    val enabled = mutableStateOf(true)

    /** 0..100, backs the App-settings slider. Applied live to the running player. */
    val volumePercent = mutableStateOf(DefaultVolumePercent)

    /** How much of the set volume plays, 0..1. Below 1 while a screen with music of its own takes
     *  over (AvatarMusic); at 0 the track waits paused where it was, and nothing else starts it. */
    private var share = 1f

    private fun gain(): Float = volume() * share

    /** The set volume, 0..1, without [share]: what a track taking over from this one plays at. */
    fun volume(): Float = (volumePercent.value.coerceIn(0, 100)) / 100f

    /** Turn the track down to [value] of its set volume, for AvatarMusic's handover. */
    fun setShare(value: Float) {
        share = value.coerceIn(0f, 1f)
        val p = player ?: return
        runCatching {
            p.setVolume(gain(), gain())
            if (share <= 0f) {
                if (p.isPlaying) p.pause()
            } else if (foreground && !p.isPlaying && !pausedForFocus && enabled.value &&
                MainActivityRuntime.eState.value == EmuState.STOPPED
            ) {
                p.start()
            }
        }
    }

    // True only while the activity is in the foreground, from its onResume and onPause. Screens
    // with music of their own hand the track back as they go, and they go as the app leaves: the
    // XMB's SND0 preview ends with resume(), and its boot music fades in with setShare(). Each of
    // those started the track again out of a backgrounded app, on the OS home screen or with the
    // screen off, so start(), resume() and setShare() all wait for this instead.
    @Volatile private var foreground = true

    /** The activity's foreground state. Leaving pauses the track; onResume's start() brings it back. */
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (!fg) pause()
    }

    /** Display name of the user's own track, or null when playing the bundled default.
     *  The app never ships or redistributes user tracks — this only plays a file the user
     *  chose from their own device, the same as importing a texture pack or a skin. */
    val customName = mutableStateOf<String?>(null)

    /** The imported track is copied here (extension-less; MediaPlayer sniffs the format), so
     *  playback doesn't depend on holding a SAF permission or the source file staying put. */
    private fun customFile(context: Context): File =
        File(File(context.filesDir, "librarymusic").apply { mkdirs() }, "track")

    /** A playlist playing (the XMB's Music column): its songs in order, and which one is on.
     *  Empty while one track plays on a loop. */
    val queue = mutableStateOf<List<File>>(emptyList())
    val queueIndex = androidx.compose.runtime.mutableIntStateOf(0)

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    /** True when we stopped for something temporary (a call, another app ducking us)
     *  and should resume ourselves when focus comes back — as opposed to being off. */
    private var pausedForFocus = false

    fun load() {
        enabled.value = MainActivityRuntime.prefs.getBoolean(EnabledKey, true)
        volumePercent.value = MainActivityRuntime.prefs.getInt(VolumeKey, DefaultVolumePercent)
        customName.value = MainActivityRuntime.prefs.getString(CustomNameKey, null)
        queue.value = runCatching {
            val list = org.json.JSONArray(MainActivityRuntime.prefs.getString(QueueKey, "[]"))
            (0 until list.length()).map { File(list.getString(it)) }.filter { it.isFile }
        }.getOrDefault(emptyList())
        queueIndex.intValue = MainActivityRuntime.prefs.getInt(QueueIndexKey, 0).coerceIn(0, (queue.value.size - 1).coerceAtLeast(0))
    }

    /** Play [files] in order from [start], round again after the last: a playlist. */
    fun playQueue(context: Context, files: List<File>, start: Int) {
        if (files.isEmpty()) return
        queue.value = files
        queueIndex.intValue = start.coerceIn(0, files.lastIndex)
        customName.value = files[queueIndex.intValue].name
        MainActivityRuntime.prefs.edit {
            putString(QueueKey, org.json.JSONArray(files.map { it.absolutePath }).toString())
            putInt(QueueIndexKey, queueIndex.intValue)
            putString(CustomNameKey, customName.value)
        }
        if (enabled.value) restart(context) else set(context, true)
    }

    /** The next song of the playlist, when one ends. */
    private fun next(context: Context) {
        val list = queue.value
        if (list.isEmpty()) return
        queueIndex.intValue = (queueIndex.intValue + 1) % list.size
        customName.value = list[queueIndex.intValue].name
        MainActivityRuntime.prefs.edit {
            putInt(QueueIndexKey, queueIndex.intValue)
            putString(CustomNameKey, customName.value)
        }
        restart(context)
    }

    private fun clearQueue() {
        queue.value = emptyList()
        MainActivityRuntime.prefs.edit {
            remove(QueueKey)
            remove(QueueIndexKey)
        }
    }

    /** Import a user-picked audio file as the library track, replacing the default. Copies it
     *  into app-private storage and restarts playback so the change is heard immediately.
     *  Returns true on success. */
    fun setCustomTrack(context: Context, uri: android.net.Uri, displayName: String): Boolean {
        clearQueue()
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                customFile(context).outputStream().use { ins.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!ok || customFile(context).length() == 0L) {
            customFile(context).delete()
            return false
        }
        customName.value = displayName
        MainActivityRuntime.prefs.edit { putString(CustomNameKey, displayName) }
        restart(context)
        return true
    }

    /** Drop the user track and go back to the bundled default. */
    fun clearCustomTrack(context: Context) {
        clearQueue()
        customFile(context).delete()
        customName.value = null
        MainActivityRuntime.prefs.edit { remove(CustomNameKey) }
        restart(context)
    }

    /** Stop and re-start so a track/volume change is heard now (only replays in the library).
     *  Forces past the "is another app playing?" guard: we just stopped our OWN stream, which
     *  AudioManager can still briefly report as active, and this is a user-initiated change
     *  (picking or resetting the track) so we always want it heard — without force, Reset went
     *  silent until a game boot/exit cleared the transient (KamFretoZ). */
    private fun restart(context: Context) {
        stop(context)
        start(context, force = true)
    }

    /** Set the music volume (0..100) and apply it live to a playing track. */
    fun setVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        volumePercent.value = p
        MainActivityRuntime.prefs.edit { putInt(VolumeKey, p) }
        runCatching { player?.setVolume(gain(), gain()) }
    }

    /** True while the track is actually audible — drives the cold-start retry below. */
    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun set(context: Context, value: Boolean) {
        enabled.value = value
        MainActivityRuntime.prefs.edit { putBoolean(EnabledKey, value) }
        if (value) start(context) else stop(context)
    }

    /** Always the application's AudioManager. Focus belongs to the AudioManager that asked for it,
     *  and the callers pass different contexts: the activity from the runtime, the application's
     *  from the Settings music switch and custom track. Asking through both made us a second
     *  client, so the system took focus from the first, our own listener heard AUDIOFOCUS_LOSS and
     *  released the track that had just started (found in ARMSX2). */
    private fun audioManager(context: Context): AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * True when another app is playing something the user is listening to: music, a podcast,
     * a video. Deliberately narrower than AudioManager.isMusicActive(), which is true for ANY
     * live stream on the media channel, games included: ARMSX2 left in the background with a
     * PS2 game paused keeps its silent stream open for hours, and that alone kept the library
     * silent all afternoon (Odin 3, 2026-09-23).
     *
     * The usage alone cannot tell: that stream was USAGE_GAME in one ARMSX2 build and
     * USAGE_MEDIA in another. What media apps do and emulators do not is say what they play:
     * music, a film, speech (Spotify, YouTube, podcast apps, and our own splash and pause-menu
     * tracks all set it). A raw game stream leaves the content type unknown, so it never
     * counts, and neither does our own SPU output still closing after a game exits. Apps are
     * only given the players that are live right now, so no state check is needed.
     */
    private fun otherMediaPlaying(am: AudioManager): Boolean =
        runCatching {
            am.activePlaybackConfigurations.any {
                val attributes = it.audioAttributes
                attributes.usage in MEDIA_USAGES && attributes.contentType in MEDIA_CONTENT
            }
        }.getOrElse { am.isMusicActive }

    private val MEDIA_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN)
    private val MEDIA_CONTENT = setOf(
        AudioAttributes.CONTENT_TYPE_MUSIC,
        AudioAttributes.CONTENT_TYPE_MOVIE,
        AudioAttributes.CONTENT_TYPE_SPEECH,
    )

    /**
     * Begin playing, if we should. No-ops when the setting is off, a VM is running, or
     * another app is already playing music, a podcast or a video.
     *
     * The [otherMediaPlaying] check is the difference between a nice touch and a hostile one:
     * without it, opening the app over someone's podcast or Spotify starts a second
     * stream on top of theirs. Deferring to whoever is already playing costs us nothing
     * — the user can still toggle it on explicitly.
     */
    fun start(context: Context, force: Boolean = false) {
        if (!enabled.value) return
        if (!foreground) return
        if (MainActivityRuntime.eState.value != EmuState.STOPPED) return
        if (player != null) { resume(); return }
        val am = audioManager(context)
        // Skip the "someone else is playing" deference when forced — a user-initiated track
        // change is an explicit request to hear our music now (see restart()).
        if (!force && am != null && otherMediaPlaying(am)) {
            Log.i(TAG, "another app is playing audio; not starting library music")
            return
        }
        if (am != null && !requestFocus(am)) return

        // Built by hand rather than MediaPlayer.create(): create() does setDataSource +
        // prepare internally, so attributes set afterwards land on an already-prepared
        // player and are ignored. Android routes and ducks by those attributes, so
        // getting them applied before prepare() is what makes this behave like media
        // rather than a system sound.
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                val custom = customFile(context)
                val list = queue.value
                if (list.isNotEmpty()) {
                    setDataSource(list[queueIndex.intValue.coerceIn(0, list.lastIndex)].absolutePath)
                } else if (customName.value != null && custom.length() > 0L) {
                    setDataSource(custom.absolutePath)
                } else {
                    setDataSource(
                        context,
                        android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.library_music}"),
                    )
                }
                // A playlist goes on to its next song; a single track goes round.
                isLooping = list.size <= 1
                if (list.size > 1) setOnCompletionListener { next(context) }
                setVolume(gain(), gain())
                prepare()
                // Handed over to another screen's music: ready, but quiet until it hands back
                if (share > 0f) start()
                player = this
            }
        }.onFailure { Log.w(TAG, "start failed", it) }
    }

    /** Stop and release. Called when a game boots and when the toggle goes off. */
    fun stop(context: Context) {
        pausedForFocus = false
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        audioManager(context)?.let { abandonFocus(it) }
    }

    /** Suspend without releasing — app backgrounded. */
    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun resume() {
        if (!enabled.value) return
        if (!foreground) return
        if (MainActivityRuntime.eState.value != EmuState.STOPPED) return
        if (share <= 0f) return
        runCatching { player?.takeIf { !it.isPlaying }?.start() }
    }

    // ---- audio focus ----

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent: another app took over for good. Drop the player entirely
                // rather than sitting paused forever holding a decoder.
                pausedForFocus = false
                player?.let { p ->
                    runCatching { if (p.isPlaying) p.stop() }
                    runCatching { p.release() }
                }
                player = null
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Arm self-resume on GAIN whenever we own an enabled player — not only when
                // it's live right now. onPause() may have paused it just before this fires, and
                // keying on isPlaying then left the flag false so GAIN never resumed the duck.
                if (player != null && enabled.value) { pausedForFocus = true; pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedForFocus) { pausedForFocus = false; resume() }
            }
        }
    }

    private fun requestFocus(am: AudioManager): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(focusListener) }
        }
    }
}
