# High-risk audit: PR #131 cross-profile classification evidence seam (Issue #129)

> Status: accepted
> Audit date: 2026-08-24

- Auditor: independent ZCode session (ox-alpha subagent); implementation performed by a different session
- PR: https://github.com/nunu1733/NunuLauncher/pull/131
- Head SHA: 1f9c86b14c8aefcf51eb6cb3e4befbba90ef67ad
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32678455245
- Criteria: specs/83-production-organization-input-sources/spec.md AC-3, AC-5, AC-6, AC-7
- Criteria: docs/adr/0007-authoritative-organization-policy-sources.md (ADR-0007)

## Scope

監査対象diffは `git diff 51940f3dfc..1f9c86b14c8aefcf51eb6cb3e4befbba90ef67ad` である。監査時点のcheckout HEADは `1f9c86b14c8aefcf51eb6cb3e4befbba90ef67ad` でPR #131のheadと一致し、working treeはcleanであった。変更ファイルは次の2件のみ。

- `lawnchair/src/app/lawnchair/organizer/integration/AndroidClassificationSignalSnapshotSource.kt`: cross-profileのapplication info解決を `appContext.createContextAsUser(user, 0).packageManager.getApplicationInfo(...)` から、launcher-authorizedかつprofile-awareな `LauncherApps.getApplicationInfo(packageName, flags, user)` へ置換した。`LauncherApps` はconstructor引数(既定値は `appContext.getSystemService(LauncherApps::class.java)`)として注入可能になり、test seamを形成する。
- `tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt`: composer経由のregression test `productionComposerComposesEvidenceForEveryAvailableProfileWithoutPrivilegedCrossUserAccess`、adapter直接のregression test `androidEvidenceFailsClosedForRealProfileMissingThePackageInsteadOfFallingBackAcrossProfiles`、helper (`awaitProfileConvergence`, `launchableComponents`, `ProfileFixture`) を追加。

runtime書き込み経路とmigration対象: 本diffはLauncher DBへの書き込み経路、recovery point作成、schema/migrationを一切変更しない。対象はclassification evidenceのplatform読み取りのみであり、composerは引き続きread-onlyである。permission、dependency、通信の追加もない。

## Criteria check

specs/83-production-organization-input-sources/spec.md の受入条件ごとの確認結果。

**AC-3(one canonical capture)— 適合。** 変更後も `AndroidClassificationSignalSnapshotSource` は既存の `ClassificationSignalSnapshotSource` port唯一のproduction実装のままであり、second snapshot sourceやUI DB accessは導入していない。読み取りAPIを権限のあるsurfaceへ差し替えたのみである。composer全体がcanonical captureからevidenceを構成することを、production seam (`ProductionOrganizationInputComposer` + 実writer) 経由のinstrumentation testで確認した。

**AC-5(fail closed)— 適合。** `launcherApps.getApplicationInfo` の戻り値nullは明示的に `return PlatformEvidenceReadResult.Unreadable` となる。対象profileに当該packageが不在の場合に投げられる `PackageManager.NameNotFoundException` およびその他のI/O/platform失敗は既存の外側 `catch (_: Exception)` で捕捉され `PlatformEvidenceReadResult.Unreadable` となる。無効なprofile serialも既存どおりUnreadableである。これにより「読めなかったこと」が有効な空observationへ化けず、typed non-write resultとして上位へ伝わる。このfail-closed挙動をadapter直接で検証するregression testが追加されている。

**AC-6(profile and lock safety)— 適合。** 読み取りは要求された `request.profile` のserialから `userCache.getUserForSerialNumber` / `getSerialNumberForUser` のround-trip検証を経たUserHandleに正確に束縛され、そのprofile外へfallbackする経路は存在しない。availability/lockのcanonical保持は本diffでは変更されておらず、preserved系の既存composer testが本監査のinstrumentation再実行でも通過している。

**AC-7(deterministic composition)— 適合。** S2マッピング (`policy.androidCategoryMapping[info.category]`)、S5のgoogle/system優先順位、evidence行フォーマット `"${item}:${category}:${flags}:${s2}:${s5}"` と `sha256Canonical` によるcanonical digest構成はdiff上いっさい変更されていない(context行として不変)。したがってvalue-equivalent capture + 同一immutable policy bundle identityから得られる `PlatformClassificationEvidence`(identity digestを含む)は従来と同一ルールで決定的に構成される。

docs/adr/0007-authoritative-organization-policy-sources.md との整合: ADR-0007 §2のS2/S5 adapter semantics、§5のimmutable identity/provenance、§7の「unexpected platform evidence read failureはsource failure(fail closed)」に、本diffの置換後挙動は整合する。integration層はadapterにとどまり、第二のpolicy ownerを作らない。§7の「semantically validな不在」と「読み取り失敗」の区別も、null/NameNotFoundExceptionをUnreadableへ落とす現行契約で維持されている。

## Executed test surface

監査sessionで実際に実行したcommandと結果を列挙する。

