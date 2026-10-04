---
issue: "#366"
status: implemented
requirements: [FR-004, FR-006]
risk: []
updated: 2026-09-21
---

# Organizer hub（T-01）を設定から開ける恒常的なOrganizer作業領域として新設する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-01, D-02第1段階, T-01, §5.2 primary entry, §8.1, §9, §10, §13-1段階(a), §13-5）。
> 本specは[Issue #366][1]の成果物である。statusが `draft` の間はimplementation-readyではない。

## Problem

Organizerの入口が設定画面の複数箇所に分散している: run入口（manual organization row、
#232でGeneral group先頭へ昇進）、lock/診断/category override/ユーザー定義カテゴリ
（Layout group）、使用状況ヒントの記録toggleとUsage Access行（Personalization group）。
「整理の作業領域」として一箇所から全体を見渡せる恒常面がなく、AS-IS監査の構造的finding
（F-01 run/設定/AIの役割混在、F-02材料分散、F-10再発見断絶）が残っている。#361でaccepted
されたTO-BE decision（D-01）は、Organizerを独立した作業領域（Organizer hub）として新設し、
設定 → Home screen → Organizerをprimary entryとすることを決定した。TO-BE §13-1の段階(a)
として、本Issueではhubのshell（status card第1段階＋開始CTA＋材料・診断への導線）を新設し、
設定側の既存導線は当面維持する（段階移行。材料移動は#367、strategy特例廃止は#368、
run面統合は#369が所有）。

## Outcome

ユーザーが設定 → Home screen → Organizerを開くと、Organizer作業領域（hub、TO-BE T-01）が
開く。hubにはstatus cardの第1段階（spec 271の `durableOrganizerStatus` seamを再利用した
durable statusの表示、「整理を開始」CTA、診断導線）と、既存authoring画面（分類/カテゴリ/
lock/使用状況）への材料導線が置かれる。既存manual organization run flowをhubから開始でき、
runの安全契約（spec 13/52）は一切変わらない。設定側の既存row群は維持され、段階(a)単独で
shippableである。

## Scope

- **hub destinationの新設**: 設定 → Home screenにOrganizer入口rowを追加し、新しい
  preferences navigation destination（T-01）として開く。画面構成は既存Lawnchair
  preference component規約（spec 123収束対象）に従う。
- **status card第1段階**: durable statusの表示（spec 271 seam
  `readDurableOrganizerStatus()` + `readinessState` の再利用。閉域語彙のみ、fail-closed、
  表示のみで復元CTA等の操作は付けない）＋「整理を開始」CTA＋診断導線。
- **整理を開始**: hubのCTAから既存manual organization run面へ到達し、既存の開始行から
  `MANUAL_FULL` triggerのrun flowを開始できる。run面・coordinatorの実装は変更しない。
- **材料導線**: hubから既存authoring面（category override、ユーザー定義カテゴリ、
  placement lock、使用状況ヒント）へ到達できる。使用状況ヒントは設定側と同一の
  preference状態と同一のUsage Access app-op読み取りを操作する共有表現とする
  （単独のdestinationは現行に存在しないため）。設定側のrowは当面維持する。
- **視覚収束**: spec 123 AC-1のinventory evidence文書へhubを追加し、既存Lawnchair
  component/theme tokenでの実装とする。
- **Localization**: 新規user-visible文字列はすべてAndroid resource由来でEN（`values/`）
  とja（`values-ja/`）の両方を供給する。

## Non-goals

- 設定側Organizer row群の移設・廃止（段階(b)、#367が所有）。
- strategy pickerのhub/材料面への移設・run面からの撤去（D-03、#368が所有）。hubに
  方針（T-05）の操作面を置かない。
- run面の表示統合・canonical順序変更・T-07前置き面（D-05/D-06、#369が所有）。hubの
  「整理を開始」はT-07前置きを挟まず既存run面へ到達する（本段階の暫定導線）。
- status cardの拡張のうち、復元CTA（D-15、#376）と最近のrun結果（process内のみであり
  既存run面が所有）をhubへ置かない。placeholderや無効化された操作を見せてcapabilityを
  先取りしない。（#374改訂: 進行中AI依頼行・取り込み済み提案行は本Issue第1段階のnon-goal
  から除外され、#374実装でstatus cardへ追加された — 後述のHUB-AC-03改訂。）
- onboarding提案・hintの接続先変更（D-16表記、#370が所有）。
- run state machine、spec 13/52/205/328/271の契約、diagnostics event、persistent
  store、permission、外部送信の変更。
- 設定検索への新規登録（現行のpreference画面に設定検索indexは存在しないため対象外）。

## Domain language

- **Organizer hub**: 整理の恒常的な作業領域として新設されるTO-BE T-01の面。語彙の正本は
  organizer-to-be-ux.md §10およびD-01である。`CONTEXT.md` への用語追加（Organizer hub /
  材料 / 語彙規約 D-13）は[Issue #365][2]が所有し、本specでは複製しない。
- **status card**: hubのdurable事実と進行中状態の単一閲覧領域（D-02）。本段階では
  durable status表示・開始CTA・診断導線のみを含む。
- **材料**: 分類・ロック・方針・使用状況ヒントの総称（organizer-to-be-ux.md §10）。
  本段階でhubに置くのは分類/カテゴリ/lock/使用状況の導線である（方針は#368以降）。

## Behavior scenarios

### Scenario: 設定からhubが開く

Given 設定 → Home screenを開いている
When Organizer入口rowをタップする
Then hub（T-01）が開き、status card領域（durable status行群＋「整理を開始」CTA＋診断導線）
と材料群（分類/カテゴリ/lock/使用状況）が表示される
And hubのsystem BackはHome screen設定へ戻る（TO-BE §9: hub Back=設定へ）

### Scenario: status cardにdurable statusが閉域語彙で表示される

Given recovery storeにretention内の `VERIFIED` recordがあり、run coordinatorが
`Idle`/`Cancelled`、startup reconciliationが完了している
When hubを開く
Then status cardにspec 271と同一語彙・同一string resourceの「organized and restorable」行が
表示される
And 行にはrecord identifier、revision、digest、item identity、timestampが含まれない
And hubから復元操作は提供されない（復元CTAは#376が所有）

### Scenario: restored/expired と unresolved

Given restore完了後（またはretention失効後）のrecord/tombstone、またはunresolved record
がある
When hubのstatus cardを観察する
Then spec 271と同一の「restored or expired」行、または「unresolved」行と既存の
safe-support（診断を開く）導線が表示される
And 診断導線は既存のorganizer diagnostics destinationへ到達する

### Scenario: never organized では status行を表示しない

Given recovery storeにdurable recordもretained tombstoneもない
When hubを開く
Then durable status行は表示されず、「never organized」を偽装する行も作らない

### Scenario: fail-closed と reconciliation完了後の回復

Given startup reconciliationが未完了（gate `IDLE`/`RECONCILING`）である
When hubのstatus cardを観察する
Then 明示的なchecking行が表示され、「never organized」と視覚的に区別される
And gateがterminal state（`READY`/`FAILED`）に到達すると同じ面上でstatus行が回復する
And 読み取り失敗（store unavailable、run mutex競合等）は行なしにfail-closedし、
発明した状態を表示せず、書込みもjournal eventも発生させない（spec 271と同一契約）

### Scenario: hubから既存run flowを開始する

Given hubを開いている
When 「整理を開始」CTAをタップする
Then 既存のmanual organization run面へ遷移する
And run開始は既存run面の既存開始行のみが発行し、triggerは `MANUAL_FULL` である
And spec 328のimport-attempt freeze、spec 205のentry hosting、run single-flight
（`StartOutcome`）を含む既存のadmission gateはすべて既存run面上でそのまま働く
And hubから直接 `start()` を発行する経路は存在しない（開始authorityの一元化。
1 tap開始はT-07前置き面（#369）で実現する）

### Scenario: run進行中はstatus cardがdurable行を隠す

Given 同一process内でrun coordinatorが進行中（`Idle`/`Cancelled` 以外）である
When hubを表示する
Then status cardはdurable status行とchecking行を表示しない（進行中のprocess-local状態が
優先され、live状態は既存run面が表示する）
And 「整理を開始」CTAで既存run面へ戻ると進行中runの状態表示がそのまま現れる
And run coordinatorが `Idle`/`Cancelled` に戻ると、hubのstatus cardはdurable statusを
再読込して表示する

### Scenario: cold processでhubが最初の面になる

Given exported preference activity経由で、Launcher未起動のfresh processの最初の面として
hubが開く
When status cardのdurable statusを観察する
Then `ManualOrganizationModule` と同一の初期化経路（`LauncherAppState` 構成保証と
共有のidempotentなstartup reconciliation trigger、spec 271 DS-AC-10と同一）により、
Launcherを開かずにterminal gateへ到達し、durable statusが表示される
And 真のload failure/timeoutのときのみfail-closedする

### Scenario: 材料導線と使用状況ヒントの単一の真実

Given hubと設定 → Home screenの両方から使用状況ヒントmaterialに到達できる
When いずれかの面で記録toggleを変更する、またはUsage Access行からsystem設定へ遷移する
Then 変更対象は同一の既存preference（launcher-origin記録toggle）と同一のapp-opであり、
もう一方の面で再表示したとき状態が一致する
And spec 203 U-2の常設row（設定側）とresume時の付与状態再読取の規約は変更されない

### Scenario: 設定側の既存導線が維持される

Given 本Issueの変更が適用されている
When 設定 → Home screenを観察する
Then manual organization行、placement lock行、diagnostics行、category override行、
ユーザー定義カテゴリ行、Personalization groupがすべて残り、既存どおり機能する
And onboarding提案のflow（`OrganizationEntry.ONBOARDING` 経由のrun接続）は変更されない

### Scenario: 意味論の非変更（回帰保証）

Given 本Issueの変更が適用されている
When 既存のorganizer unit gate、instrumentation lane、spec 271の
`ManualOrganizationPreferencesInstrumentationTest` を実行する
Then すべてbehavior変更なしでpassする
And run state machine、確認・復旧の契約、diagnostics event、persistent storeへのdiffが
存在しない

## Data and state

- **読む**: durable status projection seam（`LayoutApplicationModule.durableOrganizerStatus()`、
  spec 271。UIは閉じたenumのみを読み、recovery storeには触れない——DESIGN.md §4.2）、
  startup readiness gate状態、process-local run coordinator状態（`ManualOrganizationModule`
  singletonの既存public seam）、既存のpersonalization記録preference（既存adapter経由）、
  Usage Access app-op付与状態（既存の読み取り）。
- **書く**: 新規の永続化はない。hubの使用状況materialからの記録toggle変更は既存preference
  adapter経由で既存の同一preferenceへ書くのみである。
- **Identity**: hubは新たなidentityを導入しない。navigation routeはrun状態・書込み権限を
  持たない既存destinationと同型の引数なしrouteである。
- **Migration / backup / restore / rollback**: 対象外。schema・preference format変更はなく、
  Launcher layout DB / `favorites` への接触はないためホームレイアウト安全規約の適用対象外。
  PR revertで現行挙動へ戻る。downgrade時に残留物はない。

## Permissions, privacy, and security

- None — 新しいpermission、外部送信、sensitive dataの扱い追加はない。
- durable statusの語彙はspec 271どおり閉域であり、payload・revision・digest・item
  identity・timestampを運ばない。hubはdurable statusを表示のみに使い、復元等の操作を
  新設しない。
- Usage Access行は既存どおりsystem設定への遷移のみであり、app-op状態の表示に留まる。

## Accessibility and localization

- 新surfaceにorganization-run-ux §6のaccessibility受入基準を適用する: TalkBackは
  status cardの各行・CTA・導線のname/role/stateを公開し、summaryに状態を含む。
  focus restorationは決定的である。200% font scaleでclipping/overlapや
  critical actionの到達不能を生まない。状態表示はcolor-onlyにしない。
  keyboard/switch traversalでstatus card → 「整理を開始」→ 材料導線へ論理順に到達し
  すべて活性化できる。touch target/contrastは既存platform基準を下げない。
- status cardのTalkBack読み順はTO-BE §13-5の「状態→残期限→操作」順に従う。本段階に
  残期限要素は存在しないため、実効順序は「状態→操作」であり、#374/#376が残期限要素を
  追加する際にこの順序へ挿入される。
- 進行中runによるstatus行の隠蔽（durable行なしの状態）は、silentな空白ではなく
  screen readerから到達可能な面構造として成立させる（durable行の不在自体は既存run面の
  規約と同様に許容するが、CTA・導線の発見可能性を損なわない）。
- すべての新規user-visible文字列とaccessibility読み上げ文をAndroid resource由来とし、
  EN（`values/`）とja（`values-ja/`）を供給する。複合文はformat resourceで構成し、
  Kotlin側の連結・補間で文を生成しない（spec 123 AC-4/AC-5規約）。既存destinationへの
  導線行は既存のlabel/summary string resourceを再利用する。

## Compatibility and migration

- navigation追加のみ。persistent state変更なし。process death時の既存挙動は不変であり、
  hubの再構成時にdurable statusは毎回deriveされる（spec 271のderived projection契約）。
- 設定側row維持により、本Issue単独でshippableであり、後続Issue（#367/#368/#369）と
  独立にrevert可能である。
- onboarding、exchange（spec 205/328）、strategy picker（spec 182/283）の既存flowに
  変更がない。

## Dependencies

- **#365（正本改訂、OPEN）**: 本specの契約根拠は、accepted済みでmain（`3076bdae7e`）に
  存在するorganizer-to-be-ux.mdであり、#365（product-brief/requirements/
  organization-run-ux/DESIGN/CONTEXTのAmend、docs-only）は本specの執筆をblockしない。
  ただし実装着手は正本先行の原則（AGENTS.md「正本を先に」、organizer-to-be-ux.md §11の
  要求更新表）に従い、#365のmerge後に限るものとする。`CONTEXT.md` のhub/材料語彙は
  #365が追加する。実装開始前にownerが#365の完了を確認すること。
- **後続**: #367（材料集約・設定row廃止）、#368（strategy移設・特例廃止）、
  #369（T-07以降のrun面統合）、#374/#375（status cardのAI依頼・取り込み済み提案）、
  #376（status card復元CTA）。本specはこれらの契約を先取り・先実装しない。
- **前提（実装済み・accepted）**: spec 271（durable status seam、implemented）、
  spec 13（safe apply、accepted）、spec 52（manual run縦切り、implemented）、
  spec 123（UI収束、implemented）、spec 203（使用状況・U-2、accepted/実装済み）、
  organization-run-ux §6（accessibility受入基準）。

## Acceptance criteria

- [ ] **HUB-AC-01**: 設定 → Home screenにOrganizer入口rowがあり、そこからhub（T-01）が
      開く。hubにはstatus card領域（durable status行群＋「整理を開始」CTA＋診断導線）と
      材料群（分類/カテゴリ/lock/使用状況）が表示される。（Issue受入1）
- [ ] **HUB-AC-02**: hubのstatus cardは、run coordinatorが `Idle`/`Cancelled` のときに
      spec 271と同一の閉域語彙・同一string resourceでdurable statusを表示する
      （restorable / restored-or-expired / unresolved＋safe-support導線）。never organized
      では行なし。reconciliation未完了ではchecking行を表示し、gate terminal到達時に
      同一面で回復する。読み取り失敗はfail-closed（行なし、書込み・journal eventなし）。run
      進行中はdurable行とchecking行を表示しない。（Issue受入2）
- [ ] **HUB-AC-03**（#374改訂）: hubに復元CTA（#376）と最近のrun結果の表示・操作が存在
      しない（non-goalsの機械的保証としての否定的観測）。進行中AI依頼行・取り込み済み提案行は
      #374実装でstatus cardへ追加された（D-02/D-08。追加行の契約は
      [spec 374](../374-durable-imported-intent/spec.md) が所有する）。
- [ ] **HUB-AC-04**: hubの「整理を開始」CTAから既存manual organization run面へ到達し、
      既存開始行により `MANUAL_FULL` triggerのrun flowが開始できる。hubから `start()` を
      直接発行する経路がなく、spec 13/52の安全契約・run state machine・spec 328/205の
      既存gateに変更がない（既存suiteが無編集でgreen）。（Issue受入3）
- [ ] **HUB-AC-05**: 設定側の既存Organizer導線（manual organization行、lock行、
      diagnostics行、category override行、ユーザー定義カテゴリ行、Personalization
      group）が維持され、onboarding flowを含む現行flowが壊れない。段階(a)単独で
      shippableである。（Issue受入4）
- [ ] **HUB-AC-06**: hubの使用状況materialは設定側と同一のpreferenceと同一のapp-op読み取り
      を操作し、両面で状態が一致する。spec 203 U-2の常設rowとresume時再読取の規約は
      変更されない。
- [ ] **HUB-AC-07**: organization-run-ux §6のaccessibility受入基準を新surfaceに適用する。
      status cardのTalkBack読み順は「状態→残期限→操作」規約に従い（本段階の実効順序は
      状態→操作）、focus restoration、200% reflow、non-color-only表示、keyboard/switch
      traversal、touch target基準を満たす。（Issue受入5）
- [ ] **HUB-AC-08**: すべての新規user-visible文字列とaccessibility読み上げ文がAndroid
      resource由来であり、ENとjaの両方のresourceが存在し、複合文はformat resourceで
      構成されている。既存destinationへの導線行は既存stringを再利用する。
- [ ] **HUB-AC-09**: hubが既存Lawnchair preference component/theme tokenで実装されており、
      spec 123 AC-1のinventory evidence文書（`docs/assessment/evidence/issue-123-ui-mapping.md`）
      へhubのmappingが追加されている。（Issue受入6）
- [ ] **HUB-AC-10**: run state machine、spec 13/52/205/328/271の契約面、diagnostics
      event、persistent store、permissionに変更がなく、既存のorganizer unit gateと
      instrumentation lane（編集なしの `ManualOrganizationPreferencesInstrumentationTest`
      を含む）がgreenである。

## Test oracle

| AC | Evidence |
|---|---|
| HUB-AC-01 | UI instrumentation test: hub入口rowの存在、hub画面の表示、status card領域と材料群の行構造。emulator screenshot（light/dark × ja/default） |
| HUB-AC-02 | UI instrumentation test: durable status行のstatus別render（restorable/restored-or-expired/unresolved＋safe-support/never organized/checking行）、run進行中の行隠蔽、Idle復帰後の再表示。spec 271の `OrganizerDurableStatusInstrumentationTest` 相当のseam再利用確認。emulator cold-process evidence（Launcher未起動でhubからdurable status到達、DS-AC-10スタイル） |
| HUB-AC-03 | UI instrumentation testの否定的観測 + 実装PR diff review（復元CTA・run結果の表示要素が存在しないこと。AI依頼・取り込み済み提案行は#374で追加済み — 追加行の検証はspec 374 DI-AC-11が所有） |
| HUB-AC-04 | UI instrumentation test: hub CTA → run面遷移 → 既存開始行からrun開始（既存 `ManualOrganizationPreferencesInstrumentationTest` を無編集のままgreen）。`app.lawnchair.organizer.*` unit gate green。diff上、run面/coordinator/application moduleの契約コード無編集 |
| HUB-AC-05 | UI instrumentation test: 設定側row群の存在と既存flowの回帰（manual entry/onboarding経由のrun接続）。既存instrumentation lane green |
| HUB-AC-06 | UI instrumentation test: hub側toggleと設定側rowが同一preferenceを読み書きすること（片側変更→他面の状態一致）。resume再読取の維持 |
| HUB-AC-07 | Compose semantics assertion（name/role/state、読み順、traversal）+ focus restoration test + 200% font scaleでのcritical action到達test + emulator evidence。organization-run-ux §6の表に基づく確認記録 |
| HUB-AC-08 | 新規stringの `values/` と `values-ja/` のname集合・placeholder一致の機械確認（spec 123 AC-5の検証集合定義方式を新規string集合に限定して適用）+ hardcoded literal grep |
| HUB-AC-09 | `docs/assessment/evidence/issue-123-ui-mapping.md` のdiff（hub行の追加、surface→参照component→差分）+ 実装PR差分のtoken/component再利用確認 |
| HUB-AC-10 | `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane、CI `final-status` green（PR記録） |

## Open questions

実装開始前に解消が必須な問いはない。非blocking事項:

1. hub入口rowの配置（General group内、#232で昇進済みのmanual organization行の隣を
   想定）と、status cardの視覚表現（heading cardか行群か）は、既存preference
   component規約の範囲で実装PRのreviewで確定する。TO-BEは視覚designを扱わない
   （organizer-to-be-ux.md §1）。
2. ja訳の最終文言は実装PRのstring diffで確定する（spec 123と同一の非blocking扱い）。
3. 高解像度（two-pane/expanded screen）でのhub描画は既存destinationの
   `PreferenceScaffold` + `LocalIsExpandedScreen` 規約に従う。追加の画面別契約は不要と
   想定するが、実装時のemulator evidenceで確認する。

## Change history

- 2026-09-21: **#374改訂（Amend。status card 2行の追加を受けてHUB-AC-03の否定的観測を縮小）**:
  進行中AI依頼行・取り込み済み提案行（D-02/D-08）をnon-goals/HUB-AC-03の対象から除外し、
  #374実装でstatus card（durable status行と開始CTAの間）へ追加されたことを記録した。
  追加行の契約・読み順挿入（状態→残期限→操作）は [spec 374](../374-durable-imported-intent/spec.md)
  が所有する。復元CTA（#376）・最近のrun結果の否定的観測は維持。
- 2026-09-19: Draft created for #366（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `3076bdae7e`、PR #364）と現行実装調査
  （`HomeScreenPreferences.kt`、`PreferenceRoutes.kt`、`PreferenceNavigation.kt`、
  `ManualOrganizationPreferences.kt`、`ManualOrganizationRun.kt`）を入力に作成。
  #365はOPENだが契約根拠はaccepted正本に存在するため、執筆をblockしないと判定。
- 2026-09-19: Re-entry確認とaccepted化。起草baseline `3076bdae7ebf` → 実装開始時
  `origin/main` `ec34dd3fa6` の差分はdocs-only（#378 disposition、#365正本改訂 =
  PR #379 merge済み）であり、Current evidenceの前提（`HomeScreenPreferences.kt` /
  `PreferenceRoutes.kt` / `PreferenceNavigation.kt` / run面 / instrumentation
  harness）は無変更。ownerのsession指示（Issue 366 対応開始、2026-09-19）により
  実装を開始し、本spec/planをacceptedへ更新。実装PRでの確定事項:
  status cardの診断導線行は常設とし（`organizer_diagnostics_title/description`
  再利用、既存diagnostics destinationへ）、UNRESOLVED時はunresolved行＋
  safe-terminal行をその上に表示する（run面のopen-diagnostics行を複製しない）。
  「整理を開始」CTAのlabelは既存 `manual_organization_start` を再利用する。
- 2026-09-19: **implemented**。[PR #380](https://github.com/nunu1733/NunuLauncher/pull/380) merge（commit `32c72094a4`）。ChatGPT reviewはhead `b59c11c85b`で中重要度2件（[初回](https://github.com/nunu1733/NunuLauncher/pull/380#issuecomment-5740584469)）→ `d22f33db60`修正 → [再レビュー: 指摘なし](https://github.com/nunu1733/NunuLauncher/pull/380#issuecomment-5740683416) → test-only修正 `c2efd2178a` を[実質変更なし確認](https://github.com/nunu1733/NunuLauncher/pull/380#issuecomment-5740825290)。CI `final-status` green（[run 35434960677](https://github.com/nunu1733/NunuLauncher/actions/runs/35434960677)、head `c2efd2178a`）。HUB-AC-01..AC-10のevidenceはPR本文のVerification表・[emulator evidence](../../docs/assessment/evidence/issue-366/README.md)（`docs/assessment/evidence/issue-366/`）。本PR本文は`Closes #366`でmerge時にIssue #366をclose済み。
- 2026-09-19: 実装review対応（ChatGPT review [PR #380 comment](https://github.com/nunu1733/NunuLauncher/pull/380#issuecomment-5740584469)、head `b59c11c85b`基準、中重要度2件）。(1) hubのdurable status描画を`showDurableStatus`で同時ガードし、run-active遷移直後に前回のdurable行を1 composition描画し得る構造を解消（HUB-AC-02）。hides testも「Idle表示中にrunを開始してactive遷移で行が消える」順に固定。 (2) HUB-AC-07の受入証跡を補強: hub入口focusの決定的focus restoration（start CTAへの`FocusRequester`。`clickable()`がfocus targetとEnter活性化を所有するため`focusable()`は付けない）、keyboard/DPAD traversal（状態→CTA→診断→材料の順に到達し各操作が活性化可能）、semantics（行のname＋click action、Switchのrole/state）のinstrumentation test 3件を追加。合計15件green、run面41件は無編集green。

[1]: https://github.com/nunu1733/NunuLauncher/issues/366
[2]: https://github.com/nunu1733/NunuLauncher/issues/365
