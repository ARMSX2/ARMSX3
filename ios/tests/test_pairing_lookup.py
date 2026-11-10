#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-only
"""The pre-main pairing lookup: pairing_shape.h, compiled with cc over synthetic fixtures, gives the same verdict as
PairingFileCheck.swift, names the retry and result words, and the launcher takes a Documents drop before it builds its
candidate list, records the origin only on a move and never logs a path. A host and source check, not device evidence."""

import os
import plistlib
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from source_helpers import function_span, without_c_comments
from swiftc_host import compile_and_run

ROOT = Path(__file__).resolve().parents[2]
HEADER_DIR = ROOT / "ios" / "jit-enabler"
LAUNCHER_M = HEADER_DIR / "StikJITExtensionLauncher.m"
BRIDGE_MM = ROOT / "ios" / "app" / "Bridge" / "Rpcs3Bridge.mm"
CHECK_SWIFT = ROOT / "ios" / "app" / "JIT" / "PairingFileCheck.swift"

COMPILE_TIMEOUT_SECONDS = 120
RUN_TIMEOUT_SECONDS = 30
CAP = 65536


def key():
    return os.urandom(32)


def padded(total, extra=None):
    """An RPPairing plist of exactly `total` bytes."""
    fields = {"public_key": key(), "private_key": key(), "identifier": "synthetic-host"}
    fields.update(extra or {})
    base = len(plistlib.dumps(dict(fields, pad="")))
    return plistlib.dumps(dict(fields, pad="a" * (total - base)))


# name: (bytes, shape word, whether PairingFileCheck, which has no size cap, must agree)
FIXTURES = {
    "rp-xml": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "synthetic-host"}), "rp", True),
    "rp-binary": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "synthetic-host"},
                                 fmt=plistlib.FMT_BINARY), "rp", True),
    "rp-extra": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "x", "alt_irk": os.urandom(16)}),
                 "rp", True),
    "rp-lockdown": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": "synthetic-host",
                                    "HostCertificate": b"synthetic", "SystemBUID": "SYNTHETIC-BUID"}), "rp-lockdown", True),
    "lockdown": (plistlib.dumps({"HostCertificate": b"synthetic", "DeviceCertificate": b"synthetic",
                                 "HostPrivateKey": b"synthetic", "SystemBUID": "SYNTHETIC-BUID"}), "lockdown", True),
    "lockdown-with-half-rp": (plistlib.dumps({"public_key": key(), "SystemBUID": "SYNTHETIC-BUID"}), "lockdown", True),
    "no-private": (plistlib.dumps({"public_key": key(), "identifier": "x"}), "invalid", True),
    "short-key": (plistlib.dumps({"public_key": os.urandom(31), "private_key": key(), "identifier": "x"}), "invalid", True),
    "string-key": (plistlib.dumps({"public_key": "not data", "private_key": key(), "identifier": "x"}), "invalid", True),
    "number-identifier": (plistlib.dumps({"public_key": key(), "private_key": key(), "identifier": 7}), "invalid", True),
    "array": (plistlib.dumps([1, 2, 3]), "invalid", True),
    "garbage": (os.urandom(64), "invalid", True),
    "empty": (b"", "invalid", True),
    "oversize-garbage": (os.urandom(CAP + 1), "invalid", True),
    "at-cap": (padded(CAP), "rp", True),
    "over-cap": (padded(CAP + 1), "invalid", False),
}

# helper word: (retries, result word)
WORDS = {
    "pairing-rejected": (True, "rejected"),
    "tunnel-failed": (True, "rejected"),
    "start-failed": (False, "not-tried"),
    "connect-failed": (False, "not-tried"),
    "connect-timeout": (False, "not-tried"),
    "timeout": (False, "not-tried"),
    "interrupted": (False, "not-tried"),
    "invalid-request": (False, "not-tried"),
    "pairing-absent": (False, "not-tried"),
    "": (False, "not-tried"),
    "finished": (False, "accepted"),
    "attach-failed": (False, "accepted"),
    "pairing-rejected-ish": (False, "accepted"),
}

