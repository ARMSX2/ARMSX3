#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""RpcnSession.swift, run on the Mac host with RpcnState.swift, gives the running game's RPCN menu line, invites, boot
banners, return and drop watches exactly as pinned; static checks pin the game screen's poll, its one background task
and no background mode. It does not show a real session, invite, drop or background trip."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
SESSION = APP / "Online" / "RpcnSession.swift"
STATE = APP / "Online" / "RpcnState.swift"
SCREEN = APP / "Game" / "GameScreenView.swift"
MENU = APP / "Game" / "QuickMenuView.swift"

RQL_LINE = "RQL signed=online signing=signing failed=offline dropped=offline notrpcn=none"
RQB_LINE = "RQB nosecret=1 password=1 reach=1 elsewhere=1 other=1 once=1 simulated=1"
BOOT_SENTENCES = {
    "NOSECRET": "Online play is on, but you're not signed in to RPCN. Sign in from Settings › RPCN, then start the game again.",
    "BADPASSWORD": "RPCN didn't accept your password, so this game is offline. Sign in again from Settings › RPCN.",
    "REACH": "Couldn't reach RPCN, so this game is offline. Check the internet connection, then start the game again.",
    "ELSEWHERE": "Your RPCN account is signed in on another device, so this game is offline.",
    "OTHER": "Couldn't sign in to RPCN (code 12), so this game is offline.",
    "SIMULATED": ("This game needs RPCN to play online, and PSN status is set to Simulated. Turn on Online Play in "
                  "Settings › RPCN, then start the game again."),
}
RQI_LINE = "RQI new=1 seen=0 one=1 many=1"
FRIENDS = APP / "Online" / "RpcnFriends.swift"
PLIST = ROOT / "ios" / "app" / "Resources" / "Info.plist"
RRW_LINE = "RRW kept=kept resigned=resigned offline=offline window=15"
# The drop banner's wording, verbatim.
RDW_LINE = "RDW drop=1 once=1 duringreturn=0 quickresign=0"
DROPPED = "RPCN disconnected. Signing in again."
# The session-closed banner and its offline variant, verbatim.
RESIGNED = "You were away too long, so RPCN closed your session. You're signed in again."
OFFLINE = ("You were away too long, so RPCN closed your session, and signing in again didn't work. This game is "
           "offline.")

# The quick menu line, verbatim.
SIGNING = "Signing in to RPCN…"

