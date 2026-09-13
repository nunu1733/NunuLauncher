---
issue: "#304"
status: draft
requirements:
  - RC-AC-01
  - RC-AC-02
  - RC-AC-03
  - RC-AC-04
  - RC-AC-05
risk: []
updated: 2026-09-13
---

# api36 UI lane の burst 発生 boot で window focus を保持する occluder が gate 証拠から特定され、root cause 結論または残存リスク受容が記録される

## Problem

api36 UI lane（`organizer-instrumentation-issue53-tests`、`organizer-instrumentation-issue52-tests`）
で、実入力注入テストが 1 run 内に連続失敗（burst）する環境フレイクが発生している
（[Issue #300](https://github.com/nunu1733/NunuLauncher/issues/300) 記録の
[run 34677444335](https://github.com/nunu1733/NunuLauncher/actions/runs/34677444335) 等）。
共通シグネチャは `launcherWindowFocus=false` — launcher window が焦点を得られないまま
テストが進行し、注入が宛先 window へ届かない。同一 head の api35 lane は green であり、
フレイクは head・diff 非依存、rerun（別 boot）で green になる per-boot の環境状態である。

2026-09-12 の Spec/Plan review で、#300 は緩和（window focus precondition gate、
run-level environment health state、診断強化）を担い、burst の根本原因である
`launcherWindowFocus=false` が起こる per-boot トリガーの特定は本 Issue に分離された。

#300 の緩和 gate（PR #305。本 spec 作成時点で main 未 merge）は、実 CI 発生時に
environment 異常を 1 失敗 + 完全な環境証拠として捕獲することを実証した
（[Issue #304 コメント 1](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647372032)、
[コメント 2](https://github.com/nunu1733/NunuLauncher/issues/304#issuecomment-5647683394)）:

| # | 捕捉された frontmost / focused window | run | 観測された証拠 field |
|---|---|---|---|
| 1 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（CI image 内蔵の標準ランチャー） | [34704064012](https://github.com/nunu1733/NunuLauncher/actions/runs/34704064012)（head `083c902973`、2026-09-12 16:13 UTC、issue53 lane） | `interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... nexuslauncher/.NexusLauncherActivity}, frontmostPackage=com.google.android.apps.nexuslauncher` |
| 2 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [34709095836](https://github.com/nunu1733/NunuLauncher/actions/runs/34709095836)（2026-09-12 17:54 UTC、issue52 lane） | `interactive=true, keyguardLocked=false, focusedWindow=mCurrentFocus=Window{... Application Not Responding: com.android.systemui}, frontmostPackage=android` |
| 3 | `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`（標準ランチャー） | [34732479463](https://github.com/nunu1733/NunuLauncher/actions/runs/34732479463)（2026-09-13、issue53 lane） | 既存 capture 1 と同じ標準ランチャー frontmost の証拠 |
| 4 | `Application Not Responding: com.android.systemui`（ANR ダイアログ） | [34733839798](https://github.com/nunu1733/NunuLauncher/actions/runs/34733839798)（2026-09-13、issue52 lane） | 既存 capture 2 と同じ system UI ANR dialog の証拠 |

同じ収集期間の [34733180391](https://github.com/nunu1733/NunuLauncher/actions/runs/34733180391) は
gate を通らない `previewHeadingRestoresFocus...` の Compose timeout であり、occluder capture
ではない既存の非gateフレイクとして分離する。

これにより、burst は単一原因ではなく「焦点を保持する system window が存在する boot」
全般で発生していたことが強く示唆される。ただし次が未確定のままである:

1. **標準ランチャー occluder の発生機構**。test の launcher 起動は明示 component 指定
   （`am start -n <Lawnchair> -a MAIN -c HOME`。main の
   `OnboardingOrganizationProposalInstrumentationTest.kt:1158` 実測）であるにもかかわらず、
   標準ランチャーが frontmost focused に留まった。default HOME role の解決、起動の
   redirect、z-order 残留のいずれかは判別できていない。gate 証拠は
   `mCurrentFocus` 行と frontmost package のみを保持し、window z-order や HOME role
   state は残らない。
2. **gate 診断が強制状態を特定できることの実証**。修復対象状態（非 interactive /
   keyguard）は gate の自動修復で解消し証拠を残さないことが実証済み
   （KEYCODE_SLEEP 強制 → wakeup → green。PR #305 本文）。したがって「診断が状態を
   特定できる」ことの実証対象は修復対象外の occluder 状態であり、少なくとも 1 状態での
   実証が本 Issue の終了条件である。
3. **CI 失敗時の証拠保全の要否判断**。現在の lane の failure artifact は test report
   （と issue52 の UI evidence）のみであり（`ci.yml:551-560` 実測）、logcat / dumpsys は
   残らない。gate 証拠で足りるか、追加保全が要るかの判断が未記録である。
4. **root cause の結論そのもの**。上記 1〜3 の証拠を統合した結論、または緩和により
   再発が観測できなくなった場合の残存リスク受容の判断が未記録である。

## Outcome

本 Issue が完了したとき、api36 UI lane の burst は「なぜどの window が焦点を保持して
いたのか」で説明される。gate 診断が occluder 状態を特定できることが少なくとも 1 強制
状態で実証され、実発生 capture は occluder 型ごとに分類され、証拠保全の要否判断と
理由が記録され、root cause 結論（単一 cause または分類）が本 Issue へ記録される。
再発が観測できなくなった場合は「root cause 未確定のまま残存リスクを受容する」判断と
その根拠が記録されて完了する。後続の対策（provisioning 対策・gate 修復拡張）を
実施するかどうかの判断材料が、実装を伴わずに揃う。

## Scope

本 Issue は調査（investigation）であり、成果は証拠・判断・記録である:

- **強制状態による診断特定の実証**（終了条件 1）: ローカル api36 emulator 上で
  occluder 状態を人為的に作り、gate 失敗メッセージがその状態を特定することを
  少なくとも 1 状態で実証する。試行の成否・出力・CI シグネチャとの一致度を
  [plan.md](./plan.md) に記録する。
- **発生時証拠の蓄積と occluder 分類**: gate capture（既存 2 例を初期集合とする）を
  occluder 型（標準ランチャー / system dialog / keyguard / 非 interactive / その他 /
  不明）に分類し、証拠行だけから分類可能であることを確認する。
- **標準ランチャー occluder の機構判別に必要な観測の特定**: default HOME role
  （`RoleManager` / `cmd role get-role-holders android.app.role.HOME`）、window z-order
  （`dumpsys window windows`）、HOME category 起動の redirect 挙動のうち、どれが
  判別に必要で、gate 証拠の外でどう取得するかを整理する（取得の実装は非対象）。
- **証拠保全の判断**（終了条件 2）: gate 証拠で捕捉できない状態を列挙し、CI 失敗時の
  logcat / dumpsys artifact 等の導入/不導入と理由を本 Issue へ記録する。
  導入と判断した場合の実装は別 PR である（workflow 変更は
  [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md) の管轄）。
- **root cause 結論の記録**（終了条件 3）: [plan.md](./plan.md) の判断基準に従い、
  結論または残存リスク受容を本 Issue へ記録する。

## Non-goals

- 緩和策の実装（#300 / PR #305）。gate・health state・診断の変更も本 Issue では
  行わない。
- gate 修復の拡張（ANR dialog dismiss 等の修復追加）の実装。判断材料の提供まで。
- lane 構成・CI workflow・emulator provisioning の変更。証拠保全で必要になった場合も
  本 Issue で判断して記録するのみで、実装は別 PR で行う。
- issue52 lane の `(Focused = 'true')` assertion 失敗の原因断定。#300 と同様、
  同一原因とは主張せず、次回の gate 証拠で判別する。
- api35 lane の TwoPanelOrientationCaptureInstrumentationTest（issue #292、別メカニズム）。
- 新規 Issue の自動起票。分割が必要と判明した場合は本 Issue へ記録する。

## Domain language

なし（実装語のみ。`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: gate 診断は強制された occluder 状態を特定する (RC-AC-01)

Given api36 emulator 上で、focus を保持する外部 window（例: 標準ランチャーの
activity、他 app の activity）を前面化した状態がある
When gate を通る test 実行が `ensureWindowFocused` または `ensureInteractiveUnlocked`
経由の待ちで停止する
Then 失敗メッセージは gate の environment 接頭辞
（`input environment never reached a focused window` /
`input environment prevented the launcher from resuming` /
`input environment blocked the frontmost-window accessibility scan`）で始まり、
`focusedWindow=` / `frontmostPackage=` が強制した occluder を名指しする
And `interactive=` / `keyguardLocked=` はその時点の実際の device state と一致する
And 修復対象状態（非 interactive / keyguard locked）は gate の自動修復で解消して
green になるため、証拠特定の実証対象は occluder 系状態である（KEYCODE_SLEEP 実証済み
の挙動と整合する）

### Scenario: 発生時の証拠行だけで occluder を分類できる (RC-AC-02)

Given #300 の gate が動作する lane で environment anomaly が 1 失敗として停留した
When 失敗メッセージの `evidence=...; interactive=..., keyguardLocked=...,
focusedWindow=..., frontmostPackage=...` を読む
Then occluder の identity（焦点を保持した window の owner component または dialog 種別）
が証拠行だけから判別できる
And gate の分類（ENVIRONMENT_ANOMALY としての停留）と矛盾しない
And 既存の 2 capture（標準ランチャー、ANR ダイアログ）はこの契約を既に満たす実例である

### Scenario: root cause の結論が証拠つきで記録される (RC-AC-03)

Given 発生時 capture と強制状態試行の証拠が蓄積している
When [plan.md](./plan.md) の判断基準を適用する
Then per-boot トリガーの結論（単一 cause、または occluder 型ごとの分類）と、根拠と
なった run link・試行記録が本 Issue へ記録される。AC-3 の root cause 確定には、
最終的な occluder を直接前面化しただけの強制試行では足りず、仮説の因果経路を作る
制御再現、または自然発生した CI boot における role/resolve/activity/window/z-order
遷移の直接証拠を要求する。最終状態のシグネチャ一致は AC-1/AC-2 の証拠に限る。
Or 緩和により再発が観測できなくなった場合、「root cause 未確定のまま残存リスクを
受容する」判断とその根拠（occluder 一覧、gate の証拠能力、観測期間）が本 Issue へ
記録されて完了する

### Scenario: 証拠保全の判断が実装なしで記録される (RC-AC-04)

Given gate 証拠が保持しない状態（window z-order、HOME role state、logcat、
ANR trace 等）の不足が列挙されている
When CI 失敗時の証拠保全手段の要否を判断する
Then 導入/不導入と理由が本 Issue へ記録される
And 導入と判断した場合も本 Issue では実装せず、別 PR の対象として記録される

### Scenario: 調査証跡は plan.md に残る (RC-AC-05)

Given 強制状態による再現試行をローカル api36 emulator で実施した
When 試行が終了する
Then 成否・作った状態・gate 証拠の出力実物・CI シグネチャとの一致度が
[plan.md](./plan.md) に記録される（#300 の plan.md への強制状態試行記録と同じ扱い）

## Data and state

- 本 Issue は production data・schema・migration に触れない。
- 調査成果の正本は本 Issue（結論・判断）と [plan.md](./plan.md)（試行証跡・証拠一覧）
  である。CI run link、head SHA、確認日を添える。
- 強制状態試行で作る device state はローカル emulator 上の一時的なものであり、
  永続化・commit するものはない。

## Permissions, privacy, and security

None。新規 permission・外部送信はない。証拠に含まれるのは CI emulator 上の
component 名・window title・dumpsys 行であり、個人情報を含まない。

## Accessibility and localization

None。production の accessibility 振る舞いは変更しない。

## Acceptance criteria

- [ ] AC-1: gate 診断メッセージが強制状態を特定できることを、少なくとも 1 状態で
      実証する。手順・出力実物・CI シグネチャ一致度を plan.md に記録する
      （RC-AC-01、RC-AC-05）。
- [ ] AC-2: 実発生の gate capture が証拠行だけで occluder 型へ分類できることを確認し、
      分類表を本 Issue または plan.md に記録する（RC-AC-02）。
- [ ] AC-3: 実発生時の証拠または強制状態との整合から root cause を特定し、本 Issue に
      結論を記録する。ただし、最終的な occluder を直接強制しただけのシグネチャ一致は
      AC-1/AC-2 の証拠であり、AC-3 の機構確定には、因果経路の制御再現または自然発生
      CI boot の role/resolve/activity/window/z-order 遷移の直接証拠を要する。緩和により
      再発が観測できなくなった場合は、「root cause 未確定のまま残存リスクを受容する」
      判断とその根拠を記録して完了する（RC-AC-03）。
- [ ] AC-4: CI 失敗時の証拠保全手段の判断（導入/不導入と理由）を本 Issue に記録する
      （RC-AC-04）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | ローカル api36 emulator での強制状態試行 log と gate 失敗メッセージ実物。plan.md Verification evidence 節に記録 |
| AC-2 | gate capture の証拠行と occluder 分類表（自然発生 4 例 + 非gateフレイクの分離 + 強制 1 例）。本 Issue または plan.md に記録 |
| AC-3 | 本 Issue の結論コメント（自然発生CIの遷移証拠または因果経路の制御再現と、根拠 run link / 試行記録への参照つき） |
| AC-4 | 本 Issue の判断コメント（列挙した不足状態と理由つき） |

## Open questions

blocking なものはない。調査中に解決すべき問い:

- 明示 component 付き HOME category 起動が default HOME role holder へ redirect
  される platform 挙動の有無（標準ランチャー occluder 機構判別の中心問い）。
- gate の focus 待ち deadline（15 秒、PR #305）と main の `awaitResumedLauncher`
  待ち（120 × 100ms ≒ 12 秒、`OnboardingOrganizationProposalInstrumentationTest.kt:1126`
  実測）の差が、発生状態の観測 window に与える影響。
- gate 付きの発生頻度の定量は PR #305 の merge 後にしか取れない。

## Change history

- 2026-09-13: Draft created for #304（#300 の Spec/Plan review による分離決定と、
  2026-09-12 の gate 証拠 2 例の Issue コメントを入力として作成）。
- 2026-09-13: 再開時に `origin/main`=`3aa6e83a1f` とIssue全コメントを再確認し、
  #305 merge後の現行gateで標準ランチャーoccluderを強制した診断実証と、追加証拠保全を
  別PRへ分離する判断を `plan.md` に記録。契約範囲とroot cause未確定の扱いは変更なし。
- 2026-09-13: review指摘を反映し、自然発生CI capture（標準ランチャー 2 例、system UI
  ANR 2 例）と非gateフレイクを分類表へ追加。最終occluderの強制再現はAC-1/AC-2に限り、
  AC-3には因果経路の制御再現または自然発生CIの遷移証拠を要求するよう明記した。
