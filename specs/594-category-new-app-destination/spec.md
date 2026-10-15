---
issue: "#594"
status: accepted
tier: M
requirements:
  - FR-008
  - NFR-014
  - NFR-010
  - NFR-002
  - NFR-005
  - NFR-009
  - D-015
  - D-013
updated: 2026-10-14
---

# カテゴリ別の新規アプリ配置（既存destination-policy seam上・縮小scope）

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。Launcher DB書込み経路・schema・上流model/loader bridge・queue wire formatの変更を含まない（変更surfaceはfork-owned moduleのみ。§Verification）。

> Risk tier: M — 新しい書込み経路を持たない。新規アプリのINSERTは#497の既存admission内write（`ModelWriter.addPendingInstallForDirectEdit`）をそのまま使い、本機能は既存適用経路の前段（capture時の配置先決定）と選択面を拡張する。高リスクpath一覧（`tools/repo-contract/validate_high_risk_evidence.py`）に触れない。
> 判断の正本: [ADR-0015](../../docs/adr/0015-new-app-destination-policy.md)（accepted）。本specはそのDecision 3/15をsuccessor ADR（[ADR-0017](../../docs/adr/0017-category-new-app-destination.md)）で拡張する。Decision 1/4/5/6/7/8/9/10/11/14は不変。**Decision 11（same-destination / no-move）は維持し、promise経路のruntime到達条件とCategory mode fallback意味論のみADR-0017 Decision 4が明確化する。**
> 受入: **PR #595のmergeを受入eventとし、本specとADR-0017は同時にacceptedとなる**。runtime実装・検証は後続の実装PR（#594の終了条件2/3）が行い、spec/ADR statusの後追い変更は発生しない。
> Phase A根拠: `docs/assessment/issue-509-category-destination-feasibility.md`（Revision 7、対象commit `391ad7eaa0f84f43af67f7101071f91325c998d9`）とowner判断（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-6163435986)、2026-10-14。縮小scope受容）

## Problem

#497により新規アプリを利用者の指定した1フォルダへ追加できるが、カテゴリに応じた複数の既存フォルダへの振り分けはできない。利用者がカテゴリ別にホームを構成している場合、install後の手動振り分けが残る。#509の調査により、capture時分類は実機主要経路（promise session）では成立しないことが確定したが、owner判断により縮小scope（非promise capture＋S2/S5/S1 coverage）での実装が受容された。

## Benchmark

改善する課題: **B1の固定派生scenario「B1-CAT」**（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md) §6のB1（baseline 8操作/操作数3、#497実装後の固定フォルダ結果=追加操作0）とは別会計とする。固定フォルダのB1/B6=0を本機能の改善として二重計上しない）。

- B1-CATの固定scenario: 利用者が2つのカテゴリ（例: GAME→「Games」フォルダ、PRODUCTIVITY→「Work」フォルダ）へmappingを1回設定した後、**manifestで`appCategory`を宣言する**カテゴリ別の新規アプリ2個とmapping外の新規アプリ1個をinstallする（非promise capture経路。adb install等の信頼できないinstaller経由）。対応カテゴリの2個は追加操作0（重み付きコスト0）、mapping外の1個は上流既定（ベースライン動作と同じ）に置かれる。
- 目標値: **mapping設定後の、S2/S5/S1で解決できたカテゴリ新規アプリの追加操作0**（重み付きコスト0・操作数0）。mapping設定は「一度だけの設定コスト」として会計から分離する（NFR-014の固定派生scenarioとして§6/§7に記録する）。
- **coverageの限定（owner判断どおり）**: promise経路（実機主要経路）のinstallは本機能の成功対象外であり、B1-CAT会計にも含めない。設定面でcoverageを明示する。

## Prior art

- AOSP PackageInstallerService（session callbackの可視性filter。createSessionInternalのinstaller名uid check含む）: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/pm/PackageInstallerService.java （確認日 2026-10-04、Revision 5追加確認 2026-10-14。promise経路のruntime到達条件とinstaller偽装不可の根拠。Phase A assessment §3.1/§3.6）
- Smart Launcher Smart Folders: https://docs.smartlauncher.net/products/faq/changelog/5.4 （確認日 2026-10-02。自動分類に基づく全自動追加はADR-0015 AlternativesでRejected。本機能は利用者の明示mapping＋解決不能時の上流既定fallbackであり採用しない）
- Android `LauncherApps.getApplicationInfo`: https://developer.android.com/reference/android/content/pm/LauncherApps#getApplicationInfo(java.lang.String,%20int,%20android.os.UserHandle) （確認日 2026-10-04。分類readのfail-closed契約）

