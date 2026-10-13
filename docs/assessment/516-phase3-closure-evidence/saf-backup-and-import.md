# Issue #516 SAF backup, restore, and exchange evidence

**Status: runtime evidence complete.** The earlier `ae697` run completed the real system-picker backup/restore and Manual Organization discard checks, but its exchange import crashed before durable persistence. A later same-owner replay on source `75492ec6407b4c36645186c519bdd93583008f5d` successfully imported through OpenDocument, recovered the pending proposal after a cold restart, and confirmed discard without applying layout changes. A subsequent native API 37 run now evidences the owned 43-row restore, folder open/Back, bounded drag, and probe package add/remove refresh in [native-operations.md](./native-operations.md). Supported cold-settings restore #587/#588 audit/publication checks passed; PR588 mergeda9ec830a. The Phase3 completion decision belongs to matrix§15/PR573; this packet records its bounded runtime evidence.

## Run identity and evidence

The identity and backup evidence below describe the earlier `ae697` run; the repaired import replay is recorded separately below.

- Source: `ae69715379314f24338304ca5a0b2b4f9e9f7c43`.
- APK SHA-256: `9ff6c8483ff74b00ed50ee295d994a001439fa69f22315f0b39af6436b049bd4`.
- Package / launcher: `app.lawnchair.debug` / `app.lawnchair.LawnchairLauncher`.
- Device: owned API 37 Google APIs emulator `emulator-5560`, Android 17 build `CE2A.260420.019/15611780:userdebug/dev-keys`; no personal device or other emulator was used.
- Active DB: `launcher_5_4_4.db`, schema 33. The independent read-only receipt tool in the private run directory produced the DB and archive hashes below. Captures indexed in [`saf-backup-and-import/README.md`](./saf-backup-and-import/README.md) are selected PNG/XML pairs; raw DBs, archives, request/reply files, and private physical data are not included.

The current 500-row fixture is synthetic. “500 favorites” refers to existing layout rows, not 500 installed apps. The Manual Organization inventory separately showed 112 eligible off-home app candidates. In the exchange exercise one personal-profile candidate was selected; the work-profile duplicate was left unselected.

## Earlier `ae697` SAF backup and restore

Before the current-build SAF run, the active DB was 13,336,576 bytes, raw SHA-256 `9e65326b86bdd7a0afbce5e562bfdefd31469fc532a3ce01aa1a161d044f6aec`, schema 33, `quick_check=ok`, 500 favorites (all item type 0), 32 screens, and no duplicate coordinate groups. Its semantic projection SHA-256 was `f531d7e9db4369d775b0f24134bc4f1e01738cadfa9be84f27655b69902ad527`. The 317-byte `preferences.preferences_pb` SHA-256 was `fd46fa26aaa50214eff2afe80cc1129ac9ba6e150f2413ee0ec476f93b9b4ff2`.

