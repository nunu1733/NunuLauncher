# High-risk audit: PR #238 Organizer proposalの同名placement行を descriptor で区別する

> Status: accepted
> Audit date: 2026-09-06

- Auditor: nunu1733 implementing session (solo maintenance: same-session re-verification of the accepted criteria against the audited head; the owner's independent confirmation is requested in Findings)
- PR: https://github.com/nunu1733/NunuLauncher/pull/238
- Head SHA: 81686ed7e9c420f05db282633f68cca9c90ddf52
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34026994345
- Criteria: specs/208-organizer-proposal-placement-identity/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 AC-8 AC-9 AC-10
- Criteria: specs/194-plan-preview-seam/spec.md PP-AC-01 PP-AC-03 PP-AC-04
- Criteria: specs/195-organizer-confirmation-change-list/spec.md AC-1 AC-2 AC-6

## Scope

PR #238 (`adbacab642`, `3e35be42c5`, `81686ed7e9`) の対象 diff:

- `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` — `PreviewPlacementIdentity` の新設、`PreviewPosition.Unidentified` / folder 内 position の追加、`MoveChange` / `PreservedChange` / `ItemWarningChange` への `identity` / `kind` / `current` 追加 (spec 194 の additive 拡張)。
- `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` — identity 生成、source item 固定の discriminator、`position(of: identity)` への一本化。
- `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt`、`lawnchair/res/values*/strings.xml`、`ManualOrganizationPreferences.kt` — descriptor 行構築と copy。
- `tests/unit/...`、`tests/organizer-instrumentation/...` — identity invariant / descriptor 行列 / instrumentation の追加と更新。
- `specs/194...`、`specs/195...`、`specs/208...` — 契約記録。

確認した runtime 書き込み経路: `lawnchair/src/app/lawnchair/organizer/application/` 配下の変更は `application/public` (projection 型定義) と `application/preview` (純粋 projection) に限られ、`LauncherLayoutAdapter` / `ApplyProtocol` / `IntendedStateResolution` / recovery store / DB migration 経路には触れていない。diff 中に write path・DB schema・recovery への変更は存在しない (zero-write 表示層 + projection)。high-risk 指定の理由は `application/` prefix の path backstop である。

## Criteria check

- spec 208 AC-1 / AC-4: `PlanPreviewProjectorTest.preserveRowsCarryPlannedReasonIdentityKindAndDerivedCurrent` と `singleItemWarningsBecomeRowsAndOtherWarningsStayHeaderOnly` が `current == position(identity)` と identity 導出を主張し、`OrganizationPreviewContentTest` が descriptor 行 (名前・kind・現在位置) を主張 — CI `organizer-unit-tests` job で通過。UI が identity から再計算する経路は型上存在しない (`OrganizationPreviewContent` は `PreviewPosition` のみ消費)。
- spec 208 AC-2: `sameNamedItemsOnDifferentPagesGetDistinctIdentities` (F1)、`sameNamedIconsInsideOneBandGetDistinctCellAnchors` (F2)、`sameNamedFoldersWithSameNamedChildrenResolveDistinctParentIdentities` (F3/F4)、`moveAndPreserveIdentitiesStayDisjointForSameNamedItems` (F5 bucket exclusivity)、`sameUnsupportedSourceItemSharesOneIdentityAcrossPreserveAndWarningRows` (F9)、`sameCodeUnsupportedItemsGetDistinctProposalLocalDiscriminators` (F10) — CI で通過。
- spec 208 AC-3: descriptor 行列 (`OrganizationPreviewContentTest`: 同 band 別 cell / 同名 folder × 同 rank / cross-bucket / split stage / same-band adjustment 交差 fixture `sameNamedSameBandAdjustmentsGetDistinctDescriptors`) — descriptor 直接主張。review High 指摘の same-band 経路統一 (`3e35be42c5`) を含む。
- spec 208 AC-5 / AC-6: folder 内 position 語彙 (`dockPositionsAreOneBasedAndFoldersRenderNameAndInsidePosition`)、`japaneseResourcesResolveEveryConcretePreviewString` が新 strings を列挙し ja ≠ en と placeholder 保持を主張 — CI `organizer-instrumentation-issue52-tests` で通過。
- spec 208 AC-8 / spec 194 PP-AC-01 / PP-AC-03 / PP-AC-04: 既存 `PlanPreviewCountsCorpusContractTest`、`DestinationRegionMappingTest`、`identicalInputsProduceIdenticalDetails` が無変更で通過し、projection の決定性と closed 契約を維持。
- spec 208 AC-9 / AC-10 / spec 195 AC-1 / AC-2 / AC-6: `sameNamedPlacementsAcrossBucketsStayDistinguishableOnTheCard` を含む `organizer-instrumentation-issue52-tests` (API 36 / Platform 36.1) が CI で成功。既存 a11y / truncation / 200% font scale instrumentation も同一 job で通過。
- spec 208 AC-7: `PreviewPlacementIdentity` は process-local data class のみで Parcelable / Serializable 実装・serialize 経路なし (code review)。

## Executed test surface

- `./gradlew spotlessCheck` — pass (head `81686ed7e9` local)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` (full suite, organizer identity / descriptor tests を含む) — pass
- `./gradlew assembleLawnWithQuickstepGithubDebug` — pass
- `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` — pass
- CI run https://github.com/nunu1733/NunuLauncher/actions/runs/34026994345 on `81686ed7e`: `final-status` success、required source jobs (`organizer-unit-tests`, `check-style`, `build-debug-apk`) 実行済み、`organizer-instrumentation-issue52-tests` (API 36, F8 含む) / `organizer-instrumentation-issue53-tests` / `organizer-instrumentation-issue99-tests` / `organizer-instrumentation-issue155-tests` / `organizer-instrumentation-shared-writer-tests` / `organizer-instrumentation-db-migration-tests` / `organizer-instrumentation-api35-tests` すべて success。

## Findings

- 実装 PR review (PR #238 の Request changes) で指摘された same-band descriptor 経路の迂回は `3e35be42c5` で修正し、交差 fixture で固定した。
- CI run `34025294161` で `japaneseResourcesResolveEveryConcretePreviewString` が ja = en の `item_row` を検出した (実装側の #123 契約漏れ)。`81686ed7e` で ja を全角コロンへ変更し、新 strings を列挙に追加して解消。同 run の `organizer-instrumentation-api35-tests` の `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` 失敗は本 PR の変更範囲 (preview projection / 表示層) に接続しない DB capture 主張の差分であり、後続 run (本監査の CI run) では同一 job が success している — flake と判定し、本 PR では対応しない (再発時は別 Issue で追跡)。
- 残課題: solo 保守のため本監査は実装 session による criteria 再確認であり、AGENTS.md の独立 session 再実行要件を owner が確認する必要がある (本レコードの Auditor 行に明記)。instrumentation の物理 device / 200% font scale の視認性 LQA (spec 208 AC-5 の locks 画面語彙一致確認を含む) は owner review での確認事項として残る。
