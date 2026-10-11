# Issue #574 — Real-device contacts search journey (API 37, physical tegu)

Date: 2026-10-11 20:48–21:05 JST
Device: 56231JEBF08674 (Pixel physical, tegu, API 37, CP3A.260905.009)
Verdict: **PASS**

## Identity / source base

- Installed APK: `app.lawnchair.debug` base.apk
  sha256 = `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`
  (matches expected `Lawnchair.16.Dev.(33b4c06).github.debug.apk`)
- Source base: worktree `/Users/nunu/Documents/work2/NunuLauncher-574` at commit `33b4c061`
  **plus uncommitted prod diff**: `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/ContactsSearchProvider.kt`, 10 deletions (account projection/reads/constants removal, Issue #574). Not a clean-tree SHA; the diff is uncommitted in that worktree.
- Oracle link accepted by user: Issue #574 comment 6108156834 (all-API unused-account removal).

## Baseline (reconfirmed before change)

- `READ_CONTACTS: granted=false` (USER_SET flags, denied)
- `pref_searchResultPeople` = `false` (shared_prefs/com.android.launcher3.prefs.xml)
- IME: `com.adamrocker.android.input.simeji/.OpenWnnSimeji`
- Default HOME: Nova (`com.teslacoilsw.launcher/.NovaLauncher`), untouched as default

## Fixture

- Accountless synthetic raw contact created via `adb shell content insert`
  (`content://com.android.contacts/raw_contacts`, account_name/account_type = null)
- raw_contact_id = **423**
- name data row: `ZZ574_QA_20261011` (mimetype vnd.android.cursor.item/name)
- phone data row: `5550574` (mimetype vnd.android.cursor.item/phone_v2, type 2=mobile)
- Verified by scoped query `raw_contact_id=423` only (no existing personal contacts queried).

## Journey

1. People pref enabled via UI: PreferenceActivity deep route
   `SearchProviderPreference(id=CONTACTS)` → internal permission dialog
   (「連絡先へのアクセス権限が必要です」) → 設定を開く → system App info →
   権限 → 連絡先とアカウント → 許可する. `READ_CONTACTS: granted=true`.
   MainSwitch toggled ON → `searchResultPeople" value="true"`.
   Evidence: `prefs-people-on.png`
2. Debug launcher drawer search (`app.lawnchair.LawnchairLauncher`, swipe-up drawer,
   in-drawer search field). IME temporarily switched to Gboard LatinIME because
   Simeji converted `input text` ASCII to kana; restored to Simeji afterwards.
3. Query `ZZ574_QA` → result section 「デバイスの連絡先」 showing
   `ZZ574_QA_20261011`. No web suggestions used for the contact verification.
   Evidence: `search-results.png`
4. Tapped result → system Contacts `QuickContactActivity` (launched from uid 10594
   app.lawnchair.debug, see logcat-search-window.log) showing
   `ZZ574_QA_20261011`, phone `5550574` (携帯), no other person data.
   Evidence: `contact-details.png`

## Scoped logs (minimized 2026-10-11; raw originals private outside git, see ../redaction-provenance.md)

- `logcat-scoped.log`: minimized to filtered-empty note (original 106 lines PID 27711 pref-enable noise; 0 evidence lines).
  `Invalid column` count in inspected window = **0**; not proof for whole app (known filter limitation).
- `logcat-search-window.log`: minimized to 5-line tap window 20:58:56-20:59:15 (original 682 lines).
  `Invalid column` count in inspected window = **0**. Key line: debug app (uid 10594) launching
  `QuickContactActivity` with `content://com.android.contacts/...` VIEW intent.
- Limitation: the PID-scoped capture covers the pref-enable session; the search
  window is covered by the buffer-filtered capture (original PID was rotated by
  an intermediate force-stop needed to escape a stale subpage).

## Cleanup (all verified)

- `content delete --uri .../raw_contacts --where "_id=423"` →
  scoped re-query `_id=423` and `raw_contact_id=423` on data: **No result found** (both)
- `READ_CONTACTS: granted=false` (pm revoke; baseline restored)
- `searchResultPeople" value="false"` (toggled OFF via UI on the People screen)
- IME restored: `com.adamrocker.android.input.simeji/.OpenWnnSimeji`
- HOME returned to Nova (`topResumedActivity=com.teslacoilsw.launcher/.NovaLauncher`)
- No global settings (font/rotation/locale/back gesture) touched; Fixture 05 and
  other home fixtures untouched.

## Notes

- First attempt at the drawer search tapped the hotseat QSB (Google web search)
  by mistake; backed out immediately, no web suggestion was used as evidence.
- The route JSON extra needed fully-qualified type discriminator to reach the
  People screen directly; fallback to Root settings was used once (harmless).
