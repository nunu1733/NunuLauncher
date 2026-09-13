# Plan — Issue #287 グリッド変更後のunknown review完結

> status: draft (spec承認後に実装開始)
> spec: [spec.md](./spec.md)
> revision: rev 2 (2026-09-13) — Phase 1 review (code-reviewer-1) の指摘を反映:
> stale旧capture拒否のscenario/AC/test追加、同一プロセス自動回帰chainの
> instrumentation test化、listing/decision一致の述語明確化、path・表現の修正。

## 現在codeの根拠 (2026-09-13, main `f9afd8bfde`)

| 事実 | 位置 |
|---|---|
| グリッド移行が全行をUNKNOWN化 | `src/com/android/launcher3/model/GridSizeMigrationUtil.java:211` (`markOrganizerLocksUnknown`、`UPDATE favorites SET organizerLockState = 0`)。呼出しは `migrateGridInTransaction` の `UNKNOWN_MARK` step (:187)。ADR-0004通りの設計 |
| composerのfail-closed | `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt:143-153` (`items.any { it.lockState == OrganizerLockState.UNKNOWN }` → `CAPTURE_UNKNOWN_LOCK`)。captureはcomposition毎に `captureSource.capture()` で取得し、世代cacheなし |
| **本欠陥: フォルダ子の1ページ容量上限** | `lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt:174-176` (`fitsProfile` の `PlacementState.FolderChild` → `rank < caps.folderMaxColumns * caps.folderMaxRows` → `PLACEMENT_OUT_OF_PROFILE`) |
| 拒否のuser-facing表現 | `LockMessages.rejection(PLACEMENT_OUT_OF_PROFILE)` → `organizer_lock_error_unsupported` = 「このアイテムはロックできません。」(`lawnchair/res/values/strings.xml:973`、`values-ja/strings.xml:76`)。実機観測と一致 |
| listing/explainと書込みの判定差 | `LockReviewListing.of` (`LockReview.kt:44-56`) は `UNKNOWN` かつ既知kindのみでlisting。`lockStateListing` (`LockReview.kt:98-112`) は既知kindのみでunknown区分を構成。`explain` (`LockAuthoringModule.kt:34-44`) はlisting membership判定。書込み (`LockAuthoring.kt:145-155` `sharedRejection`) は追加でref/kind/container/profile/bounds判定 → 「78件の配置を…」表示で0件適用の不一致。3面が単一decision関数を直接共有しているわけではないが、本修正でbounds次元の不一致を解消する |
| listingされるが書込み不可の他の経路 | 利用不可profile行、`UnsupportedContainer` 行。本specでは既存挙動 (fail-closed) を維持し、書込み適格行の述語をspec scenarioで定義してinvariant testの対象を明確化 |
| 旧世代captureの拒否機構 | `LockStateDbAdapter.write` (`lawnchair/src/app/lawnchair/organizer/locks/adapter/LockStateDbAdapter.kt:51-63`): transaction内でcaptureを取り直し、`before.revision != plan.sourceRevision` → `STALE_REVISION` (`LockAuthoringModule.execute` が `STALE_CAPTURE` へ写像)、さらに各行のexact precondition不一致 → `PRECONDITION_FAILED`。revisionは `RevisionCalculator.revisionOf` (`lawnchair/src/app/lawnchair/organizer/application/revision/RevisionCalculator.kt:34-39`) によるcanonical layout state全体 (lock stateを含む) の内容digest → 全行UNKNOWN化を含む移行は必ずrevisionを変える |
| プラットフォームのフォルダはページング表示 | `src/com/android/launcher3/folder/FolderPagedView.java:211` (`pageNo = rank / mOrganizer.getMaxItemsPerPage()`)。1ページ容量は表示のpage分割のみで、loaderが永続化したメンバーrank (capacity以上を含む) はそのままloadされる |
| グリッドpresetの容量がgrid毎に異なる | `res/xml/device_profiles.xml`: `5_by_5` は `numFolderRows=4 × numFolderColumns=4` (16)、`6_by_5` は3×3 (9)。グリッド変更でcaptureされる `folderMaxColumns/Rows` が既存rankを下回り得る (`lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt:493-496` の `idp.numFolder*.maxOrNull()`) |
| plannerは既存メンバーrankをgateしない | `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt:248` / `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt:397,503` の容量は新規フォルダ形成のgroup sizeのみ。preserved memberは `capturedToOutput` でそのまま保持 |
| 適用検証もrankをgateしない | `lawnchair/src/app/lawnchair/organizer/application/protocol/MaterializedStateValidator.kt:107` (FolderChildはparent参照の解決のみ) |
| 既存test seam | JVM: `tests/unit/app/lawnchair/organizer/locks/` (`LockAuthoringDecisionTest`, `LockAuthoringModuleProtocolTest`, `LockReviewListingTest`, `LockFixtures` — fixture容量は4×4)。実DB: `tests/organizer-instrumentation/app/lawnchair/organizer/locks/LockAuthoringInstrumentationTest.kt`。実loader・実composer・実writerの同一プロセスE2E harness: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt` (fixture行insert → 実loader reload → 実 `ManualOrganizationRun`) |

## 変更module

1. **`lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt`** (唯一の
   production変更)
   - `fitsProfile` の `PlacementState.FolderChild` 分岐を
     `placement.rank >= 0` のみにする。1ページ容量による上限を削除する。
   - workspace / Dock / AppPairChild / UnsupportedContainer の分岐は不変。
   - seam・interface・型の追加変更はなし (`LockAuthoringDecision` の
     公開surfaceはそのまま)。

2. **test追加** (production変更なし)
   - `LockAuthoringDecisionTest`: 1ページ容量を境界とするrank
     (capacity-1 / capacity / capacity超過) のフォルダ子を
     `evaluateChange` / `evaluateReviewBatch` が受入すること、負rank拒否の
     維持、一括の全体atomicity (1行でも負rankなら全行不変)。
     fixtureはloaderが永続化する形 (親フォルダ行 + 連続rankのメンバー行) を
     使用し、疎な極端rankは対象外とする。
   - `LockReviewListingTest`: specの「書込み適格行の述語」
     (persistent ref・既知kind・supported container・利用可能profile・
     能力内placement) を満たすlisting行がdecision受入されるinvariant。
     述語外行 (負rank・unsupported container・利用不可profile) は
     拒否維持の対证として明示。
   - `LockAuthoringInstrumentationTest` 拡張 (実DB・実migration primitive・
     実writer):
     (a) 容量超過rankのフォルダメンバーを含むlayoutをseedし、実
     `GridSizeMigrationUtil#markOrganizerLocksUnknown` を通してから
     module経由でreviewし、再captureのlock stateがUNKNOWN 0であること。
     (b) 移行前に取得したcaptureを `sourceRevision` とする書込みplanが、
     移行後に `STALE_CAPTURE` で拒否され無変更であること。
   - 新規instrumentation test (AC-4): `ManualOrganizationProductionE2EInstrumentationTest`
     のharness pattern (実 `LauncherAppState`/`LauncherModel`/`ModelDbController`、
     fixture行insert、実loader reload、実 `ManualOrganizationRun`) を利用し、
     同一プロセスで次のchainを実行する:
     1ページ容量超過メンバーを持つフォルダを含むlayoutをseed →
     organizer実行でpreview到達 → 適用と復元を実行 →
     グリッドpreference変更 (`workspaceColumns` 等) と
     `LauncherAppState.getIDP(context).onPreferencesChanged(context)` による
     production経路のmigration → organizer再実行で
     `InputUnavailable(CAPTURE_UNKNOWN_LOCK)` をassert →
     `LockAuthoringModule` (実adapter) 経由で全unknown行
     (容量超過メンバー含む) をreview解消 → organizer再実行でpreview到達を
     assert。グリッドpreferenceはtearDownで復元する。
     Phase 1 review (非ブロッキング指摘) のとおり、グリッド変更が実際に
     起きたことを明示assertする: 変更前後のgrid preference値が異なること、
     変更後のIDPが期待profileであること、migration実行後にfavorites全行が
     `UNKNOWN` 化したこと、capture revisionが変化したこと。preference値が
     不変の場合やmigrationが走らなかった場合にtestが先へ進まないよう、
     このassertをchainの前提として置く。

