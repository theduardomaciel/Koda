# Fork releases

How this fork is built, signed and published, and what to do when upstream
moves or a new fork build is cut.

Fork-only: do not upstream this file, `ForkConfig.kt` or
`.github/workflows/fork-release.yml`.

## Branches and remotes

| Name | Purpose |
|---|---|
| `origin` | `theduardomaciel/Koda`, the fork that publishes releases |
| `upstream` | `Ivorisnoob/Koda`, the source project |
| `beta` | A pure mirror of `upstream/beta`; only fast-forward it |
| `custom` | Integration and release branch; everything the fork ships |
| feature branches | Cut from `custom` for fork-only work, or from `beta` when the work is going upstream |

Rules:

- `custom` must always contain `upstream/beta`; the release workflow checks this.
- Merge, do not rebase, branches that have been shared.
- Keep `app/build.gradle.kts` and `gradle/libs.versions.toml` identical to
  upstream. The fork does not change `versionCode` or `versionName` in source.
- Do not merge `custom` into an upstream-bound feature branch.

## Release model

- `versionCode` comes from upstream and must be `>=` upstream's at release time.
- The release workflow stamps the APK's `versionName` from the tag; the source
  tree keeps upstream's value.
- Tags use four numeric parts: `v<major>.<minor>.<patch>.<forkBuild>`
  (for example `v5.2.0.2`). The in-app updater compares digit runs, not SemVer,
  so four parts make a fork build newer than its upstream base and older than
  the next upstream release.
- Never reuse or move a published tag.
- Releases must be published and non-prerelease; GitHub's `/releases/latest`
  ignores drafts and pre-releases, and the updater reads that endpoint.

## One-time GitHub setup

1. Enable Actions on the fork.
2. Allow **Read and write** workflow permissions (the release job creates the
   release).
3. Add the signing secrets:

```powershell
gh secret set KEYSTORE_BASE64 --repo theduardomaciel/Koda
gh secret set KEYSTORE_PASSWORD --repo theduardomaciel/Koda
gh secret set KEY_ALIAS --repo theduardomaciel/Koda
gh secret set KEY_PASSWORD --repo theduardomaciel/Koda
```

`KEYSTORE_BASE64` is the base64 of the release `.jks`. Keep the keystore and
passwords outside the repository.

Tag-triggered releases do not depend on the default branch. `workflow_dispatch`
does: the workflow file must exist on the repository's default branch, or set
the default branch to `custom` / release by tag.

Local signing is optional. The release workflow signs in CI from the secrets.
For a local signed build, inject AGP's four signing properties through a
private Gradle init script or environment variables; never commit the keystore
or passwords.

## Cutting a release

1. Update and verify the branch:

```powershell
git fetch upstream
git checkout beta
git merge --ff-only upstream/beta
git checkout custom
git merge beta
git status --short
git merge-base --is-ancestor upstream/beta custom   # must succeed
git diff --stat upstream/beta...custom -- "*.gradle.kts" "gradle/libs.versions.toml"   # must be empty
.\gradlew.bat :app:compileDebugKotlin
```

2. Pick the next tag. With upstream at `5.2`, fork builds are `v5.2.0.1`,
   `v5.2.0.2`, and so on. If upstream moves to `5.2.1`, restart at
   `v5.2.1.1`. The tag must be higher than every published tag.

3. Write the release notes (see below).

4. Tag and push:

```powershell
git tag -a v5.2.0.2 -m "Koda v5.2.0.2"
git push origin custom
git push origin v5.2.0.2
```

5. Watch the run and verify the release:

```powershell
gh run list --repo theduardomaciel/Koda --workflow fork-release.yml --limit 5
gh run watch <run-id> --repo theduardomaciel/Koda
gh release view v5.2.0.2 --repo theduardomaciel/Koda
```

Expected: a green run, a published non-prerelease latest release, and three
APKs (`app-universal-release.apk`, `app-arm64-v8a-release.apk`,
`app-armeabi-v7a-release.apk`).

The workflow:

