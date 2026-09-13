# Implementation Plan: Nova restore後のOrganizer captureがCAPTURE_INVALIDに恒常化しない

> Issue: #299
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

対象baseline: `origin/main` = `37e3dd8feb9240e90587620e8175330b48604e19`
（2026-09-13取得・再確認）。初版作成時baselineは `f9afd8bfde`（2026-09-12取得）
であった。その後mainは29 commit進んだが、`f9afd8bfde..37e3dd8feb` の差分で
capture pathの本体
（[RowManifestCodec.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt)、
[LauncherLayoutAdapter.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt)、
[NovaBackupConverter.kt](../../lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt)、
[RestoreDbTask.java](../../src/com/android/launcher3/provider/RestoreDbTask.java)、
[OrganizationInputComposer.kt](../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt)、
diagnostics module）は無変更であり、同区間のorganizer領域の変更は #287 の
lock authoring修正（`LockAuthoring.kt`、planning側）と #304 のspec/assessment
docs追加のみである。観測build `d0f40446c7` は現baselineの祖先であり、
`d0f40446c7..f9afd8bfde` の差分でcomposerに #228 追加（selection stale gate等）
があるが、capture失敗 → `CAPTURE_INVALID` の導出は不変である。

### 記録済みのruntime証拠（事実 — Issue #299本文、2026-09-12T05:47:43Z作成）

- 9/9のOrganizer runが `INPUT_NOT_READY` / `CAPTURE_INVALID` で失敗。
- 4回のapp process置換と5分超を跨いで持続。ZIP restore、Nova再restore、
  process再起動で不回復。
- debug capture diagnosticsが一貫して
  `phase=CAPTURE exceptionClass=IllegalArgumentException` を報告。
- 失敗はstrategy planningより前（数十ミリ秒、capture直後）。
- 同日同buildの同様のNova restore手順の他セッションは成功（セッション依存
  trigger、ただし一旦到達すると持続）。
- 同一セッションで #298 のwrong-thread signature
  （`Cache accessed on wrong thread` 等）も観測。因果関係は未確立。

### 現在のmainで確認したcode path（2026-09-12に実読、事実）

1. **`CAPTURE_INVALID` の導出点は1か所。**
   [OrganizationInputComposer.kt](../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt)
   :143-148 — `captureSource.capture()` が非Readyのとき
   `NotReady(InvalidCanonicalCapture(CAPTURE_UNAVAILABLE), CAPTURE_INVALID)`。
   `UNKNOWN_LOCK`（:149）、`CAPTURE_UNREPRESENTABLE`（:157）、
   `CAPTURE_RESERVED_OVERLAP`（#185、:175）は別codeに分離済み。

2. **production capture sourceはRuntimeExceptionをclassのみに潰す。**
   同file :94-110 — `LayoutWriterCanonicalCaptureSource` が
   `writer.captureCurrent(CaptureId("organization-input"))` を呼び、
   `RuntimeException` をcatchして `CaptureFailureObserver.onCaptureFailure(f.javaClass)`
   のみを観測し `CanonicalCaptureReadResult.Invalid` を返す。
   observerのproduction実装は
   [LayoutApplicationModule.kt](../../lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt)
   :493-494 → `DiagnosticsLogger.logCaptureFailure`。

3. **debug diagnosticsは `phase=CAPTURE exceptionClass=<SimpleName>` のみ。**
   [DiagnosticsLogger.kt](../../lawnchair/src/app/lawnchair/organizer/diagnostics/logger/DiagnosticsLogger.kt)
   :95-109 — debug build限定、journal側はterminal `INPUT_NOT_READY` +
   `ErrorFamily.INPUT_READINESS, code=CAPTURE_INVALID`
   （[InputReadinessProjection.kt](../../lawnchair/src/app/lawnchair/organizer/diagnostics/projection/InputReadinessProjection.kt)）。
   message/stackはどのsurfaceにも載らない（#172契約）。
   よって観測された `IllegalArgumentException` の出所はdiagnosticsからは
   特定できない — これが本Issueの出発点である。

4. **UI/run側の終点。**
   [ManualOrganizationRun.kt](../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt)
   :423 — compose `NotReady` → `State.InputUnavailable`（terminal）、
   :1017-1031 — terminal `INPUT_NOT_READY` journal。各runは新鮮なcaptureから
   再出発する。`CAPTURE_INVALID` 専用の復旧機構は存在しない。回復は
   「次回run時にDB/状態がcapture-validになっている」ことのみによる。

