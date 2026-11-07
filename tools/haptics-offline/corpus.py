"""Deterministic actuator PCM hypotheses, not game audio or DS5 recordings.

Generation requires NumPy.
"""
import argparse
import concurrent.futures
import csv
import hashlib
import json
import math
import subprocess
import wave
from pathlib import Path

import numpy as np

RATE = 48000
SECONDS = 3.2
SEEDS = (7, 19, 41)
PEAKS = (.03, .06, .12)
FAMILIES = ("rain", "climb", "run_fine", "run_low", "run_noise",
            "draw_clean", "draw_noise", "beating", "bass", "mixed")


def band_noise(n, rng, lo=50, hi=400):
    """Periodic band-limited Gaussian noise; outer smooth envelope removes seam."""
    spectrum = np.fft.rfft(rng.standard_normal(n))
    hz = np.fft.rfftfreq(n, 1 / RATE)
    # Raised-cosine edges, not a hard spectral brick wall.
    weight = np.minimum(np.clip((hz - lo) / 30, 0, 1), np.clip((hi - hz) / 30, 0, 1))
    spectrum *= .5 - .5 * np.cos(np.pi * weight)
    signal = np.fft.irfft(spectrum, n)
    return signal / np.sqrt(np.mean(signal * signal))


def make(family, seed, peak):
    rng = np.random.default_rng(seed)
    t = np.arange(round(SECONDS * RATE)) / RATE
    pcm = np.zeros((len(t), 2))
    labels = []
    support = np.clip(np.minimum((t - .3) / .08, (2.9 - t) / .08), 0, 1)
    support = np.sin(support * np.pi / 2) ** 2
    phases = rng.uniform(0, 2 * np.pi, 2)
    if family in ("run_fine", "run_low", "run_noise", "bass", "beating", "mixed"):
        for lane in range(2):
            gait = .65 + .20 * np.sin(2 * np.pi * 2.8 * t + lane * np.pi)
            if family == "run_fine":
                carrier = np.sin(2 * np.pi * 260 * t + phases[lane]) + .18 * np.sin(2 * np.pi * 95 * t)
            elif family == "run_low":
                carrier = np.sin(2 * np.pi * 95 * t + phases[lane]) + .18 * np.sin(2 * np.pi * 260 * t)
            elif family == "run_noise":
                carrier = band_noise(len(t), rng, 80, 380)
            elif family == "beating":
                carrier = np.sin(2 * np.pi * 160 * t + phases[lane]) + np.sin(2 * np.pi * 200 * t + phases[lane])
                gait = 1
            elif family == "bass":
                carrier = np.sin(2 * np.pi * 85 * t + phases[lane])
                gait = 1
            else:
                carrier = .22 * np.sin(2 * np.pi * 85 * t + phases[lane])
                gait = 1
            pcm[:, lane] = support * gait * carrier
    if family in ("draw_clean", "draw_noise"):
        for at, duration in ((.4, .12), (1.15, .30), (2.0, .60)):
            indices = (t >= at) & (t < at + duration)
            local = t[indices] - at
            u = local / duration
            envelope = np.sin(np.pi * u) ** 2
            for lane in range(2):
                # Integrate instantaneous frequency to keep phase continuous.
                phase = 2 * np.pi * (90 * local + (320 - 90) * local * local / (2 * duration))
                carrier = np.sin(phase + phases[lane])
                if family == "draw_noise":
                    carrier = .6 * carrier + .4 * band_noise(len(local), rng)
                pcm[indices, lane] = envelope * carrier
    if family in ("rain", "climb", "mixed"):
        if family == "rain":
            times = .4 + np.cumsum(rng.uniform(.045, .11, 25))
        else:
            times = np.arange(8) * .3 + .4
        for i, at in enumerate(times):
            labels.append(float(at))
            local = t - at
            active = (local >= 0) & (local < .05)
            x = local[active]
            # Existing SDK positive-control morphology: fast rise, damped carrier.
            pulse = (1 - np.exp(-x / .0007)) * np.exp(-x / .006) * np.sin(2 * np.pi * 270 * x)
            amplitude = float(rng.uniform(.4, 1))
            for lane in range(2):
                pcm[active, lane] += pulse * amplitude * (1 if lane == i % 2 else .6)
    if family != "silence":
        pcm *= peak / np.max(np.abs(pcm))
    quantized = np.rint(pcm * 32767).astype("<i2")
    assert np.max(np.abs(quantized.astype(np.int32))) < 32767
    return quantized, {"family": family, "seed": seed, "peak": peak,
                       "expected_onsets_s": labels, "expected_discrete": bool(labels),
                       "intent": "compact discrete attacks" if labels else "continuous texture; no authored clicks",
                       "sample_rate": RATE, "channels": 2, "format": "S16LE", "duration_s": SECONDS}


