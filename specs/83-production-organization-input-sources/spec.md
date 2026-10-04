---
issue: "#83"
status: implemented
requirements:
  - FR-002
  - FR-003
  - FR-010
  - FR-011
  - FR-015
  - NFR-002
  - NFR-003
  - NFR-005
  - NFR-007
  - NFR-009
  - NFR-011
updated: 2026-08-19
---

# 手動全体整理向けProduction OrganizationInput供給境界

> **実装完了判定:** Decision Issue [#86](https://github.com/nunu1733/NunuLauncher/issues/86) の受諾、ADR-0007、受諾済み正本`plan.md`に基づき、4つのplanner policy inputを合成するproduction seamを実装した。focused JVM/instrumentation evidence、CI `final-status`、および独立high-risk auditの受諾により、本仕様の受入条件は完了している。 [1] [2] [13] [14]

## Problem

Issue #52の手動全体整理は、既存の純粋な`OrganizationPlanner`へ、fresh canonical captureとversion付きの`RuleSemantics`、`TaxonomyContract`、`ClassificationSignals`、`TargetSet`を渡さなければならない。しかし、planner契約はこれらを**入力として消費する**だけであり、productionでの所有者、読み取り、互換性確認、失敗時の非書込み結果を定めない。[3] [4]

現在のapplication境界は、Launcher DBを権威として、revision、完全なlayout state、profile状態、device capability、row manifestを一つのcanonical captureとして取得できる。一方で、プロジェクト設計書は整理ルールとcategory overrideのownership/migrationを別途定義すると明記しており、Rule Managementは構想上のmoduleにとどまる。[5] [6]

この不足をUIやcoordinatorが補うと、UI-local policy、二つ目のsnapshot、暗黙のfallback、またはprofile/lockの不整合を生む。したがって、Issue #83はpolicyそのものを発明せず、受諾済みのpolicy sourceを検証してplanner入力に合成する唯一のproduction seamを提供しなければならない。[1] [2]

## Outcome

手動全体整理の呼出側はUI DBへ直接アクセスせず、production composition seamにfresh canonical captureを要求する。その結果は、完全かつ互換な`OrganizationInput`を返す`Ready`、または原因を識別できるtyped non-write resultのいずれかである。`Ready`は、既存の`OrganizationPlanner`の公開型を変更せず、capture revisionと4 policy input（rules/taxonomy/signals/targets）のimmutable identity、`OrganizerPolicyBundle`のidentity、profile/device/lock状態を同じ安定したcutへ束縛する。[3] [5] [13]

> **安全原則:** 必須sourceが存在しない、読めない、unsupported、incompatible、または相互に矛盾する場合、composition seamはplannerを呼ばず、書込みを開始せず、空のrule・taxonomy・signal・target setを代入しない。これはplannerの「入力を勝手に補正しない」という契約およびlayout safety規約に従う。[2] [4]

## Scope

| 対象 | 本仕様で定義すること | 実装前提 |
|---|---|---|
| Canonical capture | 既存application captureからplanner用snapshotを組み立てる責務とprovenance | `LauncherLayoutAdapter`由来のcaptureを再利用すること |
| Policy composition | rule、taxonomy、classification signal、target membershipをowner portから取得し、互換性を検証する順序 | 各owner/sourceがDecisionで受諾されていること |
| Failure handoff | #52がUI-local fallbackなしで扱えるtyped non-write result | user-facing文言と診断情報を分離すること |
| Context safety | profile、quiet/private/unavailable、lock、device、page、revisionをcapture内で保存すること | current layoutの正本はLauncher DBのままとすること |
| Verification design | composition unit/contract/integrationのtest oracleと高リスク証拠 | focused unit/instrumentation、CI、独立auditで受入条件を実証すること |

## Non-goals

本Issueはplanner algorithm、planner公開型、layout application/recovery mutation、UI、onboarding、package-event incremental placementを変更しない。また、Launcher DBの`favorites`を直接書き込まず、recovery pointを作成せず、ADR-0007で受諾されたv1 bundle/override contractを越えるrule format、taxonomy内容、package/intent rule、override persistence、network/LLM/usage signalを新規に選定しない。[1] [3] [13]

既存Flowerpotは、asset rulesをUI/runtime app listへ適用するlegacy分類実装である。現状ではorganizerの`CategoryId`、`TaxonomyVersion`、`SignalSource`、profile単位のoverride、planner互換性を所有しないため、本仕様でauthoritative sourceとして採用しない。採用の可否はDecisionで明示的に決める。[7] [8]

## Domain language

新しいdomain語は追加しない。ここで使用する**composition**は、既存の`OrganizationInput`を構成するために、canonical captureとversion付きpolicy sourceを検証して束ねる内部責務を指す。承認時も`CONTEXT.md`の用語を変更する必要はない。

## Authority and owner matrix

次表は調査結果と、`accepted`にするために必要なownerを区別する。`confirmed`は既存の受諾済み契約またはproduction codeがあることを意味し、`proposed`および`unresolved`は実装の根拠にならない。

| Planner input / context | 現在確認できるsource | 現在の状態 | 受諾後に必要なproduction owner | Stage A判定 |
|---|---|---|---|---|
| `LayoutSnapshot`、revision、pages、device、items | Issue #14 application capture。`LayoutWriterPort.captureCurrent`と`LauncherLayoutAdapter` | **confirmed**。canonical `LayoutState`、manifest、revisionを一括取得する | application capture adapter。compositionはread-only consumer | 利用可能 |
| lock state | canonical itemの`OrganizerLockState`、ADR-0004の`favorites.organizerLockState` | **confirmed**。`UNKNOWN`/unreadableはfail-closed | application capture adapter。別のlock sourceを追加しない | 利用可能 |
| profile / item availability | canonical itemとprofile state | **confirmed**。profileはcapture時の値であり、availabilityを保持する | application capture adapter | 利用可能 |
| `RuleSemantics` | Rule Management `OrganizerPolicyBundle.rules` | **accepted**。`organization-policy-v1`のimmutable built-in bundleから`RuleVersion("v1")`を直接投影 | `app.lawnchair.organizer.rules` | 実装可能 |
| `TaxonomyContract` | Rule Management `OrganizerPolicyBundle.taxonomy` | **accepted**。同bundleから`TaxonomyVersion("v1")`、34 category、fallback `OTHER`を直接投影 | `app.lawnchair.organizer.rules` | 実装可能 |
| `ClassificationSignals` | 同bundleのclassification policy、Rule Management `CategoryOverrideStore`、integration `ClassificationSignalSnapshotSource` | **accepted**。S1はprofile-scoped override、S2/S5はstable platform evidence、S3/S4はbundle内の明示的empty table | Rule Managementが唯一のpolicy owner。integrationはadapter | 実装可能 |
| `TargetSet` | Rule Management `OrganizerPolicyBundle.fullOrganizationTargets`を#83 composition boundaryでmaterialize | **accepted**。canonical captureの全itemをexactly-once partition、additionsは明示的empty | Rule Management policy + #83 materializer | 実装可能 |
| 4 policy inputの整合断面 | immutable built-in bundle + dynamic snapshot validation | **accepted**。override/platform evidenceを前後2回読取り、全attemptを1回だけretry。2回目も不安定なら`InconsistentPolicyRead` | #83 composition boundary | 実装可能 |

`LayoutSnapshot`等のconfirmed contextは、application/recoveryが既にcanonical stateを取得してrevisionへ束縛する設計と一致する。ADR-0007は`DESIGN.md`とtaxonomy proposalに残っていたpolicy owner/identity/migrationの判断を、manual `FullOrganization` v1に限って解消した。実装はADRで明示されたbuilt-in bundleとtyped local override snapshot以外をpolicy sourceにしてはならない。[5] [6] [7] [13]

## Accepted composition contract

`organizer/integration`内に次のinternal seamを置く。Rule Managementの`OrganizerPolicyBundle`と`CategoryOverrideStore`はpolicy authorityであり、integrationはfresh canonical captureとplatform evidenceを読み取り、ADR-0007の安定性プロトコルに従ってplanner inputへmaterializeするadapterである。このproduction seamは受諾済み正本`plan.md`に従って実装・検証済みである。[13] [14]

```kotlin
internal interface OrganizationInputComposer {
    fun composeFullOrganization(): OrganizationInputComposition
}

internal sealed interface OrganizationInputComposition {
    data class Ready(
        val input: OrganizationInput,
        val provenance: InputProvenance,
    ) : OrganizationInputComposition

    data class NotReady(
        val reason: InputReadinessReason,
        val diagnostic: CompositionDiagnostic,
    ) : OrganizationInputComposition
}

internal data class InputProvenance(
    val revision: RevisionId,
    val rules: PolicyInputIdentity,
    val taxonomy: PolicyInputIdentity,
    val signals: PolicyInputIdentity,
    val targets: PolicyInputIdentity,
    val policyBundle: PolicyBundleIdentity,
)

/** ADR-0007: source kind + semantic version or generation + SHA-256 content digest. */
internal data class PolicyInputIdentity(
    val source: PolicySource,
    val semanticVersionOrGeneration: String,
    val contentDigest: String,
)

/** Proves that all four policy inputs came from one immutable consistency cut. */
internal data class PolicyBundleIdentity(
    val consistencyToken: String,
    val contentDigest: String,
)

internal sealed interface InputReadinessReason {
    data class SourceUnavailable(val source: PolicySource) : InputReadinessReason
    data class SourceUnreadable(val source: PolicySource) : InputReadinessReason
    data class UnsupportedVersion(val source: PolicySource, val actual: PolicyInputIdentity?) : InputReadinessReason
    data class IncompatiblePolicyBundle(
        val rules: PolicyInputIdentity,
        val taxonomy: PolicyInputIdentity,
        val signals: PolicyInputIdentity,
        val targets: PolicyInputIdentity,
        val policyBundle: PolicyBundleIdentity,
    ) : InputReadinessReason
    data class InconsistentPolicyRead(
        val expected: PolicyBundleIdentity,
        val observed: PolicyBundleIdentity,
    ) : InputReadinessReason
    data class ContradictorySource(val source: PolicySource) : InputReadinessReason
    data class InvalidCanonicalCapture(val category: CaptureFailureCategory) : InputReadinessReason
}
```

型名は実装時に既存命名との整合を確認するが、次の性質は固定する。`Ready`だけがplanner呼出しを許可し、`NotReady`は**non-write**である。`InputProvenance`はrule、taxonomy、signals、targetsの**全4入力**についてimmutable identityと、同一整合断面を示すbundle identityを保持する。identityは同じversion/generationが異なるcontentを指すことを許さず、content digestを含む。`NotReady`の診断は安定したcodeとopaque parameterだけを保持し、#52はこのresultからuser-facing reasonを組み立てる。journalやraw DB/model typeをUI reasonのsourceにしてはならない。[1] [3] [9]

### Required ports and dependency direction

CompositionはUIからDBへ到達してはならない。#52はplanner-input policyについて`OrganizationInputComposer`にだけ依存し、既存planner/application/diagnostics seamをorchestrationする。composerは以下のread-only portを通じて情報を得る。platform/DB typeはportの内部に閉じ込め、plannerには既存のdomain型だけを渡す。[3] [5] [13]

| Port responsibility | Input / output | Required guarantee |
|---|---|---|
| `CanonicalCapturePort` | fresh capture → canonical state + revision | 同一capture内のpage、device、profile、item、lock、availabilityを返す。DB/UI型を公開しない |
| `OrganizerPolicyBundleSource` | active immutable built-in bundle → rules/taxonomy/classification policy/target policy + `PolicyBundleIdentity` | `organization-policy-v1`を唯一のpolicy authorityとして返す。digest検証・supported version/bindingの失敗は値で返す |
| `CategoryOverrideSnapshotSource` | captured profiles → profile-scoped S1 snapshot + schema/generation/digest | defined empty generation-0は正常値。corrupt/I/O/unsupported/contradictory duplicateはfail-closed |
| `ClassificationSignalSnapshotSource` | capture + bundle policy + stable override snapshot → platform evidence + canonical digest | S2/S5 read failureをabsenceに丸めない。S3/S4の明示的empty table、正常なno-observationだけがabsence |
| `FullTargetSetMaterializer` | canonical captured inventory + bundle target policy → complete `TargetSet` + membership digest | 全captured itemを一度だけpartitionし、out-of-scope itemを削除・脱落させない。`additions`は明示的empty |

`ClassificationSignals`にentryがないことは、受諾済みのtaxonomy/ruleが存在する場合にはplannerのS6 fallbackとして扱える。しかし、taxonomyまたはsignal sourceそのものがunavailableであることは、空集合を渡す根拠にならない。この区別をcomposition層で強制する。[3] [4]

## Data flow and safety conditions

1. Composerはapplication capture portからfresh canonical captureを一度だけ取得する。captureが失敗、構造不正、lock `UNKNOWN`、profile/contextが読めない場合は`NotReady(InvalidCanonicalCapture)`を返す。
2. ComposerはRule Managementのactive immutable `OrganizerPolicyBundle`を読み、そのsource kind、`organization-policy-v1`、SHA-256 digestから`PolicyBundleIdentity`を検証する。rules/taxonomyはbundleから直接投影し、`RuleVersion("v1")`と`TaxonomyVersion("v1")`のbindingを検証する。
3. 一つのdynamic attemptで、composerはprofile-scoped override snapshot **A**、canonicalized platform evidence **E1**、override snapshot **B**、同じevidence **E2**の順に読む。A/B identity、E1/E2 digest、bundle identityが等しいときだけcutはstableである。
4. stableでなければcomposerはattempt全体を破棄して一回だけ再試行する。二回目も不安定なら`NotReady(InconsistentPolicyRead)`を返し、mixed snapshotを使用しない。source read failure、unsupported version、invalid binding、contradictory evidence/category、incomplete partitionもtyped non-write resultにする。
5. Composerはcaptureを`LayoutSnapshot`へlosslessに射影し、`full-target-v1`でcapture内の全itemを完全partitionする。captured itemがtarget集合から抜けることは不正であり、`additions`は明示的emptyである。
6. Composerは`OrganizationInput(..., runMode = FullOrganization)`を構成し、4 input identity、bundle/cut identity、capture revisionをprovenanceに添えて`Ready`を返す。planner呼出し後、#52はechoされたrevisionをapplication apply seamへ渡し、適用側が再検証する。[13]

```mermaid
flowchart LR
    UI[Issue #52 UI / coordinator] --> C[OrganizationInputComposer]
    C --> A[Canonical application capture]
    C --> R[Accepted RuleSemantics owner]
    C --> T[Accepted Taxonomy owner]
    C --> S[Accepted signal owners]
    C --> M[Accepted full-target policy owner]
    A --> V{Capture valid?}
    R --> K{All sources compatible?}
    T --> K
    S --> K
    M --> K
    V -->|No| N[NotReady: non-write]
    K -->|No| N
    V -->|Yes| O[OrganizationInput]
    K -->|Yes| O
    O --> P[OrganizationPlanner]
```

### Capture mapping requirements

| Canonical capture field | Planner projection | Required behavior |
|---|---|---|
| revision | `LayoutSnapshot.revision` | planner結果へechoされる。stalenessはplanner外で比較する |
| persistent pages | `Page(id, order)` | committed desktop row由来の安定順序。UI-only pageを補完しない |
| device capabilities | planner `DeviceCapabilities` | capture後に変化した場合、apply前にstaleとして再captureする |
| item identity / profile / target / placement / structure | `CapturedItem` | raw rowをplanner public surfaceに漏らさず、全rowを表現またはfail-closedする |
| item/profile availability | `Availability` | quiet/private/unavailableをavailable扱いに変換しない |
| lock state | `CapturedItem.locked` | `LOCKED`だけをtrueへ写像する。`UNKNOWN`はunlockedに丸めず`NotReady` |

canonical captureが`favorites`を現在layoutの正本として扱い、row、profile、device、lockをrevisionに含める既存契約は再利用する。layout application/recoveryのcaptureを二重化せず、UI DB accessも追加しない。[5] [6] [10]

### Target membership requirements

`full-target-v1`では、`TargetSet.additions`は明示的emptyであり、`TargetSet.existing`はcanonical captureの全itemをちょうど一度だけ`Movable`または`Preserved`に分類する。`Preserved`は削除や無視を意味せず、occupied-spaceとconservationのconstraintである。[3] [4] [13]

classification precedenceは、(1) `locked = true`、(2) `Availability != AVAILABLE`、(3) Dock、(4) folder/app-pair structural member、(5) `APPWIDGET`、`CUSTOM_APPWIDGET`、`APP_PAIR`、`SHORTCUT_LEGACY`を`Preserved`とする。availableかつunlockedのtop-level workspace `APPLICATION`、`DEEP_SHORTCUT`、`FOLDER`だけが`Movable`であり、その他の有効なcaptured itemは`Preserved`である。unknown item kind、unsupported container、dangling/contradictory reference、invalid placement、unknown lock truth、表現不能profile/contextは`Preserved`に丸めず`NotReady`にする。private/quiet profileを対象から落としたり、unknown lockを`false`としたりしてはならない。[10] [13]

## Behavior scenarios

### Scenario: 完全かつ互換なsourceから全体整理入力を構成する

**Given** fresh canonical capture、accepted rule/taxonomy/signal/target policyが同じsupported bindingで読める。
**When** #52が`composeFullOrganization()`を呼ぶ。
**Then** `Ready`は`FullOrganization`の`OrganizationInput`を一つ返す。
**And** inputはcapture revision、device、page、profile、lock、availability、全item、rule version、taxonomy versionを保持する。
**And** plannerは同じpublic seamで呼ばれ、UIはDBへ直接アクセスしない。

### Scenario: ruleまたはtaxonomy sourceが欠落・未読・unsupportedである

**Given** mandatory rule/taxonomy sourceが存在しない、読み取れない、またはsupported version外である。
**When** composerがinputを構成する。
**Then** `NotReady(SourceUnavailable | SourceUnreadable | UnsupportedVersion)`を返す。
**And** planner、application write、recovery point作成は実行されない。
**And** empty rule、empty taxonomy、推測したversionを代入しない。

### Scenario: canonical captureがunknown lockまたは表現不能なitemを含む

**Given** canonical captureが`UNKNOWN` lock state、unknown item kind、欠損profile、または構造不正を示す。
**When** composerがsnapshotを射影する。
**Then** `NotReady(InvalidCanonicalCapture(...))`を返す。
**And** lockをunlockedへ丸めず、itemをtarget集合から落とさず、永続変更を行わない。

### Scenario: quiet/private/unavailable itemを含む

**Given** captured itemまたはprofileがquiet/private/unavailableであり、placementと参照は構造的に有効である。
**When** accepted target policyがinputを構成する。
**Then** itemのprofile identityとavailabilityを保持する。
**And** accepted preservation policyが`Preserved`を要求する場合、itemはoccupancy constraintとして`TargetSet`に残る。
**And** profile間でtargetまたはsignalを混同しない。

### Scenario: policy source間のbindingが矛盾する

**Given** taxonomyがruleが要求するcategoryを含まない、またはsignal ownerのmetadataがrule/taxonomy bindingと矛盾する。
**When** composerが互換性を検証する。
**Then** `NotReady(IncompatiblePolicyBundle | ContradictorySource)`を返す。
**And** 4入力のidentityとbundle identityを診断可能なprovenanceとして残し、plannerにpartial inputを渡さず、writerを開始しない。

### Scenario: policy sourceが読み取り途中に更新される

**Given** composerがrule/taxonomy/signals/targetsを読む間にpolicy bundleのgenerationまたはcontent digestが変化する。
**When** composerがcross-source consistencyを再検証する。
**Then** accepted owner contractに従って一貫したbundleを再読する、または`NotReady(InconsistentPolicyRead)`を返す。
**And** 異なるbundleから得たinputを混在させず、planner、writer、recovery point作成を開始しない。

## Data and state

Composerはread-onlyであり、Planner inputまたはpolicy dataを永続化しない。current home layout、lock state、revisionの正本は既存どおりLauncher DBであり、recovery storageはIssue #14のapplication moduleが所有する。Composerはcaptureのrevisionをprovenanceとして保持するが、別snapshot、別cache、別DB、または別recovery recordを正本にしない。[5] [6] [10]

ルール、taxonomy、override、package/intent ruleの永続化・migration・backup/restoreは、前提Decisionで決めるownerが所有する。本Issueのacceptance後にそのownerがread-only portを公開するまで、composerはsourceを仮実装しない。profile serialのrestore remappingはorganization run外の既存責務であり、composerはcapture済み`ProfileId`を再割当しない。[5] [7] [11]

## Permissions, privacy, and security

**None。** 本仕様はpermission、network、telemetry、外部分類、usage data collectionを追加しない。production実装はaccepted sourceがlocal-onlyであることを前提とし、raw package name、profile serial、row IDをuser-facing messageへ露出しない。診断は既存のprivacy-safe diagnostics契約に従う。[7] [9]

## Accessibility and localization

Composer自体はUIを持たない。#52は`NotReady`を利用して、ユーザーが理解可能なsafe failure/retry guidanceを表示し、developer diagnosticと分離する。reason codeからの文字列化、TalkBack、focus、font scaling、translationは#52のUI specが所有する。Composerはlocalized textを返さない。[1] [9]

## Acceptance criteria

- [x] **AC-1 — authoritative ownership and identity:** accepted spec/planが`RuleSemantics`、`TaxonomyContract`、`ClassificationSignals`、full-run `TargetSet`の各々について、一つのproduction owner/source、immutable identity（source/version or generation/content digest）、compatibility rule、read failureを明記する。
- [x] **AC-2 — consistent policy cut:** accepted owner contractが4 policy inputを読むatomic policy bundle、shared generation/fence token、又はread-after-validate-retryを定義する。composerはmixed snapshotを`Ready`にできない。
- [x] **AC-3 — one canonical capture:** accepted implementationは既存application capture seamからfresh inputを構成し、UI DB access、second planner、second snapshot sourceを持たない。
- [x] **AC-4 — complete conservation input:** full-run target membershipは全captured itemを一度だけpartitionし、out-of-scope/quiet/private/unavailable/locked itemを無説明に脱落させない。
- [x] **AC-5 — fail closed:** mandatory policy/capture sourceのmissing、unreadable、unsupported、incompatible、contradictory、policy read中のconsistency mismatch、unknown lock、unrepresentable itemはtyped non-write resultとなり、planner/write/recovery pointを開始しない。
- [x] **AC-6 — profile and lock safety:** profile identity、availability、device context、lockをcanonical captureから保持し、unknown lockやunavailable profileをimplicit defaultに変換しない。
- [x] **AC-7 — deterministic composition:** value-equivalent captureと**同一immutable policy bundle identity**はvalue-equivalent `OrganizationInput`又は同じtyped rejectionを返す。同一version/generationが異なるcontentを指してはならない。
- [x] **AC-8 — evidence:** focused unit/contract/integration tests、repository-contract、format、relevant organizer unit tests、debug buildの結果を記録する。layout-dataリスクのPRではindependent auditとsuccessful CI merge gateを揃える。[2] [12]

## Test oracle

| AC | 実装後のevidence |
|---|---|
| AC-1 | 4 ownerのcontract test。各source identity（source/version or generation/content digest）をprovenance/diagnosticから追跡できること、およびaccepted Decision/ADR/specリンク |
| AC-2 | atomic bundle/shared fence又はread-after-validate-retryのcontract test。read中のgeneration/digest変更ではretry又は`InconsistentPolicyRead`となり、mixed inputを返さないこと |
| AC-3 | production adapter instrumentationで`captureCurrent`経由のfresh captureを確認。UI/SQLite型がcomposer public surfaceにない静的shape test |
| AC-4 | target partition fixture: normal、Dock、widget、folder、app pair、legacy、lock、quiet/private/unavailable。同一packageのprofile evidenceは、valid current profileとunresolvable profile serialを組み合わせ、profile identityを越えてfallbackしないことを検証する。 |
| AC-5 | source failure/identity mismatch matrix unit test。各`NotReady`でplanner/writer invocation countが0であること |
| AC-6 | canonical capture→planner input mapping testとreal DB instrumentation。unknown lock/read failureはno-write |
| AC-7 | reordered equal fixtureと**同一immutable bundle identity**のrepeat composeがvalue-equalであること。同一version/generationでcontentが変化するfixtureはreject/retryとなること |
| AC-8 | `spotlessCheck`、organizer JVM tests、debug build、repository-contract、CI `final-status`、independent audit record |

## Decision resolution and completed implementation

Decision Issue [#86](https://github.com/nunu1733/NunuLauncher/issues/86) はclosedであり、ADR-0007が4 inputのowner、immutable identity、consistent policy cut、failure/migration/rollback、profile/target membershipを受諾済みとしている。したがって、以前のD-83-01からD-83-05はすべて解消済みである。[13]

| Former blocker | Accepted resolution | Implemented verification |
|---|---|---|
| Rule/taxonomy owner | Rule Managementのimmutable built-in `OrganizerPolicyBundle`が`v1` projectionsを所有 | bundle digest、supported binding、category setを検証する |
| Signal source | Rule Management override snapshot + integration-owned platform evidence adapter。S3/S4は明示的empty | missing sourceをempty observationへ変換しない |
| Target membership | `full-target-v1`のexactly-once partition、explicit empty additions | unknown/invalid captureはpreserveではなくrejectする |
| Cross-source cut | immutable bundle + A/E1/B/E2 dynamic read + maximum one retry | 二回目も不安定なら`InconsistentPolicyRead` |

**Completion state:** 本仕様は`implemented`である。production implementationは受諾済み正本`plan.md`に従って完了し、focused unit/instrumentation evidence、CI `final-status`、独立high-risk auditにより検証済みである。以後ADR-0007と矛盾するplatform/source limitationを発見した場合は、codeで都合よく解釈せず、#52以降の利用をblockしてfollow-up Decisionを作成する。[2] [13] [14]

## Change history

- 2026-08-19: Issue #83のStage Aレビュー用`proposed`仕様を作成。既存application captureは再利用可能だが、policy source ownership/versioningが未受諾であるためproduction implementationをblockした。
- 2026-08-19: レビュー指摘を反映。4 policy inputのimmutable identity/content digest、cross-source consistency cut、read中更新時のretry/reject、deterministic test oracleを追加し、owning Decision Issue #86を起票した。
- 2026-08-19: Decision #86のclosedおよびADR-0007のacceptedを反映し、statusを`accepted`へ更新。owner/source、v1 target partition、A/E1/B/E2 retry protocol、non-write failureを確定した。正本`plan.md`はレビュー待ちである。
- 2026-08-19: production Rule Management/integration seam、focused JVM tests、production instrumentation、required CI gateを実装し、独立high-risk auditを受諾した。statusを`implemented`へ更新し、AC-1〜AC-8を完了と記録した。[14]

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/83 "Issue #83 — Establish production OrganizationInput sources"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/AGENTS.md "AGENTS.md — Issue/spec driven workflow and layout safety"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/10-pure-organization-planning/spec.md "Spec #10 — Pure organization planning interface"
[4]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/12-deterministic-full-layout-planner-v1/spec.md "Spec #12 — Deterministic full layout planner v1"
[5]: https://github.com/nunu1733/NunuLauncher/blob/main/DESIGN.md "DESIGN.md — Module boundaries and data ownership"
[6]: https://github.com/nunu1733/NunuLauncher/blob/main/lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt "LauncherLayoutAdapter — Canonical production capture"
[7]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/product/category-taxonomy-v1.md "Category Taxonomy v1 — Proposed product research"
[8]: https://github.com/nunu1733/NunuLauncher/blob/main/lawnchair/src/app/lawnchair/flowerpot/Flowerpot.kt "Flowerpot — Existing legacy asset categorization"
[9]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/engineering/organizer-diagnostics.md "Organizer diagnostics contract"
[10]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/adr/0004-organizer-lock-persistence.md "ADR-0004 — Organizer lock persistence"
[11]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/product/item-preservation-policy.md "Item preservation policy — Proposed research"
[12]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/engineering/quality-strategy.md "Quality strategy — High-risk independent evidence"
[13]: https://github.com/nunu1733/NunuLauncher/issues/86 "Issue #86 — Authoritative versioned policy sources decision"
[14]: https://github.com/nunu1733/NunuLauncher/blob/issue-83-stage-a-input-sources/docs/assessment/pr-88-production-organization-input-sources.md "PR #88 — Accepted independent high-risk audit"