5. **production capture実体。**
   [LauncherLayoutAdapter.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt)
   :97-131 — `capture()` は (a) `captureWorkspaceContext()`（live IDP +
   `FeatureFlags.topQsbOnFirstScreenEnabled`、reservation =
   first screen cell(0,0) span(numSearchContainerColumns,1)）、(b)
   `dbDesktopPageIds()`（`SELECT DISTINCT SCREEN ... WHERE CONTAINER=DESKTOP`）、
   (c) UserCache/UserManagerによるprofile inventory（:101-107 の
   `requireNotNull` 2か所はIAEを出しうる）、(d)
   `RowManifestCodec.capture(controller.db, ...)`、(e)
   `RevisionCalculator` の順。**captureはleaseを取らず、(b)と(d)は同一
   transactionでも同期もされていない2つの独立queryである。** manual composeは
   module mutexの対象外（LayoutApplicationModule.kt:111-125の契約コメント）。

6. **`RowManifestCodec.capture` 内のrequire群（IAE候補の本体）。**
   [RowManifestCodec.kt](../../lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt)
   - :73 `require(orderedPages ⊇ referencedPages)` — page inventoryが
     favorites参照pageを欠く。(b)と(d)の間でDBが変化すると成立しうる。
   - :90 `require(rows.all { profileId ∈ inventory })` — favoritesが
     権威的user inventoryにないprofileを参照。
   - :137-164 `validateReservations` — 重複/不明page/非正span/範囲外/重複
     reservation。reservationはlive IDP由来（Adapter :160-168）。
   - :232 desktop行の `requireNotNull(screen)`（NULL SCREEN）。
   - :259-261 desktop行の `requireNotNull(rawCell/rawSpan)`（NULL cellX/Y、
     spanX/Y）。
   - :296-297 widget行のprovider/appWidgetId欠落（`appWidgetId=-1` は
     `toPersistentRow` :240 でnull化される）。
   - :370 application行のintent component欠落、:376-380 deep shortcutの
     package/shortcut_id欠落、:398-401 malformed intent。
   - :106 `error("unresolved item reference")` はIllegalStateException
     （IAEではないが同一terminal path）。`CanonicalItemOrder.sortedResolved`
     の null return は production captureでは到達しにくい（全rowが
     PersistentItemを生成するため）。

7. **Nova restore path。**
   [NovaBackupConverter.kt](../../lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt)
   :158-229 — 専用thread `NovaBackupRestore`（#168）上で `BACKUP_RESTORE`
   lease（#58）1個の内側で: staging DB作成（`createRestoredDb` :344-375 →
   `insertNovaItems` :377-484）→ grid prefs書込み + `applyConvertedGrid`
   （Main hop、#168）→ `restored.db` copy →
   `RestoreDbTask.performRestore`（[RestoreDbTask.java](../../src/com/android/launcher3/provider/RestoreDbTask.java)
   :205-240 — `sanitizeDB`（未復元profile行の削除、restored flag付与、
   profile id remap）+ `restoreAppWidgetIdsIfExists`）→
   `reloadAfterRestore`（:273-288 — `forceReload`）。
   `insertNovaItems` はNova DBの値をほぼ生で写す（`appWidgetId=-1` 固定、
   `restored=0` 固定、Nova固有itemType値の混入、intent文字列の生copy、
   folder子行がNova親idを直接参照、cell/screenは復元側で常に非NULL）。
   performRestoreのsanitizeがどの程度これらを正規化するかは、本調査の
   観測対象である（静的には確定させていない）。

8. **#185 / ADR-0010の保護は3層で現存する。** composer の
   `RESERVED_OVERLAP` gate（OrganizationInputComposer.kt:160-180）、Adapterの
   A5内 `overlapAcceptanceHolds`（LauncherLayoutAdapter.kt:313-328, :519-531）、
   codecの `validateReservations`（reservation自身のgeometryのみfail-closed、
   overlap行はrepresentable）。既存coverage:
   [OrganizationInputComposerTest.kt](../../tests/unit/app/lawnchair/organizer/integration/OrganizationInputComposerTest.kt)
   （:101付近の#185 case）、
   [LoaderCursorOverlapAcceptanceContractTest.kt](../../tests/organizer-instrumentation/app/lawnchair/organizer/planning/LoaderCursorOverlapAcceptanceContractTest.kt)、
   [OverlapAcceptanceGateSeamInstrumentationTest.kt](../../tests/organizer-instrumentation/app/lawnchair/organizer/application/OverlapAcceptanceGateSeamInstrumentationTest.kt)。
   本Issueの調査でこの経路の回帰・変種を再確認する（Issueのinvestigation
   point）。

