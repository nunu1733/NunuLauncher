# API37 Smartspace and page-capture evidence

Status: native clock/ON-OFF UI and fixed16 page-capture GREEN complete; main/15 PR #582 CI, audit, and merge are complete. Final Phase3 closure effectiveness is governed by matrix§15/PR573; supported cold-settings restore #587/#588 is merged and its checks passed; repaired SAF Exchange Import/cold review/discard succeeded on separately identified source754. Updated:2026-10-13. Disposable emulator only, not Pixel or Phase4 cutover.

## Native placement and update

Clean16 validation source `41d8753d7a23eac495856f31d57774a7b8e5d07a`, APK SHA-256 `83884d094e05a9f1efcf96c5868e72f82a2361b022093eaf431479151dcd5abc`, API37 fingerprint `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`.

The actual Smartspace preference UI enabled Time while Date remained enabled and Weather disabled. Native home rendered “October12 at10:40PM” in `54-home-clock.png`; the next fresh capture rendered10:41PM in `55-home-clock-later.png`. Corresponding XML records the full date/time and bounds `[64,234][1058,291]`. This verifies native home placement and a real minute update; no external weather service was needed. The PNGs show the actual space-separated strings.

`57-smartspace-top.xml` records Show on home screen checked=true. Actual UI toggling produced `59-smartspace-off-settled.xml`, checked=false, with the subordinate Smartspace controls absent. A post-toggle dump returned no fresh hierarchy; it was excluded, and the settled capture was obtained separately.

## Restore/conversion boundary

Existing API37 production Nova converter ON/OFF and native-widget restore/capture runs use clean production source `06bce6e5c9fa6c3afed6b1fb0abdd545fd61d595`, app APK `754d9564217a4f0e1dc0b04d5af8788d47d43813036b85480332cb102d96473e` and instrumentation APK `7f0da3b6e8a73679d037e19a6ce2c02047cb9ffe1787811a96543329010e5967`.

- `NovaConverterBoundaryTest#converterShiftsCellYCompensatesRowsAndAppliesClampAndSkip`:1/1,18.657s.
- `NovaConverterBoundarySmartspaceOffTest#converterKeepsRoundedCellYAndNovaRowsWhenSmartspaceOff`:1/1,14.473s.
- `NovaRestoreCaptureWidgetWindowTest#novaRestoreWithWidget_returnsAfterSettleBarrierWithBoundRowAndReadyCapture`:1/1,9.213s.

These restore/conversion boundaries complement the native clock observation; they do not claim that the clock screenshot itself came from a restore. Actual SAF preference/archive restoration is owned by the [SAF receipt](./saf-backup-and-import.md).

## QSB OFF defect and repair

[#581](https://github.com/nunu1733/NunuLauncher/issues/581) tracks the existing adapter omitting occupied first screen0 when QSB is OFF. After the actual OFF UI capture, the installed test APK was read and SHA-256 matched `7f0da3b6e8a73679d037e19a6ce2c02047cb9ffe1787811a96543329010e5967`. Existing `PageCaptureInstrumentationTest#modelOnlyEmptyPagesExcludedFromCapture` then failed1/1 in2.760s on the source41d APK above: `Model page inventory omits a page referenced by favorites`, at `RowManifestCodec.capture:75`. Raw instrumentation status is preserved in `581-page-off-red.txt`. ADB exit0 is not interpreted as a test pass.

This is a supplemental16 validation-source RED against accepted Spec155; it does not establish the distinct Spec155 QSB-AC-06 Loader-deletion oracle or physical QSB evidence. The fixed16 production port is clean source `ae69715379314f24338304ca5a0b2b4f9e9f7c43`, app APK `9ff6c8483ff74b00ed50ee295d994a001439fa69f22315f0b39af6436b049bd4`. Production assembly passed5m27s with14 executed tasks. The existing six-case regression class from main/15 source `94d0c088df979b4d0aa32158701aa8ab62c530b4` is ported only for the16 callback signature (`bindCompleteModel` / WorkspaceData); test source SHA `78983ab9e14b5331f0262d16c9966cebc414a509b2b0783faf0d481e366f09bb` is copied as `page-capture-port-source.kt.txt`. Instrumentation APK SHA `f70fb5499ee4be81415b7839bb2d9bc13c2b8433cfd5449d3211ec00ae62f8e4`, assembly3m36s with actual test compilation; the test port is a tracked working-tree modification, so no clean-head test APK claim is made.

Actual `am instrument -w -r` class execution on the warmed Launcher passed6/6 in34.573s. `581-page-off-on-green.txt` includes each method's start and success status0, with no skip status. OFF occupied0+1, first-only, empty fallback, numeric ordering, repeated capture and ON synthesis are all covered. All real Smartspace writes execute on the main thread because its callback can recreate Launcher. The initial main/15 CI failure of all six cases on the former test-thread callback is retained as historical evidence. PR [#582](https://github.com/nunu1733/NunuLauncher/pull/582) merged the QSB-off occupied-page fix as `0ef86713b9414f0aa85c028e2e77d3ec14c0f4db` (2026-10-12T15:31:59Z); the corrected main-thread test source is `94d0c088df979b4d0aa32158701aa8ab62c530b4`. Exact all-affected/final CI [38267058803](https://github.com/nunu1733/NunuLauncher/actions/runs/38267058803) and high-risk gate [38267058744](https://github.com/nunu1733/NunuLauncher/actions/runs/38267058744) passed. Independent full-current Standards review (Luna) and Spec review approved. The audit publication is recorded at commit `eead64fabe9581f997a54924d43f660875257669`.

After the six tests, all500 saved layout rows retained exact ID/container/screen/cell/span/type/profile/rank/lock fields. Launcher refreshed raw synthetic labels to actual activity labels and added the equivalent intent package during model reload, so the full13-column projection changed; `581-page-post-six-restoration.json` records this explicitly. It is not a full byte/metadata equality claim. The canonical500 SAF repeat after that refresh is recorded separately in the SAF packet. Production adapter blobs are byte-identical between main15 and16 before (`a09f1a6e424fb021aa21463e7d45c73aed6fb725`) and after (`871261171dc2a031198e827cdb0da8872de29d67`) this repair. The earlier failed CI remains historical; PR #582's exact-head CI, independent reviews, high-risk gate, audit publication, and merge are complete.

## Publication boundary

Only synthetic owned-emulator screenshots/XML and bounded runner summaries are copied. [SHA256SUMS](./smartspace-and-page-capture/SHA256SUMS.txt) fixes their bytes. No physical notifications, personal app inventory, APK, database or preference binary is published. [Owned fixture teardown](./owned-fixture-teardown.md) is complete with preserved restoration limits.
