---
issue: "#446"
status: draft
requirements:
  - D-015
  - FR-008
updated: 2026-09-27
---

# 新規アプリの配置先ポリシー（ADR-0015）の起草・受入

> Risk tier: H — #446（メモ§4.7）が「直接編集（R-4）はADR-0013と最初の実装は階層H」と定め、ADR-0015はADR-0013の対象(b)「上流が行う単一アイテムの追加の配置先決定」を所有する決定Issueである。本ADR自体はdocs-onlyであり、`docs/project/github-workflow.md` Risk tiersの階層H条件「既存の書込み経路の契約を変える」「上流のmodel/loaderへのbridgeを作るまたは変える」を**将来の実装PRが満たす前提**を確定する。本PR自体はrisk labelなし・高リスクpath変更なしのdocs-onlyであるため、`high-risk-evidence` gateの機械的発火対象ではない（workflow適用条件による判断。tier Lへの下げではない）。保守者の指示により、本PRへ別session（general-purposeサブエージェント）による独立監査を追加で実施し、結果をPRコメントへ記録する。
> Revision 1: 2026-09-27 — 初版。Phase1 reviewへ提出。

## Problem

上流Lawnchair/Launcher3は、新しくinstallしたアプリのアイコンを常にワークスペースの空きセルへ追加する（`SessionCommitReceiver.java:76-87`、`AddWorkspaceItemsTask.java:123-126`、`AddWorkspaceItemsTask.java:194-197`）。利用者が「新規アプリはこのフォルダに入れたい」という配置方針を持っていても反映されず、新規installのたびにホームが散らかる。編集負担ベンチマーク（`docs/engineering/editing-burden-benchmark.md`）のB1/B6がこの散らかりを測定対象としている。

以前の調査（ADR-0005、`docs/engineering/package-provenance.md`）は、このeventを「organizerの増分整理提案」として扱うにはprior absence（以前存在しなかったことの証明）が取得不可能という結論を出し、FR-008をLater/deferredにした（#85 Option B）。しかし再焦点化方針メモ（2026-09-24承認、Revision 5）§4.4/R-6が指摘するとおり、利用者から見える問題は「配置先が決められない」ことであり、「新規installであることの証明」はその解決に不要である。

#445でADR-0013（直接編集の書込み契約）が受入済みであり、その対象(b)「上流が行う単一アイテムの追加の配置先決定」の所有と書込みの安全条件の委譲先が未確定である（`docs/adr/0013-direct-edit-write-contract.md` 対象の定義、Consequences）。#446はこの決定Issueであり、ADR-0015「新規アプリの配置先ポリシー」の起草・受入を追跡する（メモ§4.4、R-6、D-015）。

## Outcome

`docs/adr/0015-new-app-destination-policy.md` が受入済み（`status: accepted`）として存在し、次を`path:line`の根拠つきで確定する:

- 配置先の3選択肢（上流の既定 / 指定フォルダ / 追加しない）と、「追加するかどうかの判定は上流のまま変えない」こと。
- prior absenceの証明が不要であることの根拠（上流の`shortcutExists`重複除外）と、ADR-0005の適用範囲の「既存アイテムを動かす増分整理提案」への狭め。
- 指定フォルダが使えない場合の上流既定への戻し条件（存在しない・別profile・Dock・配置制約違反・満杯）と、理由の記録方法。
- 指定フォルダをアイテムのidで保持すること、削除時は上流既定へ戻して設定の行で一度だけ知らせること。
- ロックの意味論（ADR-0004。親ロック下でも既存子のcaptured rankを変えない追加に限る）。
- promise iconの段階から同じ配置先を使うこと（2段階移動の回避）。
- bridgeの最小化（`PackageUpdatedTask`へ分岐を追加しない）とポリシーを所有するmodule（organizerと別の、直接編集と同じ側のmodule）。
- 書込み契約はADR-0013へ委譲し、本ADRは重複定義しないこと。

ADR-0005と`docs/engineering/package-provenance.md`には関連リンクのみ追加する（本文は変えない。メモ§4.4の承認済み判断）。#448（直接編集の第1段）と#450（Undo）のspecが、本ADRをADR-0013と並ぶ前提として参照できる状態になる。