### 静的読解で確定しないこと（未確定 — 本タスクでfix architectureを決めない根拠）

- 上記6のどのrequireが観測障害の本体か。静的にはIAE候補のinventoryしか
  作れない。特に:
  - Nova converterはdesktop行のscreen/cell/spanを常に非NULLで書くため、
    :232/:259-261が成立するには別の書込み経路（Lawnchair ZIP restore、
    platform restore remap、旧DB移行）が必要になる — あるのかは未確認。
  - :90（profile不在）は `sanitizeDB` が未復元profile行を削除するため
    通常は防がれるはずだが、sanitizeが失敗・部分適用だった場合や
    ZIP restoreとの組み合わせでの成立可能性は未確認。
  - widget行はNova converterが `appWidgetId=-1`、`restored=0` で書くため、
    `restoreAppWidgetIdsIfExists` がidを再bindしない場合
    :296-297が成立する。ただしそれではwidgetを含む全Nova restoreが失敗する
    はずであり、「他セッションは成功」という観測と矛盾する。再bindの
    実際の動作は未確認。
  - :73（page inventory不整合）はcapture読み取り窓にrestore/model書込みが
    重なった場合に成立する。#298のreload中断と時間的に相関しうるが、
    因果は未確立。9/9・5分超の持続がこの説明で足りるかも未確認。
    reload中断がloader本来の修復・削除処理（I-4の経路 (c)）を途中で止めて
    部分適用状態を残す可能性も含めて、定点matrixで評価する。
- 観測セッションで実際に復元されたDB内容（privacy上、issue記録には含まれない）。
- emulator上での再現手順と再現率。
- `captureWorkspaceContext` のlive IDP読み取りがgrid切替窓
  （`applyConvertedGrid`、:204のMain hop）で不整合値を取りうるか
  （:137-164のreservation検証を通過する値か）。

## Design

### Investigation plan（root cause確定が最初の成果物）

fix architectureはI-5のdecision gateを通過するまで決定しない。

調査はcapture側のレース仮説に寄せない。restore → loader/reload → captureの
全体経路を対象にし、下記の **state比較matrix** をI-1〜I-4の共通計測軸として
最初から組込む。#298（reload中断が部分適用状態を残す経路）は、相関の後段確認
対象ではなくroot cause切り分けの必須比較軸である。証拠が出るまで両Issueを
mergeしない方針は不変である。

**state比較matrix（成功/失敗セッションで同一手順・同一定点で収集する）**

比較時点（最低5定点）:

1. `RestoreDbTask.performRestore` 直後（sanitizeDB / widget rebind後）
2. `reloadAfterRestore` 開始時
3. 通常reload完了後（I-1で特定したcompletion barrier観測signalの到達時点）
4. 中断されたreload後（#298のwrong-thread障害が観測された場合のみ記録する。
   未観測の場合は `N/A / not observed` として記録し、通常reload完了後と
   同値扱いしない）
5. Organizer capture直前（completion barrier到達後の時点）

各時点で収集するbounded分類（値そのものは記録しない。layout内容を含まない）:

- widget行の件数
- widget IDの妥当性category（`appWidgetId >= 0` / 負値・null）
- widget providerの有無category
- restore flag category（`restored` 値の区分）
- 行削除・正規化の有無（sanitize/restore後に行集合が変化したかの分類）
- pages / profiles / reservationsの整合category（#185のreservation幾何を含む
  codec不変条件ごとの通過/違反区分）

