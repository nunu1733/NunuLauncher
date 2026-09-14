# Assessment: Issue #299 — Nova restore後のOrganizer capture `CAPTURE_INVALID`

Status: `investigation I-1/I-2 complete; I-4完了（中断repair状態のprocess restart / re-restore追跡まで実施）; I-3はcontrolled matrixで履行・intermittent triggerは未確定`（I-5 decision gateは再開decisionを含むgateとして扱う）

Date: 2026-09-13（I-2完了revision）
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/299
Spec: [specs/299-nova-restore-capture-invalid/spec.md](../../specs/299-nova-restore-capture-invalid/spec.md)（accepted）
Plan: [specs/299-nova-restore-capture-invalid/plan.md](../../specs/299-nova-restore-capture-invalid/plan.md)

Evidence harness (committed): `tests/organizer-instrumentation/app/lawnchair/backup/NovaRestoreCaptureTestBase.kt` + `NovaRestoreCaptureControlTest` / `NovaRestoreCaptureWidgetWindowTest` / `NovaRestoreCaptureInterruptedReloadTest` / `NovaRestoreCaptureUnknownProviderTest`。raw logcatはcommitしない（本書の引用はharness matrix行とshipped diagnostics行のみで、いずれも件数・分類・例外class identityのみを含む）。

## I-2 additions（2026-09-13, rev 2 — I-2 review反映）: throw-site直接捕捉とmatrix拡張

### capture throw-siteの直接捕捉（CI-AC-01の本体）

一時的なcapture path instrumentation（debug build限定・未commit・実施後にrevert済み。
`LayoutWriterCanonicalCaptureSource` のcatch節でclass simple nameとstack最上位frameを
debug logcatへ出力）により、repair-point中断が残したinvalid state上でのcapture失敗の
throw点を **直接捕捉**した（run @ 2026-09-13T22:04）:

```text
I299Probe: captureThrow class=IllegalArgumentException
           top=app.lawnchair.organizer.application.adapter.RowManifestCodec.toCanonical:297
```

`RowManifestCodec.kt:297` は
`requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }` である。
計測時の同時観測: widget行 `appWidgetId=-1` 1行（`widgetIdNegative=1 widgetIdValid=0`）、
capture fail-closed、observerへの通知は `IllegalArgumentException.class`。
これで「`appWidgetId=-1` が存在 + IAEが出た → 当該requireがthrow-site」が推論ではなく
直接観測になった。**synthetic issue-representative failureに対するCI-AC-01の
throw-site/invariant特定は完了**（元実機セッションとの同一性の限界は後述）。

### #298相当failure path（repair-point中断）での持続観測

同じく一時的な調査計測（`WorkspaceItemProcessor.processWidget` 冒頭のcrashを
cache-dir marker fileでtoggle、`IllegalStateException` を注入。実施後にrevert済み）で、
修復点がcrashした場合の挙動を観測した。logcatには
**`Desktop items loading interrupted`**（WorkspaceItemProcessorの既存catch行）として
記録された — #298/#287 セッションで観測されたloader中断signatureと同一の行
（両者が最終的にloader interruptionへ到達したことのevidence。同じ原因・同じ
interruption pointだったことのidentityではない）。

観測結果（SimulatedWrongThread scenario、1 process、run @ 2026-09-13T21:48）:

| 時点 | widget行 appWidgetId | restored flag | capture |
|---|---|---|---|
| 定点1（performRestore commit直後、I299Probe） | **-1** | 7 | —（capture不可時点） |
| 定点2（reloadAfterRestore直前、I299Probe） | -1（`modelLoaded=false` を同時記録） | 7 | — |
| 修復点crashのreload generation群の後（cycle1） | **-1（残存）** | 7 | **Invalid（IllegalArgumentException、throw-site上記）** |
| 中断を除去しreload activityをsettle heuristicまで走らせた後（cycle2） | **有効値** | 4 | **Ready** |

I-2から言える範囲（evidence boundary）:

- **repair generationを同じ位置で継続的に中断すれば**、invalid rowと
  `CAPTURE_INVALID` は持続し、**中断を止めれば修復する**（直接証明）。
- 元実機セッションの「process再起動・再restoreでも不回復」をこの機構で
  説明できるというのは、現段階では **有力なmechanism hypothesis**である
  （process restart / re-restoreを跨ぐ追跡は未実施。#298のactual
  wrong-thread呼び出し連鎖も未捕捉。cross-process追跡とactual path特定は
  I-4残務 / #298 scope）。
