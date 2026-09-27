---
issue: "#445"
status: draft
requirements:
  - D-013
updated: 2026-09-27
---

# 直接編集の書込み契約（ADR-0013）の起草・受入と `AGENTS.md` 安全規約の適用範囲の明確化

## Problem

`AGENTS.md`「ホームレイアウトを扱う安全規約」は「次の条件を満たさない変更をDBへ適用してはならない」と対象を限定せずに書かれている。この全面適用読みのままだと、ユーザーが明示的に選んだ1個のアイテムへの即時編集（長押しpopup等でのページ移動・フォルダ投入・ホームから外す）、上流が行う単一アイテムの追加の配置先決定（#446/ADR-0015）、および前者のUndo（#450）の1操作ごとに、snapshot revision照合・recovery point・相関reload・適用後の全体再検証が要求されることになり、応答性（NFR-013）と実装量が割に合わない。

一方で、今日の手動dragは既にこれらの条件なしでDBへ書いている（`src/com/android/launcher3/Workspace.java:2345` の `modifyItemInDatabase`、単一行updateはtransactionを明示しない `src/com/android/launcher3/model/ModelWriter.java:471-475` 等。全根拠は #445 付録のADR-0013草案Contextに記録済み）。単一アイテムの書込みに複数アイテム一括適用と同じ装置を付けても安全性の実質は上がらず、品質順位の第1位（layoutを失わない。`docs/engineering/quality-strategy.md` Quality order 1）は損なわれない。

`AGENTS.md` は安全規約の例外に「受入済みADRと破壊・復旧テスト」を要求するため、線引きを正本に反映するにはADRの受入が前提になる。#445はこの決定Issueであり、ADR-0013「直接編集の書込み契約」の起草・受入を追跡する（メモ§4.2、R-4、D-013）。#446、#448、#450は本契約を前提とする（メモ§5）。

## Outcome

`docs/adr/0013-direct-edit-write-contract.md` が受入済み（`status: accepted`）として存在し、直接編集の対象 (a)(b)(c)、契約条件1〜6、「不要とするもの」、ロックの扱い（既定案）、要求テスト（5系統＋破壊・復旧）を、`path:line` の根拠つきで確定する。`AGENTS.md` の安全規約節は適用対象を「organizer等が生成する計画的な複数アイテムの書換え」と明記し、既存7条件の文言と強度は1字も変えず、直接編集の区分を末尾の1段落でADR-0013へ委ねる。ADR-0004とは関連リンクで接続する（本文は変えない。メモ§4.2の承認済み判断）。#446（ADR-0015）、#448、#450のspecが、本契約を参照して書ける状態になる。

本Issueは決定Issueであり、成果物は文書である。コード変更・テストファイルの作成は行わない。

## Scope

- **ADR-0013の新設**: `docs/adr/0013-direct-edit-write-contract.md`。出典は #445 付録の承認済み草案（2026-09-24、Revision 5のメモ§4.2を正とする）で、次の修正を加えて収録する:
  - 草案内のRF-ID参照（RF-06/07/08/09等）をIssue番号（#446/#448/#449/#450）へ置き換える。対応関係はメモ§3と #445 本文のとおり。
  - 未収録の `refocus-drafts/` 配下への参照を #445（付録が正）への参照に置き換える。ただし編集負担ベンチマークへの参照は、#441で正本が `docs/engineering/editing-burden-benchmark.md` に収録済みのため、そちらへ置き換える。
  - frontmatterの `status` は本PRで `accepted` とする（受入はこのPRのmergeで完了する。ADR-0013を `Criteria` として参照する最初の実装PRより前に `accepted` である必要がある。`docs/project/github-workflow.md` の高リスクaudit要件）。
  - Decision本文の契約条件1〜6、「不要とするもの」、ロック（既定案）、要求テスト表、Alternatives、Consequencesは草案の判断を変えず収録する。
