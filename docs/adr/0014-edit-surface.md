---
status: accepted
---

> Status: Accepted（2026-09-28。#442の最終結論C（[research §6.2](../assessment/issue-442-android16-17-fitness-research.md)、[owner record](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5863040551)）を受けて受入した。起草（Proposed）は #447 の決定IssueでPR #471により行い、出典は #447 付録の承認済み草案（2026-09-24）である。本ADRがその正本である。受入PRは #447 をcloseする）
> Date: 2026-09-28（起草 2026-09-24、Proposed 2026-09-27）
> Revision 2: 2026-09-27 — PR #471 review（ラウンド1）の指摘に対応: ①「図の描画に使えるデータ」の記述を実際の契約へ修正（`icon` はcustom icon bitmapのoptionalな転写であり、通常アプリの表示iconは `TargetKey` とprofileからの `IconCache` 解決が要る。解決不能時のfallbackは#449 specの責務）。②案A/案Bの入口コストを確定手順（workspace空きスペース長押し2 + メニュー項目tap 1 = 3）で再計算（案A 9/8/9→10/9/10、案B 9/7/9→11/9/10）し、#441 §4の確定重みでの比較へ限定。「重みの取り方に依存しない」という一般の不変性の主張は撤回。判断内容（案B推奨）の変更はない。
> Revision 3: 2026-09-28 — 受入（Proposed → Accepted）。#442の最終結論C（Lawnchair 16へのrebaseを専用Epic+ADRで計画）の確定を受けて、Decision節の受入gateを決着し、C確定下での再確認（実装順序とpatch surfaceの追加許容。Decision節末尾）を記録。判断内容（案B推奨）の変更はない。
> 対応: #447（方針判断メモ: 再焦点化方針メモ（2026-09-24に承認、Revision 5）§4.3、R-5、D-014。以下「メモ§x」はこのメモの出典を指す）

# 編集の操作面と上流workspaceへの変更

## Context

再焦点化（メモ R-5。Epic #439）により、編集アクションの振る舞い（項目単位の3アクション: ページへ移動、フォルダへ入れる、ホームから外す。視覚的編集画面では「選択から新しいフォルダを作る」を加えた4アクション。メモ§4.9のFR-018/FR-019）はfork側moduleに1回だけ実装し、操作面は段階的に増やす。第1段は長押しpopupによる項目単位の操作（#448）、第2段は複数選択（#449）である。このADRは第2段の操作面を、実コードの調査に基づいて決める。

判断が高コストな理由:

1. 操作面の選択は、上流（`src/com/android/launcher3/`）のworkspace、drag、popup、LauncherState周辺へのpatch surfaceの増加を直接決める。patch surfaceは[受入済みbaseline](../assessment/upstream-patch-surface-baseline.md)（47 counted files, +3,993/-1,017）で管理され、増加はNFR-010のreview signalとなる。
2. Lawnchair 16へのrebaseは通常updateとして扱わず専用Epic/ADRを要求する（AGENTS.md）。上流のどの領域に触るかがrebase costを左右する。
3. ランチャーの中核操作（drag、ページ移動、フォルダ）の安定性への影響は、後から取り消しが難しい。

### 調査で確認した現在の状態

**上流の状態機械とEDIT_MODE**

- `LauncherState.java:140-142` に `SPRING_LOADED` と `EDIT_MODE` が存在する。`EDIT_MODE` は `states/EditModeState.kt:18` の「home gardening multi-select」用の状態で、`FLAG_MULTI_PAGE or FLAG_WORKSPACE_INACCESSIBLE or ...` を持つ。
- `EDIT_MODE` への入口は存在する。workspace空きスペース長押し → `WorkspaceTouchListener.java:223`（`showDefaultOptions`）→ `OptionsPopupView.java:204,211-215`（`enterHomeGardening` が `goToState(EDIT_MODE)`）。Lawnchair側のオプション面にも「edit_mode」項目がある（`lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt:79-86`）。
- しかし `EDIT_MODE` は選択UIの「器」だけである。選択・アクションバー・複数選択の実装は存在しない。`FeatureFlags.java:171-172` の `MULTI_SELECT_EDIT_MODE` は既定DISABLEDのdebug flagで、参照箇所はdrag後の状態遷移の抑制のみ（`Launcher.java:939,1957`、`src/com/android/launcher3/dragndrop/PinShortcutRequestActivityInfo.java:101`）。`Workspace.java` 内の `isInState(EDIT_MODE)` 分岐（569, 1664, 2253, 2388, 2420, 3022行）は、drag開始・drop後の `SPRING_LOADED`/`NORMAL` への自動復帰を抑止するためのものだが、選択の蓄積や操作面はどこにもない。メモ§2の「複数選択は存在しない」と一致する。