## Outcome

配置先ポリシーでカテゴリモードを選び、カテゴリと既存フォルダの対応（mapping）を明示設定した状態では、上流が追加を決めた新規アプリのうち、capture時点で分類が対応カテゴリへ解決できたものが、利用者の追加操作なしで対応フォルダ内（末尾rank）に置かれる。解決できないアプリ（未mapping・分類不可・対応カテゴリ消失・promise経路）は上流の既定（空きセル）へ置かれ、既存アイテムは後追いで移動しない。capture（enqueue）時に解決を終えて既存の4field `FOLDER` snapshotとして永続化するため、promise→完了で配置が動かず、再flush・process再起動でも同じ配置になる（#497契約の継承）。

## Scope

- **policy選択の拡張**: 既存のポリシー行（[AppDestinationPreference](../../lawnchair/src/app/lawnchair/homeedit/ui/AppDestinationPreference.kt)）の選択肢にカテゴリモードを追加する（既存の「上流の既定／指定フォルダ／追加しない」と排他的な1選択。「追加しない」⇔`pref_add_icon_to_home` OFFの整合、ホーム画面ロック中の無効化は既存のまま）。
- **mapping管理**: 利用者が`CategoryIdentity + profile → 既存フォルダ（favorites行id）`の対応を明示設定する。mapping対象カタログは**capture時に実際に到達するカテゴリ**に限定する（S2のAndroid category 8種〔GAME/MUSIC/VIDEO/PHOTOGRAPHY/SOCIAL/NEWS/MAPS/PRODUCTIVITY〕、S5の`com.google.*`→TOOLS、S1 overrideが存在するuser-definedカテゴリ。Phase A assessment §3.3/§4、実装調査〔`BuiltInOrganizerPolicyBundleSource.kt:22-35`〕）。`OTHER`等capture時に到達しないbuilt-inは露出しない。フォルダ選択は既存のフォルダ選択dialogと同型（Dock上のフォルダ除外、profile注記）。mappingはid基準であり、rename・同名再作成で復活しない。
- **capture時の解決**: `AppDestinationBridge`のresolver（captureDestination）内で、既存優先順位（S1 override → S2 Android category → S5 system/Google。`OrganizationInputComposer.materializeSignals`と同一順序）で分類signalを解決し、mapping照合して既存`FOLDER` snapshotを返す。**captureの責務は「分類→mapping照合→folderIdの復元」までに限定する**: 分類不可・未mapping・mapping記録からfolderIdを復元不能の場合は既存`UPSTREAM` snapshotを返すが、folderIdを復元できた場合は**liveness/profile/layout検証を行わず**既存4field `FOLDER(folderId, userSerial, packageName)` snapshotを作る。folder missing / profile mismatch / Dock / 制約違反の検証は既存のstage-2 planner（`AppDestinationPlanner`）に一本化し、#497のtyped fallback＋one-shot通知契約（ADR-0015 Decision 4/5/7/8）をそのまま使う（capture側での重複validation・新model-read seamを作らない）。分類seamはorganizerの既存source（`CategoryOverrideStoreModule`、`AndroidClassificationSignalSnapshotSource`、`BuiltInOrganizerPolicyBundleSource`）を再利用し、organizer run/snapshot/recoveryは起動しない。解決関数はAndroid-freeな純粋関数としてplanner側（`AppDestinationPlanner.kt`と同じJVM test対象面）に置き、adapterはwire変換のみに保つ（既存分離の継承）。`CategoryIdentity`はcapture前のpolicy stateにのみ留め、`DirectEditContract`へのfield/kind追加は行わない。
- **分類fallbackの記録**: capture時に分類・mappingが解決できなかったことをtypedにFileLogへ記録する（`AppDestination` tag + reasonキー。package名は出力しない）。**設定行での通知は行わない**（分類不能は正常系であり、installのたびの通知は騒音になるため。mapping自体が無効になっている状態は設定行のsummary表示で扱う）。
- **queue wire・書込み経路の変更なし**: `DirectEditContract`の4field snapshot・2kind、`ItemInstallQueue`/`AddWorkspaceItemsTask`/`ModelWriter`/`PersistedItemArray`の差分0。stage-2検証・admission内write・typed fallback・one-shot通知・post-admission bindは#497実装をそのまま使う。
- **successor ADR**: ADR-0015のDecision 3（選択肢）/Decision 15（設定）の拡張と、Decision 11のruntime到達性とCategory mode fallback意味論の明確化（ADR-0017 Decision 4。same-destination / no-move invariant自体は不変）。ADR-0015本文は上書きせず、Change historyへ関連リンクを1行追加する。
- **テスト**: capture解決の純粋関数（分類signal解決→mapping照合→snapshot生成、typed fallback、決定性・冪等性）を既存homeedit JVM gateへ追加する（test-audit適用）。#497の既存JVM/instrumentation testが回帰なく通ることを同じPRで確認する。新規CI laneは作らない。
- **文書**: 後続の実装PR（#594終了条件2/3）で`CONTEXT.md`（「分類フォルダ」語の更新）、successor ADRの実装outcome追記、ベンチマーク§6/§7へのB1-CAT記録、`docs/product/requirements.md`のFR-008への注記（implementedの範囲が固定1フォルダ＋カテゴリmappingへ広がったこと。カテゴリ自動分類の品質向上は含まない）を実装結果と同時に更新する（本docs受入PRでは実装前の事実を先取りしない）。

