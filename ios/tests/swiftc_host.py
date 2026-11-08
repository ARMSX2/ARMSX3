# SPDX-License-Identifier: GPL-2.0-only
"""Compile pure Swift sources with a driver on the Mac host, run the binary and return its stdout.
Skips only when xcrun or swiftc is absent; a compile failure or non-zero exit raises AssertionError.
Only Foundation sources belong here, plus Compression for ZipArchive.swift and ThemeBytes.swift, Security for SonyTrust.swift and CryptoKit for PackageDigest.swift."""

import shutil
import subprocess
import unittest
from pathlib import Path

COMPILE_TIMEOUT_SECONDS = 300
RUN_TIMEOUT_SECONDS = 30


def compile_and_run(sources, driver_swift, tmp):
    if shutil.which("xcrun") is None:
        raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
    found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "swiftc"],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    if found.returncode != 0:
        raise unittest.SkipTest("xcrun finds no macOS `swiftc`; a skip is not a pass")

    tmp = Path(tmp)
    for source in sources:
        if not Path(source).is_file():
            raise AssertionError("{0} is missing. FAILURE, not skip.".format(source))
    driver = tmp / "main.swift"
    binary = tmp / "driver"
    driver.write_text(driver_swift, encoding="utf-8")
    sources = [str(s) for s in sources]
    built = subprocess.run(
        ["xcrun", "--sdk", "macosx", "swiftc", "-O", "-o", str(binary)] + [str(s) for s in sources] + [str(driver)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
    )
    if built.returncode != 0:
        raise AssertionError("swiftc failed:\n" + built.stdout)
    ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True)
    if ran.returncode != 0:
        raise AssertionError("the driver exited {0}:\n{1}".format(ran.returncode, ran.stdout))
    return ran.stdout
