---
issue: "#372"
status: accepted
requirements: [FR-006, FR-017, NFR-009]
risk: []
updated: 2026-09-21
---

# AI相談を「整理案の作り方」の1つとしてT-07方法選択へ統合し、依頼作成（T-15）と送信前確認（T-16）を要約主面・期待明示・D-13語彙で再構成する

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-04, D-09, D-10, D-13, D-14, D-17, §5.1 T-07/T-15/T-16, §5.3 idle AI相談の遷移,
> §6.5, §7.1 export文書の有効性行・送信前確認行, §8.2 AI依頼行, §9 語彙規約, §10 用語）。
> 処分の正本: accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§3.11 spec 204文言Amend、§3.12 spec 205分段改訂のうち本件分、§4.1 supersession map、
> §5 更新順序 #6、§7.2 (c)）が本件を「specs 205 / 327 / 204文言のAmend、#372が実行」と定める。
> 本specは[Issue #372][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> Status: **accepted**（2026-09-21）— Phase1 reviewを通過して受入された。初版snapshot
> （`6ba84fc4`）への [1st review](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5740061847)
> は **Changes requested**（中3/低1）で、`0649c7e436` で全件対応。同headへの
> [2nd review](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5752821376)
> は **Changes requested**（中2）で、`7b1dd3d92d` で全件対応。同headへの
> [最終review](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5752909266)
> は **Approved**（blocking指摘0件。implementation-ready判定）。
> 前提: [Issue #369][2]のT-07前置き面（transitional構成・方法選択・admissionなし）が実装・merge済み
> である（spec 369は`implemented`。実装PR [#387](https://github.com/nunu1733/NunuLauncher/pull/387)、
> merge commit `7ec9e9d3fe`。spec 369 RD-1が「AIに相談」選択肢行の新設を#372に委ねる）。
> 本改訂（2026-09-21 re-entry）のbaselineはmain `13c95eafe6`（PR #389 merge後。
> #365/#368/#369/#370適用済み）である。

## Problem

External Agent Exchange（spec 205 implemented、UI `ExchangeFlowUi.kt`）は、run面の
Idle/Cancelled分岐の最下部に独立したentry row（V-27 `ExchangeEntryRow`。「依頼文を作成」
「回答を取り込む」の2ボタン＋capability説明）としてhostされ、以下のAS-IS findingを抱えている:

- **F-07（サブシステム感）**: AI相談が「整理」と並ぶ独立サブシステムとして見え、
  entryが操作面の末尾に置かれている（監査 D-8、V-27の配置）。accepted TO-BE D-04は
  External Agent Exchangeを**独立サブシステムとして見せず「整理案の作り方」の1つ**へ
  統合することを決定した。
- **active依頼の不可視性（監査 V-29/30）**: activeなexport session（24h・単一active）の
  存在は、次に生成しようとするまでUIから知る方法がなく、置換確認（V-30）で初めて知らされる。
  TO-BE D-02/§6.5は依頼の存在と残時間を事前表示することを要求する（status cardへの接続は
  #374であり、本IssueはT-15事前表示のみ）。
- **E-3（期待の不可視性）**: 依頼は作成時点のホームで固定される（`sourceContextDigest`が
  sessionに記録され、会話中のホーム・分類変更は取り込み時 `CONTEXT_STALE` になる）が、
  この期待は依頼作成時に表示されない。TO-BE D-09は期待明示を生成前と送信前確認の**両方**に
  表示することを決定した。
- **E-5（全文審査の形式化）**: 送信前確認（V-32 `ExchangeDisclosure`）は生成済みpackage全文
  （最大256KiB、240dp scroll）を常時提示するが、人間が全文を審査するのは実際上不可能で
  同意が形式的になっている。TO-BE D-10は送信前確認を「種別・件数・上限の要約を主面、
  全文は展開して確認可能」へ再構成することを決定した（外部開示の同意点は送信前確認1点のまま）。
- **監査 D-2（`LOCAL_FULL` UI語彙）**: spec 204は3 tierを定義するが、UIは2択のみであり、
  契約語彙とUI語彙の乖離がtier概念の説明を難しくしている。TO-BE D-14はユーザー向け選択肢を
  2tierに固定し、`LOCAL_FULL`は契約値としてのみ維持することを決定した。
- **D-13語彙（監査 F-09の一部）**: 送信前確認の「キャンセル」ラベルがzero-write中止と
  不可逆な未送信session無効化（pre-send cancel）の両方の意味で使われている。TO-BE §9は
  pre-send cancelを「破棄」（必ず確認を伴う）へ改めることを決定した。

## Outcome

T-07前置き面（#369）に「AIに相談」の方法選択が現れる。選ぶと**idle AI相談
（pre-run request flow）**として依頼作成面T-15が開く（run admissionなし・RUN lease取得なし・
trigger startなし）。T-15はactive依頼の存在と残時間を事前表示し、tier選択は
「情報を減らして送る / ラベル付きで送る」の2択固定で依頼作成へ進む（active依頼がある場合は
「破棄」語彙の置換確認を経る。spec 205 AC-13のgate構造は不変）。生成後の送信前確認T-16は
出る情報の種別・件数・上限の要約を主面とし、生成済みpackage全文は展開して確認できる
（確認対象と送信対象の同一性契約は不変）。依頼作成面と送信前確認の両方に期待明示（D-09）が
表示される。未送信依頼の破棄（pre-send cancel）は「破棄」ラベル＋確認dialogになる
（送信後の「閉じる」は依頼を生存させる）。既存のidle entry rowは撤去され、idle相談flowから
依頼作成と回答取り込みの双方へ到達できる。run-in相談（T-08選択面のscope凍結entry）の
契約は変更しない。

## Scope

- **idle entry rowの撤去とT-07方法選択への統合（D-04）**: T-07前置き面（#369が新設する
  Idle/Cancelled分岐の前置き面）から `ExchangeEntryRow`（V-27）を撤去し、「AIに相談」の
  方法選択（既存 `exchange_entry_subtitle` 相当の短い説明を伴う）を新設する。選択でidle相談
  flow（T-15）を開く。run admission（RUN lease取得）・`start(trigger)` 発行は行わない
  （D-04/D-17。capability先取り禁止の#366原則に従い、#369はこの選択肢を新設しない）。
- **T-15依頼を作る面の新設（V-29/V-30/V-31の統合・再構成）**: 現行のtier選択
  （`ExchangePrivacySelection`）と置換確認（`ExchangeReplacementConfirm`）を依頼作成面
  T-15として再構成する。面は次を持つ:
  1. **active依頼の事前表示**: activeなexport session（`ExportSessionStore.active`）が
     存在するとき、その存在と残時間を表示する。残時間は表示根となる読取時点のsession
     `expiresAtEpochMs` から導出する。再読取は (a) T-15進入時（`openFlow()`）、
     (b) T-15表示中のlifecycle resume（ON_RESUME）、(c) 表示中のsessionの失効時刻に
     1回だけscheduleした再読取、で行い、そのつど存在・残時間・置換確認要否を再読取値で
     更新する（`active()` は失効sessionを不在として返すため、(c)によりT-15を表示したまま
     TTLを跨いでも事前表示は消える。lifecycle遷移に依存しない）。残時間の連続的な
     時計追随更新（秒針tick等）は行わない。(c)のscheduleはholderのoperation scopeで
     実行し、画面離脱でcancelされた場合は次の進入時の読取が表示を回復する。
     idle/run-in両形態で共通の表示である（TO-BE §5.3「面の共有」）。status card
     への依頼表示は#374であり、本IssueではT-15事前表示のみである。
  2. **tier選択の2択固定（D-14）**: ユーザー向け選択肢は「情報を減らして送る（既定）/
     ラベル付きで送る」の2つのみである。`LOCAL_FULL`はUI語彙に出現しない（契約値としては
     spec 204が維持）。label付き選択時の開示警告は、context v4（spec 337）のlabel付きtierで
     外部へ出る自由文集合（アプリ名・フォルダ名・**ユーザー定義カテゴリの名前**）を正確に
     列挙するよう改訂する（現行 `exchange_privacy_labels_warning` はユーザー定義カテゴリ名を
     欠く。T-16の既存開示文言 `exchange_disclosure_labels_included` との整合）。
  3. **期待明示（D-09・生成前）**: 「この依頼は作成時点のホームで固定されます。会話中に
     ホームや分類を変更すると回答が取り込めなくなります」の意味要素（依頼は作成時点のホーム
     で固定・会話中のホームや分類の変更で回答が取り込めなくなる）を表示する。
  4. **依頼を作成CTA**: 既存の生成順序契約（gate → build → save → compose → disclose、
     `ExchangeFlowController.generate`）を不変で呼ぶ。active依頼が存在する場合は置換確認
     （次項）を経る。
  5. **置換確認（spec 205 AC-13維持・語彙改訂）**: active依頼が存在する状態での新規生成開始は
     既存の`ExchangeGenerationGate`経由の確認を必須とする（承認なしの生成開始経路は存在しない）。
     確認copyは「破棄」語彙（依頼の置換 = 既存依頼宛回答の不可逆な無効化）に揃え、
     承認は「破棄して作成」相当、辞退は既存依頼を生存させる（現行契約どおり）。
  6. **回答を取り込む導線**: 撤去されたentry rowの「回答を取り込む」（既存T-17入力面、
     spec 332のUI）への到達経路をT-15面に維持する（Contract notes 2）。
- **T-16送信前確認の再構成（D-10・V-32）**: 現行 `ExchangeDisclosure` を要約主面へ再構成する:
  1. **要約主面**: 出る情報の**種別**（tier別の既存開示文言の継承）、**対象項目数**
     （生成済みpackageのexported items数。語彙で「対象項目数」であることを明示し、外部へ出る
     全record数と誤読できる単なる「件数」表示をしない。category collectionのrecord数は
     表示せず、categoryの有無・名前の要否は種別文言が説明する。生成済みimmutable値に
     紐づくsession `itemRefs` から導出し、表示中にlive状態から再計算しない）、**上限**
     （spec 204 V1 content limits: items 512件・export canonical
     JSON 256 KiB、のユーザー向け表現）の要約を主面とする。
  2. **全文展開**: 生成済みpackage全文は既定では折りたたみ、展開して確認できる状態を保つ。
     展開textは確認対象と同一のimmutableな値である（spec 205 AC-12の同一性契約は不変）。
     全文の常時強制提示は廃止する。
  3. **期待明示（D-09・送信前）**: T-15と同一の意味要素の期待明示を送信前確認にも表示する。
  4. **transport契約の不変**: clipboard copy / Share Sheet / file保存の3経路、
     送信承認なしにtransport経路が開かれない構造、再生成時の確認やり直しは現行契約どおり
     （spec 205 AC-3/AC-12のgate構造は不変。表示形式のみ改訂）。
- **pre-send cancelの「破棄」化（D-13）**: 未送信依頼の破棄（既存 `closeDisclosure` の
  cancel経路 = 当該未送信sessionの明示的失効 `ExportSessionStore.invalidate`）のラベルを
  「キャンセル」から「破棄」へ改め、**確認dialog 1回を経る**（D-13 §9の破棄の確認規約）。
  失効対象は当該未送信sessionのみであることは現行契約どおり（transport in-flight中・送信済み
  は対象外）。送信後の「閉じる」は依頼を生存させ、確認dialogを要さない（現行契約どおり）。
  T-15進入前の中止（依頼作成前のtier選択の中止）は「キャンセル」（確認不要・zero-write）の
  ままである（D-13 §9）。
- **system Backの契約固定（T-15/T-16）**: T-15/T-16表示中のsystem Backは、host面に常時
  compositionされる専用handler（`ExchangeImportSuccessBackHandler` と同型。lazy item内には
  置かない。spec 328 D-2の#328 reviewで確立した配置原則の継承）で扱い、次を契約とする
  （accepted TO-BE §5.3「Backは常に1つ前の面へ戻る」・§9遷移図
  `IdleExchange → Start: 破棄`・§9 Back規約への適合）:
  1. **T-15（tier選択・置換確認）Back**: zero-writeでflowを閉じT-07面へ戻る
     （確認不要。run-inではflowを閉じて選択面へ戻る。既存のflow close契約と同一経路）。
  2. **T-16未送信（cancelable）Back**: 未送信依頼の「破棄」確認dialog（「破棄」ボタンと
     同一の確認。D-13）へ進む。confirmで当該未送信sessionのみが失効し（既存
     `closeDisclosure()` の構造gate経由。`cancelling`確定 → `invalidate` → flow close）、
     dismissではT-16が維持される。これにより、生成済み・未送信packageの表示だけを
     離脱してactive依頼をdurableに残す経路（ghostな未送信依頼）をBackから閉ざす。
  3. **生成中（`Generating`）・transport in-flight中・`cancelling`中のBack**: Backを
     handlerが取り込み（consume）、画面離脱させない（無操作。operationのsettle後は
     通常のBack契約へ戻る）。理由: holderのoperation（generation・transport・
     `invalidate`）はhostが`rememberCoroutineScope()`で作るscopeで実行され、画面離脱
     （composition破棄）はscope cancelを介してこれらを中断させる。busy状態でBackを
     既定のdismiss/navigate経路へ委ねることは「operationは継続する」契約と矛盾するため、
     Backを取り込むことが契約整合的な不受理である（Phase1 review 2回目指摘1。
     TO-BE §9の適用中Back不受理と同型の保護）。
  4. **T-16送信済み Back**: 「閉じる」と同一（zero-write、依頼は生存、確認不要）。
  T-17入力面（`Importing`）・取り込み成功状態のBackは現行契約（spec 328/332）のままである
  （本Issueはimport側の継続性の契約変更を行わない）。
- **capability説明の配置転換（spec 327 Decision 4の改訂）**: idle entry rowにhostされていた
  capability説明4要素（具体例での「AIでできること」・「AIはホーム画面を直接変更しない」・
  期待される会話flow（質問→方針確認→最終案、1往復）・会話はNunuLauncherを経由しない）を
  idle相談導線の新しい配置（T-15面本体。T-07の「AIに相談」選択肢には短い説明。Contract
  notes 1）へ移す。run-in entry（`ExchangeScopedEntryRow`）のcapability説明は現行のまま
  変更しない。
- **spec改訂（実装PRで実施）**:
  - **spec 205**: (1) 「exchange導線の提示はmanual run操作非active時に限定する (V1)」の
    規定とV-27単独idle entry配置をsupersedeし、「idle相談はT-07方法選択「AIに相談」から
    開始するpre-run request flow（run admission・RUN lease取得なし。D-04/D-17）。run-in相談
    （選択面T-08のscope凍結entry）はspec 331契約を維持」へ改訂（Behavior scenariosの
    entry前提、process recreation後のrun再構築scenario内のV1規定、Data and state）。
    (2) AC-3/AC-12の送信前確認の**表示形式**を要約主面＋全文展開へ改訂（gate構造・同意点1点・
    確認対象とtransport対象の同一性は不変）。(3) AC-13の確認語彙を「破棄」へ揃える
    （gate契約は不変）。(4) T-15事前表示・D-09期待明示・pre-send cancelの破棄＋確認を
    本specの契約として追記。
  - **spec 327**: Decision 4（capability説明の配置）の配置前提を「idle entry」から
    「T-07方法選択（短い説明）＋T-15依頼作成面（4要素本体）」へ改訂。AC-4/AC-5の検証対象面の
    配置前提を更新（4要素の必須性・文言契約は不変）。instruction契約（AC-1〜AC-3）には触れない。
  - **spec 204**: privacy tier節へ「UI選択肢は2種（redacted / labels。TO-BE D-14の語彙）。
    `LOCAL_FULL`は内部契約値（将来のlocal LLM向け余地、#206）として維持し、外部workflowのUIに
    出現させない」を明文化（文言のみ。契約値・schema・validator・tier matrixは不変）。
- **Localization**: 新規・改訂のuser-visible文字列はAndroid resource由来でEN（`values/`）と
  ja（`values-ja/`、正本）の双方に供給する（spec 123契約）。改訂copyはTO-BE §10語彙
  （依頼（AI相談）/破棄/取り込み）とD-13語彙規約に従う。統合により未使用になったstringは
  実装PRのreference grepで確定し、同じPRで双方のresourceから削除する。

## Non-goals

- 取り込みUI・失敗表示の再構成（D-11手段別再投影、T-17/T-18、**#373**）。T-17入力面
  （spec 332）と失敗表示（typed 20種の直接説明）は現行のまま維持する。
- 取り込み済み提案のdurable化・hub status cardへの依頼表示・依頼カードのstatus card統合
  （D-08/D-02、**#374**）。pending intentはprocess-localのままである。
- run-in相談の契約変更（選択凍結・`attachIntent`・scope binding gate・選択復元初期値は
  spec 331/375の所有。本Issueではrun-in entry行とflow面を現行どおりhostする）。
- 会話形式・instruction契約の変更（interview-first 2-phase、canonical example、
  `Issue348AiFacingContractSyncTest` の回帰対象はspec 327/348が所有。本IssueはUI配置のみ）。
- 外部開示の同意点の移動・追加（送信前確認1点のまま。D-10）。
- exchange framing、envelope上限、session TTL 24h・単一active・`sourceContextDigest`、
  transport 3経路、import pipeline・validatorの変更（spec 204/205/329の契約は不変）。
- `LOCAL_FULL`契約値・`ContextExportBuilder`のtier分岐の削除（将来のlocal LLM向け余地として
  維持。監査 D-2、disposition §4.3 Defer）。
- spec 328（accepted・implemented）のimport attempt anchor・success state・freeze機構の再設計
  （disposition §3.14のrev.2は#374が所有）。本IssueはT-07「そのまま整理」CTAへのidle start
  row freeze継承（#369）と、`importAttemptActive`中の既存freeze挙動を回帰として維持するのみ。
- hub（T-01）の構成変更（status card・材料導線は#366/#367/#374/#376の所有）。
- run state machine・typed outcome・journal event・diagnostics契約・persistent store・
  permission・外部送信経路の変更。

## Domain language

- **idle AI相談（idle exchange / pre-run request flow）**: run admission（RUN lease取得）を
  伴わず、依頼の作成・待機・取り込み中も恒常authoringが可能なAI相談形態（TO-BE D-04/D-17）。
  T-07方法選択「AIに相談」から開始し、run admissionは「この提案で続ける」以後に発生する
  （既存の取り込み成功状態CTA経由、spec 328の継続CTA契約）。
- **依頼（AI相談）**: export sessionと送出文書の一組（TO-BE §10）。本specのUI copyは
  「エクスポートセッション」「交換」ではなく「依頼」を正とする。既存stringsの全面改名の
  要否（`exchange_*`の「交換」語彙）は実装PRで確定する（非blocking。新規・改訂copyは依頼語彙を
  正とする）。
- **T-15依頼を作る / T-16送信前確認**: organizer-to-be-ux.md §5.1のAI系表面ID。本specでは
  ユーザーに区別して見せる面の契約名として使い、UI上のlabelとしては現れない（ユーザー向け語彙
  はTO-BE §10およびD-13に従う）。実装上は既存のinline flow screen群（`ExchangeScreen`）の
  再構成であり、新規destinationは要求しない。
- **破棄（pre-send）**: 未送信依頼の不可逆な無効化（当該未送信sessionの明示的失効）。
  D-13 §9の破棄（必ず確認を伴う）に分類される。「依頼」「中止語彙規約」の正本は
  `CONTEXT.md`（[Issue #365][3]で収録済み）であり、本specでは複製しない。
  idle AI相談の語彙はTO-BE D-17に由来する。

## Behavior scenarios

### Scenario: T-07で「AIに相談」を選ぶとadmissionなしで依頼作成へ進む

Given #369のT-07前置き面が表示されており、run coordinatorが `Idle` である
When 「AIに相談」の方法選択を選ぶ
Then 依頼作成面（T-15）が開き、tier選択と依頼を作成CTAが表示される
And run admission（RUN lease取得）・`start(trigger)`・検出/compositionの実行は発生せず、
run coordinatorは `Idle` のままである
And 「そのまま整理」の方法選択と「AIに相談」の選択は並存し、いずれもT-07面上の操作である

### Scenario: idle entry rowは撤去され、依頼作成と取り込みはT-15から到達できる

Given #369適用後のT-07前置き面が表示されている
When Idle/Cancelled面を観察する
Then 単独のidle exchange entry row（`ExchangeEntryRow`。title＋subtitle＋capability説明＋
「依頼文を作成」「回答を取り込む」2ボタンのblock）は存在しない
And 「AIに相談」→ T-15から依頼作成と回答取り込み（既存T-17入力面への導線）の双方が到達可能である
And run-in entry（`ExchangeScopedEntryRow`）はT-08選択面に現行どおり存在する

### Scenario: T-15はactive依頼の存在と残時間を事前表示する

Given 有効期限内のexport sessionが存在する（前回生成から24h以内）
When T-15依頼作成面を開く
Then active依頼の存在と残時間が表示される（表示根となる読取時点のsession
`expiresAtEpochMs` からの導出。面上で継続的に秒針のように更新される時計は要求しない）
And 同一面にtier選択が表示され、依頼を作成CTAの選択は置換確認を経る
And active依頼が存在しないとき、事前表示は行われない（「依頼がありません」等の偽装行も作らない）

### Scenario: T-15表示中にTTLを跨ぐと、表示と確認要否が失効状態と一致する

Given T-15にactive依頼の事前表示がされており、clockを制御できる
When lifecycle遷移を発生させずにclockを失効時刻まで進める
Then 失効時刻にscheduleされた再読取でactive sessionは不在として読まれ、事前表示は消える
And 置換確認も不要となり、依頼を作成CTAは確認なしで生成を開始できる
And 表示の判定は生成時のgate（`ExchangeGenerationGate` + `activeSession()` 再読取）と一致する
And 進入・ON_RESUME・失効時刻scheduleのいずれの読取経路でも、読取後の表示が
失効状態と矛盾することはない

### Scenario: 置換は「破棄」語彙の確認を経る（spec 205 AC-13維持）

Given active依頼が存在し、T-15でtierを選択して依頼を作成を選ぶ
When 置換確認が表示される
Then 確認copyは「破棄」語彙（新規依頼の作成により既存依頼宛の回答が取り込めなくなることの明示）
であり、承認（破棄して作成相当）なしには生成（context export生成・session保存・package合成）
が開始されない
And 辞退した場合は既存依頼は不変であり、既存依頼宛の回答は引き続き取り込める
And 承認後の生成は既存の順序契約（gate → build → save → compose → disclose）を通る

### Scenario: tier選択は2択固定でありLOCAL_FULLはUI語彙に出現しない（D-14）

Given T-15依頼作成面が表示されている
When tier選択肢を観察する
Then 選択肢は「情報を減らして送る（既定）」と「ラベル付きで送る」の2つのみであり、
`LOCAL_FULL` を示す語彙はUIに存在しない
And label付き選択時にはアプリ名・フォルダ名・ユーザー定義カテゴリの名前が外部へ出る旨の
警告が表示される（context v4の自由文集合に一致する警告copy。EN/ja双方で供給）
And 選択は既存の `EXTERNAL_REDACTED` / `EXTERNAL_WITH_LABELS` 契約値に対応し、
`ContextExportBuilder` のtier分岐（`LOCAL_FULL`含む）は変更されない

### Scenario: 送信前確認は要約主面＋全文展開である（D-10）

Given 生成済みのexchange packageに対して送信前確認（T-16）が表示されている
When 確認面を観察する
Then 出る情報の種別・対象項目数・上限の要約が主面として表示され（件数は「対象項目数」の
語彙で示す。category collectionのrecord数は表示しない）、生成済みpackage全文は
既定では折りたたまれ、展開操作で確認できる
And 展開された全文は確認対象と同一のimmutableな値であり、確認後に実行されるtransport
（clipboard copy / Share Sheet / file保存）は同一の値を送出する（spec 205 AC-12の回帰）
And 確認の前に外部送信経路が開かれることはなく、tier変更等の再生成時は確認からやり直す
（現行契約の回帰）

### Scenario: 期待明示（D-09）が生成前と送信前の両方に表示される

Given idle相談flowで依頼を作成している
When T-15依頼作成面とT-16送信前確認をそれぞれ観察する
Then いずれの面にも「依頼は作成時点のホームで固定される」「会話中にホームや分類を変更すると
回答が取り込めなくなる」の意味要素を含む期待明示が表示される
And 期待明示の表示はtier選択に依存しない（両tierで表示される）

### Scenario: 未送信依頼の破棄は「破棄」ラベル＋確認である（D-13）

Given 生成済み・未送信のpackageに対して送信前確認が表示されている
When 「破棄」ボタン（pre-send cancel）を選ぶ（system Backも同じ破棄確認へ収斂する）
Then 「破棄」ラベルの操作であり、確認dialog 1回を経たのちに当該未送信sessionのみが
明示的に失効し（`ExportSessionStore.invalidate`）、activityな依頼を残さない
And 確認dialogで辞退した場合はpackageと依頼は生存し、送信前確認が続行する
And transport in-flight中・送信済みのdisclosureは破棄対象にならない（現行契約の回帰）

### Scenario: T-15/T-16のsystem Backは面の契約に従う

Given idle相談flowが開いている
When T-15（tier選択または置換確認）でsystem Backを押す
Then flowはzero-writeで閉じ、T-07面へ戻る（確認dialogは出ない。run-inではflowが閉じて
選択面へ戻る。既存のflow close契約と同一経路）
And T-16未送信（transport非in-flight・非`cancelling`）でsystem Backを押すと、
未送信依頼の「破棄」確認dialogが出る
And 確認を承認すると当該未送信sessionのみが失効してflowが閉じ、dismissするとT-16が維持される
And 生成中（`Generating`）・transport in-flight中・`cancelling`中のsystem Backは
handlerが取り込み、画面は離脱せずoperationはcancelされずにsettleまで継続する
And settle後のsystem Backは、その時点の面の契約（閉じる・破棄確認等）が適用される
And T-16送信済みでsystem Backを押すと確認なしでflowが閉じ、依頼は生存する

### Scenario: 送信後の「閉じる」で依頼は生存する

Given transport成功後の送信前確認が表示されている
When 「閉じる」を選ぶ
Then 依頼（session）は失効せず、同一回答の取り込みが期限以内に成立する
And 「閉じる」には確認dialogが要らない（zero-write、D-13 §9のキャンセル分類）

### Scenario: idle相談中も恒常authoringは可能である（lease拒否なしの回帰）

Given T-07からidle相談flowを開き、依頼の生成中または送信前確認の表示中である
When 材料（分類・ロック・方針・使用状況）の編集を行う
Then idle相談はRUN lease・AUTHORING leaseを取得しないため、編集はlease拒否されずに成立する
And T-07面を離れて材料を編集し戻ると、依頼（session）はdurableに生存しており、
T-15の事前表示と取り込み導線から再開できる（flowの表示状態自体はprocess-localで
画面離脱で閉じる。現行の画面状態扱いどおり）

### Scenario: run-in相談の契約は変更されない

Given T-08選択面が表示されており、scope凍結AI入口（run-in entry）がhostされている
When run-in相談flowを開く
Then `ExchangeScopedEntryRow`・capability説明・scope凍結notice・scope-composed生成・
選択凍結・`attachIntent`接続・`SCOPE_MISMATCH`表示は本Issue適用前と同一である
And T-15/T-16の再構成（事前表示・要約主面・語彙）はidle/run-inで共有される面に対して
等しく適用される（TO-BE §5.3「面の共有」）

### Scenario: 同意gate・transport・session契約は変更されない（回帰保証）

Given 本Issueの実装PRが作成されている
When spec 205/204の契約test（session置換gate・disclosure状態遷移・transport同一性・
import pipeline・`ExchangeFlowStateHolderTest` 群）を実行する
Then 表示形式の変更対象を除き、すべてが無編集または表示面移設のみの更新でgreenである
And `Issue348AiFacingContractSyncTest` とspec 327のinstruction契約test
（`ExchangePackageComposerTest`）は無編集でgreenである（instruction契約に触れないことの証拠）

## Failure behavior

| Condition | Observable outcome |
|---|---|
| 生成時の入力未READY（`InputNotReady`） | 既存のtyped status（`GENERATION_INPUT_NOT_READY`）とT-15面への復帰。現行契約の回帰 |
| session store保存失敗 | 既存のtyped status（`GENERATION_STORE_FAILURE`）。packageは出ない（fail-closed回帰） |
| encode失敗（content limits超過） | 既存のtyped status（`GENERATION_OVERSIZE`）。ghost active sessionは残らない（現行契約の回帰） |
| T-15表示中にactive依頼がTTL失効 | 失効時刻にscheduleされた再読取（および進入・ON_RESUMEの再読取）で事前表示は消え、置換確認も不要になる（`active()` は失効sessionを不在として返す）。生成時のgate（`ExchangeGenerationGate` + `activeSession()` 再読取）と表示の判定は同一seamで常に一致し、取り込みは期限切れを `SESSION_EXPIRED` でtypedに拒否される（fail-closed回帰） |
| 破棄確認中のtransport試行 | 破棄受付後のdisclosureはterminal `cancelling` 状態であり、transportは開始もsettleもしない（現行 `closeDisclosure` 契約の継承。確認dialogはUI層のaffordanceであり、構造gateは現行どおり） |
| import attempt生存中のT-07「そのまま整理」CTA | 既存のidle start row freeze（spec 328、`importAttemptActive`）が「そのまま整理」CTAに継承される（#369で規定、本Issueでは回帰として維持） |
| 取り込み失敗・成功状態の表示 | 現行契約（spec 205/328/329/332）どおり。再構成は#373 |

## Stale state / concurrency

- 依頼の有効性（TTL 24h・単一active・置換で無効化）はspec 204/205契約のまま不変であり、
  T-15事前表示はその**表示**を担うのみである。表示と実効gateの乖離は生成時の
  `activeSession()` 再読取と取り込み時の `SESSION_EXPIRED`/`EXPORT_MISMATCH` 検証が防ぐ
  （「UI disabled/表示はaffordanceにすぎない」原則の継承）。
- 残時間表示は表示根の読取時点のclock読取から導出し、同一面上の連続的な再計算・
  live更新は行わない。読取は進入・ON_RESUME・失効時刻にscheduleされた1回の再読取で
  行われるため、T-15を表示したままTTLを跨いでも（lifecycle遷移なしでも）表示は
  失効状態へ一致し、実効gate（生成時の再読取・取り込み時の `SESSION_EXPIRED`/
  `EXPORT_MISMATCH` 検証）が常に正しさを担保する
  （「UI disabled/表示はaffordanceにすぎない」原則の継承）。
- pre-send破棄の確認dialogはUI層のaffordanceであり、session失効の構造gate
  （Main上の`cancelling`確定 → transport開始/settle不受理 → `invalidate`）は現行
  `closeDisclosure` 契約を継承する。
- run-in相談の選択凍結・scope gate・attempt anchor（spec 331/328）との相互作用は変更しない。

## Data and state

- **読む**: active export session（`ExportSessionStore.active`。既存のcontroller seam
  `activeSession()`）。T-15事前表示とT-16要約（件数）はsession `expiresAtEpochMs` /
  `itemRefs` から導出する（いずれも既存field。新規の読取seamは設けない）。
  要約の表示値は生成済みimmutable packageに紐づくsessionから得る（確認対象との同一性を維持）。
- **書く**: 新規の永続化・preference・diagnostics eventはない。書込みは既存のsession保存/
  invalidate（spec 204/205契約）のみである。pending intentはprocess-localのままである
  （durable化は#374）。
- **Identity**: 変更なし。`exportId`、ref↔`ItemId` map、`sourceContextDigest`、
  tier契約値は不変である。
- **Migration / backup / restore / rollback**: persistent state変更なし。schema変更なし。
  Launcher layout DB / `favorites` への接触なし（ホームレイアウト安全規約の適用対象外）。
  PR revertで旧entry row＋旧確認面構成へ戻る。downgrade時の残留物なし。
- **利用者の既存状態**: active依頼・未送信package・取り込み済み提案は本変更の前後で同じ
  意味を保つ。画面離脱によるflow表示状態の消失（process-local）は現行どおりであり、
  sessionのdurable性（24h）で依頼は生存する。

## Permissions, privacy, and security

- None — 新規permission、network、外部送信経路の追加はない。外部送信は既存の送信前確認
  （T-16）経由のみであり、同意点は1点のままである（D-10）。
- 要約主面は「出る情報の種別」のinformed consent根拠を弱めない: 種別（tier別開示文言）・
  件数・上限は生成済みpackageから導出され、展開可能な全文と同じ対象を説明する。
  件数等の表示値はsession由来であり、追加の個人情報を画面へ新規に運ばない
  （session `itemRefs` の**件数**のみで、ref値・内部IDを表示しない）。
- `LOCAL_FULL`のUI語彙除外は表示の変更であり、tier matrix・redacted tierの自由文除外
  （spec 204契約）は不変である。
- clipboard内容・package textのdiagnostics記録禁止（organizer-diagnostics.md）は現行どおり。

## Accessibility and localization

- T-15/T-16の再構成面にorganization-run-ux §6のaccessibility受入基準を最初から適用する:
  TalkBackは見出し・事前表示・要約・展開操作・transport・破棄のname/role/stateを公開する。
  全文展開はexpand/collapseのstateをTalkBackへ伝え、展開前に全文がTalkBackの読み順に
  大量投入されない（要約主面化のa11y上の利点を保持する）。focus restorationは決定的であり、
  破棄確認dialogはconfirm/cancelを明示的なroleで提供しfocusをdialogへ移す。
  200% font scaleでclipping/overlapやcritical actionの到達不能を生まない。
  期待明示・警告はcolor-onlyにしない。timeout auto-confirm/cancelは存在しない。
- 新規・改訂string（T-15見出し・事前表示・残時間format・tier語彙・期待明示・要約・
  全文展開・破棄確認dialog等）はAndroid resource由来でEN（`values/`）とja（`values-ja/`、
  正本）の双方へ供給する。複合文（残時間等）はformat resourceで構成し、Kotlin側の連結で
  文を生成しない（spec 123 AC-4/AC-5規約）。既存の開示文言・失敗文言で契約由来のもの
  （spec 205/204由来の開示種別文言等）は可能な限り再利用する。
- 統合により未使用になるstring（撤去されるentry row由来等）は実装PRのreference grepで
  確定し、`values/`/`values-ja/`双方から削除する（孤立user-visible参照の排除）。

## Acceptance criteria

- [ ] **EX-AC-01**: 単独のidle exchange entry rowが撤去され、T-07方法選択の「AIに相談」から
      idle相談flow（T-15）が開く。選択はrun admission（RUN lease取得）・`start(trigger)` を
      発生させず、依頼作成と回答取り込み（既存T-17入力面への導線）の双方に到達できる。
      run-in entryはT-08選択面に現行どおり存在する。（Issue受入1）
- [ ] **EX-AC-02**: idle相談中（依頼作成・生成中・送信前確認中）に材料（分類等）の編集が
      lease拒否されずに可能である。idle相談flowの開始・生成・確認表示はRUN lease /
      AUTHORING leaseを取得しない。（Issue受入2）
- [ ] **EX-AC-03**: T-15がactive依頼の存在と残時間を事前表示し（active依頼なしでは表示しない）、
      T-15進入・ON_RESUME・失効時刻にscheduleした再読取で存在・残時間・置換確認要否が
      `activeSession()` の値へ一致する（lifecycle遷移なしでfake clockをTTL超過まで進め、
      事前表示が消え確認要否が下がるclock-controlled oracleを含む）。
      active依頼がある状態の新規作成が「破棄」語彙の置換確認を経る。spec 205 AC-13の
      gate契約（承認なし生成不開始・辞退時既存依頼不変・E1→E2→取消→E1の`EXPORT_MISMATCH`
      zero-write回帰）は不変である。（Issue受入3）
- [ ] **EX-AC-04**: 送信前確認（T-16）が種別・対象項目数・上限の要約を主面とし（件数は
      「対象項目数」の語彙で明示される）、生成済みpackage全文を
      展開して確認できる。全文は確認対象と同一のimmutable値である。期待明示（D-09の意味要素:
      作成時点のホームで固定・会話中のホーム/分類変更で回答取り込み不可）がT-15（生成前）と
      T-16（送信前）の両方に表示される。（Issue受入4）
- [ ] **EX-AC-05**: tier選択肢が2つのみ（「情報を減らして送る / ラベル付きで送る」）であり、
      `LOCAL_FULL`がUI語彙に出現しない。label付き選択の開示警告がcontext v4の自由文集合
      （アプリ名・フォルダ名・ユーザー定義カテゴリの名前）をEN/ja双方で正確に列挙する。
      契約値（`EXTERNAL_REDACTED` / `EXTERNAL_WITH_LABELS` /
      `LOCAL_FULL`）と `ContextExportBuilder` のtier分岐は維持されている。（Issue受入5）
- [ ] **EX-AC-06**: 外部開示の同意点が送信前確認1点であること、およびtransport契約
      （clipboard copy / share / file、確認前送信の不在、再生成時の確認やり直し、
      同一immutable値の送出）に変更がないことが、spec 205 AC-3/AC-12対応testのgreenで
      検証される。（Issue受入6）
- [ ] **EX-AC-07**: capability説明4要素（具体例での「AIでできること」・「AIはホーム画面を
      直接変更しない」・期待される会話flowと1往復の受け渡し・会話はNunuLauncherを経由しない）
      が新しい配置（idle相談導線）で満たされ、run-in entryの説明も維持される。
      （Issue受入7。spec 327 AC-4/AC-5の配置前提改訂と対応）
- [ ] **EX-AC-08**: 未送信依頼の破棄（pre-send cancel）が「破棄」ラベル＋確認dialog 1回で
      当該未送信sessionのみを失効させる。送信後の「閉じる」は確認なしで依頼を生存させる。
      （D-13）
- [ ] **EX-AC-09**: spec 205（entry規定のsupersede・AC-3/AC-12表示形式・AC-13語彙・T-15/T-16
      追記）、spec 327（Decision 4配置・AC-4/AC-5配置前提）、spec 204（D-14文言）の改訂が
      同じ実装PRで行われている（Scope節の改訂内容のとおり。gate構造・schema・validator・
      instruction契約には触れない）。
- [ ] **EX-AC-10**: T-15/T-16の再構成面がorganization-run-ux §6のaccessibility受入基準を
      満たし（TalkBack name/role/state、展開state、focus restoration、200% reflow、
      non-color-only、traversal、no timeout auto-confirm）、新規・改訂stringがEN/ja双方で
      供給され、未使用化stringが双方のresourceから削除されている。
- [ ] **EX-AC-11**: T-15/T-16のsystem Backがhost面に常時compositionされるhandlerで
      契約どおり扱われる: T-15（tier選択・置換確認）Backはzero-writeでflowを閉じ
      T-07（run-inは選択面）へ戻り、T-16未送信 Backは「破棄」確認dialog
      （confirm = 当該sessionのみ失効・dismiss = T-16維持）を経て、T-16送信済み Backは
      確認なしで閉じて依頼を生存させ、生成中（`Generating`）・transport in-flight中・
      `cancelling`中のBackはhandlerが取り込んで画面離脱させず、operationはsettleまで
      cancelされずに継続する（blocking fake generation / `FileExchangeTransport` での
      直接assertを含む）。lazy item内へのBack handler配置は行わない（spec 328 D-2の原則）。

## Test oracle

| AC | Evidence |
|---|---|
| EX-AC-01 | instrumentation: T-07で「AIに相談」→T-15表示（coordinator `Idle`維持・`start`不発行の否定的観測）、T-15から取り込み導線の到達、idle entry rowの不在（`exchange-entry-title`等の否定的観測）、T-08でのrun-in entry存在の回帰 |
| EX-AC-02 | unit: idle相談flow状態（`Disclosing`表示等）下でのauthoring lease取得成功（既存authoring seam経由）。holder/controllerがlease seamに接触しないことの構造確認。instrumentation: flow表示中の材料編集経路（現行導線）の回帰 |
| EX-AC-03 | unit: `ExchangeFlowStateHolderTest`拡張（事前表示用session読取・再読取による確認要否更新・lifecycle遷移なしのfake clock TTL超過で不在〔失効時刻schedule再読取〕・置換確認経由の生成開始）+ 既存AC-13系test（承認なし生成不開始・辞退時不変・E1→E2→取消→E1 `EXPORT_MISMATCH`）のgreen。instrumentation: T-15事前表示（session存在時の表示・不在時の非表示）、破棄語彙の確認dialog |
| EX-AC-04 | unit: 要約要素の導出（対象項目数=session `itemRefs`サイズ、種別文言、上限文言。語彙に「対象項目数」相当が含まれること）+ `ExchangeDisclosureStateTest`回帰（同一性・cancel契約）。instrumentation: T-16要約主面・全文の折りたたみ/展開・D-09表示（T-15/T-16両面） |
| EX-AC-05 | unit/instrumentation: tier選択肢2個の観測、`LOCAL_FULL`文字列のUI不在（strings走査含む）、label付き警告copyがユーザー定義カテゴリ名を列挙すること（EN/ja双方のstring内容確認）、redacted/labels契約値の生成対応の回帰 |
| EX-AC-06 | spec 205 AC-3/AC-12対応test（確認前送信の不在・同一値受渡し・再生成時の確認やり直し）のgreen + transport 3経路の既存test回帰 |
| EX-AC-07 | unit: `ExchangeCapabilityCopyTest`拡張（新配置での4要素存在）。instrumentation: T-15面でのcapability説明表示・run-in entryの説明回帰 |
| EX-AC-08 | unit: pre-send破棄の確認受付→`invalidate`（当該sessionのみ）・辞退時の生存・`cancelling`後のtransport不受理（現行`ExchangeDisclosureStateTest`/`ExchangeFlowStateHolderTest`契約の継承）。instrumentation: 破棄ラベル・確認dialog・送信後「閉じる」の確認なし |
| EX-AC-09 | specs 205/327/204のdiff review（改訂箇所がScope節と一致し、gate構造・schema・validator・instruction契約が不変であること）+ `Issue348AiFacingContractSyncTest`・`ExchangePackageComposerTest`無編集green |
| EX-AC-10 | Compose semantics assertion（見出し・要約・展開state・dialog role・traversal順）+ focus restoration test + 200% font scale test + light/dark × ja/default screenshot evidence。新規stringの`values/`と`values-ja/`のname集合・placeholder一致の機械確認 + 削除stringのreference grep（0件）+ hardcoded literal grep |
| EX-AC-11 | unit: Back応答写像の全状態表駆動test（Close/RequestDiscard/Blocked/None）+ Back経由の破棄確認→当該sessionのみ`invalidate`・dismiss生存・送信済みclose生存 + blocking fake generation / `FileExchangeTransport` でbusy中のBack受付後もjobがcancelされずsession保存・transport settleが終端まで到達することの直接assert。instrumentation: T-15/T-16でのBack遷移とT-07復帰、Back起因の破棄確認dialog、busy中のBackで画面離脱しないことの観測 |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、exchange系instrumentation lane
（`ExchangeImportSurfaceInstrumentationTest`、`ExchangeImportSuccessInstrumentationTest`、
`StrategyPickerFreezeInstrumentationTest`のfreeze回帰、`ManualOrganizationPreferencesInstrumentationTest`）、
CI `final-status` green。本Issueは `risk: layout-data`/`risk: migration` を付けない
（表示・導線の再構成のみでpersistent state・DB書込み経路・同意gate構造に触れず、
session store契約は不変であるため、high-risk evidence gateの対象外）。

## Contract notes（owner reviewで確認すべき解釈）

旧note 3（pre-send破棄の確認dialog）・旧note 4（残時間の更新性）・旧note 6（idle/run-inでの
T-15共有）は、accepted TO-BE D-13/§9・§5.3および`CONTEXT.md`「中止語彙規約」で確定済みとして
2026-09-21のre-entryで未解決一覧から除去した（Phase1 review指摘4。旧4の更新規則は
Scope「active依頼の事前表示」の契約として固定済みである）。

1. **capability説明4要素の配置**: Issue本文scopeは「capability説明（spec 327 Decision 4）は
   T-07/T-15へ配置転換」と両面を挙げる。本specは「4要素の本体はT-15依頼作成面に置き、
   T-07の「AIに相談」選択肢には既存 `exchange_entry_subtitle` 相当の短い説明（1〜2行）を
   置く」と解する。理由: T-15は依頼作成の実行点であり、送信前にユーザーが必ず通る面である
   （4要素の初期配置がspec 327 AC-4で要求した「導線の説明領域」の実効性を保つ）。T-07に
   4要素の本体を置く（選択肢の下に常時展開する）解釈がowner reviewで選ばれた場合は、
   本specの該当行・EX-AC-07・spec 327改訂内容を修正する。
2. **回答を取り込む導線の配置**: 撤去されるentry rowが「依頼文を作成」と「回答を取り込む」の
   両方の入口を持つため、本specはT-15面に取り込み導線を維持する（T-17入力面への到達）。
   代替（T-07面上に「取り込み」を別選択肢として置く、またはT-15に置かず#373/#374まで
   一時的に導線を失う）は採らない。取り込みUI自体の再構成は#373であり、本specは到達性の
   維持のみを契約する。
3. **T-16要約の「対象項目数」「上限」の出所**: 対象項目数は生成済みpackageに紐づくsession
   `itemRefs` のサイズ（exported items数。`MOVABLE`/`CONDITIONAL`/`FIXED`の全exported refを
   含む）とし、語彙で「対象項目数」であることを明示する（全record数との誤読防止。
   Phase1 review指摘3）。category collectionのrecord数は表示しない（categoryの有無・
   名前の要否は種別文言が説明する）。表示中のlive状態から再計算しない（確認対象との
   同一性維持）。上限はspec 204 V1 content limits（items 512件・export canonical
   JSON 256 KiB）のユーザー向け表現とする（適用値ではなく契約上限の告知。適用値表示を
   要求する解釈がowner reviewで選ばれた場合は修正する）。文言は実装PRで確定する
   （非blocking）。

## Dependencies

- **#369（run面統合・T-07前置き面）**: merge済み（spec 369は`implemented`。実装PR #387、
  merge commit `7ec9e9d3fe`）。T-07前置き面はtransitional構成（spec 369 RD-1）であり、
  「AIに相談」選択肢行の新設を本Issueが所有する。idle start row freeze
  （`importAttemptActive`、spec 328）は「そのまま整理」CTAに継承済みで、本Issueでは
  「AIに相談」rowをfreeze対象にしない（現行entry rowと同一の扱い）。
- **#365（正本改訂）**: merge済み（PR #379）。`CONTEXT.md`に「依頼」「中止語彙規約」等が
  収録済みであり、本specはそれを参照する（複製しない）。
- **#368（strategy pickerの材料面移設）**: merge済み（PR #384）。exchange側strategy gate
  （`strategyWriteStartBlockedFor`/`strategyRestartSuppressedFor`/`strategyArbiterBusy`、
  `IMPORT_STRATEGY_BUSY`/`CTA_STRATEGY_BUSY`）は#368で削除済みであり、本specの契約範囲に
  strategy競合gateは含まれない（#368 spec「exchange逆参照の除去」のとおり）。
- **#370（onboarding提案・再表示hintのhub接続）**: merge済み（PR #389）。本改訂baseline
  `13c95eafe6` に含む（host変更の競合なし。`ManualOrganizationPreferences.kt` への
  #370適用済み形状をCurrent evidence〔plan.md〕で確認済み）。
- **後続**: #373（取り込みUI・失敗の手段別再投影。T-17/T-18。#372後）、#374（durable pending
  intent・status card依頼表示。T-15事前表示と置換確認copyの拡張（取り込み済み提案の破棄
  追記）は#374が契約化）、#375（scope mismatch原因別remedy・選択復元初期値）。
- **前提（実装済み・accepted）**: specs 204（accepted・implemented）/205（implemented）/
  327（implemented）/328（accepted・implemented）/329/330/331/332/348の現行契約、
  spec 123（UI収束・strings契約）、organization-run-ux §6（accessibility受入基準）、
  organizer-to-be-ux.md @ main `13c95eafe6`（accepted）、
  organizer-disposition-migration.md @ main `13c95eafe6`（accepted）。

## Open questions

実装開始前に解消が必須な問いはない（Contract notes 1〜3の解釈確認をowner reviewが所有し、
確認前は本specは`draft`のままである）。非blocking事項:

1. T-15/T-16の見出し・事前表示・期待明示・要約・破棄確認dialogの最終文言（D-13/§10語彙に
   沿うことのみ拘束）は実装PRで確定する。
2. 既存 `exchange_*` stringsの「交換」語彙の全面改名（依頼語彙への統一）の範囲は実装PRで
   確定する（新規・改訂copyは依頼語彙を正とする）。
3. 統合により未使用になるstringの最終集合は実装PRのreference grepで確定する。
4. 全文展開の操作形式（展開row/リンク等）と、T-15事前表示・置換確認・期待明示の面上配置
   （縦順）は200% reflowとTalkBack読み順を満たす範囲で実装PRのreviewで確定する
   （spec 123収束対象）。

## Change history

- 2026-09-19: Draft created for #372（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `a2b6aba318`、PR #364）、accepted処分文書
  （organizer-disposition-migration.md @ main `a2b6aba318`、PR #378、§3.11/§3.12/§4.1/§5/§7.2）、
  #366〜#371 spec/plan draft（`bf00f96175`/`a50f074ac2`/`206a4c1198`/`3c39ceb2f8`/
  `168d1587`/`0564184b`、うち#369が直接依存）、現行実装調査
  （`ExchangeFlowUi.kt`、`ExchangeFlowController.kt`、`ExchangeGenerationGate.kt`、
  `ManualOrganizationPreferences.kt`、`ContextExportModels.kt`、`ContextExportBuilder.kt`、
  exchange系unit/instrumentation test群、`strings.xml`/`values-ja/strings.xml`）を入力に作成。
- 2026-09-21: Re-entry revision（[Phase1 review 1回目](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5740061847)
  = Changes requested（中3/低1）の全指摘対応）。(1) T-15/T-16のsystem Back契約
  （host常時composed handler・4区分）をScope・scenario・EX-AC-11/oracleへ追加（指摘1）。
  (2) 残時間表示の再読取規則（T-15進入・ON_RESUME）とTTL跨ぎのclock-controlled oracleを
  固定し、単一読取の規定を置換（指摘2）。(3) label付きtierの開示警告をcontext v4の
  自由文集合（ユーザー定義カテゴリ名を含む）へ一致させ、T-16要約の件数を「対象項目数」
  語彙へ明示（指摘3）。(4) baselineをmain `13c95eafe6` へ再固定し、#365/#368/#369/#370の
  merge状態と#368によるexchange側strategy gate削除を前提へ反映、旧Contract notes
  3/6（および更新規則を契約化した旧4）を未解決一覧から除去（指摘4）。
- 2026-09-21: Re-entry revision round 2（[Phase1 review 2回目](https://github.com/nunu1733/NunuLauncher/issues/372#issuecomment-5752821376)
  = Changes requested（中2）の全指摘対応）。(1) busy state（`Generating`・transport
  in-flight・`cancelling`）のBackを「既定経路へ委ねる」から「handlerが取り込み画面離脱を
  起こさない」へ変更（holderのoperationはhostの`rememberCoroutineScope()`で実行されるため、
  画面離脱はscope cancelでoperationを中断させ「operationは継続」契約と矛盾するため。
  EX-AC-11にblocking fake generation / `FileExchangeTransport`での直接assertを追加）。
  (2) TTL跨ぎ表示を「失効時刻に1回scheduleした再読取」で固定し、lifecycle遷移なしで
  fake clockをTTL超過まで進める直接oracleをEX-AC-03へ追加。

## References

- [Issue #372](https://github.com/nunu1733/NunuLauncher/issues/372)
- [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)（accepted。D-04/D-09/D-10/D-13/D-14/D-17、T-07/T-15/T-16、§5.3、§6.5、§7.1、§8.2、§9、§10）
- [organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)（accepted。§3.11/§3.12/§4.1/§5 更新順序 #6/§7.2 (c)）
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md)（implemented。entry規定・送信前確認・置換確認・pre-send cancel・transport・session契約の所有。本Issueが表示面とentry導線を改訂）
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md)（accepted・implemented。tier matrix・content limits・session契約の所有。本IssueがD-14文言を改訂）
- [Spec 327: interview-first](../327-agent-exchange-interview-first/spec.md)（implemented。capability説明4要素とDecision 4配置の所有。本Issueが配置前提を改訂）
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md)（implemented。run-in entry・選択凍結・scope gate）
- [Spec 328: import success state](../328-exchange-import-success-state/spec.md)（accepted・implemented。attempt anchor・success state・freeze。rev.2は#374）
- [Spec 332: import input UI](../332-exchange-import-input-ui/spec.md)（implemented。T-17入力面）
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md)（ja/en strings契約）
- [organization-run-ux.md](../../docs/product/organization-run-ux.md)（§6 accessibility受入基準）
- [CONTEXT.md](../../CONTEXT.md)（外部エージェント交換・送信前確認等の既存用語）, [DESIGN.md](../../DESIGN.md)（gate 13）, [AGENTS.md](../../AGENTS.md)
- 先行Issue: [Issue #369][2]（T-07前置き面。`implemented`。RD-1 transitional構成）、
  [Issue #366][4]（hub shell）、[Issue #365][3]（正本改訂。merge済み）

[1]: https://github.com/nunu1733/NunuLauncher/issues/372
[2]: https://github.com/nunu1733/NunuLauncher/issues/369
[3]: https://github.com/nunu1733/NunuLauncher/issues/365
[4]: https://github.com/nunu1733/NunuLauncher/issues/366
