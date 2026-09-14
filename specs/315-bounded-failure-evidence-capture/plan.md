# Plan: failure-time evidence capture の bounded 化

> Issue: #315
> Spec: [spec.md](./spec.md)
> Status: proposed（実装 PR #<TBD> と同時）

## 変更 module / seam

- `tools/ci/capture-emulator-failure-evidence.sh`（単一 seam: failure-time capture helper）
  - `capture_serial` / `capture_global` を共通の bounded runner（`run_bounded`）経由に集約。
  - 追加設定（env 上書き可）:
    - `CAPTURE_COMMAND_TIMEOUT_SECONDS`（既定 15）
    - `CAPTURE_TOTAL_BUDGET_SECONDS`（既定 150）
    - `CAPTURE_MAX_BYTES_PER_FILE`（既定 2097152）
    - `CAPTURE_LOGCAT_LINES`（既定 5000）
  - timeout 実装: `timeout -k 2`（CI ubuntu に存在）。無い環境（macOS ローカル）は
    background + watchdog（KILL + flag file）でフォールバックし、どちらの path でも
    `timed_out` を判定する。
  - budget: 開始時に deadline を算出。各 command の effective timeout は
    `min(command timeout, 残り budget)`。残り 0 以下なら以降は実行せず
    `skipped_budget_exhausted=true` を file/manifest に記録して最終処理へ進む。
  - job log: `capture start: <name>` / `capture end: <name> status=<s> elapsed=<n> timed_out=<b> bytes=<n>`。
  - artifact: 既存の各 `<name>.txt` + 新規 `capture-manifest.tsv`。exit code は常に 0 を維持。
  - 出力 cap: 一時 file へ受けてから byte cap を適用し、`[truncated ...]` を追記。
    logcat は `-t "$CAPTURE_LOGCAT_LINES"` で直近行数に制限。
- `.github/workflows/ci.yml`
  - Issue #52/#53 の capture step の `run` を `timeout --kill-after=30 300` でラップ
    （script 内部 budget 150s に対する外側の绝对上限。GHA は step timeout を持たないため）。
  - `if: failure()` / `continue-on-error: true` / upload step は変更しない。
- `tools/ci/test_capture_emulator_failure_evidence.sh`
  - fake adb に `HANG_PATTERNS`（マッチする command で sleep）を追加。
  - シナリオ追加:
    1. 既存: 全 command 応答 + 1 件 exit 7（回帰維持）
    2. hang 1 件: 個別 timeout で打ち切り、`timed_out=true` 記録、後続実行、wall clock 上限
    3. hang 複数 + 短い budget: 途中スキップ（`skipped_budget_exhausted=true`）、
       README/manifest あり、exit 0、総時間 bounded
- 検証 command: `bash tools/ci/test_capture_emulator_failure_evidence.sh`（ローカル: `timeout` 無し
  = fallback path、CI: `validate-repo-contract` job = timeout path の両方）。

## Migration / rollback

- 対象は CI tool のみ。DB・layout・runtime 影響なし。rollback は revert commit。

## リスク

- budget/timeout 既定値が厳しすぎて partial evidence 化が進む可能性:
  全 20 command × 15s = 300s が最悪値だが、正常応答時は #313 と同程度（数秒）で終わる。
  実測 run で調整（AC 上、上限内なら timed_out 自体が証拠なので許容）。
