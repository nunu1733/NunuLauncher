# 日本語 UI コピー スタイルガイド

> Status: implemented for Issue #161
>
> 対象: NunuLauncher が追加したユーザー向け Android UI の日本語 resource

## 目的と読み手

第一の読み手は、実装 details を知らない通常の日本語 Android 利用者である。文章は英語の語順を保存するのではなく、画面上で利用者が何を確認し、何を実行できるかが自然に分かることを優先する。

このガイドは Lawnchair/AOSP の既存日本語を置き換えるためのものではない。同じ概念に既存の自然な用語がある場合はそれを参照し、Nunu 固有の文言に適用する。用語の意味そのものを変更する必要がある場合は、翻訳修正ではなく `PRODUCT_DECISION` として扱う。

## Surface class

| Class | 対象 | 方針 |
|---|---|---|
| primary normal-user UI | 設定のタイトル、ボタン、メニュー、通常の状態表示 | 短く、行動と結果が予測できる利用者語を使う。実装語を出さない。 |
| confirmation / warning / recovery | 確認、警告、失敗、復旧、適用結果 | 自然さより意味保存を優先する。変更の有無、確認の必要性、復旧可否、再実行禁止などを削らない。 |
| settings / help descriptive copy | 設定概要、説明、補助文 | 一文一要点を基本とし、何が自動で起きるか・起きないかを明示する。 |
| accessibility-only text | `contentDescription`、読み上げ専用の説明 | 視覚用の省略記号や名詞の羅列をそのまま流用せず、聞いて意味が取れる文にする。 |
| diagnostics / support / developer-facing | 診断画面、サポート用エクスポート、拒否理由 | 技術的な正確さを優先してよい。通常 UI へ同じ専門語を広げる根拠にはしない。 |

## 表現の原則

### CTA、タイトル、状態

- ボタン・メニューは、タップ後の行動を表す動詞または「〜を確認」「〜を適用」のような動作句にする。
- 「確認」だけでは何を確認するのか不明な場合は「整理案を確認」「配置を確認」のように目的語を補う。
- タイトルは名詞句でもよいが、操作入口では操作対象を含める。説明文と同じ内容を冗長に繰り返さない。
- 状態表示では、現在の状態と利用者が選択できる操作を混同しない。「使用中」「保存済み」「要確認」は状態、「使用する」「保存」「確認」は操作である。
- 件数は `%1$d件`、ページは `%1$dページ`、枠は `%1$d枠` のように、対象に合う助数詞を使う。既存の placeholder の数・型・順序は変更しない。

### 自然さ、簡潔さ、語順

- 英語の名詞をそのままカタカナ化しない。日本語の利用者概念で同じ意味を伝えられる場合はそちらを使う。
- 長い説明を削って警告を短くすることは禁止する。必要な結果や制約は説明文へ残す。
- 主語を省略しても誤解しない場合は省略する。誰が何をするかが安全判断に関わる場合は主語や対象を明示する。
- 中黒（`・`）や読点（`、`）は、視覚的な並列と読み上げの自然さを分けて判断する。読み上げ文は format resource で日本語の語順を制御する。

### 句読点と末尾

| UI | 原則 |
|---|---|
| button / menu label | 句点を付けない。短い動作句にする。 |
| screen title / heading | 句点を付けない。疑問形タイトルは `？` を使う。 |
| description / help | 完全文には `。` を付ける。複数文でも各文を明確に区切る。 |
| dialog / warning / error | 利用者の判断に必要な文には `。` を付ける。確認要求は `？` を使う。 |
| progress / toast | 完了・失敗を伝える文は `。` を基本とする。進行中の省略記号は source の意味を保つ。 |
| accessibility-only | 読み上げて自然な完全文にする。句読点を視覚ラベルの規則だけで省略しない。 |

既存の上流 resource の表記を stylistic preference だけで変更しない。この表は Nunu 固有 resource の新規・修正時に適用する。

### カタカナ・英語・技術語

- Android の利用者が設定画面で通常使う語（アプリ、ウィジェット、ショートカット、プロファイル、Dock など）は、既存の Lawnchair/AOSP 表記と互換なら維持する。
- `organizer`、`target`、`canonical`、`override`、`checkpoint`、`diagnostic` のような実装・ドメイン語は、通常 UI で利用者の判断に不要なら user concept へ言い換える。
- 固定の技術識別子やファイル名は翻訳しない場合でも Kotlin の literal に置かず、`translatable="false"` resource に置く。実行時に取得する package 名、実ファイル名、ID などの runtime data はこの限りではない。
- 診断・サポート画面では、調査に必要な technical term を残してよい。その場合は review table で `TECHNICAL_ONLY` と理由を記録する。

### 安全、失敗、復旧

- 「何も変更されていない」「変更を適用していない」「以前のレイアウトを復元した」「復旧を検証できない」などは別の状態として訳し分ける。
- `apply`、`restore`、`recovery point`、`rollback` を、より自然に見せるため「自動で直る」「必ず成功する」などの保証へ変えてはならない。
- stale state、競合、復旧失敗、再実行禁止の文言は high severity として source の behavior/spec と照合する。
- 破壊的・不可逆な操作の結果、確認の要否、操作対象の限定範囲は短縮しない。

## Android resource 互換性

- default resource と Japanese resource の name、resource kind、placeholder の数・型・位置指定・順序を一致させる。
- `%1$s` と `%1$d` の型を変更しない。日本語の語順を変える場合は format resource の placeholder の位置を保ったまま文全体を組み替える。
- plurals の quantity set、escaping、XML entity、`translatable="false"` の意図を保持する。
- 複数の値を Kotlin で連結して user-visible 文や読み上げ文を作らない。locale が語順・区切りを選べる format resource を使う。
- placeholder の実例を一つ以上レンダリングして、助数詞・読点・語順が自然であることを確認する。

## レビュー時の停止条件

次の場合は文言を決め打ちしない。

- 文言変更が navigation、情報階層、automatic/manual の挙動、または domain concept の変更になる。
- 警告・失敗・復旧の意味、成功保証、復旧可能性が変わる。
- source English 自体が accepted behavior と矛盾する。
- surface、UI role、placeholder の意味が分からず、文脈を安全に復元できない。

これらは `PRODUCT_DECISION`、owner resolution、または別 Issue の対象として evidence に残す。
