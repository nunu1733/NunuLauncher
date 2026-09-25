# Implementation Plan: 編集負担ベンチマークの定義と計測fixtureの再現手段

> Issue: #441
> Spec: [spec.md](./spec.md)
> Status: draft（Revision 3。Phase 1 re-reviewのfixture geometry・B1配置指摘対応済み、再review待ち）
> Branch: `issue-441-editing-burden-benchmark`

## Current evidence

- 出典の正本: 再焦点化方針メモ（2026-09-24承認、Revision 5）§4.1（R-2）、§4.9（NFR-014）、§11（B-9）、§12（B3）。メモと作業草案（`refocus-drafts/`）はリポジトリ未収録（untracked）であり、#441と同様に本PRではcommitしない。定義文書の内容の正は [Issue #441](https://github.com/nunu1733/NunuLauncher/issues/441) 付録（承認済み草案の全文）であり、本planは正本側pathへの移植時の適合化判断を固定する。
- 参照Issueの対応（起票済みを2026-09-26に `gh issue view` で確認）: RF-02→#441、RF-01→#440、RF-03→#442、RF-06→#446、RF-07→#448、RF-08→#449、RF-10→#451、RF-11→#452。ADR-0015は未作成で起草担当は#446。
- **gridの草案誤り（適合化A-6で修正）**: 付録草案§5は「5列×4行（reference環境と同じ。performance-budgets §2.1）」と記載するが、performance-budgets §2.1のreference gridは `5 rows x 4 columns` であり、Lawnchair既定phone grid `4_by_5`（`lawnchair/res/xml/device_profiles.xml`。`numRows=5`、`numColumns=4`）と一致する。草案の「5列×4行」は列/行の取り違えであり、正本文書では「4列×5行」へ正規化する（意味は「reference環境と同じ既定grid」で不変）。この修正は本planでの記録に加え、PRでIssue #441へも記録する。
- fixture seedingの実績seam: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt:486-523` の `insertFixtureRow`（`Favorites`表へ `CONTAINER_DESKTOP`/`SCREEN`/`CELLX`/`CELLY`等を明示挿入。`generateNewItemId`でid取得）。同ファイルの `snapshotFavorites`（全行読み出し）/ `restoreFavorites`（transactionで全削除→再挿入）/ `waitForModel` / `reloadAndWait` も流用可能な実績実装である。
- **予約領域のauthoritative seam（Phase 1 review Condition 3で確定）**: productionは `LauncherLayoutAdapter.captureCurrent`（`lawnchair/src/app/lawnchair/organizer/application/adapter/`）が `LayoutState.reservedWorkspaceRegions`（platform-owned cell。Issue #155。「never layout items」）を返す。予約重複の受入述語は `ReservationOverlapAcceptance.overlaps`（`lawnchair/src/app/lawnchair/organizer/planning/ReservationOverlapAcceptance.kt`。ADR-0010で唯一の受入述語に固定）。seeding testはこの2つのproduction seamのみを使って予約領域を扱い、DB行の有無を前提にしない。
- **QSB予約と新規アプリ配置の実装事実（Phase 1 re-reviewで指摘され、2026-09-26に実コードで確認）**: `LauncherLayoutAdapter.captureWorkspaceContext`（同adapter 158-168行）は `FeatureFlags.topQsbOnFirstScreenEnabled` が有効のとき、first screenの `GridCell(0,0)` に `GridSpan(idp.numSearchContainerColumns, 1)` を予約する。`InvariantDeviceProfile.java:982-984` により `numSearchContainerColumns` 未指定時の既定は `numColumns`、すなわち4列×5行gridでは予約は4cell（行0全体）で、1ページ目の利用可能cellは16である。また `WorkspaceItemSpaceFinder.findSpaceForItem`（`src/com/android/launcher3/model/WorkspaceItemSpaceFinder.java:55-66`）はQSB有効時に `FIRST_SCREEN_ID` を新規アプリ配置候補から除外するため、新規アプリは2ページ目以降で最初に空きのある既存pageへ置かれる。fixture（A-8）では2ページ目に空きがあるため、B1の新規アプリは **2ページ目** に配置される（付録草案§6が想定した「最終ページ」ではない）。
- **androidTest source setの構成**: `build.gradle:374-375` が `androidTest` source setの `java.srcDirs = ['tests/organizer-instrumentation']` と `manifest.srcFile 'tests/organizer-instrumentation/AndroidManifest.xml'` を定義する。test APKはtarget app（`app.lawnchair.debug`）と別packageでinstallされ、manifest mergeでtest APK側にcomponentを宣言できる。AGPのconnectedAndroidTestは実行後にapp/test APKをuninstallするため、計測sessionでのseedingは手動 `adb install` + `adb shell am instrument` で行いtest APKを保持する（適合化A-12）。
- CI実行形態: manual-organization-ui lane（`.github/workflows/ci.yml` の `organizer-instrumentation-manual-organization-ui-tests`）が `tools/ci/run-manual-organization-ui-instrumentation.sh` の明示class listで `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...` を実行する。lane↔surface対応は `tools/repo-contract/ci_portfolio_map.yml`（ui lane ↔ `surface_organizer_ui`）。path filterは `tests/organizer-instrumentation/app/lawnchair/organizer/ui/**` を `surface_organizer_ui` にmappingする（ci.yml `changes` job）。test APKのmanifest/res変更も同source tree配下でありmappingは変わらない。
- 実機計測の外部依存: 定義文書§7は計測者=保守者、端末=実機Pixel 9a（tegu、API 37。`docs/assessment/ac14-physical-device-evidence.md:4`と同じ端末）と固定する。本sessionに実機は接続されておらず（2026-09-26に `adb devices` で0台確認）、B6の終了判定も測定者判断を含むため、baseline実測（AC-7）と目標確定（AC-8）は実装agentの実行範囲外である。実行可能な検証環境としてreference系emulator（既存AVD `nunu_smoke_api35` 等）が本機にある（AC-2のevidenceに使用）。
- `docs/README.md` は文書map表を持つため、新文書の行追加が必要である。
- spotlessの対象はjava/kotlinのみ（markdown対象外）。markdown linkの検証は `tools/repo-contract/validate_repo_contract.py` が担う。refocus-drafts宛の相対linkはcommitしない限りlink切れになるため、正本文書へ持ち込まない（#440の実績どおり）。

## Design

### 適合化の方針（付録草案 → `docs/engineering/editing-burden-benchmark.md`）

| # | 適合化 | 内容 |
|---|---|---|
| A-1 | status header | 草案の「Status: Draft（未起票・未承認）」を `Accepted` + `Updated: 2026-09-26` + 出典行（メモ§4.1（R-2）/§11 B-9/§12 B3、Issue #441付録、本specへの参照）へ置き換える。草案は2026-09-24にメモ一式として承認済みであり、#440のproduct文書と同じ「承認済み草案の正本反映」である |
| A-2 | RF-IDの置換 | 草案内のRF-IDをIssue番号へ置換する（Current evidenceの対応表）。メモ参照はIssue #441/#439への参照に置き換える |
| A-3 | 未作成ADRへの参照 | ADR-0015への参照は起草担当Issue #446へのlinkにし、将来のfile pathは平文で併記する（#440 A-3の慣行）。ADR accepted時に当該IssueのPRがfile linkへ更新する |
| A-4 | link正規化 | 配置先が `docs/engineering/` になるため相対linkを正規化する（performance-budgets→`./performance-budgets.md`、github-workflow→`../project/github-workflow.md`、specs→`../../specs/...`）。`refocus-drafts/` 宛のlinkは作らない |
| A-5 | 草案表現の確定化 | 重み表から「提案/仮」を除き確定値として載せる（重みは本書承認時に確定するのが草案§4の規定）。未解決事項のうち、NORMAL drag中ページ切替待ち（副指標の実測で記録し定数を要求しない）、B6終了判定（測定者判断+終了時screenshot 1枚）、重複アイコンのfixture作り方（A-10のとおり同一起動先の2行を直接書く）は解消済みとして本文へ織り込む。「upstream releases確認が未実施」の項は起草過程のメタ情報であり#442の担当のため正本からは除く。B1の「一度だけの設定操作は課題コストに含めず別記録」は定義注記として残す |
| A-6 | gridの正規化 | 草案§5の「5列×4行」を「4列×5行」へ修正する（Current evidenceの根拠どおり）。変更履歴に修正の根拠を残す |
| A-7 | baselineと目標の位置づけ | §6のbaseline概算は「手順からの算出値であり実測値ではない」を明記したまま残す。実測記録の受け皿 `docs/assessment/editing-burden-baseline.md`（§7記録形式。計測後に作成）と、目標確定手順（baseline実測後に§6を確定値へ更新、NFR-014をrequirements.mdで確定）を§7に明記する。確定前であることを目標表にstatusとして明示する |
| A-8 | fixture定義の補完 | 草案§5の「約16個」等の概数を、QSB有効・4列×5行での収容可能数に合わせて確定値へ置き換える: ページ0に15個（移動対象5・削除対象3・重複1組2個・通常5）+指定フォルダ1（QSB予約4cellを除く16cellに満杯）、ページ1に12個（削除対象3・B3対象2・重複1組2個・通常5。空き8cell=B1の新規アプリ配置先）、ページ2に8個（B3対象2・通常6。空き12cell=B2の受け皿）。計測対象はidentity（fixture起動先のlabel）で指定し、位置はその時点のfixture layoutで確認する。dock（hotseat）とplatform予約領域は「既定のまま、seedingで変更しない」と明記する |
| A-9 | cell座標の正本 | 正確なcell座標はseedingの決定的配置規則（A-11）とfixture入力表が定め、文書はrole定義と再現手段への参照を持つ（同一入力→同一fixtureを検証するtestが再現性を担保する。文書とコードの二重管理を避ける）。測定が前提とする4列×5行+既定QSBでの配置例を文書に載せてよいが、正本は入力表+配置規則である |
| A-10 | fixture identity方針（Condition 1対応） | fixture起動先はinstrumentation test APK内のactivity-alias 34本（`F01`〜`F34`。互いに異なるcomponent・label・icon。`ACTION_MAIN`+`CATEGORY_LAUNCHER`のintent-filter付き）で供給する。通常アイコン・B3対象・移動対象・削除対象・指定フォルダの内容はそれぞれ異なるidentityを割り当て、同一起動先の重複は指定2組（ページ0とページ1に各1組、各2個）のみとする。重複の判定キーは「component + profileの一致」（メモ§4.5のRF-10定義と同一）とし、定義文書§5とtest assertionの両方に明記する。実在第三者packageには依存しない |
| A-11 | seedingの決定的配置規則と置換境界（Condition 2対応） | 置換対象は「fixture対象graph = `CONTAINER_DESKTOP`のroot行とその子孫行（fixtureが作るフォルダの内容行を含む）」であり、保持対象は「hotseat root行+その子孫」と「予約領域に重なる行」である。削除は子孫→rootの順に行う。insertは、保持行が占有するcellと予約領域を避けたrow-majorのfirst-fitで、固定のitem順（ページ0→1→2。フォルダはページ0の先頭）に配置する。grid差異（エミュレータ/実機の既定grid差）はこの規則が決定的に吸収し、測定は定義文書が固定する4列×5行で行う。収容契約（全rootの収容、1ページ目はQSB予約を除き満杯、2ページ目に空きcell≥1）を満たせない場合はfail-fastする。test oracleは「保持行の前後一致」「fixture全spanの予約領域非交差（`ReservationOverlapAcceptance.overlaps`がfalse）」「収容契約の成立」「2回のseedingで正規化projectionが一致」を含む |
| A-12 | 計測時のseeding永続化 | AC-2〜4の検証testは既定（restore mode）で終了時にfavoritesを復元する。計測sessionのfixture構築は、手動 `adb install`（app APK + test APK）→ `adb shell am instrument -e persist true -e class <新test class> …` で実行し（persist modeは復元をskipする）、test APKを計測中installしたまま保つことでfixture起動先が解決され続けるようにする。手順を定義文書§7に記載する |
| A-13 | QSB状態とB1配置先の確定（re-review指摘対応） | 計測はQSB/Smartspaceの既定状態（`topQsbOnFirstScreenEnabled` 有効）で行うことを定義文書§7に明記する。B1の新規アプリの配置先は、`WorkspaceItemSpaceFinder` の実挙動（QSB有効時は1ページ目を候補から除外し、2ページ目以降で最初に空きのある既存page）に従い **2ページ目の最初の空きcell** として§3.4/§6に記載する（コード根拠: `WorkspaceItemSpaceFinder.java:55-66`）。付録草案§6のB1 baseline「約9（最終ページ想定の内訳）」は内訳との算術不整合（実質10）と配置前提の誤りがあるため、B1 baseline概算を **8** に修正する: 2ページ目へのswipe×1（1）+ 長押し（2）+ 1ページ跨ぎdragで0ページ目の指定フォルダへ追加（4+1=5）。B6は同じ配置規則に従う旨を§3.4に追記する |

### Modules and interfaces

- production codeの変更はなし。新規production interface・moduleも作らない（計測は手動であり、productへのhookはperformance-budgets §10と同じく分離対象）。
- 新規instrumentation test 1本: `app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest`（`tests/organizer-instrumentation/app/lawnchair/organizer/ui/`）。
  - 配置根拠: (1) 既存manual-organization-ui laneが「database-heavy fixture」を所有するlaneであり同一のseam（`launcher.model.modelDbController`）を使う実績testと同居する、(2) path filter上で既に `surface_organizer_ui` にmappingされるため新規mapping・新規laneが不要、(3) `app.lawnchair.organizer.ui` packageはorganizer UI契約testの所在地であり、本testはそのseam上のfixture契約testとして分類する。
  - seam: `ManualOrganizationProductionE2EInstrumentationTest` と同一（modelDbControllerのDB直接insert + model reload + snapshot読み出し）+ 予約領域のproduction seam（`LauncherLayoutAdapter.captureCurrent` → `LayoutState.reservedWorkspaceRegions`、`ReservationOverlapAcceptance.overlaps`）。内部実装の個別検証はせず、既存seam経由で契約を検証する。
- fixture起動先: `tests/organizer-instrumentation/AndroidManifest.xml` へactivity 1本+activity-alias 35本（A-10）と、`tests/organizer-instrumentation/res/` にalias icon用drawable 35件を追加する。`build.gradle` のandroidTest source setへ `res.srcDirs` を追加する。test APKは計測・testの間だけinstallされるものであり、製品manifest・製品buildは変えない。
- CI: `tools/ci/run-manual-organization-ui-instrumentation.sh` のclass listへ新test classを追加する。lane↔surface edge（`ci_portfolio_map.yml`）の変更はなし。`docs/engineering/ci-test-portfolio.md` の当該lane行（coverage説明・cost）を同じPRで更新する。

### Data flow（fixture seeding test）

1. model起動完了を待ち、test開始時のfavorites全行をsnapshotする（restore用baseline）。production capture（`LauncherLayoutAdapter.captureCurrent`）で予約領域とgrid、保持対象行（hotseat root+子孫、予約領域重複行）のsnapshotを取得する。
2. seed処理（同一関数、入力はfixture入力表のみ）: (a) 保持対象を除くfixture対象graph（desktop root+子孫）を子孫→rootの順に削除、(b) fixture入力表のitem順に従い、保持行の占有cellと予約領域を避けるrow-major first-fitでdesktop行・フォルダ行・フォルダ内容行をinsert（A-11）。
3. seed処理を1回実行→reload→正規化projection P1（`TITLE`/`INTENT`/`CONTAINER`/`SCREEN`/`CELLX`/`CELLY`/`SPANX`/`SPANY`/`ITEM_TYPE`/`PROFILE_ID`/`RANK`。folder内容の`CONTAINER`はfixtureフォルダ行との対応でid解決。状態依存の`_ID`/`MODIFIED`は除外）を取得。`ReservationOverlapAcceptance.overlaps`が全fixture項目でfalseであること、identity一意性と重複2組の計数（A-10）を検証。
4. seed処理をもう1回実行（削除→insertの全体が再度走る）→reload→projection P2を取得し、`P1 == P2` を検証する（同一入力→同一fixture）。
5. 保持対象（hotseat root+子孫、予約領域重複行）がstep 1のsnapshotと一致することを検証する。
6. restore mode（既定）では開始時snapshotを復元し、復元後のfavorites一致を検証して終了する（永続残SIなし）。persist mode（`-e persist true`。計測setup専用、A-12）では復元をskipする。

### Alternatives rejected

- `fill_screens.py` 流用 / backup restore instrumentation流用: 草案§5が排除済み（package名・権限が現行と不合 / fixture用途に過大）。
- fixture用の新CI lane: quality-strategyの「既存laneへの統合で済む場合は独立laneを作らない」により不採用。
- 正確なcell座標の文書記載: 文書とコードの二重管理になるため不採用（A-9）。
- 計測の自動化（UI Automator等でdragを自動計測）: 草案§7が計測者=保守者のtouch操作を対象とし、B6終了判定は測定者判断を含む。CI自動化はnon-goal。
- 全fixtureアイコンを自packageのlauncher activity 1本に統一（初版plan）: Phase 1 review Condition 1のとおり、全アイコンが同一起動先になりB7の課題定義・対象識別が崩れるため不採用（A-10へ変更）。
- 製品manifestへのfixture component追加: 可観測な製品挙動に触れる可能性とpatch surface増加があるため不採用（test APK manifestに限定）。
- 新Gradle application moduleとしてのfixture APK: 同等のidentityをtest APK manifestで賄えるため、module追加・settings.gradle・CI path mappingの変更を避ける（test APKのinstall保持はA-12で解決）。
- 2ページ目・3ページ目を満杯にしてB1の新規アプリを最終ページへ出す構成: 付録草案§5のページ別個数（ページ1約12個）と矛盾し、B2の受け皿の空きも失うため不採用。実挙動（2ページ目配置）に合わせてB1の経路と概算を更新する（A-13）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/engineering/editing-burden-benchmark.md` | 新設。付録草案+適合化A-1〜A-13 | ベンチマーク定義の正本（Issue scope 1） |
| `docs/README.md` | 文書mapへ新文書の行を追加 | docs indexの慣行 |
| `specs/441-editing-burden-benchmark/spec.md` `plan.md` | 本spec/plan（Phase 1） | workflow契約 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.kt` | 新規。fixture seeding+同一性・保持・非交差・identity検証 | Issue scope 2 / AC-2〜4 |
| `tests/organizer-instrumentation/AndroidManifest.xml` | fixture用activity 1本+activity-alias 35本の追加 | fixture identity供給（A-10）。test APK限定 |
| `tests/organizer-instrumentation/res/`（新規） | alias icon用drawable 35件 | A-10（互いに異なるicon） |
| `build.gradle` | androidTest source setへ `res.srcDirs = ['tests/organizer-instrumentation/res']` を追加 | alias iconのres解決 |
| `tools/ci/run-manual-organization-ui-instrumentation.sh` | class listへ1件追加 | 既存laneへの統合（AC-5） |
| `docs/engineering/ci-test-portfolio.md` | manual-organization-ui lane行のcoverage説明を更新（lane↔surface edge変更なし） | quality-strategyの新test審査規則 |
| `CONTEXT.md` | Domain languageの4用語を追加 | spec承認時の反映 |

## Migration and recovery

- schema/rule/runtime migrationなし。製品挙動への影響なし（test process内の完了。fixture componentはtest APK限定）。
- 戻し方は `git revert` のみ。seeding test自体はrestore modeでfavoritesを復元する（failure時を含む。復元はfinallyで行う）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 定義文書の節構成と付録草案+適合化の対照確認（PR本文へ対照表）。markdown link検証 | `python3 tools/repo-contract/validate_repo_contract.py` |
| AC-2 | 新testの実行記録（P1==P2の成立、収容契約=全rootの収容・1ページ目QSB予約除き満杯・2ページ目空きcell≥1の成立）。ローカルemulator（既存AVD、API 35系）で実行しcommand・環境・結果をPRへ記録 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest` |
| AC-3 | 同test内assertion（保持行の前後一致、`ReservationOverlapAcceptance.overlaps`全項目false、復元前後一致）+ PR本文 | 同上 |
| AC-4 | 同test内assertion（identity一意性・重複2組計数）+ 定義文書§5との対照 | 同上 |
| AC-5 | PR本文へtest-audit審査項目を記載。portfolio整合validator | `python3 tools/repo-contract/test_validate_ci_portfolio.py` + CI portfolio job |
| AC-6 | spotless | `./gradlew spotlessCheck` |
| AC-7/AC-8 | 後続ステップ（保守者が実機Pixel 9aで計測→`docs/assessment/editing-burden-baseline.md`作成→§6確定+NFR-014確定）。本PRでは実施せず、PR本文に残置事項として明記する | 実機（保守者） |

test-audit審査（AC-5。実装前に確定しPRへ記載する）:

1. 既存test/laneで不足する理由: 既存E2E testはorganizer run契約を検証し、fixture seedingの「同一入力→同一fixture」「保持対象の不変」「予約領域非交差」「identity構成」の契約を検証するtestは存在しない。
2. oracleの配置: 最も低い実行可能層はinstrumentation（実frameworkの`modelDbController`/reload経路と実PackageManagerのcomponent解決に依存するためJVMでは成立しない）。計測fixtureの再現性という契約自体が端末上のLauncher DB+model+package解決を対象とする。
3. impact surface: `surface_organizer_ui`（manual-organization-ui laneの既存database-heavy fixture面）。起動条件は既存laneと同一（`full` または `surface_organizer_ui`）。test APKのmanifest/res変更も同path配下でありmappingは不変。
4. 重複: 既存laneの他class（organizer run E2E、preferences、diagnostics route）と契約が重複しない。
5. 恒久PR gateへの昇格: 新規gateではなく既存laneへの追加であり、scheduled sweepのみではfixture seam（reload、package解決、予約領域capture）の退行をsurface_organizer_ui変更時に検知できないため、lane本体へ入れる。
6. map/portfolio更新: `ci_portfolio_map.yml`はedge不変のため変更なし。`ci-test-portfolio.md`のlane行を同じPRで更新する。

## Documentation updates

- [ ] `docs/engineering/editing-burden-benchmark.md`（新設。Phase 2）
- [ ] `docs/README.md` 行追加（Phase 2）
- [ ] `CONTEXT.md` 4用語（Phase 2）
- [ ] spec status遷移（Phase 1 review承認で `accepted`。Issue #441全体の完了（AC-7/8）で `implemented`）
- [ ] 変更しないもの: DESIGN.md / ADR / AGENTS.md / requirements.md（NFR-014の確定はAC-8で後続実施）/ 製品manifest

## Execution checklist

- [x] Issue・付録草案・関連正本の確認（AGENTS.md必読順）
- [x] Phase 1 review（ChatGPT）の指摘対応（Revision 2。Conditions 1〜4 → spec/plan修正・コード事実の確認）
- [x] Phase 1 re-review（ChatGPT）の指摘対応（Revision 3。fixture geometry・QSB状態・B1配置先の確定。`LauncherLayoutAdapter` 158-168行 / `InvariantDeviceProfile.java:982-984` / `WorkspaceItemSpaceFinder.java:55-66` を実コードで確認）
- [ ] Phase 1 再review（ChatGPT）のクリア
- [ ] spec statusを `accepted` へ更新（再review承認後）
- [ ] Phase 2: 定義文書の作成（適合化A-1〜A-13）
- [ ] Phase 2: test-audit skillの適用と新test実装（manifest alias、drawable、build.gradle res.srcDirsを含む）
- [ ] Phase 2: emulatorでのinstrumentation実行とevidence記録（AC-2〜4）
- [ ] Phase 2: spotlessCheck・repo-contract validatorの実行（AC-1/5/6）
- [ ] Phase 2 review（ChatGPT）と指摘対応
- [ ] PR作成（`Refs #441`。AC-7/8を残置事項として明記）+ 独立監査（general-purpose subagent）+ 保守者承認・merge
- [ ] 後続（保守者）: 実機計測→baseline記録→目標確定→NFR-014確定→最終PRが `Closes #441`
