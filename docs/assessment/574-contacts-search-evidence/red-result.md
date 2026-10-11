# Issue #574 RED evidence

> Status: observed. Date: 2026-10-11. Base: `33b4c061c55db6edf646a48e6a18570ce771d15f`.

Owner-approved oracle: [Issue #574 acceptance](https://github.com/nunu1733/NunuLauncher/issues/574#issuecomment-6108156834).

Command (before production modification):

```sh
ANDROID_HOME=/Users/nunu/Library/Android/sdk ANDROID_SERIAL=emulator-5554 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.search.ContactsSearchProviderDataProjectionTest
```

Observed report: `build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/TEST-issue526_api37_pixel_9a(AVD) - 17.xml`, timestamp `2026-10-11T11:34:27`, device `emulator-5554`.

- 3 tests, 1 failure, 0 errors, 0 skipped.
- `granted_searchEmitsContact_whenAccountColumnsRejectedLikeApi37`: **FAIL** at test line 92.

```text
java.lang.AssertionError: API 37 Data view rejects account_type/account_name projections; search must still emit the synthetic contact instead of silently emitting empty expected:<1> but was:<0>
```

- `granted_searchEmitsContact_whenAccountColumnsAllowedLikeApi36`: PASS (API 36-like provider contract, not a physical API 36 runtime result).
- `denied_shortCircuitsWithoutAnyProviderQuery`: PASS (zero provider queries).

The test invokes the public `ContactsSearchProvider.search` seam with a behavioral ContentProvider fixture. It neither queries real contacts nor changes contact permissions. The existing preferences flag is restored after each test.

## Byte identity (PR #575 review `6109082627`, docs-only)

- RED prod source: BASE `33b4c061` blob `cf953262db52c6272acb52cc27a5d0c3dde142b8` (unmodified pre-fix tree). VERIFIED.
- Historical first run (`2026-10-11T11:34:27`, emulator-5554, 3 tests / 1 failure at line 92): ran on the uncommitted new file; exact uncommitted bytes were not reconstructed, so that run stays historical with no invented hash and no PASS-review binding claimed.
- Acceptance RED is the exact-blob rerun `2026-10-11T12:48:33` ([red-rerun.xml](./red-rerun.xml), emulator-5554, 3 tests / 1 failure at line 92 `expected:<1> but was:<0>`): prod `cf953262db52c6272acb52cc27a5d0c3dde142b8` and test `0c86cab4e70679f4d64a179feacf95d22811f88e` both hash-verified before the run, `git diff --exit-code` clean after. Full packet: `byte-identity.md`.
