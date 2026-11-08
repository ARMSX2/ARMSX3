# SPDX-License-Identifier: GPL-2.0-only
"""The RPCN password and token stay in memory: checks over rpcn_config.h and rpcn_config.cpp and the ABI that carries them.
Each mutation edits the real text once and shows the matching check fail.
"""

import re
import tempfile
import unittest
from pathlib import Path

from source_helpers import GATE, LOGGING, abi_text, brace_span, facade_text, read, without_comments
from source_helpers import hop_spans


IOS_DIR = Path(__file__).resolve().parents[1]

ROOT = IOS_DIR.parent

HEADER = ROOT / "rpcs3" / "Emu" / "NP" / "rpcn_config.h"

IMPL = ROOT / "rpcs3" / "Emu" / "NP" / "rpcn_config.cpp"

ABI_DIR = IOS_DIR / "bridge"

ABI_HEADER = IOS_DIR / "bridge" / "rpcs3_ios_abi.h"

FACADE_H = IOS_DIR / "app" / "Bridge" / "Rpcs3Bridge.h"

FACADE_DIR = IOS_DIR / "app" / "Bridge"

ARM, SET = "rpcn_arm_memory_secrets", "rpcn_set_memory_secrets"

DECLS = ("void rpcn_arm_memory_secrets();",
         "void rpcn_set_memory_secrets(std::string_view password, std::string_view token);")

STRUCT = re.compile(r"struct\s+cfg_rpcn\s*:\s*cfg::node\s*\{")

ARMED = re.compile(r"if\s*\(\s*s_secrets_armed\s*\.\s*load\s*\(\s*\)\s*\)")

LOCK = re.compile(r"std::lock_guard\s+\w+\s*\(\s*s_secret_mutex\s*\)")

SLOT_NS = re.compile(r"namespace\s*\{[^{}]*s_secret_mutex[^{}]*\}")

GETTERS = (("cfg_rpcn::get_password", "s_password", "password.to_string()"),
           ("cfg_rpcn::get_token", "s_token", "token.to_string()"))

SETTERS = (("cfg_rpcn::set_password", "s_password", "this->password.from_string("),
           ("cfg_rpcn::set_token", "s_token", "this->token.from_string("))

BLANK = ('password.from_string("")', 'token.from_string("")')

PREPROC_IF = re.compile(r"^[ \t]*#[ \t]*(?:if|ifdef|ifndef|elif|else|endif)\b", re.MULTILINE)

SET_SECRET = "Rpcs3IOS_RpcnSetSecret"

SET_SECRET_DECL = "Rpcs3IOS_Status Rpcs3IOS_RpcnSetSecret(const char* secret, const char* token);"

NULL_CHECK = re.compile(r"if\s*\(\s*secret\s*==\s*nullptr\s*\|\|\s*token\s*==\s*nullptr\s*\)\s*\{?\s*"
                        r"return\s+RPCS3_IOS_STATUS_NULL_ARGUMENT\s*;")

RPCN_HOP = "dispatch_async(rpcs3_rpcn_queue()"

RPCN_QUEUE = re.compile(r"static\s+dispatch_queue_t\s+rpcs3_rpcn_queue\s*\(\s*void\s*\)\s*\{[^}]*dispatch_queue_create\s*\(\s*"
                        r"\"armsx3\.rpcn\"\s*,\s*dispatch_queue_attr_make_with_qos_class\s*\(\s*DISPATCH_QUEUE_SERIAL\s*,\s*"
                        r"QOS_CLASS_USER_INITIATED\s*,\s*0\s*\)\s*\)")

FACADE_DECL = re.compile(r"\+\s*\(\s*void\s*\)\s*rpcnSetSecret\s*:\s*\(\s*NSString\s*\*\s*\)\s*secret\s+token\s*:\s*"
                         r"\(\s*NSString\s*\*\s*\)\s*token\s+completion\s*:[^;]*NS_SWIFT_DISABLE_ASYNC\s*;")