C_DRIVER = r"""
#include "pairing_shape.h"
#include <stdio.h>
#include <stdlib.h>

static const char* shape_word(armsx3_pairing_shape shape)
{
    switch (shape) {
        case ARMSX3_PAIRING_RP: return "rp";
        case ARMSX3_PAIRING_RP_LOCKDOWN: return "rp-lockdown";
        case ARMSX3_PAIRING_LOCKDOWN: return "lockdown";
        default: return "invalid";
    }
}

int main(int argc, char** argv)
{
    if (argc > 1 && strcmp(argv[1], "--words") == 0) {
        for (int i = 2; i < argc; i++)
            printf("%s=%d,%s\n", argv[i], armsx3_pairing_should_retry(argv[i]), armsx3_pairing_result_word(argv[i]));
        printf("<null>=%d,%s\n", armsx3_pairing_should_retry(NULL), armsx3_pairing_result_word(NULL));
        printf("<null-bytes>=%s\n", shape_word(armsx3_pairing_shape_of(NULL, 5)));
        return 0;
    }
    static unsigned char buffer[1 << 20];
    for (int i = 1; i < argc; i++) {
        FILE* file = fopen(argv[i], "rb");
        if (file == NULL) return 2;
        size_t length = fread(buffer, 1, sizeof buffer, file);
        fclose(file);
        const char* base = strrchr(argv[i], '/');
        printf("%s=%s\n", base ? base + 1 : argv[i], shape_word(armsx3_pairing_shape_of(buffer, length)));
    }
    return 0;
}
"""

SWIFT_DRIVER = r"""
import Foundation
let folder = URL(fileURLWithPath: "@@DIR@@")
for name in try! FileManager.default.contentsOfDirectory(atPath: folder.path).sorted() where name.hasSuffix(".bin") {
    let data = try! Data(contentsOf: folder.appendingPathComponent(name))
    let verdict = PairingFileCheck.classify(data)
    let carries = PairingFileCheck.carriesLockdownKeys(data)
    let word: String
    switch verdict {
    case .valid: word = carries ? "rp-lockdown" : "rp"
    case .legacyLockdown: word = "lockdown"
    case .invalid: word = "invalid"
    }
    print("\(name.dropLast(4))=\(word)")
}
"""


def run_c(tmp, args):
    compiler = shutil.which("cc")
    if compiler is None:
        raise unittest.SkipTest("no `cc` on PATH; a skip is not a pass")
    tmp = Path(tmp)
    (tmp / "driver.c").write_text(C_DRIVER, encoding="utf-8")
    built = subprocess.run(
        [compiler, "-std=c11", "-Wall", "-Werror", "-I", str(HEADER_DIR), str(tmp / "driver.c"),
         "-framework", "CoreFoundation", "-o", str(tmp / "driver")],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True)
    if built.returncode != 0:
        raise AssertionError("cc failed:\n" + built.stdout)
    ran = subprocess.run([str(tmp / "driver")] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True)
    if ran.returncode != 0:
        raise AssertionError("the C driver exited {0}:\n{1}".format(ran.returncode, ran.stdout))
    return dict(line.split("=", 1) for line in ran.stdout.strip().split("\n"))


class TheShapeCheckAgreesWithSwift(unittest.TestCase):
    def test_every_fixture(self):
        with tempfile.TemporaryDirectory() as build, tempfile.TemporaryDirectory() as fixtures:
            paths = []
            for name, (data, _, _) in FIXTURES.items():
                paths.append(str(Path(fixtures, name + ".bin")))
                Path(paths[-1]).write_bytes(data)
            c_got = run_c(build, paths)
            swift_out = compile_and_run([CHECK_SWIFT], SWIFT_DRIVER.replace("@@DIR@@", fixtures), build)
        swift_got = dict(line.split("=", 1) for line in swift_out.strip().split("\n"))
        for name, (_, want, parity) in FIXTURES.items():
            self.assertEqual(want, c_got[name + ".bin"], "C verdict for " + name)
            if parity:
                self.assertEqual(c_got[name + ".bin"], swift_got[name], "Swift verdict for " + name)

    def test_null_bytes_are_invalid(self):
        with tempfile.TemporaryDirectory() as build:
            got = run_c(build, ["--words"])
        self.assertEqual("invalid", got["<null-bytes>"])


class TheRetryAndResultWords(unittest.TestCase):
    def test_words(self):
        with tempfile.TemporaryDirectory() as build:
            got = run_c(build, ["--words"] + list(WORDS))
        for word, (retries, result) in WORDS.items():
            self.assertEqual("{0},{1}".format(int(retries), result), got[word], "word " + repr(word))
        self.assertEqual("0,not-tried", got["<null>"])