本Issueは決定Issueであり、成果物は文書である。コード変更・テストファイルの作成は行わない。

## Scope

- **ADR-0015の新設**: `docs/adr/0015-new-app-destination-policy.md`。出典は #446 付録の承認済み草案（2026-09-24、Revision 5のメモ§4.4を正とする）で、次の修正を加えて収録する:
  - 草案内のRF-ID参照（RF-06/07/09等）をIssue番号（#446/#448/#450）へ置き換える。対応関係はメモ§3と #446 本文のとおり。
  - 未収録の `refocus-drafts/` 配下への参照を #446（付録が正）への参照に置き換える。ただし編集負担ベンチマークへの参照は、#441で正本が `docs/engineering/editing-burden-benchmark.md` に収録済みのため、そちらへ置き換える。
  - frontmatterの `status` は本PRで `accepted` とする（受入はこのPRのmergeで完了する。ADR-0015を `Criteria` として参照する最初の実装PRより前に `accepted` である必要がある。`docs/project/github-workflow.md` 高リスクaudit要件）。
  - Decision本文の判断1〜13、Alternatives、Consequencesは草案の判断を変えず収録する。ただし草案の「未解決事項（保守者の判断が必要）」4項を、本specの「Open questions」の決着（下記）に従って解決済みとして収録する。
- **ADR-0005への反映**: `docs/adr/0005-fresh-install-presence-evidence.md` のChange historyへ関連リンク（ADR-0015への参照、適用範囲の狭めの明示）を1行追加する。Decision/Context本文は1字も変えない（メモ§4.4「本文は変えず、関連リンクだけを足す」の承認済み判断。#445のADR-0004処理と同じ形式）。
- **`docs/engineering/package-provenance.md`への反映**: §7（Verification and change history）へ関連リンクを1行追加する。§4分類表を含む本文は変えない。分類表は「増分整理proposal」の分類正本として維持される（ADR-0015 Decision 2）。
- **`docs/product/requirements.md`への反映**:
  - D-015行の参照を起草Issue #446から `docs/adr/0015-new-app-destination-policy.md` へ更新する（#440の未解決事項「D-013〜016のADR link」のうちD-015分。なおD-013分は#445で未実施のまま残っており、本PRでは触れない）。
  - FR-008行のstatusを「proposed（再定義）」から「accepted（ADR-0015受入。実装は未着手）」へ更新する。要件のstatus更新の根拠は「Issueの受入、mainline merge」（`docs/product/requirements.md` Traceability rule）であり、ADR受入はD-015 gateの受入であってFR-008の製品実装完了ではないため、status語彙は実装完了を意味しない受入状態を示す。
  - 未解決事項「FR-008と旧Issue #85決定（Option B）の整合」を解決済みとして整理する（解決の正本はADR-0015 Decision 2）。
- **`docs/product/organization-run-ux.md`への反映**: §2.3（package event後のincremental placement）へ注記を1文追加する。「上流が追加するアイコンの配置先決定はFR-008再定義（ADR-0015）によりorganizerのincremental proposalの枠外へ移った。ADR-0005が防ぐ対象は『既存アイテムを動かす増分整理提案』である」。安全契約本体（§1、§3〜§6）と§2.3の表・state diagramは変えない（`refocus-drafts/product/other-doc-impacts.md` organization-run-ux節の承認済み指示。変更の担当はRF-06=本Issue）。
- **spec/plan（本ディレクトリ）**: 本specとplan。planには、ADR-0015の実装を所有する将来PRへの引き継ぎ事項（bridge候補位置、module配置、high-risk判定、テスト要求）を記載する。
- **PR構成**: ADR-0015の新設、ADR-0005のChange history追記、package-provenanceのChange history追記、product文書2件の更新、spec/planを単一のPRで行う。

## Non-goals

