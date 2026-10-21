# Fork setup, signed releases and updates

Everything specific to running this repository as a fork of `Ivorisnoob/Koda`: the branch model, the signing key, the release workflow, the in-app updater, and what to do every time upstream ships or you cut a release.

**This file is fork-only. Do not upstream it.** Upstream has no fork key, no `custom` branch and no `fork-release.yml`. Nothing here changes the app's normal build or upstream compatibility: the fork additions are one Kotlin object, four one-line call sites, one workflow file and local-only signing configuration.

Read this before touching the release setup. The reasoning behind the signatures and the branch rules is in the previous plan, but this file is self-contained.

---

## At a glance

| Item | Value |
|---|---|
| Fork repository | `theduardomaciel/Koda` (remote `origin`) |
| Upstream repository | `Ivorisnoob/Koda` (remote `upstream`) |
| App package | `com.ivor.ivormusic` |
| Release branch | `custom` |
| Upstream mirror | `beta` (tracks `upstream/beta`, fast-forward only) |
| Release workflow | `.github/workflows/fork-release.yml` |
| Fork updater constant | `app/src/main/java/com/ivor/ivormusic/data/ForkConfig.kt` |
| Signing key (local) | `~/.android-keys/koda-fork.jks` (`%USERPROFILE%\.android-keys\koda-fork.jks` on Windows) |
| Key alias | `koda-fork` |
| Local signing init script | `~/.gradle/init.d/koda-signing.gradle` |
| Release tag format | `v<major>.<minor>.<patch>.<forkBuild>`, for example `v5.2.0.1` |
| Release assets | `app-universal-release.apk`, `app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk` |

A release is built and signed in GitHub Actions, not on a developer machine. The local key and init script exist only so you can build a signed release locally when needed.

---

## 1. Branch model

There are three kinds of branches. Keeping them separate is what makes upstream merges predictable.

```
upstream/beta  --fast-forward-->  beta
                                   |
                                   +--> feat/liked-songs-cache
                                              |
                                              +--> feat/account-library
                                                          |
                                                          +--> custom (release branch)
```

| Branch | Purpose | Rules |
|---|---|---|
| `beta` | A mirror of `upstream/beta`. | **Never commit here.** Only `git merge --ff-only upstream/beta`. It must stay byte-identical to upstream so `git log beta..upstream/beta` is always empty. |
| `feat/liked-songs-cache` | Base feature branch: the account Liked Music cache and sync. | Cut from `beta`. Never rebase. Merge `beta` into it when upstream moves. This is the branch an upstream pull request should come from first. |
| `feat/account-library` | Stacked on `feat/liked-songs-cache`: account Library tabs and offline Library behavior. | Not cut from `beta`. Never rebase it onto `beta`; that would duplicate the base branch's commits. Merge `feat/liked-songs-cache` into it. |
| `custom` | Integration and release branch. Contains everything the fork ships. | Merge `beta`, then the feature branches. Never rebase. **Fork-only files live here** (`ForkConfig.kt`, `fork-release.yml`, this document). This is what the release workflow builds. |
| Fork-only branches (optional) | New work that is specific to the fork. | Cut from `custom`, merge back into `custom`. Do not base them on `beta` unless the work is going upstream. |

Rules that apply to every branch:

- Merge, never rebase, after a branch has been shared or stacked. Rebasing rewrites the commit ids the other branches build on.
- Do not merge `custom` into a feature branch. The feature branch must stay upstream-PR-ready.
- `rerere` is enabled locally. When you resolve the same conflict on a later upstream merge, Git replays the recorded resolution; still read the result before committing.
- Upstream pull requests target `beta`, never `main` (see `CLAUDE.md`, section 1).

State at the time of writing (hashes drift; re-derive with `git log`):

- `beta` = `957b24aa` (upstream `beta`).
- `feat/liked-songs-cache` = `729ef124` after merging `beta`.
- `feat/account-library` = `70878358` after merging the stack base.
- `custom` = `70878358`, plus the fork files, which were still uncommitted when this document was written.

---

## 2. One-time setup

Do this once per machine and once per fork repository. The keystore must survive machine changes; back it up before you need it.

### 2.1 Prerequisites

- Git, with `origin` and `upstream` configured.
- JDK 17 for Gradle (the release workflow uses Temurin 17).
- Android SDK with `compileSdk = 37` installed, and `local.properties` pointing at it.
- `keytool` (part of the JDK) to create and inspect the signing key.
- `apksigner` from the installed Android build-tools to verify a built APK.
- GitHub CLI (`gh`), authenticated with access to the fork repository, for secrets, runs and releases.

Check the tools:

```powershell
git --version
java -version
gh auth status
keytool -help
```

### 2.2 Clone and remotes

From an existing clone, confirm the remotes:

```powershell
git remote -v
```

They must be:

```text
origin    https://github.com/theduardomaciel/Koda.git (fetch)
origin    https://github.com/theduardomaciel/Koda.git (push)
upstream  https://github.com/Ivorisnoob/Koda.git (fetch)
upstream  https://github.com/Ivorisnoob/Koda.git (push)
```

If `upstream` is missing:

```powershell
git remote add upstream https://github.com/Ivorisnoob/Koda.git
git fetch upstream
```

### 2.3 Git settings

```powershell
git config --local rerere.enabled true
git config --local rerere.autoupdate true
git config --local pull.ff only
```

Why:

- `rerere` remembers how you resolved an upstream conflict and replays it on the next merge.
- `rerere.autoupdate` stages the replayed resolution.
- `pull.ff only` refuses to create surprise merge commits when pulling. Every merge in this workflow is explicit.

### 2.4 Branch layout

On a fresh clone:

