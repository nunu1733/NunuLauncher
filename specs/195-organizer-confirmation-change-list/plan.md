# Implementation Plan: Organizer confirmation UI renders the concrete change list

> Issue: #195
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

- `State.Preview(summary, details: PlanPreviewDetails?)` は spec 194 で導入済み (`lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:144`)。coordinator は `Previewed` / 環境的失敗 / fail-closed を分岐済みで、本 Issue で coordinator を変更する必要はない。
- preview projection 型: `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` — `PreviewChange` (Move / Preserved / NewFolder / NewPage / ItemWarning)、`PreviewLabel` (`Named` / `KindFallback(CanonicalItemKind)`)、`PreviewPosition` (`Workspace(pageDisplayOrdinal, isNewPage, rowBand, columnBand, rowOrdinal)` / `DockRank` / `InFolder` / `InAppPair`)、`MoveChange.sameBandAdjustment`、`PreviewCounts`。`NewFolderOrdinal` / `NewPageOrdinal` は 0-based (`planning/Identity.kt:163-176`)、`NewPageChange.displayPosition` は 1-based、`DockRank.rank` は 0-based (文言側で +1)。
- projection の決定的順序: `application/preview/PlanPreviewProjector.kt` — action 順の move / preserve 行、ordinal 昇順の folder / page 行、`Planned.warnings` 宣言順の警告行。
- 現行 UI: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` — `State.Preview` は `FocusTargetText` (heading, liveRegion, focus 復帰) + `summaryItems(summary)` + confirm / cancel (`:153-173`)。`summaryItems` は scope / device / 変更件数 (Summary truth) / constraints を1つの `LazyListScope` 拡張に持つため、context 行と件数行の分離が必要。
- strings: `lawnchair/res/values/strings.xml` と `lawnchair/res/values-ja/strings.xml` の `manual_organization_*` block。既存 count 文言 (`manual_organization_moved_count` 等) はヘッダ行として PreviewCounts truth で再利用する。
- test: instrumentation は `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` (FakeApplication が `inspectPlan` → `WriterBusy` の count-only mode で既存 test が走る)。CI の `organizer-instrumentation-issue52-tests` job が同 class を API 36 / Platform 36.1 で実行し、UI evidence を upload する。JVM unit test は `tests/unit/app/lawnchair/organizer/` 配下 (`app.lawnchair.organizer.*` filter で CI gate 対象)。

## Design

### Modules and interfaces

```text
lawnchair/src/app/lawnchair/organizer/ui/
└── OrganizationPreviewContent.kt   # 新規: 純粋な行構築 module (UI 文言は注入)
    ├── OrganizationPreviewWording  # interface: 解決済み format 文言の集合
    ├── OrganizationPreviewSection  # group 見出し + 行 text 一覧 (決定的)
    └── organizationPreviewSections(details, wording): List<Section>

lawnchair/src/app/lawnchair/ui/preferences/destinations/
└── ManualOrganizationPreferences.kt # State.Preview 分岐: details mode / degraded mode、
                                     # summaryItems の分離 (context / change counts / constraints)、
                                     # truncation + 展開 UI state、a11y semantics
```

- 行構築 (位置語合成、same-band 判定の行形式、行序数 note、理由語) は organizer ui 配下の純粋関数に置き、`stringResource` は composable 側の `OrganizationPreviewWording` 実装のみに閉じる。JVM unit test が同じ seam を文言リテラルで検証する。
- `ManualOrganizationRun` (coordinator) は変更しない。

### Preview 画面の構成 (details mode)

```text
heading (既存, focus + liveRegion)
scope 行 / device 行            ← Summary (入力文脈, 両 mode 共通)
件数ヘッダ (moved / preserved / new folders / new pages / warnings)
                                ← PreviewCounts (AC-1 truth 契約)
