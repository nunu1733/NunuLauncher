# V8 closure reinspection

Status: **completed bounded consumer inspection**. Date: 2026-10-12. Owner: Issue [#516](https://github.com/nunu1733/NunuLauncher/issues/516), accepted input [#521](https://github.com/nunu1733/NunuLauncher/pull/529). This supersedes the unresolved *consumer-classification* statements in the private historical V8 reports; their earlier observations remain historical evidence. It is a Worker evidence supplement, not independent approval or a Phase 3 closure decision.

## Identity and method

- App APK SHA-256: `260bccdab43a7fd8ea10b5e1a56333caac4d0fc33bf28575e6c5a4da42ff55da`, independently rehashed. All 24 DEX entries were read directly from that ZIP.
- Read-only source worktree: `NunuLauncher-574`, clean HEAD `000c26903b9f0880ce551721dd89c3bb09da1ad6`. Submodule `platform_frameworks_libs_systemui`: `7d9e92bda50359dc66a530248e15f9748343524e`.
- APK provenance remains the precommit build on `33b4c061c55db6edf646a48e6a18570ce771d15f` plus the provider ten-line removal, with production-equivalence evidence in the [PR #575 byte-identity packet](https://github.com/nunu1733/NunuLauncher/blob/000c26903b9f0880ce551721dd89c3bb09da1ad6/docs/assessment/574-contacts-search-evidence/byte-identity.md). The version string is not used to infer a source SHA; this is not an APK built on the clean source HEAD above.
- Existing companion instrumentation APK independently inspected: SHA-256 `e89f6dd46e0d155588da9b76c0b86fd63a2f9595036215b3c9a7d92e12ac5042`, 13 DEX entries. Its timestamp/build metadata does not establish exact clean-HEAD provenance. This is separate artifact inspection, not a claim that a new test execution used it.
- Main supplied the framework JAR pulled read-only from the same physical API 37 Pixel: SHA-256 `626bd7db5eb29b9d3c45ee9f405ed833eded31c12c8c70c94b38d3610671cca2`. Only class/field declarations were inspected. Device identity is maintained in [identity record](identity-and-provenance.md); serials and framework/APK binaries are not published here.
- A private Python reader decoded every DEX header, string/type/prototype/method table and every defined method's `code_item`. It walked instructions at their declared widths, including switch/array payloads, then enumerated direct invoke sites for all `Field.set*`, native-load and class-loader references. Method references are counted per DEX, including duplicate cross-DEX references. All methods reached their declared instruction end without a width/offset error. Format definitions: [DEX format](https://source.android.com/docs/core/runtime/dex-format), [Dalvik bytecode](https://source.android.com/docs/core/runtime/dalvik-bytecode).
- Android SDK 37.0.0 `dexdump -d` independently supplied targeted disassembly: existing DEX 20–23 output plus newly completed selected DEX 1 and instrumentation DEX 1 output. Helpers were followed to their field selection, not classified from receiver nullness. For static fields, the receiver argument is ignored by [Field.set](https://developer.android.com/reference/java/lang/reflect/Field#set(java.lang.Object,%20java.lang.Object)).

## Complete app method inventory

| DEX | Method-ID entries | Defined code methods | Direct `Field.set*` sites, excluding `setAccessible` |
|---|---:|---:|---:|
| 1 | 64,959 | 57,245 | 5 |
| 2 | 21,027 | 16,501 | 0 |
| 3 | 641 | 580 | 0 |
| 4 | 9,267 | 5,875 | 0 |
| 5 | 5,534 | 3,544 | 1 |
| 6 | 4,555 | 2,613 | 0 |
| 7 | 9,606 | 5,486 | 0 |
| 8 | 4,392 | 3,224 | 0 |
| 9 | 10,607 | 7,253 | 0 |
| 10 | 10,631 | 7,153 | 0 |
| 11 | 7,070 | 4,672 | 0 |
| 12 | 6,554 | 4,672 | 0 |
| 13 | 6,863 | 4,726 | 0 |
| 14 | 8,832 | 6,932 | 0 |
| 15 | 9,120 | 5,548 | 0 |
| 16 | 6,978 | 4,202 | 0 |
| 17 | 7,902 | 5,297 | 0 |
| 18 | 2,102 | 1,196 | 0 |
| 19 | 878 | 818 | 0 |
| 20 | 65,229 | 56,732 | 2 |
| 21 | 65,041 | 51,344 | 2 |
| 22 | 65,355 | 53,341 | 3 |
| 23 | 20,139 | 16,741 | 10 |
| 24 | 623 | 366 | 0 |
| **Total** | **413,905** | **326,061** | **23** |

Across this complete method-reference inventory, no `System.load`, `Runtime.load`, `DexClassLoader`, or `InMemoryDexClassLoader` reference was present. Nine `System.loadLibrary` invoke sites were present. This is a bounded binary consumer inventory; computed reflective entry names and arbitrary native implementation behavior are not proven absent.

## T11: reflective write classification

API 37's new restriction is specifically on **static final** fields, including JNI static-field setters. Ordinary static fields and final instance fields are different categories. [Official target-37 behavior](https://developer.android.com/about/versions/17/behavior-changes-17#static-final-fields).

The table accounts for all 23 direct app write invoke sites. Offsets are DEX `code_item` offsets; multiple sites are explicitly counted.

| DEX / consumer / code offset | Target and evidence | Classification |
|---|---|---|
| 1: `ImmLeaksCleaner.ValidCleaner.clearNextServedView`, `0x1e46d4` | Initializer selects `InputMethodManager.mNextServedView`; physical framework field flags `0x0002`. | Instance, non-final. |
| 1: `DropDownListView.PreApi33Impl.setSelectedChildViewEnabled`, `0x21dc1c` | Initializer selects `AbsListView.mIsChildViewEnabled`; framework flags `0x0002`. | Instance, non-final; legacy helper. |
| 1: `LocationCompat.Api26Impl.removeBearingAccuracy`, `0x103ae24`; `removeSpeedAccuracy`, `0x103aea0`; `removeVerticalAccuracy`, `0x103af1c` | All three `setByte` sites use `LocationCompat.getFieldsMaskField`, which selects `Location.mFieldsMask`; framework flags `0x0002`. The current framework type is `int`; these legacy helpers' successful execution on API 37 is not claimed. | Three instance targets; no static-final write. |
| 5: `OverScrollerCompat.setField`, `0x50498` | Production callers supply `mSplineDistance` / `mSplineDuration` on spline scroller objects, pre-S path. Source callers were read. | Instance fields; generic helper's concrete production targets classified. |
| 20: `ViewLayer.Companion.updateDisplayList`, `0x568cf8` | Selects `View.mRecreateDisplayList`, then `setBoolean(view, true)`; framework flags `0x0000`. | Instance, non-final. |
| 20: `NotificationCompatJellybean.getExtras`, `0x695e94` | Selects `Notification.extras` and assigns a Bundle to the Notification object. | Instance field; unrelated to MessageQueue. |
| 21: `ViewUtilsApi19.setTransitionVisibility`, `0x35996c` | Selects `View.mViewFlags`; framework flags `0x0000`. | Instance, non-final. |
| 21: Studio `LeakCanaryReflectionHelper.setDumpHeapEnabled`, `0x3dc2e8` | Obtains LeakCanary config via `getConfig`, selects `dumpHeap`; packaged `LeakCanary.Config.dumpHeap` is in instance fields, flags `0x0012`. | Final instance field; not static-final. |
| 22: Guava `Finalizer.startFinalizer`, `0x208ee4` | Selects `Thread.inheritableThreadLocals`, clears it on the newly created Thread. | Instance Thread state. |
| 22: `RootServerMain.<init>`, `0x35d64c` | Explicitly selects `Resources.mSystem`; physical framework static-field flags `0x0008`. AOSP source independently declares mutable static state ([Resources](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/Resources.java), observed blob `05596318aef57c7558791b3c33016329f5de68ce`). | **Static, non-final**. Corrects the withdrawn receiver-based instance inference. Root-server entry is not treated as ordinary launcher startup. |
| 22: Curtains `WindowManagerSpy.swapWindowManagerGlobalMViews`, `0x362e38` | Lazy helpers select `android.view.WindowManagerGlobal` then `mViews`; physical framework instance-field flags `0x0012`. [AOSP WindowManagerGlobal](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManagerGlobal.java) corroborates the instance declaration. | **Final instance** field; not static-final. |
| 23: `ACTIVITY_MANAGER.apply.1.3.invoke`, `0xda888` | Captured Samsung ActivityManager `mContext`; helper tests STATIC modifier before registering callback. The actual packaged `ACTIVITY_MANAGER.apply` entry (`0xdaae4`) compares manufacturer and returns, with no helper creation/registration call. | Retained vendor callback; no write reached through the packaged fix's entry. Target FINAL modifier not assumed. |
| 23: `BUBBLE_POPUP.apply.1.2.invoke`, `0xdab54` | Captured LG `BubblePopupHelper.sHelper`; packaged `BUBBLE_POPUP.apply` (`0xdad08`) compares manufacturer and returns without creating/registering the callback. | Retained vendor callback, inactive through fix entry; vendor field modifiers not claimed. |
| 23: `IMM_CUR_ROOT_VIEW.apply.1.onActivityDestroyed`, `0xdbac0`; `.apply.2.onRootViewRemoved`, `0xdbc94` | Both select `InputMethodManager.mCurRootView`, framework flags `0x0000`; packaged fix returns for SDK >=29 before registering either callback. | Two instance-field sites, legacy SDK branch. |
| 23: `LAST_HOVERED_VIEW.apply.1.2.invoke`, `0xdc1a4` | Vendor callback; packaged fix entry `0xdc350` compares manufacturer and returns without registration. | Inactive through fix entry; unknown vendor modifier is not converted into instance/non-final. |
| 23: `SPELL_CHECKER.apply.proxyService.1.invoke`, `0xdc614` | Captured spell-checker field in retained proxy callback; packaged fix entry `0xdc7c4` immediately returns after parameter check. | Inactive through fix entry; no universal callback reachability claim. |
| 23: `ServiceWatcher.swapActivityManager`, `0xe1e78` | Reads `ActivityManager.IActivityManagerSingleton`, then writes **Singleton.mInstance**; physical framework instance flags `0x0042`. | Instance, volatile/non-final. Reading the static singleton is not writing that static field. |
| 23: `ServiceWatcher.swapActivityThreadHandlerCallback`, `0xe1f54` | Reads ActivityThread `mH`, then writes **Handler.mCallback**; physical framework instance flags `0x0010`. | Final instance; not static-final. |
| 23: Okio `ByteString.readObject`, `0x14cfc8` | Explicitly selects packaged `ByteString.data` on the deserialized object. | Instance deserialization field. Earlier DEX20 attribution is corrected to DEX23. |
| 23: ChickenHook `Reflection.setReflective(Object,Class,String,Object)`, `0x16c47c` | Dynamic named target. All 24 DEX method tables/invoke sites identify only its own overload (`0x16c4a4`) as caller; no external statically referenced setter consumer. | Packaged generic capability with no concrete external write consumer found. Dynamic/reflected invocation cannot be excluded. |

**T11 consumer-scan result: PASS, bounded.** No currently identified concrete app consumer writes a static-final field. This does not assert that every dynamically invocable helper or vendor/native implementation is incapable of doing so.

## T10: MessageQueue and test tooling

- Complete app string inventory found `mMessages` only in DEX20/23. DEX20 occurrences are NotificationCompat ordinary fields/keys, not platform MessageQueue reflection. DEX23 occurrences in Shark `AndroidObjectInspectors` and `AndroidReferenceReaders.MESSAGE_QUEUE` use `HeapObject.HeapInstance.get(className,fieldName)` over heap-dump data, not reflection on the live queue. These diagnostic readers' compatibility with every future heap schema is not proven.
- Actual platform queue consumers found in Launcher `LooperIdleLock` / `BaseLauncherBinder` and LeakCanary idle/reference-cleaner callbacks use public `getQueue`, `myQueue`, `addIdleHandler`, `removeIdleHandler`, or `isIdle`. No live-queue `mMessages` app reflection consumer was identified.
- Companion test APK: all 13 DEX inspected, 28,790 method-ID entries. **Espresso does contain a legacy `mMessages` reflection branch** in `TestLooperManagerCompat.<clinit>` (`0x9efac`). `initTestLooperManager` (`0x9ee44`) tries `TestLooperManager.peekWhen` and `isBlockedOnSyncBarrier` for SDK>=35; success skips the legacy branch. Failure falls back. This finding is not hidden behind a blanket token-absence claim.
- Previously completed resolved-dependency result in the historical V8 result establishes Espresso **3.7.0** on the selected AndroidTest runtime configuration. Prior API37 fresh 3/3 and physical O1 10/10 instrumentation evidence is retained as the runtime/idle observation; this reinspection ran zero tests. The [official guidance](https://developer.android.com/about/versions/17/changes/messagequeue) requires Espresso>=3.7.0, and requires Robolectric>=4.17/PAUSED **when Robolectric is executed**.
- Root Gradle sourceSets use `tests/unit` and `tests/organizer-instrumentation`; they do not include `tests/src_deviceless`, root/quickstep multivalent suites, or WMShell unittest suites. Existing CI invokes those configured sourceSets, not an `atest` Robolectric suite. `mockito-robolectric` in the version catalog is an alias for **Mockito**, not a Robolectric runtime. Widgetpicker's configured tests use Mockito/JUnit; animationlib's Robolectric dependency line is commented out. Current executed configuration therefore has no resolved/executed Robolectric runtime. Dormant runner/source files are not represented as a successful Robolectric run.

**T10 result: PASS for the app/tooling consumer reinspection plus previously recorded API37 evidence.** Robolectric version/PAUSED is conditional N/A for the selected executed configuration; enabling a different runner/configuration requires a new check.

## Instrumentation fixtures and submodule

Focused test-audit: protected contract is API37 compatibility of the **existing** reflection fixtures/idle tooling; credible regression is a platform restriction rejecting a previously supported field write or queue assumption. Owner boundary is packaged tooling plus declared Gradle sourceSets. Existing physical instrumentation already owns runtime proof; this inspection adds classification rather than duplicate execution. Impact is platform/build compatibility, diagnostic evidence. No production seam, test, or CI lane is added or removed.

- Companion test APK contains four direct reflective write sites: JUnit `createTestUsingFieldInjection` (parameter fields on the test object); `DeckRetirementTestRunner.installPauseObserver` (mutable `DeckRetirementMigration.phaseObserver`); and two `installProcessLocalRunner` fixtures writing mutable `ManualOrganizationModule.instance`. JUnit runtime-supplied fields are classified as injection capability; no concrete static-final parameter target was identified. Root selected instrumentation source was also searched/read to verify the production targets are vars rather than static-final vals.
- Read-only source/submodule scan repeats the private queue/native-load/setter searches. Submodule pin above has no `mMessages`, `MessageQueue.class`, `field.set`, null-target primitive setter, `Modifier.FINAL`, `System.load`, or `loadLibrary` hit in tracked Java/Kotlin. This is source inspection, not a claim about unexecuted generated outputs.
- The upstream WMShell `DesktopModeStatusTest.setEnforceDeviceRestriction` **does** write `ENFORCE_DEVICE_RESTRICTIONS`, whose production declaration is private static final. It is under `wmshell/tests/unittest`, excluded from this APK/test APK and selected Gradle/CI execution. It is a conditional future fixture incompatibility, not current configured instrumentation evidence. Cached flag fields in that fixture are separate mutable static caches.
- `TaskbarUnitTestRule.injectController` sets annotation-selected test instance controller fields, and dormant `ReflectionHelpers.setField` is a generic test helper. Their trees are outside the selected sourceSets. Host `tools/diagnostics/RecentsTransitionWireProbe` changes SDK/vendor fields and uses DexClassLoader intentionally outside the launcher/test APK; it is not counted as a shipped app consumer or claimed compatible with the target37 app contract.

## T19: packaged native consumers

| DEX / initializer | Constant library name | Packaged library |
|---|---|---|
| 21 `SharedCounter.Factory` | `datastore_shared_counter` | `libdatastore_shared_counter.so` |
| 21 `PathIteratorImpl` | `androidx.graphics.path` | `libandroidx.graphics.path.so` |
| 21 `JniBindings` | `graphics-core` | `libgraphics-core.so` |
| 21 `SyncFenceBindings` | `graphics-core` | `libgraphics-core.so` |
| 21 `SyncFenceV19` | `graphics-core` | `libgraphics-core.so` |
| 21 `EGLBindings` | `graphics-core` | `libgraphics-core.so` |
| 22 `NativeBlurProcessor` | `hoko_blur` | `libhoko_blur.so` |
| 22 `BitmapUtil` | `hoko_blur` | `libhoko_blur.so` |
| 23 `NativeReflectionBypass` | `nrb` | `libnrb.so` |

All nine load sites are class initializers using fixed `System.loadLibrary` names, matching the five libraries packaged for all four APK ABIs. ChickenHook's packaged native API declarations return declared fields/methods; no static-field setter API is exposed by that Java binding. This is consumer/API classification, not native disassembly or proof that JNI internals never mutate unrelated state.

**T19 consumer-scan result: PASS, bounded.** No writable-file `System.load`/Runtime.load or dynamic class-loader consumer was found in the complete app method-reference/invoke inventory or tracked source/submodule. The [target37 read-only native-file restriction](https://developer.android.com/about/versions/17/behavior-changes-17#safer-dcl) is distinguished from packaged `loadLibrary` consumers. No new writable-file loader was exercised, and no universal stripped-library safety proof is claimed. The previously recorded successful packaged `nrb` runtime load remains prior evidence.

## T08: merged media scope

`aapt dump permissions` on the identified APK confirms READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_AUDIO and READ_EXTERNAL_STORAGE. Source media consumers exist: `PermissionDialog`, preference file search, and `FileAccessManager`. The manager distinguishes Full/Partial/Denied, rechecks permissions on refresh, and does not equate selected-media access with full wallpaper access. No `MediaStore.getVersion` or OWNER_PACKAGE_NAME ownership-based consumer was found in the selected source search; the complete app method table also has no MediaStore.getVersion reference.

**T08 result: PASS for #521's requested merged-manifest/media-consumer confirmation and scoped backup/export ownership classification.** SAF document selection remains distinct from media permission requests. This does not prove every grant/reselection/revoke flow in media UI; no such runtime cycle was executed in this inspection. The permission declarations are not incorrectly called absent/N/A. [Official owned-photo change](https://developer.android.com/about/versions/16/behavior-changes-16#owned-photos).

## Reproduction and remaining bounds

Completed read-only commands, with artifact identities above as the stable references:

1. `shasum -a 256 <app.apk> <companion-test.apk> <physical-framework.jar>`.
2. Private Python structured DEX inventory over ZIP `classes*.dex`, using method IDs, ULEB128 class data and instruction-aligned invokes. App and test output completed without decoder errors; output summaries are recorded above. Raw DEX/output/parser stay private temporary artifacts.
3. `$ANDROID_SDK_ROOT/build-tools/37.0.0/dexdump -d <dex> > <private-output>`; app DEX20–23 were preexisting completed outputs, DEX1 selected classes and companion-test DEX1 were newly completed. A first test-Dexdump invocation placed `-o` after the filename and emitted stdout; the corrected redirected invocation completed. No timeout is used as an absence result.
4. Same DEX class-data reader over physical framework JAR, inspecting declared static/instance fields and access flags; source declaration corroboration where linked above.
5. `git grep -n -E 'mMessages|MessageQueue\.class|field\.set|Modifier\.FINAL|System\.load|loadLibrary' -- '*.java' '*.kt'` in the clean source and pinned submodule; targeted full reads of matched helpers/fixtures/sourceSets/catalog/CI. Media search included READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED, MediaStore.getVersion and OWNER_PACKAGE_NAME.
6. `$ANDROID_SDK_ROOT/build-tools/37.0.0/aapt dump permissions <app.apk>`.

**V8 consumer-inspection blocker: none remaining for the inspected app/test configuration.** This conclusion uses #521's requested consumer rescan boundary, not an assertion of complete library implementation safety. Independent review, source/APK provenance acceptance and the overall Phase3 matrix remain owned by the main closure work. No build/test rerun, dependency/configuration change, device operation, commit, or publication was performed by this reinspection.

## Published reproduction supplement

[scan_dex_consumers.py](scan_dex_consumers.py) accepts APK and output-directory arguments and preserves the original decoder mechanics. Main reran it on the identified app APK; generated `inventory.json` was byte-identical to the original completed inspection, SHA-256 `2117a384ebdaf038ffe25a9d33dbbe1765a73d8cacfc62228c185a29dcf9ae64`. Runtime inputs/APKs remain private.

```sh
python3 scan_dex_consumers.py <identified-app.apk> <private-output-directory>
```
