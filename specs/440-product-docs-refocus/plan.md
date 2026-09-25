# Implementation Plan: product文書の再焦点化改訂

> Issue: #440
> Spec: N/A — docs-only maintenance。Issue本文のFeature checkが「specの作成は不要」（観測可能なproduct behaviorを変えない。受入条件はIssue終了条件で検証する）と明記しており、github-workflow.mdのmaintenance/docs-only規約（spec/plan不要理由・scope・exit criteriaの明記）に従う。実装上の判断は本planに固定する。
> Status: draft（Phase 1 review指摘と保守者判断を反映。Revision 2）

## Current evidence

- 出典の正本: 再焦点化方針メモ（2026-09-24承認、Revision 5）。メモと作業草案はリポジトリ未収録であり、改訂全文は [Issue #440](https://github.com/nunu1733/NunuLauncher/issues/440) 付録（product-brief.md / requirements.md / other-doc-impacts.md）に添付されている。付録は草案（`refocus-drafts/product/`）のRF-IDをIssue番号へ置換した全文であり、本planは付録の内容を正本側のpathへ移植する際の適合化判断を固定するもので、判断・要件・IDの実質はメモと付録が正本である。
- 作業参照資料: 保守者が `refocus-drafts/`（メモRevision 5・ADR草案・RF草案・`issues/RF-next-backlog.md` を含む監査一式）を作業directoryへ配置した（2026-09-25、untracked。Issue本文が「リポジトリ未収録」と宣言するため本PRではcommitしない）。seed-backlog移管の項目一覧はメモ§4.5とRF-next-backlog.mdのNext-1〜12・Later 3項目を正本とする。
- 現状ファイル: `docs/product/product-brief.md`（2026-09-19版。organizer MVP中心）、`docs/product/requirements.md`（2026-09-19版。FR-008/FR-009はLater/deferred、FR-017はLater）、`README.md` 3行目（organizer中心の説明文）、`docs/project/seed-backlog.md`（未起票提案4件）。
- 参照Issueの存在: #439〜#453はすべて起票済み（2026-09-25に `gh issue view` で確認）。
- CI挙動: 変更が `**/*.md` のみのPRではsource job（organizer-unit-tests / check-style / build-debug-apk）はpath filterでskipされ、`validate-repo-contract` のみ実行される（`.github/workflows/ci.yml` のsource filter）。validatorはMarkdown local linkの解決を検証する（`tools/repo-contract/validate_repo_contract.py`）。Phase 1の最初のhead（d9afa2d）ではplan.md内のlink例示がこの検証に掛かりCIが失敗した（PR #463レビュー指摘1。本Revisionで修正）。
- 付録草案が持つ次のlinkはそのまま移植するとCIがfailする: メモへの相対link（メモはリポジトリ未収録）、`docs/adr/0013〜0015-*.md`（ADR未作成。`docs/adr/` の既存は0001〜0012）。
- spotlessの対象はjava/kotlinのみ（`build.gradle` のspotless block）。markdownは対象外。
- seed-backlog担当の矛盾（解決済み）: Issue #440本文の非対象はseed-backlog.md変更を除外する一方、付録other-doc-impacts.mdとEpic #439は「RF-next項目の移管を#440のPRで行う」としていた。2026-09-25の保守者判断で「本PRに含める」へ確定し、矛盾記録コメント（issues/440#issuecomment-5833760135）の推奨から変更した。Epic #439終了条件6は本PRで満たされる。

## Design

### 適合化の方針（付録草案 → 正本ファイル）

付録は草案として書かれたため、正本ファイルへ置く際に次の適合化を行う。新規の要件ID・判断を独自に追加しないことと、IssueのCompletion evidence（切れたlink 0件・ID割当がメモ§4.9と一致・既存IDのstatus維持）を満たすことを基準とする。

| # | 適合化 | 内容 |
|---|---|---|
| A-1 | status header | 付録の「承認済み草案（2026-09-24）」表記を、docs/README.mdの状態表記に合わせ `Accepted` + `Updated: 2026-09-25` + 出典行（メモ承認日・Revision 5・Issue #440付録・Epic #439への参照）へ置き換える |
| A-2 | メモへのlink | 付録のメモ参照相対link（メモはリポジトリ未収録のためlink切れ）はIssue #440 / Epic #439への絶対URL参照に置き換える |
| A-3 | 未作成ADRへのlink | Decision gatesのD-013〜D-016が参照する `docs/adr/0013〜0015-*.md` へのfile linkは、ADR起草担当Issue（D-013: #445、D-014: #447、D-015: #446、D-016: #443）へのlinkに置き換える。将来のADR file pathはlinkではなく平文（backtick）で併記し、各ADRがacceptedになった時点で当該IssueのPRがfile linkへ更新する（その旨をDecision gates行に残す） |
| A-4 | 相対linkの正規化 | 付録の `../../docs/product/x.md` 形式は現行ファイルの慣行（`./x.md`、`../adr/x.md`、`../../specs/x/spec.md`）へ正規化する（解決先は同一のため意味不変） |
| A-5 | 草案注記の除去 | 「変更なし。」「既存のID・statusは維持する。」「（既存のとおり。変更しない）」等の改訂指示表現は正本ファイルには載せない。該当section（requirements.mdのRequired item coverage / Decision gatesのD-001〜D-012行）は現行の内容をそのまま維持することで反映する。Traceability ruleはA-9の適合化を行う |
| A-6 | status表記 | FR-008は `proposed（再定義 2026-09-24。旧: deferred by Issue #85）`、FR-017は `Frozen（2026-09-24。旧: spec accepted（…））`、新規ID（FR-018〜023、NFR-013〜014）は `proposed（2026-09-24）`。付録の「Draft 2026-09-24」のDraft語は正本では除く |
| A-7 | Decision history | 付録の「2026-09-24（proposed）」項を承認済み判断として確定して追加する（「本項は草案であり、保守者の承認後に正本へ反映する」の文は除去。メモ参照は#439/#440への参照に置換） |
| A-8 | 未解決事項section | 付録の「未解決事項（保守者の判断が必要）」のうち、本PRの保守者承認をもって確定する項目（product-brief: Vision文言、Target users。requirements: FR-009文言、FR-017のfrozen語彙、NFR-013のCategory名 `Responsiveness`、FR-008 status表記、D-013〜016のlink方針）は、確定記録としてDecision history（requirements.md）および本PR本文に残し、正本ファイルの未解決事項sectionには残課題（#441への測定対象判断、ADR-0015承認前の測定条件、FR-022の#443分担、各ADR accepted時のlink更新）のみを残す。保守者承認済み（2026-09-25） |
| A-9 | Traceability ruleの適合化 | 現行の「要件別のowning Issue/spec/PR、primary evidence、limitation、blocking follow-upは mvp-release-readiness.md にだけ記録する」は、FR-008再定義・FR-017 Frozen・新規要件追加後はreadiness（organizer MVPのevidence inventoryのまま）と衝突する。requirements.mdのTraceability ruleを次へ書き換える: mvp-release-readiness.mdはorganizer MVP要件（MVP/Foundation phase要件）のevidence inventoryの正本のまま変えない。再定義FR-008、FR-017（Frozen）、FR-018〜023、NFR-013〜014のstatus・evidence・owning Issue/spec/PRはrequirements.mdと各担当Issue/spec/PRが正本とする（Phase 1 review指摘2への対応。保守者承認済み） |
| A-10 | Frozen語彙の定義 | メモ§4.9の確定事項「状態の語彙に frozen（実装済みの機能を残すが、開発を止めたもの）を加える」に従い、requirements.mdのFunctional requirements table直前にstatus語彙の注記行を追加する（`frozen` と `deferred` の差の明示を含む。Phase 1 review指摘3への対応。保守者承認済み） |
| A-11 | seed-backlogのRF-next移管 | メモ§12 B4（RF-nextは起票せず、RF-01=#440のPRで未起票提案の表へ移す）に従い、`docs/project/seed-backlog.md` の未起票提案の表をRF-next-backlog.mdのNext-1〜12とLater 3項目（FR-012、FR-014、#206）へ置き換える。既存の4提案（rule format、import/export、usage-frequency、external classification）はそれぞれFR-012 Later・Next-4・FR-014 Laterへ吸収されるため除く。Issue navigationへの再焦点化行の追加は#439の担当のため行わない |

### Change set

| File | Intended change |
|---|---|
| `docs/product/product-brief.md` | 付録版へ全面置換（Vision、User problem、Target users、Value proposition、Product principles、Now outcome（旧MVP outcome）、Non-goals、Success measures、未解決事項）。適合化A-1/A-2/A-4/A-8 |
| `docs/product/requirements.md` | 現行ファイルを基準に、FR-008/FR-009/FR-017の3行を付録版へ置換し、FR-018〜023・NFR-013〜014の行とDecision gatesのD-013〜016行を追加し、Decision historyへ2026-09-24項を追加し、headerを更新する。既存の他の行（FR-001〜007/010〜016、NFR-001〜012、D-001〜012）とRequired item coverage sectionは現行維持。適合化A-1/A-3/A-4/A-5/A-6/A-7/A-8/A-9/A-10 |
| `README.md` | 3行目の説明文を改訂後のVisionと一致させる（下記案）。読み始める場所の順序は変更しない |
| `docs/product/mvp-release-readiness.md` | 冒頭へ1文の注記（下記案）を追加する |
| `docs/project/seed-backlog.md` | 未起票提案の表をRF-next項目（Next-1〜12、Later 3項目）へ置き換える（適合化A-11）。表のintro文と末尾の独立track注記を再焦点化後の状態へ更新する。headerのUpdatedを更新する |

### README.md冒頭案（3行目の置換文。保守者承認済み・2026-09-25）

> ホーム画面の日常的な編集と、散らからない状態を保つ手間を減らす、Android向けランチャープロジェクトです。Lawnchairを基盤とし、編集アクションによる直接編集と、ルールに基づくホームレイアウト一新機能を提供します。

### mvp-release-readiness.md注記案（冒頭blockquoteへ1行追加。本文は英語のため英語）

> Note (2026-09-25): the organizer MVP outcome assessment above is final; subsequent outcome judgments moved to editing-burden reduction (see the "Now outcome" section of product-brief.md and #441). Requirements added or redefined by the 2026-09 refocus (FR-008, FR-017, FR-018〜FR-023, NFR-013/014) are tracked in requirements.md and their owning issues, not in this inventory.

実際の注記では、docs/product/ 配下で解決する相対link（product-brief.md・requirements.mdは `./` 参照）と [#441](https://github.com/nunu1733/NunuLauncher/issues/441) への絶対linkを付ける。

## 保守者判断事項の確定（2026-09-25、owner decision）

Phase 1 review（PR #463、issues/440のレビューコメント）がowner decisionを求めた4項目を、保守者が2026-09-25に確定した。判断の記録はPR #463のコメントに残す。

1. **mvp-release-readiness.mdへの1文注記**: 本PRに含める（other-doc-impacts.mdの担当割当「#440（注記のみ）」どおり）。
2. **seed-backlog.mdのRF-next移管**: 本PRに含める。保守者が `refocus-drafts/` 監査一式（メモRevision 5・RF-next-backlog.md）を作業directoryへ配置したため、メモ§4.5/§12 B4に従い移管を実施する。Epic #439終了条件6（RF-nextの移管）は本PRで満たす。
3. **README.md冒頭とreadiness注記の文言**: READMEは保守者指定の文言（上記案。「安全で説明可能な」を除外し「ホームレイアウト一新機能を提供します」）とする。readiness注記はplan案どおり。
4. **適合化A-8/A-9/A-10**: 承認（未解決事項の振り分け、Traceability rule適合化、Frozen語彙定義の追加）。

## Migration and recovery

- 対象なし（docs-only。schema/rule/runtimeへの影響なし）。戻し方は `git revert` のみ。

## Verification

| Evidence（IssueのCompletion evidence） | 方法 | Command / oracle |
|---|---|---|
| 切れたlink 0件（変更後の全markdown） | repo-contract validator（CIのlink検証と同一）。Phase 1 headでも通す | `python3 tools/repo-contract/validate_repo_contract.py` |
| validator自己整合 | self-test | `python3 tools/repo-contract/test_validate_repo_contract.py` |
| FR/NFR/D-IDの割当がメモ§4.9と一致（差分0件・新規IDの独自追加なし）、既存IDのstatusが失われていない | oracleは再現可能な成果物に固定する: (a) Issue #440本文の要件ID一覧（改訂・追加するID: FR-008再定義、FR-017 Frozen、FR-018〜023、NFR-013〜014、D-013〜016）、(b) Issue #440付録requirements.md（メモ§4.9の転載）。requirements.mdのdiffでIDごとに照合（FR-008再定義・FR-017 Frozen・追加ID、他のIDのPhase/Statusは不変）し、照合結果のID表をPR本文へ転記して独立再現可能にする | `git diff` + 目視照合（結果をPR本文へ転記） |
| seed-backlogのRF-next移管がメモ§4.5と一致 | oracle: メモ§4.5 Next/Later項目とRF-next-backlog.md（Next-1〜12・Later 3項目）。移管後の表との一致をPR本文へ転記 | 目視照合（結果をPR本文へ転記） |
| spotlessCheck | markdownはspotless対象外（java/kotlinのみ）。省略理由をPRに記載する。無影響の確認も兼ねて実行する | `./gradlew spotlessCheck` |
| 保守者のreview承認 | PR review（ChatGPT review結果のGitHub確認 + 保守者の最終承認） | GitHub |

CI evidence: docs-only PRのため `validate-repo-contract` jobの成功をもって merge gate (`final-status`) の成立とする（source jobはpath filterによりskip。品質戦略のEvidence選択原則「docs-only変更: repository contract gateで足りる」）。

## Documentation updates

- [ ] `docs/product/product-brief.md` / `docs/product/requirements.md`（Change setのとおり。Phase 2で実施）
- [ ] `README.md` 冒頭（Phase 2で実施）
- [ ] `docs/product/mvp-release-readiness.md` 注記（Phase 2で実施）
- [ ] `docs/project/seed-backlog.md` RF-next移管（Phase 2で実施）
- 変更しないもの: CONTEXT.md / DESIGN.md / ADR / AGENTS.md / organization-run-ux.md / organizer-to-be-ux.md / docs/README.md（other-doc-impacts.mdの担当割当どおり、各担当RF（#443/#445/#446/#447/#451/#452/#453/#439）のPRで行う）

## Execution checklist

- [x] Issue・付録・関連正本の確認（AGENTS.md必読順）
- [x] 適合化判断と保守者判断事項の固定（本plan）
- [x] Phase 1 review（ChatGPT）の指摘対応（Revision 2。指摘1〜5へ対応、保守者判断を確定）
- [ ] Phase 1 再reviewのクリア
- [ ] 文書改訂の実装（Change set 5ファイル）
- [ ] 検証の実行とPRへの結果記録
- [ ] Phase 2 review（ChatGPT + 保守者）の指摘対応
- [ ] 独立監査（general-purpose subagent、docs-onlyのため高リスクaudit要件の対象外であるが独立確認として実施）
- [ ] 保守者承認・merge（本PRがIssue #440の全終了条件を満たす最終PRとして `Closes #440` で閉じる）
