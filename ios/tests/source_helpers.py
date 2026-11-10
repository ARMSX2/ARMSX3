# SPDX-License-Identifier: GPL-2.0-only
"""Source-reading helpers shared by the guard tests."""

import re


DIRECTIVE = re.compile(r"^[ \t]*#[ \t]*(ifdef|ifndef|if|elif|else|endif)\b([^\n]*)$", re.M)


IOS_MACRO = "TARGET_OS_IPHONE"


class Arm(object):
    """One preprocessor arm: the condition that opened its group, and whether this is the else."""

    def __init__(self, condition, is_else):
        self.condition = condition
        self.is_else = is_else

    def mentions_ios(self):
        return IOS_MACRO in re.sub(r"\s+", "", self.condition)

    def describe(self):
        return "{0}{1}".format("#else of " if self.is_else else "", self.condition.strip() or "(none)")


def _condition(directive, rest):
    if directive == "if" or directive == "elif":
        return rest
    if directive == "ifndef":
        return "!defined({0})".format(rest.strip())
    return "defined({0})".format(rest.strip())


def arms_enclosing(source, index):
    """The stack of preprocessor arms enclosing `index`, outermost first.
    A directive on the same line but starting before `index` counts as enclosing.
    An unbalanced file raises, since a partial stack cannot be trusted."""
    stack = []
    for match in DIRECTIVE.finditer(source):
        if match.start() >= index:
            break
        directive = match.group(1)
        rest = match.group(2)
        if directive in ("if", "ifdef", "ifndef"):
            stack.append(Arm(_condition(directive, rest), False))
        elif directive == "elif":
            if not stack:
                raise AssertionError("#elif with no open conditional at offset {0}".format(match.start()))
            stack[-1] = Arm(rest, False)
        elif directive == "else":
            if not stack:
                raise AssertionError("#else with no open conditional at offset {0}".format(match.start()))
            stack[-1] = Arm(stack[-1].condition, True)
        else:
            if not stack:
                raise AssertionError("#endif with no open conditional at offset {0}".format(match.start()))
            stack.pop()
    return stack


def brace_block(text, start):
    """The `{ ... }` block opening at or after `start`, as (block_text, offset_of_open_brace).
    Raises when there is none, so a guard that cannot find its subject fails."""
    open_at = text.index("{", start)
    depth = 0
    index = open_at

    while index < len(text):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1

            if depth == 0:
                return text[open_at : index + 1], open_at

        index += 1

    raise AssertionError(
        "unbalanced braces from offset {0}. The guard cannot scope its assertions to a block it "
        "could not delimit.".format(start)
    )


def body(text, signature):
    found = re.search(signature, text)
    return brace_block(text, found.end())[0] if found else None


def targets_of(yaml_text):
    """Ordered {target name: block text} for the column-0 `targets:` section.
    Raises when the section is missing or empty, so the guard cannot pass over nothing."""
    lines = yaml_text.split("\n")
    starts = [i for i, line in enumerate(lines) if re.match(r"targets:\s*$", line)]
    if len(starts) != 1:
        raise AssertionError("project.yml has {0} column-0 targets: sections, expected one".format(len(starts)))

    end = len(lines)
    for i in range(starts[0] + 1, len(lines)):
        if lines[i] and not lines[i][0].isspace():
            end = i
            break

    targets = {}
    name = None
    for line in lines[starts[0] + 1 : end]:
        match = re.match(r"  ([A-Za-z0-9_.-]+):\s*$", line)
        if match:
            name = match.group(1)
            if name in targets:
                raise AssertionError("target {0} is declared twice".format(name))
            targets[name] = []
        if name is not None:
            targets[name].append(line)

    if not targets:
        raise AssertionError("the targets: section of project.yml holds no target")
    return {key: "\n".join(value) for key, value in targets.items()}


def yaml_without_comments(text):
    """Drop whole-line YAML comments, so prose naming a setting cannot satisfy a count."""
    kept = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        kept.append(line)
    return "\n".join(kept)


MM_ALLOWLIST = frozenset((
    "TargetConditionals.h",
    "UIKit/UIKit.h",
    "QuartzCore/QuartzCore.h",
    "ios_frame.h",
    "util/logs.hpp",
    "cstdio",
    "ctime",
    "rpcs3_ios_abi.h",
    "lifecycle_pause.h",
    "mutex",
    "present_gate.h",
    "ios/core/ios_stderr_witness.h",
))


SWITCH_SIGNATURE = "bool prepare_through_builtin_enabler("


