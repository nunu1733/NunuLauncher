# Phase 4 G3' — upstream patch-surface 再測定と正式採用（upstream 16-dev切替）

> Status: Accepted（2026-10-13。ADR-0018 revision 10 Phase 4 cutoverの正本同期の一部として正式採用）
> 正本参照:
> - [docs/engineering/upstream-strategy.md](../engineering/upstream-strategy.md) — Current state（baseline 16-dev commit pin運用）
> - [docs/engineering/building.md](../engineering/building.md) — Baseline header・toolchain実測値
> - [ADR-0018 revision 10](../adr/0018-lawnchair-16-rebase.md) — Decision 6/7（build/toolchain・device test matrix）とPhase 4 cutover実行契約
> - Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 4 / [specs/516-16-rebase-phase4/plan.md](../../specs/516-16-rebase-phase4/plan.md) §5 G3'（ownership inventory）・§7（AC-6 正本同期）
>
> 採用先: [upstream-patch-surface-baseline.json](./upstream-patch-surface-baseline.json)（status=accepted、upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`、main `20f7b9f33dd31501cb7a589b8d941b2da5be816c`、captured_on 2026-10-13）および [upstream-patch-surface-baseline.md](./upstream-patch-surface-baseline.md)（ヘッダ・Acquisition history）。
> 本document以下は2026-10-13のG3'測定作業record（candidate作成時点のreport本文）。candidate JSONは本cutover PRで上記正本へ採用済み（`status` をacceptedへ、`main_commit` を統合head Sのfull SHAへ確定）。採用後の自己整合は `python3 tools/repo-contract/measure_upstream_patch_surface.py --verify` PASS（delta +0/+0/+0）で確認している。

---

# Phase4 G3' — upstream patch-surface 測定とcandidate baseline作成

- 作成日: 2026-10-13
- 対象: ADR-0018 / [specs/516-16-rebase-phase4/plan.md](../../specs/516-16-rebase-phase4/plan.md) §5 G3' ownership inventory
- worktree: `/Users/nunu/Documents/work2/NunuLauncher-phase4-cutover`（branch `issue-516-phase4-cutover`）
- target (HEAD): `20f7b9f33dd31501cb7a589b8d941b2da5be816c`
- upstream (新baseline候補): `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（Lawnchair 16-dev。worktree内object DBに存在、`merge-base --is-ancestor` OK。main repo `git cat-file -e` でも存在確認済み — fetch不要）
- 成果物:
  - candidate baseline: `/tmp/phase4-g3-candidate-baseline.json`（全counted path分類済み・pin 59件再記録・expected_measurement再実測値反映・`--verify` PASS）
  - 本報告: `/tmp/phase4-g3-report.md`
  - 生log: `/tmp/phase4-g3-run1.log`（初回FAIL）、`/tmp/phase4-g3-refresh-pins.json`、`/tmp/phase4-g3-run2.log`（PASS）、`/tmp/phase4-g3-expected.json`、`/tmp/phase4-g3-verify.log`（`--verify` PASS）
- git index・worktree内fileには触れていない（commitなし）。正式採用（`docs/assessment/upstream-patch-surface-baseline.json` / `.md` 更新）はcutover PR所管（plan §5 G3'・AC-6）。

## 1. 測定command列

```bash
# (0) upstream commitの存在とancestor確認
git cat-file -e 43a21b43d7cc7850ab54e14b1a57dc9646685f35^{commit}        # OK
git -C /Users/nunu/Documents/work2/NunuLauncher cat-file -e 43a21b43...^{commit}  # OK（main repo object DBにも存在）
git merge-base --is-ancestor 43a21b43d7cc7850ab54e14b1a57dc9646685f35 HEAD  # OK

# (1) 既定baseline（upstream 505dbc40向け・accepted）をコピーし、
#     upstream_commit/main_commit/status(candidate)/captured_on(2026-10-13)を書換え
#     ※ expected_measurementはscript必須keyのため初回は15実測値を残しdelta比較に使用

# (2) 初回測定 → FAIL（記録: /tmp/phase4-g3-run1.log）
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --baseline-file /tmp/phase4-g3-candidate-baseline.json \
  --upstream 43a21b43d7cc7850ab54e14b1a57dc9646685f35 --target HEAD

# (3) pin対の再記録（path集合は不変、blob対のみ再計算）
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --baseline-file /tmp/phase4-g3-candidate-baseline.json \
  --upstream 43a21b43d7cc7850ab54e14b1a57dc9646685f35 --target HEAD --refresh-pins

# (4) bridge group割当て反映後の再測定 → PASS（記録: /tmp/phase4-g3-run2.log）
#     （同command、割当てはcandidate JSONのbridge_groupsへ反映済み）

# (5) 再実測値の確定出力 → candidate JSONのexpected_measurementへ反映
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --baseline-file /tmp/phase4-g3-candidate-baseline.json \
  --upstream 43a21b43d7cc7850ab54e14b1a57dc9646685f35 --target HEAD --print-expected-measurement

# (6) candidate自己整合の検証 → PASS（delta +0/+0/+0）
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --baseline-file /tmp/phase4-g3-candidate-baseline.json --verify
```

