# The built-in JIT enabler: attribution ledger

Plan 05-20. SPDX-keyed attribution for every upstream file `../vendor.tsv` marks INCLUDE, and for the
idevice source build. This project is GPL-2.0-only; each section below says why its licence may be
combined with it and what obligation it carries. ~~The licence texts sit beside this file:
`MPL-2.0.txt`, `GPL-2.0-or-later.txt` (the GNU GPL version 2 text, under which the "or later" grant
is exercised as version 2) and `MIT.txt`.~~ *(Struck 2026-10-18, plan 15-01, D-15-13: the list gained
the CDLA text.)* The licence texts sit beside this file: `MPL-2.0.txt`, `GPL-2.0-or-later.txt` (the GNU
GPL version 2 text, under which the "or later" grant is exercised as version 2), `MIT.txt` and
`CDLA-Permissive-2.0.txt`.

## SPDX: MPL-2.0 — StikJIT

Upstream `StikDebug/StikJIT`, pin `3623e725876f76aecb0520582ad6194bacb15d39`. Licence text:
`MPL-2.0.txt` (the upstream `LICENSE`, byte-identical, blob `a612ad9813b006ce81d1ee438dd784da99a54007`).

| File | Upstream | Pin | SPDX |
|---|---|---|---|
| `LICENSE` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/BundledScript.swift` | `StikDebug/StikJIT` | `32287268fa5824f9edce4cb359f5833ce0cf7b00` | MPL-2.0 |
| `Sources/DDISession.swift` | `StikDebug/StikJIT` | `32287268fa5824f9edce4cb359f5833ce0cf7b00` | MPL-2.0 |
| `Sources/DeveloperDiskImageService.swift` | `StikDebug/StikJIT` | `32287268fa5824f9edce4cb359f5833ce0cf7b00` | MPL-2.0 |
| `Sources/EndpointProbe.swift` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/IdeviceFFI.swift` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/JITSession.swift` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/ProcessInfo+TXM.swift` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/ScriptRunner.swift` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/StikJIT.swift` | `StikDebug/StikJIT` | `32287268fa5824f9edce4cb359f5833ce0cf7b00` | MPL-2.0 |
| `Sources/StikJITError.swift` | `StikDebug/StikJIT` | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |
| `Sources/SynchronousDDIDownloader.swift` | `StikDebug/StikJIT` | `32287268fa5824f9edce4cb359f5833ce0cf7b00` | MPL-2.0 |
| `engine/BlessCommands.swift` (derived, plan 05-21) | `StikDebug/StikJIT` `Sources/ScriptRunner.swift` with the audited full-width fix | `3623e725876f76aecb0520582ad6194bacb15d39` | MPL-2.0 |

**Why it combines.** MPL-2.0 §3.3 lets a Larger Work combine Covered Software with a work under a
Secondary License, and §1.12 names the GNU GPL version 2.0 as one. That permission is withdrawn only by
Exhibit B ("Incompatible With Secondary Licenses"), and no StikJIT file carries Exhibit B — no file
carries a header at all, and the `LICENSE` holds only the stock template of the exhibits. **Obligation:**
the MPL-2.0 files stay under MPL-2.0, their source (including the locally patched `ScriptRunner.swift`)
is made available, and `MPL-2.0.txt` ships with them.

