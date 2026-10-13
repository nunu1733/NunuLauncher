# Physical discard-notice follow-up

Status: owner-observed device result; independent audio proof absent. Source and instrumentation semantic evidence are separate. PR #580 subsequently passed its exact-source/final-head CI and separate audit and was merged as `840603371c498fa6162192facf9a2aa73dadc242`. Date: 2026-10-12. Device identity is recorded in [identity-and-provenance.md](identity-and-provenance.md).

## Build and observation

The observed app used source `41d8753d7a23eac495856f31d57774a7b8e5d07a`, the clean `f2592577f9675a5153ffa5819651e1001b5c518e` basis plus the single production #579 patch from `05c194843596efa5b9e46b519c3ee7e3197edb37`. The app APK SHA-256 was `83884d094e05a9f1efcf96c5868e72f82a2361b022093eaf431479151dcd5abc`.

Before the owner confirmation, one selected item was asserted. The owner rotated the Pixel and reported `一度だけ聞こえた` (“I heard it once”). This is a **narrow owner-observed audible-once PASS**. There is no independent audio recording or speech-timing trace, so the observation does not establish exact-once delivery independently.

A later UI capture, after the owner response, had zero notice nodes and zero selected items. It does not independently prove that the notice was audible or delivered only once. Why selection was zero and the notice absent in that later capture is unknown; this report does not infer another rotation or other cause. The attached [sanitized receipt](physical-discard-notice-receipt.json) records both the owner oracle and this capture limit. The raw screenshots and XML remain private; selected hashes are in [private-followup-inventory.json](private-followup-inventory.json).

## Data and teardown

An initial read under the wrong database name returned 54 bytes and was not SQLite; that output was rejected and excluded. The correct `launcher_5_4_4` database was then read-only. Its 43 favorites rows, across all columns, exactly matched the earlier `after-restored.db` snapshot. No Apply action occurred.

TalkBack was removed and the five pre-existing accessibility-service entries were restored byte-for-byte. Final settings recorded for this follow-up were `accessibility_enabled=1`, font scale `1.0`, OS auto-rotate `1`, `user_rotation=0`, Simeji as IME, app locale `ja-JP`, and Nova as HOME. The owner accepted the current usable settings state. The original historic font, rotation, and locale values remain unknown; current-state acceptance does not reconstruct them.

The original debug APK was restored in place and matched SHA-256 `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`. This physical observation does not stand in for the separate source/instrumentation semantic proof, the separate #579 CI/audit/merge results are recorded in matrix §15.

## Final scoped device recheck

At 2026-10-12T14:52:22.157011+00:00 main independently rechecked the restored debug APK SHA260bcc…ff55da, font1.0, OS auto-rotateON, user_rotation0, Simeji, appja-JP, Nova HOME, and TalkBackOFF. Protected release still reports15.Dev.(#45), lastUpdateTime2026-10-03 18:22:17. This was read-only and did not inspect notifications or personal app contents. Private receipt SHA-256 `371d240a17ded0d635f7e7f84025518386d6366bac80ea0826c6e6965b4c1256` is retained outside publication. Home-rotationOFF and removed extra Gboard languages remain the previously verified teardown observations; this final check did not reopen their settings UI or reconstruct unknown historic originals.