LOOKUP = "static NSArray<Armsx3PairingCandidate*>* Armsx3PairingLookup("
TAKE = "static NSString* _Nullable Armsx3TakeDocumentsDrop("
READ_CAPPED = "static NSData* _Nullable Armsx3ReadCapped("
SIDESTORE = "static NSArray<Armsx3PairingCandidate*>* Armsx3SideStoreCandidates("
ATTEMPT = "static armsx3_jit_enabler_result Armsx3PrepareAttempt("
ENTRY = "int armsx3_jit_enabler_prepare_ranges("
MOUNT_PICK = "static NSData* _Nullable Armsx3MountPairing("
DDI = "int armsx3_jit_enabler_ddi("
LOGGING_FUNCTIONS = (LOOKUP, TAKE, READ_CAPPED, SIDESTORE, ENTRY)
STRING_LITERAL = re.compile(r'@?"(?:[^"\\]|\\.)*"')
PATHY = re.compile(r"[Pp]ath|[Ff]older|URL")


def region(code, signature):
    start, end = function_span(code, signature)
    return code[start:end]


def nslog_arguments(text):
    """The arguments of every NSLog call in `text`, string literals blanked."""
    out = []
    for match in re.finditer(r"\bNSLog\(", text):
        depth, i = 1, match.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        out.append(STRING_LITERAL.sub('""', text[match.end():i - 1]))
    return out


def lookup_problems(raw):
    code = without_c_comments(raw)
    out = []
    if not re.search(r'#\s*include\s*"pairing_shape\.h"', code):
        out.append('include: the launcher does not include "pairing_shape.h"')
    try:
        lookup = region(code, LOOKUP)
        take = region(code, TAKE)
        regions = [region(code, signature) for signature in LOGGING_FUNCTIONS]
    except (ValueError, AssertionError):
        return out + ["functions: the lookup, the Documents take or the capped read is missing"]

    drop = re.search(r"if \(premain\) dropWord = Armsx3TakeDocumentsDrop\(", lookup)
    candidates = lookup.find("NSMutableArray")
    if drop is None or candidates < 0 or drop.start() > candidates:
        out.append("documents: the lookup does not take a Documents drop before it builds the candidate list")

    if "armsx3_pairing_shape_of(" not in take:
        out.append("shape: the Documents take does not check the shape")
    if 'return @"lockdown";' not in take or 'return @"invalid";' not in take:
        out.append("shape: a lockdown or invalid Documents file is not left alone")
    steps = [take.find(s) for s in ("replaceItemAtURL", "kArmsx3PairingOriginName", "removeItemAtPath:dropPath")]
    if min(steps) < 0 or steps != sorted(steps):
        out.append("origin: the take does not swap, then write the origin, then remove the Documents file")
    if '@"documents"' not in take:
        out.append("origin: the take does not write the word documents")
    if len(re.findall(r"\bkArmsx3PairingOriginName\b", code)) != 2 \
            or len(re.findall(r'@"pairingFile\.origin"', code)) != 1:
        out.append("origin: the origin file is named or written anywhere but the Documents take")

    for text in regions:
        for argument in nslog_arguments(text):
            if PATHY.search(argument):
                out.append("log: an NSLog argument names a path: " + argument.strip())
    return out


SIDESTORE_WRITES = re.compile(r"moveItem|removeItem|copyItem|replaceItem|writeToFile|writeToURL|createFile|setAttributes"
                              r"|fileHandleForWriting|fileHandleForUpdating|NSOutputStream")
PRE_MAIN_WAITS = re.compile(r"dispatch_get_main_queue|NSTimer|dispatch_after|performSelector|dispatch_main")


