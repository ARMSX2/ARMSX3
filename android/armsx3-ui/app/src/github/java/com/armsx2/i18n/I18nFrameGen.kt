package com.armsx2.i18n

/**
 * Frame generation's strings, which only the github build carries.
 *
 * They name the product the shaders come from, so the play build must not contain them at all:
 * a string in a shared map is compiled in whatever flag hides the screen that shows it. The play
 * source set has an empty map in its place.
 */
internal val FRAME_GEN_STRINGS: Map<String, String> = mapOf(
    "perf.framegen.title" to "Frame Generation (Experimental)",
    "perf.framegen.import" to "Import from Lossless Scaling\u2026",
    "perf.framegen.import.missing" to "Shaders not imported, so frame generation will not run.",
    "perf.framegen.import.ok" to "Shaders imported (%d).",
    "perf.framegen.import.working" to "Reading shaders\u2026",
    "perf.framegen.import.failed" to "Could not read shaders from that file.",
    "perf.framegen.label" to "Lossless Scaling",
    "perf.framegen.performance.label" to "Performance shaders",
    "perf.framegen.performance.description" to "Use Lossless Scaling's lighter 3.1p shaders instead of the full-quality 3.1 set. Cheaper to run and slightly softer in motion. On by default, because the quality set usually costs more than the frames it buys on a phone. Both come from the file you imported, so switching does not need another import.\n\nTakes effect when frame generation next starts: turn it off and on again, or restart the game.",
    "perf.framegen.flowScale.label" to "Motion detail",
    "perf.framegen.targetRate.label" to "Target refresh rate",
    "perf.framegen.targetRate.description" to "Generate as many frames as it takes to hold this rate, instead of a fixed multiplier. Steadies the picture when the game's own frame rate moves. Off uses the multiplier above.",
    "perf.framegen.flowScale.description" to "How finely motion is measured between frames, as a percentage of full resolution. Lower is faster and blurrier around moving edges. Drop this before dropping the multiplier if frame generation is costing more than it gives.\n\nTakes effect when frame generation next starts.",
    "perf.framegen.off" to "Off",
    "perf.framegen.x2" to "x2",
    "perf.framegen.x3" to "x3",
    "perf.framegen.x4" to "x4",
    "perf.framegen.description" to "EXPERIMENTAL. Insert generated frames between the ones the game actually draws. Costs GPU time and adds latency, so it helps when the CPU is the limit and hurts when the GPU already is.\n\nIt works best from a steady framerate. Interpolating a game that is already struggling tends to look worse rather than better: generated frames land at the wrong moment when the real interval keeps changing, which reads as judder. A locked 25 usually looks better than a wandering 28.\n\nOn-screen text shimmers or flickers while this is on: the overlay and the game\u0027s own menus get interpolated along with everything else, and fine text is what that looks worst on. That is how frame generation behaves, not a fault. Turning it off restores steady text. Switching it on or off during a game also pauses for a few seconds while the shaders are prepared.\n\nThis does nothing until you import Lossless.dll below. It is part of Lossless Scaling on Steam; you need your own copy, and nothing is bundled or downloaded. On Windows the file sits in steamapps\\common\\Lossless Scaling\\Lossless.dll; copy it to your device and pick it with the button below. Only the shaders are kept, and your copy of the file is deleted afterwards.",
)
