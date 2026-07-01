# Releasing & LSPosed Distribution

Canonical module package: **`io.github.kvmy666.autoexpand`**.
Legacy package **`com.autoexpand.xposed`** is deprecated — do not publish to it.

## Versioning scheme

Single source of truth: `app/build.gradle.kts`.

- `versionName` — human SemVer, e.g. `3.2.1`.
- `versionCode` — monotonic integer derived from versionName as `MAJOR*10000 + MINOR*100 + PATCH`.
  - `3.2.1` → `30201`; `3.2.0` → `30200`; `3.10.4` → `31004`.
  - **Must strictly increase every release** (LSPosed/Android use it to detect updates).
- Git tag format: **`<versionCode>-<versionName>`**, e.g. `30201-3.2.1`. The release
  workflow triggers on tags matching `[0-9]*-*`.
- Release APK asset name: `AutoExpandNotifications-<versionName>.apk`.

To cut a release:
```bash
# 1. bump versionCode + versionName in app/build.gradle.kts, commit
# 2. tag and push
git tag 30201-3.2.1
git push origin 30201-3.2.1
```
The `Build Release APK` workflow then builds, signs, creates the GitHub Release on
the personal repo, and mirrors it to the Xposed org repo (marking it "latest").

## Why LSPosed showed v1.2.1 (root cause — verified June 2026)

It was **not** a versioning bug or a missing pipeline:

- `app/build.gradle.kts` was already correct: `applicationId = io.github.kvmy666.autoexpand`,
  `versionCode 30201`, `versionName 3.2.1`. No `com.autoexpand.xposed` reference exists in code.
- The mirror repo `Xposed-Modules-Repo/io.github.kvmy666.autoexpand` **already has** every
  3.x release up to `30200-3.2.0`, and GitHub's `/releases/latest` correctly returns `30200-3.2.0`.
- BUT **modules.lsposed.org** — the aggregator the in-app Repository reads — still serves
  **v1.2.1** as "Latest Release" (it lists 3.2.0 but doesn't flag it latest).

What happened: the old `5-1.2.1` / `5-1.2.0` releases on the mirror were re-created/edited
with a `created_at` newer than the 3.x backfill, so for a window GitHub flagged **1.2.1** as
"latest". modules.lsposed.org indexed during that window and cached it. The store has been
frozen at 1.2.1 since.

## Fixes applied in this repo
- `.github/workflows/build-release.yml`:
  - Personal-repo release now sets `make_latest: true`.
  - Mirror step now passes `--latest` on create **and** runs
    `gh release edit <tag> --latest --draft=false --prerelease=false` after upload, so every
    publish re-asserts the correct "latest" pointer and this can't regress.

## Manual GitHub steps you (the maintainer) must do

These need org/repo permissions or are one-time cleanups.

1. **Confirm the Actions secret exists** on `kvmy666/-AutoExpandNotifications`:
   `XPOSED_REPO_TOKEN` = a PAT with `contents:write` (repo) scope on
   `Xposed-Modules-Repo/io.github.kvmy666.autoexpand`. Without it, the mirror step is skipped
   (`if: env.GH_TOKEN != ''`). Also confirm `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD` are set.

2. **Clean the stale mirror releases** on `Xposed-Modules-Repo/io.github.kvmy666.autoexpand`
   so 3.x is unambiguously newest. Delete the duplicate old releases (keep the 3.x history):
   ```bash
   gh release delete 5-1.2.1 --repo Xposed-Modules-Repo/io.github.kvmy666.autoexpand --cleanup-tag
   gh release delete 5-1.2.0 --repo Xposed-Modules-Repo/io.github.kvmy666.autoexpand --cleanup-tag
   # then force the newest to latest:
   gh release edit 30200-3.2.0 --repo Xposed-Modules-Repo/io.github.kvmy666.autoexpand --latest
   ```

3. **Ship 3.2.1** (tag `30201-3.2.1` as above). This both publishes the real current version
   and triggers modules.lsposed.org to re-index. After it runs, verify:
   ```bash
   curl -s https://api.github.com/repos/Xposed-Modules-Repo/io.github.kvmy666.autoexpand/releases/latest | grep tag_name
   # expect: 30201-3.2.1
   ```
   Then in the LSPosed app: Repository tab → pull-to-refresh (the aggregator re-scrapes on a
   schedule; the new release nudges it). It may take a few hours for modules.lsposed.org to update.

4. **Deprecate `com.autoexpand.xposed`:**
   - Edit that repo's `README.md`: add a top banner — "⚠️ Deprecated. Moved to
     `io.github.kvmy666.autoexpand`" with a link.
   - Stop publishing to it (the workflow already targets only the io.github mirror).
   - Optionally open an issue on the `Xposed-Modules-Repo` org asking maintainers to archive/remove
     the `com.autoexpand.xposed` repo so it stops appearing in the store.

## Do not
- Change `applicationId` — it is already the canonical package. Changing it makes existing
  installs treat the module as a brand-new app (loses scope/settings, breaks updates).
