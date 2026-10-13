# Curated native operations evidence

This directory contains a curated subset from the owned API 37 Google APIs emulator run on production source 75492ec6407b4c36645186c519bdd93583008f5d (app APK SHA-256 a02cf76f0557c76cf751d852effb4d724384db33c0225c9ad11cba10fe7bd74e). Device fingerprint: google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys.

## Selected screenshot/XML pairs

| Pair | Evidence |
|---|---|
| [051 PNG](051-restore-preview-after-native-init.png) · [XML](051-restore-preview-after-native-init.xml) | Matching owned archive preview after Launcher initialization and before the successful normal-route restore. |
| [056 PNG](056-folder-open.png) · [XML](056-folder-open.xml) | Native open of the synthetic Benchmark folder. |
| [058 PNG](058-folder-back.png) · [XML](058-folder-back.xml) | Back returned to the home screen; persisted state stayed byte-identical. |
| [061 PNG](061-drag-start.png) · [XML](061-drag-start.xml) | Synthetic fixture layout before the bounded native drag. |
| [063 PNG](063-drag-result.png) · [XML](063-drag-result.xml) | One fixture item moved; database receipt records the one-row field delta. |
| [091 PNG](091-probe-visible-in-drawer.png) · [XML](091-probe-visible-in-drawer.xml) | Installed permissionless probe package appears in the native drawer. |
| [113 PNG](113-probe-removed-from-drawer.png) · [XML](113-probe-removed-from-drawer.xml) | After successful uninstall, the probe is absent from the refreshed drawer. |

Two proposed pairs were deliberately excluded after viewing: 069 PNG depicts Settings while its XML reports the restore-preview screen; 097 PNG depicts the app drawer while its XML reports only the probe label. Null-root transitions, failed swipe attempts, and their captures are not used as success evidence.

## Permissionless probe source

- [Manifest](probe-AndroidManifest.xml)
- [Activity source](probe-MainActivity.java)

The probe package is dev.nunu.phase3.installprobe and its screen label is “Phase3 install probe”. The manifest contains one exported MAIN/LAUNCHER Activity and no permission, network, or widget declaration. The APK, build script, signing material, database, archive, private capture JSON, and device-specific paths are not included.

The privacy-filtered [receipt](receipts.json) records safe hashes, row counts, bounded row-delta summary, install/uninstall outcomes, and known evidence limits. The full narrative is in [native-operations.md](../native-operations.md).

Native runtime observations are complete. [Owned fixture teardown](../owned-fixture-teardown.md) records stop/release and baseline limits; [cold settings restore](../cold-settings-restore.md) records the supported-entry correction. PR588 source audit/publication checks passed and it mergeda9ec830a; PR573 final closure is governed by matrix§15. Current closure decision belongs to matrix§15/Issue516; Phase4 cutover is separate.


Candidate072 is excluded: transient BAL-blocked toast in PNG absent from XML.
