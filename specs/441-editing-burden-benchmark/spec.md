---
issue: "#441"
status: accepted
requirements: [NFR-014]
updated: 2026-09-26
---

# 編集負担ベンチマークの定義と、計測fixtureの再現手段

## Problem

再焦点化方針メモ（2026-09-24承認、Revision 5。Epic #439）のR-2により「編集負担ベンチマーク」が北極星指標とされたが、課題（B1〜B7）、重み、fixture、計測手順が定義されておらず、Now段階の機能spec（#445〜#450）に「改善する課題と目標値」を書かせられない（メモ §4.1「Now段階の機能specは、改善する課題と目標値がなければ受け入れない」）。また、素のLauncher3/Lawnchairでの日常編集コストの実数値（baseline）が存在せず、改善効果を比較できない。NFR-014は [#441](https://github.com/nunu1733/NunuLauncher/issues/441) を正本としながら `proposed` のままである。

## Outcome

`docs/engineering/editing-burden-benchmark.md` が正本として存在し、課題B1〜B7、重み付き操作コストと操作数の定義（指標は手順コストの会計であり知覚負担の測定ではないことの明示を含む）、fixtureホーム、検証手順、baseline（固定手順からの決定的算出）、目標を1か所で定義する。fixtureホームはinstrumentationにより同一入力から再現でき、1回の実行で再現性を検証できる。baselineと目標の確定、および検証はagent実行可能な手順のみで完結する（2026-09-26のオーナー判断で人間の実測計測は対象外）。

## Scope