```powershell
git fetch upstream
git checkout beta
git branch --set-upstream-to=upstream/beta beta
git merge --ff-only upstream/beta
git checkout -b custom beta          # or: git checkout custom, if origin already has it
git checkout feat/liked-songs-cache
git merge beta
git checkout feat/account-library
git merge feat/liked-songs-cache
git checkout custom
git merge feat/liked-songs-cache
git merge feat/account-library
```

If this is a brand-new machine, the two feature branches must already exist locally or on `origin`. Push them from the machine that has them before setting up the new clone (`git push origin feat/liked-songs-cache feat/account-library`); they are not created by this sequence.

Note the order for the stack: update `feat/liked-songs-cache` with `beta` first, then merge that branch into `feat/account-library`. Merging `beta` directly into the stacked branch also works, but skips keeping the base current.

Because `custom` is a superset of both feature branches in this setup, the last two merges are fast-forwards. If the branches diverge later, they become ordinary merge commits.

Verify the layout:

```powershell
git merge-base --is-ancestor upstream/beta custom; echo $LASTEXITCODE   # 0
git merge-base --is-ancestor feat/liked-songs-cache custom; echo $LASTEXITCODE
git merge-base --is-ancestor feat/account-library custom; echo $LASTEXITCODE
git merge-base --is-ancestor feat/liked-songs-cache feat/account-library; echo $LASTEXITCODE
```

Every command must print `0`.

### 2.5 Create the signing key (once)

Create the directory first, then generate a PKCS12 keystore. `keytool` prompts for the store password and the key password; use the same strong password for both (PKCS12 normally requires it) and store it in a password manager. Do not put the password on the command line or in shell history.

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.android-keys"
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.android-keys\koda-fork.jks" `
  -alias koda-fork -keyalg RSA -keysize 4096 -validity 10000 `
  -storetype PKCS12
```

On macOS or Linux:

```bash
mkdir -p ~/.android-keys
keytool -genkeypair -v \
  -keystore ~/.android-keys/koda-fork.jks \
  -alias koda-fork -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

Check the certificate and record the SHA-256:

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android-keys\koda-fork.jks" -alias koda-fork
```

**Back the keystore up now.** Copy `koda-fork.jks` to two places that are not this machine (an encrypted archive in cloud storage and an offline drive), and record the passwords separately. Losing the key means:

- You cannot sign an update that installs over an existing fork install.
- Every user must uninstall (losing app data that is not in the in-app backup) before installing again.
- The only recovery is a new key with a new application identity, which is effectively a different app to Android's package manager.

Never commit the keystore. The repository ignores `keystore/`, and the fork key lives outside the repository anyway.

### 2.6 Local signing without editing build files

Release signing is injected through Gradle properties that AGP reads directly. The repository's `app/build.gradle.kts` stays untouched, which matters because it is an upstream file that would conflict on every merge.

The four properties AGP requires:

```text
android.injected.signing.store.file
android.injected.signing.store.password
android.injected.signing.key.alias
android.injected.signing.key.password
```

Do not add them to `gradle.properties` in the repository. Either export environment variables or create a private properties file.

**Option A: environment variables (recommended for a one-off local build).**

```powershell
$env:KODA_KEYSTORE = "$env:USERPROFILE\.android-keys\koda-fork.jks"
$env:KODA_KEYSTORE_PASSWORD = '<store password>'
$env:KODA_KEY_ALIAS = 'koda-fork'
$env:KODA_KEY_PASSWORD = '<key password>'
```

**Option B: a private properties file.**

Create `%USERPROFILE%\.android-keys\koda-signing.properties` (or `~/.android-keys/koda-signing.properties`):

```properties
storeFile=C:/Users/Eduardo/.android-keys/koda-fork.jks
storePassword=<store password>
keyAlias=koda-fork
keyPassword=<key password>
```

Restrict the file so other users cannot read it:

```powershell
icacls "$env:USERPROFILE\.android-keys\koda-signing.properties" /inheritance:r /grant:r "$($env:USERNAME):(R,W)"
```

On macOS or Linux: `chmod 600 ~/.android-keys/koda-signing.properties`.

**The init script.**

Create `%USERPROFILE%\.gradle\init.d\koda-signing.gradle` (or `~/.gradle/init.d/koda-signing.gradle`):

```groovy
import java.util.Properties

def kodaRoot = new File('C:/Users/Eduardo/Documents/Projetos/Koda').canonicalFile

def signingValues = { settings ->
    def home = System.getProperty('user.home')
    def propsFile = new File(home, '.android-keys/koda-signing.properties')
    def fileProps = new Properties()
    if (propsFile.isFile()) {
        propsFile.withInputStream { fileProps.load(it) }
    }
    [
        storeFile    : System.getenv('KODA_KEYSTORE') ?: fileProps.getProperty('storeFile') ?: "${home}/.android-keys/koda-fork.jks",
        storePassword: System.getenv('KODA_KEYSTORE_PASSWORD') ?: fileProps.getProperty('storePassword'),
        keyAlias     : System.getenv('KODA_KEY_ALIAS') ?: fileProps.getProperty('keyAlias') ?: 'koda-fork',
        keyPassword  : System.getenv('KODA_KEY_PASSWORD') ?: fileProps.getProperty('keyPassword'),
    ]
}

gradle.beforeSettings { settings ->
    if (settings.rootDir.canonicalFile != kodaRoot) return

    def values = signingValues(settings)
    if (!values.storePassword || !values.keyPassword) {
        logger.warn('[koda-signing] No credentials; release signing left untouched.')
        return
    }

    settings.gradle.startParameter.projectProperties =
        settings.gradle.startParameter.projectProperties + [
            'android.injected.signing.store.file'    : values.storeFile.replace('\\', '/'),
            'android.injected.signing.store.password': values.storePassword,
            'android.injected.signing.key.alias'     : values.keyAlias,
            'android.injected.signing.key.password'  : values.keyPassword,
        ]
}

gradle.beforeProject { project ->
    if (project.path != ':app' || project.rootDir.canonicalFile != kodaRoot) return

    project.afterEvaluate {
        def android = project.extensions.findByName('android')
        def release = android?.buildTypes?.findByName('release')
        def signing = android?.signingConfigs?.findByName('release')
        if (release == null || signing == null) return

        def values = signingValues(project)
        if (!values.storePassword || !values.keyPassword) return

        signing.storeFile = new File(values.storeFile)
        signing.storePassword = values.storePassword
        signing.keyAlias = values.keyAlias
        signing.keyPassword = values.keyPassword
        release.signingConfig = signing
    }
}
```

