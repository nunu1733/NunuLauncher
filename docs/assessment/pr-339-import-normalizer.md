# Independent audit: PR #339 External Agent ExchangeのImport Normalizer (spec 329)

> Status: accepted
> Audit date: 2026-09-17

- Auditor: Independent audit session (ZCode subagent、GLM。implementation PRを担当したsessionとは別の作業として実施。実装側の主張は参照したが依拠していない)
- PR: https://github.com/nunu1733/NunuLauncher/pull/339
- Head SHA: `f98d9e7d8fae26714aa59ed29ce67c1af726f8f0` (base `main` = `15f4f0209f`)
- CI run (audit時点のPR checks): https://github.com/nunu1733/NunuLauncher/actions/runs/35121959814 (head `f98d9e7d8f` 紐付けを `commits/f98d9e7d8f/check-runs` APIで確認) / high-risk-evidence: https://github.com/nunu1733/NunuLauncher/actions/runs/35121959803
- Criteria: `specs/329-import-normalizer/spec.md` AC-1〜AC-10、D-1〜D-8、Security regression coverage節 (+ `plan.md` の検証計画)

## Scope

本監査は、accepted spec 329 (2026-09-17 accepted、ChatGPT re-review Accepted 0 findings) とplanを正本として、PR #339のhead `f98d9e7d8fae26714aa59ed29ce67c1af726f8f0` の全diff (14 files, +1167/−21) を自ら読んで確認したものである。監査用checkout (`/private/tmp/audit-329-wt`、head `f98d9e7d8f`、clean) でspec/plan/実装/test本文を実読し、automated evidenceは監査session自らの実行結果のみを用いた。

確認した変更面:

