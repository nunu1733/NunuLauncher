# Issue #554 runtime検証 evidence — API 37 recents callback decoder + guard修復

CI merge gate: 本tree（head `c2a8870865`）で [run 37712590248](https://github.com/nunu1733/NunuLauncher/actions/runs/37712590248) 成功
（成功後rerunを失敗再試行し最終success。最初の2runで category-override の
`rowsRemainReachableAtTwoHundredPercentFontScale` がCompose runtime内部の
ArrayIndexOutOfBoundsExceptionでflake、shared-writerは1回flake。他全job success）。

問題・受入条件は `specs/554-api37-recents-parcel/spec.md`（accepted @ `5c724d60c6`、docs PR #556 merge済み）。
本dirはAC-1〜AC-5のruntime matrix証跡の限定版（全logはcommitせず、signature/transition/windowと
synthetic emulator screenshotのみ）。実APKと全logcatは検証session `/tmp/554-evidence/` と
`/tmp/554-evidence-apk/`（session後消滅し得る。sha256を本READMEが所有）。

## 対象commit / APK対応表

| 役割 | commit (branch `issue-554-api37-recents-parcel`) | APK / sha256 |
|---|---|---|
| 修正前対照（#545 v5 candidate、既知FAIL） | `da15f4b2e7` | candidate5-release `e584e078…`（#545 README表を参照） |
| 554 decoder実装 | `150cb726d9` | — |
| 1次検証candidate | `73e984278d` | candidate-initial-73e9842-release（session /tmp。未記録→本表から除外、1次runは決定証跡ではない） |
| payload検証clarify / truncated拒否 | `80b8c34fa8` / `9ea6271ad9` | — |
| API36/37共通 conductor: guard catch修正（Throwable） | `a556c92904` | — |
| **AC検証candidate（runtime検証実行tree）** | `e3f0ad5ef3` | 554-candidate-release-e3f0ad5 `860aae1bb83807614e2661d9591f2eeca5dfcd0271b4be6937d8a5da4a43234f`（rebuild実物を/tmpに保存。versionName `16.Dev.(e3f0ad5)`、size 19,571,572） |
| candidate明示revert／**最終tree** | `85dea91329` | release `c687300445e029898abb235116e91888b9ede561f1899cd5d413af07298e4d50` / debug `37a0254aed65266e76878a4d38dd4dc54cc04f8e07e44966312a567dcc8bc2ea`（versionName `16.Dev.(85dea91)`、maxSdk36維持） |
| diagnostics追補 | `bbc05fd07c` card title特定 / `4c51e755b2` --launcher-activity・--entry appswitch | —（oracle Toolのみ。APK不変） |

## Provider環境（#524 README §2/§4・#545 README手順の再利用）

- API 37 device: emulator-5554 / AVD `issue526_api37_pixel_9a`（fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`）。
  release priv-app `/product/priv-app/LawnchairRelease/LawnchairRelease.apk` ＋ release RRO。
  preflight: `cmd overlay lookup` = `app.lawnchair/com.android.quickstep.RecentsActivity`。
- API 36 device: emulator-5556 / AVD `issue142_api36`（fingerprintは各run `fingerprint.txt`/result.json failure縁を参照）。
  debug priv-app `/product/priv-app/LawnchairDebug` ＋ debug RRO ＋ allowlist。
  preflight lookup = `app.lawnchair.debug/com.android.quickstep.RecentsActivity`、HOME=debug launcher。
- `adb root; adb remount` はreboot毎に再実行。oracleは `python3 tools/diagnostics/verify_recents_provider.py`（恒久）。

## 実行matrixと結果

| matrix | 実行tree/APK | 結果 | 証跡file |
|---|---|---|---|
| pre-fix: API 37 release provider, gesture | `da15f4b2e7` release | **FAIL** — parcel unread 8（IHomeTransitionListener側）＋Bundle alignment再現、overview未達。`parcel_exception_count=1` | `prefix-api37-da15f4b-gesture-result.json` / `…parcel-signatures.txt`（詳細は#554 issue本文・#545 qvb2） |
| **AC-1/2/3: API 37, gesture, run 1** | candidate `e3f0ad5ef3` release | **PASS** — `overview_and_task_return=true`、`parcel_exception_count=0`、`launcher_fatal_count=0`、`bal_blocks=[]`（takeover diagnostic 1件のみ。spec記載の補助signature。AC-2成立とShell側branch記録で判断） | `head-api37-run1-result.json` / `…parcel-signatures.txt` / `…overview.png`（overviewでtask card表示）/ `…after-task-return.png`（Settings復帰） |
| AC-1/2 repeat: API 37, gesture, run 2 | 同上 | **PASS** — run1と同一（FATAL/parcel/BAL 0） | `head-api37-run2-result.json` |
| AC-4: API 36, gesture, head | 最終source debug `85dea91` | overview state到達（`mState:Overview`）・launcher FATAL 0・本decoderarrowの例外0。card検出のみ不成立（下記Finding C）。`parcel_exception_count=1`はFinding B（base同一） | `api36-head-85dea91-gesture-result.json` / `…overview.png` |
| AC-4 contrast: API 36, gesture, accepted base | base source debug `0c25041`（#550直前のcompat7 tree） | 同一profile（overview card不成立＋unread 12 1件＋FATAL 0）→ **554 diff由来のAPI36回帰なし** | `api36-base-0c25041-gesture1/2-result.json` |
| AC-4: API 36, APP_SWITCH entry, head / base / head+pm clear | `85dea91` / `0c25041` / `85dea91` | 全て同profileの不成立。head/baseで差分なし | `api36-head-85dea91-appswitch*` / `api36-base-0c25041-appswitch-result.json` |

## 検証で確定した事実

- **AC-1**: 現行head decoder（truncated拒否込み）でAPI 37 release providerの両parcel例外signatureが0件
  （gesture重複run 2回）。修正前は同環境で unread 8／Bundle alignment が再現（#554本文どおり）。
- **AC-2**: 開始callback到達→overview UI render（task card、`…overview.png`）→card tap→Settings
  foreground復帰（`…after-task-return.png`、`topResumedActivity=com.android.settings`）が2回成立。
- **AC-3**: AC-2遷移windowでBAL block signature `Background activity (launch|start) blocked|BAL.*block` = 0件
  → **G3「BAL block無し」で確定**。PI creator mode判断は#545へ引き渡し（mode変更はscope外）。
- **Finding A（1次candidateで発見し修正済み）**: `RecentsView.finishRecentsAnimation → PipFlags` の
  fail-closed guardが `catch (Exception)` のため `NoSuchFieldError`（Error）を通過し launcher FATAL
  （excerpt: `head-candidate-1st-guard-fatal-excerpt.txt`）。`a556c92904` で `catch (Throwable)` に修正
  （PipFlags.kt / AbsSwipeUpHandler.java、近傍LC-Issue comment）。
- **Finding B（既存baseline、554 diff由来ではない）**: API 36 providerでlauncher→shell
  `RecentTasksController$IRecentTasksImpl.onTransact` が `unread size: 12` を1回記録。
  accepted base `0c25041` でも同一の1回であり、554の受信decoder（SDK_INT>=37 gate、送信path不変）では説明不能。
  #545-era API 36 evidence（me-leg @ `2987e525bd`）では0件で、その後のstack差分に含まれる。
- **Finding C（Finding Bと同様に既存stack事象、554 diff由来ではない）**: API 36 provider gestureで
  launcher `mState:Overview` には到達するが、card UIが空のままappへ戻る実測（head/base同型）。
  昨日のme-legでの成立実績との差分は 本検証では確定できず、issue commentへ記録済み。

## 未確認 / 制約

- Nothing OS 4実機不所持 → 既存専用decoderの保持はdiff review確認（spec記載どおり）。
- 不正payload実送信は未実施。truncated/underflow拒否はcode review + diagnosticsの対照run（#558での契約明確化）。
  実binder負payload注入は行わない。
- API 36のoverview card不成立（Finding C）は本diffの範囲外。#545系列の追跡を推奨（Issue記録参照）。
- thumbnailは#555。maxSdk引き上げ meritは#545のADR-0018契約に従う（candidateでのpassをadvertised
  support拡張とは扱わない）。
- Cutover前merge/close保留（ADR-0018 Decision 3/8）。
