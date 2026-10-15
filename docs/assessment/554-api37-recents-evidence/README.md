# Issue #554 runtime検証 evidence — API 37 recents callback decoder + guard修復

## 判定契約（結論を先に）

本evidenceの判定契約は **accepted spec `5c724d60c68cc5c17205421567ae75796196340f`（PR #556 merge済み）＋ owner decision revision `57503d88fc`（PR #560 merge済み、AC-4のAPI 36 runtime legを#559へ分離）** である。

- stack内のspec/plan copyは最終headでmain正本と同一化済み（stack commit `a4a1345092` "Sync spec/plan with merged main revision"）。
- stack内の #558相当 clarify text（commit `80b8c34fa8`）と `RecentsParcelProbe.java`（commit `9ea6271ad9`）は **#558（clarify PR pending）と同じ内容を含むが、本PRの判定契約へ採用されない**。実装は#556契約のScenario 4（切れたpayload・余剰data・descriptor不一致のfail-closed拒否）を満たす。
- runtime検証で使用したsource SHAとPR最終headを区別する: runtime = candidate `e3f0ad5e`/`8469afa`（maxSdk37一時candidate tree）＋ final source debug `6595f22`。PR final head（docs/evidence deltaのみ）と最終CIは下記の表。

## PR final head / 最終CI

| 項目 | 値 |
|---|---|
| runtime検証実行source | candidate2 `8469afa6`（revert済み）/ final source `6595f22e`（maxSdk36 debug） |
| evidence origin commit | `c2a8870865` → review対応拡張は本READMEを含む最終commit |
| **PR final head** | 本PRの最終push commit（PR本文に記載。source deltaは `6595f22e` / `8469afa...0ed0518d` revert以降docsのみ） |
| **最終CI merge gate** | PR final head上で `final-status` success のrun（PR本文に記載）。`c2a8870`でのrun 37712590248（rerun成功）と final head run の両方が本treeのsource同型を検証 |
| review対応後の確認更新 | 初版README (commit `c2a8870865`) の「本tree=c2a8870 / 最終tree=85dea91」混在を本版で分離 |

## 対象commit / APK対応表

| 役割 | commit (branch `issue-554-api37-recents-parcel`) | APK / sha256 |
|---|---|---|
| 修正前対照（#545 v5 candidate、既知FAIL） | `da15f4b2e7` | candidate5-release（#545 README表を参照） |
| 554 decoder実装 | `150cb726d9` | — |
| 1次検証candidate（検証session使用tree） | `73e984278d` | candidate-initial-73e9842-release（session /tmp。log解析用。sha256未記録のため決定証跡から除外） |
| payload検証clarify / truncated拒否 | `80b8c34fa8` / `9ea6271ad9` | — |
| API36/37共通 conductor: guard catch修正 | `a556c92904` → reviewで `6595f22eac` へ縮小（下記Finding A） | — |
| **AC検証candidate（1次 runtime tree）** | `e3f0ad5ef3` | 554-candidate-release-e3f0ad5 `860aae1bb83807614e2661d9591f2eeca5dfcd0271b4be6937d8a5da4a43234f` |
| **guard縮小後 re-run candidate（runtime tree）** | `8469afa69b` | 554-candidate-release-2-8469afa `7ccd6adac0905793bee519a811f6840c3aa18074029669e4c23f079ea427f1e2` |
| candidate明示revert | `85dea91329` / `0ed0518d4c` | final release（`0ed0518d` tree）`6be9b81ae59153ebb8cb60c51612ecc2fba985d7bdd5581167e9351260a00fae` / final debug（`6595f22e` tree）`936cca0dda198e81c1d78fb7b47dce64a9dd6d69ac07d127a14c76a28c909a6c` |
| diagnostics追補 | `bbc05fd07c` card title特定 / `4c51e755b2` --launcher-activity・--entry appswitch | — |
| review対応 docs sync | `a4a1345092` spec/plan sync（main revision `57503d88fc`） | — |

APK実物はsession `/tmp/554-evidence-apk/`（e3f0ad5・8469afa）と `/tmp/554-head-debug.apk`（6595f22 debug）。

## Provider環境（#524 README §2/§4・#545 README手順の再利用）

