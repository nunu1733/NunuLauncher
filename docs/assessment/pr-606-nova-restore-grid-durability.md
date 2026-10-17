# High-risk audit: PR #606 Nova restore grid durability (complete-grid pref-store ordering)

> Status: accepted
> Audit date: 2026-10-17

- Auditor: 独立session（solo保守）。実装を行ったsessionとは別の、独立したgeneral-purpose agent sessionが本auditを実施した。監査対象headは自sessionでの読み取りにより検証した。
- PR: https://github.com/nunu1733/NunuLauncher/pull/606
- Head SHA: efbea18a09e67206b39eaec236212bb6ca9c5199
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/38998690241 （CI / final-status SUCCESS on this head。`gh run view 38998690241 -R nunu1733/NunuLauncher`をread-onlyで確認: `final-status` ✓成功。高リスクgateのCI要件を満たす）
- Criteria: specs/605-nova-restore-grid-durability/spec.md（AC-1、AC-2、AC-3、AC-4、AC-5）

## Scope

対象diff `38b4818917b2b469d9f1c717cef301d23273a626..efbea18a09e67206b39eaec236212bb6ca9c5199`（`git diff --stat` 実行、4 files / +558 −3）。自sessionでdiff全文を読み、以下を確認した。

- **production**: `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` のみ。complete-grid分岐（`NovaBackupConverter.kt:217-233`）で `gridState.writeToPrefs(context)` → `writeGridToLawnchairPrefs(info, smartspaceEnabled)`（:219）→ `applyConvertedGrid(gridInfo)`（:224、live IDP binding）→ `gridState.writeToPrefs(context, true)` 最後（:233、synchronous commit durability barrier）の順序がspec #168 Behavior 1どおり復元されている。`FileLog.i` marker2件（:217 metadata write、:232 durability barrier）と、:219ペアの`writeGridToLawnchairPrefs`内部の `"Committing converted grid to prefs"` を `Log.i` から `FileLog.i` へ変更（:395-398付近。logcat出力は不変、file copyを追加）。partial-grid `else` 分岐は `writeGridToLawnchairPrefs` のみでspecのnon-goalどおり未変更（:235）。restore-family lease / reload barrier / `restored.db` rename / `cleanUpDatabases` seamへの変更なし。
- **test**: `tests/organizer-instrumentation/app/lawnchair/backup/NovaConverterBoundaryScenarioBase.kt` のみ（+152行、新規classなし）。`assertCommittedGrid()`（:299-326）拡張: (1) `assertGridApplicationTraceOrder()`（:397-422）が**先**に走る順序oracle — metadata → committed values → live binding → barrier の4 FileLog markerをrestore window（`traceBeforeRestore` 以降のappend-only suffix、:424-439）内のindex順で検証。 (2) `assertLawnchairTypedPrefsPersisted()`（:336-385）— lawnchair typed prefs（`PreferenceManager.workspaceColumns/workspaceRows/hotseatColumns`）と `com.android.launcher3.prefs.xml` の persisted XML content readback（`DocumentBuilderFactory`でint keyをparse）。round-4 review対応（head efbea18a09）としてpre-restore dumpの**内容全体**を保持し `after.startsWith(before)` でappend-onlyを検査する実guard（:434-438。旧length-only guardの修正）と、trace-order oracle-firstのassertion順（:320-324）が入っている。
- **docs**: `specs/605-nova-restore-grid-durability/spec.md`（+261）と `plan.md`（+123）。
- runtime書き込み経路: 変更はcomplete-grid分岐の同一SharedPreferences/DeviceGridState書込み群の順序とlawnchair typed-prefs書込みの復元のみ。新キー、新format、新permission、DB migration、新書込み経路なし。`LauncherPrefs`/`DeviceGridState` storesへの書込みは既存 #168 契約の再現。

## Criteria check

参照した正本: `specs/605-nova-restore-grid-durability/spec.md` @ 5c8cd6ba54229fe5a45a8ea1885fe081d9f31e53（`git show 5c8cd6ba54:...` でfrontmatter `status: accepted`（spec / plan とも）を自sessionで確認。plan.mdも同commitでaccepted固定）。spec.md:196-226 の受入条件ごとの確認結果:

