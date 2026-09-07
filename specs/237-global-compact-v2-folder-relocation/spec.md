---
issue: "#237"
status: accepted
requirements:
  - FR-003
  - FR-016
  - NFR-003
  - NFR-004
  - NFR-009
  - NFR-010
  - NFR-011
  - NFR-012
updated: 2026-09-07
---

# Compact existing 1×1 folders across pages under a new GLOBAL_COMPACT_V2 strategy

> Status: accepted — 本specの受入により、`GLOBAL_COMPACT` intent の下で既存 1×1 top-level folder が前方pageの空きへ apps と同様に詰められる versioned successor strategy と、その versioning・選択・preview 契約が確定した。`GLOBAL_COMPACT_V1` の observable behavior は一切変更しない。

## Problem

「ページ間でコンパクト」(`GLOBAL_COMPACT_V1`) を 3 page の Home に適用すると、2 page には減るものの、1 page 目に明確な空き cell が残ったまま 2 page 目に多数の項目 (既存 folder が大半) が残る。ユーザーは name と description (`後ろのページのアプリを、前のページの空きスペースへ移動します。`) から「後方 page の top-level 要素を前方 page へ順次詰め、前方 page が埋まってから次 page を使う」結果を予想するが、現状は既存 folder が後方 page に固定されたまま compaction が停止し、見た目は歯抜けのままである。

原因は one-pass allocator の欠陥ではなく、spec 182 の受入済み内部 semantics である:

- `LayoutStrategyRegistry.GLOBAL_COMPACT_V1` の `eligibleUnitFilter` は 1×1 `APPLICATION`/`DEEP_SHORTCUT` のみであり、既存 folder unit は含まれない。
- `FullRunExecution.executeGlobalCompact` は filter 外の movable item を `STRATEGY_PRESERVED` として元位置に固定する (`GlobalCompactStrategyTest.existingFoldersNeverCompactCrossPage()` で normative として固定済み)。

spec 182 はこの制限を INV-8 (replan 冪等性) の heterogeneous-span counterexample から導いたが、同時に「A folder-compacting variant would be a new strategy ID requiring its own complete proof」と明示している。つまり本問題は受入済み strategy identity の無言変更では解決できず、versioned successor を導入して user-facing 契約と内部 semantics の不一致を解消する必要がある。

## Outcome

ユーザーが「compact across pages」intent の後継 strategy を選ぶと、移動可能な 1×1 top-level 要素 — apps、deep shortcuts、および既存 1×1 top-level folder を含む — が、前方 page から順に空き cell へ詰められる。後方 page に movable 1×1 top-level unit が残っているのに、それより前の page に配置可能な 1×1 空き cell が残る状態で compaction が完了しない、という歯抜け結果は regression test で防止される。既存 folder の identity・members・profile isolation は完全に保持され、preview は folder の cross-page move を正しく表示し、`GLOBAL_COMPACT_V1` は catalog から消えることなく旧挙動のまま共存する。

## Scope

- Versioned successor strategy `GLOBAL_COMPACT_V2` の導入: 既存 1×1 top-level `FOLDER` unit を movable 対象に加え、1×1 singleton と同一の global captured visual order / `CAPTURED_THEN_NEW` page scope / row-major first-fit で配置する。
- 既存 folder の移動時 invariant 保持: folder unit は span (`folderMaxColumns`/`folderMaxRows` を超えない 1×1) でのみ移動し、folder id・members・rank・profile・naming は不変。children は `STRUCTURAL` のまま folder 内に留まり、folder の跨ぎ移動で member の workspace 書き込みは発生しない。
- `GLOBAL_COMPACT_V2` を runtime-supported catalog へ追加し、ADR-0007 §8 に従い新 bundle semantic version (`organization-policy-v2.5`) / generation / digest を publish する。`rule-v2`・selection store schema は不変。
- Strategy picker UI・localization copy: `GLOBAL_COMPACT_V2` の name/description 追加。`GLOBAL_COMPACT_V1` の copy は、その挙動 (既存 folder は動かさない) を正直に表す内容へ更新する。
- Preview: cross-page move count / rows に folder relocation を含める (projection 種別の追加は不要)。
- 実機再現 fixture (前方 page に空き、後方 page が既存 folder 主体) の追加と、apply → recapture → replan の idempotence 検証。
- `CONTEXT.md` の domain language 追記、`DESIGN.md` への影響有無の確認、`requirements.md` traceability 更新。

