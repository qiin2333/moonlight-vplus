# Release workflow

Use `vMAJOR.MINOR.PATCH-beta.N` or `-rc.N` for a pre-release, and
`vMAJOR.MINOR.PATCH` for a stable release. A stable version must be newer than
every published stable or preview version, matching the app's update check.
Stable notes cover changes since the previous stable ancestor; preview notes use
the previous reachable release. The generated notes are a commit-based draft
and should be reviewed for user-facing clarity.

Release jobs run serially. Each tag and commit reserves a monotonically
increasing Android versionCode in the public `release-versions` branch. The
migration floor is 121531001 (v12.15.31); reserved numbers are not reused even
if a build fails. Retrying a reserved tag uses the same number; if a newer
reservation exists, the older build is rejected even if the newer build failed.
Moving a reserved tag is rejected. Version codes no longer encode version names.
Protect and back up the ledger branch: deleting or resetting it could allow
reserved version codes to be reused, especially if master sync has failed.
The workflow verifies that the remote release tag still points to the checked
out commit before allocation and immediately before publication; repository tag
protection is still needed to prevent a move between those checks.

Before publishing, CI verifies the APK signature, package, version and required
native libraries. The expected certificate fingerprint was verified against the
v12.15.17 and v12.15.31 official APKs. For an intentional key rotation, first
verify the new fingerprint independently and check Android signing lineage and
update compatibility; then update the verifier's pin.

## Mapping archive

Each published build's mapping is compressed into this public repository's
`mapping-archive` branch and uploaded to Crashlytics when configured. The branch
grows over time.
Archives begin with releases built after this workflow change; the Actions
artifact also contains the mapping but expires under the retention policy.

Each `<tag>/<run-id>-<attempt>/` directory contains `mapping.txt.gz` and
`metadata.json`, with the APK and mapping SHA-256 hashes. Match the APK hash
before retrieving the corresponding mapping:

```sh
tag=v12.15.32
build=123456789-1
git fetch origin mapping-archive
git show "FETCH_HEAD:$tag/$build/metadata.json"
git show "FETCH_HEAD:$tag/$build/mapping.txt.gz" | gzip -dc > mapping.txt
```

If archiving fails, publication stops before a Release is created.

The release Action uploads to a draft; CI verifies the remote APK hash before
making it public. A retry after publication downloads and checks the existing
APK and resumes version synchronization without replacing it. If a draft
already contains a different APK or edited notes, publication stops for manual
review instead of overwriting either. The release job requires write access to
`release-versions`, `mapping-archive`, and `master`.