FACADE_METHOD = re.compile(r"\+\s*\(\s*void\s*\)\s*rpcnSetSecret\s*:[^{;]*\{")

SECRET_LINE = re.compile(r"^\s*(?:Password|Token)\s*:\s*(.*?)\s*$", re.MULTILINE)


def member_body(impl, name):
    """The brace-matched body of `name`'s definition (a trailing `const` allowed), or "" when there is none."""
    match = re.search(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*(?:const\s*)?\{", impl)
    if match is None:
        return ""
    start, end = brace_span(impl, match.end() - 1)
    return impl[start:end]


def armed_block(body):
    """The `{ ... }` of the first `if (s_secrets_armed.load())` in `body`, or None."""
    found = ARMED.search(body)
    if found is None:
        return None
    start = body.find("{", found.end())
    if start < 0:
        return None
    depth = 0
    for at in range(start, len(body)):
        depth += {"{": 1, "}": -1}.get(body[at], 0)
        if depth == 0:
            return found.start(), body[start:at + 1], at + 1
    return None


def blank_value(value):
    return value in ("", '""', "''")


def leaked_lines(text):
    """The Password/Token lines in a YAML text whose value is not empty."""
    return [m.group(0).strip() for m in SECRET_LINE.finditer(text) if not blank_value(m.group(1))]


def opens_with_gate(body, name):
    gate = GATE.search(body)
    refuse = re.search(r'return\s+refuse_main_thread\s*\(\s*"' + re.escape(name) + r'"\s*\)\s*;', body)
    return gate is not None and refuse is not None and gate.start() == body.find("if") and gate.start() < refuse.start()


def method_body(text, pattern):
    found = pattern.search(text)
    if found is None:
        return ""
    start, end = brace_span(text, found.end() - 1)
    return text[start:end]


def secret_problems(header, impl, abi_impl, abi_header, facade_h, facade_impl):
    header = without_comments(header)
    impl = without_comments(impl)
    abi_impl = without_comments(abi_impl)
    abi_header = without_comments(abi_header)
    facade_h = without_comments(facade_h)
    facade_impl = without_comments(facade_impl)
    problems = []

    flat = re.sub(r"\s+", " ", header)
    struct = STRUCT.search(flat)
    struct_end = flat.find("};", struct.end()) if struct else -1
    for decl in DECLS:
        at = flat.find(decl)
        if at < 0 or struct_end < 0 or not at > struct_end:
            problems.append("decl: rpcn_config.h does not declare `{0}` after the cfg_rpcn struct".format(decl))

    for name, slot, node_read in GETTERS:
        body = member_body(impl, name)
        block = armed_block(body)
        node_at = body.find(node_read)
        if block is None or not LOCK.search(block[1]) or not re.search(r"return\s+" + slot + r"\s*;", block[1]) \
                or node_at < 0 or not block[2] <= node_at:
            problems.append("armedget: {0} does not return {1} under the lock when armed, before the node".format(name, slot))

    for name, slot, node_write in SETTERS:
        body = member_body(impl, name)
        block = armed_block(body)
        node_at = body.find(node_write)
        if block is None or not LOCK.search(block[1]) or not re.search(slot + r"\s*=\s*\w+\s*;", block[1]) \
                or not re.search(r"return\s*;", block[1]) or node_at < 0 or not block[2] <= node_at:
            problems.append("armedset: {0} does not write only {1} under the lock and return when armed".format(name, slot))

    load = member_body(impl, "cfg_rpcn::load")
    block = armed_block(load)
    migration = load.find("save()")
    if block is None or not all(b in block[1] for b in BLANK) or migration < 0 or not block[2] <= migration:
        problems.append("loadblank: load() does not blank the Password and Token nodes when armed, before the "
                        "version-1 migration's save()")

    arm = member_body(impl, ARM)
    if not re.search(r"s_secrets_armed\s*\.\s*store\s*\(\s*true\s*\)", arm) \
            or not all("g_cfg_rpcn." + b in arm for b in BLANK):
        problems.append("armblank: {0} does not set the armed flag and blank g_cfg_rpcn's two nodes".format(ARM))

    decl_start = header.find(ARM)
    decl_end = header.find(SET, decl_start + 1)
    header_seam = header[decl_start:header.find(";", decl_end) + 1] if decl_start >= 0 and decl_end >= 0 else ""
    slot_ns = SLOT_NS.search(impl)
    seam = [header_seam, slot_ns.group(0) if slot_ns else "", arm, member_body(impl, SET), load]
    seam += [member_body(impl, g[0]) for g in GETTERS] + [member_body(impl, s[0]) for s in SETTERS]
    if not header_seam or not slot_ns or any(PREPROC_IF.search(part) for part in seam):
        problems.append("noguard: the seam is missing a part, or a preprocessor conditional sits inside it")

    init = member_body(abi_impl, "Rpcs3IOS_Initialize")
    gate = GATE.search(init)
    call = init.find(ARM + "();")
    later = [at for at in (init.find("install_callbacks("), init.find("Emu.Init(")) if at >= 0]
    if gate is None or call < 0 or not gate.end() < call or len(later) != 2 or not call < min(later):
        problems.append("arm: Rpcs3IOS_Initialize does not call {0}() after its gate and before install_callbacks "
                        "and Emu.Init".format(ARM))

    if SET_SECRET_DECL not in re.sub(r"\s+", " ", abi_header):
        problems.append("abidecl: rpcs3_ios_abi.h does not declare `{0}`".format(SET_SECRET_DECL))

    body = member_body(abi_impl, SET_SECRET)
    if not opens_with_gate(body, SET_SECRET):
        problems.append("abigate: {0} does not open with `if (pthread_main_np() != 0)` and refuse_main_thread".format(SET_SECRET))

    null = NULL_CHECK.search(body)
    call = body.find("rpcn::set_secret(")
    if null is None or call < 0 or not null.end() < call:
        problems.append("abinull: {0} does not return NULL_ARGUMENT for a null secret or token before "
                        "rpcn::set_secret".format(SET_SECRET))

    if not body or LOGGING.search(body.replace("refuse_main_thread(", "")):
        problems.append("abiquiet: {0} is missing or makes a log call".format(SET_SECRET))

    spans = hop_spans(facade_impl, RPCN_HOP)
    calls = [m.start() for m in re.finditer(re.escape(SET_SECRET) + r"\s*\(", facade_impl)]
    method = method_body(facade_impl, FACADE_METHOD)
    if not RPCN_QUEUE.search(facade_impl) or not FACADE_DECL.search(facade_h) or not calls \
            or any(not any(a < at < b for a, b in spans) for at in calls) or "dispatch_get_main_queue()" not in method:
        problems.append("queue: rpcs3_rpcn_queue() is not a serial user-initiated armsx3.rpcn queue, the selector is not "
                        "declared NS_SWIFT_DISABLE_ASYNC, a {0} call lies outside every RPCN-queue block, or it does not "
                        "complete on main".format(SET_SECRET))
    return problems


def real_inputs():
    return (read(HEADER), read(IMPL), abi_text(ABI_DIR), read(ABI_HEADER), read(FACADE_H), facade_text(FACADE_DIR))


class RpcnSecretSeam(unittest.TestCase):
    def test_real_files_have_no_problems(self):
        self.assertEqual([], secret_problems(*real_inputs()))

    def test_edge_missing_and_empty_inputs_raise(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.h"
            empty.write_text("  \n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                read(empty)
            with self.assertRaises(OSError):
                read(Path(tmp) / "missing.h")

    def test_leak_reader_sees_a_value_and_ignores_blanks(self):
        self.assertEqual(["Password: x"], leaked_lines('Version: 2\nPassword: x\nToken: ""\n'))
        self.assertEqual([], leaked_lines("Password: ''\nToken:\n"))


class RpcnSecretSeamMutations(unittest.TestCase):
    """Each method makes ONE edit to the real text and shows the checker fail."""

    @classmethod
    def setUpClass(cls):
        cls.inputs = list(real_inputs())

    def edited(self, text, old, new):
        self.assertTrue(old in text, "the mutation's target text {0!r} is not in the file".format(old))
        mutated = text.replace(old, new, 1)
        self.assertNotEqual(text, mutated)
        return mutated

    def body_edited(self, index, name, old, new):
        text = self.inputs[index]
        body = member_body(text, name)
        self.assertTrue(body, name)
        return self.edited(text, body, self.edited(body, old, new))

    def assertFlagged(self, index, text, prefix):
        inputs = list(self.inputs)
        inputs[index] = text
        problems = secret_problems(*inputs)
        self.assertTrue(any(p.startswith(prefix + ":") for p in problems), problems)

    def test_mutation_decl_setter_dropped(self):
        self.assertFlagged(0, self.edited(self.inputs[0], DECLS[1], ""), "decl")

    def test_mutation_armedget_token_read_unlocked(self):
        text = self.body_edited(1, "cfg_rpcn::get_token", "std::lock_guard lock(s_secret_mutex);", "")
        self.assertFlagged(1, text, "armedget")

    def test_mutation_armedset_password_falls_through_to_the_node(self):
        text = self.body_edited(1, "cfg_rpcn::set_password", "return;", "")
        self.assertFlagged(1, text, "armedset")

    def test_mutation_loadblank_token_kept(self):
        text = self.body_edited(1, "cfg_rpcn::load", BLANK[1] + ";", "")
        self.assertFlagged(1, text, "loadblank")

    def test_mutation_armblank_flag_never_set(self):
        text = self.body_edited(1, ARM, "s_secrets_armed.store(true);", "")
        self.assertFlagged(1, text, "armblank")

    def test_mutation_noguard_setter_fenced(self):
        text = self.body_edited(1, SET, "std::lock_guard lock(s_secret_mutex);",
                                "#if TARGET_OS_IPHONE\n\tstd::lock_guard lock(s_secret_mutex);\n#endif")
        self.assertFlagged(1, text, "noguard")

    def test_mutation_arm_after_emu_init(self):
        text = self.body_edited(2, "Rpcs3IOS_Initialize", ARM + "();", "")
        text = self.edited(text, "\tEmu.Init();", "\tEmu.Init();\n\t" + ARM + "();")
        self.assertFlagged(2, text, "arm")

    def test_mutation_abidecl_token_dropped(self):
        self.assertFlagged(3, self.edited(self.inputs[3], SET_SECRET_DECL,
                                          "Rpcs3IOS_Status Rpcs3IOS_RpcnSetSecret(const char* secret);"), "abidecl")

    def test_mutation_abigate_as_a_bare_truth_test(self):
        text = self.body_edited(2, SET_SECRET, "if (pthread_main_np() != 0)", "if (pthread_main_np())")
        self.assertFlagged(2, text, "abigate")

    def test_mutation_abinull_token_unchecked(self):
        text = self.body_edited(2, SET_SECRET, "secret == nullptr || token == nullptr", "secret == nullptr")
        self.assertFlagged(2, text, "abinull")

    def test_mutation_abiquiet_a_log_line_added(self):
        text = self.body_edited(2, SET_SECRET, "return RPCS3_IOS_STATUS_OK;",
                                'utils::stderr_witness_printf("set\\n");\n\treturn RPCS3_IOS_STATUS_OK;')
        self.assertFlagged(2, text, "abiquiet")

    def test_mutation_queue_on_the_bridge_queue(self):
        facade = self.inputs[5]
        at = facade.index(SET_SECRET + "(")
        hop = facade.rindex(RPCN_HOP, 0, at)
        text = facade[:hop] + "dispatch_async(rpcs3_bridge_queue()" + facade[hop + len(RPCN_HOP):]
        self.assertFlagged(5, text, "queue")


if __name__ == "__main__":
    unittest.main()
