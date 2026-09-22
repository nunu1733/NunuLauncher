---
issue: "#377"
status: draft
requirements: []
updated: 2026-09-22
---

# TO-BE移行で余剰となった実装・文言・test oracleが、判断基準とobsolete理由の記録つきで整理されている

> Status: **draft**（本specは準備taskが作成したdraftであり、owner受入は別途行う）
> 正本参照: [disposition](../../docs/product/organizer-disposition-migration.md)（accepted。§1「削除時は旧oracleのobsolete理由を記録する」、§4.3 Defer、§8 backlog、§11「reconciliation統合の設計: #377が所有」）、[TO-BE](../../docs/product/organizer-to-be-ux.md)（D-14）、[AS-IS監査 §11.2](../../docs/assessment/organizer-as-is-ux-data-flow-audit.md)（accidental complexity候補）、[spec 13](../13-safe-layout-application/spec.md)（§"Transaction outcome classification"、§"Restart reconciliation"がdecision tableの受入済み正本）

## Problem

TO-BE移行（#365〜#376）の結果、旧UX・旧構造を固定する実装・文言・test oracleが残る。disposition（accepted, 2026-09-19）は、実装・store・navigation・resource・testの削除判断を#377が所有すると定め（disposition §8末尾）、reconciliation decision table統合の設計とexport文書usage重複の評価記録を#377に委譲している（disposition §11）。

依存Issue（#368/#369/#373/#374）は全てmerge済みである（2026-09-22確認）。現時点（base `c52d5fcc15`、2026-09-22確認）で観測可能な余剰:

- reconciliation decision tableの重複実装（監査§11.2）:
  - pure `LifecycleReconciler`（`application/lifecycle/LifecycleReconciler.kt`）: `reconcile` / `classifyApplyOutcome` / `classifyRecoveryOutcome`はproduction経路から**呼ばれていない**（production参照は`SUPPORTED_FORMAT`定数のみ: `RecoveryProtocol.kt:232`、`RestartReconciler.kt:284`、`RecoveryPreviewProtocol.kt:129`）。直接実行するのはunit test `LifecycleReconcilerTest`のみ
  - 生存実装はprotocol側の2系統: `RestartReconciler.reconcileWithLease`（restart path、`application/protocol/RestartReconciler.kt:296-373`）とin-flight系 `ApplyProtocol.classifyApplyOutcome`（`application/protocol/ApplyProtocol.kt:267-326`）+ `RecoveryProtocol` inline分類（`application/protocol/RecoveryProtocol.kt:165-189`）
  - 3実装は同値コピーではなく、行単位の差異を持つ（例: READY×非PRE_STATEで`LifecycleReconciler`は`SilentPrune`、`RestartReconciler`はfail-closedな`Unresolved(COMMIT_OUTCOME_UNKNOWN)`。RESTORING×REVIEWED_CURRENT_STATEで前者はNotCommitted、後者はrecovery再試行。gate: format不整合で`LifecycleReconciler`は`INCOMPATIBLE`遷移、`RestartReconciler`はlifecycle維持の`Unresolved`）。差異の突合・裁定は本specの要件である（Scope §1）
  - なおdigest比較primitive（`Ports.classifyAuthoritativeState`、`Ports.kt:74`。実装はadapter）は既に単一実装であり、重複しているのはclassification結果から次状態・公開結果へのdecision tableである
- `RecoveryPreviewSummary`が閉域語彙サイズ1（`RecoveryPreviewEffect`は`RESTORE_SAVED_LAYOUT`のみ）で公開seam（`application/public/RecoveryPreview.kt`）を通っている。`RecoveryPreviewResult.Restorable`の必須fieldとしてspec 84（accepted）が明示する公開契約である
- export文書内のusage重複（envelope `usageSignals` + item毎`usage`。`personalization/ContextExportModels.kt`、`ContextExportBuilder.kt`）

依存Issueのmergeで確定済みとなった残対象:

- import attempt freezeの表示統合残骸（#374によるfreeze再設計後に残る個別無効化）
- 旧UXを固定するtest oracle（strategy picker run面配置・strategy restart抑制・選択面必須通過・typed失敗文言直接表示等）のうち、各移行Issueで対応しきれなかった残り
- organizer系stringsの未使用項目

整理基準を契約にせず個別判断で削除すると、safety mechanismの支え手（監査§11.1）や将来契約の値域（FR-008/D-004のtrigger契約、D-14のlocal LLM向け余地）を誤って壊すリスクがある。

## Outcome

依存Issueのmerge完了後の時点で、本specの判断基準に基づくinventoryが実行され、各余剰候補が「統合 / 削除 / 維持」のいずれかに根拠つきで分類され、分類に従って整理が実行される。**挙動不変の対象は生存production経路の観測可能な振る舞いである**（reconciliationの裁定規則はScope §1）。obsolete oracleの削除には、その理由がPRに記録されることを要求する（disposition §1の正本規定の実施）。