What it does:

- `beforeSettings` injects real `-P`-style project properties, which is what AGP's `SigningOptions` reads. This is the primary path and it overrides the build type's signing config.
- `beforeProject`/`afterEvaluate` reconfigures the existing `release` signing config as a safety net if a future AGP stops reading the injected properties.
- The root-directory check means the script does nothing for any other project, even though it lives in the global init directory.
- With no credentials it logs `[koda-signing] No credentials; release signing left untouched.` and leaves the normal build alone.

**Two traps that were hit while setting this up:**

1. **The file must not start with a UTF-8 BOM.** Groovy rejects it with `Unexpected character: '﻿'`. If you create the file with Windows PowerShell 5.1's `Set-Content -Encoding UTF8`, it writes a BOM. Use `[IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`, or save as "UTF-8 without BOM" in an editor.
2. **`gradle.logger` is not available inside `beforeSettings` in Gradle 9.** Use `logger.warn(...)`, which is the script's own logger, as the snippet above does.

The repository path is hardcoded in `kodaRoot`. If you move the clone, update it.

**Optional fallback (last resort only).** If a future Gradle/AGP combination refuses the init script entirely, append one line to `app/build.gradle.kts`:

```kotlin
apply(from = rootProject.file("signing/fork-signing.gradle"))
```

and create `signing/fork-signing.gradle`:

```groovy
android.signingConfigs.getByName('release').with {
    storeFile = file(System.getenv('KODA_KEYSTORE') ?: "${System.getProperty('user.home')}/.android-keys/koda-fork.jks")
    storePassword = System.getenv('KODA_KEYSTORE_PASSWORD')
    keyAlias = System.getenv('KODA_KEY_ALIAS') ?: 'koda-fork'
    keyPassword = System.getenv('KODA_KEY_PASSWORD')
}
android.buildTypes.getByName('release').signingConfig =
    android.signingConfigs.getByName('release')
```

This edits an upstream file and creates a fork-only script that must be added to `custom`. It is not expected to be needed. If you use it, also update the release workflow's guard/merge practice so the line is preserved across upstream merges.

**Build and verify a local signed release.**

```powershell
.\gradlew.bat :app:assembleRelease
```

Find the newest build-tools and verify the output:

```powershell
# ANDROID_HOME if set; otherwise use the sdk.dir path from local.properties.
# In this checkout local.properties says C:\Users\Eduardo\AppData\Local\Android\Sdk.
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = 'C:\Users\Eduardo\AppData\Local\Android\Sdk' }
$bt = Get-ChildItem "$sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs `
  app\build\outputs\apk\release\app-universal-release.apk
```

`apksigner` must print `Verifies` and a signer certificate. Its SHA-256 must match `keytool -list -v`. If the two differ, the injected properties did not win and the build used another key.

`minSdk = 30`, so v2/v3 signatures are what matter; v1 may report `false` without consequence.

If you changed the keystore or password and Gradle reuses a cached configuration, run once with `--no-configuration-cache` or `--rerun-tasks`.

### 2.7 GitHub Actions setup

Fork repositories start with workflows disabled and no secrets. Both must be handled.

1. Open the fork on GitHub, go to **Actions**, and enable workflows if GitHub asks. A fork shows "Workflows aren't being run on this forked repository" until you confirm.
2. Under **Settings > Actions > General > Workflow permissions**, allow **Read and write permissions**. The workflow declares `permissions: contents: write`, but the repository policy must allow granting it; otherwise `gh release create` fails with `Resource not accessible by integration`.
3. Add the four repository secrets (`Settings > Secrets and variables > Actions`, or the CLI below).

**Generate `KEYSTORE_BASE64`.** Any base64 form of the `.jks` bytes works; do not add line breaks or a BOM.

Windows:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android-keys\koda-fork.jks")) |
  Set-Content -NoNewline keystore.b64
Get-Content -Raw .\keystore.b64 | gh secret set KEYSTORE_BASE64 --repo theduardomaciel/Koda
Remove-Item .\keystore.b64
```

macOS:

```bash
base64 -i ~/.android-keys/koda-fork.jks -o keystore.b64
gh secret set KEYSTORE_BASE64 --repo theduardomaciel/Koda < keystore.b64
rm -P keystore.b64 2>/dev/null || rm keystore.b64
```

Linux:

```bash
base64 -w0 ~/.android-keys/koda-fork.jks > keystore.b64
gh secret set KEYSTORE_BASE64 --repo theduardomaciel/Koda < keystore.b64
shred -u keystore.b64
```

**Set the other secrets.** These are short strings; `gh` prompts for the value without echoing it and without leaving it in shell history:

```powershell
gh secret set KEYSTORE_PASSWORD --repo theduardomaciel/Koda
gh secret set KEY_ALIAS --repo theduardomaciel/Koda        # value: koda-fork
gh secret set KEY_PASSWORD --repo theduardomaciel/Koda
```

