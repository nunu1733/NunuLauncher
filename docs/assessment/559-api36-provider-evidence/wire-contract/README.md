# Issue 559: installed API36 startRecentsTransition wire contract

Status: observed wire mismatch; proposed minimal correction, not implemented or runtime-validated.
Observed: 2026-10-09. Device: emulator-5556. No UI reruns or device mutations.

## Decisive mapping

Descriptor: `com.android.wm.shell.recents.IRecentTasks`.

| Endpoint | Transaction | Arguments after interface token |
|---|---:|---|
| Installed API36 SystemUI | **6** | PendingIntent, Intent, Bundle, IApplicationThread binder, IRecentsAnimationRunner binder |
| Installed candidate2 generated proxy | **6**, FLAG_ONEWAY | PendingIntent, Intent, Bundle, **nullable WindowContainerTransaction**, IApplicationThread binder, IRecentsAnimationRunner binder |
| Existing initial16 AOSP raw shim | **5**, FLAG_ONEWAY | PendingIntent, Intent, Bundle, IApplicationThread binder, IRecentsAnimationRunner binder |
| Existing initial16 Nothing raw shim | **6**, FLAG_ONEWAY | Same five-argument payload |

The server's transaction **5 is getRunningTasks(int)**, not startRecentsTransition.
The generated AIDL source's explicit `= 5` is not the final Binder code: the installed generated proxy proves the actual code is **6**. Do not infer transaction numbers from the AIDL literal alone.

The installed BE2A fingerprint does incorrectly miss `checkGenericBaklavaInitial()`'s BP2A/BD1A allowlist. However, **adding BE2A alone is insufficient**: it selects the AOSP raw shim's transaction 5, which this server interprets as getRunningTasks. Do not label this Google emulator Nothing OS merely to obtain transaction 6: that predicate also controls callback compatibility.

### Minimal correction identified (not applied)

For this verified BE2A API36 provider, select the existing **five-argument serialization**, with **transaction 6**, preserving the existing fail-closed rejection of non-null WCT. Introduce/extend a narrowly scoped BE2A provider decision in the initial-layout selector **and** the raw transaction-code selector; keep Nothing-specific callback behavior unchanged. Do not change the generated modern AIDL or change transaction 5 globally for other providers.

Between the proposed scopes, choose a **narrow BE2A compatibility case** (ideally bounded by API36.0 and the observed Google provider/build family). This single installed APK does **not** establish that every SDK36.0 provider with `SDK_INT_FULL < 3600001` has this same transaction number or payload. Broad SDK-only routing is not justified. Even a BE2A family rule generalizes beyond the one exact build observed here; exact build matching is the strictest evidence scope.

Wire mismatch is proven from both installed binaries. The previously reported 12-unread-byte failure is not harmless: server offset `00e2` enforces complete consumption **before** dispatch. This extraction does not simulate Android Parcel cursor advancement or claim a fresh reproduction of the exact 12-byte count. No corrected-client run occurred, so this is not a claim that all transition/callback behavior is fixed.

## Artifact identity and command extracts

```text
$ /Users/nunu/Library/Android/sdk/platform-tools/adb -s emulator-5556 shell 'getprop ro.build.fingerprint; getprop ro.build.version.sdk; getprop ro.build.version.sdk_full; getprop ro.build.id; sha256sum /system_ext/priv-app/SystemUIGoogle/SystemUIGoogle.apk'
google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys
36
36.0
BE2A.250530.026.F3
a895f88ae0539d9950a74e49f0471ca81237337f0b31d6eb5f4fe29e814d14f5  /system_ext/priv-app/SystemUIGoogle/SystemUIGoogle.apk

$ adb -s emulator-5556 shell pm list packages -f lawnchair
package:/product/priv-app/LawnchairDebug/LawnchairDebug.apk=app.lawnchair.debug

$ adb -s emulator-5556 shell 'getprop ro.nothing.version.id; getprop ro.build.nothing.version; getprop ro.build.nothing.feature.base'
[three empty values]
```

