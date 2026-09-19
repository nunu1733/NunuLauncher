---
issue: "#369"
status: draft
requirements: [FR-004, FR-006, FR-015, NFR-009, NFR-011]
risk: []
updated: 2026-09-19
---

# run面の表示を8ユーザー状態へ統合し、canonical順序（前置き→検出→[選択]→capture/plan→確認）を固定してD-06条件表示とD-13語彙規約を適用する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-05, D-06, D-12, D-13, §5.1 T-07〜T-13, §5.3 canonical順序, §8.1 8状態, §8.3, §9）。
> 本specは[Issue #369][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> 前提: [Issue #366][2]のhub shell（T-01、「整理を開始」CTA）と[Issue #368][3]のstrategy
> picker撤去・run中変更特例廃止が実装・merge済みであること（Issue本文 `Depends on`）。
> #366はspec draft（branch `issue-366-spec-plan`、commit `bf00f96175`）、#368はspec/plan
> draft（branch `issue-368-spec-plan`、commit `206a4c1198`）を接続先として参照している
> （2026-09-19時点）。
> 処分の正本: [docs/product/organizer-disposition-migration.md][4] §3.3/§3.13/§4.1
> （PR #378、proposed）が本件を「spec 52/228 Amend、spec 210注記、#369が実行」と定める。

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
（表示統合であり、D-05の契約上の根拠）。唯一の状態遷移レベルの変化はD-06に伴う
0件時の`Selecting`不進入であり、Contract notes 3で範囲を限定して定める。

## Outcome

run面のユーザー向け表示がTO-BEの8状態（organizer-to-be-ux.md §8.1）に統合される。
hubの「整理を開始」から到達するrun前置き面T-07で「そのまま整理」を選ぶとrun admission
（RUN lease取得）が発生し、T-09準備中面に検出→capture→planの統合progress（phase行＋中断）
が現れる。検出で候補があればT-08選択面へ一時遷移し（明示選択契約・unchecked初期値は不変）、
候補0件なら選択面を表示せずcapture/planへ続行する（D-06）。提案はT-10確認面（concrete /
count-only / Add-run再previewの3変種を1面に統合）、適用はT-11適用中面、結果はT-12結果面
（成功・変更なし・適用されなかった[stale等]・部分的失敗の変種）に統合表示され、typed失敗と
入場前stale（`DETECTED_BEFORE_REVIEW`）はT-13「実行できませんでした」（原因＋次の手段:
再試行/中断/診断）1面に畳まれる。Back/中断/破棄/キャンセルの語彙と確認規則（D-13 §9）が
全run面で一貫する。coordinatorのstate machine、typed outcome、journal相関の安全契約は
D-06の0件経路を除いて一切変わらず、既存契約testはそれを証拠として示す。

## Scope

- **T-07 run前置き面**: run面のIdle/Cancelled分岐（V-06）の開始rowを「方法選択とscope要約」
  の前置き面へ置換する。主CTA「そのまま整理」の選択で既存の`start(trigger)`経路により
  run admission（RUN lease取得）が発生する。scope要約はadmission前に取得できる事実のみから
  構成し、検出・compositionの前倒し（件数の事前計算）をしない（D-06の「検出前に候補有無を
  前提化しない」の維持）。「AIに相談」分岐の実装は#372が所有する（Contract notes 1）。
- **canonical順序の固定（D-05）**: admission → 検出 → 対象選択（候補あり時のみT-08へ
  一時遷移）→ capture+plan → 提案の確認 の順序をrun面の面構成として固定する。
  現行coordinatorは既に`start()`で検出→（選択）→composed phase（capture/plan）の順に
  遷移するため、本Issueの変更は表示統合が主であり、遷移順序そのものの再設計は行わない。
- **T-09統合progress面**: `Capturing` / `CandidateDetection` / `Planning`を1つの準備中面に
  統合する。面はcanonical順序に沿ったphase行（現在の段: 検出 / capture / plan。選択は
  T-08一時遷移として扱う）と中断actionを持つ。phase遷移の読み上げは1段階につき1回である
  （organization-run-ux §6 Progress）。
- **D-06候補0件時の選択面非表示**: `start()`の検出結果が0件のとき、選択面（T-08）を
  表示せずcapture/planへ続行する。intent-bound runのscope gate契約（spec 331の完全一致
  gate・mismatch時の選択面再表示・typed `SCOPE_MISMATCH`）は現行どおり維持する
  （Contract notes 3のguard条件）。
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
  exchange idle entry row（V-27）のT-07方法選択への統合も#372が所有し、本Issueでは
  spec 205 V1規約どおりIdle/Cancelled面にhostされ続ける。
- durable pending intent（D-08系、#374）とstatus cardへの退避。
- 内部state machine・typed outcome・journal event・diagnostics契約の変更（D-06の0件経路を
  除く。Contract notes 3）。
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
| 対象選択 | `Selecting` | T-08（候補あり時のみ。0件時は不進入、D-06） |
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
（spec 331 D-5/D-2）は現行契約どおり維持する。

## Behavior scenarios

### Scenario: hubから前置き面を経てrun admissionが発生する

Given #366のhubと#368のstrategy撤去が適用済みであり、run coordinatorが`Idle`である
When hubの「整理を開始」からrun面を開く
Then 方法選択とscope要約の前置き面（T-07）が表示され、主CTA「そのまま整理」が存在する
And 「そのまま整理」を選ぶとrun admission（RUN lease取得）が発生し、T-09準備中面の
最初のphase（検出）へ進む
And 前置き面の表示中は検出・compositionの実行がなく、候補有無の前提化やzero-writeを超える
書込みが発生しない

### Scenario: 準備中は1つのprogress面に統合される

Given runがadmission済みである
When runが`CandidateDetection` → （候補ありならT-08へ一時遷移して戻る）→ `Capturing` →
`Planning`へ遷移する
Then ユーザーには1つの準備中面（T-09）が表示され、phase行が現在の段
（検出 / capture / plan）を示す
And 面には中断actionがあり、選択・提案がまだ無いため確認なしでzero-write中断できる
（Contract notes 4の確認規則）
And 各phase遷移はTalkBackへ1回だけannounceされ、連続的な再announceやspamを生まない

### Scenario: 候補0件では選択面を表示せず続行する（D-06）

Given 検出が`Ready`で候補0件であり、validated intentがboundされていない
When 検出phaseが完了する
Then 選択面（T-08）は表示されず、runはcapture/planへ続行する
And 0件notice面（`manual_organization_missing_apps_empty`）と「続行」の選択面は
表示されない（旧表示oracleの更新対象）
And この経路はエラー・typed失敗ではなく、通常の全体整理として計画される

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

### Scenario: 内部契約はD-06経路を除いて変更されない

Given 本Issueの実装PRが作成されている
When state machine・typed outcome・journal相関を固定する既存契約test
（`ManualOrganizationRunTest`のadmission/stale/zero-write/journal相関群、
`ExchangeFlowStateHolderTest`、E2E `staleProductionConfirmationDoesNotWrite`等）を実行する
Then D-06の0件経路を対象とするtestを除き、すべてが無編集でgreenである
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
- D-06の0件skipは`start()`の検出直後の単一判定点で行われ（plan参照）、選択面が開く既存経路
  （候補あり・intent-bound mismatch）の遷移順序とlock構造は変更しない。
- 中断・Backの確認dialogはUI層のaffordanceであり、coordinatorのcancel/dismiss契約
  （zero-write、`USER_CANCELLED` journal規則、admission後不受理）を変えない。
  「UI disabledはaffordanceにすぎない」原則（spec 328）に従い、不受理はcoordinator gateが
  構造的に担保する。

## Data and state

- **読む**: run coordinator state（既存`stateFlow`）、durable status projection（Idle面の
  現行表示維持）、既存selection/preview/summary/recovery state。新規の読取seamは設けない。
  T-07のscope要約はadmission前に取得できる事実のみから構成する。
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
      固定され、T-07前置き面の「そのまま整理」でrun admission（RUN lease取得）が発生する。
      候補0件時に選択面が表示されずcapture/planへ続行する（D-06）。intent-bound runの
      scope gate契約（spec 331）と検出`Unavailable`時の現行継続経路は変化しない。
      （Issue受入1）
- [ ] **RUN-AC-02**: 20 coordinator状態の表示が対応表のとおり8ユーザー状態へ統合され、
      typed失敗（V-11〜V-14系）と入場前stale（`DETECTED_BEFORE_REVIEW`）がT-13
      「実行できませんでした」の原因＋次の手段（再試行/中断/診断）で表示される。typed分類名は
      補助情報である。T-09は1つの統合progress面（phase行＋中断）として表示される。
      （Issue受入2）
- [ ] **RUN-AC-03**: 内部state machine・typed outcome・journalの安全契約を固定する既存
      契約testが、D-06の0件経路を除いて無編集でgreenである（表示統合が契約を変えないことの
      証拠）。D-06経路のtest期待値更新はD-06決定の反映としてPRに記録される。
      （Issue受入3）
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

## Test oracle

| AC | Evidence |
|---|---|
| RUN-AC-01 | unit: `start()`のD-06 skip（0件＋intent未boundで`Selecting`不進入・plain compose直行）、guard条件（intent-bound・export scope候補あり＋検出0件では選択面が開く）、検出`Unavailable`継続の回帰。instrumentation: T-07→「そのまま整理」→T-09検出phase→0件続行の遷移と、0件時に`missing_apps_empty`/`missing_apps_continue`が表示されないことの否定的観測 |
| RUN-AC-02 | instrumentation: 8状態への統合（T-09が検出/capture/planで同一面構成を保つこと、T-13見出し＋原因＋再試行/中断（＋該当時診断）の構造、T-10/T-12の変種表示）。既存の各失敗原因文字列がT-13上に現れることのassert |
| RUN-AC-03 | `ManualOrganizationRunTest`・`ExchangeFlowStateHolderTest`・organizer unit gateの無編集green（D-06対象経路を除く）+ D-06経路testの更新diff + `ManualOrganizationProductionE2EInstrumentationTest.staleProductionConfirmationDoesNotWrite`のgreen |
| RUN-AC-04 | instrumentation: T-10中断の確認dialog（1回・破棄/中断語彙）、T-08選択あり中断の確認、復元確認キャンセルの確認なし、Applying checkpoint前の確認付き中断、checkpoint後の不受理（既存gate oracle）。unit: cancel/dismiss契約の既存test無編集green |
| RUN-AC-05 | `MissingAppSelectionInstrumentationTest.zeroCandidatesShowsTheEmptyNoticeAndStillContinues`等の旧oracle更新diff + obsolete理由のPR本文記録 + spec 228/210改訂diff review |
| RUN-AC-06 | Compose semantics assertion（見出し・原因・手段の読み上げ、phase遷移announce回数、traversal順）+ focus restoration test + 200% font scale test + light/dark × ja/default screenshot evidence（organization-run-ux §6表に基づく確認記録） |
| RUN-AC-07 | 既存preview/confirmation/result/recovery系instrumentationの無編集または表示面移設のみの更新でgreen + spec 194/195/209/228/230/231/210対応oracleのdiff review |
| RUN-AC-08 | specs 52/228/210のdiff review（改訂箇所がScope節と一致し、safe apply契約・明示選択契約・spec 210文言が不変であること） |
| RUN-AC-09 | 新規stringの`values/`と`values-ja/`のname集合・placeholder一致の機械確認 + 削除stringのreference grep（0件）+ hardcoded literal grep |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane
（`ManualOrganizationPreferencesInstrumentationTest`、`MissingAppSelectionInstrumentationTest`、
exchange系）、CI `final-status` green（本Issueは `risk: layout-data`/`risk: migration` を
付けない。表示・navigationのみでpersistent state・DB書込み経路に触れないため、high-risk
evidence gateの対象外）。

## Contract notes（owner reviewで確認すべき解釈）

1. **T-07の「AIに相談」の扱い**: Issue本文scopeは「『そのまま整理 / AIに相談』の方法選択」
   をT-07に置くが、AI分岐の実装は#372（Issue本文明記）。本specは「#369では『そのまま整理』
   のみを方法選択のprimary CTAとし、AIに相談の選択肢行は新設しない（capability先取り禁止、
   #366原則）。既存exchange idle entry row（V-27）はspec 205 V1規約どおり同一面にhostされ
   続け、#372がこれをT-07の方法選択へ統合する」と解する。#369でAI選択肢をdisabled表示する
   ことをowner reviewが選んだ場合は、本specの該当行とRUN-AC-01の否定的観測を修正する。
2. **中断後の行き先とdecision pairのcancel**: TO-BE §5.3は`Reviewing --> Hub: 中断（破棄確認）`
   と図示し、§9は中断を「runを止めてhubへ戻る」と定義する。本specは「中断（確認応答後）は
   run停止後にhubへ戻る（system Backと同一のnavigation結果）」と解する。一方でspec 209の
   decision pair契約（confirm/cancel pairの視覚構造）は保持し、cancel側のlabel・確認・
   遷移のみをD-13へ合わせる。確認なしにT-07面へ残る解釈を選ぶ場合は、本specの該当scenarioと
   RUN-AC-04を修正する。
3. **D-06の0件skipとstate machineの可観測変化**: 「内部state machine不変」はD-05の表示統合に
   関する契約上の根拠であるが、D-06は候補0件時に`State.Selecting`へ進入しないという
   遷移レベルの変化を要求する。本specは0件skipをcoordinatorの`start()`内の単一判定点で
   実装する解釈を採る。guard条件: 検出0件かつ（intent未bound または export scopeの候補0件）
   のときのみskipする。export scopeに候補がある場合は選択面を開き、spec 331のmismatch表示
   契約を保存する。代替（UIが0件`Selecting`を観測して自動`confirmSelection(emptySet())`を
   発行する解）は、明示選択契約（spec 228 D-1）が要求するユーザーの明示actionを偽装し、
   state/表示の瞬間的な不整合を生むため不採用。よってRUN-AC-03の「無編集green」はD-06対象
   経路を除くものとし、0件経路のtest更新はRUN-AC-05のobsolete理由記録義務で追跡する。
4. **T-09中断の確認要否**: TO-BE §5.1のT-09行は「中断（破棄確認）」と表記する一方、
   D-13 §9の中断の確認規則は「提案または選択があるとき1回」である。T-09時点には提案も選択も
   存在しないため、本specはD-13規則を正とし「T-09の中断は確認なし（zero-write、失う作業なし）
   で可能」と解する。T-09でも常に確認を要求する解釈がowner reviewで選ばれた場合は、
   Behavior scenariosの該当行とRUN-AC-04を修正する。
5. **T-07のscope要約の内容**: TO-BE §5.1 T-07は「対象要約」を内容に挙げるが、admission前に
   取得できる事実は限られる（検出・compositionの前倒しはD-06の「検出前に候補有無を前提化
   しない」と衝突する）。本specはscope要約を「対象範囲の説明（ホーム全体の整理であること、
   確認前に適用されないこと）の文言」として解し、件数の事前計算を要求しない。具体的文言は
   実装PRで確定する（非blocking）。

## Dependencies

- **#366（hub shell、OPEN・実装未着手）**: hub destination、「整理を開始」CTA、材料セクション
  が存在することが前提。本specのT-07は#366の暫定導線（hub CTA → 既存run面）を前置き面経由へ
  置き換える。spec執筆は#366 draft（`bf00f96175`）との整合で可能。実装着手は#366 merge後。
- **#368（strategy移設・特例廃止、OPEN・実装未着手）**: Issue本文 `Depends on`。run面から
  strategy picker・`StrategyWriteArbiter` restart経路が撤去済みであることが前提。本specの
  面統合は#368適用後のrun面構成を基準にする。
- **#365（正本改訂、OPEN）**: `CONTEXT.md`の語彙規約・材料語彙は#365が所有する。実装着手は
  #365 merge後（AGENTS.md「正本を先に」。#366/#367/#368と同一判定）。
- **後続**: #370（onboarding/hint表記。T-07前置き省略の契約表現）、#372（AI相談統合。T-07の
  方法選択へのexchange entry統合とidle AI相談）、#373（exchange失敗の手段別再投影）、
  #375（T-08復元初期値・scope mismatch原因別remedy）、#377（cleanup）。
- **前提（実装済み・accepted）**: specs 13/52/84/172/194/195/209/210/228/230/231/271/328/331
  の現行契約、organization-run-ux §4/§5/§6、organizer-to-be-ux.md @ main `3076bdae7e`
  （accepted、PR #364）。

## Open questions

実装開始前に解消が必須な問いはない（Contract notes 1〜5の解釈確認をowner reviewが所有し、
確認前は本specは`draft`のままである）。非blocking事項:

1. T-07のscope要約・方法選択の最終文言、T-09/T-13の見出し文言、確認dialogの文案（D-13語彙に
   沿うことのみ拘束）は実装PRで確定する。
2. T-13で診断導線を提供する原因の集合（bug系`InputUnavailable`は既存copy splitから必須。
   その他の原因での提供可否）は実装PRのreviewで確定する。
3. 統合により未使用になるstringの最終集合は実装PRのreference grepで確定する。
4. T-09のphase行が「現在の段」のみか「完了段を含む列挙」かは、organization-run-ux §6の
   announce規約（1段階1回）と200% reflowを満たす範囲で実装PRのreviewで確定する
   （spec 123収束対象）。

## Change history

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
