# Independent audit: PR #382 organizer materials relocation（#367）

> Status: accepted（audit完了）
> Audit date: 2026-09-19
> Verdict: **条件付きGO**（変更内容は受入条件をすべて満たす。mergeは本headでのCI `final-status` green確認を条件とする。詳細は Findings / 最終判定）

- Auditor: 実装を行っていない独立session（実装agent/実装sessionとは別の監査として実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/382
- Head SHA: `e82645b7c9c9751da480d766f574e1524ed2f204`（base `main` = `1285c13cc683b9c49532fec62adfc7d5b0bce6d8`、merge-base一致を確認）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35445985764（監査時点で `completed failure`。job単位の結果は「CI status」節）
- Criteria: [specs/367-organizer-materials-relocation/spec.md](../../specs/367-organizer-materials-relocation/spec.md)（accepted、MAT-AC-01〜08、Contract notes 1/3）、[plan.md](../../specs/367-organizer-materials-relocation/plan.md)、[organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md) §2.3/§3.10/§5/§7.2(b)、organizer-to-be-ux.md §5.1/§5.2（D-01/T-02〜T-06）、Issue #367本文（受入条件2は段階契約へ改訂済み）・全コメント、Issue #370本文（manual row廃止の所有）
- 監査方法: `git diff main...issue-367-materials-relocation` の全diff review、`git grep` による参照確認、GitHub上のreview記録・CI結果の照合。監査者自身はgradle build/testを実行していない（実行表面は実装evidenceとCIで確認）。

## Scope

対象コミット（7件）:

| commit | 内容 |
|---|---|
| `243106f4b7` | spec/plan revision（owner review対応） |
| `518f6934d4` | spec/plan revision（re-review対応。disposition段階ownership含む） |
| `dd09ef1a18` | spec/plan revision（Issue AC 2段階契約 + spec 203 carve-out） |
| `96677230db` | feat本体（settings row削除、strings、specs表記、CI lane、evidence、test更新） |
| `c25b00d21b` | test（MAT-AC-01/03/06 review対応） |
| `8a7262bec3` | test（Usage Access intent oracle + plan整合） |
| `e82645b7c9` | docs 1行（evidence READMEのspec link depth修正） |

diff全体（22 file、+1220/−77）:

- Source: `HomeScreenPreferences.kt`（row削除のみ）、`values/strings.xml`＋`values-ja/strings.xml`（`organizer_personalization_section` 1件削除のみ）
- Test: `OrganizerDiagnosticsRouteInstrumentationTest.kt`、`CustomCategoryPreferencesInstrumentationTest.kt`
- CI: `.github/workflows/ci.yml`（issue-52 laneへのdiagnostics route class追加の1行）
- Docs: specs 38/99/336/138/203、disposition doc、spec 123 inventory、spec/plan 367、evidence README＋screenshot 6枚

### 非変更範囲の検証（check 1）— PASS

`git diff main...issue-367-materials-relocation --name-only` の全22 pathを確認し、次を機械的に検証した（該当pathのdiffは0件、grepのexit 1で確認）:

- `lawnchair/src/app/lawnchair/organizer/application/`、`organizer/planning/`、`organizer/rules/`、`organizer/diagnostics/`、`organizer/integration/` 配下: **変更なし**
- `PreferenceRoutes.kt`、`PreferenceNavigation.kt`、`OrganizerHubPreferences.kt`、`OrganizerUsageMaterialRows.kt`、`ManualOrganizationPreferences.kt`、`OrganizationOnboardingProposal.kt`: **変更なし**
- run面・store・AUTHORING/RUN排他lease・route object・preference定義に触れるfile: **変更なし**（`OrganizationOnboardingProposal.kt`のhint path文言・`organization_onboarding_reentry_hint_body`も無編集）
- Launcher layout DB / `favorites` / migration / 新規永続化: **接触なし**（ホームレイアウト安全規約は適用対象外と判定）
- 依存追加・permission追加・通信追加: なし

## Criteria check（MAT-AC-01〜08）

