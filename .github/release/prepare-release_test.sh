#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
script="$script_dir/prepare-release.py"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-prepare-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
git init -q -b master "$tmp/repo"
cd "$tmp/repo"
git config user.name Test
git config user.email test@example.com
printf 'apk content\n' > "$tmp/app.apk"
commit() {
  printf '%s\n' "$1" >> tracked.txt
  git add tracked.txt
  git commit -qm "$1"
}
commit 'feat: initial'
git tag v12.15.17
commit '修复音频兼容 (#854)'
commit 'feat: 王冠预览 (#855)'
git tag v12.15.28
commit 'fix: 暂停输入 (#865)'
git tag v12.15.29
commit 'fix: 商店导入 (#877)'
git tag v12.15.31
commit 'feat: 新功能 (#900)'
git tag v12.15.32-beta.1
commit 'fix: 测试修复 (#901)'
git tag v12.15.32-beta.2
commit 'fix: 最终修复 (#902)'
git tag v12.15.32
git checkout -qb unrelated v12.15.17
commit 'feat: 独立分支'
git tag v12.15.30
git checkout -q master

mkdir "$tmp/bin"
cat > "$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'x' >> "$GH_CALLS"
[ "${FAIL_GH:-0}" != 1 ] || exit 1
cat "$GH_RESPONSE"
SH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GITHUB_REPOSITORY=example/project
export GH_RESPONSE="$tmp/releases.json" GH_CALLS="$tmp/gh-calls"
cat > "$GH_RESPONSE" <<'JSON'
[[
  {"tag_name":"v12.15.17","draft":false,"prerelease":false,"published_at":"2026-11-16T12:00:00Z"},
  {"tag_name":"v12.15.28","draft":false,"prerelease":true,"published_at":"2026-11-20T12:00:00Z"},
  {"tag_name":"v12.15.29","draft":false,"prerelease":true,"published_at":"2026-11-21T01:00:00Z"},
  {"tag_name":"v12.15.30","draft":false,"prerelease":false,"published_at":"2026-11-21T01:30:00Z"},
  {"tag_name":"v12.15.32-beta.1","draft":false,"prerelease":true,"published_at":"2026-11-22T01:00:00Z"}
]]
JSON
expect_kind() { [ "$(python3 "$script" check "$2")" = "$1" ]; }
expect_kind false v12.15.31
expect_kind false v12.1
expect_kind true v12.15.32-beta.1
expect_kind true v12.15.32-rc.2
expect_kind true v12.14.14-beta.mat.1
expect_kind true v12.3.1-rc12
for tag in v12.15.32-preview.1 v12.15.32-beta v12.15.32-rc.foo; do
  if python3 "$script" check "$tag" >/dev/null 2>&1; then
    echo "ERROR: unsupported tag $tag accepted" >&2; exit 1
  fi
done
[ ! -e "$GH_CALLS" ] # Classifying a tag does not call the API.
python3 "$script" notes v12.15.31 "$tmp/app.apk" > "$tmp/stable.md"
[ "$(cat "$GH_CALLS")" = x ] # Exactly one paginated API invocation.
grep -Fq 'v12.15.17 之后至 v12.15.31' "$tmp/stable.md"
for text in '王冠预览' '修复音频兼容' '暂停输入' '商店导入' 'https://github.com/example/project/pull/855' 'SHA256'; do
  grep -Fq "$text" "$tmp/stable.md"
done
! grep -Fq '独立分支' "$tmp/stable.md"
! grep -Fq '新功能' "$tmp/stable.md"
# Add the just-published stable version before generating subsequent notes.
python3 - "$GH_RESPONSE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: releases = json.load(f)
releases[0].append({'tag_name': 'v12.15.31', 'draft': False, 'prerelease': False, 'published_at': '2026-11-21T02:00:00Z'})
with open(p, 'w') as f: json.dump(releases, f)
PY
python3 "$script" notes v12.15.32-beta.2 "$tmp/app.apk" > "$tmp/beta.md"
grep -Fq 'v12.15.32-beta.1 之后至 v12.15.32-beta.2' "$tmp/beta.md"
grep -Fq '测试版' "$tmp/beta.md"
grep -Fq '测试修复' "$tmp/beta.md"
! grep -Fq '最终修复' "$tmp/beta.md"
python3 "$script" notes v12.15.32 "$tmp/app.apk" > "$tmp/final.md"
grep -Fq 'v12.15.31 之后至 v12.15.32' "$tmp/final.md"
for text in '新功能' '测试修复' '最终修复'; do grep -Fq "$text" "$tmp/final.md"; done
# Never overwrite a published release, and fail closed on API/baseline errors.
for tag in v12.15.28 v12.15.31; do
  if python3 "$script" notes "$tag" "$tmp/app.apk" >/dev/null 2>&1; then
    echo "ERROR: already published $tag was accepted" >&2; exit 1
  fi
done
if FAIL_GH=1 python3 "$script" notes v12.15.32 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: failed GitHub API did not stop notes generation' >&2; exit 1
fi
printf '%s\n' '[[{"tag_name":"v12.15.30","draft":false,"prerelease":false,"published_at":"2026-11-21T01:30:00Z"}]]' > "$GH_RESPONSE"
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: accepted unrelated release baseline' >&2; exit 1
fi
printf '{"error":"unexpected"}\n' > "$GH_RESPONSE"
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: malformed response was accepted' >&2; exit 1
fi
printf '%s\n' '[[{"tag_name":"v12.15.17","draft":false,"prerelease":false}]]' > "$GH_RESPONSE"
if python3 "$script" notes v12.15.31 "$tmp/app.apk" >/dev/null 2>&1; then
  echo 'ERROR: published release without a timestamp was accepted' >&2; exit 1
fi
echo 'prepare-release tests passed'
