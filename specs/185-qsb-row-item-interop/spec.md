---
issue: "#185"
status: implemented
requirements:
  - FR-002
  - FR-003
  - FR-015
  - NFR-011
risk:
  - layout-data
updated: 2026-08-31
---

# QSB予約行に存在するitemをorganizerが扱えるinterop意味論

## Problem

QSB有効な5x5 gridにNova backupをrestoreすると、first screenのQSB予約行(`(0,0)` から幅 `numSearchContainerColumns`、高さ1)の中にitemが存在するworkspaceが生まれ得る。#172 assessmentで実証された再現では、favorites row 115(DEEP_SHORTCUT)が `screen=0 cell(2,0)` に置かれ、`LauncherLayoutAdapter.captureWorkspaceContext()` が生成する予約矩形(幅5 = 行全体)と重なる。`RowManifestCodec.capture` はこの状態を require でfail-closeし、以後すべてのmanual全体整理が恒久的に `INPUT_NOT_READY / INPUT_READINESS.CAPTURE_INVALID` で終わる。ユーザーはQSB行からitemを手で動かさない限り、organizerを二度と使えない。

この状態は対応する経路から到達可能である。Nova import( converter はsmartspace無効時にcellY shiftを適用しない)やgrid migrationはQSB行内のitemを `favorites` に書き込める。一方、platform loaderの受け入れは `allowWidgetOverlap` preferenceに依存する: `LoaderCursor.checkItemPlacement` はQSB行をoccupiedとmarkした上で、重複itemをpreference値がtrueのときだけ残す。#172再現環境ではitemが複数回のmodel loadを跨いで生存しており(取得DBとlogcatで確認)、loaderが受容している状態でorganizerだけが拒絶している、というinteropの不一致が実在する。

## Outcome

QSB予約行に重なるcaptured itemは、organizerが表現でき、安全に扱える状態になる。captureは重複itemをlosslessなmanifest行として保持したまま `Ready` を返し、plannerは当該itemを予約領域に固定された `Preserved` として扱う(動かさない、予約セルをtargetにしない既存不変条件は不変)。適用・A7検証・recoveryの契約は変更しない。

同時に、compositionと適用はplatformの実際の受け入れに継続的に整合する。loaderが重複itemを削除する設定(`allowWidgetOverlap=false`)で重複が存在する場合、organizerはwrite前に新しい狭いtyped理由で `NotReady` となり、A7/recovery検証を壊す実行を開始しない。さらに、compose時の受容判定がapply前に無効化されても古い計算結果を適用しない: 受容policyはcompose時に一度読まれるだけでなく、apply/recoveryのtransaction内で現在値として読み直され、intended stateに対するacceptance再評価がtyped precondition rejectionで強制される。#172の再現手順は、予約重複itemがあっても入力合成が完了する(またはloader非受容時は狭いtyped理由で失敗する)。

## Decision(本specの中心判断)

Issue #185が挙げた3候補を、以下の証拠に基づいて評価し、**候補1(予約重複itemのpreserved空間への射影)+ platform受容gate** を採用する。判断の正本は [ADR-0010](../../docs/adr/0010-qsb-row-item-overlap-interop.md)(作成を本specの受入条件とする)であり、ADR-0008の「real item overlap は typed non-write failure」の帰結を置換する。

1. **採用: preserved射影 + 受容gate。** 重複itemはrepresentableであり、`Preserved(RESERVED_REGION)` として存在し続ける。loaderが受容している(prefがtrueの)workspaceではorganizerだけが拒絶する現状の不一致を解消し、apply/A7/recoveryの契約を一切変えずに済む。
2. **却下: 予約の狭小化。** 再現された5x5 gridでは `numSearchContainerColumns == numColumns == 5` であり、QSBは行全体を占有する。狭小化は当該ケースでno-opである。さらに `LoaderCursor` は同一のspan(`numSearchContainerColumns`)をload時にmarkするため、capture側だけ狭めると#155の相関再読込削除を再発させる。
3. **却下: import時の拒絶・修復(主fixとして)。** 既にimport済みのworkspaceを修復できず(恒久的失敗が残る)、import時の無黙知な移動は#168のauthoritative import契約とlayout fidelityに反する。producer側の防御は将来の別Issueに分離可能であり、本specのnon-goalとする。

