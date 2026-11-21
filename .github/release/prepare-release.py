#!/usr/bin/env python3
"""Classify a release tag and generate notes from published releases."""

import argparse
import datetime
import hashlib
from itertools import zip_longest
import json
import os
import re
import subprocess
import sys

TAG_RE = re.compile(r'^v(\d+)\.(\d+)(?:\.(\d+))?(?:[-.]([A-Za-z0-9][A-Za-z0-9.-]*))?$', re.I)
RELEASE_TAG_RE = re.compile(r'^v[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-(beta\.[0-9]+|beta\.mat\.[0-9]+|rc\.[0-9]+|rc[0-9]+))?$')
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


def update_version_parts(tag):
    """Match the Android updater's versionName comparison, including legacy tags."""
    normalized = re.sub(r'^[Vv]', '', tag).split('+', 1)[0]
    qualifier_match = re.search(r'[-_\s]', normalized)
    number_part = normalized[:qualifier_match.start()] if qualifier_match else normalized
    qualifier = normalized[qualifier_match.end():] if qualifier_match else ''

    def android_int(text):
        if not re.fullmatch(r'[0-9]+', text):
            return 0
        value = int(text)
        return value if value <= 2_147_483_647 else 0

    numbers = [android_int(part) for part in number_part.split('.')]
    tokens = [part for part in re.split(r'[.\-_\s]+', qualifier.lower()) if part]
    name = next((part for part in tokens if re.search(r'[a-z]', part)), '')
    rank = {'hotfix': 1, 'fix': 1, 'patch': 1, '': 0, 'stable': 0,
            'release': 0, 'final': 0, 'rc': -1, 'beta': -2, 'alpha': -3}.get(name, -1)
    qualifier_numbers = [android_int(match.group()) for token in tokens
                         if (match := re.search(r'\d+', token))]
    return numbers, rank, qualifier_numbers


def newer_than(target, previous):
    left = update_version_parts(target)
    right = update_version_parts(previous)
    for left_num, right_num in zip_longest(left[0], right[0], fillvalue=0):
        if left_num != right_num:
            return left_num > right_num
    if left[1] != right[1]:
        return left[1] > right[1]
    for left_num, right_num in zip_longest(left[2], right[2], fillvalue=0):
        if left_num != right_num:
            return left_num > right_num
    return False


def ensure_stable_is_newer(tag, releases):
    # Both channels use the same package. A formal release must be detected as
    # newer by devices already running any publicly released preview version.
    for release in releases:
        if release['draft']:
            continue
        previous = release['tag_name']
        if newer_than(tag, previous) is False:
            raise ValueError(f'stable release {tag} is not newer than published {previous}; '
                             'choose a higher version')


def releases_from_json(payload):
    if not isinstance(payload, list):
        raise ValueError('GitHub releases response must be an array')
    if payload and isinstance(payload[0], list):
        if not all(isinstance(page, list) for page in payload):
            raise ValueError('invalid release page')
        payload = [release for page in payload for release in page]
    for release in payload:
        if (not isinstance(release, dict) or not isinstance(release.get('tag_name'), str)
                or not isinstance(release.get('draft'), bool)
                or not isinstance(release.get('prerelease'), bool)
                or (not release['draft'] and not isinstance(release.get('published_at'), str))):
            raise ValueError('invalid release record')
    return payload


def classify(tag, releases):
    match = RELEASE_TAG_RE.fullmatch(tag)
    if not match:
        raise ValueError(f'unsupported tag {tag!r}; use vMAJOR.MINOR.PATCH, -beta.N, or -rc.N')
    if any(release['tag_name'] == tag and not release['draft'] for release in releases):
        raise ValueError(f'release {tag} is already published; refusing to overwrite it')
    return bool(match.group(1))


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
        tag_ref = f'refs/tags/{name}'
        ref = f'{tag_ref}^{{commit}}'
        if subprocess.run(['git', 'rev-parse', '--quiet', '--verify', ref],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            # A release may have appeared after the initial checkout. Fetch only
            # the missing tag, without forcing an existing local tag to move.
            fetched = subprocess.run(['git', 'fetch', '--quiet', '--no-tags', 'origin',
                                      f'{tag_ref}:{tag_ref}'])
            if fetched.returncode or subprocess.run(
                    ['git', 'rev-parse', '--quiet', '--verify', ref],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                raise ValueError(f'cannot fetch published release tag: {name}')
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
    if not prerelease:
        ensure_stable_is_newer(tag, releases)
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
    subparsers = parser.add_subparsers(dest='command', required=True)
    check = subparsers.add_parser('check', help='classify a release tag')
    check.add_argument('tag')
    notes = subparsers.add_parser('notes', help='check existing releases and render notes')
    notes.add_argument('tag')
    notes.add_argument('apk')
    args = parser.parse_args()
    try:
        prerelease = classify(args.tag, [])
        if args.command == 'check':
            print(str(prerelease).lower())
        else:
            repo = os.environ.get('GITHUB_REPOSITORY', '')
            if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
                raise ValueError('GITHUB_REPOSITORY must be set to owner/repository')
            payload = json.loads(run('gh', 'api', '--paginate', '--slurp',
                                     f'repos/{repo}/releases?per_page=100'))
            releases = releases_from_json(payload)
            classify(args.tag, releases)
            print(render(args.tag, prerelease, args.apk, repo, releases), end='')
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print(f'ERROR: could not prepare release: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
