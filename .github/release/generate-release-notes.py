#!/usr/bin/env python3
"""Generate release notes from published releases and the current tag's history."""

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys

TAG_RE = re.compile(r'^v(\d+)\.(\d+)(?:\.(\d+))?(?:[-.]([A-Za-z0-9][A-Za-z0-9.-]*))?$', re.I)
COMMIT_RE = re.compile(r'^(feat|fix|perf|refactor|remove|chore|ci|docs|style|test|build)(?:\([^)]*\))?!?:\s+(.+)$')


def run(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout.strip()


def version(tag):
    match = TAG_RE.fullmatch(tag)
    if not match:
        return None
    base = tuple(int(part or 0) for part in match.group(1, 2, 3))
    suffix = match.group(4)
    # Compare alternating text and numbers so beta.2 precedes beta.10.
    tokens = tuple((0, int(s)) if s.isdigit() else (1, s.lower())
                   for s in re.findall(r'\d+|[A-Za-z]+', suffix or ''))
    return base, tokens


def older(candidate_tag, candidate_pre, target_tag, target_pre):
    candidate = version(candidate_tag)
    target = version(target_tag)
    if not candidate or not target:
        return False
    if candidate[0] != target[0]:
        return candidate[0] < target[0]
    if not target_pre:
        return candidate_pre
    # Unmarked numeric prereleases have no well-defined order within the same
    # semantic base; never guess whether they precede the target prerelease.
    return candidate_pre and bool(candidate[1]) and candidate[1] < target[1]


def releases_from_json(payload):
    if not isinstance(payload, list):
        raise ValueError('GitHub releases response must be an array')
    if payload and isinstance(payload[0], list):
        payload = [release for page in payload for release in page]
    if not all(isinstance(release, dict) for release in payload):
        raise ValueError('invalid release record')
    return payload


def select_previous(releases, tag, prerelease):
    candidates = []
    for release in releases:
        name = release.get('tag_name')
        published = release.get('published_at')
        if release.get('draft') or not published or not isinstance(name, str) or name == tag:
            continue
        previous_pre = release.get('prerelease') is True
        if not prerelease and previous_pre:
            continue
        if not older(name, previous_pre, tag, prerelease):
            continue
        candidates.append((published, name))
    for _, name in sorted(candidates, reverse=True):
        ref = f'refs/tags/{name}^{{commit}}'
        if subprocess.run(['git', 'rev-parse', '--quiet', '--verify', ref],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            raise ValueError(f'published release tag is missing locally: {name}')
        result = subprocess.run(['git', 'merge-base', '--is-ancestor', ref,
                                 f'refs/tags/{tag}^{{commit}}'])
        if result.returncode == 0:
            return name
        if result.returncode != 1:
            raise ValueError(f'cannot compare history for {name} and {tag}')
    if candidates:
        raise ValueError('no older published release is an ancestor of the current tag')
    if any(not release.get('draft') and release.get('published_at') for release in releases):
        raise ValueError('published releases exist but no eligible earlier baseline was found')
    return None


def render(tag, prerelease, apk, repo, releases):
    if not version(tag):
        raise ValueError(f'unsupported release tag: {tag}')
    if prerelease and not version(tag)[1]:
        raise ValueError('prerelease tags must include a suffix, such as -beta.1')
    if not prerelease and version(tag)[1]:
        raise ValueError('stable tags must not contain a prerelease suffix')
    if not os.path.isfile(apk):
        raise ValueError(f'APK not found: {apk}')
    run('git', 'rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}')
    previous = select_previous(releases, tag, prerelease)
    revision = f'{previous}..{tag}' if previous else tag
    log = run('git', 'log', '--no-merges', '--format=%s', revision)
    categories = {'feat': [], 'fix': [], 'perf': [], 'remove': []}
    ignored = {'chore', 'ci', 'docs', 'style', 'test', 'build'}
    unmatched = []
    for line in log.splitlines():
        match = COMMIT_RE.match(line)
        if not match:
            if repo:
                line = re.sub(r'\(#(\d+)\)$', lambda m: f'([#{m.group(1)}](https://github.com/{repo}/pull/{m.group(1)}))', line)
            unmatched.append(line)
            continue
        kind, description = match.groups()
        if kind in ignored:
            continue
        kind = 'perf' if kind == 'refactor' else kind
        # Keep pull request references directly clickable in the release.
        if repo:
            description = re.sub(r'\(#(\d+)\)$', lambda m: f'([#{m.group(1)}](https://github.com/{repo}/pull/{m.group(1)}))', description)
        categories[kind].append(description)
    sha256 = hashlib.sha256()
    with open(apk, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            sha256.update(block)
    out = [f'## 🌙 Moonlight V+ {tag[1:]}', '']
    if prerelease:
        out.extend(['**测试版**：适合愿意参与测试的用户，更新内容仍可能调整。', ''])
    if previous:
        out.extend([f'本次更新汇总 **{previous} 之后至 {tag}** 的改动。', ''])
    for kind, heading, icon in [('feat', '新增', '✨'), ('perf', '优化', '⚡'),
                                ('fix', '修复', '🐛'), ('remove', '其他', '🗑️')]:
        if categories[kind]:
            out.append(f'### {heading}')
            out.extend(f'- {icon} {item}' for item in categories[kind])
            out.append('')
    if unmatched:
        out.extend(['### 更新内容', *(f'- {item}' for item in unmatched), ''])
    if previous and repo:
        out.extend([f'[查看完整变更](https://github.com/{repo}/compare/{previous}...{tag})', ''])
    issues = f'https://github.com/{repo}/issues' if repo else '../../issues'
    out.extend(['### 📦 下载', f'- APK 文件：`{os.path.basename(apk)}`', '', '### SHA256', '',
                '```text', f'{sha256.hexdigest()}  {os.path.basename(apk)}', '```', '',
                '---', f'如遇到问题，请在 [Issues]({issues}) 反馈。🎮', '',
                f'*发布于 {datetime.datetime.now(datetime.timezone.utc).date().isoformat()}*', ''])
    return '\n'.join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag')
    parser.add_argument('channel', choices=('stable', 'prerelease'))
    parser.add_argument('apk')
    parser.add_argument('--releases-json', help='read GitHub release records from a JSON file')
    args = parser.parse_args()
    repo = os.environ.get('GITHUB_REPOSITORY', '')
    try:
        if args.releases_json:
            with open(args.releases_json, encoding='utf-8') as stream:
                payload = json.load(stream)
        else:
            if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
                raise ValueError('GITHUB_REPOSITORY must be set to owner/repository')
            payload = json.loads(run('gh', 'api', '--paginate', '--slurp',
                                     f'repos/{repo}/releases?per_page=100'))
        print(render(args.tag, args.channel == 'prerelease', args.apk, repo,
                     releases_from_json(payload)), end='')
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print(f'ERROR: could not generate release notes: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
