# High-risk audit: PR #321 organizer personalization signal snapshot / optional composition input / launcher-origin counter

> Status: accepted（verdict: **audit pass**。code findings 無し。evidence gaps（findings F-1〜F-3）
> と観察（O-1〜O-3）を下記に記録。いずれも現行 diff の merge を阻塞む code 欠陥ではないが、
> spec U-5 の probe 完了条件 (a)〜(d) は spec/plan 契約どおり **本PRの merge gate** として
> 未実施である（F-1。merge 前に記録が必要））
> Audit date: 2026-09-15

- Auditor: 独立audit session（PR #321 / Issue #203 の実装を行っていない general-purpose subagent。
  solo保守のため同一保守の別sessionとして、実装者の報告（PR本文・commit message）を信用せず、
  code実読・local再実行・GitHub API確認で独立検証した）
- PR: https://github.com/nunu1733/NunuLauncher/pull/321（base `main`、head branch `issue-203-implementation`）
- Head SHA: 3e077e72f3e8b18311c4f7a03d8e6e5083b86eb3
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34930710699
  （`head_sha=3e077e72f3…`、PR #321 関連付け。**completed/success、`final-status` success**。
  本audit本人が `gh pr checks 321` で全14 jobのpassを直接確認: build-debug-apk /
  organizer-unit-tests / check-style / 全 organizer-instrumentation lane（api35, db-migration,
  issue155, issue299, issue52, issue53, issue99, shared-writer）/ changes / validate-repo-contract /
  final-status。High-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/34930710706
 も pass）
- Criteria: specs/203-usage-implicit-preference-signals/spec.md（status: accepted、commit `d236b7e294`、
  owner acceptance [comment 5674638975](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674638975)、
  対象 head `7a08f9c86a`）の AC-1〜AC-16 と Decisions U-1〜U-6。
  対応する plan: 同 directory の plan.md（accepted、commit `d236b7e294`）。
  関連 ADR: docs/adr/0007-authoritative-organization-policy-sources.md（本PRで §9 追加、§9 自体を監査対象として確認）
- 監査手順の正本: AGENTS.md「高リスクPRの独立エビデンス」節 / docs/project/github-workflow.md
  Execution and approval contract

## Scope

- base `main` = `f12d67bcb6`（`origin/main`、`gh repo view` で nameWithOwner `nunu1733/NunuLauncher` /
  default branch `main` を確認済み）。head `3e077e72f3`。
- **base head は head の祖先ではない**（branch は main の #319/#202 → 実際は #319/#320 merge 前
  `397d3fd9` を merge base とする、`git merge-base` で確認）。GitHub は PR を `MERGEABLE` と報告し、
  PR差分（3-dot、merge-base基準）は29 file。2-dot diff `f12d67bcb6..3e077e72f3` に現れる
  LoaderTask.java / issue-298 docs / RestoreLease test 等の「逆差分」は #319/#320 未含有による
  artifact であり（merge-base `397d3fd9` 以降の #319/#320 内容を head が単に持たないだけ）、
  PR変更ではないことを本audit本人が確認した。merge は 3-way であり #319/#320 内容が消える
  経路は存在しない。
- 実読みした全PR差分（29 file）: pure domain 新規4（`organizer/personalization/`）、integration 新規5
  （aggregator / system usage reader / launcher-origin store+reader / recorder）、
  `OrganizationInputComposer.kt`（optional source 接続 + `OrganizationInput` 搭載 + provenance 8番目 field）、
  `ProductionOrganizationInputComposer.kt`（wiring）、`OrganizationInput.kt`、`CompositionModels.kt`、
  `PolicyModels.kt`（`PERSONALIZATION_SIGNAL_SNAPSHOT` 追加）、`LawnchairLauncher.kt`（`logAppLaunch`
  override）、`PreferenceManager2.kt`（recording toggle + OFF で clear）、`HomeScreenPreferences.kt`
  （Personalization section）、res 3 file（文言 / default config）、tests 新規5、
  specs/203 spec+plan（accepted flip）、DESIGN.md §9、ADR-0007 §9、organizer-diagnostics.md
  （personalization 3行）、requirements.md（FR-013 / D-010）。
