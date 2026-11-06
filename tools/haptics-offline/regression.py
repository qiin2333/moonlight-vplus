"""Compare identical PCM through two SDK builds and the production client probe.

The budgets below are engineering regression limits, not perceptual thresholds.
No actuator dynamics or game ground truth is inferred from command-domain metrics.
"""
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
from corpus import matching, rows


def compare(before, after, host_only=False):
    manifests = [json.loads((p / "manifest.json").read_text()) for p in (before, after)]
    originals = {c["name"]: c for c in manifests[0]["cases"]}
    assert set(originals) == {c["name"] for c in manifests[1]["cases"]}
    groups = defaultdict(lambda: defaultdict(float))
    for case in manifests[1]["cases"]:
        name, family = case["name"], case["family"]
        assert originals[name]["pcm_sha256"] == case["pcm_sha256"], name
        assert originals[name]["expected_onsets_s"] == case["expected_onsets_s"], name
        for root in (before, after):
            assert hashlib.sha256((root / name / "input.pcm").read_bytes()).hexdigest() == case["pcm_sha256"]
        group = groups[family]
        group["cases"] += 1
        for prefix, root in (("before", before), ("after", after)):
            events = rows(root / name / "events_240.csv")
            matched, missed, extra = matching(case, events)
            group[prefix + "_events"] += len(events)
            group[prefix + "_matched"] += matched
            group[prefix + "_missed"] += missed
            group[prefix + "_extra"] += extra
        # Protect every positive scene, not just the aggregate recall.
        if case["expected_discrete"]:
            old = matching(case, rows(before / name / "events_240.csv"))
            new = matching(case, rows(after / name / "events_240.csv"))
            assert new[0] >= old[0] and new[2] <= old[2], f"positive regression: {name}"
        if host_only:
            continue
        old_client, new_client = [rows(root / name / "client.csv") for root in (before, after)]
        assert len(old_client) == len(new_client)
        assert [r["time_us"] for r in old_client] == [r["time_us"] for r in new_client]
        previous = np.array([float(r["new_low"]) for r in old_client])
        current = np.array([float(r["new_low"]) for r in new_client])
        low = np.array([float(r["old_low"]) for r in new_client])
        high = np.array([float(r["old_high"]) for r in new_client])
        old_split_same_frames = np.sqrt(np.maximum(0, low * low - high * high))
        for key, values in (("previous", previous), ("current", current), ("old_split_same_frames", old_split_same_frames)):
            group[key + "_low_tv"] += float(np.abs(np.diff(values)).sum())
            group[key + "_low_squared_sum"] += float(np.square(values).sum())
        for prefix, client in (("before", old_client), ("after", new_client)):
            group[prefix + "_native_points"] += sum(int(r["new_points"]) for r in client)
        # The native packet builder must not silently discard surviving points.
        assert sum(int(r["new_points"]) for r in new_client) == len(rows(after / name / "events_240.csv")), name
    for family in ("run_fine", "run_low", "draw_clean", "beating", "bass", "silence"):
        assert groups[family]["after_extra"] == 0, f"continuous regression: {family}"
    # Residual noise errors are explicit; do not label these scenes 'solved'.
    for family, fraction in (("run_noise", .3), ("draw_noise", .7)):
        assert groups[family]["after_extra"] <= groups[family]["before_extra"] * fraction, family
    if not host_only:
        for family in ("run_fine", "run_low", "run_noise", "draw_clean", "draw_noise", "beating"):
            group = groups[family]
            assert group["current_low_tv"] <= group["previous_low_tv"] * 1.001 + 1e-6, family
            assert group["current_low_tv"] <= group["old_split_same_frames_low_tv"] * 1.001 + 1e-6, family
    result = [{"family": family, **values} for family, values in groups.items()]
    (after / "regression.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    with (after / "regression.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(result[0]))
        writer.writeheader()
        writer.writerows(result)
    print(json.dumps(result, indent=2))


def plot(before, after):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    families = ("run_fine", "run_noise", "draw_noise", "mixed")
    fig, axes = plt.subplots(4, 3, figsize=(13, 10), constrained_layout=True)
    for index, family in enumerate(families):
        name = family + "_s7_p0.06"
        for label, root, color, level in (("previous", before, "#b65348", 1),
                                           ("current", after, "#177e89", 0)):
            data = rows(root / name / "client.csv")
            time = np.array([int(r["time_us"]) / 1e6 for r in data])
            events = rows(root / name / "events_240.csv")
            axes[index, 0].scatter([int(e["source_us"]) / 1e6 for e in events],
                                   [level] * len(events), marker="|", s=35, color=color,
                                   label=f"{label}: {len(events)}")
            axes[index, 1].plot(time, [float(r["new_low"]) for r in data], color=color, lw=1, label=label)
            axes[index, 2].plot(time, [float(r["new_body"]) for r in data], color=color, lw=1, label=label)
        axes[index, 0].set_ylabel(family)
        axes[index, 0].set_yticks([0, 1], ["current", "previous"])
        axes[index, 0].set_ylim(-.6, 1.6)
        axes[index, 0].legend(fontsize=7, loc="upper right")
        for ax in axes[index]:
            ax.set_xlim(0, 3.2)
            ax.grid(alpha=.2)
    for col, title in enumerate(("Unique SDK points", "Controller low command", "Native continuous gain")):
        axes[0, col].set_title(title)
        axes[-1, col].set_xlabel("Source time (s)")
    axes[0, 1].legend(fontsize=8)
    fig.suptitle("Same synthetic PCM: previous candidate vs current SDK + allocator\nCommand-domain regression; no physical actuator response model")
    fig.savefig(after / "regression.png", dpi=150)
    fig.savefig(after / "regression.pdf")
    plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--host-only", action="store_true")
    parser.add_argument("--plot", action="store_true")
    args = parser.parse_args()
    compare(args.before, args.after, args.host_only)
    if args.plot:
        assert not args.host_only
        plot(args.before, args.after)
