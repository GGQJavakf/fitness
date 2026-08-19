#!/usr/bin/env python3
"""Build a bench-press preview with a pixel-locked body and moving arms only."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageSequence, ImageStat


PROJECT_ROOT = Path(__file__).resolve().parents[1]
BODY_SOURCE = (
    PROJECT_ROOT
    / "assets-source/exercise-guides/bench-press-body-locked-v5-preview.png"
)
ARM_SOURCE = (
    PROJECT_ROOT
    / "assets-source/exercise-guides/bench-press-arm-sprites-v5-preview.png"
)
DEFAULT_OUTPUT = (
    PROJECT_ROOT
    / "assets-source/exercise-guides/previews/dumbbell-bench-press-v5-preview.gif"
)

SIZE = 300
FRAME_COUNT = 50
FRAME_DURATION_MS = 30
BACKGROUND = (250, 248, 241)

Point = tuple[float, float]


@dataclass(frozen=True)
class AnchoredSprite:
    image: Image.Image
    anchor: Point


def trim_alpha(image: Image.Image) -> Image.Image:
    alpha = image.getchannel("A")
    bounding_box = alpha.getbbox()
    if bounding_box is None:
        raise RuntimeError("Sprite crop contains no visible pixels")
    return image.crop(bounding_box)


def crop_sprite(
    sheet: Image.Image,
    box: tuple[int, int, int, int],
    size: tuple[int, int],
    anchor_ratio: Point,
) -> AnchoredSprite:
    sprite = trim_alpha(sheet.crop(box))
    sprite.thumbnail(size, Image.Resampling.LANCZOS)
    anchor = (
        sprite.width * anchor_ratio[0],
        sprite.height * anchor_ratio[1],
    )
    return AnchoredSprite(sprite, anchor)


def rotate_around_anchor(
    sprite: AnchoredSprite,
    clockwise_angle: float,
) -> AnchoredSprite:
    padding = max(sprite.image.width, sprite.image.height) * 4
    center = padding // 2
    canvas = Image.new("RGBA", (padding, padding))
    canvas.alpha_composite(
        sprite.image,
        (
            round(center - sprite.anchor[0]),
            round(center - sprite.anchor[1]),
        ),
    )
    rotated = canvas.rotate(
        -clockwise_angle,
        resample=Image.Resampling.BICUBIC,
        center=(center, center),
    )
    return AnchoredSprite(rotated, (center, center))


def paste_at(
    target: Image.Image,
    sprite: AnchoredSprite,
    anchor: Point,
) -> None:
    target.alpha_composite(
        sprite.image,
        (
            round(anchor[0] - sprite.anchor[0]),
            round(anchor[1] - sprite.anchor[1]),
        ),
    )


def smooth_cycle(index: int) -> float:
    return (1 - math.cos(2 * math.pi * index / FRAME_COUNT)) / 2


def lerp(start: float, end: float, progress: float) -> float:
    return start + (end - start) * progress


def load_sources() -> tuple[Image.Image, dict[str, AnchoredSprite]]:
    if not BODY_SOURCE.exists() or not ARM_SOURCE.exists():
        raise FileNotFoundError("Bench-press preview sources are missing")

    with Image.open(BODY_SOURCE) as opened:
        body = opened.convert("RGBA").resize(
            (SIZE, SIZE),
            Image.Resampling.LANCZOS,
        )
    with Image.open(ARM_SOURCE) as opened:
        sheet = opened.convert("RGBA")

    scale = SIZE / 420
    forearm_size = (round(108 * scale), round(108 * scale))
    sprites = {
        "forearm-left": crop_sprite(
            sheet,
            (95, 730, 525, 1090),
            forearm_size,
            (0.5, 0.94),
        ),
        "forearm-right": crop_sprite(
            sheet,
            (725, 730, 1155, 1090),
            forearm_size,
            (0.5, 0.94),
        ),
    }
    return body, sprites


def render_frame(
    body: Image.Image,
    sprites: dict[str, AnchoredSprite],
    progress: float,
) -> Image.Image:
    frame = body.copy()
    scale = SIZE / 420
    left_shoulder = (178 * scale, 215 * scale)
    right_shoulder = (252 * scale, 210 * scale)
    left_forearm_angle = lerp(-38, -18, progress)
    right_forearm_angle = lerp(38, 18, progress)
    paste_at(
        frame,
        rotate_around_anchor(
            sprites["forearm-left"],
            left_forearm_angle,
        ),
        left_shoulder,
    )
    paste_at(
        frame,
        rotate_around_anchor(
            sprites["forearm-right"],
            right_forearm_angle,
        ),
        right_shoulder,
    )

    for shoulder in (left_shoulder, right_shoulder):
        radius = 5 * scale
        ImageDraw.Draw(frame).ellipse(
            (
                shoulder[0] - radius,
                shoulder[1] - radius,
                shoulder[0] + radius,
                shoulder[1] + radius,
            ),
            fill=(226, 168, 77, 255),
        )
    return frame.convert("RGB")


def encode_gif(frames: list[Image.Image], output: Path) -> None:
    palette_source = Image.new("RGB", (SIZE, SIZE * len(frames)))
    for index, frame in enumerate(frames):
        palette_source.paste(frame, (0, index * SIZE))
    palette = palette_source.quantize(
        colors=128,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    )
    quantized = [
        frame.quantize(palette=palette, dither=Image.Dither.NONE)
        for frame in frames
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    quantized[0].save(
        output,
        save_all=True,
        append_images=quantized[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        disposal=1,
        optimize=True,
    )


def validate_output(
    body: Image.Image,
    source_frames: list[Image.Image],
    output: Path,
) -> dict[str, object]:
    decoded: list[Image.Image] = []
    durations: list[int] = []
    with Image.open(output) as image:
        default_duration = int(image.info.get("duration", 0))
        loop = int(image.info.get("loop", -1))
        for frame in ImageSequence.Iterator(image):
            decoded.append(frame.convert("RGB").copy())
            durations.append(int(frame.info.get("duration", default_duration)))

    static_mask = Image.new("L", (SIZE, SIZE), 255)
    ImageDraw.Draw(static_mask).rectangle(
        (
            round(40 * SIZE / 420),
            round(35 * SIZE / 420),
            round(400 * SIZE / 420),
            round(300 * SIZE / 420),
        ),
        fill=0,
    )
    body_rgb = body.convert("RGB")
    static_differences = [
        sum(
            ImageStat.Stat(
                ImageChops.difference(frame, body_rgb),
                mask=static_mask,
            ).mean
        )
        / 3
        for frame in source_frames
    ]
    adjacent_differences = [
        sum(
            ImageStat.Stat(
                ImageChops.difference(current, following)
            ).mean
        )
        / 3
        for current, following in zip(
            decoded,
            (*decoded[1:], decoded[0]),
            strict=True,
        )
    ]
    if len(decoded) != FRAME_COUNT:
        raise RuntimeError(
            f"Expected {FRAME_COUNT} frames, got {len(decoded)}"
        )
    if set(durations) != {FRAME_DURATION_MS}:
        raise RuntimeError(f"Unexpected GIF durations: {sorted(set(durations))}")
    if loop != 0:
        raise RuntimeError(f"GIF loop must be infinite, got {loop}")
    if max(static_differences) > 0:
        raise RuntimeError("Pixels outside the arm motion region changed")

    return {
        "file": output.name,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "size": [SIZE, SIZE],
        "frameCount": len(decoded),
        "frameDurationMs": FRAME_DURATION_MS,
        "framesPerSecond": round(1000 / FRAME_DURATION_MS, 1),
        "loop": loop,
        "bodyBenchStaticPixelDifference": max(static_differences),
        "maxAdjacentMeanDifference": round(max(adjacent_differences), 3),
        "sizeBytes": output.stat().st_size,
    }


def write_contact_sheet(frames: list[Image.Image], output: Path) -> None:
    indexes = (0, FRAME_COUNT // 6, FRAME_COUNT // 3, FRAME_COUNT // 2)
    sheet = Image.new(
        "RGB",
        (SIZE * len(indexes), SIZE),
        BACKGROUND,
    )
    for column, index in enumerate(indexes):
        sheet.paste(frames[index], (column * SIZE, 0))
    sheet.save(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--contact-sheet", type=Path)
    arguments = parser.parse_args()

    body, sprites = load_sources()
    frames = [
        render_frame(body, sprites, smooth_cycle(index))
        for index in range(FRAME_COUNT)
    ]
    encode_gif(frames, arguments.output)
    if arguments.contact_sheet is not None:
        arguments.contact_sheet.parent.mkdir(parents=True, exist_ok=True)
        write_contact_sheet(frames, arguments.contact_sheet)
    print(
        json.dumps(
            validate_output(body, frames, arguments.output),
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
