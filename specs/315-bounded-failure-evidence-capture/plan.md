# Plan: failure-time evidence capture の bounded 化

> Issue: #315
> Spec: [spec.md](./spec.md)
> Status: implemented（PR #316 = merge commit `2af57b3ad8b905a3f81147bf0f8aa4b3e90fbfc1`、
> head `a50301b76567f38476f049038ce051e139851a7d`。検証記録は PR #316 の packet comments 参照）

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
  - 出力 bounded 化（PR #316 review P2 対応、producer 側）: coreutils path は
    `timeout ... "$@" 2>&1 | head -c "$MAX_BYTES"` でパイプ消費、fallback path は
    FIFO + `head -c` 消費者で、いずれも上限到達で producer が SIGPIPE（status 141）で
    停止し、一時ファイルは cap 超えに成長しない。`output_truncated` を file/manifest/log に記録。
    logcat は `-t "$CAPTURE_LOGCAT_LINES"` で直近行数に制限。
    注: 内部 budget は capture ループの上限であり、command 終了後の file 追記は cap 済み
    （≤2MiB、ms 級）。wall-clock の最终硬上限は workflow step の `timeout --kill-after=30 300`。
- `.github/workflows/ci.yml`
  - Issue #52/#53 の capture step の `run` を `timeout --kill-after=30 300` でラップ
    （script 内部 budget 150s に対する外側の绝对上限。GHA は step timeout を持たないため）。
  - `if: failure()` / `continue-on-error: true` / upload step は変更しない。
- `tools/ci/test_capture_emulator_failure_evidence.sh`
  - fake adb に `HANG_PATTERNS`（マッチで sleep）と `BIG_PATTERNS`（マッチで約200MBストリーム）を追加。
  - シナリオ:
    1. 既存: 全 command 応答 + 1 件 exit 7（回帰維持）
    2. hang 1 件: 個別 timeout で打ち切り、`timed_out=true` 記録、後続実行、wall ≤ 20s（timeout=3s）
    3. hang 複数 + 短い budget(6s): 途中スキップ記録、partial artifact、exit 0、wall ≤ 15s
    4. 巨大出力 1 件: producer 側 cap で `output_truncated=true`、file ≤ cap+overhead、
       evidence dir ≤ 2MiB、後続実行、wall ≤ 20s
- 検証 command: `bash tools/ci/test_capture_emulator_failure_evidence.sh`（ローカル: `timeout` 無し
  = fallback path、CI: `validate-repo-contract` job = timeout path の両方）。

## Migration / rollback

- 対象は CI tool のみ。DB・layout・runtime 影響なし。rollback は revert commit。

## リスク

- budget/timeout 既定値が厳しすぎて partial evidence 化が進む可能性:
  全 20 command × 15s = 300s が最悪値だが、正常応答時は #313 と同程度（数秒）で終わる。
  実測 run で調整（AC 上、上限内なら timed_out 自体が証拠なので許容）。
