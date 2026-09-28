---
issue: "#449"
status: draft
requirements:
  - FR-019
  - NFR-013
  - NFR-014
  - D-014
updated: 2026-09-28
---

# 複数選択の視覚的編集画面（選択への一括アクションと確定時の一括適用）

> Risk tier: H — 現行workflowの階層H条件のうち「Launcher DBへの書込み（layout適用）」と「recovery store（recovery pointの作成を伴う適用）」に当たる（メモ§4.7。Issue #449 Risk areas「Layout data or recovery」チェックどおり）。新しい書込み経路・上流model/loader bridgeは作らない（適用は既存のorganizer application module、入口はfork側拡張点）。手順は現行どおり: accepted spec + plan.md、Execution and approval contract、`risk: layout-data` labelによる高リスク独立エビデンス契約。
> Epic: #439（再焦点化）。出典: 再焦点化方針メモ（2026-09-24に承認、Revision 5。以下「メモ§x」）。操作面の方式の正本は [ADR-0014](../../docs/adr/0014-edit-surface.md)（#447で起草、Proposed Revision 2）。
> **受入の前提（Issue本文どおり）**: 本specを `accepted` に進めることは ADR-0014 の受入が前提であり、ADR-0014の受入は #442 の最終結論が前提である（メモ§4.3、§5、ADR-0014 Decision末尾）。受入前に本specは `draft` のまま維持し、ADR-0014がAccepted化した時点でそのrevisionを取り込んで本spec/planとの整合を再照合したうえで受入手続きへ進める。ADR-0014側に実質変更が入った場合は本spec/planを先に同期する。
> 書込み経路の分担: 項目単位の即時編集（ADR-0013契約）は #448（実装済み）が所有する。本specの一括確定はADR-0013の対象外であり、organizerの安全な適用経路（recovery point付き）で現行の安全規約（`AGENTS.md`「ホームレイアウトを扱う安全規約」）に従う（メモ§4.3、ADR-0013 Decision冒頭）。
> ベンチマークの正本: [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441で確定。baseline B2=48 / B3=21 / B4=25、目標はNow-2全体で B2≤24 / B3≤10 / B4≤12。合否は #448+#449 のうち適した方で判定する）

## Problem

ホーム画面の複数のアイテムをまとめて移動・フォルダ化・削除する手段が存在しない。baseline（Lawnchair 15 beta 3相当）では1項目につき長押ししてdragし、必要ならページをまたいで運ぶしかなく、別々のページにある数個のアプリをまとめる操作は重い（メモ§2）。#448で項目単位のpopup編集アクション（ADR-0013経路の即時書込み）は届いたが、複数アイテムへの一括操作と、確定前まで無書込みで試せる操作面はまだない。上流に `EDIT_MODE` 状態と入口は存在するが選択UIの実装はなく（`src/com/android/launcher3/states/EditModeState.kt:18`、`src/com/android/launcher3/views/OptionsPopupView.java:211-215`）、`MULTI_SELECT_EDIT_MODE` flagは既定DISABLEDで選択UIを持たない（`src/com/android/launcher3/config/FeatureFlags.java:171-172`）。ADR-0014は複数選択の操作面として、上流workspaceに触れないfork側の視覚的編集画面（案B）を推奨・決定した（D-014）。

## Outcome

利用者が編集画面（現在のホームレイアウトを図で表示したfork側の全画面UI）で複数のアイテムを選択し、4つの編集アクション（ページへ移動、既存フォルダへ入れる、選択から新しいフォルダを作る、ホームから外す。FR-019）を1操作で選択全体に適用できる。アクションの結果はまず図へ即座に反映され（NFR-013）、ホームDBへの書込みは確定時まで発生しない。確定で選択全体が1回の適用としてまとめて反映され、1個の復元点が作られる。図には各アイテムの実際の位置・ラベル・iconが表示される。

## Scope

- **編集画面（fork側 `app.lawnchair.homeedit`。メモ§4.8。ADR-0014案B）**:
  - **図**: 読み取り専用capture（organizer application moduleの既存capture経路。書込み・lifecycle遷移・diagnostics発行を行わない読み取り）で取得したcapture時点のレイアウトを、全workspaceページを1面に並べた縮小図で描画する。各アイテムの実際の位置・ラベル・iconを表示する。widgetは占有footprint、フォルダはフォルダiconとして表示する。dock行とQSB等の予約領域は表示するが選択できない。
  - **icon前提（ADR-0014 Revision 2修正の反映。本specの責務として決定する）**: capture済みレイアウトから直接得られるのは placement・identity（`TargetKey`）・optional title・optionalなcustom iconバイト列であり、通常アプリの表示iconは `TargetKey.AppKey` とprofileからの `IconCache` / `LauncherIcons` 解決で得る。解決できない場合（unavailable / quiet / private spaceのprofile、解決失敗）は、汎用のplaceholder iconを表示し、ラベルはoptional title（無い場合は既定の無題表示）とする。fallback表示のアイテムも選択・アクションの対象とする（外す・移動はicon解決に依存しない）。
  - **snapshot表示の明示**: 図がcapture時点のsnapshotであること（編集中にホームが変わっても図は自動更新しないこと）を画面に常時示す。
  - **選択**: tap選択（複数）、選択数の表示、選択解除。選択対象は #448 と同じ絞り込み（workspace配置の `ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT` かつidあり）に加え、ロック中でないことを要求する。widget、フォルダ自身、app pair、dock上のアイテム、ロック中のアイテムは選択不可で、ロック中であることをtap時に示す。根拠: 確定時の適用は現行安全規約の対象であり、「ロック配置が変化していない」条件を満たす必要がある（単一アイテムの移動は #448 経路が担う）。
  - **編集セッションと4アクション**: 開いてから確定またはキャンセルで終わるまでが1回の編集セッションである。アクションは選択全体へ1操作で適用され、まずセッション計画（図）に反映される。セッション計画の構築は #448 の純粋計画関数（`HomeEditPlanner`）を共有し、図の視覚順に決定的に行う。
    - **「ページへ移動…」**: 既存ページの選択dialog → 選択全体をそのページの空きセルへ移動する（1個ずつ純粋計画関数で決定的配置）。いずれかのアイテムに空きがない場合は選択全体を移動せず、typedな理由（空きがない旨）を表示する（部分適用しない）。
    - **「フォルダへ入れる…」**: 既存フォルダ（同じprofileのフォルダに限る）の選択dialog → 選択全体を選択の視覚順にrank末尾へ追加する。選択に複数のprofileが混在する場合はtypedな理由を表示して全体を適用しない。
    - **「新しいフォルダを作る」**: 選択の視覚順で最初のアイテムの元セルに1x1の新規フォルダを作り、選択全体を選択順にrank 0から追加する。先頭セルは選択全アイテムが計画上そこから除かれた後に空くため、必ず置ける。この置き先契約を保証するため、共有module（#448の純粋計画module）へ「指定セルへの新規フォルダ作成」のintent variantを追加する（additive拡張であり、#448のpopup経路が使う既存intentと振る舞いは変更しない。本Issueで計画の振る舞いを再実装しない方針は変わらない）。
    - **「ホームから外す」**: 選択全体を図から除く（favorites行削除の計画）。アンインストール・フォルダ中身の暗黙削除をしない。
  - **セッション計画の規則**: アクションを実行したアイテムは選択から外れ、セッション計画に固定される（同じアイテムへの複数回のアクションはしない。取り消しはセッションのリセットのみ）。選択解除は以後のアクション対象から外すのみで、実行済みのアクションには影響しない。セッション計画が空の間は確定できない。
  - **セッションのリセットとキャンセル**: リセットは全セッション変更を破棄して開始時の図へ戻す（無書込み）。画面を閉じる（キャンセル）は無書込みで終了する。
  - **確定と一括適用**: 確定 = 1回の適用 = 1個の復元点（recovery point）。organizerの安全な適用経路を再利用する（`LauncherLayoutAdapter` の `captureCurrent`:94 / `prepareApplyWriteSet`:180 / `applyWriteSet`:293 / `requestCorrelatedReload`:428、既存 `ApplyProtocol`。メモ§4.3）。適用計画のsourceStateはセッション開始時のcaptureであり、確定時の再captureとのrevision・状態一致検証が適用経路の内部で行われる（`ApplyProtocol.kt:113` / `:118`）。適用が変えるのは選択アイテムの行（移動・フォルダ追加・削除）と、それに伴う新規フォルダ行のみであり、他の行・`organizerLockState` 列・dock・予約領域は不変である。AGENTS.md安全規約の各条件（同一revision、全配置アイテムが保持・移動・明示的削除のいずれかに説明可能、ロック配置不変、座標・参照有効、適用前のrecovery point、transactionとしての成功または変更前への復帰、適用後の再読込と不変条件の再検証）は、既存の適用経路の契約（spec 13、spec 152）によって満たされる。
  - **stale時（ずれ検出。メモ§4.3）**: 確定時の再captureでrevisionまたは状態が一致しない場合、適用経路は零書込みでtyped拒否する。編集画面はセッション（選択とアクション結果）を破棄し、理由を表示したうえで最新のホームで編集画面を開き直す（第1版。編集内容の載せ直しはNon-goals）。
  - **復元点の扱い（ADR-0014 Decision）**: 1セッション = 1適用 = 1復元点。activeな未確定復元点が3つある等により `RECOVERY_POINT_ADMISSION_BLOCKED`（`ApplyProtocol.kt:199`。#166の受付制限はPR #183で解消済みのため正常系で起きない）が観測された場合は、書かずにその理由を示して再試行を促す。このときセッションは保持する（理由が解消した後に再確定できる）。
  - **organizer runとの排他**: 適用はorganizer application moduleの既存のlease（`LayoutWriteCoordinator` のORGANIZER。`src/com/android/launcher3/model/LayoutWriteCoordinator.java:53-58`）を経由するため、organizer runの適用と同時に書かれない。organizer run適用中に確定した場合は書かず、その旨のtyped理由を示して再試行を促す（セッション保持）。逆に編集画面の適用中はorganizer runの適用は開始できない（既存の排他機構とmodule mutex）。
  - **適用結果ごとの観測可能な契約（既存 `ApplyResult` の各variantに対応）**:
    - **確定前のtyped拒否（pre-writeの `Rejected`。`STALE_REVISION` / `EXACT_PRECONDITION_FAILED` / `INVALID_PLAN` / `RECOVERY_POINT_ADMISSION_BLOCKED` / `RECOVERY_STORE_UNAVAILABLE` / `WRITER_BUSY` / `ConcurrentRun` / `LOCK_STATE_UNAVAILABLE` 等）**: 零書込みである。理由の表示と、拒否理由ごとの次の操作（staleは開き直し、blocked・busyは再試行の促し）は上記の各節のとおり。
    - **`RolledBack`**: transaction rollback後のpre-stateである（無変更）。理由を表示し、ホームが変化していないことを示す。
    - **`Recovered`**: 自動復旧完了後のpre-stateである（無変更）。理由を表示し、ホームが変化していないことを示す。
    - **`Unresolved` / `RecoveryFailed`**: `authoritativeState` が示す状態に従う。pre-stateの確認ができない限り「ホームは変化していない」と断定せず、状態不明または復旧未完了としてfail-closedに扱う。理由と、復旧への導線（アプリ内の復元導線。適用と復旧の契約の正本は spec 13）を表示する。
  - **ロック状態が確認できない行（`OrganizerLockState.UNKNOWN`）の扱い**: 適用経路は、capture内に1行でもUNKNOWNがあるとapply全体を `LOCK_STATE_UNAVAILABLE` で拒否する既存契約である（`ApplyProtocol.kt:557-558`。ADR-0004の移行では既存行がUNKNOWNに設定される）。編集画面はこれに合わせ、(1) UNKNOWN行は図上で「ロック状態が確認できない」ことを示したうえで選択不可とし、(2) セッション開始時のcapture内にUNKNOWN行が1つでも存在する間は確定を無効化してその理由を示す（零書込み。UNKNOWN行が正規化されるまで確定できない）。確定前に無効化することで、確定時に必ず失敗する試行を発生させない。
  - **図描画の分離**: 図の描画（capture→図投影→描画）は、Nextのorganizer図previewとの共通化を見越し、homeedit内で分離可能な構成で書く（抽出自体はNext。メモ§4.5）。
- **入口（メモ§4.3。ADR-0014 Decision）**:
  - workspace空きスペース長押しメニューへ「編集画面」項目を追加する（fork側 `LauncherOptionsPopup.kt` のoption listへの追加分のみ。上流ファイル変更を想定しない。発生した場合はNFR-010の記録対象）。ホーム画面ロック（`lockHomeScreen`）有効時は既存のedit_mode / widgetsと同様に項目を出さない。
  - Organizer hubに「編集画面」rowを追加する。
  - 入口コスト会計の固定（ADR-0014 Revision 2の入口手順に基づく）: 主経路（長押しメニュー直行）= workspace空きスペース長押し2 + 項目tap 1 = **3**。hub経由 = workspace空きスペース長押し2 + 「ホームを整理」tap 1 + hub row tap 1 = **4**（記録のみ。ベンチマークの合否会計は主経路で行う）。

## Non-goals

- ワークスペース上での複数選択（案A。ADR-0014で不採用。将来の再評価を排除するものではない）。
- 図上でのdragによる移動（第1版はtap選択+アクションボタン。メモ§4.3で確定済み。図上dragの是非は第1版実装後のベンチマーク結果を待ってspecで判断する）。
- Dock / ウィジェットの複数選択（Next。メモ§4.5）。
- 「新しいページ」の作成（#448と同じく非対象。B2は既存ページで達成可能。メモNext）。
- 編集アクションの振る舞いの新規実装（#448の純粋計画関数を共有する。本Issueで再実装しない）。
- Undoの実装（#450が所有する。適用後の取り消しは#450の経路を使う。ベンチマークB5は対象外）。セッション内の操作履歴・複数段取り消しも作らない（第1版の誤操作回収はリセットとキャンセル）。
- organizerのplanner、提案生成、AI交換（凍結。#443）への変更。
- 図previewとorganizer previewの本格的な共通化（Next。本Issueは描画の分離可能な構成のみ）。
- stale時の編集内容の載せ直し（Next。メモ§4.3）。
- homeeditの即時書込み経路（#448、ADR-0013契約）の変更。

## Domain language

- **編集画面 (Edit Surface)**: 現在のホームレイアウトをcapture時点の図で表示し、複数選択、選択への編集アクション、確定時の一括適用を提供するfork側の全画面UI（ADR-0014案B）。_Avoid_: EDIT_MODE（上流のworkspace状態。案Aで不採用）、整理run面（organizer runの確認・結果面と混同）
- **編集セッション (Edit Session)**: 編集画面を開いてから確定またはキャンセルで終わるまでの、1回の複数選択編集の試行。選択と、適用待ちのアクション結果（セッション計画）を持ち、process内でのみ存在する。確定 = 1回の適用 = 1個の復元点。_Avoid_: 整理run（organizerのsnapshot取得〜適用〜検証の一連の試行。別の粒度）、依頼（AI相談の語）
- **セッション計画 (Session Plan)**: 編集セッション内でアクション実行の結果として図に反映されている、まだ適用されていない変更の集まり。#448の純粋計画関数の結果から構成される純dataであり、確定時にcaptureと照合される。_Avoid_: レイアウトplan（organizerのplan artifact。適用の正本と混同）、Undo記録（#450が所有する情報）

（承認時に `CONTEXT.md` へ反映する）

## Behavior scenarios

### Scenario: 長押しメニューから編集画面を開く

Given ホーム画面ロックが無効で、workspaceにページ0〜2がある
When 利用者がworkspace空きスペースを長押しし「編集画面」を選ぶ
Then 編集画面が全画面で開き、全ページの図が表示される
And 各アイテムは実際の位置・ラベル・iconで表示され、widgetはfootprint、フォルダはフォルダiconで表示される
And 図がcapture時点のsnapshotであることが画面に示されている
And dockと予約領域は表示されるが選択できない

### Scenario: 複数選択と選択数の表示

Given 編集画面が開いている
When 利用者がページ0のアプリAとページ2のアプリBをtapし、続けてAを再度tapする
Then 1回目のtapでAとBが選択され選択数が「2」と表示される
And 2回目のtapでAの選択が解除され選択数が「1」になる

### Scenario: 選択できないアイテム

Given 図にwidget W、フォルダF、dock上のアプリD、ロック中のアプリLがある
When 利用者がW / F / D / Lをtapする
Then いずれも選択状態にならず、Lについてはロック中であることが示される

### Scenario: 「ページへ移動…」で選択全体を動かす

Given アプリA（ページ0）とB（ページ2）が選択され、ページ1に空きがある
When 利用者が「ページへ移動…」→ ページ1を選ぶ
Then 図の上でAとBがページ1の空きセルへ移動した表示に切り替わり（セッション計画の反映）、ホームDBは変化しない
And 確定すると1回の適用でAとBのfavorites行がページ1へ移り、1個の復元点が作られる
And 選択していないアイテムの行、ロック列、dock、予約領域は変化しない

### Scenario: 「ページへ移動…」で空きが足りない

Given 選択3個に対して選んだページの空きが2個分しかない
When 利用者が「ページへ移動…」→ そのページを選ぶ
Then セッション計画は変化せず（3個とも移動しない）、図も変化しない
And 空きが足りない旨のtypedな理由が表示される

### Scenario: 「フォルダへ入れる…」で既存フォルダに入れる

Given アプリAとB（同profile）が選択され、同じprofileのフォルダFがある
When 利用者が「フォルダへ入れる…」→ Fを選ぶ
Then 図の上でAとBがページから消えFに含まれた表示になり、確定時に選択順のrankでFへ追加される（1回の適用、1個の復元点）
And 選択に異なるprofileが混在する場合、このアクションはtypedな理由の表示で全体が適用されない

### Scenario: 「新しいフォルダを作る」

Given ページ1のアプリA、ページ2のアプリB、ページ2のアプリCがこの視覚順で選択されている
When 利用者が「新しいフォルダを作る」を選ぶ
Then 図の上でAの元セルに新規フォルダが置かれ、A・B・Cがその中に入った表示になる
And 確定すると1回の適用で、Aの元セルに1x1のフォルダ行がINSERTされ、A・B・Cが選択順（A=0、B=1、C=2）でそのフォルダへ入る
And 新規フォルダの作成と子の移動は1つのtransactionとして成功するか、変更前へ戻る

### Scenario: 「ホームから外す」

Given アプリA、B、Cが選択されている
When 利用者が「ホームから外す」を選ぶ
Then 図の上でA・B・Cが除かれた表示になり、確定時に3行が1回の適用で削除される
And アンインストールはせず、他のアイテム（フォルダ中身を含む）は一切変化しない

### Scenario: 確定時のstale（ずれ検出）

Given 編集セッションのcaptureの後、ユーザーがホームへ戻って手動drag等でレイアウトを変えた
When 利用者が編集画面へ戻り確定する
Then 適用経路の再captureでrevision・状態の一致検証が失敗し、favorites DBは一切変化しない
And セッション（選択とアクション結果）は破棄され、ずれがあった旨の理由が表示される
And 理由確認後、最新のホームをcaptureした図で編集画面が開き直される

### Scenario: 復元点の受付が止まっているときの確定

Given activeな未確定復元点が3つある（process死等の異常時）
When 利用者が確定する
Then favorites DBは変化せず、復元点の受付が止まっている旨の理由が表示され、再試行が促される
And セッションは保持され、理由が解消した後に再確定できる

### Scenario: organizer runの適用中の確定

Given organizer runの適用（ORGANIZER lease）が実行中である
When 利用者が編集画面で確定する
Then favorites DBは変化せず、整理中のため書けない旨のtypedな理由が表示され、再試行が促される
And セッションは保持される。逆に編集画面の適用中はorganizer runの適用は開始できない

### Scenario: 適用の途中失敗でpre-stateへ戻る

Given 確定後の適用でwrite setの書込みが失敗する（test注入）
Then 1 transaction全体がrollbackし、favorites DBとmodelは適用前のままになる（復元点のrecordの扱いは既存の適用プロトコル契約に従う）
And `RolledBack` として失敗がtypedに表示され、ホームが変化していないことが示される

### Scenario: 復旧が完了しない適用失敗をfail-closedに扱う

Given 適用の書込み失敗後の自動復旧が完了しない（test注入。`Unresolved` または `RecoveryFailed`）
Then 編集画面は「ホームは変化していない」と断定しない
And 状態不明または復旧未完了であることがfail-closedに表示され、復旧への導線が示される

### Scenario: ロック状態が確認できない行がある場合の確定

Given セッション開始時のcaptureに `OrganizerLockState.UNKNOWN` の行が1つある（ADR-0004移行由来の行など）
When 利用者が選択とアクションを行い確定しようとする
Then 確定は無効で、ロック状態が確認できない行があるため確定できない旨が示される
And favorites DBは変化しない。UNKNOWN行は「ロック状態が確認できない」表示で選択もできない

### Scenario: リセットとキャンセルは無書込み

Given アクションを実行したセッションがある
When 利用者がリセットする／画面を閉じる（キャンセル）
Then セッション計画は開始時の図へ戻る／編集画面が閉じられ、いずれもfavorites DBは変化しない

### Scenario: iconが解決できないアイテムの表示と操作

Given work profileがlocked（quiet mode）で、そのprofileのアプリPがworkspaceに置かれている
When 編集画面を開く
Then Pは汎用のplaceholder iconと既知のラベル（titleが無ければ既定表示）で表示される
And Pは選択でき、「ホームから外す」等のアクションが機能する

### Scenario: 適用後の編集画面の再表示

Given 確定による適用が成功した
When 編集画面を再度開く
Then 図は適用後の最新ホームのcaptureから描画される

## Data and state

- 読むdata: organizerの読み取り専用capture（`LayoutState`。pages / items / placement / `TargetKey` / optional title / optional custom icon bytes / lockState / profile availability。既存capture経路）、device profileの格子寸法、icon解決（`TargetKey.AppKey` + profile → 上流の `IconCache` / `LauncherIcons`。既存経路）。新規の権威を作らない。
- 書くdata: organizer適用経路のwrite setを経由する `favorites` 行のupdate（移動・フォルダ追加）、INSERT（新規フォルダ行）+ UPDATE（子の移動）、DELETE（外す）のみ。schema変更・migrationなし（書く行は上流と同じ標準構造）。`organizerLockState` 列は書かない（ロック中アイテムは選択不可のため対象にならない）。dock・予約領域は不変。
- 一時的なstate: 編集セッション（選択とセッション計画）はprocess内で、編集画面の生存期間のみ存在し、永続化しない。process死・画面離脱で消失する（確定まで無書込みのため復旧不要。適用済みの復旧はrecovery pointの既存契約に従う）。

## Permissions, privacy, and security

- None。新規permission、外部通信、sensitive dataの追加はない。title/iconは端末上の図表示のみに使う。diagnostics / exportへの投影禁止はorganizer-diagnostics契約に従う（`specs/194-plan-preview-seam/spec.md` の先例）。icon解決は端末内の既存経路のみを使う。

## Accessibility and localization

- 図グリッドの各アイテムはTalkBackで読めるラベル（title、位置、選択状態。選択不可の場合はその理由）を持つ。tap選択・選択解除・4アクション・確定・キャンセル・リセット・理由表示が支援技術で読めて操作できる。
- touch target、font scaling、contrastは既存のfork UI慣行（`organizer_lock_*`、#448の文字列）に従う。新規文字列は `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` へ追加する。
- 上流のaccessibility経由の編集（`LauncherAccessibilityDelegate` のMOVE / REMOVE / MOVE_TO_WORKSPACE）とkeyboard shortcutは変更しない。編集画面はそれらの補完であり、単一アイテムのmodel書込み（#448、ADR-0013経路）と編集画面の一括適用（本spec、安全規約経路）はどちらも不変条件を保つ。

## Acceptance criteria

- [ ] AC-1: 入口2経路（workspace長押しメニュー、Organizer hub row）から編集画面が開く。`lockHomeScreen` 有効時は長押しメニューの項目を出さない。エミュレータのスクリーンショットで構造を確認し、実機での表示・操作をownerが確認する。
- [ ] AC-2: 図がcapture時点のホームを表示する（全ページ、実際の位置・ラベル・icon、widget footprint、フォルダicon、dockと予約領域の非選択表示）。icon解決不能時はplaceholderで表示され、選択・アクションが機能する。snapshot表示の明示がある。
- [ ] AC-3: tap選択（複数）、選択数表示、選択解除が動作する。対象外（widget、フォルダ自身、dock上のアイテム、app pair、ロック中）は選択できず、ロック中はその旨が示される。
- [ ] AC-4: 4アクションがセッション計画と図へ即座に反映される（決定的）。ページ移動は選択全体の一括移動（空き不足時は全体不適用+typed理由）、フォルダ追加は選択順rank末尾（profile混在はtyped理由）、新規フォルダは先頭アイテムの元セルに1x1+選択順rank、外すは削除計画。アクション実行済みアイテムは再操作対象にならず、リセットでのみ戻る。セッション計画が空の間は確定できない。
- [ ] AC-5: 確定時の適用がorganizerの安全な適用経路で行われる: 適用前の再captureとrevision・状態一致検証（不一致時は零書込み）、recovery point 1個の作成、1 transaction（全成功か変更前へ戻る）、相関reload、適用後の不変条件再検証。適用が変えるのは選択アイテムとそれに伴う新規フォルダ行のみで、他の行・lock列・dock・予約領域は不変である（AGENTS.md安全規約の各条件）。テストで確認する。
- [ ] AC-6: stale時に零書込みでセッションが破棄され、理由表示と最新ホームでの開き直しが動作する（Scenarioどおり）。
- [ ] AC-7: 確定前のtyped拒否（`RECOVERY_POINT_ADMISSION_BLOCKED` 観測時、organizer run適用中のbusy、`LOCK_STATE_UNAVAILABLE` を含む）で、零書込み・理由表示・再試行の促し・セッション保持が動作する。capture内に `OrganizerLockState.UNKNOWN` の行が存在する場合、確定が無効化され理由が示され、UNKNOWN行は選択できない（テストで確認）。
- [ ] AC-8: リセットとキャンセルが零書込みである（テストで確認）。
- [ ] AC-9: セッション計画の構築（#448の純粋計画関数を共有する部分を含む）がinterface経由でテストされている（fixture、境界値、typed拒否理由、決定性、冪等性。AGENTS.mdテスト規約）。
- [ ] AC-10: 編集セッションから構築した適用計画に対する適用のテストが、実DB（test DB）で行われている（選択行のみの変化、新規フォルダのINSERT+子UPDATEの1 transaction性、失敗注入でのrollback、stale、排他、process死後の整合）。
- [ ] AC-11: NFR-013: 選択・選択解除・アクション実行の図への反映が即時である（目標100ms以内。セッション計画は純粋計算）。確定から適用完了（ホームへの反映）までの目標は3秒以内とし、エミュレータ計測値をPRに記録する（実機計測はowner確認に含める）。排他中・blocked中の表示（Scenarioどおり）を含む。
- [ ] AC-12: ベンチマーク: 会計は [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §6の確定値と重みで行う。編集画面経由の会計は B2 = 入口3 + 選択tap 5 + アクション1 + 対象ページ1 + 確定1 = **11**（≤24）、B3 = 入口3 + 選択tap 4 + アクション1 + 確定1 = **9**（≤10）、B4 = 入口3 + 選択tap 6 + アクション1 + 確定1 = **11**（≤12）である（ADR-0014 Revision 2のB4=10は確定操作を含まない算出であり、確定1を含む本会計でも目標判定は変わらない）。hub経由入口=4を記録する。fixture（§5）を使うエミュレータでの実行記録（§7の手順に準拠）をPRに残し、B5は#450の経路に依存するため記録のみ（対象外）とする。
- [ ] AC-13: patch surface: PR上で `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` を実行し、結果をPR本文に記録する。入口追加はfork側ファイル（`LauncherOptionsPopup.kt`）への追加分であり、src/側の変更が発生した場合はNFR-010としてPRで記録し、bridge ownerを明示する。
- [ ] AC-14: 文書: specが `implemented` になり、`DESIGN.md`（homeedit moduleの記述へ編集画面と一括適用の追加）、`CONTEXT.md`（domain language 3語）、[requirements.md](../../docs/product/requirements.md)（FR-019 / NFR-013のstatus。FR-018は#448分を含めて実装mergeを根拠に更新）が更新される。
- [ ] AC-15: アクセシビリティ: 図アイテムのラベル・選択状態・選択不可の理由、4アクション、確定・キャンセル・リセット、理由表示がリソース由来かつ空でない文字列から供給されることの自動検証に加え、エミュレータTalkBackでの読み上げ確認を記録し、実機確認をowner確認に含める。
- [ ] AC-16: 適用結果の観測契約が既存 `ApplyResult` の各variantに対応する: 確定前の `Rejected` は零書込み、`RolledBack` と `Recovered` はpre-state（無変更）の表示、`Unresolved` と `RecoveryFailed` は `authoritativeState` に従いpre-stateを確認できない限り「無変更」と断定しないfail-closed表示（復旧導線の表示を含む）。テストで確認する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 / AC-2 / AC-3 | エミュレータスクリーンショット（入口2経路、図、選択、対象外非選択、ロック注記）+ owner実機確認。図投影と選択対象述語のJVM test |
| AC-4 | セッション計画構築のJVM test（一括移動・空き不足・フォルダ・新規フォルダ・外す・決定性・冪等性・typed拒否）+ エミュレータ操作記録 |
| AC-5 | instrumentation（適用経路の既存seam）: 編集セッション→適用計画→applyの統合test（選択行のみ変化、recovery point 1個、相関reload、1 transaction、選択外の行不変）。既存の適用プロトコル自体の契約テスト（rollback・失敗注入・検証）は既存test群が所有し、本Issueでは回帰確認のみ |
| AC-6 / AC-7 / AC-8 | instrumentation: 適用前にDBを変えて零書込みと開き直し（stale）、lease保持中の零書込みと理由（排他・blocked）、リセット・キャンセルの零書込み、UNKNOWN行存在時の確定無効化と選択不可 |
| AC-16 | instrumentation: 失敗注入での `RolledBack`（pre-state表示）と `Unresolved` / `RecoveryFailed`（fail-closed表示。既存プロトコルの自動復旧testとの接続を確認） |
| AC-9 | `tests/unit/app/lawnchair/homeedit/` のJVM test群。`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'`（CI: `organizer-unit-tests`。#448でfilter追加済み） |
| AC-10 | instrumentation（既存laneへの追加。新laneは作らない）: 上記AC-5/6/7/8の実framework・実DB test群 |
| AC-11 | エミュレータでの操作計測（選択反映・確定→適用完了。PR本文）+ owner実機確認 |
| AC-12 | ベンチマーク§5のfixture + §7の手順に準拠したエミュレータ実行記録（B2/B3/B4の編集画面経由会計、hub経由=4。PR本文） |
| AC-13 | `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の出力（PR本文） |
| AC-14 | `validate_repo_contract.py` 成功 + diff確認（DESIGN.md / CONTEXT.md / requirements.md / spec status） |
| AC-15 | UI構築のJVM test（文言がリソース由来かつ空でない）+ エミュレータTalkBackでの読み上げ記録 + owner実機確認 |

## Open questions

- なし（承認時点で解決済み）。図の構成（全ページ1面の縮小表示。B2〜B4の会計が入口3+確定1で成立する根拠）、新規フォルダの置き先（選択の視覚順で最初のアイテムの元セル。1x1。共有moduleへの指定セル作成variantの追加で保証する）、空き不足時は全体不適用+typed理由（部分適用しない）、profile混在選択へのフォルダ系アクションは全体不適用、アクション実行済みアイテムは再操作対象外（リセットのみで戻る）、ロック中（`LOCKED`）アイテムは選択不可（現行安全規約の「ロック配置不変」を適用経路の入力側で保証）、`UNKNOWN` 行は選択不可+確定無効化（既存 `LOCK_STATE_UNAVAILABLE` 契約との一致）、「新しいページ」非対象（#448と同じくNext）、dock/widget/フォルダ自身/app pair非選択、stale時は破棄+開き直し（メモ§4.3確定）、blocked/busy時はセッション保持+再試行、適用結果は `ApplyResult` のvariantごとの観測契約に従う（pre-write拒否は零書込み、`RolledBack` / `Recovered` はpre-state、`Unresolved` / `RecoveryFailed` はfail-closed）、NFR-013の数値（反映≤100ms、適用完了≤3秒目標。エミュレータ計測）、入口コスト会計（主経路3、hub経由4）、は本specで決定した。

## Change history

- 2026-09-28: Draft created for #449（Phase 1）。出典: Issue #449本文と同コメント（ADR-0014 Revision 2に伴う前提同期。icon前提と入口コストの修正を反映）+ 承認済み再焦点化方針メモ（Revision 5）§4.3/§4.5/§4.8/§4.9 + ADR-0014（Proposed Revision 2）+ ADR-0013（#445受入）+ ベンチマーク正本（#441確定）+ #448実装済みmodule（PR #472、main `f35ff4494f`）。
- 2026-09-28: Revision 2 — Phase 1 review round 1（[判定](https://github.com/nunu1733/NunuLauncher/issues/449#issuecomment-5862216096): Request changes）の指摘に対応。指摘1（受入前提）: 冒頭へ「本specの受入はADR-0014の受入が前提。受入まで `draft` を維持し、Accepted化時にrevisionを取り込んで再照合する」を明記（外部前提は #442 の結論待ち。本revisionでは対応の明示のみ）。指摘2: 適用結果を `ApplyResult` のvariantごとの観測契約へ分離し（pre-write拒否=零書込み、`RolledBack` / `Recovered`=pre-state、`Unresolved` / `RecoveryFailed`=`authoritativeState` に従うfail-closed）、Scenario・AC-16・test oracleへ同期（旧「すべて無変更」の記述を撤回）。指摘3: 新規フォルダの置き先契約（先頭アイテムの元セル）を保証するため、共有moduleへの「指定セルへの新規フォルダ作成」intent variant追加（additive）をScopeへ明記（詳細はplan）。指摘4: `OrganizerLockState.UNKNOWN` の扱いを既存 `LOCK_STATE_UNAVAILABLE` 契約（`ApplyProtocol.kt:557-558`）と一致させ、UNKNOWN行は選択不可+確定無効化とし、Scenario・AC-7・test oracleへ同期。
