package com.armsx2.update

import androidx.compose.runtime.Composable

/**
 * No-op replacements for the in-app updater, for the Play build.
 *
 * Google Play forbids an app that updates itself from outside the store, and the rejection is on
 * the REQUEST_INSTALL_PACKAGES permission being present in the bundle -- not on whether the code
 * behind it ever runs. So the real implementation lives in src/github and this stands in its
 * place here, which keeps the permission, the FileProvider and the download-and-install code out
 * of the play bundle entirely rather than merely unreachable.
 *
 * Both call sites are already guarded by BuildConfig.IN_APP_UPDATER, which is false for this
 * flavor, so neither of these is reached. They exist so the play source set still compiles.
 */
@Composable
fun UpdaterEntry() = Unit

@Composable
fun AutoUpdateGate() = Unit
