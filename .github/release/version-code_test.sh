#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SCRIPT="$SCRIPT_DIR/version-code.sh"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-versions-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

git init -q --bare "$tmp/origin.git"
git clone -q "$tmp/origin.git" "$tmp/a"
cd "$tmp/a"
git config user.name Test
git config user.email test@example.com
git checkout -qb master
mkdir -p app
printf 'versionName "12.15.31"\nversionCode = 121531001\n' > app/build.gradle
git add app/build.gradle
git commit -qm init
git push -q origin master
git clone -q "$tmp/origin.git" "$tmp/b"

test_code() {
  local tag=$1 expected=$2 expected_floor=$3 output code floor source
  output=$(bash "$SCRIPT" "$tag")
  IFS=$'\t' read -r code floor source <<< "$output"
  [ "$code" = "$expected" ] || fail "$tag: expected code $expected, got $code"
  [ "$floor" = "$expected_floor" ] || fail "$tag: expected floor $expected_floor, got $floor"
}

git tag v12.15.32-beta.1
test_code v12.15.32-beta.1 121531002 121531001
# A failed run's retry is immutable and an unrelated unissued future tag cannot
# prevent a lower-version hotfix from reserving the next code.
test_code v12.15.32-beta.1 121531002 121531002
# A manually changed master must not assign an existing code to a different
# version; detecting this after publishing would be too late.
printf 'versionName "12.15.31"\nversionCode = 121531002\n' > app/build.gradle
git add app/build.gradle
git commit -qm 'simulate conflicting master version'
git push -q origin master
if bash "$SCRIPT" v12.15.32-beta.1 >/dev/null 2>&1; then
  fail 'same master code with a different versionName accepted'
fi
printf 'versionName "12.15.31"\nversionCode = 121531001\n' > app/build.gradle
git add app/build.gradle
git commit -qm 'restore master floor'
git push -q origin master
git tag v99.0.0-beta.1
git tag v12.15.31-rc.1
test_code v12.15.31-rc.1 121531003 121531002
# Same-commit beta and stable tags receive increasing, durable allocations.
git tag v12.15.32-rc.1
test_code v12.15.32-rc.1 121531004 121531003
git tag v12.15.32
test_code v12.15.32 121531005 121531004
# Even if a later release published but its master sync failed, its reserved
# code remains the floor, so the caller must reject an older reservation.
test_code v12.15.32-beta.1 121531002 121531005

# Simulate successful publication and master sync. Earlier reservations keep
# their assigned numbers but expose the new floor, so the caller rejects them.
printf 'versionName "12.15.32"\nversionCode = 121531005\n' > app/build.gradle
git add app/build.gradle
git commit -qm sync
git push -q origin master
test_code v12.15.32-beta.1 121531002 121531005

# Distinct concurrent writers must never receive the same number or lose data.
git tag v12.15.33-beta.1
(cd "$tmp/b" && git tag v12.15.34-beta.1)
(bash "$SCRIPT" v12.15.33-beta.1 > "$tmp/a.out" 2> "$tmp/a.err") & pid_a=$!
(cd "$tmp/b" && bash "$SCRIPT" v12.15.34-beta.1 > "$tmp/b.out" 2> "$tmp/b.err") & pid_b=$!
wait "$pid_a" || { cat "$tmp/a.err" >&2; fail 'first concurrent allocation failed'; }
wait "$pid_b" || { cat "$tmp/b.err" >&2; fail 'second concurrent allocation failed'; }
git --git-dir="$tmp/origin.git" show release-versions:version-codes.json > "$tmp/ledger.json"
python3 - "$tmp/ledger.json" <<'PY'
import json, sys
with open(sys.argv[1]) as stream:
    data = json.load(stream)['assignments']
codes = [data[name]['versionCode'] for name in ('v12.15.33-beta.1', 'v12.15.34-beta.1')]
assert sorted(codes) == [121531006, 121531007], codes
assert data['v12.15.32-beta.1']['versionCode'] == 121531002
PY

# A tag that moves after reservation must not silently reuse the old code.
git tag -f v12.15.32-beta.1 HEAD >/dev/null
if bash "$SCRIPT" v12.15.32-beta.1 >/dev/null 2>&1; then
  fail 'moved release tag accepted'
fi
if bash "$SCRIPT" v12.15.32-beta.99 >/dev/null 2>&1; then
  fail 'nonexistent tag accepted'
fi
if bash "$SCRIPT" invalid >/dev/null 2>&1; then
  fail 'invalid tag accepted'
fi
printf 'versionName "210.0.0"\nversionCode = 2100000000\n' > app/build.gradle
git add app/build.gradle
git commit -qm 'raise floor'
git push -q origin master
git tag v12.15.35
if bash "$SCRIPT" v12.15.35 >/dev/null 2>&1; then
  fail 'versionCode over Android limit accepted'
fi
echo 'version-code tests passed'
