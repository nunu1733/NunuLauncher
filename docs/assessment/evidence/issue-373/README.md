# Issue #373 device evidence — T-18 failure face (手段別失敗投影)

Captured on emulator (API 36, issue142_api36 AVD, 1080×2400) via
`ExchangeImportSurfaceInstrumentationTest#captureFailureEvidence*`
(`docs/assessment/evidence/issue-373/`). The fixture imports a marked reply
bound to no active session, which settles as `EXPORT_MISMATCH` — the remedy
projection answers 「依頼を作り直す」 (RECREATE_REQUEST).

| File | Content |
|---|---|
| `issue373-failure-{ja,default}-{light,dark}.png` | T-18 failure face, primary face only: remedy copy + 「依頼を作り直す」 action + 面レベル手段「中断する」+ 「詳細を表示」 (closed). No typed vocabulary on the primary face (D-11). |
| `issue373-failure-detail-{ja,default}-{light,dark}.png` | Same face with the detail expansion opened: 「種別: EXPORT_MISMATCH」 (typed classification name) + typed cause copy (inherited `exchange_failure_export_mismatch`) + recognition facts (形式・version・エントリ数) + raw text, bounded with internal scroll. |
| `issue373-failure-ja-light-200font.png` / `issue373-failure-detail-ja-light-200font.png` | 200% font scale (ja, light): the remedy copy and the primary action stay on screen; the detail expansion stays bounded with internal scroll (IM-AC-09 reflow). |

Note: the capture harness composes the exchange flow without the host's
diagnostics route callback, so 「診断を開く」 is intentionally absent from these
shots (the row hides when no route exists — the same safe absence the
instrumentation asserts). The diagnostics-face auxiliary row (「直近の取り込み失敗」)
is covered by `OrganizerDiagnosticsRouteInstrumentationTest#recentImportFailureRowMirrorsTheTransientHolder`.
TalkBackの実AT walkthroughはspec 332 AC-8と同一の合成入力制約により未取得
(Compose semantics assertion + 手動確認を第一証拠とし、plan.mdの未取得範囲記載どおり)。
