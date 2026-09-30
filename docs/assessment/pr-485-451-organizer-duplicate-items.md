# High-risk audit: PR #485 全体整理が同じ起動先の重複アイテムを同じ新規フォルダに入れず、重複をpreviewで示す（#451）

> Status: **GO**
> Audit date: 2026-09-30

- Auditor: 独立session（subagent監査者）。実装sessionではなく、本PRのdiff作成・review・検証実行に関与していない。監査は実装sessionとは別の作業として、作業tree（= PR headそのもの。`git rev-parse HEAD` と監査対象SHAが一致、tracked変更なし）でread-only照合とtest再実行を行った。
- PR: https://github.com/nunu1733/NunuLauncher/pull/485 （`risk: layout-data` label確認済み。head branch `issue-451-spec-plan`、base `main`）
- Head SHA: 828401fbadbfad5d7950891515906121036b43fd
  （`gh pr view 485` の `headRefOid` と一致。base `origin/main` とのdiff = 29 files, +2146/-25）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36707859774 （pull_request event、CI workflow、head `828401fbadbfad5d7950891515906121036b43fd`、2026-09-30。**conclusion = success、`final-status` job = success**。job内訳は「Executed test surface」節）
- 監査対象commit上で本記録が未存在だったため、run [36707859748](https://github.com/nunu1733/NunuLauncher/actions/runs/36707859748) の `high-risk-evidence` はfailure（`FAIL: no docs/assessment/pr-485-<slug>.md audit record for this PR`）。本recordのpush後のrunで再評価される。
- Criteria: [spec 451](../../specs/451-organizer-duplicate-items/spec.md)（accepted）の AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9 および規則 N-1, N-2, N-3, N-4, N-5, N-6, N-7 / FR-021 / NFR-014
- Criteria: [policy bundle ADR](../../docs/adr/0007-authoritative-organization-policy-sources.md)（accepted）の ADR-0007 §8「Upgrade, migration, downgrade, backup, and rollback」（bundle semantic version 1 increment手順）
- Criteria: [strategy catalog ADR](../../docs/adr/0012-versioned-layout-strategy-catalog.md)（accepted）の ADR-0012（「A behavior change is a new ID」。`StrategyId` / catalog不変の確認）
- Criteria: [spec 12](../../specs/12-deterministic-full-layout-planner-v1/spec.md)（implemented）の AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7（planner contract regressionの再検証対象。規則P-03/P-04/P-05/P-07/P-08/P-10/P-12のamendmentは本PR diff内で2026-09-30付 amendment blockとして適用済みであることを確認）

## Scope

- 対象diff: `origin/main`..`828401fbad`（29 files, +2146/-25）。production 11 file、tests 13 file、docs/spec 5 file。
- **production変更はplanning/rules/ui/文言seamのみ。`organizer/application/**`（高リスクpath一覧）と `src/`（Launcher3 bridge）はdiff内に出現しない**ことを `git diff --stat` で確認。DB schema・適用経路・recovery・migration・dependency・permission変更なし（`lawnchair/src/app/lawnchair/organizer/application/` と `AndroidManifest.xml` はdiff外）。
- 本auditが実コードで追跡した経路（spec N-1〜N-7との照合）:
  - **N-1（重複検出の純粋関数）**: 新規 `planning/DuplicateLaunchTargets.kt` の `duplicateSurplusIds(items)`。`APPLICATION`/`DEEP_SHORTCUT` かつ `TargetKey.AppKey`/`ShortcutKey` のみを `target` 値でgroup化し、size ≥ 2のgroupを `ItemId` 昇順（UTF-8 byte順）に並べ `drop(1)`（代表=最初の1個、超過分=残り）。strategy・分類・locale・threadに依存しない。`PlanningPlacement.place` 冒頭で1回だけ計算し、全経路へ同一集合を渡す。
  - **N-2（新規フォルダ形成からの除外）**: `determinePreservation` がsurplusに対し `PreserveReason.DUPLICATE_LAUNCH_TARGET` を返すことで、`FullOrganization` と `ScopeComposedOrganization` full-run phaseの両movable stream（`PlanningPlacement.kt:104` と `:165` の `filter { determinePreservation(...) == null }`）から自動的に外れる。保持行・占有cell処理は既存の `place()` occupancy loop と `appendPreservedPlacements` が発行する。`FolderFormation.kt` / 3 executorのgroup化規則は無変更（除外後membersでP-04/P-05が自然に適用される構造）。
  - **N-3（preservation precedence と incrementalの付け替え）**: `determinePreservation` の `when` で `DUPLICATE_LAUNCH_TARGET` 分岐は `STRUCTURAL` の後・`else`（= movable）の前に配置。RESERVED_REGION > LOCKED > UNAVAILABLE_TARGET > DOCK > WIDGET > APP_PAIR > LEGACY_SHORTCUT > NON_TARGET > STRUCTURAL > **DUPLICATE_LAUNCH_TARGET** > movable、という既存優先順序の末尾追加であり、locked/dock/既存folder member等の強いreasonが勝つ。`PreserveReason` enum上の位置も `STRUCTURAL` と `ALREADY_CANONICAL` の間（plan推奨どおり）。`placeIncrementalRun` は `effectiveReason = reason ?: ALREADY_CANONICAL` の既存構造のままsurplus集合のみ渡し、配置不変・reason語彙のみ `ALREADY_CANONICAL` → `DUPLICATE_LAUNCH_TARGET` に付け替わる（`PlanningPlacement.kt:212` 付近）。
  - **呼び出しsite網羅（plan Risk節の取り漏らし懸念）**: `determinePreservation` の全6呼び出しsite（`place()` occupancy loop、full-run movable stream 2箇所、`placeIncrementalRun`、`FullRunExecution.kt` の `appendPreservedPlacements` と widget stream障害物判定）がすべて同一のsurplus集合を受けることをgrepで確認。`FullRunContext` に `duplicateSurplus` fieldを追加し、executor側2箇所は `context.duplicateSurplus` を読む。
  - **N-4（警告）**: `WarningCode.DUPLICATE_LAUNCH_TARGET` は既存3値（`LEGACY_SHORTCUT_REVIEW` / `FALLBACK_CATEGORY` / `UNAVAILABLE_PRESERVED`）の**末尾**に追加。警告は `place()` の全item loop内でsurplus 1個につき1件 `Warning(DUPLICATE_LAUNCH_TARGET, [ItemParam])` を追加。run mode分岐より前にあるため `createsFolders`・run modeに依存せず発生し、代表（surplus非所属）は警告なし。正準化は既存 `warningComparator`（code ordinal優先）のままのため既存警告のcanonical順序不変。
  - **N-5（決定性・P-10）**: 代表選択は `ItemId` canonical順（materialize後安定）。`materializedReplanMovesNothingAndKeepsDuplicateSurplusReason`（`DuplicateLaunchTargetsTest`）と重複corpus fixture 4件のIDEMPOTENCE check、`harness/Oracle.kt` のreplan期待値への `DUPLICATE_LAUNCH_TARGET` 追加で再証明。
  - **N-6（preview）**: `OrganizationPreviewContent.kt` の `warningText` / `preservedReasonText`（exhaustive `when`）と `OrganizationPreviewWording` / `ResourceOrganizationPreviewWording` / `organizationPreviewWording()` への2 field追加のみ。新行種別・画面なし。application module（`PlanPreviewProjector`）は無変更（`WarningCode` 素通し）。
  - **N-7（version契約とoracle）**: `PolicyModels.kt` の `POLICY_BUNDLE_VERSION` を `organization-policy-v2.7` → `v2.8` へ。`BuiltInOrganizerPolicyBundleSource.kt` のversion注記へ#451行追加。catalog（runtime-supported 9 strategy、default `CANONICAL_PAGE_COMPACT_V1`）は不変。golden corpus: `ExampleCorpus` への重複fixture 4件追加（`duplicate-launch-targets` / `duplicate-locked-representative` / `duplicate-min-group-boundary` / `duplicate-multi-surplus-folder`。代表が `ItemId` "10" < "2" になるUTF-8 byte順を意図的に固定）と `tests/unit/resources/planner-golden-corpus/sha256.txt` の再固定（1行差替えのみ）。
- **digest再固定の理由（AC-5）**: `sha256.txt` の差分は全corpus集約digestの1行のみで、重複fixture 4件のcorpus追加が唯一の原因であることをdiffで確認。既存fixtureへの出力影響なしの証明は新規test `GoldenOracleCorpusTest.everyPre451CorpusSourceKeepsItsPerFixtureDigest` が行う: `digestsBySource()` のうち `ExampleCorpus.duplicateFixtureSources` 以外の全source（#451以前の140 source）の `source=digest` 結合に対するSHA-256集約を、重複fixture追加前のcommit `c1ba1d1caa`（headの祖先であることを `git merge-base --is-ancestor` で確認）でpinし（`a8e55b89...`）、pin一致をassertする。#451 fixtureの除外漏れ/過剰除外も同testのguard assertで検出する。本auditの再実行でこのtestはgreen。
- 対象外: AC-7のja実機確認（PR本文に記録済みの通り未実施。en/emulator実施。ja文言はunit resource testが担保）、CI merge gate runの取得そのもの（本auditは既存green runを確認・参照する）。

## Criteria check

総合: **全受入条件 PASS（下記の限定を除き）**。

- **AC-1（planner interface経由で重複超過分が新規フォルダmemberにならない）**: **PASS**。`DuplicateLaunchTargetsTest` は `planner.plan(input)` → `Planned` のplanner interface seamのみを検証し、`assertNewFolderTargetsUnique` が全新規フォルダ内の起動先一意をassertする（`duplicateSurplusIsExcludedFromTheNewFolderAndWarnedOnce` ほか）。property test `DuplicateInjectionPropertyTest.injectedDuplicatesNeverJoinANewFolderAndStayIdempotent`（重複注入wrapper。既存generator/corpus不変）も同一保証。corpus重複fixture 4件にもfolder target一意とIDEMPOTENCE oracleが効く。
- **AC-2（代表選択の決定性とP-10再実行）**: **PASS**。`duplicatePlanningIsDeterministicAcrossInvocationsLocaleAndThreads`（同入力→値等価）、`materializedReplanMovesNothingAndKeepsDuplicateSurplusReason`（全target不変・`Moved`/`newPages`/`newFolders`なし・同一reason。`PostPlanMaterializer` seam）。property testもidempotent replanを含む。plan Verificationどおりの重複注入wrapper方式であり、`SyntheticFixtureGenerator`（`template = index % 8`）は無変更で既存 `generated:<id>` caseの内容不変。
- **AC-3（警告1件/surplus、代表0件、`createsFolders`非依存、canonical順序不変）**: **PASS**。`everySurplusItemIsWarnedExactlyOnceAndTheRepresentativeIsNot`、`newWarningCodeAppendedLastKeepsExistingCanonicalWarningOrder`、および `ScopeComposedPlannerTest.duplicateSurplusUnderNeverCreatingFolderStrategiesIsPreservedAndWarnedOnce`（createsFolders=false strategy × ScopeComposedOrganization。commit `230464180a` のP2対応test）。警告発行がrun mode分岐前にある構造も確認済み。
- **AC-4（P-03優先度、P-05分割、incremental付け替え）**: **PASS**。`lockedRepresentativeKeepsLockReasonAndSuppressesTheMovableDuplicate`（N-1契約拡張: 非movable代表でもmovable側を抑止）、`unavailableDuplicateKeepsItsStrongerReason`、`structuralMemberRepresentativeSuppressesTheTopLevelDuplicate`、`exclusionBelowMinGroupSizePreventsFolderFormation`（除外後membersでmin判定。形成阻止と形成継続の両境界）、`incrementalRunKeepsAllCapturedPositionsAndRelabelsOnlyTheSurplusReason`。
- **AC-5（既存corpus個別digest不変、理由付き再固定）**: **PASS**。`everyPre451CorpusSourceKeepsItsPerFixtureDigest` のpin機構と、本audit再実行でのgreen。再固定理由はPR本文「digest再固定の理由」節とcorpus fixture commit `b45c53c18a` に記録済み。
- **AC-6（bundle v2.8、catalog不変）**: **PASS**。`BuiltInOrganizerPolicyBundleSourceTest` が `semanticVersion == "organization-policy-v2.8"` と新規 `issue451VersionBumpLeavesTheStrategyCatalogAndDefaultUnchanged`（runtime-supported 9 strategy・default `CANONICAL_PAGE_COMPACT_V1` の全 `StrategyId` 不変）を検証。ADR-0007 §8の1 increment、ADR-0012の「A behavior change is a new ID」不触及のspec確定どおり。
- **AC-7（preview警告行・件数truth・理由表示・ja/en文言）**: **PASS（unit + エミュレータ実機）/ ja実機は未実施**。unit: `PlanPreviewProjectorDuplicateWarningTest`（`duplicateSurplusProjectsAsPreserveRowAndWarningRowWithMatchingCounts` = spec 195 D2件数truth、`duplicateWarningWithoutADispositionFailsClosed`、`duplicateWordingResourcesExistInEnAndJa`）、`OrganizationPreviewContentTest.duplicateWarningAndPreservedReasonRenderThroughTheExistingGroups`。実機: PR本文に2026-09-30記録（emulator-5554 / API 36、branch head `230464180a` のdebug build。重複fixtureでpreview警告行6件=警告見出し件数truth一致、保持理由表示「kept in place as a duplicate of another icon」、適用後verified・新規フォルダ内重複なし、既存folder member代表のN-1拡張動作も確認。ja実機未実施、unit resource testが担保）。本auditはこの記録をPR本文で確認した（実機操作の再現は監査対象外）。
- **AC-8（B7目標のbenchmark文書反映）**: **PASS**。`docs/engineering/editing-burden-benchmark.md` §6 B7行へ構造保証（新規フォルダ内同一起動先重複0組）と可視性（preview警告行識別）の目標を反映、baseline 9との整合を明記。#441 baseline値の変更なし。spec「B7目標」節と文面一致。
- **AC-9（検証commandの成功とPR記録）**: **PASS**。PR本文に実行結果記録あり。本auditが同じcommand群を独立再実行（次節。全green）。CI merge gate: 対象head上のrun 36707859774がsuccess（`final-status` 含む全実行job success）。
- **FR-021 / NFR-014**: `docs/product/requirements.md` のFR-021 status を accepted（2026-09-30）へ更新済み。NFR-014（編集負担ベンチマーク）はAC-8の反映で接続。
- **spec 12 amendment（P-03/P-04/P-05/P-07/P-08/P-10/P-12）**: diff内で各P節へamendment block追加済み、Change historyへ#451行追加。本文と実装の文面一致を確認（末尾追加の警告順序、P-04のeligible条件追加、P-05のpost-exclusion適用、P-07の最下位predicate、P-08のreason付け替え、P-10のexact reason拡張、P-12のItemParam only）。

## Executed test surface

独立sessionによる対象head `828401fbadbfad5d7950891515906121036b43fd`（作業tree = head、tracked変更なし）での再実行:

```bash
git rev-parse HEAD
# → 828401fbadbfad5d7950891515906121036b43fd

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.*'
# → BUILD SUCCESSFUL in 29s (exit 0)
#   test-results XML集計: tests=321 failures=0 errors=0 skipped=0
#   内訳（本audit関連）: DuplicateLaunchTargetsTest 11 / DuplicateInjectionPropertyTest 1 /
#   GoldenOracleCorpusTest 2（pinned aggregate digest + everyPre451CorpusSourceKeepsItsPerFixtureDigest）

./gradlew spotlessCheck --rerun-tasks
# → BUILD SUCCESSFUL in 16s（5 tasks executed、全強制再実行）

git diff --check origin/main..828401fbadbfad5d7950891515906121036b43fd
# → exit 0（whitespace errorなし）
```

CI merge gate（対象head上のpull_request run）: https://github.com/nunu1733/NunuLauncher/actions/runs/36707859774 （run id 36707859774、head SHA `828401fbadbfad5d7950891515906121036b43fd`、2026-09-30、**conclusion = success、18m43s**）。job結果:

- success: `changes`、`build-debug-apk`、`organizer-unit-tests`、`check-style`、`validate-repo-contract`、`organizer-instrumentation-exchange-import-ui-tests`、`organizer-instrumentation-category-override-tests`、`organizer-instrumentation-manual-organization-ui-tests`、`organizer-instrumentation-method-choice-journey-tests`、`organizer-instrumentation-onboarding-proposal-tests`
- skipped（path filter、変更対象外lane）: `organizer-instrumentation-restore-capture-tests`、`-reservation-recovery-tests`、`-production-input-tests`、`-db-migration-tests`、`-shared-writer-tests`
- **`final-status`: success**（job 109868510408）

assemble（AC-9の一部）はCI `build-debug-apk` のsuccessで代替し本auditでは再実行しない。`high-risk-evidence` gate run [36707859748](https://github.com/nunu1733/NunuLauncher/actions/runs/36707859748) は本record不在でfailure。push後の再評価で解消する。

## Findings

1. **test数の僅差（阻塞りではない）**: PR本文の検証記録は `planning.*` = 320 tests、本auditの再実行は321 tests。差分1件はPR記録後のP2対応commit `230464180a` で追加された `ScopeComposedPlannerTest.duplicateSurplusUnderNeverCreatingFolderStrategiesIsPreservedAndWarnedOnce` と整合し、矛盾ではない。
2. **AC-7のja実機確認は未実施**（PR本文記録どおり。enエミュレータのみ）。ja/en文言は `duplicateWordingResourcesExistInEnAndJa` unit resource testが担保する。spec 195 AC-6型の要求をunit testで満たす構成であり、本auditは阻塞りとしない。jaでの実表示確認が必要になった場合は後続のowner確認item。
3. **P-10サブscenario「新規pageへ溢れる」の直接構成は不可**（PR本文「記録済み逸脱」）。full-runではconservation上overflow scenarioが構成できず、materializer matrix（代表がfolderへ入る／locked／min未満／複数surplus）+ 多ページreplanで網羅した旨がcommit `b45c53c18a` に記録されている。本auditで該当commitの説明とtest構成の記載を確認した。N-5の安定性論証（`ItemId` 不変性）と直接replan testにより目的（再実行で代表/surplusが入れ替わらない）は担保されている。
4. **E2E instrumentation fixtureの適応変更**（`ManualOrganizationProductionE2EInstrumentationTest`、test-only）: 旧fixtureが同一起動先の行を含んでいたため、#451規則下ではフォルダ形成が抑止される。行へdistinct componentを与える正しい適応であり、むしろ新規則が実E2E経路まで効いていることの裏付け。production変更なし。
5. **新規観察（阻塞りではない）**: `duplicateSurplusIds` は `items.asSequence()` でO(n log n)（group化+sort）であり、planner入力サイズに対し無視できるコスト。純粋関数でtotal（例外を投げない）ことも確認。
6. **spec本文のstatus**: `accepted` のまま（workflow運用どおりclose時の最終PRで `implemented` へ更新する物）。CI `validate-repo-contract` job はsuccess。

## 判定

**GO** — spec 451の全受入条件（AC-1〜AC-9）、規則N-1〜N-7、spec 12 amendment（P-03/P-04/P-05/P-07/P-08/P-10/P-12）、ADR-0007 §8・ADR-0012のversion契約を対象headの実コード・diff・test oracleで確認した。独立再実行（planning unit tests 321 tests / 0 failures、spotlessCheck強制再実行、`git diff --check`）はすべてgreen。merge gateの機械要件も充足: 対象head `828401fbadbfad5d7950891515906121036b43fd` 上のCI run 36707859774がsuccess（`final-status` = success）。残るnote（ja実機未実施、test数僅差、P-10 overflow構成の代替網羅）はいずれも対象外項目または記録済みの限定であり、mergeを阻塞らない。本recordのpush後、`high-risk-evidence` gateがこのheadを機械検証する。audit後はdocs-only commitのみ許容される。
