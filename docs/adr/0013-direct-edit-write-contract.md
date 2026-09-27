---
status: accepted
---

# 直接編集の書込み契約（ADR-0013）

> Status: Accepted（2026-09-27。#445の決定Issueで起草・受入を追跡した。出典は #445 付録の承認済み草案（2026-09-24）であり、本ADRがその正本である。Phase 1 review（#445 のreviewコメント）で確定した再検証契約・書込み構造の明示・ロックの限定解釈の根拠を含む）
> Date: 2026-09-27（起草 2026-09-24）
> 対応: #445（方針判断メモ: 再焦点化方針メモ（2026-09-24に承認、Revision 5）§4.2、R-4、D-013。以下「メモ§x」はこのメモの出典を指す）

## Context

再焦点化（メモ R-1/R-4）により、プロダクトの中心を「ホーム画面の日常的な編集」へ移す。日常編集の本体は、ユーザーが明示的に選んだ1個のアイテムへの即時の編集アクション（ページへ移動、フォルダへ入れる、ホームから外す）と、上流が行う単一アイテムの追加の配置先決定（ADR-0015）である。これらを現在のorganizer安全規約（`AGENTS.md`「ホームレイアウトを扱う安全規約」、`specs/13-safe-layout-application/spec.md`）の全面適用対象にすると、1操作の編集にsnapshot revision照合・recovery point・相関reload・全体再検証が毎回付き、応答性（NFR-013）と実装量が割に合わない。

一方で、この緩和が「layoutを失わない」という品質順位の第1位（`docs/engineering/quality-strategy.md` Quality order 1）を損なわないことを、コードの事実で示す必要がある。判断が高コストな理由は:

1. 安全規約の適用範囲の線引きは、`AGENTS.md` という正本の変更（#445 付録の変更文案、本ADRの受入PRで適用）と、以降のすべての編集機能（#446/#448/#449/#450）のspecの前提になる。
2. 書込み経路の選択（上流のmodel writerをそのまま使うか、organizerの適用経路を分流させるか）は、`LayoutWriteCoordinator` の排他の意味論と、将来のLawnchair 16 rebase costを左右する。
3. ロック（ADR-0004）の意味論の解釈（自動の変更への制約か、すべての移動への制約か）は、既存のlock UI（Issue #38）とorganizer runの両方に影響する。

### 調査で確認した現在の状態

`path:line` の根拠は2026-09-27に検証した。対象commitは本ADRの受入PRのbase（main `b40888ae17ce8924f23316119888ed6b80693d70`）である。以降の上流同期で参照先がずれた場合は、本節の根拠を更新して追跡する。

**上流の手動編集がDBへ書く経路（すべてbaseline由来でforkは変えていない）**

- drag&dropの移動: drop確定時に `Workspace.onDrop` が `mLauncher.getModelWriter().modifyItemInDatabase(info, container, screenId, cellX, cellY, spanX, spanY)` を呼ぶ（`src/com/android/launcher3/Workspace.java:2345`）。
- フォルダ作成・フォルダへの参加: drop先が別アイコン上のとき `createUserFolderIfNecessary` / `addToExistingFolderIfNecessary` が分岐し（`src/com/android/launcher3/Workspace.java:3111-3116`）、フォルダ内への書込みは `LauncherDelegate` / `Folder` が `addOrMoveItemInDatabase` で行う（`src/com/android/launcher3/folder/LauncherDelegate.java:99`、`src/com/android/launcher3/folder/Folder.java:1437,1517`）。
- 新規アイテムの追加（drawer等から）: `Launcher.addOrMoveItemToDatabase` 系が `addItemToDatabase` を呼ぶ（`src/com/android/launcher3/Launcher.java:1503,1593,2048`）。
- 削除（Remove target）: `DeleteDropTarget.onDrop` → `prepareToUndoDelete()` → `completeDrop` → `deleteItemFromDatabase`（`src/com/android/launcher3/DeleteDropTarget.java:120-137`、`src/com/android/launcher3/Launcher.java:2132,2369`）。削除はsnackbarのUndo（`commitDelete`/`abortDelete`、`src/com/android/launcher3/model/ModelWriter.java:425,434`。timeout 4000ms、`src/com/android/launcher3/views/Snackbar.java:50`）を持つが、移動にはUndoがない（`docs/engineering/editing-burden-benchmark.md` §3.3）。
- アクセシビリティ経由: `LauncherAccessibilityDelegate` の `MOVE_TO_WORKSPACE` が `moveItemInDatabase` で単一アイテムを移す（`src/com/android/launcher3/accessibility/LauncherAccessibilityDelegate.java:495`）。