## interface / seam

呼出側・testとも既存の `LockAuthoringModule` seamを通したまま。
UI (`PlacementLockPreferences`)、writer adapter (`LockStateDbAdapter`)、
`LayoutWriteCoordinator` は無変更。本修正は `LockAuthoringDecision` の
判定のみを変える。listing (`LockReview`) と `explain` は現在kind判定/
listing membershipで行を選んでおり単一decision関数を直接参照しないが、
bounds次元の判定が一致するため「表示するが容量超過を理由に書込み不可」の
不一致は解消される。利用不可profile等の他の次元の不一致は
既存fail-closed (spec scenarioで対证) のままである。

## migration / rollback

- schema・DB・preferenceのmigrationはnone。
- rollbackはcommit revertのみ。書込まれたlock stateは既存のreview画面で
  再編集可能であり、データ互換性の懸念はない (列の値域は変わらない)。

## リスク評価

- `risk: layout-data` を付ける。対象は `favorites.organizerLockState` への
  書込み受容判定であり、layout domainの判定変更であるため。
  高リスクgate要件 (CI `final-status` + `docs/assessment/pr-<n>-<slug>.md`
  の独立audit) を満たす。auditは実装sessionとは別のgeneral-purpose
  subagentで実施する。
- 誤った行への書込みリスクは増えない: exact per-row preconditionと
  in-transaction revision再読取は不変。受容範囲が広がるのは「loaderが
  永続化しloadする行 (既存フォルダメンバーのrank)」のみであり、
  旧世代capture由来のplanは既存revision機構で拒否される。