- 配置先ポリシーの実装（設定UI、フォルダ指定の保持、追加経路へのbridge、diagnostics記録）。ADR-0015のConsequencesと本planの引き継ぎ事項が、将来の実装Issueのspec/planの入力になる。実装Issueは本ADR受入後に起票する。
- 追加するかどうかの判定の変更（install reason、`pref_add_icon_to_home`の意味、重複除外。上流のまま）。
- カテゴリの一致するフォルダ、指定ページへの配置（Next。メモ§4.5）。
- 既存アイテムを動かす増分整理提案（FR-009、Laterのまま。ADR-0005の適用範囲は維持される）。
- organizerのplanner/application/recovery protocolの変更、およびorganizer runとの同時書込みの許可。
- `PackageUpdatedTask.java`（AOSP由来）への分岐追加（Deckの失敗の再現を避ける。ADR-0015 Decision 9）。
- 分類機構の新設（分類を使う機能はNextで、organizerの分類seamを使う。AGENTS.md設計規約）。
- `docs/project/github-workflow.md` の変更。高リスクpath一覧は「Launcher DBへ書くコードのpath」の機械一覧であり、配置先ポリシーの実装PRが書込み経路を確定した時点で追加する（#445 planのhomeedit追加方針と同じ規則）。
- `refocus-drafts/` のcommit（リポジトリ未収録のまま。本Issueに必要な文書はIssue付録が正である）。
- D-013のrequirements.md参照更新（#445の未実施項。本PRでは触れない）。
- mvp-release-readiness.mdのFR-008行の変更（#440で本書のinventory対象外が明記済み。新FR-008のowning Issue/specは本書へ登録しない）。
- B1/B6の目標値の確定（B1の目標は`docs/engineering/editing-burden-benchmark.md` §6で確定済み。B6の目標は本ADRの受入後に実装Issueのspecで確定する）。

## Domain language

**配置先ポリシー (New App Destination Policy)**: 上流が既にホームへ追加するアイコン（`pref_add_icon_to_home`が有効で上流の追加条件を満たすもの）について、どこへ置くかを決めるユーザー選択の規則（ADR-0015）。追加するかどうかの判定は含まない。_Avoid_: 増分配置 (Incremental Placement)（既存アイテムを動かす整理runを指す既存語との混同）、自動配置（ユーザー選択の規則であることの不明瞭化）。

`CONTEXT.md` への反映は本ADRのacceptedと同じPRで行う（`refocus-drafts/product/other-doc-impacts.md` CONTEXT.md節の追加時期注意「ADRがproposedの間は追加しない」を満たす。#445はCONTEXT.mdへ用語を追加しなかったが、配置先ポリシーは新規アプリの追加経路という新しい領域の語であり、`CONTEXT.md` の既存語彙（配置アイテム、配置）では「追加するかどうかの判定を含まない」境界を表現できない）。

## Behavior scenarios

本Issueの成果物は文書であるため、シナリオは「受入時に文書が満たしているべき状態」として検証する。ポリシーが将来の実装で満たすべき振る舞い（fallback、id保持、promise icon段階の統一等）はADR-0015のDecisionが所有し、実装は将来の実装Issueが行う。

### Scenario: ADR-0015が判断を完備して受入済みになる

Given `docs/adr/0015-new-app-destination-policy.md` が存在する
When 受入条件を確認する
Then frontmatterは `status: accepted` であり、対応として #446 を参照する
And Decisionが対象の定義（上流が追加するアイコンの配置先だけを決める。追加するかどうかの判定は上流のまま）を含む
And 判断1〜13（prior absence不要の根拠、3選択肢、fallback条件、id保持、ロック意味論、書込み契約のADR-0013への委譲、promise icon段階の統一、bridgeの最小化、module所有、Deckとの違い、Undo対象外、設定の置き場所）をそれぞれ `path:line` の根拠つきで含む
And 「追加しない」が独立したtoggleではなく既存の `pref_add_icon_to_home` と同じ結果を指すことが記録されている

### Scenario: ADR-0005との関係が本文変更なしで接続される