**これらの書込みの性質（根拠）**

- 単一アイテムの更新は1行の `update` であり、transactionを明示しない（`UpdateItemRunnable.runImpl`、`src/com/android/launcher3/model/ModelWriter.java:471-475`）。複数アイテムの一括更新だけが `UpdateItemsRunnable` 内で `SQLiteTransaction` を使い、失敗時は例外を握りつぶす（`src/com/android/launcher3/model/ModelWriter.java:488-502`。握りつぶしは `:499-501`）。folder bindの `updateItemLocationsInDatabaseBatch` がこの経路である（`docs/assessment/pr-114-model-writer-reentry.md`、Issue #113）。
- 新規追加は `insert` + `mBgDataModel.addItem` + `ModelVerifier.verifyModel()`（`src/com/android/launcher3/model/ModelWriter.java:299-313`）。削除は `delete` + `removeItem`（`src/com/android/launcher3/model/ModelWriter.java:345-352`）。
- いずれのメソッドも `favorites.organizerLockState` 列を書かない。`ContentWriter` にlock列を置く呼出しは `src/` の編集経路に存在せず、`ORGANIZER_LOCK_STATE` の `src/` 内の参照は `src/com/android/launcher3/LauncherSettings.java:357`（列名定義）のほか、migration/readiness系の `src/com/android/launcher3/model/DatabaseHelper.java:286-292`（列追加のmigration）、`src/com/android/launcher3/model/GridSizeMigrationUtil.java:198-214`（migration時のlock値扱い）、`src/com/android/launcher3/model/ModelDbController.java:915-919`（readiness集計）にある。編集経路（`ContentWriter`）には存在しない。つまり**手動dragはロック列に触れず、行に付いたlock状態は移動後も保持される**。手動移動をforkが制限・拒否する仕組みも存在しない。
- 事前検証は上流のUI層の判定（`findNearestArea`/`performReorder`/`willCreateUserFolder` の距離・空きセル判定。`src/com/android/launcher3/Workspace.java:2030-2056,2876-2940`）だけである。副作用のない計画関数によるconservation・重なり・container参照の検証、snapshot revision照合、recovery point、適用後の全体再検証はどこにもない。
- **既存メソッドはadmissionの前にin-memory modelを変更する**: `ModelWriter.moveItemInDatabase` / `modifyItemInDatabase` は `executeOnModelThread()`（admission）より先に `updateItemInfoProps(...)` で `ItemInfo` の `container`/`screenId`/`cellX`/`cellY`/`spanX`/`spanY` を書き換える（`src/com/android/launcher3/model/ModelWriter.java:190-192,252-254`）。coordinatorがgateするのはその後の `ModelTask` runnableであり、deferされたrunnableは解消後に検証なしでDB updateを実行する（`:471-475`）。この順序は既存のdrag経路では容認されてきたが、直接編集の契約（契約2・4）では許容しない。

**forkが追加した書込み排他**