- **ADR-0004への反映（Decision ロック節の確定）**: 「手動編集（直接編集を含む）はロックを妨げず、lock列の値を保ったまま移す」はADR-0013本文が正本として明示する。ADR-0004の本文は変えず、Change historyへ関連リンク（ADR-0013への参照）を1行追加する。これはメモ§4.2の承認済み判断（「ADR-0004の本文は変えず、関連リンクだけを足す」）であり、草案の未解決事項（1行追記 vs 参照で足りる）を解決する。
- **`AGENTS.md`「ホームレイアウトを扱う安全規約」節の変更**: 変更後の全文は次のとおり（第1段落の冒頭に適用対象を明示し、7条件は1字も変えず、末尾に直接編集の1段落を追加する）:

  > ## ホームレイアウトを扱う安全規約
  >
  > organizer等が生成する計画的な複数アイテムの書換え（organizer runの適用を含む）をDBへ適用するときは、次の条件を満たさなければならない。
  >
  > - 入力snapshotと適用時の状態が同じrevisionである。
  > - 全配置アイテムが「保持・移動・明示的削除」のいずれかに説明可能である。
  > - ロック配置が変化していない。
  > - 座標がdevice profile内で、重複せず、folder/container参照が有効である。
  > - 適用前にアプリ内から復旧可能なrecovery pointが作成されている。
  > - 変更はtransactionとして成功するか、変更前へ戻る。
  > - 適用後に再読込し、不変条件を再検証する。
  >
  > `favorites` の無条件な全削除→再挿入、遅延時間への依存、手動バックアップだけをundoとみなす実装は禁止する。例外が必要なら、受入済みADRと破壊・復旧テストを要求する。
  >
  > ユーザーが明示的に選んだ1個のアイテムへの即時の編集アクション、上流が行う単一アイテムの追加の配置先決定、および前者のUndoは、この規約ではなくADR-0013（直接編集の書込み契約）に従う。これらは1アクション = 1 DB transaction、書込み前の副作用のない検証、fail-closedなUndoを要求するが、snapshot revision照合・recovery point・相関reload・適用後の全体再検証は要求しない。

  承認済み草案の変更案からの差分は1点である: 適用対象の例示から「視覚的編集画面の確定時の一括適用」を外した。ADR-0014（#447）が未受入であり、未受入の将来機能を規約文の例示に含めないためである（ADR-0014の受入時に #447/#449 側で追加しうる）。要約段落は草案案のとおり残す（agentがADRを開かずに境界を判断できるようにするため。詳細はADR-0013を正とする）。
- **spec/plan（本ディレクトリ）**: 本specとplan。planには、homeedit高リスクpath一覧への追加方針、writer inventory allowlistの自動検出の注記、実装PRとの順序制約を記載する（下記AC-7）。
- **PR構成**: ADR-0013の新設、`AGENTS.md` の変更、ADR-0004のChange history追記は、単一のPRで行う（「AGENTS.mdの変更がADR-0013の受入と同じPR群で行われる」ことを、このPR構成で満たす）。

## Non-goals

- 直接編集の実装（#448）、新規アプリの配置先ポリシーの実装（#446/ADR-0015）、UndoのUIと寿命（#450）。ADR-0013の要求テストは、これらの将来の実装PRに対する要求であり、本PRではテストファイルを作成しない。
- `docs/project/github-workflow.md` の変更。`Criteria` はspecまたはADRのいずれかを参照できる既存仕様であり、writer inventory allowlistの更新は既存の仕組みで自動検出されるため、workflow文書の変更は不要と判断した（#445 未解決事項の解決記録）。ただしplanに、順序制約とhomeedit path追加方針を記載する。
- `AGENTS.md` 既存7条件の文言・強度の変更。本変更は適用対象の明示であり、緩和ではない。
- spec 13（`specs/13-safe-layout-application/spec.md`）とorganizerの安全な適用経路の変更。視覚的編集画面（#449）の確定時適用は引き続き現行の安全規約に従う（メモ§4.3、#447のADR-0014）。
- ADR-0004の本文（Decision、Identity rules表、Lifecycle表等）の変更。
- `refocus-drafts/` のcommit（リポジトリ未収録のまま。本Issueに必要な文書はIssue付録が正である）。
- 上流の `UpdateItemsRunnable` の失敗握りつぶし（`ModelWriter.java:499-501`）の是正。別調査候補として記録済みで、本Issueの範囲外である。
- NFR-013の数値目標の定義（deferを含むか否か含む。#441/#448のspecの対象）。
- ワークスペース上のdragで移動したロック済みアイテムの扱い（RF-next。ADR-0013では触れない）。

