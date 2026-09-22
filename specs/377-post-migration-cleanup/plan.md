# Implementation Plan: TO-BE移行で余剰となった実装・文言・test oracleの整理

> Issue: #377
> Spec: [spec.md](./spec.md)
> Status: draft（inventory/criteria契約としてのplan）

## Current evidence

依存Issue（#368/#369/#373/#374）は全てmerge済みである。以下はbase `c52d5fcc15`（2026-09-22確認。依存merge後の`origin/main`）で直接確認済みの事実である。

### reconciliation decision tableの現状と突合matrix

重複の実態（監査§11.2の指択どおり3実装が現存するが、**同値コピーではなく、生存実装とproduction到達不能な実装に分かれる**）:

- **実装A（production到達不能）**: pure `LifecycleReconciler`（`lawnchair/src/app/lawnchair/organizer/application/lifecycle/LifecycleReconciler.kt`、360行）。`reconcile` / `classifyApplyOutcome` / `classifyRecoveryOutcome`のproduction callerは存在しない（2026-09-22にgrepで確認。productionから参照されるのは`SUPPORTED_FORMAT`定数のみ: `RecoveryProtocol.kt:232`、`RestartReconciler.kt:284`、`RecoveryPreviewProtocol.kt:129`）。直接実行するのは`tests/unit/.../lifecycle/LifecycleReconcilerTest.kt`（19 test）のみ。
- **実装B（生存: restart path）**: `RestartReconciler.reconcileWithLease`（`application/protocol/RestartReconciler.kt:296-373`）。`(lifecycle × AuthoritativeClass)`の分類に加え、lease取得・store副作用（advance/prune/quarantine）・相関reload・検証・inline recovery再試行を持つ。
- **実装C（生存: in-flight系）**: `ApplyProtocol.classifyApplyOutcome`（`application/protocol/ApplyProtocol.kt:267-326`。apply失敗・close例外・reload/検証失敗時）と`RecoveryProtocol` inline分類（`application/protocol/RecoveryProtocol.kt:165-189`。recovery失敗時）。
- digest比較primitive（`Ports.classifyAuthoritativeState`、`Ports.kt:74`。production実装は`adapter/LauncherLayoutAdapter.kt:465`）は既に単一実装。重複しているのは「classification → 次状態・公開結果・副作用」のdecision tableである。

現行matrixの突合（`(path × lifecycle × AuthoritativeClass)`。副作用はプロトコル層の責務):

| lifecycle × class | A: pure（到達不能） | B: restart（生存） | C: in-flight（生存） |
|---|---|---|---|
| gate: checksum無効 | CORRUPT / Unresolved(RECOVERY_STORE_FAILED) | advance CORRUPT + 同左 | — （上位で拒否） |
| **gate: format不整合** | **INCOMPATIBLE / Unresolved(RECOVERY_STORE_FAILED)** | **Unresolved(RECOVERY_STORE_FAILED)（lifecycle維持。advance(*, INCOMPATIBLE)のproduction callerなし）** | — （上位で拒否） |
| CREATING × PRE_STATE | READY / SilentPrune | advance READY + prune → SilentPrune | — |
| CREATING × その他 | CORRUPT / Unresolved(RECOVERY_STORE_FAILED) | advance CORRUPT + 同左 | — |
| READY × PRE_STATE | READY / SilentPrune | prune → SilentPrune | — |
| **READY × 非PRE_STATE** | **READY / SilentPrune（benign扱い）** | **Unresolved(COMMIT_OUTCOME_UNKNOWN)（fail-closed、record保持）** | — |
| APPLYING × PRE_STATE | READY / SilentPrune | advance READY + prune → SilentPrune | RolledBack（callerへ返却）+ prune |
| APPLYING × INTENDED_POST | COMMITTED_UNVERIFIED / ResumeApply(Applied) | finishCommittedApply（reload・検証・VERIFIED化）→ ResumeApply(Applied) | continueCommitted → Applied / 失敗時automaticRecovery |
| APPLYING × その他 | RESTORING / Unresolved(COMMIT_OUTCOME_UNKNOWN) | recover()（recovery再試行）→ 成功時ResumeRecovery / 失敗時Unresolved | automaticRecovery |
| COMMITTED_UNVERIFIED × INTENDED_POST | VERIFIED / ResumeApply(Applied) | finishCommittedApply → ResumeApply(Applied) | — |
| COMMITTED_UNVERIFIED × PRE_STATE | READY / ResumeApply(RolledBack) | advance READY + prune → ResumeApply(RolledBack) | — |
| COMMITTED_UNVERIFIED × その他 | RESTORING / Unresolved(COMMIT_OUTCOME_UNKNOWN) | recover() | — |
| RESTORING × RECOVERY_TARGET | RESTORED / ResumeRecovery(Restored) | finishRestored → ResumeRecovery(Restored) | Restored（検証込み） |
| **RESTORING × REVIEWED_CURRENT** | **priorLifecycle / ResumeRecovery(RestoreFailed(COMMIT_OUTCOME_UNKNOWN))（NotCommitted）** | **recover()（persisted recovery intentの再開）** | **RestoreFailed（REVIEWED_CURRENT_DB_MODEL_UNVERIFIED）** |
| RESTORING × PRE_STATE | RESTORING / Unresolved（※AはPRE_STATEをRECOVERY_TARGETと区別しない行を持たない） | finishRestored（※BはrecoveryTargetDigest=preDigestのためPRE_STATE≡RECOVERY_TARGET） | PRE_STATE/RECOVERY_TARGET → Restored系 |
| RESTORING × その他 | RESTORING / Unresolved(COMMIT_OUTCOME_UNKNOWN) | recover() | RestoreFailed(UNKNOWN系) |
| VERIFIED × 任意 | VERIFIED / SilentAdvance | SilentAdvance | — |

