# API 37 large-home apply, recovery, and ZIP restore evidence

Status: curated local evidence for bounded scenarios; this packet does not claim V7 or Phase 3 closure. Date: 2026-10-12.

## Large-home apply and cold-process recovery

The run targeted a disposable API 37 AVD on `emulator-5560`, fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`. It was created for Issue #516; the existing `5554` and `5556` emulators were left untouched. This is emulator evidence, not physical-device proof. Root access was used for setup and read-only database export.

The AVD used software graphics, animation scales set to zero, and the speed-compiled APK. The requested2048MB RAM was raised by the API37 emulator to its4096MB minimum; startup logs explicitly record that adjustment. It had owner user0 and managed user10, with no accounts; those numbers are profile IDs, not app counts. These settings bound performance and profile representativeness. Initial boot and shared-host load produced ART inflate ANRs and system-startup stalls; those observations are kept separate from the #565 product deadlock.

The local API 37 diagnostic used 500 synthetic Launcher rows across multiple workspace pages. It exercised the production capture, preview, apply, model-reload verification, and durable-recovery path through `ManualOrganizationRun` and `LayoutApplicationModule.production`. The fixture included 49 locked rows, two canonical movable rows, and the first-page QSB reservation. The copied source is a diagnostic artifact, not a permanent test or CI lane.

The apply leg passed 1/1 in 13.427 seconds (`large-apply-runner.txt`). Its before and after projections were read from a separate read-only SQLite connection. The 500-row projection became 501 rows: 498 existing rows were unchanged, including all 49 locked rows, and exactly two movable rows changed. Those two rows became children of new folder ID 2247; their title, intent, item type, profile ID, and lock state stayed the same, while the parent, rank, screen, and workspace-span fields changed as expected for folder children. One workspace folder row was added.

The diagnostic asserts valid folder-parent references and ranks, in-bounds positive workspace spans, and no workspace overlap. It also verifies that the QSB reservation survives apply unchanged and that no applied workspace item overlaps it. Before confirmation, both the production capture and separate read-only SQLite projection still matched the original 500-row state.

The app was force-stopped between the apply and cold-recovery legs. The cold leg passed 1/1 in 8.559 seconds (`cold-recovery-runner.txt`): PID 6158 wrote recovery point `0820775f500c5c59e2dcb1319b0606bf`; fresh PID 6232 reported `Restored` for that same point and matched the exact saved 500-row semantic projection. `markers-final.txt` keeps only these final-pass process markers.

An earlier cold attempt without the registered model-callback owner left the raw database at 500 rows but did not report a `Restored` result, so it is excluded from the pass. The qualifying driver registers the real callback owner and waits for the model reload before cold recovery. This evidence does not establish a product runtime root cause or claim that one was fixed.

## Build and source identity

| Artifact | Identity |
|---|---|
| Production app installed for the run | APK SHA-256 `754d9564217a4f0e1dc0b04d5af8788d47d43813036b85480332cb102d96473e`; clean production source commit `06bce6e5c9fa6c3afed6b1fb0abdd545fd61d595`, based on `000c26903b9f0880ce551721dd89c3bb09da1ad6` plus the accepted #565 cherry-pick |
| Instrumentation APK for the large-home legs | SHA-256 `7f0da3b6e8a73679d037e19a6ce2c02047cb9ffe1787811a96543329010e5967`; packaged the local diagnostic and uncommitted #577 port, so it is not a clean-HEAD instrumentation APK |
| Diagnostic source bytes | SHA-256 `119c59e89278e9f4579dac3238379cac1cda0366943eab36326fbc10e98db5aa`; preserved in `api37-data-and-recovery/diagnostic-source.kt.txt` |
| Build record | `largehome-cold-owner-build.log` completed successfully in 30 seconds with `--no-watch-fs --no-configuration-cache`; Android-test Kotlin compilation and APK assembly ran. The full build log and APKs are not copied into this packet. |

The source and APK identities are recorded separately because the production app APK came from a clean production commit while the instrumentation APK included uncommitted diagnostic/test-port source.

## Real ZIP restore boundary

The existing `RealZipRestoreE2E` run passed 2/2 in 12.273 seconds (`real-zip-runner.txt`). It restores generated two-row ZIP fixtures through `Uri.fromFile`: one schema-32 archive exercises legacy upgrade and unknown lock state; one schema-33 archive checks lock preservation. Both replace an old-epoch marker, check restored rows and profile/grid state, require a pristine recovery store, and wait for reload.

This is small generated-file-URI coverage. It does not exercise SAF selection or a 500-row archive, and it does not complete separate package, profile, or Smartspace coverage. The source for the large-home diagnostic and the runner/projection artifacts are included; no APK, database, device backup, or full build log is included.

## Curated artifacts

All files are under `api37-data-and-recovery/`. The JSON files contain only the synthetic fixture rows and selected `favorites` fields. The runner files are the passing instrumentation summaries; `markers-final.txt` is trimmed to the successful cold-recovery attempt.

| File | SHA-256 |
|---|---|
| `before.json` | `46b6de1a9ed921a6da9d65427f89fb466ee69c0ddf2e65a9e382634f8f1804f5` |
| `applied.json` | `f64b8042737af7b0d6d11e6a977a0655603ebcadfbfe02b5c82fe7135e81736b` |
| `large-apply-runner.txt` | `7c40b0a5308add5762c45a223af8c8164d9afbf3b08390affc21606f0c713c6d` |
| `cold-recovery-runner.txt` | `0108161c254a046f6b86fff71139d4c9d862acebdb1e72dbbc175807f30faa88` |
| `real-zip-runner.txt` | `1420d1dcf534ab030b534601b1869c9351ab4b19951e04cd1cf80e0c98e7aa64` |
| `markers-final.txt` | `b4ef45b797758dcbdbb1834e10bb1f85cf1aa7a1b463941cba7bc51886ea4682` |
| `diagnostic-source.kt.txt` | `119c59e89278e9f4579dac3238379cac1cda0366943eab36326fbc10e98db5aa` |
