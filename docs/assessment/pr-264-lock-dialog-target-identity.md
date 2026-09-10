# High-risk audit: PR #264 lock dialog target identity

> Status: accepted
> Audit date: 2026-09-09

- Auditor: 独立 session (ZCode general-purpose subagent。実装 session とは別の監査専用 session であり、実装・commit は行っていない)
- PR: https://github.com/nunu1733/NunuLauncher/pull/264
- Head SHA: a29006e3f5767cb8f18a474a3242b3c569a96fb9
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34343658504
- Criteria: specs/211-lock-dialog-target-identity/spec.md — AC-1, AC-2, AC-3, AC-4, AC-5

本PRは高リスク path を含まない (risk label なし) ため high-risk gate の対象外であるが、
solo保守の独立監査規約に従い本記録を作成した。監査時点で PR は OPEN、
base = main (`b25f20ca7c31ad384fbe8f8b696e87118f8fbe8c`)、head = `a29006e3f5767cb8f18a474a3242b3c569a96fb9`
(`git fetch origin issue-211-lock-dialog-target-identity` 後の FETCH_HEAD と一致)。

## Scope

diff 対象 (`git diff --stat b25f20ca..a29006e3f`): 14 files changed, 775 insertions(+), 2 deletions(-)。

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` (+20/-2 相当): `LockChangeDialog` へ `profileLabel` 引数追加、`Available` 経路の `text` slot を `Column` 化し対象行 2 node を追加。
- `lawnchair/res/values/strings.xml` / `values-ja/strings.xml`: `organizer_lock_dialog_target_title` (`Target: %1$s` / `対象: %1$s`) を 1 行ずつ追加。
- `tests/organizer-instrumentation/app/lawnchair/organizer/locks/OrganizerLockScreenTest.kt`: fixture `sameTitleState()` と新規 test 3 件 (+ PixelCopy capture helper)。既存 4 test への変更行はゼロ (diff の削除行は import 追加のみで既存 test 本体の削除なし)。
- `specs/211-lock-dialog-target-identity/spec.md` / `plan.md` (新規)、`specs/38-lock-authoring-unknown-review/spec.md` (docs-only 追記)。
- `docs/evidence/issue-211/` (README + PNG 6枚)。

非対象の確認 (AC-5): `git diff --stat <base>..<head> -- lawnchair/src/app/lawnchair/organizer/locks/ lawnchair/src/app/lawnchair/organizer/application/` は空出力。`organizer/locks/**` と `organizer/application/**` への変更はゼロであり、`LockStateEntry`、`LockAuthoringModule`、planner、Launcher DB、recovery への変更がない。migration、permission、dependency、通信の追加なし。runtime 書き込み経路・migration対象: なし (表示のみの変更)。

## Criteria check

- **AC-1 (対象行の表示、state/scope/effect 維持)** — PASS。
  `PlacementLockPreferences.kt:352-367` (head 時点): `LockChangeDialog` の `Available` 経路 `text` slot が `Column` となり、(1) `stringResource(R.string.organizer_lock_dialog_target_title, entry.title.textOrFallback(entry))` の title 導入行、(2) `Text(placementDescription(entry, profileLabel))` の配置概要行が本文の先頭に追加されている。行 (`LockRow`, `PlacementLockPreferences.kt:278,282`) と同一の private 合成関数 `textOrFallback` (`:459`) と `placementDescription` (`:411`) を直接呼んでおり、行とダイアログで合成経路が分岐しない構造 (spec D1) を満たす。既存本文 (state/scope/effect、UNKNOWN は review intro 付き) は第 3 node として現行文字列のまま (`:366`)。`profileLabel` は呼び出し側から `profileLabels[entry.profile.value]` で渡す (`:236`)。evidence: en-02/en-03/ja-01/ja-02 screenshot に `Target: Google` / `対象: Google` と行と同一の配置概要が表示されることを目視確認。
- **AC-2 (同名行の区別、count-based assert)** — PASS。
  `OrganizerLockScreenTest.kt` の `dialogNamesTheTappedRowAmongSameTitleRows` が同名 fixture `sameTitleState()` (title `Google` の 2 行 + protected 行、全行 description 一意) で、tap 行の description (`Home screen 1`) の node 数が dialog 表示中に 2 (背景行 + dialog 対象行) になること、対抗行の description (`Inside a folder, position 1`) が 1 のまま (dialog に現れない) ことを count assert で検証する。tap を入れ替えたとき count が入れ替わること (差し替え確認) と、protected 行の double 合成値がそのまま dialog へ出ることも検証する。順序依存なしの count 比較で tap 行と対抗行を区別できており、spec の required evidence (a) 行 description == dialog 内 description、(b) 他方の description が dialog に現れない、を満たす。
- **AC-3 (書込みなし契約の維持)** — PASS。
  既存 4 test (`unknownReviewResolvesOnlyThroughConfirmedDialog`、`busyFailureRendersLocalizedMessage`、`folderLockDialogExplainsChildCoverageBeforeMutation`、`unknownBannerAndTextStateLabelsAreRendered`) への diff 変更はゼロ (test file の削除行は存在しないことを `git diff | grep '^-'` で確認、import 追加のみ)。新規 test 内でも `assertEquals(0, writer.writes.size)` を dialog open 前・dialog 表示後・入替後の各段階で assert し、書込みなし契約を二重に担保する。CI `organizer-unit-tests` および lokalise 不要の JVM gate が head 上で成功。
- **AC-4 (2 Text node 分離 + en/ja 同時追加)** — PASS。
  `dialogTargetRowsRenderAsIndependentTextNodes` が `Target: Google` と配置概要の exact match (`onNodeWithText` 既定 = 完全一致) を個別に成立させ、1 連結文字列でなく独立 node であることを検証する。strings は `values/strings.xml:925` と `values-ja/strings.xml:36` に同 key `organizer_lock_dialog_target_title` を同時追加 (en `Target: %1$s`、ja `対象: %1$s`) し、`PlacementLockPreferences.kt:360` から使用していることを確認。ja 証憑 (ja-01/ja-02/ja-03) の文言も key と一致。
- **AC-5 (locks module / domain / DB 無変更)** — PASS。
  Scope 節のとおり `organizer/locks/**` (production) と `organizer/application/**` への変更ゼロ。`LockStateEntry`・`LockAuthoringModule` への言及箇所は diff 内に存在しない。既存 JVM gate (`organizer-unit-tests` job) が head で success。

文言確認: `organizer_lock_dialog_target_title` は en/ja 両方に存在し、`PlacementLockPreferences` から使用されている (上記 AC-4)。ja 語彙は glossary どおり「対象」「フォルダ内の位置」「ホーム画面」を使用 (ja-01/ja-02 screenshot で目視確認)。

evidence 確認: `docs/evidence/issue-211/` に README + PNG 6枚が存在し、監査者が全画像を Read tool で開いて目視確認した。

- `en-01-rows.png`: 同名 `Google` 2 行 (Home screen 1 / Inside a folder, position 1) + protected 行を含む管理画面。UX review F-05 の曖昧性を再現する fixture と一致。
- `en-02-dialog-home-screen-row.png`: `Target: Google` + `Home screen 1` が `Current state: Unlocked` より前に表示される。tap 行と同一の識別情報。
- `en-03-dialog-folder-row.png`: 同一 dialog が `Target: Google` + `Inside a folder, position 1` に差し替わり、dialog 文言だけで対象を区別できる。
- `ja-01-dialog-home-screen-row.png`: `対象: Google` / `ホーム画面 1` / `現在の状態: ロック解除済み`。
- `ja-02-dialog-folder-row.png`: `対象: Google` / `フォルダ内の位置 1`。
- `ja-03-dialog-200-percent-font-scale.png`: 200% font scale で対象 block が wrap し、truncation や崩れなし (spec の a11y 目視確認要件を満たす)。

なお README の capture 条件 (head `114f496fcc` 時点の capture) は最終 head `a29006e3f` より前であるが、capture 対象の UI file・strings は `114f496fcc` 以降無変更であることを `git diff 114f496fcc..a29006e3f -- lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt lawnchair/res` が空であることで確認済み (実装 PR 本文にも記載どおり)。※この確認は監査者のローカルに `114f496fcc` が存在する前提の追試であり、同一 commit の存在は `git cat-file -t 114f496fcc` = commit で確認した。

## Executed test surface

監査者が対象 head `a29006e3f5767cb8f18a474a3242b3c569a96fb9` の checkout 上で実行:

- `git status` → 実験開始時・`spotlessCheck` 実行後ともに working tree clean (`nothing to commit, working tree clean`)。
- `./gradlew spotlessCheck` → `BUILD SUCCESSFUL in 1s` (5 actionable tasks, exit 0)。
- `gh pr checks 264 -R nunu1733/NunuLauncher` → 全 check pass (下記)。`final-status` は pending 期間 (~10分) を挟んで完了を確認。

CI run (workflow `CI`, run id 34343658504, headSha `a29006e3f5767cb8f18a474a3242b3c569a96fb9` = 監査対象 head と一致, conclusion = success):

- `final-status` → success (merge gate)。
- source job: `check-style` → success (1m5s)、`organizer-unit-tests` → success (5m6s)、`build-debug-apk` → success (4m1s)。skip なし。
- その他: `changes`, `validate-repo-contract`, `organizer-instrumentation-{api35,db-migration,issue155,issue52,issue53,issue99,shared-writer}-tests`, `high-risk-evidence` (別 run 34343658575) → すべて success。

connected instrumentation test (`OrganizerLockScreenTest` 7/7, en/ja/200% scale) は実装 session の local emulator evidence であり (PR 本文記載)、CI class filter 外のため監査では再実行していない。代わりに監査者は screenshot evidence 6枚の目視確認 (上記) と test code の突合で AC-1/AC-2/AC-4 の観測的根拠を確認した。

## Findings

確認された問題: none。

- AC-1〜AC-5 のいずれにも違反を認めなかった。diff は spec の Scope (D1〜D3) と plan revision 3 に正確に対応する。
- 残課題 (ブロッキングなし):
  - connected test / screenshot evidence は local emulator 起点であり、監査者による実機再実行はしていない (spec の test oracle が local emulator evidence を想定するため許容。solo保守の独立監査規約どおり screenshot の目視確認と CI gate で補完)。
  - evidence README の capture 時 head (`114f496fcc`) と最終 head (`a29006e3f`) の差分に UI/strings 変更がないことを監査者が確認済み (上記)。capture の再取得は不要と判断する。
  - long-press ポップアップ側 (`OrganizerLockShortcut`) への対象行追加は spec D3 により非対象であり、必要になった時点で別 Issue とする予定 (spec Non-goals に記載済み。追跡の必要な新規未決定事項は発生していない)。
