# Implementation Plan: product文書の再焦点化改訂

> Issue: #440
> Spec: N/A — docs-only maintenance。Issue本文のFeature checkが「specの作成は不要」（観測可能なproduct behaviorを変えない。受入条件はIssue終了条件で検証する）と明記しており、github-workflow.mdのmaintenance/docs-only規約（spec/plan不要理由・scope・exit criteriaの明記）に従う。実装上の判断は本planに固定する。
> Status: draft

## Current evidence

- 出典の正本: 再焦点化方針メモ（2026-09-24承認、Revision 5）。メモと作業草案（`refocus-drafts/`）はリポジトリ未収録であり、改訂全文は [Issue #440](https://github.com/nunu1733/NunuLauncher/issues/440) 付録（product-brief.md / requirements.md / other-doc-impacts.md）に添付されている。付録は「承認済み草案の全文」であり、本planは付録の内容を正本側のpathへ移植する際の適合化判断を固定するもので、判断・要件・IDの実質は付録（とその出典であるメモ）が正本である。
- 現状ファイル: `docs/product/product-brief.md`（2026-09-19版。organizer MVP中心）、`docs/product/requirements.md`（2026-09-19版。FR-008/FR-009はLater/deferred、FR-017はLater）、`README.md` 3行目（organizer中心の説明文）。
- 参照Issueの存在: #439〜#453はすべて起票済み（2026-09-25に `gh issue view` で確認）。
- CI挙動: 変更が `**/*.md` のみのPRではsource job（organizer-unit-tests / check-style / build-debug-apk）はpath filterでskipされ、`validate-repo-contract` のみ実行される（`.github/workflows/ci.yml` のsource filter）。validatorはMarkdown local linkの解決を検証する（`tools/repo-contract/validate_repo_contract.py`）。
- したがって付録草案が持つ次のlinkはそのまま移植するとCIがfailする: `../00-decision-memo.md`（メモはリポジトリ未収録）、`docs/adr/0013〜0015-*.md`（ADR未作成。`docs/adr/` の既存は0001〜0012）。
- spotlessの対象はjava/kotlinのみ（`build.gradle` のspotless block）。markdownは対象外。
- 矛盾の記録: Issue #440本文の非対象は `docs/project/seed-backlog.md` の変更を除外する一方、付録other-doc-impacts.mdとEpic #439の段階表は「RF-next項目の未起票提案表への移管を#440のPRで行う」とする。また移管対象一覧の正本であるメモ§4.5はIssue付録に含まれず、例示（図によるpreview、S3のpackage→category表、使用頻度を使う方針、「新着」の整理、重複の削除提案「等」）以外の全量が入手できない。→ 本PRでは実施しない。#440へ矛盾と判断をコメントで記録し、保守者がメモ§4.5一覧の提供または担当の再割当（Epic #439終了条件6の振替）を指示するまでopenとする（AGENTS.md「矛盾を見つけた場合はIssueへ記録して該当する正本を先に直す」）。

## Design

### 適合化の方針（付録草案 → 正本ファイル）

付録は草案（`refocus-drafts/` 配下のファイルとして）書かれたため、正本ファイルへ置く際に次の適合化を行う。新規の要件ID・判断を独自に追加しないことと、IssueのCompletion evidence（切れたlink 0件・ID割当がメモ§4.9と一致・既存IDのstatus維持）を満たすことを基準とする。

| # | 適合化 | 内容 |
|---|---|---|
| A-1 | status header | 付録の「承認済み草案（2026-09-24）」表記を、docs/README.mdの状態表記に合わせ `Accepted` + `Updated: 2026-09-25` + 出典行（メモ承認日・Revision 5・Issue #440付録・Epic #439への参照）へ置き換える |
| A-2 | メモへのlink | `[00-decision-memo.md](../00-decision-memo.md)`（未収録でlink切れ）はIssue #440 / Epic #439への絶対URL参照に置き換える |
| A-3 | 未作成ADRへのlink | Decision gatesのD-013〜D-016が参照する `docs/adr/0013〜0015-*.md` へのfile linkは、ADR起草担当Issue（D-013: #445、D-014: #447、D-015: #446、D-016: #443）へのlinkに置き換える。将来のADR file pathはlinkではなく平文（backtick）で併記し、各ADRがacceptedになった時点で当該IssueのPRがfile linkへ更新する（その旨をDecision gates行に残す） |
| A-4 | 相対linkの正規化 | 付録の `../../docs/product/x.md` 形式は現行ファイルの慣行（`./x.md`、`../adr/x.md`、`../../specs/x/spec.md`）へ正規化する（解決先は同一のため意味不変） |
| A-5 | 草案注記の除去 | 「変更なし。」「既存のID・statusは維持する。」「（既存のとおり。変更しない）」等の改訂指示表現は正本ファイルには載せない。該当section（requirements.mdのRequired item coverage / Traceability rule / Decision gatesのD-001〜D-012行）は現行の内容をそのまま維持することで反映する |
| A-6 | status表記 | FR-008は `proposed（再定義 2026-09-24。旧: deferred by Issue #85）`、FR-017は `Frozen（2026-09-24。旧: spec accepted（…））`、新規ID（FR-018〜023、NFR-013〜014）は `proposed（2026-09-24）`。付録の「Draft 2026-09-24」のDraft語は正本では除く |
| A-7 | Decision history | 付録の「2026-09-24（proposed）」項を承認済み判断として確定して追加する（「本項は草案であり、保守者の承認後に正本へ反映する」の文は除去。メモ参照は#439/#440への参照に置換） |
| A-8 | 未解決事項section | 付録の「未解決事項（保守者の判断が必要）」のうち、本PRの保守者承認をもって確定する項目（product-brief: Vision文言、Target users。requirements: FR-009文言、FR-017のfrozen語彙、NFR-013のCategory名 `Responsiveness`、FR-008 status表記、D-013〜016のlink方針）は、確定記録としてDecision history（requirements.md）および本PR本文に残し、正本ファイルの未解決事項sectionには残課題（#441への測定対象判断、ADR-0015承認前の測定条件、FR-022の#443分担、各ADR accepted時のlink更新）のみを残す |

### Change set

| File | Intended change |
|---|---|
| `docs/product/product-brief.md` | 付録版へ全面置換（Vision、User problem、Target users、Value proposition、Product principles、Now outcome（旧MVP outcome）、Non-goals、Success measures、未解決事項）。適合化A-1/A-2/A-4/A-8 |
| `docs/product/requirements.md` | 現行ファイルを基準に、FR-008/FR-009/FR-017の3行を付録版へ置換し、FR-018〜023・NFR-013〜014の行とDecision gatesのD-013〜016行を追加し、Decision historyへ2026-09-24項を追加し、headerを更新する。既存の他の行（FR-001〜007/010〜016、NFR-001〜012、D-001〜012）とRequired item coverage・Traceability rule sectionは現行維持。適合化A-1/A-3/A-4/A-5/A-6/A-7/A-8 |
| `README.md` | 3行目の説明文を改訂後のVisionと一致させる（下記案）。読み始める場所の順序は変更しない |
| `docs/product/mvp-release-readiness.md` | 冒頭へ1文の注記（下記案）を追加する【保守者判断1】 |

### README.md冒頭案（3行目の置換文）

> ホーム画面の日常的な編集と、散らからない状態を保つ手間を減らす、Android向けランチャープロジェクトです。Lawnchairを基盤とし、編集アクションによる直接編集と、ルールに基づく安全で説明可能なホームレイアウト整理（Organizer）を提供します。

### mvp-release-readiness.md注記案（冒頭blockquoteへ1行追加、本文は英語のため英語）

> Note (2026-09-25): the organizer MVP outcome assessment above is final; subsequent outcome judgments moved to editing-burden reduction ([product-brief.md Now outcome](./product-brief.md), [#441](https://github.com/nunu1733/NunuLauncher/issues/441)).

## 保守者判断事項（本PRのreviewで確定する）

1. **mvp-release-readiness.mdへの1文注記のタイミング**: 本PRに含めるか、FR-008の新spec起票時に送るか。推奨は本PRに含める（other-doc-impacts.mdの担当割当は「#440（注記のみ）」であり、1文のみで後続に残す理由がない）。
2. **seed-backlog.mdのRF-next移管の除外**: 推奨は本PRから除外（#440非対象の明記、およびメモ§4.5一覧が未入手で全量を保証できないため）。矛盾は#440へ記録し、保守者の指示を待つ。
3. **README.md冒頭とmvp-release-readiness.md注記の文言**: 案のとおりでよいか、修正するか。
4. **適合化A-8（未解決事項の整理）の承認**: 付録の未解決事項を「本PRで確定するもの」と「残課題」に振り分ける方針の承認。

## Migration and recovery

- 対象なし（docs-only。schema/rule/runtimeへの影響なし）。戻し方は `git revert` のみ。

## Verification

| Evidence（IssueのCompletion evidence） | 方法 | Command |
|---|---|---|
| 切れたlink 0件（product-brief.md / requirements.mdの全相対link） | repo-contract validator（CIのlink検証と同一） | `python3 tools/repo-contract/validate_repo_contract.py` |
| validator自己整合 | self-test | `python3 tools/repo-contract/test_validate_repo_contract.py` |
| FR/NFR/D-IDの割当がメモ§4.9と一致（差分0件・新規IDの独自追加なし）、既存IDのstatusが失われていない | requirements.mdのdiffでIDごとに照合（FR-008再定義・FR-017 Frozen・FR-018〜023/NFR-013〜014/D-013〜016追加、他のIDのPhase/Statusは不変）し、結果をPR本文に記録 | `git diff` + 目視照合 |
| spotlessCheck | markdownはspotless対象外（java/kotlinのみ）。省略理由をPRに記載する。無影響の確認も兼ねて実行する | `./gradlew spotlessCheck` |
| 保守者のreview承認 | PR review（ChatGPT review結果のGitHub確認 + 保守者の最終承認） | GitHub |

CI evidence: docs-only PRのため `validate-repo-contract` jobの成功をもって merge gate (`final-status`) の成立とする（source jobはpath filterによりskip。品質戦略のEvidence選択原則「docs-only変更: repository contract gateで足りる」）。

## Documentation updates

- [x] `docs/product/product-brief.md` / `docs/product/requirements.md`（Change setのとおり）
- [x] `README.md` 冒頭
- [ ] `docs/product/mvp-release-readiness.md` 注記（保守者判断1）
- [ ] `docs/project/seed-backlog.md` RF-next移管（本PRでは実施しない。保守者判断2）
- CONTEXT.md / DESIGN.md / ADR / AGENTS.md / organization-run-ux.md / organizer-to-be-ux.md / docs/README.md は変更しない（other-doc-impacts.mdの担当割当どおり、各担当RF（#443/#445/#446/#447/#451/#452/#453/#439）のPRで行う）

## Execution checklist

- [x] Issue・付録・関連正本の確認（AGENTS.md必読順）
- [x] 適合化判断と保守者判断事項の固定（本plan）
- [ ] Phase 1 review（ChatGPT + 保守者）の指摘対応
- [ ] 文書改訂の実装（Change set 4ファイル）
- [ ] 検証の実行とPRへの結果記録
- [ ] Phase 2 review（ChatGPT + 保守者）の指摘対応
- [ ] 独立監査（general-purpose subagent、docs-onlyのため高リスクaudit要件の対象外であるが独立確認として実施）
- [ ] 保守者承認・merge（本PRがIssue #440の全終了条件を満たす最終PRとして `Closes #440` で閉じる）