1. Checks out the tagged commit with full history.
2. Fetches `upstream/beta` and asserts it is an ancestor.
3. Asserts the local `versionCode` is at least upstream's.
4. Stamps `versionName` with the tag and bundles the release-notes asset.
5. Builds and signs the three APKs.
6. Verifies the signature and the stamped version name.
7. Creates the GitHub release with generated notes.

## Release notes and changelogs

There are three separate surfaces; they are not the same file.

### 1. In-app "What's new" sheet

- File: `app/src/main/assets/release-notes/<versionName>.md`
- Read on the first launch after an update, offline; 3–6 `- ` bullets.
- A release build's `versionName` is the tag without `v`, so `v5.2.0.2` looks
  for `5.2.0.2.md`.
- If that file is missing, the release workflow copies the upstream base file
  (`5.2.md`) to the tag name in the CI checkout. To give a fork release its own
  sheet, commit the version-specific file and push it before tagging.
- This file ships inside the APK; editing it after the build has no effect.

### 2. Update dialog on an older install

- The dialog reads the GitHub release body and takes the bullets under
  `## Highlights`.
- `--generate-notes` does not create that heading. Add it after the release is
  published, keeping the generated notes below it if wanted:

```markdown
## Highlights

- One user-visible reason to update.
- Another one.

## What's Changed
...
```

```powershell
gh release edit v5.2.0.2 --repo theduardomaciel/Koda --notes-file .\release-notes.md
```

- Without the heading the updater still works but shows a one-line
  "no highlights" message.

### 3. GitHub release / Telegram changelogs

- The GitHub release body is created with `--generate-notes`, which lists
  commits and merged PRs since the previous release. Commit titles are shown
  as-is, so edit the release if they are too internal.
- Upstream's `build.yml` posts a separate changelog to Telegram when `beta` is
  pushed. It reads the `Changelog:` section from each commit message between
  successful beta builds:

```text
Short commit subject

Changelog:
- User-visible change.
- Another one.
```

- Use `Changelog:` only for commits that change the APK. Docs/CI-only commits
  omit it. This Telegram path does not run for fork releases.

## Updating from upstream

```powershell
git fetch upstream
git checkout beta
git merge --ff-only upstream/beta
git checkout custom
git merge beta
```

Then:

- Resolve conflicts by reading both sides; never blindly take one.
- Keep upstream's version values.
- Re-point any new user-facing `BuildConfig.GITHUB_REPO` call site at
  `ForkConfig.GITHUB_REPO`.
- Run `git merge-base --is-ancestor upstream/beta custom` and
  `.\gradlew.bat :app:compileDebugKotlin` before tagging.

## Install notes

The fork is signed with a different key from upstream. The first switch from
the original app requires an uninstall; back up first (Settings > Backup) and
copy out anything the backup does not carry. After that, fork-to-fork updates
keep data as long as the key is unchanged and `versionCode` does not go
backwards.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Workflow missing | Enable Actions; for manual dispatch the workflow must be on the default branch, or push a tag instead. |
| `Resource not accessible by integration` | Workflow permissions are read-only; allow write. |
| Guard: `upstream/beta` is not an ancestor | Merge the current `upstream/beta` into `custom`, then tag again. |
| Guard: `versionCode` too low | Merge upstream's bump into `custom`; never override it locally. |
| APK signed with the wrong key | Compare `apksigner verify --print-certs` with `keytool -list -v`; check the init script/secrets. |
| Release exists already | Use a new fork-build tag; prefer that over deleting a published release. |
| Updater says "up to date" | Confirm the release is latest and non-prerelease, the tag is higher than the installed version, and `ForkConfig.GITHUB_REPO` still points at the fork. |
| Install fails `UPDATE_INCOMPATIBLE` | Different signing key; uninstall/reinstall (back up first). |
| Install fails `VERSION_DOWNGRADE` | Publish a higher `versionCode`. |

## Security

- Never commit the keystore, base64 dumps, or passwords.
- Keep two offline/encrypted keystore backups and the passwords in a password
  manager. Losing the key forces every user to uninstall.
- Fork pull requests never receive secrets; do not add `pull_request` triggers
  to the release workflow.