## Non-goals

- install完了後にもう一度振り分ける（二段階配置）。promise段階の分類失敗を通常経路の品質主張に使うこと。
- 新しい分類エンジン・外部分類・使用頻度・AI相談、S3表。
- folder自動作成、タイトル一致・子の多数決・暗黙のfolder成長による対応。
- order 5のplanner既存folder加入、全体整理/増分整理、既存homeの後追い再配置。
- queue wire format・`DirectEditContract`の拡張、新しいkind、`PackageUpdatedTask`への分岐追加。
- 新規CI lane、permission/network追加、schema/migration、Undo/recovery protocolの変更。
- `app.lawnchair.deck`の調査と並行分類機構の追加（AGENTS.md設計規約）。

## Behavior scenarios

### Scenario: mappingしたカテゴリの新規アプリが対応フォルダへ置かれる（B1-CAT）

Given カテゴリモードでGAME→フォルダG、PRODUCTIVITY→フォルダWのmappingが設定されている。`pref_add_icon_to_home`は有効
When manifestで`appCategory="game"`を宣言する新規アプリをinstallする（install reason USER。非promise capture経路）
Then アプリは利用者の追加操作なしでG内の末尾rankに置かれる（追加操作0）
And capture時のsnapshotは既存4field形式の`FOLDER`（Gのid）であり、書込みは#497の既存admission内INSERT 1行である
And 既存子のrankと`organizerLockState`列は不変である

### Scenario: 分類・mappingが解決できない場合は上流の既定へ静かに落ちる

Given カテゴリモードでmappingが設定されている。新規アプリXは`appCategory`を宣言せず、`com.google.*`でもない
When Xのinstallが完了する
Then Xは上流の既定（空きセル）へ置かれる（captureは既存`UPSTREAM` snapshotとなりstock pathを通る）
And 分類fallbackがFileLogへtypedに記録される（package名なし）。設定行の通知は発生しない
And 既存アイテムは移動しない

### Scenario: promise経路のcaptureは分類せず上流既定へ落ちる（予防的fallback契約）

Given カテゴリモードでmappingが設定されている。badging付きtrusted-installer sessionにより、install完了前にpromise captureが発生する（実機主要経路。Phase A assessment §3.3/§3.6）
When captureがinstall完了前に実行される（targetが未installでS2/S5が読めない）
Then captureは解決不能として既存`UPSTREAM` snapshotを永続化し、promise iconは上流の既定（空きセル）へ置かれる
And install完了後の再captureは`alreadyAddedPromiseIcon`で抑止され、配置は動かない（ADR-0015 Decision 10/11の継承。no-move）
And 分類fallbackがFileLogへtypedに記録される

### Scenario: capture後のmapping変更で配置が動かない（再flush決定性の継承）

Given GAME→Gのmappingで新規アプリAの自動追加がqueueに投入された（snapshot=G固定）
When flushの前にmappingをGAME→別フォルダへ変更し、processが死に、再起動後のflushでqueueが消化される
Then AはsnapshotどおりG内へ置かれる（capture後のmapping変更を再読しない。first enqueue wins継承）
And stage-2はGの存在・profile・Dock・制約のみを再検証し、別のカテゴリフォルダへ再選択しない