- `git rev-parse HEAD` → `1f9c86b14c8aefcf51eb6cb3e4befbba90ef67ad` (監査対象SHAと一致)。`git status --porcelain` → 出力なし(clean)。`git branch --show-current` → `issue-129-cross-profile-evidence-seam`。
- `gh pr view 131 --repo nunu1733/NunuLauncher --json labels,title,headRefName` → labelsに `risk: layout-data` を確認(type: bugも付与)。headRefNameは `issue-129-cross-profile-evidence-seam`。
- `gh run view 32678455245 --repo nunu1733/NunuLauncher --json status,conclusion,headSha -q '.status+" "+.conclusion+" "+.headSha'` → `completed success 1f9c86b14c8aefcf51eb6cb3e4befbba90ef67ad`。
- `gh pr checks 131 --repo nunu1733/NunuLauncher` → `final-status` pass、`build-debug-apk` pass、`check-style` pass、`validate-repo-contract` pass、`organizer-unit-tests` pass、`organizer-instrumentation-*`(api35/db-migration/issue52/issue53/issue99/shared-writer)すべてpass。`high-risk-evidence` のみfail(本audit記録の存在が前提のjobであり、audit作成前の時点では想定内)。
- `git diff 51940f3dfc..HEAD` → 上記Scopeの内容を目視review(statは2 files changed, 144 insertions, 4 deletions)。
- `python3 tools/repo-contract/validate_repo_contract.py` → `repository contract OK (/Users/nunu/Documents/work/NunuLauncher)`。
- `python3 tools/repo-contract/test_validate_repo_contract.py` → `Ran 11 tests ... OK`(invalid fixtureに対するFAILED出力はtest suiteが意図的に検証している想定動作)。
- `./gradlew spotlessCheck` → BUILD SUCCESSFUL。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → BUILD SUCCESSFUL。
- `./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest` → BUILD SUCCESSFUL。
- `adb devices` → `emulator-5554	device`。`adb -s emulator-5554 shell pm list users` → users 0 / 10(Issue108) / 11(Issue108) がrunning、current userは0。
- `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.integration.ProductionOrganizationInputInstrumentationTest` → `Starting 10 tests on nunu_qpr2_api36_1(AVD) - 16` / `Finished 10 tests`、BUILD SUCCESSFUL。結果XML `build/outputs/androidTest-results/connected/debug/flavors/lawnWithQuickstepGithub/TEST-nunu_qpr2_api36_1(AVD) - 16-_-lawnWithQuickstepGithub.xml` は `tests="10" failures="0" errors="0" skipped="0"`。testcase一覧に新規2件 `productionComposerComposesEvidenceForEveryAvailableProfileWithoutPrivilegedCrossUserAccess` と `androidEvidenceFailsClosedForRealProfileMissingThePackageInsteadOfFallingBackAcrossProfiles` を含む10件すべてがfailure/errorなし。
- `adb -s emulator-5554 logcat -d -s ProductionOrgInputTest | tail -3` → `ISSUE129_EVIDENCE profiles=0,10 insertedRows=2 systemPackage=true ready=true`(composer testがpersonal 0 とmanaged work 10 の双方にrowを挿入し、各profileのevidence読み取りでReadyになったことを示す)。

## Findings

- **CI単一profileエミュレータでの縮退:** CIのorganizer-instrumentation jobはsingle-profileエミュレータで動くため、新規composer testはaccessible profileがpersonalのみとなり、挿入rowが1行に縮退する(multi-profileのcross-profile性はCI上では検証されない)。multi-profile coverage(personal 0 + managed work 10)は本監査のローカルAVD実行(logcat `profiles=0,10`、insertedRows=2)で補完し、ここに記録する。
- **private space user 11の扱い:** ローカルAVDではuser 11(private space)がlockedのため `isUserUnlocked` 判定でaccessible集合から除外され、evidence requestsの対象にならない。この扱い自体はpreserved系の既存test (`productionComposerPreservesQuietPrivateDisabledAndUnavailableProfileWithoutEvidenceFallback`) が担保しており、本監査のinstrumentation再実行でも通過している。
- **NameNotFoundExceptionの到達点:** `LauncherApps.getApplicationInfo` は対象profileに不在なpackageで `PackageManager.NameNotFoundException` を投げる。production adapterはこれを外側catchで捕捉して `PlatformEvidenceReadResult.Unreadable` とする(戻り値nullも同様)。別profileの同名packageへのfallbackは発生せず、これをadapter直接で検証するregression testが追加されている。
- **監査commit自体について:** 本audit記録の追加はdocs-only commitであり、監査対象Head SHA以降にcode/test変更は発生しない。merge gate (`final-status`) は監査対象SHA上でsuccess済みである。
- **その他:** 本diffにDB書き込み経路・migration・permission・依存の変更はなく、AGENTS.mdのホームレイアウト安全規約に触れる操作は含まれない。発見した障害・要修正事項はない。
