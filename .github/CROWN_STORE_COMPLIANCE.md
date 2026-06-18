# Crown Store Compliance Notes

The Crown Store is public user-generated content distributed through GitHub.
Moonlight V+ does not operate a custom server for review, storage, or takedown,
so the public GitHub repository, pull requests, issue reports, and maintainer
review are the governance layer.

## Public Submission Rules

- Submissions must be Crown profile bundles only, not full app backups.
- Submissions must not include tokens, passwords, pairing identity, client
  certificates, private keys, device identifiers, host addresses, or other
  private data.
- Submissions must not include copyrighted media, trademark-confusing branding,
  abusive content, harassment, malware-like behavior, or illegal content.
- Users must confirm they own the profile or have permission to share it before
  Moonlight V+ opens a GitHub pull request from their account.
- Maintainers may reject, edit, or remove submissions from the public index at
  any time.

## App-Side Controls

- The publish dialog requires an explicit confirmation before a profile can be
  submitted.
- Store metadata is length-limited and rejects links, email addresses, and
  common secret markers such as GitHub tokens, passwords, private keys, pairing
  identity, and client certificate fields.
- The generated bundle is parsed before publishing and must match the public
  Crown-only bundle shape.
- Store cards expose a Report action that opens a GitHub issue with the profile
  name and source URL prefilled.

These checks reduce accidental disclosure. They are not a substitute for GitHub
review, repository CI, or maintainer moderation.

## Maintainer Review

Before merging a Crown Store submission:

- Confirm the profile is a Crown-only bundle and imports cleanly.
- Confirm the metadata is appropriate for public display.
- Check that no secrets, pairing material, device identity, full backup content,
  private endpoints, copyrighted media, or abusive text is present.
- Reject submissions that appear to impersonate another creator or product.
- If a takedown, DMCA, privacy, or safety report is credible, remove the profile
  from `index/v1.json` and the referenced `profiles/` file while the report is
  investigated.

## Report Handling

Reports should be filed through the Crown Store report issue template. The
maintainer response should prefer quick delisting when privacy, secret leakage,
or safety risk is plausible, then follow up with the submitter if needed.
