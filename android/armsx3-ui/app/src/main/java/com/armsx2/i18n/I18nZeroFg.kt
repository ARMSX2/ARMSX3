package com.armsx2.i18n

/** ZeroFG frame generation's strings, in every build (it needs nothing imported). [EN] merges them. */
internal val ZEROFG_STRINGS: Map<String, String> = mapOf(
    "perf.zerofg.title" to "ZeroFG Frame Generation",
    "perf.zerofg.label" to "Frames per game frame",
    "perf.zerofg.off" to "Off",
    "perf.zerofg.description" to "Turn it on before starting the game: it can only be changed during a game that started with it on.\n\nAdds frames between the ones the game draws: x2, x3 or x4, limited by the display refresh. While it is on, the experimental frame generation below stays off.",
    "perf.zerofg.note" to "Turn it on before starting the game: it can only be changed during a game that started with it on.",
)
