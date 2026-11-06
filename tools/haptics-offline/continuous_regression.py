"""Check continuous feature retention and real backend command traces; no hardware model.

Compare to a frozen SDK corpus so a client improvement cannot conceal detector changes.
Run AuthoredOfflineCorpusTest on --after first. Budgets describe software contracts,
not perceived DS5 equivalence, physical acceleration or SoundPool mixing behavior.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path

from corpus import rows, matching


def check(before, after):
    manifest = json.loads((after / "manifest.json").read_text())
    assets = Path(__file__).resolve().parents[2] / "app/src/main/assets/haptics/native"
    groups = defaultdict(lambda: defaultdict(int))
    for meta in manifest["cases"]:
        name = meta["name"]
        original, current = before / name, after / name
        assert hashlib.sha256((original / "input.pcm").read_bytes()).hexdigest() == meta["pcm_sha256"], name
        events = rows(current / "events_240.csv")
        assert rows(original / "events_240.csv") == events, f"SDK events changed: {name}"
        frames, trace = rows(current / "frames_240.csv"), rows(current / "native_render.csv")
        client = rows(current / "client.csv")
        assert len(frames) == len(trace) == len(client), name
        for frame, rendered, command in zip(frames, trace, client):
            assert frame["time_us"] == rendered["time_us"] == command["time_us"]
            low = max(float(frame["low_l"]), float(frame["low_r"]))
            high = max(float(frame["high_l"]), float(frame["high_r"]))
            assert abs(float(rendered["low"]) - low) < 1e-6, name
            assert abs(float(rendered["high"]) - high) < 1e-6, name
            assert abs(float(rendered["gain"]) - min(1, math.sqrt(2 * (low * low + high * high)))) < 1e-6
            assert float(command["new_low"]) == float(command["new_high"]) == 0
            if rendered["sp_asset"]:
                assert (assets / (rendered["sp_asset"] + ".ogg")).is_file(), rendered["sp_asset"]
        rt_points = sum(int(r["rt_points"]) for r in trace)
        sp_points = sum(int(r["sp_points"]) for r in trace)
        rt_starts = sum(int(r["rt_body_starts"]) for r in trace)
        sp_starts = sum(int(r["sp_body_starts"]) for r in trace)
        assert rt_points == len(events), name
        assert sp_points == sum(int(r["new_sp_points"]) for r in client), name
        matched, missed, extra = matching(meta, events)
        group = groups[meta["family"]]
        for key, value in dict(cases=1, events=len(events), matched=matched, missed=missed,
                               extra=extra, richtap_points=rt_points, soundpool_points=sp_points,
                               richtap_body_starts=rt_starts, soundpool_body_starts=sp_starts).items():
            group[key] += value
        if meta["family"] == "draw_clean":
            frequencies = {int(r["rt_frequency"]) for r in trace if float(r["gain"]) > .015}
            assert max(frequencies) - min(frequencies) >= 10, name
            assert rt_points == sp_points == 0, name
            group["minimum_he_frequency_span"] = min(group.get("minimum_he_frequency_span", 100), max(frequencies) - min(frequencies))
    for family in ("rain", "climb"):
        assert groups[family]["richtap_points"] == groups[family]["soundpool_points"] == groups[family]["events"]
    for family in ("run_fine", "run_low", "draw_clean", "beating", "bass", "silence"):
        assert groups[family]["extra"] == 0, family
    result = {"scope": "software commands only; no actuator response or E2E", "families": dict(groups)}
    (after / "continuous_regression.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    args = parser.parse_args()
    check(args.before, args.after)
