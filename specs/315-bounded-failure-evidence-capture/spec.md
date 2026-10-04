---
issue: "#315"
status: implemented
requirements: [AC-315-01, AC-315-02, AC-315-03, AC-315-04, AC-315-05, AC-315-06]
updated: 2026-09-14
---

# failure-time emulator evidence capture は system-service 無応答時も bounded に終了し、partial artifact を残す

## Problem

#304 の観測インフラ（PR #313 で導入した `tools/ci/capture-emulator-failure-evidence.sh`）が、
導入後の実失敗（run 34841787277 / Issue #52 lane）で診断処理自体が長時間停止し、
artifact upload に到達しなかった。capture 対象の `dumpsys` は stall が疑われる
system service への問い合わせそのもののため、失敗条件と診断処理が同じ無応答に巻き込まれる。
運用者（#304 root cause 調査者）は、失敗後の診断だけで job timeout（50分）を消費され、
元の test failure の証拠すら失われる状況に困っている。

## Outcome

failure-time diagnostics が「失敗原因と独立に必ず終了する bounded observer」になる。
個別 command が hang しても timeout 自体が証拠として artifact / job log に残り、
後続 capture と partial artifact の upload が常に成立する。#304 の H1/H1'/H2 判別に
必要な観測粒度（window / activity / HOME / ANR / logcat / input / SurfaceFlinger）は維持する。

## Scope

- capture helper の各 adb/dumpsys 呼び出しへの hard timeout と、timeout 発生時の記録
- script 全体の実行時間 budget と、budget 超過以降の command のスキップ記録
- 各 capture の開始・終了・elapsed・timed_out の job log / manifest への出力
- logcat 行数制限と 1 file あたり出力バイト上限を **producer 側** で適用する
  （無制限な一時ファイルを作らず、上限到達で dumper 自体を SIGPIPE で停止する）
- workflow の capture step 外側にも `timeout` で绝对上限を置く
  （内部 budget は capture ループの上限であり、step 外側 timeout が wall-clock の最终 backstop）
- fake-`adb` smoke test への hung command / 巨大出力 command の回帰契約の追加

## Non-goals

- Issue #52 の test failure 自体の原因修正
- #304 root cause の確定
- SystemUI ANR / NexusLauncher occluder の自動修復
- emulator 側の状態回復・再起動（capture は観測のみ）

## Domain language

- **bounded observer**: 観測対象の無応答に関係なく、既定時間以内に必ず終了し、
  未実施・打ち切りを証拠として残す診断処理。

## Behavior scenarios

### Scenario: 個別 command が hang する

Given emulater の system service が無応答で `dumpsys window windows` が返らない
When failure-time capture が実行される
Then その command は既定 timeout（既定 15 秒、環境変数で上書き可）で打ち切られる
And 該当 file と manifest に `timed_out=true` と終了 status が記録される
And job log に `capture start:` / `capture end: ... elapsed=... timed_out=...` が出る
And 後続の capture は通常どおり実行される

### Scenario: 全体の budget を超える

Given hang する command が複数あり、個別 timeout の合計が script budget（既定 150 秒）を超える
When capture が budget に到達する
Then 未実行の command は `skipped_budget_exhausted=true` として manifest / 各 file に記録される
And script は exit 0 で終了し、生成済み file と README・manifest が upload 可能な状態に残る

### Scenario: 正常な失敗時capture（既存契約の維持）

Given emulator が応答するが test が失敗している
When capture step（`if: failure()` / `continue-on-error: true`）が走る
Then 全 snapshot が #313 と同じ観測粒度で取得される
And capture の成否が元の test failure の semantics を上書きしない

### Scenario: 巨大出力

Given `dumpsys dropbox` や無限出力する service が巨大なストリームを返す
When capture する
Then 出力は producer 側のバイト上限で打ち切られ、一時ファイルは上限超えに成長しない
And 打ち切られた command は `output_truncated=true` として file・manifest・job log に記録される
And 後続の capture は実行される
And logcat は直近行数に制限される

## Data and state

- artifact directory 内の file は best-effort snapshot。永続化・DB 影響なし。
- `capture-manifest.tsv` に command 名・status・elapsed・timed_out・output_truncated・outcome を集約する。
- migration / layout とは無関係。

## Permissions, privacy, and security

- None。追加 permission・外部送信なし。CI runner 上の emulator 出力のみを artifact 化する。

## Accessibility and localization

- 該当なし（CI ツール）。

## Acceptance criteria

- [ ] AC-315-01: 各 capture command は hard timeout を持ち、system service 無応答でも無期限待ちしない
- [ ] AC-315-02: script 全体に明示的な budget 上限があり、到達後も partial files + manifest + README が upload 可能な状態で残る
- [ ] AC-315-03: timeout / skip が artifact（file・manifest）と job log の両方に診断情報として残る
- [ ] AC-315-04: hung command があっても後続 capture が実行され、script は exit 0 で所定時間内に終了する
- [ ] AC-315-05: fake-`adb` の hang 回帰 test が smoke test に追加され、CI で pass する
- [ ] AC-315-06: Issue #52/#53 lane の既存 failure semantics（`if: failure()` / `continue-on-error`）と観測粒度を維持する

## Test oracle

| AC | Evidence |
|---|---|
| AC-315-01..04 | `tools/ci/test_capture_emulator_failure_evidence.sh` の hang / budget / 巨大出力シナリオ（CI job `validate-repo-contract`）。wall-clock oracle は設定値に比例する上限（timeout=3s→≤20s、budget=6s→≤15s）と保存バイト上限（cap+header 以内、dir≤2MiB）を照合 |
| AC-315-05 | 同上 smoke test が `ci.yml` の gate で実行され pass すること |
| AC-315-06 | smoke test の既存成功シナリオ維持 + ci.yml diff レビュー |

## Open questions

- なし（timeout 既定値 15s / budget 150s は Issue の目安 10–15s / 2–3 分の範囲内で、実測失敗 run の挙動に基づく調整は環境変数で可能）。

## Change history

- 2026-09-14: Draft created for #315（Issue 本文の要件・AC を正本化）。
- 2026-09-14: PR #316 review P2 対応。出力上限を保存後 truncation から producer 側 cap（FIFO/パイプ + SIGPIPE）へ変更し、巨大出力シナリオと比例 wall-clock oracle を spec に反映。
- 2026-09-14: implemented。PR #316 merge commit `2af57b3ad8b905a3f81147bf0f8aa4b3e90fbfc1`（head `a50301b76567f38476f049038ce051e139851a7d`、全15 checks / final-status green、review P2/P3/merge precondition closure は PR #316 の packet comments 参照）。
