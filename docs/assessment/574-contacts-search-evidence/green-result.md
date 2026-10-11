# Issue #574 GREEN verification summary

> Status: observed (diagnostic, local-only). Date: 2026-10-11.
> Base: `33b4c061c55db6edf646a48e6a18570ce771d15f` + uncommitted 10-line
> deletion diff in `ContactsSearchProvider.kt` (account projection/reads/constants
> removal). No head SHA claimed here; exact head after commit will be added by PR owner.
> RED baseline archived in `red-result.md` (3 tests, 1 fail on emulator-5554 before fix).

Oracle: [Issue #574 acceptance](https://github.com/nunu1733/NunuLauncher/issues/574#issuecomment-6108156834).
Scope: #574 only. Epic Phase 3 remaining matrix is still pending (not claimed here).

## Instrumented oracle (new 3 tests, no test changes in this doc step)

Class: `app.lawnchair.search.ContactsSearchProviderDataProjectionTest`
(expected version matches the new 3 tests).

- GREEN emulator-5554 (API 37): XML timestamp `2026-10-11T11:43:36`, 3 tests,
  0 failures, 0 errors, 0 skipped.
- GREEN emulator-5556 (API 36): 3/3 PASS on actual API 36 runtime XML.
  Command took 54 sec (main-measured).
- No CI execution claimed for the new test. Classification stays diagnostic /
  local-only; authoring-gate owner pending review (`test-audit.md`).

## Real search journey (synthetic fixture only)

- Physical API 37 (tegu): search query emits the synthetic contact and tap opens
  system `QuickContactActivity` with the synthetic phone. Verdict PASS.
  Detail: `api37/result.md`.
- Emulator API 36 (emulator-5556): same search/tap to `QuickContactActivity`
  with the synthetic phone. Verdict PASS. Detail: `api36/result.md`.

## Binary identity

- Fixed APK sha256 `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`
  (`Lawnchair.16.Dev.(33b4c06).github.debug.apk`).
- After verification main restored the API 36 original APK
  (`5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278`)
  via `adb install -r`, with on-device hash match.
- Physical baseline restored: `READ_CONTACTS=false`, People pref `false`,
  IME restored to Simeji, HOME left on Nova.

## Regression gates run by main (no fabricated test count)

- `spotlessCheck`: PASS.
- Repo validator: PASS.
- Affected JVM suite
  `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.search.*' --tests 'app.lawnchair.organizer.*' --quiet`: exit 0.
- Limitation: Kotlin LSP not installed (user declined); compiler/Gradle was used
  as the alternative verification instead.

## Next step

Main prepares atomic code/test + evidence commits on user explicit request;
ChatGPT review goes through GitHub next. No review approval or close claimed here.

## Byte identity (PR #575 review `6109082627`, docs-only)

- Prod tested bytes: blob `ebfdc1d38277fd36795790d8c54fcf5b4b095475`, sha256 `8f4e2e0819e26f94ae01f8fa96cea02bb603d2305ce3fc6ce53667c9af306e9a`. Equals commit `51657770` and HEAD; BASE-to-final diff is exactly the 10 deletions captured before commit. VERIFIED.
- Test bytes: blob `0c86cab4e70679f4d64a179feacf95d22811f88e`, sha256 `c21119b4f81a7d8d612828c2d85371bec2eb91a2fcc4be8e1c0e75f4c97b2148` (260 lines; added in `51657770`, identical at HEAD).
- Runtime: historical GREEN XML `2026-10-11T11:43:36` device `emulator-5554` 3/3 (first fix observation, same bytes); API 36 device `emulator-5556` class 3/3 same code. Fixed APK sha256 `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`, built from BASE `33b4c061` full tracked prod tree except the provider 10-line diff (BuildConfig metadata still `33b4c06`); not a clean-tree SHA. Full packet: `byte-identity.md`.
- Acceptance GREEN is the exact-blob rerun `2026-10-11T12:51:07` ([green-rerun.xml](./green-rerun.xml), emulator-5554, 3 tests / 0 failures, 0 errors, 0 skipped): prod `ebfdc1d38277fd36795790d8c54fcf5b4b095475` and test `0c86cab4e70679f4d64a179feacf95d22811f88e` both hash-verified before the run, `git diff --exit-code` clean after.