Given ADR-0015のDecision 2と `docs/adr/0005-fresh-install-presence-evidence.md`
When 両者の関係を確認する
Then ADR-0015が「本ポリシーは『上流が追加を決めた1アイテムの置き場所』の決定であり、既存アイテムを動かす提案ではない。上流の `shortcutExists` 重複除外（`AddWorkspaceItemsTask.java:100-107`、`:274-281`）が再install重複量産を構造的に防ぐ」ことを根拠つきで記録している
And ADR-0005の適用範囲が「既存アイテムを動かす増分整理提案（organizerのplanner/application経路から出すproposal）」へ狭められたことが記録されている
And ADR-0005の本文は変更されず、Change historyにADR-0015への関連リンクが1行追加されている
And `docs/engineering/package-provenance.md` の本文（§4分類表を含む）は変更されず、Change historyへ関連リンクが1行追加されている

### Scenario: 指定フォルダの境界条件がfallbackとして確定する

Given ADR-0015のDecision 4と5
When 指定フォルダが使えない場合の振る舞いを確認する
Then フォルダが存在しない・別profileにある・Dockにある・配置制約を満たせない・満杯の場合に上流の既定へ戻すことが記録されている
And 書込み前の副作用のない計画関数で検証し、満たせなければ書かずに既定へ戻すことが記録されている（ADR-0013契約2への整合）
And fallback理由が後から確認できる形で記録されることが記録されている
And 指定フォルダはアイテムのidで保持され、削除されたら上流既定へ戻して設定の行で一度だけ知らせることが記録されている
And Dockにあるフォルダは指定できないことが記録されている

### Scenario: ロックの意味論がADR-0004と矛盾しない

Given ADR-0015のDecision 6と `docs/adr/0004-organizer-lock-persistence.md` Identity rules表
When ロック済みフォルダへの追加の扱いを確認する
Then 親ロック（`LOCKED`）が「parent lock protects the parent cell and every child's captured container/rank」を定める引用と、それが既存子のrank保護であって新規子の追加禁止ではない限定解釈が記録されている
And 追加で既存子のcaptured rankをずらす実装が禁止されている（末尾rank追加等、既存子のrankを変えない方法に限る）
And 子自身の`LOCKED`が兄弟の追加を妨げないことが記録されている
And フォルダ内の空きrank計算がgrid非依存の固定計算（Deckの`index % 4`型）を行わないことが記録されている

### Scenario: 書込み契約がADR-0013へ委譲される

Given ADR-0015のDecision 7と `docs/adr/0013-direct-edit-write-contract.md`
When 書込みの安全条件の所有を確認する
Then ADR-0015が「1アイテムの追加はADR-0013の対象(b)であり、1 DB transaction、model writer経路、`LayoutWriteCoordinator`との排他、organizer runとの同時書込み禁止をADR-0013に委ねる。本ADRは契約を重複定義しない」ことを記録している
And ADR-0013のConsequencesが「#446のspecは本契約を参照して書かれる」ことを満たす形になっている

### Scenario: promise iconの段階から配置先が決まる

Given ADR-0015のDecision 8
When 配置先決定のタイミングを確認する
Then 上流がpromise iconを追加する時点（`AddWorkspaceItemsTask`の書込み）で配置先を決めることが記録されている
And install完了後の`PackageUpdatedTask.OP_ADD`が既存promise iconのintent/iconを更新するだけで配置を動かさないこと（`PackageUpdatedTask.java:261-320`）の根拠が記録されている
And 配置先をinstall完了後に変える案（2段階移動）がAlternativesでRejectedされている

### Scenario: bridgeとmoduleの所有が確定する

Given ADR-0015のDecision 9と10
When bridgeの場所とmoduleの所有を確認する
Then bridgeが`ItemInstallQueue`→`AddWorkspaceItemsTask`の追加経路の1箇所に置かれ、`PackageUpdatedTask`（AOSP由来）へ分岐を追加しないことが記録されている
And Deckが`PackageUpdatedTask.OP_ADD`へ直接deck分岐を書いたことがNFR-010違反のpatch surfaceとして退役理由の1つである旨（`docs/assessment/lawnchair-deck-audit.md` §6.6、ADR-0006）が記録されている
And ポリシーを所有するmoduleがorganizerとは別の、直接編集と同じ側のmodule（メモ§4.8の仮称`app.lawnchair.homeedit`）であり、organizerのplanner/application/locks/recovery protocolに依存しないことが記録されている
And bridgeの具体的な実装場所（`ItemInstallQueue`のflush時か`AddWorkspaceItemsTask`内か）の調査がplanに記載されている

