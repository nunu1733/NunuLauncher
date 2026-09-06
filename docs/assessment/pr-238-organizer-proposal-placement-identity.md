# High-risk audit: PR #238 Organizer proposalの同名placement行を descriptor で区別する

> Status: accepted
> Audit date: 2026-09-06

- Auditor: independent audit subagent (general-purpose), ZCode session 2026-09-06 — 実装 session とは別の agent として criteria 再確認・検証コマンド再実行・CI evidence 照合を実施した (pre-audit は実装 session が実施し、本レコード初版に記録済み)
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

確認した runtime 書き込み経路: `lawnchair/src/app/lawnchair/organizer/application/` 配下の変更は `application/public` (projection 型定義) と `application/preview` (純粋 projection) に限られ、`LauncherLayoutAdapter` / `ApplyProtocol` / `IntendedStateResolution` / recovery store / DB migration 経路には触れていない。diff 中に write path・DB schema・recovery への変更は存在しない (zero-write 表示層 + projection)。high-risk 指定の理由は `application/` prefix の path backstop である。独立監査 agent が `git diff --stat main...issue-208-placement-identity` (14 files) でこの範囲を再確認し、adapter / protocol / launcher3 側への変更がないことを確認した。

## Criteria check

独立監査 agent が spec 208 AC-1〜AC-10 をコード inspection・test 読解・コマンド再実行・CI 照合で全件再検証し、**PASS** を判定した。以下は criteria ごとの確認内容 (検証方法は「code inspection / test 名 / command / CI job」)。

- spec 208 AC-1 / AC-4: `PlanPreview.kt` の `PreviewPlacementIdentity` (Workspace / Dock / FolderChild / AppPairChild / PlannedFolder / Unidentified) と `identity` / `kind` / `current` field を確認。`PositionContext.position(identity)` が唯一の identity → position 経路であり、旧 `position(state: CanonicalItemState)` は削除済み (diff vs main で確認)。`current` は Preserve / warning 行の生成点すべてで `context.position(identity)` から構成され、NewFolderChange の placement も同経路。UI が identity から位置を再計算する経路は型上存在しない (`OrganizationPreviewContent` は `PreviewPosition` のみ消費。`identitySupplement` による descriptor 補助語の導出は spec §Scope 3 で承認された collision 補助語であり、位置再計算ではない — この例外を明示して解釈)。`PlanPreviewProjectorTest.preserveRowsCarryPlannedReasonIdentityKindAndDerivedCurrent` (163行目) と `singleItemWarningsBecomeRowsAndOtherWarningsStayHeaderOnly` (374行目) が `current == position(identity)` を主張。
- spec 208 AC-2: `sameNamedItemsOnDifferentPagesGetDistinctIdentities` (F1)、`sameNamedIconsInsideOneBandGetDistinctCellAnchors` (F2)、`sameNamedFoldersWithSameNamedChildrenResolveDistinctParentIdentities` (F3/F4)、`moveAndPreserveIdentitiesStayDisjointForSameNamedItems` (F5 bucket exclusivity)、`sameUnsupportedSourceItemSharesOneIdentityAcrossPreserveAndWarningRows` (F9)、`sameCodeUnsupportedItemsGetDistinctProposalLocalDiscriminators` (F10) の 6 fixture を独立確認。`unidentifiedDiscriminatorByItemId` が source item key で proposal あたり 1 回採番であることを `PlanPreviewProjector.kt:269-284` で確認 (呼び出し順 counter ではない = F9 契約の成立条件)。
- spec 208 AC-3: descriptor 行列 (`OrganizationPreviewContentTest` 168 / 277 / 304 / 332 / 356 / 379 行目の 6 test) を独立確認。比較対象行の fate 部分 (移動先・理由語) を同一にして descriptor の差のみで主張している。`moveRowText` の same-band 分岐が `descriptorText(..., supplement)` を通ること (`3e35be42c5` の review High 修正)、`descriptorSupplements` が衝突する (label, kind, position) triple のみに補助語を付与することを確認。
- spec 208 AC-5 / AC-6: `PreviewPosition.InFolder` の 1-based rank (`identity.rank + 1`)、`manual_organization_preview_position_folder_existing/planned` の `%2$d` が values/ と values-ja/ の両方に存在、locks 画面語彙との一致 (`organizer_lock_screen_placement_folder` = 「フォルダ内の位置 %1$d」 vs 「フォルダ「X」内の位置 N」) を両 locale で確認。新 strings 8 種が両 locale に存在し ja ≠ en、ja `item_row` は全角コロンで en と非同一。`japaneseResourcesResolveEveryConcretePreviewString` (956行目) が新 strings を列挙。
- spec 208 AC-7: `Parcelable|Serializable|Parcel` の grep で zero hit を独立確認。
- spec 208 AC-8 / spec 194 PP-AC-01 / PP-AC-03 / PP-AC-04: `git diff main...issue-208-placement-identity -- PlanPreviewCountsCorpusContractTest.kt DestinationRegionMappingTest.kt` が空 (無変更) であることを独立確認。`identicalInputsProduceIdenticalDetails` (544行目) 通過。
- spec 208 AC-9 / AC-10 / spec 195 AC-1 / AC-2 / AC-6: `sameNamedPlacementsAcrossBucketsStayDistinguishableOnTheCard` (584行目) が各行の descriptor 要素 (kind 語 + 現在位置) を主張する構成であることを確認し、CI `organizer-instrumentation-issue52-tests` (API 36 / Platform 36.1) の成功で実行を担保。既存 a11y / truncation / 200% font scale instrumentation (`changeRowsArePlainReadableNodesWithoutLiveRegion` 899行目、`previewRemainsReadableAtTwoHundredPercentFontScale` 293行目) は期待文字列が新 format に更新されており削除・弱体化なし。

