# SPDX-License-Identifier: GPL-2.0-only
"""No code-signing material is tracked by git, checked over `git ls-files` so a force-added file is caught
and an ignored local certificate is not."""

import subprocess
import unittest
from pathlib import Path


# ios/tests/ is two levels below the repository root.
ROOT = Path(__file__).resolve().parents[2]

# The extensions .gitignore covers: PKCS#12 archives, provisioning profiles, certificate signing requests.
SIGNING_EXTENSIONS = (".p12", ".mobileprovision", ".certSigningRequest")

IRREVERSIBLE = (
    "A tracked private key or provisioning profile is an irreversible credential "
    "disclosure. Rewriting git history after a push does not un-publish it, so the "
    "only real remedy is revoking the identity and issuing a new one."
)


def tracked_paths():
    """Every path in the git index, NUL-split so a path containing a newline cannot hide."""
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=str(ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    listing = result.stdout.decode("utf-8", errors="replace")
    return [entry for entry in listing.split("\0") if entry]


class NoSigningMaterialTracked(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tracked = tracked_paths()

    def test_no_tracked_file_carries_a_signing_material_extension(self):
        for extension in SIGNING_EXTENSIONS:
            with self.subTest(extension=extension):
                suffix = extension.lower()
                offenders = [p for p in self.tracked if p.lower().endswith(suffix)]
                self.assertEqual(
                    [],
                    offenders,
                    "{0} is tracked in git: {1}. {2}".format(
                        extension, ", ".join(offenders), IRREVERSIBLE
                    ),
                )


if __name__ == "__main__":
    unittest.main()
