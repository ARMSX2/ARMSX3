#!/usr/bin/env python3
"""Render the ARMSX3 animated boot logo and its preview assets."""

from __future__ import annotations

import argparse
import math
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    if edge0 == edge1:
        return float(value >= edge1)
    x = clamp01((value - edge0) / (edge1 - edge0))
    return x * x * (3.0 - 2.0 * x)


def ease_out_cubic(value: float) -> float:
    x = clamp01(value)
    return 1.0 - (1.0 - x) ** 3


def ease_out_back(value: float) -> float:
    x = clamp01(value)
    c1 = 1.70158
    c3 = c1 + 1.0
    return 1.0 + c3 * (x - 1.0) ** 3 + c1 * (x - 1.0) ** 2


def alpha_composite_rgb(base: np.ndarray, layer: Image.Image) -> np.ndarray:
    rgba = np.asarray(layer, dtype=np.float32)
    alpha = rgba[..., 3:4] / 255.0
    return base * (1.0 - alpha) + rgba[..., :3] * alpha


class Renderer:
    def __init__(self, source: Path, size: int, fps: int, duration: float) -> None:
        self.size = size
        self.fps = fps
        self.duration = duration
        self.source = Image.open(source).convert("RGB")
        self.logo = self._extract_logo()
        self.logo_mask = self.logo.getchannel("A")
        self.background = self._make_background()
        self.yy, self.xx = np.mgrid[0:size, 0:size].astype(np.float32)
        self.rng = np.random.default_rng(3303)
        self.particles = self._make_particles()

    def _extract_logo(self) -> Image.Image:
        src = np.asarray(self.source, dtype=np.float32)
        height, width = src.shape[:2]

        # Model the vertical purple gradient from the quiet image edges. A low
        # percentile rejects the bright XMB ribbon and particle highlights.
        edge_samples = np.concatenate((src[:, :64], src[:, width - 64 :]), axis=1)
        background_by_row = np.percentile(edge_samples, 28, axis=1)

        x0, x1 = 88, 424
        y0, y1 = 132, 372
        crop = src[y0:y1, x0:x1]
        background = background_by_row[y0:y1, None, :]
        delta = np.clip(crop - background, 0.0, 255.0)
        delta_luma = 0.30 * delta[..., 0] + 0.58 * delta[..., 1] + 0.12 * delta[..., 2]

        # The supplied mark is substantially brighter than its backdrop. Keep
        # its soft bevel/glow while rejecting the rectangular purple crop.
        alpha = np.clip((delta_luma - 24.0) / 68.0, 0.0, 1.0)
        alpha = alpha * alpha * (3.0 - 2.0 * alpha)
        alpha[alpha < 0.035] = 0.0
        # Keep the extraction tightly bounded to the supplied numeral. The
        # source ribbon continues beneath it and would otherwise reveal the
        # rectangular limits of this crop when animated over a new backdrop.
        alpha[229:, :] = 0.0
        alpha[:, :9] = 0.0
        alpha[:, 328:] = 0.0
        alpha_img = Image.fromarray(np.uint8(alpha * 255.0), "L")
        alpha_img = alpha_img.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(1.1))

        crop_img = Image.fromarray(np.uint8(np.clip(crop, 0.0, 255.0)), "RGB")
        crop_img.putalpha(alpha_img)
        return crop_img

    def _make_background(self) -> np.ndarray:
        size = self.size
        y = np.linspace(0.0, 1.0, size, dtype=np.float32)[:, None, None]
        x = np.linspace(-1.0, 1.0, size, dtype=np.float32)[None, :, None]
        top = np.array([22.0, 5.0, 42.0], dtype=np.float32)[None, None, :]
        bottom = np.array([111.0, 31.0, 226.0], dtype=np.float32)[None, None, :]
        base = top * (1.0 - y) + bottom * y
        base = np.broadcast_to(base, (size, size, 3)).copy()

        # A broad center bloom and restrained edge vignette evoke the XMB
        # backdrop without competing with the mark.
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
        cx, cy = size * 0.50, size * 0.60
        radius = np.sqrt(((xx - cx) / size) ** 2 + ((yy - cy) / size) ** 2)
        bloom = np.exp(-(radius / 0.48) ** 2)[..., None]
        base += bloom * np.array([24.0, 13.0, 52.0], dtype=np.float32)
        vignette = np.clip(np.abs(x) ** 1.7 * 18.0, 0.0, 18.0)
        base -= vignette
        return np.clip(base, 0.0, 255.0)

    def _make_particles(self) -> list[tuple[float, ...]]:
        particles: list[tuple[float, ...]] = []
        for _ in range(88):
            particles.append(
                (
                    float(self.rng.uniform(-0.15, 1.15)),
                    float(self.rng.uniform(0.025, 0.085)),
                    float(self.rng.normal(0.0, 0.032)),
                    float(self.rng.uniform(0.8, 2.6)),
                    float(self.rng.uniform(0.0, math.tau)),
                    float(self.rng.uniform(0.45, 1.0)),
                )
            )
        return particles

    def wave_y(self, x: np.ndarray | float, t: float, band: int = 0) -> np.ndarray | float:
        normalized_x = np.asarray(x) / self.size
        phase = t * (0.18 + band * 0.035)
        y = self.size * (0.625 + band * 0.018)
        y += self.size * (0.038 + band * 0.008) * np.sin(math.tau * (normalized_x * 0.72 - phase) + band * 1.15)
        y += self.size * 0.014 * np.sin(math.tau * (normalized_x * 1.55 + t * 0.09) + band * 0.7)
        return y

    def _draw_ribbons(self, t: float, opacity: float) -> Image.Image:
        size = self.size
        glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        glow_draw = ImageDraw.Draw(glow)
        core = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        core_draw = ImageDraw.Draw(core)

        colors = ((231, 217, 255), (167, 111, 255), (255, 255, 255))
        for band in range(3):
            points = []
            for x in range(-16, size + 17, 5):
                points.append((x, float(self.wave_y(x, t, band))))
            glow_alpha = int(opacity * (92 - band * 18))
            core_alpha = int(opacity * (178 - band * 25))
            glow_draw.line(points, fill=(*colors[band], glow_alpha), width=10 - band * 2, joint="curve")
            core_draw.line(points, fill=(*colors[band], core_alpha), width=max(1, 3 - band), joint="curve")

        glow = glow.filter(ImageFilter.GaussianBlur(8.0))
        return Image.alpha_composite(glow, core)

    def _draw_particles(self, t: float, opacity: float) -> Image.Image:
        size = self.size
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        for x0, speed, offset, radius, phase, brightness in self.particles:
            x_norm = ((x0 + speed * t) % 1.3) - 0.15
            x = x_norm * size
            y = float(self.wave_y(x, t, 0)) + offset * size
            y += math.sin(t * 1.3 + phase) * size * 0.006
            twinkle = 0.30 + 0.70 * (0.5 + 0.5 * math.sin(t * 3.4 + phase)) ** 2
            alpha = int(255 * opacity * brightness * twinkle)
            r = radius * (0.75 + 0.30 * twinkle)
            draw.ellipse((x - r, y - r, x + r, y + r), fill=(249, 239, 255, alpha))

        glow = layer.filter(ImageFilter.GaussianBlur(2.5))
        glow.putalpha(glow.getchannel("A").point(lambda a: min(180, a * 2)))
        return Image.alpha_composite(glow, layer)

    def _draw_logo_flash(self, t: float) -> Image.Image:
        """Sweep a soft highlight over the original numeral without redrawing it."""
        progress = (t - 0.72) / 1.08
        if progress <= 0.0 or progress >= 1.0:
            return Image.new("RGBA", (self.size, self.size), (0, 0, 0, 0))

        scale = self.size / self.source.width
        mask_width = int(round(self.logo_mask.width * scale))
        mask_height = int(round(self.logo_mask.height * scale))
        mask = self.logo_mask.resize((mask_width, mask_height), Image.Resampling.LANCZOS)

        local_x = np.arange(mask_width, dtype=np.float32)[None, :]
        local_y = np.arange(mask_height, dtype=np.float32)[:, None]
        center = (-0.28 + progress * 1.62) * mask_width
        stripe = np.exp(-((local_x + local_y * 0.36 - center) / (mask_width * 0.052)) ** 2)
        envelope = math.sin(math.pi * progress) ** 0.65
        mask_np = np.asarray(mask, dtype=np.float32) / 255.0
        stripe_alpha = np.uint8(np.clip(stripe * mask_np * envelope * 178.0, 0.0, 255.0))

        flash = Image.new("RGBA", (mask_width, mask_height), (255, 250, 255, 0))
        flash.putalpha(Image.fromarray(stripe_alpha, "L").filter(ImageFilter.GaussianBlur(1.4 * scale)))
        glow = flash.filter(ImageFilter.GaussianBlur(5.5 * scale))
        glow.putalpha(glow.getchannel("A").point(lambda a: min(150, int(a * 1.25))))

        layer = Image.new("RGBA", (self.size, self.size), (0, 0, 0, 0))
        x = int(round(88 * scale))
        y = int(round(132 * scale))
        layer.alpha_composite(glow, (x, y))
        layer.alpha_composite(flash, (x, y))
        return layer

    def _logo_layer(self, t: float, opacity: float) -> Image.Image:
        size = self.size
        progress = clamp01((t - 0.42) / 0.95)
        settle = ease_out_back(progress)
        scale = 0.72 + 0.28 * settle
        scale *= 1.0 + 0.006 * math.sin(max(0.0, t - 1.35) * 1.55) * math.exp(-max(0.0, t - 1.35) * 0.65)

        target_width = int(size * 0.635 * scale)
        target_height = max(1, int(target_width * self.logo.height / self.logo.width))
        logo = self.logo.resize((target_width, target_height), Image.Resampling.LANCZOS)

        blur_radius = (1.0 - ease_out_cubic(progress)) * 18.0
        if blur_radius > 0.2:
            logo = logo.filter(ImageFilter.GaussianBlur(blur_radius))
        logo_alpha = logo.getchannel("A").point(lambda a: int(a * opacity))
        logo.putalpha(logo_alpha)

        x = (size - target_width) // 2
        y = int(size * 0.49 - target_height * 0.50)

        # A subtle depth shadow makes the bevel read when the ribbons pass
        # behind it.
        shadow_mask = logo_alpha.filter(ImageFilter.GaussianBlur(7.0))
        shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        shadow_stamp = Image.new("RGBA", logo.size, (12, 3, 30, 0))
        shadow_stamp.putalpha(shadow_mask.point(lambda a: int(a * 0.33)))
        shadow.alpha_composite(shadow_stamp, (x + 4, y + 10))

        result = shadow
        result.alpha_composite(logo, (x, y))

        # A diagonal glint traverses only the mark during its final lock-in.
        glint_progress = clamp01((t - 1.18) / 0.90)
        if 0.0 < glint_progress < 1.0:
            local_x = np.arange(target_width, dtype=np.float32)[None, :]
            local_y = np.arange(target_height, dtype=np.float32)[:, None]
            center = (-0.32 + glint_progress * 1.65) * target_width
            stripe = np.exp(-((local_x + local_y * 0.38 - center) / (target_width * 0.055)) ** 2)
            alpha_np = np.asarray(logo_alpha, dtype=np.float32) / 255.0
            stripe_alpha = np.uint8(np.clip(stripe * alpha_np * 132.0, 0.0, 255.0))
            glint = Image.new("RGBA", (target_width, target_height), (255, 250, 255, 0))
            glint.putalpha(Image.fromarray(stripe_alpha, "L").filter(ImageFilter.GaussianBlur(1.2)))
            result.alpha_composite(glint, (x, y))

        return result

    def frame(self, t: float) -> Image.Image:
        # Keep the supplied artwork completely intact. Only the XMB ribbon
        # and its particles move; there is no logo extraction, reveal, scale,
        # or fade layer that can introduce seams through the numeral.
        source_frame = self.source.resize((self.size, self.size), Image.Resampling.LANCZOS)
        bg = np.asarray(source_frame, dtype=np.float32).copy()

        ribbon = self._draw_ribbons(t, 0.72)
        bg = alpha_composite_rgb(bg, ribbon)
        particles = self._draw_particles(t, 0.78)
        bg = alpha_composite_rgb(bg, particles)
        flash = self._draw_logo_flash(t)
        bg = alpha_composite_rgb(bg, flash)
        return Image.fromarray(np.uint8(np.clip(bg, 0.0, 255.0)), "RGB")


