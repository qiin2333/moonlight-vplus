"""Compare identical PCM through two SDK builds.

The budgets below are engineering regression limits, not perceptual thresholds.
No actuator dynamics or game ground truth is inferred from command-domain metrics.
"""
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path

from corpus import matching, rows


def compare(before, after):
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
    for family in ("run_fine", "run_low", "draw_clean", "beating", "bass", "silence"):
        assert groups[family]["after_extra"] == 0, f"continuous regression: {family}"
    # Residual noise errors are explicit; do not label these scenes 'solved'.
    for family, fraction in (("run_noise", .3), ("draw_noise", .7)):
        assert groups[family]["after_extra"] <= groups[family]["before_extra"] * fraction, family
    result = [{"family": family, **values} for family, values in groups.items()]
    (after / "regression.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    with (after / "regression.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=list(result[0]))
        writer.writeheader()
        writer.writerows(result)
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    args = parser.parse_args()
    compare(args.before, args.after)
