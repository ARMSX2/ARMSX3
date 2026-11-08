# SPDX-License-Identifier: GPL-2.0-only
"""The recording writer run on the host against Apple's encoder: both files play back red with sound, and a cut-off file still plays.
"""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


IOS_DIR = Path(__file__).resolve().parents[1]

WRITER_H = IOS_DIR / "bridge" / "ios_video_writer.h"

WRITER_MM = IOS_DIR / "bridge" / "ios_video_writer.mm"

WRITER_API = IOS_DIR / "bridge" / "video_writer.h"

VSCUT_LINE = "VSCUT video=1 audio=1 decoded=1 duration_ok=1"

VS_LINE = "VS video=1 codec=avc1 audio=1 duration_ok=1 rgba=1 bgra=1"

FRAMEWORKS = ("AVFoundation", "CoreMedia", "CoreVideo", "Accelerate", "Foundation", "CoreGraphics")

COMPILE_TIMEOUT_SECONDS = 300

RUN_TIMEOUT_SECONDS = 120

DRIVER = r'''// Host driver for test_video_sink.py. A macOS HOST run, never device evidence.
#import <AVFoundation/AVFoundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <CoreMedia/CoreMedia.h>

#include "ios_video_writer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace
{
	constexpr unsigned k_frame_w = 640, k_frame_h = 360, k_pitch = k_frame_w * 4 + 64;
	constexpr unsigned k_rate = 48000, k_block = 1024;

	// The writer drops what its input is not ready for; the driver offers the same sample again a little later.
	template <typename F>
	bool offered(F&& append)
	{
		for (int i = 0; i < 500; ++i)
		{
			if (append())
				return true;
			std::this_thread::sleep_for(std::chrono::milliseconds(2));
		}
		return false;
	}

	bool write_file(const std::string& path, bool bgra)
	{
		armsx3::bridge::video_writer_settings s;
		s.path = path;
		s.width = 320;
		s.height = 180;
		s.fps = 30;
		s.video_bps = 1000000;
		s.sample_rate = k_rate;
		s.audio_bps = 192000;
		const auto w = armsx3::bridge::make_avfoundation_video_writer(s);
		if (!w)
			return false;

		std::vector<std::uint8_t> frame(std::size_t{k_pitch} * k_frame_h, 0);
		const std::uint8_t px[4] = {std::uint8_t(bgra ? 0 : 255), 0, std::uint8_t(bgra ? 255 : 0), 255};
		for (unsigned y = 0; y < k_frame_h; ++y)
			for (unsigned x = 0; x < k_frame_w; ++x)
				std::copy(px, px + 4, frame.data() + std::size_t{y} * k_pitch + x * 4);

		std::vector<float> tone(k_block * 2);
		const unsigned total = k_rate * 2;
		unsigned fed = 0;
		for (unsigned f = 0; f < 60; ++f)
		{
			const std::int64_t pts = std::int64_t{f} * 33;
			if (!offered([&] { return w->append_video(frame.data(), k_pitch, k_frame_w, k_frame_h, bgra, pts); }))
				return false;
			const unsigned until = f == 59 ? total : std::min(total, unsigned((pts + 33) * k_rate / 1000));
			while (fed < until)
			{
				const unsigned n = std::min(k_block, total - fed);
				for (unsigned i = 0; i < n; ++i)
				{
					const float v = 0.25f * std::sin(2.0f * float(M_PI) * 440.0f * float(fed + i) / float(k_rate));
					tone[i * 2] = v;
					tone[i * 2 + 1] = v;
				}
				const std::int64_t ts = std::int64_t{fed} * 1000000 / k_rate;
				if (!offered([&] { return w->append_audio(tone.data(), n, 2, ts); }))
					return false;
				fed += n;
			}
		}
		return w->finish();
	}

	struct read_back
	{
		bool video = false, avc1 = false, audio = false, duration = false;
		double seconds = 0;
		int r = -1, g = -1, b = -1;
	};

	read_back read_file(const std::string& path)
	{
		read_back out;
		AVURLAsset* asset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:@(path.c_str())] options:nil];
		dispatch_semaphore_t done = dispatch_semaphore_create(0);
		__block NSArray<AVAssetTrack*>* videos = nil;
		__block NSArray<AVAssetTrack*>* audios = nil;
		[asset loadTracksWithMediaType:AVMediaTypeVideo completionHandler:^(NSArray<AVAssetTrack*>* t, NSError*) {
			videos = t;
			dispatch_semaphore_signal(done);
		}];
		dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
		[asset loadTracksWithMediaType:AVMediaTypeAudio completionHandler:^(NSArray<AVAssetTrack*>* t, NSError*) {
			audios = t;
			dispatch_semaphore_signal(done);
		}];
		dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
		out.video = videos.count == 1;
		out.audio = audios.count == 1;
		if (out.video)
		{
			__block NSArray* formats = nil;
			[videos[0] loadValuesAsynchronouslyForKeys:@[@"formatDescriptions"] completionHandler:^{
				formats = videos[0].formatDescriptions;
				dispatch_semaphore_signal(done);
			}];
			dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
			out.avc1 = formats.count == 1 &&
				CMFormatDescriptionGetMediaSubType((__bridge CMFormatDescriptionRef)formats[0]) == 'avc1';
		}
		[asset loadValuesAsynchronouslyForKeys:@[@"duration"] completionHandler:^{
			dispatch_semaphore_signal(done);
		}];
		dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
		out.seconds = CMTimeGetSeconds(asset.duration);
		out.duration = std::fabs(out.seconds - 2.0) <= 0.25;

		AVAssetImageGenerator* generator = [AVAssetImageGenerator assetImageGeneratorWithAsset:asset];
		__block CGImageRef image = nullptr;
		[generator generateCGImageAsynchronouslyForTime:CMTimeMake(1, 1)
			completionHandler:^(CGImageRef decoded, CMTime, NSError*) {
				image = decoded ? CGImageRetain(decoded) : nullptr;
				dispatch_semaphore_signal(done);
			}];
		dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
		if (image)
		{
			const std::size_t w = CGImageGetWidth(image), h = CGImageGetHeight(image);
			std::vector<std::uint8_t> rgba(w * h * 4);
			CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
			CGContextRef context = CGBitmapContextCreate(rgba.data(), w, h, 8, w * 4, space, kCGImageAlphaPremultipliedLast);
			CGContextDrawImage(context, CGRectMake(0, 0, w, h), image);
			const std::uint8_t* centre = rgba.data() + ((h / 2) * w + w / 2) * 4;
			out.r = centre[0];
			out.g = centre[1];
			out.b = centre[2];
			CGContextRelease(context);
			CGColorSpaceRelease(space);
			CGImageRelease(image);
		}
		return out;
	}

	int red(const read_back& f)
	{
		return f.r > 200 && f.g < 60 && f.b < 60;
	}

	// 7 s of frames and tone, then the process ends WITHOUT finish, as a kill would.
	int write_cut(const std::string& path)
	{
		armsx3::bridge::video_writer_settings s;
		s.path = path;
		s.width = 320;
		s.height = 180;
		s.fps = 30;
		s.video_bps = 1000000;
		s.sample_rate = k_rate;
		s.audio_bps = 192000;
		const auto w = armsx3::bridge::make_avfoundation_video_writer(s);
		if (!w)
			return 1;
		std::vector<std::uint8_t> frame(std::size_t{k_pitch} * k_frame_h, 255);
		std::vector<float> tone(1600 * 2, 0.1f);
		for (int f = 0; f < 7 * 30; ++f)
		{
			const std::int64_t pts = std::int64_t{f} * 1000 / 30;
			if (!offered([&] { return w->append_video(frame.data(), k_pitch, k_frame_w, k_frame_h, true, pts); }) ||
				!offered([&] { return w->append_audio(tone.data(), 1600, 2, std::int64_t{f} * 1600 * 1000000 / k_rate); }))
				return 1;
		}
		std::this_thread::sleep_for(std::chrono::seconds(1));
		std::fflush(stdout);
		_exit(0);
	}
}

int main(int argc, char** argv)
{
	if (argc == 3 && std::string(argv[2]) == "cut")
		return write_cut(std::string(argv[1]) + "/cut.mp4");
	if (argc != 2)
		return 2;
	const std::string dir = argv[1];
	const std::string rgba_path = dir + "/rgba.mp4", bgra_path = dir + "/bgra.mp4";
	if (!write_file(rgba_path, false) || !write_file(bgra_path, true))
	{
		std::printf("VS write failed\n");
		return 1;
	}
	const read_back a = read_file(rgba_path), b = read_file(bgra_path);
	std::printf("VSPIX rgba=%d,%d,%d bgra=%d,%d,%d\n", a.r, a.g, a.b, b.r, b.g, b.b);
	std::printf("VS video=%d codec=%s audio=%d duration_ok=%d rgba=%d bgra=%d\n", int(a.video && b.video),
		a.avc1 && b.avc1 ? "avc1" : "other", int(a.audio && b.audio), int(a.duration && b.duration), red(a), red(b));
	const read_back c = read_file(dir + "/cut.mp4");
	std::printf("VSCUT video=%d audio=%d decoded=%d duration_ok=%d\n", int(c.video), int(c.audio), int(c.r >= 0),
		int(c.seconds >= 4.5 && c.seconds <= 7.5));
	return 0;
}
'''