## 2. 測定結果

| 項目 | 15 baseline（accepted） | 16 candidate（今回） | delta |
|---|---|---|---|
| upstream | `505dbc40e615` (15.0.0-beta3.0) | `43a21b43d7` (16-dev) | — |
| main/target | `8af117b6fc` | `20f7b9f33d`（統合head） | — |
| **counted_files** | 105 | **354** | +249 |
| **counted_additions** | +23314 | **+32307** | +8993 |
| **counted_deletions** | −1098 | **−8923** | +7825 |
| project-owned additions | 179 files / +37601 | 179 files / +37690 | ほぼ不変 |
| excluded churn | 1299 files / +221797 / −863 | 2119 files / +1239396 / −12026 | 証跡log類（docs/配下）が主体 |

bridge group別（16 candidate）:

| group | files | +add / −del | 15 baseline |
|---|---|---|---|
| fork-platform-preexisting | **250** | +7944 / −6409 | 8 files, +608/−32 |
| homeedit-edit-surface | 26 | +10107 / −60 | 22 files, +9546/−18 |
| organizer-ui-and-lock-authoring | 13 | +6326 / −45 | 13 files, +6388/−9 |
| model-reload-and-transaction-gates | 13 | +3582 / −395 | 11 files, +3425/−79 |
| deck-retirement | 19 | +1472 / −1678 | 20 files, +765/−862 |
| new-app-destination | 10 | +970 / −142 | 10 files, +941/−10 |
| homeedit-edit-undo | 9 | +953 / −4 | 8 files, +932/−3 |
| layout-schema-and-recovery | 12 | +917 / −188 | 11 files, +677/−84 |
| organizer-home-entry | 2 | +36 / −2 | 2 files, +32/−1 |

**注意**: これらの数値比較はreview要件の表示であり品質判定ではない（script自身の `--enforce-baseline` 設計思想、およびbaseline JSONの `comparison_rule`「Growth is a positive delta ... It is not a quality score」に依拠）。counted増加の大半は後述の「upstream 16-dev差分（forkが未採用の16変更）」の顕在化であり、forkの新規書込み面の増加ではない。

## 3. 15 baselineとの差分要因の分類

counted 354 = 15 member継続101 + 新規counted 253 +（group再割当てなし）。新規counted 253の要因（HEAD blobと 15 upstream (`505dbc40`) / 15 fork main (`8af117b6`) / 16 upstream (`43a21b4`) のblob比較で機械分類）:

| 要因 | files | +add / −del | 説明 |
|---|---|---|---|
| upstream 16-dev差分・未採用（内容15のまま） | 147 | +2628 / −4733 | 16が変更したfileをfork側が15内容のまま保持（統合mergeで15側を採用）。lawnchair moduleのpreferences UI・search・smartspace・theme等が主体 |
| upstream 16-devの移動・削除に対する15 fileの残存 | 16 | +39409* / −0 | 16が移動・削除した旧path（`icons/*` 旧位置、Inter font、search container layout等）がHEADに残存し、16基準では「追加」として出現（A）。*ttf 4件（binary）は除外扱いのため実際のcounted linesには含まず |
| adapt（16構造への再適合） | 80 | +2700 / −1744 | Phase 2 replay（#532）・#545/#563/#559等のapi36/37適合・`LauncherModel.kt`/`FolderIcon.java`等のorganizer bridge再適用 |
| 新規port（15・16の双方に存在しない） | 10 | +916 / −0 | `homeedit/EditSurfaceApplyGate.kt`・`WorkspaceViewLookup.kt`、#528 LAN検索3件、#532 DI 2件（`ForkServiceModule.kt`・`PreferenceManagerModule.kt`）、`ThemedIconDrawable.kt`、`src/debug/` 2件（#528 debug network security config） |

補足:

- 15 member 105件のうち4件が16 diffから消失（upstreamが `.java`→`.kt` 書換え等）: `LauncherModel.java`、`util/MainThreadInitializedObject.java`、`provider/LauncherDbUtils.java`（→後継pathへ写像）、`LauncherPrefs.kt`（deck-retirement分。16側での書換えによりfork差分が消滅、後継割当てなし）。
- excluded churnの急増（+1.24M lines）は `docs/assessment/545-api37-provider-fix-evidence/*.txt` 等の証跡log（docs/ prefix exclusion）が主体。計量上は「可視だがスコア外」。

## 4. bridge groupへの新path写像対応表（15path → 16path）

規則: 既存9 groupのみ使用（新規group不設置）。15構造→16構造で移動したpathは意味的に同じgroupへ写像。迷うpathは意味対応で既存groupへ入れ本表に記録。

### 4.1 意味的後継（明示的写像）

| 15のpath（group） | 16のpath | 割当てgroup | 根拠 |
|---|---|---|---|
| `src/com/android/launcher3/LauncherModel.java`（model-reload） | `src/com/android/launcher3/LauncherModel.kt` | model-reload-and-transaction-gates | organizer相関reload token・`quiesceForRestore`（#58 S3a）bridgeが`.kt`側に再適用されている |
| `src/com/android/launcher3/util/MainThreadInitializedObject.java`（model-reload） | `src/com/android/launcher3/LauncherAppState.kt` | model-reload-and-transaction-gates | Issue #14 startup hook（onPostInit）の後継であるS2c `onLauncherAppStateCreated` init hookがここに移住（diff内comment明記） |
| 〃 | `src/com/android/launcher3/util/DaggerSingletonObject.java` | model-reload-and-transaction-gates | S2c: LauncherProvider/RestoreDbTask向け `getNoCreate`/`executeIfCreated`（restore契約のfail-closed）後継 |
| 〃 | `lawnchair/src/app/lawnchair/util/MainThreadInitializedObject.java` | model-reload-and-transaction-gates | fork側に残存する同一classのcompat copy（onPostInit hookを保持） |
| `src/com/android/launcher3/provider/LauncherDbUtils.java`（layout-schema） | `src/com/android/launcher3/provider/LauncherDbUtils.kt` | layout-schema-and-recovery | 同一責務（provider DB utils）の`.kt`移行先 |
| —（15に無し） | `src/com/android/launcher3/Utilities.java` | layout-schema-and-recovery | `SHOULD_SHOW_FIRST_PAGE_WIDGET`（DatabaseHelperが読むfirst page widget予約判定・ADR-0008系）。`isDevelopersOptionsEnabled`（platform要素）も混在 — 対応表記録として残す |
| `src/com/android/launcher3/folder/Folder.java`（homeedit-edit-undo） | `src/com/android/launcher3/folder/FolderIcon.java` | homeedit-edit-undo | Folder.javaの兄弟view。direct-edit/undo用 `onAdd`/`onRemove` view-only通知bridge（#497） |
| —（15に無し） | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceApplyGate.kt` | homeedit-edit-surface | homeedit packageの適用gate |
| —（15に無し） | `lawnchair/src/app/lawnchair/homeedit/WorkspaceViewLookup.kt` | homeedit-edit-surface | sessionのworkspace view検索 |
| —（15に無し） | `src/com/android/launcher3/Workspace.java` | homeedit-edit-surface | `getHomescreenIconByItemId` bridge（#448/#497 callers = edit surface系のlookup） |
| —（15に無り） | `src/com/android/launcher3/DeviceProfile.java` | homeedit-edit-surface | homeedit/organizer code向けaccessor群（`isTwoPanels`・`topQsbOnFirstScreenEnabled`。capture/projection消費） |

### 4.2 fork-platform-preexistingへの意味対応（242件・領域別）

lawnchair module配下のfork platform機能（15ではupstreamと一致していたためsurface外、16の再構成で差分顕在化）を意味対応で格納:

| 領域 | 件数 | 代表path |
|---|---|---|
| ui（preferences・popup・theme含む） | 92 | `ui/preferences/**`、`ui/popup/LawnchairShortcut.kt` |
| search | 20 | `search/algorithms/**`（#528 LAN 3件の新規port含む） |
| icons | 13 | `icons/*` 旧位置8件（16は `icons/iconpack/`・`icons/picker/` へ移動） |
| quickstep | 12 | `SystemUiProxy.kt`、`RecentsView.java`、`TaskContainer.kt`（#545/#559/#563 api36/37適合） |
| smartspace | 11 | `smartspace/**` |
| util / allapps / data / qsb / gestures / theme / font / nexuslauncher / その他 | 84 | `LawnchairUtils.kt`、`data/folder/**`、`qsb/providers/**` 等 |
| Launcher3 core (src/) | 7 | `PagedView.java`（#563 NaN pivot fix）、`FeatureFlags.java`、`IconShape.java`、`PreviewContext.kt`、`ContextTracker.java` |
| core res/ | 3 | `attrs.xml`（#532 G4 gridType scope bridge）、`dimens.xml`（#545 taskbar 48dp fix）、`folder_shapes.xml` |
| systemUI/shared・wmshell | 7 | `ThumbnailData.kt`（#554 parcel）、`PipFlags.kt`、`multivalentTestsForDevice(less)`（test scaffoldingの内容差異） |
| lawnchair res等 | 5 | `ic_app_drawer.xml`、`search_input_fg.xml`、`search_container_*.xml`（16移動後の旧path残存） |
| src/debug | 2 | #528 debug network security config（debug source set） |
| dagger / preferences DI | 2 | `ForkServiceModule.kt`・`PreferenceManagerModule.kt`（ADR-0018 Decision 9のnon-model binding） |

判断記録: `ForkServiceModule.kt`/`PreferenceManagerModule.kt` は`PreferenceManager.kt`（15でnew-app-destination所属）のDIでも、modelでない横断的service bindingでありfork-platform-preexistingへ意味対応した。`AppCategorizationUtils.kt` は呼び出し元が `LawnchairAlphabeticalAppsList`（app drawer）のみのためorganizer.categoryではなくfork-platformへ。

### 4.3 binary 4件の例外扱い（要・正式採用時review）

`lawnchair/res/font/inter_{bold,medium,regular,semi_bold}.ttf`（16がInter→Google Sans Flex置換、forkは15 fontを残存）:

- script仕様上、binaryのcounted pathは「explicit exclusion」のみ許容（bridge group割当てだと `counted bridge path is binary` でfail）。
- 本taskの制約（pin追加不可・新規group不可）の中で唯一script-validな措置として `explicit_exclusions.paths` へ追加し、exclusions proseに説明文を追記した。
- **正式採用時の推奨**: pinned content exclusion（`build.gradle` と同じ「生産能力のあるpathの内容pair固定」機構）への転換、または当該font残存の削除判断を、cutover PRで所有権reviewとして行うこと。

### 4.4 pin再記録

- 59 pin全件についてupstream=`43a21b4`/target=HEADのblob対を再記録（`--refresh-pins`、path集合は不変）。
- うち58件（`values-*` locale 58file）と `gradle/libs.versions.toml` は内容pairが変化した再記録。`build.gradle` は記録済pairがそのまま一致（upstream・target両側でblob不変）のため実質変更なし。

## 5. script生log要点

- run 1（FAIL）: `content-pinned ... does not match the recorded upstream and target content pair` 59件（`gradle/libs.versions.toml` + locale strings 58件）+ `changed path is neither explicitly excluded, project-owned, nor assigned to a bridge responsibility` **257件**（内binary ttf 4件）。
- run 2（PASS・exit 0）: `Counted upstream/bridge surface: 354 file(s), +32307 / -8923 lines` / `Excluded project-owned additions: 179 file(s), +37690 / -0` / `Explicitly excluded non-production churn: 2119 file(s), +1239396 / -12026` / `Baseline delta: files +249, additions +8993, deletions +7825`（15のexpected_measurementとの比較）→ `PASS: measurement completed with complete bridge ownership.`
- verify（PASS・exit 0）: candidate JSON記載のupstream/main/expected_measurementが完全再現（delta +0/+0/+0）。

## 6. 正式採用（cutover PR）時の留意点

1. `fork-platform-preexisting` の責務説明文は15時代の8 file前提の記述（「backup/restore UI, bugreport, device-profile config, build configuration」）であり、250 file構成では文言の更新が必要。本taskではgroup文言変更は行っていない（新規group不設置の制約に従いpath集合のみ拡張）。
2. Inter font 4件のexplicit exclusionは暫定措置（§4.3）。pin転換か削除かの所有権reviewが必要。
3. `LauncherPrefs.kt`（deck-retirement）は16 diffから自然消滅。groupのpath集合には残置しているが、次回測定では出現しない。
4. counted 354の大半（約2/3）は「16が変更・移動した箇所の15側保持」であり、16構造への追従（upstream採用）が進むほどこのsurfaceは縮小する見込み。数値の大小自体は品質判定ではない（§2注意書き参照）。
