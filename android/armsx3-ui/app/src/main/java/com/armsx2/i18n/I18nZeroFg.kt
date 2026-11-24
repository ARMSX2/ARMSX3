package com.armsx2.i18n

/** ZeroFG frame generation's strings, in every build (it needs nothing imported). [EN] merges them;
 *  the other languages have them in assets/i18n. */
internal val ZEROFG_STRINGS: Map<String, String> = mapOf(
    "perf.zerofg.title" to "ZeroFG Frame Generation",
    "perf.zerofg.label" to "Frames per game frame",
    "perf.zerofg.off" to "Off",
    "perf.zerofg.description" to "Switch it on before starting a game. Switched on during a game that started without it, it only starts once the game restarts.\n\nAdds frames between the ones the game draws: x2, x3 or x4, limited by the display refresh. While it is on, the experimental frame generation below stays off.",
    "perf.zerofg.note" to "Switch it on before starting a game, or restart the game after switching it on.",
    "perf.zerofg.restart" to "ZeroFG starts when this game restarts.",
    "perf.zerofg.restartAsk" to "ZeroFG starts when the game restarts. Restart the game now?",
)