| Actual UI action | Observed result |
|---|---|
| CreateBackup → system CreateDocument → Cancel | DB raw hash and preferences bytes/hash matched the immediately preceding checkpoint exactly. |
| CreateBackup → system CreateDocument → save `NunuLauncher_500_AE697_20261012.lawnchairbackup` | Host-inspected archive the private exported archive, SHA-256 `e0965bb5a7ee26dadaef68bae723812bfc8a8168b54c806831781d83424fec26`, 12,230,321 bytes. ZIP integrity test passed. Entries include `launcher.db`, `preferences.preferences_pb`, metadata, and screenshot. No wallpaper or recovery database entry was present. Embedded DB is the exact pre-save raw DB and semantic projection; embedded preferences are the exact pre-save 317-byte preferences. Export did not alter the active DB or preferences. |
| Turn **Show labels** off in settings | Preferences changed to 356 bytes, SHA-256 `9ee088dc620793308c2dfc0486b5306ab8dcb76996862c7e4e483e42185fad6a`; DB raw hash/projection stayed unchanged. |
| Select the saved archive in RestoreBackup and leave its preview with Back | Preview showed the saved layout/settings restore options and wallpaper disabled. DB stayed at `9e65326…f6aec`; preferences stayed at the changed 356-byte `9ee088…fad6a` checkpoint. This is a measured no-write cancellation. |
| Remove exactly one synthetic favorite, then select the archive and press Restore | The remove action took the active table from 500 to 499 favorites. The 499-row state was raw SHA-256 `dee491c1360dca868261b3efcb663996082fa7b02e903e3830d3b960726273ea`, projection `d42eb60cf6daa1003e94969cd4f7a2a109bf1a59a171357889ecc616a07a719b`. Positive restore returned 500 favorites and archive preferences. |
| Force-stop/start Launcher after the positive restore | Reloaded DB passed `quick_check`; its raw hash was `47ab0d6c3c55d9e94e6aa6c29500c6f3da5d86272ad997d3025a1256e8c7137c`, semantic projection matched the archive/current canonical projection `f531d7e9db4369d775b0f24134bc4f1e01738cadfa9be84f27655b69902ad527`, and preferences returned exactly to 317 bytes / SHA `fd46fa26aaa50214eff2afe80cc1129ac9ba6e150f2413ee0ec476f93b9b4ff2`. Subsequent no-write cases retained this semantic projection. |

The screenshot/XML pairs for the backup picker, both cancellation routes, one-row removal, restore preview/result, and cold reload are in the evidence directory. Every XML capture was freshly pulled only after `uiautomator dump` printed its completion marker.

### Historical 838 build evidence is separate

An earlier actual SAF pass used source `41d8753d7a23eac495856f31d57774a7b8e5d07a` and APK SHA-256 `83884d094e05a9f1efcf96c5868e72f82a2361b022093eaf431479151dcd5abc`. Its synthetic pre-reload fixture projection was `c67bdf165011036042694cf4a4aa4a04d92f172c867dd82092cfbfa1b07285c7`; normal Launcher reload refreshed synthetic shortcut titles and equivalent intents to actual activity labels and added the app package, yielding projection `f531d7e9db4369d775b0f24134bc4f1e01738cadfa9be84f27655b69902ad527`. That metadata normalization is why the historical run is not claimed as a full `c67` after-cold-reload equality. The stronger archive-to-cold-reload projection equality above belongs to the canonical `ae697` run only.

## Manual Organization preview cancellation

On the `ae697` build, Manual Organization displayed 112 eligible off-home candidates. The run selected these candidates for non-AI Manual planning, opened the rendered plan/review surface, then used **Stop** and confirmed **Discard this work?**. The confirmation stated that selections and the proposal would be discarded and nothing would be applied to the home layout.

The explicit checkpoint is:

- Before preview: DB raw SHA-256 `47ab0d6c3c55d9e94e6aa6c29500c6f3da5d86272ad997d3025a1256e8c7137c`; projection `f531d7e9db4369d775b0f24134bc4f1e01738cadfa9be84f27655b69902ad527`; 500 favorites; `quick_check=ok`.
- During preview: same raw DB hash, projection, row count, and preferences.
- After Stop → Discard settled: same raw DB hash, projection, row count, and 317-byte preferences SHA-256 `fd46fa26aaa50214eff2afe80cc1129ac9ba6e150f2413ee0ec476f93b9b4ff2`.

Thus the cancel completion is supported by a before/during/after database and preferences comparison. This only proves cancellation left those persisted states unchanged; it does not claim the plan was applied or exercise a successful apply.

## Historical `ae697` exchange-import crash before persistence

The actual exchange flow generated a request from the production export path using the redacted request tier and the single selected synthetic personal-profile candidate. The generated file was saved through CreateDocument. For privacy, the request, reply, export ID, and candidate reference remain in the private run directory and are not copied into this packet. The synthetic reply used the production `personalized-intent-v4` schema, the generated export ID and candidate reference, and one `importance: HIGH` result inside a single fenced `json` block; it contained no app label or package name and was not sent to a network service or external AI.

