<!-- blueprints/ci-pipeline.md: GitHub Actions build, test, and artifact workflow. -->

# CI pipeline

## Outcome or responsibility

GitHub Actions builds and tests the Android app and the Windows helper on every work-branch push and publishes installable artifacts. It is the only build authority; nothing is built locally.

## Current verified status

**Status:** Implemented

The workflow exists. Work-branch pushes run only the two Windows jobs, both pass, and they publish the APK artifact and the helper zip artifact.

## Architecture dependencies

- [Verification boundaries](../architecture.md#verification-boundaries): CI runs every unit-test suite named there; hardware acceptance stays manual.

## Local rules and implications

- Triggers: `push` to any branch except `main`, and `workflow_dispatch` with a boolean `all_os` input. No `pull_request` trigger and no `push` to `main`.
- Branch runs use only `windows-latest`:
  - Android: Gradle `assembleDebug assembleRelease testDebugUnitTest lintDebug`; uploads the debug and unsigned release APKs as `padlink-android`, plus test and lint reports on failure.
  - Helper: `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo build --release`; downloads Google platform-tools and uploads `PadLinkHelper-windows.zip` (exe plus `platform-tools/`) as `padlink-helper-windows`.
- A newer push to the same branch cancels the older run.
- macOS and Linux jobs run only the `padlink-protocol` crate tests, and only for a manual dispatch on `main` with `all_os` enabled.
- Gradle runs through `gradle/actions/setup-gradle` with a pinned Gradle version, so no wrapper JAR is committed.

## Related blueprints

### Required

None

### Impact checks

None

## Relevant implementation and tests

- `.github/workflows/ci.yml`

## Acceptance or verification criteria

- [x] A work-branch push runs only Windows jobs and both succeed.
- [x] Artifacts contain the debug APK and the helper zip with adb.

## Remaining gaps and unknowns

- The release APK is unsigned; signing configuration is out of scope.
- The manual `all_os` dispatch on `main` has not been exercised.