**drag/dropとpopup（第1段の土台）**

- dragは `dragndrop/DragController.java`（抽象、`startDrag` 150-200行）と `LauncherDragController.java` が担い、drop先は `DropTargetBar`（`src/com/android/launcher3/DropTargetBar.java:41`）配下の `ButtonDropTarget`（`DeleteDropTarget`、`SecondaryDropTarget` 等）である。
- アイテム長押しpopupは `popup/PopupContainerWithArrow.java:80`。fork側の追加shortcutは `LawnchairLauncher.kt:286-295` の `getSupportedShortcuts()` で `Stream.concat` され、実例が `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt:31-37`（`SystemShortcut.Factory` として `PLACEMENT_LOCK` を返す）。#448はこの仕組みに載る。

**アクセシビリティ・keyboard経由の非drag編集**

- `accessibility/LauncherAccessibilityDelegate.java` が `REMOVE`（72行）、`ADD_TO_WORKSPACE`（78行）、`MOVE`（79行）、`MOVE_TO_WORKSPACE`（80行）を持ち、keyboard shortcut（X/P/M）も定義する（87-101行）。
- `MOVE_TO_WORKSPACE` の実装（484-502行 `moveToWorkspace`）は、フォルダから取り出して `ModelWriter.moveItemInDatabase` でworkspaceの空きセルへ移す。単一アイテムのみで、選択の概念はない。dragを使わない書込み経路の実例として参照に値するが、複数選択の土台にはならない。
- `WorkspaceAccessibilityHelper`（`accessibility/WorkspaceAccessibilityHelper.java:35`）はa11y経由の仮想dragを提供するが、これも単一アイテムである。

**organizer側で再利用できる部品（fork側）**

- captureと適用: `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`（670行）が `captureCurrent`（94行）、`prepareApplyWriteSet`（180行）、`applyWriteSet`（293行）、`requestCorrelatedReload`（428行）を持ち、`LayoutWriteCoordinator` のlease（79行、`src/com/android/launcher3/model/LayoutWriteCoordinator.java:53-58` の `OwnerKind.ORGANIZER`）で排他する。
- 図の描画に使えるデータ: `application/public/LayoutState.kt:27-31` の `LayoutState` は pages/items を持ち、`CanonicalItemState`（117-134行）は placement（`PlacementState`、`GridCell`/`GridSpan`）、title（`OptionalText`）、icon（`OptionalBytes`）を含む。ただし `icon` はfavorites DBの `ICON` 列の転写であり、この列はcustom icon bitmap用である（`LauncherSettings.Favorites` の `ICON`、「The custom icon bitmap.」）。DB値がnullのときは `OptionalBytes.Absent` になる（`RowManifestCodec.kt:373-374`）。つまり通常のアプリ（custom icon未設定）について、capture済みsnapshotから直接得られるのは placement・identity（`TargetKey`）・optional title・optionalなcustom iconバイト列であり、実際のアプリアイコンのバイト列が常に入っているわけではない。図に通常のアプリアイコンを表示するには、`TargetKey.AppKey` とprofileから上流の `IconCache` / `icons/LauncherIcons.java` 側でiconを解決する描画経路が別途必要である。unavailable/private profile等で解決できない場合のfallbackの扱いは、図描画の契約として #449 のspecで決める。
- previewの現状: `application/preview/PlanPreviewProjector.kt:49` と `ui/OrganizationPreviewContent.kt` は変更一覧の「文章」のみを組む。図による変更前後表示は spec 194 で対象外だった（`specs/194-plan-preview-seam/spec.md:38,52`）。
- 適用の安全性: organizer runの適用は recovery point付きの現行安全規約の経路である（メモ§4.3）。視覚的編集画面の確定時の一括適用はこれを再利用できる。

