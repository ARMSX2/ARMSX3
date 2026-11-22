package com.armsx2.i18n

/** ZeroFG frame generation's strings, in every build (it needs nothing imported). [EN] merges them. */
internal val ZEROFG_STRINGS: Map<String, String> = mapOf(
    "perf.zerofg.title" to "ZeroFG Frame Generation",
    "perf.zerofg.label" to "Frames per game frame",
    "perf.zerofg.off" to "Off",
    "perf.zerofg.description" to "Mobile-native frame generation (ZeroFG): synthetic frames between the ones the game draws, x2, x3 or x4, capped by what the display refresh can show (x4 from a 30 fps game needs 120 Hz). It runs on its own Vulkan device, so it never makes the game wait on the display, and it needs nothing imported. While it is on, the experimental frame generation below stays off.\n\nIt can be switched on, off and between x2, x3 and x4 during a game, but only if the game was started with it on. If it was started with Off, restart the game to use it.",
    "perf.zerofg.note" to "ZeroFG can be switched during a game only if the game was started with it on. If it was started with Off, restart the game to use it.",
)
