# Lawnchair Upstream Strategy

> Status: Accepted for current baseline
> Updated: 2026-08-09

## Current state

GitHub forkとLawnchair sourceを導入済みである。bootstrapの進捗と証拠は [Issue #1](https://github.com/nunu1733/NunuLauncher/issues/1) で追跡する。

現在のbaselineは次の通り。

```text
upstream repository: https://github.com/LawnchairLauncher/lawnchair.git
origin repository:   https://github.com/nunu1733/NunuLauncher.git
baseline branch:     main
upstream branch:     15-beta
baseline tag:        v15.0.0-beta3.0
baseline commit:     505dbc40e6154c05158b5d0271c45f6a885a411b
upstream HEAD:       16-dev (2026-08-09時点)
```

## Bootstrap decision

最初のupstream Issueで以下を実行・記録した。

1. GitHub上にLawnchairのfork `nunu1733/NunuLauncher` を作成した。
2. `main` をcandidate commitへ固定し、default branchにした。
3. remote名を `origin`（NunuLauncher fork）と `upstream`（公式Lawnchair）に固定した。
4. submoduleを固定commitへ初期化した。
5. JDK 21とAndroid SDK 36.1でformat checkとdebug APK buildを再現した。
6. upstreamの `LICENSE.txt` がbaselineと同じblobであることを検証した。top-level `NOTICE` はこのbaselineに存在しない。

検証済みcommandと環境は [building guide](./building.md) に記録する。

Git履歴を失うsource copy、無関係なroot repositoryとのsquash import、既定branchだけを見たversion選択は行わない。

## Patch-surface policy

- project固有logicは原則 `app.lawnchair` 配下のまとまったmoduleへ置く。
- `com.android.launcher3` / AOSP由来pathは、event・model・UIを接続する最小bridgeだけ変更する。
- upstream fileへのpatchは、対応Issue、必要理由、代替不可の根拠をPRに記載する。
- 既存Deck layoutを調査し、同じevent、preference、DB backupを持つ並行機能を作らない。
- upstream source全体のformat変更、package移動、機械的rewriteをproject機能PRへ混ぜない。

## Sync workflow

同期は専用 `type: upstream` IssueとPRで行う。

1. 対象upstream commit範囲とrelease noteを記録する。
2. security、schema、model event、backup/restore、Deck layoutの変更を先に調査する。
3. merge/rebase方式はrepository公開時に1つへ決め、毎回変えない。
4. conflictはproject固有意図をspec/DESIGN/ADRから確認して解消する。
5. full build、upstream test、planner/apply contract、migration、backup/restore、smoke testを実行する。
6. project patch surfaceの増減と、次回までのtemporary workaroundをPRに記録する。

## Upgrade policy

15→16のmajor rebaseは通常のdependency updateとして扱わない。別Epicで次を比較する。

- product valueとAndroid version support。
- Launcher3 model/schema/eventの変更。
- Deck layoutの変化とproject patchの再適用cost。
- build/toolchainとdevice test matrix。
- rollback可能なrelease/migration path。

採用はADRの対象になる。
