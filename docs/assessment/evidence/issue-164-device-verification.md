# Issue #164 device verification — new-folder plans reach A8 (Pixel 9a, debug + release)

> Recorded: 2026-08-29 (re-verification after the implementation review)
> Tested head: `344eabe73a` — both APKs are `15.Dev.(344eabe)`, built from the
> exact reviewed head
> Redacted evidence only: opaque run/point IDs, closed phases/stages, counts,
> and row-accounting/byte-equality results. Raw favorites rows are not
> committed; row assertions were computed locally from pulled copies.

## Environment

- Physical **Pixel 9a** (`tegu`), 1080x2424, Lawnchair grid **4 × 5**, hotseat
  4 — the Issue #164 reproduction device. The device OS upgraded from
  Android 16 (as reported in the issue) to **Android 17 (SDK 37)** between
  the report and this verification; device, grid, and default-workspace
  shape are unchanged and the fresh default workspace reproduces the issue's
  exact 17-row baseline and new-folder plan.
- The device's daily launcher is Nova; both Lawnchair builds were installed
  fresh for the runs and uninstalled afterwards, with the default home
  restored to Nova. No other app or data was touched.
- Supplementary run: API 36.1 AVD (`avd-release-journal-full.jsonl`,
  production code of head `aed928dfc6` — identical to `344eabe73a`'s), kept
  from the first verification pass.

## Run A — debug (`app.lawnchair.debug`) on the Pixel 9a

- Fresh default workspace: **17 pre-apply favorites rows** — the issue's
  exact baseline.
- Manual flow: Settings → ホーム画面 → ホームレイアウトを整理 → 整理を確認
  → 確認した整理を適用.
- Plan preview: 17 targets, **5 placements moved** (2 as folder members,
  2 as single placements, 1 as a folder unit), 12 preserved,
  **1 new folder created** — the issue's exact plan shape.
- Result surface: `整理を適用し、検証しました。` (applied **and verified**).
- Journal (`device-debug-journal-full.jsonl`):

```text
runId 1052815cfc464b617b6ccde74f2fc3a3
CHECKPOINTED      stage=A4  pointId 1430e0b7d3aca8c51c684aacb993128b
APPLY_COMMITTED   stage=A6
APPLY_VERIFIED    stage=A8  {preserve: 12, update: 5, insert: 1}
```

- Row accounting: pre 17 rows → post **18 rows** (one new folder row,
  `_id=19`, desktop). The allocated folder id `19` byte-sorts before `2` —
  the exact intended-vs-capture divergence shape that failed A7 before the
  fix — now verified through it. All 18 rows are accounted for by the plan
  (5 moved, 12 preserved, 1 inserted).

### Explicit recovery (AC-164-05)

- Flow: `以前のレイアウトを復元` → `保存されたレイアウトを復元` → result
  `保存されたレイアウトを復元しました。`
- Journal:

```text
RECOVERY_REQUESTED  pointId 1430e0b7d3aca8c51c684aacb993128b
                    recovery.pointOriginRunId = 1052815cfc464b617b6ccde74f2fc3a3
RECOVERY_RESTORED   pointId 1430e0b7d3aca8c51c684aacb993128b
                    recovery.pointOriginRunId = 1052815cfc464b617b6ccde74f2fc3a3
```

- Row accounting: post-recovery favorites **17 rows; the tuple set
  (`_id, itemtype, container, screen, cellx, celly, spanx, spany, rank`) is
  exactly equal to the pre-apply rows**.

## Run B — release (`app.lawnchair`) on the Pixel 9a

- Fresh install of the release build on the same device and the same fresh
  default workspace (17-row shape; onboarding proposal dismissed with `後で`).
- Plan preview: identical to Run A — **1 new folder created** (5 moved,
  12 preserved).
- Result surface: `整理を適用し、検証しました。` (applied **and verified**).
- Journal obtained through the **supported diagnostics export route**
  (Settings → オーガナイザー診断 → 書き出し, SAF save to Downloads; release
  builds are not run-as capable):
  `device-release-export-apply.json`:

```text
runId 4ca4787f46b9f056433a0e6c6c78cc27   (appVersion 15.Dev.(344eabe))
CHECKPOINTED      stage=A4  pointId 01a165822f665b5906c0fd2dc551df66
APPLY_COMMITTED   stage=A6
APPLY_VERIFIED    stage=A8  {preserve: 12, update: 5, insert: 1}
```

- Row accounting is not available on the release build (no run-as, device
  not rooted); Run A carries the DB-level accounting on the identical
  default workspace.

### Explicit recovery (AC-164-05)

- Result surface: `保存されたレイアウトを復元しました。`
- Journal: `device-release-export-recovery.json`:

```text
RECOVERY_REQUESTED  pointId 01a165822f665b5906c0fd2dc551df66
                    recovery.pointOriginRunId = 4ca4787f46b9f056433a0e6c6c78cc27
RECOVERY_RESTORED   pointId 01a165822f665b5906c0fd2dc551df66
                    recovery.pointOriginRunId = 4ca4787f46b9f056433a0e6c6c78cc27
```

## Safety observations (all runs)

- No weakened verification: every apply passed the unchanged exact A7
  comparison; every explicit recovery passed the exact restore verification
  before `RECOVERY_RESTORED`.
- The layout always ended in the correct state: the verified apply left the
  planned layout, and recovery restored the exact pre-apply rows
  (byte-equal on debug).
- Recovery points, locks, profiles, and the recovery store behaved per
  ADR-0003/spec-13; only existing diagnostics projections (opaque IDs,
  phases, counts) were used.
- Note on the supplementary AVD run: the AOSP image provides no platform
  classification signals, so its new-folder plan was produced via the
  product's S1 category-override surface (spec #99); the primary Pixel 9a
  runs above needed no such aid — the plain default workspace produces the
  new-folder plan, exactly as the issue reported.