*Plan 05-21 Task 1.* The eleven `Sources/*.swift` files are tracked under `ios/jit-enabler/engine/`,
byte-identical to the pin except `ScriptRunner.swift`, which carries the audited full-width fix
(`05-18-evidence/licence/scriptrunner-fullwidth.patch`). `engine/BlessCommands.swift` is a derived
MPL-2.0 file: the patched encoder's bodies copied verbatim, with the MPL-2.0 Exhibit A notice and no
Exhibit B. ~~It is the only engine file the `JITEnabler` extension compiles.~~ *(Struck 2026-10-18, plan 15-04:
already stale since quick 261018-fe8.)* The `JITEnabler` extension now compiles seven engine files:
`BlessCommands.swift`, `EndpointProbe.swift`, `DeveloperDiskImageService.swift`, `StikJITError.swift`,
`DDISession.swift`, `SynchronousDDIDownloader.swift` and `IdeviceFFI.swift` (the last three since plan
15-04, for the helper's Mount op). All are unmodified MPL-2.0 copies except `BlessCommands.swift`, the
derived encoder above. `StikJIT.swift` stays uncompiled: the one type `DDISession.swift` names from it,
`StikJIT.Configuration`, is declared in this port's own `JITEnablerExtension/DDIMount.swift`
(GPL-2.0-only), not copied from it.
Since plan 15-01 (2026-10-18), five of those engine copies, `DDISession.swift`,
`DeveloperDiskImageService.swift`, `SynchronousDDIDownloader.swift`, `StikJIT.swift` and
`BundledScript.swift`, are byte-identical to `32287268fa5824f9edce4cb359f5833ce0cf7b00` instead, upstream's
cryptex-aware versions, re-pinned on the owner's yes (D-15-13).

## SPDX: GPL-2.0-or-later — Dolphin iOS pull request #277

Upstream `OatmealDome/dolphin-ios`, pull request #277, head `a848f126c2929f70879ab1b09a450237f1b23ed9`,
open and unmerged when audited. Licence text: `GPL-2.0-or-later.txt`.

| File | Upstream | Pin | SPDX |
|---|---|---|---|
| `Source/iOS/App/Common/Jit/StikJITExtensionLauncher.h` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/StikJITExtensionLauncher.m` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/JITEnablerExtension/main.m` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/JITEnablerExtension/JITEnablerRequestHandler.swift` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/JITEnablerExtension/Info.plist` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/JITEnablerExtension/JITEnablerExtension.entitlements` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/JitManager+StikJIT.swift` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/StikJITManager.swift` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Common/Jit/DDIManager.swift` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |
| `Source/iOS/App/Project/Config/JITEnablerExtension.xcconfig` | `OatmealDome/dolphin-ios` | `a848f126c2929f70879ab1b09a450237f1b23ed9` | GPL-2.0-or-later |

**Why it combines.** GPL-2.0-or-later includes GPL-2.0, so the files are used under GPL-2.0, this
project's own licence. `Info.plist` and the `.entitlements` carry no header; they are treated
conservatively as the repository's declared GPL-2.0-or-later, which is compatible either way.
**Obligation:** every adapted file keeps the upstream `Copyright 2025 DolphiniOS Project` line and its
`SPDX-License-Identifier: GPL-2.0-or-later` line, and names the pull request and head pin.

## SPDX: MIT — idevice, built from source

Upstream `jkcoxson/idevice`, tag `v0.1.68` = `d32c8189c51c2789496b0768039419c3705498c3`, built by
`../build-idevice.sh` into the gitignored tree; no prebuilt is taken. Licence text: `MIT.txt` (the
upstream `LICENSE.txt` at the pin, byte-identical). The resolved crates were audited in plan 05-18 (Q2):
none is Apache-2.0-only, OpenSSL/SSLeay, AGPL or GPL-3.0-only, and neither `aws-lc-rs` nor `ring` is in
the build. **Obligation:** the copyright and permission notice in `MIT.txt` ship with the helper.

**`plist_ffi` 0.1.6 — owner acceptance of the manifest-declared MIT.** One C file compiled into the
archive, `plist_ffi` 0.1.6 `src/shims.c`, comes from a crate whose manifest declares `license = "MIT"`
but which ships no licence file, and neither does its upstream repository. Plan 05-18 flagged it
(Q2 INCONCLUSIVE on that paperwork gap alone). **The owner accepted the manifest's MIT declaration as the
grant** on 2026-09-26, after plan 05-18 Task 7, choosing the option "Accept manifest MIT (Recommended)":
take the MIT declared in the crate's Cargo manifest, record it, Q2 cleared to GO for the build plan. The
crate's author is idevice's author, whose `LICENSE.txt` is the MIT text in `MIT.txt`.

**webpki-roots 1.0.6 — CDLA-Permissive-2.0, owner acceptance (D-15-13, 2026-10-18).** Plan 15-01
rebuilds idevice with the `tss` and `cryptexd` features, so the helper can mount the Developer Disk
Image. The licence audit for that rebuild (15-RESEARCH, "The cryptexd licence audit") found exactly one
crate added to the resolved build: `webpki-roots` 1.0.6, whose manifest declares
`license = "CDLA-Permissive-2.0"`. It is pulled in only by idevice's `_http` feature, which `tss`
enables. It is Mozilla's root certificate authority list, compiled into the helper's archive as data.
CDLA-Permissive-2.0 §2.1 sets one condition: a recipient who shares the data "makes available the text
of this agreement with the shared Data". That condition is met by `CDLA-Permissive-2.0.txt` beside this
file, which is the crate's own `LICENSE`, byte-identical. The licence is a data licence and is not
OSI-approved; the owner accepted it on 2026-10-18 (D-15-13), as with `plist_ffi` above. No source states
that CDLA-Permissive-2.0 is compatible with the GPL, so this is recorded as the owner's acceptance, not
as a finding. **Obligation:** `CDLA-Permissive-2.0.txt` ships with the helper.

## Excluded, and why nothing below is attributed

`universal.js` and `legacy.js` (licence unstated, inferred AGPL-3.0), StikJIT's prebuilt
`libidevice_ffi.a` and `idevice.h`, the Dolphin pull request's prebuilt `StikJIT.xcframework`, and every
Manic EMU file (AGPL-3.0) are EXCLUDE rows in `../vendor.tsv`. None is fetched, built or tracked, so none
is attributed here.