def ladder_problems(raw):
    code = without_c_comments(raw)
    out = []
    try:
        lookup = region(code, LOOKUP)
        side = region(code, SIDESTORE)
        attempt = region(code, ATTEMPT)
        entry = region(code, ENTRY)
        pick = region(code, MOUNT_PICK)
        ddi = region(code, DDI)
    except (ValueError, AssertionError):
        return ["functions: a ladder function is missing"]

    if lookup.find("@\"own\"") < 0 or lookup.find("Armsx3SideStoreCandidates()") < lookup.find("@\"own\""):
        out.append("order: the SideStore candidates do not come after ARMSX3's own file")
    if 'getenv("LC_HOME_PATH")' not in side:
        out.append("sidestore: the candidates are not tied to LC_HOME_PATH")
    names = [side.find(n) for n in ("PairingFile_RemoteRP.plist", "ALTPairingFile.mobiledevicepairing")]
    words = [side.find(w) for w in ("sidestore-lc-rp", "sidestore-lc-alt")]
    if min(names + words) < 0 or names != sorted(names) or words != sorted(words):
        out.append("sidestore: RemoteRP does not come before the ALT file")
    if "ARMSX3_PAIRING_RP_LOCKDOWN" not in side or "ARMSX3_PAIRING_RP" not in side or "continue;" not in side:
        out.append("sidestore: a file that is not a Remote Pairing file is not skipped")
    if SIDESTORE_WRITES.search(side):
        out.append("sidestore: the candidate code changes a file")

    if len(re.findall(r"12LL \* NSEC_PER_SEC", code)) != 1 or entry.find("12LL * NSEC_PER_SEC") < 0 \
            or entry.find("12LL * NSEC_PER_SEC") > entry.find("Armsx3PrepareAttempt("):
        out.append("deadline: the 12 s deadline is not set once, before the first attempt")
    if "dispatch_semaphore_wait(done, deadline)" not in attempt:
        out.append("deadline: an attempt does not wait on the deadline it was given")
    for need in ("armsx3_pairing_should_retry(r.helper_outcome)", "i + 1 == candidates.count",
                 "dispatch_time(DISPATCH_TIME_NOW, 0) >= deadline", "rejected = used.source", "Armsx3PrepareAttempt("):
        if need not in entry:
            out.append("ladder: the entry point lacks " + need)
    for name, text in (("lookup", lookup), ("attempt", attempt), ("entry", entry)):
        if PRE_MAIN_WAITS.search(text):
            out.append("pre-main: the " + name + " uses the main queue or a timer")

    if "Armsx3PairingLookup(NO, NULL)" not in ddi or "Armsx3PairingLookup(YES" in ddi:
        out.append("mount: the post-main path may move a Documents file")
    if "Armsx3MountPairing(" not in ddi:
        out.append("mount: the disk-image path does not pick the file the report used")
    if "armsx3_jit_enabler_pairing_report(&report)" not in pick or 'strcmp(report.result, "accepted") == 0' not in pick \
            or "strcmp(candidate.source.UTF8String, report.used) == 0" not in pick:
        out.append("mount: the pick does not follow an accepted report")
    return out


def bridge_problems(raw):
    out = []
    start = raw.find("+ (NSDictionary<NSString*, NSString*>*)jitPairingReport")
    body = raw[start:raw.find("\n}\n", start)] if start >= 0 else ""
    for need in ("armsx3_jit_enabler_pairing_report(&report) != 0", "return @{};", '@"tried"', '@"documents"',
                 "rpcs3_report_word("):
        if need not in body:
            out.append("bridge: jitPairingReport lacks " + need)
    word = raw.find("static NSString* rpcs3_report_word(")
    filter_text = raw[word:raw.find("\n}\n", word)] if word >= 0 else ""
    if "c >= 'a' && c <= 'z'" not in filter_text or "c == '-'" not in filter_text:
        out.append("bridge: the report word filter does not keep only [a-z-]")
    return out


class TheLauncherLookup(unittest.TestCase):
    raw = LAUNCHER_M.read_text(encoding="utf-8")

    def mutated(self, old, new):
        self.assertEqual(1, self.raw.count(old), "mutation anchor: " + old)
        return lookup_problems(self.raw.replace(old, new))

    def test_baseline(self):
        self.assertEqual([], lookup_problems(self.raw))

    def test_missing_include_is_caught(self):
        self.assertTrue(any(p.startswith("include") for p in self.mutated('#include "pairing_shape.h"\n', "")))

    def test_dropping_the_documents_take_is_caught(self):
        got = self.mutated("if (premain) dropWord = Armsx3TakeDocumentsDrop(folder, path);", "if (premain) dropWord = nil;")
        self.assertTrue(any(p.startswith("documents") for p in got))

    def test_taking_the_drop_after_the_candidate_list_is_caught(self):
        step = ("    NSString* dropWord = nil;\n    if (premain) dropWord = Armsx3TakeDocumentsDrop(folder, path);\n"
                "    if (documentsWord != NULL) *documentsWord = dropWord;\n")
        late = self.raw.replace(step, "").replace("    return candidates;\n}", step + "    return candidates;\n}", 1)
        self.assertTrue(any(p.startswith("documents") for p in lookup_problems(late)))

    def test_skipping_the_lockdown_refusal_is_caught(self):
        got = self.mutated('    if (shape == ARMSX3_PAIRING_LOCKDOWN) return @"lockdown";\n', "")
        self.assertTrue(any(p.startswith("shape") for p in got))

    def test_origin_written_elsewhere_is_caught(self):
        got = self.mutated("[folder stringByAppendingPathComponent:kArmsx3PairingOriginName]",
                           '[folder stringByAppendingPathComponent:@"x"]')
        self.assertTrue(any(p.startswith("origin") for p in got))

    def test_removing_the_drop_before_the_swap_is_caught(self):
        anchor = "    [files createDirectoryAtPath:folder withIntermediateDirectories:YES attributes:nil error:nil];\n    NSString* incomingPath"
        got = self.mutated(anchor, "    [files removeItemAtPath:dropPath error:nil];\n" + anchor)
        self.assertTrue(any(p.startswith("origin") for p in got))

    def test_a_logged_path_is_caught(self):
        got = self.mutated('NSLog(@"[JITEnablerHost] pairingFile.plist moved out of Documents");',
                           'NSLog(@"[JITEnablerHost] moved %@", dropPath);')
        self.assertTrue(any(p.startswith("log") for p in got))


