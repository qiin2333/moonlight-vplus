#!/usr/bin/env bash
# Verify that the tag being published still identifies the checked-out commit.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Usage: $0 TAG [REMOTE]" >&2
  exit 2
fi
tag=$1
remote=${2:-origin}
ref="refs/tags/$tag"
git check-ref-format "$ref" >/dev/null

local_commit=$(git rev-parse --verify "$ref^{commit}")
head_commit=$(git rev-parse --verify 'HEAD^{commit}')
if [ "$local_commit" != "$head_commit" ]; then
  echo "ERROR: local tag $tag does not point to the checked-out commit" >&2
  exit 1
fi

# ls-remote returns both the tag object and its peeled target for annotated
# tags. The peeled value is the commit; lightweight tags use the direct value.
if ! refs=$(git ls-remote --exit-code --tags "$remote" "$ref" "$ref^{}"); then
  echo "ERROR: cannot read remote tag $tag" >&2
  exit 1
fi
direct=
peeled=
while read -r object name; do
  case "$name" in
    "$ref") direct=$object ;;
    "$ref^{}") peeled=$object ;;
    *) echo "ERROR: unexpected remote tag response" >&2; exit 1 ;;
  esac
done <<< "$refs"
remote_commit=${peeled:-$direct}
if [ -z "$direct" ] || [ "$remote_commit" != "$head_commit" ]; then
  echo "ERROR: remote tag $tag no longer points to the checked-out commit" >&2
  exit 1
fi
echo "Verified remote tag $tag points to $head_commit"