group: 移動 → 新規フォルダ → 新規ページ → 保持 → 警告   ← PreviewChange 行 (先頭5件 + 展開)
constraint 行                    ← Summary (入力文脈, 両 mode 共通)
confirm / cancel (既存)
```

degraded mode (`details == null`): 告知行 + 現行 `summaryItems` 全文 + confirm / cancel (既存表示の維持 + 告知のみ追加)。

### 文言設計

- 追加 strings 約58個 × (`values/` + `values-ja/`): group 見出し、行 format (move / same-band / item)、行序数 note、領域語 9 種、page / dock / folder / app pair 位置語、kind fallback 8 種、移動理由 4 種、保持理由 10 種、項目警告 3 種、新規フォルダ / 新規ページ行、展開 / 折りたたみと state 語、degraded 告知。
- 既存 strings の再利用: heading、件数ヘッダ、scope / device / constraint、confirm / cancel、No changes / stale。

### Alternatives rejected

- **`details == null` で confirm を block する案**: 環境的失敗が機能全体の hard blocker になり、A2 gate が既に保証する安全を二重に犠牲にする。D1 で不採用 (degraded 告知で観測可能化)。
- **行 text を composable 内で直接組む案**: 位置語合成と行形式判定が JVM test できなくなる。純粋 module + wording 注入で分離 (AGENTS.md の副作用のない計画 module 規約)。
- **fail-closed 終了の専用文言**: `State` が区別を持たない以上、文言だけ分けられない (spec D4)。不採用。
- **truncation を全体 1 toggle にする案**: group 件数と表示行数の一致 (行 ↔ ヘッダ) が崩れる。group ごと採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/ui/OrganizationPreviewContent.kt` (新規) | 純粋行構築 module + wording interface | JVM test 可能な単一 seam、UI 文言の注入点 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | `State.Preview` の details / degraded 分岐、`summaryItems` の分離、group 描画、truncation / 展開、a11y semantics | spec scope 1, 3, 4 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 追加文言 (spec D3 含む) | spec scope 2, #123 契約 |
| `tests/unit/.../organizer/ui/OrganizationPreviewContentTest.kt` (新規) | grouping、位置語、same-band、行序数、理由語、counts truth | AC-1, AC-2 |
| `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` (拡張) | 5 fixture (移動のみ / フォルダ / 新規ページ / 変更0 / degraded) + truncation + a11y + 200% + ja/en parity | AC-3〜AC-7 |
| `specs/52-manual-full-organization-vertical-slice/spec.md` | §Preview and details へ描画契約所在を追記 | AC-8 |
| `specs/195-.../spec.md`, `plan.md` | 本 spec / plan | workflow |

## Migration and recovery

該当なし。zero-write の表示層であり、DB / schema / rule / recovery format への影響はない。release rollback でも既存 count-only 表示へ戻るのみ。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1, AC-2 | `OrganizationPreviewContentTest` | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-3〜AC-7 | `ManualOrganizationPreferencesInstrumentationTest` 拡張 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest` (API 36 emulator) / CI `organizer-instrumentation-issue52-tests` |
| 全体 | formatting + organizer JVM gate + debug build | `./gradlew spotlessCheck` / `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` / `./gradlew assembleLawnWithQuickstepGithubDebug` |

含めるべき観点: unit/contract (純粋行構築の決定性、境界)、UI fixture (代表 5 ケース + truncation + degraded + a11y + locale)。DB / integration は本 Issue の変更外。

## Documentation updates

- [x] spec / plan (本 directory)
- [ ] spec 52 §Preview and details 追記 (実装 PR で適用)
- [ ] CONTEXT.md — 影響確認の結果、用語追加不要 (`plan preview` 用語は #194 で導入済み、新 domain 概念なし)
- [ ] DESIGN.md — 影響確認の結果、変更不要 (module 構造・不変条件に影響なし)
- [ ] ADR — 不要 (判断は spec §Design decisions D1–D6 に記録。ADR 3条件の「実際の選択肢があった」判断は spec 承認で管理される範囲)
- [ ] AGENTS.md — 変更不要

## Risk label 判断

`risk: layout-data` / `risk: migration` は付与しない。本 PR は coordinator / `LayoutApplicationModule` / DB / recovery への変更を含まない表示層のみの差分であり、高リスク path (layout data / migration) を触らない。organizer JVM gate と Issue 52 instrumentation job は通常の required evidence として source PR で実行される。

## Execution checklist

- [x] 現行 UI / coordinator / projection 型の trace 完了。
- [x] `OrganizationPreviewContent.kt` + unit test を interface 経由で実装 (test 先行)。
- [x] `ManualOrganizationPreferences.kt` の details / degraded 分岐 + truncation + a11y 実装。
- [x] strings (`values/` + `values-ja/`) 追加。
- [x] instrumentation test 拡張 (5 fixture / truncation / a11y / 200% / ja-en parity)。
- [x] spec 52 追記。
- [x] `./gradlew spotlessCheck` + organizer JVM gate + `assembleLawnWithQuickstepGithubDebug` + instrumentation 実行結果を PR へ記録。
