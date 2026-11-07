"""Decode the shipped Ogg bank and verify short attacks independently of renderer commands."""
import argparse
import io
import json
from pathlib import Path
import subprocess

import numpy as np
import soundfile as sf
from mutagen.oggvorbis import OggVorbis


def verify(reference):
    root = Path(__file__).resolve().parents[2]
    bank = root / "app/src/main/assets/haptics/native"
    worst_tail, comparisons, frames = 0.0, [], 0
    checked = set()
    for hz in (100, 160, 260):
        for body in range(17):
            if body == 0 and hz != 160:
                continue
            for point in range(9):
                suffix = "" if hz == 160 else f"{hz}_"
                name = f"body_{suffix}{body}" if point == 0 else f"mixed_{suffix}{body}_{point}"
                path = bank / (name + ".ogg")
                checked.add(path)
                pcm, rate = sf.read(path, always_2d=True)
                assert rate == 48000 and pcm.shape == (48000 if point == 0 else 1920, 3), name
                assert OggVorbis(path)["ANDROID_HAPTIC"] == ["1"], name
                assert np.max(np.abs(pcm[:, :2])) < 1e-6, name
                frames += len(pcm)
                if point == 0 and body > 0:
                    peak_hz = np.argmax(np.abs(np.fft.rfft(pcm[:, 2])))
                    assert abs(peak_hz - hz) <= 1, name
                if point > 0 and body == 0:
                    energy = pcm[:, 2] ** 2
                    tail = energy[576:].sum() / energy.sum()
                    worst_tail = max(worst_tail, float(tail))
                    assert tail < .01, name
                    if hz == 160:
                        relative = path.relative_to(root).as_posix()
                        previous = subprocess.check_output(["git", "-C", str(root), "show", reference + ":" + relative])
                        old, _ = sf.read(io.BytesIO(previous), always_2d=True)
                        a, b = old[:576, 2], pcm[:576, 2]
                        correlation = float(np.corrcoef(a, b)[0, 1])
                        ratio = float(np.linalg.norm(b) / np.linalg.norm(a))
                        assert correlation > .999 and .99 < ratio < 1.01, name
                        comparisons.append(dict(point=point, correlation=correlation, rms_ratio=ratio))
    assert checked == set(bank.glob("*.ogg")), "Unexpected native haptics assets"
    return dict(assets=len(checked), worst_point_energy_after_12_ms=worst_tail,
                decoded_pcm16_bytes=frames * 3 * 2,
                compressed_bytes=sum(p.stat().st_size for p in bank.glob("*.ogg")),
                reference=reference, original_point_comparisons=comparisons)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", default="682a912c6", help="Git revision of the original 160 Hz bank")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    result = json.dumps(verify(args.reference), indent=2)
    if args.out:
        args.out.write_text(result, encoding="utf-8")
    print(result)