class TheLadder(unittest.TestCase):
    raw = LAUNCHER_M.read_text(encoding="utf-8")

    def caught(self, old, new, prefix):
        self.assertEqual(1, self.raw.count(old), "mutation anchor: " + old)
        problems = ladder_problems(self.raw.replace(old, new))
        self.assertTrue(any(p.startswith(prefix) for p in problems), problems)

    def test_baseline(self):
        self.assertEqual([], ladder_problems(self.raw))

    def test_a_removal_in_the_sidestore_code_is_caught(self):
        self.caught("    return found;", "    [[NSFileManager defaultManager] removeItemAtPath:folder error:nil];\n    return found;",
                    "sidestore")

    def test_a_write_in_the_sidestore_code_is_caught(self):
        self.caught("    return found;", "    [data writeToFile:folder atomically:YES];\n    return found;", "sidestore")

    def test_the_sidestore_files_in_the_wrong_order_are_caught(self):
        self.caught('@[@"PairingFile_RemoteRP.plist", @"ALTPairingFile.mobiledevicepairing"]',
                    '@[@"ALTPairingFile.mobiledevicepairing", @"PairingFile_RemoteRP.plist"]', "sidestore")

    def test_sidestore_without_livecontainer_is_caught(self):
        self.caught('const char* home = getenv("LC_HOME_PATH");', 'const char* home = "/x";', "sidestore")

    def test_sidestore_before_the_own_file_is_caught(self):
        add = "    [candidates addObjectsFromArray:Armsx3SideStoreCandidates()];\n"
        late = self.raw.replace(add, "").replace("    NSMutableArray<Armsx3PairingCandidate*>* candidates = [NSMutableArray array];\n",
                                                  "    NSMutableArray<Armsx3PairingCandidate*>* candidates = [NSMutableArray array];\n" + add, 1)
        self.assertTrue(any(p.startswith("order") for p in ladder_problems(late)))

    def test_retrying_on_every_word_is_caught(self):
        self.caught("!armsx3_pairing_should_retry(r.helper_outcome)", "false", "ladder")

    def test_trying_past_the_last_file_is_caught(self):
        self.caught("i + 1 == candidates.count", "false", "ladder")

    def test_ignoring_the_spent_budget_is_caught(self):
        self.caught("dispatch_time(DISPATCH_TIME_NOW, 0) >= deadline", "false", "ladder")

    def test_a_deadline_per_attempt_is_caught(self):
        self.caught("BOOL timedOut = (dispatch_semaphore_wait(done, deadline) != 0);",
                    "BOOL timedOut = (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 12LL * NSEC_PER_SEC)) != 0);",
                    "deadline")

    def test_a_main_queue_hop_is_caught(self):
        self.caught("            tried++;\n", "            tried++;\n            dispatch_async(dispatch_get_main_queue(), ^{});\n",
                    "pre-main")

    def test_a_documents_move_after_main_is_caught(self):
        self.caught("Armsx3PairingLookup(NO, NULL)", "Armsx3PairingLookup(YES, NULL)", "mount")

    def test_a_mount_that_ignores_the_report_is_caught(self):
        self.caught('strcmp(report.result, "accepted") == 0', "true", "mount")


class TheBridgeAccessor(unittest.TestCase):
    raw = BRIDGE_MM.read_text(encoding="utf-8")

    def test_baseline(self):
        self.assertEqual([], bridge_problems(self.raw))

    def test_an_unfiltered_word_is_caught(self):
        got = bridge_problems(self.raw.replace("c == '-'", "c == '/'"))
        self.assertTrue(any("filter" in p for p in got))

    def test_a_missing_getter_call_is_caught(self):
        got = bridge_problems(self.raw.replace("armsx3_jit_enabler_pairing_report(&report) != 0", "false"))
        self.assertTrue(any("lacks" in p for p in got))


if __name__ == "__main__":
    unittest.main()
