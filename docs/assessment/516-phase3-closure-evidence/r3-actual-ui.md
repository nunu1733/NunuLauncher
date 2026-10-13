# R3 actual UI evidence and cancellation boundary

Status: curated evidence for successful staged UI actions and a no-write cancellation; no actual Apply is claimed. Date: 2026-10-12.

## Provenance and fixture

The run was on the disposable owned API37 emulator `emulator-5560`, fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`, with no accounts and synthetic fixture rows. These PNGs are owned-emulator captures, not raw physical-device screenshots. The captured UI run used production source SHA `f2592577f9675a5153ffa5819651e1001b5c518e`, based on `bc0171f25374fadf2d6296a0df4c29022416f12f` plus the accepted #565 cherry-pick, with app APK SHA-256 `898e4b3569c231d97c171b539ca27f4262c5c10e9f6de49b0575c103779fcd79`.

The isolated fixture had 42 synthetic favorites. Rows seeded as `UNKNOWN` (ordinal 0) were adapted to `UNLOCKED` (ordinal 1); Fixture 06 was explicitly `LOCKED` (ordinal 2). The retained screenshots show the organizer edit surface and synthetic fixture labels. Each retained PNG is 1080×2424 and has a same-stem XML/JSON capture. Each hierarchy was retrieved only after the UI-automator dump completion marker appeared. The screenshot and hierarchy pairs were manually checked for the reported states. The temporary capture helper and per-capture command transcript are not included in this packet.

The selected `00-foreground-confirmed` capture follows organizer foreground confirmation; earlier System UI dialogs and stale routes are excluded. For the fresh move sequence, `34-reopened-ready` shows zero selected items; `35-selected04` and `36-selected05` show selection counts of one and two. The earlier partial Page 3 image has been removed from the curated bundle.

## Observed behavior

The baseline capture shows Page 1 with 0 selected, the existing Benchmark folder containing 2 items at cell (0,1), Fixture 04 at (1,1), Fixture 05 at (2,1), and locked Fixture 06 at (3,1). The tap sequence selected 04 and 05, deselected 04, then reselected it. Folder non-eligibility and locked-item refusal were also observed; the lock message said the icon remains locked when moved.

The retained actual tap frames show the selection counter and item state at each step:

| Capture | Visible state |
|---|---|
| [`01-select04`](r3-actual-ui/01-select04.png) ([XML](r3-actual-ui/01-select04.xml), [JSON](r3-actual-ui/01-select04.json)) | `1 selected`; Fixture 04 is selected. |
| [`02-select05`](r3-actual-ui/02-select05.png) ([XML](r3-actual-ui/02-select05.xml), [JSON](r3-actual-ui/02-select05.json)) | `2 selected`; Fixtures 04 and 05 are selected. |
| [`03-deselect04`](r3-actual-ui/03-deselect04.png) ([XML](r3-actual-ui/03-deselect04.xml), [JSON](r3-actual-ui/03-deselect04.json)) | `1 selected`; Fixture 04 is deselected and Fixture 05 remains selected. |
| [`04-reselect04`](r3-actual-ui/04-reselect04.png) ([XML](r3-actual-ui/04-reselect04.xml), [JSON](r3-actual-ui/04-reselect04.json)) | `2 selected`; Fixtures 04 and 05 are selected again. |
| [`05-refuse-folder`](r3-actual-ui/05-refuse-folder.png) ([XML](r3-actual-ui/05-refuse-folder.xml), [JSON](r3-actual-ui/05-refuse-folder.json)) | The count remains `2 selected`; Benchmark remains unselected and is described as `not selectable`. |
| [`06-refuse-lock`](r3-actual-ui/06-refuse-lock.png) ([XML](r3-actual-ui/06-refuse-lock.xml), [JSON](r3-actual-ui/06-refuse-lock.json)) | The count remains `2 selected`; Fixture 06 remains unselected, and the capture shows “This icon is placement-locked. The lock is kept when moved.” |

The lock explanation is visible inline in the captured screen. No toast-delivery claim is made.

The PNG/XML/JSON bytes for `02-select05`, `04-reselect04`, and `05-refuse-folder` are identical: all record the same two-item selection, with Benchmark still unselected and no separate folder-rejection message. The folder capture therefore supports the unchanged selection state and the hierarchy's non-selectable label; it does not show distinct visual feedback for the folder attempt.

Four changes were staged against fixture items, with Reset after each action:

| Staged action | Actual UI evidence |
|---|---|
| Add to folder | `13-folder-staged` shows the existing Benchmark folder at Page 1 cell (0,1) with its count increased from 2 to 4; Fixture 06 remains visible and locked. |
| Create folder | `17-create-staged` shows Benchmark still at count 2 and a new folder at Page 1 cell (1,1) containing the two selected items. |
| Remove from Home | `21-remove-staged` shows the selected items removed from the visible Page 1 projection while Benchmark and locked Fixture 06 remain. |
| Move to Page 3 | `37-move-dialog` shows Page 3 as a destination. `38-moved-page3` shows the source cells cleared while Benchmark and locked Fixture 06 remain, with `Apply (2)` staged. After three 800 ms edge-scroll gestures from (1065,1700) toward (650,800), `39-move-scroll-3` shows Page 3 with Fixture 04 at cell (0,2), bounds `[52,884][290,1122]`, and Fixture 05 at cell (1,2), bounds `[301,884][539,1122]`. Both target cards and labels are within the captured viewport. The selection remains the intended two items; Fixture 34 is not selected. |

The final action ended with Cancel; no Apply confirmation occurred. `cancel-receipt.json` records 42 favorites rows before and after, exact equality across all `favorites` columns, `no_apply: true`, and matching database SHA-256 values (`2a35ca7d8ca380e30bff8a9742ca8c41f9bffb6eeebb69d9fc57891f08ff15b5`). This supports the cancellation no-write result for this run. It does not show a committed layout edit, actual Apply behavior, or an apply rollback.

## Curated files and limits

The `r3-actual-ui/` folder contains the original foreground-confirmed and staged-action captures, the six actual selection/eligibility captures above, the fresh 34–39 move sequence, and the completed cancellation receipt. `SHA256SUMS.txt` lists hashes computed directly from the curated bytes. No APK, database, full build log, device serial, or private icon is included. The fixture labels and fixture icon content are synthetic. The Page 3 visibility claim applies to Fixture 04 and Fixture 05 in the captured state; it is not a general no-clipping claim for every card or viewport.
