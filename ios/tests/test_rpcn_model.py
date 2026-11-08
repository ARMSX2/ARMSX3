#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""RpcnState.swift, run alone on the Mac host, gives the RPCN pane's switch writes, account states, sign-in, account,
friend and avatar sentences exactly as the UI copy says; static checks pair the friends session's open and close and
pin the model's façade calls. It does not show the pane drawing these states or the core going online."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

from swiftc_host import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios" / "app"
STATE = APP / "Online" / "RpcnState.swift"
MODEL = APP / "Online" / "RpcnModel.swift"

RMW_LINE = "RMW on=2 onconnected=1 off=1 offkeepsinternet=1"
RMA_LINE = "RMA off=off opening=opening signed=signedin failed=failed nosecret=form"
RMS_LINE = "RMS connect=1 password=1 other=1"
RMC_LINE = ("RMC taken=1 email=1 banned=1 creation=1 invalidinput=1 toosoon=1 emailfail=1 malformed=1 tokenactivate=1 "
            "tokenreset=1")
RMI_LINE = "RMI create=username newpassword=newpassword filled=none outcome=1"
ACCOUNT_CALLS = ("Rpcs3Bridge.rpcnCreateAccount(", "Rpcs3Bridge.rpcnResendToken(", "Rpcs3Bridge.rpcnSendResetToken(",
                 "Rpcs3Bridge.rpcnResetPassword(")
FRIENDS = APP / "Online" / "RpcnFriends.swift"
RMF_LINE = "RMF order=received,sent,online,offline sorted=1 already=1 notfound=1 blocked=1 other=1"
FRIEND_CALLS = ("Rpcs3Bridge.rpcnAddFriend(", "Rpcs3Bridge.rpcnFriends {")
RMV_LINE = "RMV order=builtin,psn,picture count1=1 avatars count=2 more=1 nomore=1"
FRIEND_SENTENCES = {
    "ALREADY": "You're already friends with frienduser.",
    "NOTFOUND": "There's no RPCN account named frienduser.",
    "BLOCKED": "frienduser can't be added.",
    "FRIENDOTHER": "Couldn't update your friends (code 42). Try again.",
}

# The UI copy, verbatim.
CONNECT = "Couldn't reach RPCN. Check the internet connection, then try again."
PASSWORD = "That password isn't right. Try again, or use Forgot Password."
OTHER = "Something went wrong with RPCN (code 42). Try again."
ACCOUNT_SENTENCES = {
    "TAKEN": "That username is taken. Pick another one.",
    "EMAIL": "That email address already has an account. Sign in, or use Forgot Password.",
    "BANNED": "RPCN doesn't accept addresses from that email provider. Use another address.",
    "CREATION": "RPCN couldn't create the account. Try again later.",
    "INVALID": "RPCN didn't accept those details. Usernames are 3 to 16 letters, numbers, - or _.",
    "TOOSOON": "Too many tries. Wait a few minutes, then try again.",
    "EMAILFAIL": "RPCN couldn't send the email. Try again later.",
    "MALFORMED": "ARMSX3 sent RPCN an incomplete request (code 7). Report it from Settings › Diagnostics.",
    "TOKENACTIVATE": "That code isn't right or has expired. Use Email the Code Again for a new one.",
    "TOKENRESET": "That code isn't right or has expired. Go back and use Email Me a Code for a new one.",
    "USERNAME": "Enter your username.",
    "NEWPASSWORD": "Enter a new password.",
}

