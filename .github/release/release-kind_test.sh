#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
script="$script_dir/release-kind.sh"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-kind-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
cat > "$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [ "${FAIL_GH:-0}" = 1 ]; then
  echo 'API unavailable' >&2
  exit 1
fi
cat "$GH_RESPONSE"
SH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH"
export GH_RESPONSE="$tmp/response.json"
printf '[[{"tag_name":"v12.15.28","draft":false,"prerelease":true}],[{"tag_name":"v12.15.29","draft":false,"prerelease":true}]]\n' > "$GH_RESPONSE"

expect_kind() {
  local expected=$1 tag=$2 actual
  actual=$(bash "$script" "$tag" owner/repo)
  if [ "$actual" != "$expected" ]; then
    echo "ERROR: $tag classified as '$actual', expected '$expected'" >&2
    exit 1
  fi
}
expect_kind false v12.15.31
expect_kind false v12.1
expect_kind true v12.15.32-beta.1
expect_kind true v12.15.32-rc.2
expect_kind true v12.14.14-beta.mat.1
expect_kind true v12.3.1-rc12

# Never alter an existing release, including manually flagged numeric previews
# and a release on the second API page.
for tag in v12.15.28 v12.15.29; do
  if bash "$script" "$tag" owner/repo >/dev/null 2>&1; then
    echo "ERROR: already published $tag was accepted" >&2
    exit 1
  fi
done
# Drafts can be completed, while unknown suffixes must never default to stable.
printf '[[{"tag_name":"v12.15.32-beta.1","draft":true,"prerelease":true}]]\n' > "$GH_RESPONSE"
expect_kind true v12.15.32-beta.1
for tag in v12.15.32-preview.1 v12.15.32-beta v12.15.32-rc v12.15.32-rc.foo v12.15.32-foo; do
  if bash "$script" "$tag" owner/repo >/dev/null 2>&1; then
    echo "ERROR: unsupported tag $tag was accepted" >&2
    exit 1
  fi
done
if FAIL_GH=1 bash "$script" v12.15.32 owner/repo >/dev/null 2>&1; then
  echo 'ERROR: API failure was treated as no published release' >&2
  exit 1
fi
printf '{"error":"unexpected"}\n' > "$GH_RESPONSE"
if bash "$script" v12.15.32 owner/repo >/dev/null 2>&1; then
  echo 'ERROR: malformed response was accepted' >&2
  exit 1
fi
echo 'release-kind tests passed'