**復元点の上限と頻繁な編集（視覚的編集画面案の検証）**

- `application/lifecycle/RetentionPolicy.kt:24-26` は24時間・`MAX_NON_EXPIRED_POINTS = 3`。ただし Issue #166 の「24h tombstone lockout」は PR #183（merged、`Closes #166`）で修正済みで、最終非復元行（RESTORED等）はtombstoneとなり容量を消費しない（`RetentionPolicy.kt:9-10,105-120`）。admissionがblockedになるのは、active/unresolved（`APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING`）の3点が残る場合のみである（`RetentionPolicy.kt:131`、`store/RecoveryStore.kt:551-552`）。これはprocess死などの異常時だけで、正常な頻繁な編集では起きない。
- 正常編集への実際の制約は容量ではなく「最新3点のみ残る」ことである: 新規checkpoint作成時にcapacityが足りなければ最古の `VERIFIED` 点からevictする（`RetentionPolicy.kt:113-127`）。つまり24時間以内でも、4回目の適用で1回目の復元点は消える。

## Options considered

**案A: ワークスペース上での複数選択（上流のEDIT_MODEに載せる）**

`EDIT_MODE` に入り、アイコンをtapで選択、アクションバー（既存の `DropTargetBar` を再利用または追加）から編集アクションを実行する。

- ベンチマークB2〜B5（重み: tap=1、長押し=2、同一ページdrag=2、ページ越drag=4+越えたページ数。草案時点の仮の重み。後に `docs/engineering/editing-burden-benchmark.md` §4 として確定した重みと同一である。baselineの確定値と目標は同書§6）:
  - baseline（fork現状）: B2 = 5×(長押し2 + 2ページ越drag 6) = 40。B3 = 4×(長押し2 + 1〜2ページ越drag 5〜6) ≈ 26〜28（フォルダ作成を含め+2）。B4 = 6×(長押し2 + 削除targetへのdrag 2) = 24。B5 = 誤り1件の手動復帰 ≈ 8（B2の1項目分と同程度）。
  - 案A: 入口の操作を確定手順に固定する: workspace空きスペース長押し（重み2）→ メニューの「edit_mode」項目tap（重み1）= 3（`WorkspaceTouchListener.java:223` の `showDefaultOptions` → `OptionsPopupView.java:211-215` の `enterHomeGardening`。重みは#441 §4）。B2 = 入口3 + 選択tap 5 + 「ページへ移動」1 + 対象ページ選択1 = 10。B3 = 3+4+1（フォルダ作成）+1 = 9。B4 = 3+6+1 = 10。B5 = #450のUndo 1操作。
  - いずれもB2〜B4の50%削減目標（メモ§4.1）を満たす。案Bとの重み付きコストはほぼ同等である。
- 上流変更量: `Workspace.java`（2,700行超の上流最大級class）への選択状態・選択描画・tapハンドラの追加、`Launcher.java` の状態遷移分岐の拡張、`DragController`/`DragLayer` への選択モードの割込み、アクションバーの新設。概算で上流file 5〜8本、+600〜1,000行以上。選択モードは通常dragのtouch処理と同じsurfaceを共有するため、既存bridge（47 files）と同格では済まない規模の増加になる。
- 安定性リスク: 最大。workspaceのtouch配送・drag開始判定・ページscrollは中核操作であり、ここへの割込みは通常利用のregression riskを直接持ち、実機での破壊・復旧テストの対象になる。
- 再利用: organizerの図preview（Next）との共通化はできない。選択UIはworkspace上にしか存在しないためである。

