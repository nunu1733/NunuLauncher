# AGENTS.md

この規約はリポジトリ全体に適用する。より深いディレクトリに `AGENTS.md` がある場合は、その範囲について追加規約を適用する。

## プロジェクト状態

- GitHub repositoryは `nunu1733/NunuLauncher`、`origin` はそのfork、`upstream` は `LawnchairLauncher/lawnchair` である。
- `main` baselineはLawnchair `v15.0.0-beta3.0` のcommit `505dbc40e6154c05158b5d0271c45f6a885a411b` である。
- baseline導入は [Issue #1](https://github.com/nunu1733/NunuLauncher/issues/1) で追跡する。
- Lawnchair 16への変更は通常updateとして扱わず、専用EpicとADRを要求する。

## 作業開始時の必読順

1. 作業対象のGitHub Issueと全コメント
2. Issueからリンクされた `specs/<issue>-<slug>/spec.md`
3. この `AGENTS.md`
4. [CONTEXT.md](./CONTEXT.md)
5. [DESIGN.md](./DESIGN.md)
6. 関連する [docs/adr/](./docs/adr/) と対象module近傍の文書
7. [docs/engineering/quality-strategy.md](./docs/engineering/quality-strategy.md)
8. buildを伴う場合は [docs/engineering/building.md](./docs/engineering/building.md)

Issueまたは承認済みspecがない機能実装は開始しない。調査、障害対応、文書修正もIssueへ成果物と終了条件を記載する。

## 正本の分担

| 情報 | 正本 |
|---|---|
| 目的、範囲、優先度、進捗、担当、依存関係 | GitHub Issue |
| 観測可能な振る舞い、受入条件、非対象 | `spec.md` |
| 実装順序、変更module、migration、検証方法 | 同じspecディレクトリの `plan.md` |
| 全体のmodule構造、interface、seam、システム不変条件 | `DESIGN.md` |
| ドメイン用語 | `CONTEXT.md` |
| 変更困難な設計判断と理由 | `docs/adr/` |
| 現在の作業状態 | GitHub Issue / Pull Request |

同じ内容を複数箇所に複製しない。矛盾を見つけた場合は、コードで都合よく解釈せず、Issueへ記録して該当する正本を先に直す。ADRとspecが衝突する場合は実装を止め、判断を更新する。

## Issue駆動・仕様駆動の手順

1. Issueの問題、成果、非対象、要件ID、リスク、終了条件を確認する。
2. 機能変更ではspecを作成または更新し、シナリオと失敗時の振る舞いを具体化する。
3. 未決定の製品判断が残る場合は実装せず、research/decision Issueへ分離する。
4. spec承認後に `plan.md` を作り、変更するmodule、seam、migration、rollback、検証を記載する。
5. 最小の縦切りで実装し、interfaceを通したテストを先に追加する。
6. 実行した検証と結果をPRへ記録し、必要な文書を同じPRで更新する。
7. PRは `Closes #<issue>` を含め、specの受入条件と対応付ける。

詳細は [docs/project/github-workflow.md](./docs/project/github-workflow.md) を参照する。

並行作業では、共有interface、DB migration、Launcher3 bridgeを先行Issueで確定する。各planに主な変更pathを記載し、同じseamを複数Agentが未調整で同時変更しない。

## 設計規約

- **Module** は小さな **interface** の背後に大きな振る舞いを隠す。単なる委譲moduleを増やさない。
- 呼び出し側とテストは同じ **seam** を使う。内部実装を直接検証しない。
- 配置計算は副作用のない計画moduleに置き、Launcher DBへの反映moduleから分離する。
- platform型やDB行を計画moduleのinterfaceへ漏らさない。
- production用とtest用の実体が必要になるまで、仮想的なinterfaceやadapterを増やさない。
- 既存の `app.lawnchair.deck` を調査せず、並行する分類・配置機構を追加しない。
- Launcher3/AOSP由来コードへの変更はbridgeとなる最小箇所に限定し、Issue番号と理由を近傍文書に残す。

## ホームレイアウトを扱う安全規約

次の条件を満たさない変更をDBへ適用してはならない。

- 入力snapshotと適用時の状態が同じrevisionである。
- 全配置アイテムが「保持・移動・明示的削除」のいずれかに説明可能である。
- ロック配置が変化していない。
- 座標がdevice profile内で、重複せず、folder/container参照が有効である。
- 適用前にアプリ内から復旧可能なrecovery pointが作成されている。
- 変更はtransactionとして成功するか、変更前へ戻る。
- 適用後に再読込し、不変条件を再検証する。

`favorites` の無条件な全削除→再挿入、遅延時間への依存、手動バックアップだけをundoとみなす実装は禁止する。例外が必要なら、受入済みADRと破壊・復旧テストを要求する。

## テスト規約

- 計画module: fixture、境界値、property test、決定性、冪等性をinterface経由で検証する。
- 適用module: test DBを使い、transaction、競合、失敗注入、rollback、folder/widget参照を検証する。
- integration: package追加、work profile、grid変更、restore、プロセス再起動を対象にする。
- UI: 確認、失敗、復旧、アクセシビリティを検証する。
- 修正は失敗を再現するテストを伴う。テストできない場合は理由と代替証拠をPRに記載する。

## 検証済みcommand

JDK 21、Android SDK Platform 36.1、Build Tools 36.1.0を使う。環境詳細は [building guide](./docs/engineering/building.md) を正本とする。

```bash
git submodule update --init --recursive
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
```

新しいcommandを必須にする前に、clean checkoutまたはCIで成功を確認してbuilding guideへ追記する。

## ドキュメント規約

- `CONTEXT.md` に実装詳細を書かない。
- `DESIGN.md` に一時的な進捗を書かない。
- ADRは「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」の3条件を満たす判断だけに使う。
- 調査結果は根拠のURL、対象commit、確認日を残す。
- 文書の状態を `draft`、`proposed`、`accepted`、`implemented` 等で明示する。
- Issueの番号が確定する前に仮の番号をspecディレクトリへ割り当てない。

## 変更時の基本動作

- 変更前に `git status` と対象ファイルを確認し、ユーザーや他Agentの変更を保護する。
- 大きなformat変更と機能変更を同じPRに混ぜない。
- dependency追加、権限追加、通信追加、DB migrationはspecとリスク評価なしに行わない。
- 既存のbuild/test/lintコマンドを優先し、成功したコマンドをPRへ正確に残す。
- 生成物、credential、端末固有設定、個人データをcommitしない。

## 完了条件

- Issueの終了条件とspecの全受入条件が満たされている。
- 関連テスト、lint、buildが成功し、結果がPRにある。
- migrationとrollbackを必要な環境で検証している。
- `CONTEXT.md`、`DESIGN.md`、ADR、要件のうち影響する正本が更新されている。
- 新たな未決定事項を隠さず、追跡Issueへ分離している。