def rows(path):
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def generate(args):
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest = {"synthetic": True, "source": "engineering hypotheses, not recorded game/DS5 signals",
                "sdk_commit": subprocess.check_output(["git", "-C", str(args.sdk), "rev-parse", "HEAD"], text=True).strip(),
                "sdk_dirty": subprocess.check_output(["git", "-C", str(args.sdk), "status", "--porcelain"], text=True),
                "sdk_engine_sha256": hashlib.sha256((args.sdk / "src/core/authored_event_engine.cpp").read_bytes()).hexdigest(),
                "sdk_analysis_sha256": {str(p.relative_to(args.sdk)): hashlib.sha256(p.read_bytes()).hexdigest()
                                        for p in (args.sdk / "src/core").glob("authored*.*")},
                "analyzer_sha256": hashlib.sha256(args.analyzer.read_bytes()).hexdigest(),
                "build_provenance_note": "SDK metadata describes supplied source directory; rebuild analyzer after source edits.",
                "numpy_version": np.__version__, "generator_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "matching_window_ms": [-5, 30], "cases": []}
    cases = [(f, s, p) for f in FAMILIES for s in args.seeds for p in PEAKS] + [("silence", 7, 0)]
    for family, seed, peak in cases:
        name = f"{family}_s{seed}_p{peak:.2f}"
        directory = out / name
        directory.mkdir(exist_ok=True)
        pcm, meta = make(family, seed, peak)
        binary = pcm.tobytes()
        (directory / "input.pcm").write_bytes(binary)
        with wave.open(str(directory / "input.wav"), "wb") as wav:
            wav.setparams((2, 2, RATE, 0, "NONE", "not compressed"))
            wav.writeframes(binary)
        meta.update(name=name, pcm_sha256=hashlib.sha256(binary).hexdigest())
        (directory / "labels.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
        manifest["cases"].append(meta)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    def analyze(meta):
        directory = out / meta["name"]
        for chunk in (240, 137):
            subprocess.run([str(args.analyzer.resolve()), str(directory / "input.pcm"),
                            str(directory / f"frames_{chunk}.csv"), str(directory / f"events_{chunk}.csv"), str(chunk)], check=True)
        # Multiple candidates can mature in a different publication order within a chunk.
        a = sorted(rows(directory / "events_240.csv"), key=lambda r: int(r["id"]))
        b = sorted(rows(directory / "events_137.csv"), key=lambda r: int(r["id"]))
        assert len(a) == len(b), f"chunk-dependent event count: {meta['name']}"
        for left, right in zip(a, b):
            for key in ("id", "source_us", "left", "right", "sharpness"):
                assert left[key] == right[key], f"chunk-dependent {key}: {meta['name']}"
        fa, fb = rows(directory / "frames_240.csv"), rows(directory / "frames_137.csv")
        by_time = {r["time_us"]: r for r in fb}
        matches = 0
        for r in fa:
            if r["time_us"] in by_time:
                matches += 1
                for key in ("low_l", "high_l", "low_r", "high_r"):
                    assert abs(float(r[key]) - float(by_time[r["time_us"]][key])) < 1e-6
        assert matches >= 4
        return meta["name"], len(a)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for name, count in pool.map(analyze, manifest["cases"]):
            print(f"{name}: {count} unique events", flush=True)


def matching(meta, events):
    remaining = list(meta["expected_onsets_s"])
    matched = 0
    for event in events:
        time = int(event["source_us"]) / 1e6
        candidates = [(abs(time - at), i) for i, at in enumerate(remaining) if -.005 <= time - at <= .030]
        if candidates:
            remaining.pop(min(candidates)[1])
            matched += 1
    return matched, len(remaining), len(events) - matched

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--analyzer", type=Path, required=True)
    parser.add_argument("--seeds", type=int, nargs="+", default=SEEDS)
    generate(parser.parse_args())