## Scope

### 1. 現時点で確定している対象（base `c52d5fcc15`で観測済み）

#### 1-a. reconciliation decision tableの統合（監査§11.2、disposition §11）

統合に先立ち、**現行decision matrixの突合と差異の裁定**を必須手順とする:

1. **matrix突合**: 3実装について `(path context × lifecycle × AuthoritativeClass) → (次lifecycle・公開結果・副作用)` の現行decision matrixを行ごとに突合し、差異行を明示する。plan.mdのCurrent evidenceに`c52d5fcc15`時点のmatrixと既知差異を記録する。実装中に新たな差異行が見つかった場合も同じ手順で追加する。
2. **差異の裁定規則**: 各差異行について、受入済み正本（spec 13 §"Transaction outcome classification" / §"Restart reconciliation"、spec 174 / ADR-0009 containment）を根拠に正本挙動を決める。裁定は次のいずれかとする:
   - (a) **生存pathのbug**: 本Issueの整理対象外とし、別bug Issueまたは明示的な契約改訂へ分離する。統合に混ぜない。
   - (b) **path context依存の意図した差**（例: 同じAPPLYING×PRE_STATEでもrestart reconciliationは`SilentPrune`、in-flight outcome classificationは`RolledBack`をcallerへ返す）: 統合後の単一decision tableの入力にpath contextを明示的にモデル化して保持する。差を潰してuniform化しない。

   裁定実施状況（base `c52d5fcc15`。正本記録はplan.md Current evidenceのmatrix・裁定）: A（production到達不能）側の矛盾行は削除候補、生存実装間の差異は規則(b)。ただし**gate: format不整合の1行は生存実装の挙動（lifecycle維持の`Unresolved`、`advance(*, INCOMPATIBLE)`のproduction callerなし）がspec 13の`unsupported version -> INCOMPATIBLE`状態図から乖離しており、`RecoveryStore.rowToRecordRead()`がcodec record decodeのformat rejectを通さないためproduction到達可能である。よって規則(a)「生存pathのbug」としbug #407へ分離した**。#407の修正は本Issueの対象外。reconciliation統合・characterization testは#407解決後の正本挙動（INCOMPATIBLE遷移）を基準に固定し、bug挙動をcharacterization固定しない（統合着手のgate。plan Gating 4）。
3. **挙動不変の意味**: 「単一実装への統合」は、裁定済みmatrixが生存pathで前後同一であることを指す（gate: format行は#407解決後の正本挙動を基準とする）。production到達不能な実装（`LifecycleReconciler.reconcile/classify*`）の行は観測可能な振る舞いではないため、その削除自体は挙動変更ではない。ただし削除は3条件（§3）と、当該行の差異が全て裁定済みであることを条件とする。
4. **characterization test**: 統合の実施前に、裁定済みmatrixを行列表として固定するtable-driven characterization testを生存seam（`RestartReconciler`、`ApplyProtocol`、`RecoveryProtocol`の契約testが使う既存test seam）経由で追加する。統合前後でこのtestが同一結果を返すことをAC-1の主証拠とする。

#### 1-b. 維持判断記録（契約値・将来値域）

- `Trigger.INCREMENTAL_PROPOSAL`（未使用enum、監査D-7）の**維持**: FR-008（Later再開時のtrigger契約）とD-004が参照する値域であり、削除の利益が小さい（disposition §4.3）。本specが維持を明記する。
- `LOCAL_FULL` tier契約値の**維持**: D-14（UI語彙からは外すが契約値は将来のlocal LLM #206向けに維持。disposition §4.3）。本specが維持を明記する。

#### 1-c. `RecoveryPreviewSummary` seam簡素化の評価（実施 / 見送りの記録のみ）

- `RecoveryPreviewResult.Restorable`の`summary` fieldはspec 84（accepted）が明示する公開seam契約である。したがって本Issueで実施を許すのは**公開shapeを一切変えない内部簡素化**（生成箇所の整理等）に限定する。
- `summary` field・型・`RecoveryPreviewEffect`語彙の変更・削除はspec 84 / DESIGN / consumersの改訂を要求する契約変更であり、**別Issueへ分離する**。本Issueの成果は、その要否を含む評価記録である。

#### 1-d. export文書usage重複の解消評価

- envelope `usageSignals` + item毎`usage`の解消評価（実施 / 見送りと理由の記録を成果とする）。schema v5影響はspec 204改訂を別途要する場合は本Issueでは調査記録のみ。実施は別起票（disposition §11の正本規定）。

### 2. 依存merge後にinventoryで確定する対象