- `LayoutWriteCoordinator` はprocess-wideな再入可能leaseで、`OwnerKind` は `ORGANIZER / MODEL_WRITER / GRID_MIGRATION / RESTORE / BACKUP_RESTORE`（`src/com/android/launcher3/model/LayoutWriteCoordinator.java:53-59`）。
- `ModelWriter` のすべてのタスクは `executeOnModelThread` を経て `runOrDefer(MODEL_WRITER, token=0)` でgateされ（`src/com/android/launcher3/model/ModelWriter.java:570-579`）、organizer lease保持中はtokenlessなMODEL_WRITER系書込みがFIFOへdeferされる（`LayoutWriteCoordinator.runOrDefer`、`src/com/android/launcher3/model/LayoutWriteCoordinator.java:460-497`、`defersTokenlessWork` は `ORGANIZER` とrestore-familyのみ、`:525-527`）。つまり**organizer runの適用中に手動drag等のMODEL_WRITER系書込みは実行されず、lease解放後に順に流れる**。これはIssue #14（`docs/assessment/issue-44-shared-writer-audit.md` runtime writer inventory表の `ModelWriter` 行）とIssue #60（`docs/assessment/issue-60-executor-writer-admission-audit.md`）でaudit済みである。
- organizer側のport契約は `WriterKind`（`lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt:92`）と `LayoutWriterPort.applyWriteSet`（同 `:51-56`）で、適用は1 transaction（A5-A6、`specs/13-safe-layout-application/spec.md` Apply protocol表）であり、A5のtransaction内再読でrevision/preconditionを検証する。

**organizerの復元と手動編集の関係（既に実績がある組合せ）**

- Issue #265（`specs/265-post-apply-recovery-reconciliation/spec.md`）が実機で確認したsequenceは「organizer適用 → 手動でアイテムをフォルダから移動 → Restore要求」である。復元はrecovery pointのpre-state manifestとの照合で行われ、手動編集後の状態がpre-stateと一致しなければ `NotRestorable(STALE_REVISION)` になる（同spec Established facts 1、`specs/13-safe-layout-application/spec.md` Recovery protocol 3）。手動編集はorganizerから見ると「現在のrevisionを変えた外部変更」であり、復元はfail-closedに拒否される。これが今日の手動dragと直接編集で変わることはない。

## Decision

**直接編集（メモ§4.2対象 (a)(b)(c)）は、organizerの安全な適用経路を使わず、上流のmodel writer経路で書く。** ただし次の契約を満たす。視覚的編集画面（#449）の一括確定は対象外であり、そちらはorganizerの安全な適用経路（recovery point付き）で現行の安全規約に従う（メモ§4.3、ADR-0014は #447 が起草・受入を追跡する）。

対象の定義:

- (a) ユーザーが長押しpopup等で明示的に選んだ1個のアイテムへの即時の編集アクション（ページへ移動、フォルダへ入れる、ホームから外す）。
- (b) 上流が行う単一アイテムの追加の配置先決定（ADR-0015が所有。書込みの安全条件だけを本ADRに委ねる）。
- (c) (a)のUndo（FR-020。UIと寿命は #450 が所有する。第1版はprocess内・次の編集まで。メモ§11 B-1）。(b)の新規アプリの配置はUndoの対象外である（メモ§4.1。利用者の操作ではなく、多くはランチャーが裏にいる間に起きるため。置き場所を変えたい場合は項目単位の編集アクション（#448）を使う）。

契約（メモ§4.2必須条件を、コード事実に接地面を明示して確定する）:

1. **変更対象の限定**: 1アクションが変えるのは、選ばれたアイテム（または追加される1アイテム）と、そのために作る新しいフォルダ/ページだけである。未選択のアイテムのrowは一切書かない。これは上流の単一アイテム書込み（`modifyItemInDatabase`/`addItemToDatabase`/`deleteItemFromDatabase`。上記の根拠）と同じ性質である。
2. **書込み前と、admission後の二段階の副作用のない検証**: 検証は副作用のない計画関数で行い、(i) 書込みの依頼時に、(ii) MODEL_WRITER admissionが成立した後（deferされていた場合はlease解放後）かつ最初のmodel/DB変更の前に、現在の状態に対して同一の純粋関数で検証する。検証対象はconservation・重なりなし・device profile範囲内・container参照の有効性・profile分離である。どちらの段階でも満たせなければ書かずに、理由をtypedに示す。二段階とする根拠は、検証済みの前提がdefer中にstaleになりうることである（organizer適用が対象rowや配置先を変更しうる）。検証関数は #448 の第1段と視覚的編集画面で共有する（メモ§4.3「計算を共有し、書き方だけが異なる」）。検証はorganizerのplanner/plan artifact（`specs/13-safe-layout-application/spec.md` の `ValidatedLayoutPlan`）を作らず、単一操作の入出力（現状態 + 対象アイテム + 意図）に対する純関数として `app.lawnchair.homeedit` 側に置く。
3. **1アクション = 1 DB transaction**: 全成功か変更なし。上流の単一アイテム経路は1行update/insert/deleteで自ずとatomicであるが、新規フォルダ作成＋子の移動のように複数rowを書くアクションは `ModelDbController.newTransaction()`（`src/com/android/launcher3/model/ModelDbController.java:298-303`）で明示的に1 transactionに包む。transaction内の失敗はrollbackし、model/DBがずれたままにしない。
4. **上流のmodel writer経路と排他、および検証→admission→変更の順序**: `ModelWriter` の既存メソッド（または同経路に追加する最小の操作）で書き、model/DBの整合は上流の仕組み（`checkItemInfoLocked`、`ModelVerifier`、`mBgDataModel`更新。`src/com/android/launcher3/model/ModelWriter.java:121-156,505-555`）に従う。新規の書込みは必ず `LayoutWriteCoordinator` のMODEL_WRITER admission（`ModelWriter.ModelTask.executeOnModelThread`、`src/com/android/launcher3/model/ModelWriter.java:570-579`）を経由し、organizer runの適用（ORGANIZER lease）と同時に書かない。**既存メソッドがadmission前に `ItemInfo` を変更する順序（`updateItemInfoProps` が `executeOnModelThread` より先。`src/com/android/launcher3/model/ModelWriter.java:190-192,252-254`）を、直接編集の書込み構造は踏まない**: 直接編集は「validation（契約2の一段階目）→ admission → admission後の再検証（契約2の二段階目）→ model/DB変更」の順序が保たれる構造、すなわちadmissionの内側で検証と変更が完結する最小の操作（同経路に追加する）として実装する。admission成立より前にmodel/DBのいずれも変更しない。deferされた書込みは、ユーザーが意図した操作の結果がlease解放後に反映されるため、UIの応答性要件（NFR-013）はdefer解消後の反映を対象にspecで確定する。
5. **Undo（fail-closedな逆操作）**: 直前のアクションを1操作で取り消せる。逆操作は、実行前に「現在の状態が、直前のアクションの結果と一致するか」を副作用のない検証で照合し、ずれていれば書かずにtypedな失敗を返す。照合の対象は、アクションが変えたrow（対象アイテム、作ったフォルダ/ページ）に限り、全レイアウトのrevision照合は要求しない。「ホームから外す」のUndoは、上流の削除Undoの仕組み（`prepareToUndoDelete`/`commitDelete`/`abortDelete`、`src/com/android/launcher3/model/ModelWriter.java:400-442`）を再利用してよい（詳細は #450 のspecで決める。メモ§11 B-2）。移動・追加のUndoは、直前アクションの逆row書込み（1 transaction）として実装する。UndoのUI（操作直後のsnackbar）、寿命（第1版はprocess内で次の編集まで。永続化はNext）・複数段のUndoは #450 が所有する（メモ§11 B-3）。
6. **「ホームから外す」の意味**: アイコンの削除（`favorites` 行の削除）であり、アプリのアンインストール（上流の `UNINSTALL_APP`、`src/com/android/launcher3/popup/SystemShortcut.java:337`）ではない。フォルダから取り出す操作や、親フォルダの中身の暗黙の削除をしない。フォルダが空になった後の扱い（空フォルダの残置/削除）はspec 24（`specs/24-empty-folder-policy/`）が所有する方針に従い、本ADRでは決めない。

