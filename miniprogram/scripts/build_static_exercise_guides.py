#!/usr/bin/env python3
"""Build the approved golden-cat static exercise breakdown pack.

The output intentionally contains only a small set of instructional JPEG
keyframes. It crops the approved high-detail static action sheets (plus the
approved complete-image bench-press keyframes), and never builds, decodes, or
packages animation files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import deque
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Sequence

from PIL import Image, ImageChops, ImageFilter, ImageOps

PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = PROJECT_ROOT / "src/subpackages/exercise-guide/assets/exercise-guides"
SOURCE_ROOT = PROJECT_ROOT / "assets-source/exercise-guides"
CHARACTER_ID = "golden-shaded-cat-coach-v4-rigged"
MODEL_SHEET = SOURCE_ROOT / "golden-cat-coach-turnaround-v3.png"
RIG_PART_SHEET = SOURCE_ROOT / "golden-cat-coach-rig-parts-v4.png"
IMAGE_SIZE = 256
MAX_IMAGE_BYTES = 40 * 1024
GRID_COLUMNS = 4
GRID_ROWS = 3

BENCH_PRESS_SOURCE_FRAMES = (
    SOURCE_ROOT / "bench-press-v8-v7based/frame-00-bottom.png",
    SOURCE_ROOT / "bench-press-v8-v7based/frame-50-middle.png",
    SOURCE_ROOT / "bench-press-v8-v7based/frame-100-top.png",
)


@dataclass(frozen=True)
class StageSpec:
    id: str
    label: str
    description: str
    phase: float


@dataclass(frozen=True)
class ExerciseSpec:
    code: str
    slug: str
    renderer: str


EXERCISES: tuple[ExerciseSpec, ...] = (
    ExerciseSpec("GOBLET_SQUAT", "goblet-squat", "squat"),
    ExerciseSpec("DUMBBELL_FRONT_SQUAT", "dumbbell-front-squat", "squat"),
    ExerciseSpec("BODYWEIGHT_SQUAT", "bodyweight-squat", "squat"),
    ExerciseSpec("DUMBBELL_ROMANIAN_DEADLIFT", "dumbbell-romanian-deadlift", "hinge"),
    ExerciseSpec("DUMBBELL_DEADLIFT", "dumbbell-deadlift", "deadlift-from-floor"),
    ExerciseSpec("BODYWEIGHT_HIP_HINGE", "bodyweight-hip-hinge", "hinge"),
    ExerciseSpec(
        "DUMBBELL_BENCH_PRESS",
        "dumbbell-bench-press",
        "horizontal-press",
    ),
    ExerciseSpec("DUMBBELL_FLOOR_PRESS", "dumbbell-floor-press", "horizontal-press"),
    ExerciseSpec("INCLINE_PUSH_UP", "incline-push-up", "incline-push-up"),
    ExerciseSpec("SEATED_CABLE_ROW", "seated-cable-row", "row"),
    ExerciseSpec("CABLE_HIGH_ROW", "cable-high-row", "row"),
    ExerciseSpec("CABLE_SINGLE_ARM_ROW", "cable-single-arm-row", "row"),
    ExerciseSpec(
        "DUMBBELL_OVERHEAD_PRESS",
        "dumbbell-overhead-press",
        "overhead-press",
    ),
    ExerciseSpec("SEATED_DUMBBELL_PRESS", "seated-dumbbell-press", "overhead-press"),
    ExerciseSpec(
        "SINGLE_ARM_DUMBBELL_PRESS",
        "single-arm-dumbbell-press",
        "overhead-press",
    ),
    ExerciseSpec("LAT_PULLDOWN", "lat-pulldown", "pulldown"),
    ExerciseSpec(
        "CABLE_STRAIGHT_ARM_PULLDOWN",
        "cable-straight-arm-pulldown",
        "straight-arm-pulldown",
    ),
    ExerciseSpec("NEUTRAL_GRIP_PULLDOWN", "neutral-grip-pulldown", "pulldown"),
    ExerciseSpec("PRONE_W_RAISE", "prone-w-raise", "prone-raise"),
    ExerciseSpec("PRONE_Y_RAISE", "prone-y-raise", "prone-raise"),
    ExerciseSpec("DEAD_BUG", "dead-bug", "dead-bug"),
    ExerciseSpec("BIRD_DOG", "bird-dog", "bird-dog"),
    ExerciseSpec("PLANK", "plank", "plank"),
)


THREE_STAGE_SPECS: dict[str, tuple[StageSpec, ...]] = {
    "squat": (
        StageSpec("setup", "站稳", "脚掌稳定，负重靠近身体。", 0.0),
        StageSpec("drive", "下蹲", "屈髋屈膝，膝盖跟随脚尖方向。", 0.5),
        StageSpec("finish", "到位", "保持躯干稳定，在可控深度停住。", 1.0),
    ),
    "hinge": (
        StageSpec("setup", "站稳", "脚掌压稳，背部保持自然。", 0.0),
        StageSpec("drive", "送髋", "髋部向后，负重贴近腿部。", 0.5),
        StageSpec("finish", "到位", "大腿后侧有拉伸感时保持稳定。", 1.0),
    ),
    "deadlift-from-floor": (
        StageSpec("setup", "握稳", "哑铃靠近小腿，脚掌压稳并保持背部自然。", 0.0),
        StageSpec("drive", "提起", "脚掌发力站起，负重持续贴近腿部。", 0.5),
        StageSpec("finish", "站直", "髋膝伸直后稳定站立，避免身体后仰。", 1.0),
    ),
    "horizontal-press": (
        StageSpec("setup", "起始", "肩背和脚部先稳定支撑。", 0.0),
        StageSpec("drive", "推起", "手腕稳定，沿可控路径推起负重。", 0.5),
        StageSpec("finish", "完成", "手臂接近伸直，避免锁死关节。", 1.0),
    ),
    "incline-push-up": (
        StageSpec("setup", "支撑", "双手撑稳，身体保持一条直线。", 0.0),
        StageSpec("drive", "下降", "屈肘让胸口靠近支撑面。", 0.5),
        StageSpec("finish", "最低点", "核心收紧，不塌腰或耸肩。", 1.0),
    ),
    "row": (
        StageSpec("setup", "伸臂", "躯干稳定，肩膀自然下沉。", 0.0),
        StageSpec("drive", "后拉", "肘部向后，肩胛向中间收拢。", 0.5),
        StageSpec("finish", "收紧", "握把靠近身体，避免大幅后仰。", 1.0),
    ),
    "overhead-press": (
        StageSpec("setup", "肩前", "核心收紧，负重稳定在肩部附近。", 0.0),
        StageSpec("drive", "推举", "沿头部两侧向上推，避免腰部后仰。", 0.5),
        StageSpec("finish", "顶端", "负重位于身体上方，肩颈保持放松。", 1.0),
    ),
    "pulldown": (
        StageSpec("setup", "伸展", "坐稳并保持胸廓自然。", 0.0),
        StageSpec("drive", "下拉", "肘部向下，握把朝上胸移动。", 0.5),
        StageSpec("finish", "收紧", "肩胛下沉，不把握把拉到颈后。", 1.0),
    ),
    "straight-arm-pulldown": (
        StageSpec("setup", "举臂", "手臂接近伸直，躯干保持稳定。", 0.0),
        StageSpec("drive", "下压", "以肩关节为轴向下压握把。", 0.5),
        StageSpec("finish", "到腿侧", "背部收紧，避免摆动借力。", 1.0),
    ),
    "prone-raise": (
        StageSpec("setup", "俯卧", "颈部放松，额头轻靠支撑。", 0.0),
        StageSpec("drive", "抬臂", "肩胛先稳定，再小幅抬起手臂。", 0.5),
        StageSpec("finish", "收紧", "保持 W 或 Y 形，不耸肩。", 1.0),
    ),
}

FOUR_STAGE_SPECS: dict[str, tuple[StageSpec, ...]] = {
    "dead-bug": (
        StageSpec("setup", "准备", "仰卧抬起手脚，腰背保持稳定。", 0.0),
        StageSpec("extend", "伸展", "对侧手臂和腿缓慢远离身体。", 0.34),
        StageSpec("control", "控制", "继续伸展，同时保持腰背贴稳。", 0.67),
        StageSpec("finish", "到位", "在躯干不晃动的范围内停住。", 1.0),
    ),
    "bird-dog": (
        StageSpec("setup", "四点支撑", "双手双膝压稳，背部保持平直。", 0.0),
        StageSpec("extend", "开始伸展", "对侧手脚缓慢离地。", 0.34),
        StageSpec("control", "继续伸展", "髋部保持水平，避免身体旋转。", 0.67),
        StageSpec("finish", "伸直到位", "手脚延伸，躯干仍然稳定。", 1.0),
    ),
}

TWO_STAGE_SPECS: dict[str, tuple[StageSpec, ...]] = {
    "plank": (
        StageSpec("setup", "支撑定位", "前臂和脚尖压稳，身体接近直线。", 0.0),
        StageSpec("hold", "稳定保持", "收紧腹部并自然呼吸，不塌腰。", 1.0),
    ),
}


def stages_for(renderer: str) -> tuple[StageSpec, ...]:
    return (
        FOUR_STAGE_SPECS.get(renderer)
        or TWO_STAGE_SPECS.get(renderer)
        or THREE_STAGE_SPECS[renderer]
    )


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


CURATED_SOURCE_INDICES: dict[str, tuple[int, ...]] = {
    # The generic 0/2/5 selection catches the return path instead of the
    # lowest position on this cyclic source sheet.
    "incline-push-up": (0, 2, 4),
    # Cell 5 has already started lowering again; cell 3 is the clear lockout.
    "dumbbell-floor-press": (0, 2, 3),
    # Cells 0 and 2 are effectively the same setup pose. Cell 8 supplies the
    # missing controlled mid-range before the stable overhead position.
    "single-arm-dumbbell-press": (0, 8, 3),
}


def source_indices(exercise_slug: str, renderer: str) -> tuple[int, ...]:
    curated = CURATED_SOURCE_INDICES.get(exercise_slug)
    if curated is not None:
        return curated
    if renderer in FOUR_STAGE_SPECS:
        return (0, 1, 2, 3)
    if renderer in TWO_STAGE_SPECS:
        return (0, 3)
    return (0, 2, 5)


def source_image(
    exercise_slug: str,
    renderer: str,
    stage_index: int,
) -> tuple[Image.Image, Path, int | None]:
    if exercise_slug == "dumbbell-bench-press":
        path = BENCH_PRESS_SOURCE_FRAMES[stage_index]
        with Image.open(path) as opened:
            return opened.convert("RGB"), path, None

    path = SOURCE_ROOT / f"{exercise_slug}-sprite-v3.png"
    source_index = source_indices(exercise_slug, renderer)[stage_index]
    row, column = divmod(source_index, GRID_COLUMNS)
    with Image.open(path) as opened:
        sheet = opened.convert("RGB")
    left = round(column * sheet.width / GRID_COLUMNS)
    top = round(row * sheet.height / GRID_ROWS)
    right = round((column + 1) * sheet.width / GRID_COLUMNS)
    bottom = round((row + 1) * sheet.height / GRID_ROWS)
    return (
        isolate_primary_subject(sheet.crop((left, top, right, bottom))),
        path,
        source_index,
    )


def isolate_primary_subject(image: Image.Image) -> Image.Image:
    corners = (
        image.getpixel((0, 0)),
        image.getpixel((image.width - 1, 0)),
        image.getpixel((0, image.height - 1)),
        image.getpixel((image.width - 1, image.height - 1)),
    )
    background_color = tuple(
        round(sum(pixel[channel] for pixel in corners) / len(corners))
        for channel in range(3)
    )
    background = Image.new("RGB", image.size, background_color)
    mask = ImageChops.difference(image, background).convert("L").point(
        lambda value: 255 if value > 12 else 0
    )
    pixels = mask.tobytes()
    visited = bytearray(len(pixels))
    largest: list[int] = []
    width = image.width
    height = image.height

    for start, value in enumerate(pixels):
        if not value or visited[start]:
            continue
        component: list[int] = []
        queue = deque([start])
        visited[start] = 1
        while queue:
            current = queue.popleft()
            component.append(current)
            x = current % width
            y = current // width
            for next_x, next_y in (
                (x - 1, y),
                (x + 1, y),
                (x, y - 1),
                (x, y + 1),
            ):
                if not (0 <= next_x < width and 0 <= next_y < height):
                    continue
                next_index = next_y * width + next_x
                if pixels[next_index] and not visited[next_index]:
                    visited[next_index] = 1
                    queue.append(next_index)
        if len(component) > len(largest):
            largest = component

    if not largest:
        raise RuntimeError("Static source cell contains no foreground subject")
    kept = bytearray(len(pixels))
    for index in largest:
        kept[index] = 255
    subject_mask = Image.frombytes("L", image.size, bytes(kept)).filter(
        ImageFilter.MaxFilter(5)
    )
    subject = Image.composite(image, background, subject_mask)
    box = subject_mask.getbbox()
    if box is None:
        raise RuntimeError("Static source subject mask is empty")
    padding = 8
    return subject.crop(
        (
            max(0, box[0] - padding),
            max(0, box[1] - padding),
            min(image.width, box[2] + padding),
            min(image.height, box[3] + padding),
        )
    )


def encode_jpeg(image: Image.Image) -> bytes:
    resized = ImageOps.contain(
        image,
        (IMAGE_SIZE, IMAGE_SIZE),
        Image.Resampling.LANCZOS,
    )
    canvas = Image.new("RGB", (IMAGE_SIZE, IMAGE_SIZE), (250, 248, 241))
    canvas.paste(
        resized,
        ((IMAGE_SIZE - resized.width) // 2, (IMAGE_SIZE - resized.height) // 2),
    )
    for quality in (86, 82, 78, 74, 70):
        output = BytesIO()
        canvas.save(
            output,
            "JPEG",
            quality=quality,
            optimize=True,
            progressive=False,
            subsampling=2,
        )
        value = output.getvalue()
        if len(value) <= MAX_IMAGE_BYTES:
            return value
    raise RuntimeError("Static exercise keyframe exceeds the 40 KiB package limit")


def typescript_identifier(file: str) -> str:
    parts = file.removesuffix(".jpg").split("-")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


def typescript_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def write_typescript(
    entries: Sequence[dict[str, object]],
    output: Path,
) -> None:
    stages = [
        stage
        for entry in entries
        for stage in entry["stages"]  # type: ignore[index]
    ]
    lines = [
        "/* Generated by scripts/build_static_exercise_guides.py. Do not edit manually. */",
    ]
    for stage in stages:
        file = str(stage["file"])
        lines.append(f"import {typescript_identifier(file)} from './{file}'")
    lines.extend(("", "export const staticGuidesByExerciseCode = {"))
    for entry in entries:
        lines.append(f"  {entry['exerciseCode']}: {{")
        lines.append(f"    primaryRef: {typescript_string(str(entry['primaryRef']))},")
        lines.append("    stages: [")
        for stage in entry["stages"]:  # type: ignore[index]
            file = str(stage["file"])
            lines.extend(
                (
                    "      {",
                    f"        id: {typescript_string(str(stage['id']))},",
                    f"        label: {typescript_string(str(stage['label']))},",
                    f"        description: {typescript_string(str(stage['description']))},",
                    f"        source: {typescript_identifier(file)},",
                    "      },",
                )
            )
        lines.extend(("    ],", "  },"))
    lines.extend(("} as const", "", "export const staticSourcesByAssetRef = {"))
    for stage in stages:
        file = str(stage["file"])
        lines.append(
            f"  {typescript_string(f'asset://exercise-guides/{file}')}: "
            f"{typescript_identifier(file)},"
        )
    lines.extend(("} as const", ""))
    (output / "static-assets.generated.ts").write_text(
        "\n".join(lines),
        encoding="utf-8",
    )


def prune_old_runtime_media(output: Path) -> None:
    for path in output.iterdir():
        if path.is_file() and (
            path.suffix.lower() in {".gif", ".webp", ".mp4", ".webm"}
            or path.name.endswith("-poster.jpg")
            or path.name in {"motion-manifest.json", "THIRD_PARTY_NOTICES.md"}
        ):
            path.unlink()


def build(output: Path) -> list[dict[str, object]]:
    output.mkdir(parents=True, exist_ok=True)
    prune_old_runtime_media(output)
    entries: list[dict[str, object]] = []

    for exercise in EXERCISES:
        stage_entries: list[dict[str, object]] = []
        for index, stage in enumerate(stages_for(exercise.renderer), start=1):
            source, source_path, source_grid_index = source_image(
                exercise.slug,
                exercise.renderer,
                index - 1,
            )
            file = (
                f"{exercise.slug}-{index:02d}-{stage.id}.jpg"
            )
            value = encode_jpeg(source)
            (output / file).write_bytes(value)
            stage_entries.append(
                {
                    "id": stage.id,
                    "label": stage.label,
                    "description": stage.description,
                    "file": file,
                    "phase": stage.phase,
                    "sourceFile": str(source_path.relative_to(PROJECT_ROOT)).replace("\\", "/"),
                    **(
                        {"sourceGridIndex": source_grid_index}
                        if source_grid_index is not None
                        else {}
                    ),
                    "sourceSha256": sha256(source_path),
                    "sha256": hashlib.sha256(value).hexdigest(),
                    "sizeBytes": len(value),
                }
            )
        entries.append(
            {
                "exerciseCode": exercise.code,
                "primaryRef": (
                    "asset://exercise-guides/"
                    f"{stage_entries[0]['file']}"
                ),
                "stages": stage_entries,
            }
        )

    manifest = {
        "format": "static-keyframes-v1",
        "imageWidth": IMAGE_SIZE,
        "imageHeight": IMAGE_SIZE,
        "maxImageBytes": MAX_IMAGE_BYTES,
        "character": {
            "id": CHARACTER_ID,
            "modelSheet": "assets-source/exercise-guides/golden-cat-coach-turnaround-v3.png",
            "modelSheetSha256": sha256(MODEL_SHEET),
            "rigPartSheet": "assets-source/exercise-guides/golden-cat-coach-rig-parts-v4.png",
            "rigPartSheetSha256": sha256(RIG_PART_SHEET),
        },
        "renderMode": "approved-static-source-crops",
        "assets": entries,
    }
    (output / "static-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_typescript(entries, output)
    return entries


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT_ROOT)
    arguments = parser.parse_args()
    entries = build(arguments.output)
    print(
        json.dumps(
            {
                "actions": len(entries),
                "stages": sum(len(entry["stages"]) for entry in entries),
                "output": str(arguments.output),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