### Scenario: 対応先フォルダが削除された場合は既存のtyped fallback（stage-2側）

Given GAME→Gのmappingが設定され、かつ新規アプリAがcapture時にmapping照合を通過し`FOLDER`(G) snapshotでqueueに載っている
When flush前にGが削除され、flushでstage-2が実行される
Then stage-2 plannerはGのliveness検証で失敗し、Aは上流の既定（空きセル）へ置かれる（`UpstreamDefault(FOLDER_MISSING)`。既存経路でtyped記録・one-shot通知される。#497契約のまま）
And 同名のフォルダを再作成してもmappingは復活しない（id基準）

### Scenario: capture時点でmapping先フォルダのlivenessは検証しない（責務分離）

Given GAME→Gのmappingが設定され、Gがこの時点で既に削除されている
When 新規アプリAがinstallされ、capture時に分類→mapping照合が成功する（folderId=Gを復元できる）
Then captureはGのlivenessを検証せず、既存4field `FOLDER`(G) snapshotを永続化する（capture側でtyped fallbackを発火させない）
When flushでstage-2が実行される
Then stage-2がGの欠損を検出し、Aは上流の既定へ置かれ、`FOLDER_MISSING`が既存経路で記録・通知される（folder有効性の検証はstage-2に一本化）

### Scenario: 対応先カテゴリがcatalogから消えた場合はfail-closed

Given user-definedカテゴリU→フォルダFのmappingが設定されている
When Uが削除された後に、S1でUに分類されるアプリの再installで自動追加が発生する
Then capture時の解決は失敗し、アプリは上流の既定へ置かれる（誤配送は起こらない。zero新規mapping解決）
And 分類fallbackがFileLogへtypedに記録される

### Scenario: 同一カテゴリ・同一アプリでもmappingはprofileで分離される（正方向）

Given カテゴリモードでカテゴリCについてpersonal profile→フォルダP、work profile→フォルダWのmappingが設定されている。同一package Xがpersonalとworkの両方にinstallされている
When personal側でXの自動追加が発生し、work側でもXの自動追加が発生する
Then personal captureは`FOLDER`(P, personalSerial, X)、work captureは`FOLDER`(W, workSerial, X)をserializeする（mapping keyは`CategoryIdentity + profile`であり、profileをまたいだ値の共有は発生しない）
And 各profileのcaptureは自profileのmappingとuserSerialのみを使う（既存`canonicalProfileId`のuserSerial）

### Scenario: work側にmappingが無い場合、personalのmappingを使わない（negative branch）

Given カテゴリCのmappingはpersonal→Pのみ設定されており、work側にはCのmappingが存在しない
When work profileのXの自動追加が発生する
Then work captureはpersonal側のmapping（P）を使わず、mappingなしとして既存`UPSTREAM` snapshotをserializeする（上流既定配置）
And 分類fallbackがFileLogへtypedに記録される

### Scenario: カテゴリ削除→同名再作成では旧mappingが復活しない（identity基準）

Given user-definedカテゴリU（UUID identity `u:<uuid1>`）→フォルダFのmappingが設定されている
When Uを削除し、**同じ表示名で**新しいuser-definedカテゴリを作成する（新identity `u:<uuid2>`）
Then 旧mapping（`u:<uuid1>`→F）は新カテゴリ（`u:<uuid2>`）にはattachされない（persisted identityは表示名ではなくUUID。ADR-0015 Decision 5のsame-name recreation不採用と同型）
And S1で新カテゴリに分類される再installはmappingなしとして上流既定へ落ちる
And built-inカテゴリもlocalized labelではなく既存のstable identity（`CategoryId`値）で保持する

## Verification