- **定点1/2の取得**: `performRestore` commit直後（定点1）に既に
  `widgetIdNegative=1 / restored=7` であり、正規化は行われていないことが確定。
  `reloadAfterRestore` 直前（定点2）でも `modelLoaded=false`（reload未完了状態からの
  forceReload dispatch）を記録。定点4相当（中断generation後）はcycle1の観測が該当する。
- これらの計測はplan I-2の「出荷しない調査計測」契約どおり、PRから取り除いた
  （`git checkout`でrevert、一時scenario fileも削除済み。本assessmentの
  引用ログのみが残る）。

### matrix拡張: provider未インストールwidget（削除修復経路）

`NovaRestoreCaptureUnknownProviderTest`（committed harness、存在しないproviderの
widget行）の結果:

| 時点 | widget行 | capture |
|---|---|---|
| settle点前 | `appWidgetId=-1` 1行 | **Invalid（IllegalArgumentException）** |
| settle点後（削除修復完了） | **0行**（`markDeleted` で削除） | **Ready** |

installed provider（bind修復）とunknown provider（削除修復）のどちらの経路でも、
settle点までのcaptureは同一のcodec widget不変条件でfail-closedする。修復の
成否・形態にかかわらず「settle点までの窓が `CAPTURE_INVALID` になる」構造は共通である。

### 複数profile / grid変換窓 — de-scope判定

throw-siteの直接捕捉（上記）により、**synthetic issue-representative failureに
対して**、widget不変条件が実際のthrow点であることが確定した。providerの
bind/delete分岐が同じ不変条件に到達することに加え、この確定により
「profile inventory欠落（:90）」「page inventory不整合（:73）」
「NULL screen/cell/span（:232, :259-261）」「malformed intent（:370-401）」といった
**他のIAE candidateは、synthetic issue-representative failureのcapture失敗点から
除外された**（元実機セッションが同じrequireだったこと自体は未証明 —
「この調査で確定しないこと」節参照）。複数profileとgrid変換窓が新たな
IAE candidateを追加する可能性は、
candidate path（Nova restore → unbound widget行）が既に直接捕捉済みの以上に
優先される根拠がない。よって両項目を本Issueの調査matrixからde-scopeし、
将来I-3で成功/失敗差分が本mechanismで説明できない場合に限りI-2追補として
再開する（plan.mdへ同期済み）。

## I-3/I-4 additions（2026-09-13, rev 2 — I-3/I-4 review反映）: cross-process persistence（standalone再試験）とI-3 contract整理

### 前回のcross-process結論は撤回（レビュー指摘1）

初版（head `033edcd6`）の「Stage B favorites=0 → process death単独ではunbound行は持続せず、
death窓は空workspace/削除修復に帰結」という観測は、**Stage B自身のbase setUpが
workspaceをリセットしていた**ことによるfakeだった（`favorites=0`はsetup生成。markerを
test method内で書くため`@Before`より遅く、かつ`restoreGridToOriginal`+`reloadAndWait`が
repair/deleteを走らせていた）。この結論は全面撤回する。

### 修正したstandalone harnessによる再試験（I-4残務、正しい観測）

`NovaRestoreCaptureCrossProcessStageATest` / `StageBTest` をbase classを継承しない
standaloneへ書き直し、**Stage Bはモデル初期化前にlauncher DBファイルのみをread-only接続で
直接読む**（committed stateのみ、in-flight書き込みやrepairを呼ばない）構成にした
（run @ 2026-09-13T23:26、手動 `install -r` + `am instrument` を2プロセス、A/Bの間にforce-stop）:

| 時点 | widget行 appWidgetId（read-only direct / pre-model-init） |
|---|---|
| Stage A: restore直後のcommitted DB（dbFile `launcher_6_4_4.db`） | **[-1]**（unbound 1行、bound 0） |
| Stage B（新process）: **モデル初期化前** のread-only直接読み | **[-1]**（unboundがprocess deathを生存） |
| Stage B: 新processのreload activityがsettle heuristicへ到達した後 | [-1]→有効値（unbound 0、bound 1 — 修復） |

**確定したI-4の帰結**:

