# Implementation Plan: 編集負担ベンチマークの定義と計測fixtureの再現手段

> Issue: #441
> Spec: [spec.md](./spec.md)
> Status: draft（Phase 1 review待ち）
> Branch: `issue-441-editing-burden-benchmark`

## Current evidence

- 出典の正本: 再焦点化方針メモ（2026-09-24承認、Revision 5）§4.1（R-2）、§4.9（NFR-014）、§11（B-9）、§12（B3）。メモと作業草案（`refocus-drafts/`）はリポジトリ未収録（untracked）であり、#441と同様に本PRではcommitしない。定義文書の内容の正は [Issue #441](https://github.com/nunu1733/NunuLauncher/issues/441) 付録（承認済み草案の全文）であり、本planは正本側pathへの移植時の適合化判断を固定する。
- 参照Issueの対応（起票済みを2026-09-26に `gh issue view` で確認）: RF-02→#441、RF-01→#440、RF-03→#442、RF-06→#446、RF-07→#448、RF-08→#449、RF-10→#451、RF-11→#452。ADR-0015は未作成で起草担当は#446。
- **gridの草案誤り（適合化A-6で修正）**: 付録草案§5は「5列×4行（reference環境と同じ。performance-budgets §2.1）」と記載するが、performance-budgets §2.1のreference gridは `5 rows x 4 columns` であり、Lawnchair既定phone grid `4_by_5`（`lawnchair/res/xml/device_profiles.xml`。`numRows=5`、`numColumns=4`）と一致する。草案の「5列×4行」は列/行の取り違えであり、正本文書では「4列×5行」へ正規化する（意味は「reference環境と同じ既定grid」で不変）。この修正は本planでの記録に加え、PRでIssue #441へも記録する。
- fixture seedingの実績seam: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt:486-523` の `insertFixtureRow`（`Favorites`表へ `CONTAINER_DESKTOP`/`SCREEN`/`CELLX`/`CELLY`等を明示挿入。`generateNewItemId`でid取得）。同ファイルの `snapshotFavorites`（全行読み出し）/ `restoreFavorites`（transactionで全削除→再挿入）/ `waitForModel` / `reloadAndWait` も流用可能な実績実装である。
- CI実行形態: manual-organization-ui lane（`.github/workflows/ci.yml` の `organizer-instrumentation-manual-organization-ui-tests`）が `tools/ci/run-manual-organization-ui-instrumentation.sh` の明示class listで `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...` を実行する。lane↔surface対応は `tools/repo-contract/ci_portfolio_map.yml`（ui lane ↔ `surface_organizer_ui`）。path filterは `tests/organizer-instrumentation/app/lawnchair/organizer/ui/**` を `surface_organizer_ui` にmappingする（ci.yml `changes` job）。
- 実機計測の外部依存: 定義文書§7は計測者=保守者、端末=実機Pixel 9a（tegu、API 37。`docs/assessment/ac14-physical-device-evidence.md:4`と同じ端末）と固定する。本sessionに実機は接続されておらず（2026-09-26に `adb devices` で0台確認）、B6の終了判定も測定者判断を含むため、baseline実測（AC-6）と目標確定（AC-7）は実装agentの実行範囲外である。実行可能な検証環境としてreference系emulator（既存AVD `nunu_smoke_api35` 等）が本機にある（AC-2のevidenceに使用）。
- `docs/README.md` は文書map表を持つため、新文書の行追加が必要である。
- spotlessの対象はjava/kotlinのみ（markdown対象外）。markdown linkの検証は `tools/repo-contract/validate_repo_contract.py` が担う。refocus-drafts宛の相対linkはcommitしない限りlink切れになるため、正本文書へ持ち込まない（#440の実績どおり）。
- QSB予約領域: 上流Lawnchair/Launcher3のQSB（検索bar）はscreen 0のplatform予約cellであり、favorites上の行として存在するか否かはbuild依存の可能性がある。実装時にemulator上のDBを確認し、seedingの保持対象（hotseat行+QSB行があればそれ）を確定する（spec AC-3）。

## Design

### 適合化の方針（付録草案 → `docs/engineering/editing-burden-benchmark.md`）

| # | 適合化 | 内容 |
|---|---|---|
| A-1 | status header | 草案の「Status: Draft（未起票・未承認）」を `Accepted` + `Updated: 2026-09-26` + 出典行（メモ§4.1（R-2）/§11 B-9/§12 B3、Issue #441付録、本specへの参照）へ置き換える。草案は2026-09-24にメモ一式として承認済みであり、#440のproduct文書と同じ「承認済み草案の正本反映」である |
| A-2 | RF-IDの置換 | 草案内のRF-IDをIssue番号へ置換する（Current evidenceの対応表）。メモ参照はIssue #441/#439への参照に置き換える |
| A-3 | 未作成ADRへの参照 | ADR-0015への参照は起草担当Issue #446へのlinkにし、将来のfile pathは平文で併記する（#440 A-3の慣行）。ADR accepted時に当該IssueのPRがfile linkへ更新する |
| A-4 | link正規化 | 配置先が `docs/engineering/` になるため相対linkを正規化する（performance-budgets→`./performance-budgets.md`、github-workflow→`../project/github-workflow.md`、specs→`../../specs/...`）。`refocus-drafts/` 宛のlinkは作らない |
| A-5 | 草案表現の確定化 | 重み表から「提案/仮」を除き確定値として載せる（重みは本書承認時に確定するのが草案§4の規定）。未解決事項のうち、NORMAL drag中ページ切替待ち（副指標の実測で記録し定数を要求しない）、B6終了判定（測定者判断+終了時screenshot 1枚）、重複アイコンのfixture作り方（本PRのinstrumentation seamで直接2行書く）はspec Open questions/本planのとおり解消済みとして本文へ織り込む。「upstream releases確認が未実施」の項は起草過程のメタ情報であり#442の担当のため正本からは除く。B1の「一度だけの設定操作は課題コストに含めず別記録」は定義注記として残す |
| A-6 | gridの正規化 | 草案§5の「5列×4行」を「4列×5行」へ修正する（Current evidenceの根拠どおり）。変更履歴に修正の根拠を残す |
| A-7 | baselineと目標の位置づけ | §6のbaseline概算は「手順からの算出値であり実測値ではない」を明記したまま残す。実測記録の受け皿 `docs/assessment/editing-burden-baseline.md`（§7記録形式。計測後に作成）と、目標確定手順（baseline実測後に§6を確定値へ更新、NFR-014をrequirements.mdで確定）を§7に明記する。確定前であることを目標表にstatusとして明示する |
| A-8 | fixture定義の補完 | 草案§5の「約16個」等の概数に、role構成を確定値として併記する: 移動対象5個（ページ0、B2）、削除対象6個（ページ0に3個+ページ1に3個、B4）、B3対象4個（ページ1に2個+ページ2に2個）、重複2組（各2個）、B1指定フォルダ（ページ0、seed済みアイテム1個を含む。空フォルダはdrag対象として振る舞いが不安定なため）。計測対象は位置（page/row/column）で指定する（fixtureアイコンは同一起動先で視覚的に識別できないため）。dock（hotseat）とplatform予約領域（QSB）は「既定のまま、seedingで変更しない」と明記する |
| A-9 | cell座標の正本 | 正確なcell座標はinstrumentationのfixture入力表を正とし、文書はrole定義と再現手段への参照を持つ（同一入力→同一fixtureを検証するtestが再現性を担保する。文書とコードの二重管理を避ける） |

### Modules and interfaces

- production codeの変更はなし。新規production interface・moduleも作らない（計測は手動であり、productへのhookはperformance-budgets §10と同じく分離対象）。
- 新規instrumentation test 1本: `app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest`（`tests/organizer-instrumentation/app/lawnchair/organizer/ui/`）。
  - 配置根拠: (1) 既存manual-organization-ui laneが「database-heavy fixture」を所有するlaneであり同一のseam（`launcher.model.modelDbController`）を使う実績testと同居する、(2) path filter上で既に `surface_organizer_ui` にmappingされるため新規mapping・新規laneが不要、(3) `app.lawnchair.organizer.ui` packageはorganizer UI契約testの所在地であり、本testはそのseam上のfixture契約testとして分類する。
  - seam: `ManualOrganizationProductionE2EInstrumentationTest` と同一（modelDbControllerのDB直接insert + model reload + snapshot読み出し）。内部実装の個別検証はせず、DB snapshotという既存seam経由で契約を検証する。
- CI: `tools/ci/run-manual-organization-ui-instrumentation.sh` のclass listへ新test classを追加する。lane↔surface edge（`ci_portfolio_map.yml`）の変更はなし。`docs/engineering/ci-test-portfolio.md` の当該lane行（coverage説明・cost）を同じPRで更新する。

### Data flow（fixture seeding test）

1. model起動完了を待ち、test開始時のfavorites全行をsnapshotする（復元用。hotseat保持の検証baselineにも使う）。
2. fixture入力表（定義文書§5のrole構成に対応する決定的な定数表。page/cell/title/intent）からdesktop行+指定フォルダ（folder行1+内容行1）+重複2組をinsertする。
3. modelをreloadし、配置正規化projection P1（`TITLE`/`INTENT`/`CONTAINER`/`SCREEN`/`CELLX`/`CELLY`/`SPANX`/`SPANY`/`ITEM_TYPE`/`PROFILE_ID`/`RANK`。folder内容の`CONTAINER`はfolder行との対応でid解決。状態依存の`_ID`/`MODIFIED`は除外）を読む。
4. desktop行を削除し（hotseat行とQSB行があれば保持）、同一入力で再insert → reload → projection P2を読む。
5. `P1 == P2` を検証する（同一入力→同一fixture）。加えてfixture不変条件（page数3、ページ別アイコン数、指定フォルダ1+内容1、重複2組、範囲内・重なりなし、hotseat行がtest開始時と一致）を検証する。
6. 開始時snapshotを復元し、復元後のfavorites一致を検証して終了する（永続残SIなし）。

### Alternatives rejected

- `fill_screens.py` 流用 / backup restore instrumentation流用: 草案§5が排除済み（package名・権限が現行と不合 / fixture用途に過大）。
- fixture用の新CI lane: quality-strategyの「既存laneへの統合で済む場合は独立laneを作らない」により不採用。
- 正確なcell座標の文書記載: 文書とコードの二重管理になるため不採用（A-9）。
- 計測の自動化（UI Automator等でdragを自動計測）: 草案§7が計測者=保守者のtouch操作を対象とし、B6終了判定は測定者判断を含む。CI自動化はnon-goal。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `docs/engineering/editing-burden-benchmark.md` | 新設。付録草案+適合化A-1〜A-9 | ベンチマーク定義の正本（Issue scope 1） |
| `docs/README.md` | 文書mapへ新文書の行を追加 | docs indexの慣行 |
| `specs/441-editing-burden-benchmark/spec.md` `plan.md` | 本spec/plan（Phase 1） | workflow契約 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.kt` | 新規。fixture seeding+同一性検証 | Issue scope 2 / AC-2 |
| `tools/ci/run-manual-organization-ui-instrumentation.sh` | class listへ1件追加 | 既存laneへの統合（AC-4） |
| `docs/engineering/ci-test-portfolio.md` | manual-organization-ui lane行のcoverage説明を更新（lane↔surface edge変更なし） | quality-strategyの新test審査規則 |
| `CONTEXT.md` | Domain languageの3用語を追加 | spec承認時の反映 |

## Migration and recovery

- schema/rule/runtime migrationなし。製品挙動への影響なし（test process内の完了）。
- 戻し方は `git revert` のみ。seeding test自体はtest内でfavoritesを復元する（failure時を含む。復元はfinallyで行う）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 定義文書の節構成と付録草案+適合化の対照確認（PR本文へ対照表）。markdown link検証 | `python3 tools/repo-contract/validate_repo_contract.py` |
| AC-2 | 新testの実行記録（P1==P2の成立）。ローカルemulator（既存AVD、API 35系）で実行しcommand・環境・結果をPRへ記録 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.EditingBurdenBenchmarkFixtureSeedingInstrumentationTest` |
| AC-3 | 同test内assertion（hotseat前後一致、復元前後一致）+ PR本文 | 同上 |
| AC-4 | PR本文へtest-audit審査項目を記載。portfolio整合validator | `python3 tools/repo-contract/test_validate_ci_portfolio.py` + CI portfolio job |
| AC-5 | spotless | `./gradlew spotlessCheck` |
| AC-6/AC-7 | 後続ステップ（保守者が実機Pixel 9aで計測→`docs/assessment/editing-burden-baseline.md`作成→§6確定+NFR-014確定）。本PRでは実施せず、PR本文に残置事項として明記する | 実機（保守者） |

test-audit審査（AC-4。実装前に確定しPRへ記載する）:

1. 既存test/laneで不足する理由: 既存E2E testはorganizer run契約を検証し、fixture seedingの「同一入力→同一fixture」契約を検証するtestは存在しない。
2. oracleの配置: 最も低い実行可能層はinstrumentation（実frameworkの`modelDbController`/reload経路に依存するためJVMでは成立しない）。計測fixtureの再現性という契約自体が端末上のLauncher DB+modelを対象とする。
3. impact surface: `surface_organizer_ui`（manual-organization-ui laneの既存database-heavy fixture面）。起動条件は既存laneと同一（`full` または `surface_organizer_ui`）。
4. 重複: 既存laneの他class（organizer run E2E、preferences、diagnostics route）と契約が重複しない。
5. 恒久PR gateへの昇格: 新規gateではなく既存laneへの追加であり、scheduled sweepのみではfixture seamの退行（reload/seam変更による破壊）をsurface_organizer_ui変更時に検知できないため、lane本体へ入れる。
6. map/portfolio更新: `ci_portfolio_map.yml`はedge不変のため変更なし。`ci-test-portfolio.md`のlane行を同じPRで更新する。

## Documentation updates

- [ ] `docs/engineering/editing-burden-benchmark.md`（新設。Phase 2）
- [ ] `docs/README.md` 行追加（Phase 2）
- [ ] `CONTEXT.md` 3用語（Phase 2）
- [ ] spec status遷移（Phase 1 review承認で `accepted`。Issue #441全体の完了（AC-6/7）で `implemented`）
- [ ] 変更しないもの: DESIGN.md / ADR / AGENTS.md / requirements.md（NFR-014の確定はAC-7で後続実施）

## Execution checklist

- [x] Issue・付録草案・関連正本の確認（AGENTS.md必読順）
- [ ] Phase 1 review（ChatGPT）と指摘対応
- [ ] spec statusを `accepted` へ更新（review承認後）
- [ ] Phase 2: 定義文書の作成（適合化A-1〜A-9）
- [ ] Phase 2: test-audit skillの適用と新test実装
- [ ] Phase 2: emulatorでのinstrumentation実行とevidence記録（AC-2/3）
- [ ] Phase 2: spotlessCheck・repo-contract validatorの実行（AC-1/4/5）
- [ ] Phase 2 review（ChatGPT）と指摘対応
- [ ] PR作成（`Refs #441`。AC-6/7を残置事項として明記）+ 独立監査（general-purpose subagent）+ 保守者承認・merge
- [ ] 後続（保守者）: 実機計測→baseline記録→目標確定→NFR-014確定→最終PRが `Closes #441`
