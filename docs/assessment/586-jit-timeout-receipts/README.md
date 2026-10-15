# Issue #586 evidence — settings-return timeout diagnostics の controlled targeted execution 記録

正本の分析・分類は [issue-586-jit-settings-timeout-receipts.md](../issue-586-jit-settings-timeout-receipts.md)。本ディレクトリはその実行証跡（生XMLとoverlay差分）を保存する。

## Files

| File | 内容 |
|---|---|
| `impl-class-run1.xml` / `impl-class-run2.xml` | baseline系列（implヘッド: Handler publication + diagnostics）のsettings-return 2本を含むclass実行。settings-return 2本PASS、`jitDialogBackDeclines…` はtree非依存のlocal環境失敗（本文書の親で記録） |
| `clean-class-run1.xml` / `clean-class-run2.xml` | 対照系列（stash適用による main `cd50255146` + spec のみ）。同一BACK失敗が再現し、BACK失敗が本diff非依存であることの根拠 |
| `candidate-grant-run1..5.xml` | candidate系列（Direct-publication overlay = 77b6b97時代の `newProductionRunner` 相当）でのgrant method実行。5/5 PASS＝原signature非再現 |
| `candidate-overlay.diff` | candidate実行に使った一時overlayの差分（非commit。`newProductionRunner` から `publicationThread = HandlerRunPublicationThread()` を除去） |

## 実行環境（共通）

- host: macOS arm64、local emulator AVD `issue142_api36`（API 36 / Android 16、`sdk_gphone64_arm64`）
- 実行: `ANDROID_SERIAL=emulator-5556 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...`
- CI（API 36 / x86_64 / pixel_7_pro / clean boot / disable-animations）とは環境が異なる。環境差の含意の限界は親文書の「分類」節を参照。

receipts経路の意図発火確認（1ms一時overlay、非commit・revert済み）の出力quoteは親文書を参照。