- 新規永続 state: `SharedPreferences` ファイル `organizer_launcher_origin_v1`（launcher-origin counter
  の count + 絶対 day anchor のみ）。**backup 排除を独立確認**: root `AndroidManifest.xml` の
  `fullBackupContent=@xml/backupscheme` は include-only list（launcher系db + launcher prefs +
  downgrade_schema.json のみ）であり、新規 prefs file は backup/restore 対象外（spec U-3 /
  Privacy 節どおり）。dataExtractionRules は repo 内に存在しないため fullBackupContent が適用される。
- Launcher3/AOSP 由来 file への変更: 0件（確認済み。hook は fork 所有 `LawnchairLauncher.kt` の
  override で完結し、AGENTS.md bridge 規約を満たす）。

## Criteria check

実装・test を実読みした上での個別判定（「test名」は本auditが test source で直接確認した実在 test）。

- **AC-1（採用/不採用と根拠）**: spec での承認済み（U-1 確定）。実装は foreground30d/7d bucket +
  recency + active-days（system）と launcher-origin count/recency のみを実装し、install age 等の
  deferred 候補は実装されていない（不採用集合の漏れ無し）。**確認**。
- **AC-2（型・値域・identity、同一入力→同一 digest、sparse object 契約、1:1 digest ↔ object）**:
  `SignalField<T> = Value / Absent` の non-null closed 二値（`PersonalizationSignalModels.kt`）、
  section 分離 sealed 型（`SystemUsageSection` / `LauncherOriginSection`）を確認。
  `contentDigest` は `sha256Canonical(canonicalRepresentation())` として **object 自身から導出**される
  （`PersonalizationSignalSnapshot.kt`）ため、canonical rows と object 形状が構造的に一致し
  （unavailable section の package-level entry は object 側にも構築されない）、digest が
  consumer-observable projection を identify する sparse object 契約が構造的に成立。
  test: `digestIsDeterministicAndIdentityIsContentAddressed` / `notGrantedAndQueryFailureAreDifferentDigests` /
  `sectionUnavailableAndAllAbsentAreDifferentDigests` / `digestTracksEveryConsumerObservableChange` /
  `differentPackageSetsOfAnUnavailableSectionShareTheDigestAndTheObject` / `canonicalRowsCarryHeaderProfileAndEntryRowsWithSourceIdentity` /
  `absentFieldsSerializeWithoutAValue`（`PersonalizationSignalSnapshotTest` 10 test、全pass）。**確認**。
- **AC-3（NOT_GRANTED / UNAVAILABLE で deterministic fallback）**: `AndroidSystemUsageSignalReader` は
  app-op 非許可で query せず `NOT_GRANTED` fast path（`SystemUsageSection.Unavailable`）。
  composer は cut 安定後に snapshot を1回読み、source 失敗・未接続は `unavailable()` へ degrade して
  `Ready` を返す（`readPersonalizationSnapshot` の try/catch + aggregator の try/catch の二重化）。
  test: `notGrantedSnapshotComposesReadyAndKeepsLauncherOriginEntries` / `throwingSourceComposesReadyWithUnavailableSnapshot` /
  `unwiredSourceComposesReadyWithTheUnavailableDefault`（`PersonalizationCompositionTest`）。
  unit 面は確認。**integration / device 面（実 permission 状態での Organizer 全機能）は未実施**（F-2）。
- **AC-4（section-level availability と Absent の typed 区別）**: field は二値 `SignalField` のみで
  failure を運ばず、failure は section state のみ。test `sectionUnavailableAndAllAbsentAreDifferentDigests`。
  **確認**。
- **AC-5（content-addressed identity、stale/replan semantics）**: identity は
  `PolicyInputIdentity(PERSONALIZATION_SIGNAL_SNAPSHOT, "personalization-signals-v1", contentDigest)`、
  generation 無し。stale/replan（usage 変化は plan を stale にしない、capture revision が正本）の
  直接 test は無いが、(a) `dynamicCutIdentity` は本diffで不変、(b) personalization identity は
  provenance のみに参加し cut / capture revision に参加しない、(c)
  `mandatoryDynamicCutIdentityIsUnchangedByPersonalizationPresence` が mandatory provenance 行の
  全等価を検証、により構造的に成立（cut は mandatory 行の純関数であるため等価行 → 等価 cut）。
  間接 evidence として **確認（構造根拠付き）**。
- **AC-6（purity guard）**: `PurityGuardTest.everyProductionPersonalizationFileIsFreeOfForbiddenPackagePrefixes`
  が `organizer/personalization/` を forbidden prefix（`android.` / `com.android.` / `androidx.` /
  coroutines / IO / net 等）で検査。**確認**。