- **B1-CAT実機確認（必須・gating）**: owner確認用のスクリーンショット/録画は**実機で取得**するが、**必須証跡は「captureが発生したこと（queue投入log）」「capture時の4field snapshotの値」「最終DB/layout destination（追加操作0）」であり、動画/スクリーンショットは補助証跡に留める**。B1-CATのinstall手順は、Phase A assessment §3.5で確立したinstall-session手順（`pm install-create --install-reason 4` → write → commit）か、**実行前に`INSTALL_REASON_USER`成立を同等に保証・確認できる手順**をnormativeとし、destination判定前に`installReason=4 (USER)`を`dumpsys`等でoracle記録する（shell経路の利用自体は問題ではなく、reason成立の保証と記録が要件）。
- **promise経路の実機観察（best-effort・non-gating）**: 実機GMS端末でPlay install→promise iconが上流既定位置へ置かれ、完了後も動かないことの観察を**best-effortで行う**（owner判断どおりimplementation prerequisiteではない。直接観察できなくても実装受入をfailさせない）。Decision 11のsame-destination / no-moveは、promise経路の予防的fallback scenario（`UPSTREAM` snapshot凍結・`alreadyAddedPromiseIcon`抑止）の構造testと既存contractで必須のまま維持する。
- capture解決の純粋関数JVM test（signal解決の優先順位〔S1→S2→S5〕、mapping照合、typed fallback、決定性・冪等性。capture後にmappingとS1 overrideの双方を変更してもserialized destinationは不変、category rename不変。**実際のmapping codec/resolver→capture seamを通す**。既存homeedit gateへ追加。test-audit適用をPRへ記録）。
- **work profile分離のJVM test**: 上記の正方向scenario（personal/workで別snapshot）とnegative branch（work側mappingなし→personal mappingを使わず`UPSTREAM`）を、実際のmapping codec/resolver→capture seamで検証する。identityは既存userSerial/`canonicalProfileId`を使い、新profile ID型は追加しない。
- **category identityのJVM test**: rename不変に加え、削除→同名再作成で新identityが生まれ旧mappingがattachされないnegative testを検証する。
- #497の既存test（homeedit JVM gate + 書込み経路harness + queue test）が回帰なく通ること（固定フォルダ/既定/追加しない・fallback・snapshot契約への回帰なし）。
- 書込み経路を追加しないことの確認（diffで触れるpathの列挙。`src/`配下の差分0）と、`python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`の結果をPR本文へreportする（階層M要件）。
- `python3 tools/repo-contract/validate_writer_inventory.py`がPASSすること（新規writer fileなし）。

## Accessibility and localization

- カテゴリモードの選択肢・mapping管理UI・summary・分類fallback説明の文言はすべて文字列リソース由来（`values` + `values-ja`）で、TalkBackで読める。font scalingで崩れない。既存の`AppDestinationPolicyTextKeys`／`AppDestinationSettingsTextTest`のsource-contract契約を延長する。
- フォルダ選択dialogは#448/#497と同じAlertDialog慣行に従い、既存のアクセシビリティ検証と同型の検証を行う。

## Change history

- 2026-10-14: Review round 2対応（ChatGPT、[PR #595 comment](https://github.com/nunu1733/NunuLauncher/pull/595#issuecomment-6164387447)）: 残存中1（旧lifecycle文言の混線）— Scope「文書」を「後続の実装PR（#594終了条件2/3）で実装結果と同時に更新」へ変更（本docs受入PRでの`implemented`範囲の先取りを避ける。前倒し追加は行わない）。round 1の5指摘は全て解消済みと判定された。
- 2026-10-14: Review round 1対応（ChatGPT、[PR #595 comment](https://github.com/nunu1733/NunuLauncher/pull/595#issuecomment-6164162557)）: 高1（lifecycle）— 受入をPR #595 mergeに一本化し、statusをacceptedへ。高2（capture責務分離）— captureは分類→mapping照合→folderId復元までに限定し、folder有効性検証はstage-2に一本化するScope修正とscenario 2件（stage-2側FOLDER_MISSING / capture時liveness不検証）へ分離。中1（B1-CAT手順）— install手順をnormative化（`installReason=4` oracle記録を必須化）、必須証跡をlog/snapshot/DB destinationに固定、promise実機観察をbest-effort・non-gatingとして分離。中2（work profile誤流用）— 正方向＋negative branch scenario 2件を追加し、mapping codec→capture seamでの検証を必須化。中3（category identity削除/再作成）— negative scenarioを追加し、persisted identityの正（UUID/CategoryId値）を明記。
- 2026-10-14: Draft created for #594（#509 owner判断〔縮小scope受容〕を受けて起草。履歴`59aa164a62`のobsolete draftを、review round 1指摘論点〔D11 relation明文化・promise/reflush/category identity/work profile oracle設計・新baseline path:line〕を反映して起草し直したもの）。
