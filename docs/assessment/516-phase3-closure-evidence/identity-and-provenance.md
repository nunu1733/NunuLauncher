# Phase 3 artifact identity and provenance

Status: verified evidence record; closure decision governed by matrix§15/PR573. Date: 2026-10-12.

Physical device: Google Pixel 9a, API 37, fingerprint `google/tegu/tegu:17/CP3A.260905.009/16091614:user/release-keys`. Serial, device paths, personal app inventories and raw device backups remain private. Every physical action was explicitly targeted to the serial in the private identity receipt.

| Surface | Source/build basis | APK SHA-256 |
|---|---|---|
| O1 UI / initial V3 | PR #538 head `bc0171f25374fadf2d6296a0df4c29022416f12f` | `34316901f22f11e64eda94babf1f43ca04b3c4e8996f70847ad0db50a9388bec` |
| O1 instrumentation | Companion test APK for that slice | `62da3b8eac1e316d47aeb2228467d234a2817a91b25ed0e4df8e9ef977eaa32b` |
| O2 Calendar/date-card and share chooser | PR #541 head `54a75042305c74ddc0cb118ce6313e2c1746ccca` | `5096d9413b55f71d18fb58496beec2194e9a43f36decc0f17ab27fceed7c1f8a` |
| O3 LAN permission states | PR #543 head `57d4338bb5be5114481b98bef5a90f757b1d1f23` | `c90836379adb42e323bb56c1d400779ffcf4a5e63e166f268aaa28d65c8d819b` |
| O3 Katbin release buildType QA | Same source plus temporary release applicationId suffix `.debug`; debug certificate fallback | `33fc2bb5de4e8da5fdf105c9b62933d78be08008d3cf600c6d993392276c15bf` |
| O4 initial folder UI | Clean `33b4c061c55db6edf646a48e6a18570ce771d15f`, installed/local APK hash match | `681595e5920eecdc3bde31c7e2dd270532d018bcf12962e39b716e3ca730b26b` |
| O4 follow-ups / V6 / V8 / Bluetooth keyboard / CJKV | Precommit build on `33b4c061c55db6edf646a48e6a18570ce771d15f` plus ContactsSearchProvider ten-line deletion | `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da` |

## Restored APK / keyboard provenance closure

The last row is **not a clean-head APK** and is not attributed to PR #538. Version `16.Dev.(33b4c06)` is metadata from the build basis, not source verification.

The existing [reviewed byte-identity packet](https://github.com/nunu1733/NunuLauncher/blob/000c26903b9f0880ce551721dd89c3bb09da1ad6/docs/assessment/574-contacts-search-evidence/byte-identity.md) fixes the full tracked production basis and sole provider diff. Production blob changed from `cf953262db52c6272acb52cc27a5d0c3dde142b8` to `ebfdc1d38277fd36795790d8c54fcf5b4b095475`; final bytes SHA-256 `8f4e2e0819e26f94ae01f8fa96cea02bb603d2305ce3fc6ce53667c9af306e9a`. The ten deletions are committed at `516577701b23047e6ad70c4e3809dd56134c45fe` and remain byte-identical at PR #575 head `000c26903b9f0880ce551721dd89c3bb09da1ad6`.

On 2026-10-12 main re-read that durable packet, verified the referenced production blob/diff, and matched the physical restored APK hash to the private preserved original APK. This resolves the keyboard follow-up's source attribution with an explicit base-plus-diff identity. It does not invent a clean commit build or a new physical execution. PR #575's [exact-head CI attempt 2](https://github.com/nunu1733/NunuLauncher/actions/runs/38141049003/attempts/2) remains prior evidence; it was not rerun. Initial unrelated Compose failure remains tracked in #576.

The QA Katbin variant is a release **buildType**, signed with the debug fallback and installed only as `app.lawnchair.debug`. It is not a production-keystore release proof. Temporary suffix configuration was not committed. No trust-anchor/NSC change is claimed.