DRIVER = r'''import Foundation

let psn = "Net@@PSN status", net = "Net@@Internet enabled"
func count(_ w: [(path: String, value: String)]) -> Int { w.count }
let a = RpcnState.onlineSwitchWrites(on: true, internetConnected: false)
let b = RpcnState.onlineSwitchWrites(on: true, internetConnected: true)
let c = RpcnState.onlineSwitchWrites(on: false, internetConnected: true)
let d = RpcnState.onlineSwitchWrites(on: false, internetConnected: false)
let onOK = a.count == 2 && a.first?.path == net && a.first?.value == "Connected" && a.last?.path == psn && a.last?.value == "RPCN"
let connectedOK = b.count == 1 && b[0].path == psn && b[0].value == "RPCN"
let offOK = c.count == 1 && c[0].path == psn && c[0].value == "Disconnected" && d.count == 1 && d[0].path == psn
let keeps = !(c + d).contains { $0.path == net }
print("RMW on=\(onOK ? count(a) : 0) onconnected=\(connectedOK ? 1 : 0) off=\(offOK ? 1 : 0) offkeepsinternet=\(keeps ? 1 : 0)")

func word(_ on: Bool, _ secret: Bool, _ session: Int) -> String {
    String(describing: RpcnState.accountState(onlinePlay: on, configured: true, hasSecret: secret, session: session)).lowercased()
}
print("RMA off=\(word(false, true, 2)) opening=\(word(true, true, 1)) signed=\(word(true, true, 2)) failed=\(word(true, true, 3)) nosecret=\(word(true, false, 2))")

let connect = RpcnState.signInSentence(state: 5, error: 0, raw: 5) == "%CONNECT%"
let password = RpcnState.signInSentence(state: 0, error: 7, raw: 8) == "%PASSWORD%"
let other = RpcnState.signInSentence(state: 0, error: 42, raw: 42) == "%OTHER%"
print("RMS connect=\(connect ? 1 : 0) password=\(password ? 1 : 0) other=\(other ? 1 : 0)")

func says(_ error: Int, _ raw: Int, _ screen: RpcnState.AccountScreen, _ text: String) -> Int {
    RpcnState.accountSentence(error: error, raw: raw, screen: screen) == text ? 1 : 0
}
print("RMC taken=\(says(10, 11, .create, "%TAKEN%")) email=\(says(12, 13, .create, "%EMAIL%")) banned=\(says(11, 12, .create, "%BANNED%")) creation=\(says(9, 10, .create, "%CREATION%")) invalidinput=\(says(2, 3, .create, "%INVALID%")) toosoon=\(says(3, 4, .reset, "%TOOSOON%")) emailfail=\(says(13, 14, .reset, "%EMAILFAIL%")) malformed=\(says(1, 7, .create, "%MALFORMED%")) tokenactivate=\(says(8, 9, .activate, "%TOKENACTIVATE%")) tokenreset=\(says(8, 9, .newPassword, "%TOKENRESET%"))")

func first(_ text: String?) -> String {
    switch text {
    case nil: return "none"
    case "%USERNAME%"?: return "username"
    case "%NEWPASSWORD%"?: return "newpassword"
    default: return "other"
    }
}
let created = first(RpcnState.missingInput(username: "", password: "", email: ""))
let renewing = first(RpcnState.missingInput(username: "probeuser", code: "1", newPassword: ""))
let filled = first(RpcnState.missingInput(username: "probeuser", password: "p", email: "e", code: "1", newPassword: "n"))
let outcome = RpcnState.accountOutcome(status: 0, state: 0, error: 0, raw: 0, screen: .create) == nil
    && RpcnState.accountOutcome(status: -7, state: 0, error: 0, raw: 0, screen: .create) == "%INVALID%"
    && RpcnState.accountOutcome(status: 0, state: 5, error: 10, raw: 5, screen: .create) == "%CONNECT%"
print("RMI create=\(created) newpassword=\(renewing) filled=\(filled) outcome=\(outcome ? 1 : 0)")

// Synthetic names only, given out of order and in mixed case.
let friendsJSON = """
{"signedIn":true,"friends":[{"npid":"frienduserd","online":false,"title":"","status":"","comment":""},\
{"npid":"FriendUserB","online":true,"title":"Probe Title","status":"","comment":""},\
{"npid":"frienduserc","online":false,"title":"","status":"","comment":""},\
{"npid":"frienduserA","online":true,"title":"","status":"","comment":""}],\
"received":["requestuserB","requestusera"],"sent":["sentuser"]}
""".data(using: .utf8)!
let friendRows = RpcnState.friendRows(friendsJSON)
var groups: [String] = []
for row in friendRows where groups.last != row.kind.rawValue { groups.append(row.kind.rawValue) }
let grouped = Dictionary(grouping: friendRows, by: { $0.kind.rawValue })
let inOrder = friendRows.count == 7 && grouped.values.allSatisfy { group in
    group.map { $0.name.lowercased() } == group.map { $0.name.lowercased() }.sorted()
}
func friendSays(_ error: Int, _ raw: Int, _ text: String) -> Int {
    RpcnState.friendSentence(error: error, raw: raw, name: "frienduser") == text ? 1 : 0
}
print("RMF order=\(groups.joined(separator: ",")) sorted=\(inOrder ? 1 : 0) already=\(friendSays(16, 28, "%ALREADY%")) notfound=\(friendSays(14, 26, "%NOTFOUND%")) blocked=\(friendSays(15, 27, "%BLOCKED%")) other=\(friendSays(99, 42, "%FRIENDOTHER%"))")

// A synthetic avatar list, given out of order, with one PSN avatar still needing its licence.
let avatarJSON = """
{"firmware":true,"unusable":1,"avatars":[\
{"path":"/probe/home/00000001/armsx3_avatar_0000000000000001.png","kind":"picture","title":""},\
{"path":"/probe/home/00000001/psn_avatar/ZZZZ00002/b.edat","kind":"psn","title":"ZZZZ00002","usable":true},\
{"path":"/probe/dev_flash/user/000.png","kind":"builtin","title":"","usable":true},\
{"path":"/probe/home/00000001/psn_avatar/ABCD00001/c.edat","kind":"psn","title":"ABCD00001","usable":false},\
{"path":"/probe/home/00000001/psn_avatar/ABCD00001/a.edat","kind":"psn","title":"ABCD00001","usable":true}]}
""".data(using: .utf8)!
let avatarList = try! JSONDecoder().decode(RpcnState.AvatarList.self, from: avatarJSON)
let avatarTiles = RpcnState.avatarTiles(avatarList.avatars)
var avatarKinds: [String] = []
for tile in avatarTiles where avatarKinds.last != tile.kind { avatarKinds.append(tile.kind) }
let psnTitles = avatarTiles.filter { $0.kind == "psn" }.map(\.title)
let avatarOrder = psnTitles == ["ABCD00001", "ZZZZ00002"] && !avatarTiles.contains { $0.usable == false }
    ? avatarKinds.joined(separator: ",") : "unordered"
let one = RpcnState.avatarCountLine(n: 1, m: 0) == "1 avatar." ? 1 : 0
// The plural line printed through its own words: "2 avatars." reads `avatars count=2`.
let plural = RpcnState.avatarCountLine(n: 2, m: 0).dropLast().split(separator: " ")
// "needs" for one, "need" for more.
let more = RpcnState.avatarCountLine(n: 3, m: 1) == "3 avatars. 1 more needs their licence."
    && RpcnState.avatarCountLine(n: 3, m: 2) == "3 avatars. 2 more need their licence." ? 1 : 0
let nomore = RpcnState.avatarCountLine(n: 3, m: 0) == "3 avatars." ? 1 : 0
print("RMV order=\(avatarOrder) count1=\(one) \(plural.last ?? "") count=\(plural.first ?? "") more=\(more) nomore=\(nomore)")
'''.replace("%CONNECT%", CONNECT).replace("%PASSWORD%", PASSWORD).replace("%OTHER%", OTHER)
for _key, _text in list(ACCOUNT_SENTENCES.items()) + list(FRIEND_SENTENCES.items()):
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