- **I-1: emulator再現の確立とcompletion barrierの特定。** API 36.1 emulator +
  debug build（building guide準拠）でNova restore → Organizer captureを
  繰り返す。影響を受けたbackupのsynthetic等価fixture（privacy配慮の下で
  layout内容を差し替えた同構造backup）を作り、widgets / folder / deep
  shortcut / 複数profile要素を組み替えたmatrixで `phase=CAPTURE` 出力の有無を
  収集する。再現が弱い場合はcapture読み取り窓とrestore/reloadの重叠を意図した
  手順（restore直後の即時organize要求等）で窓を広げる。I-1の各runは
  上記matrixの定点計測を最初から収集する（後付けの再現を要求しない）。
  あわせて、**restoreに対応するmodel reload generationがterminal completionに
  到達したことを観測できるproduction/test signalを特定し、それを本planの
  completion barrierとする**。現行実装では `RestoreDbTask.reloadAfterRestore`
  は `LauncherModel.forceReload()` を呼ぶのみであり
  （RestoreDbTask.java:283-288、LauncherModel.java:314-327）、そのreturnは
  reload完了を意味しない（callbackが無い場合、loaderは次回launcher起動まで
  延期されうる）。したがってrestore APIのreturnや固定sleepをcompletion扱いする
  oracleは禁止する。既存のcorrelated reload generation機構（#152）や
  loader状態が観測点として使えるかをこの時点で評価し、同等signalが
  production/testに存在しない場合は、その設置をfix seam候補の一つとして
  I-5のdecision gateへ持ち上げる。
- **I-2: throw点の特定（CI-AC-01）。** 再現時、capture pathに一時的な
  local調査計測（debug build限定、出荷しない）を入れてthrow点と違反不変条件を
  特定する。調査計測は#172契約の出荷surfaceに載せず、PRから取り除く。
  特定結果（例外種別、file:line、違反したrequire、当該行のDB内容の
  分類 — 値そのものではなく）を `docs/assessment/issue-299-<slug>.md` に
  対象build SHA・取得logの要約・確認日とともに記録する。
- **I-3: 成功セッションとの差分。** 同一手順で成功する場合と失敗する場合の
  stateを、上記5定点 × bounded分類軸で比較し、不変条件差を特定する。
  定点3/5の観測はI-1で特定したcompletion barrier signalを基準にする
  （barrier未定義のままのcapture直前比較をしない）。
  capture直前の1時点だけではなく、restore/reloadの途中経過の差がいつ生まれるか
  を特定する（例: sanitize完了時点で既に差があるか、reload中に生まれるか）。
- **I-4: 持続性とloader修復経路の切り分け。** (a) 復元dataの永続的無効性
  （再restore後も同一row分類が残る）か、(b) capture読み取り窓の世代不整合
  （レース）か、(c) restore後のloader/reloadが本来修復・削除するはずの
  invalid rowの処理が中断され部分適用状態が残る経路かを、process再起動・
  再restore後のrow分類追跡と定点matrixで切り分ける。(c)では、Nova converter
  由来行（`appWidgetId=-1`、Nova固有itemType、folder子行の親参照等）に対して
  `sanitizeDB` / `restoreAppWidgetIdsIfExists` / 通常reloadがどの修復・削除を
  行うはずかを動的に確認し、#298のreload中断がその処理をどこで止めたかを
  評価する。#298との因果判断はこの観測でのみ行い、証拠なしにmergeしない。
- **I-5: #185非回帰確認とdecision gate。** I-2の結果を #185 の保護と突き
  合わせ、回帰/変種か独立障害かを記録する。その上で、修正のseam選択
  （下記候補）とtest戦略を確定する。正規化/拒絶を採用するか否かの決定と、
  CI-AC-08のbounded diagnostic category（正規化/拒絶の採否に依存せず必須）の
  設計をここで確定する。変更困難な判断（復元dataの正規化writeを
  どこが所有するか等）が残る場合はADRの3条件を再確認し、必要ならADRを
  作成する。

### Modules and interfaces（候補 — decision gate後確定）

修正が守るべき既存contract:

- #172: privacy-bounded capture failure diagnostics。例外class simple name
  のみの出荷surface。文脈追加はorganizer diagnostics契約
  ([docs/engineering/organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md))
  のbounded field拡張として行い、message/stack/layout由来textは載せない。
  違反不変条件のbounded categoryの追加はspec CI-AC-08により必須であり、
  正規化/拒絶の採否に依存しない。
- composerの閉じたcode語彙（`InputCompositionCode`）。新codeの追加は
  `InputReadinessProjection` / diagnostics契約 / 既存testへの波及を伴う
  契約変更であり、必要になった場合は本specの更新を先に行う。
- #185 / ADR-0010: `RESERVED_OVERLAP` gate、`overlapAcceptanceHolds`、
  reservation geometry検証。
- #58 / #168: restore serialization・authoritative restore。復元dataの
  正規化writeを行う場合は既存 `BACKUP_RESTORE`/`RESTORE` leaseと
  transactionの内側に置く。