Confirm all four exist (values are never displayed):

```powershell
gh secret list --repo theduardomaciel/Koda
```

Expected:

```text
KEYSTORE_BASE64
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

**Default branch and `workflow_dispatch`.** The release workflow lives on `custom`. GitHub only offers a `workflow_dispatch` workflow if that workflow file exists on the repository's **default branch**. The fork inherits `beta` as its default branch, which does not contain the workflow. Choose one:

- **Recommended:** set the fork's default branch to `custom` (Settings > General > Default branch, or `gh repo edit theduardomaciel/Koda --default-branch custom`). The branch must exist on `origin` first; push it as shown in section 2.8. The "Run workflow" button then appears, and future releases still work by tag push.
- **Alternative:** release only by pushing tags. Tag-triggered runs do not depend on the default branch, because the workflow file is in the tagged commit.

If you use **Run workflow**, always fill in the `tag` input. The manual path checks out `custom`, but the publish step needs a tag name; without one it stops with an empty `TAG`.

Changing the default branch affects what PRs target on the fork. That is fine for a personal fork, but know the trade-off.

### 2.8 Commit the fork files on `custom`

The fork additions are:

| File | What it does |
|---|---|
| `app/src/main/java/com/ivor/ivormusic/data/ForkConfig.kt` | Holds `GITHUB_REPO = "theduardomaciel/Koda"` so the fork's links and updater never depend on upstream's `BuildConfig`. |
| `app/src/main/java/com/ivor/ivormusic/ui/home/HomeScreen.kt` | Launch/resume update check uses `ForkConfig.GITHUB_REPO`. |
| `app/src/main/java/com/ivor/ivormusic/ui/settings/UpdateScreen.kt` | Manual update check uses `ForkConfig.GITHUB_REPO`. |
| `app/src/main/java/com/ivor/ivormusic/ui/settings/SettingsScreen.kt` | GitHub link uses `ForkConfig.GITHUB_REPO`; the developer profile link intentionally still uses upstream's `BuildConfig.GITHUB_USERNAME`. |
| `app/src/main/java/com/ivor/ivormusic/ui/report/ReportBugScreen.kt` | New-issue link uses `ForkConfig.GITHUB_REPO`. |
| `.github/workflows/fork-release.yml` | Signed release build and publication. |
| `docs/fork-releases.md` | This document. |

`BuildConfig.GITHUB_REPO` in `app/build.gradle.kts` is deliberately **not** changed. It still says `ivorisnoob/Koda`; leaving it alone is what keeps the build file conflict-free when upstream changes it.

Check that nothing else still points the user-facing links at upstream:

```powershell
git grep -n "BuildConfig.GITHUB_REPO"
```

Every hit must be a non-user-facing use or already replaced. If upstream adds a new call site, replace that one with `ForkConfig.GITHUB_REPO` too.

Commit with the repository's commit rules (`CLAUDE.md`): a commit that changes the APK ends with a `Changelog:` section of user-visible `-` bullets; a pure CI/docs change does not. Example for the fork configuration commit:

```text
Wire the fork's update checks and release links

Changelog:
- Updates and GitHub links now point at this fork's releases and issues.
```

The workflow and this document can be a separate CI/docs commit without a changelog.

Then push the branch so the fork repository has it:

```powershell
git push -u origin custom
```

Do not push `beta` unless you want a mirror backup on the fork; it is not needed for releases.

---

## 3. Cutting the first release

### 3.1 Pre-flight

Run these on `custom` with a clean working tree:

```powershell
git checkout custom
git status --short                      # must be empty
git merge-base --is-ancestor upstream/beta HEAD; echo $LASTEXITCODE   # 0
git diff --stat upstream/beta...custom -- "*.gradle.kts" "gradle/libs.versions.toml"
```

The last command must print nothing: no build-file divergence means the next upstream merge stays cheap. If it prints files, stop and understand why before releasing.

Confirm the version the app will report. Upstream keeps literal values in `app/build.gradle.kts`:

```powershell
Select-String -Path app/build.gradle.kts -Pattern "versionCode =|versionName ="
```

At the time of writing: `versionCode = 30`, `versionName = "5.2"`. The fork does not override either. The workflow enforces `versionCode(custom) >= versionCode(upstream/beta)`.

Compile before tagging:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

### 3.2 Choose the tag

The in-app updater does **not** use SemVer. It extracts every run of digits from the tag and compares them left-to-right, treating missing parts as `0`. That is why fork tags use four numeric components:

| Upstream `versionName` | Fork tags | Why |
|---|---|---|
| `5.2` | `v5.2.0.1`, `v5.2.0.2`, … | `[5,2,0,1]` is newer than the installed `5.2` (`[5,2,0,0]`). |
| `5.2.1` | `v5.2.1.1`, `v5.2.1.2`, … | `[5,2,1,1]` is newer than `5.2.1` and also newer than any `v5.2.0.x`. |
| `5.10` | `v5.10.0.1` | Numeric comparison, so `5.10` is greater than `5.9`. |

Rules:

- Pad a two-part upstream version with `0` for the patch, then append the fork build number.
- Start `forkBuild` at `1`. A tag of `v5.2.0` extracts `[5,2,0]`, which equals the installed `5.2` and would not prompt.
- Never reuse a tag. GitHub rejects a duplicate tag, and reusing one makes the updater's history confusing.
- Never go backwards. If you accidentally tag `v5.2.0.2` before `v5.2.0.1`, skip `v5.2.0.1`.
- Do **not** use suffixes such as `v5.2-fork.1`: the digits extract as `[5,2,1]`, which looks like upstream `5.2.1` and breaks the ordering the four-component scheme guarantees.
- If upstream ever ships a four-component `versionName`, stop and reassess the scheme before tagging.

### 3.3 Tag and push

```powershell
git checkout custom
git tag -a v5.2.0.1 -m "Koda v5.2.0.1"
git push origin v5.2.0.1
```

Push the branch too if it is not on `origin` yet:

```powershell
git push -u origin custom
```

The tag push triggers `.github/workflows/fork-release.yml`. The workflow:

1. Checks out the pushed tag with full history.
2. Fetches `upstream/beta` and asserts it is an ancestor of the tagged commit.
3. Asserts `versionCode` in `custom` is greater than or equal to upstream's.
4. Decodes `KEYSTORE_BASE64` into `$RUNNER_TEMP/keys/koda-fork.jks`.
5. Runs `:app:assembleRelease` with the four injected signing properties.
6. Runs `apksigner verify --print-certs`.
7. Creates a non-prerelease, latest GitHub Release with the three APKs and generated notes.

The workflow only runs in the fork (`if: github.repository == 'theduardomaciel/Koda'`), so it cannot consume secrets in upstream's repository.

### 3.4 Watch and verify the run

```powershell
gh run list --repo theduardomaciel/Koda --workflow fork-release.yml --limit 5
gh run watch --repo theduardomaciel/Koda
```

If it fails, read the failing step: `gh run view <run-id> --repo theduardomaciel/Koda --log-failed`.

Verify the release:

```powershell
gh release view v5.2.0.1 --repo theduardomaciel/Koda
gh release view v5.2.0.1 --repo theduardomaciel/Koda --json isPrerelease,isDraft,assets `
  --jq '{prerelease: .isPrerelease, draft: .isDraft, assets: [.assets[].name]}'
```