### Scenario: 利用者向け設定の置き場所が確定する

Given ADR-0015のDecision 13
When 設定の扱いを確認する
Then 選択肢が「上流の既定 / 指定フォルダ / 追加しない」の3択であることが記録されている
And 「追加しない」が既存の`pref_add_icon_to_home`（`lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:51`、UIは`HomeScreenPreferences.kt:74-82`）と同じ結果を指し、独立したtoggleを新設しないことが記録されている
And ポリシー設定のUI置き場所が「既存の『ホームにアイコンを追加』設定の近傍」に決定している
And ホーム画面ロックが有効な間は追加自体が行われないこと（`SessionCommitReceiver.java:98-102`）が記録されている

### Scenario: Undoの対象外がADR-0013と揃っている

Given ADR-0015のDecision 12とADR-0013の対象(c)
When Undoの扱いを確認する
Then 新規アプリの配置がUndoの対象外であることが両ADRで揃って記録されている
And 置き場所を変えたい場合は項目単位の編集アクション（#448/FR-018）で動かすことが記録されている

### Scenario: 下流specが本ADRを参照して書ける

Given #448、#450のspec起草者と、将来の配置先ポリシー実装Issueの起草者
When 本ADRを参照する
Then 対象の所有関係（配置先ポリシーはADR-0015が所有し、書込みの安全条件だけをADR-0013に委ねる）、fallback条件、id保持、promise icon段階の統一、bridge制約、module所有がADR内で自己完結している
And Consequencesが「実装Issueのspecで判断を重複定義しない」ことを指針として含んでいる

## Data and state

- 読むdata: なし（文書変更のみ）。
- 永続化するdata: なし。Launcher DBへの書込み、schema、migration、backup/restoreへの影響はない。
- layoutを扱う場合の扱い: 本PRはlayoutを扱わない。ただし決定の結果として、配置先ポリシーの最初の実装PRが `risk: layout-data` の対象になる見込みである（指定フォルダへの追加はfavorites行の追加を伴う。ADR-0013の契約で扱う。#446 リスク節どおり）。

## Permissions, privacy, and security

None。新規permission、外部送信、sensitive dataの追加はない（文書変更のみのため）。将来の実装におけるpackage名のdiagnostics記録は、organizer-diagnostics §7のNever分類に従う旨をADR-0015のDecision 4に記録する（`docs/engineering/organizer-diagnostics.md` §7: package名は**Never**、件数のみ。なおorganizer-diagnostics §1のscopeは「organization run / recovery操作だけを記録する」run journalであり、新規アプリ配置はorganizer runではないため、fallback理由の記録先はrun journalではない。記録方法の確定は将来の実装Issueのspecが行う。本ADRは「後から確認できる」ことを要求するのみとする）。

## Accessibility and localization

None。UI変更はない（文書変更のみのため）。将来の設定UIの追加時に検証する（ADR-0015 Decision 13が記録対象）。

## Acceptance criteria