- **AC-1（complete-grid経路で3つのpref storeへcommit、return時点でtyped prefs値 + persisted XML、ON/OFF）: 実装・装置は確認。** production diffで `writeGridToLawnchairPrefs` 復元を確認。test装置（`assertLawnchairTypedPrefsPersisted()`:336-385）はtyped pref API読み取りとpersisted XML readbackの両方を持ち、ON（`NovaConverterBoundaryTest`）/ OFF（`NovaConverterBoundarySmartspaceOffTest`）で網羅。PR bodyのGREEN実行証跡（両class）とCI run 38998690241 が本headでのPASSを裏付ける。実行コマンドの即時再実行は本auditでは行わない（CI artifacts + PR body証跡を代用）。
- **AC-2（順序契約がdeterministic order oracleでpinされ、missing-write RED + barrier-first mutation RED の両感受性が証跡化）: 装置はheadで確認。** order oracle（:397-422）は偏順 `metadata < committed < binding < barrier` をassert。assertion順をtrace-oracle-firstに修正済み（:320-324）。append-only guardはround-4指摘を解消する内容guard（:434-438）。PR bodyのRED-1（回帰head: "the DeviceGridState metadata-write marker must be in the restore window"）とmutation RED（"grid-application order must be ... got metadata=95 committed=308 binding=735 barrier=198"）は、**round-4修正後head efbea18a09での上位CI run** の最終確認を本auditが直接検証できたのは該当CI jobの成否（connected restore-capture lane ✓）までであり、RED/mutation系の期待失敗パラメータ（95/308/735/198）は実装sessionの記録値をtranscribeしたものとして注意付きで記載する。GREEN側はCIで独立確認済み。
- **AC-3（mismatch/match: 変換DBがactive、restore windowで.cleanup未削除、live binding一致。既存#168 oracle全PASS）: 装置・CI確認。** `assertCommittedGrid()` のlive binding assertion（:310-320）+ 既存restore-capture lane全classが本headのCI runで✓（organizer-instrumentation-restore-capture-tests 9m6s 成功）。runtime UI復元観測（API 37エミュレータ、`issue526_api37_pixel_9a`: 自己再起動後 `Grid recompute from prefs: rows=6 columns=5 hotseat=5`、`launcher_6_5_5.db` のunmatched削除ログなし、cold start後も同一grid/DB保持）はPR bodyの証跡として記録（本auditはエミュレータを起動しないため直接再現せず、記録の内部一致性のみ確認）。
- **AC-4（custom-layout開始状態 a/b/c）: 装置はspec通り観測課題であり、test装置ではなくruntime観測が正。** PR body の観測記録: (a) 無効ファイル→"Invalid backup file"中断・確認キャンセル→favorites 15件・カスタム配置・grid prefs無しのまま完全不変; (b) Restoreタップ+0.5sのprocess stop→grid書込み前で永続変更ゼロ、+1.0sのstop→持続書込み完了後・reload完了前で変換DB+prefs 6/5/5コミット済み・新process再bind・一般化した全失敗保護の主張なし（specの「observation, not protection claim」どおり）; (c) Gmail をpage 0へ移動したcustom配置からの成功復元→authoritative import。specが課したa/b/cの各観測がPR bodyで確認できる。
- **AC-5（物理API 37実機owner確認 または #605上のowner decision permalink）: 未充足。open item。** 本audit時点で物理実機owner確認もowner decision permalinkも存在しない（PR bodyも「**未実施**: 物理API 37実機でのowner確認」と明言）。spec.md:238-243（Open questions）とspec.md:227-236（Test oracle表 AC-5行）と整合する。本PRでは満たされないことを本auditはそのまま記載する。

## Executed test surface

本audit session自身の実行はread-only検証のみ（gradle build / emulator実行は実施していない。ContributionとしてPR body・CI・repo artifact読み取りで証跡照合）。実装sessionが実行し本auditが記録照合した操作面（PR bodyの証跡表から exact にtranscribe; script存在はrepo上で自session確認済み）:

- `./gradlew spotlessCheck` → PASS（CI check-style jobでも同系checker確認済み）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → PASS（CI build-debug-apk job成功）
- **RED on regression head**: converterをbase版にrevertして `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.backup.NovaConverterBoundaryTest` → **FAIL（期待どおりのRED）**: `"the DeviceGridState metadata-write marker must be in the restore window"`（round-4後のoracle-first assertion順によるmissing-write RED）
- **Barrier-first mutation**: 修正版converterのbarrierをtyped setters前に移動して同じcommand → **FAIL（期待どおりのRED）**: `"grid-application order must be metadata write -> committed values -> live binding -> durability barrier; got metadata=95 committed=308 binding=735 barrier=198"`
- **GREEN**: 同test class 2件（`NovaConverterBoundaryTest` ON / `NovaConverterBoundarySmartspaceOffTest` OFF）をfix head efbea18a09で同command → BUILD SUCCESSFUL（実装session記録。CI run 38998690241で本headのrestore-capture lane全class ✓と一致）
- **Full lane**: `tools/ci/run-restore-capture-instrumentation.sh`（repo実在確認済み: `tools/ci/run-restore-capture-instrumentation.sh`, executable）→ 8 gradle stage BUILD SUCCESSFUL + cross-process pair OK(1 test) x2
- **Runtime UI verification（API 37 emulator, Pixel 9a相当 AVD `issue526_api37_pixel_9a`, 検証後にshutdown済み・実装session記録）**: UI restore grid-mismatch extra-row ON 1回 → 自己再start/cold startでconverted DB/prefs保持、`launcher_6_5_5.db` のunmatched削除logなし; AC-4a invalid/cancel keep state; AC-4b 2時点のmid-restore process stop観測（+0.5s / +1.0s）; AC-4c custom-layout start状態 → authoritative import
- **CI gate（merge要件）**: run 38998690241, final-status SUCCESS on head efbea18a09, trigger branch `issue-605-nova-restore-grid-durability`, event `pull_request`（`gh run view` read-only確認、完了job: changes / check-style / organizer-unit-tests / validate-repo-contract / organizer-instrumentation-restore-capture-tests / build-debug-apk / final-status）

## Findings

1. **AC-5はOPEN（本PRのmerge gateではないが、issue #605 close の必須条件）。** 物理API 37実機でのowner確認、または#605上のemulator等価性owner decision permalinkが必要。どちらの記録も本audit時点で存在しない。本PRは#605をcloseしてはならない（PR bodyも「本PRでは #605 をcloseしない」と宣言）。merge後にissue tracker上でAC-5の判定が残る。
2. **実装review round 5がaudit時点でin-flight。** round 4（comment 6222511793, Changes requested）→ round-4 fixes commit efbea18a09は確認したが、そのfixに対するre-review結果commentは本audit執筆時点で未投稿（直近commentは6222511793）。本audit記録のmerge gate構成は CI final-status（✓on this head）+ 本audit記録であり、round-5 re-reviewの結果次第でPR statusが変わりうる。merge前にround-5の判断をPR上で確認すること。
3. **Phase 1 design review Approve（comment 6221495547, head bc463afc12）とaccepted固定（@ 5c8cd6ba54）は実装commit（18cd347c66）より前に存在することを自sessionで確認** — Phase 1 review対応の順序の正統性は満たされる。
4. **diff自体の実質的問題は本auditでは見つからなかった。** production順序はspec #168 Behavior 1と一致し、`else` 分岐のpartial-grid動作・lease/barrier/rename/cleanup seamは未変更。testのappend-only guardはround-4指摘のtautology（length-only）を実内容比較に修正している。使用しているmarker文字列（`MARKER_LIVE_BINDING = "applyGridInfo: dbFile"`）は `src/com/android/launcher3/InvariantDeviceProfile.java:658` の既存FileLog出力と一致することを確認。FileLogがmirrorするlogcat出力デザインも破壊していない（`Log.i` → `FileLog.i` はmirrorのまま）。
   - 製品影響の微小な注意点（非blocker）: FileLog trace markerは文字列がproduction/testでliteral重複していて、production側を改名するとtest REDがmissing-marker方向で発火する（同PRで同期更新することをtest comment:95-97に明記済み）。FileLog dumpのappend-only性はlane起動ごとのapp data wipeに依存し、FileLog day rotation等が実行形態によって有効な場合はguardが発火して誤検知側のREDになる（test自身がwarnする設計）。どちらもspecのoracle契約の範囲内で、merge阻断とはしない。
5. **他CI lane（db-migration / production-input / shared-writer / exchange-import-ui / onboarding-proposal / category-override / reservation-recovery / method-choice / manual-organization 等）は本CI runでは skipped（変更非対象判定）。** 変更がconverter-only/lane-only/docsであることはdiff実読で確認したため、skippedはrouting契約どおり妥当。merge gateがfinal-status ✓で機械判定済みの点に影響なし。