def model_problems(state, model, friends):
    problems = []
    imports = re.findall(r"^\s*import\s+(\w+)", live(state), re.MULTILINE)
    if imports != ["Foundation"]:
        problems.append("foundation: RpcnState.swift imports {0}, not Foundation alone".format(imports))

    code = live(model)
    opens = len(re.findall(r"Rpcs3Bridge\.rpcnFriendsSession\(\s*true\b", code))
    closes = len(re.findall(r"Rpcs3Bridge\.rpcnFriendsSession\(\s*false\b", code))
    if opens != 1 or closes != 1:
        problems.append("pairing: RpcnModel opens the session at {0} sites and closes it at {1}, not one each".format(opens, closes))

    if "RpcnState.onlineSwitchWrites(" not in code or "Rpcs3Bridge.setSetting(" not in code:
        problems.append("writes: RpcnModel does not apply onlineSwitchWrites through setSetting")

    uncalled = [call for call in ACCOUNT_CALLS if code.count(call) != 1]
    if uncalled:
        problems.append("accountops: RpcnModel calls {0} other than once".format(uncalled))

    uncalled = [call for call in FRIEND_CALLS if call not in code]
    if uncalled:
        problems.append("friendops: RpcnModel does not call {0}".format(uncalled))
    return problems


class TheRealFilesHold(unittest.TestCase):
    # No docstrings, so `unittest -v` prints `... ok` on the method's own line.
    def test_the_real_files_have_no_problem(self):
        self.assertEqual([], model_problems(read(STATE), read(MODEL), read(FRIENDS)))

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

    def fails_with(self, prefix, state=None, model=None, friends=None):
        found = model_problems(state if state is not None else read(STATE), model if model is not None else read(MODEL),
                               friends if friends is not None else read(FRIENDS))
        self.assertTrue(any(p.startswith(prefix + ":") for p in found), found)

    def test_foundation_a_second_import_fails(self):
        self.fails_with("foundation", state=self.edited(read(STATE), "import Foundation", "import Foundation\nimport SwiftUI"))

    def test_pairing_a_second_open_fails(self):
        model = read(MODEL)
        self.fails_with("pairing", model=self.edited(model, "func restoreSecret() async {",
                                                     "func restoreSecret() async {\n        Rpcs3Bridge.rpcnFriendsSession(true) { _, _, _, _ in }"))

    def test_writes_the_setting_call_dropped_fails(self):
        self.fails_with("writes", model=self.edited(read(MODEL), "RpcnState.onlineSwitchWrites(", "RpcnState.onlineWrites("))

    def test_accountops_a_call_renamed_fails(self):
        self.fails_with("accountops", model=self.edited(read(MODEL), "Rpcs3Bridge.rpcnSendResetToken(", "Rpcs3Bridge.rpcnForgot("))

    def test_friendops_a_call_dropped_fails(self):
        self.fails_with("friendops", model=self.edited(read(MODEL), "Rpcs3Bridge.rpcnFriends {", "Rpcs3Bridge.rpcnPeople {"))