- composer意味論・migration仕様には触れないため、#83/#172/#59の受入条件と
  衝突しない。

## 検証

1. JVM: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`
2. build/lint: `./gradlew spotlessCheck assembleLawnWithQuickstepGithubDebug`
3. 実DB: organizer-instrumentation suite (`LockAuthoringInstrumentationTest`
   および新規AC-4 testを含む) をAPI 36.1 emulatorで実行。
4. 端末検証 (AC-6): debug buildをemulatorへinstallし、spec再現シーケンスを
   UI操作で実行する。
   - 大フォルダ (1ページ容量超過) の作成はinstrumentation seedまたは
     UI自動化で行い、実施方法をassessmentへ記録する。
   - 失敗状態 (`INPUT_NOT_READY` / `CAPTURE_UNKNOWN_LOCK` のjournal記録) を
     診断exportで確認してからreview解消 → 再preview成功を確認する。
   - 手順・結果・journal抜粋を `docs/assessment/issue-287-grid-change-unknown-lock-recovery.md`
     に記録する。
5. 実施しないものと理由: Nova backup実機ファイルを使った完全再現は
   依頼者の環境依存のため、emulatorでは等価シーケンス (ZIP backupまたは
   seedによるフォルダ構成) で検証する。差分をassessmentに明記する。

## 実装順

1. JVM test追加 (先に失敗を再現: 容量超過rankが拒否される現行挙動を赤で固定)
2. `fitsProfile` 修正 (青)
3. JVM test緑化 + 既存organizer gate全走行
4. `LockAuthoringInstrumentationTest` 拡張 (実DB review + stale旧capture拒否)
5. AC-4同一プロセスchain test追加・実行
6. emulator端末検証 + assessment記録
7. PR (labels: `bug`, `risk: layout-data`)、spec statusをaccepted→実装反映

## plan revision

- rev 1 (2026-09-13): 初版。
- rev 2 (2026-09-13): Phase 1 review指摘対応。stale旧capture拒否の根拠
  (revision content digest機構) を根拠表へ追加、同一プロセスchainの
  instrumentation test化、書込み適格行の述語明確化、
  `MaterializedStateValidator` の正しいpath表記、listing/explainと
  decisionの参照関係の正確な記述に修正。
- rev 3 (2026-09-13): Phase 1 re-review (Approve) の非ブロッキング指摘を
  反映: AC-4 testでのgrid変更・migration実行の明示assert要件を
  実装注意事項として追記。

## Review record (Phase 1)

- Reviewer: code-reviewer-1 (independent session)。
- rev 1 → `Request changes` (Blocker 1 / Major 2 / Minor 3) → rev 2 で対応 →
  re-review で `Approve` (working tree content, HEAD `f9afd8bfde121932c0c8ed965225d52a84d86ab4`)。
- 残存指摘なし。非ブロッキング注意 (AC-4の明示assert) は rev 3 へ反映済み。
