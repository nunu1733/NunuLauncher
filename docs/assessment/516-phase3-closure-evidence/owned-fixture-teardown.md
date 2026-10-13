# Owned fixture teardown

Status: completed stop/release; historical restoration limits retained. Date:2026-10-13.

Both task-owned API37 validation emulators have been stopped after recording their ownership. The long-lived fixture HOME was returned to its recorded app.lawnchair.debug baseline before stop; literal notification-listener baseline was independently verified at the earlier cleanup checkpoint. There is no final listener reread after later HOME changes, so a complete final-settings restoration is not claimed. The fresh cold-restore fixture retained its original Google HOME and was stopped after installed candidate APK bytes were independently read and matched SHA10580cd2ffeb273ccaa6dbc27eafe619dffb6a41d41f49315ecd84efa937c97c.

The owned AVD data and diagnostic evidence are retained privately for review; no live fixture remains from these two runs. Existing protected emulators and the physical Pixel were not changed by this cleanup. No notification contents were read. The Pixel's current usual state and unknown historical originals remain covered by [CJKV/teardown](./cjkv-and-teardown.md). [Bounded teardown receipt](./owned-fixture-teardown.json) records stop times and private-receipt hashes without paths or serials.