## Domain language

なし。本Issueは契約の線引きであり、新規のドメイン用語を追加しない。「直接編集」の対象と契約はADR-0013が定義する契約語であり、`CONTEXT.md` への反映は不要と判断する（既存語彙: 配置アイテム、Locked Placement等で記述でき、ADRの受入条件への参照で足りる）。

## Behavior scenarios

本Issueの成果物は文書であるため、シナリオは「受入時に文書が満たしているべき状態」として検証する。契約が将来の実装で満たすべき振る舞い（1操作defer、Undoのfail-closed等）はADR-0013のDecisionと要求テスト表が所有し、実装は#446/#448/#450が行う。

### Scenario: ADR-0013が契約を完備して受入済みになる

Given `docs/adr/0013-direct-edit-write-contract.md` が存在する
When 受入条件を確認する
Then frontmatterは `status: accepted` であり、対応として #445 を参照する
And Decisionが対象 (a)(b)(c) の定義（(b)の書込み安全条件は本ADRが所有し、(b)の新規アプリ配置はUndo対象外、(c)のUIと寿命は#450が所有）を含む
And 契約条件1〜6（変更対象の限定、書込み前の副作用のない検証、1アクション = 1 DB transaction、上流model writer経路と排他、Undoのfail-closed、ホームから外すの意味）をそれぞれ `path:line` の根拠つきで含む
And 「不要とするもの」（snapshot revision照合、recovery point、相関reload、適用後の全体再検証）とその根拠を含む
And ロックの扱い（既定案: 自動の変更への制約、明示的直接編集は妨げずlock列の値を保存）とADR-0004との整合分析を含む

### Scenario: 手動dragとの比較の根拠がADR内に記録される

Given ADR-0013のContextを読む
When 「今日の手動dragが安全規約の条件なしで書いている」ことを確認する
Then drag&drop（`Workspace.java:2345`）、フォルダ（`LauncherDelegate.java:99`、`Folder.java:1437,1517`）、追加（`Launcher.java:1503,1593,2048`）、削除（`Launcher.java:2132,2369`）、アクセシビリティ（`LauncherAccessibilityDelegate.java:495`）の書込み経路が `path:line` つきで記録されている
And 単一行updateがtransactionを明示しないこと（`ModelWriter.java:471-475`）、lock列を書かないこと（`ORGANIZER_LOCK_STATE` の参照が編集経路の `ContentWriter` に存在しないこと）の根拠が記録されている
And 直接編集が現行dragより弱くならない理由（検証・1 transaction・Undoを足すだけ）が「不要とするもの」の根拠として記録されている

### Scenario: 安全規約の適用範囲が明示され、7条件の強度が不変である

Given 変更後の `AGENTS.md` 安全規約節
When organizer runの適用について規約を読む
Then 第1段落は「organizer等が生成する計画的な複数アイテムの書換え（organizer runの適用を含む）」に適用対象を限定している
And 既存7条件の箇条書きは変更前と1字も変わらない
And 「favorites の無条件な全削除→再挿入…」の段落も変更前と変わらない
And 末尾段落が直接編集の区分（明示的単一アイテム編集、単一アイテムの追加配置先決定、前者のUndo）をADR-0013へ委ね、契約の要約（1アクション = 1 DB transaction、副作用のない検証、fail-closedなUndo／revision照合・recovery point・相関reload・全体再検証は要求しない）を含む
And 視覚的編集画面の例示は含まない（ADR-0014未受入のため）

### Scenario: ロックの扱いがADR-0004と矛盾せず、反映形式が確定する

Given ADR-0013のDecision ロック節と `docs/adr/0004-organizer-lock-persistence.md`
When 両者の関係を確認する
Then ADR-0013が「ADR-0004はlock truthの格納とfail-closedの読み方を定める。手動移動はロックを妨げず状態を保つ、は現状の意味論の延長であり矛盾ではない」ことを根拠つきで記録している
And ADR-0004の本文は変更されず、Change historyにADR-0013への関連リンクが1行追加されている
And ロック済みアイテムへの直接編集時にその旨を示す方針（ADR-0004の `LockEffectNote` 機構の利用）が記録されている

