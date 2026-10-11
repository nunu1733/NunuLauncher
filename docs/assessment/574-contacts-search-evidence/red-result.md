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
