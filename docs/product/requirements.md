# Product Requirements

> Status: Accepted — 再焦点化方針メモ（2026-09-24承認、Revision 5）による改訂（[Issue #440](https://github.com/nunu1733/NunuLauncher/issues/440)）
> Updated: 2026-09-25
> 出典: 再焦点化方針メモ（リポジトリ未収録のため [Issue #440](https://github.com/nunu1733/NunuLauncher/issues/440) 付録に全文）§4.9、[Epic #439](https://github.com/nunu1733/NunuLauncher/issues/439)
> organizer MVP要件の実装・evidence・deferred dispositionは [MVP release readiness](./mvp-release-readiness.md) を正本とする。

## Functional requirements

Status語彙: `implemented` / `accepted/evidence pending` / `proposed` / `deferred`（開発対象から外した将来候補）/ `frozen`（実装済みの機能を残すが、開発を止めたもの。2026-09-24メモ§4.9で追加）。

| ID | Phase | Status | Requirement |
|---|---|---|---|
| FR-001 | Foundation | implemented | side effectなしにlayout snapshotからレイアウトplanとdiagnosticを生成できる |
| FR-002 | MVP | implemented | 全体整理の対象集合を明示し、対象外itemを変更しない |
| FR-003 | MVP | implemented | ロック配置と占有領域を保持し、満たせないplanは適用不能として説明する |
| FR-004 | Foundation | implemented | 適用前にrecovery pointを作り、失敗時とユーザー操作時に復旧できる |
| FR-005 | Foundation | implemented | staleでない検証済みplanだけを原子的に適用し、適用後に再検証する |
| FR-006 | MVP | implemented | 明示的なユーザー操作から全体整理を開始し、差分・警告・未配置itemを確認できる。ユーザー可視の入口はOrganizer hub経由へ改訂される（[organizer-to-be-ux.md](./organizer-to-be-ux.md) D-01/D-03。hub導入は後続実装Issueが行う） |
| FR-007 | MVP | implemented | onboardingで整理を提案できるが、既存layoutの無確認な全体変更を行わない |
| FR-008 | Now-1 | proposed（再定義 2026-09-24。旧: deferred by [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)） | 上流がホームへ追加する新規アプリのアイコンを、ユーザーが選んだ配置先ポリシーに従って置ける。追加するかどうかの判定は上流のまま変えず、どこへ置くかだけを決める（ADR-0015）。書込みの安全条件はADR-0013に従い、本書では重複して定義しない |
| FR-009 | Later | deferred by [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) | 増分配置と全体整理の収束。FR-008の再定義後も、既存アイテムを動かす増分整理提案はLaterのままとする（ADR-0005は「既存アイテムを動かす増分整理提案についての判断」として維持する） |
| FR-010 | MVP | implemented | ユーザーがカテゴリ割当をoverrideでき、推定より優先される。割当先は組み込みtaxonomyに加えユーザー定義カテゴリ（stable local identity、[spec 336](../../specs/336-user-defined-categories/spec.md)）も選択できる |
| FR-011 | MVP | implemented | Android application category等のlocal signalとdeterministic fallbackで分類できる |
| FR-012 | Later | deferred | version付き整理ルールをvalidation付きでimport/exportできる |
| FR-013 | Later | input implemented ([spec 203](../../specs/203-usage-implicit-preference-signals/spec.md), [Issue #203](https://github.com/nunu1733/NunuLauncher/issues/203)) | usage signalを明示的な許可の下で利用し、取得不能時も動作できる。normalized signal snapshot (`PersonalizationSignalSnapshot`) と usage access の明示的opt-in/opt-outを実装。usage-based strategy自体は#182 catalogの将来member |
| FR-014 | Later | deferred | local分類が不明な場合だけ、明示的opt-inで外部分類adapterを利用できる |
| FR-015 | MVP | implemented | 各移動、folder化、未配置、fallbackの主要理由を表示・診断できる |
| FR-016 | Later | implemented ([spec 182](../../specs/182-layout-strategy-catalog/spec.md), [ADR-0012](../adr/0012-versioned-layout-strategy-catalog.md), [spec 237](../../specs/237-global-compact-v2-folder-relocation/spec.md)、widget移動対応の `STABLE_PAGE_TIDY_V2`/`BOTTOM_FIRST_V2` は [spec 235](../../specs/235-widget-strategy-placement/spec.md)、下部領域successorの `BOTTOM_REGION_V1` は [spec 398](../../specs/398-strategy-intent-first-bottom-region/spec.md)) | ユーザーがversion付きの組み込みlayout strategyを選択でき、有効strategyのidentityと結果 (移動件数、新規folder/page、strategy固定item、警告) を確認前にpreviewできる。選択はversion付きで検証され、unsupported/破損/newer選択はfail-closedする。**strategyのprimary success metricは「ユーザーが選んだ/表明したHome構成意図との一致」であり、page/folder/density等の数量指標は意図一致を損なわない範囲の補助指標・tie-breakerである（[spec 398](../../specs/398-strategy-intent-first-bottom-region/spec.md) Strategy objective）。** |
| FR-017 | Frozen | Frozen（2026-09-24。旧: spec accepted ([spec 204](../../specs/204-ai-personalization-context-intent-contract/spec.md), [spec 205](../../specs/205-external-agent-exchange/spec.md), [spec 328](../../specs/328-exchange-import-success-state/spec.md), [spec 329](../../specs/329-import-normalizer/spec.md), Issues [#204](https://github.com/nunu1733/NunuLauncher/issues/204)/[#205](https://github.com/nunu1733/NunuLauncher/issues/205)/[#328](https://github.com/nunu1733/NunuLauncher/issues/328)/[#329](https://github.com/nunu1733/NunuLauncher/issues/329))） | AI personalization（外部AI交換）の機能開発を凍結する（D-016、[#443](https://github.com/nunu1733/NunuLauncher/issues/443)）。コードとテストは残し、既定の導線から外して実験的機能のtoggleの背後へ移す。データ安全に関わるbugの修正は行う。既存のdurableな状態（有効な依頼、取り込み済み提案）がある間は期限切れまで到達可能に保つ |
| FR-018 | Now-2 | proposed（2026-09-24） | ユーザーが長押しで選んだアイテムに、編集アクション（ページへ移動、フォルダへ入れる、ホームから外す）を1操作で適用できる（[#448](https://github.com/nunu1733/NunuLauncher/issues/448)、D-013） |
| FR-019 | Now-2 | proposed（2026-09-24） | 複数のアイテムを選び、まとめてページへ移動する・既存のフォルダへ入れる・選択から新しいフォルダを作る・ホームから外すことができる視覚的編集画面を提供する（[#449](https://github.com/nunu1733/NunuLauncher/issues/449)、D-014） |
| FR-020 | Now-2 | proposed（2026-09-24） | 直前の編集（項目単位のアクション、編集画面の確定）を1操作で取り消せる（[#450](https://github.com/nunu1733/NunuLauncher/issues/450)、D-013） |
| FR-021 | Now-3 | proposed（2026-09-24） | 全体整理は、同じ起動先のアイテムを同じ新規フォルダに入れず、重複をpreviewで示す（[#451](https://github.com/nunu1733/NunuLauncher/issues/451)） |
| FR-022 | Now-3 | proposed（2026-09-24） | ホーム画面から整理を開始でき、AI相談が無効の間は方法選択を経ない（[#452](https://github.com/nunu1733/NunuLauncher/issues/452)、[#443](https://github.com/nunu1733/NunuLauncher/issues/443)） |
| FR-023 | Now-3 | proposed（2026-09-24） | 整理方針の選択肢は、区別できる意図ごとに最大3つである（[#453](https://github.com/nunu1733/NunuLauncher/issues/453)） |

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
| NFR-013 | Responsiveness | proposed（2026-09-24） | 直接編集の応答性: 編集アクションの結果が即座に見える。数値はspecで決める（[#448](https://github.com/nunu1733/NunuLauncher/issues/448)、[#449](https://github.com/nunu1733/NunuLauncher/issues/449)） |
| NFR-014 | Editing burden | accepted（2026-09-26。ベンチマークのbaseline・目標確定に伴う。旧: proposed（2026-09-24）） | Now段階の機能は編集負担ベンチマーク（手順コストの会計）の目標を満たす（[#441](https://github.com/nunu1733/NunuLauncher/issues/441)が正本） |

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
| D-010 | usage access | optionalとし、拒否時のdeterministic fallbackを必須にする。spec 203で `NOT_GRANTED`/`UNAVAILABLE` をsection-level availabilityとしてtyped化し、launcher-origin signalを別sourceとして併用 | FR-013 |
| D-011 | external LLM | privacy/threat modelとoffline behavior承認後まで導入しない。FR-017 (AI personalization intent契約、[spec 204](../../specs/204-ai-personalization-context-intent-contract/spec.md)) もD-011の対象であり、#205のprovider接続実装前にprivacy/threat model承認を要求する。#205のexternal exchangeはnetwork/provider APIを含まないuser-mediated text交換 (clipboard/share/file) であり、privacy/threat modelは[spec 205](../../specs/205-external-agent-exchange/spec.md)が定義しreviewで承認済み。in-app provider API接続 (#206) は引き続き本gate内 | FR-014, FR-017 |
| D-012 | UI framework | 既存画面のconventionを優先し、Compose/Viewを画面ごとに判断 | UI work |
| D-013 | 直接編集の書込み契約 | ADR-0013「直接編集の書込み契約」。起草: [#445](https://github.com/nunu1733/NunuLauncher/issues/445)。accepted後に `docs/adr/0013-direct-edit-write-contract.md` を作成してここへlinkする | FR-018, FR-020, FR-008, #446/#447/#449 |
| D-014 | 編集の操作面と上流workspaceへの変更 | ADR-0014「編集の操作面と上流workspaceへの変更」。起草: [#447](https://github.com/nunu1733/NunuLauncher/issues/447)。#442の結論が受入の前提。accepted後に `docs/adr/0014-edit-surface.md` を作成してここへlinkする | FR-019, #449 |
| D-015 | 新規アプリの配置先ポリシー | ADR-0015「新規アプリの配置先ポリシー」。起草: [#446](https://github.com/nunu1733/NunuLauncher/issues/446)。accepted後に `docs/adr/0015-new-app-destination-policy.md` を作成してここへlinkする | FR-008, #446 |
| D-016 | AI相談の凍結 | [#443](https://github.com/nunu1733/NunuLauncher/issues/443)の草案が正本 | FR-017, FR-022, #452 |

## Decision history

- 2026-08-21: [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85) selected Option B. FR-008 and FR-009 move from MVP to Later/deferred; the current package-event behavior remains fail-closed and produces no incremental proposal.
- 2026-09-19: [organizer-to-be-ux.md](./organizer-to-be-ux.md)（[Issue #361](https://github.com/nunu1733/NunuLauncher/issues/361)でaccepted）のD-01〜D-17を反映し、FR-006/FR-017のユーザー可視表現がOrganizer hub経由になる旨を追記した。新FR/NFRは起票しない: TO-BE決定は既存FRの達成経路・可視性・timingの改善であり、新たな観測可能要件となるのはD-08（取り込み済み提案のdurable化）とD-15（durable statusからの復元導線）由来のみで、それらは各実装Issueのspecが所有する（[Issue #365](https://github.com/nunu1733/NunuLauncher/issues/365)）。
- 2026-09-24: 再焦点化方針メモ（2026-09-24承認、Revision 5。[Epic #439](https://github.com/nunu1733/NunuLauncher/issues/439)、[Issue #440](https://github.com/nunu1733/NunuLauncher/issues/440)付録）に基づき、FR-008を「新規アプリの配置先ポリシー」として再定義してNow-1へ戻した（ADR-0015、D-015）。FR-009はLaterのまま（ADR-0005は「既存アイテムを動かす増分整理提案についての判断」として維持）。FR-017をFrozenへ変更した（D-016）。FR-018〜023、NFR-013〜014を新設し、D-013〜016をDecision gatesへ追加した。プロダクトの中心成果を「ホーム画面の日常的な編集と散らからない状態の維持にかかる手間の削減」へ移す（R-1/R-2）。2026-09-25の [PR #463](https://github.com/nunu1733/NunuLauncher/pull/463) で保守者が承認した確定事項: product-briefのVision/User problem文言とTarget usersの再優先づけ、FR-009の補足文言、状態語彙 `frozen` の追加、NFR-013のCategory名 `Responsiveness`、FR-008のstatus表記（`proposed（再定義）` + 旧#85履歴の併記）、D-013〜016の参照形式（各ADRのacceptedまでは起草Issueをlinkし、file pathは平文で併記）。

- 2026-09-26: [Issue #441](https://github.com/nunu1733/NunuLauncher/issues/441)（正本文書とfixtureは[PR #464](https://github.com/nunu1733/NunuLauncher/pull/464)で構築済み）の編集負担ベンチマークについて、保守者が人間の実測計測を対象外とする判断を記録した（[Issue #441コメント](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)。指標は手順コストの会計であり知覚負担の測定ではない）。baseline・目標を固定手順からの決定的算出として確定し、NFR-014をacceptedへ変更した。

## Traceability rule

要件を変更するIssueはIDをtitle/bodyへ記載する。spec frontmatterは関連IDを列挙し、PRはIssueとspecをlinkする。実装後、要件のstatusはIssueの受入、mainline merge、verification evidenceを根拠に更新する。organizer MVP要件（Foundation/MVP phase要件）別のowning Issue/spec/PR、primary evidence、limitation、blocking follow-upは [MVP release readiness](./mvp-release-readiness.md) にだけ記録する。再焦点化（2026-09-24）で再定義・新設した要件（FR-008再定義、FR-017、FR-018〜023、NFR-013〜014）のstatus・evidence・owning Issue/spec/PRは本書と各担当Issue/spec/PRを正本とし、[MVP release readiness](./mvp-release-readiness.md) のinventory対象外とする。

## 未解決事項

- **FR-008と旧[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)決定（Option B）の整合**: fail-closed維持と再定義の関係は、ADR-0015（[#446](https://github.com/nunu1733/NunuLauncher/issues/446)）の起草で明文化する。
- **D-013〜016のADR link**: 各ADRがacceptedになった時点で、Decision gatesの参照を起草Issueから `docs/adr/` のfile linkへ更新する（#445/#446/#447/#443のPRで行う）。
- **FR-022の#443との分担**: FR-022のowning Issueは[#452](https://github.com/nunu1733/NunuLauncher/issues/452)、「AI相談が無効の間は方法選択を経ない」部分の実装は[#443](https://github.com/nunu1733/NunuLauncher/issues/443)が担当する（両Issue本文が参照する）。
