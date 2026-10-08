---
issue: "#554"
status: accepted
updated: 2026-10-08
---

# API 37 Recents callback互換bridge

Risk tier: H（specのprovider境界判定）。DB/model、高リスクpath変更なし。

## Revision / base

仕様: [spec.md](./spec.md)。実装baseはPR #550 head
`a4fa7310e9ae576e58af72d8d87e204d44423cbd`。依存#545のcompat7を利用する。
仕様/planはmain docs PR、実装は専用stack branch。未merge他branchからcodeをコピーしない。

## Owned paths / seam

- `quickstep/src/com/android/quickstep/SystemUiProxy.kt`: 既存RecentsAnimationListenerStub.onTransactにAPI>=37＋transaction3のdecoderを追加。minimizedHomeBoundsはnullで既存listenerへ渡す。
- `quickstep/src/com/android/quickstep/HomeVisibilityState.kt`: 登録中のHome listener Stub.onTransactにAPI>=37＋transaction1の3-boolean decoderを追加。既存MAIN配送へ渡す。
- `tools/diagnostics/verify_recents_provider.py`: 実provider構成のADB oracle。全logを恒久commitせず、限定signature/transition/windowとsynthetic emulator screenshotをassessmentへ保存する。
- `docs/assessment/554-api37-recents-evidence/`: SHA/APK対応、pre-fix signature、AOSP/DEX比較、API37/API36 runtimeと未確認範囲。
- 新規DB/interface/adapter、prebuilt JAR、vendored AIDL全体更新なし。

## Order / migration / rollback

1. spec/plan reviewを現在revisionへ固定し、受入commitとWorker start packetをIssueへ記録する。
2. 先にruntime oracleを保存して旧release APKに対しFAILを確認する。
3. 2つのdecoderを適用。Issue番号とAOSP差分理由を近傍commentに残す。
4. spotlessCheck、debug/release build。一時maxSdk37 candidateを独立commitでbuildし、API37 release provider検証する。
5. candidateを明示revertしmaxSdk36を維持。API36 providerはfinal source/maxSdk36 APKで回帰確認。
6. evidenceと現在headのCI、別Review sessionの全diff確認をpacketへ記録。stackをpushする。

migrationなし。rollbackは2decoder commitのrevertで旧schemaへ戻る。runtime candidateは必ずrevert。
provider emulator配置手順は#545/#524 evidence READMEを利用し、試験のための権限緩和をsourceへ追加しない。

## Verification / constraints

spec AC-1..5のruntime matrixとrepo既存commandを使用する。新CI laneなし。
API36回帰、API37 senderの実binder/parcel dispatch、UI/task foreground復帰を証拠にする。
不正payload/Nothing OS実機は未実施としてreview packetへ残す。
upstream patch surfaceは16 anchor対象の既存baseline不整合を含め結果をreportする。
cutover前の実装PRにはRefs #554を使い、mainline統合後の最終PRのみclosing keywordを使用する。

- 2026-10-08: 別Review sessionの[Approve](https://github.com/nunu1733/NunuLauncher/pull/556)（head `d56bb6026515e696f89cd9be51a67d6b312f8b96`）を確認。利用者の#554対応指示の範囲で受入。cutover保留は維持。