| AC | 判定 | 根拠 |
|---|---|---|
| MAT-AC-01 | **PASS** | `OrganizerDiagnosticsRouteInstrumentationTest`に新設された`homeScreenHubMaterialsRoutesToEachAuthoringDestination`（hub材料セクション→T-02/T-03/T-04をproduction graph上でactivation、backstack `hasRoute` assert＋T-03/T-04は固有UI marker表示assert。T-02はtest環境でapp listが空になり得るためroute assert）と`homeScreenHubTogglesRecordingPreferenceAndRereadsUsageAccessOnResume`（T-06 toggle、Usage Access intent monitor、app-op表示、`ON_RESUME`再読取）、および既存oracleのhub→diagnostics導線。同classはCI issue-52 laneで本headにおいて**green**（job 105904960545）。screenshot 6枚（`docs/assessment/evidence/issue-367/`） |
| MAT-AC-02 | **PASS** | `HomeScreenPreferences.kt`のdiffでLayout groupのorganizer系row 4件（`HomeScreenPlacementLocks`/`HomeScreenOrganizerDiagnostics`/`HomeScreenCategoryOverrides`/`HomeScreenCustomCategories`）とPersonalization group（`organizer_personalization_section` heading＋`OrganizerUsageMaterialRows()`）を削除。`homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`が6 labelの不在を全list走査（sentinel `force_widget_resize_label`到達まで段階scroll、各stepで`assertDoesNotExist`）後にassertし、manual row＋hub入口rowの残存とhub遷移をassert。暫定併存（段階契約）はcode comment・PR本文・disposition §7.2(b)・Issue #367受入条件2（改訂済み）・Issue #370本文に記録済み |
| MAT-AC-03 | **PASS**（CI再実行を条件に付記） | 非変更範囲検証のとおりstore/lease/route/run面にdiffなし。既存直接compose系test（`CategoryOverridePreferencesInstrumentationTest`、`OrganizerLockScreenTest`、`ManualOrganizationPreferencesInstrumentationTest`、onboarding系oracle）は本PRで無編集（diff対象外）。`CustomCategoryPreferencesInstrumentationTest`のみtest-infrastructure例外（下記専用節）。補足: CI issue-99 laneが本headで無編集classのfont-scale test 1件を失敗（Findings 2。main本線とbranch上のlocal実行ではgreen。本PR由来の証拠はなく、再実行で確認が必要） |
| MAT-AC-04 | **PASS** | specs 38（+5行）/99（+2行）/336（+2行）/138（+1行）はいずれも「Issue #367 (entry notation only, contract unchanged)…」の入口表記追記のみで、AC・契約節は無変更。spec 203はPermission/fallback表のopt-in行とU-2 decision noteの**2箇所のみ**変更（`settingsのOrganizerセクションに常設`→`Organizer hub (T-01) 材料セクションに常設`。no-JIT/fallback/`ON_RESUME`再読取/再促なし規定は不変、JITは#371所有と明記。旧settings配置のnormative記述は残存なし）。spec 123 inventory（`docs/assessment/evidence/issue-123-ui-mapping.md`）row 7/8の更新はcode変更と整合。disposition改訂は§2.1/§2.3/§3.10/§4.1/§5/§7.2(b)/status追記で段階ownership（#367=材料rowのみ、#370=manual row廃止＋spec 232 AC-3＋hint更新、spec 203=配置#367→JIT#371）を記載 |
| MAT-AC-05 | **PASS** | 旧`homeScreenEntryNavigatesToDiagnosticsRouteShowingExportSurface`（settings直接entry oracle）は削除され、`homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`へ付け替え済み（旧名はplan.mdの説明文にのみ残存）。obsolete理由（D-01材料集約・TO-BE §5.2によるsettings材料row廃止）はtest KDocに明記され、PR本文の変更内容節にも記録。safe terminal oracle（`safeTerminalOpenDiagnosticsRoutesThroughProductionGraphToExportSurface`）は維持（scroll-into-view修正はtest-onlyで観測対象不変）。同classはissue-52 CI laneへ登録され、本headでgreen |
| MAT-AC-06 | **PASS** | 設定側Personalization group削除によりT-06の唯一インスタンスはhub内（`homeScreenMaterialsRelocationRoutesDiagnosticsThroughHub`内の`assertCountEquals(1)`）。`homeScreenHubTogglesRecordingPreferenceAndRereadsUsageAccessOnResume`がproduction hub上でrecording toggle ↔ 共有preference一致、Usage Access row click時に`Settings.ACTION_USAGE_ACCESS_SETTINGS` intent（instrumentation monitor 1件hit）、app-op付与状態表示一致、UiAutomation shellによるgrant/revoke＋test制御LifecycleOwnerのresume cycleでの`ON_RESUME`再読取を固定。`OrganizerUsageMaterialRows.kt`（production）は無編集 |
| MAT-AC-07 | **PASS** | `OnboardingOrganizationProposalInstrumentationTest.kt`はdiff対象外（#232 oracle `homeScreenSettingsShowsTheOrganizerEntryInGeneralAboveTheFold`、hint oracle `laterTapShowsTheReentryHintAndPreservesTheDeferOutcome`とも無編集）。新oracleがGeneral groupのmanual row＋hub row残存をassert。CI issue-53 lane（onboarding含む）が本headでgreen（job 105904960559）。spec 232 AC-3契約は#370まで不変（disposition §2.3に記録済み） |
| MAT-AC-08 | **PASS** | lock関連production変更なし（T-20 dialog・`OrganizerLockScreenTest`対象面はdiff外）。`organizer_personalization_section`は`values/`（1007行目付近）と`values-ja/`（29行目付近）の双方から削除。code参照grep（`*.kt`/`*.xml`）0件を確認（残存hitはspec/plan文書内の説明のみ）。`organizer_personalization_recording_label`/`usage_access_label`等の残存stringはhub `OrganizerUsageMaterialRows`が使用継続のため孤立なし。production diffに新規hardcoded literalなし。`OrganizerLockScreenTest`はCI lane未登録のためlocal green記録（evidence README）のみ — 補足はFindings 6 |