## Executed test surface

実装 session による初回実行:

- `./gradlew spotlessCheck` — pass (head `81686ed7e9` local)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` (full suite) — pass
- `./gradlew assembleLawnWithQuickstepGithubDebug` — pass
- `./gradlew compileLawnWithQuickstepGithubDebugAndroidTestJavaWithJavac` — pass

独立監査 agent による再実行 (checkout `70f4b26e5f`、`git diff --name-only 81686ed7e9 70f4b26e5f` が docs-only であることを確認済みのためコードは audited head と同一):

- `./gradlew spotlessCheck` — BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` — BUILD SUCCESSFUL in 23s (exit 0)
- `gh run view 34026994345 --repo nunu1733/NunuLauncher --json headSha,conclusion,jobs` — headSha が `81686ed7e9c420f05db282633f68cca9c90ddf52` と一致、conclusion success、13 job すべて success (`final-status`、`organizer-unit-tests`、`check-style`、`build-debug-apk`、`organizer-instrumentation-issue52/53/99/155/api35/shared-writer/db-migration-tests`)

## Findings

- 実装 PR review (PR #238 の Request changes) で指摘された same-band descriptor 経路の迂回は `3e35be42c5` で修正し、交差 fixture で固定した。独立監査 agent が修正の存在と descriptor 経路の統一を再確認した。
- CI run `34025294161` で `japaneseResourcesResolveEveryConcretePreviewString` が ja = en の `item_row` を検出した (実装側の #123 契約漏れ)。`81686ed7e` で ja を全角コロンへ変更し、新 strings を列挙に追加して解消。同 run の `organizer-instrumentation-api35-tests` の `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` 失敗は本 PR の変更範囲 (preview projection / 表示層) に接続しない DB capture 主張の差分であり、後続 run (本監査の CI run) では同一 job が success している — flake と判定し、本 PR では対応しない (再発時は別 Issue で追跡)。
- 監査レコードの精緻化 (独立監査 agent の指摘): AC-1 の「UI が identity から再計算する経路は型上存在しない」の記述には、spec 承認済みの descriptor 補助語 (`identitySupplement` による row/column 序数・stage 語の導出) が位置再計算ではない例外である旨を追記した (Criteria check 参照)。動作への影響なし。
- 残課題 (owner review 項目): 物理 device での TalkBack / Switch Access 読み上げ LQA と、spec 208 AC-5 の locks 画面語彙一致の視認性確認。code / instrumentation level の evidence は本監査で確認済み。
