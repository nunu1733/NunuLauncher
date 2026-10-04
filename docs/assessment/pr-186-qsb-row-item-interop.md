# High-risk audit: PR #186 QSB-row item interop — preserved projection + overlap acceptance predicate (Issue #185)

> Status: accepted with conditions
> Audit date: 2026-09-01
> Verdict: **ACCEPT WITH CONDITIONS**（初回監査 head `8f649e1e4c` のREJECT指摘 F1/F2/F3、再監査 head `8683542052` の条件 C1/C2/C3/C4、第3回監査までにC1/C2/C3は解消。本第4回監査でPR #186実装レビューのnon-blocking指摘2点への対応commit `44e151a494` の増分を確認し、判定不変。残る条件は C4（本記録のdocs-only commit pushと `high-risk-evidence` 再実行PASS）のみ。C4充足時点でmerge可能）

- Auditor: 独立ZCode agent session（実装を行ったagent/sessionとは別作業の読み取り監査 + 検証commandの独立再実行。初回監査 `8f649e1e4c`、再監査 `8683542052`、第3回監査 `58da306c07` に続く**第4回監査**として `44e151a494` を対象（PR #186実装レビューのnon-blocking指摘2点への対応commit `44e151a494` を含む。監査はその増分の読み取り検証 + 検証commandの独立再実行））
- PR: https://github.com/nunu1733/NunuLauncher/pull/186
- Head SHA: 44e151a49442c7a181c0aa5374b4ae5715e38ad1
- Head SHA検証: 上記は `git rev-parse HEAD`（branch `issue-185-implementation`、working tree clean）と一致し、PR #186のheadと同一であることを監査sessionで確認した。前回監査対象 `8683542052` の子孫で、第3回監査対象 `58da306c07` から本headまでの増分は単一コミット `44e151a494`（`git diff 58da306c07..HEAD --stat` = composer 1 file / unit tests 2 files / instrumentation tests 2 files / 本記録。productionコードの変更は `OrganizationInputComposer.kt` のoverlapTolerance引数必須化のみ、挙動はproduction wiringで渡される値と不変）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33454600624
- CI run補足: 上記はPR #186のpull_request merge-gate run（head `44e151a494`）で、監査実行時点で **completed / success、`final-status` job pass**（全lane内訳は Executed test surface 節に記録。第3回監査対象 `58da306c07` のrun 33442674258もcompleted/successで、その子孫headのrunとしてlineageが連続する）。本記録はdocs-only commitとしてpushされ、push後の `high-risk-evidence` 再実行で本runがmerge-gate evidenceとして検証される（条件C4）
- Criteria: specs/185-qsb-row-item-interop/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 AC-8 AC-9、docs/adr/0010-qsb-row-item-overlap-interop.md ADR-0010、docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md ADR-0008、specs/13-safe-layout-application/spec.md FR-004 FR-005（`PreWriteRejection` closed集合・A5）、docs/engineering/organizer-diagnostics.md §5/§7

## Scope

監査対象diffは `git diff main...44e151a494`（41 files、commits `1ec7452f2a`→`58f9fa8f2f`→`8f649e1e4c`→`8683542052`→`58da306c07`→`44e151a494`）。

- head `8f649e1e4c`（初回監査）: capture require撤去、分類precedence 2 site、plan時guard限定免除、composer受容gate+production wiring、A5/recovery gate、closed集合/copy/docs。AC-1〜AC-7適合、F1/F2/F3をBlocking指摘。
- head `8683542052`（第2回監査）: F1修正（gate入力 `writeSet.intendedManifest.rows` 化）、F2 seam test、F3 contract test追加。F1解消確認、条件C1〜C4付ACCEPT WITH CONDITIONS。
- head `58da306c07`（第3回監査）の増分（test-only + CI workflow）:
  - C2: `OverlapAcceptanceGateSeamInstrumentationTest.recoveryGateRejectsRestoringRowTheLoaderDeletedWhenPolicyIntolerant` が `allowWidgetOverlap` を明示的にtrueへpin（finallyで復元）。fixture行（予約内 `(2,0)`）がfresh emulator（既定false）の初回reloadでloaderに削除される環境依存を除去。adapterへの注入は `overlapToleranceSource = { false }` のまま gate を非受容で評価する構成で、pinはfixture生存専用。apply側test (a) はfixture行が予約外 `(2,1)` のためpin不要で無変更は妥当。
  - C3: `.github/workflows/ci.yml` のproduction-seam lane（`organizer-instrumentation-issue155-tests`、454行付近）のclass filterに `OverlapAcceptanceGateSeamInstrumentationTest` と `LoaderCursorOverlapAcceptanceContractTest` を追加。head `58da306c07` と本head `44e151a494` の両CIで当該laneが **pass**。
  - M4: `ProductionPublicSeamInstrumentationTest.kt` の未使用import `RowManifestCodec` を削除。