**案B: fork側の視覚的な編集画面（Compose。現在のホームを図で表示し、選択・操作し、確定時にまとめて適用）**

- ベンチマークB2〜B5: 入口の操作を案Aと同じ確定手順に固定する: workspace空きスペース長押し（重み2）→ 長押しメニューに追加する「編集画面」項目tap（重み1）= 3（Decisionの入口方針のうちworkspace側。#441 §4の重み）。B2 = 入口3 + 選択tap 5 + 「ページへ移動」1 + 対象ページ1 + 確定1 = 11。B3 = 3+4+1+1 = 9。B4 = 3+6+1 = 10。B5 = Undo 1操作。案Aと同等（B2で1操作差、B3/B4は同値）。Organizer hub入口は同一決定における別導線であり、その開く手順のコスト会計は#449のspecで固定する。
- 上流変更量: 最小。画面は `lawnchair/src/app/lawnchair/` 配下の新module（メモ§4.8 の仮称 `app.lawnchair.homeedit`）で完結し、入口（popup項目またはpreference）の追加だけ。上流fileへの変更は0〜1本。patch surfaceの増加は新規project-owned fileでcounted外（baselineの分類規則、`docs/assessment/upstream-patch-surface-baseline.md:49`）。
- 安定性リスク: 最小。workspace/dragのtouch処理に触れない。
- 再利用: capture済み `LayoutState`（座標・title・custom iconの有無。通常アプリの表示iconは `TargetKey` とprofileからの `IconCache` 解決で得る）を図previewの部品にできる。確定時の適用はorganizerの安全な適用経路（recovery point付き）を再利用するため、メモ§4.3のとおりADR-0013の契約ではなく現行安全規約に従う。spec 194が除外した図previewを、編集画面とorganizer previewで共通の描画moduleとして実現できる（メモ§4.5 Nextの「共通化」）。
- コスト: 図グリッドのCompose UIを新規に作る実装量（概算fork側+2,000〜4,000行・テスト含む）。確定までの間、workspace上の実際の状態と画面がずれうる点（適用はまとめて1回）は、capture時点のsnapshot表示として明示すれば受容できる。

**案C: 案Aを第2段、案BをNextとする（またはその逆）の段階的組合せ**

重み付きコストが同等である以上、段階の価値は「先に安定性リスクの低い方を届けるか」で決まる。案Aを先にすると中核操作への割込みが先に発生し、案Bを先にすると上流変更を後回しにできるが、後から案Aを追加しても上流変更量は減らない。

### ベンチマーク値の確定値との照合（2026-09-27起草時に追記。同日のreview指摘により入口手順を確定手順へ修正）

上記のbaseline概算（B2≈40、B3≈26〜28、B4=24）は草案時点の手順に基づく近似である。#441（closed）が `docs/engineering/editing-burden-benchmark.md` として重み・baseline・目標を確定した（§4の重みは本ADRの仮の重みと同一。§6のbaselineは決定的算出の確定値で、drop後の視点移動のswipeを内訳に含むため B2=48、B3=21、B4=25、B5=削除1/移動8。目標は B2≤24、B3≤10、B4≤12、B5=1操作）。案A・案Bの入口は、本ADRが引用する確定手順（workspace空きスペース長押し → メニュー項目tap。重み合計3）に固定して再計算済みであり、修正後の値（案A: 10/9/10、案B: 11/9/10）はいずれも§6のbaselineに対する50%以上の削減と確定目標（B2≤24、B3≤10、B4≤12）を満たす。両案の入口の操作種別は同一であり、選択後のアクションもtapで完結するため、案A/案Bの「同等」という相対判断と4基準での順位はこの確定値によっても変わらない。合否の絶対判定は、#449の実装時に同書§6の会計（baseline確定値と目標）で行う。

## Decision

**第2段（#449）の複数選択の操作面は、案B（fork側の視覚的な編集画面）を推奨する。** 第1段（#448、popup）と案Bの組合せを段階的な構成とする。

**視覚的編集画面の最初の版（メモ§4.3）**: tapによる選択とアクションボタンで操作する（図の中でのdragは後の版）。入口は、ワークスペースの長押しメニューとOrganizer hubの両方に置く。

