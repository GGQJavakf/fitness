"""Build a full-frame dumbbell bench-press preview.

The runtime artifact is a sequence of complete images.  It does not depend on
a body background plus detached limb sprites.  Generated keyframes are aligned
and their stationary pixels are locked before intermediate full frames are
rendered.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (
    ROOT
    / "assets-source"
    / "exercise-guides"
    / "bench-press-full-frames-v6-keyframes.png"
)
PREVIEW_DIR = ROOT / "assets-source" / "exercise-guides" / "previews"
KEYFRAME_DIR = PREVIEW_DIR / "dumbbell-bench-press-v6-frames"
GIF_PATH = PREVIEW_DIR / "dumbbell-bench-press-v6-preview.gif"
CONTACT_PATH = PREVIEW_DIR / "dumbbell-bench-press-v6-contact.png"

CANVAS_SIZE = 504
OUTPUT_SIZE = 320
FRAME_DURATION_MS = 40
STEPS_BETWEEN_KEYFRAMES = 7
BACKGROUND = (254, 253, 249)

# The source is a generated 3 x 2 full-frame sheet.  Each crop contains the
# complete cat and bench; gutters are intentionally excluded.
# Only the first four full frames are used.  They preserve the original camera
# and body while the screen-left arm moves, matching the supplied unilateral
# motion reference.  The final two generated panels changed character scale
# and are deliberately excluded.
PANEL_ORIGINS = (
    (0, 0),
    (516, 0),
    (1030, 0),
    (0, 518),
)


def crop_keyframes(sheet: Image.Image) -> list[Image.Image]:
    frames: list[Image.Image] = []
    for left, top in PANEL_ORIGINS:
        frame = sheet.crop(
            (left, top, left + CANVAS_SIZE, top + CANVAS_SIZE)
        ).convert("RGB")
        frames.append(frame)
    return frames


def motion_region() -> Image.Image:
    """Limit changes to the screen-left arm and dumbbell trajectory."""

    region = Image.new("L", (CANVAS_SIZE, CANVAS_SIZE), 0)
    draw = ImageDraw.Draw(region)
    draw.ellipse((55, 20, 300, 320), fill=255)

    # The face and central torso stay locked even if generated linework varies.
    draw.ellipse((270, 108, 410, 245), fill=0)
    draw.ellipse((170, 250, 360, 438), fill=0)
    draw.rectangle((0, 292, CANVAS_SIZE, CANVAS_SIZE), fill=0)
    return region


def lock_stationary_pixels(
    reference: Image.Image, frame: Image.Image, allowed_region: Image.Image
) -> Image.Image:
    """Keep the complete reference frame outside actual arm-motion pixels."""

    reference_pixels = np.asarray(reference, dtype=np.int16)
    frame_pixels = np.asarray(frame, dtype=np.int16)
    difference = np.max(np.abs(frame_pixels - reference_pixels), axis=2)

    changed = Image.fromarray(np.where(difference > 46, 255, 0).astype(np.uint8))
    changed = changed.filter(ImageFilter.MedianFilter(3))
    changed = changed.filter(ImageFilter.MaxFilter(15))
    changed = changed.filter(ImageFilter.MinFilter(5))
    changed = Image.composite(changed, Image.new("L", changed.size, 0), allowed_region)
    changed = changed.filter(ImageFilter.GaussianBlur(1.8))

    return Image.composite(frame, reference, changed)


def interpolate_complete_frames(
    first: Image.Image, second: Image.Image, steps: int
) -> list[Image.Image]:
    return [
        Image.blend(first, second, step / steps)
        for step in range(steps)
    ]


def build_animation(keyframes: list[Image.Image]) -> list[Image.Image]:
    upward: list[Image.Image] = []
    for first, second in zip(keyframes, keyframes[1:]):
        upward.extend(
            interpolate_complete_frames(
                first, second, STEPS_BETWEEN_KEYFRAMES
            )
        )
    upward.append(keyframes[-1])

    # Reuse the same complete frames in reverse for a controlled descent.
    cycle = upward + upward[-2:0:-1]
    return [
        frame.resize((OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS)
        for frame in cycle
    ]


def quantize_animation(frames: list[Image.Image]) -> list[Image.Image]:
    """Use one palette so the GIF stays compact without per-frame color flicker."""

    palette_strip = Image.new(
        "RGB", (OUTPUT_SIZE, OUTPUT_SIZE * len(frames)), BACKGROUND
    )
    for index, frame in enumerate(frames):
        palette_strip.paste(frame, (0, index * OUTPUT_SIZE))

    shared_palette = palette_strip.quantize(
        colors=96,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.FLOYDSTEINBERG,
    )
    return [
        frame.quantize(
            palette=shared_palette,
            dither=Image.Dither.FLOYDSTEINBERG,
        )
        for frame in frames
    ]


def save_keyframes(keyframes: list[Image.Image]) -> None:
    KEYFRAME_DIR.mkdir(parents=True, exist_ok=True)
    for stale_frame in KEYFRAME_DIR.glob("frame-*.png"):
        stale_frame.unlink()
    for index, frame in enumerate(keyframes, start=1):
        path = KEYFRAME_DIR / f"frame-{index:02d}.png"
        frame.resize(
            (OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS
        ).save(path, optimize=True)


def save_contact_sheet(keyframes: list[Image.Image]) -> None:
    cell_size = 240
    gap = 12
    margin = 12
    columns = 2
    rows = 2
    width = margin * 2 + cell_size * columns + gap * (columns - 1)
    height = margin * 2 + cell_size * rows + gap * (rows - 1)
    contact = Image.new("RGB", (width, height), BACKGROUND)

    for index, frame in enumerate(keyframes):
        row, column = divmod(index, columns)
        thumbnail = frame.resize(
            (cell_size, cell_size), Image.Resampling.LANCZOS
        )
        x = margin + column * (cell_size + gap)
        y = margin + row * (cell_size + gap)
        contact.paste(thumbnail, (x, y))

    contact.save(CONTACT_PATH, optimize=True)


def main() -> None:
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    sheet = Image.open(SOURCE).convert("RGB")
    raw_keyframes = crop_keyframes(sheet)

    reference = raw_keyframes[0]
    allowed_region = motion_region()
    stable_keyframes = [reference]
    stable_keyframes.extend(
        lock_stationary_pixels(reference, frame, allowed_region)
        for frame in raw_keyframes[1:]
    )

    save_keyframes(stable_keyframes)
    save_contact_sheet(stable_keyframes)

    animation = quantize_animation(build_animation(stable_keyframes))
    animation[0].save(
        GIF_PATH,
        save_all=True,
        append_images=animation[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        disposal=2,
        optimize=True,
    )

    print(f"keyframes: {len(stable_keyframes)}")
    print(f"animation frames: {len(animation)}")
    print(f"frame duration: {FRAME_DURATION_MS} ms")
    print(f"gif: {GIF_PATH}")
    print(f"contact: {CONTACT_PATH}")


if __name__ == "__main__":
    main()
