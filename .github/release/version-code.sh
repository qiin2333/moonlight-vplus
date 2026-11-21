#!/usr/bin/env bash
# Reserve immutable, monotonically increasing versionCodes in an isolated branch.
# Usage: version-code.sh TAG
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 TAG" >&2
  exit 2
fi
tag=$1
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+(\.[0-9]+)?(-[a-zA-Z0-9][a-zA-Z0-9.-]*)?$ ]]; then
  echo "ERROR: invalid release tag: $tag" >&2
  exit 1
fi
commit=$(git rev-parse --verify "refs/tags/$tag^{commit}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=gradle-version.sh
source "$SCRIPT_DIR/gradle-version.sh"
tmp=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-versions.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
export GIT_INDEX_FILE="$tmp/index"

# At migration v12.15.31 was the highest published build (121531001). Fetch
# current master as well so another release or manual version update cannot
# cause an allocation below the installed version. This floor does not depend on
# unissued tags or the potentially old commit being released.
git fetch --quiet --no-tags origin refs/heads/master
git show FETCH_HEAD:app/build.gradle > "$tmp/master.gradle"
master_version=$(read_gradle_version "$tmp/master.gradle")
master_name=${master_version%$'\t'*}
master_code=${master_version##*$'\t'}
legacy_floor=121531001
floor=$legacy_floor
floor_source='verified v12.15.31 migration floor'
if [ "$master_code" -gt "$floor" ]; then
  floor=$master_code
  floor_source='origin/master'
fi

for retry in 1 2 3 4 5; do
  rm -f "$GIT_INDEX_FILE"
  if git ls-remote --exit-code --heads origin refs/heads/release-versions > /dev/null; then
    git fetch --quiet --no-tags origin refs/heads/release-versions
    parent=$(git rev-parse FETCH_HEAD)
    git read-tree "$parent"
    git show "$parent:version-codes.json" > "$tmp/previous.json"
  else
    status=$?
    if [ "$status" -ne 2 ]; then
      echo 'ERROR: unable to inspect release version ledger' >&2
      exit "$status"
    fi
    parent=''
    git read-tree --empty
    printf '{"schema":1,"assignments":{}}\n' > "$tmp/previous.json"
  fi

  # The tag/commit binding and stored code never change. A failed run leaves a
  # reservation, so its retry uses the same code and later builds skip it.
  allocation=$(python3 - "$tmp/previous.json" "$tmp/updated.json" "$tag" "$commit" "$floor" "$ANDROID_VERSION_CODE_MAX" <<'PY'
import json
import re
import sys

source, output, tag, commit, floor, maximum = sys.argv[1:]
floor, maximum = int(floor), int(maximum)
with open(source, encoding='utf-8') as stream:
    data = json.load(stream)
if not isinstance(data, dict) or data.get('schema') != 1 or not isinstance(data.get('assignments'), dict):
    raise SystemExit('ERROR: invalid release version ledger')
assignments = data['assignments']
codes = []
for key, entry in assignments.items():
    if (not isinstance(key, str) or not isinstance(entry, dict)
            or type(entry.get('versionCode')) is not int
            or not 1 <= entry['versionCode'] <= maximum
            or not isinstance(entry.get('commit'), str)
            or not re.fullmatch(r'[0-9a-f]{40,64}', entry['commit'])):
        raise SystemExit('ERROR: invalid release version ledger entry')
    codes.append(entry['versionCode'])
if len(codes) != len(set(codes)):
    raise SystemExit('ERROR: duplicate versionCodes in release version ledger')
ledger_floor = max(codes, default=0)
if tag in assignments:
    entry = assignments[tag]
    if entry['commit'] != commit:
        raise SystemExit('ERROR: release tag has moved since its versionCode was reserved')
    print(f"{entry['versionCode']}\texisting\t{ledger_floor}")
else:
    code = max([floor, *codes]) + 1
    if code > maximum:
        raise SystemExit('ERROR: Android versionCode limit exceeded')
    assignments[tag] = {'commit': commit, 'versionCode': code}
    with open(output, 'w', encoding='utf-8') as stream:
        json.dump(data, stream, indent=2, sort_keys=True)
        stream.write('\n')
    print(f'{code}\tnew\t{ledger_floor}')
PY
  )
  IFS=$'\t' read -r code state ledger_floor <<< "$allocation"
  if [ "$code" -eq "$master_code" ] && [ "$master_name" != "${tag#v}" ]; then
    echo "ERROR: versionCode $code already belongs to $master_name on master" >&2
    exit 1
  fi
  # A later reservation may have been published even if master synchronization
  # failed. Conservatively block earlier reservations from being published later.
  if [ "$ledger_floor" -gt "$floor" ]; then
    floor=$ledger_floor
    floor_source='release-versions ledger'
  fi
  if [ "$state" = existing ]; then
    printf '%s\t%s\t%s\n' "$code" "$floor" "$floor_source"
    exit 0
  fi
  blob=$(git hash-object -w "$tmp/updated.json")
  git update-index --add --cacheinfo "100644,$blob,version-codes.json"
  tree=$(git write-tree)
  if [ -n "$parent" ]; then
    next=$(printf 'Reserve versionCode %s for %s\n' "$code" "$tag" | git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit-tree "$tree" -p "$parent")
  else
    next=$(printf 'Reserve versionCode %s for %s\n' "$code" "$tag" | git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit-tree "$tree")
  fi
  if git push --quiet origin "$next:refs/heads/release-versions"; then
    printf '%s\t%s\t%s\n' "$code" "$floor" "$floor_source"
    exit 0
  fi
  echo "Version reservation push failed; retrying ($retry/5)" >&2
done
echo 'ERROR: could not reserve a versionCode after 5 attempts' >&2
exit 1
