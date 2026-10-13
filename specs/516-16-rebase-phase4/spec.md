---
status: proposed
---

# Spec: Lawnchair 16 rebase Phase 4 — baseline切替cutover・T9・正本同期（Epic #516）

> Status: proposed（本PRのreviewでaccept判定後にacceptedへ遷移。受入は本PRのmergeで完了）
> Risk tier: H（baseline切替・Launcher DB互換・rollback契約を含む。高リスクPR独立エビデンス要件の対象）
> 対応: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 4 / [ADR-0018 revision 10](../../docs/adr/0018-lawnchair-16-rebase.md)
> 前提: Phase 2（[#532](https://github.com/nunu1733/NunuLauncher/issues/532) / PR #535）・Phase 3（[matrix §15](../../docs/assessment/issue-516-phase3-closure-matrix.md)）完了済み。Phase 3残項目・tracked limitations（#576/#586）は本specの対象外。

## 目的

ADR-0018 Decision 3/5/6/7がPhase 4に予約した「保持stackとmain修正を統合した単一cutover headによるbaseline切替」「rollback実証」「T9実APK互換」「正本同期」を完了し、Epic #516のexit criteriaを満たす。

## 成果（observable）

1. **cutover head（統合branch）**: ADR-0018 Decision 3 revision 10の統合集合（保持stack全head＋main post-replay全unit）を含む単一branch。
2. **cutover merge**: mainへの変更が単一merge commit（機構はADR Decision 3 revision 10）。merge直前main headにrollback点tag `pre-lawnchair16-<date>` を付与。
3. **T9証跡**: rollback点の15 fork APKとcutover headの16 APKを同一dataで入替（15→16→15→16）し、各段階を独立cold-process読取で比較した証跡。
4. **rollback証明**: cutover mergeのrevertが旧main treeへ正確に戻る機械検証と、T9 rollback leg。
5. **正本同期**: upstream-strategy.md / building.md / patch-surface baseline（新upstream ancestor基準での再採択）/ ADR-0018 / AGENTS.md / assessment一式の更新。

## 受入条件（AC）

### AC-1 統合完全性（cutover head）

- plan §3対照表の全unitが統合headに適用済みであること（各unitの適用commit SHA・適用方式を対照表へ記録）。
- cutoverはADR Decision 3 revision 10の機構で行う: 統合source head Sでcutover PRを作成し、SのCIと独立audit（AC-7）を経て、audit記録をdocs-only commit Eとして同一PRへ追加した後、**R（SHA固定したcutover時点の `main` head。tag `pre-lawnchair16-<date>`）を第2 parentとする `-s ours` merge commit M（M^1=E、M^2=R）を最終PR head**とし、main上のPR merge commit Oをcutover mergeとする。**S→Mの差分がdocs-only（audit文書・merge構造のみ）であること**、**OのtreeがMのtreeと完全一致**すること（`git diff` が空）、merge時点で `O^1 == R == tag == M^2` であること。mainが固定後に進んだ場合は、追加unitを対照表へ追記しG1'〜G4'（T9のR依存legを含む）から再実行してM・tag・PRを作り直す。
- cutover後に `git diff --name-status <pre-lawnchair16-tag>..<cutover merge O>` の削除エントリ（D）がすべて意図的dropとして説明済みであること。意図しないmain専用pathの消失を残さない。

### AC-2 CI

- cutover PRのheadで `pull_request` event CI（ci.yml）の `final-status` がsuccess。cutover headのsource変更で無効化したgateがないこと。

### AC-3 T9 emulator leg（API 36）

次を同一emulator dataで実施し、全段階でcrash/ANRなし・data保持を確認。**15 APKは事前にSHA固定したR_candidateからbuildしたものであること**（T9証跡にR_candidate SHAを記録）:

1. 15 APK（rollback点Rのbuild）で代表dataセットを作成（folder作成・hotseat移動・設定変更を含む日常操作）。独立cold読取 read0 を取得。
2. 16 APK（cutover head build）を `adb install -r` で入替（data消去なし）。cold起動し正常動作を確認。独立cold読取 read1 を取得。**read0→read1比較でschema/favorites行/preferences/recoveryに非説明な差分がないこと**。
3. 15 APKを再install（rollback leg）。cold起動し正常動作を確認。独立cold読取 read2 を取得。**read1→read2比較で16が書き込んだdataを15が保持していること**（行喪失なし。preferencesの再直列化は分類記録の上で許容）。
4. 16 APKを再install。cold起動し正常動作を確認。独立cold読取 read3 を取得。read0→read3比較。

「独立cold読取」は各段階で `am force-stop` 後、`run-as` でDB（launcher.db・journal/wal）・shared_preferences・datastore・recovery artifactsを取り出し、file hash・sqlite行dump・prefs key/valueで比較する。APK identity（sha256・versionName）を各install前に記録する。schema32 historical binaryはRの代替としない。

### AC-4 T9実機leg（API 37 Pixel 9a）— owner decision

- 実機leg（owner手順）を実施するか、emulator証跡と残存リスク（実機固有migration経路・provider構成差）を明示したowner decisionをADR-0018へ記録する（ADR Decision 5の「実証しない場合」経路）。owner不在のまま実施済み扱いにしない。

### AC-5 rollback証明

**rollback対象はmain上のcutover merge Oに一意に固定する**（ADR Decision 3/5 revision 10）。

1. merge前の予備証明: 統合branch上の `-s ours` merge commit Mに対する `git revert -m 2 M --no-commit` dry-runの結果treeがR_candidate（tag `pre-lawnchair16-<date>`）のtreeと**hash一致**することを記録（検証後abort）。
2. merge後の最終証明: main上のOに対する `git revert -m 1 O --no-commit` dry-runの結果treeがRのtreeと**hash一致**することを記録（検証後abortし、mainへはpushしない）。
3. T9 rollback leg（AC-3.3）。
4. 上記の手順（revert手順・release再発行path）をassessmentへ記録。実運用のrollbackはmain上で `git revert -m 1 O` をPRとして実行する（protected mainへのforce-resetは行わない）。

### AC-6 正本同期（cutover PRまたはその前提docs PRで更新）

- `docs/engineering/upstream-strategy.md`: Current state（baseline commit `43a21b43d7cc7850ab54e14b1a57dc9646685f35`・upstream branch 16-dev・toolchain・更新日）。
- `docs/engineering/building.md`: toolchain表（JDK 21 / Gradle 9.8.0 / AGP 9.4.1 / Kotlin 2.4.20 / compileSdk 37 minor 2 / buildTools 37.0.0 / targetSdk 37 / minSdk 26 / submodule `7d9e92bd`）と検証済みcommand・成果物の実測値。
- patch-surface baseline（`docs/assessment/upstream-patch-surface-baseline.json` / `.md`）: upstream ancestor `43a21b43d7` 基準でcutover head上に再採択（pin再記録・expected_measurement更新・bridge_groupsの新path写像・Acquisition history追記）。**旧15 baselineの数値比較を品質判定に流用しない**。
- `docs/adr/0018-lawnchair-16-rebase.md`: cutover実行記録（統合head SHA・merge SHA・tag SHA・T9結果・rollback証明結果）。
- `AGENTS.md` プロジェクト状態のbaseline行。
- assessment: Phase 4 cutover記録（統合対照表・gates結果・T9証跡index・rollback証明）＋高リスクPR独立audit記録 `docs/assessment/pr-<cutover PR番号>-<slug>.md`。

### AC-7 独立audit

- 高リスクPR要件（AGENTS.md「高リスクPRの独立エビデンス」）どおり、cutover PRに `risk: layout-data` / `risk: migration` labelを付け、**統合source head Sに対する別session audit**の記録を `docs/assessment/pr-<cutover PR番号>-<slug>.md` として同一PRのdocs-only commit（E）へ含める（記録: Head SHA=S・参照契約・実行test表面・Sの成功CI run link）。最終head Mまでの差分がdocs-onlyであることを条件に、Sのauditで要件を満たす（docs以外の変更がS→Mで入った場合はauditをやり直す）。high-risk-gate workflowのPASSをMでも確認する。

### AC-8 保持PR・子Issue処遇の記録

- #535/#538/#541/#543/#546/#550/#558/#575と、#532/#545/#554/#574の処遇（cutoverへの到達経路・ancestry証拠・close）をEpic #516へ記録する。

## 非対象

- Phase 3残項目・tracked limitations（#576/#586）の原因解明。rerun成功で解消扱いにしない（matrix §15の契約を維持）。
- upstream anchor刷新（16-devの新commit追従）・Phase 0の不運搬33 commitsの再棚卸し。
- 新機能・契約変更。統合で契約変更が必要になった場合は実装を停止しADR/specへ戻す。
- Lawnchair 16以降の次期upstream同期。

## 失敗時の振る舞い

- 統合branchのgate（build/format/unit/CI）失敗: cutover merge不可。修正は統合branchへのappend commit（fail-closed）。mainは変更しない。
- T9でdata破壊・rollback失敗を観測: baseline切替完了とは扱わず、原因調査を子Issueへ分離しPhase 4を維持する（ADR Decision 5）。
- cutover後のregression: AC-5のrevert手順で旧baselineへ戻し、追跡Issueで処理する。

## Open questions

なし（実機legの実施/代替はAC-4のowner decisionとして処理）。
