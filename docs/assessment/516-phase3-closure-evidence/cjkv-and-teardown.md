# CJKV and physical keyboard follow-up / teardown

Status: executed bounded evidence. Date: 2026-10-12. Physical Pixel 9a/API 37; exact APK/build basis: [identity record](identity-and-provenance.md), last table row (`260bccda…ff55da`).

## Existing physical keyboard result

The previous session observed Dell Bluetooth keyboard `IsExternal: true` with KEYBOARD/DPAD sources. Owner reported representative input, Esc cancellation, horizontal-to-vertical rotation and Lawnchair rotation-setting restoration successful. This is **owner-observed PASS** for actions, and main-observed for connection/current settings. ADB Virtual input is not substituted for that physical action. Those completed actions were not repeated in this continuation.

## CJKV conversion and feedback

The initial unavailable-language claim was investigated. Gboard was installed with Japanese 12-key layout; it was not English-only. Main recorded that baseline before temporarily adding Simplified Chinese/Pinyin, Korean/Dubeolsik and Vietnamese/QWERTY. No new IME app was installed.

The debug launcher's search field contained only a synthetic `qzxv516` prefix and test characters; contacts permission remained denied. No personal app/contact result was used as evidence, and no search-result external action was clicked.

| Case | Actual action / observation | Result |
|---|---|---|
| zh | Pinyin `nihao` produced the visible `你好` candidate; candidate tap committed `你好` to the search EditText | PASS, main visual + targeted UI-field observation |
| ko | Dubeolsik key sequence `gksrmf` produced underlined composition `한글`; space committed it | PASS, main visual |
| vi | Telex `tieengs` produced visible diacritics in `tiếng`; a follow-up Vietnamese input was issued during TalkBack | PASS for observed Telex conversion; exact wording of the later suffix is not inferred |
| TalkBack feedback | Existing accessibility services were preserved, TalkBack was added temporarily, TTS synthesis requests were observed; owner answered `フィードバックが聞こえる` | owner-observed audible feedback PASS; pronunciation quality/exactly-once speech is not claimed |
| Cancellation | Synthetic query cleared; search dismissed with BACK; HOME returned to Nova | PASS, no external search action |

These CJKV operations used ADB-generated key/tap events through Gboard, not owner physical typing. They supplement the distinct owner physical-keyboard evidence. Safe rotation/cancellation are traced to the already completed representative keyboard/ja tests on the same restored APK; no language-by-language rotation cross-product is claimed.

Only synthetic-field observations are published. Raw physical screenshots and input-method dumps are retained privately, with hashes in the publication inventory. They are not uploaded wholesale.

## Teardown and owner acceptance

Main restored enabled accessibility services **byte-for-byte** from a private before snapshot; TalkBack was removed. The three temporary Gboard languages were deleted through its settings UI; final language list again contained Japanese / 12-key only. Gboard Japanese subtype was restored, then default IME returned to Simeji. `show_ime_with_hard_keyboard` was restored from 1 to its recorded baseline 0. The synthetic query was cleared.

Read-only final observations: fontScale 1.0; OS auto-rotation ON; HOME Nova; IME Simeji. During this CJKV leg there was no physical APK install, HOME-role change, contacts/notification grant or layout edit. A later separate #526 follow-up temporarily installed the UI validation APK; see the capture diagnosis below. Prior current receipt also fixes user_rotation0, app locale ja-JP and Lawnchair home rotation OFF.

Owner answered `現在設定で問題ない` after reviewing fontScale1.0 / OS auto-rotationON / Lawnchair home-rotationOFF / Simeji / ja-JP / Nova. This records acceptance of the **current usable setting state**, not reconstruction of an unknown historical original. Historic font/rotation/locale and original O3 LNP CLI baseline remain unproven. The restored APK does not declare LNP; that fact does not prove historic permission restoration.

The prior owner report `通知はない` settles current absence of the deliberate crash notification. It does not identify who dismissed it or when, and does not prove the failed controlled owned-dot cycle. No personal notification content, notification shade capture, global notification dump or clear-all was used by main.

## Separate #526 capture diagnosis and subsequent TalkBack notice follow-up

A clean UI validation build at `f2592577f9675a5153ffa5819651e1001b5c518e` (#538 source `bc0171f25374fadf2d6296a0df4c29022416f12f` plus accepted #565 fix) had APK SHA-256 `898e4b3569c231d97c171b539ca27f4262c5c10e9f6de49b0575c103779fcd79`. The matching debug signer allowed an in-place temporary install. A private before-data backup and exact prior260bcc… APK were preserved. HOME stayed Nova.

The existing debug layout contained43 rows, including two unopened drag-folder children with paired raw cell sentinels -1/-1. Targeted exception-only JDWP tracing identified `PersistentRow.<init>` / `PersistenceManifest.kt:80`, not a TalkBack defect. The unchanged15/16 representation rejected those nonspatial raw cells before canonical capture. This is tracked in [#579](https://github.com/nunu1733/NunuLauncher/issues/579); the attempt is not a TalkBack PASS. No direct row normalization or replacement was used.

Debugger and forwarding were removed. The preserved APK was restored in-place and re-hashed to260bcc…ff55da; independent readonly SQLite comparison confirmed **all43 favorites rows exactly unchanged** against the before backup. HOME returned to Nova. TalkBack and the recorded system/IME settings were not changed during this diagnostic attempt. The later #579 follow-up is recorded in [physical-discard-notice.md](physical-discard-notice.md) with its [sanitized receipt](physical-discard-notice-receipt.json): before confirmation one selected item was asserted, and the owner reported `一度だけ聞こえた` (“I heard it once”) after rotating the Pixel. This is owner-observed audible-once PASS without independent audio proof. A later capture showed zero selected items and no notice nodes; the cause is unknown and that capture does not prove the audible event or exact-once delivery. A read-only check of the correct database found all43 favorites rows and columns equal to the earlier restored snapshot; no Apply occurred. TalkBack and system/IME settings were restored, and the owner accepted the current usable state; historic font/rotation/locale remain unknown. #579 source/final-head CI and separate audit subsequently passed; PR #580 merged as `840603371c498fa6162192facf9a2aa73dadc242`. The later read-only device recheck is recorded in the physical notice receipt.
