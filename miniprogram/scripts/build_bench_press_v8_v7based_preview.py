"""Build the v7-based dumbbell bench-press GIF from complete illustrations.

The animation deliberately avoids optical-flow interpolation, cross-fades,
bone rigs, and separated limb layers.  Every motion step starts as a complete
illustration.  A shared soft mask then restores the unchanged parts from the
v7 bottom master so that the lower body, tail, and bench do not wobble.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat


ROOT = Path(__file__).resolve().parents[1]
GUIDE_ROOT = ROOT / "assets-source" / "exercise-guides"
V7_DIR = GUIDE_ROOT / "bench-press-v7"
V7_BASED_DIR = GUIDE_ROOT / "bench-press-v8-v7based"
PREVIEW_DIR = GUIDE_ROOT / "previews"
OUTPUT_FRAME_DIR = PREVIEW_DIR / "dumbbell-bench-press-v8-v7based-frames"
GIF_PATH = PREVIEW_DIR / "dumbbell-bench-press-v8-v7based-preview.gif"
CONTACT_PATH = PREVIEW_DIR / "dumbbell-bench-press-v8-v7based-contact.png"
METRICS_PATH = PREVIEW_DIR / "dumbbell-bench-press-v8-v7based-metrics.json"

SOURCE_SIZE = (1254, 1254)
OUTPUT_SIZE = (320, 320)
FRAME_DURATION_MS = 40
FPS = 25
ENDPOINT_FRAME_COUNT = 4
BACKGROUND = (248, 245, 237)

# The order is based on the measured top edges of both dumbbells, not on the
# labels requested during image generation.  This prevents a hand or dumbbell
# from reversing direction midway through the press.
SOURCE_FRAMES = (
    ("00", V7_BASED_DIR / "frame-25-quarter.png"),
    ("01", V7_BASED_DIR / "frame-00-bottom.png"),
    ("02", V7_DIR / "keyframe-transition-low.png"),
    ("03", V7_BASED_DIR / "frame-transition-low-mid.png"),
    ("04", V7_DIR / "keyframe-012-eighth.png"),
    ("05", V7_DIR / "keyframe-transition-low-synced.png"),
    ("06", V7_BASED_DIR / "frame-50-middle.png"),
    ("07", V7_DIR / "keyframe-050-middle.png"),
    ("08", V7_DIR / "keyframe-transition-extra.png"),
    ("09", V7_DIR / "keyframe-037-three-eighth.png"),
    ("10", V7_DIR / "keyframe-transition-mid-low.png"),
    ("11", V7_BASED_DIR / "frame-transition-high-mid.png"),
    ("12", V7_BASED_DIR / "frame-75-three-quarter.png"),
    ("13", V7_DIR / "keyframe-transition-mid-high.png"),
    ("14", V7_DIR / "keyframe-075-three-quarter.png"),
    ("15", V7_BASED_DIR / "frame-transition-top.png"),
    ("16", V7_BASED_DIR / "frame-100-top.png"),
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_frame(path: Path) -> Image.Image:
    if not path.exists():
        raise FileNotFoundError(path)
    frame = Image.open(path).convert("RGB")
    if frame.size != SOURCE_SIZE:
        raise RuntimeError(f"Unexpected frame size for {path}: {frame.size}")
    return frame.resize(OUTPUT_SIZE, Image.Resampling.LANCZOS)


def build_motion_mask() -> Image.Image:
    """Return the soft union envelope for both full arm trajectories."""

    mask = Image.new("L", OUTPUT_SIZE, 0)
    draw = ImageDraw.Draw(mask)
    draw.polygon(
        (
            (70, 34),
            (181, 34),
            (188, 171),
            (177, 197),
            (153, 214),
            (116, 211),
            (84, 192),
            (68, 151),
        ),
        fill=255,
    )
    draw.polygon(
        (
            (153, 34),
            (278, 34),
            (280, 181),
            (267, 209),
            (239, 228),
            (205, 225),
            (177, 202),
            (158, 166),
        ),
        fill=255,
    )
    return mask.filter(ImageFilter.GaussianBlur(radius=5))


def restore_static_regions(
    source: Image.Image,
    master: Image.Image,
    motion_mask: Image.Image,
    index: int,
) -> Image.Image:
    """Keep the complete moving pose while locking visibly static regions."""

    stabilized = Image.composite(source, master, motion_mask)

    # The belly never intersects the moving arms.  Restoring it removes the
    # most noticeable full-frame redraw shimmer.
    belly_mask = Image.new("L", OUTPUT_SIZE, 0)
    ImageDraw.Draw(belly_mask).ellipse((112, 166, 197, 238), fill=255)
    belly_mask = belly_mask.filter(ImageFilter.GaussianBlur(radius=3))
    stabilized = Image.composite(master, stabilized, belly_mask)

    # Once the dumbbells are above the forehead, the face is unobstructed and
    # can be locked as well.  Earlier frames retain their complete source face
    # because the right dumbbell overlaps its edge.
    if index >= 8:
        face_mask = Image.new("L", OUTPUT_SIZE, 0)
        ImageDraw.Draw(face_mask).ellipse((151, 108, 220, 165), fill=255)
        face_mask = face_mask.filter(ImageFilter.GaussianBlur(radius=2))
        stabilized = Image.composite(master, stabilized, face_mask)

    return stabilized


def save_keyframes(frames: list[Image.Image]) -> None:
    OUTPUT_FRAME_DIR.mkdir(parents=True, exist_ok=True)
    for stale in OUTPUT_FRAME_DIR.glob("frame-*.png"):
        stale.unlink()
    for index, frame in enumerate(frames):
        frame.save(
            OUTPUT_FRAME_DIR / f"frame-{index:02d}.png",
            optimize=True,
        )


def save_contact_sheet(frames: list[Image.Image]) -> None:
    columns = 6
    rows = 3
    cell = 160
    label_height = 22
    gap = 8
    margin = 8
    width = margin * 2 + columns * cell + (columns - 1) * gap
    height = margin * 2 + rows * (cell + label_height) + (rows - 1) * gap
    contact = Image.new("RGB", (width, height), BACKGROUND)
    draw = ImageDraw.Draw(contact)

    for index, frame in enumerate(frames):
        row, column = divmod(index, columns)
        left = margin + column * (cell + gap)
        top = margin + row * (cell + label_height + gap)
        draw.text((left + 4, top + 4), f"{index:02d}", fill=(11, 47, 40))
        contact.paste(
            frame.resize((cell, cell), Image.Resampling.LANCZOS),
            (left, top + label_height),
        )

    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    contact.save(CONTACT_PATH, optimize=True)


def build_animation(
    keyframes: list[Image.Image],
) -> tuple[list[Image.Image], list[int]]:
    frames = keyframes + keyframes[-2:0:-1]
    durations = [FRAME_DURATION_MS] * len(frames)
    endpoint_duration = FRAME_DURATION_MS * ENDPOINT_FRAME_COUNT
    durations[0] = endpoint_duration
    durations[len(keyframes) - 1] = endpoint_duration
    return frames, durations


def quantize_animation(frames: list[Image.Image]) -> list[Image.Image]:
    palette_strip = Image.new(
        "RGB",
        (OUTPUT_SIZE[0], OUTPUT_SIZE[1] * len(frames)),
        BACKGROUND,
    )
    for index, frame in enumerate(frames):
        palette_strip.paste(frame, (0, index * OUTPUT_SIZE[1]))
    palette = palette_strip.quantize(
        colors=160,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.FLOYDSTEINBERG,
    )
    return [
        frame.quantize(
            palette=palette,
            dither=Image.Dither.FLOYDSTEINBERG,
        )
        for frame in frames
    ]


def save_gif(frames: list[Image.Image], durations: list[int]) -> None:
    quantized = quantize_animation(frames)
    quantized[0].save(
        GIF_PATH,
        save_all=True,
        append_images=quantized[1:],
        duration=durations,
        loop=0,
        disposal=2,
        optimize=True,
    )


def static_change_percent(
    master: Image.Image,
    frame: Image.Image,
    motion_mask: Image.Image,
) -> float:
    strict_static_mask = motion_mask.point(
        lambda value: 255 if value <= 4 else 0
    )
    difference = ImageChops.difference(master, frame).convert("L")
    changed = difference.point(lambda value: 255 if value > 3 else 0)
    total = ImageStat.Stat(
        Image.new("L", OUTPUT_SIZE, 255),
        mask=strict_static_mask,
    ).sum[0]
    if total == 0:
        return 0.0
    changed_total = ImageStat.Stat(changed, mask=strict_static_mask).sum[0]
    return round(changed_total / total * 100, 4)


def motion_delta(frame_a: Image.Image, frame_b: Image.Image) -> float:
    difference = np.asarray(
        ImageChops.difference(frame_a, frame_b).convert("L"),
        dtype=np.float32,
    )
    return round(float(difference.mean()), 3)


def save_metrics(
    sources: list[Path],
    keyframes: list[Image.Image],
    animation: list[Image.Image],
    durations: list[int],
    master: Image.Image,
    motion_mask: Image.Image,
) -> None:
    entries = []
    for index, ((phase, source), frame) in enumerate(
        zip(SOURCE_FRAMES, keyframes, strict=True)
    ):
        entries.append(
            {
                "index": index,
                "phase": phase,
                "source": source.name,
                "sha256": sha256(sources[index]),
                "strictStaticChangedPixelPercent": static_change_percent(
                    master,
                    frame,
                    motion_mask,
                ),
                "deltaFromPrevious": (
                    0.0
                    if index == 0
                    else motion_delta(keyframes[index - 1], frame)
                ),
            }
        )

    metrics = {
        "source": "v7 complete-image frames plus approved v7-based frames",
        "renderMode": "complete-image-with-static-region-lock",
        "opticalInterpolation": False,
        "crossFrameBlend": False,
        "boneRig": False,
        "fps": FPS,
        "frameDurationMs": FRAME_DURATION_MS,
        "endpointDurationMs": FRAME_DURATION_MS * ENDPOINT_FRAME_COUNT,
        "uniqueUpFrames": len(keyframes),
        "animationFrames": len(animation),
        "loopDurationMs": sum(durations),
        "outputSize": list(OUTPUT_SIZE),
        "endpointFrameCount": ENDPOINT_FRAME_COUNT,
        "frames": entries,
    }
    METRICS_PATH.write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    source_paths = [path for _phase, path in SOURCE_FRAMES]
    source_frames = [load_frame(path) for path in source_paths]
    master = load_frame(V7_BASED_DIR / "frame-00-bottom.png")
    motion_mask = build_motion_mask()
    keyframes = [
        restore_static_regions(frame, master, motion_mask, index)
        for index, frame in enumerate(source_frames)
    ]

    save_keyframes(keyframes)
    save_contact_sheet(keyframes)
    animation, durations = build_animation(keyframes)
    save_gif(animation, durations)
    save_metrics(
        source_paths,
        keyframes,
        animation,
        durations,
        master,
        motion_mask,
    )

    print(f"unique complete frames: {len(keyframes)}")
    print(f"animation frames: {len(animation)}")
    print(f"fps: {FPS}")
    print(f"loop duration: {sum(durations)} ms")
    print(f"gif: {GIF_PATH}")
    print(f"contact: {CONTACT_PATH}")
    print(f"metrics: {METRICS_PATH}")


if __name__ == "__main__":
    main()
