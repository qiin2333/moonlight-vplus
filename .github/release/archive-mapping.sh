#!/usr/bin/env bash
# Archive the exact R8 mapping for a release without touching the checkout.
# Usage: archive-mapping.sh MAPPING APK TAG RUN_ID RUN_ATTEMPT
set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "Usage: $0 MAPPING APK TAG RUN_ID RUN_ATTEMPT" >&2
  exit 2
fi
mapping=$1
apk=$2
tag=$3
run_id=$4
attempt=$5

if [ ! -f "$mapping" ] || [ ! -f "$apk" ]; then
  echo 'ERROR: mapping and APK must both exist' >&2
  exit 1
fi
if [[ ! "$tag" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] ||
   [[ ! "$run_id" =~ ^[0-9]+$ ]] || [[ ! "$attempt" =~ ^[0-9]+$ ]]; then
  echo 'ERROR: invalid tag, run ID, or attempt' >&2
  exit 1
fi

tmp=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mapping-archive.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
export GIT_INDEX_FILE="$tmp/index"
archive_dir="$tag/$run_id-$attempt"
archive_mapping="$archive_dir/mapping.txt.gz"
archive_metadata="$archive_dir/metadata.json"

gzip -n -c -- "$mapping" > "$tmp/mapping.txt.gz"
python3 - "$mapping" "$apk" "$tag" "$run_id" "$attempt" "$tmp/metadata.json" <<'PY'
import hashlib
import json
import os
import sys

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

mapping, apk, tag, run_id, attempt, output = sys.argv[1:]
metadata = {
    'tag': tag,
    'run_id': run_id,
    'run_attempt': attempt,
    'apk_name': os.path.basename(apk),
    'apk_sha256': digest(apk),
    'mapping_sha256': digest(mapping),
}
with open(output, 'w', encoding='utf-8') as stream:
    json.dump(metadata, stream, indent=2, sort_keys=True, ensure_ascii=False)
    stream.write('\n')
PY
mapping_blob=$(git hash-object -w "$tmp/mapping.txt.gz")
metadata_blob=$(git hash-object -w "$tmp/metadata.json")

# Each retry starts from the latest remote tree and adds only this build's files.
# All Git operations use the checkout's repository and its authenticated remote.
for retry in 1 2 3 4 5; do
  rm -f "$GIT_INDEX_FILE"
  if git ls-remote --exit-code --heads origin refs/heads/mapping-archive > /dev/null; then
    git fetch --quiet --no-tags origin refs/heads/mapping-archive
    parent=$(git rev-parse FETCH_HEAD)
    git read-tree "$parent"
    if git cat-file -e "$parent:$archive_mapping" 2>/dev/null ||
       git cat-file -e "$parent:$archive_metadata" 2>/dev/null; then
      existing_mapping=$(git rev-parse "$parent:$archive_mapping" 2>/dev/null || true)
      existing_metadata=$(git rev-parse "$parent:$archive_metadata" 2>/dev/null || true)
      if [ "$existing_mapping" = "$mapping_blob" ] && [ "$existing_metadata" = "$metadata_blob" ]; then
        echo "Mapping already archived at $archive_dir"
        exit 0
      fi
      echo "ERROR: archive already exists with different content: $archive_dir" >&2
      exit 1
    fi
  else
    status=$?
    if [ "$status" -ne 2 ]; then
      echo 'ERROR: unable to inspect mapping archive branch' >&2
      exit "$status"
    fi
    parent=''
    git read-tree --empty
  fi
  git update-index --add --cacheinfo "100644,$mapping_blob,$archive_mapping"
  git update-index --add --cacheinfo "100644,$metadata_blob,$archive_metadata"
  tree=$(git write-tree)
  if [ -n "$parent" ]; then
    commit=$(printf 'Archive mapping for %s run %s attempt %s\n' "$tag" "$run_id" "$attempt" | git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit-tree "$tree" -p "$parent")
  else
    commit=$(printf 'Archive mapping for %s run %s attempt %s\n' "$tag" "$run_id" "$attempt" | git -c user.name='github-actions[bot]' -c user.email='41898282+github-actions[bot]@users.noreply.github.com' commit-tree "$tree")
  fi
  if git push --quiet origin "$commit:refs/heads/mapping-archive"; then
    echo "Archived mapping at $archive_dir"
    exit 0
  fi
  echo "Archive push failed; retrying ($retry/5)" >&2
done
echo 'ERROR: could not update mapping archive after 5 attempts' >&2
exit 1
