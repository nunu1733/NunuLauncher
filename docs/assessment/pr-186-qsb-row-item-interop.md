# High-risk audit: PR #186 QSB-row item interop — preserved projection + overlap acceptance predicate (Issue #185)

> Status: accepted with conditions
> Audit date: 2026-08-31
> Verdict: **ACCEPT WITH CONDITIONS**（初回監査 head `8f649e1e4c` のREJECT指摘 F1/F2/F3、および再監査 head `8683542052` で課した条件 C1/C2/C3/C4(M4含む) のうち、C2/C3/C1は解消を確認。残る条件は C4（本記録のdocs-only commit pushと `high-risk-evidence` 再実行PASS）のみ。C4充足時点でmerge可能）

- Auditor: 独立ZCode agent session（実装を行ったagent/sessionとは別作業の読み取り監査 + 検証commandの独立再実行。初回監査 `8f649e1e4c`、再監査 `8683542052` に続く第3回監査として `58da306c07` を対象。コードは未変更、本記録の更新のみ）
- PR: https://github.com/nunu1733/NunuLauncher/pull/186
- Head SHA: 58da306c076874d8956da2754b4f1cd641322df8
- Head SHA検証: 上記は `git rev-parse HEAD`（branch `issue-185-implementation`、working tree clean）と一致し、PR #186のheadと同一であることを監査sessionで確認した。前回監査対象 `8683542052` の子孫で、両者の間の増分は単一コミット `58da306c07`（`git diff 8683542052..HEAD --stat` = ci.yml 1行、seam test +8行、ProductionPublicSeam import −1行、本記録。productionコードへの変更なし）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33442674258
- CI run補足: 上記はPR #186のpull_request merge-gate run（head `58da306c07`）で、監査実行時点で **completed / success、`final-status` job pass**（全lane内訳は Executed test surface 節に記録）。本記録はdocs-only commitとしてpushされ、push後の `high-risk-evidence` 再実行で本runがmerge-gate evidenceとして検証される（条件C4）
- Criteria: specs/185-qsb-row-item-interop/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 AC-8 AC-9、docs/adr/0010-qsb-row-item-overlap-interop.md ADR-0010、docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md ADR-0008、specs/13-safe-layout-application/spec.md FR-004 FR-005（`PreWriteRejection` closed集合・A5）、docs/engineering/organizer-diagnostics.md §5/§7

## Scope

監査対象diffは `git diff main...58da306c07`（37 files、+1774/−46、commits `1ec7452f2a`→`58f9fa8f2f`→`8f649e1e4c`→`8683542052`→`58da306c07`）。

- head `8f649e1e4c`（初回監査）: capture require撤去、分類precedence 2 site、plan時guard限定免除、composer受容gate+production wiring、A5/recovery gate、closed集合/copy/docs。AC-1〜AC-7適合、F1/F2/F3をBlocking指摘。
- head `8683542052`（第2回監査）: F1修正（gate入力 `writeSet.intendedManifest.rows` 化）、F2 seam test、F3 contract test追加。F1解消確認、条件C1〜C4付ACCEPT WITH CONDITIONS。
- head `58da306c07`（本監査）の増分（test-only + CI workflow + 本記録）:
  - C2: `OverlapAcceptanceGateSeamInstrumentationTest.recoveryGateRejectsRestoringRowTheLoaderDeletedWhenPolicyIntolerant` が `allowWidgetOverlap` を明示的にtrueへpin（finallyで復元）。fixture行（予約内 `(2,0)`）がfresh emulator（既定false）の初回reloadでloaderに削除される環境依存を除去。adapterへの注入は `overlapToleranceSource = { false }` のまま gate を非受容で評価する構成で、pinはfixture生存専用。apply側test (a) はfixture行が予約外 `(2,1)` のためpin不要で無変更は妥当。
  - C3: `.github/workflows/ci.yml` のproduction-seam lane（`organizer-instrumentation-issue155-tests`、454行付近）のclass filterに `OverlapAcceptanceGateSeamInstrumentationTest` と `LoaderCursorOverlapAcceptanceContractTest` を追加。本headのCIで当該laneが **pass**（両classがCIで実行されたことをlane成功と、contract testの `assumeTrue` 前提（numColumns>=3、重複形状）がCI emulatorで成立していることをlane filterの性質上記録する。skipによるvacuous greenの排除はCI report artifactでの追加確認を推奨）。
  - M4: `ProductionPublicSeamInstrumentationTest.kt` の未使用import `RowManifestCodec` を削除。ただし同ファイルの未使用import `org.junit.Assert.assertFalse`（`8683542052` で追加され、bodyでの使用なしをgrepで再確認）は残存 — 軽微指摘M5として継続扱い（非blocking、spotless/check-style通過済み）。

runtime書き込み経路とmigration対象: 増分はproductionコードに一切触れない（test fixture + CI list + import除去）。F1以降の書き込み経路契約（transaction内・write前評価、recovery lifecycle、schema/format不変）は第2回監査どおり。

## Criteria check

AC-1〜AC-7およびAC-8/AC-9の本体適合は第1回・第2回監査で確認済みで、本増分（test-only + CI）はそれらの層を変更しない。本監査での更新点は以下。

**AC-8 — 適合（条件なし）。** F1修正（intended state評価）は第2回監査で確認済み。第2回で課した条件C2（recovery seam testのambient pref依存）は解消: fixture setupで `allowWidgetOverlap=true` をpinしfinallyで復元（`OverlapAcceptanceGateSeamInstrumentationTest.kt:196-206, 266-269`）。実装側は `pm clear` 直後（ambient false）のエミュレータで seam + contract 両classの3 tests OKを報告、本監査はコードレビューでpinの位置（fixture reload前）と復元（finally）を確認した。条件C3（CI lane未組み込み）も解消: 両classがproduction-seam lane filterに追加され、head `58da306c07` のCI run 33442674258で当該laneがpass。

