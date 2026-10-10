# Issue #545 runtime検証matrix — API 37 Quickstep provider修復（compat対応）

- 実施日: 2026-10-07〜10-08
- accepted spec: `specs/545-api37-quickstep-provider-repair/spec.md`（revision 1: PR #548、
  addendum revision 2〜5: PR #549/#551/#553。Owner decision 1〜13）
- plan: 同dir `plan.md`（revision 5 accepted）
- 実装PR: #550（`issue-545-api37-provider-fix`、base PR #546 `issue-524-api37-quickstep` stack。
  ADR-0018 Decision 3/8によりcutoverまでmergeしない）
- この記録は検証証跡であり、正本の判断はIssue #545 / spec / ADR-0018に従う。

## 総合判定（最終。v6 quick re-verification 2026-10-09）

**v6 candidate `4ddd6ae306` PASS → 確定採用**。残存2系統gapは別Issueで解消済み:
thumbnail描画（`TaskContainer.setState` 式body欠陥。#555 / PR #561 merge）と
recents遷移parcel schema（#554 / PR #557）。quick re-verification（qa3/qb3）で
**bind完了・overview（実app screenshot thumbnail）・task切替・G3遷移完走（BAL 0）・
全signature/marker/parcel 0** を両legで確認したため、`quickstepMaxSdk` 36→37を確定採用し
ADR-0018 revision 9（advertised 35..37）へ反映した。（v5 acceptanceのcandidate FAIL・
36維持判断は下記のとおり記録として保持。）

## 実装commit（実装PR #550。branch `issue-545-api37-provider-fix`、base `e214b7b190`）

| commit | 内容 | spec根拠 |
|---|---|---|
| `416273ce2f` | `InputConsumerController.registerInputConsumer` — API 37+でreturn-InputChannel形式をreflection呼出し | decision 1 |
| `7cc0a8398c` | `TaskbarRecentAppsController` throttle flag読取りにNoSuchFieldError guard | decision 8 |
| `63a8a3a7d5` | `taskbar_phone_size` をliteral 48dpへpin | decision 9 |
| `68e68a6a45` | `KeyButtonRipple` flags-class読取りにNoClassDefFoundError guard | decision 10 |
| `bdea76ea75` | `getTaskThumbnail` をTaskSnapshotManager経由reflectionへ | decision 11 |
| `e77311059e` | `ThumbnailData.makeThumbnail` をwrapToBitmap reflectionへ（black fallback維持） | decision 12 |
| `0c25041285` | `takeTaskThumbnail` をTaskSnapshotManager経由reflectionへ | decision 13 |
| v1〜v5 candidate群（`e2fe6f80df`→revert `6cc8e88725`、`2987e525bd`→revert `cf5fee009f`、`40eb5dbfab`→revert `e7d6b4ce11`、`212097886b`→revert `ca015d173f`、`da15f4b2e7`→revert `6dce6c26e6`） | quickstepMaxSdk 36→37の検証candidate。 **全て検証不成立でdrop/revert。確定採用なし（v6 candidateは下記最終acceptanceで確定採用）** | decision 7 |
| evidence commits（`f5a3977281`/`e70a58c1fd`/`206850b3d7`/`5c6d40a56d`/`fc5169566c`/`1777e348c9`/`65729184c8`/`71c6251203`/`8babd3bb10`/`8108e3a1aa`/`3f31768d04`/`4daa239e0f`） | 検証証跡（diagnostic含む） | 各decision |

## matrix↔実行SHA↔APK対応表（AC-9）

**最終acceptance（v6。現行契約）**

| matrix | 実行SHA | APK（sha256） | 判定 |
|---|---|---|---|
| qa3 (a) API 37 debug＋debug overlay＋priv-app、native density | v6 candidate `4ddd6ae306`（#550 7 fixes＋#555修復＋#554対応統合） | candidate6-debug `7f5d28b1…` | **PASS**: 6 signature 0・3 marker 0・FATAL 0・scoped parcel 0・bind完了・**overview実thumbnail rendering（#555修復）**・task切替成功 |
| qb3 (b)+G3 API 37 release＋release overlay＋priv-app、native density | v6 candidate `4ddd6ae306` | candidate6-release `22da1cba…`（manifest 35/37静的確認済） | **PASS**: 同oracle＋sheet非表示＋**G3遷移完走（BAL 0）**・permanent oracle pass=true（#554 PASS profileと同型） |

**v5 acceptance（diagnostic/superseded。candidate不採用の記録）**

| matrix | 実行SHA | APK（sha256） | 判定 |
|---|---|---|---|
| qva2 (a) API 37 debug＋debug overlay＋priv-app、native density | compat 7修正head `0c25041285` | compat7b-debug-0c25041 `04d048b2…` | PARTIAL: 6 signature 0・3 marker 0・FATAL 0・bind完了・overview起動・task切替成功／thumbnail AC-10d FAIL（黒fallback。root causeは#555の式body欠陥と後に確定） |
| qvb2 (b)+G3 API 37 release＋release overlay＋priv-app、native density | candidate `da15f4b2e7` | candidate5-release `e584e078…` | FAIL: bind・compatible=true・sheet非表示までPASS／AC-10e2 parcel例外再現（SystemUiProxy parcel schema skew。→#554で解消）→G3未達 |

**diagnostic run（superseded。acceptance不使用）**

