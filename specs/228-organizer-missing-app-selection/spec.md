---
issue: "#228"
status: draft
requirements:
  - FR-002
  - FR-006
  - NFR-002
  - NFR-003
  - NFR-009
risk:
  - layout-data
updated: 2026-09-11
---

# ホーム未配置アプリを選択してOrganizerの対象へ追加できる

> Status: draft (未承認)。Issue #228 の依存Issue (#182, #194, #195, #208) はいずれもimplementedであり、本specはそれらの契約を変更せず消費する。#203 (usage signal) には依存しない。

## Problem

Organizerの入力は現在、Home workspaceに**すでに存在する**配置アイテムのcaptureのみから構成される。production composition seam (`OrganizationInputComposer.composeFullOrganization`) は `FullTargetSetMaterializer` が返す `TargetSet` を使っており、`additions` は常に空で、`RunMode` は常に `FULL_ORGANIZATION` に固定されている (`ProductionOrganizationInputComposer.kt`, `ManualOrganizationRun.kt`)。domain modelとplannerは `TargetSet.additions: List<CandidateItem>` と `RunMode.IncrementalPlacement` を既に持つが、productionから additions を与える経路は存在しない。

このため、別端末・新規端末・別launcherからの切り替え直後のようにHome workspaceが空またはほぼ空の状況では、Organizerを開始する前にユーザーがApp Drawerから手作業でアプリをHomeへ置く必要がある (onboarding gap)。また、インストール済みアプリを無差別に全追加することも適切ではない。ユーザーが追加対象を明示的に選べる、target scope compositionの手順が必要である。

## Outcome

Organizerは、現在Homeに存在しないinstalled/launchable appsを**候補**として検出し、ユーザーが追加対象を選択したうえで、既存配置と合わせた整理提案を生成・preview・applyできる。選択UIを開く、候補を検出する、提案を生成する、いずれもworkspaceへの書込みを行わない。選択されたアプリは提案内で `Move` でも `Preserve` でもない明示的な **`Add` (新規配置)** として表現され、未選択の候補はいかなるworkspace変更も生まない。Homeが空またはほぼ空の状態も入力としてサポートされる。

## 用語とidentity

- **未配置アプリ候補 (missing-app candidate)**: installed かつ launchable な app のうち、現在のlayout snapshotに同じ安定identityで表現されていないもの。**安定identityは `ComponentKey` (package + activity component) と `ProfileId` の組**であり、表示labelやiconでは同定しない (planning modelの `CandidateTarget.AppKey` と同一規約)。
- **Home上のapp表現**: snapshot中の `CapturedItem` のうち `TargetKey.AppKey` を持つもの (workspace icon / dock / folder member / app pair memberを含む)。folder内・重複配置も「表現されている」と数える。
- **`Add`**: 現在workspaceに存在しないアプリに対する新規配置の作成。application層の create mutation (`ApplyAction.Insert` 経由の `ApplicationItemRef.PlannedCandidate`) の提案側表現である。`Move` (既存placementの移動) とは区別される。**現在のrepositoryには、この経路の断片 (型定義、DB書込み面の `ApplyAction.Insert` 取扱い) のみが存在し、planをcandidate Insertへ流すproduction materialization経路は存在しない** (`OrganizationPlanMaterializer` はplanned placementがcaptured snapshot itemに対応することを要求し、`PlanPreviewProjector` は `PlannedCandidate` をrejectする)。本機能はこのcreate経路を新規に構築し、既存のMove/Update適用と同等の保証 (transaction / stale-check / recovery / 適用後検証) を与える (§6)。

## Scope

### 1. Missing-app detection (candidate detection)

canonicalな検出式:

```text
launchable installed apps (per-profile, LauncherApps権限で列挙)
  − snapshot中のapp表現の安定identity集合
  = missing-app candidates
```

要件:

- 列挙はlauncher-authorizedな `LauncherApps` surfaceからprofileごとに行う (既存の `AndroidClassificationSignalSnapshotSource` / `CategoryOverrideAuthoring` と同じ権限面。新規permissionは追加しない)。
- 同一appの重複placement (home iconが2箇所等) は「表現済み」と判定する。identity集合からの差分計算であるため、重複は候補性に影響しない。
- folder内・dock・app pair内のappも「表現済み」と数える (`TargetKey.AppKey` の等価性で判定し、配置種別に依存しない)。
- work profile / personal profileの別は `ProfileId` で区別する (profile isolation不変条件の継続)。
- **検出の初期対象は「選択UI表示時点で `AVAILABLE` な候補」に限る**。disabled / suspended / quiet / locked-private-space / unavailable なappは候補一覧から除外する。検出後に無効化されたappは提案生成または適用時に失敗として扱う (§failure)。
- launcher自身・system内部activity等の「通常のユーザーappとして露出すべきでない」componentの除外は、platformのlaunchable列挙 (`LauncherApps.getActivityList`) がlaunchableと判定したactivity集合をそのまま使う。project側で独自のblocklistを追加しない (最初のdeliveryではplatform列挙を信頼し、除外が必要になった時点で証拠とともに別Issueで扱う)。
- deep-link-only / non-launchable packageは `getActivityList` に現れないため、構造的に候補にならない。
- 検出は読み取りのみであり、診断には個人情報 (package名のtext化) を露出しない (既存diagnostics契約の継続)。

### 2. 選択UI (明示的選択)