`ro.build.version.sdk_full` is the property string `36.0`; it is not a direct read of the Java integer `Build.VERSION.SDK_INT_FULL`.

After `ls /var/folders/mn/pkw1gn755x51m98nvw2c36l00000gn/T/opencode`, pulled both installed APKs with the absolute adb executable above:

```text
adb -s emulator-5556 pull /system_ext/priv-app/SystemUIGoogle/SystemUIGoogle.apk <temp>/559-SystemUIGoogle.apk
adb -s emulator-5556 pull /product/priv-app/LawnchairDebug/LawnchairDebug.apk <temp>/559-LawnchairDebug.apk
shasum -a 256 <temp>/559-*.apk
a895f88ae0539d9950a74e49f0471ca81237337f0b31d6eb5f4fe29e814d14f5  559-SystemUIGoogle.apk
5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278  559-LawnchairDebug.apk
```

No use of `/tmp/554-evidence/SystemUIGoogle.apk`.

Source root: `/Users/nunu/Documents/work2/NunuLauncher/worktree-545-impl`.
HEAD observed: `c5063e3aec9a06dea875b52a18dd2820dea3b6c4`.
`git diff 2987e525bd --` the three paths below returned no differences:

```text
SHA256                                                            Path
8bf1e20cea2d8a07d6ef000d9f3842a28eabc515f24defc2a8a739843d5588a2  quickstep/src/com/android/quickstep/SystemUiProxy.kt
4232b91ecd2aec9b3dd99dd76eaf0403eaf6f4aa591086c3b581622724bec8bc  lawnchair/src/app/lawnchair/util/Compatibility.kt
9b391c73ae417c228b0671e2c3009cb171d44140e2ed4ad79d8a2ffefef641bc  wmshell/src/com/android/wm/shell/recents/IRecentTasks.aidl
```

The worktree codegraph index pointed to another worktree; its results were not used as candidate2 evidence. Candidate source was read directly, and installed client DEX independently confirms the serializer and selector.

## Extraction method

Temporary isolated `uv run` script with dependency `androguard==4.1.3`; no global installation. Official standalone uv archive: <https://github.com/astral-sh/uv/releases/latest/download/uv-aarch64-apple-darwin.tar.gz>. APK ZipFile entries `classes*.dex` were parsed using `androguard.core.dex.DEX`, class names filtered, and only selected methods emitted using `get_instructions_idx()`, `get_name()`, `get_output()`. No bulk decompilation or historical artifact search.

Commands (temporary script removed after extraction):

```text
uv run 559-dex.py 559-SystemUIGoogle.apk IRecentTasksImpl
uv run 559-dex.py 559-LawnchairDebug.apk 'Lcom/android/wm/shell/recents/IRecentTasks$Stub$Proxy;'
uv run 559-dex.py 559-LawnchairDebug.apk 'Lcom/android/quickstep/SystemUiProxy'
uv run 559-dex.py 559-LawnchairDebug.apk 'Lapp/lawnchair/util/CompatibilityKt;'
```

Exact selected instruction excerpts are in [dex-excerpts.txt](dex-excerpts.txt). Addresses are method-relative **byte offsets**; DEX branch displacements shown with `h` are in 16-bit code units. Thus `004e + 2*0007 = 005c` selects startRecentsTransition for code 6; `0048 + 2*006a = 011c` selects getRunningTasks for code 5. No stack-line guess is involved.

Source cross-reference: SystemUiProxy.kt lines 221–249 (raw writer), 1308–1337 (routing and modern call), 1490–1507 (codes and SDK/fingerprint selector); Compatibility.kt lines 66–69 (prefixes); IRecentTasks.aidl lines 58–60 (six-argument signature).

Scope stop: no production/test changes, build, generated-code re-vendor, GitHub operation, commit/push, or end-to-end rerun. Temporary extraction code/APK/tool files were removed; only requested evidence is retained. Build and source-code LSP verification are not applicable to these text artifacts.