**差異行の裁定（spec Scope §1-aの規則で実施済み。実装中に新たな差異行が見つかった場合は同じ規則で追記する）**:

1. **READY × 非PRE_STATE**（A=SilentPrune vs B=Unresolved fail-closed）: Aはproduction到達不能であり、この行のA側挙動は観測可能でない。Bのfail-closed（record保持・unresolved表面化）はADR-0009 containment（unreadable/未識別状態のrecordを保持してfail-closed）と監査§11.1の姿勢に整合する。**裁定: B（生存実装）が正本。A側は到達不能なdrifted duplicateであり、行挙動の変更を伴わない削除候補**。規則(a)のbug分離は不要（生存pathにbugがない）。
2. **RESTORING × REVIEWED_CURRENT**（A=NotCommitted vs B=recover()再開 vs C=RestoreFailed）: spec 13 §"Restart reconciliation"は「recovery did not commit. Restore the recorded prior lifecycle and **either resume the persisted recovery intent or surface an interrupted recovery**」と両者を許容。B（restart path）はresume、C（in-flight path）はtyped RestoreFailedを返す。**裁定: 規則(b)のpath context依存の意図した差。双方ともspec 13の受入済み契約の範囲内。統合時はpath contextをdecision tableの入力としてモデル化して保持する**。AのNotCommitted行は到達不能なため1と同様に削除候補。
3. **APPLYING × PRE_STATEの公開結果の差**（B=SilentPrune vs C=RolledBack）: restart reconciliation（caller不在のため静かに掃除）とin-flight classification（callerへ結果返却）の文脈差。spec 13自身が§"Transaction outcome classification"と§"Restart reconciliation"を別tableとして定義する意図した差。**裁定: 規則(b)。path context入力で保持**。
4. **COMMITTED_UNVERIFIED × PRE_STATEのprune有無**（A=pure modelにpruneなし vs B=advance+prune込み）: 公開結果（RolledBack）は同一。pruneはprotocol層の副作用でspec 13「prune the unused record」どおり。**裁定: 差異ではなく責務分離。統合後も副作用はprotocol層に置く**。
5. **gate: format不整合**（A=INCOMPATIBLE遷移 vs B=lifecycle維持のUnresolved）: spec 13 §"Recovery record and lifecycle"の状態図は`unsupported version -> INCOMPATIBLE`を明示し、record-level logical format遷移を変更する新しいaccepted spec/ADRは存在しない（spec 174の`INCOMPATIBLE_VERSION`はstore-level（物理schema）availability契約）。Bの現行挙動（advanceなし・lifecycle維持）はspec 13契約から乖離しており、`RecoveryStore.rowToRecordRead()`がcodec record decodeのformat rejectを通さず`Readable`を返すため当該gateはproduction到達可能である。**裁定: 規則(a)「生存pathのbug」。bug Issue #407へ分離し、#377の統合・characterization testは#407解決後の正本挙動（INCOMPATIBLE遷移）を固定する（bug挙動をcharacterization固定しない）。統合実施は#407解決をgateとする（Gating 4）**。