| run | 実行SHA | APK（sha256） | 役割・結果 |
|---|---|---|---|
| pre-guard diagnostic | `416273ce2f`（reflection単体） | compat-debug-416273c `4c091d8d…` | decision 8/9の根拠。seam oracle成立（createInputConsumer NME 0・receiver生成）・Taskbar破壊発見。wm density 280 accommodation（superseded） |
| post-guard matrix | compat 3修正head `63a8a3a7d5`（debug）／candidate `e2fe6f80df`（release/debug） | compat3 `bed40262…`／candidate2 `166beaea…`/`74adbbca…` | (e) API 36 provider PASS・(f) stock PASS・hiddenapi 0・wmshell 0・48dp pin静的確認／(a)(b)はTaskbar throttle FATALで未達（第4破壊発見） |
| qa/qb diagnostic | `68e68a6a45`（debug）／`40eb5dbfab` candidate（release） | compat4 `489b9f1f…`／candidate3 `04d048b2…`/`6c0e0272…` | 第4修正実証（bind完了）／**第5破壊発見**（overview読み込み中に `getTaskSnapshot(IZ)` NMEでlauncher死亡。task切替未達） |
| qva/qbv diagnostic | `bdea76ea75`（debug）／`212097886b` candidate（release） | compat5 `f9116a72…`／candidate4 `1265c7fc…`/`b701173e…` | 第5修正実証（overview起動・task切替到達）＋**第6破壊発見**（thumbnail黒fallback。`getHardwareBuffer` deprecated-null）／qvbで **第7破壊発見**（`takeTaskSnapshot(IZ)` NME、launcher FATAL x3）＋parcel例外oracle初観測 |

API 36/stock leg（(e)/(f)）のPASS実績: post-guard matrix（`fc5169566c`。実行SHA=candidate `2987e525bd`、
APK `166beaea…`/`74adbbca…`。6修正はAPI 37のTaskbar/thumbnail/take経路のみで変化しないため有効）。

## 確定した事実（一次出力は各contrast/evidence file参照）

1. **bind path修復の成立**: 7件の破壊（createInputConsumer削除、Taskbar throttle flag欠損、
   `taskbar_phone_size` ID shift、KeyButtonRipple flags class欠損、getTaskSnapshot削除、
   getHardwareBuffer廃止、takeTaskSnapshot削除）を修正し、qva2で6 signature 0・3 marker 0・
   FATAL 0・`isConnected=true`まで到達。
2. **thumbnail bitmap修復（#555で解消）**: wrapToBitmap分岐は正常にflowしており、bitmapは
   card描画経路へ届いていた。途絶は `TaskContainer.setState` のKotlin式body欠陥（ラムダを返す
   だけでtile state更新が未実行 → `TaskThumbnailView.setState` 未呼出しで、#545 qva2の「黒
   fallback」評価は未描画card領域の誤読）。PR #561 `4a8508498b`で修復し、r9 run／qa3・qb3で
   **実thumbnail rendering oracle成立**。
3. **G3/parcel schema修復（#554で解消）**: shell→launcher recents遷移のbinder callbackの
   37 schema driftを `SystemUiProxy` `$RecentsAnimationRunnerStub` の受信境界正規化
   （descriptor・全field消費検証後にdispatch、truncated/余剰dataはfail-closed拒否。#557）で
   解消し、parcel例外0×3 run・G3遷移完走（BAL 0）を確定。qa3/qb3でもscoped parcel 0。
4. **CE pre-unlock FATAL**: provider/role切替boot時に既存の二次signature（#524 leg (e)と同一）。
   oracle範囲外として記録。
5. hiddenapi: 全runでapp.lawnchair*のdenial/block 0件（新reflection call含む補助観測）。
6. wmshell `PipInputConsumer`: 全runで0件（launcher到達性の前提どおり）。
7. 既知のpre-existing vendor-skew（candidate非起因。cross-run突合済み）: bind時の
   `IPipAnimationListener` unread-16・system_server `TransitionFilter` 1MB-overrunの
   BadParcelableException警告（binder warning level。qa3/qb3にも同型出現。magic value一致で
   継続中の既存signature）。

## 解消記録

- **thumbnail pipeline root cause**: wrapToBitmap戻り値は正常にflowしていた。途絶は
  `TaskContainer.setState` がKotlin式body（ラムダを返すだけ）になっており
  `TaskThumbnailView.setState` が一度も呼ばれないため（#555 / PR #561 `4a8508498b`で修復。
  r9 runで実thumbnail scoreed oracle成立）。
- **G3/parcel schema**: shell→launcher callbackの37 schema driftを
  受信境界で正規化（#554 / PR #557。parcel例外0 ×3 run・BAL 0でG3確定）。
- `QUICKSTEP_MAX_SDK` 36→37とADR-0018 range 35..37反映: v6 candidate `4ddd6ae306` で
  **確定採用**（qa3/qb3 PASS後。ADR-0018 revision 9）。

## 使用コマンド列・逸脱の要点

#524 README §2/§4手順の再利用（overlay差し替え、priv-app配置、run毎preflight）。
主な逸脱: privapp allowlistはAPK宣言permissionのsuperset（debug 55/release 54。boot一回成功）。
`adb root; adb remount` はreboot毎に再実行。HOME設定は `cmd package set-home-activity`。
qva2のdebug APKはcompat 7修正head（`0c25041285`）専用build（`compat7b-debug-0c25041`）。

## 証跡file一覧

本dir内の`qa-`/`qb-`/`qva-`/`qbv-`/`qva2-`/`qvb2-`/`ma-`〜`mf-`/`a-`〜`e-`各fileと
contrast file（`pre-guard-field-and-resource-contrast.txt`／`api36-image-framework-res-contrast.txt`／
`gettasksnapshot-class-contrast.txt`／`wraptobitmap-contrast.txt`／
`tasksnapshotmanager-reflection-targets.txt`／`taketasksnapshot-contrast.txt`）。
APK実物はcommitしない（sha256は上記表。実物は検証session `/tmp/545-evidence-apk/`）。
