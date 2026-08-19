"""Validate and preview v7-based complete-image bench-press keyframes.

These keyframes are complete illustrations derived from the v7 bottom pose.
This script deliberately does not assemble limb parts and does not build a GIF.
Animation interpolation starts only after the complete poses pass visual review.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageStat


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = (
    ROOT
    / "assets-source"
    / "exercise-guides"
    / "bench-press-v8-v7based"
)
PREVIEW_DIR = ROOT / "assets-source" / "exercise-guides" / "previews"
CONTACT_PATH = (
    PREVIEW_DIR
    / "dumbbell-bench-press-v8-v7based-keyframes.png"
)
METRICS_PATH = (
    PREVIEW_DIR
    / "dumbbell-bench-press-v8-v7based-keyframes.json"
)

FRAME_NAMES = (
    ("0%", "frame-00-bottom.png"),
    ("25%", "frame-25-quarter.png"),
    ("50%", "frame-50-middle.png"),
    ("75%", "frame-75-three-quarter.png"),
    ("100%", "frame-100-top.png"),
)

EXPECTED_SIZE = (1254, 1254)
CELL_SIZE = 260
LABEL_HEIGHT = 26
GAP = 8
MARGIN = 8
BACKGROUND = (248, 245, 237)

# The shoulders, both complete arms, hands, and dumbbells may change here.
# Everything outside this envelope should remain visually stable.
ARM_MOTION_ENVELOPE = (230, 130, 1080, 860)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_frames() -> list[tuple[str, Path, Image.Image]]:
    frames: list[tuple[str, Path, Image.Image]] = []
    for phase, name in FRAME_NAMES:
        path = SOURCE_DIR / name
        if not path.exists():
            raise FileNotFoundError(path)
        frame = Image.open(path).convert("RGB")
        if frame.size != EXPECTED_SIZE:
            raise RuntimeError(
                f"Unexpected frame size for {path}: {frame.size}"
            )
        frames.append((phase, path, frame))
    return frames


def outside_arm_mask() -> Image.Image:
    mask = Image.new("L", EXPECTED_SIZE, 255)
    ImageDraw.Draw(mask).rectangle(ARM_MOTION_ENVELOPE, fill=0)
    return mask


def difference_metrics(
    reference: Image.Image,
    frame: Image.Image,
    mask: Image.Image,
) -> tuple[float, float]:
    difference = ImageChops.difference(reference, frame)
    mean_difference = (
        sum(ImageStat.Stat(difference, mask=mask).mean) / 3
    )
    thresholded = difference.convert("L").point(
        lambda value: 255 if value > 18 else 0
    )
    changed_pixels = ImageStat.Stat(thresholded, mask=mask).mean[0] / 255
    return round(mean_difference, 3), round(changed_pixels * 100, 3)


def save_contact_sheet(
    frames: list[tuple[str, Path, Image.Image]],
) -> None:
    width = (
        MARGIN * 2
        + CELL_SIZE * len(frames)
        + GAP * (len(frames) - 1)
    )
    height = MARGIN * 2 + LABEL_HEIGHT + CELL_SIZE
    contact = Image.new("RGB", (width, height), BACKGROUND)
    draw = ImageDraw.Draw(contact)

    for index, (phase, _path, frame) in enumerate(frames):
        left = MARGIN + index * (CELL_SIZE + GAP)
        draw.text(
            (left + 4, MARGIN + 5),
            phase,
            fill=(11, 47, 40),
        )
        contact.paste(
            frame.resize(
                (CELL_SIZE, CELL_SIZE),
                Image.Resampling.LANCZOS,
            ),
            (left, MARGIN + LABEL_HEIGHT),
        )
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    contact.save(CONTACT_PATH, optimize=True)


def build_metrics(
    frames: list[tuple[str, Path, Image.Image]],
) -> dict[str, object]:
    reference = frames[0][2]
    mask = outside_arm_mask()
    entries: list[dict[str, object]] = []
    for phase, path, frame in frames:
        mean_difference, changed_percent = difference_metrics(
            reference,
            frame,
            mask,
        )
        entries.append(
            {
                "phase": phase,
                "file": path.name,
                "sha256": sha256(path),
                "outsideArmMeanDifference": mean_difference,
                "outsideArmChangedPixelPercent": changed_percent,
            }
        )
    return {
        "source": "v7 complete-image keyframes",
        "renderMode": "complete-image-identity-preserve",
        "gifBuilt": False,
        "frameSize": list(EXPECTED_SIZE),
        "armMotionEnvelope": list(ARM_MOTION_ENVELOPE),
        "frames": entries,
    }


def main() -> None:
    frames = load_frames()
    save_contact_sheet(frames)
    metrics = build_metrics(frames)
    METRICS_PATH.write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    print(f"contact: {CONTACT_PATH}")


if __name__ == "__main__":
    main()
