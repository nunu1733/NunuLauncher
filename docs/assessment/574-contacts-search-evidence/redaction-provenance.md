# Redaction provenance (574 runtime logs)

- Date: 2026-10-11. Raw originals preserved PRIVATE outside git (binary/text only, no exports):
  archive: /var/folders/mn/pkw1gn755x51m98nvw2c36l00000gn/T/opencode/574-raw-logs/
- Original SHA-256:
  - api37/logcat-scoped.log (106 lines): 61088c540a138e1a2ee1ee2d540dd3d8b26f5bae18d53b381552c912c009eab3
  - api37/logcat-search-window.log (682 lines): 9154e02c7d7520d74996545ecae44b1cf8457fd6fff4c6c3f5317531b97e233c
  - api36/logcat-scoped.log (850 lines): 2a8c90e73b8ad63fac42a48b7814c7fb94f90070d74dd4d40ffef7cbbe5c5512
- Filter method: per-file grep for QuickContact|ACTION_VIEW|ContactsSearch|Invalid column|ZZ574|START/Displayed contact markers;
  retained ONLY bounded actual search/tap window lines (app debug to QuickContact ACTION_VIEW synthetic contact, ContactSearch/Invalid column errors).
  Excluded unrelated settings/transitions/user-package paths and prior query strings. Only test query name allowed: ZZ574_QA.
  Long transition blobs redacted with [redacted] markers; contact URIs already redacted as content://com.android.contacts/... in source.
- Results:
  - api37/logcat-scoped.log: filtered empty (startup noise only, 0 evidence lines). Not proof for whole app.
  - api37/logcat-search-window.log: 5 evidence lines kept (20:58:56 START VIEW QuickContact from uid 10594 + close 20:59:15). Invalid column count in window: 0.
  - api36/logcat-scoped.log: 3 evidence lines kept (21:12:14-21:12:41 QuickContact topActivity + close; no START line in this capture). Invalid column count in window: 0.
- Known limitation: PID-scope/pref-enable log filters cannot prove query PASS; UI photos are the primary contract.
- Privacy: no unexpected personal info found in inspected windows; any such finding would stay raw-private and not be reposted.
- Published logs are each 30 lines or fewer and committable; raw originals stay outside git.
