# Issue #574 — API 36 emulator contacts search journey (emulator-5556) — RESUMED

Date: 2026-10-11 21:11–21:20 JST
Verdict: **PASS**

Supersedes the prior identity-blocked record in this directory (history: first
attempt stopped because only the priv-app image build `16.Dev.(2987e52)` /
sha256 `5098ceb4…` was present; no UI or state actions were taken then).

## Identity

- Main backed up the original priv-app APK (sha256
  `5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278`) and
  installed the fixed APK via `adb install -r`.
- Verified before UI: `pm path` → `/data/app/~~Z-k_VIoCiZYZqHpCNibfXg==/.../base.apk`,
  sha256 = `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`,
  versionName = `16.Dev.(33b4c06)`, lastUpdateTime 2026-10-11 21:08:30. ✓
- Source base: `33b4c061` + the uncommitted 10-line deletion diff in
  `ContactsSearchProvider.kt` (account projection/reads/constants removal).
- Binary restore after cleanup is Main's responsibility (not done by this session).

## Baseline (reconfirmed)

- `READ_CONTACTS: granted=false`; People switch rendered `checked="false"`
  (run-as is denied for this package on the emulator, so pref state was read
  from the UI switch instead of shared_prefs).
- IME: `com.google.android.inputmethod.latin/.LatinIME` (original; unchanged
  throughout — ASCII input worked without IME swap).
- HOME role untouched (owner default preserved; only the debug app was run).

## Journey

1. Fixture: accountless raw contact **raw_id=1**, name `ZZ574_QA_36`, phone
   `555057436` (mobile), created via `content insert`; verified by scoped
   `raw_contact_id=1` query only.
2. People route (exact deep JSON from the API 37 run):
   `PreferenceActivity --es app.lawnchair.ui.preferences.DESTINATION_ROUTE
   '{"type":"app.lawnchair.ui.preferences.navigation.SearchProviderPreference","id":"CONTACTS"}'`
   → internal dialog "Contacts permission needed" → Grant permissions → system
   runtime dialog → Allow → `READ_CONTACTS granted=true` → switch ON
   (`checked="true"`).
3. Drawer search (swipe-up, in-drawer EditText): query `ZZ574_QA` → section
   "Contacts from device" with tile `ZZ574_QA_36`. Evidence: `search-results.png`
4. Tapped tile → `com.google.android.contacts/.quickcontact.QuickContactActivity`
   showing `ZZ574_QA_36`, phone `555057436` (Mobile), no other person data.
   Evidence: `contact-details.png`

## Scoped logs

`logcat-scoped.log`: device buffer filtered to lines mentioning
`app.lawnchair.debug` / `ContactsSearch` / `Invalid column`. 850 lines,
`Invalid column` count = **0**, SQLiteException/IllegalArgumentException = **0**.

## Cleanup (all verified)

- `content delete --uri .../raw_contacts --where "_id=1"` → scoped re-query
  `_id=1` and `raw_contact_id=1` on data: **No result found** (both).
- People switch toggled OFF via UI before revoking: `checked="false"`.
- `pm revoke` → `READ_CONTACTS: granted=false` (baseline restored).
- IME unchanged (LatinIME = original baseline).
- HOME key pressed; HOME role never modified.
- No other QA, no physical device actions, no intermediate private dumps kept
  (ui.xml removed after each use).

## Byte identity cross-reference (PR #575 review `6109082627`, docs-only)

- Tested APK sha256 `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`; basis `33b4c061` + uncommitted provider 10-line diff (final prod blob `ebfdc1d38277fd36795790d8c54fcf5b4b095475`). No clean-tree SHA claimed for the APK.
- Test blob `0c86cab4e70679f4d64a179feacf95d22811f88e`; historical first-RED bytes unproven with no invented hash; acceptance RED/GREEN VERIFIED by exact-blob rerun (RED `12:48:33` prod `cf953262...`, GREEN `12:51:07` prod `ebfdc1d3...`) per `../byte-identity.md`, `../red-result.md`, `../green-result.md`.
