package com.armsx2.ui.settingshub

import com.armsx2.navigation.SettingsCategory

/** In the play build, searching for frame generation finds the note that says where it is. */
internal val FRAME_GEN_SEARCH_ENTRIES: List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry("perf.framegenPlay.title", true, SettingsCategory.Performance),
)