**不要とするもの（メモ§4.2「不要とするもの」の確定）**: organizerのsnapshot revision照合、recovery point（checkpoint）、相関reload、適用後の全体再検証。根拠は次のとおり。

- これらは「計画的な複数アイテムの書換え」が対象として設計された（`specs/13-safe-layout-application/spec.md` Problem/Outcome。`docs/assessment/initial-design-review.md` P0「clear → re-insertの洗い替え」への対応として導入された）。単一アイテムの即時操作は洗い替えではなく、失敗の最大被害が1アイテムである。
- 今日の手動dragが同じ性質の書込みをこれらの条件なしで行っており（上記の根拠）、直接編集はそれに検証（契約2）とtransaction保証（契約3）とUndo（契約5）を**足す**だけである。安全性を現在のdragより弱めない。
- 復元の相互運用は失わない。organizerの復元は現在のrevisionとの照合でfail-closedに拒否するため（Issue #265の実績）、直接編集がrecovery pointを持たないことが復元の安全性を壊さない。

**ロック（既定案の確定）**: ロックは**自動の変更**（organizer runの適用、新規アプリの配置先）に対する制約とする。ユーザーが明示的に選んだアイテムへの直接編集は妨げず、ロック状態を保ったまま移す（移動後も `organizerLockState` 列の値は不変）。

- **ADR-0004との整合（限定解釈の根拠）**: ADR-0004（`docs/adr/0004-organizer-lock-persistence.md`）の規範部分は、(i) Decision節のlock truthの格納とfail-closedな読み方（`favorites.organizerLockState` のtri-state。Snapshot、revision、exact precondition、layout transaction、recovery manifest、post-write verificationに列が含まれ、独立のlock storeは正確性に参与しない）、(ii) Identity and effective-lock rulesのprotection表（例: Widgetは `LOCKED` がcell/span/占有領域を、Folder parentはparent cellと全childのcaptured container/rankを保護する）、(iii) Context節がspec 13契約（organizerの適用と復旧）との結合を述べる部分、である。これらのprotection表はorganizerのcapture/apply/recoveryという**操作契約に結びついたrules**であり、上流dragがロック済みrowを移動することを禁止する語ではない。実際、上流のdragはlock列に触れずに移動してきた実績があり（上記の根拠。`ORGANIZER_LOCK_STATE` の参照が編集経路に存在しないこと）、手動移動後のorganizer runはcapture時点の現在状態を正として扱い、矛盾は生じない（今日の手動dragと同じ）。また手動編集後の復元は #265 の実績どおりfail-closedに `NotRestorable(STALE_REVISION)` となるため、ロックを含むlayout truthの保護は復元経路でも維持される。よって「手動移動はロックを妨げず状態を保つ」はADR-0004の意味論の延長であり、矛盾ではない。本ADRがこの限定解釈の正本であり、ADR-0004の本文は変更しない（受入時にADR-0004のChange historyへ関連参照を1行追加する。メモ§4.2の決定）。
- 選択にロック中のアイテムが含まれる場合（第1段は単一選択なので「選んだアイテム自身がロック済み」の場合）、操作時にそれを示す（ADR-0004の `LockEffectNote` の既存の説明機構、`lawnchair/src/app/lawnchair/organizer/locks/EffectiveLocks.kt:18-46` をUI側で利用する）。
- organizer runの影響: ロック済みrowが直接編集で移動した後のorganizer runは、capture時点の現在状態を正として扱うため、矛盾は生じない（今日の手動dragと同じ）。

**AGENTS.mdとの関係**: 「ホームレイアウトを扱う安全規約」に、直接編集の区分だけを明示carve-outする1段落を追記する。既存の節（第1段落、7条件、「favorites…」段落）は1字も変更しない。列挙されないLauncher DBへのlayout書込みは引き続き既存の条件に従う（fail-closedなdefaultの維持）。変更文案は #445 のspecが所有し、本ADRの受入と同じPRで適用する。AGENTS.mdは安全規約の例外に「受入済みADRと破壊・復旧テスト」を要求するため、本ADRが要求するテストを次節に定める。