- 定義文書 `docs/engineering/editing-burden-benchmark.md` の新設。内容は [Issue #441](https://github.com/nunu1733/NunuLauncher/issues/441) 付録の承認済み草案（2026-09-24）を正とし、plan.mdの適合化を適用する:
  - 課題B1〜B7の定義（開始状態 → 終了状態）。
  - 重み付き操作コストの重み表（本書の承認時に確定値となる）と追加規則。
  - 「操作数」（tapも長押しも1と数える手順の数。メモ §11 B-9）を重み付きコストとは別の指標として定義する。#452の「確認面まで4操作以下」等の数え方の正本となる。
  - fixtureホームの定義（ページ数、grid、ページ別アイコンrole、起動先identity方針、指定フォルダ、重複2組、dock/QSBの扱い）と、再現手段（instrumentation fixture seeding）への参照。
  - 検証手順（agent実行可能な検証: fixture契約test、§3.4配置規則の実証、baseline算術の照合）。
  - baseline（固定手順と重み表からの決定的算出として確定。drop後の視点移動と課題内ページ移動を内訳に含め、操作数を併記）。
  - 目標表（メモ §4.1の初期目標の確定値）。
- fixture seeding instrumentationの新規test。定義文書§5のfixtureホームを同一入力から構築し、同一入力から同一fixtureが得られることを1回のinstrumentation実行で検証する（AC-2）。
- fixture起動先の供給: instrumentation test APKのmanifestに、互いに異なるcomponent・label・iconを持つfixture用activity-aliasを宣言し、実在第三者packageに依存しない形でfixtureアイコンのidentityを確保する（AC-4）。
- 上記testの既存CI lane（manual-organization-ui）への統合。新laneは作らない。

## Non-goals

- 改善機能の実装（#445〜#450のspec/実装が別Issue）。
- organizer runの性能budget（NFR-006、[performance-budgets](../../docs/engineering/performance-budgets.md)が正本）。本ベンチマークは手動編集の操作コストを扱い、面を分ける。
- 計測のCI自動化（performance-budgets §10と同じ分離基準を適用する）。
- 可観測な製品挙動の変更。fixture seedingはinstrumentation test process内の完了であり、製品buildの挙動を変えない（fixture用activity-aliasはtest APKにのみ宣言し、製品manifestは変更しない）。
- 人間による実測（実機でのtouch操作計測・所要時間測定）。2026-09-26のオーナー判断（[Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)）により対象外とし、指標は手順コストの会計に限定する（定義文書§1）。

## Domain language

承認時に `CONTEXT.md` へ反映する用語:

- **編集負担ベンチマーク (Editing Burden Benchmark)**: ホーム画面の日常的な編集と、散らからない状態の維持にかかる手間を、再現可能な課題（B1〜B7）と重み付き操作コストで測る測定体系。NFR-014の正本。
- **重み付き操作コスト (weighted operation cost)**: 課題あたりの主指標。操作種別ごとの重み（tap=1等）の合計。
- **操作数 (operation count)**: 利用者の手順の数。tapも長押しも1と数え、重み付きコストとは別の指標。#452等の「n操作以下」の数え方の正本。
- **重複アイテム (duplicate item)**: 同一起動先（アプリ項目はcomponent + profileの一致。メモ §4.5のRF-10定義と同一）を持つ複数の配置アイテム。B7の対象であり、fixtureでは指定した2組のみがこれに該当する。

## Behavior scenarios

### Scenario: fixture seedingは同一入力から同一fixtureを再現する

Given seeding対象のlauncher DB
When 同一のfixture入力表から、fixture対象graph（workspaceのdesktop rootとその子孫の全行）を削除してからinsertする操作を2回実行し、それぞれmodelをreloadする
Then 配置に関与する行の正規化projection（container、screen、cell、span、item type、intent、folder参照をid解決したもの）が2回とも一致する
And page数、ページ別アイコン数、指定フォルダ、重複2組の存在がfixture定義と一致する
And fixtureの全rootが予約領域を避けて収容され、1ページ目はQSB予約を除き満杯、2ページ目にはB1の新規アプリ配置先として空きcellが1つ以上残る

### Scenario: fixtureのidentity構成はB7の前提を成立させる

Given fixture入力表に従いseedingされたworkspace
When 各アイコンの起動先identity（component + profile）を確認する
Then 通常アイコン（移動対象・削除対象・B3対象・通常）は互いに異なるidentityを持ち、同一起動先の重複は指定した2組（各2個）のみである
And fixture起動先はinstrumentation test APK内のactivity-alias（互いに異なるcomponent・label・icon）であり、実在第三者packageに依存しない

### Scenario: seedingはdockとplatform予約領域を壊さない

Given 既定layoutが持つhotseat（dock）行とその子孫、およびproduction captureが返す予約領域（`LayoutState.reservedWorkspaceRegions`）
When fixture seedingがworkspaceのfixture対象graphを置換する
Then hotseat行・その子孫・予約領域に重なる行は変化しない
And fixtureの全配置spanが予約領域と非交差である（production唯一の受入述語 `ReservationOverlapAcceptance.overlaps` が全fixture項目でfalse）
And 製品buildの挙動は変化しない（seedingはtest process内の完了である）

### Scenario: seedingの検証は永続的な残SIを残さない

Given seeding実行前のfavorites行のsnapshot
When seeding検証test（既定のrestore mode）が完了する
Then snapshotが復元され、test実行前のfavorites行へ戻っている
And 永続mode（persist。plan.md参照）はtest oracleの対象外であり、fixture構築と§3.4実証のagent手順（定義文書§7）で明示的に使う

### Scenario: baselineは固定手順から決定的に算出される

Given 定義文書§2の共通開始条件（ページ0表示。B5は誤操作の結果ページ、B6はinstall後にページ0へ戻すsetup）、§3に固定された操作手順、§4の重み表
When 各課題の内訳を手順に沿って再計算する
Then 重み合計と操作数は一意に定まり、§6のbaseline表と一致する
And 内訳はdrop後の視点移動（§3.1）を含む、課題の完了に必要な全操作を数える

## Data and state

- seedingが読むdata: launcher DB（`favorites`表）とmodel（`modelDbController`）。予約領域はproduction capture seam（`LauncherLayoutAdapter.captureCurrent` → `LayoutState.reservedWorkspaceRegions`。Issue #155）から読む。正本は端末上のLauncher DBである。
- seedingが書くdata: workspaceのfixture対象graph（`CONTAINER_DESKTOP`のroot行と、その子孫行（fixtureが作るフォルダの内容行を含む））。hotseat行・その子孫・予約領域に重なる行は書き換えない。test終了時（restore mode）に元へ復元する。
- 検証記録はrepository内の文書（`docs/assessment/441-fixture-seeding-evidence.md`）とPR本文であり、端末dataへ書き戻さない。実測記録文書（`docs/assessment/editing-burden-baseline.md`）は2026-09-26のオーナー判断により廃止した（作成しない）。
- migration、backup/restore、rollbackへの影響なし（schema変更なし）。
- layoutを扱う場合の対象集合: fixture対象graph（workspaceの全desktop root+子孫）のみ。hotseat・予約領域は保持され、保持対象外の既存項目はfixture置換の前に削除される（置換前にsnapshotを取り、検証後に復元する）。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataなし。fixture起動先はinstrumentation test APK内に宣言したactivity-alias（自package拡張。互いに異なるcomponent・label・icon）であり、実在の第三者package名・実端末のlayoutを入力に使わない（[performance-budgets](../../docs/engineering/performance-budgets.md) §4.4と同じ方針）。

## Accessibility and localization

None。UI変更・文字列変更なし（fixture用のlabel/iconはtest APKリソースであり、製品のUI・翻訳に現れない）。

## Acceptance criteria

- [ ] AC-1: `docs/engineering/editing-burden-benchmark.md` が存在し、Status: Acceptedである。課題B1〜B7、重み表（確定値）、操作数の定義、指標の性質（手順コストの会計。人間実測の対象外）の明示、fixtureホーム（identity方針・収容可能なページ別root数・重複2組の定義を含む）、検証手順（agent実行可能）、baseline（決定的算出の確定値。操作数を含む）、目標表（確定値）を含む。付録草案からの適合化（plan.mdのA-1〜A-13）とオーナー判断による改正（Revision 7）が適用されている。
- [ ] AC-2: fixture seeding instrumentationが存在し、同一入力から同一fixtureを再現することを1回のinstrumentation実行で検証する（fixture対象graphの削除→insertを2回行い、正規化projectionが一致する）。fixture入力の収容契約（全rootが予約領域込みで収容、1ページ目はQSB予約を除き満杯、2ページ目に空きcell≥1）もtest assertionで確認する。実行command・環境・結果がPRに記録されている。
- [ ] AC-3: seedingがhotseat行・その子孫・予約領域に重なる行を保持し、fixtureの全配置spanが `LayoutState.reservedWorkspaceRegions` と `ReservationOverlapAcceptance.overlaps` 基準で非交差である。restore modeのtest終了時にfavoritesを復元する。製品buildの挙動を変えない。
- [ ] AC-4: fixtureの通常アイコンが互いに異なる起動先identity（component + profile）を持ち、同一起動先の重複が指定2組のみであることを、test内assertionと定義文書§5のidentity方針で確認できる。fixture起動先はtest APKのactivity-aliasで供給され、製品manifestは変更しない。
- [ ] AC-5: 新testが既存manual-organization-ui laneへ統合される（新lane・新CI workflowを作らない）。[test-audit skill](../../.agents/skills/test-audit/SKILL.md)の審査項目（既存coverageで不足する理由、oracle配置、impact surface、重複、CI分類）がPRに記載され、`ci_portfolio_map.yml`と[ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)の整合が保たれる。
- [ ] AC-6: `./gradlew spotlessCheck` が成功する。
- [ ] AC-7: 定義文書§6のbaselineが固定手順からの決定的算出として確定している。内訳は§2の共通開始条件、§3の手順、§4の重み表から一意に再計算でき（操作数を含む）、起草時の概算からの再計算（drop後の視点移動・課題内ページ移動の織り込み）がchange historyに記録されている。
- [ ] AC-8: 定義文書§6の目標表が確定値であり、NFR-014がrequirements.mdで確定（accepted）される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 文書の存在と節構成の確認（PR本文の対照表）。repository contract gate（markdown link検証） |
| AC-2 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<新test class>` の実行結果（エミュレータ）。P1==P2のassertion。commandと結果をPRへ記録 |
| AC-3 | 同test内assertion（hotseat子孫・予約領域行の前後一致、`ReservationOverlapAcceptance.overlaps`全項目false、復元の前後一致）+ PR本文への記載 |
| AC-4 | 同test内assertion（identity一意性と重複2組の計数）+ 定義文書§5のidentity方針との対照 |
| AC-5 | PR本文へのtest-audit審査記載。`python3 tools/repo-contract/test_validate_ci_portfolio.py` とCIのportfolio検証job |
| AC-6 | `./gradlew spotlessCheck` の実行結果 |
| AC-7 | §6の内訳を§2/§3/§4から再計算する照合表（PR本文）。定義文書change history |
| AC-8 | requirements.mdのNFR-014行とDecision historyのdiff |

## Open questions

- なし。Phase 1 review（[Conditions 1〜4](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5836226695)）への対応で、fixture identity（Conditions 1・4）、fixture対象graphの削除境界（Condition 2）、予約領域のauthoritative seam（Condition 3）は本spec/planで確定した。

## Change history

- 2026-09-26: Draft created for #441（付録草案 `refocus-drafts/product/editing-burden-benchmark.md`（2026-09-24承認）を基に起草）。
- 2026-09-26: Revision 2。Phase 1 reviewのConditions 1〜4に対応: fixture identity方針（test APK activity-alias・重複2組限定・component+profileの重複キー）をAC-4として明文化、fixture対象graph（desktop root+子孫）の削除境界とoracleへ修正、予約領域契約をproduction seam（`LayoutState.reservedWorkspaceRegions` + `ReservationOverlapAcceptance`）で確定、AC-7に3試行/課題を明記。
- 2026-09-26: Revision 3。Phase 1 re-reviewの指摘（fixture geometryとB1配置前提）に対応: 計測時のQSB/Smartspace状態を既定（有効）で固定し、収容可能なページ別root数（1ページ目15アイコン+フォルダ）へ修正、B1の新規アプリ配置先を`WorkspaceItemSpaceFinder`の実挙動（QSB有効時は1ページ目を候補から除外し2ページ目以降の最初の空きcell）として確定し、収容契約と2ページ目空きのassertionをAC-2へ追加。
- 2026-09-26: Revision 4。alias/drawable数の文書内不一致（34 vs 35）をA-10へ統一し導出を明記。Phase 1 re-reviewでApproved（head `4259fc362f`。判定記録: [Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5836812340)）によりstatusを`accepted`へ更新。
- 2026-09-26: Revision 5（Phase 2実装中の発見を反映）。launcher loaderが1項目フォルダを自動でiconへ展開する（`LAUNCHER_FOLDER_CONVERTED_TO_ICON`）ため、指定フォルダのseed内容を1個から2個（Fixture 01・35）へ変更し、fixture起動先を35本へ更新（plan A-8/A-10）。受入条件の実質（identity一意性・重複2組・収容契約・保持契約）は不変。
- 2026-09-26: Revision 6（Phase 2 review対応）。seedingのwrite境界を「非preservedなdesktop root+その子孫（fixture対象graph）のみ」へ限定し、保持対象外・fixture外の行の無変化をoracleへ追加（AC-3の一般化）。既存hotseatフォルダ+子孫の保持を非自明に検証する第2testを追加。B1/B6計測の固定対象アプリとinstall経路（`INSTALL_REASON_USER`）・試行間reset・計時点を定義文書§7へ確定。受入条件の意味は不変で、write境界と計測再現性の契約を明確化。
- 2026-09-26: Revision 7（オーナー判断による改正）。人間の実測計測（AC-7の実機3試行計測）を対象外へ変更し、終了条件をagent実行可能な検証のみで完結する形へ改正した。AC-7を「baselineの決定的算出（§6）+算術照合可能性」へ、AC-8を「目標確定+NFR-014のaccepted化（本改正で実施）」へ再定義。B6の終了状態を決定的化（10個すべてが指定フォルダ内）。指標の性質（手順コストの会計）の明示をAC-1へ追加。判定記録: [Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)。