突合の帰結: **生存実装（B/C）間の行差異はpath context依存の意図したもの（規則(b)）のみ**。A（production到達不能）側の矛盾行に加え、**gate: format不整合の1行は生存Bの挙動がaccepted spec 13から乖離している（規則(a)）ためbug #407へ分離した**。したがって統合は「生存matrixをcharacterization testで固定した上で（format行は#407解決後の正本挙動で固定）、Aとそのtestをobsolete oracleとして削除し、B/Cの重複する純分類部分をpath contextを入力とする単一decision tableへ抽出する」という形状になる（確定は実装PRの設計）。

### その他の現状証拠

- `RecoveryPreviewSummary`: `application/public/RecoveryPreview.kt:34`（`Restorable.summary` fieldは同file L15。`RecoveryPreviewEffect`は`RESTORE_SAVED_LAYOUT`のみ、全field定数default）。生成箇所は`RecoveryPreviewProtocol.kt:100`。**spec 84（accepted）が`Restorable`のclosed summary fieldとして公開契約明示**（spec 84 §I5・RP-AC-02）。productionで`RecoveryPreview.kt`/`RecoveryPreviewProtocol.kt`外の直接参照なし（test参照は`RecoveryPreviewContractTest`等）。
- export usage重複: item毎`usage: UsageProjection`（`personalization/ContextExportModels.kt:231/329`）とenvelope `usageSignals: UsageSignalsSection`（同L368）が併存。builderは`ContextExportBuilder.kt:177`（`buildUsageSection`は同L423）。
- import attempt freeze実装箇所（#374/#375 merge後の現状。残骸はここを起点にinventory）: `organizer/ui/exchange/ExchangeFlowUi.kt`、`ui/preferences/destinations/ManualOrganizationPreferences.kt`。#375（durable pending intent・rebind）で`ui/exchange/`配下に新規module群（`PendingImport*`、`ExchangeImportFailureDisplay`等）が追加されており、inventoryで対象を再列挙する。
- `Trigger.INCREMENTAL_PROPOSAL`: `diagnostics/model/Trigger.kt:12`に定義のみ（production emitなし。監査D-7）。**維持**（FR-008/D-004の値域、disposition §4.3）。
- `LOCAL_FULL`: `personalization/ContextExportModels.kt:61`等。UI到達不可（監査D-2）。**維持**（D-14、disposition §4.3）。
- organizer系strings計数（計上方法が定まっていないことの証拠。base `a2b6aba318`時点の簡易計数。`c52d5fcc15`で再計上をinventoryの初手とする）:
  - 監査の計上: 「organizer系約407項目」（`docs/assessment/organizer-as-is-ux-data-flow-audit.md` L869）/ values-ja 426
  - `name="organizer` prefix一致: values 83件 / values-ja 83件
  - organizer code（`lawnchair/src/app/lawnchair/organizer/` + launcher3側）からの`R.string`参照distinct数: 約194件
  - `lawnchair/res/values/strings.xml`の総string数1011 / values-ja 413
  - 監査の「約407」は上記いずれとも一致しないため、監査値は参考値とし、inventoryで計上方法（prefix / 参照 / translatable対象等）を確定して記録する
- 検証済みcommand（[building guide](../../engineering/building.md)正本）: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`./gradlew assembleLawnWithQuickstepGithubDebug`。Android lint taskは検証済みcommandに存在しない（specの文言とAC-3はこれに整合させる。lintを必須oracle化する場合はclean checkout/CIでの成功確認を経てbuilding guideへ追加してからとする）。

## Gating（着手条件。全て満たすまで実装に進まない）