## Test-infrastructure exception（MAT-AC-03）の検証 — PASS

`CustomCategoryPreferencesInstrumentationTest.kt`の差分（16行）は次の2点のみで、spec MAT-AC-03が認めた例外の範囲に一致する:

1. `FakeCatalogStore.mutate`（test内fake）: Createの`Committed`がminted entryを報告するよう修正（coordinatorのverified-Create契約、spec 336への追従。base `dd09ef1a18`でも再現していた既存不備）。
2. rename行matcher: visible text → `onNodeWithContentDescription`（実UIのrename affordanceがcontentDescriptionであるため）。

production code・oracle観測対象は無編集。同classはCI lane未登録のまま（実行はlocal evidence、class全体安定greenをevidence READMEが記録）。実装re-review 2（[5742231568](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5742231568)）がplan本体（Current evidence/Verification/Change set/checklist 7）との整合を含め承認済みで、plan `Change set`に同test fileが記載されていることをdiffで確認した。

## Review coverage

ChatGPT review記録（いずれもIssue #367コメント。IDはREST APIで実在確認済み）:

| 段階 | 対象head | 結果 | link |
|---|---|---|---|
| spec 初回（draft `a50f074a`） | draft | Changes requested（#370先取り・re-entry stale） | [5740049626](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5740049626) |
| spec re-review 1 | `243106f4b7` | Changes requested（ownership未閉・spec 203 U-2矛盾） | [5741019899](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741019899) |
| spec re-review 2 | `518f6934d4` | Changes requested（Issue AC 2不一致・旧記述残存） | [5741111260](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741111260) |
| 段階契約の記録 | — | Issue #367本文AC 2改訂の記録 | [5741164754](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741164754) |
| spec 最終 | `dd09ef1a18` | **指摘なし / Approve相当** | [5741181088](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741181088) |
| 実装 初回 | `96677230db` | Changes requested（MAT-AC-03既存failure・MAT-AC-01/06 oracle不足） | [5741853711](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5741853711) |
| 実装 re-review 1 | `c25b00d21b` | Changes requested（plan整合・Usage Access intent oracle） | [5742148902](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5742148902) |
| 実装 re-review 2 | `8a7262bec3` | **指摘なし / Approve相当** | [5742231568](https://github.com/nunu1733/NunuLauncher/issues/367#issuecomment-5742231568) |

承認後のcommitは `e82645b7c9` のみで、diff検証の結果、**`docs/assessment/evidence/issue-367/README.md` の1行のみ**（spec相対linkのdepth修正 `../../../specs/…` → `../../../../specs/…`）を変更するdocs-only commitであることを確認した。承認が実装head `8a7262bec3` をcoveringし、以後の実質変更はない。

## CI status（監査時点、run 35445985764 @ `e82645b7c9`）

| 結果 | job |
|---|---|
| pass（13） | changes / validate-repo-contract / check-style / high-risk-evidence / build-debug-apk / organizer-unit-tests / organizer-instrumentation-shared-writer-tests / db-migration / issue299 / **issue52（本PRのoracle class群。14m9s）** / issue155 / issue332 / issue53（onboarding） |
| fail（2） | organizer-instrumentation-api35-tests（job [105904960565](https://github.com/nunu1733/NunuLauncher/actions/runs/35445985764/job/105904960565)）/ organizer-instrumentation-issue99-tests（job [105904960569](https://github.com/nunu1733/NunuLauncher/actions/runs/35445985764/job/105904960569)） |
| 最終 | `final-status` = **failure**（required gate集約） |

本PRには `risk: layout-data` / `risk: migration` labelは付与されておらず、変更pathもhigh-risk対象外のため `high-risk-gate` の独立エビデンス要件自体は適用外（high-risk-evidence jobはpass）。ただし `final-status` は通常のmerge gateであり、現状redである。

## Findings

1. **CI api35 lane失敗（本PR非依存と判定、再実行が必要）**: `TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` が `expected:<STALE_REVISION> but was:<RECOVERY_STORE_UNAVAILABLE>` で失敗。同class（`organizer/application`配下）は本PRで無編集であり、同一code（`8a7262bec3`は`e82645b7c9`とcode差分なし・docs 1行のみ）での先行runはlane自体がcancelled、main本線run [35436026985](https://github.com/nunu1733/NunuLauncher/actions/runs/35436026985)（`1285c13cc6`）ではgreen。失敗様式は環境起因（recovery store解決）を示唆する。→ **本headでのre-runによる確認をmergeの前提条件とする**。
2. **CI issue-99 lane失敗（本PR非依存と判定、再実行が必要）**: `CategoryOverridePreferencesInstrumentationTest.cancelRestoresFocusAndLongAppLabelRemainsReachableAtTwoHundredPercentFontScale` がCompose内部の `IndexOutOfBoundsException: Index 2, size 2` で失敗。同classは本PRで無編集、main本線ではgreen、branch上のlocal実行ではgreen（evidence README記録）。→ 同上、re-runで確認するまでMAT-AC-03のCI面の証拠は不完全として扱う。
3. **本PRのoracle本体はCI独立確認済み**: 更新・追加された `OrganizerDiagnosticsRouteInstrumentationTest`（MAT-AC-01/02/05/06の自動oracle全部）とonboarding lane（MAT-AC-07）は、本headのCIでgreen。失敗2件はいずれも本PRが触れていないclassである。
4. **planのcheckbox未更新（軽微・hygiene）**: plan.mdのExecution checklist 8（full verification＋PR作成）とDocumentation updates 1/3/4/6が未チェックのままだが、対応作業は本PRに実施済み（spec/plan status自体は `accepted` 維持。#366前例（PR #381 / commit `72205871c8`）どおりimplemented status更新はmerge後のdocs作業として分離される形で一貫）。
5. **plan Change setの軽微な不正確さ（軽微）**: `HomeScreenPreferences.kt`行の「不要import（…`OrganizerUsageMaterialRows`等）を整理」は実差分より広い表現。実際は同package定義のためimportは存在せず、削除されたのは未使用route import 4件のみ。実態に影響なし。
6. **`OrganizerLockScreenTest`はCI lane未登録（既存状態）**: MAT-AC-08のT-20 oracleはlocal green記録のみ。本PR由来の制限ではないが、事実として記録する。

## 未確認範囲

- 監査者自身によるlocal gradle実行（build/instrumentation）は未実施。検証結果はrecorded local evidence（`docs/assessment/evidence/issue-367/README.md`）とCI runの照合に基づく。
- emulator screenshot 6枚の視覚内容はfile存在とREADME記載の確認まで（画素単位の検証はしていない）。

## 最終判定

**条件付きGO。**

- 変更内容はaccepted spec（MAT-AC-01〜08）・plan・段階ownership契約（disposition §2.3/§3.10/§5/§7.2(b)、Issue #370本文）にすべて適合し、非変更範囲（材料面の実装・store・lease・run面・route）の汚染はない。spec/plan/実装の計6回のChatGPT reviewラウンドを経ており、実装最終承認は `8a7262bec3`、承認後差分はdocs 1行のみである。
- ただし本headのCI `final-status` がfailureであるため、このままではmergeできない。失敗2 jobは本PRが触れていないclassの、本PR非依存と認められるfailure様式（main green・local green・環境起因様式）であるが、**re-run等により本headでの `final-status` greenを確認するまでmergeへ進めない**。re-run後も同一失敗が再現する場合は、本PRと無関係としても、別途起票のうえ原因究明と該当laneの修正を先に行うこと。
