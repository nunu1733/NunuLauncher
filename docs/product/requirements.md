# Product Requirements

> Status: Accepted — 要件ごとの実装・evidence・deferred dispositionは [MVP release readiness](./mvp-release-readiness.md) を正本とする。
> Updated: 2026-08-23

## Functional requirements

| ID | Phase | Status | Requirement |
|---|---|---|---|
| FR-001 | Foundation | implemented | side effectなしにlayout snapshotからレイアウトplanとdiagnosticを生成できる |
| FR-002 | MVP | implemented | 全体整理の対象集合を明示し、対象外itemを変更しない |
| FR-003 | MVP | implemented | ロック配置と占有領域を保持し、満たせないplanは適用不能として説明する |
| FR-004 | Foundation | implemented | 適用前にrecovery pointを作り、失敗時とユーザー操作時に復旧できる |
| FR-005 | Foundation | implemented | staleでない検証済みplanだけを原子的に適用し、適用後に再検証する |
| FR-006 | MVP | implemented | 明示的なユーザー操作から全体整理を開始し、差分・警告・未配置itemを確認できる |
| FR-007 | MVP | implemented | onboardingで整理を提案できるが、既存layoutの無確認な全体変更を行わない |
| FR-008 | Later | deferred by [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) | 新しいlaunchable appをuser/profile identityを保ったまま増分配置できる。package eventによるincremental placementはMVP外であり、将来のaccepted product decisionとspecが必要である。 |
| FR-009 | Later | deferred by [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) | 増分配置はfull organizationと収束し、update/restoreを新規installと誤認しない。FR-008と不可分のためMVP外とする。 |
| FR-010 | MVP | implemented | ユーザーがカテゴリ割当をoverrideでき、推定より優先される |
| FR-011 | MVP | implemented | Android application category等のlocal signalとdeterministic fallbackで分類できる |
| FR-012 | Later | deferred | version付き整理ルールをvalidation付きでimport/exportできる |
| FR-013 | Later | deferred | usage signalを明示的な許可の下で利用し、取得不能時も動作できる |
| FR-014 | Later | deferred | local分類が不明な場合だけ、明示的opt-inで外部分類adapterを利用できる |
| FR-015 | MVP | implemented | 各移動、folder化、未配置、fallbackの主要理由を表示・診断できる |

## Non-functional requirements

| ID | Category | Status | Requirement |
|---|---|---|---|
| NFR-001 | Data safety | implemented | crash、cancel、process death、書込失敗で適用前layoutを失わない |
| NFR-002 | Integrity | implemented | conservation、bounds、overlap、container参照、lock、profile isolationを適用前後に検証する |
| NFR-003 | Determinism | implemented | 同じcanonical inputから同じplanを生成する。tie-breakをruleで固定する |
| NFR-004 | Idempotence | implemented | 適用済みlayoutへの同じfull organizationは空の差分を返す |
| NFR-005 | Offline | implemented | MVPの計画・適用・復旧はnetworkなしで完了する |
| NFR-006 | Performance | accepted/evidence pending | 端末class、item数、grid別のp50/p95 budgetを性能Issueで定義し、UI threadを長時間blockしない |
| NFR-007 | Compatibility | accepted/evidence pending | 採用Lawnchair revisionがsupportするphone/tablet、orientation、profile、gridで検証する |
| NFR-008 | Privacy | accepted/evidence pending | package、usage、rule、layout情報の収集・保持・送信をdocument化し、外部送信をdefault offにする |
| NFR-009 | Accessibility | accepted/evidence pending | 確認、警告、進捗、復旧をTalkBack、font scaling、keyboard/switch accessで利用可能にする |
| NFR-010 | Maintainability | accepted/evidence pending | project固有logicを少数の深いmoduleへ置き、上流sourceのpatch surfaceを記録・計測する |
| NFR-011 | Observability | accepted/evidence pending | 個人情報を含めず、run ID、phase、error category、plan summaryを診断可能にする |
| NFR-012 | Migration | implemented | DB/rule schema変更はupgrade、downgrade/rollback、backup/restoreとの整合をtestする |

## Required item coverage

対象基準commitが持つ全item typeをinventoryし、各typeについて次のいずれかをspecで明記する。

- move: 整理対象として移動する。
- preserve: 現在の配置を占有constraintとして保持する。
- transform: 明示的な変換規則と復旧方法を持つ。
- reject: run全体を適用不能にし、理由を示す。

「認識できないので無視する」は許容しない。最低限、app icon、deep shortcut、folderとchild、app widget、custom widget、app pair、Dock、複数user/profileを調査対象とする。

## Decision gates

| ID | Decision | Recommendation | Blocks |
|---|---|---|---|
| D-001 | 基準revision | 15 Beta 3を候補に再現buildと既存機能を評価し、commit SHAで固定 | 全実装 |
| D-002 | Deck layoutの扱い | replaceを採用。既存Deckのruntime除去は別Issueで扱う（[ADR-0002](../adr/0002-replace-deck-layout.md)） | architecture |
| D-003 | 対象集合 | defaultで既存home itemを保持し、drawer全アプリ追加は明示的modeに分ける | FR-002, planner |
| D-004 | trigger | 手動、onboarding提案、package event増分を別policyにする。package eventによるincremental placementは[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)のOption BによりMVP外へdeferredとする。 | FR-006〜009 |
| D-005 | safe UX | recoveryをFoundation、preview/confirmationをMVPに置く | apply/UI |
| D-006 | lock semantics | itemではなくplacement constraintとして定義し、folder/Dock/grid変更を明記。storageは[ADR-0004](../adr/0004-organizer-lock-persistence.md)で決定。 | FR-003 |
| D-007 | layout strategy v1 | fixed rowではなくdevice profileからregionを導出する | planner |
| D-008 | category taxonomy | Android categoryをsignalの1つとし、project taxonomyを独立定義する | FR-010〜011 |
| D-009 | rule format | typed model/version/migrationを先に決め、XML/JSONは比較後に選ぶ | FR-012 |
| D-010 | usage access | optionalとし、拒否時のdeterministic fallbackを必須にする | FR-013 |
| D-011 | external LLM | privacy/threat modelとoffline behavior承認後まで導入しない | FR-014 |
| D-012 | UI framework | 既存画面のconventionを優先し、Compose/Viewを画面ごとに判断 | UI work |

## Decision history

- 2026-08-21: [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) selected Option B. FR-008 and FR-009 move from MVP to Later/deferred; the current package-event behavior remains fail-closed and produces no incremental proposal.

## Traceability rule

要件を変更するIssueはIDをtitle/bodyへ記載する。spec frontmatterは関連IDを列挙し、PRはIssueとspecをlinkする。実装後、要件のstatusはIssueの受入、mainline merge、verification evidenceを根拠に更新する。要件別のowning Issue/spec/PR、primary evidence、limitation、blocking follow-upは [MVP release readiness](./mvp-release-readiness.md) にだけ記録する。