- `performRestore` がcommitした **unbound widget行はprocess deathを跨いで次プロセスへ持続する**
  （新processのモデル初期化前のread-only読みに [-1] が残存。capture条件は無効）。
- 次プロセスのreload activityがsettle heuristicに到達すれば修復（bind）し、その後の
  captureは成立する（generation identityは主張しない）。
  → process restart単独ではunbound行が持続するが、**reload activityがsettleに
  到達すれば解消**する。
- 元実機セッションの「process再起動・再restoreで不回復」は、**再起動後のreloadも
  継続的にsettleに到達できない場合**に本mechanismで説明できる。#298 actual wrong-thread
  pathの再現が持続条件の本体として引き続き未検証（#298 scope）。

### I-4核心: 実際に中断されたrepair状態のprocess restart / re-restore追跡（I-3/I-4 review要求の最小追加試験）

I-2で使用したrepair-point中断（`WorkspaceItemProcessor.processWidget` 冒頭の
marker-toggle crash。一時production patch・未commit・実施後にrevert済み）を
cross-process実験へ組み込んだ（一時scenario、run @ 2026-09-14T00:26、
手動 `am instrument` A2 → force-stop → B2）:

| 時点 | widget行 appWidgetId（read-only direct） |
|---|---|
| Stage A2: **crash marker有**でrestore（repair generationが実際にcrash → `Desktop items loading interrupted`） | **[-1]**（unbound committed、bound 0） |
| Stage B2（新process）: **モデル初期化前**read-only読み | **[-1]**（**中断repairの部分適用状態がprocess deathを生存**） |
| Stage B2: 中断除去 + settle heuristic到達後 | [有効値]（unbound 0、bound 1 — 修復） |
| Stage B2: **re-restore**（元事象の2回目restore相当）直後 | **[-1]**（**CAPTURE_INVALID窓がre-restoreで再open**） |
| Stage B2: re-restore後のsettle heuristic到達後 | [有効値]（再修復） |

これでaccepted I-4が要求した切り分けを直接完了した:

- **(c) 中断されたrepairの部分適用状態は、process restart を跨いで持続する**
  （crashした修復generationが残した [-1] 行が次プロセスのモデル初期化前読みに残存）。
- **re-restoreは状態を「修復」しない** — 新たなunbound行を書き込み、
  CAPTURE_INVALID窓を再openする。**解消は「中断が止まった後のsettle到達reload」のみ**。
  元実機セッションの「ZIP restore・再restoreでも不回復」は、再restoreのたびに窓が
  再生成され、かつ各reloadが継続的にsettleに到達できなかった場合に
  本mechanismで完全に説明できる。
- 持続性の本体は「復元dataの永続的無効性」でも「capture読み取り窓の世代不整合」でもなく、
  **「修復を含むreloadがsettleに到達しない状態の持続」**である（(a)/(b)は本実験系列では
  観測されず、(c)が観測経路として成立）。
- この計測はplan I-2契約どおり一時patch・一時scenarioとして実施し、
  revert・削除済み（本assessmentの引用が証跡）。committed harnessは
  reload非dispatch版（`CrossProcess*Test`）とsettle heuristic版assertionを保持。

実装上の注記: Gradle connectedTestはtest APK再install時にapp dataを消去するため、
cross-process state検証には手動 `install -r` + `am instrument`（A→force-stop→B）が必須。
Stage A/Bはbase classの`LauncherAppState.getInstance`・reload lifecycleを継承しない
standalone classである（これが前fakeの原因排除点）。

### I-3 contractの状態（レビュー指摘2）

Accepted PlanのI-3は「同一手順で成功する場合と失敗する場合」の
5定点 × bounded分類軸比較を要求する。本環境では**同一手順のintermittent failureは
再現できていない**（failureは特定の中断注入に依存させ決定的、というpacketの記述どおり）。
よってI-3は以下のように整理する（Planへの同期は次節）:

- **controlled interruption matrixで履行**した系列: normal success（settle後Ready）、
  意図的repair-point継続中断（Invalid持続）、解除後recovery（Ready）、process death跨ぎ
  （unbound持続→次processのreload activityがsettle heuristicへ到達すれば修復）。成功/失敗のstate差は5定点中4定点
  （restore直後=定点1、モデル初期化前=定点2/5、settle後=定点3、中断後=定点4相当）で
  bounded分類取得済み。profiles=1、desktopPages=2、widget行のid/restored分類で系列を
  区別できた。