論拠は4基準での比較である（メモ§4.3）:

1. **B2〜B5の重み付きコスト**: 案Aと案Bは同等（上記の概算。いずれもB2〜B4で50%以上の削減、B5は#450の1操作）。
2. **上流変更量とrebase cost**: 案Bは上流変更0〜1本に対し、案Aは上流file 5〜8本・+600〜1,000行以上。Lawnchair 16のrebaseでは、Launcher3のworkspace/state機械が変更されやすい領域ほどconflictと再検証のcostが大きい（16-devの中身の調査は#442が所有する。本ADRは定性的判断に留める）。
3. **中核操作の安定性リスク**: 案Bはworkspace/dragのtouch処理に触れない。案Aは最大リスク。
4. **organizerの図preview・部分修正への再利用**: 案Bのみが `LayoutState` → 図描画 → 部分修正 → 安全な適用の経路を編集画面とorganizer previewで共通化する。

第1段（#448）のpopup操作面は、`SystemShortcut.Factory` の既存仕組み（`LawnchairLauncher.kt:286-295`、実例 `OrganizerLockShortcut.kt:31-37`）に載るため上流変更が小さく、本ADRはその存在を前提とするのみで追加のbridgeを要求しない。

**上流workspaceへの変更を受け入れる範囲**: 案Bでは、入口（workspace長押しオプションへの項目追加、`LauncherOptionsPopup.kt` のoption listへの追加程度）を除き、上流workspace/drag/popup/LauncherStateへのbridgeを要しない。将来案Aを追加する場合でも、振る舞いはfork側moduleに置き、上流側は呼出しと表示の最小bridgeに限る（メモ§4.3）。並行する独自のUI基盤を上流の中に作らない。

**復元点の扱い（案Bの確定時適用）**: 1回の編集セッションの確定 = 1回の適用 = 1個の復元点とする。復元点は24時間・最新3点で、#166の受付制限はPR #183で解消済み（tombstoneは上限に数えない）のため、正常系でのlockoutは起きない。ただし未確定の復元点が3つある間は新しい適用が止まるので、その理由を画面に示す。`RECOVERY_POINT_ADMISSION_BLOCKED` が編集画面で観測された場合も、理由を利用者に示して再試行を促す（Issue #166のdiagnostic code契約に従う）。復元点の保持件数の拡張（24時間・3点の再設定）は本ADRでは行わない。必要になれば別Issueでrecovery-store変更として扱う。

**確定時のホーム変更（stale）の扱い（メモ§4.3）**: 確定時にホームが変わっていた（stale）場合は書かない。第1版では編集内容を破棄し、理由を示したうえで最新のホームで編集画面を開き直す。編集内容を新しいホームへ載せ直す機能は後の版（Next。メモ§4.5）。

この決定は#442（Android 16/17での日常利用適性とupstream同期判断）の結論が出るまで受け入れない（メモ§4.3、§5）。起草時点（2026-09-27）で#442のresearch（`docs/assessment/issue-442-android16-17-fitness-research.md`）は暫定結論「A/C未確定」を記録しており（同書§6.2。選択肢B=15系同期は不成立）、同書§6.1のC-(2)「ADR-0014の操作面方式が16-devで既に解決されている領域に依存するか」は不成立（操作面の方式は16-dev依存ではない）と評価されており、本ADRの基準2の定性的判断と整合する。#442が「先にrebaseすべき」（C）を最終結論した場合、案A/Bの比較自体は変わらないが、実装順序とpatch surfaceの追加許容を再確認する。

**受入gateの決着（2026-09-28。受入PRで記録）**: #442は2026-09-28に最終結論**C**を確定した（research §6.2。owner record [issuecomment-5863040551](https://github.com/nunu1733/NunuLauncher/issues/442#issuecomment-5863040551)）。C確定下での再確認の結果:

