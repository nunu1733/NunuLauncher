---
issue: "#371"
status: draft
requirements: [FR-013, D-010]
risk: []
updated: 2026-09-19
---

# Usage Access要求をjust-in-time化する（初回signal読み取り時点での文脈付き任意要求）

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-07、§6.4、§7.1 JIT行、§7.3「なぜ今」の説明基準、§7.2「signalの保存なし・bucketのみ外部送出、
> 権限なしは失敗でない」の維持規定）および accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§3.10、§4.1 supersession map「spec 203 U-2常設rowのみ → 常設row＋JIT要求」、
> §5 更新順序 #5、§7.3 compatibility matrixのJIT行）。
> 本specは[Issue #371][1]の成果物である。statusが `draft` の間はimplementation-readyではない。

## Problem

Usage Accessの要求導線は設定面の常設rowのみである（spec 203 U-2 draft、現行実装は
`HomeScreenPreferences.kt` のPersonalization group内の行。#367適用後はhub T-06の
使用状況materialが唯一のインスタンスになる）。AS-IS監査のfinding F-06/E-1のとおり、
要求タイミング（設定閲覧時点）と利用タイミング（整理runのcomposition / AI依頼生成時の
signal読み取り）が最遠であり、常設rowのrationale文言は「なぜ必要か」を説明しても
「なぜ**今**」を説明できない。TO-BE §7.3はすべてのpermission要求に
(1)何のために使うか (2)なぜこの時点で聞くのか (3)断った場合に何ができるか/できなくなるか
を1文で説明することを要求するが、常設rowのみの構成ではこの基準を満たす要求時点が存在しない。

## Outcome

Usage Access未付与の状態で、そのprocessで**最初にsignalを読むcomposition**
（整理runのcomposition、またはAI依頼生成のcomposition）が実行されようとする時点で、
文脈付きの任意要求（JIT要求）が**1回だけ**表示される。要求文言は§7.3の3要素を1文で満たす。
system設定 (`ACTION_USAGE_ACCESS_SETTINGS`) へ遷移して付与して戻れば、その試行の
signal読み取りは付与済みの状態で行われ、断れば（または設定に行かずに閉じれば）
system usage sectionが `Unavailable` のままrun・依頼生成が続行する（現行propertyの維持）。
材料面T-06の常設row（状態表示＋system設定への遷移＋再付与）は現行どおり残り、
JIT要求以降の状態管理の正の導線であり続ける。spec 203はU-2の改訂
（常設row＋初回signal読み取り時のJIT要求1回）で amendment される。

## Scope

- 初回signal読み取り時点でのJIT要求UI（compose上のdialog。trigger条件と1回限りの規則は
  Behavior scenariosで定義）。
- 要求文言の要件: §7.3の3要素（用途/時点理由/拒否時の影響）を1文で満たし、
  「任意（許可しなくてもOrganizerの全機能が利用可能）」「local-only」であることを
  偽りなく示すこと。
- system設定への遷移と復帰時の状態再取得（現行resume契約＝`ON_RESUME`でのapp-op再読取の再利用）。
  復帰後に元の操作（run開始 / 依頼生成）を続行すること。
- 拒否時の振る舞い: 該当試行のsignal読み取りは未付与のまま行われ、system usage sectionは
  `Unavailable`、launcher-origin sectionは保持、run・依頼生成は続行する（failではない）。
- spec 203の改訂（同じPRで実施）: U-2の文言（常設row＋JIT要求1回。同一run内再促しなし規則は維持）、
  Permission and fallback behavior表の「opt-in (初回)」行更新とJIT要求行の追加、
  JIT要求の受入条件（AC）追加。signal snapshot・provenance契約（AC-11/AC-12/AC-13/U-4）は不変。
- 入口表記の追記（契約不変）: JIT要求のtrigger時点が「初回signal読み取り」である旨のspec 203側規定。
  先行spec draftsと同様、#366/#367が所有する面の契約は触れない。

## Non-goals

- 権限のapp内付与（system設定経由のみ、現行どおり）。新しいpermission・新しいapp-opの追加。
- signal収集・bucket化・export開示契約の変更（spec 203/204/205のsnapshot・tier・送信前確認契約は不変）。
- `usageAccess=UNAVAILABLE`（許可済みだがquery失敗）へのJIT要求（要求すべき権限が存在しない。
  現行どおりsection Unavailableで継続）。
- composer・snapshot契約の変更: composition per attemptの1回読み、snapshotのephemeral性、
  personalization由来の`NotReady`禁止、provenance参加形式（U-4）は一切変更しない。
  JIT要求はsignalを読む**UI上のtrigger点**に置かれるgateであり、composer seamの内部には入らない。
- 同一run内・同一process内での再促し（「騒がせない」規則の維持・強化）。
- 「JIT要求を表示した」事実の永続化（persistent state変更なし。disposition §7.3
  「要求stateはprocess-local（再訪時は付与状態で判断）」どおり）。
- T-06常設row自体の変更（状態表示・再付与・`ON_RESUME`再読取は現行契約どおり。
  面の配置は#366/#367が所有）。
- run面統合（#369）、AI相談統合（#372）、strategy特例廃止（#368）の契約の先取り。
  JIT要求のtriggerは「signalを読む操作」という振る舞いで定義され、特定の面IDに結合しない。
- diagnostics / run journalへの新規event追加（本specでは要求しない）。

## Domain language

- **JIT要求 (just-in-time usage access request)**: 最初にsignalを読む時点で提示される、
  文脈付きの任意のpermission要求。TO-BE §7.3の説明基準を満たす文言を持ち、
  断っても整理が続行する。常設row（T-06）の存在を置換するものではなく補完するものである。
  語彙の正本はorganizer-to-be-ux.md D-07/§7.3であり、`CONTEXT.md` への用語追加は
  [Issue #365][2]が所有するため本specでは複製しない。
- **初回signal読み取り (first signal read of a process)**: 当該process内で
  `PersonalizationSignalSnapshotSource.read()` が初めて実行されようとする時点。
  compositionはrun開始（`Trigger` を問わない）と依頼生成（idle / run-in両entry）の
  いずれの経路でも同一のcanonical seam（`OrganizationInputComposer`）で行われる（現行実装）。

## Behavior scenarios

### Scenario: 未付与の初回run compositionでJIT要求が1回だけ出る

Given Usage Accessが未付与で、このprocessで一度もsignal読み取りcompositionが行われていない
When ユーザーが整理runを開始する（開始行の操作。`Trigger` は `MANUAL_FULL` 等を問わない）
Then compositionの実行前にJIT要求が表示される（run開始は要求の解決まで遅延する）
And 要求文言は§7.3の3要素（何のためか/なぜ今か/断ったらどうなるか）を1文で満たし、
任意であることが示されている
And 要求の提示は1回限りであり、表示の事実は永続化されない

### Scenario: 依頼生成でも同一のtrigger規則が働く

Given Usage Accessが未付与で、このprocessで一度もsignal読み取りcompositionが行われていない
（idle相談のpre-run request flowではrun admissionなしで依頼生成が可能であるため、
依頼生成がprocess初回のsignal読み取りになり得る）
When ユーザーがAI依頼の生成を要求する（tier選択後の生成操作）
Then compositionの実行前にJIT要求が表示される
And activityなexport sessionが存在する場合はsession置換確認（spec 205 AC-13）が先に解決され、
その後にJIT要求が表示される
And 置換確認が不要な場合（active sessionなし）はJIT要求が最初の応答点になる

### Scenario: 設定へ遷移して付与して戻ると、その試行のsignal読み取りは付与済みで行われる

Given JIT要求が表示されている
When ユーザーが要求面上の遷移操作でsystemのusage access設定 (`ACTION_USAGE_ACCESS_SETTINGS`)
を開き、付与して戻る
Then 復帰時（`ON_RESUME`）に付与状態が再取得される（現行resume契約の再利用）
And 遅延されていた元の操作が続行し、compositionのsignal読み取りは付与済みの状態で行われ、
system usage sectionが構築される
And JIT要求が再度表示されることはない

### Scenario: 設定で付与せずに戻った場合はUnavailableで続行し、再促ししない

Given JIT要求からsystem設定を開いたが、付与せずに戻った
When 復帰時に付与状態が再取得される
Then 遅延されていた元の操作が続行し、signal読み取りは未付与のまま行われる
And system usage sectionは `Unavailable`、launcher-origin sectionは保持される
（spec 203 sparse object契約・AC-13の回帰）
And run・依頼生成は続行し、`NotReady` にはならない（personalization由来の`NotReady`は
契約として存在しない。spec 203 AC-11回帰）
And JIT要求はこのprocess内で再表示されない

### Scenario: 要求を断って即座に続行できる

Given JIT要求が表示されている
When ユーザーが「続行」に相当する操作で要求を閉じる（system Backを含む）
Then 遅延されていた元の操作が即座に続行し、signal読み取りは未付与のまま行われる
And system usage sectionは `Unavailable` でrun・依頼生成は続行する（前scenarioと同一の
現行property。断ったこと自体は失敗として扱われない）
And 書込み・journal event・diagnostics出力は発生しない（zero-writeな表示flow）

### Scenario: 1回限りの規則（同一process内での再促しなし）

Given JIT要求が一度表示された（付与の成否を問わない）
When 同一process内で再度整理runを開始する、または依頼生成を行う
Then JIT要求は表示されず、操作は即座に実行される
And 同一run内はもちろん、runを跨いでも再促ししない（現行「同一runでは再促しない」規則の
上位集合として維持）
And 未付与のままの再付与はT-06常設rowの導線で行う

### Scenario: 初回trigger時に既に付与済みだった場合は要求が出ない

Given Usage Accessが付与済みで、このprocessで一度もsignal読み取りcompositionが行われていない
When 整理runを開始する、または依頼生成を行う
Then JIT要求は表示されず、操作は即座に実行される
And signal読み取りは付与済みの状態で行われる（現行挙動どおり）
And その後に同一process内で権限が取り消された場合も、JIT要求は表示されない
（JIT要求は「最初にsignalを読む時点」に結合される。取り消し後の再付与はT-06常設rowの
導線で行い、次回compositionからは `NOT_GRANTED` のfallbackへ戻る。spec 203
「許可後のrevoke」行どおり）

### Scenario: 設定画面が開けない環境でも壊れない

Given JIT要求が表示されているが、`ACTION_USAGE_ACCESS_SETTINGS` を解決できるactivityが
存在しない（端末依存のunsupported case）
When ユーザーが遷移操作を行う
Then crashせず、要求面は閉じないか、閉じた場合は「断って続行」と同等の継続が可能である
And 永続化・書込みは発生しない
（現行常設rowは遷移失敗を明示的に扱っていないため、JIT要求では遷移の失敗を
crashにしないことを最低限要求する。遷移成功時と同一の復帰継続契約が適用される）

### Scenario: process死・中断後の再訪は付与状態で判断される

Given JIT要求の表示中またはsystem設定遷移中にprocess死が発生した
When ユーザーが戻って再度整理runを開始する（新しいprocess）
Then 遅延されていた操作は失われている（pending actionはprocess-local。永続化しない）
And 新しいprocessでは再び「初回signal読み取り」であるため、未付与ならJIT要求が表示される
（disposition §7.3「要求stateはprocess-local（再訪時は付与状態で判断）」）
And 付与済みなら表示されない

### Scenario: onboarding経由の初回run compositionでも同一規則が働く

Given fresh install直後でUsage Accessが未付与である
When onboarding提案の「確認」でrun admissionが発生する（`Trigger.ONBOARDING_PROPOSAL`。
T-07前置きは省略される。D-16継続）
Then runのcomposition実行前にJIT要求が表示され、解決後にrunが続行する
（D-07は「初回run composition」をtriggerと定義し、triggerを限定していない。
onboarding momentでの表示適否はOpen questions参照）
And onboarding提案自体のoutcome契約（defer/skip・再表示規則）は変更されない

### Scenario: T-06常設rowは現行契約のままである

Given 本Issueの変更が適用されている
When T-06の使用状況materialで付与状態を観察し、system設定へ遷移して戻る
Then 状態表示・遷移・`ON_RESUME`での再読取（spec 203 U-2）は本Issue適用前と同一である
And JIT要求の追加により常設rowの契約・表示・操作が変化しない
And hub材料面（#366/#367）からT-06へ到達できる導線は不変である

## Data and state

- **読む**: `UsageAccess.isGranted()`（app-op `OPSTR_GET_USAGE_STATS` ベースの既存共有predicate、
  spec 203実装）。snapshot自体はcomposerの既存seamで読まれ、本specはその読み取り内容を変えない。
- **書く**: 何も書かない。JIT要求の「表示済み」stateはprocess内のin-memory latchのみであり、
  persistent store・preference・backup対象には入らない。disposition §7.3のとおり
  downgrade時は「従来の常設rowのみ」に戻る（残留物なし）。
- **Identity**: 新しいidentityを導入しない。snapshot identity（schemaVersion + contentDigest）、
  provenance参加（U-4）は不変。
- **Migration / backup / restore / rollback**: persistent state変更なし。
  Launcher layout DB / `favorites` への接触なしのためホームレイアウト安全規約の適用対象外。
  PR revertでJIT要求が消え、常設rowのみの現行構成へ戻る。
- **遅延される操作**: JIT要求が表示されている間、triggerされた操作（run開始 / 依頼生成の
  composition起点）は解決まで開始されない。pending actionはprocess-localであり、
  process死で失われる（失われても書込み・不整合は発生しない。run/single-flight gateは
  開始前に存在しないため）。操作の再試行はユーザーの再操作による。

## Permissions, privacy, and security

- 新しいpermission・外部送信・sensitive dataの扱い追加は **None**。
  JIT要求は既存のsystem usage access（app-op）への任意の付与導線を、利用時点へ移動させるだけである。
- 要求文言は真実であることを要求する: usage情報が端末内でのみ使用されること（local-only）、
  目的が整理精度の向上であること、許可しなくてもOrganizerの全機能が利用可能であること。
  spec 203のrationale文言要件（Permission and fallback behavior表「opt-in (初回)」行）を
  JIT要求の文言へも適用する（spec 203 amendmentで明文化）。
- 任意性の強制: 要求はdismiss可能であり、断ることが常に等価の継続（section `Unavailable`
  で続行）に接続する。断ったことを示すstateを保存しない（「断ったユーザー」の永続markは作らない）。
- diagnostics: 既存契約（`usageAccessState` のtyped codeとsnapshot identityのみ）を変更しない。
  JIT要求の表示・応答はjournal / diagnostics eventを発生させない。

## Accessibility and localization

- JIT要求のdialogはorganization-run-ux §6の受入基準を適用する: TalkBackで要求の
  name/role/stateと文言全体が読めること、focusがdialogへ移動し解決後に元の面へ復帰すること、
  200% font scaleで操作の到達不能・clippingを生まないこと、状態をcolor-onlyにしないこと、
  keyboard/switch traversalで遷移操作・続行操作の双方に到達できること。
- 任意性（「許可しなくても整理は続く」）はtextとして明示する（視覚強調だけに依存しない）。
- すべての新規user-visible文字列とaccessibility読み上げ文はAndroid resource由来とし、
  EN（`values/`）とja（`values-ja/`）の両方を供給する。複合文はformat resourceで構成し、
  Kotlin側の連結・補間で文を生成しない（spec 123 AC-4/AC-5規約）。ja文言は
  spec 161のLQA規約に従う。
- §7.3の3要素を1文で満たす文言の最終copyは実装PRで確定するが、3要素・任意性・local-onlyの
  要件自体は本specの受入条件である（TO-BE §7.3は文言の要件を契約として固定する）。

## Acceptance criteria

- [ ] **JIT-AC-01**: Usage Access未付与のprocessで最初にsignalを読むcomposition
      （run開始または依頼生成。`Trigger` とentry種別を問わない）が実行されようとする時点で、
      JIT要求がcomposition前に1回だけ表示される。triggerされた操作は要求の解決後に続行する。
      既に付与済みの場合は要求を表示せず操作を即座に実行する。（Issue受入1）
- [ ] **JIT-AC-02**: 要求文言が§7.3の3要素（(1)何のために使うか (2)なぜこの時点で聞くのか
      (3)断った場合に何ができるか/できなくなるか）を1文で満たし、任意性・local-onlyを
      偽りなく示す。EN/ja両resourceが存在する。（Issue受入2）
- [ ] **JIT-AC-03**: system設定への遷移と復帰時の状態再取得（`ON_RESUME`でのapp-op再読取、
      現行resume契約の再利用）が動作し、復帰後に元の操作が続行する。付与されて戻れば
      その試行のsignal読み取りは付与済みで行われる。（Issue Scope）
- [ ] **JIT-AC-04**: 拒否・設定遷移後の未付与復帰・遷移失敗のいずれの場合も、run・依頼生成は
      `NotReady` にならず続行し、system usage sectionは `Unavailable`、launcher-origin
      sectionは保持される（spec 203 AC-11/AC-13回帰。personalization由来の`NotReady`が
      存在しないことの回帰）。（Issue受入3）
- [ ] **JIT-AC-05**: 同一process内でJIT要求は最大1回であり、同一run内での再促しが存在しない
      （現行規則の回帰とその上位集合）。初回trigger時に付与済みだった場合、同一process内の
      後続の権限取消でも要求は表示されない。（Issue受入4）
- [ ] **JIT-AC-06**: T-06常設rowが残り、付与状態の表示・system設定への遷移・`ON_RESUME`
      再読取・再付与が引き続き可能である（spec 203 U-2常設row契約の維持）。
      JIT要求は常設rowの契約を変えない。（Issue受入5）
- [ ] **JIT-AC-07**: spec 203が同じPRでamendmentされている: U-2の改訂（常設row＋初回signal
      読み取り時のJIT要求1回。再促しなし規則の維持）、Permission and fallback behavior表の
      「opt-in (初回)」行更新とJIT要求行の追加、JIT要求の受入条件追加、change history更新。
      snapshot・provenance契約（AC-11/AC-12/AC-13/U-4）への変更がない。（Issue Spec節、
      disposition §3.10/§4.1）
- [ ] **JIT-AC-08**: 新しいpermission・persistent state・diagnostics/journal eventが
      存在しないことがdiff reviewとtestで示されている。JIT要求の表示・応答の全failure path
      （遷移失敗・process死・操作中断）がzero-writeである。（Non-goals機械的保証）
- [ ] **JIT-AC-09**: JIT要求のdialogがorganization-run-ux §6のaccessibility受入基準を満たす
      （TalkBack name/role/state、focus移動と復帰、200% font scale、non-color-only、
      traversal）ことがevidenceで示されている。

## Test oracle

| AC | Evidence |
|---|---|
| JIT-AC-01 | UI instrumentation test: app-opをshell経由で未付与にした状態（`UsageAccessTransitionProbeTest` と同一の`appops set` pattern）でrun開始→JIT要求の表示assert→解決後にcomposition開始（run state遷移）。依頼生成経路も同様。app-op付与済みの否定的観測（要求が出ない） |
| JIT-AC-02 | string diff review（EN/ja、format resource、3要素の文言要件）+ 実装PRでの文言確認記録。単体testでresourceの存在とplaceholder一致を機械確認 |
| JIT-AC-03 | UI instrumentation test: JIT要求から`ACTION_USAGE_ACCESS_SETTINGS`遷移を発火させ、shell `appops set ... allow` 後の`ON_RESUME`再読取と操作続行・snapshot付与済み構築（composer test併用）を確認 |
| JIT-AC-04 | UI instrumentation test: 断って続行→runがpreviewまで進行（`NotReady`不発生）。composer seamの既存unit test（`PersonalizationCompositionTest`等）が無編集でgreen（AC-11/AC-13回帰） |
| JIT-AC-05 | UI instrumentation test: 1回目の応答後に2回目のrun開始・依頼生成で要求が表示されないassert（付与/未付与の両状態）。process-local latchのunit test（latch状態遷移: 未表示→表示済み、付与済み初回trigger→latch消費） |
| JIT-AC-06 | #366/#367のT-06関連instrumentation（toggle↔preference一致、resume再読取）が無編集でgreen + JIT追加diffが常設row契約に触れないことのdiff review |
| JIT-AC-07 | specs/203-usage-implicit-preference-signals/spec.mdのdiff review（U-2・表・AC・change history。snapshot/provenance契約節の無変更） |
| JIT-AC-08 | 実装PR diff review（permission manifest、persistent store、diagnostics eventの無変更）+ failure pathのunit/instrumentation test |
| JIT-AC-09 | Compose semantics assertion + focus restoration test + 200% font scaleでの到達test + emulator evidence（organization-run-ux §6の表に基づく確認記録） |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane、CI `final-status` green。

## Dependencies

- **#367（材料集約、OPEN・spec draft `a50f074ac2`）**: T-06常設管理rowがhub材料面に存在することが
  実装前提である（Issue本文 `Depends on: #367`）。spec執筆は#367のdraft specとの整合で可能
  （現時点でそうしている。#367は「spec 203のU-2本体改訂は#371が所有」と明記している）。
  実装着手は#366→#367のmerge後。
- **#365（正本改訂、OPEN）**: `CONTEXT.md` の語彙（JIT要求・材料）は#365が所有する。
  契約根拠（organizer-to-be-ux.md、disposition）はaccepted済みでmainに存在するため
  spec執筆はblockされないが、実装着手は#365のmerge後（正本先行の原則。#366/#367と同一の判定）。
- **並行**: #368（strategy移設）、#369（run面統合）、#372（AI相談統合・T-07/T-15再配置）。
  JIT要求のtriggerは面IDでなく「signalを読む操作」に結合されるため、#369/#372が
  trigger点の面を動かしても本specの契約は変化しない。#372が依頼生成entryをT-07へ移した場合も
  JIT要求は生成操作に付随し続ける。
- **前提（実装済み・accepted）**: spec 203（accepted/実装済み。U-2・app-op predicate・
  snapshot契約）、spec 205（実装済み。依頼生成の生成順序契約・session置換確認AC-13）、
  spec 204（実装済み。exportのusageSignals projection）、spec 83（composition契約）、
  organization-run-ux §6（accessibility受入基準）、spec 123（UI収束・localization規約）、
  spec 161（ja LQA）。

## Open questions

実装開始前に解消が必須な問いはない。非blocking事項:

1. **onboarding経由の初回runでのJIT要求表示**: D-07は「初回run composition」をtriggerとし
   限定していないため、本specはonboarding経由も含める（上記scenario）。
   onboarding momentでの要求が過剰であるというproduct判断がowner reviewで示された場合は
   該当scenarioとJIT-AC-01を修正する（その場合のonboarding経路の除外は「同一process初回
   triggerの非表示＋latch非消費」の扱いを含めて明文化を要する）。
2. 要求文言の最終copy（ja/EN）は実装PRのLQAで確定する。3要素の要件自体はJIT-AC-02で拘束される。
3. session置換確認（spec 205 AC-13）とJIT要求の両方が必要な場合の提示順序は
   「置換確認→JIT要求」を本specのscenarioどおりとする。逆順がUX上望ましいという判断が
   owner reviewで示された場合は該当scenarioを修正する（どちらの順序もJIT-AC-01の
   「composition前の1回表示」契約には影響しない）。

## Change history

- 2026-09-19: Draft created for #371（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md D-07/§6.4/§7、disposition §3.10/§4.1/§5/§7.3 @ main
  `a2b6aba318`）、先行spec drafts（#366 `bf00f96175`、#367 `a50f074ac2`）、
  現行実装調査（`HomeScreenPreferences.kt` のUsage Access行、`UsageAccess.kt`、
  `OrganizationInputComposer.readPersonalizationSnapshot`、
  `AndroidPersonalizationSignalSnapshotSource`、`ManualOrganizationRun.start`、
  `ExchangeFlowController` / `ExchangeInputAdapter` / `ExchangeFlowUi` の生成経路、
  `OrganizationOnboardingProposal` の開始経路、`UsageAccessTransitionProbeTest`）を
  入力に作成。spec 203の本体改訂は本specの受入条件（JIT-AC-07）であり、
  実装PRで実施される。

[1]: https://github.com/nunu1733/NunuLauncher/issues/371
[2]: https://github.com/nunu1733/NunuLauncher/issues/365