## 要求するテスト（AGENTS.mdの例外要件への対応）

テストの層は `docs/engineering/quality-strategy.md` の区分に従う。新規CI laneは作らず、既存の `organizer-unit-tests` gate（`docs/engineering/quality-strategy.md` Organizer unit-test CI gate節）と既存のinstrumentation lane（`docs/engineering/ci-test-portfolio.md`、`tools/repo-contract/ci_portfolio_map.yml`）に載せる。lane追加の判断はAGENTS.mdテスト規約の審査対象であるため、追加が必要になった時点で別途審査する。実装は #446/#448/#450 が行い、本表はその実装PRが満たすべき要求である。

| 要求 | 層 | 内容 |
|---|---|---|
| 途中失敗の注入 | Layout Application interface相当のJVM test（test DB使用） | 複数rowアクション（フォルダ作成＋移動等）のN番目のwrite失敗で全rollbackし、model/DBが一致したままであること。単一rowアクションは1行書込みのため、失敗時のpartial stateが構造上存在しないことを示す。 |
| transaction rollback | 同上 | `newTransaction()` での複数row書込みが、close/失敗で全件元に戻ること。`UpdateItemsRunnable` の既存の失敗握りつぶし（`src/com/android/launcher3/model/ModelWriter.java:499-501`）を新規経路が真似しないこと。 |
| admission後の再検証（defer後のstale検証） | 同上 | ORGANIZER lease保持中に直接編集のintentを投入 → organizer適用が対象row/配置先を変更 → lease解放後、直接編集は契約2の二段階目の検証でtypedに失敗して書かないこと。lease解放前にmodel/DBのいずれへの変更も発生しないこと（契約4の順序）。 |
| Undoのfail-closed | 同上（契約5の検証） | 逆操作の直前に状態がずれた場合（対象rowが他経路で変えられた、作ったフォルダが既に消えた等）に、書かずにtypedな失敗を返すこと。正常な逆操作で元に戻ること。 |
| organizer runとの排他 | 既存のshared-writer instrumentation seam（`LayoutWriteCoordinatorTest` / `ModelWriterTransactionReentryTest` の実例。`docs/assessment/pr-114-model-writer-reentry.md`） | ORGANIZER lease保持中に直接編集の書込みがdeferされ、lease解放後に実行されること。逆方向（organizer applyが直接編集のlease保持中に `Rejected(WRITER_BUSY)` になること）は `specs/13-safe-layout-application/spec.md` SA-24で既に保証される。 |
| process死 | instrumentation（既存のprocess-death smokeの慣行。`specs/13-safe-layout-application/spec.md` AC-14） | 直接編集のtransaction中のprocess死後、再起動時のmodel/DBが整合していること（1 transactionのため、DBは前か後のどちらかであることの確認）。直接編集はrecovery recordを持たないため、restart reconciliationの対象にならないことを確認する。 |
| 破壊・復旧（AGENTS.mdの例外要件） | 上記の組合せ | 「破壊」= 直接編集の失敗・process死、「復旧」= rollbackとUndo。recovery pointを使わない例外であることの根拠（本ADR）と、このテスト群を同じPRで示す。 |

## Alternatives considered

### 直接編集もorganizerの安全な適用経路で書く

Rejected。1操作ごとにrecovery point作成（recovery DB transaction + read-back検証）、A5のtransaction内revision再読、相関reload待ち、独立recapture検証が付き、NFR-013の即時性と実装量が割に合わない。単一アイテムの失敗被害が小さいのに、複数アイテム一括適用と同じ装置を付ける過剰である。またADR-0015の新規アプリ配置（install直後の自動処理）にrecovery pointを付けるのは、上流の追加経路の性質と合わない。

### 直接編集専用の新しいwriter（model writerを経由しない書込み）