- import attempt freezeの表示統合残骸（#374再設計後の個別無効化の清掃。現行の個別実装位置は`organizer/ui/exchange/ExchangeFlowUi.kt`と`ui/preferences/destinations/ManualOrganizationPreferences.kt`を起点にinventoryで再確認）
- 旧UXを固定するtest oracleの削除・更新（#368: run dismiss+再start、#369: 個別失敗面・選択面必須通過、#373: typed失敗文言20種の直接表示、#374: freeze 4箇所個別実装。各移行IssueのPRが記録したobsolete理由を入力に、対応漏れを検出する）
- organizer系stringsの使用状況点検と不要文言の削除（監査は「約407項目」と計上するが、`name="organizer` prefix一致83件 / organizer codeからの`R.string`参照約190件であり、計上方法の確定自体がinventoryの一部。詳しくはplan.md）

### 3. 手続契約（全整理共通）

- 削除の3条件: production経路からの参照0件、維持判断済みの契約値・将来値域でない、obsolete/未使用の根拠をPRに記録できる。
- 削除したtest oracleごとに、固定していた旧挙動とobsolete理由をPRへ記録する（disposition §1）。
- 削除候補でも3条件を満たさないものは維持し、理由を記録する。

## Non-goals

- 機能変更・契約変更（整理のみ。生存経路の観測可能な振る舞い・公開seam契約は不変）
- **spec 84の公開seam契約変更**（`RecoveryPreviewResult.Restorable`の`summary` field・型・effect語彙の変更・削除。評価記録のみ。実施はspec 84改訂を要求する別Issue）
- spec 204のschema v5改訂の実施（usage重複の解消にschema変更が必要と評価された場合は別Issueで行う）
- bug #407（gate: format不整合行のINCOMPATIBLE遷移欠落）の修正（規則(a)で分離済み。#377は#407解決後の正本挙動を前提に統合するのみ）
- `Trigger.INCREMENTAL_PROPOSAL` / `LOCAL_FULL`契約値の削除（上記のとおり維持）
- safety mechanismの変更: revision二重確認（A2事前 + A5 in-transaction）、checkpoint → atomic write → 相関reload検証、scope binding gateの完全一致検証、fail-closed（監査§11.1）は統合によっても弱めない。裁定規則(a)によるbug分離を除き、生存pathのfail-closed挙動をuniform化の名の下に緩めない
- `app.lawnchair.deck`の調査、並行する分類・配置機構の追加（AGENTS.md設計規約）
- 表示文言の新規変更（語彙規約D-13の適用は#369/#373/#374のscope。本Issueはその残骸清掃のみ）

## Domain language

- **余剰（surplus）**: production経路から到達不能な実装・文言・resource、または同一decisionを複数の実装が重複して保持している状態。統合・削除の候補になり得る。
- **obsolete oracle**: 過去のUX・契約を固定し、移行後の正本挙動と矛盾するtest。削除時にobsolete理由のPR記録を要求する（disposition §1）。
- **inventory**: 本specの判断基準を依存merge後の最新mainへ適用し、余剰候補を「統合 / 削除 / 維持」へ分類する手順。plan.mdで手順を定める。
- **裁定済みmatrix（adjudicated matrix）**: 3実装のdecision matrix突合の結果、差異行ごとに正本挙動が裁定された状態のmatrix。統合前後の挙動同一性の基準（Scope §1-a）。

## Behavior scenarios

整理は挙動不変の変更であるため、観測対象は「整理後の挙動同一性」と「整理の証跡」である。

### Scenario: reconciliation統合前のmatrix固定

Given 3実装のdecision matrixが突合され、差異行が裁定規則(a)/(b)で裁定された状態（gate: format行はbug #407解決後の正本挙動を基準）
When 裁定済みmatrixを固定するtable-driven characterization testが生存seam経由で追加される
Then 全行（path context × lifecycle × AuthoritativeClass）について、裁定済み正本挙動と一致する結果が固定される（統合実施前の時点で。規則(a)分離行のbug挙動は固定しない）

### Scenario: reconciliation統合後の挙動同一性

Given 裁定済みmatrixを固定したcharacterization testと、既存の契約・回帰test（`RestartReconcilerTest`、`LayoutApplicationModuleRecoveryEntryTest`、`ReadinessGateTest`、`RecoveryProtocolTest`、instrumentation test `tests/organizer-instrumentation/app/lawnchair/organizer/`配下等）が存在する状態
When 統合が実施され、これらのtestを実行する
Then characterization testが統合前と同一結果を返し、生存pathの契約・回帰testは修正なしで通る
And safety mechanism（recovery適用・stale・fail-closed）を固定するtestも修正なしで通る

### Scenario: 使用されていないstringの削除

