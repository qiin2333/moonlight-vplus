"""Exploratory positive controls across pulse carrier/background/contrast.

Reports regressions without tuning the detector or redefining intended onsets.
Synthetic actuator signals; no game recordings or perceptual ground truth.
"""
import argparse
import concurrent.futures
import csv
import hashlib
import itertools
import json
import subprocess
from collections import defaultdict
from pathlib import Path

import numpy as np
from corpus import RATE, band_noise, matching, rows


def run(args):
    args.out.mkdir(parents=True, exist_ok=True)
    time = np.arange(int(1.8 * RATE)) / RATE
    support = np.sin(np.clip(np.minimum((time - .1) / .08, (1.7 - time) / .08), 0, 1) * np.pi / 2) ** 2
    onsets = [.4, .65, .9, 1.15, 1.4]
    cases = []
    # Include the former 270 Hz positive control, plus lower/higher carriers.
    for carrier, background, background_amp, pulse_amp, phase in itertools.product(
            (100, 160, 270, 330), ("bass", "high", "noise"), (.01, .03), (.015, .03, .06), (0, 1.5707963267948966)):
        if background == "noise" and phase != 0:
            continue  # Phase applies to tonal backgrounds; don't duplicate noise PCM.
        name = f"f{carrier}_{background}_b{background_amp:.3f}_p{pulse_amp:.3f}_phase{phase:.2f}"
        path = args.out / name
        path.mkdir(exist_ok=True)
        if background == "noise":
            base = band_noise(len(time), np.random.default_rng(13), 80, 380)
        else:
            base = np.sin(2 * np.pi * (85 if background == "bass" else 260) * time + phase)
        signal = support * background_amp * base
        for at in onsets:
            local = time - at
            active = (local >= 0) & (local < .05)
            t = local[active]
            signal[active] += pulse_amp * (1 - np.exp(-t / .0007)) * np.exp(-t / .006) * np.sin(2 * np.pi * carrier * t)
        assert np.max(np.abs(signal)) < 1
        pcm = np.repeat(np.rint(signal * 32767).astype("<i2")[:, None], 2, axis=1).tobytes()
        (path / "input.pcm").write_bytes(pcm)
        meta = dict(name=name, carrier=carrier, background=background, background_amp=background_amp,
                    pulse_amp=pulse_amp, phase=phase, expected_onsets_s=onsets, pcm_sha256=hashlib.sha256(pcm).hexdigest())
        cases.append(meta)

    def analyze(meta):
        path = args.out / meta["name"]
        result = {k: v for k, v in meta.items() if k not in ("expected_onsets_s", "pcm_sha256")}
        for prefix, executable in (("before", args.before_exe), ("after", args.after_exe)):
            for chunk in (240, 137):
                suffix = "" if chunk == 240 else "_137"
                subprocess.run([str(executable.resolve()), str(path / "input.pcm"), str(path / (prefix + "_frames" + suffix + ".csv")),
                                str(path / (prefix + "_events" + suffix + ".csv")), str(chunk)], check=True, capture_output=True)
            events = rows(path / (prefix + "_events.csv"))
            other = rows(path / (prefix + "_events_137.csv"))
            assert len(events) == len(other), meta["name"]
            for a, b in zip(sorted(events, key=lambda r: int(r["id"])), sorted(other, key=lambda r: int(r["id"]))):
                assert all(a[k] == b[k] for k in ("id", "source_us", "left", "right", "sharpness")), meta["name"]
            frames = rows(path / (prefix + "_frames.csv"))
            other_frames = {r["time_us"]: r for r in rows(path / (prefix + "_frames_137.csv"))}
            common = [r for r in frames if r["time_us"] in other_frames]
            assert len(common) >= 2
            for r in common:
                assert all(abs(float(r[k]) - float(other_frames[r["time_us"]][k])) < 1e-6
                           for k in ("low_l", "high_l", "low_r", "high_r")), meta["name"]
            matched, missed, extra = matching(meta, events)
            result.update({prefix + "_matched": matched, prefix + "_missed": missed, prefix + "_extra": extra})
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(analyze, cases))
    with (args.out / "stress.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(results[0]))
        writer.writeheader()
        writer.writerows(results)
    groups = defaultdict(lambda: defaultdict(int))
    for result in results:
        group = groups[f"{result['background']}_{result['carrier']}"]
        group["expected"] += len(onsets)
        group["cases"] += 1
        for key in ("before_matched", "before_extra", "after_matched", "after_extra"):
            group[key] += result[key]
        group["regressed_cases"] += result["after_matched"] < result["before_matched"]
    (args.out / "summary.json").write_text(json.dumps(groups, indent=2), encoding="utf-8")
    manifest = {"synthetic": True, "sample_rate": RATE, "channels": 2, "format": "S16LE",
                "note": "Known additive impulses; temporal matches in noisy backgrounds can be accidental.",
                "before_analyzer_sha256": hashlib.sha256(args.before_exe.read_bytes()).hexdigest(),
                "after_analyzer_sha256": hashlib.sha256(args.after_exe.read_bytes()).hexdigest(), "cases": cases}
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(groups, indent=2))
    regressions = [r["name"] for r in results if r["background"] != "noise" and
                   (r["after_matched"] < r["before_matched"] or r["after_extra"] > r["before_extra"])]
    (args.out / "recall_regressions.json").write_text(json.dumps(regressions, indent=2), encoding="utf-8")
    if args.check and regressions:
        raise SystemExit(f"Recall/false-positive regression in {len(regressions)} tonal-background positive cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-exe", type=Path, required=True)
    parser.add_argument("--after-exe", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="Fail on tonal-background recall or false-positive regression")
    run(parser.parse_args())