- **案A/Bの比較は不変**: 4基準は15 baseline上での操作面の比較であり、rebaseの要不要の結論に依存しない。むしろC確定は、上流変更量とrebase costを最小化する案B（基準2）の価値を強化する方向である。#442のresearch §6.1でもC-(2)（操作面方式の16-dev依存）は不成立と評価されており、本ADRの基準2と整合する。
- **実装順序**: #448（第1段）と#449（第2段・案B）は15 baseline上での実装を継続する（#442 research §6.2-4）。rebaseの着手は専用Epic+ADRを経てから行われ、rebase後の再検証（#445〜#450）はEpic側で計画する（research §6.3-4）。本ADRの決定によりrebaseが前倒しされることはない。
- **patch surfaceの追加許容**: 案Bの上流変更0〜1本（入口の追加のみ）という方針はC確定後も不変であり、将来のrebase costを増やさない。将来案Aを追加する場合の最小bridge限界も本Decisionどおり維持する。

## Consequences

- #449は、fork側の視覚的編集画面のspecとして書かれる。適用はorganizerの安全な適用経路を再利用するため、#449のPRは `risk: layout-data` の対象になる（organizerの適用moduleを経由するため）。
- 編集アクションの振る舞いmodule（#448と共有、メモ§4.8 の `app.lawnchair.homeedit`）は、popup経由の即時適用（ADR-0013契約）と、編集画面経由の一括適用（現行安全規約・recovery point付き）の2つの書込み経路を持つことになる。この2経路の共存と排他（organizer runとの同時書込み禁止、`LayoutWriteCoordinator` のleaseで保証）を、#448/#449のspecで明示する。
- 図描画moduleはNextのorganizer図previewと共有する見込みであり、その時点で `app.lawnchair.homeedit` とorganizerの依存方向（共有moduleへの抽出）を決める小さなrefactorが発生する。
- 案A（workspace上の複数選択）は採用しないが、`EDIT_MODE` 状態とその入口は上流に既に存在するため、将来の再評価は可能である。本ADRで案Aを永久に排除するものではない。
- patch surfaceの増加は本決定ではほぼ発生しない。発生した場合（入口の追加等）はNFR-010の記録・reviewの対象とする。

## 未解決事項（保守者の判断が必要）

- **重みの仮定** — 決着（2026-09-27起草時。同日のreview指摘により表現を修正）: 本ADRのB2〜B5の概算は tap=1、長押し=2、同一ページdrag=2、ページ越drag=4+越えたページ数 の草案時点の仮の重みに基づく。正式な重み・baseline確定値・目標は#441（closed）が `docs/engineering/editing-burden-benchmark.md` §4/§6として確定し、重みは仮の重みと同一である。案A/案Bの「同等」という結論の根拠は、**この確定重みでの比較において両案の差がB2で1操作、B3/B4で同値であること**に限定して主張する（入口を確定手順の長押し+tapに固定して再計算済み。草案時点に置いていた「重みの取り方に依存しない」という一般の不変性の主張は、入口手順を実際より簡素に想定していたため撤回する）。絶対値の目標判定は同書§6（B2≤24、B3≤10、B4≤12、B5=1操作）に定義済みで、残る判断は#449実装時の合否測定である。
- **案Bの「ずれ」の扱い（開き直すUIの詳細）**: 未解決。stale検出時に編集内容を破棄して最新のホームで開き直す第1版の挙動はDecisionで確定済み（メモ§4.3）。開き直すUIの詳細（理由表示の文言・配置、再captureのタイミング）はspec（#449）で決める。
- **16-devのworkspace/state変更の具体量** — 部分決着（2026-09-28受入時）: #442は最終結論Cを確定したが、16-devのworkspace/state変更量の把握は定性概観まで（research §3。compare API概観のみ）であり、本ADRの基準2も定性判断に留まる。本格的なpatch-surface計測（local object databaseへのfetchと `measure_upstream_patch_surface.py` による計測）は、rebase Epicの `type: upstream` Issueの対象である（research §6.3-1）。定量比較が出た場合、本ADRの基準2は更新されうる（判断の変更は新しいrevisionで記録する）。