1. #368、#369、#373、#374が全てmerge済みであること。→ **2026-09-22時点で充足**（4 IssueともCLOSED。base `c52d5fcc15`）。
2. 最新`origin/main`を取得し、本planのCurrent evidenceと`git log`/`git diff`で差分照合すること。→ 本改訂時点で`c52d5fcc15`まで照合済み。実装着手時に再度`origin/main`を取得して再照合する（特に`organizer/application/**`と`ui/exchange/**`への追加変更）。
3. 以下のinventoryを実行し、change setを確定してspec/planを必要なら改訂してから実装へ進む。reconciliationのmatrix突合・裁定は本plan Current evidenceに実施済みであり、残りはfreeze残骸・旧UX oracle・stringsのinventoryである。
4. **bug #407（gate: format不整合行のINCOMPATIBLE遷移欠落）が解決済みであること**。reconciliation統合・characterization testは#407解決後の正本挙動を固定する。#407未解決のまま統合を実施しない（bug挙動のcharacterization固定は規則(a)違反）。

## Design

### Inventory procedure（確定手順）

1. 差分照合: `git log --oneline c52d5fcc15..origin/main -- lawnchair/src/app/lawnchair/organizer tests/organizer-instrumentation tests/unit/app/lawnchair/organizer lawnchair/res` 等で対象pathの変化を列挙する。
2. 対象列挙（spec Scope §2の各領域）:
   - **reconciliation**: 実施済み（Current evidenceのmatrix・裁定。format行はbug #407へ分離済み）。実装中に新たな差異行を検出した場合はspec Scope §1-aの規則で裁定を追記する。
   - **RecoveryPreviewSummary**: 生成箇所・消費箇所・test参照を列挙し、公開shape不変の内部簡素化の実施可否を評価する。公開shape（`Restorable.summary` field・型・effect語彙）の変更はspec 84改訂を要求する別Issueとし、本Issueでは起案記録のみ（spec Scope §1-c）。
   - **usage重複**: envelope `usageSignals`とitem毎`usage`の参照関係（spec 204契約・AI-facing instruction・validator/codec）を確認し、schema v5（spec 204改訂）を要するかを評価する。本Issueの成果は評価記録まで。
   - **freeze残骸**: #374/#375/#376 merge後のfreeze・無効化実装から、status card＋T-18中心への再設計で残った個別無効化を列挙する（起点: `ExchangeFlowUi.kt`、`ManualOrganizationPreferences.kt`、`ui/exchange/`配下の新規module群）。
   - **旧UX oracle**: #368/#369/#373/#374の各PRが記録したobsolete理由一覧を入力に、対応漏れのtest（旧挙動を固定するassertion）を検索する。
   - **strings**: 計上方法を確定（例: organizer code参照集合を起点にres側を逆引き）し、production/test参照0件のstring/resourceを列挙する。**参照scanはcode（`R.string`参照）に加え、XML layout/manifest/resource alias等の非code参照を必ず含む**。`values-ja`対を必ず同時に扱う。
3. 分類: 各候補を「統合 / 削除 / 維持」へ分類し、spec §3の削除3条件（production参照0件 / 契約値・将来値域でない / 根拠記録可能）を満たすかで判定する。満たさないものは維持し理由を記録する。
4. change set確定後、PRを起こす。分割推奨: (a) reconciliation統合、(b) resource/strings削除、(c) test oracle整理。挙動不変の変更とformat-only変更を混ぜない（AGENTS.md基本動作）。

### Modules and interfaces

- 統合はorganizer/application内部の重複解消であり、**公開seam（`application/public/**`、`RecoveryPreviewResult`等）の契約は変えない**。呼び出し側とtestは既存seamを使い続ける（AGENTS.md: 呼び出し側とテストは同じseamを使う。内部実装を直接検証しない）。
- 統合後の単一decision tableは、裁定差異2・3（Current evidence）のとおり**path context（restart reconciliation / in-flight outcome classification）を入力としてモデル化**する。純分類（副作用なし）とprotocol層の副作用（advance/prune/reload/検証）の責務分離（配置計算と反映の分離、AGENTS.md設計規約）は維持する。
- `LifecycleReconciler`（実装A）の削除を実施する場合、production参照のある`SUPPORTED_FORMAT`定数は参照元（`RecoveryProtocol`、`RestartReconciler`、`RecoveryPreviewProtocol`）が使える形へ移動する。KDoc上のformat所有者は`RecoveryRecordCodec.RECORD_FORMAT_VERSION`であるため、移動先はこれと整合する場所を選ぶ。
- `RecoveryPreviewSummary`の簡素化を実施する場合も、`RecoveryPreviewResult`の公開shape（spec 84契約）は一切変えない（spec Scope §1-c）。