- ホームレイアウト安全規約: DB書込みを伴う修正はrecovery point・transaction・
  適用後再検証を要求する（AGENTS.mdの規約通り）。
- #14 / #152: capture/reloadのgeneration機構。capture読み取りを
  transaction化・lease化する場合でも、既存reload token機構と競合する
  新しい待ち機構を作らない。

候補seam（I-2の結果により選択・絞り込み）:

- **capture読み取りの整合性**: (b) `dbDesktopPageIds` と (d)
  `RowManifestCodec.capture` を単一読み取りtransactionにまとめる等、
  capture内部の複数queryを一貫した世代で読む。レースが本体の場合の第一候補。
  captureは既に読み取り専用であり、書込み側の契約変更を伴わない。
- **復元dataの正規化/拒絶**: Nova converter / `RestoreDbTask` 経路で、
  capture不変条件に適合しない復元行をdeterministicに正規化または拒絶する。
  拒絶の場合、restore UIへの既存の失敗surfac経路を使う。DB writeを伴う
  場合はホームレイアウト安全規約と高リスクgateの対象になる。
- **capture側のtyped分解**: `RowManifestCodec` のrequire群を、captureを
  崩さずに違反行を型付きで報告する投影へ変える（例: 違反行の存在を
  composer語彙へ）。`CAPTURE_INVALID` のままfail-closedしつつ診断identityを
  改善する方向。語彙変更はspec更新を先に行う。
- **bounded diagnostic文脈の追加**: `CaptureFailureObserver` / diagnostics
  契約のbounded fieldとして、違反不変条件のcategory（layout内容を含まない
  列挙値）を運ぶ。

### Data flow

（root cause確定まで確定しない。）現状の関連flow:

```text
Nova restore (NovaBackupRestore thread, BACKUP_RESTORE lease)
  -> restored.db copy -> performRestore (sanitizeDB, widget rebind)
  -> reloadAfterRestore (forceReload)
Organizer run (manual)
  -> composeManualFullOrganizationInput (readinessGate)
  -> LayoutWriterCanonicalCaptureSource.capture()
       -> LauncherLayoutAdapter.capture()   [leaseなし、複数query]
            -> captureWorkspaceContext (live IDP)
            -> dbDesktopPageIds (query 1)
            -> profiles (UserCache/UserManager)
            -> RowManifestCodec.capture (query 2, require群)
  -> Ready / NotReady(CAPTURE_INVALID)
       -> State.InputUnavailable + INPUT_NOT_READY journal
```

## Change set

| Area | Intended change | Why here |
|---|---|---|
| （decision gate前は未確定） | | |
| 候補: `lawnchair/src/app/lawnchair/organizer/application/adapter/` | capture読み取りの一貫世代化、または違反行のtyped投影 | capture不変条件の実装点がここに集約されている |
| 候補: `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` / `src/com/android/launcher3/provider/RestoreDbTask.java` | 復元dataの正規化/拒絶 | 復元data自体が無効な場合の正本fix点 |
| 候補: `lawnchair/src/app/lawnchair/organizer/diagnostics/` | bounded文脈field | #172契約の正本がここにある |
| `specs/299-nova-restore-capture-invalid/` | root cause確定後のspec/plan更新 | 正本の同期 |
| `docs/assessment/issue-299-<slug>.md` | 調査記録（CI-AC-01の証跡） | 調査証跡のrepository追跡先 |

## Migration and recovery

- schema/rule migrationは計画していない。復元data正規化にDB writeが必要に
  なった場合のみ、対象行集合・transaction・recovery point・適用後再検証を
  plan更新で確定させる（ホームレイアウト安全規約の適用を受ける）。
- `CAPTURE_INVALID` からの復旧は現状「次回runの新鮮なcapture」のみであり、
  これを変えない（run間の状態持ち越しを作らない）。修復操作が必要になる
  選択をした場合は、その操作の確認・失敗・復旧UXをspecへ追加する。
- release rollback / downgradeへの影響は想定していない（schema不変を
  前提とする）。schema変更が利益と判明した場合は再計画する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| CI-AC-01 | 調査記録 + throw点の証跡 | 手動調査（emulator debug build）、記録は `docs/assessment/issue-299-<slug>.md` |
