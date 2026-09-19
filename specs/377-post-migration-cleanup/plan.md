# Implementation Plan: TO-BE移行で余剰となった実装・文言・test oracleの整理

> Issue: #377
> Spec: [spec.md](./spec.md)
> Status: draft（inventory/criteria契約としてのplan。依存Issue merge後のinventory確定まで実装に進まない）

## Current evidence

以下はbase `a2b6aba318`（2026-09-19確認。本plan作成時の`origin/main`）で直接確認済みの事実である。**依存Issue（#368/#369/#373/#374）merge後の状態は未確認であり、推測として扱わないこと。**

- reconciliation decision table三重実装（監査§11.2の指摘どおり現存）:
  - pure `LifecycleReconciler`（object）: `lawnchair/src/app/lawnchair/organizer/application/lifecycle/LifecycleReconciler.kt`（360行）
  - `RestartReconciler`（internal class）: `lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt`（526行）。`classify`/`classifyMeta`が`writer.classifyAuthoritativeState`へ委譲しつつinline分類を持つ（L488-490）
  - `ApplyProtocol.classifyApplyOutcome`: `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt:267`。`Ports.classifyAuthoritativeState`（`Ports.kt:74`）と併存
  - 関連契約test（修正なし通過が統合の証拠になる対象）: `tests/unit/app/lawnchair/organizer/application/lifecycle/LifecycleReconcilerTest.kt`、`tests/unit/app/lawnchair/organizer/application/protocol/RestartReconcilerTest.kt`、`tests/unit/app/lawnchair/organizer/application/protocol/LayoutApplicationModuleRecoveryEntryTest.kt`、`tests/unit/app/lawnchair/organizer/application/protocol/ReadinessGateTest.kt`、instrumentation `tests/organizer-instrumentation/app/lawnchair/organizer/application/`配下
- `RecoveryPreviewSummary`: `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryPreview.kt:34`。`RecoveryPreviewEffect`は`RESTORE_SAVED_LAYOUT`のみ（閉域語彙サイズ1）、全fieldが定数default。生成箇所は`application/protocol/RecoveryPreviewProtocol.kt:100`の`RecoveryPreviewSummary()`。production参照は`RecoveryPreview.kt`/`RecoveryPreviewProtocol.kt`外で直接参照なし（test参照は`RecoveryPreviewContractTest`等）
- export usage重複: item毎`usage: UsageProjection`（`personalization/ContextExportModels.kt:231/329`）とenvelope `usageSignals: UsageSignalsSection`（同L368）が併存。builderは`ContextExportBuilder.kt:177`で`buildUsageSection`を組む
- import attempt freeze実装箇所（現行。#374再設計後の残骸はここを起点にinventory）: `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`、`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
- `Trigger.INCREMENTAL_PROPOSAL`: `lawnchair/src/app/lawnchair/organizer/diagnostics/model/Trigger.kt:12`に定義のみ（production emitなし。監査D-7）。**維持**（FR-008/D-004の値域、disposition §4.3）
- `LOCAL_FULL`: `personalization/ContextExportBuilder.kt`・`ContextExportModels.kt`に存在。UI到達不可でbuilderは`EXTERNAL_WITH_LABELS`と同一扱い（監査D-2）。**維持**（D-14、disposition §4.3）
- organizer系strings計数（計上方法が定まっていないことの証拠。全てbase `a2b6aba318`での簡易計数）:
  - 監査の計上: 「organizer系約407項目」（`docs/assessment/organizer-as-is-ux-data-flow-audit.md` L869、対象`lawnchair/res/values/strings.xml`）/ values-ja 426
  - `name="organizer` prefix一致: values 83件 / values-ja 83件
  - organizer code（`lawnchair/src/app/lawnchair/organizer/` + launcher3側）からの`R.string`参照distinct数: 約194件
  - `lawnchair/res/values/strings.xml`の総string数1011 / values-ja 413
  - 監査の「約407」は上記いずれとも一致しないため、監査値は参考値とし、inventoryで計上方法（prefix / 参照 / translatable対象等）を確定して記録する

## Gating（着手条件。全て満たすまで実装に進まない）