- API 37 device: emulator-5554 / AVD `issue526_api37_pixel_9a`（fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`）。
  release priv-app `/product/priv-app/LawnchairRelease/LawnchairRelease.apk` ＋ release RRO。
  preflight: `cmd overlay lookup` = `app.lawnchair/com.android.quickstep.RecentsActivity`。
- API 36 device: emulator-5556 / AVD `issue142_api36`。
  debug priv-app `/product/priv-app/LawnchairDebug` ＋ debug RRO ＋ allowlist。
  preflight lookup = `app.lawnchair.debug/com.android.quickstep.RecentsActivity`、HOME=debug launcher。
- `adb root; adb remount` はreboot毎に再実行。oracleは `python3 tools/diagnostics/verify_recents_provider.py`（恒久）。

## 実行matrixと結果

| matrix | 実行tree/APK | 結果 | 証跡file |
|---|---|---|---|
| pre-fix: API 37 release provider, gesture | `da15f4b2e7` release | **FAIL** — parcel unread 8＋Bundle alignment再現、overview未達 | `prefix-api37-da15f4b-gesture-result.json` / `…parcel-signatures.txt` |
| AC-1/2/3 run1: API 37, gesture | candidate `e3f0ad5ef3` | **PASS** — `overview_and_task_return=true`、`parcel_exception_count=0`、FATAL 0、BAL 0（takeover diagnostic 1件のみ＝spec記載の補助signature） | `head-api37-run1-result.json` / screenshots / `…parcel-signatures.txt`(空) |
| AC-1/2 repeat run2 | 同上 | **PASS** — run1相同 | `head-api37-run2-result.json` |
| **guard縮小後 re-run（review Medium対応確認）: API 37, gesture** | candidate `8469afa69b` | **PASS** — parcel 0・FATAL 0・BAL 0・task返り成立 | `head-api37-guardfix-run-result.json` |
| AC-4（owner decision縮小後）: API 36 provider。**「554 diff由来の回帰なし」のみ** | head／base／昨PASS artifact の3対照 | **PASS（diff由来回帰なし）** — 全artifactで同型profile。overview card成立確認自体は#559へ別途 | `api36-head-85dea91-*` / `api36-base-0c25041-*` / `api36-meleg-pass-artifact-2987e52-appswitch-result.json`（2026-10-09補記: 当時のFAIL profileはwire mismatch（Finding B/C訂正参照）由来。3 artifact対照の「同型profile」観察自体は有効で、554 diff由来の回帰なしという結論は維持。判定材料は#559のassessmentとreview-proof.json。） |
| 不正payload probe（synthetic。実転送envelopeは別） | final head debug `6595f22eac` | **PASS 8/8 cases**（runner/home × valid/欠損marker/descriptor違い/余剰tail。欠損marker→Incomplete拒否、余剰→enforceNoDataAvail拒否。いずれもlistener未呼び出し） | `parcel-probe-head-6595f22.txt`（本実行）／ `parcel-probe-pre-guard-2-first-observation.txt`（対策前の漏出対照） |

probe実行手順（恒久手順。tools/diagnostics/にfile版あり）:

```bash
# 1. debug APK（検証対象tree）のclasses dexをAPKから抽出し /data/local/tmp へ push
# 2. 補助クラス を android-36.1 SDK でjavac → build-tools d8でdex化
javac --release 17 -cp $SDK/platforms/android-36.1/android.jar -d classes RecentsParcelProbe.java
$BUILD_TOOLS/d8 --release --min-api 35 --output . classes/*.class
adb push classes.dex /data/local/tmp/recents-parcel-probe.dex
adb push <検証対象treeのdebug APK> /data/local/tmp/lawnchair-head-debug.apk
# 3. API 37 emulator（SDK_INT=37 branch条件）上のapp_processで実行
adb shell CLASSPATH=/data/local/tmp/recents-parcel-probe.dex \
  app_process /system/bin RecentsParcelProbe /data/local/tmp/lawnchair-head-debug.apk /data/local/tmp/recents-probe-dexcache
```

先sessionのpre-guard artifact（`parcel-probe-pre-guard-2-first-observation.txt`: runner欠損marker・home tail欠損でlistener漏れが起こるFAIL）は実行treeがfingerprint記録されていない（commit `9ea6271ad9`前後、09:16-09:19 session）ため対照示性のみに使用し、本headでの 8/8 cases PASSを決定証跡とする。

## 検証で確定した事実

- **AC-1**: 現行head decoder（truncated拒否込み）でAPI 37 release providerの両parcel例外signatureが0件
  （candidate×3 run: e3f0ad5 run1/run2＋guard縮小後 8469afa run1）。修正前は同環境で unread 8／Bundle alignment が再現。
- **AC-2**: 開始callback到達→overview UI render（task card、`…overview.png`）→card tap→Settings
  foreground復帰が2回＋guard縮小後1回成立。
- **AC-3**: AC-2遷移windowでBAL block signature = 0件
  → **G3「BAL block無し」で確定**。PI creator mode判断は#545へ引き渡し（mode変更はscope外）。
- **Finding A（1次candidateで発見・review対応）**: `RecentsView.finishRecentsAnimation → PipFlags` の
  fail-closed guardが `catch (Exception)` のため `NoSuchFieldError`（Error）を通過し launcher FATAL
  （excerpt: `head-candidate-1st-guard-fatal-excerpt.txt`）。
  `a556c92904` で `catch (Throwable)` にしたが、review 6052316052「中」指摘（OOM/StackOverflow等VM系も握り潰す）
  を受けて `6595f22eac` で `Exception`＋`LinkageError` のみへ縮小。両file同方針。API 37 recents完走は
  `8469afa69b` candidateで1回再確認（guard縮小後 re-run行）。
- **Finding B**（2026-10-09 update: 初版の「emulator環境state driftと確定」は **superseded**。判定は#559のwire解析とQAに訂正）: API 36 provider（emulator-5556 / AVD `issue142_api36`）で、当時クライアントが送信した `IRecentTasks.startRecentsTransition` は **transaction 6 + nullable-WCT の6引数** で、インストール済みAPI 36 SystemUI（fingerprint `BE2A.250530.026.F3`）が期待する **transaction 6 + 5引数（WCTなし）** と不一致。shell側で `unread size: 12` が1回記録され、`enforceNoDataAvail` で遷移要求が拒否される。したがって経路依存のwire mismatchであり、ソースdiff由来でも環境drift確定でもない。両方向のnightly対照とwire参照は [#559](https://github.com/nunu1733/NunuLauncher/issues/559) が恒久。
- **Finding C**（2026-10-09 update: Finding Bの同一wire mismatch由来、修正は#559 source branchでruntime GREEN。参照: Issue #559 / [#559 assessment](https://github.com/nunu1733/NunuLauncher/pull/564/files)・`docs/assessment/issue-559-api36-recents-repair.md`（#564でmain向けdocs PRに記載）。本branchでは554 READMEから直接参照せずリポジトリ内はmain merge後に有効）: API 36 provider gesture／appswitchでoverview card表示が空になりappへ戻る現象は、Finding Bのwire mismatchによる遷移要求拒否で説明される。shell観測 `BLASTSyncEngine: ... never received commit callback` と `No matching remote found to takeover` は当該遷移のエコー。修復確認（direct APP_SWITCH/gesture PASS）は#559のruntime evidence。HOME経由で一時的にカードが空になる別観測は [#563](https://github.com/nunu1733/NunuLauncher/issues/563)。

## 未確認 / 制約

- Nothing OS 4実機不所持 → 既存専用decoderの保持はdiff review確認（spec記載どおり）。
- 不正payloadの**実転送envelope（他プロセスからcross-process送信）**は未実施。synthetic probe（8/8 cases PASS）と実際provider oracle runを証拠とする。
- API 36 overview/card不成立（Finding C）および unread12（Finding B）は#559へ別途追跡（2026-10-09 update: root causeはwire mismatch確定済み、判定はmain側 [#559 assessment](https://github.com/nunu1733/NunuLauncher/pull/564) `docs/assessment/issue-559-api36-recents-repair.md`。HOME経由の一時的空カードは #563 で独立追跡）。本PRのdiff由来回帰なし確認は3 artifact対照で完了。
- thumbnailは#555。maxSdk 37は#545契約に従う（candidateでのpassをadvertised support拡張とは扱わない）。
- #558（clarify PR）は本sessionのowner packetではmergeしない（head branchがmainに対して未syncのためGitHub merge不可とcreate確認済み）。#556+#560が本PRの判定契約。
- Cutover前merge/close保留（ADR-0018 Decision 3/8）。