Rejected。`ModelWriter` を経由しない書込みは `mBgDataModel` との整合を自前で保証する必要があり、Issue #44 auditが示した「すべてのruntime writerがcoordinatorを経る」不変条件（writer inventory source-scan allowlist、`docs/assessment/issue-60-executor-writer-admission-audit.md`）を弱める。上流の仕組み（`checkItemInfoLocked`、`ModelVerifier`）を再実装する価値がない。

### ロック済みアイテムへの直接編集を拒否する

Rejected（第1段としては）。ユーザーの明示的な操作を、organizerが管理するロックで妨げるのは、ロックの目的（自動の変更から配置を守る。`docs/adr/0004-organizer-lock-persistence.md` Contextのuser intent保護）の転用である。上流のdragが既に同じことを許している以上、popup操作だけを厳しくする一貫性はない。ただし、ロック済みアイテムへの操作時にその旨を示す（Decision節）。

### Undoをorganizerのrecovery pointで実装する

Rejected。recovery pointは「適用前の全レイアウト」の復元であり、1操作のUndoに対して過大である。pre-state manifestの作成・保存コストが頻繁な編集に乗る。上流の削除Undo（enqueue/commit/abort）の延長で、単一操作の逆row書込みと照合で足りる。

### 契約をspecにだけ置き、ADRを作らない

Rejected。安全規約の適用範囲の線引きは `AGENTS.md`（正本）の変更を伴い、AGENTS.mdは例外に「受入済みADR」を要求する。また、線引きの判断は「後から変えるcostが高い」「codeだけでは理由が不明」「実際のtrade-offがあった」の3条件（`docs/adr/README.md`）を満たす。

## Consequences

- #446（ADR-0015）、#448、#450のspecは、本契約を参照して書かれる。それぞれのspecで契約を重複定義しない（AGENTS.md正本の分担）。
- 直接編集の書込みは `risk: layout-data` の対象になる（`docs/project/github-workflow.md` 高リスクpath一覧の `src/com/android/launcher3/model/ModelWriter.java` への触れ方による。メモ§4.7の階層H）。最初の実装PRは独立auditと `final-status` を必要とする。
- 直接編集の新moduleは仮称 `app.lawnchair.homeedit`（メモ§4.8）であり、そのうちLauncher DBへ書くコードのpathは高リスクpath一覧へ追加する。追加の判断の正本は #445（メモ§11 B-10）であり、具体的pathと追加の実行は最初の書込み実装PRが行う（UIと副作用のない計算は含めない）。
- 直接編集が新たなDB書込みfileを追加した場合、`tools/repo-contract/validate_writer_inventory.py` のsource-scan allowlistの更新がCIで要求される。allowlistは新規writerのinventory漏れを検出するbackstopであり、契約4の実保証は「書込みが `ModelWriter`/coordinator admissionを通る構造」と要求テスト（admission後の再検証・organizer runとの排他）で担保する。
- organizerの復元（recovery point）は、直接編集後の状態に対して `NotRestorable(STALE_REVISION)` でfail-closedに拒否しうる。これは今日の手動dragと同じ挙動であり、復元の対象はorganizer適用直後の状態に限るという既存の意味論である。
- 上流の `UpdateItemsRunnable` の失敗握りつぶし（`src/com/android/launcher3/model/ModelWriter.java:499-501`）は既存の問題として残る。直接編集の複数rowアクションはこの経路を使わず、`newTransaction()` を使う（契約3）。是正の調査候補はメモ§10により別調査候補として記録済みである。
- Lawnchair 16へのrebase時、本契約が依存するのは上流の `ModelWriter`/`LayoutWriteCoordinator` の既存shapeであり、新規の上流状態機械への依存を持たない。rebase riskはADR-0014の操作面より小さい。

## Change history

- 2026-09-27: Accepted。#445 の決定Issueで起草・受入（出典: #445 付録の承認済み草案2026-09-24）。Phase 1 review（#445 のreviewコメント）で確定した契約2の二段階検証、契約4の書込み順序の明示、要求テストへのadmission後の再検証行、ロックの限定解釈の根拠を含む。
