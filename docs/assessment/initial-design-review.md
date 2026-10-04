# Initial Design Review

> Status: Completed assessment
> Reviewed: 2026-08-09
> Input: `home-organizer-design.md`（596行の初期検討資料）
> Verification target: Lawnchair `15-beta` / `v15.0.0-beta3.0` at `505dbc40e6154c05158b5d0271c45f6a885a411b`

## 結論

プロダクトの狙い、Android専用化、Lawnchairを基盤にする判断、ローカル優先、ロックと復旧を重視する方向は妥当である。一方、現案のまま `favorites` を洗い替える実装へ進むと、ユーザーの既存layoutを破損するリスクが高い。

実装前に、既存Deck layoutの調査、対象集合、trigger、安全な適用、item type、grid非依存ruleをIssueで確定する必要がある。MVPは「高機能な配置」より先に「純粋なplan生成と安全な適用」を成立させるべきである。

## 良い点

- iOSをscope外にし、Android launcherとして価値を集中している。
- 常時LLM依存を避け、offline fallbackを基本にしている。
- 全体整理と新規アプリの増分配置を分けている。
- user overrideとlockを優先し、ユーザーの意思を残そうとしている。
- 上流差分をproject固有packageへ局所化する意図がある。
- 要件IDとphaseがあり、Issue/specへ移しやすい。

## 問題・不足点

| 重要度 | 問題 | 影響 | 必要な対応 |
|---|---|---|---|
| P0 | 「初回+install時のみ」「任意実行」「設定action」が矛盾する | 意図せぬ全体変更、受入条件の不一致 | triggerごとの確認・適用policyを決定Issueにする |
| P0 | Undo必須なのにpreview/UndoがPhase 3 | MVPが破壊的操作を安全に提供できない | recovery point、preview/confirmation、rollbackをMVPの土台へ移す |
| P0 | `clear -> re-insert` の洗い替えを想定 | folder/widget ID、途中失敗、memory modelとの競合でlayoutを失う | validated planをtransactionで差分適用し、失敗注入testを作る |
| P0 | 整理の「全アプリ」が未定義 | drawer内全launchable appをhomeへ追加するのか、既存homeだけか不明 | 対象集合と非対象を決める |
| P0 | Lawnchair 15の既存Deck layoutを認識していない | 同じeventへの二重hook、機能重複、上流merge conflict | 既存実装のreuse/refactor/replaceを先にspikeする |
| P0 | 想定hook `PackageInstalledTask` が15系に存在しない | 初期class構成と工数見積りが成立しない | 実際の `ModelLauncherCallbacks` / `PackageUpdatedTask(OP_ADD)` を基準commitで再調査する |
| P1 | Lawnchair backupをXMLと記載 | rule形式の選定理由とundo設計が誤る | backupはZIP内のDB等である事実と、rule serializationを分離する |
| P1 | 手動export backupを即時Undoとみなしている | 作成失敗、復旧操作、process restart、保持期間が未定 | 専用recovery pointの原子性・検証・保持policyを定義する |
| P1 | `ApplicationInfo.category` をPlay Store categoryと呼んでいる | taxonomyと分類精度の期待がずれる | Android application categoryとして扱い、undefinedを通常ケースにする |
| P1 | 固定 `row 0-5` の3 zone | 4x5/5x5、tablet、landscape、foldable、widget spanで破綻 | device profileから領域を導出し、固定行番号をrule外へ出す |
| P1 | app/widget以外のitem typeが不足 | deep shortcut、app pair、custom widget、folder child等が消失・誤配置 | 全item typeの保持/移動/非対象policyを決める |
| P1 | lockがitemのBooleanだけ | folderをlockしたときの子、Dock、grid変更時の意味が不明 | 「ロック配置」と占有constraintのsemanticsをspec化する |
| P1 | 増分処理と全体整理の収束条件がない | installのたびにfolder/pageが変わり、次の全体整理で再移動する | convergenceとidempotenceをinvariantにする |
| P1 | package/user identityが弱い | work/private profile、複数activity、update/restoreを新規installと誤認する | component+userをidentityとし、event matrixをtestする |
| P1 | usage accessのpermission UX・privacyがない | 権限拒否時の停止、説明不足、Play policyリスク | optional signalとし、拒否時fallback、data保持、説明をspec化する |
| P1 | LLMへ渡すdataとsecurityが未定 | package/app情報の外部送信、prompt injection、credential漏洩 | Phase 2前にprivacy/threat model Issueを完了する |
| P1 | rule schemaが文字列conditionとphysical rowsを持つ | 構文検証・migration・端末移植が難しい | typed rule modelを先に定義し、version/schema/migrationを設計する |
| P2 | `O(N log N)` だけでperformance要件としている | DB/UI bind、backup、端末差を評価できない | 端末class・件数・p95・UI block時間のbudgetを決める |
| P2 | UI stackの採用範囲が曖昧 | View/Compose bridge増加と上流追従cost | 既存設定UIのconventionに合わせ、画面単位で決める |
| P2 | accessibility、localization、battery、telemetryが不足 | 品質確認と改善判断ができない | NFRとtest matrixへ追加する |

## 公式sourceで確認した差分

### Lawnchair 15は安定版として固定されていない

公式repositoryは16系を開発branchとしており、通常利用者向けには15 Beta 3を案内している。15系のtagは `v15.0.0-beta3.0` であり、基準commitをIssueで固定する必要がある。

- [Lawnchair repository](https://github.com/LawnchairLauncher/lawnchair)
- [15 Beta 3 tag](https://github.com/LawnchairLauncher/lawnchair/tree/v15.0.0-beta3.0)

### 既存Deck layoutが近い機能を持つ

`LawndeckManager` は全アプリをカテゴリ化してhomeへ追加し、layout切替用にDBを複製する。`PackageUpdatedTask(OP_ADD)` には新規アプリをカテゴリfolderへ加える処理が既にある。ただし、source上に `TODO`、固定遅延、直接DB file copy等があり、要件を満たす完成形と仮定してはならない。

- [LawndeckManager.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt)
- [AddFoldersWithItemsTask.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt)
- [PackageUpdatedTask.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/PackageUpdatedTask.java)

### Backupはrule XMLではない

LawnchairのbackupはZIPで、launcher DB、preferences、Protobuf metadata、任意の画像を格納する。rule fileをXMLにする根拠にはならない。

- [LawnchairBackup.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt)

### Android application categoryは限定的なsignalである

`ApplicationInfo.category` はmanifestまたはinstaller hint由来で、undefinedを含む限定的なカテゴリ群である。Play Store taxonomyと同一ではない。現行Android公式資料ではaccessibilityを含むため、「8種固定」も将来にわたり正しい前提ではない。

- [Android ApplicationInfo](https://developer.android.com/reference/android/content/pm/ApplicationInfo)
- [Android UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)

## 推奨するMVPの切り方

1. Lawnchair forkを作り、15系の正確なcommitとbuildを固定する。
2. 既存Deck layoutをfixtureと実機で評価し、再利用範囲を決める。
3. platform非依存のsnapshot、rules、plan、diagnosticを定義する。
4. 副作用のない計画moduleで、保存・lock・grid invariantをtestする。
5. test DBを使う安全な適用moduleとrecoveryを成立させる。
6. その後に手動全体整理、lock UI、新規アプリ増分配置を縦に接続する。
7. rule import/export、usage frequency、LLMは土台の安全性と収束性が確認された後に追加する。

この順序では、初期案のPhase 1にあった機能数は減るが、ユーザーlayoutを預かる製品として必要な安全性を先に完成させられる。
