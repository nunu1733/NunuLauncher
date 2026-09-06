---
issue: "#228"
status: draft
requirements: []
risk: [layout-data]
updated: 2026-09-06
---

# Organizer がホーム未配置アプリをユーザー選択で整理対象に追加できる

## Problem

別端末・新規端末・別 launcher から Lawnchair/NunuLauncher へ切り替えた直後、ホームレイアウトはほぼ空であることが多い。Nova を使っていない端末では Nova import ([spec 168](../168-nova-restore-authoritative/spec.md)) も入力源にならず、Organizer を試す前にユーザーが App Drawer から多数のアプリを手作業でホームへ追加する必要がある。これは migration 不足ではなく、**fresh workspace から Organizer を開始できない onboarding gap** である。

一方、インストール済みアプリを無条件ですべてホームへ追加するのは適切でない。launchable app であってもホームに置きたくないアプリは普通に存在するため、追加対象はユーザーが明示的に選べる必要がある。また、proposal 上で「新規作成」と「既存 placement の移動」が同じ `Move` として表現されると、transactional / review の意味が曖昧になる。

## Outcome

Organizer が、現在ホームに存在しない launchable installed apps を候補として検出し、ユーザーが multi-select で追加対象を選択したうえで、既存 placement と合わせて organize proposal を生成・preview・confirm・適用できる。新規追加は proposal の中で `Add` として `Move` と区別して表示され、適用は既存の transactional / stale check / recovery 契約と同等の保証を持つ。

## Scope

