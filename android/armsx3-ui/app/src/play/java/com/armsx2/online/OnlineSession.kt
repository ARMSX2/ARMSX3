package com.armsx2.online

import android.content.Context

/**
 * No-op in place of the GitHub flavor's background keep-alive for online games.
 *
 * That one is a foreground service, and the play bundle declares none (see the main
 * AndroidManifest): one there needs a Play Console declaration first. Without it a session that
 * drops while the app is in the background still signs in again by itself (rpcn_client), but a
 * lobby open at the time is lost.
 */
object OnlineSession {
    fun keepAlive(context: Context) = Unit

    fun release(context: Context) = Unit
}
