package com.armsx2.ui.settingshub

import com.armsx2.navigation.SettingsCategory

/** Frame generation's search rows. Kept out of the generated index: the section is github-only. */
internal val FRAME_GEN_SEARCH_ENTRIES: List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry("perf.framegen.title", true, SettingsCategory.Performance),
    SettingsSearchEntry("perf.framegen.label", true, SettingsCategory.Performance),
    SettingsSearchEntry("perf.framegen.performance.label", true, SettingsCategory.Performance),
    SettingsSearchEntry("perf.framegen.flowScale.label", true, SettingsCategory.Performance),
    SettingsSearchEntry("perf.framegen.targetRate.label", true, SettingsCategory.Performance),
)
