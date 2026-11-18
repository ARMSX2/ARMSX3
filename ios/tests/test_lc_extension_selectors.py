#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The launcher's NSExtension selectors are the set LiveContainer commit 9d6ea09 implements. Checks the cancellation block,
the connect deadline that applies only inside LiveContainer, and the check-only disk-image op. A source check on the
comment-stripped launcher and bridge, not device evidence."""

import re
import unittest
from pathlib import Path

from source_helpers import block_at, function_span, without_c_comments

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER_M = ROOT / "ios" / "jit-enabler" / "StikJITExtensionLauncher.m"
BRIDGE_MM = ROOT / "ios" / "app" / "Bridge" / "Rpcs3Bridge.mm"

SELECTORS = {
    "extensionWithIdentifier:excludingDisabledExtensions:error:",
    "setRequestInterruptionBlock:",
    "setRequestCancellationBlock:",
    "beginExtensionRequestWithInputItems:error:",
    "beginRequestWithInputItems:",
    "pidForRequestIdentifier:",
    "_kill:",
}

ATTEMPT = "static armsx3_jit_enabler_result Armsx3PrepareAttempt("
DDI = "int armsx3_jit_enabler_ddi("
WORDS = "static bool Armsx3IsDDIWord("
CHECK_WORDS = ["ready", "unreachable", "pairing", "unmounted", "other"]
STAGE_WORDS = ["connecting", "pairing", "downloading", "mounting", "checking"]


def region(code, signature):
    start, end = function_span(code, signature)
    return code[start:end]


def selector_problems(raw):
    code = without_c_comments(raw)
    out = []
    literals = re.findall(r'NSSelectorFromString\(@"([^"]*)"\)', code)
    for name in sorted(set(literals) - SELECTORS):
        out.append("selectors: " + name + " is not one LiveContainer implements")
    for name in sorted(SELECTORS - set(literals)):
        out.append("selectors: " + name + " is no longer looked up")

    lookup = re.search(r'SEL (\w+) = NSSelectorFromString\(@"setRequestCancellationBlock:"\);\s*', code)
    if lookup is None:
        return out + ["cancellation: the selector is not looked up into a variable"]
    var = lookup.group(1)
    guard = "if ([s->_extension respondsToSelector:" + var + "]) {"
    if not code.startswith(guard, lookup.end()):
        return out + ["cancellation: the block is not installed behind respondsToSelector: on the same selector"]
    block = block_at(code, lookup.end()) or ""
    if "dispatchInterruptionFrom:" not in block:
        out.append("cancellation: the block does not route to dispatchInterruptionFrom:")
    if "objc_msgSend)(s->_extension, " + var + "," not in block:
        out.append("cancellation: the block is not set on the extension inside the guard")
    if len(re.findall(r"objc_msgSend\)\(s->_extension, " + var + r"\b", code)) != 1:
        out.append("cancellation: the selector is sent from somewhere else too")
    return out


def attempt_problems(raw):
    code = without_c_comments(raw)
    out = []
    try:
        attempt = region(code, ATTEMPT)
    except (ValueError, AssertionError):
        return ["functions: the single-attempt function is missing"]

    if not re.search(r"dispatch_semaphore_t started = dispatch_semaphore_create\(0\);", attempt):
        out.append("started: the attempt has no started semaphore")
    if not re.search(r"launchWithCompletion:\^\([^()]*\)\s*\{\s*dispatch_semaphore_signal\(started\);", attempt):
        out.append("started: the launch completion block does not signal it first")

    gate = attempt.find('if (getenv("LC_HOME_PATH") != NULL) {')
    if gate < 0:
        return out + ["gate: the connect wait is not tied to LC_HOME_PATH"]
    gated = block_at(attempt, gate) or ""
    waits = [m.start() for m in re.finditer(r"dispatch_semaphore_wait\(started,", attempt)]
    if len(waits) != 1 or not (gate < waits[0] < gate + len(gated)):
        out.append("gate: the started wait is not alone inside the LiveContainer gate")
    if "5LL * NSEC_PER_SEC" not in gated or len(re.findall(r"5LL \* NSEC_PER_SEC", code)) != 1:
        out.append("gate: the connect wait is not 5 s")
    if "connectBy > deadline" not in gated:
        out.append("gate: the connect wait can outlast the shared deadline")
    if 'Armsx3SetOutcomeWord(&cand, "connect-timeout");' not in gated or "[launcher submitTerminal:&cand]" not in gated:
        out.append("word: a missed connect does not submit connect-timeout through submitTerminal:")
    if len(re.findall(r'"connect-timeout"', code)) != 1:
        out.append("word: connect-timeout is written anywhere but the connect wait")
    last = attempt.find("dispatch_semaphore_wait(done, deadline)")
    if last < 0 or last < gate:
        out.append("deadline: the wait on done does not come after the connect wait")
    return out


def ddi_problems(raw):
    code = without_c_comments(raw)
    out = []
    try:
        ddi = region(code, DDI)
        words = region(code, WORDS)
    except (ValueError, AssertionError):
        return ["functions: the disk-image entry point or its word check is missing"]

    if "op != ARMSX3_JIT_DDI_OP_RESET && op != ARMSX3_JIT_DDI_OP_MOUNT && op != ARMSX3_JIT_DDI_OP_CHECK" not in ddi:
        out.append("op: the entry point does not accept exactly reset, mount and check")
    if not re.search(r"if \(op != ARMSX3_JIT_DDI_OP_RESET\) \{\s*pairing = Armsx3MountPairing\(Armsx3PairingLookup\(NO, NULL\)\);", ddi):
        out.append("pairing: check does not read the pairing bytes as mount does")

    if len(re.findall(r"mountDDIWithPairing:", ddi)) != 1:
        out.append("mount: the mount selector is called more than once")
    check_at = ddi.find("else if (op == ARMSX3_JIT_DDI_OP_CHECK) {")
    check = (block_at(ddi, check_at) or "") if check_at >= 0 else ""
    if "[runner checkSetupWithPairing:pairing withReply:onReply];" not in check:
        out.append("check: op 3 does not call the check selector with the pairing bytes")
    if re.search(r"mountDDIWithPairing:|resetDDIWithReply:", check):
        out.append("check: op 3 calls a selector that mounts or deletes")
    mount_at = ddi.find("if (op == ARMSX3_JIT_DDI_OP_MOUNT) {\n                [runner")
    mount = (block_at(ddi, mount_at) or "") if mount_at >= 0 else ""
    if "mountDDIWithPairing:" not in mount:
        out.append("mount: the mount selector is not behind op 1")
    if "initWithStage:(op != ARMSX3_JIT_DDI_OP_RESET) ? stage : nil" not in ddi:
        out.append("stage: check does not forward stages")
    if not re.search(r"\(op == ARMSX3_JIT_DDI_OP_MOUNT\) \? 900LL : \(op == ARMSX3_JIT_DDI_OP_CHECK\) \? 60LL : 30LL", ddi):
        out.append("wait: the seconds are not mount 900, check 60 and reset 30")
    if "Armsx3IsDDIWord(op, r.helper_outcome)" not in ddi or "Armsx3IsDDIWord(op, [(NSString*)word UTF8String])" not in ddi:
        out.append("words: a reply is not filtered by the closed word list of its op")

    listed = re.search(r"check\[\] = \{([^}]*)\};", words)
    if listed is None or re.findall(r'"([^"]*)"', listed.group(1)) != CHECK_WORDS:
        out.append("words: op 3 does not accept exactly ready, unreachable, pairing, unmounted, other")
    elif "words = check;" not in words or "op == ARMSX3_JIT_DDI_OP_CHECK" not in words:
        out.append("words: the check list is not chosen for op 3")

    host = code[code.find("@implementation Armsx3DDIHost"):]
    host = host[:host.find("@end")]
    stages = re.search(r"@\[([^\]]*)\] containsObject:word", host)
    if stages is None or re.findall(r'@"([^"]*)"', stages.group(1)) != STAGE_WORDS:
        out.append("stage: the host does not forward exactly connecting, pairing, downloading, mounting, checking")

    if not re.search(r"setClasses:replyClasses\s*forSelector:@selector\(checkSetupWithPairing:withReply:\)\s*argumentIndex:0\s*ofReply:YES",
                     code):
        out.append("classes: the check reply classes are not pinned on the runner interface")
    return out


def bridge_problems(raw):
    out = []
    start = raw.find("+ (void)testJitSetupWithStage:")
    if start < 0:
        return ["bridge: testJitSetupWithStage:completion: is missing"]
    body = block_at(raw, start) or ""
    global_hop = body.find("dispatch_get_global_queue(")
    main_hop = body.find("dispatch_async(dispatch_get_main_queue()")
    if global_hop < 0 or "rpcs3_bridge_queue" in body:
        out.append("bridge: the check does not run on a global queue off the emulator's queue")
    if "rpcs3_bridge_ddi(ARMSX3_JIT_DDI_OP_CHECK, stage, &result, &reason);" not in body:
        out.append("bridge: the check does not run op 3 with the caller's stage block")
    if main_hop < 0 or main_hop < global_hop or "completion(result, reason);" not in body[main_hop:]:
        out.append("bridge: the completion is not called on the main queue")
    return out


class TheSelectorSet(unittest.TestCase):
    raw = LAUNCHER_M.read_text(encoding="utf-8")

    def caught(self, problems, prefix):
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def mutated(self, old, new):
        self.assertEqual(1, self.raw.count(old), "mutation anchor: " + old)
        return self.raw.replace(old, new)

    def test_baseline(self):
        self.assertEqual([], selector_problems(self.raw))

    def test_an_unknown_selector_is_caught(self):
        raw = self.mutated('SEL pidSel = NSSelectorFromString(@"pidForRequestIdentifier:");',
                           'SEL pidSel = NSSelectorFromString(@"pidForRequestIdentifier:");\n'
                           '        SEL extra = NSSelectorFromString(@"setMadeUpBlock:");')
        self.caught(selector_problems(raw), "selectors")

    def test_a_dropped_selector_is_caught(self):
        raw = self.mutated('NSSelectorFromString(@"_kill:")', 'NSSelectorFromString(@"kill:")')
        self.caught(selector_problems(raw), "selectors")

    def test_a_cancellation_block_without_the_guard_is_caught(self):
        raw = self.mutated("if ([s->_extension respondsToSelector:cancelSel]) {", "if (YES) {")
        self.caught(selector_problems(raw), "cancellation")

    def test_a_cancellation_block_that_ignores_the_coordinator_is_caught(self):
        raw = self.mutated('[coordinator dispatchInterruptionFrom:@"request-cancelled"];', "(void)coordinator;")
        self.caught(selector_problems(raw), "cancellation")


class TheConnectDeadline(unittest.TestCase):
    raw = LAUNCHER_M.read_text(encoding="utf-8")

    def caught(self, old, new, prefix):
        self.assertEqual(1, self.raw.count(old), "mutation anchor: " + old)
        problems = attempt_problems(self.raw.replace(old, new))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_baseline(self):
        self.assertEqual([], attempt_problems(self.raw))

    def test_the_wait_outside_livecontainer_is_caught(self):
        self.caught('if (getenv("LC_HOME_PATH") != NULL) {\n            dispatch_time_t connectBy',
                    "if (true) {\n            dispatch_time_t connectBy", "gate")

    def test_another_word_is_caught(self):
        self.caught('"connect-timeout"', '"connect-failed"', "word")

    def test_another_length_is_caught(self):
        self.caught("5LL * NSEC_PER_SEC", "9LL * NSEC_PER_SEC", "gate")

    def test_a_wait_past_the_shared_deadline_is_caught(self):
        self.caught("if (connectBy > deadline) connectBy = deadline;", "", "gate")

    def test_a_late_signal_is_caught(self):
        self.caught("            dispatch_semaphore_signal(started);\n            StikJITExtensionLauncher* l = weakLauncher;\n"
                    "            if (!l) return;\n",
                    "            StikJITExtensionLauncher* l = weakLauncher;\n            if (!l) return;\n"
                    "            dispatch_semaphore_signal(started);\n", "started")

    def test_a_connect_word_that_skips_the_arbiter_is_caught(self):
        self.caught("[launcher submitTerminal:&cand];\n            }\n        }\n        BOOL timedOut",
                    "}\n        }\n        BOOL timedOut", "word")


class TheCheckOnlyOp(unittest.TestCase):
    raw = LAUNCHER_M.read_text(encoding="utf-8")

    def caught(self, old, new, prefix):
        self.assertEqual(1, self.raw.count(old), "mutation anchor: " + old)
        problems = ddi_problems(self.raw.replace(old, new))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_baseline(self):
        self.assertEqual([], ddi_problems(self.raw))

    def test_an_op_3_that_mounts_is_caught(self):
        self.caught("[runner checkSetupWithPairing:pairing withReply:onReply];",
                    "[runner mountDDIWithPairing:pairing withReply:onReply];", "mount")

    def test_an_op_3_that_deletes_is_caught(self):
        self.caught("[runner checkSetupWithPairing:pairing withReply:onReply];",
                    "[runner resetDDIWithReply:onReply];", "check")

    def test_an_op_3_that_is_refused_is_caught(self):
        self.caught(" && op != ARMSX3_JIT_DDI_OP_CHECK", "", "op")

    def test_check_without_pairing_bytes_is_caught(self):
        self.caught("if (op != ARMSX3_JIT_DDI_OP_RESET) {\n        pairing", "if (op == ARMSX3_JIT_DDI_OP_MOUNT) {\n        pairing",
                    "pairing")

    def test_a_longer_wait_is_caught(self):
        self.caught("(op == ARMSX3_JIT_DDI_OP_CHECK) ? 60LL", "(op == ARMSX3_JIT_DDI_OP_CHECK) ? 900LL", "wait")

    def test_an_extra_result_word_is_caught(self):
        self.caught('"pairing", "unmounted", "other"};', '"pairing", "unmounted", "download", "other"};', "words")

    def test_an_unfiltered_reply_is_caught(self):
        self.caught("Armsx3IsDDIWord(op, r.helper_outcome)", "true", "words")

    def test_a_host_without_the_pairing_stage_is_caught(self):
        self.caught('@"connecting", @"pairing", @"downloading"', '@"connecting", @"downloading"', "stage")

    def test_a_host_without_the_stage_block_for_check_is_caught(self):
        self.caught("initWithStage:(op != ARMSX3_JIT_DDI_OP_RESET) ? stage : nil",
                    "initWithStage:(op == ARMSX3_JIT_DDI_OP_MOUNT) ? stage : nil", "stage")

    def test_unpinned_check_reply_classes_are_caught(self):
        self.caught("forSelector:@selector(checkSetupWithPairing:withReply:)", "forSelector:@selector(resetDDIWithReply:)",
                    "classes")


class TheBridgeMethod(unittest.TestCase):
    raw = BRIDGE_MM.read_text(encoding="utf-8")

    def caught(self, old, new):
        self.assertEqual(1, self.raw.count(old), "mutation anchor: " + old)
        self.assertTrue(bridge_problems(self.raw.replace(old, new)))

    def test_baseline(self):
        self.assertEqual([], bridge_problems(self.raw))

    def test_the_emulator_queue_is_caught(self):
        start = self.raw.find("+ (void)testJitSetupWithStage:")
        old = "dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{"
        at = self.raw.find(old, start)
        mutated = self.raw[:at] + "dispatch_async(rpcs3_bridge_queue(), ^{" + self.raw[at + len(old):]
        self.assertTrue(bridge_problems(mutated))

    def test_mounting_from_the_test_is_caught(self):
        self.caught("rpcs3_bridge_ddi(ARMSX3_JIT_DDI_OP_CHECK, stage,", "rpcs3_bridge_ddi(ARMSX3_JIT_DDI_OP_MOUNT, stage,")

    def test_a_completion_off_the_main_queue_is_caught(self):
        start = self.raw.find("+ (void)testJitSetupWithStage:")
        old = "dispatch_async(dispatch_get_main_queue(), ^{"
        at = self.raw.find(old, start)
        mutated = self.raw[:at] + "dispatch_async(dispatch_get_global_queue(0, 0), ^{" + self.raw[at + len(old):]
        self.assertTrue(bridge_problems(mutated))


if __name__ == "__main__":
    unittest.main()