The actual OpenDocument cancel route returned without modifying the 500-row DB or current preferences. Selecting the valid reply from OpenDocument then pressing **Import** crashed the app instead of creating a durable pending proposal. The observed failure was:

```text
FATAL EXCEPTION: DefaultDispatcher-worker-4
java.lang.IllegalStateException: organizer run state machine must not run on the publication (main) thread
  at ManualOrganizationRun.isLiveScopeOwner
  at ExchangeFlowStateHolder.settleImport (ExchangeFlowUi.kt:1534)
  at ExchangeFlowStateHolder$import… (ExchangeFlowUi.kt:1480)
```

The failure occurred at `settleImport` before the `pendingValidated` transition and `persistPendingImport` call. After the crash the active DB remained raw SHA-256 `47ab0d6c3c55d9e94e6aa6c29500c6f3da5d86272ad997d3025a1256e8c7137c` with the same 500-row `f531…a527` projection and `quick_check=ok`; preferences remained the same 384-byte file with SHA-256 `b96f93e93d4fb282d99487959c4d8e751bdc286c0d5747eb2199481e78cd6f3f`. No pending-import preferences file appeared in the inspected `files/datastore` inventory. Therefore this is neither a successful durable proposal import nor a rejected-reply validation result; Apply was never reached.

Issue #583 tracked this failure; #582 was merged at `0ef86713b9414f0aa85c028e2e77d3ec14c0f4db`. The repaired same-owner replay below supersedes the pending-replay statement in this historical section. The prior crash and its editor capture remain separate from the new success evidence; no live export ID, candidate reference, raw request/reply, or reply-editor screenshot is published.

## Repaired same-owner OpenDocument import and cold recovery

The repaired replay used the actual OpenDocument path on the owned API 37 emulator and returned same-owner `ImportSuccess`. The import result recognized one wish, left 500 items unstated, and covered one app outside the home. The proposal was not applied. After force-stop/restart, the app opened the same pending proposal for review; its serialized record remained byte-identical through cold recovery and review. The final explicit discard removed that pending record while retaining the active request.

| Identity or checkpoint | Recorded value |
|---|---|
| Installed production source / APK | Source `75492ec6407b4c36645186c519bdd93583008f5d`; APK SHA-256 `a02cf76f0557c76cf751d852effb4d724384db33c0225c9ad11cba10fe7bd74e` |
| Main production source / replay test tree | Main production source `77b6b97768822158d3a3b13bafa6267a0b57e4f1`; replay test-tree identity `5cefa6dfff0d9af3a76bebe9d5ebc1950c5e7da0`. The test tree retained uncommitted PageCapture `f70` build edits; test-only changes were not packaged in the APK. |
| Production parity | `ManualOrganizationRun.kt` and `ExchangeFlowUi.kt` were both recorded `main16_identical=true`; SHA-256 values `eaa8e6b7ad4197a7dea0245dfc05b487bda58cd72793714cb85ce1c475bf2794` and `b94ae4360f8a460006194d606407013ff8acd02d0a75c19c725c1c4a4c80539c`, respectively. |
| Import result | Request contained 501 items against 500 existing rows; one wish recognized, 500 unstated, one off-home app covered; `applied=false`. |
| Pending proposal | 32,915 bytes; SHA-256 `94265ca91f2e46bd4d17303cca31ddf91a29b1c610dd990fe1bfcbf643d5e794`. |
| Process restart | Force-stop/start changed PID `20516` → `21569`; the pending bytes and proposal summary were unchanged at cold review. |
| DB and preferences at all five checkpoints | DB raw SHA-256 `47ab0d6c3c55d9e94e6aa6c29500c6f3da5d86272ad997d3025a1256e8c7137c`, semantic projection `f531d7e9db4369d775b0f24134bc4f1e01738cadfa9be84f27655b69902ad527`, 500 rows, `quick_check=ok`; preferences 384 bytes, SHA-256 `b96f93e93d4fb282d99487959c4d8e751bdc286c0d5747eb2199481e78cd6f3f`. |
| Confirmed discard | Pending record deleted; active request remained available; DB/preferences stayed at the same checkpoint; no layout apply occurred. |