class TheRulesComputeOnTheHost(unittest.TestCase):
    """A macOS host run of Foundation-only Swift: what the source computes, never device evidence."""

    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            cls.out = compile_and_run([STATE], DRIVER, tmp)
        cls.lines = [line for line in cls.out.splitlines() if line[:4] in ("RMW ", "RMA ", "RMS ", "RMC ", "RMI ", "RMF ", "RMV ")]
        sys.stderr.write("\n" + "\n".join(cls.lines) + "\n")

    def test_the_switch_writes(self):
        self.assertIn(RMW_LINE, self.lines, self.out)

    def test_the_account_states(self):
        self.assertIn(RMA_LINE, self.lines, self.out)

    def test_the_sentences(self):
        self.assertIn(RMS_LINE, self.lines, self.out)

    def test_the_account_sentences(self):
        self.assertIn(RMC_LINE, self.lines, self.out)

    def test_the_missing_input_and_outcome(self):
        self.assertIn(RMI_LINE, self.lines, self.out)

    def test_the_friend_rows_and_sentences(self):
        self.assertIn(RMF_LINE, self.lines, self.out)

    def test_the_avatar_order_and_count_line(self):
        self.assertIn(RMV_LINE, self.lines, self.out)


if __name__ == "__main__":
    unittest.main()
