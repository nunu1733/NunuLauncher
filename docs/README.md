# Documentation Map

このリポジトリは、GitHub Issueを開発の入口とし、長期的な知識をリポジトリ内文書に置く。Issueのコメントだけに仕様や設計理由を残さない。

## 文書の役割

| 文書 | 読者の問い | 更新契機 |
|---|---|---|
| [README.md](../README.md) | このprojectは何で、どこから読むか | projectの入口が変わるとき |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | GitHub上で変更をどう開始するか | contributor向け入口が変わるとき |
| [AGENTS.md](../AGENTS.md) | Agentはどの順序・制約で作業するか | 開発手順、検証command、構造が変わるとき |
| [CONTEXT.md](../CONTEXT.md) | 用語は何を意味するか | domain用語が解決・変更されたとき |
| [DESIGN.md](../DESIGN.md) | system全体のmodule/interface/invariantは何か | 全体設計が変わるとき |
| [product/product-brief.md](./product/product-brief.md) | 誰の何を解決し、何をしないか | product方針が変わるとき |
| [product/requirements.md](./product/requirements.md) | 必要な振る舞いと品質は何か | 要件Issueが承認されたとき |
| [assessment/initial-design-review.md](./assessment/initial-design-review.md) | 初期案の問題と根拠は何か | 原則固定。再調査は別文書にする |
| `assessment/pr-<n>-<slug>.md`（形式は [assessment/_template.md](./assessment/_template.md)） | 高リスクPRの独立audit証拠は何か | `risk: layout-data` / `risk: migration` PRのmerge前。手順は [project/github-workflow.md](./project/github-workflow.md) |
| [project/github-workflow.md](./project/github-workflow.md) | Issue/spec/PRをどう流すか | 開発processが変わるとき |
| [project/seed-backlog.md](./project/seed-backlog.md) | 起票済みIssueへのnavigationと未起票提案 | Issueの起票・close時に更新する |
| [engineering/upstream-strategy.md](./engineering/upstream-strategy.md) | Lawnchair上流とどう同期するか | baseや同期方針が変わるとき |
| [engineering/building.md](./engineering/building.md) | どのtoolchainとcommandで検証するか | baseline/toolchain/commandが変わるとき |
| [engineering/quality-strategy.md](./engineering/quality-strategy.md) | 何をどう検証するか | test/CI方針が変わるとき |
| [engineering/organizer-diagnostics.md](./engineering/organizer-diagnostics.md) | organizer runのdiagnosticのfield・redaction・保持・出力はどう決まるか | diagnostics契約が変わるとき |
| [adr/](./adr/) | 変更しにくい判断をなぜ行ったか | 判断時。日々の進捗では更新しない |
| [specs/](../specs/) | あるIssueの観測可能な振る舞いは何か | 受入条件が変わるとき |

## 正本とリンク

- Issueは目的、範囲、状態、担当、依存関係を持つ。
- specは振る舞い、受入条件、非対象を持つ。
- planは変更module、migration、rollback、検証手順を持つ。
- PRは実装差分と検証結果を持つ。
- ADRは変更困難な判断と理由だけを持つ。

Issue、spec、plan、PRは相互にリンクする。進捗用の `STATUS.md` や重複したTODO一覧は作らない。

## 状態表記

文書冒頭で必要に応じて次を使う。

- `draft`: 検討中で、実装の入力にできない。
- `proposed`: 提案済みで、承認待ち。
- `accepted`: 判断または仕様として承認済み。
- `implemented`: 受入条件を満たす実装がmainlineに入った。
- `superseded`: 別文書に置き換えられた。