The selected fresh PNG/XML pairs show the 501-item request review, named save, local reply picker, import result, cold hub, cold review, and confirmed discard. Raw request/reply files, the reply editor, live export ID/candidate reference, pending-record JSON, database/archive/APK binaries, and the initial null-root/pre-confirmation captures remain excluded.

Validation limits: Historical CI [38321690999](https://github.com/nunu1733/NunuLauncher/actions/runs/38321690999) failed. Source/test5ce CI [38324607913](https://github.com/nunu1733/NunuLauncher/actions/runs/38324607913), independent method-choice rerun, publication0bbc CI [38347440730](https://github.com/nunu1733/NunuLauncher/actions/runs/38347440730) and high-risk gate [38347440751](https://github.com/nunu1733/NunuLauncher/actions/runs/38347440751) pass; PR584 mergedc3898c74. Actual16 source754 replay remains a separately identified validation port. The JIT timeout noted in Issue #586 remains unexplained and is not attributed to this replay.

## Native no-write operation and owned baseline inventory

The synthetic Home settings shortcut in the personal hotseat was launched from the dock; it opened the Preferences activity. System Back returned to Launcher home. Before/after hotseat DB snapshots and preferences were byte-identical: DB raw SHA-256 `47ab0d6c3c55d9e94e6aa6c29500c6f3da5d86272ad997d3025a1256e8c7137c`, projection `f531d7e9db4369d775b0f24134bc4f1e01738cadfa9be84f27655b69902ad527`, 500 favorites; preferences 384 bytes / SHA `b96f93e93d4fb282d99487959c4d8e751bdc286c0d5747eb2199481e78cd6f3f`. That earlier session did not exercise folder open/Back or drag. A later native run records those operations in [native-operations.md](./native-operations.md).

During that earlier inspection step, the owned emulator’s original SAF archive was copied and inspected read-only without restoring it. The archive at the owned emulator’s Downloads provider was mirrored privately as the private original-43 archive, SHA-256 `fdc507b7b7c8c16b443cc4ce0abd24848306617d1fcd0c8e87dd7388fb443dac`, 189,913 bytes. Its embedded schema-33 DB passed `quick_check` and contains 43 favorites: 41 app rows, one folder, and one widget; projection SHA-256 `965b1986165c4a15c0bc8b3fea85f52a470b05189e20a68ff5581e89d2be1bff`. Its embedded preferences match the 317-byte SHA `fd46fa26…b9b4` in the current 500-row archive. A later native API 37 run restored this archive and exercised folder open/Back and bounded drag; see [native-operations.md](./native-operations.md). [Owned AVD teardown](./owned-fixture-teardown.md) is complete with stated historical limits.

This 317-byte archive preference checkpoint is not identical to an older 253-byte pre-Smartspace/clock preferences snapshot (SHA-256 `b31b7ec0b05427ab5fd6d6da1710bd73500cf5277281987c258a168b41921d4b`). These are different historical checkpoints; no equivalence is claimed.

## Evidence directory and remaining work

[`saf-backup-and-import/README.md`](./saf-backup-and-import/README.md) contains selected safe screenshots and fresh XML hierarchy captures for the completed actions. [`receipts.json`](./saf-backup-and-import/receipts.json) contains privacy-filtered hashes and checkpoint facts. It intentionally excludes the raw request/reply, live export ID/reference, database/archive binaries, APK, and unrelated physical data.

Source PR588 merged; final Phase3 closure/publication decision belongs to matrix§15/PR573. The owned 43-row restore, folder open/Back, bounded safe drag with a restore path, and probe package add/remove refresh are evidenced in [native-operations.md](./native-operations.md). Earlier route failures remain historical; candidate cold route is GREEN and source PR588 merged; final Phase3 closure belongs to matrix§15/PR573.
