# Feature Specifications

`specs/` はGitHub Issueに対応する観測可能な振る舞いの正本である。project-wide architectureやdomain用語をここへ複製しない。

## Directory convention

```text
specs/
├── README.md
├── _template/
│   ├── spec.md
│   └── plan.md
└── <issue-number>-<short-slug>/
    ├── spec.md
    └── plan.md
```

例: Issue `#42` は `specs/42-safe-layout-apply/`。Issue番号が作成される前に仮番号を採番しない。

## Rules

- `spec.md` はproblem、scope、non-goals、scenario、acceptance criteria、failure behaviorを記述する。
- `plan.md` は承認済みspecをどう実装・migration・検証するかを記述する。
- Issueは状態、優先度、担当、依存関係を持ち、specはそれらを複製しない。
- Pull RequestはIssueとspecをlinkし、受入条件ごとのevidenceを示す。
- 1つのspecが複数の独立成果を持つならIssueから分割する。
- behavior変更時はcodeと同じPRでspecを更新する。

## Status

- `draft`: 検討中。
- `accepted`: 実装可能。
- `implemented`: mainlineで受入済み。
- `superseded`: 別specに置換。
