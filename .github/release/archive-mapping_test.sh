#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
script="$script_dir/archive-mapping.sh"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/mapping-archive-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

git init --quiet --bare "$tmp/remote.git"
git clone --quiet "$tmp/remote.git" "$tmp/a"
git clone --quiet "$tmp/remote.git" "$tmp/b"
printf 'obfuscated mapping\n' > "$tmp/mapping.txt"
printf 'release apk\n' > "$tmp/app.apk"

(cd "$tmp/a" && "$script" "$tmp/mapping.txt" "$tmp/app.apk" v1.2.3 10 1)
# An identical retry is harmless; a changed mapping cannot overwrite the same build.
(cd "$tmp/a" && "$script" "$tmp/mapping.txt" "$tmp/app.apk" v1.2.3 10 1)
printf 'different mapping\n' > "$tmp/other.txt"
if (cd "$tmp/a" && "$script" "$tmp/other.txt" "$tmp/app.apk" v1.2.3 10 1); then
  echo 'ERROR: changed mapping overwrote an existing build' >&2
  exit 1
fi
# Both writers may start from the same parent; neither archive may be lost.
(cd "$tmp/a" && "$script" "$tmp/mapping.txt" "$tmp/app.apk" v1.2.4 11 1) > "$tmp/a.log" 2>&1 &
pid_a=$!
(cd "$tmp/b" && "$script" "$tmp/mapping.txt" "$tmp/app.apk" v1.2.5 12 1) > "$tmp/b.log" 2>&1 &
pid_b=$!
wait "$pid_a" || { cat "$tmp/a.log" >&2; exit 1; }
wait "$pid_b" || { cat "$tmp/b.log" >&2; exit 1; }
for path in v1.2.3/10-1 v1.2.4/11-1 v1.2.5/12-1; do
  git --git-dir="$tmp/remote.git" show "mapping-archive:$path/mapping.txt.gz" | gzip -dc > "$tmp/extracted.txt"
  cmp "$tmp/mapping.txt" "$tmp/extracted.txt"
  git --git-dir="$tmp/remote.git" show "mapping-archive:$path/metadata.json" > "$tmp/metadata.json"
  python3 - "$tmp/metadata.json" "$tmp/mapping.txt" "$tmp/app.apk" <<'PY'
import hashlib
import json
import sys
with open(sys.argv[1]) as f:
    metadata = json.load(f)
for label, path in [('mapping', sys.argv[2]), ('apk', sys.argv[3])]:
    with open(path, 'rb') as f:
        assert metadata[label + '_sha256'] == hashlib.sha256(f.read()).hexdigest()
PY
done
if (cd "$tmp/a" && "$script" "$tmp/mapping.txt" "$tmp/app.apk" ../escape 13 1); then
  echo 'ERROR: accepted invalid tag' >&2
  exit 1
fi
[ -z "$(git -C "$tmp/a" status --porcelain)" ]
echo 'archive-mapping tests passed'