1. #368、#369、#373、#374が全てmerge済みであること（disposition §8 graph: D368/D369/D373/D374 → D377）。
2. 最新`origin/main`を取得し、本planのCurrent evidenceと`git log`/`git diff`で差分照合すること。特に:
   - #368merge後の`RestartReconciler`残存範囲（restart path廃止で縮小する可能性）
   - #374merge後のfreeze実装の再設計結果
   - #369/#373merge後の文言・表示実装
3. 以下のinventoryを実行し、change setを確定してspec/planを必要なら改訂してから実装へ進む。

## Design

### Inventory procedure（確定手順）

1. 差分照合: `git log --oneline <本plan baseline>..origin/main -- lawnchair/src/app/lawnchair/organizer tests/organizer-instrumentation tests/unit/app/lawnchair/organizer lawnchair/res` 等で対象pathの変化を列挙する。
2. 対象列挙（spec Scope §2の各領域）:
   - **reconciliation**: #368merge後の三実装（あるいは縮小後の残存実装）の分類責務対応表を作る。各実装が担当するdecision行を列挙し、重複行・正本行を特定する。
   - **RecoveryPreviewSummary**: 生成箇所・消費箇所・test参照を列挙し、seam簡素化（閉域語彙サイズ1の定数をseam外へ下ろす等）の影響範囲を確定する。実施 / 見送りをAC-4の記録対象とする。
   - **usage重複**: envelope `usageSignals`とitem毎`usage`の参照関係（spec 204契約・AI-facing instruction・validator/codec）を確認し、schema v5（spec 204改訂）を要するかを評価する。本Issueの成果は評価記録まで。
   - **freeze残骸**: #374merge後のfreeze実装から、status card＋T-18中心への再設計で残った個別無効化を列挙する。
   - **旧UX oracle**: #368/#369/#373/#374の各PRが記録したobsolete理由一覧を入力に、対応漏れのtest（旧挙動を固定するassertion）を検索する。
   - **strings**: 計上方法を確定（例: organizer code参照集合を起点にres側を逆引き）し、production/test参照0件のstring/resourceを列挙する。`values-ja`対を必ず同時に扱う。
3. 分類: 各候補を「統合 / 削除 / 維持」へ分類し、spec §3の削除3条件（production参照0件 / 契約値・将来値域でない / 根拠記録可能）を満たすかで判定する。満たさないものは維持し理由を記録する。
4. change set確定後、PRを起こす。分割推奨: (a) reconciliation統合、(b) resource/strings削除、(c) test oracle整理。挙動不変の変更とformat-only変更を混ぜない（AGENTS.md基本動作）。

### Modules and interfaces

- 統合はorganizer/application内部の重複解消であり、**公開seam（`application/public/**`、`RecoveryPreviewResult`等）の契約は変えない**。呼び出し側とtestは既存seamを使い続ける（AGENTS.md: 呼び出し側とテストは同じseamを使う。内部実装を直接検証しない）。
- 統合先の選択（三実装のどれを正とするか）はinventory §2の対応表に基づき確定する。pure計画側（`LifecycleReconciler`）と適用protocol側（`ApplyProtocol`）の責務分離（配置計算と反映の分離、AGENTS.md設計規約）は維持する。
- `RecoveryPreviewSummary`の簡素化を実施する場合も、`RecoveryPreviewResult`の閉域・read-only契約（spec 84系）は変えない。

### Alternatives rejected

- 「削除優先で一括cleanup PR」: 依存merge前の推測削除と、safety mechanismの支え手（監査§11.1(b)）を誤って除去するリスクがあるため採らない。判断基準 + inventoryを先に固定する。
- 「本Issueでspec 204 schema v5改訂も実施」: 契約変更と整理の混在を避けるため別起票（disposition §11の正本規定どおり）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/{lifecycle,protocol}` | reconciliation分類の単一実装への統合（#368merge後の形を入力にinventoryで確定） | 監査§11.2、disposition §11（設計所有#377） |
| `lawnchair/src/app/lawnchair/organizer/application/{public,protocol}` | `RecoveryPreviewSummary` seam評価（実施なら簡素化、見送りなら記録） | Issue scope、監査§11.2 |
| `lawnchair/src/app/lawnchair/organizer/personalization` | export usage重複の評価記録（実施は別Issue） | Issue scope、disposition §11 |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange`、`ui/preferences/destinations` | freeze残骸清掃（#374merge後の残存個別無効化） | Issue scope |
| `tests/unit/`、`tests/organizer-instrumentation/`（organizer配下） | 旧UX oracleの削除・更新とobsolete理由のPR記録 | Issue AC-2、disposition §1 |
| `lawnchair/res/values/strings.xml`、`values-ja/strings.xml` | 未使用organizer系string削除（en/ja対） | Issue AC-3 |

