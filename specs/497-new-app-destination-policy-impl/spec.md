---
issue: "#497"
status: draft
requirements:
  - FR-008
  - NFR-014
  - NFR-010
  - D-015
  - D-013
updated: 2026-10-02
---

# 新規アプリの配置先ポリシーの実装（ADR-0015、B1/B6）

> Risk tier: H — Launcher DB（`favorites`）への新しい書込み経路（指定フォルダへの行追加。ADR-0013契約4どおりの最小の`ModelWriter`操作追加）と、上流のmodel経路（`ItemInstallQueue` / `AddWorkspaceItemsTask`）へのbridgeを作るため。`ModelWriter.java`は高リスクpath一覧内であるため、本実装PRは `risk: layout-data` 対象であり、独立auditと`final-status`を要求する（[docs/project/github-workflow.md](../../docs/project/github-workflow.md) 高リスクPRへの独立エビデンス要求）。
> Epic: #439（再焦点化）。判断の正本: [ADR-0015](../../docs/adr/0015-new-app-destination-policy.md)（accepted、#446）。本specはADR-0015のDecisionを参照して書かれ、判断を重複定義しない。書込みの安全条件は [ADR-0013](../../docs/adr/0013-direct-edit-write-contract.md)（accepted、#445）へ委譲する。
> ベンチマークの正本: [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441確定。B1 baseline 8操作 / B6 baseline 84操作（操作数32）。B1の目標は同書§6で確定済み。B6の目標は本specで確定する）

## Problem

ADR-0015（accepted、#446 / PR #470）で「上流の既定 / 指定した1つのフォルダ / 追加しない」の配置先ポリシー契約と境界条件は確定したが、その実装が存在しない。利用者が指定フォルダへの配置を望んでいても、新規installのアイコンは上流既定（空きセル。`SessionCommitReceiver` → `ItemInstallQueue` → `AddWorkspaceItemsTask` → `WorkspaceItemSpaceFinder`）に置かれ、新規installのたびにホームが散らかる。編集負担ベンチマークのB1（baseline 8操作 / 3操作数）とB6（baseline 84 / 操作数32）が未改善のままであり、Epic #439 終了条件2の唯一の未達項目である。

## Outcome

配置先ポリシーで「指定した1つのフォルダ」を選んだ状態では、新規アプリのアイコンが利用者の追加操作なしで指定フォルダ内（末尾rank）に置かれる（B1=追加操作0）。promise iconの段階から同じ配置先を使い、install完了後の差し替えで配置は動かない。指定フォルダが使えない場合（削除・別profile・Dock・配置制約違反）は上流の既定（空きセル）へ戻り、その理由がFileLogに記録され、設定の行で一度だけ知らされる（ADR-0015 Decision 4どおり）。ポリシーは「上流の既定 / 指定フォルダ / 追加しない」の3択で、「ホームにアイコンを追加」設定の近傍から選べる。書込みはADR-0013契約4どおり、admissionの内側で検証と変更が完結する最小の`ModelWriter`操作で行う。

## Scope

- **配置先ポリシーmodule（`app.lawnchair.homeedit`配下。ADR-0015 Decision 12どおりorganizerとは別の直接編集と同じ側のmodule。本Issueが新設）**:
  - **純粋計画関数**: 入力は「現状態の投影（snapshot。フォルダの存在・profile・container（Dock判定）・子の件数、格子寸法とscreen順）」「対象アイテムのidentity（user、package/intent）」「ポリシースナップショット（queue投入時にcaptureしたpolicy選択、指定folder id、user、package。ADR-0015 Decision 10）」。出力はclosed result（`FolderTarget(folderId, rank)` / `UpstreamDefault(reason)` / `Reject(reason)`。ADR-0015 Decision 8どおり）。指定フォルダの存在・profile分離・Dock・配置制約と末尾rankを検証する。**既定配置の座標計算は純粋計画関数の対象外**であり、上流の`WorkspaceItemSpaceFinder`の意味論をadmission内で用いる（書込み構造の項参照）。UI・DB・model状態に触れない。
  - **ポリシースナップショットのcapture・永続化・読み出し**: captureは自動追加のqueue投入（enqueue）時に1回、queue永続化とともに保持し、flush時は読むだけ（current policyから再生成しない）。保持方法（queue fileへの追加attribute、別の永続file等）はplanが決める。
  - **fallback理由の記録と通知**: fallback時にtypedな理由をFileLogへ記録する（package名は出力に含めない。organizer-diagnostics §7のNever分類準拠）。設定の行で一度だけ表示する通知state（表示後に消費される）を持つ。記録方法は446 spec Open question 5の委譲を受け、本specでFileLog + one-shot通知stateへ確定する。
- **bridge（追加経路の1箇所。ADR-0015 Decision 9）**: 自動追加経路（`SessionCommitReceiver` → `ItemInstallQueue`の`queueItem(packageName, user)` overload、およびpromise iconの`InstallSessionHelper`経路）への接続。enqueue時のsnapshot capture、flush時の読み出し、決定の運搬、書込み分岐。`PackageUpdatedTask.java`（AOSP由来）には分岐を追加しない。手動配置（`AddItemActivity`経由の`queueItem(ShortcutInfo)` / widget overload）は対象外とし、captureも行わない。
- **新規の最小`ModelWriter`操作（ADR-0013契約4。同経路に追加する）**: 指定フォルダへの新規行INSERTを、admissionの内側で「stage-2再検証（同一純粋計画関数の現状態再実行）→ ID採番 → model/DB変更」が完結する構造で行う。既存の`addItemToDatabase`がadmission前に`updateItemInfoProps`・ID採番・bindItems callbackを実行する構造（ADR-0015 Decision 7が踏まないと定めた`ModelWriter.java`の構造）は使わない。stage-2で`UpstreamDefault(reason)`へ再計画した場合、**既定配置の計算は上流の`WorkspaceItemSpaceFinder`の意味論をそのままadmission内で用いる**（top QSB有効時の1ページ目除外、既存screenが全て満杯の場合の新規screen割当を含む。`WorkspaceItemSpaceFinder.java:69-86`の走査）であり、fork側で既定配置の走査を複製しない。新規screen idの採番（`getNewScreenId`）とscreen集合（`workspaceScreens` / `addedWorkspaceScreensFinal`相当）への反映もadmission内に限り、admission成立前に漏らさない。既定配置のbounds/containerを同じadmission内で検証してから書く。`organizerLockState`列は書かない。**新op routeのUI bind（`bindAppsAdded`相当）と新規screenのLauncher UIへの伝播は、書込み成功のcallbackが既定配置（screen/cell）と（採番された場合の）新規screen idを運び、`AddWorkspaceItemsTask`側で書込み成功後に1回だけ行う**（既定routeのstock経路は変更しない。folder routeはfolder iconのrefresh）。admission成立前にUI bind・folder refreshを発生させない（defer中の先行bindを防ぐ）。
- **設定UI**: 既存の「ホームにアイコンを追加」設定（`HomeScreenPreferences`）の近傍に、3択のポリシー行を追加する。独立したtoggleは新設しない（ADR-0015 Decision 15）。フォルダ選択はdialog（既存のAlertDialog慣行。#448のフォルダ選択dialogと同型）で行う。
- **テスト**: ADR-0015要求テスト表の3行（admission後の再計画、policy snapshotの再flush一貫性、snapshot欠損・破損時のclosed result）とADR-0013要求テスト表の該当行を、**ADRの層割付けどおりのcanonical surface（Layout Application interface相当の書込み経路test。`AndroidJUnit4`で実行しproduction DB adapterの代替としてtest DBを用いる書込み経路harness。organizer shared-writer lane）で満たす**（ADR本文の層の記述は本PRでこの実績surfaceへ明確化する。Change history参照）。純粋JVM test（純粋planner、snapshot分類、通知state、設定整合）は補助として残す。coordinator排他・process死はADR-0013の割付け（shared-writer instrumentation seam、process-death smokeの慣行）で満たす。新規CI lane・重複scenarioは作らない。テスト新設は[test-audit skill](../../.agents/skills/test-audit/SKILL.md)を適用して審査する。
- **ベンチマーク**: B6の目標を本specで確定（下記のとおり追加操作0）。B1/B6の実装後の再計測（決定的会計の再算出と目標照合。#449 AC-12と同じ方式）を記録する。
- **文書**: FR-008を`implemented`へ更新（本実装mergeを根拠）、ベンチマーク§6のB6目標セルを本specの確定値へ更新、`DESIGN.md`（module構成とgate 7行への実装spec link）、`CONTEXT.md`（domain language）を同じPRで更新する。NFR-010としてpatch surfaceの計測・記録を同一PRで行う。

## Non-goals

- 追加するかどうかの判定の変更（install reason、`pref_add_icon_to_home` の意味、重複除外。上流のまま。ADR-0015 Decision 1）。
- カテゴリの一致するフォルダへの配置、指定ページへの配置（seed-backlog order 8 / 5。ADR-0015 Decision 3のNext）。
- 既存アイテムを動かす増分整理提案（FR-009、Laterのまま。ADR-0005の適用範囲）。
- organizerのplanner/application/recovery protocolの変更、organizer runとの同時実行の許可（MODEL_WRITER admissionの既存defer機構に従う。ADR-0013契約4）。
- 新規アプリ配置のUndo（Undo対象外。ADR-0015 Decision 14、ADR-0013対象(c)。置き場所を変えたい場合は#448/FR-018の編集アクションを使う）。
- `PackageUpdatedTask.java`への分岐追加（Deckの失敗の再現を避ける。ADR-0015 Decision 9）。
- 新規CI laneの作成、schema/migration/recovery store/backup契約の変更（書く行は上流と同じ標準構造）。
- 複数フォルダ指定、profile別フォルダ指定（指定は1フォルダのみ。ADR-0015 Decision 3）。
- フォルダの自動作成（指定できるのは既存フォルダのみ。作成は#448の「新しいフォルダ」等の利用者操作で行う）。

## Domain language

- **ポリシースナップショット (Policy Snapshot)**: 自動追加1件のqueue投入時にcaptureされ、queueとともに永続化される、配置先決定の不変入力（policy選択、指定folder id、user、package）。flush時は読むだけであり、current policyから再生成しない。欠損・破損時もcurrent policyを再読しない（ADR-0015 Decision 10）。_Avoid_: layout snapshot（organizer runの入力。別の粒度）、編集snapshot（単一編集操作の投影。別の経路）
- **指定フォルダ (Designated Folder)**: 配置先ポリシーで利用者が選んだ1つのフォルダ。アイテムidで保持し、名前では保持しない（ADR-0015 Decision 5）。_Avoid_: 分類フォルダ（カテゴリ一致の自動分類は別の将来機能）、整理先フォルダ（organizerのplan artifactが作るフォルダ）
- **フォールバック (Fallback)**: 指定フォルダが観測可能な制約違反により使えず、上流の既定（空きセル）へ戻ること。typedな理由（`FOLDER_MISSING` / `PROFILE_MISMATCH` / `DOCK_FOLDER` / `CONSTRAINT_VIOLATION` / `SNAPSHOT_INVALID`）を持ち、記録と設定の行での一度だけの通知の対象になる。_Avoid_: 拒否（Reject。書込み経路のinvariant failureであり配置の例外ではない）、満杯（fallback条件としない。ADR-0015 Decision 4）

（承認時に `CONTEXT.md` へ反映する）

## Prior art

- Smart LauncherのSmart Folders（5.4+。カテゴリ自動分類に基づき新規アプリを含めて自動追加する）: https://docs.smartlauncher.net/products/faq/changelog/5.4（確認日 2026-10-02）。カテゴリ一致による自動追加はADR-0015 AlternativesでRejected（分類品質が不十分、意図しないフォルダ成長）のため採用しない。本実装は利用者が明示指定した1フォルダに限定する点が異なる。
- Microsoft LauncherのIntune管理構成（管理者がフォルダ・ピン配置をリモート構成する）: https://learn.microsoft.com/en-us/intune/app-management/configuration/configure-launcher-android（確認日 2026-10-02）。「構成された配置先へ自動配置する」形状の先例だが、構成チャネル（enterprise admin policy）が異なるため不採用。本実装の構成は利用者自身の3択設定であり、対象commitの実装（ADR-0015契約）に従う。
- 上流Lawnchair/Launcher3に同機能の設定は存在しない（`SessionCommitReceiver` → `ItemInstallQueue` → `AddWorkspaceItemsTask`は常に空きセルへ置く。ADR-0015 Contextの実測）。既存architectureとの整合を優先し、bridgeを`ItemInstallQueue`→`AddWorkspaceItemsTask`経路の1箇所に限定する（ADR-0015 Decision 9）。

## Behavior scenarios

### Scenario: 指定フォルダへの自動配置（B1）

Given 配置先ポリシーで個人profileのフォルダFが指定されている。`pref_add_icon_to_home`は有効
When 個人profileのアプリを新規にinstallする（install reason USER）
Then アイコンは利用者の追加操作なしでF内の末尾rankに置かれる（B1=追加操作0）
And 新規アプリのfavorites行は1回のINSERTで書かれ、既存の子のrankは変化せず、`organizerLockState`列は書かれない
And Fのbadgeと開いた中身に新しいアイコンが即座に反映される

### Scenario: promise iconの段階から同じ配置先

Given 配置先ポリシーでフォルダFが指定されている
When install完了前にpromise iconが自動追加される（`FLAG_AUTOINSTALL_ICON`）
Then promise iconがF内に置かれる
And install完了後、上流の`PackageUpdatedTask.OP_ADD`がその場で本物のアイコンへ差し替え、配置は動かない

### Scenario: 指定フォルダが削除された場合のフォールバック

Given 配置先ポリシーでフォルダF（id 42）が指定されている
When Fが削除された後に新規アプリがinstallされる
Then アイコンは上流の既定（空きセル）へ置かれる
And 理由`FOLDER_MISSING`がFileLogに記録され（package名は含まない）、設定のポリシー行に一度だけ表示される
And 同名のフォルダを再作成しても指定は復活しない（idで保持するため）。設定行は有効な指定がない状態を示し、利用者はいつでも再選択できる

### Scenario: 指定フォルダが別profileの場合のフォールバック

Given 配置先ポリシーで個人profileのフォルダFが指定されている
When work profileのアプリを新規にinstallする
Then アイコンは上流の既定へ置かれ（work profileアプリが個人側フォルダに入らない。profile分離）、理由`PROFILE_MISMATCH`が記録され、設定の行に一度だけ表示される

### Scenario: 指定フォルダがDockに移された場合のフォールバック

Given 配置先ポリシーでフォルダFが指定されている
When FがDock（hotseat）へ移された後に新規アプリがinstallされる
Then アイコンは上流の既定へ置かれ、理由`DOCK_FOLDER`が記録され、設定の行に一度だけ表示される

### Scenario: 再flush決定性（ポリシースナップショット）

Given フォルダFを指定した状態で新規アプリAの自動追加がqueueに投入された（snapshot=folder:F）
When flushの前にポリシーを「上流の既定」へ変更し、processが死に、再起動後のflushでqueueが消化される
Then AはsnapshotどおりF内へ置かれる（current policyを再読しない）
And process死の間にポリシーやフォルダ指定が変わっても、同じinstallの結果は変わらない

### Scenario: ポリシースナップショットの欠損・破損

Given 永続化されたqueue entryの基底identity（itemType、user、intent）はdecodeできるが、ポリシースナップショット部分が欠損またはdecode不能である（旧format・破損）
When flushがqueueを消化する
Then current policyを再読せず、`UpstreamDefault(SNAPSHOT_INVALID)`で上流の既定へ置き、記録と設定の行での一度だけの通知が行われる
And ポリシースナップショット部分がdecodeできても、そのidentity（user、package）が基底entryと一致しない（ペアリング破損）場合は、無変更で`Reject(SNAPSHOT_INVALID)`としてtypedに記録される
And 基底entry自体がdecode不能な場合の挙動は上流の既存のまま（entryの読み飛ばし）であり、本契約の対象外である

### Scenario: 同一installの重複enqueue

Given 同一package・同一userの自動追加が既にqueueへ投入され、snapshotが永続化されている
When flushの前に同じpackage・userの自動追加が再投入される
Then queueの重複排除は既存のまま動き（2entryにはならず）、**最初に永続化したsnapshotが保持される**（first enqueue wins。再captureで置き換わらない）
And flush時の結果は最初のsnapshotに対して決定的である

### Scenario: admission内での再計画（defer後のstale）

Given ORGANIZER lease（organizer run適用中）が保持されている間に新規アプリAのflushが行われた
When 書込みがMODEL_WRITER admissionでFIFOへdeferされ、lease解放後にadmissionが成立する
Then admissionの内側で同一の純粋計画関数が現状態へ再実行される
And 指定フォルダがlease中に削除・移動・別profile化されていた場合、`UpstreamDefault(reason)`という有効planへ再計画され、既定配置は上流と同じ走査（top QSB時の1ページ目除外・既存全満杯時の新規screen割当）で同じadmission内に決定・検証され、1 transactionで書かれる
And admission成立より前に`ItemInfo`の変更・ID採番・bindItems callback・DB書込み・新規screen id採番・UI bind（`bindAppsAdded`相当）・folder refreshのいずれも発生しない
And 書込み成功後に限り、Aのアイコンと（採番された場合の）新規screenが1回の`bindAppsAdded`相当でLauncher UIへ反映される

### Scenario: 既存screenが全て満杯の場合のfallback（上流等価の新規screen）

Given 指定フォルダが使えない状態（削除済み等）で、既存の全workspace screenに新規アプリAの1x1セルが空いていない
When Aの自動追加がflushされる
Then admission内で`UpstreamDefault(reason)`へ再計画され、上流と同じ走査で新規screenが採番され、Aはそのscreenへ置かれる
And 新規screen idとAが同じ書込み成功後のpost-admission bindへ渡され、reloadなしで新しいpageとアイコンが表示される

### Scenario: 「追加しない」を選ぶ

Given 利用者がポリシー行で「追加しない」を選ぶ
Then 既存の`pref_add_icon_to_home`がOFFになり、上流どおり自動追加が行われなくなる
And ポリシー実装に独立した抑制経路・二重判定は新設されない

### Scenario: 設定の3択と既存スイッチの整合

Given ポリシー行（上流の既定 / 指定フォルダ / 追加しない）と「ホームにアイコンを追加」スイッチが同じ画面にある
When 「追加しない」を選ぶ、またはスイッチをOFFにする
Then 両者の表示は常に整合する（追加しない ⇔ スイッチOFF）
And スイッチをONへ戻すと、フォルダ指定が残っている場合はフォルダ配置へ戻る。ホーム画面ロック中はポリシー行もスイッチと同様に無効である

### Scenario: 既定ポリシーでの挙動はbaselineと変わらない

Given 配置先ポリシーが「上流の既定」（初期値）である
When 新規アプリがinstallされる
Then アイコンは`WorkspaceItemSpaceFinder`による空きセルへ置かれ（QSB予約を除く走査。上流のまま）、記録・通知・新規書込み経路は発生しない

### Scenario: 手動配置は対象外

Given 利用者が`AddItemActivity`（ドロワーからの配置、widget追加等）でアイテムを置こうとしている
When 配置が確定する
Then 配置先ポリシーは一切適用されず（snapshotのcaptureも行われない）、上流経路のまま動く

### Scenario: ロック済みフォルダへの追加（ADR-0004との整合）

Given 指定フォルダFの親cellが`LOCKED`である
When 新規アプリがF内へ追加される
Then 追加は行われ、既存の子のcaptured container/rankは不変である（末尾rank追加。親ロックは既存子のrank保護であり新規子の追加禁止ではない。ADR-0015 Decision 6）

## Data and state

- 読むdata: `favorites`の現状態（admission内の`BgDataModel`投影。フォルダの存在・profile・container・子の件数、格子寸法、screen順。新規の権威を作らない）、device profileの格子、配置先ポリシーのpref、（通知表示時の）one-shot通知state。
- 書くdata: `favorites`行のINSERT 1行（新規アプリ。container=指定folder id + 末尾rank、または`CONTAINER_DESKTOP` + screen/cell。上流と同じ標準構造）。schema変更・migrationなし。`organizerLockState`列は書かない。既存行は書き換えない。
- 永続化するdata: (1) 配置先ポリシーのpref（policy選択 + 指定folder id。既定は「上流の既定」）、(2) ポリシースナップショット（queue永続化とともに。retentionはqueueと同じで、flushでqueueが消化されるとともに消える。同一installの重複enqueueでは最初に永続化したsnapshotが保持される（first enqueue wins）。保持方法はplanが決める）、(3) one-shot通知state（prefs。表示後に消費）、(4) FileLogのfallback記録（log。package名なし）。
- migration、backup/restore、rollbackへの影響: schema/migrationなし。queue file formatの拡張（採用時）は旧formatの読み込み互換を保ち、snapshot無しのentryは`UpstreamDefault(SNAPSHOT_INVALID)`へフォールバックする。backup契約への変更なし。rollbackはPR revert。
- layoutを扱う場合の対象集合: 書くのは自動追加の新規アプリ1アイテムのみ。既存アイテム・widget・フォルダ行は書き換えない。対象のitem typeは自動追加が生成する`ITEM_TYPE_APPLICATION`（promise iconを含む）。`AddItemActivity`経由の手動配置は対象外。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataの追加はない。書込み先は端末内のLauncher DBのみ。fallback理由の記録にpackage名を含めない（organizer-diagnostics §7のNever分類準拠）。記録に出すのはtypedな理由コードと件数の性質を持つ情報のみである。

## Accessibility and localization

- ポリシー行（3択）、フォルダ選択dialog（選択肢・profile注記・「指定をやめる」）、fallback通知の文言はすべて文字列リソース由来（`values` + `values-ja`）で、TalkBackで読める。font scalingで崩れない。
- フォルダ選択dialogは#448の「フォルダへ入れる…」dialogと同じAlertDialog慣行に従い、既存のアクセシビリティ検証（リソース由来・空でない文言のJVM test + エミュレータTalkBack確認）と同型の検証を行う。
- 上流のaccessibilityアクション・keyboard shortcutは変更しない。

## Acceptance criteria

- [ ] AC-1: 設定の「ホームにアイコンを追加」の近傍に3択のポリシー行が存在し、「上流の既定 / 指定フォルダ / 追加しない」が動作する。「追加しない」は既存の`pref_add_icon_to_home`のOFFと同じ結果であり、独立した抑制経路・二重判定を新設しない。3択と既存スイッチの表示は常に整合し、ホーム画面ロック中はポリシー行も無効である。フォルダ選択dialogで、Dockにない既存フォルダ（profile注記つき）から1つ選べる。エミュレータのスクリーンショットで構造を確認し、実機での表示・操作をownerが確認する。
- [ ] AC-2: 指定フォルダ選択時、自動追加の新規アプリ（promise icon段階を含む）が追加操作0で指定フォルダ内の末尾rankに置かれる。既存子のrankと`organizerLockState`列は不変、書込みは1回のINSERTである。実機でB1の挙動を確認し、会計（追加操作0）をPRに記録する。
- [ ] AC-3: フォールバック（削除・別profile・Dock・配置制約違反）で上流の既定へ戻り、typedな理由がFileLogへ記録され（package名なし）、設定の行に一度だけ表示される。同名フォルダ再作成で指定は復活しない。既定ポリシー時は記録・通知・新規書込み経路が発生しない。
- [ ] AC-4: 再flush決定性: snapshot=Aでqueue投入 → policy変更 → process再起動 → flush → snapshot Aが使用される（current policyを再読しない）。同一installの重複enqueue後も、最初に永続化したsnapshotが保持される（first enqueue wins。queueの重複排除の意味論は変更しない）。snapshot部分の欠損・decode不能時（基底entryのidentityが読める場合）もcurrent policyを再読しない（`UpstreamDefault(SNAPSHOT_INVALID)`で既定配置）。snapshot部分がdecodeできても基底entryとidentity（user、package）が一致しない場合は無変更で`Reject(SNAPSHOT_INVALID)`のtyped failure。基底entry自体のdecode不能は上流の既存挙動（読み飛ばし）のまま本契約の対象外である。
- [ ] AC-5: 書込み構造がADR-0013契約4どおりである。(a) admission成立より前に`ItemInfo`の変更・ID採番・bindItems callback・DB書込み・新規screen id採番・UI bindが発生しない、(b) admission後に同一の純粋計画関数を現状態へ再実行する、(c) 指定folderがstaleなら`UpstreamDefault(reason)`という有効planへ再計画され、既定配置は上流`WorkspaceItemSpaceFinder`と同じ意味論（top QSB時の1ページ目除外・既存全満杯時の新規screen割当。fork側の走査複製を作らない）で同じadmission内に決定・検証されてから1 transactionで書く。新規screen id採番・screen集合への反映もadmission前に漏らさない、(d) default側も成立しない真のinvariant failureだけが`Reject`（無変更・typed failure）である、(e) 新op routeのUI bind（`bindAppsAdded`相当）と新規screenの伝播は書込み成功後のcallbackが運ぶ最終配置・新規screen idに基づき、`AddWorkspaceItemsTask`側で1回だけ行われる（defer中はbind・folder refreshが発生しない。既定routeのstock経路は変更しない）。ORGANIZER lease中のdeferでも同様であり、単一行INSERTでatomicである（失敗の握りつぶしなし）。
- [ ] AC-6: bridgeが自動追加経路の1箇所に限定され、`PackageUpdatedTask.java`への分岐追加がない。手動配置（`AddItemActivity`経由）は対象外であり、snapshotのcaptureも行われない。
- [ ] AC-7: 純粋計画関数がinterface経由でテストされている（fixture、境界値、typed拒否理由、決定性、冪等性。AGENTS.mdテスト規約）。ADR-0015要求テスト表の3行（admission後の再計画、policy snapshotの再flush一貫性、snapshot欠損・破損時のclosed result）の**canonical ownerは、ADRの層割付け（本PRで明確化済み。ADR-0015/ADR-0013要求テスト表）どおりのLayout Application interface相当の書込み経路test surface（`AndroidJUnit4` + test DBの書込み経路harness。organizer shared-writer lane。#448/#450がADR-0013該当行を満たした実績と同じ。spec 448 AC-7）である**。純粋JVM test（純粋planner、snapshot分類、通知state、設定整合）は補助として残す。coordinator排他・process死はADR-0013が元へ割り付けているsurfaceで満たす。新規CI lane・重複scenarioは増やさない。既定配置の上流意味論との等価性（QSB時の1ページ目除外、満杯時の新規screen割当）とpost-admission bind契約（defer中はbind無し・成功後に1回）を同じcanonical surfaceで固定する。テスト新設時のtest-audit適用を記録する。
- [ ] AC-8: ベンチマーク: **B6の目標を「配置先を設定した後の追加操作0（10個の合計。重み付きコスト0）」と確定する**（B1の改善の10個分の合計。§6の重み表で算出）。B1/B6の実装後の会計を決定的再算出で記録し（baseline B1=8操作/3操作数、B6=84/32 からの改善）、ベンチマーク§7準拠のエミュレータ実行記録（fixture seeding + install実証）をPRに残す。実機確認はowner確認に含める。
- [ ] AC-9: patch surface: PR上で `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` を実行し、結果をPR本文に記録する。src/側の増分（bridge。`ItemInstallQueue`、`AddWorkspaceItemsTask`、`ModelWriter`、契約型等）をNFR-010として理由つきで記録する。`validate_writer_inventory.py` がPASSすること（新規のDB書込みfileを作らない場合、allowlist変更は不要であることを確認する）。
- [ ] AC-10: 文書: `docs/product/requirements.md` のFR-008を`implemented`へ更新する（本実装mergeを根拠。実績はAC-2〜AC-5のevidence）。ベンチマーク§6のB6目標セルを本specの確定値へ更新する（Change historyに1行追加）。ADR-0015とADR-0013の要求テスト表の層の記述を、実績surface（`AndroidJUnit4` + test DBの書込み経路harness）へ明確化する（Phase 1で実施済み。検証内容の変更なし。Phase 2ではDiffが不変であることの確認のみ）。`DESIGN.md`のmodule構成へ配置先ポリシーmoduleを追加し、§11 gate 7行に本specへのlinkを補う。`CONTEXT.md`へdomain language 3語を反映する。
- [ ] AC-11: アクセシビリティ: ポリシー行、フォルダ選択dialog、fallback通知の文言が文字列リソース由来（ja含む）でTalkBack読み上げ可能である。dialog構築のJVM test（文言がリソース由来かつ空でない）+ エミュレータTalkBackでの読み上げ確認を記録し、実機確認をowner確認に含める。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | エミュレータスクリーンショット（ポリシー行・dialog・整合表示）+ owner実機確認。設定構築のJVM test（3択の状態遷移と整合） |
| AC-2 | 書込み経路harness test（AndroidJUnit4 + test DB。INSERT 1行・既存子rank不変・lock列不変）+ エミュレータ実行記録 + owner実機確認（B1会計） |
| AC-3 | 純粋計画関数のJVM test（typed理由）+ FileLog記録とone-shot通知stateのJVM test + 書込み経路harness（fallback経路の既定書込み。test DB使用） |
| AC-4 | snapshot分類（欠損/破損/不一致）とfirst-winsの純粋JVM test（補助）+ 書込み経路harnessのcanonical test（queue永続化を含む再flush一貫性。persist→再構築→flush。ADR-0015要求テスト表2行目） |
| AC-5 | 書込み経路harnessのcanonical test（defer後の再計画・既定配置の上流意味論等価性・admission前無変更・post-admission bind契約。ADR-0015要求テスト表1行目。test DB使用）+ shared-writer instrumentation（coordinator排他・process死。ADR-0013の割付けどおり）+ 純粋JVM test（stage-2 validatorの決定意味論。補助） |
| AC-6 | PR diffのreview（bridge範囲の確認）+ JVM/instrumentation test（手動配置経路がcaptureしないこと） |
| AC-7 | `tests/unit/app/lawnchair/homeedit/` のJVM test群（`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'`。補助）+ 書込み経路harness・instrumentation test群（canonical）。test-audit適用の記録（PR本文） |
| AC-8 | ベンチマーク§7のagent手順によるエミュレータ実行記録（fixture seeding + install実証。B1/B6会計。PR本文） |
| AC-9 | `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の出力（PR本文、NFR-010記録）+ `validate_writer_inventory.py` PASS |
| AC-10 | `validate_repo_contract.py` 成功 + diff確認（requirements.md / ベンチマーク§6 / DESIGN.md / CONTEXT.md） |
| AC-11 | dialog構築のJVM test（リソース由来・空でない文言）+ エミュレータTalkBack読み上げ記録 + owner実機確認 |

## Open questions

なし（accepted時点で解決済み）。解決内容:

1. **指定フォルダ満杯時の扱い**: ADR-0015 Decision 4どおり、満杯をfallback条件としない。上流folderにハードな上限はなくページングで拡張するため、追加は常に末尾rankとして成立する。fallback条件は観測可能な制約違反（存在しない・別profile・Dock・配置制約違反）のみ。
2. **同名フォルダ再作成時の再指定のUX**: 指定はidで保持されるため復活しない（ADR-0015 Decision 5）。フォルダが見つからない間、設定行は有効な指定がない状態を示し、fallback理由の通知は一度だけ。再選択はいつでもフォルダ選択dialogから行える。
3. **promise icon再flush時の決定性**: ポリシースナップショット（ADR-0015 Decision 10。queue投入時にcapture、flush時は読むだけ）により保証する。
4. **「追加しない」と`SessionCommitReceiver.isEnabled`との二重判定の排除**: 「追加しない」は既存の`pref_add_icon_to_home`のOFFそのものであり、ポリシー実装に抑制分岐を新設しない。ポリシー選択UIが既存prefを設定する。上流の`isEnabled`判定が単一の正である。
5. **fallback理由の記録方法**（446 spec Open question 5の委譲を受け本specで確定）: FileLogへのtyped理由コードの記録（package名なし。organizer-diagnostics §7 Never準拠）+ 設定行のone-shot通知state（prefsにpendingを保持し、表示時に消費）。organizer-diagnosticsのrun journal（`RunEvent`のclosed集合）は使わない（scopeがorganization run / recovery操作に限定されるため）。
6. **ロック状態の検証入力**（446 planの調査事項への回答）: 追加は末尾rankであり既存子のcaptured rankを変えないため、ロック状態を読み取り検証の入力にする必要がない（ADR-0015 Decision 6の制約は書込み形状で満たす）。`organizerLockState`列は書かない。organizerのlock capture経路への依存も発生しない（ADR-0015 Decision 12のmodule独立を維持）。
7. **B6の目標値**（本Issueのspecで確定すべき項）: 「配置先を設定した後の追加操作0」。10個の新規アプリすべてが追加操作0で指定フォルダ内に置かれるため、重み付きコスト・操作数ともに0である（B1の改善の10個分の合計。baseline B6=84/操作数32との差分で改善を記録する）。
8. **snapshot重複captureの扱い**（Phase 1 review round 1で確定）: first enqueue wins。`addToQueue`の重複排除（`mItems.contains`がtrueなら追加も`mStorage.write`もスキップ。`ItemInstallQueue.java:113-120`）の意味論を変更せず、最初に永続化したsnapshotが保持される。last-winsは重複排除の意味論変更を伴うため採用しない。
9. **`Reject(SNAPSHOT_INVALID)`の観測可能な条件**（Phase 1 review round 1で確定）: 基底entry自体がdecode不能な場合は上流の既存の読み飛ばしのまま対象外とする（`PersistedItemArray.read`はentry単位のcatchで読み飛ばす。raw-entry transportの新設はbridge過大）。`Reject(SNAPSHOT_INVALID)`は「snapshot部分はdecodeできるが、そのidentity（user、package）が基底entryと一致しない（ペアリング破損）」の場合に限る。欠損・decode不能（基底identity読める）は`UpstreamDefault(SNAPSHOT_INVALID)`へ明示fallbackする。
10. **既定配置の計算の所有**（Phase 1 review round 1で確定）: 純粋計画関数のclosed resultは`UpstreamDefault(reason)`まで（座標を含まない）。既定配置の座標計算は上流`WorkspaceItemSpaceFinder`の意味論（`WorkspaceItemSpaceFinder.java:69-71`のQSB時1ページ目除外、`:77-86`の満杯時新規screen割当）をadmission内で用い、fork側の走査複製を作らない。新規screen id採番・screen集合への反映はadmission内に限る（ADR-0013契約4のadmission前無変更）。
11. **post-admission bindの所有**（Phase 1 review round 2で確定）: 新op routeのUI bind（`bindAppsAdded`相当）と新規screenの伝播は、書込み成功callbackが運ぶ最終配置・新規screen idに基づき`AddWorkspaceItemsTask`側で1回だけ行う。既存の`addedItemsFinal`への事前追加（upstream経路のbind方法。`AddWorkspaceItemsTask.java:200`相当）は新op routeでは使わない（defer時にadmission成立前のbindが起こるため）。既定routeのstock経路・folder routeのfolder icon refreshはそれぞれ既存どおりである。
12. **ADR要求テスト行のcanonical owner**（Phase 1 review round 2・3で確定）: ADR-0015要求テスト表3行のcanonical ownerは、ADRの層割付けと同一のsurfaceである。ADR本文の「Layout Application interface相当のJVM test（test DB使用）」の実現surface（`AndroidJUnit4` + test DBの書込み経路harness。organizer shared-writer lane。#448/#450実績）への記述明確化を、本PRでADR-0015/ADR-0013の要求テスト表とChange historyへ先に行う（実装specでADRの割付けを読み替えない。検証内容・行の対応の変更なし）。純粋JVM testは補助。ADR本文の他の変更は本Issueでは行わない。

## Change history

- 2026-10-02: Draft created for #497（Phase 1）。出典: Issue #497本文、ADR-0015（accepted、#446 / PR #470）、ADR-0013（accepted、#445）、spec 446 planのbridge実装位置調査、editing-burden-benchmark §6（#441確定）。
- 2026-10-02: Revision 2 — Phase 1 review round 1（[PR #498 comment](https://github.com/nunu1733/NunuLauncher/pull/498#issuecomment-5935261988)）の指摘1〜4に対応。指摘1（高）: 既定配置の座標計算を純粋計画関数の対象外とし、上流`WorkspaceItemSpaceFinder`の意味論（QSB時1ページ目除外・満杯時新規screen割当）をadmission内で用いる形へ修正。新規screen id採番・screen集合反映のadmission前漏出禁止を明記（Scope/Scenario/AC-5/Open questions 10）。指摘2（中）: snapshot重複captureをfirst enqueue winsへ一意化し、`addToQueue`の重複排除の意味論変更をしないことを明記（Scenario/AC-4/Data and state/Open questions 8）。指摘3（中）: `Reject(SNAPSHOT_INVALID)`の条件を「snapshot部分のidentity不一致」に限定し、基底entry自体のdecode不能は上流の既存の読み飛ばしのまま対象外とする（Scenario/AC-4/Open questions 9）。指摘4（中）: テスト所有を「決定意味論=JVM canonical、実接続（lease defer・persist/restart・process死）=instrumentation（理由1行付き）」へ再割付け（AC-7/Test oracle）。
- 2026-10-02: Revision 3 — Phase 1 review round 2（[PR #498 comment](https://github.com/nunu1733/NunuLauncher/pull/498#issuecomment-5935544221)）の指摘1〜3に対応。指摘1（高）: admission内へ移した既定配置結果のUI反映を契約へ追加。新op routeの`bindAppsAdded`相当と新規screen伝播は、書込み成功callbackが運ぶ最終配置・新規screen idに基づき`AddWorkspaceItemsTask`側で1回だけ行う（defer中はbind・folder refresh無し。既存`addedItemsFinal`への事前追加は新op routeでは使わない）。新規scenario「既存screen全満杯時のfallback」を追加（Scope/Scenario/AC-5/Open questions 11）。指摘2（中）: ADR-0015要求テスト表3行のcanonical ownerを、ADR-0013該当行を#448/#450が満たした実績のある既存surface（AndroidJUnit4 + test DBの書込み経路harness。organizer shared-writer lane）へ戻し、純粋JVM testを補助へ位置づけた（AC-7/Test oracle/Open questions 12。ADR本文は変更しない）。指摘3（低）: PR本文packetのhead/revision/CI evidence同期は本RevisionのpushとPR本文更新で対応。
- 2026-10-02: Revision 4 — Phase 1 review round 3（[PR #498 comment](https://github.com/nunu1733/NunuLauncher/pull/498#issuecomment-5935777218)）の指摘1（中）に対応。ADR本文の「Layout Application interface相当のJVM test（test DB使用）」をAndroidJUnit4 instrumentationと同一surface扱いしないよう、ADR-0015/ADR-0013の要求テスト表の層の記述を実績surface（`AndroidJUnit4` + test DBの書込み経路harness。organizer shared-writer lane。spec 448 AC-7実績）へ先に明確化した（ADR Change historyに記録。検証内容の変更なし）。spec/planのcanonical owner表記をADRの層割付けと一意化し、`DirectEditModelWriterTest`を「JVM」と呼ぶ表現を排除（Scope テスト/AC-7/AC-10/Test oracle/Open questions 12）。round 3で解消確認済みの項目（round 2指摘1/3）の記述は維持。
