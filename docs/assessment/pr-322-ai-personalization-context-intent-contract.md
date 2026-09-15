# High-risk audit: PR #322 AI personalization Context / PersonalizedIntent exchange contract

> Status: accepted
> Audit date: 2026-09-15

- Auditor: independent audit session (ZCode subagent session, distinct from the implementation session; solo保守のための独立session再実行・再確認)。実装sessionとは別のsessionとして、実装コードの変更は行わず、読み取り・検証・本監査記録の作成のみを実施した。
- PR: https://github.com/nunu1733/NunuLauncher/pull/322
- Head SHA: 428d45a2387f6afb7459a6448facfd5ccb672eed
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34953107141
- Criteria: specs/204-ai-personalization-context-intent-contract/spec.md — AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14, FR-017
- Criteria: docs/adr/0007-authoritative-organization-policy-sources.md — ADR-0007
- Criteria: docs/adr/0012-versioned-layout-strategy-catalog.md — ADR-0012
- Criteria: docs/adr/0010-qsb-row-item-overlap-interop.md — ADR-0010

## Scope

対象diff (`origin/main` = `9ea2ba0eb4d9ef61bd96ef2b480bbd20ed055edd` → head `428d45a2387f6afb7459a6448facfd5ccb672eed`、受入commit `e6dfe8fa4b` を含む):

- 新規純粋package `lawnchair/src/app/lawnchair/organizer/personalization/` への追加 (spec 204の契約module): `ContextExportModels.kt`, `ContextExportBuilder.kt`, `RandomIdAllocator.kt`, `SourceContextIdentity.kt`, `IntentModels.kt`, `IntentCodec.kt`, `IntentValidator.kt`, `IntentIdentity.kt`, `IntentPlannerAdapter.kt`, `ExportSessionStore.kt` (seam interfaceのみ)。既存 #203 fileへの変更なし。
- Android境界1点: `lawnchair/src/app/lawnchair/organizer/integration/AndroidExportSessionStore.kt` (新規) + `SecureRandomIdAllocator`。
- Planning seamへのadditive変更: `OrganizationInput.intentPreferences` (optional, 既存runはnull), `PolicySourceKind.PERSONALIZED_INTENT` + `InputProvenance.personalizedIntent` 第7input (sentinel default), `PlanningResult.personalizedIntentDigest` echo, `PlanningResultCanonicalization` のecho伝播, `rules/PolicyModels.kt` のenum追加。
- docs: spec.md/plan.md (status accepted), requirements.md (FR-017、FR-014境界、D-011), CONTEXT.md (用語4件), DESIGN.md (module行/gate行), organizer-diagnostics.md (intent identity許容行)。
- 新規test 7 file。既存test fileの変更はゼロ。

確認したruntime書き込み経路とmigration対象:

- 唯一の新規永続化は `AndroidExportSessionStore` による `noBackupFilesDir` 配下の単一JSON (`organizer_personalization_export_session_v1.json`, AtomicFile, schema version 1)。Launcher favorites DBへの書込み・DB schema migrationは存在しない。backup対象外位置。
- validatorは純粋関数でzero-write構造。composer (`OrganizationInputComposer`) へのaccepted intent読取りは意図的に未接続 (plan.mdに記録済み。#205/#206のproducer接続時)。
- `risk: layout-data` の実質: planner seamへのadditive optional field追加のみで、既存runはsentinel identityとnull projectionで既存挙動・provenance不変 (下記Criteria check参照)。

## Criteria check

spec 204 (status: accepted) の受入条件と、ADR-0007/0012/0010のauthority境界について、head `428d45a` の実装を独立に確認した (コード読解 + ローカルtest実行 + CI結果の照合)。

- **AC-1 (FR-017)**: 満たす。requirements.mdへFR-017 (FR-014との境界備考、D-011のFR-017言及) が追加済み (受入commit `e6dfe8fa4b`)。
- **AC-2 (versioned schema)**: 満たす。`personalization-context-v1` / `personalized-intent-v1` の固定schemaVersion定数、unknown versionのfail-closed拒否 (`IntentCodecTest.unknownSchemaVersionIsRejectedFailClosed`)、immutable semantic version規則の明文化 (spec「Compatibility / migration」節)。
- **AC-3 (provider非依存)**: 満たす。codec/validatorはtransport非依存のcanonical JSONのみを扱い、test double (`SequentialIdAllocator` とJSON fixture) が同一seamで検証される。production consumer不在はspec範囲外 (#205/#206) であり契約上の問題ではない。
- **AC-4 (planner authority / FORBIDDEN_CONTENT)**: 満たす。intent schemaにraw座標・span・reservation・DB mutation・scriptの表現は存在せず、schema外authority keyは `FORBIDDEN_CONTENT` でreject (`IntentCodec.FORBIDDEN_KEYS`, `decodeEnvelope`/`decodeItemIntents`)。validator/adapter/plannerは保持判断 (`determinePreservation`) を変更しない。ADR-0007のauthority model (bundle ≠ dynamic state) と整合 — intentは`OrganizationInput.intentPreferences`経由のordering/preference biasのみ。
- **AC-5 (typed fail-closed zero-write)**: 満たす。`SCHEMA_MISMATCH`/`EXPORT_MISMATCH`/`SESSION_EXPIRED`/`CONTEXT_STALE`/`OVERSIZE`/`UNKNOWN_REF`/`DUPLICATE_REF`/`INCOMPLETE_COVERAGE`/`INVALID_ENUM`/`FORBIDDEN_CONTENT`/`MOBILITY_CONTRADICTION` がtyped sealed resultで実装され、各classにcontract testが存在 (`IntentCodecTest`, `IntentValidatorTest`)。`CAPABILITY_UNSUPPORTED` はV1でreserved/unreachable (spec固定どおり、V1必須failure test対象外)。coverage partition (itemIntents refs ∪ unresolvedRefs == 全exported refs、互いに素) をvalidatorで確認。
- **AC-6 (intent identity / sentinel)**: 満たす。accepted intentはcanonical representationのSHA-256 content digest付きidentity (`IntentIdentityCalculator`)。no-intent sentinelは `versionOrGeneration="none"`、`sha256 = SHA-256("personalized-intent:none")` で、`PolicyInputIdentity` の型不変条件 (非空version、64hex) を満たす (`UnlinkabilityAndIdentityContractTest.noIntentSentinelIsAValidPolicyInputIdentity`)。#203の `sha256Canonical` と同一の計算であることをコード照合で確認。
- **AC-7 (prompt injection脅威モデル)**: 満たす。injection label fixture test (`externalRedactedTierExcludesTheWholeFreeTextClass` は "Ignore previous instructions" labelを使用)、allow-list/closed enum reject、`rationale` のauthorityなしを確認。
- **AC-8 (privacy tier)**: 満たす。export modelはpackage名・profile identity・raw ms・DB row ID・内部ItemIdのfieldを構造的に持たない。`EXTERNAL_REDACTED` は自由文class (label) 全体を除外しsurrogateも生成しない (builder単一点制御 + test)。`LOCAL_FULL`/`EXTERNAL_WITH_LABELS` のみlabelを含む。
- **AC-9 (preview path再利用)**: 満たす。新preview truthは作らず、accepted intentは `OrganizationInput.intentPreferences` → 既存 `OrganizationPlanner.plan` → `PlanningResult` の既存pathのみを通る。adapterの差分はechoとprojectionのみ。
- **AC-10 (test計画)**: 満たす。plan.mdのVerification表にAC対応のtest表面が記載されている。
- **AC-11 (process death耐性)**: 満たす。`AndroidExportSessionStore` はAtomicFileによるdurable保持。store再生成 (process death模擬) を跨ぐ取り込み成功test、失効 `SESSION_EXPIRED`、不明session不在読出し、破損storageのfail-closed ("no session") をtestで確認 (`AndroidExportSessionStoreTest`)。
- **AC-12 (source context binding / signal分離)**: 満たす。`SourceContextIdentity` のdigest入力はcaptured snapshot (kind/lock/availability/placement) + TargetSet role + 解決済み分類の構造的canonical rowsのみで、#203 signal snapshot・envelope・exportId/ref・tier・capabilityは含まない (実装読解で確認)。envelope走査test (`theExportDocumentCarriesNoStateFingerprintOrInternalIdentity`)、signal変化でdigest不変 (`signalOnlyChangesNeverMoveTheDigest`)、signal変化のみのimport成功 (`signalOnlyChangesNeverRejectTheImport`)、signal provenanceのsession記録と非照合 (`ContextExportBuilderTest` lines 290–300、validatorはsignalを入力に取らない) を確認。**指示検証項目「structural sourceContextDigestが#203 signal snapshotを含まない」: 確認済み。signal変化でCONTEXT_STALEにならない。**
- **AC-13 (kind + mobility projection matrix)**: 実装は満たすが、test表面に部分的な欠落あり (Findings P2-1)。kind matrix: `APP_OR_SHORTCUT`/`FOLDER`/`WIDGET` のみexportされ、`APP_PAIR`/`SHORTCUT_LEGACY`/`Unknown`/unsupported containerはconstraint-only集計 (`preservedCounts`) で、それらへの参照は `UNKNOWN_REF`。mobility matrix: `projectMobility` の先勝ち順位 (RESERVED_REGION → LOCKED → UNAVAILABLE → DOCK → widget CONDITIONAL → APP_PAIR_MEMBER → FOLDER_MEMBER → MOVABLE) は `determinePreservation` (`planning/PlanningPlacement.kt:379-420`, ADR-0010のreservation最優先を含む) と独立照合で一致。locked itemのsemantic fieldはFIXED/LOCKEDへの矛盾として `MOBILITY_CONTRADICTION` に分類され、`FORBIDDEN_CONTENT` には分類されない (`lockedItemMovementPreferenceIsNotForbiddenContent`, `fixedRefContradictionsAreMobilityContradictions`)。widget (`CONDITIONAL`) の `desiredGroup`/`groupSemantic` rejectと、FIXED refの `preserve` のみ許可を確認。
- **AC-14 (cross-export unlinkability)**: 満たす。`exportId` と `items[].ref` は同一注入seam (`RandomIdAllocator`) の新鮮な乱数 (counter/timestamp/決定的導出なし。同一canonical状態の再exportが識別子を共有しないtest、degenerate allocatorのexport scope検出test、`SecureRandomIdAllocator` の16byte crypto-strength entropy)。export文書に `sourceContextDigest` 相当のstate fingerprint・signal identityが現れない走査test。ref↔ItemId mapはsessionのみ (`AndroidExportSessionStore`)。semantic contentからの確率的linkageが保証対象外である旨はspec脅威modelに明記済み。
- **ADR-0007 (authority境界)**: 整合。intent identityはADR-0007 §9の「optional dynamic input, always-present identity」規律を第7inputとして踏襲する。intent identityはimmutable bundle identityやmandatory dynamic cutにjoinしない。planner保持判断はplanner正本のまま。
- **ADR-0012 (strategy catalog)**: 整合。intentはcatalogへの新strategy追加ではなく、`OrganizationPlanner.plan` seamへの入力の1つ。strategy semanticsを弱めない。
- **ADR-0010 (reservation authority)**: 整合。reservation重複itemはexport時点で最優先の `FIXED/RESERVED_REGION` として投影され、intentでは動かせない (`MOBILITY_CONTRADICTION`)。preservedConstraintsにreservation列挙を含む。

既存run (intentなし) の挙動・provenance不変: `intentPreferences`/`personalizedIntent`/`personalizedIntentDigest` はすべてdefault付きadditive field (null / sentinel / null)。既存test fileは無修正で全organizer test (1179件) が通過。sentinel identityは既存plan/preview/applyのbyte挙動に影響しない (identityはprovenance rowの追加のみ)。

## Executed test surface

監査sessionが対象commit `428d45a2387f6afb7459a6448facfd5ccb672eed` のcheckout上で実行:

- `git status` — 作業開始時、未追跡 `.ux-review/` のみで、実装・他Agentの変更への保護を確認。
- `./gradlew spotlessCheck` → **PASS** (exit 0)。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL**。organizer全体で **1179 tests, 0 failures/errors** (test結果XMLより集計)。新規testの実行確認: `ContextExportBuilderTest` 13, `IntentCodecTest` 9, `IntentPlannerAdapterTest` 2, `IntentValidatorTest` 13, `SourceContextIdentityTest` 6, `UnlinkabilityAndIdentityContractTest` 3, `AndroidExportSessionStoreTest` 6 — すべて0 failures。
- CI (GitHub Actions, `pull_request` event, PR #322, head `428d45a2387f6afb7459a6448facfd5ccb672eed`): run [34953107141](https://github.com/nunu1733/NunuLauncher/actions/runs/34953107141)。1回目のattemptで `organizer-instrumentation-issue52-tests` がUI timeout (`ComposeTimeoutException`, 5s waitUntil) で失敗 → 監査sessionが `gh run rerun --failed` で失敗jobを再実行し、再実行で **PASS** (10m46s)。merge gate `final-status` は **success** (job: https://github.com/nunu1733/NunuLauncher/actions/runs/34953107141/job/104336508558)。`organizer-unit-tests` / `check-style` / `build-debug-apk` / instrumentation 7件はすべてsuccess。run metadata照合: `head_sha=428d45a...`, `event=pull_request`, `pull_requests=[322]`, `conclusion=success`。`gh api` で確認。
- 参考: 直近の `main` push run (34940500617, `9ea2ba0e`) はissue52 jobを含め全job success。1回目の失敗はUI待ちtimeoutのflake (本PR diffの純粋package追加・data class additive変更と無関係な経路) と判定した。

## Findings

問題の分類と記録。**mergeを阻害するP1は存在しない。**

- **P2-1: AC-13のplanner一致property testが未実装** — plan.md Verificationとspec AC-13は「FIXED/MOVABLE/CONDITIONAL判定がplannerの移動可否と一致するproperty test」を要求するが、実装には `projectMobility` と `determinePreservation` を同一入力で束縛するproperty testが存在しない (全固定原因の個別table-driven testのみ)。本監査では現行planner実装との一致をコード読解で独立確認したが、plan Risks節が想定した「planner将来変更時にmobility述語の乖離をtestが検出する構造」は未成立。乖離時の最悪caseはvalidatorの誤reject/誤許可でlayout破壊ではない (plannerが正本)。後続Issueでの分離を推奨する。
- **P3-1: `unresolvedRefs` 内重複の失敗class**: 同一refが `unresolvedRefs` 内で重複する場合、coverage partition違反 (specのINCOMPLETE_COVERAGEの「両集合の重複」) ではなく `DUPLICATE_REF` に分類される。どちらもfail-closed rejectでzero-writeであり、挙動リスクはないが分類の解釈差として記録する。
- **P3-2: intent `pageAffinity` の範囲検証なし**: `ItemIntent.pageAffinity` は任意のIntを受け付け、export gridのpage ordinal範囲 (0..pageCount-1) との照合が存在しない。消費はordering biasのみで下流影響はないが、「contextと同じ抽象度」という契約の抽象度一致はintent側で強制されない。将来schema versionでの上限検討を推奨。
- **P3-3: export側のdegenerate入力はuntyped例外**: items > 512、ref衝突、空labelの場合、builderはtyped failureではなく `IllegalStateException` でfail-loudする。純粋builder内のfail-fastであり失敗時zero-writeだが、typed failure経路の外であることを記録する。
- **参考 (flake記録)**: CI 1回目attemptの `organizer-instrumentation-issue52-tests` 失敗 (`verifiedSuccessKeepsAppliedWordingAfterRecoveryPreviewCancel` の5s UI待ちtimeout) は本PR diffと無関係なUI instrumentationのflakeと判定。再実行でPASS、`main` 最新push runも同jobはsuccess。
- **留意 (未確認範囲)**: (1) composer/producer接続 (#205/#206) までの実runでの `EXPORT_MISMATCH` (不明session宛intent) 経路は本PRでは未接続で、store不在読出しtestによる間接確認のみ。(2) emulator instrumentation test群は本監査ではローカル実行せず、CIの成功で確認 (API 35/36系7job + db migration + issue155/299/52/53/99 + shared-writer)。(3) 再生成時のpreview無効化 (spec「再生成とstale preview」シナリオ) の実装はcomposer統合と同時に#205で到達可能になり、本PRでの直接testは存在しない (plan.mdに記録済みの意図的defer)。

結論: spec 204の受入条件 AC-1〜AC-14 は、上記P2-1 (AC-13のtest表面の部分欠落、実挙動は一致) を除き実装とtestで検証済み。ADR-0007/0012/0010のauthority境界との整合を確認。merge gate (`final-status`) は対象commit上でsuccess。P2-1は後続Issueでの追跡を推奨するが、planner正本契約によりlayout破壊につながる経路は存在しない。
