---
issue: "#369"
status: implemented
requirements: [FR-004, FR-006, FR-015, NFR-009, NFR-011]
risk: []
updated: 2026-09-20
---

# run面の表示を8ユーザー状態へ統合し、canonical順序（前置き→検出→[選択]→capture/plan→確認）を固定してD-06条件表示とD-13語彙規約を適用する

> Status: **implemented** (2026-09-20) — Phase1 reviewを通過して受入された契約を実装し、
> 実装PR [#387](https://github.com/nunu1733/NunuLauncher/pull/387) merge (merge commit `7ec9e9d3fe`) で実装完了。実装レビュー4回
> ([1](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5748643146) / [2](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5749752250) / [3](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5751115495) → 修正) を経て[最終review](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5751189926)は**Accepted**（blocking 0件）。独立監査記録: [docs/assessment/pr-387-run-display-integration.md](../../docs/assessment/pr-387-run-display-integration.md)。元のaccepted記録:
>初版snapshot (`3c39ceb2f8`) への1st review
> [**Changes requested**](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5740051014)
> (高1/中2/低1) を `5a162363f9` で、2nd review
> [**Changes requested**](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5746598782)
> (中2/低1) を `ccf5883c19` で、3rd review
> [**Changes requested**](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5746725434)
> (中1) を `92c08cd9aa` で全件対応し、同headへの re-review
> [**Accepted**](https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5746792904)
> (blocking指摘0件。implementation-ready判定) を経て実装へ着手する。
> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-05, D-06, D-12, D-13, §5.1 T-07〜T-13, §5.3 canonical順序, §8.1 8状態, §8.3, §9）。
> 本specは[Issue #369][1]の成果物である。
> 前提: [Issue #366][2]のhub shell（T-01、「整理を開始」CTA、PR #380 merge済み）と
> [Issue #368][3]のstrategy picker撤去・run中変更特例廃止（PR #384、merge済み）
> が適用済みであること（Issue本文 `Depends on`）。
> 処分の正本: [docs/product/organizer-disposition-migration.md][4] §3.3/§3.13/§4.1
> （accepted、PR #378 merge済み）が本件を「spec 52/228 Amend、spec 210注記、#369が実行」と
> 定める。D-06の0件時選択面非表示を表示統合として実装し内部state machineを不変と保つ解釈は、
> 処分文書§3.3の2026-09-20追記とResolved decisions RD-3に記録した。
> Amended by #417 (head `bc459e9fa0` / spec 417 accepted):
> [spec 417](../417-scope-first-method-choice/spec.md) が RD-3・D-06節・状態対応表・0候補scenarioを
> manual runでは検出後に方法選択面へ到達する形へAmendする（onboardingは現行どおり）。

## Problem

manual organization run面（`ManualOrganizationPreferences`）は、coordinatorの20状態
（`ManualOrganizationRun.State`）をほぼ1状態1画面の`when`分岐で表示している。このため:

- 検出（`CandidateDetection`）・capture（`Capturing`）・plan（`Planning`）が別々の素の
  progress textとして現れ、canonical順序（前置き→検出→[選択]→capture/plan→確認）が
  面の上からは読めない（AS-IS監査 F-04）。
- 選択面（`Selecting`、T-08相当）が**必須通過**になっている。候補0件でも
  「すべて配置済みです」の0件notice面が出て、全件整理のユーザーに無意味な1 tapを要求する
  （監査§4.4 V-09、D-06が非表示へ決定）。
- 失敗系が5つの個別面（`InputUnavailable` / `ScopeMismatchFailed` /
  `CandidateResolutionFailed` / `PlanningRejected` / `Stale(DETECTED_BEFORE_REVIEW)`）に
  分散し、原因と次の手段の対応が面ごとに異なる（F-04）。
- Backとcancelが確認なしでrun（選択・提案）を破棄し、「キャンセル」ラベルがzero-write中断と
  対象生存の中止の両方に使われている（監査 F-09、D-13語彙規約が未適用）。

accepted TO-BE D-05/D-06/D-12/D-13は、run内canonical順序の固定、候補0件時の選択面非表示、
typed失敗と入場前staleの「実行できませんでした」1面への統合、Back/Cancel/破棄の語彙規約の
全体適用を決定した。**内部state machine・typed outcome・journal相関は変更しない**
（表示統合であり、D-05の契約上の根拠）。D-06の候補0件時の選択面非表示も表示レベルの統合とし
て実装する（Resolved decisions RD-3）: coordinatorは0件でも既存どおり`Selecting`へ進入した
直後にcoordinator内部のcontinuationでcomposed phaseへ継続するため、遷移graph・entry条件・
journal規則は変わらず、選択面がUIに現れないだけである。本Issueがcoordinatorに追加するのは
この内部継続の判定点と、検出完了後の進入判定を`RUN_STARTED`発行と同一lock下でatomic化する
cancel gateのみであり、いずれも既存契約の範囲内である（Resolved decisions RD-6）。

## Outcome

run面のユーザー向け表示がTO-BEの8状態（organizer-to-be-ux.md §8.1）に統合される。
hubの「整理を開始」から到達するrun前置き面T-07で「そのまま整理」を選ぶとrun admission
（RUN lease取得）が発生し、T-09準備中面に検出→capture→planの統合progress（phase行＋中断）
が現れる。検出で候補があればT-08選択面へ一時遷移し（明示選択契約・unchecked初期値は不変）、
候補0件なら選択面を表示せずcapture/planへ続行する（D-06。内部では既存どおり`Selecting`を
経由するためstate machineは不変）。提案はT-10確認面（concrete / count-only / Add-run
再previewの3変種を1面に統合）、適用はT-11適用中面、結果はT-12結果面
（成功・変更なし・適用されなかった[stale等]・部分的失敗の変種）に統合表示され、typed失敗と
入場前stale（`DETECTED_BEFORE_REVIEW`）はT-13「実行できませんでした」（原因＋次の手段:
再試行/中断/診断）1面に畳まれる。Back/中断/破棄/キャンセルの語彙と確認規則（D-13 §9）が
全run面で一貫する。T-09の中断により検出中のcancelがユーザー到達可能になるため、検出完了後の
進入判定は`RUN_STARTED`発行と同一lock下で行われ、cancel済みrunがjournalを開始したり
compositionを再開したりしないことが構造的に担保される。coordinatorのstate machine、
typed outcome、journal相関の安全契約は一切変わらず、既存契約testはそれを証拠として示す
（D-06の0件経路の継続timingを固定するunit oracleのみ期待値更新の対象。RUN-AC-05）。

## Scope

- **T-07 run前置き面（transitional構成）**: run面のIdle/Cancelled分岐（V-06）の開始rowを
  「方法選択とscope要約」の前置き面へ置換する。#369が提供するのは
  **#372 merge前のtransitional T-07**であり、方法選択の主CTA「そのまま整理」の選択で既存の
  `start(trigger)`経路によりrun admission（RUN lease取得）が発生する。「AIに相談」の
  選択肢行は本Issueでは新設しない（capability先取り禁止、#366原則）。最終形の2択T-07
  （AI選択肢の新設とexchange idle entry row V-27の方法選択への統合）の完成条件は#372が
  所有する（Resolved decisions RD-1）。#372統合までの間、既存のexchange idle entry rowは
  spec 205 V1規約どおり同一面にhostされ続ける（現行契約の functional entryであり、
  機能しないCTAは置かない）。scope要約はadmission前に取得できる事実のみから構成し、
  検出・compositionの前倒し（件数の事前計算）をしない（D-06の「検出前に候補有無を
  前提化しない」の維持）。
- **canonical順序の固定（D-05）**: admission → 検出 → 対象選択（候補あり時のみT-08へ
  一時遷移）→ capture+plan → 提案の確認 の順序をrun面の面構成として固定する。
  現行coordinatorは既に`start()`で検出→（選択）→composed phase（capture/plan）の順に
  遷移するため、本Issueの変更は表示統合が主であり、遷移順序そのものの再設計は行わない。
- **T-09統合progress面**: `Capturing` / `CandidateDetection` / `Planning`を1つの準備中面に
  統合する。面はcanonical順序に沿ったphase行（現在の段: 検出 / capture / plan。選択は
  T-08一時遷移として扱う）と中断actionを持つ。phase行はstate種別から直接導出するのではなく、
  state遷移と同一lock下で更新される決定的な可視phase projection（`PreparationPhase`、
  RD-7）から描画する。admission直後のlegacy `Capturing`（`beginOperation()`の初期publish）は
  検出として投影され、最初の可視phaseがcaptureに見えることはない。phase遷移の読み上げは
  1段階につき1回である（organization-run-ux §6 Progress）。
- **検出完了後の進入判定とcancel gate（coordinator・既存契約の範囲内）**: T-09の中断actionに
  より検出（`CandidateDetection`）中のcancelがユーザー到達可能になる。現行の`cancel()`は
  `CandidateDetection`を受理してleaseを解放するが、detector復帰後の進入分岐にactiveの
  再確認がないため、このままでは「検出中に中断 → detector復帰 → cancel済みrunが
  `RUN_STARTED`を発行・compositionを実行」という既存契約（composed phase前のcancelは
  journalを空のままにする。`USER_CANCELLED`は`RUN_STARTED`なしに発行されない）違反の
  競合が到達可能になる。本Issueは`runComposedPhase()`の入口でactive判定と`RUN_STARTED`
  発行（`journalStarted`設定を含む）を同一lock下でatomicに行うgateを置き、cancel済み
  operationは何もせずreturnすることを全ての進入経路（検出`Unavailable`継続、D-06の
  内部継続、`confirmSelection`後の継続）で共通に担保する（Resolved decisions RD-6）。
  gate通過後のcancelは既存契約どおり`USER_CANCELLED`が`RUN_STARTED`の後に続く。
- **D-06候補0件時の選択面非表示（表示統合として実装）**: `start()`の検出結果が0件かつ
  export scopeに候補がないとき、選択面（T-08）を表示せずcapture/planへ続行する。実装は
  coordinator内部の継続であり、state machineは既存どおり`Selecting`へ進入してから直後に
  内部continuationでcomposed phaseへ進む（遷移graph・entry条件・journal規則は不変。
  D-05/TO-BE §8.1の「内部state machine不変」を文字どおり維持する。Resolved decisions
  RD-3）。intent-bound runのscope gate契約（spec 331の完全一致gate・mismatch時の選択面
  再表示・typed `SCOPE_MISMATCH`）は現行どおり維持する（guard条件はRD-3参照）。
- **T-10提案の確認面の統合**: `Preview`（concrete / count-only degraded）/ `PreviewUnavailable`
  （Add-runの具体preview不可・re-preview誘導、spec 228 AC-14）を1つの確認面の変種として
  表示する。spec 194/195/209/231/230の契約（counts truth、degraded告知、decision pair配置、
  完了形件数、復元履歴行）は退化させない。
- **T-11適用中面**: checkpoint前のみ中断可（zero-write、1回確認）。checkpoint後はBack・中断
  とも不受理であり、atomic完了までその旨が文言で説明される（現行`ApplicationInProgress`
  契約の維持、TO-BE §9の3段階規則）。
- **T-12結果面の統合**: `Applied`（8種の`ApplyResult`変種）/ `NoChanges` /
  `Stale(APPLY_BLOCKED)` を1つの結果面の変種として表示する。typed結果ごとの異なる
  localized outcome（spec 13/52のno false success契約）、成功時の復元CTA、safe terminal時の
  診断導線は現行どおり。spec 210のoutcome文・`APPLY_BLOCKED`詳細文・recapture actionの
  文言契約は不変のまま、表示面がT-12に統合される。
- **T-13統合失敗面**: `InputUnavailable` / `CandidateResolutionFailed` / `PlanningRejected` /
  `ScopeMismatchFailed` / `Stale(DETECTED_BEFORE_REVIEW)` を「実行できませんでした」1面へ
  統合する。面は原因（既存のtyped契約由来の原因文言）＋次の手段（再試行/中断/診断）を持ち、
  typed分類名は補助情報として扱う。spec 172のcopy split（`ReconciliationPending`待機案内と
  bug系の区別）、spec 331のre-export案内は原因文言として保存する。
- **D-13語彙規約の全run面適用（TO-BE §9）**: 破棄（不可逆。必ず確認）/ 中断（zero-writeで
  runを止める。選択・提案があるとき1回確認）/ キャンセル（何も壊さず中止。確認不要）の
  語彙と確認dialog規則をrun面全体へ適用する。system Backは作業の破棄を伴うときのみ
  1回確認する。適用中checkpoint後のBack・中断不受理は現行契約維持。pre-send cancel→破棄の
  改称は#372のscopeである（本Issueでは触れない）。
- **spec改訂（実装PRで実施）**: spec 52（MFO-AC-01へ検出phaseを挿入しD-06を反映、
  Manual-run composition節のstate列挙に「表示は8ユーザー状態へ統合される」旨の注記。
  MFO-18のdecision pair規定はT-10で維持。safe apply契約には触れない）、spec 228
  （§2の0件規定「0件でも表示」→「0件なら非表示」、0件表示scenario/ACの調整。明示選択・
  unchecked初期値・process-local選択stateの契約は不変）、spec 210（統合先注記。
  `APPLY_BLOCKED`はT-12結果面、`DETECTED_BEFORE_REVIEW`はT-13統合失敗面へ表示される旨。
  文言契約は不変）。
- **Localization**: 新規user-visible文字列はAndroid resource由来でEN（`values/`）とja
  （`values-ja/`）の双方に供給する（spec 123契約）。統合により未使用になったstringは
  実装PRのreference grepで確定し、同じPRで双方のresourceから削除する。

## Non-goals

- 「AIに相談」分岐の実装（T-15/T-16への接続、idle AI相談のpre-run request flow化）— #372。
  T-07方法選択の最終形（2択）の完成（AI選択肢の新設、exchange idle entry row V-27の
  T-07方法選択への統合）も#372が所有する。本Issueではspec 205 V1規約どおりIdle/Cancelled面
  （transitional T-07）にexchange idle entry rowがhostされ続ける。
- durable pending intent（D-08系、#374）とstatus cardへの退避。
- 内部state machine・typed outcome・journal event・diagnostics契約の変更
  （D-06の0件時選択面非表示も表示統合として実装し、state machineは不変。RD-3）。
- 安全契約（spec 13/194/210のgate構造、spec 52の表）の変更。preview seam、確認=authority、
  checkpoint/apply/recoveryの適用semantics、zero-write保証はすべて現行どおり。
- strategy（#368で完了済みが前提）。run面にstrategy picker・読み取り専用表示は存在しない。
- onboarding提案のflow接続変更（D-16表記、#370）。現行のonboarding「確認」は
  `start(Trigger.ONBOARDING_PROPOSAL)`によるadmission直行であり、T-07前置きを経ない
  （現行契約どおり。T-07はMANUAL entryの開始点としてのみ導入する）。
- exchange失敗の手段別再投影（D-11、#373）。exchange導線の表示は現行のまま。
- T-08選択面の復元初期値（D-17、#375）とscope mismatch remedyの原因別明文化（#375）。
- Idle/Cancelled面のdurable status表示（spec 271）の移設・削除。hub status card（#366）との
  重複の解消は#372/#377の清掃対象とし、本Issueでは現行どおり維持する。
- run/journal eventの新設・廃止、persistent state、permission、外部送信の変更。

## Domain language

- **T-07〜T-13**: organizer-to-be-ux.md §5.1のrun系表面ID。本specではユーザーに区別して
  見せる面の契約名として使い、UI上のlabelとしては現れない（ユーザー向け語彙はTO-BE §10
  およびD-13に従う）。
- **8ユーザー状態**: organizer-to-be-ux.md §8.1のユーザー向けrun状態
  （hub待機 / 対象選択 / 準備中 / 提案の確認 / 適用中 / 結果 / 復元の確認 /
  実行できませんでした）。coordinatorの20状態の表示統合先である（§8.1対応表を正本とする）。
- **中断 / 破棄 / キャンセル**: TO-BE §9（D-13）の語彙規約。`CONTEXT.md`への用語追加は
  [Issue #365][5]が所有し、本specでは複製しない。
- **入場前stale（`DETECTED_BEFORE_REVIEW`）**: 提案が確認面に到達する前に検出されたstale
  （spec 210 D1）。T-13の原因表示に統合される。適用時stale（`APPLY_BLOCKED`）はT-12結果面の
  変種である（D-12）。

## 20状態 → 8ユーザー状態の対応表

`ManualOrganizationRun.State`の20状態の表示統合先（TO-BE §8.1に基づく。State変種・originは
保持され、統合は表示面の単位である）:

| ユーザー状態（表示面） | coordinator状態 | 面ID |
|---|---|---|
| （T-07前置き。run不在） | `Idle`, `Cancelled` | T-07 |
| 対象選択 | `Selecting` | T-08（候補あり時のみ表示。0件時は内部継続で通過し表示されない、D-06/RD-3） |
| 準備中 | `Capturing`, `CandidateDetection`, `Planning` | T-09 |
| 提案の確認 | `Preview`, `PreviewUnavailable` | T-10 |
| 適用中 | `Applying` | T-11 |
| 結果 | `Applied`, `NoChanges`, `Stale(APPLY_BLOCKED)` | T-12 |
| 復元の確認 | `InspectingRecovery`, `RecoveryPreview`, `Recovering`, `RecoveryResultState` | T-14（本Issueでは現行表示の維持。統合再設計は行わない） |
| 実行できませんでした | `InputUnavailable`, `CandidateResolutionFailed`, `PlanningRejected`, `ScopeMismatchFailed`, `Stale(DETECTED_BEFORE_REVIEW)` | T-13 |

注: Issue本文scopeの「V-11〜V-15の失敗系」は、accepted TO-BE §5.1の統合指定
（T-13 = V-11/V-12/V-13/V-14統合、T-12 = V-20/V-21/V-15統合）に従い、V-15（`NoChanges`）を
T-12結果面へ、失敗系としてV-11〜V-14＋入場前staleをT-13へ統合するものと解する。
`ScopeMismatchFailed`とmismatch時の選択面再表示（`Selecting.scopeRejection`）の使い分け
（spec 331 D-5/D-2）は現行契約どおり維持する。表は表示面の単位の統合であり、coordinator状態
の遷移graphそのものは本Issueで不変である（RD-3）。

## Behavior scenarios

### Scenario: hubから前置き面を経てrun admissionが発生する

Given #366のhubと#368のstrategy撤去が適用済みであり、run coordinatorが`Idle`である
When hubの「整理を開始」からrun面を開く
Then 方法選択とscope要約の前置き面（T-07）が表示され、主CTA「そのまま整理」が存在する
And T-07は#372統合前のtransitional構成であり（RD-1）、「AIに相談」の選択肢行は新設されず、
既存のexchange idle entry row（spec 205 V1）が同一面にhostされ続ける
And 「そのまま整理」を選ぶとrun admission（RUN lease取得）が発生し、T-09準備中面の
最初のphase（検出）へ進む
And 前置き面の表示中は検出・compositionの実行がなく、候補有無の前提化やzero-writeを超える
書込みが発生しない

### Scenario: 準備中は1つのprogress面に統合される

Given runがadmission済みである
When runが`CandidateDetection` → （候補ありならT-08へ一時遷移して戻る）→ `Capturing` →
`Planning`へ遷移する
Then ユーザーには1つの準備中面（T-09）が表示され、phase行が現在の段
（検出 / capture / plan）を示す。phase行は決定的な可視phase projection（RD-7）由来であり、
admission直後の最初の可視phaseは常に検出である（legacy `Capturing`がcaptureとして
先に見えることはない）。T-08から選択確定で戻る経路でも、戻り後の最初の可視phaseは
captureであり、検出の再表示・再announceは発生しない（RD-7の更新順序契約）
And 面には中断actionがあり、選択・提案がまだ無いため確認なしでzero-write中断できる
（D-13 §9の「提案または選択があるとき1回」規則。RD-4）
And 各phase遷移はTalkBackへ1回だけannounceされ、連続的な再announceやspamを生まない

### Scenario: 候補0件では選択面を表示せず続行する（D-06）

Given 検出が`Ready`で候補0件であり、validated intentがboundされていない
When 検出phaseが完了する
Then 選択面（T-08）は表示されず、runはcapture/planへ続行する
And coordinator内部では既存どおり`Selecting`へ進入してから内部continuationでcomposed phase
へ進むため、state machineの遷移graph・entry条件・journal規則は本Issue適用前と同一である
（RD-3/RD-7。UIのface mappingが0件の`Selecting`を決定的にT-09準備中面へ写像するため、
観測timingにかかわらず選択面は構成されない）
And 0件notice面（`manual_organization_missing_apps_empty`）と「続行」の選択面は
表示されない（旧表示oracleの更新対象）
And この経路はエラー・typed失敗ではなく、通常の全体整理として計画される

### Scenario: 検出中の中断後はrunが再開しない

Given runがT-09準備中面の検出phase（`CandidateDetection`）にあり、detectorがまだ返っていない
When ユーザーが中断を選び、cancelが受理された後でdetectorが結果を返す
（0件・候補あり・`Unavailable`のいずれでも）
Then runは`Cancelled`のまま再開せず、`RUN_STARTED`・`INPUT_NOT_READY`・plan・applyは
発生しない（検出/選択windowのjournalは空のまま。RD-6）
And RUN leaseは一度だけ解放され、二重解放や解放漏れが発生しない
And gate通過後（`RUN_STARTED`発行後）のcancelは既存契約どおり`USER_CANCELLED`として
記録される

### Scenario: 候補あり時の選択面は現行契約どおりである

Given 検出が1件以上の候補を返した
When T-08選択面が表示される
Then 明示選択契約（spec 228: icon/label/multi-select/選択数/search/Select all/Clear all、
D-1 unchecked初期値、検索・filterと選択stateの相互作用）は本Issue適用前と同一である
And 選択凍結AI入口（run-in exchange entry、spec 331）とSCOPE_MISMATCH時の差分表示も
現行どおりである

### Scenario: intent-bound runのscope gate契約はD-06によって緩まない

Given validated intentがboundされており、export scopeに候補が含まれる
When 検出が行われる
Then 候補が検出された場合はT-08選択面が開き、export scopeとの完全一致gate
（spec 331 fail-closed）が現行どおり働く
And 検出が0件でもexport scopeに候補がある場合は選択面が開き（mismatch表示契約のため）、
続行時にtyped `SCOPE_MISMATCH`のzero-write拒否とre-export案内が現行どおり表示される

### Scenario: 失敗と入場前staleが1つの「実行できませんでした」面に統合される

Given runが`InputUnavailable` / `CandidateResolutionFailed` / `PlanningRejected` /
`ScopeMismatchFailed` / `Stale(DETECTED_BEFORE_REVIEW)`のいずれかに到達した
When 統合失敗面（T-13）が表示される
Then 「実行できませんでした」の見出しと、既存typed契約由来の原因文言
（例: 入力が揃わない場合の待機案内（spec 172）、候補解決不能、計画不能/不可能、scope違反の
re-export案内（spec 331）、準備中にホームが変更された旨（spec 210
`DETECTED_BEFORE_REVIEW`詳細文））が表示される
And 次の手段として再試行と中断が提供され、既存契約が支持する原因では診断導線が提供される
And いずれの経路でもLauncher DBへの書込みは発生しない（zero-write継続）
And typed分類名（`CONTEXT_STALE`等の内部語）は見出し・主文言へ現れず、補助情報
（詳細展開・診断）に留まる

### Scenario: 適用されなかったstaleは結果面の変種として表示される

Given 提案の確認後にユーザーが適用を試み、`APPLY_BLOCKED`で拒否された
When 結果面（T-12）が表示される
Then spec 210のoutcome文「この整理案は適用されませんでした。この操作によるホーム画面の
変更はありません。」と`APPLY_BLOCKED`詳細文、recapture action（label・summary含め文言不変）
が表示される
And 面は結果面（成功・変更なし・部分的失敗と同じ面構成）の変種として描画される

### Scenario: 結果面はtyped結果ごとに真実を区別し続ける

Given 適用が完了しいずれかの`ApplyResult`変種・`NoChanges`で終端した
When 結果面（T-12）が表示される
Then spec 13/52のtyped結果ごとの異なるlocalized outcomeが表示され、検証済み成功は完了形
件数（spec 231）と復元CTAを、safe terminal（`Unresolved`/`RecoveryFailed`）は診断導線を
持つ（no false success契約の維持）
And `NoChanges`は書込みを示唆しない表示のままである

### Scenario: 復元の確認は現行契約のまま維持される

Given 検証済み適用の後、ユーザーが復元を選んだ
When 復元検査・復元確認・復元実行・結果が表示される
Then spec 84/230の契約（revision-bound preview、apply history行、confirm/cancel pair）が
本Issue適用前と同一の表示で維持される（本IssueでT-14の再設計は行わない）

### Scenario: 語彙規約が全run面で一貫する

Given 提案が表示されているT-10で中断を選ぶ、またはsystem Backを押す
Then zero-write中断には1回の確認dialogが現れ、語彙は「破棄」確認（Back）または「中断」
（D-13 §9）として一貫する
And 選択のみが存在するT-08での中断も1回確認であり、何も失わない中止
（復元確認のキャンセル等）は確認なしの「キャンセル」である
And 適用中checkpoint後はBack・中断とも不受理であり、atomic完了までその旨が
既存のapplying文言で説明される

### Scenario: 内部契約は変更されない

Given 本Issueの実装PRが作成されている
When state machine・typed outcome・journal相関を固定する既存契約test
（`ManualOrganizationRunTest`のadmission/stale/zero-write/journal相関群、
`ExchangeFlowStateHolderTest`、E2E `staleProductionConfirmationDoesNotWrite`等）を実行する
Then D-06の0件経路の継続timingを固定するtestを除き、すべてが無編集でgreenである
And D-06経路のtest期待値更新と、旧表示oracle（個別失敗面・選択面必須通過・0件選択面表示）
がなぜobsoleteか（F-04/V-09の解消、D-05/D-06の適用）がPR本文に記録されている

## Failure behavior

| Condition | Observable outcome |
|---|---|
| 検出0件＋intent未bound（またはexport scope候補0件） | 選択面を表示せずcapture/planへ続行（D-06）。エラーではない |
| 検出が`Unavailable` | 現行どおり選択面を開かずplain full composeへ続行（spec 228 §7継続） |
| 入力未READY（`ReconciliationPending`） | T-13に待機案内（spec 172 copy split）＋再試行。zero-write |
| 候補が解決不能 | T-13に解決不能の原因＋再試行（再検出）。zero-write |
| 計画がInvalid/Impossible | T-13に原因件数（既存summary表示）＋再試行。zero-write |
| preview準備中にlayout変更（`DETECTED_BEFORE_REVIEW`） | T-13にspec 210の詳細文（文言不変）＋再取得。zero-write、`A2` event継続 |
| 適用時にstale拒否（`APPLY_BLOCKED`） | T-12結果面にspec 210のoutcome＋詳細文＋recapture。zero-write保証文言は現行契約 |
| 適用中のcheckpoint前中断 | 1回確認ののちzero-write中止（現行cancel契約） |
| 適用中のcheckpoint後のBack/中断 | 不受理。applying文言が理由を説明（現行`ApplicationInProgress`契約） |
| import attempt中の前置きCTA | 現行のidle start row freeze契約（spec 328）を「そのまま整理」CTAが継承する |

## Stale state / concurrency

- stale検出機構・revision照合・適用blockの安全設計は変更しない（spec 210 D4の継続）。
  統合は表示面の単位であり、`Stale`のorigin区別（spec 210 D1）は保持される。
- D-06の0件継続は`start()`の検出完了直後の単一判定点とcoordinator内部continuationで行われ
  （plan参照）、選択面が開く既存経路（候補あり・intent-bound mismatch）の遷移順序とlock構造は
  変更しない。
- 検出完了後の進入判定と`RUN_STARTED`発行は同一lock下でatomicに行う（RD-6）。T-09の中断に
  より検出中のcancelが到達可能になった後でも、cancel済みoperationがjournalを開始したり
  compositionを実行したりしないことを構造的に担保し、既存の「composed phase前のcancelは
  journalを空のままにする」契約とlease一解放を維持する。
- 可視phase projection（RD-7）の更新は、対応するstate遷移と同一lock区間内で行い、activeで
  ないoperationに対しては更新しない。さらにT-09を可視に戻すstate publish（`Capturing`/
  `Planning`）より先にphaseを確定する順序契約を守る（RD-7）。projectionはstate machineに
  不足する情報を追加するものではなく、表示がtimingに依存しないための決定的導出である。
- 中断・Backの確認dialogはUI層のaffordanceであり、coordinatorのcancel/dismiss契約
  （zero-write、`USER_CANCELLED` journal規則、admission後不受理）を変えない。
  「UI disabledはaffordanceにすぎない」原則（spec 328）に従い、不受理はcoordinator gateが
  構造的に担保する。

## Data and state

- **読む**: run coordinator state（既存`stateFlow`）、準備phaseの可視projection
  （`PreparationPhase`。RD-7。T-09のphase行とface mappingが消費する）、durable status
  projection（Idle面の現行表示維持）、既存selection/preview/summary/recovery state。
  新規に追加する読取seamは上記projectionのみである。T-07のscope要約はadmission前に取得できる
  事実のみから構成する。
- **書く**: 新規の永続化・preference・diagnostics eventはない。書込みは既存の適用/strategy/
  exchange経路のみ（本Issueでは触れない）。
- **Identity**: 変更なし。`RunId`、typed outcome、journal相関、`StaleOrigin`、scope gate
  identityは不変。
- **Migration / backup / restore / rollback**: persistent state変更なし。schema変更なし。
  Launcher layout DB / `favorites` への接触なし（ホームレイアウト安全規約の適用対象外）。
  PR revertで旧面構成（個別progress/個別失敗面・0件選択面・確認なしcancel）へ戻る。downgrade
  時の残留物なし。
- **利用者の既存状態**: 選択・提案・durable statusは本変更の前後で同じ意味を保ち、
  process death時のrun喪失は現行どおり（D-08対象外）。

## Permissions, privacy, and security

- None — 新規permission、外部送信、sensitive dataの扱い追加はない。
- diagnostics: 変更なし（NFR-011）。原因文言は既存typed結果のlocalized mappingからのみ
  導出され、journalからUI textを構築しない（spec 52の境界継続）。typed分類名の補助情報化は
  表示の変更であり、diagnostics record形式は不変である。

## Accessibility and localization

- 統合progress面（T-09）と統合失敗面（T-13）にorganization-run-ux §6のaccessibility受入基準
  を最初から適用する: TalkBackは見出し・原因・次の手段のname/role/stateを公開する。
  phase遷移は1段階につき1回announceする（現行の状態ごとの独立`ProgressText`を統合した際に
  重複announceを生まない）。focus restorationは各状態遷移で決定的である（既存
  `focusTargetIndex`機構の継承）。200% font scaleでclipping/overlapやcritical actionの
  到達不能を生まない。状態・原因はcolor-onlyにしない。keyboard/switch traversalで
  見出し → 原因 → 再試行 → 中断 → 診断へ論理順に到達し、すべて活性化できる。
  timeout auto-confirm/cancelは存在しない。
- 確認dialog（破棄/中断）はfocusをdialogへ移し、confirm/cancelを明示的なroleで提供する。
- 新規string（「実行できませんでした」見出し、準備中面の見出し・phase行、T-07方法選択と
  scope要約、確認dialog文案、中断label等）はAndroid resource由来でEN（`values/`）とja
  （`values-ja/`）の双方に供給し、複合文はformat resourceで構成する（spec 123 AC-4/AC-5、
  spec 161のja copy規約）。既存の原因・結果文言は可能な限り再利用し、ja/enの文言契約が
  spec 210/172/331由来のものは不変である。
- 統合により未使用になるstring（0件notice `manual_organization_missing_apps_empty`等）は
  実装PRのreference grepで確定し、`values/`/`values-ja/`双方から削除する（孤立
  user-visible参照の排除）。

## Acceptance criteria

- [ ] **RUN-AC-01**: canonical順序（admission→検出→[選択]→capture/plan→確認）が面構成として
      固定され、T-07前置き面（transitional構成。RD-1）の「そのまま整理」でrun admission
      （RUN lease取得）が発生する。候補0件時に選択面が表示されずcapture/planへ続行し（D-06）、
      この統合でcoordinatorの遷移graphが変化しない（RD-3）。D-06の非表示はface mappingによる
      決定的保証であり（RD-7）、admission直後の最初の可視phaseは検出であり、T-08復帰後の
      最初の可視phaseはcaptureである（検出の再表示・再announceは0回。RD-7の更新順序契約）。
      intent-bound runの
      scope gate契約（spec 331）と検出`Unavailable`時の現行継続経路は変化しない。
      （Issue受入1）
- [ ] **RUN-AC-02**: 20 coordinator状態の表示が対応表のとおり8ユーザー状態へ統合され、
      typed失敗（V-11〜V-14系）と入場前stale（`DETECTED_BEFORE_REVIEW`）がT-13
      「実行できませんでした」の原因＋次の手段（再試行/中断/診断）で表示される。typed分類名は
      補助情報である。T-09は1つの統合progress面（phase行＋中断）として表示される。
      （Issue受入2）
- [ ] **RUN-AC-03**: 内部state machine・typed outcome・journalの安全契約を固定する既存
      契約testが、D-06の0件経路の継続timing oracleを除いて無編集でgreenである
      （表示統合が契約を変えないことの証拠）。D-06経路のtest期待値更新はD-06決定の反映として
      PRに記録される。（Issue受入3）
- [ ] **RUN-AC-04**: 語彙規約（D-13 §9）がrun面で一貫する: 破棄は必ず確認を伴い、中断は
      zero-writeで選択・提案があるとき1回確認、キャンセルは確認不要であり、適用中
      checkpoint後のBack・中断不受理は現行契約どおりである。pre-send cancel→破棄の改称は
      行わない（#372）。（Issue受入4）
- [ ] **RUN-AC-05**: 旧表示（個別失敗面、選択面必須通過、0件時の選択面表示）を固定する
      test oracleが更新され、旧oracleがなぜobsoleteか（F-04/V-09の解消、D-05/D-06の適用）
      がPRに記録されている。（Issue受入5）
- [ ] **RUN-AC-06**: 統合progress面と統合失敗面がorganization-run-ux §6のaccessibility受入
      基準を満たす（TalkBack name/role/state、phase遷移の1回announce、focus restoration、
      200% reflow、non-color-only、traversal、no timeout auto-confirm）。（Issue受入6）
- [ ] **RUN-AC-07**: T-10/T-12/T-14の既存契約が退化しない: spec 194/195/209/228(AC-14)/230/
      231の表示契約と、spec 13/52のtyped結果の区別・no false successが、統合後の面上で
      維持される。spec 210の文言契約（outcome文・origin別詳細文・recapture）は不変のまま
      表示面がT-12/T-13へ統合される。
- [ ] **RUN-AC-08**: spec 52/228/210の改訂が同じ実装PRで行われている（Scope節の改訂内容の
      とおり。safe apply契約・明示選択契約・spec 210文言には触れない）。
- [ ] **RUN-AC-09**: 新規stringがEN/ja双方で供給され（format resource規約含む）、統合により
      未使用になったstringが双方のresourceから削除されている。
- [ ] **RUN-AC-10**: 検出中の中断後、detectorが復帰してどの結果（0件/候補あり/`Unavailable`）
      を返してもrunは`Cancelled`のまま再開せず、`RUN_STARTED`/`INPUT_NOT_READY`/plan/
      applyが発生せず、leaseが一度だけ解放される（RD-6）。gate通過後のcancelは
      `USER_CANCELLED`が`RUN_STARTED`の後に記録される既存契約を維持する。

## Test oracle

| AC | Evidence |
|---|---|
| RUN-AC-01 | unit: `start()`のD-06内部継続（0件＋intent未boundで選択面を構成せずplain compose直行。state列は既存どおり`Selecting`経由）、guard条件（intent-bound・export scope候補あり＋検出0件では選択面が開く）、検出`Unavailable`継続の回帰。face mapping純関数のtable-driven unit test（0件`Selecting`〔scope rejection・export scope候補なし〕→T-09、0件＋scopeRejection→T-08、候補あり→T-08等）。blocking detectorでdetector停止中に観測した可視phase projectionが検出であることのoracle（captureが先行しない）。候補あり経路`CandidateDetection → T-08 → confirmSelection → T-09`のoracle（T-08復帰後の最初のT-09 phaseが`CAPTURE`、`DETECTION`再表示/再announce 0回。RD-7順序契約）。instrumentation: T-07→「そのまま整理」→T-09検出phase→0件続行の遷移と、0件時に`missing_apps_empty`/`missing_apps_continue`が表示されないことの否定的観測 |
| RUN-AC-02 | instrumentation: 8状態への統合（T-09が検出/capture/planで同一面構成を保つこと、T-13見出し＋原因＋再試行/中断（＋該当時診断）の構造、T-10/T-12の変種表示）。既存の各失敗原因文字列がT-13上に現れることのassert |
| RUN-AC-03 | `ManualOrganizationRunTest`・`ExchangeFlowStateHolderTest`・organizer unit gateの無編集green（D-06の0件経路継続timing oracleを除く）+ D-06経路testの更新diff + `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite`のgreen |
| RUN-AC-04 | instrumentation: T-10中断の確認dialog（1回・破棄/中断語彙）、T-08選択あり中断の確認、復元確認キャンセルの確認なし、Applying checkpoint前の確認付き中断、checkpoint後の不受理（既存gate oracle）。unit: cancel/dismiss契約の既存test無編集green |
| RUN-AC-05 | `MissingAppSelectionInstrumentationTest.zeroCandidatesShowsTheEmptyNoticeAndStillContinues`等の旧oracle更新diff + obsolete理由のPR本文記録 + spec 228/210改訂diff review |
| RUN-AC-06 | Compose semantics assertion（見出し・原因・手段の読み上げ、phase遷移announce回数、traversal順）+ focus restoration test + 200% font scale test + light/dark × ja/default screenshot evidence（organization-run-ux §6表に基づく確認記録） |
| RUN-AC-07 | 既存preview/confirmation/result/recovery系instrumentationの無編集または表示面移設のみの更新でgreen + spec 194/195/209/228/230/231/210対応oracleのdiff review |
| RUN-AC-08 | specs 52/228/210のdiff review（改訂箇所がScope節と一致し、safe apply契約・明示選択契約・spec 210文言が不変であること） |
| RUN-AC-09 | 新規stringの`values/`と`values-ja/`のname集合・placeholder一致の機械確認 + 削除stringのreference grep（0件）+ hardcoded literal grep |
| RUN-AC-10 | unit: blocking fake detector（検出をlatchで保持するfake）で`CandidateDetection`中に`cancel()`し、detectorに(a) `Ready(empty)`、(b) `Unavailable`、(c) `Ready(>0)`を返させた各系で、最終状態が`Cancelled`、当該runIdのjournal event不発（`RUN_STARTED`/`INPUT_NOT_READY`/plan系/apply系）、lease `.close()`呼び出し1回を固定する。gate通過後にcancelする対照系で`RUN_STARTED`→`USER_CANCELLED`の順序を固定 |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane
（`ManualOrganizationPreferencesInstrumentationTest`、`MissingAppSelectionInstrumentationTest`、
exchange系）、CI `final-status` green（本Issueは `risk: layout-data`/`risk: migration` を
付けない。表示・navigationのみでpersistent state・DB書込み経路に触れないため、high-risk
evidence gateの対象外）。

## Resolved decisions

初版（2026-09-19）のContract notes 1〜5はowner review対象の未決解釈だった。2026-09-20の
spec review（[Issue #369 review comment][6]。**Changes requested**、高1/中2/低1）で指摘された
内容を含め、以下のとおり決着した。本節はspec本文の規定の一部である。

1. **RD-1 — T-07はtransitional構成とし、最終形の完成条件を#372へ移す**（初版note 1＋
   review指摘「T-07の所有境界」の解消）: #369のT-07は#372 merge前のtransitional構成
   （方法選択=「そのまま整理」＋scope要約）であり、「AIに相談」の選択肢行は新設しない
   （capability先取り禁止、#366原則）。最終形の2択T-07（AI選択肢の新設とexchange idle
   entry row V-27の方法選択への統合）の完成条件は#372が所有する。#372統合まで、既存の
   exchange idle entry rowはspec 205 V1規約どおり同一面にhostされ続ける（現行のfunctional
   entryであり、機能しないCTAは置かない）。
2. **RD-2 — 中断後の行き先とdecision pairのcancel**（初版note 2。reviewで変更指示なし）:
   中断（確認応答後）はrun停止後にhubへ戻る（system Backと同一のnavigation結果）。
   spec 209のdecision pair契約（confirm/cancel pairの視覚構造）は保持し、cancel側のlabel・
   確認・遷移のみをD-13へ合わせる。pre-send cancel→破棄の改称は#372のscope（本Issue対象外）。
3. **RD-3 — D-06は表示統合として実装し、内部state machineは不変**（初版note 3＋review指摘
   「D-06の実装方針がaccepted dispositionの『内部 state machine 不変』と衝突」の解消）:
   accepted TO-BE D-06/§8.1の文言は表示レベル（「選択面を表示せずcapture/planへ続行」、
   T-08は「候補あり時のみ現れる」）であり、accepted disposition §3.3の「内部state machine・
   typed outcome不変」「`ManualOrganizationRunTest`のstate期待値は不変」はD-05と一体の
   契約である。よって0件時の選択面非表示はcoordinator内部の自動継続で実現する:
   coordinatorは0件でも既存どおり`State.Selecting`へ進入し、直後にcoordinator内部の専用
   continuation（UI actionを偽装しない）でcomposed phaseへ進む。遷移graph・entry条件・
   journal規則は不変であり、UIは選択面を構成しない（決定的なface mappingによる。RD-7。
   StateFlowのconflationには依存しない）。guard条件: 検出0件かつ（intent未bound または export
   scopeの候補0件）のときのみ内部継続する。export scopeに候補がある場合は選択面を開き、
   spec 331のmismatch表示契約を保存する。本解釈は処分文書§3.3へ2026-09-20追記として記録する
   （正本側に例外の記録を残す）。影響するtestは0件経路の継続timing oracle
   （`confirmingAnEmptySelectionRunsThePlainFullCompose`系）のみであり、更新とobsolete理由は
   RUN-AC-05の義務でPRへ記録する。代替案の評価: (i) UIが0件`Selecting`を観測して
   `confirmSelection(emptySet())`を発行する解は、明示選択契約（spec 228 D-1）のユーザー
   actionを偽装するため不採用（初版から維持）。(ii) 0件時に`Selecting`へ不進入とする解は、
   accepted正本（D-05「内部state machineの契約は変更しない」、disposition §3.3）を子specの
   契約noteだけで例外化することになり、TO-BE D-05自体の改訂を要するため不採用
   （review指摘の選択肢(b)は採らない）。
4. **RD-4 — T-09の中断は確認なし**（初版note 4。reviewで変更指示なし）: D-13 §9の中断の
   確認規則「提案または選択があるとき1回」を正とし、T-09時点（提案も選択も無し）の中断は
   確認なしのzero-write中止とする。TO-BE §5.1 T-09行の「中断（破棄確認）」表記は§9の規則が
   正である。
5. **RD-5 — T-07のscope要約はadmission前に取得できる事実のみ**（初版note 5。reviewで変更
   指示なし）: 対象範囲の説明（ホーム全体の整理であること、確認前に適用されないこと）の
   文言とし、件数の事前計算（検出・compositionの前倒し）を要求しない。具体的文言は実装PRで
   確定する（非blocking）。
6. **RD-6 — 検出完了後の進入判定と`RUN_STARTED`発行を同一lock下でatomic化する**
   （review指摘「高: T-09の『中断』を新設すると、検出中 cancel 後に run が再開する競合が
   到達可能になる」の解消）: 現行の`cancel()`は`CandidateDetection`を受理する一方、detector
   復帰後の進入分岐（`Unavailable`継続）にactiveの再確認がなく、`runComposedPhase()`は
   active確認前に`RUN_STARTED`を発行する。従来UIは検出中のcancel affordanceを持たなかったが、
   T-09の中断actionがこの競合をユーザー到達可能にする。本specは`runComposedPhase()`の入口で
   active判定・`journalStarted`設定・`RUN_STARTED`発行を同一lock区間内で行うgateを要求し、
   cancel済みoperationがjournalを開始せずcompositionも実行しないことを全ての進入経路で
   共通に担保する（gate通過後のcancelは既存契約どおり`USER_CANCELLED`が後続する）。
   検出結果の受理（`detectedCandidates`保持）も同一lock下のhelperへ集約する。
   oracleはRUN-AC-10のblocking fake detector unit testとする。
7. **RD-7 — D-06の非表示とcanonical順序の可視保証は、conflationではなく決定的な表示写像と
   可視phase projectionで担保する**（2nd review指摘「中: 0件時T-08非表示がStateFlowの
   conflationに依存」「中: 最初のvisible phaseがcaptureになり得る」の解消）:
   UIは`stateFlow`を直接購読し、run actionは`Dispatchers.IO`で実行されるため、内部連続遷移の
   中間state（0件`Selecting`、admission直後に`beginOperation()`がpublishするlegacy
   `Capturing`）はcollectorから観測され得る（StateFlowのconflationは中間値の非観測を
   保証しない）。よって:
   - **表示写像（face mapping）を決定的にする**: 候補0件かつscope rejection無しかつexport
     scope候補無しの`Selecting`は、UIのface mapping（純関数）でT-09準備中面へ写像し、T-08の
     composableを構成しない。観測timingに依存しない。
   - **coordinatorは準備phaseの可視projectionを持つ**: T-09のphase行に現れる段
     （検出/capture/plan）を、state遷移と同一lock下で更新される決定的projection
     （`PreparationPhase`）として公開する。admission直後のlegacy `Capturing`は検出として
     投影されるため、canonical順序の最初の可視phaseは常に検出である
     （D-05/RUN-AC-01、organization-run-ux §6の1段階1回announce）。state machine・遷移graphは
     不変であり、projectionは表示用の追加observableである（Data and state節参照）。
     **更新順序の契約（3rd review指摘の解消）**: `PreparationPhase.CAPTURE`は、T-09を可視に
     戻す`State.Capturing`のpublishより**先に**、かつ同一lock区間内で確定する。対象は
     `confirmSelection()`の成功経路（二重confirm禁止のguard publishを含む）、D-06内部継続、
     `runComposedPhase()`のcancel gate（RD-6のatomic区間内）である。`PreparationPhase.PLAN`
     も`State.Planning`のpublishに先立ち同一lock区間で確定する。これにより、T-08復帰後を
     含めて`(stateFlow = Capturing/Planning, preparationPhase = 取り残された前phase)`という
     組合せは観測可能にならず、可視列は常に
     `検出 → [選択] → capture → plan`の順序を保つ（検出の再表示・再announceは発生しない）。
   - oracle: face mappingの純関数unit test（0件`Selecting`→T-09を固定）と、blocking
     detectorで中間stateを保持した状態で観測するphase projection oracle
     （detector停止中の最初の可視phaseは検出で、captureが先行しない）。さらに候補あり経路の
     `CandidateDetection → T-08 → confirmSelection → T-09`を対象に、T-08復帰後の最初の
     T-09 phaseが`CAPTURE`であること（`DETECTION`の再表示/再announceは0回）を固定する。

## Dependencies

- **#366（hub shell、merge済み: PR #380）**: hub destination、「整理を開始」CTA、材料セクション
  が存在すること。本specのT-07は#366の暫定導線（hub CTA → 既存run面）を前置き面経由へ
  置き換える。
- **#368（strategy移設・特例廃止、merge済み: PR 384）**: Issue本文 `Depends on`。
  run面からstrategy picker・`StrategyWriteArbiter` restart経路が撤去済みであること。
  本specの面統合は#368適用後のrun面構成を基準にする。
- **#365（正本改訂、merge済み: PR #379）**: `CONTEXT.md`の語彙規約・材料語彙は#365が
  改訂済みである。
- **後続**: #370（onboarding/hint表記。T-07前置き省略の契約表現）、#372（AI相談統合。
  **T-07方法選択の最終形（2択）の完成条件を所有する**（RD-1）。exchange idle entryの
  T-07方法選択への統合とidle AI相談）、#373（exchange失敗の手段別再投影）、
  #375（T-08復元初期値・scope mismatch原因別remedy）、#377（cleanup）。
- **前提（実装済み・accepted）**: specs 13/52/84/172/194/195/209/210/228/230/231/271/328/331
  の現行契約、organization-run-ux §4/§5/§6、organizer-to-be-ux.md（accepted、PR #364）。

## Open questions

実装開始前に解消が必須な問いはない（初版Contract notes 1〜5と2nd review指摘はResolved
decisions RD-1〜RD-7
として決着済み。本specの受入はreviewで判定する）。非blocking事項:

1. T-07のscope要約・方法選択の最終文言、T-09/T-13の見出し文言、確認dialogの文案（D-13語彙に
   沿うことのみ拘束）は実装PRで確定する。
2. T-13で診断導線を提供する原因の集合（bug系`InputUnavailable`は既存copy splitから必須。
   その他の原因での提供可否）は実装PRのreviewで確定する。
3. 統合により未使用になるstringの最終集合は実装PRのreference grepで確定する。
4. T-09のphase行が「現在の段」のみか「完了段を含む列挙」かは、organization-run-ux §6の
   announce規約（1段階1回）と200% reflowを満たす範囲で実装PRのreviewで確定する
   （spec 123収束対象）。

## Change history

- 2026-09-24: **Amended by #417**（accepted spec [spec 417](../417-scope-first-method-choice/spec.md)、head `bc459e9fa0`）: RD-3・D-06節・20状態→8ユーザー状態対応表・0候補scenarioは、manual runでは検出後に方法選択面へ到達する形へAmend（onboardingは現行どおり）。0候補時のdisplay層pass-through（RD-3）は#417の`State.ScopeConfirmed`（方法選択面）新設によりstate層の契約へ置換される。
- 2026-09-20: **status を implemented へ移行**。実装PR [#387](https://github.com/nunu1733/NunuLauncher/pull/387) merge (merge commit `7ec9e9d3fe`)。実装はChatGPT実装レビュー4回（CR×3 → Accepted）と独立監査（[docs/assessment/pr-387-run-display-integration.md](../../docs/assessment/pr-387-run-display-integration.md)、verdict: pass）を経て受理。RUN-AC-01〜10は監査記録のtest evidence（unit oracle・UI instrumentation lane・4条件screenshot evidence）を正本とする。

- 2026-09-20: Phase1 re-entry（4th revision）。3rd review
  （[Issue #369 review comment][8]、Changes requested、中1）に対応:
  - **中（T-08復帰経路でT-09が再び検出を表示し得る）**: 可視phase projectionの更新順序契約を
    明文化（RD-7）: `PreparationPhase.CAPTURE`/`PLAN`は、T-09を可視に戻す
    `State.Capturing`/`Planning`のpublishより先に同一lock区間で確定する。
    `confirmSelection()`のguard publish・D-06内部継続・`runComposedPhase()`cancel gateの
    各経路に適用し、候補あり経路のT-08→T-09復帰oracle（`DETECTION`再announce 0回）を
    RUN-AC-01へ追加。
- 2026-09-20: Phase1 re-entry（3rd revision）。2nd review
  （[Issue #369 review comment][7]、Changes requested、中2/低1）に対応:
  - **中（D-06非表示のconflation依存）**: 非表示の保証をface mapping（純関数）による決定的
    写像へ変更（RD-7）。conflation前提の記述を削除。
  - **中（最初の可視phaseがcaptureになり得る）**: 準備phaseの可視projection
    （`PreparationPhase`）を新設し、phase行をprojection由来に変更（RD-7）。legacy
    `Capturing`（admission直後）は検出として投影される。
  - **低（Issue本文のT-07 scope）**: Issue本文のScope/Non-goalsをRD-1へ整合させた
    （transitional T-07 / 最終2択は#372）。
- 2026-09-20: Phase1 re-entry（2nd revision）。初版へのspec review
  （[Issue #369 review comment][6]、Changes requested、高1/中2/低1）に対応:
  - **高（検出中cancel競合）**: T-09中断により到達可能になる「検出中cancel → detector復帰 →
    cancel済みrunが`RUN_STARTED`発行/composition実行」を閉じるcancel gateを新設
    （RD-6、RUN-AC-10、Behavior scenario追加）。処分文書§3.3へ2026-09-20追記を実施（同じPR）。
  - **中（D-06と「内部state machine不変」の衝突）**: D-06を表示統合として実装する解釈へ
    一本化（RD-3）。`Selecting`不進入の要求を削除し、処分文書§3.3へ解釈を追記する。
  - **中（T-07の所有境界）**: transitional T-07の契約を明文化し、2択T-07の最終完成条件を
    #372へ移管（RD-1。Scope/Non-goals/Dependencies/AC更新）。
  - **低（re-entry情報の陳腐化）**: main再anchor、#365/#366/#367/#368のmerge反映、
    evidence更新（plan参照）。
  初版Contract notes 1〜5はResolved decisions RD-1〜RD-6として本文規定へ繰り入れ、
  2nd review指摘はRD-7として追記した。
- 2026-09-19: Draft created for #369（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `3076bdae7e`、PR #364）、処分文書draft（PR #378
  §3.3/§3.13/§4.1/§5/§8）、#366/#367/#368 spec/plan draft
  （`bf00f96175`/`a50f074ac2`/`206a4c1198`）、現行実装調査（`ManualOrganizationRun.kt`、
  `ManualOrganizationPreferences.kt`、`MissingAppSelectionScreen.kt`、`ExchangeFlowUi.kt`、
  `OrganizationOnboardingProposal.kt`、`PreferenceRoutes.kt`、organizer unit/instrumentation
  test群、`strings.xml`/`values-ja/strings.xml`）を入力に作成。

[1]: https://github.com/nunu1733/NunuLauncher/issues/369
[2]: https://github.com/nunu1733/NunuLauncher/issues/366
[3]: https://github.com/nunu1733/NunuLauncher/issues/368
[4]: https://github.com/nunu1733/NunuLauncher/pull/378
[5]: https://github.com/nunu1733/NunuLauncher/issues/365
[6]: https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5740051014
[7]: https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5746598782
[8]: https://github.com/nunu1733/NunuLauncher/issues/369#issuecomment-5746725434