- **AC-7（diagnostics 非流出）**: 実装は diagnostics へ personalization 由来の何も出力しない
  （RunEvent schema v1 に personalization field が存在しない、emission 経路も無い — 本audit本人が
  composer / aggregator の実読みで確認）。organizer-diagnostics.md に personalization 3行
  （usageAccessState / snapshot identity は将来 journal 化時 Allowed、signal 内容は Never）を追加。
  既存 `DiagnosticsContractTest`（負 fixture）は無変更で通過。**確認（不在による成立）**。
  personalization 固有の diagnostics contract test は plan Testing strategy の文言どおりには
  追加されていない（O-2）。
- **AC-8（launcher-origin が別 source identity で混ざらない）**: canonical entry 行が source identity
  （`SYSTEM_USAGE_V1` / `LAUNCHER_ORIGIN_V1`）を運び、section が分離。
  test `canonicalRowsCarryHeaderProfileAndEntryRowsWithSourceIdentity`。**確認**。
- **AC-9（requirements traceability）**: FR-013（input implemented）と D-010 の更新を実読み確認。**確認**。
- **AC-10（dynamic bundle identity 不変）**: `dynamicCutIdentity` 定義は本diffで未変更（実読み）。
  test `mandatoryDynamicCutIdentityIsUnchangedByPersonalizationPresence`。**確認**。
- **AC-11（personalization 由来 NotReady 不存在）**: source throw / 未接続で `Ready` かつ該当 section
  のみ利用不能化。`throwingSourceComposesReadyWithUnavailableSnapshot` / `unwiredSourceComposesReadyWithTheUnavailableDefault`。
  composer の読み取り位置は `composeInternal` の cut 安定判定後・`Ready` 返却前であり、NotReady
  経路より後方（実読み確認）。**確認**。
- **AC-12（non-null snapshot / identity、既存 strategy 挙動不変）**: `OrganizationInput.personalization`
  は non-null（composer は必ず渡す。直接 constructor の caller には default 値
  `PersonalizationSignalSnapshot.unavailable()`）、`InputProvenance.personalization` も non-null
  8番目 field（default は unavailable identity）。両者とも型として null 不可能。
  test: `notGrantedSnapshotComposesReadyAndKeepsLauncherOriginEntries`（`provenance.personalization`
  の source / digest 等価）。既存 strategy 挙動不変は既存全 suite（111 class / 1173 test）が
  無変更で通過することで担保（Executed test surface 参照）。**確認**。
- **AC-13（NOT_GRANTED 下で launcher-origin 保持）**: aggregator は `usageAccess` と独立に
  `LauncherOriginSignalReader.read` を呼び section を合成（実読み確認）。
  test `launcherOriginEntriesSurviveNotGranted`。**確認**。
- **AC-14（app pair が counter を進めない）**: `LauncherOriginLaunchRecorder.onLaunch` の item 型
  filter（`isAppPair` / `isPromiseIcon`）+ `LawnchairLauncher.logAppLaunch` override が
  `AppPairInfo` / `WorkspaceItemInfo.isPromise()` を filter へ渡す（実読み確認）。
  launcher activity 経由の app pair は hook 到達自体無し（spec 契約どおり、`launchAppPair` override
  は実装していない — 実読み確認）。
  test: `appPairAndPromiseIconLaunchesAreFilteredByItemType`。**確認（unit 面）**。instrumented / device
  面は未実施（F-2）。
- **AC-15（super exactly once、既存 side effect 不変、書き込み失敗を吸収）**: `LawnchairLauncher.logAppLaunch`
  override は第一文で無条件に `super.logAppLaunch(...)` を呼び、分岐・早期 return は super 呼び出し前に
  存在しない（実読み確認 → 構造的に exactly once）。`QuickstepLauncher.logAppLaunch` の既存 side effect
  （All Apps session InstanceId 補正 / prediction rank / `LAUNCHER_APP_LAUNCH_TAP` / hotseat prediction
  ranking info）は upstream 実読みで確認し、override はこれらを変更しない。counter 書き込みは
  `LauncherOriginLaunchRecorder` 経由の best-effort 非同期で、recorder 内 try/catch + override 内
  try/catch（`RuntimeException`）の二重吸収。
  test: `storeFailuresAreAbsorbedAndNeverPropagate`（direct executor で同期化し伝播不在を検証）。
  **確認（構造 + recorder unit）**。override 自体の mock による呼び出し回数検証 test は無い（F-3）。