- **未配置アプリ検出**: 現在のホームレイアウト capture と installed/launchable apps の在庫から、「ホームに表現されていないアプリ」を安定した app/profile identity で計算する純粋な検出 step。
- **選択 UI**: app icon + label、選択件数、検索/フィルタ、Select all、Clear all を持つ multi-select 画面。初期選択は全件未選択。
- **入力構成**: 選択結果を `TargetSet.additions` として planner 入力へ合成する pre-planning scope/input composition step ([spec 83](../83-production-organization-input-sources/spec.md) の構成 seam 上)。
- **planner 契約変更**: full organization における additions の許可 (V-18 の条件化) と、配置された追加候補の `Add` disposition の導入 ([spec 10](../10-pure-organization-planning/spec.md) の revision)。
- **proposal 変更**: preview / confirmation の変更一覧に `Add` group を追加 ([spec 194](../194-plan-preview-seam/spec.md) / [spec 195](../195-organizer-confirmation-change-list/spec.md) の revision)。Add 行は #208 の placement 識別要件と互換する表現を持つ。
- **適用と復旧**: 既存の `Insert` / recovery point / transactional apply / post-apply verification 経路への candidate materialization の接続。
- **fresh workspace 対応**: 既存 placement が 0 件または極端に少ない入力を明示的に supported とする。
- strings は `values/` と `values-ja/` の両方へ追加する (#123 契約)。

## Non-goals

- インストール済みアプリの全件自動追加。
- Nova import / backup restore の代替または置き換え。
- Play Store category だけからのホーム適性推論。
- usage access permission の要求、および [#203](https://github.com/nunu1733/NunuLauncher/issues/203) への依存 (first delivery は #203 なしで決定的に動作する)。
- AI/LLM によるアプリ選択。
- preview / confirmation の bypass。
- アプリ install 時の背景自動ホーム追加 ([ADR-0005](../../docs/adr/0005-fresh-install-presence-evidence.md)、[#85](https://github.com/nunu1733/NunuLauncher/issues/85) Option B の維持)。
- 既存 placement を今回の整理スコープから除外する双方向 scope 編集 (将来拡張。data model / UI で妨げない留めだけを入れる)。
- 選択状態の永続化 (first delivery は process-local。永続化は別 Issue で起票する)。
- onboarding proposal ([spec 53](../53-onboarding-organization-proposal/spec.md)) flow 自体の変更。

## Domain language

承認時に `CONTEXT.md` へ反映する。

- **未配置アプリ (Apps not on Home)**:
  現在のホームレイアウトに表現されていない、起動可能な installed app。identity は package + profile であり、表示 label ではない。
  _Avoid_: インストール一覧インポート (実装中心の語)、未使用アプリ (使用状況を含意する)
- **スコープ選択 (Scope Selection)**:
  未配置アプリから今回の整理対象への追加分をユーザーが明示的に選ぶ、planning 前の入力構成 step。選択されなかった候補は対象外であり、create mutation を生成しない。
  _Avoid_: 自動追加、候補の黙認取込み

## Design decisions

### D1: 検出 identity — **(package name, profile) 単位の一致**

- 候補の identity は `(package name, profile)` とする。launcher activity component は alias 切替や update で変化しうるため identity に使わない (component は代表 activity の決定的選択にのみ使う)。
- 既存 placement による「表現」の定義: capture 済み item のうち、target が `AppKey` / `ShortcutKey` / `LegacyShortcutKey` であり、その target の (package, profile) が一致するものはすべて表現とみなす。直接配置と folder member の両方を含み、同一 app が複数 placement に現れても 1 回の表現として扱う (重複排除)。app pair member も含む。
- `WidgetKey` の placement は app 起動 target ではないため表現とみなさない (widget だけがあるアプリは「未配置」として候補に現れ、ユーザーが選べる)。
- 1 候補 = 1 (package, profile)。同一 package に複数 launcher activity がある場合の代表は決定的規則 (component 文字列の collation 順の先頭) で選ぶ。
- 表示 label は identity にも一致判定にも使わない。

### D2: 候補在庫 (inventory) — **新 authoritative source port、launchable activity 限定**

- 新 port `InstalledAppInventorySource` を [spec 83](../83-production-organization-input-sources/spec.md) / [ADR-0007](../../docs/adr/0007-authoritative-organization-policy-sources.md) の source ownership model に従って追加する。production adapter は既存の category override authoring と同じ `LauncherApps.getActivityList(null, user)` 前例 ([CategoryOverrideAuthoring.kt](../../lawnchair/src/app/lawnchair/organizer/ui/CategoryOverrideAuthoring.kt)) に従う。
- 候補に含めるのは、通常のユーザー起動対象として launcher に露出される main activity を持つ app のみとする。deep-link-only / 非起動可能 package、launcher/system/internal activity は候補から除外する。
- availability: disabled / suspended / unavailable な app、locked / quiet な profile は候補から除外する (planner 契約の V-22 unavailable candidate を `Impossible` にするのではなく、入力側で生成しない)。
- 候補種別は `CandidateKind.APPLICATION` に限定し、span は 1×1 とする (deep shortcut 候補は将来拡張)。
- 排除は typed に行われ、検出は snapshot の capture revision に対して行われる。

### D3: 選択 semantics — **unchecked-by-default、明示的 multi-select、process-local**

- 初期状態は全候補未選択。installed/launchable であることだけを理由にした黙認の取込みは存在しない。
- 選択 UI は最低限: app icon、app label、checkbox (等価な multi-select affordance)、選択件数、検索/フィルタ (label の case-insensitive 部分一致)、Select all、Clear all を持つ。Select all は現在の検索条件で表示中の候補すべてに適用し、Clear all は全件の選択を解除する。
- 選択状態は process-local であり、serialize / 永続化しない。cancel で破棄される。
- 選択は使用状況や recommendation に依存しない決定的な候補順序 (profile role → label → component) で提示する。#203 の signal が将来使われる場合も、recommendation は黙認の自動追加ではなく常に user-reviewable である ([#203](https://github.com/nunu1733/NunuLauncher/issues/203) の契約)。

### D4: run 統合 — **明示的な選択 variant で coordinator 状態機械に 1 phase を追加**

- `ManualOrganizationRun` に選択 phase を導入する: 選択 variant の trigger で run を開始すると、capture 後に検出を行い `State.SelectingApps(candidates)` で停止する。既定の manual full organization run (`MANUAL_FULL`) の flow は一切変えない (additions は空で capture → planning へ直行)。
- 選択画面には「選択せずに整理する」(空選択で planning へ進む) と cancel を用意する。cancel は書込みなしで既存の cancel 契約へ従う。候補 0 件の場合は選択画面に説明文言を表示する。
- 検出・選択・planning・preview は書込みを一切行わない (既存の planner non-I/O 契約と read-only preview seam 契約の消費)。
- `Stale` 後の再 capture 時は検出をやり直し、直前の選択のうち依然未配置のものは選択済みとして保持し、新たに未配置になったものは未選択、既に表現されたものは選択から除去する。除去・保持は選択画面で観測可能である。

### D5: planner 契約変更 — **V-18 の条件化と `Disposition.Added` の導入 ([spec 10] revision)**

- `ADDITIONS_UNDER_FULL_ORGANIZATION` (V-18) は廃止ではなく条件化する: full organization は、target policy `full-target-v2` のもとで「明示的にユーザー選択された candidate 集合」を additions として受け入れる。選択に由来しない additions、および選択と provenance を伴わない additions は引き続き `Invalid` とする。
- 配置された追加候補の disposition は既存の `Moved` を流用せず、`Disposition.Added{rationale: PlacementCode}` を新設する。captured item が `Added` になることはなく、追加候補が `Moved` になることもない。追加候補の legal target は既存の candidate 対応表 (Workspace または folder member、Dock / app pair 不可) を継承する。
- Conservation 不変条件は拡張しない: additions は入力 placement item ではないため、入力の全 captured item が保持・移動・明示的削除のいずれかであるという契約は不変のままである。
- Idempotence (INV-8) はこの機能でも要求される: 適用後の追加 app は captured item になり、同じ条件で再実行すると差分 0 の `Planned` (既存 layout が canonical なら `NoChanges`) を返す。

### D6: target policy version — **`full-target-v1` → `full-target-v2`、provenance の拡張**

- `FullTargetSetMaterializer` の policy version を `full-target-v2` へ上げる: additions は「ユーザー選択された candidate 集合」であり、v2 では空も許容される (classic run との互換)。v1 は受け付けない。
- `OrganizationInputComposer` は選択結果を受け取り、provenance に installed-app inventory source の identity と選択の所在を記録する ([spec 83](../83-production-organization-input-sources/spec.md) の `InputProvenance` / [ADR-0007](../../docs/adr/0007-authoritative-organization-policy-sources.md) の source ownership)。policy bundle digest の意味論は変更しない。
- inventory source の readiness failure は typed な `InputReadinessReason` (新値) として既存の `InputUnavailable` 表示へ流れる。在庫取得失敗は classic run の readiness に影響しない。

### D7: Add の表現 — **transaction 層は既存 `Insert`、review 層は新 `AddChange`、content 解決は application 側の単一 resolver**

- transaction / apply 層は既存の `ApplyAction.Insert` + `ApplicationItemRef.PlannedCandidate` ([LayoutState.kt](../../lawnchair/src/app/lawnchair/organizer/application/public/LayoutState.kt)) を使用する。favorites 行 insert、revision 再確認、expected-absence precondition、transaction、refresh は既存 writer 契約をそのまま使う。`Move` を作成の表現に流用しない。
- `OrganizationPlanMaterializer` を拡張し、`PlannedCandidate` 参照に対して完全な intended state (title / intent / icon / placement / structure) を持つ `Insert` action を発行する。content (label / icon / intent) は run 内に保持された inventory 由来の値であり、materialize 時の単一 resolver port ([spec 201](../201-generated-folder-semantic-naming/spec.md) の `FolderTitleResolver` 前例) で解決する。label が解決できない (空) 場合は fail-closed で `Invalid` とする。
- preview / review 層: [spec 194](../194-plan-preview-seam/spec.md) の closed union `PreviewChange` に `AddChange` を追加し、`PreviewCounts` に `addedCount` を追加する。AddChange は label (`PreviewLabel`) と配置語 (`PreviewPosition`) を持ち、生 package / `ItemId` / cell 座標は表示へ露出しない (spec 194 privacy 契約の継承)。label は canonical capture title ではなく inventory 由来であることを spec 194 側へ明記する。
- proposal UI ([spec 195](../195-organizer-confirmation-change-list/spec.md)) は新規配置 (Add) group を追加する。group 順序は 移動 → 新規配置 (Add) → 新規フォルダ → 新規ページ → 保持 → 警告 とし、件数 truth は `PreviewCounts.addedCount` とする。Add 行は page 序数 + 領域語 + (フォルダ配置時は) フォルダの識別を持ち、#208 (同名 placement の識別可能性) の要件と互換する。#208 の識別設計が確定した場合は Add 行も同一の設計へ追従する。

### D8: fresh workspace — **0 件の placement は first-class な入力**

- 既存 placement が 0 件の capture は有効な入力であり、proposal は (reserved workspace regions を除き) 全件 `Add` となる。QSB 等 reserved region の扱いは既存契約 ([spec 155](../155-qsb-reservation-reload/spec.md)) に従う。
- 新規 page は uncapped であるため (planner 契約 P-11)、fit しない選択候補は構造的に発生しない。`Impossible` になった場合 (例: 候補 target の妥当性違反) は既存 `PlanningRejected` 表示で fail-closed に終了する。

### D9: diagnostics と privacy — **app 単位の情報を journal へ出さない**

- organizer diagnostics ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)) は、選択 phase の遷移と選択件数・target policy version などの非識別集計のみを記録してよい。候補一覧、app label、package、選択内容の app 単位の記録は行わない。
- 選択一覧の label / icon は app-private な process 内のみで使用し、network / export / journal へ出力しない。

## Behavior scenarios

### Scenario: Detection excludes apps represented anywhere on Home

Given 同一 app が直接アイコンと folder member の両方で配置され、別 app は widget としてのみ配置され、さらに work profile の同 app が存在する,

When 選択 variant の run で検出を行う,

Then 直接配置と folder member のいずれであっても表現とみなされ候補から除外され、widget のみの app は候補に現れ、work profile の variant は personal と別候補として現れる,

And 候補の順序は決定的であり、label は一致判定に使われていない。

### Scenario: Unavailable and non-launchable apps never become candidates

Given disabled / suspended / unavailable な app、launcher role のない system-only package、locked / quiet な profile 内の app が存在する,

When 検出を行う,

Then いずれも候補に現れず、V-22 (unavailable candidate) を planner へ到達させない,

And 排除は入力側で typed に行われる。

### Scenario: Selection UI provides conservative multi-select

Given 検出が N 件の候補を返した,

When 選択画面を開く,

Then 全候補が未選択で初期化され、icon / label / checkbox / 選択件数 / 検索 / Select all / Clear all が機能し、検索中の Select all は表示中の候補のみを選択する,

And 選択を一切行わずに「選択せずに整理する」で planning へ進める。

### Scenario: Unselected candidates never produce workspace mutations

Given 候補 10 件のうち 3 件のみを選択して planning した,

When proposal が生成される,

Then proposal に現れる Add 行は選択した 3 件と正確に一致し、未選択の 7 件に対応する create (`Insert`) action が plan / preview のどこにも存在しない,

And 検出・選択・preview の各時点で workspace への書込みは 0 件である。

### Scenario: Proposal distinguishes Add from Move and Preserve

Given 既存 placement が Move / Preserve になり、選択された候補が配置される入力,

When 確認画面を描画する,

Then Add 行は「新規配置」group に分離して表示され、`PreviewCounts.addedCount` が件数 truth であり、Add 行には配置語 (page 序数 + 領域語、フォルダ配置時はフォルダ識別) が付く,

And Move / Preserve 行の既存表現は退化しない。

### Scenario: Fresh workspace supports an all-Add proposal

Given 既存 app placement が 0 件の capture と、選択された候補,

When planning と preview を行う,

Then proposal は全配置対象を Add として表現し、reserved workspace region を侵害せず、既存 placement に対する Move / Preserve 行は存在しない。

### Scenario: Cancel and selection state lifetime

Given 選択状態で 5 件を選択している,

When ユーザーが cancel する、または process が再生成される,

Then workspace への書込みは 0 件のままであり、選択状態は保持されず、次回起動時は再検出から始まる。

### Scenario: Stale layout re-runs detection with reviewable retention

Given 選択確定後に layout revision が変化し `Stale` となった,

When 再 capture して検出をやり直す,

Then 直前の選択のうち依然未配置のものは選択済みで保持され、新たに未配置になったものは未選択、既に表現されたものは選択から除去されており、その結果が選択画面で観測可能である。

### Scenario: Apply is transactional and recoverable

Given Add を含む proposal が confirm された,

When apply が write の途中で失敗する (failure injection)、

Then transaction が rollback し、部分的に作成されたホーム item が残存しない,

And 成功した apply の recovery point への復元は、追加された favorites 行を削除して既存 placement を復元する。

### Scenario: Idempotence after apply

Given Add を含む apply が成功し、検証を通過した,

When 同じ条件で再度 full organization を実行する,

Then 追加 app は captured item として扱われ、追加差分は 0 であり、既存 layout が canonical なら `NoChanges` となる。

### Scenario: Inventory source failure is typed and does not affect the classic run

Given installed-app inventory source が読めない,

When 選択 variant を開始する、または classic run を開始する,

Then 選択 variant は typed な readiness failure (`InputUnavailable`) で終了し、classic run は現行どおり Ready で進行する,

And diagnostic は非識別集計のみを含む。

### Scenario: Accessibility of the selection UI

Given 選択画面が表示されている,

When TalkBack / keyboard / Switch Access / 200% font scale で操作する,

Then 各候補行は checkbox の選択状態を semantics で報告する単一の意味ある node であり、選択件数の変化が status として伝わり、検索 field・Select all・Clear all・確定・cancel へ traversal で到達でき、必須 content が 200% で wrap して到達可能である。

## Data and state

- 読む data: launcher DB の canonical capture (既存 `captureCurrent` 経由) と、installed-app inventory (新 port、`LauncherApps` 由来)。capture revision と inventory は同一 run 内で対応付けられる。
- 永続化する data: **なし (新規)**。選択状態は process-local。適用結果の favorites 行は既存 writer が書く通常の layout 行であり、backup / restore は通常の layout item として扱われる。schema migration、rule migration、DB schema 変更はない。
- layout を扱う場合の対象集合: 既存 captured item は全件 Movable / Preserved へ厳密分割され (V-17)、追加候補のみが additions に入る。captured item の欠落・重複は既存 validation で fail-closed。
- recovery: 既存 recovery point 契約 ([ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md)、[spec 13](../13-safe-layout-application/spec.md)) を使用し、insert の反転は既存 `RecoveryAction.DeleteRow` 経路で行う。

## Permissions, privacy, and security

- 新規 permission なし。`QUERY_ALL_PACKAGES` は baseline manifest に既存であり、`LauncherApps` の activity 一覧取得に追加の runtime permission は不要である。usage access は要求しない (#203 非依存)。
- 外部送信なし。候補 label / icon は app-private の process 内でのみ使用し、diagnostics journal には app 単位の情報を出さない (D9)。
- preview 表示の label は inventory 由来である点を除き、spec 194 の privacy 契約 (生 package / `ItemId` / cell 非露出) を継承する。

## Accessibility and localization

- 候補行は単一の意味ある node で label + 選択状態 (`checked` state) を読み上げ、icon は装飾として扱う。
- 選択件数の変化は polite な status 通知として伝える (`liveRegion` は status へ限定し、行の洪水を避ける)。
- 検索 field、Select all、Clear all、確定、cancel、(展開がある場合は) 全 action が keyboard / Switch Access traversal で到達可能であり、`stateDescription` を持つ。
- 200% font scale で候補行・件数・検索が wrap し、切抜きや横 scroll 依存がない。
- 追加 strings はすべて `values/` + `values-ja/` に置く (#123: 日本語実行時の英語 fallback 禁止)。

## Acceptance criteria

- [ ] AC-1: Organizer は、安定した app/profile identity で「現在のホーム workspace に表現されていない launchable installed apps」を検出できる。
- [ ] AC-2: 候補検出は既存の folder-contained placement と重複 placement を正しく処理する (表現とみなして除外する)。
- [ ] AC-3: ユーザーは未配置アプリを review して multi-select で追加対象に含められる。
- [ ] AC-4: 選択 UI は icon / label、選択件数、検索/フィルタ、Select all、Clear all を最低限サポートする。
- [ ] AC-5: 未選択の候補が workspace create mutation を生成することはない (plan / preview / apply のいずれにも現れない)。
- [ ] AC-6: 既存ホーム layout が 0 件 / 極端に少ない入力が Organizer として supported である。
- [ ] AC-7: 選択された未配置アプリは、曖昧な `Move` 行ではなく明示的な `Add` / create 変更として表現される (planner disposition、preview kind、apply action の各層で一貫する)。
- [ ] AC-8: proposal は Add / Move / Preserve を明確に区別し、影響する placement / app を review に足る識別度で表現する (#208 互換)。
- [ ] AC-9: 選択 UI を開く・proposal / preview を生成することは workspace への書込みを 0 件とする。
- [ ] AC-10: Add 操作の apply は、既存 Organizer mutation と同等の transactional / stale check / recovery 保証を持つ。
- [ ] AC-11: apply 失敗が部分的に作成されたホーム item を残さない。
- [ ] AC-12: usage access と #203 は optional であり、機能はそれらなしで決定的に動作する。
- [ ] AC-13: 代表的な instrumentation / device evidence が次をカバーする: 空の workspace、既存+未配置の混在、folder 内の既存 app、重複 placement、select-all / clear-all、部分選択、cancel、stale layout、apply / recovery。
- [ ] AC-14: accessibility evidence が multi-select semantics、TalkBack / keyboard / Switch Access の期待、large font layout をカバーする。
- [ ] AC-15: 契約・文書の更新 ([spec 10](../10-pure-organization-planning/spec.md) V-18 条件化と `Disposition.Added`、[spec 83](../83-production-organization-input-sources/spec.md) `full-target-v2` と新 source、[spec 194](../194-plan-preview-seam/spec.md) `AddChange` / `addedCount`、[spec 195](../195-organizer-confirmation-change-list/spec.md) group 追加、`CONTEXT.md` 用語、`DESIGN.md`) が同じ PR で完了する。
- [ ] AC-16: 検出・planning は決定的であり、適用後の再実行は追加差分 0 である (idempotence)。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-2 | 検出の純粋 unit/fixture test (interface 経由): 直接/folder member/重複/work profile variant/widget のみ/unavailable/非起動可能 の fixture。決定的順序の property test |
| AC-3, AC-4 | 選択 UI の純粋行構築 unit test + instrumentation test (icon/label/件数/検索/Select all/Clear all の動作) |
| AC-5 | planner 契約 test: 選択のみが additions に入ること、未選択候補の `Insert` 不在の主張。coordinator test: 検出・選択・preview 状態で writer 呼出 0 件 |
| AC-6, AC-16 | planner contract / property test: 空 snapshot fixture、additions 含む corpus の determinism / idempotence (適用後再計画で差分 0) |
| AC-7 | planner 契約 test (`Disposition.Added`、captured item は `Added` にならない) + materializer test (`PlannedCandidate` → 完全な intended state の `Insert`、label 未解決で fail-closed) |
| AC-8 | projector test (`AddChange` / `addedCount` / 配置語) + confirmation UI instrumentation test (group 順序、行識別)。#208 設計確定時は同一設計への追従 test |
| AC-9 | 既存 zero-write 契約 test の拡張 + coordinator test (選択/preview 時の書込み不在) |
| AC-10, AC-11 | application contract test (test DB): candidate `Insert` 行の transaction、stale revision で無書込み、Nth-write failure injection で全 rollback、recovery による insert 反転 (`DeleteRow`) と再復元 |
| AC-12 | inventory source 契約 test: signals / usage に依存しない決定的出力。permission 要求の不在 (manifest / runtime) の確認 |
| AC-13 | instrumentation / device evidence: 空 workspace、混在、folder 内、重複、select-all/clear-all、部分選択、cancel、stale、apply/recovery の各シナリオを API 36 / Platform 36.1 で実行し、run URL と結果を PR へ記録 |
| AC-14 | instrumentation test: 行 semantics (checked state)、status 通知、traversal、200% font scale。ja configuration での全 strings 解決 (#123) |
| AC-15 | PR diff review (対象 spec群 / `CONTEXT.md` / `DESIGN.md`) |
| AC-13 の記録 | `risk: layout-data` のため、`high-risk-gate` workflow の要件 (`docs/assessment/pr-<PR番号>-<slug>.md` の独立 audit、`final-status` CI 成功) を満たす |

## Open questions

None。spec 時点で確定した判断は §Design decisions (D1–D9) のとおり。実装中に inventory の取得粒度 (例: launcher activity の alias 解決) で契約で表現できない需要が判明した場合は、 owner review で停止し contract 変更として扱う。

## Change history

- 2026-09-06: Draft created for #228。既存契約の調査 ([spec 10](../10-pure-organization-planning/spec.md) / [spec 12](../12-deterministic-full-layout-planner-v1/spec.md) / [spec 13](../13-safe-layout-application/spec.md) / [spec 83](../83-production-organization-input-sources/spec.md) / [spec 194](../194-plan-preview-seam/spec.md) / [spec 195](../195-organizer-confirmation-change-list/spec.md) / [spec 201](../201-generated-folder-semantic-naming/spec.md)) に基づき、検出 identity (D1)、inventory source (D2)、選択 semantics (D3)、run 統合 (D4)、planner 契約変更 (D5)、target policy version (D6)、Add の 3 層表現 (D7)、fresh workspace (D8)、diagnostics / privacy (D9) を確定して作成。

## References

- [Issue #228: Organizerでホーム未配置アプリを選択して追加・整理できるようにする](https://github.com/nunu1733/NunuLauncher/issues/228)
- [Spec 10: pure organization planning (planner 入出力契約、V-18、CandidateItem)](../10-pure-organization-planning/spec.md)
- [Spec 12: deterministic full layout planner v1 (P-04/P-06/P-08/P-11、candidate allocation)](../12-deterministic-full-layout-planner-v1/spec.md)
- [Spec 13: safe layout application (apply/recovery 契約、Insert の expected-absence)](../13-safe-layout-application/spec.md)
- [Spec 83: production organization input sources (composer、full-target-v1、InputProvenance)](../83-production-organization-input-sources/spec.md)
- [Spec 194: plan preview seam (PreviewChange closed union)](../194-plan-preview-seam/spec.md)
- [Spec 195: organizer confirmation change list (group/truncation/a11y 契約)](../195-organizer-confirmation-change-list/spec.md)
- [Spec 201: generated folder semantic naming (resolver 前例、fail-closed)](../201-generated-folder-semantic-naming/spec.md)
- [Issue #208: 同名 placement の識別可能性](https://github.com/nunu1733/NunuLauncher/issues/208)
- [Issue #203: usage / implicit preference signals](https://github.com/nunu1733/NunuLauncher/issues/203)
- [ADR-0005: fresh-install presence evidence](../../docs/adr/0005-fresh-install-presence-evidence.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
- [AGENTS.md](../../AGENTS.md)