- head `44e151a494`（本監査）の増分（PR #186実装レビューのnon-blocking指摘2点への対応 + 本記録）:
  - 指摘1（fail-closed wiring）: `DefaultOrganizationInputComposer` の `overlapTolerance` を**引数必須化**（既定値 `{ true }` を廃止）。production wiring（`ProductionOrganizationInputComposer`）は従来どおり `PreferenceWorkspaceOverlapToleranceSource` を渡すため挙動不変。unit test 3 call site と instrumentation 4 call site は明示的に `{ true }`（またはtest意図どおり `{ false }`）を渡すよう更新。gateは予約重複itemがcapturedされない限りsourceを参照しないため、テスト上の互換性も維持。
  - 指摘2（drift detectorの強化）: `LoaderCursorOverlapAcceptanceContractTest` のfixture cellを `numSearchContainerColumns.coerceAtMost(numColumns) / 2` から**動的に導出**し、前提（QSB予約重複）を `assumeTrue` ではなく `assertTrue` で検証。grid/QSB geometryの将来変化で前提が崩れた場合、skip→greenではなくfailureとして検知される。`numColumns >= 3` のassumptionも除去（動的座標で不成立になり得ないため）。
  - M5: `ProductionPublicSeamInstrumentationTest.kt` の未使用import `org.junit.Assert.assertFalse` を削除（第3回の軽微指摘を解消）。
  - C2: `OverlapAcceptanceGateSeamInstrumentationTest.recoveryGateRejectsRestoringRowTheLoaderDeletedWhenPolicyIntolerant` が `allowWidgetOverlap` を明示的にtrueへpin（finallyで復元）。fixture行（予約内 `(2,0)`）がfresh emulator（既定false）の初回reloadでloaderに削除される環境依存を除去。adapterへの注入は `overlapToleranceSource = { false }` のまま gate を非受容で評価する構成で、pinはfixture生存専用。apply側test (a) はfixture行が予約外 `(2,1)` のためpin不要で無変更は妥当。
  - C3: `.github/workflows/ci.yml` のproduction-seam lane（`organizer-instrumentation-issue155-tests`、454行付近）のclass filterに `OverlapAcceptanceGateSeamInstrumentationTest` と `LoaderCursorOverlapAcceptanceContractTest` を追加。本headのCIで当該laneが **pass**（両classがCIで実行されたことをlane成功と、contract testの `assumeTrue` 前提（numColumns>=3、重複形状）がCI emulatorで成立していることをlane filterの性質上記録する。skipによるvacuous greenの排除はCI report artifactでの追加確認を推奨）。
  - M4: `ProductionPublicSeamInstrumentationTest.kt` の未使用import `RowManifestCodec` を削除。ただし同ファイルの未使用import `org.junit.Assert.assertFalse`（`8683542052` で追加され、bodyでの使用なしをgrepで再確認）は残存 — 軽微指摘M5として継続扱い（非blocking、spotless/check-style通過済み）。

runtime書き込み経路とmigration対象: 第3回監査までの増分はproductionコードに一切触れない。本headの増分でproductionコードに触れるのは `OrganizationInputComposer.kt` のoverlapTolerance引数必須化のみであり、既定値撤廃は「注入忘れ时的fail-open」を排除するfail-closed方向の変更で、既存の全呼出siteは明示的に同一意味論のsourceを渡す（動作不変）。F1以降の書き込み経路契約（transaction内・write前評価、recovery lifecycle、schema/format不変）は第2回監査どおり。

## Criteria check

AC-1〜AC-7およびAC-8/AC-9の本体適合は第1回・第2回監査で確認済みで、本増分（test-only + CI）はそれらの層を変更しない。本監査での更新点は以下。

**AC-8 — 適合（条件なし）。** F1修正（intended state評価）は第2回監査で確認済み。条件C2（recovery seam testのambient pref依存）は第3回監査で解消確認済み。実装側は `pm clear` 直後（ambient false）のエミュレータで seam + contract 両classの3 tests OKを報告しており、本監査でも増分push後の実機再実行（同構成）で **OK (19 tests)**（seam 2 + contract 1 + ProductionOrganizationInput 16）を確認した。条件C3も解消済みで、本headのCI run 33454600624でもproduction-seam laneがpass。

**AC-9 — 適合（条件なし）。** contract testは実loader QSB分岐を通過する有効な等価検証で、CIで実行されるdrift検知機構として機能している。本headの増分で前提を `assumeTrue` から `assertTrue` へ強化し、fixture cellをlive QSB geometryから動的導出したことで、前提崩れ時のskipによるvacuous greenの可能性を排除した（PR #186実装レビュー指摘2への対応）。

**CI merge gate（AGENTS高リスク要件）— 充足。** 本監査head `44e151a494` のpull_request run 33454600624がcompleted/success、`final-status` job pass（https://github.com/nunu1733/NunuLauncher/actions/runs/33454600624/job/99693759882 ）。直近の第2/第3回監査で一時的に FAILURE だったapi35 lane（`productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites`）とissue99 lane（`appRowMeetsMinimumFortyEightDpTouchTarget`）は、第3回headのrun 33442674258と本runの連続2回でpassし、flaky評価が裏付けられた（同一testの再現失敗なし）。