### Alternatives rejected

- 「削除優先で一括cleanup PR」: 依存merge前の推測削除と、safety mechanismの支え手（監査§11.1(b)）を誤って除去するリスクがあるため採らない。判断基準 + inventoryを先に固定する。
- 「3実装の行をuniform化して単一tableに潰す」: 差異行2・3はpath context依存の意図した差（spec 13が別tableとして定義）であり、uniform化は生存pathの挙動変更になるため採らない（spec Scope §1-a規則(b)）。
- 「本Issueでspec 204 schema v5改訂も実施」「本Issueでspec 84の`summary` shape変更も実施」: 契約変更と整理の混在を避けるため別起票（disposition §11、spec 84の正本規定どおり）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/{lifecycle,protocol}` | 裁定済みmatrixに基づくreconciliation純分類の単一実装への統合。`LifecycleReconciler`（実装A）の削除と`SUPPORTED_FORMAT`の移動。統合前にcharacterization testを追加 | 監査§11.2、disposition §11（設計所有#377）、spec AC-1 |
| `lawnchair/src/app/lawnchair/organizer/application/{public,protocol}` | `RecoveryPreviewSummary` seam評価（実施なら公開shape不変の内部簡素化、見送りなら記録。公開shape変更は別Issue起案記録のみ） | Issue scope、監査§11.2、spec 84境界 |
| `lawnchair/src/app/lawnchair/organizer/personalization` | export usage重複の評価記録（実施は別Issue） | Issue scope、disposition §11 |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange`、`ui/preferences/destinations` | freeze残骸清掃（#374/#375 merge後の残存個別無効化） | Issue scope |
| `tests/unit/`、`tests/organizer-instrumentation/`（organizer配下） | 旧UX oracleの削除・更新とobsolete理由のPR記録。`LifecycleReconcilerTest`（実装A固定のobsolete oracle）の削除と理由記録。裁定済みmatrix固定のcharacterization test追加 | Issue AC-1/AC-2、disposition §1 |
| `lawnchair/res/values/strings.xml`、`values-ja/strings.xml` | 未使用organizer系string削除（en/ja対。code + 非code参照scan込み） | Issue AC-3 |

## Migration and recovery

- 永続migrationなし。schema/store/recovery pointは不変。
- rollback: 通常のgit revert。release rollback/downgradeへの特別配慮は不要（挙動不変）。
- backup/restore compatibility: 影響なし（resource削除は参照欠落を起こさない範囲に限定）。

### 高リスク独立エビデンス（必須。risk labelの判断と独立）

reconciliation統合を含むPR（または分割した場合、`lawnchair/src/app/lawnchair/organizer/application/**`配下のhigh-risk pathを変更するsub-PR）は、[github-workflow.md「高リスクPRへの独立エビデンス要求」](../../project/github-workflow.md)の適用条件2（high-risk path変更）に**labelの有無にかかわらず**該当する。merge前に次の両方を必須evidenceとする（`high-risk-gate` workflowが機械検証する）:

1. **独立実行CI証拠**: 検証対象head SHA上で、当該PRの`pull_request` eventによる`CI / final-status`が成功していること（source job skipなし。docs-only runは不可）。
2. **独立audit記録**: `docs/assessment/pr-<PR番号>-<slug>.md`を`docs/assessment/_template.md`形式で追加すること（`Auditor`は実装主体と別。solo保守では独立session明記）。対象head SHA・参照spec受入条件・実行test表面・成功CI run linkを含む。