- **ImportNormalizer.kt (新規)**: pure・Android-free。transport正規化 (先頭BOM除去・CRLF/CR→LF、parserと同一冪等規則) → D-1優先順の外形認識。優先順1は `IntentImportParser.isMarkerLine` (internal、同module再利用) によるINTENT marker行検出で元textを `MarkedFraming` 委譲。優先順2はstateful単一passのfence走査。優先順3は深さ上限64のbracket scan事前gate + strict `Json.parseToJsonElement` (lenient無効、rootが `JsonObject` のみ) で、parse結果は外形判定のみに使用しpayloadはtrim済み元textをそのまま渡す (再serializeなし)。
- **ExchangeImportPipeline.kt**: `prepare` 先頭で `utf8ByteLengthExceeds` (#205所有gate) を先に適用し `InputOversize` でtyped identity不変のまま決着させてからnormalizerへ渡す。`ExchangeImportFailure.Normalization` (新2種のみを包む) 追加、`Prepared(intent, framing)` への `RecognizedImportFraming` (MARKER/FENCED_JSON/STANDALONE_JSON) additive field追加。`Prepared` の構築箇所はpipeline内decode成功後の1箇所のみ、`IntentCodec.decode` 呼び出しも全framing共通の1箇所のみ (grep機械確認。framing経由でcodec/validatorを迂回する経路は構造的に存在しない)。
- **ExchangeFlowUi.kt + strings (en/ja)**: `exchangeFailureText` のexhaustive `when` へ `Normalization` 2 case追加。失敗表示は17種 → **19種** (envelope 4 + normalization 2 + contract 13)。ja/en両localeに `exchange_failure_normalization_ambiguous` / `exchange_failure_normalization_unrecognized` を追加し、いずれも認識可能形式の提示 (marker囲み / ```json block 1つ / JSONのみ) と再コピー手順を含む (AC-7の文言要件)。
- **正本更新**: `CONTEXT.md` (新用語「インポート正規化」+ 既存2項の責務境界)、`DESIGN.md` (§9 personalization行 + gate 13)、spec 205 change history (7th: marker規則・typed失敗4種・上限は不変)、`docs/product/requirements.md` (FR-017へspec 329追記)。planのDocumentation updates checklistと一致。
- **tests**: `ImportNormalizerTest` (新規22件)、`ExchangeImportPipelineTest` (+11件、既存1件はmarker無しproseの再分類へ期待値更新)、`ExchangeFlowStateHolderTest` (+1件)。

`IntentImportParser` / `IntentCodec` / `IntentValidator` / `SessionExportReconstructor` / `ExchangeFlowController` / transport群・DB・Launcher3 bridgeには変更がない (diff --statの14 fileが上記範囲に収まることを確認。NO OUT-OF-SCOPE FILES)。依存・権限・通信・DB migrationの追加はなし。

## Criteria check

spec 329の受入条件ごとの確認結果。test名は監査sessionがtest本文を実読し、assert内容を確認したもの (名前のみの採用はしていない)。

- **AC-1 (accepted framing一覧とcanonicalization固定)**: PASS (unit)。`markerFormDelegatesTheOriginalTextVerbatim` (元textの同一性assert)、`singleFencedJsonBlockWithProseIsPayloadInterior` (fencing="json"、前後説明文破棄、interior一致)、`standaloneObjectIsTrimmedOnlyNeverReserialized` (trimのみ、compact textの同一性assert)、`markerLinesWithSurroundingAsciiWhitespaceStillDelegate`、`bomAndCrlfNormalizeBeforeRecognition` (BOM+CRLF入りと素の入力のpayload一致)、`loneCrNormalizesToLf`。優先順 (marker > fence > standalone) は実装の分岐順と `markerPriorityWinsOverFencesAnywhere` で固定。
- **AC-2 (standalone/fencedがmarkerと同一経路でimportできる)**: PASS (unit)。`standaloneJsonPreparesWithStandaloneFramingAndValidates` / `fencedJsonPreparesWithFencedFramingAndValidates` が同一session・同一validatorで `Validated` まで到達することをassert (device evidence部分はAC-10として残置)。
- **AC-3 (曖昧・認識不能のtyped reject)**: PASS (unit)。曖昧: `twoClosedBlocksAreAmbiguousRegardlessOfTags` (json×2、json+text の両方 → `AmbiguousBlocks`)、`bareFenceBlocksCountTowardTheAmbiguityTotal` (json+bare、bare+bare → `AmbiguousBlocks`)。認識不能: `inlineBracesInProseAreNeverExtracted`、`nonJsonSingleFenceFallsThroughToUnrecognized`、`onlyAJsonObjectRootIsStandalone` (配列・文字列・数値root)、`contextMarkersAreNotIntentMarkers`。pipeline経由では `ambiguousBlocksAreTypedNormalizerFailures` と `envelopeFailuresPassThroughWithoutSessionAccess` (marker無しprose → `Normalization(UnrecognizedFormat)`、意図された再分類)。
- **AC-4 (semantic無変更、D-6部分文字列)**: PASS (unit)。`everyPayloadIsASubstringOfTheTransportNormalizedInput` が4種corpus (fenced+前後prose、大文字JSON tag+深い入れ子、standalone、前後空白付き) に対し `normalizedInput.contains(payload.payload)` をliteralにassert — substring制約の実assertである。加えて `multiLineFencedInteriorIsVerbatimExceptOuterTrim` (外側trimのみの差分を固定)、`recognitionIsDeterministicForTheSameInput`、`normalizedPayloadIsIdempotentUnderRenormalization`。field修正・再serializeの経路は実装に存在しないことをコード読解で確認 (`Payload` の構築はinterior trimとtext trimの2箇所のみ)。
- **AC-5 (必ず #204 codec/validatorを通る)**: PASS (unit + 構造)。構造: `Prepared` 構築と `IntentCodec.decode` が `prepare` 内の共通1箇所のみで、`MarkedFraming`/`Payload` 両分岐がそこへ収束する (grep機械確認)。test: `innerInfoFenceExtendsTheBlockAndConvergesOnSchemaMismatch` (fenced pathがcodecへ到達し `SCHEMA_MISMATCH`)、`contractFailuresAreFramingIndependent` (standaloneで未知key → `SCHEMA_MISMATCH`、fencedで `INVALID_ENUM` / `UNKNOWN_REF`)、`markedReplyPreparesWithMarkerFraming` + 既存 `malformedPayloadIsContractSchemaMismatch` (marker path)。framingのみ成功で `Prepared` になる経路は存在しない。
- **AC-6 (既存reject分類のregression)**: PASS (unit)。`contractFailuresAreFramingIndependent` がunknown key / invalid enum / out-of-scope refを新framingで同一typed failureに再入力。`mobilityContradictionPassesThroughAsContractFailure` / `oversizePayloadIsContractOversize` / `structuralChangeAfterExportConvergesOnContextStale` 等の既存corpusは無修正でgreen (監査実行で確認)。FORBIDDEN_CONTENT自体の新framingでの再入力corpusは本diffにないが、全framingが同一codec/validator seamへ収束する構造 + 無修正のvalidator suiteで担保される (Findings 4.3参照、非ブロッキング)。
- **AC-7 (typed区別・19種一対対応・案内文言・framing伝播)**: PASS (unit + compile保証)。UI mapは `ExchangeFlowUi.exchangeFailureText` のexhaustive `when` でenvelope 4 + normalization 2 + contract 13 = 19種 (compile時網羅)。`ExchangeFlowStateHolderTest.normalizationFailuresReachTheImportOutcomeScreen` がcontroller seam (`importReply`) 経由で新2種がtypedに生き残ることをassert。ja/en strings追加は実読確認 (認識可能形式の提示 + 再コピー手順を含む)。`Prepared.framing` の経路別表明は `markedReplyPreparesWithMarkerFraming` / `fencedJson…` / `standaloneJson…` の3 test。Compose `stringResource` hopはplan AC-7行に明示された検証方針 (compile保証 + review) どおり (Findings 4.4参照)。
- **AC-8 (security corpus)**: PASS (unit)。nested wrapper: `fenceInsideMarkerRegionIsNeverUnwrapped` (marker内fence → `SCHEMA_MISMATCH`)、`bareFenceLinesAroundAMarkerPairStayProse` (fence内marker → 受理)、`innerInfoFenceExtendsTheBlockAndConvergesOnSchemaMismatch` + `nestedWrapperOutcomesFollowTheFenceGrammar` (内側info付きfence開始行は閉じない → blockが伸びて `SCHEMA_MISMATCH`)、独立2 block → 曖昧 (normalizer + pipeline両面)。envelope境界: `envelopeGateSettlesOversizeBeforeTheNormalizer` (limit+1 → `InputOversize`、exact-limit → normalizerの `UnrecognizedFormat` で検査順序を証明)。bounded処理: `deeplyNestedStandaloneInputFailsClosedWithoutInterpreting` (10,000深度 → 認識不能、parser到達前のdepth gate)。trailing text: `fencedJson…Validates` (block後proseは受理を阻害しない) + 2 block目JSONらしきinput → 曖昧reject。
- **AC-9 (raw text非記録)**: PASS (code review)。新規2 fileへの `Log.` / `println` / file書込み / journal呼出のgrepは0件 (監査session実施)。exchange経路のdiagnostics不記録契約は本diffで変更されていない (spec D-7 / organizer-diagnostics.mdどおり)。test oracleどおり「diagnostics契約test / code review」の後者で充足。
- **AC-10 (physical-device evidence)**: **OPEN (documented follow-up)**。planのExecution checklistも未消化のままで、後続evidence PRでの実施を予定している。本監査では自動検証できないため、残置を明示する (merge blockとはしない — plan受入どおり)。

### D-decisionの個別確認 (監査指示項目)

- **D-3 (tag-blind総数)**: `fencedBlocks` はblock外の任意の ``` 行 (bare含む) をopening候補として数えるstateful走査で、json + untagged / untagged + untagged の2 block入力が `bareFenceBlocksCountTowardTheAmbiguityTotal` で `AmbiguousBlocks` になることを実assert。json tag付きのみを数える過大受理は構造的に不可能。
- **D-4/D-8 (fence grammar)**: closing判定は `` ``` `` + 残りwhitespaceのみ (`substring(3).trim().isEmpty()`)。内側の `` ```json `` 行は閉じずblockを伸ばす (`nestedWrapperOutcomesFollowTheFenceGrammar` がpayload `{\n```json\n}` を実assert)。EOFで開いたままのcandidateはblockに数えない (`unclosedFenceDoesNotCountAsABlock` → 認識不能)。info string比較は `asciiLower` によるASCII限定で、`fenceInfoStringIsAsciiCaseInsensitiveOnly` が `JSON`/`Json` 受理・`jsonc` 拒否・**`jſon` (U+017F) を `UnrecognizedFormat` で拒否**することをassert (`String.equals(ignoreCase=true)` のUnicode foldingを使わない実装と一致)。
- **D-5 (typed failure所有)**: `ImportNormalizationFailure` は `AmbiguousBlocks` / `UnrecognizedFormat` の2 variantのみ (sealed interface実読)。envelope検査は `prepare` 先頭の #205所有gateが先に `Envelope(InputOversize)` で決着させ、limit+1/exact-limitの組でnormalizer到達順序をtestが証明。`Prepared` は `framing: RecognizedImportFraming` を保持し、MARKER/FENCED_JSON/STANDALONE_JSONの全経路が別々のtestで表明される。
- **D-6 (verbatim部分文字列)**: 上記AC-4のとおり、testは `contains` によるsubstring実assertで、fenced/standalone両経路のpayload構築箇所は実装上2箇所のみ (trim以外の変換なし)。
- **Zero-write/diagnostics**: 新codeにlogging/永続化なし (grep 0件)。normalizer失敗はpipeline戻り値のtyped failureのみで、partial適用・状態保持の経路はない。

## Executed test surface

監査session自らが実行 (head `f98d9e7d8fae26714aa59ed29ce67c1af726f8f0`、worktree clean、source未変更、JDK 21.0.12):

- `git rev-parse HEAD` → `f98d9e7d8fae26714aa59ed29ce67c1af726f8f0`
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` を確認
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.personalization.exchange.*' --tests 'app.lawnchair.organizer.ui.exchange.*'` → **BUILD SUCCESSFUL in 1m 9s** (exit 0)。結果XML集計 (監査sessionが自ら集計): **83 tests / 0 failures / 0 errors / 0 skipped** (8 class)。内訳: `ImportNormalizerTest` 22、`ExchangeImportPipelineTest` 17、`ExchangeFlowStateHolderTest` 6、`IntentImportParserTest` 16、`ExchangeDisclosureStateTest` 7、`ExchangePackageComposerTest` 6、`SessionExportReconstructorTest` 5、`ExchangeGenerationGateTest` 4。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL** (exit 0)。
- `assembleLawnWithQuickstepGithubDebug` は監査sessionでは未実行 (時間節約)。代わりに同一head上のCI `build-debug-apk` の完了をmerge時確認事項とする (下記Findings 1)。

## Findings

1. **CI状態 (audit時点、正直記録)**: PR checks run 35121959814 (head `f98d9e7d8f` 紐付け確認済み) で、`check-style` / `validate-repo-contract` / `changes` / `high-risk-evidence` (run 35121959803) は **success**。一方 `organizer-unit-tests` / `build-debug-apk` / instrumentation 8 lane (`db-migration` / `shared-writer` / `issue52` / `issue53` / `issue99` / `issue155` / `issue299` / `api35`) はaudit時点 **in_progress (pending)** であった。監査session自身のローカル実行で同一surfacesのunit testは成功しているが、CI完了はmerge前の確認事項として残る。
2. **本記録pushによるhead移動**: 本audit記録はPR branch上のdocs-only commitとしてpushされるため、push後のPR headは `f98d9e7d8f` から移動する。deltaは本file追加のみ (実装・testへの接触なし) であり、merge者は最終head上でCI (特に `final-status` 相当のmerge gateと `organizer-unit-tests`) が緑であることを確認すること。本監査のverdictは `f98d9e7d8f` に対するものであり、docs-only deltaを許容する。
3. **high-risk gate該当性**: PR #339に `risk: layout-data` / `risk: migration` labelは付与されていない (label空、API確認済み)。layout DB書込み経路の変更はないためplan Risks節の見立て (非該当) と一致する。ただしsecurity sensitiveなparse追加であるため、本監査はAGENTS.mdの独立エビデンス要件に準じて自主実施したものである。`high-risk-evidence` checkはaudit記録不在の状態でsuccessしており、gate機構上は本記録不要の対象である。
4. **非ブロッキングの観察事項**:
   - **injection corpusの文言**: spec Security regression coverageの "ignore previous instructions" 等の埋め込みcorpusに対し、実装は汎用prose (前後説明文・trailing prose) での `assertEquals` による領域外破棄の実assertで担保しており、注入文言をliteralに含むfixtureは本diffにない。payload領域の完全一致assertは注入文言の種別に非依存の強い保証であり、framing認識が内容を解釈しない構造 (D-8) と合わせて目的は充足するが、将来corpus拡張時にliteral fixtureを追加する価値はある。
   - **D-6 property testの形式**: 「property test」はランダム生成ではなく決定的corpus (4種 + BOM/CRLF/trim/冪等/決定性の個別test) による充足。純粋JVM moduleでgenerator依存を避けた判断と読めるが、spec oracleの「property test」文言との形式差はある (保証内容 — substring・決定性・冪等性 — は全て実assertされている)。
   - **AC-6のFORBIDDEN_CONTENT**: 新framingでの再入力corpusは `SCHEMA_MISMATCH` / `INVALID_ENUM` / `UNKNOWN_REF` をCoverageし、FORBIDDEN_CONTENTは全framing共通のvalidator seam + 無修正の既存suiteによる担保 (上記AC-6のとおり)。
   - **AC-7の文字列hop**: `holder.import` が `Dispatchers.Main` をpinするためJVM testから呼べず、planに明示された検証方針 (controller seam test + exhaustive `when` のcompile保証 + ja/en strings実在確認) で運用されている。本監査はその方針どおりstrings実在と`when`網羅を実確認した。
5. **AC-10 (physical-device evidence)** は未実施であり、plan checklistのdocumented follow-upとして残置する。merge自体はこれを blocker としない (planの受入条件どおり後続evidence PRで実施)。
6. **独立verdict**: **Approve** (head `f98d9e7d8fae26714aa59ed29ce67c1af726f8f0` に対する)。accepted spec 329のD-1〜D-8は実装どおりであり、fuzzy extraction禁止 (D-2、候補選択経路の不存在をコード読解で確認)、tag-blind曖昧総数 (D-3)、ASCII限定fence grammar (D-4/D-8)、typed failureの所有分離と `Prepared.framing` 伝播 (D-5)、verbatim部分文字列制約 (D-6)、raw text非記録 (D-7、grep確認)、ならびにSecurity regression coverageの主要corpusはautomated testで検証されている。上記1 (CI pending) の完了確認と2 (docs-only delta上のCI緑確認) をmerge条件とし、AC-10はdocumented follow-upとして残置する。本記録push以降はdocs-only commitのみが許容され、code変更時は本SHAに対する再監査を要する。