class TheWriterMakesAPlayableFile(unittest.TestCase):
    """A macOS host run of Apple's encoder, never device evidence.
    No docstrings on the methods, so `-v` prints `... ok` on this class's own line."""

    @classmethod
    def setUpClass(cls):
        if shutil.which("xcrun") is None:
            raise unittest.SkipTest("no `xcrun` on PATH; a skip is not a pass")
        found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "clang++"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, universal_newlines=True)
        if found.returncode != 0:
            raise unittest.SkipTest("xcrun finds no macOS `clang++`; a skip is not a pass")
        for source in (WRITER_MM, WRITER_H, WRITER_API):
            if not source.is_file():
                raise AssertionError("{0} is missing. FAILURE, not skip.".format(source))

        with tempfile.TemporaryDirectory() as tmp:
            driver, binary = Path(tmp) / "driver.mm", Path(tmp) / "driver"
            driver.write_text(DRIVER, encoding="utf-8")
            frameworks = [flag for name in FRAMEWORKS for flag in ("-framework", name)]
            built = subprocess.run(
                ["xcrun", "--sdk", "macosx", "clang++", "-std=c++20", "-fobjc-arc", "-x", "objective-c++",
                 "-I", str(WRITER_MM.parent), "-o", str(binary), str(driver), str(WRITER_MM)] + frameworks,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=COMPILE_TIMEOUT_SECONDS,
                universal_newlines=True,
            )
            if built.returncode != 0:
                raise AssertionError("clang++ failed:\n" + built.stdout)
            cut = subprocess.run([str(binary), tmp, "cut"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
            ran = subprocess.run([str(binary), tmp], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=RUN_TIMEOUT_SECONDS, universal_newlines=True, cwd=tmp)
        cls.cut_code, cls.cut_out = cut.returncode, cut.stdout
        cls.code, cls.out = ran.returncode, ran.stdout
        cls.lines = [line for line in cls.out.splitlines() if line.startswith("VS ")]
        cls.cut_lines = [line for line in cls.out.splitlines() if line.startswith("VSCUT ")]
        pixels = [line for line in cls.out.splitlines() if line.startswith("VSPIX ")]
        sys.stderr.write("\n" + "\n".join(cls.lines + pixels + cls.cut_lines) + "\n")

    def test_both_files_play_back_red_with_sound(self):
        self.assertEqual(0, self.code, self.out[-4000:])
        self.assertEqual([VS_LINE], self.lines, self.out[-4000:])

    def test_a_file_cut_off_before_finish_still_plays(self):
        self.assertEqual(0, self.cut_code, self.cut_out[-4000:])
        self.assertEqual([VSCUT_LINE], self.cut_lines, self.out[-4000:])


if __name__ == "__main__":
    unittest.main()
