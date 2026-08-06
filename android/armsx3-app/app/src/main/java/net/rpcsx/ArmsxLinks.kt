package net.rpcsx

/**
 * Canonical ARMSX3 endpoints.
 *
 * Kept in one place because these are referenced from the drawer, the About /
 * What's New screen and the updater. The repo does not exist publicly yet --
 * when it does, nothing else needs touching.
 */
object ArmsxLinks {
    /** GitHub org/repo. What's New reads its Releases feed. */
    const val REPO = "https://github.com/ARMSX2/ARMSX3"

    /** Shared with ARMSX2 -- same community. */
    const val DISCORD = "https://discord.gg/2Tynvwhc4A"

    const val WEBSITE = "https://armsx2.net/"

    /** Where "report an issue" should land. */
    const val ISSUES = "$REPO/issues"
}
