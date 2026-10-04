# Issue #532: 16-devモデル層への統一判断

> Status: 方針1を選択。ADR-0018 revision 6 / Phase 2 plan revision 2の技術review待ち（2026-10-04）
> 対象: [#532の停止報告](https://github.com/nunu1733/NunuLauncher/issues/532#issuecomment-5978884649)
> Owner入力: 2026-10-04、本Issueの進捗reviewに続く依頼「コードを踏まえて整理し、進め方含め確定」「方針１が丸い（fork乖離が無駄に増えない）」を受け、以下の根拠で方針1を選択。これは現在headに対する独立Review recommendationやmerge承認を意味しない。
> 正本: 選択と禁止境界は [ADR-0018 Decision 9](../adr/0018-lawnchair-16-rebase.md)、実装順とgateは [Phase 2 plan §4.1](../../specs/516-16-rebase-phase2/plan.md)。本書はコード根拠と比較だけを所有する。
> External reference scan: 省略。既存accepted契約・既存seamを固定upstream上へ移植する判断で、新規platform API・新しい公開seam・新しいtest strategyを採用しない。外部一次資料は以下の固定upstream sourceそのものとする。

## 1. 対象revisionと調査限界

| 記号 | commit | 用途 |
|---|---|---|
| U | `43a21b43d7cc7850ab54e14b1a57dc9646685f35` | accepted 16-dev anchor |
| F | `38262fb74d144f4655dfbf01c0084e44c86560be` | 300単位queue末尾の15系fork。契約移植のsource |
| P | `8b35f1ff7ae35bca8e9e09f9650435f68d5c44a6` | 300単位replay直後、追加修復前 |
| W | `88af5218cea51b8556b9935b35d3640714b96111` | 停止中のrebase branch head |
| D | `b759506e28f8922a2f4a02a7fbb83360b710b750` | PR #533 merge済みのaccepted spec/plan |

`git merge-base --is-ancestor U W` は成功、`git rev-list --count U..W` は310。
`git rev-list --first-parent --reverse 505dbc40..F` は300件、親数で分けるとmerge commit 234 / single-parent commit 66。PR由来か直接commitかは親数やsubjectだけでは確定しないため、旧記載「PR merge 296 + 直接commit 4」を実行証拠として使わない。

今回の検証はGit履歴、production seamと既存test/CI入口のread-only照合、文書validatorである。Android build/runtime未実行。停止報告のbuild失敗は既存作業の報告であり、この判断sessionで再現した証拠ではない。WIPの全253 pathの意味論、submodule patch同値性、全300件のreplay内容同値性を監査完了とは扱わない。

## 2. コードから確認できる統合境界

以下は全て2026-10-04に確認した固定SHAへのsource link。

| 境界 | Uの構造 | F/Wから移植・解消する対象 |
|---|---|---|
| 生成・寿命 | [LauncherModel.kt:64](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/LauncherModel.kt#L64) は `@LauncherAppSingleton @Inject`、`ModelInitializer`、`DaggerSingletonTracker`、`LoaderTaskFactory`、`BaseLauncherBinderFactory` を使用 | Wは旧Javaモデルへ戻り、[ForkBridgeModule.kt:39](https://github.com/nunu1733/NunuLauncher/blob/88af5218cea51b8556b9935b35d3640714b96111/lawnchair/src/app/lawnchair/dagger/ForkBridgeModule.kt#L39) が旧accessorからmodel/dataを供給。これをモデル所有者として固定するとanchor graphの適応範囲が増える |
| 既存呼出し入口 | [LauncherAppState.kt:28](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/LauncherAppState.kt#L28) は `@Inject` だが `INSTANCE` / `getInstance(context)` も残す。[DaggerSingletonObject.java:31](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/util/DaggerSingletonObject.java#L31) はcomponentのbindingを返す | 「Dagger vs INSTANCE」は二者択一ではない。入口の互換性を使い、生成・寿命の所有者だけをUへ統一できる。別model instanceを作るproviderは不要 |
| データモデル | [BgDataModel.kt:65](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/BgDataModel.kt#L65) は `WorkspaceData` とrepositoryを持つ。[ModelWriter.java:561](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/ModelWriter.java#L561) は `updateItems` で変更を伝播する | Fのmutable map/collectionsの直接更新をそのまま戻すとrepository側の通知を失う。forkのsnapshot投影・直接編集/UndoをUの更新入口へ接続し、DBとmodelの一致を証明する |
| Loaderの能力と完了 | Uは [LoaderTask.java:175](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/LoaderTask.java#L175) のassisted constructorとfactory、[LauncherModel.kt:340](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/LauncherModel.kt#L340) のloader transactionを使う | Fの [LoaderTask.java:274](https://github.com/nunu1733/NunuLauncher/blob/38262fb74d144f4655dfbf01c0084e44c86560be/src/com/android/launcher3/model/LoaderTask.java#L274) にあるlease admission/token、同:463のcommit+close後のqueued完了、supersession/cancel、exact-generation snapshotを移植。通常/preview loaderへorganizer能力を配らない |
| Writerと直接編集 | Uの [ModelWriter.java:635](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/ModelWriter.java#L635) はmodel executorへ直接投入する | Fの同:575のcoordinator admission、同:730の `DirectEditTask` のstage-2再検証、transaction、Undo、ADR-0015配置先処理をUのwriterに移植。ADR-0013の「admission後、最初のmodel/DB変更前に再検証」を維持 |
| CRUD・restore | Uの [ModelDbController.java:175](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/ModelDbController.java#L175) はfavorites固定のCRUD、同:98はDI constructor | Fのmutating/getDb/newTransaction経路のleaseをUのAPIで維持。[RestoreDbTask.java:206](https://github.com/nunu1733/NunuLauncher/blob/38262fb74d144f4655dfbf01c0084e44c86560be/src/com/android/launcher3/provider/RestoreDbTask.java#L206) のgetDb前restore-family lease、同kind/token再入、実DB成功経路を保持。table指定が残る必要性はcallsite別に確認する |
| grid migration | Uの [GridSizeMigrationDBController.java:123](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/GridSizeMigrationDBController.java#L123) はtable copyとtransactionを持ち、配置計算は `GridSizeMigrationLogic.kt` に分割 | Fの旧utilのGRID_MIGRATION lease、journal/digest、process restart、lock保持を新分割構造へ移植。table copyを含む全mutating区間、`tryMigrateDB` と `attemptMigrateDb` の両entryを対象とする |
| startup・preview/quickstep | UのDIは [Quickstep LauncherAppComponent.java:28](https://github.com/nunu1733/NunuLauncher/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/quickstep/dagger/com/android/launcher3/dagger/LauncherAppComponent.java#L28) でquickstep graphへ接続する | Fの [LauncherAppState.java:77](https://github.com/nunu1733/NunuLauncher/blob/38262fb74d144f4655dfbf01c0084e44c86560be/src/com/android/launcher3/LauncherAppState.java#L77) のonPostInit callbackを、default-processのmodel生成後の既存初期化経路へ一度だけ接続する。component構築中の再帰getInstanceを避け、previewやsecondary processからorganizer startupを起動しない |

spec 118はSQLiteOpenHelperのtransaction所有を扱う。process-wide leaseの所有者はspec 14とwriter/restore/grid監査群であり、二つを同じ契約と呼ばない。実装では両方を維持する。

## 3. 選択肢の比較と規模

| 選択肢 | upstream構造の維持 | fork契約の維持方法 | 判断 |
|---|---|---|---|
| 1: Uモデル層へ統一 | DI/lifecycle/factory/WorkspaceData/grid分割を維持 | fork-owned coordinator・投影・writer操作を既存seamへ移植 | 採用。既存plan §4.3を層単位で具体化できる |
| 2: 旧Javaモデルへ統一 | graph・repository・preview/quickstepの広い書換えが必要 | 旧契約のcode再利用は多いがupstream依存側を置換 | 不採用。将来の同期で維持する差分を増やす |
| 恒久的な二重モデル | 二つの生成・寿命・mutable状態を維持 | 同一性や通知の新しい整合契約が必要 | 不採用。layout authorityと能力tokenの所有が曖昧になる |

「約200行」を工数や安全性の根拠にしない。`git diff --numstat 505dbc40 F` では既存fork差分だけでもLauncherModel +322/-3、ModelWriter +923/-7、ModelDbController +753/-56、RestoreDbTask +88/-13。これは移植行数見積りではないが、reloadだけを合わせて完了とする規模ではない。

P→Wの修復差分は253 path +10,228/-9,824（`git diff --shortstat P W`）。WIPは有用な実験記録だが全体を承認済み実装として採用しない。`7bd1c681` のKotlinモデル試行と `bb832b3863` の旧Java回帰・bridge追加は異なる方向であり、「両commitを前提に継続」だけでは統一方針にならない。fork-owned UI等の有効な適応を保護し、path/hunkごとに採否を記録する。

## 4. 受入前に残る証拠

- Wは履歴適用のcheckpoint。300適用とancestryはsemantic integration完了を証明しない。adaptをPhase 3へ送るreplay-log §3の記載は、再開時にPhase 2内の未完項目へ訂正する。
- 300件のsource→replayed commit対応、追加10 commitの目的、全production pathのownerを記録する。Cはadapt path接触で決め、textual conflictの有無とは分ける。
- WにPR #533のspec/planが未運搬であるため、技術review済みの判断文書と共にrebase branchへ同期する。G4直前に最終sourceのfull SHAを `REBASE_HEAD` として固定する。
- submoduleはU/W双方のgitlinkが `7d9e92bd` であることのみ今回確認した。ログのicon-shadow patch同値性とADR Decision 2の欠落記載の矛盾は、submodule source証拠を取って別途同期する。モデル方針1の根拠や自動acceptの材料にしない。
- Android build・unit・実DB/loader・runtime、G3 ownership、T4/T5/T7/T8追加oracle、G5と高リスク独立auditは実装作業の成果物。T9のcutover closureはPhase 4に残す。

方針選択に未決定事項はない。契約の緩和、schema変更、anchor刷新、keep/adapt/drop変更を必要とする新発見だけはaccepted正本へ戻す。API表記の追従や同じ契約の新構造への移植を、毎回モデル方針の再選択へ戻さない。
