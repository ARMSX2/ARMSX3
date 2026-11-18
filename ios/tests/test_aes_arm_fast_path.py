# SPDX-License-Identifier: GPL-2.0-only
"""rpcs3/Crypto/aes.cpp built for the Mac host twice, once as is (the AESE path on Apple arm64) and once with the
Apple arm64 blocks cut out (the software AES): both give the FIPS-197 and SP 800-38A answers and the same bytes
for CTR, CBC and decryption over a random buffer.
"""

import platform
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

SOURCE = ROOT / "rpcs3" / "Crypto" / "aes.cpp"

APPLE_ARM_BLOCK = re.compile(r"#if defined\(__APPLE__\) && defined\(__aarch64__\)\r?\n.*?#endif\r?\n", re.S)

COMPILE_TIMEOUT_SECONDS = 120

RUN_TIMEOUT_SECONDS = 60

EXPECTED_VECTORS = [
    "ecb128 69c4e0d86a7b0430d8cdb78070b4c55a",
    "ecb192 dda97ca4864cdfe06eaf70a0ec0d7191",
    "ecb256 8ea2b7ca516745bfeafc49904b496089",
    "ctr128 874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff"
    "5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee",
]

DRIVER = r'''
#include "aes.h"
#include <cstdio>
#include <vector>

static void hex(const char* name, const unsigned char* p, size_t n)
{
	std::printf("%s ", name);
	for (size_t i = 0; i < n; i++)
		std::printf("%02x", p[i]);
	std::printf("\n");
}

static unsigned long long fnv(const std::vector<unsigned char>& v)
{
	unsigned long long h = 0xcbf29ce484222325ull;
	for (unsigned char c : v)
		h = (h ^ c) * 0x100000001b3ull;
	return h;
}

static unsigned long long state = 0x9e3779b97f4a7c15ull;

static unsigned char next_byte()
{
	state ^= state << 13; state ^= state >> 7; state ^= state << 17;
	return static_cast<unsigned char>(state >> 24);
}

int main()
{
	unsigned char key[32], pt[16], out[16];
	for (int i = 0; i < 32; i++) key[i] = static_cast<unsigned char>(i);
	for (int i = 0; i < 16; i++) pt[i] = static_cast<unsigned char>(i * 0x11);

	const unsigned bits[3] = {128, 192, 256};
	const char* names[3] = {"ecb128", "ecb192", "ecb256"};
	for (int k = 0; k < 3; k++)
	{
		aes_context ctx;
		aes_setkey_enc(&ctx, key, bits[k]);
		aes_crypt_ecb(&ctx, AES_ENCRYPT, pt, out);
		hex(names[k], out, 16);
	}

	const unsigned char ctr_key[16] = {0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6, 0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c};
	const unsigned char ctr_pt[64] = {
		0x6b, 0xc1, 0xbe, 0xe2, 0x2e, 0x40, 0x9f, 0x96, 0xe9, 0x3d, 0x7e, 0x11, 0x73, 0x93, 0x17, 0x2a,
		0xae, 0x2d, 0x8a, 0x57, 0x1e, 0x03, 0xac, 0x9c, 0x9e, 0xb7, 0x6f, 0xac, 0x45, 0xaf, 0x8e, 0x51,
		0x30, 0xc8, 0x1c, 0x46, 0xa3, 0x5c, 0xe4, 0x11, 0xe5, 0xfb, 0xc1, 0x19, 0x1a, 0x0a, 0x52, 0xef,
		0xf6, 0x9f, 0x24, 0x45, 0xdf, 0x4f, 0x9b, 0x17, 0xad, 0x2b, 0x41, 0x7b, 0xe6, 0x6c, 0x37, 0x10};
	unsigned char nonce[16], block[16], ctr_out[64];
	for (int i = 0; i < 16; i++) nonce[i] = static_cast<unsigned char>(0xf0 + i);
	size_t off = 0;
	aes_context ctr;
	aes_setkey_enc(&ctr, ctr_key, 128);
	aes_crypt_ctr(&ctr, 64, &off, nonce, block, ctr_pt, ctr_out);
	hex("ctr128", ctr_out, 64);

	for (int k = 0; k < 3; k++)
	{
		unsigned char rkey[32], iv[16];
		for (auto& c : rkey) c = next_byte();
		for (auto& c : iv) c = next_byte();
		iv[15] = 0xfe; iv[14] = 0xff;

		std::vector<unsigned char> data((1 << 20) + 7);
		for (auto& c : data) c = next_byte();
		const std::vector<unsigned char> plain = data;

		aes_context ctx;
		aes_setkey_enc(&ctx, rkey, bits[k]);
		off = 0;
		const size_t splits[3] = {13, 4096 + 5, data.size() - 13 - 4096 - 5};
		size_t pos = 0;
		for (size_t len : splits)
		{
			aes_crypt_ctr(&ctx, len, &off, iv, block, data.data() + pos, data.data() + pos);
			pos += len;
		}
		std::printf("rnd_ctr%u %016llx off=%zu ", bits[k], fnv(data), off);
		hex("iv", iv, 16);

		std::vector<unsigned char> cbc(plain.begin(), plain.begin() + 4096);
		unsigned char cbc_iv[16] = {};
		aes_crypt_cbc(&ctx, AES_ENCRYPT, cbc.size(), cbc_iv, cbc.data(), cbc.data());
		std::printf("rnd_cbc%u %016llx\n", bits[k], fnv(cbc));

		aes_context dec;
		aes_setkey_dec(&dec, rkey, bits[k]);
		unsigned char back_iv[16] = {};
		aes_crypt_cbc(&dec, AES_DECRYPT, cbc.size(), back_iv, cbc.data(), cbc.data());
		std::printf("rnd_cbc_back%u %d\n", bits[k], cbc == std::vector<unsigned char>(plain.begin(), plain.begin() + 4096));
	}
	return 0;
}
'''