risk label（`risk: layout-data`）は適用条件1の別経路であり、付けた場合も同じ要件が適用される。本要件はtestのみ・docs-onlyのsub-PRには適用しない（workflowの判定どおり）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | (1) matrix突合・裁定記録（本plan Current evidence + 実装中追記） (2) characterization testの統合前追加と統合前後同一結果 (3) 生存pathの既存契約test修正なし全green | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（building guide検証済みcommand）+ high-risk PRの場合`CI / final-status` green（上記必須evidence） |
| AC-2 | PR本文のobsolete理由記録（test名・旧挙動・obsolete根拠・置換oracle） | manual（PR記述） |
| AC-3 | 未使用判定scan証跡（code + XML/manifest/resource alias等の非code参照双方） + build成功 + en/ja diff対応 | `./gradlew assembleLawnWithQuickstepGithubDebug`、`./gradlew spotlessCheck`（building guide検証済みcommand。Android lintは検証済みcommandでないため必須oracleとしない） |
| AC-4 | 評価記録（`RecoveryPreviewSummary`・usage重複の実施/見送りと根拠。`RecoveryPreviewSummary`は公開shape不変の内部簡素化に限定した評価） | manual（PRまたは本spec更新） |

含めるべき観点: 裁定済みmatrixを固定するcharacterization testの統合前後同一結果と、既存契約test（unit/contract・failure injection系・recovery系）の修正なし通過が主証拠（safety mechanism不変の証拠）。characterization test以外の新規test大量追加はしない（新規挙動はないため。characterization testは既存testが固定していないmatrix行を固定するために必要な追加である）。

## Documentation updates

- [ ] spec status/history（inventory確定時に本specを改訂。挙動不変のため`implemented`到達はAC evidenceで判断）
- [ ] CONTEXT.md（domain language変更時。本整理は用語追加を想定しないが、統合結果でreconciliation用語の説明が変わる場合に検討）
- [ ] DESIGN.md（system structure変更時。統合でmodule構成が変わる場合のみ）
- [ ] ADR（統合先選択が「変更が高コスト・理由がコードから分からない・実際の選択肢があった」の3条件を満たす場合。Current evidenceの裁定記録を根拠に判定する）
- [ ] AGENTS.md（検証済みcommandの変更時のみ。新規必須commandは追加しない）

## Execution checklist

- [ ] 着手条件（Gating 1〜4）を確認した（依存4 Issueのmerge、baseline差分照合、inventory完了、bug #407解決）。
- [ ] characterization testを統合**前**に追加し、裁定済みmatrix（format行は#407解決後の正本挙動）に対してgreenであることを確認した（裁定済みmatrixの固定）。
- [ ] inventoryの分類表（統合 / 削除 / 維持 + 根拠）をPRまたはIssueへ記録した。
- [ ] 既存test修正なしでの統合を確認した（挙動差異はspecのfailure scenarioに従い処理）。
- [ ] 削除したoracleごとのobsolete理由をPRへ記録した。
- [ ] en/ja resourceの対処を確認した（code + 非code参照scan込み）。
- [ ] 全relevant検証を実行し、PRへ結果を記録した。
- [ ] **high-risk path（`organizer/application/**`）を変更したPRについて、検証対象head SHAの`CI / final-status` greenと`docs/assessment/pr-<PR>-<slug>.md`独立audit記録の両方をmerge前に揃えた**（risk labelの有無と独立な必須要件）。
- [ ] specのAC-4評価記録を残した。

## Unverified areas（本planが現時点で検証していない範囲。推測で埋めない）

- `c52d5fcc15`より後の`origin/main`追加変更（実装着手時にGating 2で再照合する）。
- freeze残骸の実際の残り方（#374/#375/#376 merge後の`ui/exchange/`配下の個別無効化のうち、再設計後も不要なものの確定）と文言変更後のstrings集合。
- organizer系stringsの正確な計上方法と実未使用数（監査「約407」と簡易計数の乖離の解消。`c52d5fcc15`での再計上含めinventoryで確定）。
- 統合後の単一実装の位置・形状の確定（Current evidenceの裁定を入力とした実装設計）と、ADR要求の3条件該当性。
- `RecoveryPreviewSummary`公開shape不変の内部簡素化の実施 / 見送り判断。
- export usage重複がschema v5（spec 204改訂）を要するか。
- 依存IssueのPRで記録されたobsolete理由一覧の内容の再確認（inventory入力）。
