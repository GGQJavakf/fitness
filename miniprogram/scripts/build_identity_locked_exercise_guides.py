#!/usr/bin/env python3
"""Build geometry-locked golden-cat exercise GIF guides.

The previous pack concatenated independently generated pose illustrations.  That
made the cat, equipment and camera change between frames.  This renderer uses a
single cutout-puppet part sheet and only applies rigid translation/rotation to
the same raster parts, so character geometry cannot morph between frames.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable, Iterable, Sequence

from PIL import Image, ImageChops, ImageDraw, ImageSequence, ImageStat


SIZE = 150
RENDER_SCALE = 2
RENDER_SIZE = SIZE * RENDER_SCALE
FRAME_COUNT = 32
FRAME_DURATION_MS = 40
FRAMES_PER_SECOND = 1000 / FRAME_DURATION_MS
CHARACTER_ID = "golden-shaded-cat-coach-v4-rigged"
RENDER_MODE = "rigid-2d-skeletal"
BENCH_PRESS_RENDER_MODE = "complete-image-static-region-lock"
BACKGROUND = (250, 248, 241)
MAX_ASSET_BYTES = 80 * 1024

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = PROJECT_ROOT / "assets-source/exercise-guides"
OUTPUT_ROOT = PROJECT_ROOT / "src/subpackages/exercise-guide/assets/exercise-guides"
MODEL_SHEET = SOURCE_ROOT / "golden-cat-coach-turnaround-v3.png"
RIG_PART_SHEET = SOURCE_ROOT / "golden-cat-coach-rig-parts-v4.png"

Point = tuple[float, float]
Limb = tuple[Point, Point, Point]

PART_NAMES = (
    "head-front",
    "head-three-quarter",
    "head-side",
    "torso-front",
    "torso-side",
    "upper-arm-left",
    "upper-arm-right",
    "forearm-left",
    "forearm-right",
    "gripping-paw",
    "thigh-left",
    "thigh-right",
    "lower-leg-left",
    "lower-leg-right",
    "tail-curled",
    "tail-straight",
    "bench",
    "dumbbells-pair",
    "cable-handle",
    "pulldown-bar",
)


@dataclass(frozen=True)
class Exercise:
    code: str
    slug: str
    renderer: str
    variant: str = ""


@dataclass(frozen=True)
class RigPose:
    head_part: str
    head_center: Point
    head_size: Point
    head_angle: float
    torso_part: str
    torso_center: Point
    torso_size: Point
    torso_angle: float
    left_arm: Limb
    right_arm: Limb
    left_leg: Limb
    right_leg: Limb
    tail_part: str = "tail-curled"
    tail_center: Point = (95, 92)
    tail_size: Point = (28, 48)
    tail_angle: float = 0


@dataclass(frozen=True)
class RenderedFrame:
    image: Image.Image
    bone_lengths: tuple[float, ...]
    head_size: Point
    torso_size: Point


EXERCISES: tuple[Exercise, ...] = (
    Exercise("GOBLET_SQUAT", "goblet-squat", "squat", "goblet"),
    Exercise("DUMBBELL_FRONT_SQUAT", "dumbbell-front-squat", "squat", "front-dumbbells"),
    Exercise("BODYWEIGHT_SQUAT", "bodyweight-squat", "squat", "bodyweight"),
    Exercise("DUMBBELL_ROMANIAN_DEADLIFT", "dumbbell-romanian-deadlift", "hinge", "romanian"),
    Exercise("DUMBBELL_DEADLIFT", "dumbbell-deadlift", "hinge", "deadlift"),
    Exercise("BODYWEIGHT_HIP_HINGE", "bodyweight-hip-hinge", "hinge", "bodyweight"),
    Exercise("DUMBBELL_BENCH_PRESS", "dumbbell-bench-press", "horizontal-press", "bench"),
    Exercise("DUMBBELL_FLOOR_PRESS", "dumbbell-floor-press", "horizontal-press", "floor"),
    Exercise("INCLINE_PUSH_UP", "incline-push-up", "incline-push-up"),
    Exercise("SEATED_CABLE_ROW", "seated-cable-row", "row", "seated"),
    Exercise("CABLE_HIGH_ROW", "cable-high-row", "row", "high"),
    Exercise("CABLE_SINGLE_ARM_ROW", "cable-single-arm-row", "row", "single"),
    Exercise("ONE_ARM_DUMBBELL_ROW", "one-arm-dumbbell-row", "dumbbell-row", "single"),
    Exercise("DUMBBELL_OVERHEAD_PRESS", "dumbbell-overhead-press", "overhead-press", "standing"),
    Exercise("SEATED_DUMBBELL_PRESS", "seated-dumbbell-press", "overhead-press", "seated"),
    Exercise("SINGLE_ARM_DUMBBELL_PRESS", "single-arm-dumbbell-press", "overhead-press", "single"),
    Exercise("DUMBBELL_LATERAL_RAISE", "dumbbell-lateral-raise", "lateral-raise", "dumbbell"),
    Exercise("SINGLE_ARM_DUMBBELL_LATERAL_RAISE", "single-arm-dumbbell-lateral-raise", "lateral-raise", "single"),
    Exercise("CABLE_LATERAL_RAISE", "cable-lateral-raise", "lateral-raise", "cable"),
    Exercise("DUMBBELL_BICEPS_CURL", "dumbbell-biceps-curl", "biceps-curl", "dumbbell"),
    Exercise("DUMBBELL_HAMMER_CURL", "dumbbell-hammer-curl", "biceps-curl", "hammer"),
    Exercise("CABLE_BICEPS_CURL", "cable-biceps-curl", "biceps-curl", "cable"),
    Exercise("CABLE_TRICEPS_PUSHDOWN", "cable-triceps-pushdown", "triceps-extension", "pushdown"),
    Exercise("DUMBBELL_OVERHEAD_TRICEPS_EXTENSION", "dumbbell-overhead-triceps-extension", "triceps-extension", "overhead"),
    Exercise("DUMBBELL_LYING_TRICEPS_EXTENSION", "dumbbell-lying-triceps-extension", "triceps-extension", "lying"),
    Exercise("DUMBBELL_REVERSE_FLY", "dumbbell-reverse-fly", "rear-delt", "dumbbell"),
    Exercise("CABLE_REVERSE_FLY", "cable-reverse-fly", "rear-delt", "cable"),
    Exercise("CABLE_FACE_PULL", "cable-face-pull", "rear-delt", "face-pull"),
    Exercise("DUMBBELL_SHRUG", "dumbbell-shrug", "shrug", "dumbbell"),
    Exercise("CABLE_SHRUG", "cable-shrug", "shrug", "cable"),
    Exercise("MACHINE_SHRUG", "machine-shrug", "shrug", "machine"),
    Exercise("LAT_PULLDOWN", "lat-pulldown", "pulldown", "wide"),
    Exercise(
        "CABLE_STRAIGHT_ARM_PULLDOWN",
        "cable-straight-arm-pulldown",
        "straight-arm-pulldown",
    ),
    Exercise("NEUTRAL_GRIP_PULLDOWN", "neutral-grip-pulldown", "pulldown", "neutral"),
    Exercise("PRONE_W_RAISE", "prone-w-raise", "prone-raise", "w"),
    Exercise("PRONE_Y_RAISE", "prone-y-raise", "prone-raise", "y"),
    Exercise("DEAD_BUG", "dead-bug", "dead-bug"),
    Exercise("BIRD_DOG", "bird-dog", "bird-dog"),
    Exercise("PLANK", "plank", "plank"),
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def trim_alpha(image: Image.Image) -> Image.Image:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise RuntimeError("Rig part contains no foreground pixels")
    return image.crop(bbox)


def keyed_part(cell: Image.Image) -> Image.Image:
    rgb = cell.convert("RGB")
    samples = (
        rgb.getpixel((0, 0)),
        rgb.getpixel((rgb.width - 1, 0)),
        rgb.getpixel((0, rgb.height - 1)),
        rgb.getpixel((rgb.width - 1, rgb.height - 1)),
    )
    background = tuple(
        round(sum(sample[channel] for sample in samples) / len(samples))
        for channel in range(3)
    )
    difference = ImageChops.difference(rgb, Image.new("RGB", rgb.size, background)).convert("L")
    alpha = difference.point(
        lambda value: 0 if value <= 7 else min(255, (value - 7) * 20)
    )
    rgba = rgb.convert("RGBA")
    rgba.putalpha(alpha)
    return trim_alpha(rgba)


class PartLibrary:
    def __init__(self, source: Path) -> None:
        if not source.exists():
            raise FileNotFoundError(f"Missing geometry-locked rig part sheet: {source}")
        with Image.open(source) as opened:
            sheet = opened.convert("RGB")
        sheet_background = tuple(
            round(
                sum(
                    sample[channel]
                    for sample in (
                        sheet.getpixel((0, 0)),
                        sheet.getpixel((sheet.width - 1, 0)),
                        sheet.getpixel((0, sheet.height - 1)),
                        sheet.getpixel((sheet.width - 1, sheet.height - 1)),
                    )
                )
                / 4
            )
            for channel in range(3)
        )
        mask = ImageChops.difference(
            sheet,
            Image.new("RGB", sheet.size, sheet_background),
        ).convert("L").point(lambda value: 255 if value > 12 else 0)
        _, y_projection = mask.getprojection()
        row_runs = projection_runs(y_projection, minimum_length=20)
        if len(row_runs) != 4:
            raise RuntimeError(
                f"Rig part sheet produced {len(row_runs)} content rows instead of 4"
            )
        parts: dict[str, Image.Image] = {}
        for row, (top, bottom) in enumerate(row_runs):
            row_mask = mask.crop((0, top, sheet.width, bottom))
            x_projection, _ = row_mask.getprojection()
            column_runs = merge_projection_runs(
                projection_runs(x_projection, minimum_length=12),
                target_count=5,
            )
            if len(column_runs) != 5:
                raise RuntimeError(
                    f"Rig part row {row + 1} produced {len(column_runs)} columns instead of 5"
                )
            for column, (left, right) in enumerate(column_runs):
                index = row * 5 + column
                name = PART_NAMES[index]
                padding = 8
                parts[name] = keyed_part(
                    sheet.crop(
                        (
                            max(0, left - padding),
                            max(0, top - padding),
                            min(sheet.width, right + padding),
                            min(sheet.height, bottom + padding),
                        )
                    )
                )
        dumbbells = parts["dumbbells-pair"]
        parts["dumbbell"] = trim_alpha(
            dumbbells.crop((0, 0, round(dumbbells.width / 2), dumbbells.height))
        )
        self._parts = parts

    def get(self, name: str) -> Image.Image:
        try:
            return self._parts[name]
        except KeyError as error:
            raise KeyError(f"Unknown rig part: {name}") from error


def projection_runs(
    projection: Sequence[int],
    minimum_length: int,
) -> list[tuple[int, int]]:
    runs: list[tuple[int, int]] = []
    start: int | None = None
    for index, value in enumerate(projection):
        if value and start is None:
            start = index
        elif not value and start is not None:
            if index - start >= minimum_length:
                runs.append((start, index))
            start = None
    if start is not None and len(projection) - start >= minimum_length:
        runs.append((start, len(projection)))
    return runs


def merge_projection_runs(
    runs: Sequence[tuple[int, int]],
    target_count: int,
) -> list[tuple[int, int]]:
    merged = list(runs)
    while len(merged) > target_count:
        merge_index = min(
            range(len(merged) - 1),
            key=lambda index: merged[index + 1][0] - merged[index][1],
        )
        merged[merge_index : merge_index + 2] = [
            (merged[merge_index][0], merged[merge_index + 1][1])
        ]
    return merged


def canvas() -> Image.Image:
    return Image.new("RGBA", (RENDER_SIZE, RENDER_SIZE), (*BACKGROUND, 255))


def scaled(value: float) -> int:
    return round(value * RENDER_SCALE)


def paste_part(
    target: Image.Image,
    source: Image.Image,
    center: Point,
    size: Point,
    clockwise_angle: float = 0,
    mirror: bool = False,
) -> None:
    part = source.transpose(Image.Transpose.FLIP_LEFT_RIGHT) if mirror else source
    resized = part.resize(
        (max(1, scaled(size[0])), max(1, scaled(size[1]))),
        Image.Resampling.LANCZOS,
    )
    rotated = resized.rotate(
        -clockwise_angle,
        resample=Image.Resampling.BICUBIC,
        expand=True,
    )
    left = scaled(center[0]) - rotated.width // 2
    top = scaled(center[1]) - rotated.height // 2
    target.alpha_composite(rotated, (left, top))


def line(
    target: Image.Image,
    start: Point,
    end: Point,
    fill: tuple[int, int, int, int] = (55, 58, 55, 255),
    width: float = 1.5,
) -> None:
    ImageDraw.Draw(target).line(
        (scaled(start[0]), scaled(start[1]), scaled(end[0]), scaled(end[1])),
        fill=fill,
        width=max(1, scaled(width)),
    )


def fur_joint(target: Image.Image, center: Point, radius: float) -> None:
    ImageDraw.Draw(target).ellipse(
        (
            scaled(center[0] - radius),
            scaled(center[1] - radius),
            scaled(center[0] + radius),
            scaled(center[1] + radius),
        ),
        fill=(225, 168, 79, 255),
    )


def endpoint(start: Point, length: float, clockwise_from_down: float) -> Point:
    radians = math.radians(clockwise_from_down)
    return (
        start[0] + length * math.sin(radians),
        start[1] + length * math.cos(radians),
    )


def two_link_joint(
    start: Point,
    end: Point,
    first_length: float,
    second_length: float,
    bend: float,
) -> Point:
    """Solve a two-segment limb while keeping both endpoints fixed."""
    delta_x = end[0] - start[0]
    delta_y = end[1] - start[1]
    span = math.hypot(delta_x, delta_y)
    if span == 0 or span > first_length + second_length:
        raise RuntimeError("Two-link target is outside the rigid limb workspace")
    along = (
        first_length**2 - second_length**2 + span**2
    ) / (2 * span)
    height = math.sqrt(max(0.0, first_length**2 - along**2))
    unit_x = delta_x / span
    unit_y = delta_y / span
    return (
        start[0] + unit_x * along - unit_y * height * bend,
        start[1] + unit_y * along + unit_x * height * bend,
    )


def rotate_offset(center: Point, offset: Point, clockwise_angle: float) -> Point:
    radians = math.radians(clockwise_angle)
    cosine = math.cos(radians)
    sine = math.sin(radians)
    return (
        center[0] + offset[0] * cosine - offset[1] * sine,
        center[1] + offset[0] * sine + offset[1] * cosine,
    )


def distance(first: Point, second: Point) -> float:
    return math.hypot(second[0] - first[0], second[1] - first[1])


def segment_angle(start: Point, end: Point) -> float:
    return math.degrees(math.atan2(end[0] - start[0], end[1] - start[1]))


def paste_segment(
    target: Image.Image,
    source: Image.Image,
    start: Point,
    end: Point,
    width: float,
    mirror: bool = False,
) -> None:
    paste_part(
        target,
        source,
        ((start[0] + end[0]) / 2, (start[1] + end[1]) / 2),
        (width, distance(start, end) * 1.24),
        segment_angle(start, end),
        mirror,
    )


def limb_lengths(limb: Limb) -> tuple[float, float]:
    return distance(limb[0], limb[1]), distance(limb[1], limb[2])


def draw_pose(
    parts: PartLibrary,
    pose: RigPose,
    underlay: Callable[[Image.Image], None] | None = None,
    equipment: Callable[[Image.Image], None] | None = None,
    overlay: Callable[[Image.Image], None] | None = None,
) -> RenderedFrame:
    frame = canvas()
    if underlay is not None:
        underlay(frame)

    paste_part(
        frame,
        parts.get(pose.tail_part),
        pose.tail_center,
        pose.tail_size,
        pose.tail_angle,
    )
    paste_segment(frame, parts.get("thigh-right"), pose.right_leg[0], pose.right_leg[1], 18, True)
    paste_segment(
        frame,
        parts.get("lower-leg-right"),
        pose.right_leg[1],
        pose.right_leg[2],
        16.5,
        True,
    )
    paste_segment(frame, parts.get("upper-arm-right"), pose.right_arm[0], pose.right_arm[1], 14, True)
    paste_segment(frame, parts.get("forearm-right"), pose.right_arm[1], pose.right_arm[2], 13, True)
    paste_segment(frame, parts.get("thigh-left"), pose.left_leg[0], pose.left_leg[1], 18)
    paste_segment(
        frame,
        parts.get("lower-leg-left"),
        pose.left_leg[1],
        pose.left_leg[2],
        16.5,
    )

    paste_part(
        frame,
        parts.get(pose.torso_part),
        pose.torso_center,
        pose.torso_size,
        pose.torso_angle,
    )
    if equipment is not None:
        equipment(frame)

    paste_segment(frame, parts.get("upper-arm-left"), pose.left_arm[0], pose.left_arm[1], 14)
    paste_segment(frame, parts.get("forearm-left"), pose.left_arm[1], pose.left_arm[2], 13)
    for shoulder in (pose.left_arm[0], pose.right_arm[0]):
        fur_joint(frame, shoulder, 5.8)
    for elbow in (pose.left_arm[1], pose.right_arm[1]):
        fur_joint(frame, elbow, 5.6)
    for hip in (pose.left_leg[0], pose.right_leg[0]):
        fur_joint(frame, hip, 6.8)
    for knee in (pose.left_leg[1], pose.right_leg[1]):
        fur_joint(frame, knee, 6.6)
    paste_part(
        frame,
        parts.get(pose.head_part),
        pose.head_center,
        pose.head_size,
        pose.head_angle,
    )
    if overlay is not None:
        overlay(frame)

    bones = (
        *limb_lengths(pose.left_arm),
        *limb_lengths(pose.right_arm),
        *limb_lengths(pose.left_leg),
        *limb_lengths(pose.right_leg),
    )
    return RenderedFrame(
        frame.convert("RGB"),
        tuple(round(value, 4) for value in bones),
        pose.head_size,
        pose.torso_size,
    )


def cycle_phase(index: int) -> float:
    return (1 - math.cos(2 * math.pi * index / FRAME_COUNT)) / 2


def validate_cycle() -> None:
    phases = [cycle_phase(index) for index in range(FRAME_COUNT)]
    midpoint = FRAME_COUNT // 2
    if any(following < current for current, following in zip(phases[:midpoint], phases[1:midpoint + 1])):
        raise RuntimeError("First half of cycle is not monotonic")
    if any(following > current for current, following in zip(phases[midpoint:], phases[midpoint + 1:])):
        raise RuntimeError("Second half of cycle is not monotonic")
    if abs(phases[0] - phases[-1]) > 0.02:
        raise RuntimeError("Cycle boundary is discontinuous")
    validate_instructional_pose_invariants()


def dumbbell_layer(parts: PartLibrary, placements: Iterable[tuple[Point, float]]) -> Callable[[Image.Image], None]:
    frozen = tuple(placements)

    def draw(frame: Image.Image) -> None:
        for center, angle in frozen:
            paste_part(frame, parts.get("dumbbell"), center, (16, 23), angle)

    return draw


def squat(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    vertical = 20 * phase
    torso_center = (75, 66 + vertical)
    left_hip = (65, 88 + vertical)
    right_hip = (85, 88 + vertical)
    left_knee = endpoint(left_hip, 27, -10 - 38 * phase)
    right_knee = endpoint(right_hip, 27, 10 + 38 * phase)
    left_ankle = endpoint(left_knee, 26, 4 + 32 * phase)
    right_ankle = endpoint(right_knee, 26, -4 - 32 * phase)
    left_shoulder = (58, 50 + vertical)
    right_shoulder = (92, 50 + vertical)

    if variant == "bodyweight":
        left_elbow = endpoint(left_shoulder, 23, -42)
        right_elbow = endpoint(right_shoulder, 23, 42)
        left_wrist = endpoint(left_elbow, 22, 88)
        right_wrist = endpoint(right_elbow, 22, -88)
        equipment = None
    elif variant == "front-dumbbells":
        left_elbow = endpoint(left_shoulder, 22, 18)
        right_elbow = endpoint(right_shoulder, 22, -18)
        left_wrist = endpoint(left_elbow, 20, -155)
        right_wrist = endpoint(right_elbow, 20, 155)
        equipment = dumbbell_layer(parts, ((left_wrist, 90), (right_wrist, 90)))
    else:
        left_elbow = endpoint(left_shoulder, 22, 28)
        right_elbow = endpoint(right_shoulder, 22, -28)
        left_wrist = (70, 74 + vertical)
        right_wrist = (80, 74 + vertical)
        equipment = dumbbell_layer(parts, (((75, 76 + vertical), 0),))

    pose = RigPose(
        "head-front",
        (75, 33 + vertical),
        (49, 45),
        0,
        "torso-front",
        torso_center,
        (48, 64),
        0,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_center=(101, 87 + vertical),
        tail_size=(27, 44),
        tail_angle=-18,
    )
    return draw_pose(parts, pose, equipment=equipment)


def side_pose(
    torso_center: Point,
    torso_angle: float,
    left_arm_angles: tuple[float, float],
    right_arm_angles: tuple[float, float],
    left_leg_angles: tuple[float, float],
    right_leg_angles: tuple[float, float],
    head_angle: float = 0,
    torso_size: Point = (30, 62),
    head_size: Point = (40, 39),
) -> RigPose:
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    head = rotate_offset(torso_center, (0, -38), torso_angle)
    left_shoulder = (shoulder[0] - 2, shoulder[1])
    right_shoulder = (shoulder[0] + 2, shoulder[1] + 1)
    left_hip = (hip[0] - 2, hip[1])
    right_hip = (hip[0] + 2, hip[1] + 1)
    left_elbow = endpoint(left_shoulder, 23, left_arm_angles[0])
    left_wrist = endpoint(left_elbow, 22, left_arm_angles[1])
    right_elbow = endpoint(right_shoulder, 23, right_arm_angles[0])
    right_wrist = endpoint(right_elbow, 22, right_arm_angles[1])
    left_knee = endpoint(left_hip, 27, left_leg_angles[0])
    left_ankle = endpoint(left_knee, 25, left_leg_angles[1])
    right_knee = endpoint(right_hip, 27, right_leg_angles[0])
    right_ankle = endpoint(right_knee, 25, right_leg_angles[1])
    return RigPose(
        "head-side",
        head,
        head_size,
        head_angle,
        "torso-side",
        torso_center,
        torso_size,
        torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=rotate_offset(torso_center, (16, 25), torso_angle),
        tail_size=(17, 43),
        tail_angle=torso_angle - 30,
    )


def hinge(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    torso_angle = -8 - 48 * phase
    torso_center = (82 + 5 * phase, 68 + 5 * phase)
    knee_bend = 10 if variant == "deadlift" else 2
    pose = side_pose(
        torso_center,
        torso_angle,
        (8, 2),
        (4, -2),
        (4 + knee_bend * phase, -4 - knee_bend * phase),
        (-2 + knee_bend * phase, 2 - knee_bend * phase),
        head_angle=-torso_angle * 0.72,
    )
    if variant == "bodyweight":
        equipment = None
    else:
        equipment = dumbbell_layer(
            parts,
            ((pose.left_arm[2], 0), (pose.right_arm[2], 0)),
        )
    return draw_pose(parts, pose, equipment=equipment)


def horizontal_press(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    torso_center = (76, 91 if variant == "floor" else 82)
    torso_angle = 90
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    head_center = rotate_offset(torso_center, (0, -38), torso_angle)
    left_shoulder = (shoulder[0] - 2, shoulder[1] - 2)
    right_shoulder = (shoulder[0] + 2, shoulder[1] + 3)
    left_elbow = endpoint(left_shoulder, 23, -90 - 88 * phase)
    right_elbow = endpoint(right_shoulder, 23, 90 + 88 * phase)
    left_wrist = endpoint(left_elbow, 22, 178)
    right_wrist = endpoint(right_elbow, 22, -178)
    left_hip = (hip[0] - 2, hip[1] - 2)
    right_hip = (hip[0] + 2, hip[1] + 2)
    left_knee = endpoint(left_hip, 27, -34)
    right_knee = endpoint(right_hip, 27, -22)
    left_ankle = endpoint(left_knee, 25, 28)
    right_ankle = endpoint(right_knee, 25, 36)
    pose = RigPose(
        "head-three-quarter",
        head_center,
        (42, 39),
        0,
        "torso-front",
        torso_center,
        (44, 59),
        torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=(49, 105),
        tail_size=(15, 38),
        tail_angle=72,
    )

    def underlay(frame: Image.Image) -> None:
        if variant == "bench":
            paste_part(frame, parts.get("bench"), (74, 104), (118, 48))
        else:
            line(frame, (18, 124), (136, 124), (187, 181, 166, 255), 1)

    equipment = dumbbell_layer(parts, ((left_wrist, 0), (right_wrist, 0)))
    return draw_pose(parts, pose, underlay=underlay, equipment=equipment)


def incline_push_up_pose(phase: float) -> RigPose:
    torso_center = (74 - 4 * phase, 77 + 4 * phase)
    torso_angle = -55
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    head = rotate_offset(torso_center, (0, -38), torso_angle)
    left_shoulder = (shoulder[0], shoulder[1] - 2)
    right_shoulder = (shoulder[0] + 2, shoulder[1] + 2)
    left_wrist = (35, 99)
    right_wrist = (39, 101)
    left_elbow = two_link_joint(left_shoulder, left_wrist, 23, 22, -1)
    right_elbow = two_link_joint(right_shoulder, right_wrist, 23, 22, -1)
    left_hip = (hip[0], hip[1] - 2)
    right_hip = (hip[0] + 2, hip[1] + 2)
    body_line_angle = -torso_angle
    left_knee = endpoint(left_hip, 27, body_line_angle)
    right_knee = endpoint(right_hip, 27, body_line_angle)
    left_ankle = endpoint(left_knee, 25, body_line_angle)
    right_ankle = endpoint(right_knee, 25, body_line_angle)
    return RigPose(
        "head-side",
        head,
        (38, 37),
        6,
        "torso-side",
        torso_center,
        (30, 61),
        torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=rotate_offset(torso_center, (16, 24), torso_angle),
        tail_size=(15, 39),
        tail_angle=65,
    )


def incline_push_up(parts: PartLibrary, phase: float, _variant: str) -> RenderedFrame:
    pose = incline_push_up_pose(phase)

    def underlay(frame: Image.Image) -> None:
        paste_part(frame, parts.get("bench"), (34, 114), (68, 29))
        line(frame, (14, 128), (141, 128), (187, 181, 166, 255), 1)

    return draw_pose(parts, pose, underlay=underlay)


def row_pose(phase: float, variant: str) -> RigPose:
    torso_center = (91, 72)
    high = variant == "high"
    single = variant == "single"
    shoulder_angle = -72 if high else -92
    elbow_angle = 84 + 40 * phase
    return side_pose(
        torso_center,
        -8 if high else 0,
        (shoulder_angle + 34 * phase, elbow_angle),
        (20, 0) if single else (
            shoulder_angle + 30 * phase,
            elbow_angle - 5,
        ),
        (-54, 28),
        (-48, 32),
        head_angle=5,
    )


def row(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    pose = row_pose(phase, variant)
    high = variant == "high"
    single = variant == "single"
    active_wrist = pose.left_arm[2]
    other_wrist = pose.right_arm[2]
    anchor = (16, 27 if high else 72)

    def underlay(frame: Image.Image) -> None:
        line(frame, anchor, active_wrist)
        if not single:
            line(frame, anchor, other_wrist)
        paste_part(frame, parts.get("bench"), (92, 113), (66, 29))

    def equipment(frame: Image.Image) -> None:
        paste_part(frame, parts.get("cable-handle"), active_wrist, (14, 23), 90)
        if not single:
            paste_part(frame, parts.get("cable-handle"), other_wrist, (14, 23), 90)

    return draw_pose(parts, pose, underlay=underlay, equipment=equipment)


def front_press_pose(phase: float, seated: bool, single: bool) -> RigPose:
    torso_center = (75, 70 if not seated else 76)
    left_shoulder = (58, 52 if not seated else 58)
    right_shoulder = (92, 52 if not seated else 58)
    left_elbow = endpoint(left_shoulder, 23, 36 - 34 * phase)
    right_elbow = endpoint(right_shoulder, 23, -36 + 34 * phase)
    left_wrist = endpoint(left_elbow, 22, 174 - 170 * phase)
    right_wrist = endpoint(right_elbow, 22, -174 + 170 * phase)
    if single:
        right_elbow = endpoint(right_shoulder, 23, -36)
        right_wrist = endpoint(right_elbow, 22, -174)
    left_hip = (65, 92 if not seated else 98)
    right_hip = (85, 92 if not seated else 98)
    if seated:
        left_knee = endpoint(left_hip, 27, -52)
        right_knee = endpoint(right_hip, 27, 52)
        left_ankle = endpoint(left_knee, 25, 20)
        right_ankle = endpoint(right_knee, 25, -20)
    else:
        left_knee = endpoint(left_hip, 27, 7)
        right_knee = endpoint(right_hip, 27, -7)
        left_ankle = endpoint(left_knee, 25, -3)
        right_ankle = endpoint(right_knee, 25, 3)
    return RigPose(
        "head-front",
        (75, 34 if not seated else 40),
        (49, 45),
        0,
        "torso-front",
        torso_center,
        (48, 64),
        0,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_center=(103, 96 if not seated else 102),
        tail_size=(27, 44),
        tail_angle=-20,
    )


def overhead_press(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    seated = variant == "seated"
    single = variant == "single"
    pose = front_press_pose(phase, seated, single)

    def underlay(frame: Image.Image) -> None:
        if seated:
            paste_part(frame, parts.get("bench"), (77, 116), (74, 31))

    placements = [(pose.left_arm[2], 90)]
    if not single:
        placements.append((pose.right_arm[2], 90))
    equipment = dumbbell_layer(parts, placements)
    return draw_pose(parts, pose, underlay=underlay if seated else None, equipment=equipment)


def pulldown(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    torso_center = (75, 79)
    left_shoulder = (59, 61)
    right_shoulder = (91, 61)
    sweep = 118 if variant == "neutral" else 145
    left_elbow = endpoint(left_shoulder, 23, 180 + sweep * phase)
    right_elbow = endpoint(right_shoulder, 23, 180 - sweep * phase)
    left_wrist = endpoint(left_elbow, 22, 180 - 38 * phase)
    right_wrist = endpoint(right_elbow, 22, 180 + 38 * phase)
    bar_y = (left_wrist[1] + right_wrist[1]) / 2
    left_hip = (65, 100)
    right_hip = (85, 100)
    left_knee = endpoint(left_hip, 27, -52)
    right_knee = endpoint(right_hip, 27, 52)
    left_ankle = endpoint(left_knee, 25, 18)
    right_ankle = endpoint(right_knee, 25, -18)
    pose = RigPose(
        "head-front",
        (75, 44),
        (47, 43),
        0,
        "torso-front",
        torso_center,
        (47, 62),
        0,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_center=(104, 105),
        tail_size=(25, 40),
        tail_angle=-20,
    )

    def underlay(frame: Image.Image) -> None:
        line(frame, (75, 0), (75, bar_y))
        paste_part(frame, parts.get("bench"), (75, 122), (63, 27))

    def equipment(frame: Image.Image) -> None:
        if variant == "neutral":
            line(frame, left_wrist, right_wrist, width=4)
        else:
            paste_part(frame, parts.get("pulldown-bar"), (75, bar_y), (85, 18))

    return draw_pose(parts, pose, underlay=underlay, equipment=equipment)


def straight_arm_pulldown(parts: PartLibrary, phase: float, _variant: str) -> RenderedFrame:
    torso_center = (92, 70)
    pose = side_pose(
        torso_center,
        -8,
        (-110 + 112 * phase, -108 + 110 * phase),
        (-106 + 108 * phase, -104 + 106 * phase),
        (3, -3),
        (-3, 3),
        head_angle=6,
    )
    anchor = (19, 15)

    def underlay(frame: Image.Image) -> None:
        line(frame, anchor, pose.left_arm[2])
        line(frame, anchor, pose.right_arm[2])

    def equipment(frame: Image.Image) -> None:
        paste_part(frame, parts.get("pulldown-bar"), pose.left_arm[2], (42, 12), 90)

    return draw_pose(parts, pose, underlay=underlay, equipment=equipment)


def prone_raise(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    torso_center = (80, 91)
    torso_angle = -90
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    left_shoulder = (shoulder[0], shoulder[1] - 3)
    right_shoulder = (shoulder[0], shoulder[1] + 4)
    if variant == "y":
        left_upper_angle = -30 - 76 * phase
        right_upper_angle = 30 + 76 * phase
        left_forearm_angle = left_upper_angle
        right_forearm_angle = right_upper_angle
    else:
        left_upper_angle = -18 - 54 * phase
        right_upper_angle = 18 + 54 * phase
        left_forearm_angle = -12 - 118 * phase
        right_forearm_angle = 12 + 118 * phase
    left_elbow = endpoint(left_shoulder, 23, left_upper_angle)
    right_elbow = endpoint(right_shoulder, 23, right_upper_angle)
    left_wrist = endpoint(left_elbow, 22, left_forearm_angle)
    right_wrist = endpoint(right_elbow, 22, right_forearm_angle)
    left_hip = (hip[0], hip[1] - 2)
    right_hip = (hip[0], hip[1] + 3)
    left_knee = endpoint(left_hip, 27, 70)
    right_knee = endpoint(right_hip, 27, 76)
    left_ankle = endpoint(left_knee, 25, 84)
    right_ankle = endpoint(right_knee, 25, 88)
    pose = RigPose(
        "head-side",
        rotate_offset(torso_center, (0, -38), torso_angle),
        (38, 37),
        0,
        "torso-side",
        torso_center,
        (30, 61),
        torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=(109, 97),
        tail_size=(15, 38),
        tail_angle=86,
    )

    def underlay(frame: Image.Image) -> None:
        line(frame, (17, 121), (137, 121), (187, 181, 166, 255), 1)

    return draw_pose(parts, pose, underlay=underlay)


def standing_front_pose(
    left_upper_angle: float,
    right_upper_angle: float,
    left_forearm_angle: float,
    right_forearm_angle: float,
) -> RigPose:
    torso_center = (75, 69)
    left_shoulder = (58, 51)
    right_shoulder = (92, 51)
    left_elbow = endpoint(left_shoulder, 23, left_upper_angle)
    right_elbow = endpoint(right_shoulder, 23, right_upper_angle)
    left_wrist = endpoint(left_elbow, 22, left_forearm_angle)
    right_wrist = endpoint(right_elbow, 22, right_forearm_angle)
    left_hip = (65, 91)
    right_hip = (85, 91)
    left_knee = endpoint(left_hip, 27, 5)
    right_knee = endpoint(right_hip, 27, -5)
    left_ankle = endpoint(left_knee, 25, -2)
    right_ankle = endpoint(right_knee, 25, 2)
    return RigPose(
        "head-front", (75, 33), (49, 45), 0,
        "torso-front", torso_center, (48, 64), 0,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_center=(103, 93), tail_size=(27, 44), tail_angle=-18,
    )


def biceps_curl(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    pose = standing_front_pose(
        5, -5,
        5 + 150 * phase,
        -5 - 150 * phase,
    )

    def underlay(frame: Image.Image) -> None:
        if variant == "cable":
            line(frame, (18, 144), pose.left_arm[2])
            line(frame, (132, 144), pose.right_arm[2])

    if variant == "cable":
        def equipment(frame: Image.Image) -> None:
            paste_part(frame, parts.get("cable-handle"), pose.left_arm[2], (14, 23), 90)
            paste_part(frame, parts.get("cable-handle"), pose.right_arm[2], (14, 23), 90)
    else:
        angle = 0 if variant == "hammer" else 90
        equipment = dumbbell_layer(parts, ((pose.left_arm[2], angle), (pose.right_arm[2], angle)))
    return draw_pose(parts, pose, underlay=underlay if variant == "cable" else None, equipment=equipment)


def lateral_raise(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    pose = standing_front_pose(
        5 + 82 * phase,
        -5 - 82 * phase,
        5 + 82 * phase,
        -5 - 82 * phase,
    )
    single = variant == "single"

    def underlay(frame: Image.Image) -> None:
        if variant == "cable":
            line(frame, (20, 144), pose.left_arm[2])
            line(frame, (130, 144), pose.right_arm[2])

    if variant == "cable":
        def equipment(frame: Image.Image) -> None:
            paste_part(frame, parts.get("cable-handle"), pose.left_arm[2], (14, 23), 90)
            paste_part(frame, parts.get("cable-handle"), pose.right_arm[2], (14, 23), 90)
    else:
        placements = [(pose.left_arm[2], 90)]
        if not single:
            placements.append((pose.right_arm[2], 90))
        equipment = dumbbell_layer(parts, placements)
    return draw_pose(parts, pose, underlay=underlay if variant == "cable" else None, equipment=equipment)


def shrug(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    base = standing_front_pose(5, -5, 5, -5)
    lift = 10 * phase

    def raised(limb: Limb) -> Limb:
        return tuple((point[0], point[1] - lift) for point in limb)  # type: ignore[return-value]

    pose = replace(
        base,
        left_arm=raised(base.left_arm),
        right_arm=raised(base.right_arm),
    )

    def underlay(frame: Image.Image) -> None:
        if variant == "cable":
            line(frame, (20, 144), pose.left_arm[2])
            line(frame, (130, 144), pose.right_arm[2])

    if variant in {"cable", "machine"}:
        def equipment(frame: Image.Image) -> None:
            paste_part(frame, parts.get("cable-handle"), pose.left_arm[2], (14, 23), 90)
            paste_part(frame, parts.get("cable-handle"), pose.right_arm[2], (14, 23), 90)
    else:
        equipment = dumbbell_layer(parts, ((pose.left_arm[2], 90), (pose.right_arm[2], 90)))
    return draw_pose(parts, pose, underlay=underlay if variant == "cable" else None, equipment=equipment)


def triceps_extension(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    if variant == "lying":
        torso_center = (76, 88)
        pose = side_pose(
            torso_center, 90,
            (-90, 176 - 160 * phase),
            (90, -176 + 160 * phase),
            (-34, 28), (-22, 36),
            torso_size=(44, 59), head_size=(42, 39),
        )

        def underlay(frame: Image.Image) -> None:
            paste_part(frame, parts.get("bench"), (74, 111), (118, 48))

        equipment = dumbbell_layer(parts, ((pose.left_arm[2], 90), (pose.right_arm[2], 90)))
        return draw_pose(parts, pose, underlay=underlay, equipment=equipment)

    if variant == "overhead":
        pose = standing_front_pose(
            -8, 8,
            170 - 120 * phase,
            -170 + 120 * phase,
        )
        equipment = dumbbell_layer(parts, ((((pose.left_arm[2][0] + pose.right_arm[2][0]) / 2,
                                             (pose.left_arm[2][1] + pose.right_arm[2][1]) / 2), 0),))
        return draw_pose(parts, pose, equipment=equipment)

    pose = standing_front_pose(
        5, -5,
        150 - 145 * phase,
        -150 + 145 * phase,
    )

    def underlay(frame: Image.Image) -> None:
        line(frame, (25, 2), pose.left_arm[2])
        line(frame, (125, 2), pose.right_arm[2])

    def equipment(frame: Image.Image) -> None:
        paste_part(frame, parts.get("cable-handle"), pose.left_arm[2], (14, 23), 90)
        paste_part(frame, parts.get("cable-handle"), pose.right_arm[2], (14, 23), 90)

    return draw_pose(parts, pose, underlay=underlay, equipment=equipment)


def rear_delt(parts: PartLibrary, phase: float, variant: str) -> RenderedFrame:
    if variant == "face-pull":
        pose = standing_front_pose(
            88 - 30 * phase,
            -88 + 30 * phase,
            88 + 72 * phase,
            -88 - 72 * phase,
        )

        def underlay(frame: Image.Image) -> None:
            line(frame, (75, 6), pose.left_arm[2])
            line(frame, (75, 6), pose.right_arm[2])

        def equipment(frame: Image.Image) -> None:
            paste_part(frame, parts.get("cable-handle"), pose.left_arm[2], (14, 23), 90)
            paste_part(frame, parts.get("cable-handle"), pose.right_arm[2], (14, 23), 90)

        return draw_pose(parts, pose, underlay=underlay, equipment=equipment)

    torso_center = (80, 90)
    torso_angle = -90
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    left_shoulder = (shoulder[0], shoulder[1] - 3)
    right_shoulder = (shoulder[0], shoulder[1] + 4)
    left_upper_angle = -10 - 82 * phase
    right_upper_angle = 10 + 82 * phase
    left_elbow = endpoint(left_shoulder, 23, left_upper_angle)
    right_elbow = endpoint(right_shoulder, 23, right_upper_angle)
    left_wrist = endpoint(left_elbow, 22, left_upper_angle)
    right_wrist = endpoint(right_elbow, 22, right_upper_angle)
    left_hip = (hip[0], hip[1] - 2)
    right_hip = (hip[0], hip[1] + 3)
    left_knee = endpoint(left_hip, 27, 70)
    right_knee = endpoint(right_hip, 27, 76)
    left_ankle = endpoint(left_knee, 25, 84)
    right_ankle = endpoint(right_knee, 25, 88)
    pose = RigPose(
        "head-side", rotate_offset(torso_center, (0, -38), torso_angle), (38, 37), 0,
        "torso-side", torso_center, (30, 61), torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight", tail_center=(109, 97), tail_size=(15, 38), tail_angle=86,
    )

    def underlay(frame: Image.Image) -> None:
        line(frame, (17, 121), (137, 121), (187, 181, 166, 255), 1)
        if variant == "cable":
            line(frame, (17, 20), pose.left_arm[2])
            line(frame, (17, 20), pose.right_arm[2])
        else:
            paste_part(frame, parts.get("bench"), (84, 112), (105, 40))

    if variant == "cable":
        def equipment(frame: Image.Image) -> None:
            paste_part(frame, parts.get("cable-handle"), pose.left_arm[2], (14, 23), 90)
            paste_part(frame, parts.get("cable-handle"), pose.right_arm[2], (14, 23), 90)
    else:
        equipment = dumbbell_layer(parts, ((pose.left_arm[2], 90), (pose.right_arm[2], 90)))
    return draw_pose(parts, pose, underlay=underlay, equipment=equipment)


def dumbbell_row(parts: PartLibrary, phase: float, _variant: str) -> RenderedFrame:
    torso_center = (82, 67)
    torso_angle = -55
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    head = rotate_offset(torso_center, (0, -38), torso_angle)
    left_shoulder = (shoulder[0], shoulder[1] - 2)
    right_shoulder = (shoulder[0] + 3, shoulder[1] + 2)
    working_wrist = (
        67 + 18 * phase,
        96 - 18 * phase,
    )
    left_elbow = two_link_joint(left_shoulder, working_wrist, 23, 22, 1)
    support_wrist = (47, 95)
    right_elbow = two_link_joint(right_shoulder, support_wrist, 23, 22, -1)
    left_hip = (hip[0], hip[1] - 2)
    right_hip = (hip[0] + 3, hip[1] + 2)
    left_knee = (96, 105)
    left_ankle = endpoint(left_knee, 25, 88)
    right_knee = endpoint(right_hip, 27, 36)
    right_ankle = endpoint(right_knee, 25, 8)
    pose = RigPose(
        "head-side", head, (38, 37), 5,
        "torso-side", torso_center, (30, 61), torso_angle,
        (left_shoulder, left_elbow, working_wrist),
        (right_shoulder, right_elbow, support_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=rotate_offset(torso_center, (16, 25), torso_angle),
        tail_size=(17, 43),
        tail_angle=torso_angle - 30,
    )

    def underlay(frame: Image.Image) -> None:
        paste_part(frame, parts.get("bench"), (73, 115), (90, 36))
        line(frame, (18, 136), (138, 136), (187, 181, 166, 255), 1)

    return draw_pose(
        parts,
        pose,
        underlay=underlay,
        equipment=dumbbell_layer(parts, ((pose.left_arm[2], 0),)),
    )


def dead_bug(parts: PartLibrary, phase: float, _variant: str) -> RenderedFrame:
    torso_center = (75, 78)
    left_shoulder = (58, 59)
    right_shoulder = (92, 59)
    left_elbow = endpoint(left_shoulder, 23, 180 + 58 * phase)
    left_wrist = endpoint(left_elbow, 22, 180 + 58 * phase)
    right_elbow = endpoint(right_shoulder, 23, 180)
    right_wrist = endpoint(right_elbow, 22, 180)
    left_hip = (65, 99)
    right_hip = (85, 99)
    left_knee = endpoint(left_hip, 27, -48)
    left_ankle = endpoint(left_knee, 25, 42)
    right_knee = endpoint(right_hip, 27, 48 - 38 * phase)
    right_ankle = endpoint(right_knee, 25, -42 + 42 * phase)
    pose = RigPose(
        "head-front",
        (75, 37),
        (47, 43),
        0,
        "torso-front",
        torso_center,
        (47, 62),
        0,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_center=(103, 102),
        tail_size=(25, 41),
        tail_angle=-16,
    )

    def underlay(frame: Image.Image) -> None:
        ImageDraw.Draw(frame).rounded_rectangle(
            (scaled(18), scaled(12), scaled(132), scaled(142)),
            radius=scaled(8),
            fill=(245, 242, 232, 255),
            outline=(221, 216, 202, 255),
            width=scaled(1),
        )

    return draw_pose(parts, pose, underlay=underlay)


def bird_dog(parts: PartLibrary, phase: float, _variant: str) -> RenderedFrame:
    torso_center = (73, 64)
    torso_angle = -90
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    head = rotate_offset(torso_center, (0, -38), torso_angle)
    left_shoulder = (shoulder[0], shoulder[1] - 3)
    right_shoulder = (shoulder[0], shoulder[1] + 3)
    left_elbow = endpoint(left_shoulder, 23, -90 * phase)
    left_wrist = endpoint(left_elbow, 22, -90 * phase)
    right_elbow = endpoint(right_shoulder, 23, 0)
    right_wrist = endpoint(right_elbow, 22, 0)
    left_hip = (hip[0], hip[1] - 3)
    right_hip = (hip[0], hip[1] + 3)
    left_knee = endpoint(left_hip, 27, 0)
    left_ankle = endpoint(left_knee, 25, 0)
    right_knee = endpoint(right_hip, 27, 90 * phase)
    right_ankle = endpoint(right_knee, 25, 90 * phase)
    pose = RigPose(
        "head-side",
        head,
        (38, 37),
        0,
        "torso-side",
        torso_center,
        (30, 61),
        torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=(108, 54),
        tail_size=(15, 40),
        tail_angle=55,
    )

    def underlay(frame: Image.Image) -> None:
        line(frame, (14, 128), (139, 128), (187, 181, 166, 255), 1)

    return draw_pose(parts, pose, underlay=underlay)


def plank_pose(phase: float) -> RigPose:
    torso_center = (70, 88)
    torso_angle = -72
    shoulder = rotate_offset(torso_center, (0, -22), torso_angle)
    hip = rotate_offset(torso_center, (0, 23), torso_angle)
    head = rotate_offset(torso_center, (0, -38), torso_angle)
    left_shoulder = (shoulder[0] - 2, shoulder[1])
    right_shoulder = (shoulder[0] + 2, shoulder[1] + 1)
    left_elbow = endpoint(left_shoulder, 23, 0)
    left_wrist = endpoint(left_elbow, 22, -90)
    right_elbow = endpoint(right_shoulder, 23, 0)
    right_wrist = endpoint(right_elbow, 22, -90)
    left_hip = (hip[0] - 2, hip[1])
    right_hip = (hip[0] + 2, hip[1] + 1)
    body_line_angle = -torso_angle
    left_knee = endpoint(left_hip, 27, body_line_angle)
    right_knee = endpoint(right_hip, 27, body_line_angle)
    left_ankle = endpoint(left_knee, 25, body_line_angle)
    right_ankle = endpoint(right_knee, 25, body_line_angle)
    return RigPose(
        "head-side",
        (head[0], head[1] - 1.2 * phase),
        (38, 37),
        2 + 3 * phase,
        "torso-side",
        torso_center,
        (30, 62),
        torso_angle,
        (left_shoulder, left_elbow, left_wrist),
        (right_shoulder, right_elbow, right_wrist),
        (left_hip, left_knee, left_ankle),
        (right_hip, right_knee, right_ankle),
        tail_part="tail-straight",
        tail_center=rotate_offset(torso_center, (16, 24), torso_angle),
        tail_size=(15, 40),
        tail_angle=82 + 8 * phase,
    )


def plank(parts: PartLibrary, phase: float, _variant: str) -> RenderedFrame:
    pose = plank_pose(phase)

    def underlay(frame: Image.Image) -> None:
        line(frame, (14, 113), (145, 113), (187, 181, 166, 255), 1)

    return draw_pose(parts, pose, underlay=underlay)


def point_line_distance(point: Point, start: Point, end: Point) -> float:
    line_length = distance(start, end)
    if line_length == 0:
        return math.inf
    return abs(
        (end[0] - start[0]) * (start[1] - point[1])
        - (start[0] - point[0]) * (end[1] - start[1])
    ) / line_length


def validate_instructional_pose_invariants() -> None:
    phases = [cycle_phase(index) for index in range(FRAME_COUNT)]
    incline_poses = [incline_push_up_pose(phase) for phase in phases]
    plank_poses = [plank_pose(phase) for phase in phases]

    for name, poses in (("incline push-up", incline_poses), ("plank", plank_poses)):
        for pose in poses:
            for arm, leg in (
                (pose.left_arm, pose.left_leg),
                (pose.right_arm, pose.right_leg),
            ):
                shoulder = arm[0]
                hip, knee, ankle = leg
                if max(
                    point_line_distance(hip, shoulder, ankle),
                    point_line_distance(knee, shoulder, ankle),
                ) > 0.05:
                    raise RuntimeError(f"{name} no longer keeps shoulder, hip and ankle aligned")

    for arm_index in (0, 1):
        planted_wrists = [
            (pose.left_arm, pose.right_arm)[arm_index][2]
            for pose in incline_poses
        ]
        if any(distance(planted_wrists[0], wrist) > 0.01 for wrist in planted_wrists[1:]):
            raise RuntimeError("Incline push-up hands are not planted on the bench")

    for pose in plank_poses:
        for arm in (pose.left_arm, pose.right_arm):
            if abs(arm[1][1] - arm[2][1]) > 0.01:
                raise RuntimeError("Plank forearms are not parallel with the floor")

    non_working_wrists = [
        row_pose(phase, "single").right_arm[2]
        for phase in phases
    ]
    if any(
        distance(non_working_wrists[0], wrist) > 0.01
        for wrist in non_working_wrists[1:]
    ):
        raise RuntimeError("Single-arm row support hand moves during the pull")


RENDERERS: dict[str, Callable[[PartLibrary, float, str], RenderedFrame]] = {
    "squat": squat,
    "hinge": hinge,
    "horizontal-press": horizontal_press,
    "incline-push-up": incline_push_up,
    "row": row,
    "overhead-press": overhead_press,
    "pulldown": pulldown,
    "straight-arm-pulldown": straight_arm_pulldown,
    "prone-raise": prone_raise,
    "biceps-curl": biceps_curl,
    "lateral-raise": lateral_raise,
    "triceps-extension": triceps_extension,
    "rear-delt": rear_delt,
    "shrug": shrug,
    "dumbbell-row": dumbbell_row,
    "dead-bug": dead_bug,
    "bird-dog": bird_dog,
    "plank": plank,
}


def shared_palette(frames: Sequence[Image.Image], colors: int) -> Image.Image:
    strip = Image.new("RGB", (SIZE, SIZE * len(frames)))
    for index, frame in enumerate(frames):
        strip.paste(frame, (0, index * SIZE))
    return strip.quantize(
        colors=colors,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    )


def encode_gif(frames: Sequence[Image.Image], path: Path, colors: int) -> None:
    palette = shared_palette(frames, colors)
    quantized = [
        frame.quantize(palette=palette, dither=Image.Dither.NONE)
        for frame in frames
    ]
    quantized[0].save(
        path,
        save_all=True,
        append_images=quantized[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        disposal=1,
        optimize=True,
    )


def decode_gif(path: Path) -> tuple[list[Image.Image], list[int]]:
    frames: list[Image.Image] = []
    durations: list[int] = []
    with Image.open(path) as image:
        default_duration = int(image.info.get("duration", 0))
        for frame in ImageSequence.Iterator(image):
            frames.append(frame.convert("RGB").copy())
            durations.append(int(frame.info.get("duration", default_duration)))
    return frames, durations


def mean_difference(first: Image.Image, second: Image.Image) -> float:
    statistics = ImageStat.Stat(ImageChops.difference(first, second))
    return sum(statistics.mean) / len(statistics.mean)


def motion_metrics(frames: Sequence[Image.Image]) -> tuple[int, float, float]:
    hashes = {hashlib.sha256(frame.tobytes()).hexdigest() for frame in frames}
    adjacent = [
        mean_difference(frame, following)
        for frame, following in zip(frames, (*frames[1:], frames[0]), strict=True)
    ]
    return len(hashes), round(max(adjacent), 3), round(adjacent[-1], 3)


def max_frame_difference(expected: Sequence[Image.Image], actual: Sequence[Image.Image]) -> float:
    if len(expected) != len(actual):
        return math.inf
    return round(
        max(mean_difference(before, after) for before, after in zip(expected, actual, strict=True)),
        3,
    )


def maximum_drift(samples: Sequence[Sequence[float]]) -> float:
    if not samples:
        return 0
    maximum = 0.0
    for component in zip(*samples, strict=True):
        baseline = component[0]
        if baseline == 0:
            continue
        maximum = max(
            maximum,
            max(abs(value - baseline) / baseline * 100 for value in component),
        )
    return round(maximum, 4)


def render_frames(parts: PartLibrary, exercise: Exercise) -> list[RenderedFrame]:
    try:
        renderer = RENDERERS[exercise.renderer]
    except KeyError as error:
        raise RuntimeError(f"Unknown renderer for {exercise.code}: {exercise.renderer}") from error
    return [
        renderer(parts, cycle_phase(index), exercise.variant)
        for index in range(FRAME_COUNT)
    ]


def build_exercise(parts: PartLibrary, exercise: Exercise, output: Path) -> dict[str, object]:
    if exercise.code == "DUMBBELL_BENCH_PRESS":
        return build_complete_image_bench_press(exercise, output)

    rendered = render_frames(parts, exercise)
    source_frames = [
        item.image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
        for item in rendered
    ]
    gif_path = output / f"{exercise.slug}.gif"
    poster_path = output / f"{exercise.slug}-poster.jpg"

    chosen_colors = 0
    for colors in (48, 40, 32, 24, 20, 16, 12, 8):
        encode_gif(source_frames, gif_path, colors)
        if gif_path.stat().st_size <= MAX_ASSET_BYTES:
            chosen_colors = colors
            break
    if chosen_colors == 0:
        raise RuntimeError(f"{exercise.code} exceeds 80 KiB at 8 colors")

    source_frames[0].save(poster_path, "JPEG", quality=88, optimize=True, progressive=False)
    decoded, durations = decode_gif(gif_path)
    distinct_count, max_adjacent, loop_closure = motion_metrics(decoded)
    max_source_difference = max_frame_difference(source_frames, decoded)
    bone_drift = maximum_drift([item.bone_lengths for item in rendered])
    head_drift = maximum_drift([item.head_size for item in rendered])
    torso_drift = maximum_drift([item.torso_size for item in rendered])

    if len(decoded) != FRAME_COUNT:
        raise RuntimeError(
            f"{exercise.code} encoded {len(decoded)} frames instead of {FRAME_COUNT}"
        )
    if set(durations) != {FRAME_DURATION_MS}:
        raise RuntimeError(f"{exercise.code} encoded unexpected durations: {sorted(set(durations))}")
    if distinct_count < 14:
        raise RuntimeError(f"{exercise.code} has only {distinct_count} distinct decoded frames")
    if max_adjacent >= 24:
        raise RuntimeError(f"{exercise.code} has an adjacent-frame jump: {max_adjacent}")
    if loop_closure >= 10:
        raise RuntimeError(f"{exercise.code} has a discontinuous loop boundary: {loop_closure}")
    if max_source_difference >= 10:
        raise RuntimeError(
            f"{exercise.code} decoded frames drift from render: {max_source_difference}"
        )
    if bone_drift > 0.05 or head_drift > 0 or torso_drift > 0:
        raise RuntimeError(
            f"{exercise.code} violates geometry lock: "
            f"bones={bone_drift}, head={head_drift}, torso={torso_drift}"
        )

    return {
        "exerciseCode": exercise.code,
        "file": gif_path.name,
        "characterId": CHARACTER_ID,
        "rigPartSheet": "assets-source/exercise-guides/golden-cat-coach-rig-parts-v4.png",
        "rigPartSheetSha256": sha256(RIG_PART_SHEET),
        "sha256": sha256(gif_path),
        "renderMode": RENDER_MODE,
        "geometryLocked": True,
        "frameCount": len(decoded),
        "distinctFrameCount": distinct_count,
        "framesPerSecond": FRAMES_PER_SECOND,
        "frameDurationMs": FRAME_DURATION_MS,
        "paletteColors": chosen_colors,
        "maxAdjacentMeanDifference": max_adjacent,
        "loopClosureMeanDifference": loop_closure,
        "maxBoneLengthDriftPercent": bone_drift,
        "maxHeadScaleDriftPercent": head_drift,
        "maxTorsoScaleDriftPercent": torso_drift,
        "maxDecodedSourceMeanDifference": max_source_difference,
        "sizeBytes": gif_path.stat().st_size,
        "motionFamily": exercise.renderer,
        "variant": exercise.variant,
    }


def complete_image_bench_press_frames() -> list[Image.Image]:
    """Load the approved v7 full frames and build a 32-step full cycle."""

    from build_bench_press_v8_v7based_preview import (
        SOURCE_FRAMES,
        V7_BASED_DIR,
        build_motion_mask,
        load_frame,
        restore_static_regions,
    )

    master = load_frame(V7_BASED_DIR / "frame-00-bottom.png")
    motion_mask = build_motion_mask()
    upward = [
        restore_static_regions(load_frame(path), master, motion_mask, index)
        for index, (_phase, path) in enumerate(SOURCE_FRAMES)
    ]
    frames = upward + upward[-2:0:-1]
    if len(frames) != FRAME_COUNT:
        raise RuntimeError(
            "DUMBBELL_BENCH_PRESS complete-image cycle must contain "
            f"{FRAME_COUNT} frames, got {len(frames)}"
        )
    return [
        frame.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
        for frame in frames
    ]


def build_complete_image_bench_press(
    exercise: Exercise,
    output: Path,
) -> dict[str, object]:
    """Encode the user-approved v7 full-frame exception deterministically."""

    source_frames = complete_image_bench_press_frames()
    gif_path = output / f"{exercise.slug}.gif"
    poster_path = output / f"{exercise.slug}-poster.jpg"

    chosen_colors = 0
    for colors in (48, 40, 32, 24, 20, 16, 12, 8):
        encode_gif(source_frames, gif_path, colors)
        if gif_path.stat().st_size <= MAX_ASSET_BYTES:
            chosen_colors = colors
            break
    if chosen_colors == 0:
        raise RuntimeError(f"{exercise.code} exceeds 80 KiB at 8 colors")

    source_frames[0].save(
        poster_path,
        "JPEG",
        quality=88,
        optimize=True,
        progressive=False,
    )
    decoded, durations = decode_gif(gif_path)
    distinct_count, max_adjacent, loop_closure = motion_metrics(decoded)
    max_source_difference = max_frame_difference(source_frames, decoded)

    if len(decoded) != FRAME_COUNT:
        raise RuntimeError(
            f"{exercise.code} encoded {len(decoded)} frames instead of {FRAME_COUNT}"
        )
    if set(durations) != {FRAME_DURATION_MS}:
        raise RuntimeError(
            f"{exercise.code} encoded unexpected durations: "
            f"{sorted(set(durations))}"
        )
    if distinct_count < 14:
        raise RuntimeError(
            f"{exercise.code} has only {distinct_count} distinct decoded frames"
        )
    if max_adjacent >= 24:
        raise RuntimeError(
            f"{exercise.code} has an adjacent-frame jump: {max_adjacent}"
        )
    if loop_closure >= 10:
        raise RuntimeError(
            f"{exercise.code} has a discontinuous loop boundary: {loop_closure}"
        )
    if max_source_difference >= 10:
        raise RuntimeError(
            f"{exercise.code} decoded frames drift from render: "
            f"{max_source_difference}"
        )

    return {
        "exerciseCode": exercise.code,
        "file": gif_path.name,
        "characterId": CHARACTER_ID,
        "rigPartSheet": (
            "assets-source/exercise-guides/"
            "golden-cat-coach-rig-parts-v4.png"
        ),
        "rigPartSheetSha256": sha256(RIG_PART_SHEET),
        "sha256": sha256(gif_path),
        "renderMode": BENCH_PRESS_RENDER_MODE,
        "geometryLocked": True,
        "boneRig": False,
        "staticRegionLocked": True,
        "frameCount": len(decoded),
        "distinctFrameCount": distinct_count,
        "framesPerSecond": FRAMES_PER_SECOND,
        "frameDurationMs": FRAME_DURATION_MS,
        "paletteColors": chosen_colors,
        "maxAdjacentMeanDifference": max_adjacent,
        "loopClosureMeanDifference": loop_closure,
        "maxBoneLengthDriftPercent": 0,
        "maxHeadScaleDriftPercent": 0,
        "maxTorsoScaleDriftPercent": 0,
        "maxDecodedSourceMeanDifference": max_source_difference,
        "sizeBytes": gif_path.stat().st_size,
        "motionFamily": exercise.renderer,
        "variant": exercise.variant,
        "sourceFrameCount": FRAME_COUNT // 2 + 1,
    }


def write_manifest(entries: Sequence[dict[str, object]], output: Path) -> None:
    manifest = {
        "character": {
            "id": CHARACTER_ID,
            "modelSheet": "assets-source/exercise-guides/golden-cat-coach-turnaround-v3.png",
            "modelSheetSha256": sha256(MODEL_SHEET),
            "rigPartSheet": "assets-source/exercise-guides/golden-cat-coach-rig-parts-v4.png",
            "rigPartSheetSha256": sha256(RIG_PART_SHEET),
        },
        "renderMode": RENDER_MODE,
        "geometryLocked": True,
        "frameCount": FRAME_COUNT,
        "frameDurationMs": FRAME_DURATION_MS,
        "framesPerSecond": FRAMES_PER_SECOND,
        "assets": list(entries),
    }
    (output / "motion-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exercise", action="append", default=[])
    parser.add_argument("--output", type=Path, default=OUTPUT_ROOT)
    parser.add_argument("--validate-cycle", action="store_true")
    arguments = parser.parse_args()
    if arguments.validate_cycle:
        validate_cycle()
        print("valid")
        return

    validate_cycle()
    selected_codes = set(arguments.exercise)
    selected = [
        exercise
        for exercise in EXERCISES
        if not selected_codes or exercise.code in selected_codes
    ]
    missing_codes = selected_codes - {exercise.code for exercise in selected}
    if missing_codes:
        raise SystemExit(f"Unknown exercise codes: {sorted(missing_codes)}")

    arguments.output.mkdir(parents=True, exist_ok=True)
    parts = PartLibrary(RIG_PART_SHEET)
    entries = [build_exercise(parts, exercise, arguments.output) for exercise in selected]
    if not selected_codes:
        write_manifest(entries, arguments.output)
    print(json.dumps(entries, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