Expected: `prerelease: false`, `draft: false`, and the three APK names. The release must be non-prerelease and marked latest, otherwise `/releases/latest` will not return it and the in-app updater will not see it.

Optionally download and verify the published APK locally:

```powershell
gh release download v5.2.0.1 --repo theduardomaciel/Koda --pattern "app-universal-release.apk"
$bt = Get-ChildItem "$env:ANDROID_HOME\build-tools" | Sort-Object Name | Select-Object -Last 1
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs .\app-universal-release.apk
```

**Add the `## Highlights` section to the release body.** The workflow uses `--generate-notes`, which produces a "What's Changed" list but no Highlights heading. The update prompt on an older install reads only the bullets under `## Highlights` from the release body; without that heading it shows a one-line "no highlights" message instead (the update still works). After the run, edit the release:

```markdown
## Highlights

- One user-visible reason to update.
- Another one.
- At most six; three to six reads best.
```

```powershell
gh release edit v5.2.0.1 --repo theduardomaciel/Koda --notes-file .\release-notes.md
```

`--notes-file` **replaces** the body. Keep the generated "What's Changed" sections below the Highlights block if you want the full list; otherwise the release carries only the highlights. Generated notes are raw commit titles, so rewrite anything internal before users read it; editing the release afterwards is fine and changes nothing about the APK or the updater.

The sheet shown after updating is different: it reads `app/src/main/assets/release-notes/<versionName>.md` from inside the APK. Fork builds keep upstream's `versionName`, so `5.2.md` is upstream's file and fork-only changes do not appear in that post-update sheet. That is intentional: the fork does not edit upstream's assets, and the update prompt's Highlights section is where fork-specific notes belong.

### 3.5 Install on a phone for the first time

The fork is signed with a different key than upstream's build. Android will refuse to install it over an existing original install. One uninstall is unavoidable.

Before uninstalling, read section 6. In short:

1. Original app: Settings > Backup, create a backup to Downloads or cloud, and verify the zip.
2. Copy out anything the backup does not carry: app-only downloads (under app storage), profile photos, downloaded captions.
3. Uninstall `com.ivor.ivormusic`.
4. Install a fork APK: `adb install app-arm64-v8a-release.apk` (or the universal build).
5. Restore the backup in Settings > Backup.
6. Sign in to YouTube and Last.fm again.

After that first install, every fork-to-fork update keeps data as long as it is signed with the same key and has an equal or higher `versionCode`.

### 3.6 Verify the updater

The updater is driven by the app, not by GitHub Actions. To test it:

1. Install a fork release.
2. Publish a newer tag (for example `v5.2.0.2`) with a non-prerelease latest release.
3. Launch or resume the app. Home rechecks at most every six hours, and the repository caches a check for ten minutes; Settings > About > Software Update has a manual check that forces a refresh.
4. Confirm the dialog names the fork release. The automatic launch/resume check is disabled in debug builds (`BuildConfig.DEBUG`); the manual check on Software Update works there, but test the real flow with a release APK.

The app never downloads or installs the APK itself. It opens the asset in the browser/system downloader and the package installer takes over, which is also where a signature mismatch would surface.

---

## 4. Future updates

### 4.1 When upstream ships a new version

Do this on a clean working tree. Commit fork-only work first; do not carry unrelated changes through a merge.

```powershell
git fetch upstream
git checkout beta
git merge --ff-only upstream/beta
```

If `--ff-only` fails, `beta` has diverged. Investigate before doing anything else; `beta` must stay a pure mirror. Do not force-reset unless you are certain there is no work on it.

Then update the stack and the release branch:

```powershell
git checkout feat/liked-songs-cache
git merge beta

git checkout feat/account-library
git merge feat/liked-songs-cache

git checkout custom
git merge feat/liked-songs-cache
git merge feat/account-library
```

Handling conflicts:

