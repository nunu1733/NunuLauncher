---
issue: "#576"
status: accepted
requirements: []
updated: 2026-10-15
---

# Spec 576: Compose LazyList計測失敗の診断 (research)

> Risk tier: L (文書 + local実験のみ。恒久test実装の仮定なし)
> Phase 1はChatGPT review用。plan.mdなし (制御matrixは本書内、tier L)。
> 詳細な証拠は [discovery assessment](../../docs/assessment/issue-576-compose-measure-discovery.md) が正本であり、本書は重複する生provenance表を持たない。
> Acceptance: [exact-head review](https://github.com/nunu1733/NunuLauncher/issues/576#issuecomment-6185424111) と [owner-authorized operator decision](https://github.com/nunu1733/NunuLauncher/issues/576#issuecomment-6185465325)。承認対象内容は `5e66359f20afb95f04983b676953d7f9d857864b`。status/linkのみを追記し、実験契約は変更しない。

## 1. 背景と参照

- Issue #576 (親 Refs #516、起点 PR #575)。失敗分類はclass 6 unknownのまま。
- 正準owner: [既存connected test](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt) の `staleApplyAttemptExplainsOutcomeAndNextStep`。
- 振る舞い契約: [spec 210](../../specs/210-stale-apply-outcome/spec.md)。stale文言のassertは無改変。
- 本書承認前の禁止: Phase 2のdevice実行・test/source変更のみ。Phase 1の文書commit・push・Issue packet・review commentは許可される。

## 2. Scope / 非対象

- 対象: 原初証拠の保全確認、baseline/candidate制御比較の契約、分類更新可否の判定。
- 非対象: 製品修正、新規class/lane/filter/timeout/production seam、依存upgrade、新harness/Robolectric。
- 恒久test実装は本書の範囲外。承認されるのは文書とlocal実験の契約のみ。

## 3. シナリオと受入条件 (binary)

- AC-1 (原初証拠): discovery assessmentの識別子・stack行が原artifactと一致し、既存testをownerとして特定できる。
- AC-2 (制御比較): 既存methodを専用API 36 deviceでbaseline 5回・candidate 5回、同一環境・同一args・既存assertionのまま完遂し、各runのsource/result/logを揃える。個別失敗はstop-and-triageし、未完matrixはblocked/incompleteと記録する (AC-2達成とはしない)。
- AC-3 (分類と隣接契約): 原signatureの再現有無と支持された原因クラスを根拠付きで記録し、因果証拠がなければclass 6のままIssue openを明記する。staleの4文言assertion・既存CI分類を維持し、証拠PRは `Refs #576` のみとする。

## 4. 制御matrix (Phase 2契約、承認後のみ)

- baseline: 現行exact source、5回。candidate: 現行 + 最小LOCAL overlayのみ、5回。同一環境・同一args。
- overlayは対象runner fixtureだけに実際の `HandlerRunPublicationThread()` を追加する。worker/instrumentation machine呼出はoff-mainのまま。`Handler.run` はblocking dispatchでありasync化を仮定しない。
- 反復は同一methodの個別起動で固定 (各5回)。XML/logをrun毎に保全する。
- 個別失敗はbreak/triage。rerun-green主張は禁止。
- 実行command (placeholder。個人pathをrepo文書に残さない):

```bash
ANDROID_SERIAL=<owned-serial> JAVA_HOME=<jdk21-home> ANDROID_HOME=<android-sdk> \
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
'-Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest#staleApplyAttemptExplainsOutcomeAndNextStep'
```

- 記録: base/test/prodのimmutable blob、overlay hash、APK hash、device/API/ABI/image、toolchain差分。
- 制約: 原初CI arch x86_64に対しlocalはarm64の可能性があり、差分として記録する。原初vs現行は制御差分ではない。専用resourceは使用後にteardown必須。

## 5. test-audit回答

1. 保護契約: #210 stale文言の表示契約。計測失敗の分類だけを問う。
2. 回帰: 新規testなし。既存正準testがowner。
3. 正準owner: 上記既存test。重複ownerを作らない。
4. 上位層risk: Compose計測と実framework境界の失敗のみ。下位層へ移せない。
5. seam cost: 恒久seam追加なし。candidate overlayはLOCAL・一時的で承認後のみ。
6. 影響面/CI: 既存instrumentation laneは無変更。one-off実験はDiagnosticであり、owner laneの再分類ではない。
7. CI分類: Diagnostic (恒久gate化なし)。
8. 費用対効果: 5+5回の実機費用は分類証拠のための支出であり、証拠が出ることの事前保証ではない。出なければclass 6のまま報告する。

## 6. Prior art

省略（理由: 階層Lの既存owner・publication seamを使う調査。新規seamやtest strategyを追加しない）。

## 7. 分類の限界と承認

- 決定論的crashや根本原因を約束しない。非再現ならclass 6のままでIssue終了条件は未達。
- publication差分だけでは原初原因の証明にならない。仮説には因果証拠を要求する。
- 研究成果とは「未支持仮説の報告」であり、no-op closureではない。
- 自己承認しない。承認はexact-head reviewと別途記録されるowner decisionによる。