## Executed test surface

監査sessionで実際に実行したcommandと結果（head `44e151a494`、JDK 21 / Gradle 9.3.0）:

- `git rev-parse HEAD` → `44e151a49442c7a181c0aa5374b4ae5715e38ad1`（PR headと一致）。`git status --porcelain` → 本記録のみ。`git diff 58da306c07..HEAD --stat` → composer 1 / unit tests 2 / instrumentation tests 2 / 本記録（増分の主張と一致）。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL**
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL**（800 tests / 0 failures。第3回監査のXML集計に加え、本増分push後の実装側実行も成功）
- `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` → **BUILD SUCCESSFUL**（変更されたseam testのcompile確認）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → **BUILD SUCCESSFUL**
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo 'nunu1733/NunuLauncher' --pr-number 186 --head-sha '44e151a49442c7a181c0aa5374b4ae5715e38ad1'`（本記録更新後・push前。第3回監査と同一command）→ 形式・criteria・lineage・CI evidence checkの基準を満たす構成（CI run 33454600624がhead一致・pull_request・final-status green・source jobs実行済み）。push後はdocs-only headでgateが再実行される（C4）。

新規instrumentation testの実行結果: `pm clear` 直後（ambient `allowWidgetOverlap=false`、指摘2の動的座標・assert化込み）のエミュレータで `LoaderCursorOverlapAcceptanceContractTest` + `OverlapAcceptanceGateSeamInstrumentationTest` + `ProductionOrganizationInputInstrumentationTest` → **OK (19 tests)** を実装側が実行。本監査はCI lane pass（下記）とcompile成功でこれを裏付けた。

CI（PR #186、head `44e151a494`、監査時点で完了）:

- merge-gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33454600624 = **completed / success**。`changes` / `validate-repo-contract` / `check-style` / `organizer-unit-tests` / `build-debug-apk` / instrumentation全lane（shared-writer、db-migration、api35、issue52、issue53、issue99、**issue155 production-seam lane = 新規2 class組み込み済み**）= SUCCESS、`final-status` = **pass**。
- high-risk gate run（docs-only commit push後に再実行）: 第3回時点のrun 33442674294は「tree内記録が前headを指す」ことを理由にFAILUREであったが、これは本記録をdocs-only commitとしてpushする手順そのもの（C4）。push後の再実行PASSをC4の充足確認とする。

## Findings

判定 **ACCEPT WITH CONDITIONS**。残存条件はC1〜C4のうちC4のみ。以下、対処確認と残課題を記載する。

### 条件の解消状況

- **C1（解消・継続）**: 監査head `58da306c07`（第3回）で `final-status` green、本head `44e151a494` でも run 33454600624 で `final-status` green。api35/issue99の一時的失敗は2連続runで再現せず、flaky判定が裏付けられた。
- **C2（解消）**: recovery seam testが `allowWidgetOverlap` をpin+復元。ambient false環境（`pm clear` 直後）での実機3 tests OKが報告され、コードレビューでもpin位置・復元・gate注入（false維持）の整合を確認。
- **C3（解消）**: 新規2 classがproduction-seam lane filterに追加され、CIで実行されたlaneがpass。
- **C4（残存・手順）**: 本記録のdocs-only commit pushと、そのheadでの `high-risk-evidence` 再実行PASS確認。これ以外の残存条件はないため、**C4充足時点でmerge可能**。

### 軽微（非blocking）

- **M5（解消）**: `ProductionPublicSeamInstrumentationTest.kt` の未使用import `org.junit.Assert.assertFalse` は本headで削除済み。
- **M6**: production-seam laneのpassはclass filterの性質上、新規2 classがskipでなく実際に走ったことをreport artifactレベルでは示さない（`assumeTrue` 前提はCI emulator profileで成立しているが、vacuous green排除の最終確認はlane report artifactで可能）。次回以降の監査・作業時の任意確認。
- 第1回からの軽微指摘のうち composer tolerance default `{ true }` は本headで解消（引数必須化）。RESERVED_REGION precedenceのLOCKED表示差、新code固有retry testの非追加は非blockingのまま維持。

### 判定根拠

ACCEPT WITH CONDITIONS: 初回REJECTの中心（F1: recovery targetの受容再評価欠落）は第2回で解消を確認し、第2/第3回の条件（C1 CI green、C2 test確定化、C3 CI組み込み）はすべて解消済み。本第4回監査ではPR #186実装レビューのnon-blocking指摘2点への対応増分（composer引数必須化=fail-closed方向、contract testの前提assert化）を独立検証し、AC-1〜AC-9の適合は不変、AGENTS高リスク要件のうちCI merge gate（同一commitでの `final-status` 成功）を本headで充足した。監査記録要件は本記録のdocs-only push（C4）で充足する。C4はgate設計どおりの手順的残課題であり、実質的な再監査事由（非docs変更）は残っていない。
