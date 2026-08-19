"""Build the v7 simultaneous dumbbell bench-press preview.

Every output frame is a complete image.  Twelve independently rendered
complete keyframes share the same cat, body, bench, camera, and canvas.  They
are ordered by measured dumbbell height and played forward then backward.  No
body/limb layers, local compositing, blending, or motion-estimation warping are
produced.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "assets-source" / "exercise-guides" / "bench-press-v7"
PREVIEW_DIR = ROOT / "assets-source" / "exercise-guides" / "previews"
KEYFRAME_DIR = PREVIEW_DIR / "dumbbell-bench-press-v7-keyframes"
GIF_PATH = PREVIEW_DIR / "dumbbell-bench-press-v7-preview.gif"
CONTACT_PATH = PREVIEW_DIR / "dumbbell-bench-press-v7-contact.png"

# Image generation did not preserve the requested percentage labels exactly.
# This order follows the measured average top edge of both dumbbells, from the
# chest-side bottom position to the highest above-chest position.
SOURCE_NAMES = (
    "keyframe-000-bottom.png",
    "keyframe-transition-low.png",
    "keyframe-012-eighth.png",
    "keyframe-transition-low-synced.png",
    "keyframe-050-middle.png",
    "keyframe-transition-extra.png",
    "keyframe-037-three-eighth.png",
    "keyframe-transition-mid-low.png",
    "keyframe-062-five-eighth.png",
    "keyframe-transition-mid-high.png",
    "keyframe-075-three-quarter.png",
    "keyframe-087-seven-eighth.png",
)

SOURCE_SIZE = 1254
OUTPUT_SIZE = 320
FRAME_DURATION_MS = 70
BACKGROUND = (254, 253, 249)


def load_source_keyframes() -> list[Image.Image]:
    frames: list[Image.Image] = []
    for name in SOURCE_NAMES:
        path = SOURCE_DIR / name
        frame = Image.open(path).convert("RGB")
        if frame.size != (SOURCE_SIZE, SOURCE_SIZE):
            raise ValueError(f"Unexpected keyframe size for {path}: {frame.size}")
        frames.append(frame)
    return frames


def save_keyframes(frames: list[Image.Image]) -> None:
    KEYFRAME_DIR.mkdir(parents=True, exist_ok=True)
    for stale in KEYFRAME_DIR.glob("frame-*.png"):
        stale.unlink()
    for index, frame in enumerate(frames):
        frame.resize(
            (OUTPUT_SIZE, OUTPUT_SIZE),
            Image.Resampling.LANCZOS,
        ).save(KEYFRAME_DIR / f"frame-{index:02d}.png", optimize=True)


def save_contact_sheet(frames: list[Image.Image]) -> None:
    cell = 160
    gap = 8
    margin = 8
    width = margin * 2 + cell * len(frames) + gap * (len(frames) - 1)
    height = margin * 2 + cell
    contact = Image.new("RGB", (width, height), BACKGROUND)
    for index, frame in enumerate(frames):
        thumbnail = frame.resize((cell, cell), Image.Resampling.LANCZOS)
        contact.paste(thumbnail, (margin + index * (cell + gap), margin))
    contact.save(CONTACT_PATH, optimize=True)


def build_animation(keyframes: list[Image.Image]) -> list[Image.Image]:
    resized = [
        frame.resize((OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS)
        for frame in keyframes
    ]
    return resized + resized[-2:0:-1]


def quantize_animation(frames: list[Image.Image]) -> list[Image.Image]:
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


def save_gif(frames: list[Image.Image]) -> None:
    quantized = quantize_animation(frames)
    quantized[0].save(
        GIF_PATH,
        save_all=True,
        append_images=quantized[1:],
        duration=FRAME_DURATION_MS,
        loop=0,
        disposal=2,
        optimize=True,
    )


def main() -> None:
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    keyframes = load_source_keyframes()
    save_keyframes(keyframes)
    save_contact_sheet(keyframes)

    animation = build_animation(keyframes)
    save_gif(animation)

    print(f"complete keyframes: {len(keyframes)}")
    print(f"animation frames: {len(animation)}")
    print(f"frame duration: {FRAME_DURATION_MS} ms")
    print(f"gif: {GIF_PATH}")
    print(f"contact: {CONTACT_PATH}")


if __name__ == "__main__":
    main()