| CI-AC-02 | restore → capture回帰。I-1/I-3で特定したcompletion barrier観測signal到達後の最初の権威的captureの成功を検証する（restore API returnや固定sleepをcompletion扱いしない）。可能ならinstrumentation化 | emulator/実機 + `NovaRestoreGridApplicationTest` 系seam拡張 |
| CI-AC-03 | fail-closed契約の維持test | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| CI-AC-04 | #185既存coverageのgreen | 同上 + `organizer-instrumentation-shared-writer-tests` job（`LoaderCursorOverlapAcceptanceContractTest`、`OverlapAcceptanceGateSeamInstrumentationTest`） |
| CI-AC-05 | 追加regressionの実行。seam作成不能の場合は理由と代替device evidence | 追加surfaceのCI gate接続 |
| CI-AC-06 | 繰り返しrestore → capture検証 | emulator/実機での手順と結果をPRに記録 |
| CI-AC-07 | decision gate記録（正規化/拒絶の採否）+ 採用時の振る舞い検証 | assessment doc + 対応test |
| CI-AC-08 | bounded category fieldの実装test（redaction non-containment含む） | organizer diagnostics契約のfixture test拡張 + `organizer-unit-tests` gate |

含めるべき観点: unit（codec fixtureによる各require不変条件の網羅）、
integration（restore → capture、process再起動後）、instrumentation
（実Launcher model / 実DB）、device evidence（restore lifecycle結合のため）。

## Documentation updates

- [ ] spec status/history（root cause確定・fix適用時）
- [ ] `docs/engineering/organizer-diagnostics.md`（CI-AC-08のbounded category field追加 — 必須成果であり、fix実装PRと同期して更新する）
- [ ] CONTEXT.md / DESIGN.md（domain language・system構造変更時のみ。現時点では想定しない）
- [ ] ADR（decision gateで3条件を満たす判断が発生した場合のみ）
- [ ] AGENTS.md（workflow/verified command変更時のみ。想定しない）

## Execution checklist

- [ ] I-1: emulator再現の確立（または再現不能の記録と代替証拠計画）とcompletion barrier観測signalの特定。定点matrix計測を含める。
- [ ] I-2: throw点・違反不変条件の特定とassessment記録（CI-AC-01）。
- [ ] I-3/I-4: 5定点 × bounded分類軸の成功/失敗差分・持続性・loader修復経路の切り分け記録。
- [ ] I-5: #185非回帰確認とdecision gate（正規化/拒絶の採否記録、seam選択、CI-AC-08設計確定、spec/plan更新）。
- [ ] 失敗を再現するtestを先に追加（修正はそれに伴う）。
- [ ] Minimal implementation、migration/recovery検証（該当時）。
- [ ] CI-AC-08のdiagnostics拡張と `docs/engineering/organizer-diagnostics.md` 更新。
- [ ] Full relevant verification（unit / instrumentation / device evidence）。
- [ ] PR evidence、残余risk、未確認範囲の記録。

## Dependencies / blockers / risk

- **#298**: 相関は未確立だが、root cause切り分けの必須比較軸である
  （I-3/I-4の定点matrixに組み込み済み。reload中断がloader修復処理を止めて
  部分適用状態を残す経路 (c) の評価に必須）。証拠が出るまで両Issueをmergeせず、
  依存関係も作らない。修正が同一seam（restore/reload窓の読み書き整合）に
  触れると判明した場合は直列化する。
- **#287**: 独立（grid-change `CAPTURE_UNKNOWN_LOCK`）。調査中に同一capture
  突合点へ到達した場合のみ情報共有。
- **risk**: 実装PRが `organizer/application/**`（高リスクpath一覧）または
  `NovaBackupConverter`/`RestoreDbTask` に触れる場合、`high-risk-gate` の
  独立エビデンス契約が適用される。DB writeを伴う修正は `risk: layout-data`
  labelの対象評価を要求する。
- **privacy**: 影響を受けた実backupの内容は入手・公開しない。fixtureは
  synthetic等価にする。

## Explicitly unverified areas

- どのrequire/不変条件が観測障害の本体か（I-2で確定させる）。
- `sanitizeDB` / `restoreAppWidgetIdsIfExists` がNova converter由来行を
  どこまで正規化するかの動的挙動。
- capture読み取り窓へのrestore/model書込み重叠の実際の生起条件。
- restore pathのreload完了を観測できるproduction/test signalの現存
  （`forceReload()` にcallbackはなく、I-1で確定させる）。
- live IDP読み取りのgrid切替窓での整合性。
- emulator上での再現率と再現手順の安定性。
- 影響を受けたセッションの実backup内容（取得不能、synthetic等価で代替）。
