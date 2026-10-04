# High-risk audit: PR #335 External Agent向けPersonalizedIntent authoring契約の簡素化 (partial authoring v3, spec 330)

> Status: accepted
> Audit date: 2026-09-16

- Auditor: Independent audit session (ZCode subagent, GLM; implementation PRを担当したsessionとは別の作業として実施)
- PR: https://github.com/nunu1733/NunuLauncher/pull/335
- Head SHA: 05d4669c4283a3e26d2bcfde8de6538b061ae032
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35109057035
- Criteria: specs/330-partial-intent-authoring/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, FR-017

## Scope

本監査は、accepted spec 330 (`specs/330-partial-intent-authoring/spec.md`、D-1〜D-6 owner受入れ済み、re-review comment 5698414707) とplan (`specs/330-partial-intent-authoring/plan.md`) を正本として、PR #335のhead `05d4669c4283a3e26d2bcfde8de6538b061ae032` (base `main` = `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b`) の全diff (22 files, +1013/−72) を自ら読んで確認したものである。実装側の主張 (PR本文・planの検証結果記載) は参照したが、依拠していない。

監査対象headの確定経緯: 初回監査を `8e7afb4d322419d45c43be18432665c3ce62f4bf` (実装commit) で開始したところ、当該head上のCI check-styleが失敗 (ktlintの行折り形式違反、`tests/unit/app/lawnchair/organizer/personalization/IntentCompletionTest.kt`)。修正push後のhead `05d4669c42` を改めて監査対象とした。delta `8e7afb4d32..05d4669c42` を自ら読み、**1 test fileのみ・行折りの空白整形のみ (private helper 3関数のsingle-line化)・振る舞い変更なし** ことを確認した。以下の確認は `aab0d293d1..05d4669c42` の全範囲に対して行った。

確認した変更面:

- **v3 version bump** (`ContextExportModels.kt`): `SCHEMA_VERSION = "personalization-context-v3"` / `INTENT_SCHEMA_VERSION = "personalized-intent-v3"` (定数のみ。spec 330 D-3、#204 immutable semantic version規則)。
- **completer新規** (`IntentCompletion.kt`): `RefDecision` (Authored / UnresolvedAuthored / UnresolvedByOmission)、`CompletedPersonalIntent` (decisions mapがexport全refの完全分割であることを `check` で要求)、`IntentCompletion.complete()`。全exportRefsを `UnresolvedByOmission` で初期化し、`unresolvedRefs` → `UnresolvedAuthored`、`itemIntents` → bare判定 (`isBare`: 全semantic field null) で `UnresolvedAuthored` / それ以外 `Authored` のみを付与する。field値の生成・書換分岐は存在しない (pure・total・deterministic)。
- **validator** (`IntentValidator.kt`): coverage規則から全数cover検査 (`covered + unresolved != exportRefs`) を削除し、unresolved内重複・両集合のoverlap検査のみにnarrow。未知ref・重複ref・enum・mobility・staleの各検査は無変更 (読み比べ済み)。completionは成功pathの最後に1回だけ呼ばれ (全fail分岐の後)、identityはcompleted形式から計算。`ValidatedPersonalizedIntent.completed` はderived property (`lazy`) で、validate内の `exportRefs` と同一式 (`export.items.map { it.ref }.toSet()`) から再導出するためseam signatureは不変。
- **identity** (`IntentIdentity.kt`): canonical行をcompleted表現からref昇順single passで生成。`Authored` は `item|...` 行、`UnresolvedAuthored` / `UnresolvedByOmission` は同一の `unresolved|ref` 行 (D-5/D-6)。row grammar・sha256は現行維持。
- **planner adapter** (`IntentPlannerAdapter.kt`): `validated.completed.decisions` の `Authored` のみを `ItemPreference` へ投影。unresolved 3形態はpreference不生成。`ItemPreference` のfield対応は無変更。
- **instruction** (`ExchangePackageComposer.kt`): coverage要求を "Author only what you actually judged" / "you do not have to cover every ref... never guessed" へ置換、response format表記をv3へ更新。
- **UI strings** (`values/strings.xml` / `values-ja/strings.xml`): `exchange_failure_incomplete_coverage` の文言のみ更新 (分割違反の説明)。`exchange_failure` 文字列数は変更前後で両localeとも17 (grep countで確認)。failure class数 (13) ・UI失敗表示17種は不変。
- **正本更新**: spec 204/205/331のChange history (v3拡張の記録)、`CONTEXT.md` 用語 (パーソナライゼーション意図)、`DESIGN.md` gate 12行。
- **tests**: `IntentCompletionTest` (新規10件)、`IntentValidatorTest` (partial受理へ期待値反転 + 分割違反・bare entry未知ref・完全omission追加)、`IntentCodecTest` (v3 + v1/v2 fail-closed + encode version)、`ExchangeTargetScopeCouplingTest` (candidate omissionのcanonical unresolved化 + v1拒否維持)、`ExchangePackageComposerTest` (部分authoring文言契約)、`ContextExportCodecTest` (v3表記)、`ManualOrganizationRunTest` (identity呼出のcompleted形式追従)。

layout application path・DB migration・Launcher3/AOSP bridgeには変更がない (diffの全22 fileが上記の範囲に収まることを `git diff --name-only` の除外検査で機械確認。「NO OUT-OF-SCOPE FILES」。`organizer/application/`、gradle定義、AndroidManifest、Launcher3側fileは未変更)。依存関係・権限・通信の追加はなし。書込み経路の変化はなし (export session store `AndroidExportSessionStore.kt` record v2 / `SessionExportReconstructor.kt` も未変更で、session recordはintent schema versionを保持しないためbump無影響の設計どおり)。

## Criteria check

spec 330の受入条件ごとの確認結果。AC番号はspec本文に定義がある。

- **AC-1 (design比較と採否の固定、D-1〜D-6受入れ)**: PASS (spec review対象)。spec本文にA/B/C・a/b/c・versioning 3案の比較表とD-1〜D-6、受入れ記録 (comment 5698414707) がある。実装不要のACであり実装側の追加作業もない。
- **AC-2 (未言及 = canonical unresolved / no-op、推測補完なし)**: PASS (unit)。実装は `IntentCompletion.complete()` のみがomission意味を所有し、未言及refへ `UnresolvedByOmission` の状態付与以外を行わない (コード読解: field生成分岐なし)。test: `unmentionedRefsCompleteToUnresolvedByOmissionOnly` (完全分割 + 非言及refが全て `UnresolvedByOmission`)、`completedIntentNeverCarriesGeneratedItemIntentFields` (全decision走査でAuthored以外はfield不所持)、`aFullyOmittedIntentCompletesToAllUnresolved` (完全omission受理)、`aBareEntryNormalizesToUnresolvedAuthored` (D-6正規化)、`plannerProjectionExcludesEveryUnresolvedForm` (unresolved 3形態がprojectionに現れない)、`replayOfTheSameSemanticContentYieldsTheSameIdentityAndProjection` (bare ≡ explicit unresolvedのprojection等価)、`aPartialResponseIsAcceptedAsCanonicalUnresolvedOmission`。
- **AC-3 (FIXED省略許容後もlock bypass不可能)**: PASS (unit + 構造)。validator層: mobility検査はdiff不変で、`fixedRefContradictionsAreMobilityContradictions` / `lockedItemMovementPreferenceIsNotForbiddenContent` (FIXED + importance → `MOBILITY_CONTRADICTION`) / `widgetGroupMembershipIsAMobilityContradiction` (CONDITIONAL) / `fixedRefPreserveOnlyIntentsAreAccepted` (preserve許可) が無修正でgreen。planner authority層: `planning/` 以下にdiffなし (`determinePreservation`・lock/bounds制約の正本は未変更)、`WidgetIntentAuthorityTest` / `DefaultLayoutComposerPlannerRegressionTest` 含む全suite回帰green。completer層: `ItemIntent` field値を生成する経路がコード上存在しない (上記AC-2)。omissionされたFIXED refがpreferenceを生まないことは `fixedOmissionIsAcceptedAndProjectedLikeAnyOmission` で検証。
- **AC-4 (planner前のcomplete変換、completeness、D-6 stable identity / replay)**: PASS (unit + 構造)。completeness: `unmentionedRefsCompleteToUnresolvedByOmissionOnly` が `decisions.keys == export refs全集合` をassertし、completerの `check(decisions.keys == exportRefs)` が型不変条件として構築時に要求される。determinism: `completionIsDeterministic`。D-6 stable identity: `bareEntryExplicitUnresolvedAndOmissionShareOneIdentity` (3表現が同一digest + canonical行 `unresolved|refA` の直接assert、`item|refA` 行の不在assert)。replay: `replayOfTheSameSemanticContentYieldsTheSameIdentityAndProjection` (異表現の同一semantic内容が同一identity・同一projection)。authored非流出: planner seamは `IntentPlannerAdapter.project` のみで、その入力は `validated.completed` のみを走査する構造 (コード確認)。authored文書は `ValidatedPersonalizedIntent.intent` にdiagnostics用として保持されるだけ。
- **AC-5 (unknown refの省略許容対象外、bare entryも対象外)**: PASS (unit)。`unknownRefIsRejectedFailClosed` (既存corpus。unresolvedRefs宛ghost)、`bareEntryWithAnUnknownRefIsStillUnknownRef` (新規。全field nullのbare entry宛ghost → `UnknownRef("ghost")`)。検査順序はコードで確認: 未知ref検査は成功path末尾のcompletionより前にあるため、正規化経路で未知refが受理される経路は構造的に存在しない。
- **AC-6 (v3 bump、旧version fail-closed、session無影響、identity schemaVersion、#206 superset)**: PASS (unit + 回帰)。`savedV1AndV2DocumentsAreRejectedFailClosed` (v1とv2の両方が `SchemaMismatch`。dual-versionなし)、`encodeWritesTheCurrentSchemaVersion` (producer側v3広告)、context側 `v1IntentsFailClosedOnDecode` + `ContextExportCodecTest` のv3 roundtrip、identity `schemaVersion` assert (`aFullyCoveredIntentIsAcceptedWithAnIdentity` が `INTENT_SCHEMA_VERSION` と同値)、instruction文言契約 (`instructionAsksForPartialAuthoringInsteadOfFullCoverage` / `instructionV2ExplainsCandidatesAndMarkers`)、session無影響 (`AndroidExportSessionStoreTest` 無修正回帰green。record v2自体にdiffなし)、superset互換 (`aPartialResponseIsAcceptedAsCanonicalUnresolvedOmission` の前半でfull-coverage文書も受理)。#206 superset互換の明文化はspec D-3にある。
- **AC-7 (4系統fixture)**: PASS (unit)。coverage omission: `aPartialResponseIsAcceptedAsCanonicalUnresolvedOmission` + `anOmittedCandidateRefCompletesToCanonicalUnresolved` (candidate省略もcanonical unresolved)。fixed omission: `fixedOmissionIsAcceptedAndProjectedLikeAnyOmission` (locked ref省略が受理されpreference不生成)。explicit unresolved: `completedIntentNeverCarriesGeneratedItemIntentFields` / `bareEntryExplicitUnresolvedAndOmissionShareOneIdentity` のexplicit変体 (validated経由で受理を確認)。malicious lock override: `fixedRefContradictionsAreMobilityContradictions` / `lockedItemMovementPreferenceIsNotForbiddenContent`。
- **AC-8 (#329 normalizerとの責務境界)**: PASS (明文化 + 代替test)。境界はspec「#329 Import Normalizerとの責務境界」節 (normalizerは外形のみ・ref追加禁止、completerがomission解釈を単独所有、順序図) で固定。#329は未実装 (personalization packageにnormalizer moduleなし) で、spec test oracleどおり#329側での境界testは将来作業。本PR時点の代替証拠として `IntentImportParserTest` 全suite (verbatim抽出・`multiLinePayloadsArePreservedVerbatim`・`parsingIsDeterministicAndIdempotentForTheSameInput` 等) がframing層がpayloadを書換えないことをpinし、意味解釈は `IntentCompletion` のみにあることを構造が保証。
- **FR-017**: spec 330 frontmatter `requirements: [FR-017]` の対象。intentはplanning seamへの入力に限定されたまま (adapter投影は`Authored`のみ)、fail-closed検証 (unknown/duplicate/overlap/enum/forbidden/mobility/stale/expiry/mismatch) は全維持、privacy境界 (completerはref文字列と状態のみ扱い、新たな個人情報経路なし) を上記AC検証で確認。planner制約・lock保持の正本は未変更。

補足確認: UI失敗表示は17種のまま (両localeの `exchange_failure*` 文字列数を変更前後でgrep count照合、変更は `incomplete_coverage` の文言のみ)。`INCOMPLETE_COVERAGE` の適用条件はunresolved内重複・両集合overlapの2分岐にnarrowされ、`coverageSplitViolationsAreStillRejected` が両方をtyped assertする。

## Executed test surface

監査session自らが実行 (head `05d4669c4283a3e26d2bcfde8de6538b061ae032`、`git status` clean、source未変更):

- `git rev-parse HEAD` → `05d4669c4283a3e26d2bcfde8de6538b061ae032`
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` を確認
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL in 34s** (exit 0)。結果XML集計: **1302 tests / 0 failures / 0 errors / 0 skipped** (126 test class)。新規 `IntentCompletionTest` 10件を含む。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL** (exit 0)。
- 参考: 同一laneは整形修正前の `8e7afb4d32` 上でも **1302 tests / 0 failures** で成功済み (deltaは当該test fileの空白整形のみであるため結果は同一と解釈できるが、本記録のevidenceは `05d4669c42` 上の実行である)。
- `./gradlew assembleLawnWithQuickstepGithubDebug` は監査sessionでは**未実行** (時間節約)。代わりに監査対象head上のCI `build-debug-apk` (同一variant) の成功をevidenceとする (下記)。
- `gh api repos/nunu1733/NunuLauncher/actions/runs/35109057035` → `event: pull_request`、`head_sha: 05d4669c4283a3e26d2bcfde8de6538b061ae032`、PR #335紐付け、`conclusion: success` を確認。全14 job success: `final-status`、`organizer-unit-tests`、`check-style`、`build-debug-apk`、`changes`、`validate-repo-contract`、instrumentation 8 lane (db-migration / shared-writer / issue52/53/99/155/299 / api35)。監査要件の3 source job (organizer-unit-tests / check-style / build-debug-apk) はいずれも実行かつsuccess (skipではない)。

## Findings

1. **CI merge gate**: CI run 35109057035 が監査対象commit `05d4669c42` 上で完全緑 (`final-status` success、必須3 source job実行済みsuccess)。高リスクgateの機械要件は本記録のpush後に再評価される。
2. **head移動の経緯**: 初回監査対象 `8e7afb4d32` のCI check-styleがktlint形式違反 (行折り) で失敗し、`05d4669c42` で修正された。deltaは1 test fileの空白整形のみであることを監査sessionがdiff読解で確認済み。実装semanticsに影響する変更はない。
3. **high-risk-evidence gate run 35109057201 (failed)**: 本監査記録不在時の評価である (失敗原因はaudit record不在のみ)。pr-333の先例と同じく、本記録push後の再評価をmerge条件とする。
4. **非ブロッキングの観察事項**:
   - **projection順序の実装詳細変化**: `IntentPlannerAdapter` の `ItemPreference` リスト順が、authored文書順から `decisions` (HashMap) の反復順に変わった。`FullRunExecution.kt` の全消費箇所を確認した結果、`associateBy` / item別 `firstOrNull` / union-find (可換・成分keyソート済み) のいずれかであり順序非依存のため挙動への影響はない。将来order-sensitiveな消費を追加する場合は注意 (後続で意識すべき実装詳細)。
   - **`ValidatedPersonalizedIntent.completed` の再導出**: validator内completion結果を保持せず `lazy` で再導出する設計。入力式がvalidate内と同一でdeterministicなため等価だが (identity / projection等価testで担保)、誤用防止の等価assertは将来の強化候補。
   - **IntentValidator先頭のKDoc検査順序コメント** (staleness → mobilityと記載、実装はmobility → staleness) は本PR以前からのdriftであり本diffでは触れていない (対象範囲外。任意のdocs後追い)。
   - **「ほぼ判断なしintent」の黙示受理**: spec Risks節どおりの意図された挙動変化。最終保護はpreview → confirm → applyのままであり、`CompletedPersonalIntent` の計数 (`omittedCount` 等) でdiagnostics基盤は提供済み。UI表示導線は #332/#205 (本PR対象外)。
5. **独立verdict**: **Approve** (head `05d4669c4283a3e26d2bcfde8de6538b061ae032` に対する)。accepted spec 330のD-1〜D-6は実装どおりであり、推測補完の不在 (completerの閉じた規則)、bare entry / 明示unresolved / 省略の同一identity・同一planner効果、v1/v2 fail-closed、coverage narrow (17種表示・13 class不変)、session無影響はautomated testで検証されている。上記4の観察はmerge blockではなく、本記録push後にhigh-risk gateの再評価が通ることを条件とする。本記録push以降はdocs-only commitのみが許容され、code変更時は本SHAに対する再監査を要する。
