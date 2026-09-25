---
issue: "#441"
status: draft
requirements: [NFR-014]
updated: 2026-09-26
---

# 編集負担ベンチマークの定義と、計測fixtureの再現手段

## Problem

再焦点化方針メモ（2026-09-24承認、Revision 5。Epic #439）のR-2により「編集負担ベンチマーク」が北極星指標とされたが、課題（B1〜B7）、重み、fixture、計測手順が定義されておらず、Now段階の機能spec（#445〜#450）に「改善する課題と目標値」を書かせられない（メモ §4.1「Now段階の機能specは、改善する課題と目標値がなければ受け入れない」）。また、素のLauncher3/Lawnchairでの日常編集コストの実数値（baseline）が存在せず、改善効果を比較できない。NFR-014は [#441](https://github.com/nunu1733/NunuLauncher/issues/441) を正本としながら `proposed` のままである。

## Outcome

`docs/engineering/editing-burden-benchmark.md` が正本として存在し、課題B1〜B7、重み付き操作コストと操作数の定義、fixtureホーム、計測手順、baseline概算、目標の確定手順を1か所で定義する。fixtureホームはinstrumentationにより同一入力から再現でき、1回の実行で再現性を検証できる。保守者が実機でbaseline計測を実施した後、同文書で目標を確定し、NFR-014を確定にできる状態になる。

## Scope

