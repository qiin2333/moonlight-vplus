#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
script="$script_dir/generate-release-notes.py"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/release-notes-test.XXXXXX")
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
cat > "$tmp/releases.json" <<'JSON'
[[
  {"tag_name":"v12.15.17","draft":false,"prerelease":false,"published_at":"2026-11-16T12:00:00Z"},
  {"tag_name":"v12.15.28","draft":false,"prerelease":true,"published_at":"2026-11-20T12:00:00Z"},
  {"tag_name":"v12.15.29","draft":false,"prerelease":true,"published_at":"2026-11-21T01:00:00Z"},
  {"tag_name":"v12.15.30","draft":false,"prerelease":false,"published_at":"2026-11-21T01:30:00Z"},
  {"tag_name":"v12.15.31","draft":false,"prerelease":false,"published_at":"2026-11-21T02:00:00Z"},
  {"tag_name":"v12.15.32-beta.1","draft":false,"prerelease":true,"published_at":"2026-11-22T01:00:00Z"},
  {"tag_name":"v12.15.32-beta.2","draft":false,"prerelease":true,"published_at":"2026-11-22T02:00:00Z"},
  {"tag_name":"v12.15.32","draft":false,"prerelease":false,"published_at":"2026-11-22T03:00:00Z"}
]]
JSON
export GITHUB_REPOSITORY=example/project
python3 "$script" v12.15.31 stable "$tmp/app.apk" --releases-json "$tmp/releases.json" > "$tmp/stable.md"
grep -Fq 'v12.15.17 之后至 v12.15.31' "$tmp/stable.md"
grep -Fq '王冠预览' "$tmp/stable.md"
grep -Fq '修复音频兼容' "$tmp/stable.md"
grep -Fq '暂停输入' "$tmp/stable.md"
grep -Fq '商店导入' "$tmp/stable.md"
grep -Fq 'https://github.com/example/project/pull/855' "$tmp/stable.md"
! grep -Fq '独立分支' "$tmp/stable.md"
! grep -Fq '新功能' "$tmp/stable.md"
python3 "$script" v12.15.32-beta.2 prerelease "$tmp/app.apk" --releases-json "$tmp/releases.json" > "$tmp/beta.md"
grep -Fq 'v12.15.32-beta.1 之后至 v12.15.32-beta.2' "$tmp/beta.md"
grep -Fq '测试版' "$tmp/beta.md"
grep -Fq '测试修复' "$tmp/beta.md"
! grep -Fq '最终修复' "$tmp/beta.md"
python3 "$script" v12.15.32 stable "$tmp/app.apk" --releases-json "$tmp/releases.json" > "$tmp/final.md"
grep -Fq 'v12.15.31 之后至 v12.15.32' "$tmp/final.md"
grep -Fq '新功能' "$tmp/final.md"
grep -Fq '测试修复' "$tmp/final.md"
grep -Fq '最终修复' "$tmp/final.md"
# A failed API request, invalid channel/tag pair or unrelated-only baseline must not silently produce notes.
mkdir "$tmp/bin"
printf '#!/bin/sh\nexit 1\n' > "$tmp/bin/gh"
chmod +x "$tmp/bin/gh"
if PATH="$tmp/bin:$PATH" python3 "$script" v12.15.31 stable "$tmp/app.apk" > /dev/null 2>&1; then
  echo 'ERROR: failed GitHub API did not stop notes generation' >&2; exit 1
fi
if python3 "$script" v12.15.31 prerelease "$tmp/app.apk" --releases-json "$tmp/releases.json" > /dev/null 2>&1; then
  echo 'ERROR: accepted an unmarked prerelease tag' >&2; exit 1
fi
printf '%s\n' '[{"tag_name":"v12.15.30","draft":false,"prerelease":false,"published_at":"2026-11-21T01:30:00Z"}]' > "$tmp/unrelated.json"
if python3 "$script" v12.15.31 stable "$tmp/app.apk" --releases-json "$tmp/unrelated.json" > /dev/null 2>&1; then
  echo 'ERROR: accepted unrelated release baseline' >&2; exit 1
fi
printf '%s\n' '[{"tag_name":"v12.15.32","draft":false,"prerelease":false,"published_at":"2026-11-22T03:00:00Z"}]' > "$tmp/newer.json"
if python3 "$script" v12.15.31 stable "$tmp/app.apk" --releases-json "$tmp/newer.json" > /dev/null 2>&1; then
  echo 'ERROR: generated first-release notes despite existing release history' >&2; exit 1
fi
echo 'generate-release-notes tests passed'