- [ ] AC-1: `docs/adr/0015-new-app-destination-policy.md` が存在し、frontmatterが `status: accepted` で #446 を対応として参照する。Decisionが対象の定義、判断1〜13、「追加しない」の`pref_add_icon_to_home`との関係をすべて含み、各判断に `path:line` の根拠を持つ。
- [ ] AC-2: ADR内に「prior absenceの証明が不要」であることの根拠（上流の重複除外の `path:line`、ADR-0005が防ぐ対象との構造的な違い）と、ADR-0005の適用範囲の狭め（「既存アイテムを動かす増分整理提案」）が記録されている。ADR-0005の本文は不変でChange historyに1行追加、`docs/engineering/package-provenance.md` の本文（§4分類表含む）は不変でChange historyに1行追加されている。
- [ ] AC-3: 指定フォルダの境界条件（存在しない・別profile・Dock・配置制約違反・満杯）が上流既定への戻し条件として記録され、書込み前の副作用のない検証（ADR-0013契約2との整合）と理由の記録が要求されている。指定フォルダのid保持、削除時の既定復帰と設定の行での一度だけの通知が記録されている。
- [ ] AC-4: ロックの扱いがADR-0004のIdentity rules表の引用と限定解釈（親ロックは既存子のrank保護であり新規追加を禁じない。既存子のcaptured rankを変えない追加に限る。子の`LOCKED`は兄弟追加を妨げない。grid非依存の固定rank計算禁止）で記録されている。
- [ ] AC-5: 書込み契約がADR-0013へ委譲され（対象(b)、1 transaction、model writer経路、coordinator排他）、ADR-0015は契約を重複定義しない。promise iconの段階から同じ配置先を使うこと（Decision 8）と、install完了後変更案のRejectedが記録されている。
- [ ] AC-6: bridgeが`ItemInstallQueue`→`AddWorkspaceItemsTask`経路の1箇所に限定され、`PackageUpdatedTask`への分岐追加が禁止されている（Deck失敗の引用つき）。ポリシー所有moduleがorganizerとは別の直接編集と同じ側のmoduleであることが記録されている。
- [ ] AC-7: 利用者向け設定が3択で定義され、「追加しない」が独立toggleでなく既存設定と同じ結果を指すこと、ポリシー設定のUI置き場所（「ホームにアイコンを追加」の近傍）、ホーム画面ロック中の追加停止が記録されている。Undo対象外がADR-0013の対象(c)と揃っている。
- [ ] AC-8: `docs/product/requirements.md` のD-015行が `docs/adr/0015-new-app-destination-policy.md` への参照に更新され、FR-008行のstatusが受入状態を示す語に更新されている。未解決事項「FR-008と旧#85決定の整合」が解決済みとして整理されている。`docs/product/organization-run-ux.md` §2.3に注記が1文追加され、安全契約本体が不変である。
- [ ] AC-9: `CONTEXT.md` に「配置先ポリシー (New App Destination Policy)」の定義が追加されている（_Avoid_を含む。追加はADRのacceptedと同じPR）。
- [ ] AC-10: plan.mdに次が記載されている: (a) bridgeの実装位置の調査結果と候補（`ItemInstallQueue`のflush時 vs `AddWorkspaceItemsTask`内。ADR-0015草案の未解決事項）、(b) 配置先ポリシー実装PRの高リスク判定の予告（favorites行追加を伴うため `risk: layout-data` 対象見込み。writer inventory allowlist更新が必要になる可能性）、(c) `Criteria: ADR-0015` 参照が `accepted` ADRのみ有効であることに由来する順序制約（本PRでADR-0015をacceptedにする）。
- [ ] AC-11: ADRが自己完結しており、#448、#450のspecと将来の配置先ポリシー実装Issueのspecが本ADRを参照して書ける状態である（対象の所有関係、fallback条件、bridge制約、module所有がADR内で完結し、Consequencesに下流specが重複定義しない指針を含む）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | PR diffのreview（ChatGPT review + 別sessionの独立監査）。frontmatterとDecision節の項目チェックリスト照合 |
| AC-2 | PR diffのreview。ADR-0005/package-provenanceのdiffがChange historyの1行追加のみであることの確認（`git diff` で機械確認可能） |
| AC-3 | PR diffのreview。Decision 4/5のfallback条件とid保持の記載確認 |
| AC-4 | PR diffのreview。ADR-0004 Identity rules表の引用と限定解釈の記載確認 |
| AC-5 | PR diffのreview。ADR-0013への委譲文とDecision 8の記載確認 |
| AC-6 | PR diffのreview。Decision 9/10の記載確認 |
| AC-7 | PR diffのreview。Decision 12/13の記載確認 |
| AC-8 | PR diffのreview。requirements.md/organization-run-ux.mdのdiff確認 |
| AC-9 | PR diffのreview。CONTEXT.mdの追加行確認 |
| AC-10 | plan.mdの内容review |
| AC-11 | PR diffのreview。ADR単体で下流specの前提が揃うことの確認（review checklist） |

本PRはdocs-onlyであり、repository contract gate（`validate-repo-contract`）で足りる（`docs/project/github-workflow.md` Evidence 選択原則のdocs-only行）。CI source jobはpath filterによりskipされる。