def function_span(code, signature):
    """(start, end) offsets of the signature through the closing brace. Raises when not found."""
    start = code.index(signature)
    block, open_at = brace_block(code, start)
    return start, open_at + len(block)


def normalise(text):
    return re.sub(r"\s+", " ", text).strip()


def read_jit_default_route(path):
    if not path.is_file():
        raise AssertionError("expected route source not found: {0}. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("route source is empty: {0}. This guard fails rather than skipping.".format(path))
    return text


def switch_body(code):
    """The brace-matched body of the launch-time switch. Raises when not found."""
    block, _ = brace_block(code, code.index(SWITCH_SIGNATURE))
    return block


COPY = {
    "row2": "Turn on get-task-allow when you sign ARMSX3. No JIT method works without it.",
    "row3": "Built-in JIT isn't available inside LiveContainer. Choose StikDebug or Wait for Debugger.",
    "row4": "Import the pairing file made for this device.",
    "row4-sidestore": "Get the pairing file from SideStore. ARMSX3 opens SideStore, which sends the file back.",
    "row4-lc-iphone": "Import the pairing file made for this device. If SideStore is installed, it keeps one in Files under On My iPhone \u203a SideStore.",
    "row4-lc-ipad": "Import the pairing file made for this device. If SideStore is installed, it keeps one in Files under On My iPad \u203a SideStore.",
    "sidestore-nothing": "SideStore didn't send a pairing file. Update SideStore and try again, or import the file from Files.",
    "sidestore-lockdown": "SideStore sent an older lockdown pairing file. In SideStore, open Pairing File Management, long-press Remote Pairing File and choose Set as Preferred, then try again.",
    "other-share": "Share the file to ARMSX3 from Files or another app.",
    "other-documents": "Or put a file named pairingFile.plist in ARMSX3's folder in Files, then open ARMSX3 again.",
    "other-iloader": "On a computer, iLoader can make a pairing file for this device. Save it to Files, then import it here.",
    "row5": "Mount the Developer Disk Image. The first mount downloads about 16 MB.",
    "row7": "Close ARMSX3 from the app switcher and open it again. JIT turns on as it opens.",
    "row8": "Install StikDebug, then come back here.",
    "row9": "StikDebug didn't turn JIT on. In StikDebug, check that LocalDevVPN is on and a pairing file is imported, then try again.",
    "row10": "StikDebug turns on JIT for ARMSX3 and sends you back here.",
    "row11-mac": "ARMSX3 waits for a debugger as it opens. Close ARMSX3, then open it from StikDebug or attach a debugger from your Mac.",
    "row11-txm": "ARMSX3 waits for a debugger as it opens. Close ARMSX3, then open it from StikDebug.",
    "livecontainer": "In LiveContainer's settings, turn on Use LiveContainer's Bundle ID.",
    "helper-connect-failed": "ARMSX3 couldn't reach this device through LocalDevVPN. Turn LocalDevVPN on and stay on Wi-Fi, or turn on Airplane mode, then reopen ARMSX3.",
    "helper-tunnel-failed": "LocalDevVPN is on, but this device didn't accept the pairing. Make a new pairing file on a computer, import it here, then reopen ARMSX3.",
    "helper-pairing-rejected": "Your pairing file couldn't be read. Make a new pairing file on a computer and import it.",
    "helper-ddi-not-mounted": "The Developer Disk Image isn't mounted. Mount it here, then reopen ARMSX3.",
    "helper-unfinished": "JIT setup didn't finish. Close ARMSX3 fully and open it again.",
    "mount-unreachable": "Couldn't reach this device through LocalDevVPN. Turn LocalDevVPN on and stay on Wi-Fi, or turn on Airplane mode, then try again.",
    "mount-download": "The disk image didn't download. Check the internet connection, then try again.",
    "mount-pairing": "This device didn't accept the pairing file. Make a new pairing file on a computer and import it.",
    "mount-mount": "The disk image didn't mount. Restart this device, then try again. If it keeps failing, use Reset Developer Disk Image.",
    "mount-other": "Mounting didn't finish. Try again. If it keeps failing, restart this device.",
    "refusal-legacy": "This is an older lockdown pairing file. ARMSX3 needs an RPPairing file made for this device.",
    "refusal-invalid": "That file isn't a pairing file. Pick the pairing file made for this device.",
    "refusal-failed": "That file couldn't be imported. Try picking it again.",
    "severity": "ARMSX3 cannot run games without JIT. Without it, games do not start at all.",
    "setup-suffix": "Setup continues here.",
    "caption-builtin": "ARMSX3 turns on JIT by itself each time it opens. Needs a pairing file and LocalDevVPN.",
    "caption-stikdebug": "ARMSX3 asks the StikDebug app to turn on JIT. Needs StikDebug installed.",
    "caption-wait": "ARMSX3 waits for a debugger you attach yourself, from StikDebug or a Mac.",
    "fix-entitlement": "No JIT method works without it. Sign ARMSX3 again with get-task-allow turned on, then reinstall it.",
    "fix-memory": "Games are more likely to close when memory runs low. Sign ARMSX3 again with the increased-memory-limit entitlement.",
}


def braced(code, opener):
    """The text of `opener` through its matching close brace. Raises when `opener` is absent or unbalanced."""
    at = code.find(opener)
    if at < 0:
        raise AssertionError("no {0!r}".format(opener))
    depth = 0
    for i in range(code.index("{", at), len(code)):
        depth += {"{": 1, "}": -1}.get(code[i], 0)
        if depth == 0:
            return code[at:i + 1]
    raise AssertionError("unbalanced braces after {0!r}".format(opener))


def scan(swift):
    """(code, literals): the text without // and /* */ comments, and every string literal's body.
    Raises on a multi-line or unterminated literal."""
    if '"""' in swift:
        raise AssertionError('multi-line string literal: this scanner does not parse """')
    code, literals = [], []
    i, n = 0, len(swift)
    while i < n:
        if swift.startswith("//", i):
            end = swift.find("\n", i)
            i = n if end < 0 else end
            continue
        if swift.startswith("/*", i):
            end = swift.find("*/", i + 2)
            if end < 0:
                raise AssertionError("unclosed /* comment at offset {0}".format(i))
            code.append("\n" * swift.count("\n", i, end))
            i = end + 2
            continue
        if swift[i] == '"':
            j = i + 1
            while True:
                if j >= n or swift[j] == "\n":
                    raise AssertionError("unterminated string literal at offset {0}".format(i))
                if swift.startswith("\\(", j):
                    depth, j = 0, j + 1
                    while j < n and not (swift[j] == ")" and depth == 1):
                        depth += {"(": 1, ")": -1}.get(swift[j], 0)
                        j += 1
                elif swift[j] == "\\":
                    j += 1
                elif swift[j] == '"':
                    break
                j += 1
            literals.append(swift[i + 1:j])
            code.append(swift[i:j + 1])
            i = j + 1
            continue
        code.append(swift[i])
        i += 1
    return "".join(code), literals


def brace_span(text, start):
    """(open, close) offsets of the `{ ... }` block opening at or after `start`, brace-matched."""
    open_at = text.index("{", start)
    depth = 0
    for index in range(open_at, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return open_at, index + 1
    raise AssertionError("unbalanced braces from offset {0}".format(start))


def body_of(text, signature):
    match = re.search(signature, text)
    if match is None:
        return None
    start, end = brace_span(text, match.end())
    return text[start:end]


def in_order(body, calls):
    at = -1
    for call in calls:
        at = body.find(call, at + 1)
        if at == -1:
            return False
    return True


def read_library_view(path):
    if not path.is_file():
        raise AssertionError("{0} is missing. This guard fails rather than skipping.".format(path))
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. This guard fails rather than skipping.".format(path))
    return text


def balanced_body(source, open_index):
    """The brace-balanced text after the `{` at open_index, or None when it never closes."""
    depth = 0
    index = open_index

    while index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1

            if depth == 0:
                return source[open_index + 1:index]

        index += 1

    return None


def function_body_open(source, name):
    """The index of the `{` opening the one definition called `name`, or None when not exactly one.
    A call is `name ( ... )` followed by anything but a brace."""
    opens = []

    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", source):
        index = match.end() - 1
        depth = 0

        while index < len(source):
            if source[index] == "(":
                depth += 1
            elif source[index] == ")":
                depth -= 1

                if depth == 0:
                    break

            index += 1

        if index >= len(source):
            continue

        tail = source[index + 1:]
        after = tail.lstrip()

        if after.startswith("{"):
            opens.append(index + 1 + (len(tail) - len(after)))

    if len(opens) != 1:
        return None

    return opens[0]


def function_body(source, name):
    """The body of a function DEFINITION called `name`, or None when there is not exactly one."""
    open_index = function_body_open(source, name)
    return None if open_index is None else balanced_body(source, open_index)


def struct_body(source, name):
    """The body of `struct <name> ... { ... }`, or None unless it appears exactly once."""
    opens = []

    for match in re.finditer(r"\bstruct\s+" + re.escape(name) + r"\b[^;{]*\{", source):
        opens.append(match.end() - 1)

    if len(opens) != 1:
        return None

    return balanced_body(source, opens[0])


def without_comments_log_ring_contract(source):
    """Strip C block and line comments so prose cannot satisfy or fail a check about code.
    String literals are not respected, so over-stripping can only cause a false failure."""
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", " ", source)
    return source


def cmake_ios_only(cmake_code, name):
    """True when `name` appears in the CMake text and every appearance is inside an if(IOS) block."""
    depth = 0
    ios_depth = None
    seen = False
    for line in cmake_code.splitlines():
        if re.match(r"^\s*if\s*\(\s*IOS\s*\)", line):
            depth += 1
            if ios_depth is None:
                ios_depth = depth
            continue
        if re.match(r"^\s*if\s*\(", line):
            depth += 1
            continue
        if re.match(r"^\s*endif\s*\(", line):
            if ios_depth is not None and depth == ios_depth:
                ios_depth = None
            depth = max(0, depth - 1)
            continue
        if re.search(r"(^|[\s(])" + re.escape(name) + r"(\s|$|\))", line):
            seen = True
            if ios_depth is None:
                return False
    return seen


GATE = re.compile(r"if\s*\(\s*pthread_main_np\s*\(\s*\)\s*!=\s*0\s*\)")


LOCK = re.compile(r"std::lock_guard\s+\w+\s*\(\s*pad::g_pad_mutex\s*\)")


LOGGING = re.compile(r"\b(?:std::)?f?printf\s*\(|\b(?:armsx3_)?stderr_witness_printf\s*\(|\bNSLog\s*\(|\bsys_log\b|\.(?:notice|error|warning|success|trace)\s*\(")


RELAXED = re.compile(r"pad::get_pad_thread\s*\(\s*true\s*\)")


UNAVAILABLE = (
    ("null thread", re.compile(r"if\s*\(\s*thread\s*==\s*nullptr\s*\)\s*\{?\s*return\s+RPCS3_IOS_STATUS_PAD_UNAVAILABLE\s*;")),
    ("null pad", re.compile(r"if\s*\(\s*!\s*pad\s*\)\s*\{?\s*return\s+RPCS3_IOS_STATUS_PAD_UNAVAILABLE\s*;")),
    ("no table button", re.compile(r"if\s*\(\s*!\s*matched\s*\)\s*\{?\s*return\s+RPCS3_IOS_STATUS_PAD_UNAVAILABLE\s*;")),
)


def definition_body(source, name):
    """The brace-matched body of the definition of `name`, or None when there is none."""
    match = re.search(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*\{", source)
    if match is None:
        return None
    start, end = brace_span(source, match.end() - 1)
    return source[start:end]


def read(path):
    """Missing or empty input raises: a guard with nothing to read must fail, not pass."""
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty".format(path))
    return text


def without_comments(source):
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


def live_code(text):
    # Block and line comments dropped, so commented-out text cannot satisfy a check.
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", text)


def read_rpcn_screens(path):
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        raise AssertionError("{0} is empty. FAILURE, not skip.".format(path))
    return text


ABI_CALL = re.compile(r"\b(?:Rpcs3IOS_\w+|armsx3_ios_prepare_host|armsx3_launch_record_write)\s*\(")


IMPL_INCLUDES = {
    "Rpcs3Bridge.h",
    "../../bridge/rpcs3_ios_abi.h",
    "../../bridge/ios_frame.h",
    "../../core/render/ios_host.h",
    "../Support/launch_record.h",
    "../Support/ios_audio_session.h",
    # The built-in JIT launcher's result snapshot, for +jitHelperOutcome.
    "../../jit-enabler/StikJITExtensionLauncher.h",
    # The stderr witness's C ABI, for +stderrWitnessDropped and +installStderrDrain (plain C).
    "../../core/ios_stderr_witness.h",
}


PAD_CALLS = ("Rpcs3IOS_SetPadState", "Rpcs3IOS_SetPressureModifier", "Rpcs3IOS_SetPlayerPadState",
             "Rpcs3IOS_SetPlayerConnected", "Rpcs3IOS_SetPlayerMotion", "Rpcs3IOS_KeyboardKey",
             "Rpcs3IOS_SetKeyboardConnected", "Rpcs3IOS_MouseButton", "Rpcs3IOS_MouseMove", "Rpcs3IOS_MouseScroll",
             "Rpcs3IOS_SetMouseConnected", "Rpcs3IOS_GetMoveColor", "Rpcs3IOS_GetPlayerRumble",
             "Rpcs3IOS_SaveStateProgress", "Rpcs3IOS_MouseAim", "Rpcs3IOS_MouseAimButton")


def hop_spans(text, hop):
    """The brace spans of every `hop, ^{ ... }` block in `text`."""
    spans = []
    offset = text.find(hop)
    while offset != -1:
        spans.append(brace_span(text, offset))
        offset = text.find(hop, offset + 1)
    return spans


def strip_line(line):
    """Remove a trailing shell comment from one line, respecting quotes.
    A `#` starts a comment only when unquoted and at the start of a word, so `${#var}` and `$#` stay."""
    in_single = False
    in_double = False
    index = 0
    length = len(line)

    while index < length:
        char = line[index]

        if in_single:
            if char == "'":
                in_single = False
        elif in_double:
            if char == "\\":
                index += 2
                continue
            if char == '"':
                in_double = False
        else:
            if char == "'":
                in_single = True
            elif char == '"':
                in_double = True
            elif char == "#":
                if index > 0 and line[index - 1] in "${":
                    index += 1
                    continue
                if index == 0 or line[index - 1] in " \t":
                    return line[:index]

        index += 1

    return line


def without_comments_run_arm_returns_after_success(source):
    return "\n".join(strip_line(line) for line in source.split("\n"))


def without_c_comments(source):
    """Strip C block and line comments, so prose naming a symbol cannot satisfy a check.
    String literals are not respected, so over-stripping can only cause a false failure."""
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", " ", source)
    return source


def block_at(code, start):
    """Text from the first `{` at or after `start` to its matching `}`, or None."""
    open_at = code.find("{", start)
    if open_at < 0:
        return None
    depth = 0
    for i in range(open_at, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[open_at:i + 1]
    return None


def swift_name(objc_suffix):
    """How Swift imports an NS_ENUM case: `Cross` -> `cross`, but `L1`, `PS` keep their capitals."""
    if len(objc_suffix) > 1 and (objc_suffix[1].isupper() or objc_suffix[1].isdigit()):
        return objc_suffix
    return objc_suffix[0].lower() + objc_suffix[1:]


ENUM_CASE = re.compile(r"\bRpcs3PadButton(\w+)\s*=\s*(\d+)")


def enum_cases(header):
    cases = {swift_name(name): int(value) for name, value in ENUM_CASE.findall(without_comments(header))}
    if not cases:
        raise AssertionError("Rpcs3Bridge.h declares no Rpcs3PadButton case")
    return cases


def abi_text(bridge_dir):
    """The C ABI implementation as one text: every rpcs3_ios_abi_<area>.cpp part, then the shared internal header.

    The header comes last so a search for a definition meets the definition before its prototype."""
    files = sorted(bridge_dir.glob("rpcs3_ios_abi_*.cpp")) + [bridge_dir / "rpcs3_ios_abi_internal.h"]
    text = "\n".join(f.read_text(encoding="utf-8") for f in files)
    if not text.strip():
        raise AssertionError("{0} holds no ABI implementation".format(bridge_dir))
    return text


def facade_text(bridge_dir):
    """The Objective-C++ facade as one text: Rpcs3Bridge.mm, its Rpcs3Bridge+<Area>.mm categories, then the shared internal header."""
    files = [bridge_dir / "Rpcs3Bridge.mm"] + sorted(bridge_dir.glob("Rpcs3Bridge+*.mm")) + [bridge_dir / "Rpcs3BridgeInternal.h"]
    text = "\n".join(f.read_text(encoding="utf-8") for f in files)
    if not text.strip():
        raise AssertionError("{0} holds no facade implementation".format(bridge_dir))
    return text


def dual_map_text(core_dir):
    """The JIT dual mapping as one text: the mapping part, the prepare and readback parts, then the private header."""
    names = ("ios_jit_dual_map_mapping.cpp", "ios_jit_dual_map_prepare.cpp", "ios_jit_dual_map_readback.cpp", "ios_jit_dual_map_private.h")
    text = "\n".join((core_dir / name).read_text(encoding="utf-8") for name in names)
    if not text.strip():
        raise AssertionError("{0} holds no dual mapping".format(core_dir))
    return text