### Scenario: 復元との相互運用が失われないことが確認される

Given ADR-0013の「不要とするもの」と #265 の実績（`specs/265-post-apply-recovery-reconciliation/spec.md` Established facts 1）
When organizer適用後の直接編集と復元の関係を確認する
Then 直接編集はorganizerから見て今日の手動dragと同じ「現在のrevisionを変えた外部変更」であり、復元はfail-closedに `NotRestorable(STALE_REVISION)` となりうることが記録されている
And これが直接編集がrecovery pointを持たないことによる復元安全性の低下ではないことが記録されている

### Scenario: 要求テストが既存test surfaceに割り付けられる

Given ADR-0013の「要求するテスト」表
When 割付けを確認する
Then 途中失敗の注入、transaction rollback、Undoのfail-closedがLayout Application interface相当のJVM test（test DB使用）に、organizer runとの排他が既存のshared-writer instrumentation seamに、process死がinstrumentation（spec 13 AC-14の慣行）に割り付けられている
And 新規CI laneを作らないことが明記されている
And 割付けはtest-auditの審査観点（protected contract: 契約3/4/5、credible regression: 失敗握りつぶしの模倣・admission迂回・ずれ状態でのUndo書込み、canonical owner: 最も低い決定的境界、重複: 逆方向の `Rejected(WRITER_BUSY)` はspec 13 SA-24が既に保証、impact surfaceとCI分類: 既存gate/laneの再利用）に整合している

### Scenario: 下流specが本契約を参照して書ける

Given #446（ADR-0015）、#448、#450のspec起草者
When 本契約を参照する
Then 対象 (a)(b)(c) の所有関係（(b)は#446が所有し書込み安全条件だけを本ADRに委ねる、(c)のUIと寿命は#450が所有する）、契約1〜6、要求テストがADR内で自己完結している
And Consequencesが「各specで契約を重複定義しない」ことを指針として含んでいる

## Data and state

- 読むdata: なし（文書変更のみ）。
- 永続化するdata: なし。Launcher DBへの書込み、schema、migration、backup/restoreへの影響はない。
- layoutを扱う場合の扱い: 本PRはlayoutを扱わない。ただし決定の結果として、最初の直接編集の実装PRが `risk: layout-data` の対象になる（ADR-0013 Consequences。高リスクpath一覧の `ModelWriter.java` への触れ方による）。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataの追加はない（文書変更のみのため）。

## Accessibility and localization

None。UI変更はない（文書変更のみのため）。ADR/AGENTS.mdは英語・日本語の既存文書規約（各文書内の既存言語）に従う。

## Acceptance criteria

