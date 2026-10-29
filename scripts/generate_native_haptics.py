"""Generate the optional SoundPool energy bank. Requires numpy, soundfile and mutagen.

48 kHz Vorbis: silent L/R audio plus ANDROID_HAPTIC=1. The 160 Hz carrier is an
experimental renderer choice, not a frequency estimate or model-specific calibration.
Body has 16 levels, points 8. Each point is a balanced 3.125 ms cycle at 320 Hz,
mixed with the 160 Hz background in one stream. Its energy matches the former
10 ms pulse, with 1.25x makeup for the weaker handheld response at 320 Hz.
https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import threading

import numpy as np
import soundfile as sf
from mutagen.oggvorbis import OggVorbis


def generate(destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    reference_time = np.arange(480) / 48000
    reference = np.sin(np.pi * reference_time / .010) ** 2 * np.sin(2 * np.pi * 160 * reference_time)
    point_time = np.arange(150) / 48000
    point_shape = np.sin(np.pi * point_time / .003125) ** 2 * np.sin(2 * np.pi * 320 * point_time)
    point_shape *= 1.25 * np.sqrt(np.sum(reference ** 2) / np.sum(point_shape ** 2))
    assert abs(point_shape.sum()) < 1e-10  # Balanced positive and negative drive.
    threading.stack_size(16 * 1024 * 1024)
    with ThreadPoolExecutor(max_workers=1) as encoder:
        for body in range(17):
            for point in range(9):
                duration = 1.0 if point == 0 else 0.4
                name = f"body_{body}" if point == 0 else f"mixed_{body}_{point}"
                t = np.arange(round(duration * 48000)) / 48000
                signal = 0.6 * body / 16 * np.sin(2 * np.pi * 160 * t)
                if point:
                    signal[:len(point_shape)] += 0.6 * point / 8 * point_shape
                pcm = np.zeros((len(t), 3), dtype=np.float32)
                pcm[:, 2] = np.clip(signal, -.95, .95)
                path = destination / f"{name}.ogg"
                encoder.submit(sf.write, path, pcm, 48000, format="OGG", subtype="VORBIS").result()
                tags = OggVorbis(path)
                tags["ANDROID_HAPTIC"] = ["1"]
                tags.save()
                decoded, rate = sf.read(path, always_2d=True)
                assert rate == 48000 and decoded.shape == pcm.shape
                assert np.max(np.abs(decoded[:, :2])) < 1e-6
    files = list(destination.glob("*.ogg"))
    print(f"Generated {len(files)} haptic assets, {sum(p.stat().st_size for p in files)} bytes")


if __name__ == "__main__":
    generate(Path(__file__).resolve().parents[1] / "app/src/main/assets/haptics/native")