def build_and_run(tmp, name, source_text):
    source = Path(tmp) / (name + ".cpp")
    source.write_text(source_text, encoding="utf-8", newline="")
    driver, binary = Path(tmp) / "driver.cpp", Path(tmp) / name
    driver.write_text(DRIVER, encoding="utf-8")
    built = subprocess.run(
        ["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-O2", "-march=armv8.4-a", "-I", str(SOURCE.parent), "-o", str(binary),
         str(driver), str(source)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS, universal_newlines=True,
    )
    if built.returncode != 0:
        raise AssertionError("clang++ failed for {0}:\n{1}".format(name, built.stdout))
    disasm = subprocess.run(["xcrun", "otool", "-tv", str(binary)], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, universal_newlines=True).stdout
    ran = subprocess.run([str(binary)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
    if ran.returncode != 0:
        raise AssertionError("{0} exited {1}:\n{2}".format(name, ran.returncode, ran.stdout[-4000:]))
    return ran.stdout.splitlines(), "aese" in disasm


class TheArmPathMatchesTheSoftwareAes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if platform.machine() != "arm64":
            raise unittest.SkipTest("the AESE path needs an arm64 Mac; a skip is not a pass")
        if shutil.which("xcrun") is None:
            raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
        if not SOURCE.is_file():
            raise AssertionError("{0} is missing. FAILURE, not skip.".format(SOURCE))

        text = SOURCE.read_text(encoding="utf-8", newline="")
        software, cut = APPLE_ARM_BLOCK.subn("", text)
        if cut != 2:
            raise AssertionError("expected the include and the AESE block under the Apple arm64 guard, found {0}"
                                 .format(cut))

        with tempfile.TemporaryDirectory() as tmp:
            cls.fast, cls.fast_has_aese = build_and_run(tmp, "fast", text)
            cls.soft, cls.soft_has_aese = build_and_run(tmp, "soft", software)

    def test_the_fast_build_uses_aese_and_the_software_build_does_not(self):
        self.assertTrue(self.fast_has_aese)
        self.assertFalse(self.soft_has_aese)

    def test_both_give_the_published_vectors(self):
        self.assertEqual(EXPECTED_VECTORS, self.fast[:4])
        self.assertEqual(EXPECTED_VECTORS, self.soft[:4])

    def test_both_give_the_same_bytes_on_random_data(self):
        self.assertEqual(13, len(self.fast))
        self.assertEqual(self.soft, self.fast)
        self.assertEqual(["rnd_cbc_back{0} 1".format(b) for b in (128, 192, 256)],
                         [line for line in self.fast if line.startswith("rnd_cbc_back")])


if __name__ == "__main__":
    unittest.main()