## Non-goals

- `GLOBAL_COMPACT_V1` の observable behavior 変更 (既存選択・golden corpus・既存 test は不変)。
- Non-1×1 top-level unit (widget、2×1 app、1×2 app、app pair、multi-span folder) の cross-page compaction (#235 / heterogeneous-span packing policy)。
- Locked / reserved / unavailable / Dock / app-pair / legacy shortcut 要素の移動。
- New folder 形成対象への既存 folder members の追加 (folder 形成は movable 1×1 singleton candidate のみ)。
- Page record の物理削除。
- Folder 内部の item ordering 変更や folder content の書き換え。

## Domain language

`CONTEXT.md` への追記 (承認時):

**strategy-fixed unit (ストラテジー固定unit)**:
ある layout strategy が「本来は movable だが、その strategy の意図として動かさない」と決めた top-level unit。`PreserveReason.STRATEGY_PRESERVED` で報告され、 occupancy constraint として残る。`GLOBAL_COMPACT_V1` では既存 folder 全体と non-1×1 singleton、`GLOBAL_COMPACT_V2` では non-1×1 top-level unit が該当する。
_Avoid_: 保存済み (naturally preserved との混同)、スキップ対象

## Versioning decision

Issue #237 が挙げた 2 選択肢のうち **選択肢 2 (versioned successor)** を採用する。理由:

1. spec 182 は `GLOBAL_COMPACT_V1` の「existing folders fixed」を受入済み normative behavior として実装・テスト済みであり (child 6 / PR #224)、AC-9b の golden corpus もこの挙動で固定されている。無言変更は ADR-0012 の「a behavior change is a new ID; renaming a shipped ID is forbidden」に直接抵触する。
2. spec 182 §Idempotence arguments 自体が「A folder-compacting variant would be a new strategy ID requiring its own complete proof」を明示しており、defect としての修正根拠が存在しない。実機観察は user-facing copy との期待不一致であって、V1 の実装が spec に反していないため「V1 observable behavior が user-facing contract に反していた defect」は成立しない。
3. 期待された結果 (folder も詰める) を得たいユーザーへの正しい提供方法は、その振る舞いを持つ新 ID と、V1 を指す copy の明確化である。ユーザーは picker で明示的に選択するため、旧挙動に依存するユーザーは選択を変えずに済む。

Versioning 機械的帰結 (ADR-0007 §8 / ADR-0012):

- `LayoutStrategyRegistry` に `GLOBAL_COMPACT_V2` を登録し、`BuiltInOrganizerPolicyBundleSource` の `runtimeSupported` に追加する。
- Bundle identity は `organization-policy-v2.4` → `organization-policy-v2.5` へ上がり、digest は再計算される。`RuleVersion` は `v2` のまま、selection store schema も不変。
- persisted selection が `GLOBAL_COMPACT_V1` の場合は引き続き有効 (V1 は catalog に残るため fail-closed にならない)。
- V1 を選択していたユーザーは挙動変化を経験しない。期待に合わないユーザーは自発的に V2 を選ぶ。

## Observable strategy behavior — GLOBAL_COMPACT_V2 normative rules

`GLOBAL_COMPACT_V1` の normative rules (spec 182) に対し、以下を除いて同一である:

- **Eligible movable units**: 1×1 `APPLICATION` / `DEEP_SHORTCUT` singleton に加え、**1×1 top-level `FOLDER` unit** (unlocked、`AVAILABLE`、workspace 配置、span 1×1)。non-1×1 top-level unit (widget、2×1/1×2 app、app pair、multi-span folder) は `STRATEGY_PRESERVED` 固定 unit のまま。
- **既存 folder の扱い**: folder unit は 1×1 である限り singleton と同一の unit stream に参加し、global captured visual order `(PageOrder, PageId, cell.y, cell.x, ItemId)` で順序付けられ、`allocateCapturedThenNew` で配置される。captured state が 1×1 以外の span を持つ folder は eligible にならず、`STRATEGY_PRESERVED` 固定 unit のままである。
- **Folder invariant**: folder を移動しても folder id・naming・profile・members・rank は変化しない。folder members の placement は `Preserved{STRUCTURAL}` 行として現存どおり出力に現れ、folder の移動によって内容が変わらない (members は既に folder 内にあり、planner は workspace item として扱わない)。folder 移動が member 行を書き換えることはない。
- **Folder formation**: canonical P-04/P-05 grouping は movable 1×1 **singleton** candidate のみを対象とする。既存 folder は formation candidate にならず、既存 folder と new folder が併存する。new folder は singleton stream の後、`(preferred page key, NewFolderOrdinal)` で配置される (V1 と同じ)。
- **1×1 制限の根拠**: spec 182 の heterogeneous-span INV-8 counterexample は「movable non-1×1 unit が compaction に参加した場合」の反例であり、本 strategy は引き続き non-1×1 を compaction 対象としないため、この反例は成立しない。
- **Replan 冪等性の証明 (folder formation を含む状態遷移の不動点)**: V2 では材料化された new folder も次回 replan で通常の movable 1×1 folder として mover stream に再参加する。したがって V1 の「formed folder は replan で fixed set に加わる」argument は eligibility 定義と矛盾するため使えず、formation を含む状態遷移ごと不動点を示す (owner review on `b613bfb42f` の P1 指摘)。証明は次の 4 段構成とする:
  1. **fixed set の不変性**: naturally preserved item と `STRATEGY_PRESERVED` unit (non-1×1 top-level unit) は replan 間で位置も理由も変わらない。したがって「全 cell から fixed 占有を除いた、page 順 → row-major 順の空き cell リスト」(new page が作られた場合は作成順の PageOrder を続けて含む) は run 間で同一である。
  2. **消費順の単調性**: run 1 では unit stream が global captured visual order で、new folder がその後 `(preferred page key, NewFolderOrdinal)` 順で、それぞれ `allocateCapturedThenNew` の first-fit により「その時点で最小の空き cell」を消費する。消費 cell 列は消費順について狭義単調増加なので、材料化後の captured visual order (位置整列) は消費順を復元する。材料化後の layout は非重複であるため ItemId tie-break は使われない。
  3. **formation の replan 安定性**: grouping は per-(profile, category) の候補群に独立に処理される。group 化から漏れた残余候補群は、(a) minGroupSize 未満、(b) fallback category (常に group 対象外)、(c) capacity < minGroupSize (formation 全体が無効) のいずれかであり、残余 alone を candidate として再実行しても新規 folder を形成しない。replan 時の top-level singleton candidate 集合はこの残余に一致する (run 1 の member は folder 内 `STRUCTURAL` となり candidate から外れる)。分類・taxonomy は同一 capture から決定論的に再現されるため、replan で新規 folder は形成されない。
  4. **帰結**: replan の mover 集合は「singleton ∪ 既存 folder」から「singleton 残余 ∪ 形成済み folder」へ構成・個数とも変化するが、その captured visual order は (2) より run 1 の消費順そのものであり、空き cell リストも (1) より同一である。first-fit は各 unit を自分の captured cell へ回収し、全 unit が `Preserved{ALREADY_CANONICAL}`、新規 folder・新規 page なし、diff は空である。formation による unit 数の縮約は run 1 の allocation 内で既に反映済みであり、replan で追加の前詰めは発生しない。
- 形成済み folder を次回以降 fixed とする永続的な provenance は導入しない (review 選択肢 (a) を不採用 — 永続 state の設計負荷に対し、上記の証明で不動点が成立するため)。代わりに、形成済み folder が replan で `Preserved{ALREADY_CANONICAL}` として自 cell を回収すること (`STRATEGY_PRESERVED` 固定ではないこと) を、formation を含む fixture で直接 pin する。formation replan 安定性は property suite の全 fixture replan assertion によっても広域に検証される。

### Failure behavior

| Condition | Observable outcome |
|---|---|
| `GLOBAL_COMPACT_V2` が movable 1×1 unit を配置できない (planner invariant 違反) | 既存の typed impossibility semantics (spec 12 P-11): V-21/V-22 のみが candidate を impossible にでき、silent drop は禁止。loud failure は typed non-apply result として表面化する。 |
| 選択 store が破損 / newer schema | 既存の fail-closed path (composer `NotReady`)。V2 導入による変更なし。 |
| persisted selection が V1 のまま | 変化なし。V1 は引き続き runtime-supported であるため fail-closed にならない。 |
| V2 選択後、APK downgrade で V2 を知らない bundle へ戻る | Selection-layer `NotReady` (typed non-write)。V2 が旧 bundle の runtime-supported set に無いため。re-upgrade で再検証される。 |

## Behavior scenarios

### Scenario: existing 1×1 folder joins cross-page compaction

Given 前方 page に movable item を 1 つだけ持ち空き cell が残る 1 page、後方 page に既存 1×1 folder が配置された snapshot
When full run が `GLOBAL_COMPACT_V2` で実行される
Then 既存 folder は前方 page の空き cell へ `Moved{FOLDER_UNIT}` として配置される
And folder id、`NewFolderRef` 等価物の有無、members、rank、naming、profile は不変である
And 前方 page に配置可能な空き 1×1 cell が残ったまま後方 page に movable 1×1 top-level unit が残る状態は発生しない
And replanning the materialized result yields an empty diff

### Scenario: 2026-09-06 実機相当 fixture — 後方 page が既存 folder 中心

Given 3 page 構成で page 1 に少ない item と空き cell、page 2/3 に既存 1×1 folder が多数ある fixture
When full run が `GLOBAL_COMPACT_V2` で実行される
Then 前方 page が先に埋まり、後方 page から folder が順次前詰めされる
And preview の cross-page move count が folder relocation を含めて正しい
And no captured page record is deleted
And replanning the materialized result yields an empty diff

### Scenario: non-1×1 units stay strategy-preserved under V2

Given 後方 page に movable 1×2 app と 2×1 app、前方 page に空き cell がある fixture
When full run が `GLOBAL_COMPACT_V2` で実行される
Then 両 non-1×1 unit は `Preserved{STRATEGY_PRESERVED}` で元位置に残る
And 1×1 unit のみが compaction に参加し、spec 182 counterexample の INV-8 violation は発生しない
And replanning the materialized result yields an empty diff

### Scenario: existing folder identity and members are untouched

Given 既存 1×1 folder が前方 page へ移動する fixture (members 2+、profile 分離あり)
When full run が `GLOBAL_COMPACT_V2` で実行される
Then folder members は workspace placement に書き出されず、member 行は `Preserved{STRUCTURAL}` として capture と同一の placement を保つ
And folder id、naming、profile、member list、member rank は capture と同一である
And profile isolation が保持される (folder が異なる profile の cell へ跨ぐことはない)

### Scenario: formed folder rejoins the movable stream and the layout is a fixed point

Given 同一 profile・同一 category の movable 1×1 singleton が minGroupSize 以上あり、`GLOBAL_COMPACT_V2` の run 1 で new folder が実際に形成される fixture
When run 1 の plan を適用し、材料化された layout を recapture して同じ `GLOBAL_COMPACT_V2` で replan する
Then replan の plan に `Moved` placement が 1 つもなく、新規 folder も新規 page も作られない
And replan における形成済み folder の disposition は `Preserved{ALREADY_CANONICAL}` であり、`STRATEGY_PRESERVED` 固定ではない (V2 では通常の movable 1×1 folder として自 cell を回収する)
And この状態遷移 (formation → apply → recapture → replan → 空 diff) を unit test で直接固定する

### Scenario: V1 remains byte-identical and selectable

Given `GLOBAL_COMPACT_V1` を選択した persisted selection
When full run が実行される
Then plan 出力は spec 182 受入時と同一である (既存 test / golden corpus が無修正で通る)
And bundle は V1 と V2 の両方を runtime-supported として宣言する
And selection は fail-closed にならない

### Scenario: preview reflects folder relocation

Given 既存 folder を含む 3 page fixture
When preview が `GLOBAL_COMPACT_V2` で生成される
Then cross-page move count に既存 folder の cross-page move が含まれる
And folder unit の行が `Moved` として表示され、member 行は `STRUCTURAL` として現存どおり表示される
And preview が V1 選択時と V2 選択時で異なる結果を示す

### Scenario: strategy picker offers both and copy is truthful

Given strategy picker が開かれた状態
When runtime-supported catalog が表示される
Then `GLOBAL_COMPACT_V1` と `GLOBAL_COMPACT_V2` が両方選択可能として並ぶ
And V1 の description は「既存 folder は動かさない」挙動を正直に表現する (期待不一致を再生しない)
And V2 の description は「後方の app と folder を前の page の空きスペースへ移動する」意図を表す

### Scenario: downgrade fails closed on V2 selection

Given persisted selection が `GLOBAL_COMPACT_V2` で、APK が V2 を知らない bundle の binary へ downgrade された
When composition runs
Then selection-layer `NotReady` (typed non-write) で organizer は unavailable になり、store は書き換えられない
And re-upgrade 後、selection は再検証されて再開する

## Data and state

- 新規永続化は selection store の新 record 追加のみ (schema v1 のまま、`GLOBAL_COMPACT_V2` という新 StrategyId 値)。selection store は backup/restore 対象外 (V1 導入時と同じ)。
- Launcher DB schema 変更なし。recovery point、checkpoint、apply write-set は不変。既存 folder の移動は workspace cell の更新として既存 writer 経路で書かれ、favorites 内 folder id / `container` 参照 / folder members の `favorites` 行は変化しない (folder 移動は folder 行の `cellX/cellY/screen` 更新のみ)。
- Bundle は `organization-policy-v2.5` として新 digest で publish (ADR-0007 §8: in-place migration なし、各 binary は自身の bundle を読む)。`rule-v2`、taxonomy/classification/target version、selection-store schema は不変。
- Migration surface: persisted selection が V1 の場合はそのまま有効。V2 は新規選択としてのみ現れる。

## Permissions, privacy, and security

None。新 permission、network、telemetry は追加しない。strategy identity は既存 version-identifier allowance の下で diagnostics に現れる (spec 182 と同じ)。layout data は既存の recovery contract で保護され、strategy ごとの例外は発生しない。

## Accessibility and localization

- `GLOBAL_COMPACT_V2` の name/description は localized string (`values/` + `values-ja/`) として追加する。
- Strategy picker、preview、confirmation UI は spec 52 (MFO-15) / spec 195 / spec 182 の TalkBack・Switch Access・200% font scaling 期待値を継承する。
- Preview の cross-page move count は text でannounce され、color-only ではない。

## Acceptance criteria

- [ ] AC-1: accepted spec が既存 1×1 folder の `GLOBAL_COMPACT` page movement semantics と versioning を決定する (本spec受入)。
- [ ] AC-2: 後方 page に movable existing 1×1 folder があり、前方 page に安全な空き 1×1 cell がある fixture で、前方 page が先に埋まる。`GLOBAL_COMPACT_V2` の plan がその結果を produce する regression test が public seam 経由で存在する。
- [ ] AC-3: apps / deep shortcuts / existing folders を含め、前方に配置可能な空きがあるのに後方へ movable 1×1 top-level unit が残る状態を regression test で防ぐ。
- [ ] AC-4: folder identity、members、profile isolation、locks、reservations、application/recovery safety が保持される。folder 移動は既存 writer 経路で favorites の folder row cell/screen のみを更新し、member 行や container 参照を変えない。
- [ ] AC-5: deterministic ordering と replan idempotence (材料化後 replan で空 diff) が `GLOBAL_COMPACT_V2` で維持される。「new folder 形成 → 適用 → recapture → 同 strategy replan → 空 diff」の状態遷移を fixture で直接固定し、形成済み folder が `Preserved{ALREADY_CANONICAL}` で自 cell を回収すること (`STRATEGY_PRESERVED` 固定ではないこと) を assert する。加えて property test / determinism suite を public seam で通す。
- [ ] AC-6: preview の cross-page move count / rows が folder relocation を正しく反映する。
- [ ] AC-7: `GLOBAL_COMPACT_V1` の plan 出力が spec 182 受入時と byte-equivalent であることを golden corpus で確認する (無修正 pass)。
- [ ] AC-8: bundle coherence 契約 test (`runtimeSupported ⊆ implemented`、`default ∈ runtimeSupported`、等価) が V2 追加後も通る。bundle identity は `organization-policy-v2.5` として publish される。
- [ ] AC-9: strategy picker が V1/V2 両方を runtime-supported set から提示し、localized copy が両 strategy の実際の挙動を正直に表す。V1 copy の更新を含む。
- [ ] AC-10: 2026-09-06 実機ケースに相当する fixture で before/preview/after を再評価し、「ページ間でコンパクト」という名称から予測できる結果になっていることを確認する。実機 evidence (before/preview/after screenshot) を Issue または PR に記録する。
- [ ] AC-11: downgrade / fail-closed path (V2 selection が旧 binary で `NotReady`) が test される。
- [ ] AC-12: 高リスク PR は `risk: layout-data` label と成功 `final-status` CI と独立 audit 記録 (docs/assessment/) を備える。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 本specの受入記録 |
| AC-2 | `GlobalCompactStrategyTest` (V2) に実機相当 fixture を追加、public seam `OrganizationPlanner.plan` |
| AC-3 | 同上 regression test + spec 11 harness / property suite |
| AC-4 | folder identity / member / profile isolation の assertion を含む unit test + apply module の test DB test |
| AC-5 | `GlobalCompactStrategyTest` (V2) の formation → apply → recapture → replan fixture + spec 11 determinism / idempotence property suite (V2 を catalog 対象に追加) |
| AC-6 | preview projection の unit test + confirmation change list test |
| AC-7 | golden corpus (pre-#182 pin + V1 出力 pin) の無修正 pass |
| AC-8 | bundle coherence contract test (`runtimeSupported == implemented` equality) |
| AC-9 | `StrategyPickerInstrumentationTest` 拡張 + strings assertion |
| AC-10 | 実機 evidence (before/preview/after) を PR に記録、fixture との対応を確認 |
| AC-11 | selection store / downgrade test (V2 → 旧 bundle で NotReady) |
| AC-12 | `high-risk-gate` workflow + `docs/assessment/pr-<n>-*.md` |

## Open questions

- なし (実装開始前に解消済み: versioning は選択肢 2 で確定、V1 は共存、eligible 対象は 1×1 top-level folder のみ)。

## Change history

- 2026-09-07: Draft created for #237. Issue の観察 (既存 folder が後方 page に固定され前方 page に空きが残る) を spec 182 の受入済み semantics と突き合わせ、versioned successor (`GLOBAL_COMPACT_V2`) 導入を選択肢 2 として決定した draft。
- 2026-09-07: Review revision (owner review on `b613bfb42f`): idempotence proof が V1 の「formed folder は replan で fixed set に加わる」argument を流用しており、V2 の eligibility 定義 (材料化された folder も movable) と矛盾していた — proof を「fixed set 不変 ⇒ 空き cell リスト同一」「消費順の単調性 ⇒ 材料化後 captured visual order が消費順を復元」「formation の replan 安定性 ⇒ replan で新規 folder なし」の 4 段構成の状態遷移不動点として書き直した — Blocking。形成済み folder を fixed 化する永続 provenance は導入せず、形成済み folder が `Preserved{ALREADY_CANONICAL}` で自 cell を回収することを fixture で pin する方針を明記 — Blocking。AC-5 と test oracle に「new folder 形成 → 適用 → recapture → replan → 空 diff」の状態遷移 test を明示 — Medium。
- 2026-09-07: Correction (implementation prep): moved existing folder の placement code を `SINGLE_PLACEMENT` から `FOLDER_UNIT` へ訂正した。UI と preview は code ごとに別 wording (`manual_organization_moved_folder_unit` / `moveReasonFolderUnit`) を持ち、canonical flow の既存 folder 移動も `FOLDER_UNIT` であるため、folder 移動を app singleton と同 code にすると change list の文言が不誠実になるため。behavior 変更ではなく code 選択の訂正である。
- 2026-09-07: Accepted by the Issue #237 owner。実装は本specと plan.md に従い、単一 PR で spec 受入条件と対応付ける。