## Open questions

なし。草案の未解決事項（保守者の判断が必要）は、次のとおり本specで解決した:

1. **フォルダ満杯の扱い**: 解決済み。上流のfolderにハードな上限item数の定数は存在せず、`FolderPagedView`は`FolderGridOrganizer.getMaxItemsPerPage()`（ページあたり`numFolderColumns×numFolderRows`。既定`4_by_5`グリッドでは3×3）を超えるとページングで拡張する（`FolderPagedView.java:558`、`FolderGridOrganizer.java:53,91`、`calculateGridSize`は`mMaxItemsPerPage`以上でmax gridに張り付くがページ数の上限はない）。したがって「満杯」は定義できない状態であり、指定フォルダへの追加は常に末尾rankへの追加として成立する。満杯をfallback条件としない代わりに、ADR-0015 Decision 4の配置制約検証（device profile外・重なり）を書込み前の計画関数で行い、検証が失敗した場合のみ既定へ戻す。これにより「満杯」という非決定的な状態の定義を避け、fallback条件を観測可能な制約違反に限定する。ADR-0015にはこの確定をDecision 4に反映して収録する。
2. **同名フォルダ再作成時の再指定のUX**: 解決済み。指定フォルダはidで保持するため（Decision 5。メモ§4.4で確定済み）、削除後に同名フォルダを作っても指定は復活しない。再指定を促すUIの詳細は将来の実装Issueのspecが決める。本ADRは「削除されたら上流の既定に戻し、設定の行で一度だけ知らせる」（Decision 5）を要求するのみとし、UI詳細をADRの対象外とする。
3. **promise iconの配置先決定の実装位置**: planで調査する（AC-10(a)。ADR-0015草案の未解決事項どおり、`ItemInstallQueue`のflush時（`ItemInstallQueue.java:135-145`）か`AddWorkspaceItemsTask`内か。task constructorの`WorkspaceItemSpaceFinder`注入点（`AddWorkspaceItemsTask.java:67-79`）をseamにできる可能性を含む）。ADR-0015は「bridgeは追加経路の1箇所に置く」（Decision 9）を要求するのみとし、具体位置はplanの調査で確定する。
4. **promise iconがqueueに残ったままprocess死した場合の再flush時の配置先の一貫性**: 解決済み。queueは`PersistedItemArray`で永続化され（`ItemInstallQueue.java:87-88`）、再flush時は`getItemInfo(mContext)`が再度呼ばれて`WorkspaceItemInfo`が再構築される（`ItemInstallQueue.java:135,199,286-318`）。配置先ポリシーは「パッケージ名+user」から決定的に計算される純関数とし、同じinstallの再flushで同じ結果になることをADR-0015に要求として明記して収録する（Decision 9のbridge最小化と整合する。状態を持たない決定的計算であることが、queue永続化をまたぐ一貫性の根拠になる）。
5. **fallback理由の記録方法**: 解決済み（方針のみ）。organizer-diagnostics §1のscopeは「organization run / recovery操作だけを記録する」run journalであり、新規アプリ配置はorganizer runではないため、run journalの`RunEvent`（`Trigger`/`PhaseCode`のclosed集合）をそのまま使うことはできない。本ADRは「fallback理由が後から確認できる形で記録される」ことを要求し、記録方法（FileLog、settings側の表示用state、diagnostics契約の拡張等）の確定は将来の実装Issueのspecが行う。package名はorganizer-diagnostics §7のNever分類に従い、出力面に含めない。
6. **PRのclosing keyword**: 本PRが #446 の全終了条件（ADR受入＋ADR-0005/package-provenance接続＋FR-008反映＋境界条件確定＋id保持確定＋module/bridge決定）を満たす単一の最終PRであるため `Closes #446` を使う（AGENTS.md「Issue駆動・仕様駆動の手順」7の規則）。Issue本文の「`Refs`するPRでdocs/adr/へ入れる」（メモ§8）は、ADRがmain直commitではなくPRで入ることの記述であり、最終性の判定はworkflowのclosing keyword規則に従う。

## Change history

- 2026-09-27: Draft created for #446.