def render_video(renderer: Renderer, ffmpeg: Path, output: Path, output_size: int) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        str(ffmpeg),
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-s",
        f"{renderer.size}x{renderer.size}",
        "-r",
        str(renderer.fps),
        "-i",
        "-",
        "-an",
        "-vf",
        f"scale={output_size}:{output_size}:flags=lanczos",
        "-c:v",
        "libx264",
        "-preset",
        "slow",
        "-crf",
        "17",
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        "-y",
        str(output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    total_frames = int(round(renderer.duration * renderer.fps))
    try:
        for frame_index in range(total_frames):
            t = frame_index / renderer.fps
            process.stdin.write(renderer.frame(t).tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("ffmpeg failed while encoding the boot animation")


def render_preview(ffmpeg: Path, video: Path, output: Path) -> None:
    filter_graph = (
        "fps=12,scale=640:640:flags=lanczos,split[s0][s1];"
        "[s0]palettegen=max_colors=192:stats_mode=diff[p];"
        "[s1][p]paletteuse=dither=bayer:bayer_scale=3"
    )
    subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(video),
            "-lavfi",
            filter_graph,
            "-loop",
            "0",
            "-y",
            str(output),
        ],
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--render-size", type=int, default=640)
    parser.add_argument("--output-size", type=int, default=1280)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--duration", type=float, default=5.0)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    renderer = Renderer(args.source, args.render_size, args.fps, args.duration)
    video = args.output_dir / "armsx3-boot-xmb.mp4"
    poster = args.output_dir / "armsx3-boot-xmb-poster.png"
    preview = args.output_dir / "armsx3-boot-xmb-preview.gif"

    render_video(renderer, args.ffmpeg, video, args.output_size)
    final_frame = renderer.frame(args.duration - 1.0 / args.fps)
    final_frame.resize((args.output_size, args.output_size), Image.Resampling.LANCZOS).save(poster)
    render_preview(args.ffmpeg, video, preview)

    print(video)
    print(poster)
    print(preview)


if __name__ == "__main__":
    main()