- [ ] AC-1: `docs/adr/0013-direct-edit-write-contract.md` が存在し、frontmatterが `status: accepted` で #445 を対応として参照する。Decisionが対象 (a)(b)(c)、契約条件1〜6、「不要とするもの」、ロック（既定案）をすべて含み、契約の各条件に `path:line` の根拠を持つ。
- [ ] AC-2: ADR内に「今日の手動dragが安全規約の条件なしで書いている」ことの根拠（`path:line` つきの書込み経路一覧と、lock列を書かないことの根拠）と、「安全性を弱めない」理由（検証・1 transaction・Undoを足すだけ。Quality order 1を損なわない）が記録されている。
- [ ] AC-3: ロックの扱いがADR-0004と矛盾しないことがADR内で確認されており、反映形式の判断（本文は変えずChange historyへ関連リンク追加。メモ§4.2の承認済み判断）が記録されている。実際にADR-0004のChange historyへリンクが追加され、ADR-0004の他の本文は不変である。
- [ ] AC-4: organizer適用後の直接編集が、復元（recovery point）から見て今日の手動dragと同じ「手動編集」であり、fail-closedに `NotRestorable(STALE_REVISION)` となりうることがADR内で確認されている（#265とspec 13 Recovery protocolへの参照を含む）。
- [ ] AC-5: 要求テスト（途中失敗の注入、transaction rollback、Undoのfail-closed、organizer runとの排他、process死、破壊・復旧）がADR内の表で既存test surfaceに割り付けられ、新規laneを作らないことが明記されている。CI routingの変更は本PRでは行わない（将来の実装PRが要求テストを実装する際に、test-audit手順と既存lane割付けに従う）。
- [ ] AC-6: `AGENTS.md` の安全規約節がScopeに定めた変更後全文のとおり変更されている（適用対象の明示、7条件と次段落の文言不変、末尾の直接編集段落、視覚的編集画面の例示なし）。ADR-0013の新設と `AGENTS.md` の変更が同じPRで行われている。
- [ ] AC-7: plan.mdに次が記載されている: (a) homeeditのうちLauncher DBへ書くコードの高リスクpath一覧への追加方針（具体的pathと、追加を実行する最初の実装PRの責任。UIと副作用のない計算は含めない）、(b) writer inventory source-scan allowlistが新規DB書込みfileを自動検出する旨と、ADR-0013 Decision 4の機械的保証がこのallowlistであること、(c) `Criteria: ADR-0013` 参照が `accepted` ADRのみ有効であることに由来する順序制約（本PRでADR-0013をacceptedにする）。
- [ ] AC-8: ADRが自己完結しており、#446（ADR-0015）、#448、#450のspecが本契約を参照して書ける状態である（対象定義の所有関係、契約1〜6、要求テストがADR内で完結し、Consequencesに下流specが重複定義しない指針を含む）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | PR diffのreview（ChatGPT review + 別sessionの独立監査）。frontmatterとDecision節の項目チェックリスト照合 |
| AC-2 | 同上。ADR Context/不要とするもの節の根拠リストと、実コードの `path:line` のspot check（本spec起草時に主要参照を実検証済み。plan参照） |
| AC-3 | PR diffのreview。ADR-0004のdiffがChange historyの1行追加のみであることの確認 |
| AC-4 | PR diffのreview。該当文の存在と #265 / spec 13 参照の確認 |
| AC-5 | PR diffのreview。要求テスト表の割付け先が既存surface（`organizer-unit-tests` gate、既存instrumentation lane）であること、新規laneを作らない文言の確認 |
| AC-6 | PR diffのreview。変更前後のdiffで7条件と次段落が1字も変わらないことの確認（`git diff` で機械確認可能） |
| AC-7 | plan.mdの内容review |
| AC-8 | PR diffのreview。ADR単体で下流specの前提が揃うことの確認（review checklist） |

本PRはdocs-onlyであり、repository contract gate（`validate-repo-contract`）で足りる（`docs/project/github-workflow.md` Evidence 選択原則のdocs-only行）。CI source jobはpath filterによりskipされる。

## Open questions

なし。草案の未解決事項（保守者の判断が必要）は、次のとおり承認済みメモまたは本specで解決した:

1. **ADR-0004への追記の要否**: 解決済み。メモ§4.2（2026-09-24承認）が「ADR-0004の本文は変えず、関連リンクだけを足す」と決めている。本specのScope（ADR-0004への反映）のとおり。
2. **AGENTS.mdへの契約要約の粒度**: 解決済み。要約段落を残す（草案案のとおり。agentがADRを開かずに境界を判断できる。詳細はADR-0013を正とする）。
3. **「視覚的編集画面の確定時の一括適用」を規約文に書くか**: 解決済み。外す（ADR-0014/#447未受入のため。Scopeの差分説明参照）。
4. **workflow文書の変更の要否**: 解決済み。不要（Non-goals参照）。順序制約はplanに記載する。
5. **`homeedit` 高リスクpathの具体path**: planで決定する（AC-7。メモ§11 B-10のとおり本Issueが正本）。
6. **PRのclosing keyword**: 本PRが #445 の全終了条件（ADR受入＋AGENTS.md変更＋plan記載事項）を満たす単一の最終PRであるため `Closes #445` を使う（AGENTS.md「Issue駆動・仕様駆動の手順」7の規則）。Issue本文の「`Refs`するPRでdocs/adr/へ入れる」（メモ§8）は、ADRがmain直commitではなくPRで入ることの記述であり、最終性の判定はworkflowのclosing keyword規則に従う。

## Change history

- 2026-09-27: Draft created for #445.
