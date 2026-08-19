"""Build a geometry-locked dumbbell bench-press v8 preview.

The cat, face, torso, legs, tail, bench, camera, and background come from one
complete master image and never change.  Both arms use the same deterministic
two-link trajectory, with the dumbbells travelling from the outer chest to
directly above the shoulders.  Every exported GIF frame is still a complete
image.
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageSequence, ImageStat

from build_identity_locked_exercise_guides import PartLibrary


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "assets-source" / "exercise-guides"
PREVIEW_DIR = SOURCE_DIR / "previews"

BODY_SOURCE = SOURCE_DIR / "bench-press-body-locked-v5-preview.png"
ARM_SOURCE = SOURCE_DIR / "bench-press-arm-sprites-v5-preview.png"
RIG_SOURCE = SOURCE_DIR / "golden-cat-coach-rig-parts-v4.png"

GIF_PATH = PREVIEW_DIR / "dumbbell-bench-press-v8-preview.gif"
CONTACT_PATH = PREVIEW_DIR / "dumbbell-bench-press-v8-contact.png"
METRICS_PATH = PREVIEW_DIR / "dumbbell-bench-press-v8-metrics.json"
FRAME_DIR = PREVIEW_DIR / "dumbbell-bench-press-v8-keyframes"

SIZE = 320
FRAME_COUNT = 72
FRAME_DURATION_MS = 40
BACKGROUND = (248, 245, 237)

BODY_CROP = (100, 200, 1150, 1250)
LEFT_FOREARM_CROP = (95, 730, 525, 1090)
RIGHT_FOREARM_CROP = (725, 730, 1155, 1090)

LEFT_SHOULDER = (132.0, 138.0)
RIGHT_SHOULDER = (199.0, 136.0)
LEFT_WRIST_BOTTOM = (98.0, 97.0)
RIGHT_WRIST_BOTTOM = (233.0, 97.0)
LEFT_WRIST_TOP = (125.0, 53.0)
RIGHT_WRIST_TOP = (206.0, 53.0)
UPPER_ARM_LENGTH = 43.0
FOREARM_LENGTH = 43.0

Point = tuple[float, float]


@dataclass(frozen=True)
class AnchoredSprite:
    image: Image.Image
    anchor: Point


def trim_alpha(image: Image.Image) -> Image.Image:
    bounding_box = image.getchannel("A").getbbox()
    if bounding_box is None:
        raise RuntimeError("Sprite crop contains no visible pixels")
    return image.crop(bounding_box)


def load_body() -> Image.Image:
    with Image.open(BODY_SOURCE) as opened:
        return opened.convert("RGB").crop(BODY_CROP).resize(
            (SIZE, SIZE),
            Image.Resampling.LANCZOS,
        )


def load_forearm(
    sheet: Image.Image,
    crop_box: tuple[int, int, int, int],
    anchor_ratio: Point,
) -> AnchoredSprite:
    sprite = trim_alpha(sheet.crop(crop_box))
    sprite.thumbnail((70, 58), Image.Resampling.LANCZOS)
    return AnchoredSprite(
        sprite,
        (
            sprite.width * anchor_ratio[0],
            sprite.height * anchor_ratio[1],
        ),
    )


def load_arm_parts() -> tuple[Image.Image, Image.Image, AnchoredSprite, AnchoredSprite]:
    library = PartLibrary(RIG_SOURCE)
    with Image.open(ARM_SOURCE) as opened:
        sheet = opened.convert("RGBA")
    return (
        library.get("upper-arm-left"),
        library.get("upper-arm-right"),
        load_forearm(sheet, LEFT_FOREARM_CROP, (0.54, 0.98)),
        load_forearm(sheet, RIGHT_FOREARM_CROP, (0.46, 0.98)),
    )


def rotate_sprite(sprite: AnchoredSprite, clockwise_angle: float) -> AnchoredSprite:
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


def paste_anchored(target: Image.Image, sprite: AnchoredSprite, anchor: Point) -> None:
    target.alpha_composite(
        sprite.image,
        (
            round(anchor[0] - sprite.anchor[0]),
            round(anchor[1] - sprite.anchor[1]),
        ),
    )


def paste_part(
    target: Image.Image,
    source: Image.Image,
    center: Point,
    size: tuple[float, float],
    clockwise_angle: float,
    mirror: bool = False,
) -> None:
    part = source
    if mirror:
        part = part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    part = part.resize(
        (max(1, round(size[0])), max(1, round(size[1]))),
        Image.Resampling.LANCZOS,
    )
    padding = max(part.width, part.height) * 3
    canvas = Image.new("RGBA", (padding, padding))
    canvas.alpha_composite(
        part,
        (
            (padding - part.width) // 2,
            (padding - part.height) // 2,
        ),
    )
    rotated = canvas.rotate(
        -clockwise_angle,
        resample=Image.Resampling.BICUBIC,
        center=(padding / 2, padding / 2),
    )
    target.alpha_composite(
        rotated,
        (
            round(center[0] - padding / 2),
            round(center[1] - padding / 2),
        ),
    )


def distance(first: Point, second: Point) -> float:
    return math.hypot(second[0] - first[0], second[1] - first[1])


def segment_angle(start: Point, end: Point) -> float:
    return math.degrees(math.atan2(end[0] - start[0], end[1] - start[1]))


def clockwise_from_up(start: Point, end: Point) -> float:
    return math.degrees(math.atan2(end[0] - start[0], start[1] - end[1]))


def interpolate(start: Point, end: Point, progress: float) -> Point:
    return (
        start[0] + (end[0] - start[0]) * progress,
        start[1] + (end[1] - start[1]) * progress,
    )


def two_link_elbow(
    shoulder: Point,
    wrist: Point,
    upper_length: float,
    lower_length: float,
    outward: str,
) -> Point:
    delta_x = wrist[0] - shoulder[0]
    delta_y = wrist[1] - shoulder[1]
    span = math.hypot(delta_x, delta_y)
    if span == 0 or span > upper_length + lower_length:
        raise RuntimeError("Wrist target is outside the two-link arm reach")

    along = (
        upper_length**2 - lower_length**2 + span**2
    ) / (2 * span)
    height = math.sqrt(max(0.0, upper_length**2 - along**2))
    unit_x = delta_x / span
    unit_y = delta_y / span
    base_x = shoulder[0] + along * unit_x
    base_y = shoulder[1] + along * unit_y
    candidates = (
        (base_x - height * unit_y, base_y + height * unit_x),
        (base_x + height * unit_y, base_y - height * unit_x),
    )
    if outward == "left":
        return min(candidates, key=lambda point: point[0])
    return max(candidates, key=lambda point: point[0])


def cycle_progress(index: int) -> float:
    return (1 - math.cos(2 * math.pi * index / FRAME_COUNT)) / 2


def render_arm(
    frame: Image.Image,
    upper_arm: Image.Image,
    forearm: AnchoredSprite,
    shoulder: Point,
    elbow: Point,
    wrist: Point,
    mirror: bool,
) -> None:
    paste_part(
        frame,
        upper_arm,
        (
            (shoulder[0] + elbow[0]) / 2,
            (shoulder[1] + elbow[1]) / 2,
        ),
        (30, distance(shoulder, elbow) * 1.22),
        segment_angle(shoulder, elbow),
        mirror=mirror,
    )
    paste_anchored(
        frame,
        rotate_sprite(
            forearm,
            clockwise_from_up(elbow, wrist),
        ),
        elbow,
    )


def render_frame(
    body: Image.Image,
    parts: tuple[Image.Image, Image.Image, AnchoredSprite, AnchoredSprite],
    progress: float,
) -> tuple[Image.Image, dict[str, Point]]:
    upper_left, upper_right, forearm_left, forearm_right = parts
    left_wrist = interpolate(LEFT_WRIST_BOTTOM, LEFT_WRIST_TOP, progress)
    right_wrist = interpolate(RIGHT_WRIST_BOTTOM, RIGHT_WRIST_TOP, progress)
    left_elbow = two_link_elbow(
        LEFT_SHOULDER,
        left_wrist,
        UPPER_ARM_LENGTH,
        FOREARM_LENGTH,
        "left",
    )
    right_elbow = two_link_elbow(
        RIGHT_SHOULDER,
        right_wrist,
        UPPER_ARM_LENGTH,
        FOREARM_LENGTH,
        "right",
    )

    frame = body.convert("RGBA")
    render_arm(
        frame,
        upper_right,
        forearm_right,
        RIGHT_SHOULDER,
        right_elbow,
        right_wrist,
        True,
    )
    render_arm(
        frame,
        upper_left,
        forearm_left,
        LEFT_SHOULDER,
        left_elbow,
        left_wrist,
        False,
    )
    return frame.convert("RGB"), {
        "leftElbow": left_elbow,
        "rightElbow": right_elbow,
        "leftWrist": left_wrist,
        "rightWrist": right_wrist,
    }


def shared_palette(frames: list[Image.Image]) -> Image.Image:
    strip = Image.new("RGB", (SIZE, SIZE * len(frames)), BACKGROUND)
    for index, frame in enumerate(frames):
        strip.paste(frame, (0, index * SIZE))
    return strip.quantize(
        colors=192,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    )


def save_gif(frames: list[Image.Image]) -> None:
    palette = shared_palette(frames)
    quantized = [
        frame.quantize(palette=palette, dither=Image.Dither.NONE)
        for frame in frames
    ]
    quantized[0].save(
        GIF_PATH,
        save_all=True,
        append_images=quantized[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        disposal=1,
        optimize=True,
    )


def save_contact_sheet(frames: list[Image.Image]) -> None:
    indexes = (0, 8, 16, 24, 36, 48, 56, 64)
    cell = 240
    columns = 4
    rows = 2
    gap = 8
    margin = 8
    sheet = Image.new(
        "RGB",
        (
            margin * 2 + columns * cell + (columns - 1) * gap,
            margin * 2 + rows * cell + (rows - 1) * gap,
        ),
        BACKGROUND,
    )
    for order, frame_index in enumerate(indexes):
        row, column = divmod(order, columns)
        sheet.paste(
            frames[frame_index].resize(
                (cell, cell),
                Image.Resampling.LANCZOS,
            ),
            (
                margin + column * (cell + gap),
                margin + row * (cell + gap),
            ),
        )
    sheet.save(CONTACT_PATH, optimize=True)


def save_keyframes(frames: list[Image.Image]) -> None:
    FRAME_DIR.mkdir(parents=True, exist_ok=True)
    for index in (0, 12, 24, 36):
        frames[index].save(
            FRAME_DIR / f"frame-{index:02d}.png",
            optimize=True,
        )


def validate(
    body: Image.Image,
    frames: list[Image.Image],
    poses: list[dict[str, Point]],
) -> dict[str, object]:
    outside_motion_mask = Image.new("L", (SIZE, SIZE), 255)
    ImageDraw.Draw(outside_motion_mask).rectangle(
        (45, 20, 280, 190),
        fill=0,
    )
    static_differences = [
        sum(
            ImageStat.Stat(
                ImageChops.difference(frame, body),
                mask=outside_motion_mask,
            ).mean
        )
        / 3
        for frame in frames
    ]
    if max(static_differences) != 0:
        raise RuntimeError("Pixels outside the arm motion envelope changed")

    ascent = poses[: FRAME_COUNT // 2 + 1]
    for previous, current in zip(ascent, ascent[1:]):
        if current["leftWrist"][1] > previous["leftWrist"][1]:
            raise RuntimeError("Left dumbbell trajectory moved downward during ascent")
        if current["rightWrist"][1] > previous["rightWrist"][1]:
            raise RuntimeError("Right dumbbell trajectory moved downward during ascent")
    max_wrist_height_difference = max(
        abs(pose["leftWrist"][1] - pose["rightWrist"][1])
        for pose in poses
    )
    if max_wrist_height_difference > 0.01:
        raise RuntimeError("The dumbbells are not vertically synchronized")

    with Image.open(GIF_PATH) as gif:
        decoded = [frame.convert("RGB").copy() for frame in ImageSequence.Iterator(gif)]
        duration = int(gif.info.get("duration", 0))
        loop = int(gif.info.get("loop", -1))
    if len(decoded) != FRAME_COUNT:
        raise RuntimeError(f"Expected {FRAME_COUNT} GIF frames, got {len(decoded)}")
    if duration != FRAME_DURATION_MS or loop != 0:
        raise RuntimeError("GIF timing or loop metadata is invalid")

    decoded_static_differences = [
        sum(
            ImageStat.Stat(
                ImageChops.difference(frame, decoded[0]),
                mask=outside_motion_mask,
            ).mean
        )
        / 3
        for frame in decoded
    ]
    if max(decoded_static_differences) != 0:
        raise RuntimeError("GIF encoding introduced flicker outside the arm envelope")

    adjacent = [
        sum(ImageStat.Stat(ImageChops.difference(first, second)).mean) / 3
        for first, second in zip(decoded, (*decoded[1:], decoded[0]), strict=True)
    ]
    wrist_steps = [
        max(
            distance(first["leftWrist"], second["leftWrist"]),
            distance(first["rightWrist"], second["rightWrist"]),
        )
        for first, second in zip(poses, (*poses[1:], poses[0]), strict=True)
    ]
    if max(wrist_steps) > 2.6:
        raise RuntimeError("A dumbbell moves too far between adjacent frames")
    return {
        "file": GIF_PATH.name,
        "sha256": hashlib.sha256(GIF_PATH.read_bytes()).hexdigest(),
        "size": [SIZE, SIZE],
        "frameCount": FRAME_COUNT,
        "frameDurationMs": FRAME_DURATION_MS,
        "framesPerSecond": round(1000 / FRAME_DURATION_MS, 1),
        "cycleDurationSeconds": round(FRAME_COUNT * FRAME_DURATION_MS / 1000, 2),
        "loop": loop,
        "bodyBenchStaticPixelDifference": max(static_differences),
        "decodedStaticPixelDifference": max(decoded_static_differences),
        "maxWristHeightDifferencePx": round(max_wrist_height_difference, 3),
        "maxWristStepPx": round(max(wrist_steps), 3),
        "maxAdjacentMeanDifference": round(max(adjacent), 3),
        "sizeBytes": GIF_PATH.stat().st_size,
    }


def main() -> None:
    for source in (BODY_SOURCE, ARM_SOURCE, RIG_SOURCE):
        if not source.exists():
            raise FileNotFoundError(source)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)

    body = load_body()
    parts = load_arm_parts()
    rendered = [
        render_frame(body, parts, cycle_progress(index))
        for index in range(FRAME_COUNT)
    ]
    frames = [item[0] for item in rendered]
    poses = [item[1] for item in rendered]

    save_gif(frames)
    save_contact_sheet(frames)
    save_keyframes(frames)
    metrics = validate(body, frames, poses)
    METRICS_PATH.write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
