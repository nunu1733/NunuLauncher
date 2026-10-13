# Issue #574 byte-identity packet (docs-only, PR #575 review `6109082627`)

Date: 2026-10-11. Head: `c577df5b3f55ca01d935c579a7ba04135135053e`.
Base: `33b4c061c55db6edf646a48e6a18570ce771d15f`.
First code commit: `516577701b23047e6ad70c4e3809dd56134c45fe` (prod fix + new test together).

## Durable Git blob identity (SHA-1 via `git hash-object` / `git ls-tree`)

- Production `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/ContactsSearchProvider.kt`
  - BASE `33b4c061` blob: `cf953262db52c6272acb52cc27a5d0c3dde142b8` (pre-fix).
  - Final blob at `51657770` and HEAD: `ebfdc1d38277fd36795790d8c54fcf5b4b095475`.
  - Working-tree sha256 of final bytes: `8f4e2e0819e26f94ae01f8fa96cea02bb603d2305ce3fc6ce53667c9af306e9a`.
  - Diff BASE to final: exactly 10 deletions (2 projection + 2 read-index + 2 read-value + 2 JSON put + 2 constants), captured before commit; `git diff --stat` shows `10 -` on provider plus `260 insertions` new test.
- Test `tests/organizer-instrumentation/app/lawnchair/search/ContactsSearchProviderDataProjectionTest.kt`
  - Blob at `51657770` and HEAD: `0c86cab4e70679f4d64a179feacf95d22811f88e` (260 lines).
  - Working-tree sha256: `c21119b4f81a7d8d612828c2d85371bec2eb91a2fcc4be8e1c0e75f4c97b2148`.
  - The file did not exist at BASE; it was added in `51657770` and is byte-identical at HEAD.

## Runtime / APK identity

- Fixed APK sha256: `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da` (`Lawnchair.16.Dev.(33b4c06).github.debug.apk`).
- Build basis: full tracked prod tree at BASE `33b4c061` except the provider 10-line deletion diff (uncommitted at build time); BuildConfig/version metadata still `33b4c06`. This is NOT a clean-tree SHA build; no clean SHA is claimed for the APK.
- Current code commit `51657770` carries the same 10 removed lines (proof captured before commit via the BASE-to-final diff above).
- API 36 original priv-app APK backed up before install: sha256 `5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278`.

## Execution records bound to the above

- RED: XML timestamp `2026-10-11T11:34:27`, device `emulator-5554`, 3 tests / 1 failure (`granted_searchEmitsContact_whenAccountColumnsRejectedLikeApi37` at test line 92). Ran on BASE prod blob `cf953262...` + uncommitted new test.
- GREEN API 37: XML timestamp `2026-10-11T11:43:36`, device `emulator-5554`, 3/3 PASS, same test code path.
- GREEN API 36: device `emulator-5556`, class `ContactsSearchProviderDataProjectionTest` 3/3 PASS on actual API 36 runtime, same code (54 sec main-measured).
- Journeys: `api37/result.md` (physical tegu, PASS) and `api36/result.md` (emulator-5556, PASS) both install the fixed APK `260bccda...` and state the `33b4c061` + uncommitted 10-line diff basis with no clean-tree SHA claim.

## 同一test bytesによる確定RED→GREEN再実行（main、2026-10-11）

過去の未コミットtest bytesの一致を推測する代わりに、確定したtest blobを固定して再実行した。

| run | 実行前に確認したproduction blob | 実行前に確認したtest blob | timestamp / XML | 結果 |
|---|---|---|---|---|
| RED rerun | `cf953262db52c6272acb52cc27a5d0c3dde142b8`（BASEと完全一致） | `0c86cab4e70679f4d64a179feacf95d22811f88e` | `2026-10-11T12:48:33` / [red-rerun.xml](./red-rerun.xml) | 3 tests / 1 failure、line 92 `expected:<1> but was:<0>` |
| GREEN rerun | `ebfdc1d38277fd36795790d8c54fcf5b4b095475`（fix/HEADと完全一致） | 同じ `0c86cab4e70679f4d64a179feacf95d22811f88e` | `2026-10-11T12:51:07` / [green-rerun.xml](./green-rerun.xml) | 3 tests / 0 failures、errors/skipped 0 |

各実行の直前に `git hash-object` で上記2ファイルのbytesを確認。REDではproviderだけをBASEと同一bytesへ一時的に戻し、GREENではfixと同一bytesへ復元した。testに変更は加えていない。

```sh
ANDROID_HOME=/Users/nunu/Library/Android/sdk ANDROID_SERIAL=emulator-5554 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.search.ContactsSearchProviderDataProjectionTest --quiet
```

実行後にprovider/testの `git diff --exit-code` 成功（HEADと差分なし）を確認。この再実行はdevice `emulator-5554` のみ。既に取得したAPI36および実機API37の操作証跡を、metadataだけ変わった別APKの新規実測と偽っていない。

## Status

- GREEN prod binding: VERIFIED (tested 10-line diff equals the committed BASE-to-`51657770` diff; final blob `ebfdc1d3...` at `51657770` and HEAD).
- GREEN test / runtime binding: VERIFIED as durable record (test blob `0c86cab4...` + sha256 above; APK `260bccda...`; XML `11:43:36` / `5554`; API 36 `3/3` / `5556`).
- Historical first-RED test identity remains unproven; no literal hash for that old execution is invented. It is **superseded for acceptance by the exact-blob RED→GREEN rerun above**, whose test bytes are identical in both runs and to HEAD.
- Acceptance RED/GREEN byte identity: **VERIFIED by rerun**, production toggles BASE `cf953262...` → fixed `ebfdc1d3...`, test stays `0c86cab4...`.
- RED prod binding: VERIFIED as BASE blob `cf953262...` (unmodified pre-fix tree).