- `rerere` will replay previous resolutions where the conflict shape is identical. Always inspect the result; a replayed resolution can be wrong when the surrounding code changed.
- Resolve, `git add` the resolved files, then `git commit --no-edit` to finish the merge.
- Build files (`app/build.gradle.kts`, `gradle/libs.versions.toml`) have historically not conflicted because the fork does not change them. If one appears, keep upstream's version values and resolve the rest on its merits.
- `ROADMAP.md`'s Shipped section is a common conflict: keep both sides' new entries under the existing month heading.
- `HomeViewModel.kt`, `MusicPlaylists.kt` and `LibraryScreen.kt` are the most merge-active files because both the feature branches and upstream change them. Read both sides; do not blindly prefer one.

After every upstream merge:

```powershell
git grep -n "BuildConfig.GITHUB_REPO"
git grep -n "class ForkConfig"
.\gradlew.bat :app:compileDebugKotlin
```

Re-point any new user-facing `BuildConfig.GITHUB_REPO` call site at `ForkConfig.GITHUB_REPO`. Confirm `ForkConfig.kt` was not dropped.

Then check what upstream changed about versions:

```powershell
Select-String -Path app/build.gradle.kts -Pattern "versionCode =|versionName ="
git diff upstream/beta...custom -- "*.gradle.kts" "gradle/libs.versions.toml"
```

The diff must stay empty. The fork never bumps `versionCode` or `versionName` on its own; upstream's numbers are the base, and the fork build number lives in the tag.

### 4.2 Adding fork-only work

Fork-only work goes on `custom` or on a short-lived branch cut from `custom`:

```powershell
git checkout custom
git checkout -b fork/my-change
# commit, test, merge back
git checkout custom
git merge fork/my-change
```

Keep the change isolated from upstream files where possible. Anything that edits `app/build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/build.yml` or `CLAUDE.md` will conflict on the next upstream merge, so prefer a new file or a small call-site change.

If the change replaces one more `BuildConfig.GITHUB_REPO` use, update `ForkConfig` instead of editing `BuildConfig`.

### 4.3 Work that should go upstream

Feature work intended for upstream must not depend on fork-only files:

```powershell
git checkout -b feat/my-feature beta
# implement, merge upstream/beta as it moves
git push origin feat/my-feature
gh pr create --repo Ivorisnoob/Koda --base beta
```

Once it lands upstream, it arrives on the next `beta` fast-forward and flows into `custom`. Do not merge `custom` into a feature branch; keep it PR-ready.

### 4.4 Release numbering over time

Examples, assuming upstream ships one version at a time:

| Situation | Next tag |
|---|---|
| Upstream still `5.2`, first fork release | `v5.2.0.1` |
| Upstream still `5.2`, second fork release | `v5.2.0.2` |
| Upstream `5.2.1` | `v5.2.1.1` |
| Upstream `5.2.1`, second fork release | `v5.2.1.2` |
| Upstream `5.10` | `v5.10.0.1` |

If you cherry-pick a fix onto an older base, the tag must still be higher than everything already published. When in doubt, increment `forkBuild` on the newest base rather than inventing a branch of the version tree.

### 4.5 Pre-release checklist

Run through this every time:

- [ ] `git status --short` is clean.
- [ ] `git merge-base --is-ancestor upstream/beta custom` returns 0.
- [ ] `git diff --stat upstream/beta...custom -- "*.gradle.kts" "gradle/libs.versions.toml"` is empty.
- [ ] `ForkConfig.GITHUB_REPO` points at `theduardomaciel/Koda`, and `git grep` shows no user-facing `BuildConfig.GITHUB_REPO` left.
- [ ] `versionCode` and `versionName` match upstream's values.
- [ ] `.\gradlew.bat :app:compileDebugKotlin` passes.
- [ ] The new tag is numerically higher than the previous tag and uses four components.
- [ ] The user-visible highlights for the fork are written (they go into the release body, not an upstream asset).
- [ ] The keystore backup and passwords are available.
- [ ] All four GitHub secrets exist (`gh secret list`).
- [ ] Workflows are enabled and have write permission.
- [ ] The release will be non-prerelease and latest (the workflow does this by default; do not edit the release into a draft or pre-release).

### 4.6 Post-release checklist

- [ ] The workflow run is green.
- [ ] The release is published, non-prerelease, and marked latest.
- [ ] The three APK assets are attached.
- [ ] The release body has a `## Highlights` section (or you deliberately accept the "no highlights" line).
- [ ] `apksigner verify` on a downloaded APK reports the expected certificate.
- [ ] Install the release over the previous fork build without uninstalling; data is intact.
- [ ] The in-app manual update check sees the new tag.
- [ ] Install on a clean secondary device (or after an uninstall) and restore a backup, if anything in the build affects startup or migration.

---

## 5. How the in-app updater behaves

This is the behavior the release setup has to satisfy. The implementation is `data/UpdateRepository.kt`.

- Endpoint: `https://api.github.com/repos/theduardomaciel/Koda/releases/latest`.
- GitHub's `/releases/latest` **excludes drafts and pre-releases**. A fork release marked pre-release is invisible; the release must be published and non-prerelease.
- Version comparison: strip one leading `v`/`V`, extract all digit runs, compare left to right, missing parts are `0`. It is not SemVer. `5.2` equals `5.2.0`; `5.2.1` is newer than `5.2`; `5.2.0.1` is newer than `5.2`.
- Asset selection: exact ABI match first (`arm64-v8a`, `armeabi-v7a`), then simplified (`v8a`, `v7a`, `arm64`), then a `universal` asset. It never picks an APK for a different architecture. If nothing matches, it falls back to opening the release page.
- The app does not download or verify the APK. It opens the URL and Android's package installer runs. The app holds no `REQUEST_INSTALL_PACKAGES`.
- Check frequency: on launch/resume at most once every six hours, with a ten-minute in-process cache; the manual check forces a refresh. The automatic check is disabled in debug builds; the manual one is not.
- The dialog's notes come from the GitHub release body, which `--generate-notes` fills from the commits and merged PRs. Edit the release after publication if you want curated notes; editing does not invalidate anything.
- If the app stays pointed at upstream (for example, a `ForkConfig` regression), it will eventually offer an upstream-signed APK. The install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the app cannot detect this.

