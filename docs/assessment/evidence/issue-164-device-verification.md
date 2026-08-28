# Issue #164 device verification — new-folder plans reach A8

> Recorded: 2026-08-28
> Tested head: `aed928dfc6` (`15.Dev.(aed928d)`, debug and release both built
> from this exact commit)
> Redacted evidence only: opaque run/point IDs, closed phases/stages, counts,
> and row-accounting/byte-equality results. Raw favorites rows are not
> committed; the assertions below were computed locally from pulled copies.

## Debug — physical device, Issue #164 environment

- Device: Pixel 9a (`tegu`), Android 17 (SDK 37), 1080x2424, Lawnchair grid
  **4 × 5**, hotseat 4 — the Issue #164 reproduction environment (OS updated
  from 16 to 17 since the original report).
- Build: `app.lawnchair.debug` `15.Dev.(aed928d)` debug, installed fresh
  (separate application ID; the owner's `app.lawnchair` install was never
  touched).
- Workspace: fresh default — **17 pre-apply favorites rows**, matching the
  issue's row-level baseline exactly.

### Apply run

- Manual flow: Settings → ホーム画面 → ホームレイアウトを整理 → 整理を確認
  → 確認した整理を適用.
- Plan preview: 17 targets, **5 placements moved** (2 as folder members,
  2 as single placements, 1 as a folder unit), 12 preserved,
  **1 new folder created** — the issue's exact plan shape.
- Result surface: `整理を適用し、検証しました。` (applied **and verified**).
- Journal (`debug-journal-full.jsonl`):

```text
runId 2d15347a030090ffdb9af69dd88c380f
CHECKPOINTED      stage=A4  pointId e33934893dd4a0ac23b3f36da4b81fc8
APPLY_COMMITTED   stage=A6
APPLY_VERIFIED    stage=A8  {preserve: 12, update: 5, insert: 1}
```

- Row accounting: pre 17 rows → post **18 rows** (one new folder row,
  `_id=19`, desktop). The allocated folder id `19` byte-sorts before `2` —
  the exact intended-vs-capture divergence shape that failed A7 before the
  fix — now verified through it.

### Explicit recovery (AC-164-05)

- Flow: `以前のレイアウトを復元` → `保存されたレイアウトを復元` → result
  `保存されたレイアウトを復元しました。`
- Journal:

```text
RECOVERY_REQUESTED  pointId e33934893dd4a0ac23b3f36da4b81fc8
                    recovery.pointOriginRunId = 2d15347a030090ffdb9af69dd88c380f
RECOVERY_RESTORED   pointId e33934893dd4a0ac23b3f36da4b81fc8
                    recovery.pointOriginRunId = 2d15347a030090ffdb9af69dd88c380f
```

- Row accounting: post-recovery favorites **17 rows, exactly equal
  (byte-identical) to the pre-apply rows**.

## Release — API 36.1 environment (AVD)

- Environment: `nunu_qpr2_api36_1` AVD (API 36.1, 4 × 5 grid, hotseat 4),
  release build `app.lawnchair` `15.Dev.(aed928d)` installed fresh.
- Why not the physical device: the device's `app.lawnchair` is CI-signed;
  the locally built release APK uses a different (debug) keystore, so an
  update-in-place install is impossible and uninstalling the owner's primary
  launcher was not permitted. The Issue #164 acceptance allows a "physical
  device / API 36 environment"; the release run uses the API 36.1 AVD with
  its default 4 × 5 workspace.
- New-folder plan on the AOSP default workspace: the workspace itself yields
  no new-folder plan (no platform classification signals on the AOSP image),
  so two movable desktop apps were given the same explicit **S1 user category
  override** through the product's own App category overrides screen
  (spec #99 surface). The planner then proposed **1 new folder** — a genuine
  release-build new-folder plan.

### Apply run

- Plan preview: 15 targets, **4 placements moved** (3 as folder members,
  1 as a folder unit), 11 preserved, **1 new folder created**.
- Result surface: `Organization was applied and verified.`
- Journal (`release-avd-journal-full.jsonl`):

```text
runId 7ebae699fc80f0b55fdb2d04c0f92786
CHECKPOINTED      stage=A4  pointId 9dd357b13456d681e294aaa6bbf19e09
APPLY_COMMITTED   stage=A6
APPLY_VERIFIED    stage=A8  {preserve: 11, update: 4, insert: 1}
```

- Row accounting: pre 15 rows → post **16 rows** (one new folder row).

### Explicit recovery (AC-164-05)

- Result surface: `The saved layout was restored.`
- Journal:

```text
RECOVERY_REQUESTED  pointId 9dd357b13456d681e294aaa6bbf19e09
                    recovery.pointOriginRunId = 7ebae699fc80f0b55fdb2d04c0f92786
RECOVERY_RESTORED   pointId 9dd357b13456d681e294aaa6bbf19e09
                    recovery.pointOriginRunId = 7ebae699fc80f0b55fdb2d04c0f92786
```

- Row accounting: post-recovery favorites **15 rows, exactly equal to the
  pre-apply rows**.

## Safety observations (both runs)

- No weakened verification: both runs passed the unchanged exact A7
  comparison; both explicit recoveries passed the exact restore verification
  before `RECOVERY_RESTORED`.
- The layout always ended in the correct state: verified apply left the
  planned layout, recovery restored the exact pre-apply rows.
- Recovery points, locks, profiles, and the recovery store behaved per
  ADR-0003/spec-13; no diagnostics schema fields beyond the existing
  projections were used.
