# NunuLauncher

ホーム画面の整理をユーザーの継続的な手作業から切り離す、Android向けランチャープロジェクトです。Lawnchairを基盤とし、ルールに基づく安全で説明可能なホームレイアウト整理を追加します。

## 現在地

GitHub repositoryは [nunu1733/NunuLauncher](https://github.com/nunu1733/NunuLauncher) です。Lawnchairのfork ancestryを保持し、projectの `main` は `v15.0.0-beta3.0` のcommit `505dbc40e6154c05158b5d0271c45f6a885a411b` をbaselineとして固定しています。

bootstrapは [Issue #1](https://github.com/nunu1733/NunuLauncher/issues/1) で追跡しています。2026-08-09にJDK 21、Android SDK 36.1、Build Tools 36.1.0でformat checkとGitHub debug APK buildを再現済みです。

## 読み始める場所

1. [AGENTS.md](./AGENTS.md) — AI Agentを含む全開発者の作業規約
2. [CONTRIBUTING.md](./CONTRIBUTING.md) — GitHub IssueからPRまでの入口
3. [docs/README.md](./docs/README.md) — 文書マップと正本の分担
4. [docs/assessment/initial-design-review.md](./docs/assessment/initial-design-review.md) — 初期案の問題・不足点
5. [docs/product/product-brief.md](./docs/product/product-brief.md) — プロダクトの目的と範囲
6. [docs/product/requirements.md](./docs/product/requirements.md) — 要件ID、未決定事項、品質要件
7. [CONTEXT.md](./CONTEXT.md) — ドメイン用語
8. [DESIGN.md](./DESIGN.md) — 目標アーキテクチャ
9. [docs/project/seed-backlog.md](./docs/project/seed-backlog.md) — 最初に作るGitHub Issue候補
10. [docs/engineering/building.md](./docs/engineering/building.md) — 検証済みtoolchainとbuild手順

## Quick build

JDK 21とAndroid SDKを用意し、submoduleを含めてcheckoutした後に実行します。詳細は [building guide](./docs/engineering/building.md) を参照してください。

```bash
git submodule update --init --recursive
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
```

## 開発原則

- すべての変更はGitHub Issueから始める。
- 機能実装は承認済みの `specs/<issue>-<slug>/spec.md` に基づく。
- ホームレイアウトへの変更は、計画・検証・適用・復旧を分離する。
- 通常機能はローカルかつオフラインで完結させる。
- Lawnchair/Launcher3本体への差分を小さく保ち、上流追従を継続可能にする。

## 参照

- [NunuLauncher GitHub repository](https://github.com/nunu1733/NunuLauncher)
- [Lawnchair公式リポジトリ](https://github.com/LawnchairLauncher/lawnchair)
- [Android `ApplicationInfo` 公式資料](https://developer.android.com/reference/android/content/pm/ApplicationInfo)

## Upstream and license

NunuLauncherはLawnchair/Launcher3を基盤とするforkです。上流のcopyright、source header、[Apache License 2.0](./LICENSE.txt)、third-party noticesを保持します。上流Lawnchairへの貢献方法は [Lawnchair contributing guidelines](https://github.com/LawnchairLauncher/lawnchair/blob/15-beta/CONTRIBUTING.md) を参照してください。