## Scope

- `RowManifestCodec.validateReservations` のitem↔reservation重複requireの撤去。予約自身の不正(重複、不明page、非正のspan、範囲外、予約↔予約重複)は引き続きfail-closedである。
- composer/plannerの分類precedenceの拡張: captured workspace placementがsnapshot内の任意の予約矩形と重なるitemは、全既存reasonに先行して `Preserved(RESERVED_REGION)` に分類される。分類siteは `FullTargetSetMaterializer`(TargetSet role)と `PlanningPlacement.determinePreservation`(disposition)の両方である。
- `PlanningValidation.checkOverlap` の修正: captured itemと予約の重複は拒絶しない。item↔item重複と予約↔予約重複の拒絶は不変である。
- `OrganizationPlanMaterializer` の予約guardの修正: 「plan済みtargetが予約と重なる」拒絶は、targetが当該itemのcaptured placementと同一である場合(その場保存)には適用しない。予約セルへの移動は引き続き `Invalid` である。
- 新しいtyped composition失敗 `InvalidCanonicalCapture(CaptureFailureCategory.RESERVED_OVERLAP)` + `InputCompositionCode.CAPTURE_RESERVED_OVERLAP`(#172のclosed集合に1値追加、16→17)。composerは、予約重複itemが存在し、かつplatform受容が無効である場合にのみこれを返す。受容policyの真実はinjected source(`WorkspaceOverlapToleranceSource`)経由で読み、productionでは `PreferenceManager2.allowWidgetOverlap` に接続する。composer自身はAndroid stateを読まない。
- **acceptance predicateの単一正本化(non-blocking指摘への対応):** 予約重複itemの受容判定をorganizer内にbooleanとして複製せず、1つの共通predicateとして定義し、composerの受容gate・A5 precondition・recovery再評価のすべてで同一predicateを呼ぶ。predicateは「現在の受容policyの真実」と対象セルの関係のみを入力とする。可能であれば `LoaderCursor.checkItemPlacement` のQSB行重複分岐からも同一predicateを参照する(LoaderCursorは既にLawnchair preference層へ依存するため配置は可能。Launcher3 bridgeはこの1箇所に限定し、Issue番号と理由を近傍文書に残す)。compile配置上の制約で共通参照が不可能な場合は、organizer判定と `LoaderCursor` acceptance ruleの一致を検証するcontract testを必須とし、この判断と理由をADR-0010に記録する。
- **受容policyのstaleness保証(Blocking 1):** 受容policyはcompose時に1回読まれるだけでなく、A5(apply開始時のtransaction内)とrecovery適用時に現在値として読み直され、**intended state(通常applyのintended manifest、recoveryではrecovery target)が予約重複desktop rowを含む場合は現在の受容が必須**というacceptance predicateとして再評価される。predicateが満たされない場合、既存の `STALE_REVISION` / exact precondition検査に加えてtyped `PreconditionFailed` でno-writeとする。「pref変更が必ず先にworkspace revisionを変える」という未保証の仮定(pref反転の `onSet` はgrid reloadを伴うが、LayoutStateが変わらない限りrevisionは不変であり得る)に依存しない。policy値自体はrevision/digest/recovery formatに含めず、state-based predicateで閉じる。
- **recovery/apply時の安全条件(Blocking 2):** revision不一致時のrecoveryは既存契約どおりpre-state完全一致のdigest比較で行われ、「placementが同じだから継続」という緩和は導入しない。plan materializationの「target == captured placementなら予約guardを免除」はplanner出力に対するplan時guardに限られ、apply/recoveryのexact precondition(item identity、container/screen、cell/span、行contentの完全一致)を一切緩和しない。同一座標・別IDのitem入替、capture後の新規occupant追加、container/screen状態の変化は、既存のrevision/precondition検証が担当する。
- 新しいuser-facing copy: `PreserveReason.RESERVED_REGION` の件数表示用string(en + `values-ja`)。
- [ADR-0010](../../docs/adr/0010-qsb-row-item-overlap-interop.md) の新規作成、ADR-0008への置換注記、[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §5のclosed集合更新。

## Non-goals

- #172所有のdiagnostics表面(journal/export/logcat契約)の変更。新codeの追加は#172が既に許可する「新定数追加はschema変更なし」規定の適用である。
- loaderの重複削除挙動、`allowWidgetOverlap` preferenceの意味、`LoaderCursor` の一般sanitationの変更(#155 AC-08の不変条件を維持する)。
- Nova converterやgrid migrationでのimport時修復・拒絶(将来の別Issue)。
- 予約重複itemを movable として扱いorganizerが移動させるsemantics(より大きなplanner product判断であり、将来の拡張候補としてADR-0010のalternativesに記録する)。
- 予約領域geometry、one-shot capture snapshot、recovery format v2、revision/digest計算の変更。`LayoutState`、`PersistenceManifest`、context resourceのshapeは不変であり、recovery互換性に影響しない。

## Domain language

- **予約重複item (reservation-overlapping item)**: captured workspace placementが同一capture snapshotの予約矩形とセル重複するdesktop item。ユーザー向け語ではなく、planner入力上の分類状態である。`CONTEXT.md` への追加は不要(既存の予約済みworkspace region概念の派生語)。

## Behavior scenarios

### Scenario: 5x5 + QSB行内itemのworkspaceで入力合成が完了する

**Given** 5x5 grid、QSB有効、favoritesに `screen=0 cell(2,0)` のDEEP_SHORTCUT行が存在し、platform受容が有効(`allowWidgetOverlap=true`)である。
**When** manual full organizationのstartが実行される。
**Then** captureは例外を投げず、manifestに当該行をlosslessに含めた `Ready` を返す。
**And** composerは `Ready` を返し、当該itemは `Preserved(RESERVED_REGION)` に分類される。
**And** plannerの全targetは予約セルと重ならず、当該itemのtargetは現在位置のままである。
**And** 適用では当該行への実質的な変更がなく、A7 recaptureがintended manifestと一致する。

### Scenario: platform受容が無効で予約重複itemが存在する

**Given** 予約重複itemが存在し、`allowWidgetOverlap=false` である。
**When** composerが入力を構成する。
**Then** `NotReady(InvalidCanonicalCapture(RESERVED_OVERLAP))` が返り、journal理由コードは `CAPTURE_RESERVED_OVERLAP` である。
**And** planner、checkpoint、apply、recovery point、model reload、Launcher DB mutationはすべて0回である。
**And** capture例外は発生せず、debug logcatのcapture-failure行も出ない(captureは成功している)。

### Scenario: 予約重複itemが存在しない場合のpreference非依存性

**Given** QSBは有効だが予約行にitemがなく、`allowWidgetOverlap` の値は任意である。
**When** composerが入力を構成する。
**Then** `Ready` が返り、受容sourceの値は挙動に影響しない。

### Scenario: 予約重複itemを含むrunでのno-op全体整理

**Given** scenario 1と同じworkspaceでcomposerが `Ready` である。
**When** 全体整理が実行される。
**Then** 当該itemは移動対象にならず、他に変更対象がなければ結果は既存契約どおりのno-change系outcomeである。
**And** 同じworkspaceに対する再実行は同じplan冪等性を保つ。

### Scenario: planがitemを予約セルへ移動させようとする

**Given** 任意のcaptured itemのplan済みtargetが予約矩形と重なり、targetがそのitemのcaptured placementと異なる。
**When** plan materializationが実行される。
**Then** `Result.Invalid` となり、application protocolへ到達しない。このguardは本specでも緩和されない。

### Scenario: 受容無効環境でrecovery targetが予約重複行を含む

**Given** recovery target(pre-state)に予約重複行が含まれ、現在の受容policyが無効である(またはcapture時から反転している)。
**When** recovery適用が評価される。
**Then** 既存のpre-state完全一致判定に加えて受容policyの再評価が行われ、不一致ならtyped failureでno-writeとする。
**And** recovery pointは消費・削除されず、削除済みitemの復活・移動も行われない。
**And** 受容policyが元に戻るまでrecoveryはunavailableであり、Launcher layoutは変更されない。

### Scenario: 同一座標での別item入替をrecoveryが復元しない

**Given** capture後に元itemが削除され、同一座標に別のitem IDで再作成されている(revision不一致)。
**When** recovery適用が評価される。
**Then** 既存のexact一致判定(item identity、container/screen、cell/span、行content)が不一致と判定し、placement一致のみでは継続しない。

### Scenario: 予約自身が不正である

**Given** 予約が重複、不明page参照、非正span、範囲外、または予約同士の重複を含む。
**When** captureが実行される。
**Then** 従来どおりfail-closedとなり、composerは既存の `InvalidCanonicalCapture` 経路でtyped non-writeを返す。#83のfail-closed保証は不変である。

### Scenario: 確認前に受容policyが反転する

**Given** previewまで作成済みのrunがあり、compose時は受容が有効で予約重複itemが `Preserved(RESERVED_REGION)` に分類されている。
**When** confirm/A5内のtransaction再読取の前に `allowWidgetOverlap` がfalseへ反転する。
**Then** A5は現在の受容policyに対するacceptance再評価でintended stateの予約重複行を受け入れられないことを検出し、typed `PreconditionFailed` でno-writeとする。
**And** 古い計算結果は適用されず、予約重複itemのsilent drop / relocationも発生しない。
**And** UIは既存のrecapture/replan flowを提示する。

### Scenario: 受容無効が継続する環境でのretry挙動

**Given** 受容が無効で予約重複itemが存在し、composeが `NotReady(CAPTURE_RESERVED_OVERLAP)` を返す状態が続いている。
**When** ユーザーが繰り返しretryする。
**Then** 同じtyped理由で即座に失敗し、planner/apply/recoveryは実行されない。自動retryやbusy loopは発生しない。
**And** NotReady画面のcopyは#172のbug-report区分であり、近い将来の成功を予告しない。retryは常にユーザー起点である。
**And** 受容policyの有効化や重複itemの削除というmeaningful changeの後のretryは、通常のcompose経路で再評価される。

### Scenario: 重複itemがapply前にloaderへ削除される

**Given** compose時は受容が有効であったが、その後workspace再読込でloaderが予約重複itemを削除した(revision不一致)。
**When** A5が実行される。
**Then** 既存の `STALE_REVISION` でno-writeとなる。削除済みitemを復活させたり、受容緩和を前提に再計算を省略したりしない。

## Data and state

- 永続化の追加はnone。`LayoutState` / `PersistenceManifest` / context resource / recovery v2 recordのshapeは不変であり、schema・format migration、backup/restore、downgradeへの影響はない。受容policyはrevision/digest計算に含めない(ADR-0010に記録。含めた場合、capture時のcapture時点のpolicy値がdigestに混入し、pending recovery pointのpre/post digest一致判定がbuild updateを跨いで破綻するため)。
- captureは既存どおり `favorites` を正本として読み、予約重複行もlosslessに保持する。organizerは当該行を移動・削除・書換えしない。
- 受容policyの読取は2箇所: compose時に1回(injected source経由、1 attemptで1回)、およびA5/recovery適用時のtransaction内で1回(現在値の再読)。policy値自体は永続化せず、毎回sourceから読む。
- journal/exportは#172契約のまま。`InputCompositionCode` の定数追加と `PreWriteRejection` の新値は§4.1/§5のversioning規定に従い、旧buildが新journalを読むdowngradeでは既存のcorruption isolation(journal全体reset、sequence保持)が適用される。
- layout DBの安全性規約(AGENTS.md)との整合: 予約重複行は「保持」に分類される配置アイテムであり、適用・recoveryは既存のtransaction/recovery point/re検証契約のままである。

## Permissions, privacy, and security

**None。** 新しいpref読取は端末内の既存Launcher preferenceであり、permission・network・telemetryは追加しない。新理由コード・新PreserveReasonは既存のprivacy-safe diagnostics契約(closed code、座標・package名を出さない)に従う。

## Accessibility and localization

- 新しいpreserved理由の件数表示は既存のsummary list文脈( TalkBack対象のsummary text)に従い、`values` と `values-ja` の両方に追加する([spec #161](../161-japanese-ui-copy-lqa/spec.md)のcopy規約)。
- `RESERVED_OVERLAP` のNotReady画面copyは既存のbug-report区分(#172のAC-4 split)を流用し、新区分を作らない。既存のFocusTargetText/liveRegion/retry操作は不変である。

## Acceptance criteria

- [ ] **AC-1 — 決定の記録:** 予約↔item重複のinterop意味論(採用候補、却下候補と理由)が [ADR-0010](../../docs/adr/0010-qsb-row-item-overlap-interop.md) に記録され、ADR-0008に置換関係が注記されている。本specのDecision節と矛盾しない。
- [ ] **AC-2 — 再現workspace形状の回帰:** 5x5 grid + `screen=0 cell(2,0)` のitem + `GridSpan(numColumns,1)` 予約のfixtureで、(a) captureが `Ready` でmanifestがlossless、(b) 受容有効時にcomposeが `Ready` でitemが `Preserved(RESERVED_REGION)`、(c) 適用で当該行が不変かつA7が一致、の3点が自動testで検証される。
- [ ] **AC-3 — #172再現手順の解消:** #172 assessmentの再現手順(5x5 Nova restore後のmanual organizer起動)が、emulator上で入力合成を完了する(または受容無効時は `CAPTURE_RESERVED_OVERLAP` でtyped失敗する)。結果をassessment記録として残す。
- [ ] **AC-4 — 予約不変条件の非弱化:** 予約セルへの新規移動target、予約自身の不正geometry、item↔item重複、予約↔予約重複は引き続きtyped拒絶である。recoveryによるpre-state完全復元(予約重複行を含む)が従来どおり検証を通る。
- [ ] **AC-5 — 受容gate:** 受容sourceの値と重複itemの有無の組合せ行列(重複+有効→Ready / 重複+無効→`CAPTURE_RESERVED_OVERLAP` / 重複なし→不変)がunit testで検証される。composerはAndroid stateを直接読まない。`NotReady` はユーザー起点のretryでのみ再評価され、自動retry・busy loop・silent drop / relocationが存在しないことがtestで検証される。
- [ ] **AC-6 — 既存表面の無回帰:** 既存composer/planner/capture/apply/recoveryのunit・contract・instrumentation testが、予約重複を含まない全fixtureで無変更相当の結果を保つ。新codeは `validCodesForFamily(INPUT_READINESS)` とdiagnostics契約§5に追加される。
- [ ] **AC-7 — copy:** `PreserveReason.RESERVED_REGION` の件数copyがen/jaで提供され、既存summary UIで表示される。
- [ ] **AC-8 — 受容policyのstalenessとrecovery安全(Blocking 1/2):** A5とrecovery適用が、transaction内で現在の受容policyを読み直し、intended stateが予約重複desktop rowを含む場合は受容を要求するpredicateで再評価すること。反転ケース(受容有効で計算→apply前に無効化)がtyped no-writeになり、recoveryが削除済みitemを復活させず、placement一致のみで古い判定を継続しないことがtestで検証される。重複行を含まないintended stateはpolicy反転の影響を受けない。
- [ ] **AC-9 — acceptance一致保証(non-blocking):** organizerのQSB重複受容判定と `LoaderCursor` のacceptance ruleが同一であることが、共通predicateの参照(bridge)または等価contract testのいずれかで保証され、選択した方式がADR-0010に記録される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | ADR-0010本体とADR-0008のchange history注記 |
| AC-2 | `RowManifestCodec` 系fixture(capture/manifest)、`OrganizationInputComposerTest`(分類)、planner seam test(非重複target + preserved disposition)、materializer test(その場保存guard)、apply/A7系unit test(行不変) |
| AC-3 | emulator `nunu_qpr2_api36_1` での再現手順実行記録(`docs/assessment/issue-185-qsb-row-interop.md`)。既存E2E(`ManualOrganizationProductionE2EInstrumentationTest` 系列)に5x5 + QSB行itemのcaseを追加するか、同等のfocused instrumentationで証明する |
| AC-4 | materializer/planner validationのnegative test群。recovery round trip test(予約重複行を含むpre-state) |
| AC-5 | composer unit testの組み合わせ行列。受容sourceはfake注入で検証。retry挙動はManualOrganizationRun系unit testで検証 |
| AC-6 | 既存test群の実行 + `ModelValidationTest` / journal系testのclosed集合更新 + `spotlessCheck` + `assembleLawnWithQuickstepGithubDebug` |
| AC-7 | string resourceのen/ja確認 + summary UI compose test(既存patternの拡張) |
| AC-8 | adapter/unit seamでのpolicy反転test(計算後の無効化→typed no-write、重複行なし→影響なし)、recovery fixture(重複行を含むtarget + 受容無効→no-write/recovery point保持)、同一座標・別ID入替fixture(exact precondition不一致)。既存 `preconditionsHold` / `RecoveryWriteSetMaterializer` 契約の無変更確認を含む |
| AC-9 | 共通predicate unit test(bridge参照時)またはLoaderCursor↔organizer等価contract test。選択方式のADR-0010記録 |

## Open questions

なし。採用semanticsの前提となる事実は次のとおり確認済みである:

- `LoaderCursor.checkItemPlacement` はQSB行を `numSearchContainerColumns × 1` でmarkし、重複itemの存続を `allowWidgetOverlap` で決める(既定false)。
- #172再現では同一itemが複数回のmodel loadを跨いでDBに生存した(logcatにoverlap削除なし)。再現環境のpref値は確認不能だが、受容gateにより両値で挙動が定義されるため、決定はこの残余不確実性に依存しない。
- `InvariantDeviceProfile.java:415-416` のとおり非multi-displayでは `numSearchContainerColumns == numHotseatColumns` であり、5x5では行全体である。
- `allowWidgetOverlap` の `onSet` はgrid reloadを伴うが、LayoutStateが変化しない限りrevisionは不変であり得るため、policy反転の検出をrevisionに依存させない(Blocking 1への対応はA5/recovery時のpredicate再評価で閉じる)。

## Change history

- 2026-08-31: #172 AC-3 assessment([docs/assessment/issue-172-input-unavailable-diagnostics.md](../../docs/assessment/issue-172-input-unavailable-diagnostics.md))のroot cause分析とIssue #185本文に基づきdraftを作成。loader受容(`allowWidgetOverlap`)の証拠を追加調査し、3候補の評価と受容gateを含むDecisionを確定した。
- 2026-09-01: Issue #185のreview(Changes requested)に対応。(1) Blocking 1: 受容policyの反転をA5/recovery時のstate-based acceptance predicate再評価で検出するよう変更(revision/digest埋込みはrecovery互換性を壊すため不採用)。(2) Blocking 2: plan materializationのその場保存免除をplan時guardに限定し、apply/recoveryのexact preconditionと「placement一致では継続しない」ことを明文化、recovery targetの受容再評価シナリオを追加。(3) Non-blocking: 共通acceptance predicate(可能なら `LoaderCursor` からも参照するbridge、不可能なら等価contract test)をscope化しAC-9として追加。retry/busy-loop挙動と重複行なし空間への影響限定を明記。
- 2026-09-01: 実装完了(reviewは指摘なしで承認済み)。statusを `implemented` へ更新。実装はADR-0010のcontract-test方式(bridge不採用)を採用。検証の詳細は [docs/assessment/issue-185-qsb-row-interop.md](../../docs/assessment/issue-185-qsb-row-interop.md) を参照(実機AC-3再現の解消、journal `preservedByReason: {RESERVED_REGION: 1}`、16/16 + 12/12 instrumentation、797 unit tests)。
