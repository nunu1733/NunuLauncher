---
issue: "#368"
status: accepted
requirements: [FR-016, NFR-009]
risk: []
updated: 2026-09-19
---

# 整理方針（strategy）pickerを材料面T-05へ移設しrun中変更特例（確認なし破棄+再start）を廃止する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-03、§4.4「run中のstrategy変更」、§5.1 T-05、§7.1 strategy行、§8.3 strategy行）。
> 本specは[Issue #368][1]の成果物である。Status: **accepted**（2026-09-19、
> re-review [Approve相当](https://github.com/nunu1733/NunuLauncher/issues/368#issuecomment-5742960028)
> @ head `5d5b61ee4e`。Contract notes 1〜3はowner指示（reviewクリア後に実装へ進行）に
> より受入: (1) 材料を見に行く導線はhub経由のみ、(2) spec 328境界はoption (A)、
> (3) T-05は専用destination）。
> 前提（いずれもmerge済み。baseline `ca9c171e91c2` 時点で確認）:
> [Issue #365][5]の正本文書改訂、[Issue #366][2]のhub shell（T-01、材料セクション、
> `OrganizerHubPreferences.kt`）、[Issue #367][3]の材料集約（設定側organizer row廃止）。
> 処分の正本: [docs/product/organizer-disposition-migration.md][4] §3.9（accepted、
> PR #378）が本件を「spec 182/283 Amend、#368が実行」と定める。spec 328のstrategy固有条項は
> Contract notes 2（本revisionで一意化済み）の境界どおり、#368が狭い改訂ownershipを持つ。

## Problem

strategy picker（radio＋説明＋selected affordance）は現在、manual organization実行面
（`ManualOrganizationPreferences`、すべてのrun状態とIdleに常時表示）に置かれており、
run中でも常にcommit操作が可能である。run中にstrategy選択をcommitすると、確認なしで
実行中のrunが破棄され（pre-checkpoint cancelのためzero-writeではあるが、確認済み寸前の
提案を含む選択・提案が失われる）、同一triggerで新しいrunがstartされる。この特例は:

- 操作時点で予告がなく、pickerが常時有効に見える（AS-IS監査 E-7 Significant、
  監査§12 D-3）。
- category/lock等がrun中lease拒否（「次回反映」）であるのに対し、strategyだけが
  「run破棄+再start」という別挙動を持つ3分類の例外である（監査 F-08）。
- scoped exchangeのexport生成中もpickerが有効のままというgate隙間を作る（監査§12 D-4。
  安全性欠陥はないが、凍結された選択を持つrunが置き換えられる不可解な中間状態を生む）。
- strategy選択変更時のrun dismiss+再startが、stale機構と並ぶ第三の「run無効化」経路と
  なっている（監査§11.2）。

さらに現行のstrategy書込は、run stateの事前確認（Main上の`state !is Idle/Cancelled`読取）
だけをgateとしており、書込開始からpublication完了までの間にrun admission
（`OrganizationOperationLease`のRUN取得）が割り込む競合窓が構造的に閉じられていない。
category/custom-category authoringが`AUTHORING` leaseをmutation全体で保持して
RUN/RECOVERYと同一admission domainに入れているのに対し、strategy書込だけが
admission domainの外に置かれている。

accepted TO-BE D-03は「run中の恒常authoring（分類・lock・方針・記録toggle）は一律不可、
『中断してから変更する』を唯一の規則」「strategy pickerをrun面から撤去し材料面のみに置く」
「run面内の『方針変更→確認なしで破棄+再start』の特例を廃止する」と決定した。

## Outcome

strategy選択はhub（T-01）の材料セクションから開く材料面T-05（整理方針）でのみ行える。
実行面（manual organization画面、全run状態・Idle・`MANUAL`/`ONBOARDING`両entry）には
picker行も読み取り専用表示も現れない。strategy書込は`OrganizationOperationLease`の
`AUTHORING` kindを書込開始から全終端（commit・非commit・storage失敗・cancel）まで保持し、
category authoringと同一の単一admission domainでrun/recoveryと相互排他する。書込開始の
結果はarbiterがtypedな開始outcome（開始・run/recovery active拒否・他authoring占有拒否・
書込single-flight拒否）としてcallerへ返し、いずれの拒否もstore呼出を開始しない
（防御呼出を含めて黙ってdropさせない）。run操作がactiveな間のstrategy選択commitは
「中断してから変更する」の1規則で説明されるtyped拒否になり、書込先行時はrun startが
`Busy`に拒否される。commit時のrun dismiss+再start経路は
削除され、選択は次回run開始のcompositionに反映される（現行fresh composition契約＝
spec 52「a run never reuses a prior snapshot」は不変）。strategy書込のwrite-vs-write
single-flightと検証付き書込契約（spec 182 AC-3b/AC-7）は維持される。

## Scope

- 材料面T-05の新設: hub材料セクションに「整理方針」entry（既存材料T-02/T-03/T-04と同じ
  `NavigationActionPreference` pattern）を追加し、既存のstrategy picker UI
  （runtime-supported catalogのradio group＋localized name/intent description＋
  spec 283のselected affordance）を専用destinationとして受ける。
- 実行面からのpicker撤去: `ManualOrganizationPreferences` からstrategy picker行・
  選択状態・arbiter wiringを削除する。run面にstrategyの読み取り専用表示は置かない
  （strategyを確認・変更する導線は材料面T-05のみ。Contract notes 1参照）。
- run中変更特例の廃止: `StrategyWriteArbiter` からrestart path
  （`RESTART_RESERVED`/`RESTARTING`状態、`restartRun`/`restartSuppressed`/`restartNeeded`
  seam、commit時 `coordinator.dismiss(); coordinator.start(trigger)` 契約）を削除する。
  arbiterは書込single-flight（`Idle → Writing → Idle`）として維持する。
- 書込のadmission domain参加: strategy書込は開始時に`OrganizationOperationLease`の
  `AUTHORING` tokenを取得し、全終端で解放する。取得はarbiterのMain-confined遷移点で
  `Idle → Writing`と原子に行い、取得失敗時は`Writing`へ入らずstore呼出も開始しない。
  arbiterは書込開始の結果をtypedな開始outcomeとしてcallerへ返す:
  `Started` / `RefusedRunOrRecoveryActive`（run/recovery operationがadmission domainを
  占有） / `RefusedAuthoringBusy`（他の`AUTHORING` token — category authoring等 — が
  占有） / `RefusedWriteBusy`（同一arbiterの書込がin-flight。single-flight）。
  すべての拒否outcomeはstore呼出なしのtyped non-writeであり、防御呼出が黙ってdropしない。
  UI affordance（行のdisabled＋理由文言のlive region通知）はrun/recoveryのoperation
  lifetimeから導出する表示であり、構造gateはlease取得自体である（spec 328が確立した
  「UI disabledはaffordanceにすぎない」原則の継続）。run/recovery active時はdisabled＋
  理由文言、他authoring占有・single-flight拒否時は行が有効表示のままtyped outcomeに
  対応するretry文言をlive regionで通知し、両者を区別する。
- operation lifetimeの可観測化: run coordinatorが「activeなrun operation
  （`activeOperation`）またはrecovery operation（`recoveryLease`）が存在するか」の
  projectionを`State`列挙と独立に公開する。表示state（`Applied`/`NoChanges`/`Stale`等の
  終端stateが表示上残る現行契約）ではなくoperation lifetimeを正本とする。このprojection
  はrun/recoveryのoccupancyを示すものであり、admission domainの占有と同値ではない
  （domainは他の`AUTHORING` tokenによっても占有され得る。その場合のT-05での見え方は
  前bulletのtyped outcomeとretry文言で扱う）。拒否outcomeの分類
  （run/recovery拒否か他authoring占有か）はこのprojectionを読んで行う。
- exchange逆参照の除去: pickerがrun面から消えrestart経路が廃止されることで無意味になる
  exchange側strategy gate（`strategyWriteStartBlockedFor`/`strategyRestartSuppressedFor`、
  `strategyArbiterBusy` callback、`IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY` status）を
  削除する。strategy書込とexchangeの競合はleaseを通じたrun seam（`start`/`attachIntent`）
  の相互排他に還元される（書込中のCTA `start()`は`Busy`になりtypedに再試行可能）。
- spec 182改訂（実装PRで実施）: §Preview integration第1bullet「Strategy selection lives
  on the manual-run surface」を材料面T-05配置＋operation不在時のみ書込可へ、
  §Selection contract write-authority step 4を「commit時にcallerがfresh compose/plan
  cycleをstartする」から「commitされた選択は次回runのcomposer readを通じてplanningへ到達
  する。callerがactiveなrunをdismiss/restartしない」へ改訂。対応する2 scenario
  （書込scenarioのrun面表記、preview表示中のstrategy変更replan scenario）を実態に合わせ
  改訂する。
- spec 283改訂（実装PRで実施）: Non-goalsの「pickerの...run 表面への配置の変更」凍結と
  「選択時の active run の dismiss + 再計画の挙動変更」凍結を解除し、AC-1〜AC-9の
  selected affordance契約がT-05に適用される旨を追記する（契約内容自体は不変）。
- spec 328のstrategy固有条項の狭い改訂（実装PRで実施。Contract notes 2参照）: pickerの
  run面撤去とrestart廃止により無効化されるstrategy固有条項（run内entry picker freeze、
  idle picker continuation中無効化、commit時gate、exchange側書込開始gate、
  `Idle → Writing → RestartReserved/Restarting → Idle`状態機械のrestart部分、
  `IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`、およびAC-3/AC-5内の対応oracle項目）を、
  lease基準の新契約へ置換・削除する。freeze再設計・durable契約（revision 2本体）は
  処分どおり#374が所有する。処分文書§3.9/§3.14のownership境界も同一PRで現実
  （spec 328はPR #353で実装済み）に合わせ更新する。

## Non-goals

- strategy catalog本体・`LayoutStrategySelectionModule` store・fail-closed契約
  （spec 182 AC-3/AC-7、ADR-0012）の変更。runtime-supported catalog、default-as-effective
  表示、読取失敗時の非選択表示、書込時catalog検証・atomic publication・failure時の既存
  選択保持はすべて現行のまま。
- `organizer_strategy_selection/selection-v1` のformat・schema・読書き経路の変更
  （persistent state変更なし。Issue本文のRisk節どおり）。
- `OrganizationOperationLease`のadmission domain自体の設計変更（単一active token、
  RUN/RECOVERY/AUTHORING 3種）。strategy書込は既存domainへ参加するだけで、domain側は
  変更しない。
- run面の他の表示統合・canonical順序・T-07前置き面（D-05/D-06、#369）。
  選択面（T-08相当）・preview（T-10相当）・語彙の変更は行わない。
- preview面のstrategy identity表示（`manual_organization_preview_strategy`）や
  consequence countsの変更（spec 194/195/235）。
- exchange導線本体とspec 328の非strategy条項（idle start row freeze、CTA single-flight、
  attempt anchor、owning runId再照合、Back interception、取り込み成功状態本体）の変更。
  strategy関係のgate除去はScope節どおり行うが、freeze再設計・durable pending intent
  （spec 328 revision 2）は#374が所有する。
- Usage AccessのJIT化（#371）、材料の他の面の再設計（#367で完了済みの対象外）、
  AI相談統合（#372）、durable pending intent（#374）。
- onboarding提案のflow接続変更（D-16表記、#370）。onboarding entry（`OrganizationEntry.
  ONBOARDING`）で開かれる実行面からもpickerが消えることは本Issueの変更だが、提案→run
  接続のflow自体は変更しない。

## Domain language

- **T-05（材料: 整理方針）**: organizer-to-be-ux.md §5.1の表面ID。strategy選択の恒常面
  （radio+説明、主CTA「選択」）。AS-ISのV-26（run面picker）の移設先である。
  hub材料セクションから到達する。
- **operation不在 / operation active**: run coordinator上にactiveなrun operation
  （`activeOperation != null`）もrecovery operation（`recoveryLease != null`）も存在しない
  こと/いずれかが存在すること。表示state列挙（`State.Idle`/`State.Cancelled`等）では
  定義しない。現行実装は終端state（`Applied`/`NoChanges`/`Stale`/`Cancelled`等）を
  operation終了後も表示上保持するため、state述語はoperation lifetimeの正本にならない。
  なおadmission domainの占有はこれと同値ではない: domainは上記に加えて他の`AUTHORING`
  token（category authoring等、strategy書込自身を含む）によっても占有され得る。
- 新規の`CONTEXT.md`語彙追加はしない（「材料」「中断してから変更する」の語彙は#365が
  追加済み。本specはorganizer-to-be-ux.md §10の語彙を借用する）。

## Behavior scenarios

### Scenario: pickerは材料面T-05にのみ存在する

Given #365/#366/#367が適用済みである（hub材料セクションが実在する）
When hubの材料セクションで「整理方針」entryを開く
Then runtime-supported catalogのstrategy行（radio＋localized name/description＋selected
affordance）が表示され、effective selection（persisted selection、absentならbundle
default）の1行だけが選択済みとして表示される
And manual organization実行面（Idle/Cancelledを含む全run状態、`MANUAL`/`ONBOARDING`
両entry）を表示したとき、strategy section・radio行・picker関連のfrozen理由行が
画面のどこにも存在しない

### Scenario: operation不在時の選択commitは従来どおり検証付き書込で次回runに効く

Given run coordinator上にactiveなoperationが存在せず、T-05が表示されている
When ユーザーが未選択のstrategy行を選択する
Then `AUTHORING` tokenが取得され、選択はRule Management所有の検証付き書込command
（書込時catalog検証、generation/digest付きatomic publication、失敗時の既存選択保持。
spec 182 AC-3b/AC-7）を通じて公開され、UIのselected affordanceが新しい行へ移る
（spec 283 AC-2）
And 書込終端で`AUTHORING` tokenが解放される
And その後に開始されたrunのcompositionは、公開された選択をcomposer read経由で受け、
plan結果とpreviewのstrategy identityがその選択を反映する（fresh composition契約は不変）
And activeなrunが存在しないためdismiss/restartは一切発生しない

### Scenario: run/recovery active中のstrategy選択commitはleaseで構造的に遮断される

Given run operationまたはrecovery operationがactiveである（`activeOperation`または
`recoveryLease`が存在し、`AUTHORING` tokenは取得できない）
When T-05が表示される（防御ケース。実測どおりproduction navigationでは到達しない:
two-pane expanded設定でもsecond paneは単一NavHostのためdestinationの同時composeは
発生せず、run面離脱時の`onDispose`→`dismiss()`によってT-05 compose時点でoperationは
終了している。plan「Risk」参照。本scenarioは将来のnavigation変更に備える防御契約である）
Then picker行は選択をcommitできない状態（disabled affordance）で表示され、理由文言が
「中断してから変更する」規則をlive regionで通知する
And この状態で選択操作を呼んでも、arbiterは`RefusedRunOrRecoveryActive`の開始outcomeを
返してstore呼出を開始せず（構造gate）、selection storeは変化しない（typed non-write。
防御呼出が黙ってdropしない）
And manual organization実行面上にはstrategy選択を開始するUI経路が存在しない

### Scenario: 他の材料authoring操作がadmission domainを占有している間の選択はtypedに案内される

Given category override等の別のauthoring操作が`AUTHORING` tokenを保持しており、
run/recovery operationは存在しない（T-05の行は有効表示）
When T-05でstrategy行が選択される
Then arbiterは`RefusedAuthoringBusy`の開始outcomeを返し、store呼出は開始されず
selection storeは変化しない
And T-05はtyped outcomeに対応するretry文言（他の整理操作の完了後に再試行）をlive regionで
通知する（run/recovery用のfrozen理由とは文言が区別される）
And 占有authoring操作の終端後に選択を繰り返すと、`Started`として検証付き書込が完了する

### Scenario: 書込中のrun開始はBusyに拒否され、run開始中の書込は開始しない

Given T-05でstrategy書込がin-flightである（`AUTHORING` token保持中、publication前で
停止している）
When 実行面からrun開始（`start(trigger)`）が試みられる
Then run開始は`StartOutcome.Busy`で拒否され（`RUN` tokenは`AUTHORING`と同一admission
domainを奪えない）、新しいrunは開始されない
And 逆にrun operationがactiveな間にstrategy選択を呼んでも、書込は開始されずstore呼出も
発生しない
And 書込が全終端（commit成功・非commit・storage失敗・cancel）で解放した後にのみ、次の
書込と次のrun開始が可能になる

### Scenario: run中にstrategyを変えたい場合は中断してから変更する

Given runがactiveで提案が表示されている
When ユーザーがstrategyを変更したい
Then 実行面の中断（提案があれば破棄確認）→ hub → 材料 → T-05という1規則の導線のみが
存在し、確認なしの破棄+再startは発生しない
And 中断は既存のzero-write中断契約（pre-checkpoint cancellation、語彙はD-13。run面
語彙の全面適用は#369）どおりである

### Scenario: run終端後のT-05はoperation不在として書込可能に戻る

Given runが正常完了（`Applied`）・`NoChanges`・typed failure（`CandidateResolutionFailed`
等）・`Stale`・`Cancelled`のいずれかの終端を表示しており、operationは終了している
（lease解放済み。終端stateは表示上残り得る）
When ユーザーがrun面を離れてhub → 材料 → T-05を開き、strategy行を選択する
Then operation activeの誤判定は発生せず、pickerは有効で選択の検証付き書込が完了する
（表示stateではなくoperation lifetimeを正本とする。plan「Design」参照）

### Scenario: 書込single-flightと全終端解除が維持される

Given T-05で1件のstrategy書込がin-flightである
When 別のstrategy行が選択される
Then 2本目の選択は`RefusedWriteBusy`の開始outcomeで拒否され、store呼出は開始されない
（arbiterが`Idle`のときのみ`Idle → Writing`と`AUTHORING`取得を原子に行う。
single-flight契約継続。typedなretry案内は他authoring占有と共通の文言）
And 書込のcommit成功・非commit・storage失敗・coroutine cancelのすべての終端でarbiterが
`Idle`へ戻り、`AUTHORING` tokenが解放され、次の書込とrun開始が可能になる
And restart状態は存在しないため、restart完了待ちの解除終端は発生しない

### Scenario: exchange競合操作の契約はstrategy関係を除き維持される

Given 実行面でexchange導線（idle entry / run-in entry）が動作している
When import attempt・CTA continuation・選択凍結・破棄・Backの各操作を行う
Then idle start rowのattempt中freeze（spec 328/205）、選択面のexchange中凍結
（spec 331）、CTA single-flight・attempt anchor・owning runId再照合（spec 328）は
本Issue適用前と同一の観測結果である
And strategy関係のexchange gate（picker frozen理由・`IMPORT_STRATEGY_BUSY`/
`CTA_STRATEGY_BUSY`・arbiter busy中のimport/CTA拒否）は削除される。strategy書込と
CTA continuationの重なりは、CTAの`start()`が`Busy`（書込が`AUTHORING`保持中）または
書込のtyped拒否（run active中）に帰着し、どちらもtypedに再試行可能である
And 実行面上にstrategy pickerが存在しないため、「pickerによるrun差し替え」の競合経路と
それを防ぐfreezeは原理的に存在しなくなる

### Scenario: fail-closed表示とabsent時のdefault表示がT-05でも維持される

Given selection storeの読取が失敗している、または選択がabsentである
When T-05が描画される
Then 読取失敗時はすべての行が未選択表示（fail-closed、composerと一致）、absent時は
bundle defaultがeffective選択として表示される（spec 182/spec 283現行契約の維持）

### Scenario: 旧特例を固定するoracleがobsolete理由つきで更新される

Given 本Issueの実装PRが作成されている
When 旧挙動（run中commit → 確認なしdismiss+再start）を固定するtest
（`StrategyWriteArbiterTest`のrestart抑止/reservation oracle、exchange側
strategy gate truth table test、run面picker freeze oracle）のdiffを確認する
Then 削除・更新された各oracleについて、なぜobsoleteか（E-7/監査D-3/D-4の解消による
特例廃止と、pickerのrun面撤去によるfreeze対象の消失）がPR本文に記録されている

## Failure behavior

| Condition | Observable outcome |
|---|---|
| run/recovery operation active中にstrategy選択が呼ばれる（UI・防御経路いずれも） | arbiterは`RefusedRunOrRecoveryActive`を返しtyped non-write（store呼出なし）。selection store不変。UIはdisabled＋理由（中断してから変更）を通知 |
| 他の`AUTHORING` token（category authoring等）がadmission domainを占有中にstrategy選択が呼ばれる | arbiterは`RefusedAuthoringBusy`を返しtyped non-write（store呼出なし）。T-05はretry文言をlive regionで通知（frozen理由とは区別） |
| strategy書込中に別のstrategy選択が呼ばれる | arbiterは`RefusedWriteBusy`を返しtyped non-write（store呼出なし）。arbiter終端後に再試行可能 |
| strategy書込中にrun開始が試みられる | `start(trigger)`は`StartOutcome.Busy`で拒否され、runは開始しない。書込は影響を受けず継続する |
| 書込中のstorage失敗 | 既存選択は保持され（spec 182 AC-3b継続）、`AUTHORING` tokenは解放される。restartが存在しないため、失敗後にrunが置き換わる経路はない |
| 書込中にCTA continuation（`start(trigger, intent)`/`attachIntent`前提のseam）が実行される | run seamはleaseを経由するため`Busy`/typed拒否に帰着し、処理中状態は解除・再試行可能になる（既存holder契約の継続） |
| T-05表示中にnavigationで離脱し書込coroutineがcancelされる | storeのatomic publication契約により選択はcommit完了分のみ有効。`AUTHORING` tokenは解放され、中途半端な書込・run置き換えは発生しない |
| selection store破損/unsupported/newer | 従来どおりcomposer `NotReady`（fail-closed）＋T-05は非選択表示。本Issueで変化しない |
| catalog外のstrategy IDの直接書込 | 従来どおり`UnsupportedStrategy`拒否（store呼出は検証付きcommand経由のまま） |

## Stale state / concurrency

- strategy変更はstale（capture revision）を発生させない（spec 182現行契約の維持）。
  選択は同一snapshotに対する別planを生むのみであり、run操作中は書込できないため、
  「run中strategy変更」というstaleとは異なるrun無効化経路が消える。
- 書込とrun admissionの相互排他は、`OrganizationOperationLease`の単一active token
  （単一lock内の`tryAcquire`）で直列化される。`AUTHORING` tokenは書込開始のMain-confined
  遷移点で`Idle → Writing`と原子に取得され、全終端（`NonCancellable`の`finally`相当）で
  解放される。したがって「gate通過後〜publication完了前にrunが開始する」競合窓は
  構造的に存在しない（Main-confinedなstate読取だけをgateにしない。category
  authoringと同一のadmission原則）。
- 拒否outcomeの分類（`RefusedRunOrRecoveryActive`か`RefusedAuthoringBusy`か）は、
  token取得失敗後の同一Main処理内でoperation lifetime projectionを読んで行う。
  分類は表示・oracleのための情報であり、排他の正本はtoken取得自体である。分類の
  取りこぼし（極小の競合窓でrun/recovery拒否をauthoring占有として返す）が生じても、
  どちらのoutcomeでもstore呼出なし・retry可能であり安全性は変わらない。
- T-05のdisabled affordance（run/recovery用frozen表示）はoperation lifetimeの
  projectionから導出される。他authoring占有・single-flightはprojectionの対象外のため
  行は有効表示のまま、typed outcomeに対応するretry文言をlive regionで通知する
  （「同じtruthからの導出」ではなく「役割分担」: 排他はlease、run/recovery表示は
  projection、それ以外の競合表示はtyped outcome）。affordanceの遅延・取りこぼしは
  書込の安全性に影響しない。
- 選択snapshotのcomposition cut（read-after-validate、A/B再読取、
  `NotReady(InconsistentPolicyRead)`）は変更しない（spec 182 Selection contract）。

## Data and state

- **読む**: selection store（`organizer_strategy_selection/selection-v1`、読取契約不変）、
  active bundleのruntime-supported catalog（表示のみ。composerが改めて検証する）、
  run coordinatorのoperation lifetime projection（T-05 affordance用。書込みではない）。
- **書く**: selection storeへの検証付き書込のみ。新規の永続化・preference・diagnostics
  eventはない。run/journal eventの新設廃止はないが、旧特例のdismiss+再startが
  `USER_CANCELLED`+新規run起動のevent対を生んでいた箇所が、特例廃止により発生しなく
  なる（event schemaの変更ではない）。
- **Identity**: 変更なし。`StrategyId`・selection snapshot identity・provenance参加
  （spec 182 AC-6）は不変。
- **Migration / backup / restore / rollback**: persistent state変更なし。schema変更なし。
  Launcher layout DB / `favorites` への接触なし（ホームレイアウト安全規約の適用対象外）。
  PR revertで旧UI構成（run面picker＋restart特例）へ戻り、selection storeに不整合は
  残らない。downgrade: 旧binaryは自身のUI（run面picker＋restart挙動）で動作し、store
  format互換はspec 182のdowngrade 3-case modelが引き続き所有する。
- **利用者の既存設定**: 選択済みstrategyの値は本変更の前後で不変であり、再選択を
  要求しない。

## Permissions, privacy, and security

- None — 新規permission、外部送信、sensitive dataの扱い追加はない。
- diagnostics: strategy identityのversion identifier許容（spec 182 Diagnostics）は不変。
  新規field・eventはない。

## Accessibility and localization

- spec 283のaccessibility契約（AC-1〜AC-6: persistent visual indicator、semanticsとの
  単一truth、TalkBack 1論理ノード読み上げ、色非依存、`selectableGroup`、keyboard/
  Switch Access、200% font scale、fail-closed/default-as-effective表示）がT-05で
  変わらず満たされること。
- operation active中のfrozen状態は、行のdisabledに加え理由文言をlive regionで読み上げる
  （既存`StrategyPickerFreezeInstrumentationTest`が確立したfrozen affordance patternの
  継続）。理由文言はD-03の「中断してから変更する」規則を説明するものとし、新規stringは
  Android resource由来でEN（`values/`）とja（`values-ja/`）の双方に供給する
  （spec 161の対訳規約に従う）。他authoring占有・single-flight拒否のtyped outcomeに対
  応するretry文言も同様に新規string（EN/ja）で供給し、frozen理由とは文言上区別する
  （`RefusedAuthoringBusy`/`RefusedWriteBusy`時にlive regionで通知し、選択が
  黙ってdropしないことを読み上げ可能にする）。
- picker撤去により実行面から消失する表示について、TalkBack到達性の低下はない
  （strategyはhub → 材料 → T-05の1 hop+1 tapで到達できる。D-01/D-03の構造そのもの）。
- 削除により未使用になるstring resource（exchange系picker frozen理由・strategy busy
  案内のうち参照が消えるもの）は実装PRのreference grepで確定し、同じPRで
  `values/`/`values-ja/`双方から削除する（孤立user-visible参照の排除。spec 123収束、
  #367 MAT-AC-09と同一pattern）。

## Acceptance criteria

- [ ] **AC-1**: strategy pickerが材料面T-05（hub材料セクションのentryから開く専用面）に
      のみ存在し、manual organization実行面（全run状態・Idle・両entry）にpicker行、
      strategy section表題、読み取り専用strategy表示が存在しない。（Issue受入1）
- [ ] **AC-2**: run中にstrategy選択をcommitする経路が存在しない。UI affordance
      （disabled＋理由。operation lifetimeのprojectionから導出）と構造gate
      （`RefusedRunOrRecoveryActive`のtyped開始outcome、store呼出なし）の両方で保証され、
      run中の選択操作はselection storeを変化させない。（Issue受入2）
- [ ] **AC-3**: operation不在時のstrategy選択は従来どおり検証付き書込（書込時catalog検証、
      atomic publication、failure時既存選択保持。spec 182 AC-3b/AC-7）であり、選択は
      次回runのcomposition（composer read）に反映される。既存のselection store unit
      test・composer/provenance contract testが無編集でgreenである。（Issue受入3）
- [ ] **AC-4**: 旧挙動（run dismiss+再start）を固定するtest oracleが削除または更新され、
      旧oracleがなぜobsoleteか（E-7の解消、監査D-3/D-4のgate隙間の解消、arbiterの
      restart状態廃止）がPRに記録されている。`StrategyWriteArbiter`からrestart状態と
      seamが削除され、strategy commitから`dismiss()`+`start(trigger)`への呼出経路が
      source上に存在しない。（Issue受入4）
- [ ] **AC-5**: spec 283のaccessibility受入基準（AC-1〜AC-6相当: visual indicator単一
      truth、TalkBack 1論理ノード、色非依存、selectableGroup、200% font scale、
      fail-closed/default-as-effective）がT-05で満たされる。（Issue受入5）
- [ ] **AC-6**: 書込single-flight（write-vs-write）と検証付き書込の失敗時挙動が維持され、
      arbiterが全終端で`Idle`へ復帰する。2本目の書込がin-flight中に`RefusedWriteBusy`
      のtyped outcomeでstore呼出を開始しないことをunit testで固定する。
- [ ] **AC-7**: exchange導線のstrategy以外の契約が回帰していない（idle start row
      freeze、選択面凍結、CTA single-flight、attempt anchor等の既存testが無編集で
      green）。実装PRにspec 182・283改訂、spec 328のstrategy固有条項の狭い改訂
      （Contract notes 2の境界）、処分文書§3.9/§3.14の境界更新が含まれている。
- [ ] **AC-8**: operation active中のfrozen理由文言と、他authoring占有・single-flight
      拒否のretry文言が新規resource（EN/ja）で供給され、いずれもlive regionで通知される
      （frozen理由とretry文言は区別される）。未使用化したstring resourceが`values/`/
      `values-ja/`双方から削除されている。
- [ ] **AC-9**: strategy書込とrun admissionの相互排他がlease基準でtestされる。
      (a) publication前で停止中の書込が存在するとき`start(trigger)`は`Busy`で
      run開始しない、(b) `RUN` token保持中のstrategy選択は`RefusedRunOrRecoveryActive`
      を返しstore呼出を開始しない、(b2) tokenが占有済みでrun/recovery projectionが
      falseの状態（他の`AUTHORING`保持を模擬）のstrategy選択は`RefusedAuthoringBusy`
      を返しstore呼出を開始しない、(c) 書込のcommit成功・非commit・storage失敗・
      coroutine cancelの全終端で`AUTHORING` tokenが解放され、次のrun開始と次の書込が
      可能になる。あわせて、`Applied`/`NoChanges`/typed failure/`Stale`/`Cancelled`の
      各終端後（operation終了・lease解放済み）にT-05の書込が可能になることをunit testで
      固定する（表示stateとoperation lifetimeの分離の回帰防止）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | instrumentation: T-05 destinationでpicker行表示・選択状態assert（既存`StrategyPickerInstrumentationTest`をT-05 hostへ再host）。実行面の否定的観測（`manual-organization-strategy-picker` testTag・section表題・radio行の不在。全run状態スナップショットまたはIdle/Selecting/Preview代表状態）。hub材料セクション→T-05 navigation assert（#366のhub instrumentation testへ追加） |
| AC-2 | unit: `RUN` token保持中の選択が`RefusedRunOrRecoveryActive`を返しstore呼出非開始であること（fake storeで表明。AC-9(b)と同一oracle）。instrumentation: 実行面上の選択UI不在（AC-1と同一否定的観測）＋operation active模擬状態でのT-05 disabled/理由表示 |
| AC-3 | 既存`LayoutStrategySelectionStoreTest`・composer/provenance contract testの無編集green + `StrategyPickerInstrumentationTest`の書込経由公開assert（T-05 rehost後） |
| AC-4 | `StrategyWriteArbiterTest`改訂diff（restart oracle削除・single-flight/lease gate/outcome oracle新設）+ `ExchangeFlowStateHolderTest`/`ExchangeImportSuccessInstrumentationTest`のstrategy wiring test削除diff + obsolete理由のPR本文記録 + source grep（`restartRun`/`RESTART_RESERVED`/`restartNeeded`等の残存0件） |
| AC-5 | 既存picker a11y oracle（selectableGroup、parent row単一truth、200% font scale、fail-closed/default-as-effective）をT-05 hostで再実行 + light/dark screenshot evidence（spec 283 AC-7 pattern） |
| AC-6 | unit: single-flight table-driven test（in-flight中の2本目が`RefusedWriteBusy`で不開始・終端別の`Idle`復帰。spec 328 (d4)/(i) patternからrestart終端を除いて継承） |
| AC-7 | 既存`ExchangeFlowStateHolderTest`・`ExchangeImportSuccessInstrumentationTest`のstrategy非依存testが無編集でgreen + specs 182/283/328と処分文書のdiff review（改訂箇所がScope節とContract notes 2の境界と一致） |
| AC-8 | instrumentation: frozen理由行・retry文言のlive region・内容assert（`StrategyPickerFreezeInstrumentationTest` patternのT-05移設。文言の区別を含む）+ string diff（EN/ja）+ 削除stringのreference grep（0件） |
| AC-9 | unit: (a) 書込をpublication前で停止 → `coordinator.start(trigger)` が`Busy`（実際の`ManualOrganizationRun`＋実`OrganizationOperationLease`を使用）、(b) `RUN` token保持中の選択が`RefusedRunOrRecoveryActive`＋store非呼出、(b2) gate占有＋projection false（他`AUTHORING`保持を模擬。fake gate/projection）の選択が`RefusedAuthoringBusy`＋store非呼出、(c) commit/非commit/storage失敗/cancel各終端後に`AUTHORING`取得（または`RUN`開始）が成功する table-driven test、(d) 上記終端state群の後に書込可能であることの回帰test |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane、
CI `final-status` green（本Issueは `risk: layout-data`/`risk: migration` を付けない。
high-risk evidence gateの対象外）。

## Contract notes（2026-09-19 owner指示により受入済み）

1. **「材料を見に行く導線」の解釈**: Issue本文scopeの「run中は読み取り専用表示もrun面には
   置かない（材料を見に行く導線のみ）」について、本specは「実行面にstrategy UIを置かず、
   strategyの確認・変更はhub → 材料 → T-05のnavigationで行う（現行run面は離脱時に
   dismissされるため、run active中にT-05へ直接遷移する導線は新設しない。run中の材料変更は
   TO-BE §6.3どおり中断→hub→材料の1規則）」と解釈する。実行面（Idle状態含む）へ
   strategy導線rowを新設する解釈がowner reviewで選ばれた場合は、本specのT-05到達導線と
   planの該当箇所を修正する。TO-BE §7.1の「run中は読み取り専用表示」はT-05側の表示状態
   （operation active時に非commit表示）として満たす。
2. **spec 328の改訂ownership境界（前reviewで指摘された曖昧さの一意化。option (A)を採用）**:
   spec 328はPR #353で実装済み（Issue #328本体はdevice evidence passが残りOPEN）であり、
   処分文書§3.14の「未実装」「実装が無いため既存oracleなし」は現実と不一致である。
   本specは#368に**strategy固有条項のみ**の狭い改訂ownershipを委譲する:
   pickerのrun面撤去とrestart廃止により無効化される条項（run内entry picker freeze、
   idle picker continuation中無効化、commit時gate、exchange側書込開始gate、arbiter
   restart状態機械、`IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`、AC-3/AC-5内のstrategy
   oracle項目）をlease基準の新契約へ置換し、意図的な正本↔実装不一致の期間を作らない。
   freeze再設計・durable契約（spec 328 revision 2本体）は処分どおり#374が所有し続ける。
   処分文書§3.9/§3.14は同一PRで境界記述と実装状況の事実修正を行い、#374へは同一境界を
   PR作成時にコメントで通知する。この境界づけ（option A）をowner reviewで確認する。
3. **T-05を専用destinationとする解釈**: TO-BE §5.1はT-05を「ユーザーに区別して見せる面」と
   して列挙し、#366実装（merge済み）は材料セクションをT-02/T-03/T-04への
   `NavigationActionPreference`＋usage rows構成とする（T-05は本Issue）。本specはT-05を
   既存材料と同型の専用destination（新route、radio group 8行を収める）とする。hub材料
   セクションへのinline埋め込みを選ぶ場合は、radio group a11y契約（selectableGroup、
   非仮想化）とhub list構成の両立を実装が証明する必要がある。

## Dependencies

- **前提（merge済み。baseline `ca9c171e91c2` で確認）**: #365（正本改訂）、
  #366（hub shell。`OrganizerHubPreferences.kt`・材料セクション・`HomeScreenOrganizer`
  routeが実在）、#367（材料集約。設定側organizer row廃止済み）。
- **前提（実装済み・accepted）**: spec 182 implemented・spec 283 accepted/implemented
  affordance・spec 328 accepted＋PR #353で実装済み（strategy条項の改訂はContract
  notes 2）・ADR-0012・organizer-to-be-ux.md accepted（PR #364）・処分文書accepted
  （PR #378）。
- **後続**: #369（run面統合。Depends on #368）、#374（spec 328 rev.2・freeze再設計。
  Contract notes 2の境界を継承）、#377（残oracle清掃。Depends on #368含む）。

## Open questions

実装開始前に解消が必須な問いはない（Contract notes 1〜3は2026-09-19のowner指示により
受入済み。note 2はoption (A)で一意化済み）。非blocking事項:

1. T-05のroute名・destination名・新規stringの最終文言（D-03語彙に沿うことのみ拘束）は
   実装PRで確定する。
2. 削除により未使用化するstringの最終集合（Scope/Accessibility節の4候補以外に発生するか）
   は実装PRのreference grepで確定する。
3. 実行面の否定的観測testで網羅するrun状態の集合（全20状態か代表状態か）はplanの
   test構成で確定する。

## Change history

- 2026-09-19: Draft created for #368（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `3076bdae7e`）、処分文書draft（PR #378 §3.9/§3.14）、
  #366/#367 spec/plan draft（`bf00f96175`/`a50f074ac2`）、現行実装調査を入力に作成。
- 2026-09-19: Re-entry revision（review「Changes requested」3点対応）。
  baselineを `ca9c171e91c2`（origin/main、#365/#366/#367 merge後）へ更新し、接続面
  （hub材料セクション・route・file名・string・test位置）を実名で再確認。
  **(1) 書込gateをlease基準へ変更（高）**: 「store呼出前のMain上run state確認」では
  gate通過後〜publication完了前にrun admissionが割り込むため、strategy書込を
  `OrganizationOperationLease.Kind.AUTHORING`でcategory authoringと同一admission
  domainへ参加させ、書込開始〜全終端までtoken保持とすることでrun/recovery/書込の
  相互排他を構造化（AC-9新設、排他oracle 3点を必須化）。`start()`先行時の`Busy`と
  書込先行時のtyped拒否の双方向を規定。
  **(2) run active定義をoperation lifetimeへ変更（中）**: `state !is Idle/Cancelled`
  述語は終端state（`Applied`/`NoChanges`/`Stale`等）表示中にoperation不在を誤判定
  するため、「activeOperation/recoveryLeaseの存在（=admission domain占有）」を正本と
  再定義し、表示state列挙から分離。終端後のT-05書込可能をscenario・oracle化。
  **(3) spec 328 ownership境界の一意化（中）**: 処分文書の「spec 328未実装」は
  PR #353 merge済みの現実と不一致のため、Contract notes 2をoption (A)（#368が
  strategy固有条項のみの狭い改訂を所有、処分文書§3.9/§3.14を同一PRで更新、#374は
  rev.2/freeze再設計を継続所有）へ一意化し、意図的な正本↔実装不一致の期間を廃止。
  exchange側strategy gateの削除をScopeへ明記。
- 2026-09-19: Revision 2（re-entry revisionへのreview「Changes requested」1点対応）。
  **typed開始outcomeの契約一意化（中）**: 「typed non-write」を検証可能にするため、
  arbiterの書込開始結果を`Started`/`RefusedRunOrRecoveryActive`/`RefusedAuthoringBusy`/
  `RefusedWriteBusy`のtyped outcomeとしてcallerへ返す契約を明示（plan Change setへ
  `onStrategySelected`返却型変更を追加）。あわせて「operation lifetime＝admission
  domain占有と同値」「affordanceと構造gateが同じtruth」の過剰な契約を修正:
  projectionはrun/recoveryのoccupancyを示し、domainは他`AUTHORING` tokenによっても
  占有され得るため、他authoring占有・single-flight時はtyped outcomeに対応するretry文言
  （frozen理由と区別される新規string）で案内する役割分担へ変更。他AUTHORING競合の
  scenario・`RefusedAuthoringBusy` oracle（AC-9(b2)）を追加。
- 2026-09-19: 実装レビュー対応でtwo-pane前提を実測に合わせ修正。production expanded
  settings（`Preferences.kt` TwoPane）はsecond paneが単一NavHostのためdestination同時
  composeは発生せず、run面の`onDispose`→`dismiss()`によりT-05 compose時点でoperationは
  終了する（実測・evidence README記録）。scenario「run/recovery active中…」を防御契約と
  して明記し、plan Risk 4 / Verification / Unverified areasを同期。
- 2026-09-19: 実装（#368実装PR。`StrategyWriteArbiter`簡素化＋`AUTHORING` admission＋typed開始outcome、
  `ManualOrganizationRun.operationActive`新設、T-05 destination新設＋hub entry＋run面picker撤去、
  exchange strategy gate除去、本spec/planのstatus/history更新、specs 182/283/328改訂、処分文書
  §3.9/§3.14境界更新）。merge後のdocs PRで`implemented`へ遷移する。
- 2026-09-19: Accepted（owner指示: reviewクリア後に実装へ進行。
  re-review [Approve相当](https://github.com/nunu1733/NunuLauncher/issues/368#issuecomment-5742960028)
  @ head `5d5b61ee4e`。statusを`accepted`へ更新。Contract notes 1〜3を受入）。
  実装PRでstatusを`implemented`へ更新する。

[1]: https://github.com/nunu1733/NunuLauncher/issues/368
[2]: https://github.com/nunu1733/NunuLauncher/issues/366
[3]: https://github.com/nunu1733/NunuLauncher/issues/367
[4]: https://github.com/nunu1733/NunuLauncher/pull/378
[5]: https://github.com/nunu1733/NunuLauncher/issues/365
