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
updated: 2026-09-10
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
- **`Add`**: 現在workspaceに存在しないアプリに対する新規配置の作成。application層の既存の typed create mutation (`ApplyAction.Insert` 経由の `ApplicationItemRef.PlannedCandidate`) の提案側表現である。`Move` (既存placementの移動) とは区別される。

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
- launcher自身・system内部activity等の「通常のユーザーappとして露出すべきでない」componentの除外は、platformのlaunchable列挙 (`LauncherApps.getActivityList`) がlaunchableと判定したactivity集合をそのまま使う。project側で独自のblocklistを追加しない (最初のdeliveryでは平台列挙を信頼し、除外が必要になった時点で証拠とともに別Issueで扱う)。
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
- user-facing語は `Select apps to organize` / `Apps not on Home` 系とする (`install inventory import` 等の実装中心語は使わない)。stringsは `values/` + `values-ja/` 両方へ置く (spec 123契約)。

### 3. 選択default (初期選択policy)

**unresolved decision D-1 (owner判断必要)**。Issue本文のpreferred baselineは「installed/launchableであるという理由だけでの黙示的包含をしない」= **unchecked-by-default** を初期案とする。本specはこの保守的なbaselineを推奨するが、UX証拠に基づく別のreview可能なdefaultを採用するかどうかはowner reviewで確定する。bulk action (Select all / Clear all) により大きな候補数も扱えることを必須要件とする。#203由来の推奨信号は将来の拡張であり、自動包含へ黙示的に変わることを許さない。

### 4. Run mode composition (既存配置と新規候補の合成)

**unresolved decision D-2 (owner判断必要)**。Issue要求のflowは「既存placements + 選択済み新規候補 → planner → Move/Preserve/Add混在の提案」である。一方、現行planner契約は:

- `RunMode.FullOrganization`: additionsが空でなければreject (`ADDITIONS_UNDER_FULL_ORGANIZATION`)。
- `RunMode.IncrementalPlacement`: 既存itemをすべてPreserveし、候補のみを配置する (既存の再整理を行わない)。

選択肢:

- **(A) FullOrganizationへのadditions許可**: 検証規則を変更し、全体整理の入力に明示的なuser選択由来のadditionsを含めることを許可する。plannerは既存対象のMove/Preserve計算と候補の配置を同一planで行う。
- **(B) 新規run mode (例: scope-composed organize)**: 既存2 modeに加え、既存の再整理と候補追加を同時に行うmodeを導入する。

本specはobservable behaviorのみを規定し、(A)/(B)の選択はplanで実装可能性を比較のうえowner reviewで確定する。いずれの選択でも、次の観測可能契約は同一とする:

- 選択された候補は既存の整理規則 (strategy #182, lock, reservation) の下で配置される。
- 未選択候補はplanにcreate mutationとして現れない。
- 既存itemのMove/Preserve意味は従来の全体整理と同じ規則に従う。
- 決定性 (同一入力からbyte-equivalentなplan) と冪等性 (適用後に再実行すると空差分) は維持する。冪等性の規則: 追加済みappは次回検出で「表現済み」となり候補にならない。

### 5. 提案・preview上の `Add` 表現

- preview projection (`PreviewChange`) に、source placementを持たない新規配置行としての **Add表現を導入する** (variant追加または同等の明示的表現)。`MoveChange` (source identity必須) へのoverloadは行わない (#208のidentity契約はsource-backed行が対象であり、Add行はsourceを持たないため、別表現が必要)。
- Add行は「アプリlabel — kind — 追加先 (page/領域/行列表現 or 生成folder所属)」を表示し、`NewFolderChange` のmemberとして配置される候補は新規folder行のmember一覧で表現される (既存契約の踏襲)。
- group順序・counts truth・truncationはspec 195の契約をAdd group分だけ拡張し、既存group契約は変更しない。
- destination表示は #234 (#208依存の実装済み契約) と同じanchor表現を使う。
- raw package / component / profile serialは表示へ現れない (spec 194 privacy契約の継続)。

### 6. 適用・stale・recovery

- Add操作の適用は、既存のtyped create mutation (`ApplicationItemRef.PlannedCandidate` → `ApplyAction.Insert`) が持つtransaction / stale-check / recovery / 適用後検証の保証をそのまま消費する。並列の独自write pathは作らない。
- **app inventoryのstale**: 選択〜適用の間にアプリがuninstalled / disabledになった場合、適用はその候補を含まない部分成功をせず、全体として失敗 (fail-closed) とする。ユーザーは再検出からやり直す。layout revisionのstale検出は既存契約 (exact precondition) に従う。
- 適用失敗時、部分作成されたHome itemを残さない (atomicity不変条件)。recovery pointは既存契約に従い作成・復旧される。
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

Then 適用は失敗し (fail-closed)、一部だけ作成されたitemは残らず、再検出を促す表示がされる。

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
| AC-3 | 初期選択policyが D-1 として明示的に決定され (推奨: unchecked-by-default)、実装がそれに従う。 |
| AC-4 | 未選択候補が提案・適用のいずれにもcreate mutationとして現れない。 |
| AC-5 | 選択された候補が `Move` / `Preserve` と区別可能な `Add` 表現として提案・確認画面に現れ、#208のsource行identity契約と #195のgrouping/counts契約を退化させない。 |
| AC-6 | 選択UIを開く・提案生成・previewの各段階でworkspace書込みが0件である (read-only seam経由)。 |
| AC-7 | Add適用が既存のtransaction / stale-check / recovery / 適用後検証を利用し、適用失敗時に部分作成を残さない。適用中のapp無効化は全体失敗 (fail-closed) となる。 |
| AC-8 | Home配置が0件の入力で、検出から選択・提案・適用まで一連のflowが成功する。 |
| AC-9 | usage accessなし (権限なし・#203なし) で全機能が決定的に動作する。 |
| AC-10 | 空workspace / 既存+未配置混在 / folder内既存 / 重複配置 / select all / clear all / 部分選択 / cancel / stale layout / 適用と復旧 を覆すinstrumentation/device evidenceがある。 |
| AC-11 | multi-select semantics / TalkBack / keyboard / Switch Access / 大font layout のaccessibility evidenceがある。 |
| AC-12 | 追加stringsがja / en両localeで解決する (spec 123契約)。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 検出moduleのunit test (fixture: folder内・重複・work profile・disabled / suspended除外・non-launchable構造除外) |
| AC-2, AC-3 | 選択UIのinstrumentation test (選択・検索・bulk操作・選択数表示・初期選択policy) |
| AC-4, AC-5 | planner / projectionのunit + property test (未選択候補の不在、Add行とMove/Preserve行の区別、counts整合) |
| AC-6 | zero-writeのinstrumentation test (DB状態比較) |
| AC-7 | application契約test (test DBでinsert失敗注入・rollback・stale・適用後検証) |
| AC-8, AC-10 | 空workspace / 混在workspaceの決定的test + device evidence (API 36 / Platform 36.1, CI job) |
| AC-9 | 権限なし環境での決定的動作test |
| AC-11 | a11y instrumentation test (TalkBack / Switch Access / keyboard / 200% font) |
| AC-12 | ja configuration contextでのstring解決test |

## Unresolved decisions (実装開始前にowner判断が必要)

- **D-1 初期選択policy**: 推奨はunchecked-by-default。UX証拠による別案の可否も含めownerが確定する。
- **D-2 run mode composition**: FullOrganizationへのadditions許可 (A) か新規mode (B) か。planner検証規則 (`ADDITIONS_UNDER_FULL_ORGANIZATION`) の変更を伴うため、plan.mdの比較をもってownerが選択する。
- **D-3 候補一覧の表示順**: label順 (locale依存) か install順など他の決定的順序か。決定性 (NFR-003) を満たす限り実装PRで確定してよい。

## Change history

- 2026-09-10: Drafted for Issue #228。依存Issue (#182/#194/#195/#208) はimplementedであることを確認し、domain model (`TargetSet.additions` / `CandidateItem` / `RunMode.IncrementalPlacement` / `ApplyAction.Insert`) に既存の拡張点があること、production wiringがFullOrganization固定であることをbaseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` 上で確認して起草。#203は未着手のため依存から除外。D-1〜D-3をunresolved decisionsとして明記。

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
