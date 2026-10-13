# Phase3 verification trace index

Status: evidence mapping assembled; supported cold settings runtime correction complete; source audit accepted; source publication complete; final closure governed by matrix§15/PR573. Date:2026-10-12–13. Current closure decision belongs to [matrix §15](../issue-516-phase3-closure-matrix.md) and Issue516. This index supersedes the understated residual mapping in historical §13; it does not change an accepted contract or independently declare closure.

The source/APK/fingerprint for each observation is fixed in its linked packet. Different validation slices remain explicit. Phase4 owns the single cutover head, cutover CI/re-audit, upstream baseline/toolchain publication and T9 roundtrip; no Phase3 residual is deferred there.

## Fork requirements

| Requirement | Specific evidence surface | Scope/result |
|---|---|---|
| R1 direct edit/admission/transaction/rollback | Historical §13-A/B exact named ModelWriter/Apply/Undo tests and §5 source CI runs | Retained backend PASS, plus current main maintenance source CI/audits in PR580/582. Current maintenance CI is not reattributed to a16 cutover. |
| R2 destination policy | InstallDestinationModelWriterTest / InstallDestinationQueueTest / AppDestinationNoticeTest, historical §4/5; [target37 UI evidence](https://github.com/nunu1733/NunuLauncher/tree/bc0171f25374fadf2d6296a0df4c29022416f12f/docs/assessment/526-target37-ui-evidence) | Retained new-baseline policy/picker verification; UI is emulator primary evidence. |
| R3 multi-select/stale/Undo | [Actual tap journey and four staged figures](./r3-actual-ui.md), historical §13-A actual planner/apply/stale/Undo tests; [owner discard notice](./physical-discard-notice.md) | UI counts1→2→1→2, folder/lock nonselection, all four actions and Cancel exact42-row no-write; Apply/Undo remain separate backend oracles. Audible-once is owner-observed. |
| R4 planner/application/recovery/lock/category | Historical exact lane/scenario tracing plus [API37 large-home apply/cold recovery](./api37-data-and-recovery.md) and [actual preview](./saf-backup-and-import.md) | Backend500→501 /49locks/conservation/independent DB/cold500 recovery; UI preview/dismissal is separate and no UI Apply is claimed. |
| R5 schema/backup/restore/downgrade | #522 accepted assessment and historical API36 G4/T1–T8; [API37 schema32/33 real ZIP](./api37-data-and-recovery.md), [actual canonical500 SAF](./saf-backup-and-import.md) | Phase3 data/restore checks only. T9 real R15→16→R15→16 roundtrip remains Phase4 by the accepted phase boundary. |
| R6a native operations | [Widget/dot/profile](./widget-notification-profile.md), [retained physical folder/drag/hotseat limits](./prior-device-observations.md), [contacts](./contacts-verification.md), [SAF/native follow-up](./saf-backup-and-import.md) | Owned-emulator notification cycle and native normal/large widgets complete. Current source-bound hotseat launch/Back is complete in the SAF packet; owned folder/drag/restore and permissionless probe add/remove refresh are complete in [native operations](./native-operations.md). Earlier physical folder/drag observations are retained separately. Physical hotseat prior report lacks an individual APK hash and is not promoted to byte-bound proof. |
| R6b recents/gesture | Historical §4/5 exact #559/#563 and #545 API36/37 provider assessments/runs | Retained provider/recents PASS; nonroot physical provider boundary remains conditional O5. Widget/Smartspace claims use their dedicated packets, not provider screenshots. |
| R7 exact-head source CI/audit | Historical §5 slice-specific successful CI; #575 attempt2 at000c26903b9f0880ce551721dd89c3bb09da1ad6; merged #578/#580; #582 source94d0c088df979b4d0aa32158701aa8ab62c530b4 source CI38262792114 attempts1/2 and independent audit; publicationeead64fabe9581f997a54924d43f660875257669 CI38267058803 and gate38267058744; merged0ef86713b9414f0aa85c028e2e77d3ec14c0f4db | Source heads and docs-only deltas remain distinct. PR584 mergedc3898c74 with source5ce CI38324607913, independent audit, publication0bbc CI38347440730/gate38347440751. PR588 source059 CI38350644104 and publicationed7a4d2 CI38353976637/gate38353976636 pass; current full reviews and independent audit accepted; mergeda9ec830a81d2924d8ff96108f576936c2b2af207. PR573 publication-head checks/review are the final effectiveness gate; no cutover CI is claimed. |
| R8 Pixel9a/API37 representative leg | [O1–O4 bounded observations](./prior-device-observations.md), [CJKV/teardown](./cjkv-and-teardown.md), [owner audible notice](./physical-discard-notice.md), [provenance](./identity-and-provenance.md) | Phone-width physical representative observations; tablet/freeform primary proof is separate emulator evidence. O5 is conditional on rooted hardware; nonroot Pixel is not a provider device. Historical original settings/LNP remain limits; current usual state is owner-accepted. |

## #521 V1–V8

| Verification scope | Mapped observations and boundaries |
|---|---|
| V1 / T01,T02 | #526 emulator UI matrix/oracles atbc0171f25374fadf2d6296a0df4c29022416f12f + exact CI37502010177; physical O1/R8 and later owner audible-once. SafeDrawing/back/idle-cancel/recreation/zero-write/stale/busy apply are named in §13-A and physical receipt. |
| V2 / T05,T24 | #526 tablet rotation/resize/freeform evidence and recreation oracles; `api37-tablet-scenario1-insets-landscape.png` and `api37-phone-backup-route-resize-720.png` are explicit artifacts. Pixel9a is not used to prove sw600+ layout. |
| V3 / T03,T12 | Arabic/Thai visible row observations + Japanese/English font1.3/2.0 images; source-bound keyboard/CJKV input/Esc/rotation and owner audible feedback. English fork fallback and word-level independent Vietnamese vs owner phrase observation remain explicit. |
| V4 / T17 | #527 BAL/granular accepted vertical at54a75042305c74ddc0cb118ce6313e2c1746ccca, CI37505731629 attempt2; physical realBcSmartspaceCard dateView→BcSmartSpaceUtil→Calendar direct-intent tap and chooser (earlier placeholder attempt superseded; other PendingIntent variants/PI-send branch unmeasured); actual foreground SAF return and native widget bind supplement. No universal HOME-exception or other PendingIntent success claim. |
| V5 / T13,T14,T18 | #528 accepted LAN/ECH/CT vertical at57d4338bb5be5114481b98bef5a90f757b1d1f23, CI37564923987; physical allow/deny/cancel and QA release-buildType Katbin success/failure. ECH/CT implementation limits and debug fallback signer remain explicit. |
| V6 / T09,T20,T21 | Permission grant/deny/revoke and fixed physical/API36 contacts search/tap/cleanup; native Smartspace clock/ON-OFF capture, converter/widget restore, normal/large/rejected RemoteViews. Contacts and widget/Smartspace receipts are dedicated; recents artifacts are not substituted. |
| V7 / T10 and data scope | API36 existing G4 and source lanes; API37 production capture/planner/apply from500 existing rows with2 moved rows, correlated reload/independentDB/cold recovery; actual UI separately selected112 eligible off-home candidates. Schema32/33 ZIP and canonical500 SAF restart/restore are separate completed flows. Native drawer fixture presence, actual work-profile UI and named positive/negative status0 tests are complete; package-install/refresh and native folder/drag receipts are complete in [native operations](./native-operations.md). Reply-file Import/cold review/discard succeeded on repaired source754. [Supported cold settings restore](./cold-settings-restore.md) is GREEN, including native cold restart; #588 independent audit/publication checks passed; PR588 mergeda9ec830a. |
| V8 / T08,T10,T11,T19 | [Complete packaged consumer inspection](./v8-consumer-inspection.md), including all24 appDEX/13 companionDEX, fixed submodule, actual selected test-sourceSets/dependency/tooling, framework field flags, native libraries and merged media manifest. Current Robolectric runner is not selected; future configuration remains conditional. |

## T01–T27 one-to-one disposition

(c) means the accepted assessment's bounded source/consumer no-change classification; it is not a new runtime PASS or a universal library claim.

| ID | Disposition / concrete owner |
|---|---|
| T01 edge-to-edge | V1: #526 named UI/inset matrix, O1/R8. |
| T02 predictive back | V1: exact busy/idle/recreation/stale instrumentation oracles and retained UI. |
| T03 elegant text height | V3: private Arabic/Thai visible localized rows and bounded font stress, curated hash references. |
| T04 fixed-rate scheduling | Retain(c), #521 §3 C12 scheduler-consumer classification. |
| T05 large-screen orientation | V2: actual #526 tablet/resize artifacts; not phone substitution. |
| T06 health permissions | Retain(c), usage hints have no BODY_SENSORS/Health Connect consumer in assessed fork scope. |
| T07 MediaStore version | Retain(c) plus V8 merged method/source rescan; no getVersion consumer; SAF URI is a separate contract. |
| T08 owned-photo/media selection | V8 merged manifest permissions/media consumers and Full/Partial/Denied source classification; no fabricated media-grant runtime cycle. |
| T09 RemoteViews memory | V6: normal update, accepted12,960,000-byte bitmap, provider rejection36,000,000>15,707,520, stable host rendering. |
| T10 lock-free queue | V8 actual configured tooling/public idle consumers plus V7/API37 actual reload/cold recovery and named instrumentation; legacy Espresso fallback disclosed. |
| T11 static-final writes | V8 all23 app setter sites/four companion sites classified by actual target/modifiers/branch/callers; conditional dormant WMShell fixture retained. |
| T12 CJKV/keyboard accessibility | V3 CJKV conversions on the physical device via ADB-generated input, and separate ADB keyboard recognition; owner physical-keyboard input/Esc/rotation/TalkBack; byte-bound build and teardown. |
| T13 ECH/GREASE | V5 accepted library/server/runtime classification; no new DNS/ECH implementation or blanket enablement claim. |
| T14 local-network permission | V5 adopted allow/deny/cancel UI/runtime scope; historic original LNP baseline remains unproven. |
| T15 password visibility | Retain(c), rename/import/search are not password fields; no custom visibility consumer. |
| T16 SMS OTP | Retain(c), no SMS read/receiver consumer; notification dots verified independently as NotificationListener behavior. |
| T17 BAL/IntentSender | V4 exact #527 sender/creator/foreground/background/HOME matrix and named owner direct-Calendar/chooser supplement. |
| T18 CT | V5 scoped HTTPS/NSC/library/typed failure evidence; existing inability to force CT-only emulator failure not silently upgraded to runtime proof. |
| T19 safer native loading | V8 all9 fixed loadLibrary sites/five packaged libs, submodule/direct-loader rescan; computed reflection/JNI internals not universally excluded. |
| T20 CP2 account columns | V6 reproduced failure, sole ten-line removal, byte identity, exact #575 CI, fixed physical/API36 search/tap. |
| T21 CP2 strict SQL | Retain(c) plus physical denied/revoked empty-search short circuit; no claim that Launcher SQLite generally changed. |
| T22 content capture | Retain(c), assessed fork has no setContentCaptureEnabled(false) dependency. |
| T23 background audio | Retain(c), assessed organizer/backup/diagnostics have no playback/focus/volume consumer. |
| T24 large-screen opt-out | V2 same tablet/freeform/recreation matrix as T05. |
| T25 RFCOMM EOF | Retain(c), no BluetoothSocket consumer; physical keyboard input is a distinct path. |
| T26 Keystore limits | Retain(c), no runtime AndroidKeyStore bulk generation; APK signing is distinct. |
| T27 direct NPU | Retain(c), local clipboard/SAF exchange is not an inference runtime. |

The original #521 assessment retains the accepted definitions and official-source citations. This index records where execution/classification evidence lives; it does not replicate or relax that contract. Final closure effectiveness is governed by matrix§15/PR573.
