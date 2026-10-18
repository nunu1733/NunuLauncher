# #607 onboarding → hub 導線 — 実機（physical device）evidence

- Issue: #607 / PR: #610
- 採取日: 2026-10-18
- Build: `Lawnchair.16.Dev.(cec9ec0).github.debug.apk`（**PR head相当** `cec9ec0b50`。implementation commit `4c4e308e8f` とコード同一 — headとの差分はdocs-onlyのaudit記録commitのみ）。`app.lawnchair.debug`
- 端末: **Pixel 9a**（`56231JEBF08674`、tegu、1080x2424、Android 16 / API 37）。[#452の実機検証](./452-organizer-home-entry-device-evidence.md)と同一端末。
- locale: **ja-JP**（端末既定。ja copyの実機確認を兼ねる）
- 端末状態の配慮: 既定homeはNova Launcherのまま変更せず、`am start` でLawnchairを起動して撮影。AC-2の配置ロックは撮影内で **ロック→解除して元の状態へ復元**（net-zero）。runは **適用せず選択面で中断**（D-13のzero-write破棄）。適用操作・workspace書込みは一切行っていない。撮影後も既定homeはNovaのまま。
- onboarding状態の構成（正直な記録）: `app.lawnchair.debug` を **uninstall → install** し、`firstInstallTime == lastUpdateTime` のFRESH_INSTALL provenanceを作成した。install直後、端末のbackup-restore経路で `launcher.organization_proposal_restore_seen=true` が自動設定されprovenanceがRESTORE判定になったため、debug QA installに対し `run-as` で **当該フラグ1件のみ** falseへ補正してからコールドスタートした（outcome・restore pending等の他の状態は未変更。この補正はevidence構築手順であってアプリの挙動ではない。本フラグ自動設定はdebug build再install時の端末backup挙動であり、フォローアップ候補としてIssue側へも記録済み）。

## spec 607 Verification節の実機対象（AC-1 / AC-2 / AC-4）

| 対象 | 手順 | 証跡 |
|---|---|---|
| AC-1（初回→hub表示） | コールドスタート → 提案表示 → 「整理案を確認」tap | `d01-ac1-onboarding-proposal.png`（提案「ホーム画面を整理しますか？」。後で / スキップ / 整理案を確認の3アクション）→ `d02-ac1-onboarding-hub.png`（**run面ではなく既存Organizer hub（整理ハブ）** が表示される。整理を開始CTA・編集画面・オーガナイザー診断・整理の材料5行が設定経由と同一構成で存在） |
| AC-2（hub材料の確認・変更） | hub「配置ロック」行 → Googleフォルダをロック → 解除で復元 | `d03-ac2-placement-locks.png`（配置ロック面「確認が必要な配置はありません。」+ すべての配置リスト）→ `d04-ac2-lock-changed.png`（「配置をロックしました。」+ Google=**ロック中**）→ `d05-ac2-lock-reverted.png`（解除後、Google=**ロック解除済み**へ復帰。net-zero） |
| AC-4（hub開始→run面） | hub「整理を開始」CTA → T-07前置き → 開始行tap | `d06-ac4-t07-preamble.png`（**標準T-07前置き**「適用前に整理案を確認できます。…」+ 整理を開始行。この時点でadmissionは発生しない）→ `d07-ac4-run-surface.png`（開始行tap後、**admitted runの選択面**「整理するアプリを選択 / 0件を選択中」を表示） |

## `REVIEWED` 記録タイミングの実機確認

- `d07` の選択面到達（= onboarding起点 `ONBOARDING_PROPOSAL` triggerのadmissionが実際に `Started`）の直後、`run-as app.lawnchair.debug` でのprefs読み取り:

  ```
  shared_prefs/com.android.launcher3.prefs.xml:
  <string name="launcher.organization_proposal_outcome">REVIEWED</string>
  ```

- hub表示（`d02`）・T-07前置き表示（`d06`）の段階では `REVIEWED` を記録しない契約（AC-3/AC-4の記録タイミング）は instrumentation（`OnboardingOrganizationProposalInstrumentationTest` の `assertDeferredWithoutRun` oracle群、20/20 green）で決定的に固定しており、実機では上記の画面記録と `Started` 後のprefs読み取りの組み合わせで確認できる。
- 選択面でBack（D-13の中断 = 確認なしでrunを停止しhubへ戻る、zero-write）→ hubへ復帰（`d08-run-discard-back-to-hub.png`）。さらにhub Back → debug Lawnchairのworkspaceへ復帰（`d09-back-to-launcher.png`）。outcomeは `REVIEWED` のまま追加変化なし。
- **適用操作は行っていない**（runは選択面で中断。preview確認・適用確認の確認面には到達していない。spec 607の実機対象3項目〔AC-1/AC-2/AC-4〕はすべてこの時点で充足）。

## 補足

- AI相談toggle既定OFFのため方法選択面は出ていない（#443既定導線どおり）。
- 選択面（`d07`）には端末にインストール済みのアプリ一覧が表示される（[#452の `d04b-selection-face.png`](./452-organizer-home-entry-device-evidence.md) と同様の記録形式）。
- usage accessのJIT promptは本フローでは出なかった（端末でusage権限が既に決定済みのため。#452実機時と異なりpromptは出ず、整理経路の固定手順には含まれない）。
- 実機確認の実施はowner指示（「接続済み実機で進めて」、2026-10-18）による。merge gateの状態はIssue #607のpacketへ記録する。
