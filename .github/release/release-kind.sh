#!/usr/bin/env bash
# Classify a release tag and refuse to replace an already published release.
# Usage: release-kind.sh <tag> <owner/repository>
# Prints "true" for a prerelease and "false" for a stable release.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <tag> <owner/repository>" >&2
  exit 2
fi
tag=$1
repo=$2

# A two-part base is accepted for existing tags such as v12.1. New preview
# tags use -beta.N or -rc.N. Legacy -rc12 and -beta.mat.1 remain supported.
if [[ "$tag" =~ ^v[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
  prerelease=false
elif [[ "$tag" =~ ^v[0-9]+\.[0-9]+(\.[0-9]+)?-(beta\.[0-9]+|beta\.mat\.[0-9]+|rc\.[0-9]+|rc[0-9]+)$ ]]; then
  prerelease=true
else
  echo "ERROR: unsupported release tag '$tag'; use vMAJOR.MINOR.PATCH, -beta.N, or -rc.N" >&2
  exit 1
fi
if [[ ! "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo 'ERROR: expected repository in owner/name form' >&2
  exit 1
fi

tmp=$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-list.XXXXXX")
trap 'rm -f "$tmp"' EXIT
# List all pages rather than treating a failed lookup as "not found". This
# also preserves old releases whose prerelease status was set manually.
if ! gh api --paginate --slurp "repos/$repo/releases?per_page=100" > "$tmp"; then
  echo 'ERROR: unable to inspect existing GitHub releases' >&2
  exit 1
fi
python3 - "$tmp" "$tag" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as stream:
    pages = json.load(stream)
if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
    raise SystemExit('ERROR: unexpected GitHub release list response')
for page in pages:
    for release in page:
        if not isinstance(release, dict) or not isinstance(release.get('draft'), bool):
            raise SystemExit('ERROR: malformed GitHub release list response')
        if release.get('tag_name') == sys.argv[2] and not release['draft']:
            raise SystemExit('ERROR: release already published; refusing to overwrite it')
PY
printf '%s\n' "$prerelease"