- **未履行として残す**: 「同一手順で再現的に揺れる」intermittent triggerそのものの再現と、
  それに伴う成功セッションとの比較。**元のintermittent triggerは未確定**としてI-5へ渡す。
  de-scopeした複数profile / grid変換窓の再開条件もこの trigger 未確定に紐づく。

### 持続メカニズムの表現（レビュー指摘3）

「only if」「持続メカニズムの全貌が確定」といった表現は使わない。現在の証拠範囲は
以下に限定される（I-2 + 本I-4 cross-process実験・中断repair追跡で強化）:

- **repair-point継続中断 → unbound rowと`CAPTURE_INVALID`持続、中断停止→修復**（I-2、決定的）。
- **performRestoreのunbound commitはprocess deathを跨ぎ持続、reload activityの
  settle heuristic到達で修復**（I-4 standalone、決定的）。
- **実際に中断されたrepairの部分適用状態もprocess restartを跨ぎ持続し、
  re-restoreは窓を再openする（修復しない）。解消は中断停止後のsettle到達のみ**
  （I-4中断repair追跡、決定的）。
- 元実機セッションの不回復をこのmechanismで説明するには「各reloadが継続的に
  settleへ到達できない」が必要で、これは #298 actual wrong-thread pathの再現に
  依存する（未実施・#298 scope）。**元の #287/#299 セッションのroot cause候補として
  capture読み取り窓の世代不整合を排除したわけではない** — 排除できるのは
  「今回再現したsynthetic widget-invariant failureの説明としてはcapture
  generation raceが不要」なことまでである（I-2のevidence boundaryを維持）。
  同一手順のintermittent trigger未再現の一点も開いたままである。

### 実装review修正記録（2026-09-14、PR #314 reviewサイクル）

- code-reviewer-1初回review: (1) loggerのinvariant fieldが自由Stringを受ける
  契約境界 → closed enum型のみ受けるAPIへ修正、(2) settle observerの登録解除が
  await正常経路しか保証されないlifecycle leak → finally+idempotent cleanup +
  interrupt対応へ修正。いずれも`b669194b55`で反映、監査人の再検証済み。
- 3回目の再監査でbarrierのre-dispatch経路の残存defectを指摘（cancelled後に
  latch/outcomeがresetされずbusy-spinになる）→ `a49dda3451`でdispatch時に
  latch/outcomeをresetする修正を適用（cancelled→re-dispatch→await経路が
  機能するようになった）。deadline超過時のrestore失敗surfaceは不変。
- Re-review（2026-09-14、`fe04bfb960`対象）の新規Major（callback lifecycle境界）:
  `dispatchRestoreReload`に「`startLoader`後もtokenが現行かつ`mLoaderTask ==
  null`ならcancelledでterminalize」を追加（callback消失でtokenがpendingし続ける
  経路を遮断）、barrierのcancelled後はre-dispatch（絶対deadline）、deadline超過時は
  `cancelRestoreReloadIfCurrent`でpending tokenをidentity付きでclearしてからthrow。
  同期cancel hot-loopはre-dispatch一本化で排除。専用token lifecycle testは
  instrument processのauto-load非決定性で断念。
- Re-review（2026-09-14、`e53ba22cb3`対象）のMajor（inactive-model fallback意味論）:
  `callbacks==0`でのfallback正常returnはCI-AC-02のsuccessful completion契約を
  満たさない（`LawnchairApp.ensureOrganizerStartupReconciliation`がsettings-only
  processでも`startLoaderWithoutCallbacks()`を起動する既存構造があり、
  「capture不能」前提が成立しない）。対応: fallback returnを削除し、
  `dispatchRestoreReload`がempty callback listでも`startLoaderWithoutCallbacks`で
  修復generationを開始、barrierはそのcompletionのみを待つ（completionのみ成功、
  cancelledは無条件re-dispatch、deadline超過throw）。barrier必須化
  （app!=null時は必ずawait）。専用regression `NovaRestoreCaptureNoCallbacksTest`
  （callbacks=0のrestoreでreturn時点unbound行なし+直後のproduction capture
  Ready）を追加しCI laneへ組み込み。barrier経路の検証: callbacks=0は
  NoCallbacksTest、callbacks有はbarrier-settled Control/WidgetWindow/
  UnknownProvider、process death跨ぎはCrossProcess A/B（bound永続）で担保。