## Migration and recovery

- 永続migrationなし。schema/store/recovery pointは不変。
- rollback: 通常のgit revert。release rollback/downgradeへの特別配慮は不要（挙動不変）。
- backup/restore compatibility: 影響なし（resource削除は参照欠落を起こさない範囲に限定）。
- risk label: reconciliation統合が`organizer/application/**`（high-risk path）を触る。変更範囲がlayout data pathに触れる場合、PRで`risk: layout-data`相当の評価を行い、必要ならlabelを付与する（IssueのRisk指示）。高リスク判定になった場合はAGENTS.mdの独立エビデンス要件に従う。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | organizer系unit + instrumentation test修正なし全greenの実行記録 | 既存gradle test task（building guideの検証済みcommandに従う） |
| AC-2 | PR本文のobsolete理由記録（test名・旧挙動・obsolete根拠・置換oracle） | manual（PR記述） |
| AC-3 | 未使用判定scan証跡 + build成功 + en/ja diff対応 | `./gradlew assembleLawnWithQuickstepGithubDebug`、`./gradlew spotlessCheck` |
| AC-4 | 評価記録（`RecoveryPreviewSummary`・usage重複の実施/見送りと根拠） | manual（PRまたは本spec更新） |

含めるべき観点: 既存契約test（unit/contract）の修正なし通過が主証拠。failure injection系・recovery系testも修正なしで通ることを確認（safety mechanism不変の証拠）。新規testの大量追加はしない（統合の証拠は既存契約testの通過。新規挙動はないため新規oracleは原則不要）。

## Documentation updates

- [ ] spec status/history（inventory確定時に本specを改訂。挙動不変のため`implemented`到達はAC evidenceで判断）
- [ ] CONTEXT.md（domain language変更時。本整理は用語追加を想定しないが、統合結果でreconciliation用語の説明が変わる場合に検討）
- [ ] DESIGN.md（system structure変更時。統合でmodule構成が変わる場合のみ）
- [ ] ADR（統合先選択が「変更が高コスト・理由がコードから分からない・実際の選択肢があった」の3条件を満たす場合。inventory §2の対応表を根拠に判定する）
- [ ] AGENTS.md（検証済みcommandの変更時のみ。新規必須commandは追加しない）

## Execution checklist

- [ ] 着手条件（Gating 1〜3）を確認した（依存4 Issueのmerge、baseline差分照合、inventory完了）。
- [ ] inventoryの分類表（統合 / 削除 / 維持 + 根拠）をPRまたはIssueへ記録した。
- [ ] 既存test修正なしでの統合を確認した（挙動差異はspecのfailure scenarioに従い処理）。
- [ ] 削除したoracleごとのobsolete理由をPRへ記録した。
- [ ] en/ja resourceの対処を確認した。
- [ ] 全relevant検証を実行し、PRへ結果を記録した。
- [ ] specのAC-4評価記録を残した。

## Unverified areas（本planが現時点で検証していない範囲。推測で埋めない）

- #368/#369/#373/#374 merge後の実コード状態: restart path廃止後の`RestartReconciler`残存形、freeze再設計後の残骸の実際の残り方、文言変更後のstrings集合。
- organizer系stringsの正確な計上方法と実未使用数（監査「約407」と本確認のprefix 83件 / 参照約194件の乖離の解消）。
- 統合先実装の選択（三実装のどれを正とするか）と、ADR要求の3条件該当性。
- `RecoveryPreviewSummary`簡素化の実施 / 見送り判断。
- export usage重複がschema v5（spec 204改訂）を要するか。
- 依存IssueのPRで記録されるobsolete理由一覧の内容（本plan作成時点で未mergeのため参照不能）。
