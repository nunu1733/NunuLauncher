# Issue #559 API36 recents repair: compact assessment

Status: independent-review input. Source-only contract evidence, not cutover.
Refs #559. Issue #559 stays open; cutover waits on ADR-0018.

## Source and contract identity

- Docs base `1b741fe877e556c18aa421ee7564ac87188a1358` (spec accepted,
  acceptance comment `6065882809`).
- Source branch `issue-559-api36-recents-compat`, base
  `f131e8e09441dc2d10801d4619b671b0da15a6ee`, head
  `54ce5074961aafe0636593ab1eaaba3c4125f4e7`.
- [Human-readable source comparison](https://github.com/nunu1733/NunuLauncher/compare/f131e8e09441dc2d10801d4619b671b0da15a6ee...54ce5074961aafe0636593ab1eaaba3c4125f4e7).
- Two changed paths: **one production path** (`SystemUiProxy.kt`, 14 changed
  lines) and **one diagnostic path** (191-line wire probe).
- Production file SHA256:
  `5ba0e9b68f5fe10f03f39f3bf4d0eeb220d2dd313fc2e76ae7d56b611ee3c0ed`.
  Production patch SHA256:
  `4d97d743ca177e5a0512fa7e9c6883a7bae65e4ab57a864487865b68c4da500f`.
  Wire probe SHA256:
  `80293e50caf2f0d70532eb3ad3c2de3d739bc7c19ea020319919b1f231daad54`.
- Independent Oracle worker-packet result accepted AC1..5 for source54ce after paired baseline:
  https://github.com/nunu1733/NunuLauncher/issues/559#issuecomment-6072568254
  (session `ses_ee3ec2004ffevIxcKwkHszuWt4`). This records the independent
  worker-packet assessment, not a formal GitHub review or user approval.
- Code is pushed on the source branch only, not in a main PR. Main PR must use
  base `main`, no 16 bulk merge.

## Root cause (exact build scope)

- Actual device: emulator-5556, SDK36, full fingerprint
  `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`;
  SystemUI SHA256
  `a895f88ae0539d9950a74e49f0471ca81237337f0b31d6eb5f4fe29e814d14f5`.
- Server route is tx6, five args, no WCT. Client sent tx6 with nullable-WCT
  extra field; AOSP raw five-arg path used tx5 (`getRunningTasks`), wrong target.
- Fix selects the exact-build five-arg route at tx6 with fail-closed WCT
  rejection. No broader family claim, no Nothing callback change.

## Decisive proof (selected, not raw dump)

- [Compact proof](./559-api36-provider-evidence/review-proof.json) contains
  30 exact selected command records with local transcript sourceLine, actual
  wall_start, exit/expected_exit, and verbatim inline stdout/stderr. No abbreviated
  argv or unpublished output-file links are used as executable receipts.
- RED (original candidate2 priv-app
  `5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278`):
  `ASSERTION FAIL caller binder identity/order`, `RESULT FAIL`, exit 1.
- GREEN (fixed APK
  `d6f50206401950c2d502e60c3e6d0777fd1cbf116d823f232bc2fc062c58ef60`):
  8/8 `RESULT PASS`, exit 0: exact36 tx6/5args, BP2A/BD1A36 tx5/5args,
  Nothing36 tx6/5args; unknown36, exact35, exact37 and Nothing37 tx6/6args.
  This APK was built from the patched, then-uncommitted f131 tree whose tested
  source/probe files are identical to committed54ce. It was **not rebuilt at54ce**;
  versionName remains `16.Dev.(f131e8e)`.
- Incoming #554 probe unchanged: native SDK36 run `RESULT FAIL failures=3`
  with **expected_exit=0, actual exit=1**. It was classified after execution as
  an API37-shaped fixture misapplied to SDK36; the original expectation is retained.
  process-only simulated-SDK37 control on ART36: 8/8 PASS, exit 0.
  The exact bootstrap source is inlined in proof; compile/d8/push/app_process
  receipts are 10:17:43 JST, not 10:13:22. Incoming probe SHA256:
  `af167203c82e832e6dafe225d3846c860068c87cb8d2b4c01423675b7603e49e`.
  Decoders unchanged; this evidence contains no actual API37-device run.
- Direct Settings to APP_SWITCH: PASS, return task879 Settings focus, gap 0.718s.
- Gesture DOWN/MOVE/hold/UP: PASS, return task879 Settings focus, gap 0.719s.
- HOME path first attempt: FAIL, six XML checkpoints zero cards, empty overview
  PNG, populated task list in log. Classification remains unknown; follow-up
  [Issue #563](https://github.com/nunu1733/NunuLauncher/issues/563), not assigned to #555.
- Same HOME path after one authorized force-stop/rebind: PASS, gap 0.710s.
- Paired baseline control (identical HOME history, both artifacts): PASS/PASS
  twice, 2 cards each, 0/0/0/0 signatures both sides. Old empty-card state not
  reproduced. This comparison uses a peer #554 APK built at
  `6595f22eac7688299a665cb1e2310ba863a1ec78`, SHA256
  `936cca0dda198e81c1d78fb7b47dce64a9dd6d69ac07d127a14c76a28c909a6c`;
  its full diff to f131 contains only seven docs/spec paths. Both source-identical
  baseline and fixed follow the same clean HOME and repeat history.
- Historical same-APK HOME PASS versus direct FAIL is an entry-state contrast;
  it **does not definitively disprove historical environment drift**. Neither
  paired PASS nor clean retry erases the earlier empty-card failure.
- AC-4 same-install candidate2 control: from the retained independent-QA raw
  artifacts (`qa-independent/artifacts/<run>/<run>-summary.json`) the direct
  entry FAIL on candidate2 is A1/A2/A3 (6 checkpoints stay Settings, 0 cards,
  72 Settings nodes, unread12 1×, 5000ms pending timeout 1×, fatal 0), and the
  clean HOME PASS is B2 (Overview with 2 `task_view_single` + icon_title
  "Settings") / B4 (same + tap (540,1200) → Settings t879 top+focus). These
  are SHA-proven pointer receipts now inlined in
  `review-proof.json#candidate2SameInstallControl`; the raw JSON/SHA paths are
  kept unpublished, and the separate first-HOME empty-card observation is not
  mixed into this AC-4 record.
- UI windows: 0 Parcel / 0 alignment / 0 FATAL / 0 5000ms pending on all four
  windows. Full local buffers retain ProtoLog/ClassLoader warnings; these are
  not counted as FATAL. Raw focus sample values/times and exact XML hashes are
  inline in proof; return is not inferred merely from the Settings title.

## CI, LSP, teardown

- CI: `workflow_dispatch` smoke on `54ce` success,
  https://github.com/nunu1733/NunuLauncher/actions/runs/37870062410
  (style/build/normal organizer-unit/repo/final-status green; instrumentation
  skipped as expected for smoke). This is not full instrumentation portfolio
  coverage or actual API37-device runtime evidence.
- LSP `kotlin-ls`/`jdtls` not installed, user declined; compiler/build checks
  and local ART proof are separate evidence.
- Final-verification uninstall-k receipt: **10:18:13 JST**, exit0/Success.
  Candidate2 restored active; same provider, GLOBAL scales0/0/0, SYSTEM
  null/null/null, CE inode549626/DE393792 and enabled0/default retained.
  These are directory identities, not bytewise user-DB equality. Four exact
  scratch names were removed; teardown does not claim a wildcard cleanup.
- Disclosure: an earlier SDK adb client file was deleted then restored to the
  same 37.0.1 bytes by the earlier worker. This historical incident is outside
  the selected QA command receipts; it is not a new tool mutation in this correction.
- #554 supplement: unified candidate2 control vs fixed artifacts kept separate,
  no regression; old oracle claim not reused.

## New finding

- First HOME empty-card FAIL is retained unclassified (unknown). Follow-up
  [#563](https://github.com/nunu1733/NunuLauncher/issues/563) was created by main.
  This packet does not classify it as #555 or claim that it is waived.

## Planned publication set (11 paths, including two already-committed specs)

- `docs/assessment/issue-559-api36-recents-repair.md` (this file)
- `docs/assessment/559-api36-provider-evidence/review-proof.json`
- `docs/assessment/559-api36-provider-evidence/wire-contract/README.md`
- `docs/assessment/559-api36-provider-evidence/wire-contract/dex-excerpts.txt`
- `docs/assessment/559-api36-provider-evidence/home-baseline-control/paired-summary.json`
- `docs/assessment/559-api36-provider-evidence/final-verification/home-overview.png`
- `docs/assessment/559-api36-provider-evidence/final-verification/direct-overview.png`
- `docs/assessment/559-api36-provider-evidence/final-verification/direct-return.png`
- `docs/assessment/559-api36-provider-evidence/final-verification/gesture-overview.png`
- `specs/559-api36-provider-recents/spec.md`
- `specs/559-api36-provider-recents/plan.md`

The two spec/plan paths already contain the accepted contract. This list is the
publication scope, not a claim that all 11 are newly staged by this correction.
Raw `commands.jsonl` (205 entries), manifests, XML files, retry PNG and bootstrap
file remain unpublished. The compact proof carries 30 selected exact commands,
all eight GREEN outputs, the bootstrap source, raw settled focus extracts,
selected XML SHA256 values and the four published image SHA256 values inline.
Test-audit: manual diagnostic selection at owned production consumer boundary,
no new CI lane.
