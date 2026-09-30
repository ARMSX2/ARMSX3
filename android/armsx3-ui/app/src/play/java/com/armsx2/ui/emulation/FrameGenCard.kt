package com.armsx2.ui.emulation

import androidx.compose.runtime.Composable
import com.armsx2.config.Settings

/** Frame generation is not in the play build, so the pause menu has no card for it. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun FrameGenCard(settings: Settings, viewModel: EmulationMenuViewModel) = Unit