---

## 6. Data, uninstall and backups

The first switch from upstream's build to the fork requires an uninstall because the signing certificate differs. This is the only time fork users lose data. After that, updates are normal package upgrades.

In-app backup: Settings > Backup. It writes a zip you can keep in Downloads, cloud storage or share elsewhere; restoring replaces app data and restarts the process.

Backed up:

- Device-wide preferences: theme, palette, player style, saved and hidden playlists, loudness data, icon styles.
- Playlist directories and covers, video playlists, `audio_profiles.json`.
- The profile roster, and per profile: subscriptions and groups, hidden videos/blocked channels, watch history and resume positions, play history, liked IDs and metadata, search history, taste profile, upload mutes.

Not backed up:

- YouTube cookies (`yt_music_session`) and the anti-bot visitor identity (the latter re-mints).
- The Last.fm session.
- The account Liked Music cache (`account_liked_songs*.json`); it re-syncs after sign-in.
- Download metadata, playback session files, downloaded captions, custom profile photos.
- Caches and backup bookkeeping.

Downloads:

- Public downloads live in `Download/Koda/Music` and `Download/Koda/Video`. The files survive uninstall, but the app's index does not, so they may not appear as Koda downloads until re-imported.
- App-only downloads live under app-private storage and are removed with the app.
- Copy out app-only downloads, profile photos and captions before uninstalling if you need them.

Android Auto Backup is enabled, but it excludes the encrypted session and Last.fm preferences and is not a reliable bridge across a signing-key change. Do not rely on it.

Exact procedure to move from the original app to the fork:

1. Create and verify an in-app backup.
2. Copy out anything from the "not backed up" list that matters.
3. Uninstall `com.ivor.ivormusic`.
4. Install the fork APK.
5. Restore the backup.
6. Sign in to YouTube and Last.fm again.
7. Verify likes, playlists, downloads and profile data.

Fork-to-fork updates:

- In-app updater: download the APK, install, keep data, provided the signature is the same and the version is equal or higher.
- `adb install -r app.apk`: works with an equal or higher `versionCode`. A lower code fails with `INSTALL_FAILED_VERSION_DOWNGRADE`.
- A build signed with a different key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the only fix is an uninstall, which loses the data listed above unless it was backed up.

Debug builds use `applicationIdSuffix = ".debug"`, so they install beside the release app and never touch its data.

---

## 7. Troubleshooting

### The release workflow never appears under Actions

- Workflows are disabled for the fork. Open Actions and enable them.
- `workflow_dispatch` needs the workflow file on the default branch. Either set the default branch to `custom`, or trigger by pushing a tag. Tag runs do not need the default branch.

### `Resource not accessible by integration` when creating the release

The token cannot write contents. Set **Settings > Actions > General > Workflow permissions** to read and write, then re-run the workflow.

### "Could not get unknown property 'logger' for build ':'"

The init script uses `gradle.logger` inside `beforeSettings`. Use `logger.warn(...)` as shown in section 2.6.

### "Unexpected character: '\ufeff'" from the init script

The file has a UTF-8 BOM. Rewrite it without a BOM; see the trap note in section 2.6.

### `[koda-signing] No credentials; release signing left untouched.`

The init script ran but found no password. Export the `KODA_*` variables or create `~/.android-keys/koda-signing.properties`. The release build then uses the repository's signing config and may fail or produce an unsigned/incorrectly signed APK.

### The APK is signed with the wrong key

Compare `apksigner verify --print-certs` with `keytool -list -v`. If they differ, the injected properties did not take effect. Confirm the init script path, the `kodaRoot` path, that the passwords are set, and run once with `--no-configuration-cache`.

### Guard fails: `upstream/beta` is not an ancestor

The tagged commit does not contain the current upstream `beta`. Merge upstream and tag again:

```powershell
git fetch upstream
git checkout beta
git merge --ff-only upstream/beta
git checkout custom
git merge beta
```

Then create a new tag. Do not move a tag that has already been pushed without understanding the consequences; the run has already used it.

### Guard fails on `versionCode`

`custom` has a lower `versionCode` than upstream. Merge upstream (which carries the bump) and retry. The fork never overrides `versionCode`.

### `KEYSTORE_BASE64` decodes to an empty or invalid file

Re-set the secret from the `.jks` bytes, with no extra whitespace and no BOM. Test the round trip locally:

```powershell
$b64 = Get-Content -Raw .\keystore.b64
[IO.File]::WriteAllBytes(".\roundtrip.jks", [Convert]::FromBase64String($b64))
keytool -list -keystore .\roundtrip.jks -alias koda-fork
```

### `actions/checkout@v7` or `actions/setup-java@v6` cannot be resolved

Pin whatever major versions the marketplace currently provides:

```powershell
gh api repos/actions/checkout/releases/latest --jq .tag_name
gh api repos/actions/setup-java/releases/latest --jq .tag_name
```