DRIVER = r'''import Foundation

func status(_ session: Int, psn: Int = 2, relogins: Int = 0, name: String = "") -> RpcnState.Status {
    RpcnState.Status(configured: true, npid: "probeuser", client: session != 0, connected: session == 2,
                     signedIn: session == 2, state: session == 3 ? 5 : 0, session: session, onlineName: name,
                     bootPsn: psn, relogins: relogins, simulatedNeedsRpcn: false)
}
func word(_ line: String?) -> String {
    switch line {
    case nil: return "none"
    case "Online as probeuser"?: return "online"
    case "%SIGNING%"?: return "signing"
    case "Offline"?: return "offline"
    default: return "other"
    }
}
let signed = word(RpcnSession.quickMenuLine(status: status(2, name: "probeuser")))
let signing = word(RpcnSession.quickMenuLine(status: status(1)))
let failed = word(RpcnSession.quickMenuLine(status: status(3)))
let dropped = word(RpcnSession.quickMenuLine(status: status(0, relogins: 1)))
let notrpcn = word(RpcnSession.quickMenuLine(status: status(2, psn: 0, name: "probeuser")))
print("RQL signed=\(signed) signing=\(signing) failed=\(failed) dropped=\(dropped) notrpcn=\(notrpcn)")

// A synthetic invite, as +rpcnInvitesWithCompletion: hands it back.
let invites = RpcnSession.invites("""
[{"id":7,"from":"frienduser","subject":"Probe subject","body":"Probe message"}]
""".data(using: .utf8)!)
let fresh = RpcnSession.inviteChanges(previous: [], current: invites).count
let seen = RpcnSession.inviteChanges(previous: [7], current: invites).count
let one = RpcnSession.invitesValue(count: 1) == "1 invite" ? 1 : 0
let many = RpcnSession.invitesValue(count: 3) == "3 invites" && RpcnSession.invitesValue(count: 0) == nil ? 1 : 0
// Synthetic boots.
func boot(_ session: Int, state: Int = 0, configured: Bool = true, psn: Int = 2, simulated: Bool = false) -> RpcnState.Status {
    RpcnState.Status(configured: configured, npid: "probeuser", client: session != 0, connected: false,
                     signedIn: session == 2, state: state, session: session, onlineName: "", bootPsn: psn, relogins: 0,
                     simulatedNeedsRpcn: simulated)
}
func says(_ status: RpcnState.Status, _ text: String, shown: Bool = false) -> Int {
    RpcnSession.bootBanner(status: status, alreadyShown: shown) == text ? 1 : 0
}
let nosecret = says(boot(0, configured: false), "%NOSECRET%")
let password = says(boot(3, state: 9), "%BADPASSWORD%")
let reach = says(boot(3, state: 3), "%REACH%") * says(boot(3, state: 5), "%REACH%")
let elsewhere = says(boot(3, state: 7), "%ELSEWHERE%")
let other = says(boot(3, state: 12), "%OTHER%")
let once = RpcnSession.bootBanner(status: boot(3, state: 5), alreadyShown: true) == nil
    && RpcnSession.bootBanner(status: boot(2), alreadyShown: false) == nil ? 1 : 0
let simulated = says(boot(0, psn: 1, simulated: true), "%SIMULATED%")
print("RQB nosecret=\(nosecret) password=\(password) reach=\(reach) elsewhere=\(elsewhere) other=\(other) once=\(once) simulated=\(simulated)")
print("RQI new=\(invites.count == 1 ? fresh : 0) seen=\(seen) one=\(one) many=\(many)")

// A return watch fed samples 2 s apart; the outcome's word, `none`, or `twice` if it answers again.
func outcome(_ samples: [RpcnState.Status], banner: String? = nil) -> String {
    var watch = RpcnSession.ReturnWatch(reloginsAtBackground: 0)
    for (at, sample) in samples.enumerated() {
        guard let result = watch.feed(sample, elapsed: TimeInterval(at * 2)) else { continue }
        if watch.feed(sample, elapsed: TimeInterval(at * 2 + 2)) != nil { return "twice" }
        return RpcnSession.returnBanner(result) == banner ? result.rawValue : "wrongbanner"
    }
    return "none"
}
let kept = outcome(Array(repeating: status(2), count: 7)) == "none" ? outcome(Array(repeating: status(2), count: 9)) : "early"
let resigned = outcome([status(2), status(2, relogins: 1)], banner: "%RESIGNED%")
let offline = outcome([boot(3, state: 5)]) == "none" ? outcome([boot(3, state: 9)], banner: "%OFFLINE%") : "early"
print("RRW kept=\(kept) resigned=\(resigned) offline=\(offline) window=\(Int(RpcnSession.ReturnWatch.window))")

// How many drop banners a sequence of (status, returning) samples puts up.
func drops(_ samples: [(RpcnState.Status, Bool)]) -> Int {
    var watch = RpcnSession.DropWatch()
    var shown = 0
    for (sample, returning) in samples where watch.feed(sample, returning: returning) { shown += 1 }
    return shown
}
let drop = RpcnSession.dropBanner == "%DROPPED%" ? drops([(status(2), false), (status(0), false)]) : 0
let dropOnce = drops([(status(2), false), (status(1), false), (status(1), false), (status(0), false)]) == 1 ? 1 : 0
let duringReturn = drops([(status(2), false), (status(1), true), (status(1), false)])
let quickResign = drops([(status(2), false), (status(2, relogins: 1), false), (status(2, relogins: 1), false)])
print("RDW drop=\(drop) once=\(dropOnce) duringreturn=\(duringReturn) quickresign=\(quickResign)")
'''.replace("%SIGNING%", SIGNING).replace("%RESIGNED%", RESIGNED).replace("%OFFLINE%", OFFLINE).replace("%DROPPED%", DROPPED)
for _key, _text in BOOT_SENTENCES.items():
    DRIVER = DRIVER.replace("%{0}%".format(_key), _text)


