# Curated SAF and import evidence

This directory contains selected PNG screenshots and matching fresh UI hierarchy XML captures from two owned API 37 emulator runs. The earlier `ae697` captures document backup/restore and the original pre-persistence import crash. The repaired replay uses production source `75492ec6407b4c36645186c519bdd93583008f5d` and records successful same-owner import, cold proposal recovery, and confirmed discard without layout apply.

## Repaired replay captures

| Pair | Evidence |
|---|---|
| [012 PNG](754-012-request-review-501.png) · [XML](754-012-request-review-501.xml) | Redacted request review shows 501 included items before saving. |
| [015 PNG](754-015-named-save.png) · [XML](754-015-named-save.xml) | System document picker with the generated request filename entered for save. |
| [022 PNG](754-022-local-reply-picker.png) · [XML](754-022-local-reply-picker.xml) | Import screen exposing the file-based reply picker route. |
| [024 PNG](754-024-import-success.png) · [XML](754-024-import-success.xml) | One wish recognized; 500 unstated items; no layout changes applied. |
| [030 PNG](754-030-cold-hub.png) · [XML](754-030-cold-hub.xml) | After process restart, both the active request and imported proposal are available. |
| [031 PNG](754-031-cold-review.png) · [XML](754-031-cold-review.xml) | Recovered proposal summary is available for review after restart. |
| [035 PNG](754-035-discard-confirmed.png) · [XML](754-035-discard-confirmed.xml) | Confirmed discard leaves the request valid and removes the imported proposal. |

Each pair includes a PNG and its XML hierarchy capture. The reply editor, live export ID/candidate reference, raw request and reply, pending-record JSON, and initial null-root or pre-confirmation captures are excluded. Database/archive/APK binaries and unrelated physical data are not included.

[`../saf-backup-and-import.md`](../saf-backup-and-import.md) records run identity, DB/preferences checkpoints, production parity, CI limits, and remaining closure work. [`receipts.json`](receipts.json) is the machine-readable privacy-filtered summary. Private replay artifacts remain outside this curated packet.

This is bounded runtime evidence for matrix§15/PR573. The owned 43-row restore, folder open/Back, bounded safe drag, and probe package add/remove refresh are recorded in [native-operations.md](../native-operations.md). [Owned fixture teardown](../owned-fixture-teardown.md) is complete with historical limits. [Supported cold settings restore](../cold-settings-restore.md) is GREEN and its source audit is accepted; source PR588 merged with checks passed; final closure is governed by matrix§15/PR573. Current closure decision belongs to matrix§15/Issue516.