Given inventoryでproduction/test参照が0件と判定されたorganizer系string（参照scanはcode（`R.string`参照）に加え、XML layout/manifest/resource alias等の非code参照を含む全対象に対して実施する）
When 削除が適用される
Then en（`lawnchair/res/values/strings.xml`）とja（`values-ja/strings.xml`）から対で削除され、検証済みbuild command（[building guide](../../docs/engineering/building.md)）が成功する
And 参照欠落（resource not found）が発生せず、TalkBack用label等のアクセシビリティ文言が欠落しない

### Scenario: 削除基準を満たさない候補の維持

Given 削除候補として挙がったが契約値・将来値域として維持中の項目（`Trigger.INCREMENTAL_PROPOSAL`、`LOCAL_FULL`等）
When inventoryが分類される
Then 削除されず、維持理由がPRへ記録される

### Scenario: 統合中の挙動差異検出（failure）

Given 統合作業中にcharacterization testまたは既存testが失敗し、生存pathの挙動差異が検出された
When 差異が裁定規則(a)の生存pathのbugであると受入済みspecを根拠に証明できる場合を除いて統合を進める
Then 統合を止め、差異をIssue/PRへ記録して裁定規則に従い処理する（差異を残したまま統合を完了しない。(a)該当時は分離したbug Issueの解決後に再開する）

## Data and state

- 永続data形式の変更なし。organizer系store（`organizer_strategy_selection`等）・export schema（現行v4相当）・recovery store・recovery pointの読み書きは不変。
- 変更はcode・resource・testの削除・統合のみ。migration、backup/restore、rollback（git revertで復元可能）への影響なし。
- usage重複の解消を実施する場合（本Issueでは評価記録まで）はexport schema変更を伴い得るため、migration/compat評価込みのspec 204改訂を別途要求する。

## Permissions, privacy, and security

None（追加・変更なし）。export文書usage重複の評価は外部開示内容・disclosure動作を変更しない。解消の実施が必要と評価された場合は、privacy影響評価を含むspec 204改訂を先に行う。

## Accessibility and localization

- strings削除はen/ja対で行い、参照欠落を起こさない。
- TalkBack label・content descriptionのうちproduction参照が残るものは削除しない。
- freeze残骸清掃（#374後）で表示を除去する場合、残る操作のaccessibility（focus・label・読み順）を壊さない。

## Acceptance criteria

- [ ] AC-1: reconciliation decision tableの統合において、(1) 現行3実装のmatrix突合と差異行の裁定（規則(a)/(b)）が記録され、(2) 裁定済みmatrixを固定するtable-driven characterization testが統合前に存在し、(3) 統合後にcharacterization testが同一結果を返し、生存pathの既存契約・回帰testが修正なしで通る
- [ ] AC-2: 削除したtest oracleごとにobsolete理由がPRに記録されている
- [ ] AC-3: 使用されていないorganizer系string/resourceが削除されている（en/ja対、参照欠落なし。参照scanはcode参照とXML/manifest/resource alias等の非code参照の双方を対象とし、検証はbuilding guideの検証済みcommandで行う）
- [ ] AC-4: 評価対象（`RecoveryPreviewSummary` seam・export usage重複）について、実施または見送り理由が記録されている。`RecoveryPreviewSummary`は公開shape不変の内部簡素化に限定し、公開shape変更が必要という評価の場合はspec 84改訂を要求する別Issueの起案を記録する

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | (1) plan.md Current evidenceのmatrix・差異裁定記録（実装中の追記込み） + (2) characterization testの追加commit（統合前の時点で存在） + (3) 同testの統合前後同一結果と、生存pathの既存test修正なし全greenの実行記録（building guide検証済みcommand。PRに実行コマンドと結果を記録） |
| AC-2 | PR本文のobsolete理由記録（対象test、固定していた旧挙動、obsolete根拠、置換oracleの有無） |
| AC-3 | 未使用判定の参照scan証跡（code + 非code参照の双方） + `./gradlew assembleLawnWithQuickstepGithubDebug` 成功 + en/ja diffの対応確認 |
| AC-4 | PR本文または本spec更新内の評価記録（実施 / 見送り、根拠、実施が必要な場合の後続Issue案） |

## Open questions

以下は本Issueの成果そのものであり（inventory・評価の記録が終了条件）、spec受入を阻む未決定の製品判断ではない:

- 統合後の単一実装の位置と形状（shared classifierへのpath contextのモデル化方法）: 裁定済みmatrix（plan.md Current evidence）を入力に、実装PRの設計として確定する。選択が変更困難な判断になる場合、planに従いADR候補として評価する。
- usage重複の解消にschema v5（spec 204改訂）が必要か: 評価記録の成果。実施が必要な場合は別起票する。
- organizer系stringsの計上方法と実未使用数: inventoryで確定する。
