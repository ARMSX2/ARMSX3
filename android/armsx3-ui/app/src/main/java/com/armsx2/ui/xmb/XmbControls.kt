package com.armsx2.ui.xmb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.armsx2.i18n.str
import com.armsx2.input.ControllerMappings
import com.armsx2.input.PadRouter

/**
 * Controller Mapping as XMB lists, at home and over a game: whose controls are being set (P1 to
 * P7), the buttons and the SIXAXIS motion each with what it is bound to (Cross listens for the
 * button to use, Triangle clears it), the mapping profiles, the hotkeys, and the switches: rumble,
 * the D-pad as the left stick, every controller as player 1, and which player each controller is.
 * It is the Controls screen's own view model, so it binds and saves exactly as that screen does.
 */
@Composable
fun xmbControllerMappingNode(): XmbNode {
    val vm: com.armsx2.ui.controls.ControllerManagerViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(key = "xmb.controls")
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", "tex_pd", "🎮")
    val players = (0 until PadRouter.MAX_PADS).map { str("pad.player${it + 1}") }
    val title = str("pad.controllerMapping")
    val playerTitle = str("pad.editing.label")
    val playerDesc = str("pad.editing.description")
    val buttonsTitle = str("pad.section.buttonMapping")
    val buttonsHelp = str("pad.instruction.tapThenPress")
    val motionTitle = str("pad.section.motion")
    val motionHelp = str("pad.motion.help")
    val profilesTitle = str("pad.section.padProfiles")
    val profilesHelp = str("pad.padProfiles.controlsInfo")
    val hotkeysTitle = str("tab.hotkeys")
    val hotkeysHelp = str("hotkeys.help")
    val rumble = str("pad.rumble.label")
    val rumbleDesc = str("pad.rumble.description")
    val dpad = str("pad.dpadAsLeftStick.label")
    val dpadDesc = str("pad.dpadAsLeftStick.description")
    val allToOne = str("pad.allToPlayer1.label")
    val allToOneDesc = str("pad.allToPlayer1.description")
    val controllers = str("pad.section.controllers")
    val assignHelp = str("pad.assign.help")
    val auto = str("pad.assign.auto")
    val press = str("pad.pressButton")
    val clear = str("pad.action.clear")
    val none = str("pad.padProfiles.none")
    val saveAs = str("pad.padProfiles.saveAs")
    val saveNew = str("pad.padProfiles.saveNewLabel")
    val namePlaceholder = str("pad.padProfiles.namePlaceholder")
    val capturePrompt = str("hotkeys.capturePrompt")
    val notSet = str("hotkeys.notSet")
    val hotkeyClear = str("hotkeys.clear")
    val resetWord = str("action.reset")
    val resetAsk = str("xmb.pad.resetAsk")
    val deleteWord = str("action.delete")
    val cancel = str("action.cancel")

    fun bindings(actions: List<ControllerMappings.Action>): List<XmbNode> {
        val s = vm.state.value
        return actions.map { action ->
            val capturing = s.capturingAction == action
            val bound = ControllerMappings.physicalFor(action, s.player)
            XmbNode.Action(
                "controls.bind.${action.id}",
                action.label,
                icon,
                if (capturing) press else ControllerMappings.labelForKey(bound),
                options = if (bound != android.view.KeyEvent.KEYCODE_UNKNOWN) listOf(XmbOption(clear) { vm.clear(action) }) else emptyList(),
            ) { if (capturing) vm.cancelCapture() else vm.beginCapture(action) }
        }
    }

    fun profiles(): List<XmbNode> = buildList {
        val s = vm.state.value
        s.profiles.forEach { name ->
            add(
                XmbNode.Action(
                    "controls.profile.$name",
                    name,
                    icon,
                    if (s.loadedProfile == s.player to name) players[s.player] else null,
                    options = listOf(XmbOption(deleteWord) {
                        ControllerMappings.deleteProfile(name)
                        vm.refresh()
                    }),
                ) { vm.loadProfile(name) },
            )
        }
        if (s.profiles.isEmpty()) add(XmbNode.Action("controls.profiles.none", none, icon) {})
        add(XmbNode.Action("controls.profile.save", saveAs, icon, saveNew) {
            XmbKeyboard.ask(namePlaceholder) { typed -> if (typed.isNotBlank()) vm.saveProfile(typed.trim()) }
        })
    }

    fun hotkeys(): List<XmbNode> {
        @Suppress("UNUSED_EXPRESSION") ControllerMappings.hotkeyBindTick.value
        val capturing = ControllerMappings.captureHotkey.value
        return ControllerMappings.hotkeysInDisplayOrder.filter { it.supported }.map { hk ->
            val binding = ControllerMappings.hotkeyLabel(hk)
            XmbNode.Action(
                "controls.hotkey.${hk.name}",
                hk.label,
                icon,
                when {
                    capturing == hk -> capturePrompt
                    binding.isEmpty() -> notSet
                    else -> binding
                },
                options = if (binding.isEmpty()) emptyList() else listOf(XmbOption(hotkeyClear) { vm.clearHotkey(hk) }),
            ) { vm.beginHotkeyCapture(hk) }
        }
    }

    val host: @Composable () -> Unit = {
        LaunchedEffect(Unit) { vm.refresh() }
        // A capture still listening when the list closes would take the next press anywhere.
        DisposableEffect(Unit) { onDispose { vm.cancelCapture() } }
    }

    return XmbNode.Folder("controls.mapping", title, icon, buttonsHelp, live = true, host = host) {
        val s = vm.state.value
        buildList {
            add(
                XmbNode.Setting(
                    "controls.player", playerTitle, icon,
                    XmbEditor.Choice(players, { s.player }) { vm.setPlayer(it) },
                    { players.getOrElse(s.player) { "" } },
                    description = playerDesc,
                ),
            )
            add(XmbNode.Folder("controls.buttons", buttonsTitle, icon, buttonsHelp, live = true) { bindings(ControllerMappings.buttonActions) })
            add(XmbNode.Folder("controls.motion", motionTitle, icon, motionHelp, live = true) { bindings(ControllerMappings.motionActions) })
            add(XmbNode.Folder("controls.profiles", profilesTitle, icon, profilesHelp, live = true) { profiles() })
            add(XmbNode.Folder("controls.hotkeys", hotkeysTitle, icon, hotkeysHelp, live = true) { hotkeys() })
            add(
                XmbNode.Setting(
                    "controls.rumble", rumble, icon,
                    XmbEditor.Toggle({ vm.state.value.rumble }) { vm.setRumble(it) },
                    { if (vm.state.value.rumble) on else off },
                    description = rumbleDesc,
                    changed = { !vm.state.value.rumble },
                ),
            )
            add(
                XmbNode.Setting(
                    "controls.dpad", dpad, icon,
                    XmbEditor.Toggle({ vm.state.value.dpadAsStick }) { vm.setDpadAsStick(it) },
                    { if (vm.state.value.dpadAsStick) on else off },
                    description = dpadDesc,
                    changed = { vm.state.value.dpadAsStick },
                ),
            )
            add(
                XmbNode.Setting(
                    "controls.allToOne", allToOne, icon,
                    XmbEditor.Toggle({ vm.state.value.allToPlayer1 }) { vm.setAllToPlayer1(it) },
                    { if (vm.state.value.allToPlayer1) on else off },
                    description = allToOneDesc,
                    changed = { vm.state.value.allToPlayer1 },
                ),
            )
            if (!s.allToPlayer1 && s.pads.isNotEmpty()) {
                add(
                    XmbNode.Folder("controls.pads", controllers, icon, assignHelp, live = true) {
                        val now = vm.state.value
                        now.pads.map { pad ->
                            XmbNode.Setting(
                                "controls.pad.${pad.descriptor}", pad.name, icon,
                                XmbEditor.Choice(listOf(auto) + players, { now.pins[pad.descriptor]?.plus(1) ?: 0 }) { i ->
                                    vm.setPin(pad.descriptor, if (i == 0) null else i - 1)
                                },
                                { now.pins[pad.descriptor]?.let { players.getOrNull(it) } ?: auto },
                            )
                        }
                    },
                )
            }
            add(XmbNode.Action("controls.reset", "$resetWord · ${players.getOrElse(s.player) { "" }}", icon) {
                XmbDialogs.target()?.ask(resetAsk, resetWord, cancel, focusNo = true) { vm.resetPlayer() }
            })
        }
    }
}