書込みの前に、ユーザーが追加対象を明示的に選択するUIを置く。最小要件 (Issue #228の指定):

- app iconとlabelの表示
- multi-select (checkbox相当の選択affordance)
- 選択数の表示
- search / filter
- Select all / Clear all

追加要件:

- 未選択のまま確定することは「候補を追加しない従来どおりの全体整理」として有効である。
- 検出された候補が0件の場合、その旨を表示して従来flowに戻る (エラーではない)。
- 選択stateはprocess-localなUI stateであり、persistしない。
- **選択stateと検索/filterの相互作用** (受入条件の一部):
  - 選択stateは候補の安定identityで保持され、検索語・filterの変更では選択を保持する (非表示になっても解除しない)。
  - **Select all** は、現在の検索語・filterに一致する候補 (表示中の集合) をすべて選択し、それに一致しない既存選択を保持する。
  - **Clear all** は、検索語・filterの状態に関係なく**候補全体**の選択を解除する (filter外の選択を残すescape hatchではない)。
  - **選択数の表示は候補全体に対する選択数**であり、filterで絞り込まれた部分集合の数と一致しないことがある。表示は「全体選択数」を正本とする。
- user-facing語は `Select apps to organize` / `Apps not on Home` 系とする (`install inventory import` 等の実装中心語は使わない)。stringsは `values/` + `values-ja/` 両方へ置く (spec 123契約)。

### 3. 選択default (初期選択policy)

**決定済み (owner, 2026-09-11): unchecked-by-default** (判断記録: [Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/228#issuecomment-5634964606))。installed/launchableであるという理由だけでの黙示的包含は行わない。全候補は初期状態で**未選択**であり、ユーザーの明示的選択のみが追加対象になる。bulk action (Select all / Clear all) により大きな候補数も扱えることを必須要件とする。#203由来の推奨信号は将来の拡張であり、自動包含へ黙示的に変わることを許さない。

### 4. Run mode composition (既存配置と新規候補の合成)

**決定済み (owner, 2026-09-11): 新規run mode (scope-composed organize) を採用する** (判断記録: [Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/228#issuecomment-5634964606))。既存2 modeの契約は無変更:

- `RunMode.FullOrganization`: additionsが空でなければreject (`ADDITIONS_UNDER_FULL_ORGANIZATION`)。この不変条件と既存property test群は維持される。
- `RunMode.IncrementalPlacement`: 既存itemをすべてPreserveし、候補のみを配置する (既存の再整理を行わない)。

本機能は第3のrun mode (planning上の仮称 `ScopeComposedOrganization`) を導入し、既存配置の全体再整理と選択済みadditionsを同一planで扱う。既存modeへの検証緩和は行わない。本modeで次の観測可能契約が成立する:

- 選択された候補は既存の整理規則 (strategy #182, lock, reservation) の下で配置される。
- **選択された候補は、既存配置と同一のpolicy authority (ADR-0007のimmutable bundleとuser category override) の下で分類・配置される**。候補に対して既存のpolicy source以外の新規policy入力を導入しない。compositionが候補を分類signal materializationの対象に含めない設計は、本specの受入条件を満たさない。
- **入力provenance (target digest等) は選択済みadditionsを含んで計算される**。同一の選択集合から同一のprovenance identityが得られ、選択集合が変わればprovenance identityが変わること (staleなprovenance再利用の禁止)。
- **選択済み候補のplanning ID (`CandidateItem.id`) は表示label等のlocale依存情報から導出せず、安定identityから決定的に導出し、既存snapshot item ID・生成folder ID等の既存namespaceと衝突しない** (導出規則はplanで固定)。
- 未選択候補はplanにcreate mutationとして現れない。
- 既存itemのMove/Preserve意味は従来の全体整理と同じ規則に従う。
- 決定性 (同一入力からbyte-equivalentなplan) と冪等性 (適用後に再実行すると空差分) は維持する。冪等性の規則: 追加済みappは次回検出で「表現済み」となり候補にならない。

### 5. 提案・preview上の `Add` 表現

- preview projection (`PreviewChange`) に、source placementを持たない新規配置行 **`AddChange`** を導入する。`MoveChange` (source identity必須) へのoverloadは行わない (#208のidentity契約はsource-backed行が対象であり、Add行はsourceを持たないため、別表現が必要)。
- **`AddChange` は配置先に関係なく、選択済み候補ごとに1行生成する** (top-level配置でも生成folder所属でも)。これにより確認画面から新規作成されるアプリを、その追加先とともにすべてAdd行として特定できる (AC-5)。生成folderへの所属がmember listだけでは表現される形は認めない (現行 `NewFolderChange` は `memberLabels` のみを持ちmemberの作成/移動を区別できないため)。
- destination表示: top-level配置はpage/領域/行列表現 (#234のanchor表現)、生成folder所属は**生成folder名を追加先として表示**する。
- **`NewFolderChange` はfolder構造の表現** (title, placement, member labels) を担い続ける。memberの作成/移動の区別は各member自身の行 (`AddChange` / `MoveChange`) で表現し、`NewFolderChange` のmember list構造 (label-only) は変更しない (#195/#201契約の維持)。
- **Add行を含む提案は、具体化されたpreview (変更一覧 `details` が存在する状態) でのみ確認可能とする**。既存flowはpreview capture/materialization失敗時に「件数のみのpreview」へfallbackする経路を持つが、Addを含むrunがこのfallback経路で確認可能なままでは、ユーザーがAdd行とその追加先を見ないで作成を承認できてしまう。Addを含むrunで具体previewが得られない場合、確認ではなくtyped失敗 (re-preview誘導) として扱う。Addを含まない従来runの既存fallback挙動は変更しない。
- **counts契約の拡張 (意図的なshape拡張を明示)**: `PreviewCounts` にAdd分のcountを追加する。**Add count = `AddChange` 行数 (配置先が確定した選択済み候補の総数)**。生成folder所属の候補も数えるため、全候補が1つの新規folderに入るケースでも作成アプリ数がcountsに現れる。既存のMove/Preserve/NewFolder/NewPage countsの意味は変更しない。
- 配置先が確定しなかった選択済み候補は既存のunplaced warning契約に従って警告表示され、`AddChange` も作成も行われない (未作成は失敗ではなく既存overflow契約)。
- group順序・counts truth・truncationはspec 195の契約をAdd group分だけ拡張し、既存group契約は変更しない。
- destination表示は #234 (#208依存の実装済み契約) と同じanchor表現を使う。
- raw package / component / profile serialは表示へ現れない (spec 194 privacy契約の継続)。

### 6. create mutation経路の構築と適用・stale・recovery

- **create経路は本機能で構築する。** 現在のrepositoryには、planをcandidate Insertへ流すproduction経路が存在しない (`OrganizationPlanMaterializer` はplanned placementをcaptured snapshot itemに限定し、candidateを含むplanを `Result.Invalid` にする。`PlanPreviewProjector` も `PlannedCandidate` をrejectする)。既存のDB書込み面 (`ApplyAction.Insert` のtransaction / stale-check / recovery取扱い) は消費するが、並列のwrite pathは作らず、既存materializer / projectorへのcandidate partition拡張として構築する。拡張内容: 候補と既存のpartition検証、候補のcanonical application item構築 (**launch intent / profile / title / iconの解決はapplication所有の `CandidateApplicationResolver` port経由**でplatform adapter実装が担う。現行materializerが受取るresolverはfolder title用のみであり、新設が必要)、planned identity mapping、生成folder membership、candidate Insert action生成。
- **適用時のavailability再検証 (新規のfail-closed事前条件)**: 既存の適用事前条件はInsertについて「対象がDBに存在しないこと」のみを検証し、capture側のavailabilityはprofile由来でcomponent状態 (disabled / suspended等) を反映しないため、preview後のアプリ無効化を既存検証では捕捉できない。本機能は、application module所有のavailability再検証 (component + profile単位) を適用の事前条件に加える。検証はstale revision再確認と同じ適用transaction境界の内側 (または直前) に行い、失敗ならcommit前に拒否する。
- **app inventoryのstale**: 選択〜適用の間にアプリがuninstall / disabled / suspendedになった場合、前項の再検証がcommit前失敗として捕捉し、全体として失敗 (fail-closed) とする。部分成功はしない。ユーザーは再検出からやり直す。layout revisionのstale検出は既存契約 (exact precondition) に従う。
- **commit前失敗とcommit後の区別**: commit前の失敗 (availability再検証、stale revision、事前条件違反、write失敗) はrollbackされ、workspace変更は0件である (atomicity不変条件)。commit後の検証失敗は既存recovery契約に従うが、recoveryが `Unresolved` / 失敗になる場合があることを前提とし、**無条件の復旧を約束しない**。ユーザー向け結果表示は既存のrecovery失敗契約 (spec 13 / #210系) に従う。
- 選択UIを開く・提案を生成する・previewするだけでworkspaceへ書き込まない (zero-write)。previewはread-only seam (`inspectPlan`) を使う。

### 7. Fresh workspace

現在のHomeにapp配置が0件またはほぼ0件の場合も、検出・選択・提案・適用の一連のflowがそのまま動作する。この経路はNova importの代替ではなく、installed inventoryとuser intentからの新規scope構築である。空の`TargetSet.existing` + 選択候補という入力がrejectされないことを保証する (planner検証の `INCOMPLETE_TARGET_PARTITION` 等は空items集合で自明に満たされる)。

## Non-goals

- 検出された全アプリの自動追加 (自動包含の禁止)。
- Nova import / backup restoreの置き換えや拡張。
- Play Store category等によるHome配置適性の推論。
- usage権限の要求、#203への依存 (#203が提供する信号は将来の選択補助にのみ使え、必須ではない)。
- AI/LLMによるアプリ選択。
- preview / 確認のbypass。
- app install時のbackground自動配置 (package-event配置はIssue #85 Option Bにより別扱い。本機能は**ユーザーが明示的に開始する**選択flowであり、#85のdefer対象であるpackage event triggerを復活させない)。
- 双方向scope編集 (既存Home配置を整理対象から除外する方向) の実装。ただしdata model / UI構造がこの拡張を妨げないこと (§将来拡張)。
- 候補の並べ替え・推薦ranking (label順等の決定的な表示順を除く)。

## 依存関係

| 依存先 | 状態 | 本機能からの利用 |
|---|---|---|
| #182 layout strategy catalog | implemented | 選択候補の配置が既存strategy選択に従う。変更なし |
| #194 plan preview seam | implemented | read-only previewと `PreviewChange` projectionの消費。Add表現の追加はprojectionのadditive拡張 |
| #195 confirmation change list | implemented | 確認UIのgrouping / counts / truncation契約のAdd分拡張 |
| #208 placement identity | implemented | Add行はsource-backed行ではないためidentity契約の対象外 (NewFolderChangeと同じ位置づけ)。Move/Preserve行のidentity契約は無変更 |
| #203 usage signals | **未着手 (unsettled)** | 依存しない。usage accessなしで決定的動作が受入条件 |
| #85 (package-event incremental) | Option Bでdeferred | 本機能はuser-initiatedでありtriggerを共有しない。FR-008のdeferを変更しない |

## Behavior scenarios

### Scenario: Empty workspace onboarding

Given Homeにapp配置が1件もなく、installed launchable appsが40件存在する,

When ユーザーがOrganizerを開始し選択UIを開く,

Then 40件が候補として表示され (label決定的順序)、選択・検索・Select all / Clear allが機能し、選択せず確定すれば従来どおり変更0件または既存itemのみの提案となる,

And 選択したアプリは `Add` 行として提案され、確認・適用を経てHomeへ作成される。

### Scenario: Folder-contained and duplicated apps are not candidates

Given 「Maps」がfolder内に存在し、「Mail」がhome iconとして2箇所に重複配置されている,

When 候補検出が実行される,

Then 「Maps」「Mail」はいずれも候補に現れない (安定identityで表現済みと判定される)。

### Scenario: Unselected candidates never produce writes

Given 候補50件のうち12件を選択して提案を生成した,

When 提案・preview・適用が完了する,

Then 残り38件について提案内にcreate mutationは現れず、workspace上に何も作成されない。

### Scenario: App removed during the flow

Given 選択済み候補の1つが、提案生成後・適用前にuninstallされた,

When ユーザーが適用を確認する,

Then 適用はcommit前に失敗し (availability再検証のfail-closed)、一部だけ作成されたitemは残らず、再検出を促す表示がされる。

### Scenario: Bulk selection under filter

Given 候補40件があり、検索語で5件が一致し、検索前に3件が選択済みである,

When ユーザーがSelect allを実行する,

Then 選択数は8 (既存3 + 一致5) となり、検索語を解除しても選択8件が保持される。

When 検索語を変えて一致0件の状態でClear allを実行する,

Then 候補全体の選択が解除され、選択数は0になる。

### Scenario: Zero-write on browsing

Given ユーザーが選択UIを開き、検索し、Select allを実行したあとキャンセルした,

When いずれの時点でも,

Then workspaceへの書込みは一切発生しない。

### Scenario: Work profile isolation

Given personal profileとwork profileの両方に同名appがインストールされ、work profile側のみHomeに配置済みである,

When 候補検出が実行される,

Then personal profile側のみが候補として現れる (profile混同なし)。

### Scenario: Disabled apps are excluded

Given 候補検出の時点でsuspended状態のappが存在する,

When 検出が実行される,

Then 当該appは候補一覧に現れない。

## データと状態

- 読むdata: canonical capture (既存 `CapturedSnapshot`) と `LauncherApps` のlaunchable activity列挙。新規の永続data、migration、backup影響は**なし**。
- 選択stateはprocess-local。候補一覧のsnapshot性: 検出は選択UI表示時に1回行い、flow内で再検出しない (staleは§6の契約で捕捉)。

## Privacy / Security / Accessibility

- 新規permission、network、telemetryなし。app inventoryの列挙は既存launcher権限内。
- 候補一覧・提案行の表示はapp labelとiconに限り、raw package / component / profile serialを表示しない (spec 194継続)。
- 選択UIのmulti-select semanticsはTalkBackで選択状態 (`stateDescription`) が読み上げられ、keyboard / Switch Accessで全候補・全actionに到達でき、200% font scaleでwrapする (spec 52 a11y契約の継承)。選択数変更はstatus行としてannounceする。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | 検出が `ComponentKey` + `ProfileId` の安定identityで行われ、folder内・dock・app pair内・重複配置のappを候補から除外する。work profile / personal profileを混同しない。 |
| AC-2 | 選択UIが icon / label / multi-select / 選択数 / search / Select all / Clear all を提供し、未選択のままの確定とキャンセルができる。 |
| AC-3 | 初期選択policyはunchecked-by-default (D-1, owner決定済み) であり、実装がそれに従う (全候補が初期未選択)。 |
| AC-4 | 未選択候補が提案・適用のいずれにもcreate mutationとして現れない。 |
| AC-5 | 選択された候補が、配置先がtop-levelか生成folder内かにかかわらず、**候補ごとに個別の `AddChange` 行**として提案・確認画面に現れ (Move/Preserveと区別可能、生成folder所属候補の作成がmember listのみで隠れない)、Add countが`AddChange`行数と一致する。#208のsource行identity契約と #195のgrouping/counts契約を退化させない。 |
| AC-6 | 選択UIを開く・提案生成・previewの各段階でworkspace書込みが0件である (read-only seam経由)。 |
| AC-7 | create経路が既存のtransaction / stale-check / recovery / 適用後検証の面を利用して構築され、適用失敗時に部分作成を残さない。commit前にavailability再検証 (component + profile単位) が行われ、適用時のapp無効化・uninstallは全体失敗 (fail-closed) となる。commit後の検証失敗・recovery失敗 (`Unresolved`含む) のユーザー結果は既存recovery契約に従う。 |
| AC-8 | Home配置が0件の入力で、検出から選択・提案・適用まで一連のflowが成功する。 |
| AC-9 | usage accessなし (権限なし・#203なし) で全機能が決定的に動作する。 |
| AC-10 | 空workspace / 既存+未配置混在 / folder内既存 / 重複配置 / select all / clear all / 部分選択 / cancel / stale layout / 適用と復旧 を覆すinstrumentation/device evidenceがある。 |
| AC-11 | multi-select semantics / TalkBack / keyboard / Switch Access / 大font layout のaccessibility evidenceがある。自動assertionとassistive-tech (TalkBack / Switch Access) を用いた実機操作evidenceを区別して記録する。 |
| AC-12 | 追加stringsがja / en両localeで解決する (spec 123契約)。 |
| AC-13 | 選択済み候補が既存配置と同一のpolicy authority (bundle + user override) で分類され、入力provenance (target digest) がadditionsを含む。同一選択集合から同一provenance identityが得られ、選択集合の変更がprovenance identityを変える。 |
| AC-14 | Addを含む提案は具体preview (`details` 有り) でのみ確認可能であり、count-only fallbackでは確認できない。Addを含まない従来runの確認挙動は無変更である。 |
| AC-15 | 選択済み候補のplanning ID (`CandidateItem.id`) が安定identityから決定的に導出され (表示label・locale非依存)、captured item ID (favorites行ID由来) および生成folder等のplanned系IDとnamespace衝突せず、同一選択集合から同一IDが得られる。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 検出moduleのunit test (fixture: folder内・重複・work profile・disabled / suspended除外・non-launchable構造除外・複数launcher activity) |
| AC-2, AC-3 | 選択UIのinstrumentation test (選択・検索・bulk操作・選択数表示・初期選択policy)。CI class filter (`ci.yml` connected-test lanes) への新test class追加を実装PRで行う |
| AC-4, AC-5 | planner / projectionのunit + property test (未選択候補の不在、Add行とMove/Preserve行の区別、counts整合) |
| AC-6 | zero-writeのinstrumentation test (DB状態比較**と**application write seamの呼出し回数0) |
| AC-7 | application契約test (test DBでinsert失敗注入・rollback・stale・適用後検証)。preview後・適用直前のdisable / uninstall注入によるcommit前拒否、およびcommit後recovery失敗 (`Unresolved`) の結果契約testを含む |
| AC-8, AC-10 | 空workspace / 混在workspaceの決定的test + device evidence (API 36 / Platform 36.1, CI job) |
| AC-9 | 権限なし環境での決定的動作test |
| AC-11 | 自動a11y assertion (unit / instrumentation) に加え、TalkBack / Switch Access実操作とkeyboard / 200% fontのdevice evidenceを分離して記録 |
| AC-12 | ja configuration contextでのstring解決test |
| AC-13 | composer / planner unit test (選択集合変更によるprovenance identity変化、候補分類がbundle + overrideのみを経由すること) |
| AC-14 | preview fallback経路のunit / instrumentation test (Add runで `details = null` が確認不可、Add無しrunは既存挙動維持) |
| AC-15 | planning ID導出のunit test (決定性、locale/label非依存、namespace分離の境界test、選択集合→同一ID) |

## Decisions (owner決定の記録)

- **D-1 初期選択policy: unchecked-by-default** — owner決定 (2026-09-11, [Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/228#issuecomment-5634964606))。
- **D-2 run mode composition: 新規run mode (scope-composed organize) 採用** — owner決定 (2026-09-11, 同コメント)。`FullOrganization` の「additions空」不変条件と既存検証は無変更。
- **D-3 候補一覧の表示順**: 実装PR内で確定 (owner判断)。決定性 (NFR-003) を満たす決定的順序であること (locale依存の表示label順はplan §4のID規約の採番には使わない; 表示順としての可否は実装PRで決める)。

実装開始は、本spec (上記決定を反映した改訂版) のowner受入をもって許可される。

## Change history

- 2026-09-10: Drafted for Issue #228。依存Issue (#182/#194/#195/#208) はimplementedであることを確認し、domain model (`TargetSet.additions` / `CandidateItem` / `RunMode.IncrementalPlacement` / `ApplyAction.Insert`) に既存の拡張点があること、production wiringがFullOrganization固定であることをbaseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` 上で確認して起草。#203は未着手のため依存から除外。D-1〜D-3をunresolved decisionsとして明記。
- 2026-09-11: 再入場検証。baseline `6b6bf8dd` から `b761839479` へのmain差分を確認し、本specが参照する拡張点・検証規則・preview variantsがいずれも未変更であることを再確認した。baseline以降のorganizer変更は Issue #271 (durable status projection) のadditive変更のみであり、本specのobservable behavior・受入条件・Non-goalsに変更なし。
- 2026-09-11: レビュー条件解消 (code-reviewer-1, Request changes → 修正)。初版は「candidate→Insert経路が既存」と記載していたが、実際にはproduction materialization / preview projection経路が存在しないため (検証: `OrganizationPlanMaterializer.kt` L79-88、`PlanPreviewProjector.kt` L79/L343)、§6を「create経路を本機能で構築」へ書き直した。適用時availability再検証の事前条件追加、commit前後の失敗区別、選択/filter相互作用 (Select all / Clear all / 選択数) の定義、Addを含むrunの具体preview前提、`PreviewCounts` のAdd拡張の明示、policy provenance (AC-13) とpreview確認gate (AC-14) の受入条件追加、test oracleの強化 (write seam counter、CI class filter、a11y evidence分離) を行った。
- 2026-09-11: ownerレビュー条件解消 (Request changes → 修正)。(1) §5を「`AddChange` を配置先に関係なく候補ごとに1行生成」へ変更し、生成folder内候補がmember listだけで隠れないことをAC-5へ明記。Add count = `AddChange` 行数へ再定義 (top-level限定数えの廃止)。`NewFolderChange` はfolder構造表現のまま構造変更なし。(2) 候補planning ID (`CandidateItem.id`) の契約を§4へ追加し、AC-15を新設 (安定identity由来・決定的・namespace分離・label非依存)。(3) §6のcanonical構築解決をapplication所有の `CandidateApplicationResolver` port経由へ明示 (現行materializerはfolder title resolverのみ受取)。 (4) D-1 = unchecked-by-default / D-2 = 新規scope-composed run mode をowner決定 (2026-09-11コメント) として§3/§4/Decisionsへ記録し、unresolved decisions を解消。

## References

- [Issue #228](https://github.com/nunu1733/NunuLauncher/issues/228)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 208: proposal placement identity](../208-organizer-proposal-placement-identity/spec.md)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 83: production organization input sources](../83-production-organization-input-sources/spec.md)
- [Spec 52: manual full-organization vertical slice](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md)
- [DESIGN.md](../../DESIGN.md) / [CONTEXT.md](../../CONTEXT.md)
- [docs/product/requirements.md](../../docs/product/requirements.md) (FR-008/#85との関係)