### I-5 decision gate 記録（2026-09-14、Phase 1締め時に決定）

証拠（I-1〜I-4 + 本実験）に基づくseam決定:

1. **正規化/拒絶は採用しない（CI-AC-07のgate記録）**。settle heuristicへ到達した
   reloadの後にはrestored workspaceは無効ではない（widget行はbind（有効id）または
   deleteで修復される — I-2/I-4で決定的に観測）。つまりcompletion barrier到達後も
   invalidが残る「genuinely invalid restored data」は存在せず、specが要求する
   「別のfixでCI-AC-02を満たせる場合のみ不採用可」の条件を満たす。採用した場合の
   restore時bind/dropは (#298 hazard) restore threadからのwidget bind、未installに
   よる行喪失（layoutを失わない原則と衝突）を招くため却下。
2. **採用するfix = 2点**（2026-09-14 PR #314 reviewで修正 — 初版のheuristic barrier +
   fail-open timeoutはCI-AC-02契約違反として撤回）:
   - **CI-AC-08（必須）**: capture失敗identityにclosed enumのbounded categoryを追加。
     codecのwidget不変条件違反をtyped例外で投げ、debug logcat行を
     `phase=CAPTURE exceptionClass=X invariant=<CATEGORY>` へ拡張。logger APIは
     closed enum型のみ受ける（自由text進入不能）。
     `docs/engineering/organizer-diagnostics.md` §7/§10/D-11を同期。
   - **CI-AC-02 seam（generation-identity付きrestore completion barrier）**:
     `LauncherModel`にrestore reload completion tokenを実装
     （organizer tokenと同型のidentity + terminal outcome、ただしtokenless
     reload＝修復sanitizeを維持）。`convertAndRestore`は当該generationの
     successful completionのみを成功とし、cancelled/supersededはbounded内で
     再dispatch、deadline超過時はrestore失敗をsurfaceする（throw — ViewModelの
     RestoreFailed経由）。fail-openな成功returnは廃止。
     モデル非活性（callbacks無し）時はreload自体がdispatchされないため
     baseline fallback（`reloadAfterRestore` no-op）とし、次回activation loadが
     修復generationとなる（organizer captureは活性modelを介してのみ可能なため、
     修復前captureはproduction経路から到達不能）。
3. **残余（I-5 gate入力として記録済み、Phase 2では扱わない）**: intermittent trigger
   未確定、#298 actual path未再現、元実機throw-site identity未証明。#298と同一seam
   （restore/reload窓）に触れる変更は本fixに含めない（#298 scope分離維持）。
4. **test戦略**: CI-AC-05はcommitted instrumentation harness
   （`NovaRestoreCapture*Test`群）+ codec/loggerのJVM unit test。CI-AC-06は
   emulator実行記録（本assessment参照）。

### I-5 decision gateへの引き継ぎ事項

I-5はarchitectureを自動選択する段階ではなく、次の未確定事項をgate条件として扱う:

1. **元のintermittent triggerは未確定**（controlled matrixで代替、deviation記録済み）。
   証拠不足と判断した場合、I-3/I-4を再開するdecisionをI-5で明示的に取り得る。
2. **#298 actual wrong-thread pathは未再現**（#298 scope）。I-5のseam選択は
   #298の修正と同一seam（restore/reload窓）に触れうるため、#298の進捗を
   gate入力として確認する。
3. **元実機セッションのthrow-site identityは未証明**（synthetic failureで
   直接捕捉済みに留まる）。CI-AC-08のbounded category設計は
   widget不変条件の区別を含めて設計する。

## Verdict（I-1/I-2で証明できた範囲）

capture側 `IllegalArgumentException` のthrow点
（`RowManifestCodec.kt:297` のwidget不変条件require）を、synthetic
issue-representative failure（repair-point中断が残したinvalid state）上で
**直接捕捉した**（I-2 additions参照）。元の#299実機セッションとの
identityの限界は下記2のとおり残る。

1. **確定した再現経路**: Nova restoreはwidget行を `appWidgetId=-1` で保存する
   （`NovaBackupConverter.insertNovaItems`（:463）の固定値。`RestoreDbTask.sanitizeDB`（:359-365）
   はrestore flag（観測値7 = ID_NOT_VALID|PROVIDER_NOT_READY|UI_NOT_READY）を付与するだけで
   idを再bindせず、`restoreAppWidgetIdsIfExists`（:516-532）はplatform restore用の
   `APP_WIDGET_IDS` prefsが無いNova pathでは実行されない —
   logcat: "Did not receive new app widget id map during Launcher restore"）。
   `toPersistentRow`（RowManifestCodec.kt:240）が負値をnull化し、
   captureは `requireNotNull(row.appWidgetId) { "Widget is missing its appWidgetId" }`
   （:296-297）で `IllegalArgumentException` となる — **このthrow点は一時
   instrumentationで直接捕捉済み**（`top=RowManifestCodec.toCanonical:297`）。
   例外は `LayoutWriterCanonicalCaptureSource`（OrganizationInputComposer.kt:94-110）がcatchして
   `CanonicalCaptureReadResult.Invalid`、composerはterminal `CAPTURE_INVALID` を返す。
   shipped diagnostics（debug build）は
   `OrganizerDiag: phase=CAPTURE exceptionClass=IllegalArgumentException` を出す。
2. **元障害とのidentityの限界**: throw点の直接捕捉はsynthetic
   issue-representative failure上のものである。元の実機セッションに残る
   shipped diagnosticsは例外class identityのみであり、「元のセッションも
   同一のrequireで落ちた」こと自体は推論の域を出ない。ただしthrow-site確定により、
   他のcodec IAE candidate（profile inventory欠落、page inventory不整合、
   NULL座標、malformed intent）は **synthetic issue-representative failureの
   capture失敗点から** 除外された（元実機セッションについての除外ではない）。
3. **修復機構（I-4経路cの実在確認）**: reload generationの内側で
   `WorkspaceItemProcessor.processWidget`（WorkspaceItemProcessor.kt:533-538）が
   `WidgetInflater` によるbind成功後に `APPWIDGET_ID` / `APPWIDGET_PROVIDER` / `RESTORED`
   をDBへ書き戻す（観測: flag 7→4、`appWidgetId` -1→有効値）。providerが
   未インストールでrestore未開始の場合は `markDeleted`（:497-505）で行を削除する。
   つまり**修復（bindまたは削除）はreload generation依存**であることまでは確認できた。
4. **持続性の証拠範囲（I-4中断repair追跡で更新）**: 直接証明できたのは —
   (a) repair generationを同じ位置で継続的に中断すればinvalid rowと
   `CAPTURE_INVALID` は持続し、中断を止めれば修復する、
   (b) `performRestore` がcommitした unbound widget行は process death を跨いで
   持続し（新processのモデル初期化前read-only読みに [-1] 残存）、reload activityが
   settle heuristicへ到達すれば修復される、
   (c) **実際に中断されたrepairの部分適用状態もprocess restartを跨いで持続し、
   re-restoreはunbound行を再書込みしてCAPTURE_INVALID窓を再openする
   （re-restore自体は修復しない）。解消は中断停止後のsettle到達のみ**。
   元障害のpersistence（process再起動・再restoreを跨ぐ恒常化）をこのmechanismで
   説明するには「各reloadが継続的にsettleへ到達できない」が必要で、
   これは #298 actual wrong-thread pathの実在と反復に依存する（#298 scope、未再現）。
   元セッションのroot cause候補としてcapture generation mismatchを排除した
   わけではない（synthetic failureの説明から不要なだけ — I-2 boundary）。
5. **#185非関与（この範囲での観測）**: 全runでreservation検証・`CAPTURE_RESERVED_OVERLAP`
   は発生せず、QSB予約不変条件への回帰・変種の兆候は観測されなかった
   （正式な非回帰確認はI-5で既存coverageのgreenをもって実施）。

## Environment

| | |
|---|---|
| Build | branch `issue-299-spec-plan` @ `cdc217d032` + review反映revision（app code `c502b22189`）、`assembleLawnWithQuickstepGithubDebug` + androidTest |
| Device | AVD `nunu_qpr2_api36_1`（Pixel 6 class, Google APIs, Android 16 / API 36.1, arm64） |
| Fixture | synthetic等価Nova backup（`nova.xml` + `nova.db`。test app自身のcomponentと合成identityのみ。grid=対象AVDの元grid 4列×5行、hotseat 4、widget/provider/deep-shortcut/folder/apps/hotseat要素） |
| Execution | 1 scenario class per `am instrument`（fresh process）。4 scenario classesすべてPASS |

## State matrix（bounded分類 — plan I-1の5定点のうち取得できた定点のみ）

表の「barrier後」はharnessのsettle heuristic到達後を意味する（§Completion barrier参照）。

| 定点 / variant | widget行 | appWidgetId | provider | restored flag | capture |
|---|---|---|---|---|---|
| restore直後（pre-barrier）/ widget有 | 1 | **-1** | あり | 7 | **Invalid（IllegalArgumentException）** |
| completion barrier後 / widget有 | 1 | **有効値** | あり | 4 | **Ready** |
| 中断generation直後 / widget有 | 1 | **-1** | あり | 7 | **Invalid（IllegalArgumentException）** |
| 中断後、reload activityがsettle heuristicへ到達 / widget有 | 1 | **有効値** | あり | 4 | **Ready** |
| barrier後 / widget無（control） | 0 | — | — | — | Ready（items=9）※ |

※ fixtureはdeep shortcut 1行を含むが、loaderのrestore sanitize
（WorkspaceItemProcessor: "removing app that is not restored and not installing"）が
未インストールpackageの行をbarrier generation内でDBから削除するため、
barrier後のcaptureは9行。これも「修復・削除はreload generationの内側で起きる」
ことの観測である（plan I-4 経路c）。profiles=1、desktopPages=2 は全variantで一定。

### 未取得の定点・matrix項目（plan I-1からの未達とI-2移送先）

- 定点1（`performRestore` 直後）/ 定点2（`reloadAfterRestore` 開始時）/ 定点4
  （中断されたreload後・#298実経路相当）は、production flowの内部時点として未取得。
  `convertAndRestore` は atomic に実行されるため、これらはI-2のdebug-only調査計測
  （plan I-2: 出荷しない）での取得対象とする。
- 複数profile要素（matrix要件）、provider未インストールwidgetの削除修復が
  中断されたvariant、grid変換（fixture grid ≠ 元grid）窓： I-2のmatrix拡張対象。
- 1 process内で重なったreload generationの全挙動： generation-agnostic観測では
  判定不能（下記barrier節）。generation identity付き観測の整備後に再計測する。

## Completion barrier（plan I-1必須成果 — 正式性の区別を含む）

- **production signalは現存しない（構造的gapの確認 — 確定した成果）**。
  `RestoreDbTask.reloadAfterRestore` → `LauncherModel.forceReload()`
  （RestoreDbTask.java:283-288, LauncherModel.java:314-327）はcallbackを持たず、
  `convertAndRestore` return時に `isModelLoaded=false` を実測した
  （restore API returnはcompletionではない — plan/specの定義どおり）。
- **bind完了はterminal commitではない（production control flowで確認）**。
  `LauncherModel.LoaderTransaction` は `mLastLoadId++` をconstructor（load開始時）で行い
  （LauncherModel.java:675）、`commit()` は `mModelLoaded = true` のみ
  （:682-686）。`BgDataModel.lastLoadId` は `BaseLauncherBinder.bindWorkspace` 内で
  copyされる（BaseLauncherBinder.java:151,172）ため、terminal commitより前の
  bind時点で更新されうる。したがってbind完了発火も `lastLoadId` も
  「commit済みgeneration数」の信号にならず、本調査で両者から
  「何generationがcommitしたか」を導くことは撤回した。
- **現在のtest barrierは契約gradeのoracleではなく、generation identityを持たない
  調査用settle heuristicである**。harnessのbarrierは「`finishBindingItems` 発火後に
  `LauncherModel.isModelLoaded()` が成立」を条件とするheuristicであり、
  `isModelLoaded()`（`mModelLoaded && mLoaderTask == null && !mModelDestroyed`）は
  観測瞬間にmodelがloadedかつactive loaderが無いという **settled stateの確認**には
  使えるが、generation identityを運ばず、generation数も数えられない。
  **restore自身のgenerationのidentityは主張しない**。I-3定点3/5の判定はこの
  settle heuristicで行い、**generation-level causal attributionには使用しない**。
  CI-AC-02の正式oracleはgeneration identityを運ぶsignalを要求し、それは
  I-5 seam decisionの対象として残る。
- **既存の `forceReloadForOrganizer` はそのままbarrierに流用できないことを確認**
  （「test-onlyでgeneration相関signalが作れない」までは主張しない）。
  lease tokenが非ゼロだとloaderが修復sanitizeをスキップし（LoaderTask.java:291、
  organizer reloadはDB不変を保持する設計）、ゼロだと完了通知が発火しない
  （LoaderTask.java:429）。修復観測とgeneration相関完了を既存機構のままで
  両立させることはできない — この確認が、I-3をsettle heuristicで進め
  production変更をI-5まで持ち越す決定の根拠である。
- **I-5 seam候補（この時点での評価）**: restore pathのreloadを、completion観測付きかつ
  修復sanitizeを保持するgeneration identity付きbarrierへ置くこと
  （`forceReloadForOrganizer` + `OrganizerReloadRequest` tokenのidentity check /
  completed-cancelled区別 / #150境界snapshot deliveryの機構は再利用候補）。
  seam選択はI-5 decision gateで行う。

## Reproduction sequence（harness）

1. fixture zipをcache dirに生成（synthetic等価、privacy-equivalent）。
2. `NovaBackupConverter.parseInfo()` → `convertAndRestore(info)` をproduction pathのまま実行
   （BACKUP_RESTORE lease、staging DB、`applyConvertedGrid`、restored.db copy、
   `performRestore`、`reloadAfterRestore` をすべて通る）。
3. 直後にproduction capture sourceでcapture（pre-barrier観測）。
4. `finishBindingItems` latch + `isModelLoaded` のsettle heuristicで
   settled stateを観測（generation identityは主張しない）。
5. bounded state matrix（件数・分類のみ）をlogcatへ記録。
6. interrupted variantは `quiesceForRestore()` でgenerationを中断してから同様に観測。

## この調査で確定しないこと（I-4実施後 / 残余）

- 元の#299実機セッションが同一のthrow点（`RowManifestCodec:297` widget不変条件）で
  落ちたことの直接証明（元セッションのdiagnosticsは例外class identityのみ。
  throw-site直接捕捉はsynthetic issue-representative failure上のものである）。
- 元実機セッションで「再起動後もreload activityが継続的にsettleへ到達できなかった」
  こと自体の証明（process death跨ぎのunbound持続は確定、解消はsettle heuristicへの到達、
  なので元セッションの不回復は「settleへ到達するreloadが走らなかった」ことに依存 —
  #298 actual pathの実在と反復が要る、#298 scope未再現）。
- 同一手順で再現的に揺れる intermittent trigger そのものの再現（I-3 contractは
  controlled interruption matrixで履行、元のintermittent triggerは未確定 → I-5）。
- #298の **actual** wrong-thread障害（`BaseIconCache.assertWorkerThread` 経由の
  `Cache accessed on wrong thread`、`Can't create handler inside Thread[NovaBackupRestore]`）
  そのものの再現。本調査は修復点crashを #298相当の **中断** として使用し、
  中断signature（`Desktop items loading interrupted`）と後続状態の因果は捕捉したが、
  両者の原因・interruption pointの同一性までは証明されておらず、
  wrong-thread呼び出し連鎖そのものの特定と修正は #298 のscopeである。
- 複数profile、grid変換（fixture grid ≠ 元grid）窓でのcapture読み取り
  （de-scope済み。再開条件は上記「複数profile / grid変換窓」節参照）。
- 影響を受けた実機セッションの実backup内容（privacy上取得不能。本調査はsynthetic等価で再現）。

## Findings

- #172契約のshipped diagnostics行（`phase=CAPTURE exceptionClass=IllegalArgumentException`）が
  harness上で再現され、throw-site（`RowManifestCodec:297` widget不変条件）が
  一時instrumentationで直接捕捉された。CI-AC-08のbounded category（widget不変条件の
  区別）への拡張はI-5後の実装で行う。
- spec CI-AC-02のoracle（completion barrier後の最初の権威的captureが成功）の
  検証surfaceは、正式oracle（production/testのgeneration-correlated barrier）が
  整備されるまで、本harnessの窓検証付き調査barrierで近似する。正式oracleの
  整備場所・形はI-5 decision gateの対象である。
- widget行 `appWidgetId=-1` を含むrestoreは「restore直後〜settle点前」のOrganizer要求に
  対して必ず `CAPTURE_INVALID` を返す。これはfail-closed契約どおりであり、UI側で
  restore直後のOrganizer要求にNotReady系の見せ方をするかどうかは本Issueのscope外
  （必要なら別Issue）。