- 定義文書 `docs/engineering/editing-burden-benchmark.md` の新設。内容は [Issue #441](https://github.com/nunu1733/NunuLauncher/issues/441) 付録の承認済み草案（2026-09-24）を正とし、plan.mdの適合化を適用する:
  - 課題B1〜B7の定義（開始状態 → 終了状態）。
  - 重み付き操作コストの重み表（本書の承認時に確定値となる）と追加規則。
  - 「操作数」（tapも長押しも1と数える手順の数。メモ §11 B-9）を重み付きコストとは別の指標として定義する。#452の「確認面まで4操作以下」等の数え方の正本となる。
  - fixtureホームの定義（ページ数、grid、ページ別アイコンrole、指定フォルダ、重複2組、dock/QSBの扱い）と、再現手段（instrumentation fixture seeding）への参照。
  - 計測手順（計測者、端末、回数、手順の固定、記録形式、B6の終了判定、目標の確定手順）。
  - baseline概算（手順からの算出値であり実測値ではないと明記）と、実測記録の受け皿（`docs/assessment/editing-burden-baseline.md`。計測後に作成する）。
  - 目標表（メモ §4.1の初期目標。baseline実測後に確定値へ更新する手順を含む）。
- fixture seeding instrumentationの新規test。定義文書§5のfixtureホームを同一入力から構築し、同一入力から同一fixtureが得られることを1回のinstrumentation実行で検証する（AC-2）。
- 上記testの既存CI lane（manual-organization-ui）への統合。新laneは作らない。

## Non-goals

- 改善機能の実装（#445〜#450のspec/実装が別Issue）。
- organizer runの性能budget（NFR-006、[performance-budgets](../../docs/engineering/performance-budgets.md)が正本）。本ベンチマークは手動編集の操作コストを扱い、面を分ける。
- 計測のCI自動化（performance-budgets §10と同じ分離基準を適用する）。
- 可観測な製品挙動の変更。fixture seedingはinstrumentation test process内の完了であり、製品buildの挙動を変えない。
- baselineの実測と目標の確定の**実施**（AC-6/AC-7）。実機Pixel 9aでの計測は計測者=保守者（定義文書§7）が実施する後続ステップであり、本specはその受け皿（記録形式・確定手順）を定義する。

## Domain language

承認時に `CONTEXT.md` へ反映する用語:

- **編集負担ベンチマーク (Editing Burden Benchmark)**: ホーム画面の日常的な編集と、散らからない状態の維持にかかる手間を、再現可能な課題（B1〜B7）と重み付き操作コストで測る測定体系。NFR-014の正本。
- **重み付き操作コスト (weighted operation cost)**: 課題あたりの主指標。操作種別ごとの重み（tap=1等）の合計。
- **操作数 (operation count)**: 利用者の手順の数。tapも長押しも1と数え、重み付きコストとは別の指標。#452等の「n操作以下」の数え方の正本。

## Behavior scenarios

### Scenario: fixture seedingは同一入力から同一fixtureを再現する

Given seeding対象のlauncher DB（desktop行のみ置換対象）
When 同一のfixture入力表から2回seedingし、modelをreloadする
Then 配置に関与する行の正規化projection（container、screen、cell、span、item type、intent、folder参照をid解決したもの）が2回とも一致する
And page数、ページ別アイコン数、指定フォルダ、重複2組の存在がfixture定義と一致する

### Scenario: seedingはdockとplatform予約領域を壊さない

Given 既定layoutが持つhotseat（dock）行と、screen 0のplatform予約領域（QSB等）
When fixture seedingがdesktop行を置換する
Then hotseat行は変化せず、予約領域を除くdesktop cellにfixtureアイコンが収まる
And 製品buildの挙動は変化しない（seedingはtest process内の完了である）

### Scenario: seedingの検証は永続的な残SIを残さない

Given seeding実行前のfavorites行のsnapshot
When seeding検証testが完了する
Then snapshotが復元され、test実行前のfavorites行へ戻っている

### Scenario: 重み合計は操作回数から決定的に算出される

Given 定義文書§3に固定された操作経路と§4の重み表
When 同一課題を複数試行する
Then 重み合計は試行間で一致する。一致しない試行は手順の逸脱として記録される

## Data and state

- seedingが読むdata: launcher DB（`favorites`表）とmodel（`modelDbController`）。正本は端末上のLauncher DBである。
- seedingが書くdata: `favorites`表のdesktop行（`CONTAINER_DESKTOP`）と、B1指定フォルダのfolder行・folder内容行。hotseat行とplatform予約領域は変更しない。test終了時に元へ復元する。
- 計測記録（`docs/assessment/editing-burden-baseline.md`）はrepository内の文書であり、端末dataへ書き戻さない。記録に含めてよいのは重み合計・実測時間・端末/build/commit SHA・計測日時・逸脱備考・evidence画像のみである。
- migration、backup/restore、rollbackへの影響なし（schema変更なし）。
- layoutを扱う場合の対象集合: seedingの対象はfixture定義のdesktopアイコン+指定フォルダ+重複2組のみ。既存アイテムの暗黙の移動・削除は行わない（置換前にsnapshotを取り、検証後に復元する）。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataなし。fixtureアイコンは自package（`context.packageName`）のlauncher activityを起動先とする合成構成であり、実端末のlayout・実在の第三者package名を入力に使わない（[performance-budgets](../../docs/engineering/performance-budgets.md) §4.4と同じ方針）。

## Accessibility and localization

None。UI変更・文字列変更なし。

## Acceptance criteria

- [ ] AC-1: `docs/engineering/editing-burden-benchmark.md` が存在し、Status: Acceptedである。課題B1〜B7、重み表（確定値）、操作数の定義、fixtureホーム、計測手順、baseline概算（非実測の明記）、計測記録形式、目標表（初期目標+確定手順）を含む。付録草案からの適合化（plan.mdのA-1〜A-9）が適用されている。
- [ ] AC-2: fixture seeding instrumentationが存在し、同一入力から同一fixtureを再現することを1回のinstrumentation実行で検証する。実行command・環境・結果がPRに記録されている。
- [ ] AC-3: seedingがhotseat行とplatform予約領域を保持し、desktop行のみを置換する。test終了時にfavoritesを復元する。製品buildの挙動を変えない。
- [ ] AC-4: 新testが既存manual-organization-ui laneへ統合される（新lane・新CI workflowを作らない）。[test-audit skill](../../.agents/skills/test-audit/SKILL.md)の審査項目（既存coverageで不足する理由、oracle配置、impact surface、重複、CI分類）がPRに記載され、`ci_portfolio_map.yml`と[ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)の整合が保たれる。
- [ ] AC-5: `./gradlew spotlessCheck` が成功する。
- [ ] AC-6: baseline実測記録が `docs/assessment/editing-burden-baseline.md` に作成され、課題ごとの重み合計・実測時間（中央値）・端末・build・commit SHA・計測日時を含む（保守者による実機計測。Issue #441の後続ステップ）。
- [ ] AC-7: baseline実測後に定義文書§6の目標表が確定値へ更新され、NFR-014がrequirements.mdで確定される（Issue #441の後続ステップ。メモ §4.1/§4.9）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 文書の存在と節構成の確認（PR本文の対照表）。repository contract gate（markdown link検証） |
| AC-2 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<新test class>` の実行結果（エミュレータ）。commandと結果をPRへ記録 |
| AC-3 | 同test内のassertion（hotseat行の前後一致、復元の前後一致）+ PR本文への記載 |
| AC-4 | PR本文へのtest-audit審査記載。`python3 tools/repo-contract/test_validate_ci_portfolio.py` とCIのportfolio検証job |
| AC-5 | `./gradlew spotlessCheck` の実行結果 |
| AC-6 | `docs/assessment/editing-burden-baseline.md`（後続PR/issue更新で確認） |
| AC-7 | 定義文書§6とrequirements.mdのdiff（後続PRで確認） |

## Open questions

- なし（承認時に確定する判断はplan.mdに提案として記載し、Phase 1 reviewで承認を得る）:
  - B6の「整った」終了判定は「測定者判断+終了時screenshot 1枚の証跡」とする（機械判定しない。メモ §4.7の階層Lに相当する軽量計測のため）。
  - NORMAL状態drag中のページ切り替え待ちに対応するコード定数は要求しない（実効待ちを副指標の実測時間として記録する。草案未解決事項1の解消）。

## Change history

- 2026-09-26: Draft created for #441（付録草案 `refocus-drafts/product/editing-burden-benchmark.md`（2026-09-24承認）を基に起草）。
