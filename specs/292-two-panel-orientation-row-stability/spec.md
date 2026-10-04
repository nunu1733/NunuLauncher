---
issue: "#292"
status: accepted
requirements:
  - TS-AC-01
  - TS-AC-02
  - TS-AC-03
  - TS-AC-04
risk: []
updated: 2026-09-12
---

# api35 lane: orientation stale-rejection test survives the launcher's one-time default-workspace load

## Problem

`organizer-instrumentation-api35-tests` lane の
`TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`
が、PR #289 の CI で 4 回中 2 回失敗した
（[run 34625444030](https://github.com/nunu1733/NunuLauncher/actions/runs/34625444030) /
[run 34665957437](https://github.com/nunu1733/NunuLauncher/actions/runs/34665957437)、
両回とも attempt 1 で失敗し rerun で green、同一シグネチャ・同一行 `_id=9`）。

失敗シグネチャ:

```text
expected: ... screen=null appWidgetId=-1 modified=0 container=6 ... _id=9 ... rank=2 cellX=null cellY=null ... title=Maps ...
but was:  ... screen=0   appWidgetId=-1 modified=<wallclock> container=6 ... _id=9 ... rank=2 cellX=2 cellY=0 ... title=Maps ...
```

失敗メカニズムは特定済みであり（[plan.md の Current evidence](./plan.md)）、テストと
launcher の起動シーケンスの競合である。production seam（stale rejection、no-write
保証）の振る舞いは正しい。テストが plan 行を確定する時点が、launcher の
**一回きりの default workspace load が favorites 全行を抹消・再採番する窓**より
前であることが敗因である:

1. lane 内で launcher の model bind がまだ一度も走っていない間、
   `EMPTY_DATABASE_CREATED` フラグが未消化のまま残る（フラグは db 作成時に立ち、
   初回 LoaderTask の `loadDefaultFavoritesIfNecessary` で消化される）。
2. テストは `ensureLauncherRow` を `bringLauncherToForeground()` の前に呼ぶ。この時点
   favorites に desktop/hotseat 行が無ければ、テストは `generateNewItemId()`
   （キャッシュされた `mMaxItemId` + 1）で自前の行を挿入する。CI 失敗時は挿入 id が 9
   になった。
3. 直後の `bringLauncherToForeground()` が launcher の初回 bind を起こし、
   LoaderTask がフラグを消化して `createEmptyDB`（テストの挿入行を含む全行削除）+
   default layout 再挿入を行う。CI の default layout では Google folder が `_id=6`、
   その 3 番目の子 Maps が `_id=9`（rank=2、cell 未設定、modified=0）になるため、
   テストの挿入 id と衝突する。
4. `rowsBefore` は再採番後のテーブルを写す。回転（landscape rebind）時、launcher は
   folder 子の配置を正当に再配置して `screen`/`cellX`/`cellY`/`modified` を書き込む
   （既知の正しい挙動）。no-write assert は「`_id == plannedRowId`」で行を比較するた
   め、比較対象が plan 行ではなく default layout 由来の Maps 子にすり替わり、その
   before/after 差でテストが壊れる。

つまり assert は「rejected apply が書き込まなかった」ことではなく、launcher 自身の
正当な folder 再配置を検出してしまっている。ローカル再現でも同一メカニズムで同種の
失敗（Gmail 子 `_id=8`）を起こせており、因果は logcat タイムラインで確認済みである。

## Outcome

api35 lane において、orientation stale-rejection テストが lane の起動順序・初回 bind
タイミングに依存しなくなる。テストは launcher model の初回 bind 完了後に行選択を行う
ため、plan 行が default workspace load によって抹消・再採番される経路がなくなり、
no-write assert は常に plan 行自身を比較する。同種のフレーク再発は、
launcher の folder 子再配置という正当な挙動を検査対象から除外し続ける
（desktop/hotseat 行のみを再利用する既存フィルタは維持する）。

## Scope

- `TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`
  の行選択タイミングを、`bringLauncherToForeground()` と model bind 完了待ちの後へ移
  動する。
- 初回 bind 完了（= default workspace load 消化）を待つ明示的な待機をテストに追加す
  る。待機がタイムアウトした場合は、テストを明示的なメッセージ付きで失敗させる。
- 行選択後の表（テストコメント）に、なぜこの順序が必要か（#292 のメカニズム）を残す。
- 失敗の再現証拠（衝突を意図的に作ったローカル再現）を PR に記録する。

## Non-goals

- production seam（`LayoutApplicationModule`、stale rejection、recovery、
  launcher 側 folder 再配置の挙動）の変更。folder 子の配置書き込みは launcher の正当
  な挙動であり、本 Issue では扱わない。
- 同クラス他 2 テストの `tearDown` における 5 秒 `isModelLoaded` 待ち時間の短縮（現
  在は model 未 bind のまま 5 秒経過して静かに抜けるが、失敗とは無関係である）。
- lane 構成、他テストクラス、CI workflow の変更。
- TestProtocol 経由のフラグ操作（`REQUEST_CLEAR_DATA` 等）による回避。lane の他クラ
  スに影響する。

## Domain language

なし（実装語のみ。`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: 行選択は初回 bind 完了後に行われる (TS-AC-01)

Given lane プロセスで launcher model がまだ bind しておらず、
`EMPTY_DATABASE_CREATED` フラグが未消化である
When stale-rejection テストが実行される
Then テストは launcher を foreground にし、model bind の完了を待ってから行選択を行う
And 行選択は既存の desktop/hotseat 行を再利用する（通常の lane では挿入経路に入らない）

### Scenario: no-write assert は plan 行自身を比較する (TS-AC-02)

Given 行選択が bind 完了後に行われた
When 回転 relayout が folder 子の配置/modified を正当に書き換え、rejected apply は
何も書き込まない
Then `_id == plannedRowId` の before/after 比較は plan 行自身で一致する
And marker title（`orientation-stale`）を持つ行は存在しない

### Scenario: 初回 bind が完了しない環境 (TS-AC-03)

Given launcher activity が生成されず model bind が完了しない
When bind 完了待ちがタイムアウトする
Then テストは待機の対象を説明する明示的なメッセージで失敗する
And 暗黙のタイムアウトや、競合に依存した不定の失敗シグネチャは発生しない

### Scenario: 失敗の再現（現行テストの欠陥実証） (TS-AC-04)

Given 現行（修正前）のテストと、挿入 id を default layout の folder 子 id に衝突させ
 る事前準備
When 再現手順（[plan.md Verification](./plan.md)）を実行する
Then 現行テストは CI と同種のシグネチャ（default layout 由来 folder 子の
before/after 差）で失敗する
And 修正後のテストは同一準備・同一手順で成功する

## Data and state

- テストのみの変更であり、読み書きする永続 data、schema、migration、backup/restore
  への影響はない。
- テストは既存の `setUp` snapshot → `tearDown` restore の pattern をそのまま使う。
