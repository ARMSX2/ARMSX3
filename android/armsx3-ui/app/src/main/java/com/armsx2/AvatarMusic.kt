package com.armsx2

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music for the RPCN tab's avatar picker, "Echoes of the Past".
 *
 * It takes over from whichever menu music is playing, the library's ([LibraryMusic]) or the pause
 * menu's ([PauseMusic]), with the blend music changes between screens use: this track eases in over
 * 2.5 s while the other plays on at full, then the other fades over 3 s and waits, paused where it
 * was. Leaving the picker plays it backwards: the other comes back in over 2.5 s under this one, then
 * this fades over 3 s. Where the menus are silent (their music switched off, or making way for
 * another app's audio) this stays silent too, and it plays at the volume set for the music it takes
 * over from.
 *
 * No audio focus of its own, as with PauseMusic: it only ever plays in place of a track that already
 * settled that, and asking again would make the app a second focus client fighting the first.
 */
object AvatarMusic {
    private const val TAG = "AvatarMusic"
    private const val InMs = 2500L
    private const val OutMs = 3000L
    private const val StepMs = 40L

    private enum class Under { Library, Pause }

    private var player: MediaPlayer? = null
    private var under: Under? = null

    /** This track's share of the volume, and the one it took over from, as last set. */
    private var level = 0f
    private var underShare = 1f

    /** The picker is up. */
    private var open = false

    @Volatile private var foreground = true

    // Main.immediate, so the volumes are set on the thread the players live on
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null

    /** The picker opened. */
    fun enter(context: Context) {
        open = true
        if (under == null) {
            under = when {
                PauseMusic.isPlaying() -> Under.Pause
                LibraryMusic.isPlaying() -> Under.Library
                else -> return
            }
            underShare = 1f
        }
        val p = player ?: create(context.applicationContext) ?: run {
            under = null
            return
        }
        if (foreground) runCatching { if (!p.isPlaying) p.start() }

        job?.cancel()
        job = scope.launch {
            ramp(level, 1f, InMs, ::applyLevel)
            ramp(underShare, 0f, OutMs, ::applyUnder)
        }
    }

    /** The picker closed. */
    fun leave() {
        open = false
        if (under == null) {
            release()
            return
        }

        job?.cancel()
        job = scope.launch {
            ramp(underShare, 1f, InMs, ::applyUnder)
            ramp(level, 0f, OutMs, ::applyLevel)
            release()
        }
    }

    /**
     * The activity's foreground state, from onResume and onPause. Away from the app the ramps stop
     * where they were heading, so nothing changes volume behind it, and this track pauses.
     */
    fun setForeground(fg: Boolean) {
        foreground = fg
        if (!fg) {
            job?.cancel()
            job = null
            runCatching { player?.takeIf { it.isPlaying }?.pause() }
            if (open) {
                applyLevel(1f)
                applyUnder(0f)
            } else {
                release()
            }
        } else if (open) {
            runCatching { player?.takeIf { !it.isPlaying }?.start() }
        }
    }

    private fun volume(): Float = when (under) {
        Under.Pause -> PauseMusic.volume()
        Under.Library -> LibraryMusic.volume()
        null -> 0f
    }

    private fun applyLevel(value: Float) {
        level = value
        val v = volume() * level
        runCatching { player?.setVolume(v, v) }
    }

    private fun applyUnder(value: Float) {
        underShare = value
        when (under) {
            Under.Pause -> PauseMusic.setShare(value)
            Under.Library -> LibraryMusic.setShare(value)
            null -> Unit
        }
    }

    /** From where a level is to [to], taking [ms] for the whole way and less for part of it. */
    private suspend fun ramp(from: Float, to: Float, ms: Long, apply: (Float) -> Unit) {
        val steps = (abs(to - from) * ms / StepMs).toInt()
        for (i in 1..steps) {
            apply(from + (to - from) * i / steps)
            delay(StepMs)
        }
        apply(to)
    }

    private fun create(context: Context): MediaPlayer? =
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.avatar_music}"))
                isLooping = true
                setVolume(0f, 0f)
                prepare()
            }
        }.onFailure { Log.w(TAG, "start failed", it) }.getOrNull()?.also {
            player = it
            level = 0f
        }

    /** Done: this track goes, and the one it took over from has its whole share back. */
    private fun release() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        level = 0f
        applyUnder(1f)
        under = null
    }
}