def read(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. FAILURE, not skip.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. FAILURE, not skip.".format(path))
    return text


def live(source):
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", source)


def brace_body(text, at):
    start = text.find("{", at)
    depth = 0
    for index in range(start, len(text)) if start >= 0 else ():
        depth += {"{": 1, "}": -1}.get(text[index], 0)
        if depth == 0:
            return text[start:index + 1]
    return ""


def session_problems(session, screen, menu, friends, plist):
    problems = []
    imports = re.findall(r"^\s*import\s+(\w+)", live(session), re.MULTILINE)
    if imports != ["Foundation"]:
        problems.append("foundation: RpcnSession.swift imports {0}, not Foundation alone".format(imports))

    code = live(screen)
    at = code.find("func pollRpcn(")
    poll = brace_body(code, at) if at >= 0 else ""
    if "RpcnModel.shared.readStatus()" not in poll or "nanoseconds: 2_000_000_000" not in poll \
            or "RpcnSession.quickMenuLine(" not in code or "subtitle: onlineLine" not in live(menu):
        problems.append("poll: GameScreenView does not read the status every 2 s into RpcnSession.quickMenuLine, or the "
                        "menu header does not draw it")

    at = code.find("func pollInvites(")
    invites = brace_body(code, at) if at >= 0 else ""
    if "await pollInvites()" not in poll or "Rpcs3Bridge.rpcnInvites" not in invites \
            or "RpcnSession.inviteChanges(" not in invites \
            or not re.search(r"RpcnSession\.inviteBanner\([^\n]*seconds: 8\b", invites):
        problems.append("invites: the poll does not read the invites into an 8-second banner per new one")

    if not re.search(r"RpcnSession\.bootBanner\(status: status, alreadyShown: rpcnBootShown\)", poll) \
            or not re.search(r"rpcnBanner\(sentence, seconds: 6\)", poll):
        problems.append("boot: the poll does not put up the boot banner once for 6 s")

    # One named task, begun only while signed in, ended on expiry and on .active.
    begins = [m.start() for m in re.finditer(r'beginBackgroundTask\(withName: "armsx3-rpcn"\)', code)]
    guard_line = code[code.rfind("\n", 0, code.rfind("if ", 0, begins[0])):begins[0]] if len(begins) == 1 else ""
    expiry = brace_body(code, begins[0]) if len(begins) == 1 else ""
    at = code.find("func endRpcnBackgroundTask(")
    ender = brace_body(code, at) if at >= 0 else ""
    at = code.find("func rpcnScenePhase(")
    phase = brace_body(code, at) if at >= 0 else ""
    active = phase[phase.find("phase == .active"):] if "phase == .active" in phase else ""
    if len(begins) != 1 or "rpcnSignedIn" not in guard_line or "endRpcnBackgroundTask()" not in expiry \
            or "UIApplication.shared.endBackgroundTask(" not in ender or "endRpcnBackgroundTask()" not in active \
            or "RpcnSession.ReturnWatch(" not in active:
        problems.append("bgtask: GameScreenView does not begin one armsx3-rpcn task only while signed in, or does not end "
                        "it on expiry and on .active ({0} begun)".format(len(begins)))
    if not re.search(r"RpcnSession\.returnBanner\([^\n]*\{ rpcnBanner\(sentence, seconds: 6\) \}", poll):
        problems.append("return: the poll does not put up the return banner for 6 s")
    # A drop in the foreground, announced once per episode.
    if not re.search(r"rpcnDrop\.feed\(status, returning: returning\)", poll) \
            or not re.search(r"rpcnBanner\(RpcnSession\.dropBanner, seconds: 6\)", poll):
        problems.append("drop: the poll does not feed the drop watch into a 6-second banner")
    # No background mode keeps the connection.
    if "UIBackgroundModes" in plist:
        problems.append("nobgmode: ios/app/Resources/Info.plist declares UIBackgroundModes")
    return problems


class TheRealFilesHold(unittest.TestCase):
    # No docstrings, so `unittest -v` prints `... ok` on the method's own line.
    def test_the_real_files_have_no_problem(self):
        self.assertEqual([], session_problems(read(SESSION), read(SCREEN), read(MENU), read(FRIENDS), read(PLIST)))

    def test_an_empty_input_raises(self):
        with tempfile.NamedTemporaryFile("w", suffix=".swift") as empty:
            with self.assertRaises(AssertionError):
                read(Path(empty.name))


class EachCheckCanFail(unittest.TestCase):
    def edited(self, text, old, new):
        self.assertIn(old, text)
        changed = text.replace(old, new, 1)
        self.assertNotEqual(text, changed)
        return changed

    def fails_with(self, prefix, session=None, screen=None, menu=None, friends=None, plist=None):
        found = session_problems(session if session is not None else read(SESSION),
                                 screen if screen is not None else read(SCREEN),
                                 menu if menu is not None else read(MENU),
                                 friends if friends is not None else read(FRIENDS),
                                 plist if plist is not None else read(PLIST))
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), found)

    def test_foundation_a_second_import_fails(self):
        self.fails_with("foundation", session=self.edited(read(SESSION), "import Foundation", "import Foundation\nimport SwiftUI"))

    def test_poll_the_header_drops_the_line_fails(self):
        self.fails_with("poll", menu=self.edited(read(MENU), "subtitle: onlineLine", "subtitle: nil"))

    def test_boot_a_banner_every_poll_fails(self):
        self.fails_with("boot", screen=self.edited(read(SCREEN), "alreadyShown: rpcnBootShown", "alreadyShown: false"))

    def test_invites_the_change_call_dropped_fails(self):
        self.fails_with("invites", screen=self.edited(read(SCREEN), "RpcnSession.inviteChanges(", "RpcnSession.inviteList("))


    def test_bgtask_no_end_on_expiry_fails(self):
        self.fails_with("bgtask", screen=self.edited(read(SCREEN), "MainActor.assumeIsolated { Self.endRpcnBackgroundTask() }",
                                                     "MainActor.assumeIsolated { }"))

    def test_return_a_banner_without_seconds_fails(self):
        self.fails_with("return", screen=self.edited(read(SCREEN), "{ rpcnBanner(sentence, seconds: 6) }",
                                                     "{ rpcnBanner(sentence, seconds: 2) }"))

    def test_drop_a_return_ignored_fails(self):
        self.fails_with("drop", screen=self.edited(read(SCREEN), "rpcnDrop.feed(status, returning: returning)",
                                                   "rpcnDrop.feed(status, returning: false)"))

    def test_nobgmode_a_declared_background_mode_fails(self):
        self.fails_with("nobgmode", plist=self.edited(read(PLIST), "<dict>",
                                                      "<dict>\n\t<key>UIBackgroundModes</key>\n\t<array><string>voip</string></array>"))


class TheRulesComputeOnTheHost(unittest.TestCase):
    """A macOS host run of Foundation-only Swift: what the source computes, never device evidence."""

    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            cls.out = compile_and_run([STATE, SESSION], DRIVER, tmp)
        cls.lines = [line for line in cls.out.splitlines() if line[:4] in ("RQL ", "RQI ", "RQB ", "RRW ", "RDW ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_the_quick_menu_line(self):
        self.assertIn(RQL_LINE, self.lines, self.out)

    def test_the_invite_changes_and_value(self):
        self.assertIn(RQI_LINE, self.lines, self.out)

    def test_the_boot_banners(self):
        self.assertIn(RQB_LINE, self.lines, self.out)

    def test_the_return_watch(self):
        self.assertIn(RRW_LINE, self.lines, self.out)

    def test_the_drop_watch(self):
        self.assertIn(RDW_LINE, self.lines, self.out)


if __name__ == "__main__":
    unittest.main()