- **AC-16（taskbar 含む、recents 非合流、promise 除外）**: taskbar 合流は本audit本人が upstream 実読みで
  再確認（`LauncherTaskbarUIController.onTaskbarIconLaunched` :336-339 が `mLauncher.logAppLaunch(...)` を呼ぶ）。
  promise icon 除外は item 型 filter（AC-14 test）。taskbar recents は seam 非合流（upstream 構造、
  spec Current evidence どおり）。**確認（構造 + unit）**。taskbar 表示端末での観測 evidence は未実施（F-2）。
- **U-1**: 採用 signal 集合どおりの実装（上記 AC-1）。**確認**。
- **U-2**: settings の organizer 関連設定が集まる HomeScreenPreferences 内に常設 section
  （既存 organizer 系 entry と同一画面 — 実装配置は本specの「Organizer セクション常設」と整合）。
  使用状況アクセス状態を text 表示、rationale 文言（local-only / 精度向上 / 許可なくとも全機能利用可）、
  `ACTION_USAGE_ACCESS_SETTINGS` 遷移。manual run 内の自動再促しは無し（実装無しを確認）。
  TalkBack / Switch Access / 200% font scaling の UI evidence は未実施（F-2）。
- **U-3**: `launcherCountClass` 0 / 1–4 / 5–19 / 20–99 / ≥100、recency は day anchor からの read 時
  投影（相対 class は永続しない — `LauncherOriginLaunchCounterStore` 実読み確認）。
  toggle default ON（`config_default_organizer_personalization_recording=true`）、OFF で `onSet` 内
  `clear()`（記録停止 + 既存記録消去が同一 action）。backup 対象外（Scope 節の独立確認）。
  **確認**。
- **U-4**: `InputProvenance` の non-null 8番目 field。**確認**（AC-12）。
- **U-5**: nearest-rack 境界 `B_k = x_⌈kN/5⌉`（実装 `(k*n+4)/5` は ceil の整数式として正しい —
  本auditが再導出）+ `bucket(v) = |{k : B_k < v}|`（境界同値は下位）。N=5/6/7/11 の期待値 test
  （`nearestRankBoundariesFollowCeilKTimesNOver5`）、N=5 相異なる値で `x_1→0 … x_5→4`
  （`distinctUniverseOfFiveMapsAscendingValuesToBucketsZeroThroughFour`）、境界同値は下位
  （`boundaryEqualValuesClassifyToTheLowerBucket`）、同値同 bucket（`equalTotalsShareOneBucket`）、
  universe < 5 で Absent（`universeBelowFiveIsNotRankable`）、recency 半開区間
  `[0,1d)/[1d,7d)/[7d,30d)/[30d,∞)`（`recencyClassesFollowTheSharedHalfOpenBoundaries`）、
  launcher-origin 暦日量子化（`launcherOriginRecencyQuantizesTheSameBoundariesToCalendarDays`）、
  active-days 5段階 / count 5段階、window は単一 anchor + local calendar day + full-containment
  filter（`isContained`、partial edge 除外 — 実装確認）、rank universe は request 集合から独立
  （usage record 由来のみから構成 — 実装確認）。**契約実装は確認**。
  **probe 完了条件 (a)〜(d) は未記録（F-1 — merge gate）**。
- **U-6**: per-profile fail-closed（`readProfile` 失敗 → `SYSTEM_USAGE_UNAVAILABLE`、全 profile 失敗の
  ときのみ `usageAccess=UNAVAILABLE`、実装確認）。**確認（unit / 実装面）**。work profile 実機面は
  instrumentation 未実施（F-2）。

## Executed test surface

本audit本人が監査対象 head `3e077e72f3` の working tree（clean、`git status` で確認）で実行。
順に実行し、すべて exit code 0。

- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL**
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` → **BUILD SUCCESSFUL**
  （386 actionable tasks: 17 executed, 3 from cache, 366 up-to-date）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → **BUILD SUCCESSFUL**
  （445 actionable tasks: 4 executed, 441 up-to-date）

新規 test 結果 XML（`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/`、
本実行のもの — file timestamp を本auditが確認）の属性値:

- `TEST-app.lawnchair.organizer.integration.LauncherOriginLaunchRecorderTest.xml`: tests="4" skipped="0" failures="0" errors="0"
- `TEST-app.lawnchair.organizer.integration.PersonalizationCompositionTest.xml`: tests="4" skipped="0" failures="0" errors="0"
- `TEST-app.lawnchair.organizer.personalization.PersonalizationBucketsTest.xml`: tests="9" skipped="0" failures="0" errors="0"
- `TEST-app.lawnchair.organizer.personalization.PersonalizationSignalSnapshotTest.xml`: tests="10" skipped="0" failures="0" errors="0"
- `TEST-app.lawnchair.organizer.planning.PurityGuardTest.xml`: tests="2" skipped="0" failures="0" errors="0"

全体: 111 test class、合計 **tests=1173 / failures=0 / errors=0**（全 XML から本auditが集計）。

## Findings

阻塞み code 欠陥は検出されなかった。以下は evidence gap と観察である。

- **F-1（merge gate・未実施）**: spec U-5 の probe 完了条件 (a)〜(d) — (a) `queryUsageStats(INTERVAL_DAILY)`
  の実 returns interval 境界・粒度の実測、(b) full-containment filter の実データ確認、(c) 30日保持の
  取得可否、(d) launcher-origin day anchor の DST / timezone 変更時挙動 — が記録されていない。
  spec / plan は「U-5 依存実装 PR は merge 前に記録」を merge gate として要求する。逸脱検出時は
  merge 前に spec 変更が必要。**本PR merge の直前条件として残る。**
- **F-2（evidence gap・spec 受入条件の runtime 面）**: AC-3 の integration/device 面、AC-14 の
  instrumented / device 面、AC-16 の taskbar 表示端末での観測、U-2 の accessibility
  （TalkBack / Switch Access / 200% font scaling）、U-6 の work profile 実機面が未実施。
  spec の受入条件上これらの面を要求するため、merge 後も Issue #203 の終了条件としては追跡が必要。
- **F-3（evidence gap・plan Testing strategy との差）**: plan は `LawnchairLauncher.logAppLaunch`
  override の `super` 呼び出し回数の mock 検証と既存 side effect の override 前後不変 test を
  要求するが、override 自体の unit test は存在しない（PR本文も「構造契約」と明記）。
  本auditは構造確認（第一文の無条件 super 呼び出し、分岐無し）で AC-15 の該当部分を
  満たすと判定したが、回帰担保は recorder level test + CI のみである。
- **O-1（観察・非阻塞）**: rank universe は `total30dMs > 0`（foreground 計上のある record）のみから
  構成される。spec U-5 の文言「usage record を持つ」を「foreground 0 の record も含む」と読む
  場合と比べ、zero-foreground app が多数存在する場合の最下位境界 B_1 の取り得る値が変わり得る
  （zero-foreground app 自体の bucket は常に 0 で不変）。実影響は marginal であり、probe (a)/(b) で
  実データを確認した上で必要なら spec 側の文言確定で吸収できる。
- **O-2（観察・非阻塞）**: AC-7 について、personalization 固有の diagnostics contract test は追加
  されていない（既存の負 fixture `DiagnosticsContractTest` は無変更）。現行は emission 経路自体が
  無いため非流出は構造的に成立するが、将来 journal 化（organizer-diagnostics.md の Allowed 行）を
  行う際に contract test の追加が前提になる。
- **O-3（観察・非阻塞・cosmetic）**: `LauncherOriginSignalReader.availability()` は呼び出し元が存在
  しない未使用 method である（aggregator は section 型から availability を導出している）。
- **機械gate状況（参考）**: 本PRは `risk: layout-data` / `risk: migration` label を持たず、
  high-risk path list（`tools/repo-contract/validate_high_risk_evidence.py` の
  `HIGH_RISK_PATH_PREFIXES` / `HIGH_RISK_PATH_FILES`）にも該当しないため、`high-risk-evidence` check は
  audit 記録なしで pass している（本auditが validator の分類 logic を読んで確認）。本記録は
  owner の監査指示により作成するものである。

## Lineage

- 本auditが検証した code head は `3e077e72f3`。本記録の push に伴う PR head の移動分は
  本記録（docs-only, `docs/assessment/pr-321-personalization-signals.md`）のみであり、
  `git diff 3e077e72f3..<push後head> --stat` で docs-only であることを merge 前に再確認できる。
- 検証対象 commit 上の CI run 34930710699 は `final-status` success を含む全 job pass
  （completed/success、本audit本人確認）。