Update the `uses:` lines, commit on `custom`, and push a new tag (or re-run after updating the tag's commit).

### APK paths in the release step do not exist

List what the build actually produced:

```powershell
Get-ChildItem app\build\outputs\apk\release
```

Adjust the three paths in `.github/workflows/fork-release.yml`. The split configuration builds `arm64-v8a`, `armeabi-v7a` and a universal APK; if AGP changes the output names, the workflow must follow.

### `gh release create` says the release already exists

Delete and recreate, or use a new tag:

```powershell
gh release delete v5.2.0.1 --repo theduardomaciel/Koda --yes
git push origin :refs/tags/v5.2.0.1   # only if you intend to delete the tag too
```

Prefer a new `forkBuild` number over deleting a published release.

### The in-app updater says "up to date"

Check, in order:

1. The release is published, non-prerelease, and marked latest.
2. The tag's digits are higher than `BuildConfig.VERSION_NAME` (for example `v5.2.0.1` versus `5.2`).
3. You are testing a release build, not a debug build.
4. Open Settings and use the manual check to bypass the ten-minute cache; the automatic check is throttled to six hours.
5. `ForkConfig.GITHUB_REPO` is still `theduardomaciel/Koda`, not upstream.

### The app installs the download but Android rejects it

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the APK is signed with a different key. Uninstall the installed app (back up first) or install the correct signed build.
- `INSTALL_FAILED_VERSION_DOWNGRADE`: the APK's `versionCode` is lower than the installed one. Publish a higher version, or uninstall (which loses data).

### The release build fails only in CI, not locally

CI builds on Ubuntu with Temurin 17 and no local init script. It passes the signing properties as `-P` flags. Read the failing step with `gh run view --log-failed`. Common causes are missing secrets, the version guard, or an output path that changed.

---

## 8. Security rules

- Never commit `koda-fork.jks`, `koda-signing.properties`, base64 dumps, or the passwords. The keystore lives outside the repository; the properties file is private.
- Never paste the keystore or a base64 dump into chat, an issue, a PR, or a build log. `gh secret set` reads from stdin.
- Keep the keystore in two offline/encrypted backups and the passwords in a password manager. The key is the app's identity.
- Rotating the key is a user-visible breaking change: every installed fork build must be uninstalled and reinstalled. Treat a key compromise as a decision that needs a migration plan, not a routine rotation.
- Fork pull requests cannot read repository secrets. The release workflow only triggers on tags pushed by maintainers or on manual dispatch, so it does not expose secrets to contributor code. Do not add `pull_request` triggers to it.
- If you ever add a step that runs arbitrary repository code with the signing secrets, remember that anyone who can push a tag to the fork can run it.

---

## 9. Command reference

### Daily branch work

```powershell
git fetch upstream
git checkout beta
git merge --ff-only upstream/beta
git checkout feat/liked-songs-cache
git merge beta
git checkout feat/account-library
git merge feat/liked-songs-cache
git checkout custom
git merge feat/liked-songs-cache
git merge feat/account-library
```

### Local signed release

```powershell
$env:KODA_KEYSTORE = "$env:USERPROFILE\.android-keys\koda-fork.jks"
$env:KODA_KEYSTORE_PASSWORD = '<store password>'
$env:KODA_KEY_ALIAS = 'koda-fork'
$env:KODA_KEY_PASSWORD = '<key password>'
.\gradlew.bat :app:assembleRelease
```

### Verify a build

```powershell
$bt = Get-ChildItem "$env:ANDROID_HOME\build-tools" | Sort-Object Name | Select-Object -Last 1
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs `
  app\build\outputs\apk\release\app-universal-release.apk
keytool -list -v -keystore "$env:USERPROFILE\.android-keys\koda-fork.jks" -alias koda-fork
```

### Publish

```powershell
git checkout custom
git status --short
git tag -a v5.2.0.1 -m "Koda v5.2.0.1"
git push origin custom
git push origin v5.2.0.1
gh run watch --repo theduardomaciel/Koda
gh release view v5.2.0.1 --repo theduardomaciel/Koda
```

### Secrets

```powershell
gh secret list --repo theduardomaciel/Koda
gh secret set KEYSTORE_BASE64 --repo theduardomaciel/Koda   # pipe the base64 in; see section 2.7
gh secret set KEYSTORE_PASSWORD --repo theduardomaciel/Koda
gh secret set KEY_ALIAS --repo theduardomaciel/Koda
gh secret set KEY_PASSWORD --repo theduardomaciel/Koda
```

### Inspect the fork state

```powershell
git branch -vv
git merge-base --is-ancestor upstream/beta custom; echo $LASTEXITCODE
git diff --stat upstream/beta...custom -- "*.gradle.kts" "gradle/libs.versions.toml"
git grep -n "BuildConfig.GITHUB_REPO"
Select-String -Path app/build.gradle.kts -Pattern "versionCode =|versionName ="
```

---

## 10. Why the setup looks like this

Short answers to the questions the setup usually raises:

- **Why not change `BuildConfig.GITHUB_REPO` in `app/build.gradle.kts`?** It is an upstream file. Changing it conflicts on every upstream version bump. `ForkConfig` plus four one-line call sites keeps the merge surface at zero.
- **Why not put the signing config in `app/build.gradle.kts`?** Same reason, plus the key path and passwords would end up in a file that is meant to be publishable. AGP's injected signing properties keep both out of the repository.
- **Why keep upstream's `versionCode` and `versionName`?** Upstream's numbers are the base users understand, and the fork build number lives in the tag where the updater can compare it. Overriding the build file would conflict on every merge and make the guard meaningless.
- **Why four-part tags?** The updater compares digit runs, not SemVer. Four parts make a fork build strictly newer than its upstream base and strictly older than the next upstream version.
- **Why non-prerelease releases?** `/releases/latest` ignores pre-releases and drafts, so a pre-release is invisible to the updater.
- **Why is the fork release workflow separate from `build.yml`?** `build.yml` stays untouched and keeps building upstream's artifacts. The fork workflow only runs for the fork repository, owns the signing secrets, and publishes the releases the updater reads.