**AC-9 — 適合（条件なし）。** contract testは第2回監査で実loader QSB分岐を通過する有効な等価検証と確認済み。C3解消によりCIで実行されるようになり、ADR-0010 §4のdrift検知機構として機能する状態になった。

**CI merge gate（AGENTS高リスク要件）— 充足。** head `58da306c07` のpull_request run 33442674258がcompleted/success、`final-status` job pass（https://github.com/nunu1733/NunuLauncher/actions/runs/33442674258/job/99657109373 ）。第2回監査でFAILUREだったapi35 lane（`productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites`）とissue99 lane（`appRowMeetsMinimumFortyEightDpTouchTarget`）は本runでいずれもpassし、flaky疑いの評価が裏付けられた（同一testの再現失敗なし）。

## Executed test surface

監査sessionで実際に実行したcommandと結果（head `58da306c07`、JDK 21 / Gradle 9.3.0）:

- `git rev-parse HEAD` → `58da306c076874d8956da2754b4f1cd641322df8`（PR headと一致）。`git status --porcelain` → 空。`git merge-base --is-ancestor 8683542052 HEAD` → 成功。`git diff 8683542052..HEAD --stat` → 4 files（ci.yml / seam test / ProductionPublicSeam import / 本記録）。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL**
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL**。XML集計（67 suites）: **800 tests / 0 failures / 0 errors / 0 skipped**（results mtime 2026-09-01 06:44、本監査実行時刻直後で再実行済みを確認）
- `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` → **BUILD SUCCESSFUL**（変更されたseam testのcompile確認）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → **BUILD SUCCESSFUL**
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo 'nunu1733/NunuLauncher' --pr-number 186 --head-sha '58da306c076874d8956da2754b4f1cd641322df8'`（本記録更新後・push前）→ 形式・criteria・lineage・CI evidence checkすべて **PASS**（CI run 33442674258がhead一致・pull_request・final-status green・source jobs実行済みとして検証された）。push後はdocs-only headでgateが再実行される（C4）。

新規instrumentation testの実行結果: 実装agentが `pm clear` 直後（ambient `allowWidgetOverlap=false`）のエミュレータで `OverlapAcceptanceGateSeamInstrumentationTest` + `LoaderCursorOverlapAcceptanceContractTest` → **OK (3 tests)** を報告（C2確定後の実機検証）。本監査では実機再実行は行わず、CI lane pass（下記）とcompile成功で確認に代えた。

CI（PR #186、head `58da306c07`、監査時点で完了）:

- merge-gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33442674258 = **completed / success**。`changes` / `validate-repo-contract` / `check-style` / `organizer-unit-tests` / `build-debug-apk` / instrumentation全lane（shared-writer、db-migration、api35、issue52、issue53、issue99、**issue155 production-seam lane = 新規2 class組み込み済み**）= SUCCESS、`final-status` = **pass**。
- high-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33442674294 = FAILURE（tree内記録が前head `8683542052` を指しており「changes after the audited Head SHA are not docs-only; re-audit against the new head」= 本更新の理由そのもの。docs-only commit push後の再実行でPASSする見込み — 本監査のローカルvalidator実行はPASS）。

## Findings

判定 **ACCEPT WITH CONDITIONS**。残存条件はC1〜C4のうちC4のみ。以下、対処確認と残課題を記載する。

### 条件の解消状況

- **C1（解消）**: 監査head `58da306c07` で `final-status` green（run 33442674258）。第2回監査時のapi35/issue99失敗は本runで再現せず、flaky判定が裏付けられた。
- **C2（解消）**: recovery seam testが `allowWidgetOverlap` をpin+復元。ambient false環境（`pm clear` 直後）での実機3 tests OKが報告され、コードレビューでもpin位置・復元・gate注入（false維持）の整合を確認。
- **C3（解消）**: 新規2 classがproduction-seam lane filterに追加され、CIで実行されたlaneがpass。
- **C4（残存・手順）**: 本記録のdocs-only commit pushと、そのheadでの `high-risk-evidence` 再実行PASS確認。これ以外の残存条件はないため、**C4充足時点でmerge可能**。

### 軽微（非blocking）

- **M5**: `ProductionPublicSeamInstrumentationTest.kt` の未使用import `org.junit.Assert.assertFalse` が残存（M4はRowManifestCodecのみ除去）。次のdocs/test作業時に除去推奨。
- **M6**: production-seam laneのpassはclass filterの性質上、新規2 classがskipでなく実際に走ったことをreport artifactレベルでは示さない（`assumeTrue` 前提はCI emulator profileで成立しているが、vacuous green排除の最終確認はlane report artifactで可能）。次回以降の監査・作業時の任意確認。
- 第1回からの軽微指摘（composer/adapterのtolerance default `{ true }`、RESERVED_REGION precedenceのLOCKED表示差、新code固有retry testの非追加）は変更なし。非blockingとして維持。

### 判定根拠

ACCEPT WITH CONDITIONS: 初回REJECTの中心（F1: recovery targetの受容再評価欠落）は第2回で解消を確認し、第2回条件（C1 CI green、C2 test確定化、C3 CI組み込み）はすべて本headで解消を独立確認した。AC-1〜AC-9は適合、AGENTS高リスク要件のうちCI merge gate（同一commitでの `final-status` 成功）は充足、監査記録要件は本記録のdocs-only push（C4）で充足する。C4はgate設計どおりの手順的残課題であり、実質的な再監査事由（非docs変更）は残っていない。
