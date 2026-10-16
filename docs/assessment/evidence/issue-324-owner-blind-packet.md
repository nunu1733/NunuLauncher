# Issue #324 owner blind adjudication packet — candidate outputs (sealed ids)

> Status: sealed-id packet for the owner's blind scoring (spec AC-324-04 / #161 §7).
> The two candidate outputs below are labeled candidate-E34A and candidate-2FB9. These labels are fresh random identifiers that have never appeared in this repository's history: no model name, vendor, adapter, or earlier-identifier correspondence for them is published anywhere, and identity-bearing strings inside the outputs are replaced with `[sealed]`. The id→model mapping is held outside the repository (implementing agent's hand-off) until the owner's scoring is recorded.
> Scoring: for each candidate and each of the 24 units in `issue-324-bakeoff-context.md`, score 0–2 on the 8 axes (Accuracy / Fluency / Terminology / UI style-concision / Context fit / Safety preservation / Resource correctness / AI-register detection); a 0 on Accuracy or Safety preservation for any unit is a hard failure; tie-break by Safety+Accuracy sums then a written rationale. Record per-unit scores and an adoption ruling by appending to this file or the final PR record; the mapping is then unsealed alongside.


## Candidate candidate-2FB9 output
### 01

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| homeedit_menu_move_to_page | OK | menu | normal | アイテム編集ボトムシート | ページへ移動… | — | 移動先を選ぶ操作として理解でき、省略記号も次の選択画面に続くことと整合する。 | low | yes：即時移動を保証していない。 | 変更なし | スタイルガイド「CTA、タイトル、状態」 |

### 02

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| homeedit_undo_label | OK | result | normal | 編集完了・Undo表示 | 編集を適用しました | — | 編集済みの状態を短く伝えている。隣接するUndo操作とも区別できる。 | low | yes：編集が確定した状態を維持。 | 変更なし | スタイルガイド「CTA、タイトル、状態」／rubric：状態と操作の区別 |

### 03

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| homeedit_undo_error_stale | REVISE | error | safety-critical | Undo失敗スナックバー／ダイアログ | ja: 元に戻せません: アイコンがその後移動されています。<br>default: Can’t undo: the icon has moved since. | ja: 編集後にアイコンが移動したため、元に戻せません。<br>default: 変更なし | 「その後」の基準を編集後と明示し、不自然な受け身を解消する。原因と拒否結果を一文で結ぶ。 | high | yes：編集後の移動を理由にUndoを拒否する意味を保持。編集の取り消しや復元成功は示さず、適用済み編集が残る挙動と整合する。 | 総文字数は短縮。ダイアログを通常・拡大文字で確認。 | スタイルガイド「安全、失敗、復旧」「自然さ、簡潔さ、語順」 |

### 04

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_exclude_action | REVISE | action | normal | 整理案プレビューのアイテム行 | ja: 提案から外す<br>default: Exclude | ja: 整理案から外す<br>default: 変更なし | 用語集の「整理案」に合わせ、ホーム画面からの削除ではなく案から外す操作だと伝える。 | medium | yes：選択アイテムを整理案から外す操作を保持。 | 1文字増。行内ボタンを通常・拡大文字で確認。 | 用語集：organization proposal／スタイルガイド「CTA、タイトル、状態」 |

### 05

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_exclusion_blocked_structural | REVISE | warning | safety-critical | プレビューの除外不可表示 | ja: 除外できません: 再計画が自動的に決定する行です<br>default: not excludable: replanning decides this row automatically | ja: この行は整理案の再計算で自動的に決まるため、整理案から外せません。<br>default: This row is determined automatically during replanning and can’t be excluded. | 「再計画が決定する」という実装寄りの表現を、表示行が決まる理由と操作制約の説明にする。英語も開発用メモのような断片を解消する。 | high | yes：再計算で自動決定される行であることと、除外できない制約を両方保持。手動変更や回避方法を追加していない。 | 長文化。警告表示を通常・拡大文字で確認。 | スタイルガイド「安全、失敗、復旧」「カタカナ・英語・技術語」／rubric：default English quality |

### 06

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_warning_duplicate_launch_target | REVISE | warning | safety-critical | プレビュー警告一覧 | ja: 重複のため移動させなかった配置: %1$d件<br>default: Duplicates of another icon were kept in place: %1$d | ja: 別のアイコンと起動先が同じため、%1$d件の配置は移動していません。<br>default: 変更なし | 見た目や名前の重複ではなく、起動先の重複が理由だと分かる。 | high | yes：同じ起動先を持つ配置を意図的に移動しなかった結果を保持。削除・統合・復元を示唆しない。%1$dは件数のまま維持。 | 長文化。警告一覧を通常・拡大文字で確認。 | スタイルガイド「安全、失敗、復旧」「CTA、タイトル、状態」／rubric：具体性 |

### 07

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_unplaced_candidates_heading | REVISE | title | normal | プレビュー概要のセクション見出し | ja: この提案では配置されない候補: %1$d件<br>default: Not placed by this proposal: %1$d | ja: この整理案では配置されない候補: %1$d件<br>default: 変更なし | 「提案」を正規用語の「整理案」にそろえ、どの案の集計かを明確にする。 | medium | yes：この案では未配置となる候補の件数を保持。配置操作を要求していない。 | 1文字増。見出しを通常・拡大文字で確認。 | 用語集：organization proposal／スタイルガイド「CTA、タイトル、状態」 |

### 08

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organizer_hub_title | REVISE | title | normal | 整理機能ハブ | ja: オーガナイザー<br>default: Organizer | ja: 整理機能<br>default: 変更なし | 通常画面では用語集が指定する「整理機能」を使い、利用目的が分かるタイトルにする。 | medium | yes：状態・整理案・材料を扱うハブの対象を保持。 | 短縮。 | 用語集：organizer／スタイルガイド「カタカナ・英語・技術語」 |

### 09

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organizer_strategy_title | REVISE | title | normal | 整理方法の設定画面 | ja: 整理ストラテジー<br>default: Organization strategy | ja: 整理方法<br>default: 変更なし | 選択する対象を一般的な日本語で示す。同じ設定の変更制限を伝える11の「整理方法」とも一致する。 | medium | yes：整理時の計画方法を選ぶ設定であることを保持。 | 短縮。 | スタイルガイド「カタカナ・英語・技術語」／rubric：terminology（strategy → 整理方法を用語集追記候補） |

### 10

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organization_strategy_tidy_v2_description | OK | summary | normal | 整理方法の選択行 | アプリを別のページへ移動せず、各ページのすき間を詰めます。ウィジェットも同じページ内で整えます。 | — | ページをまたがない制約と、ウィジェットも整える動作を分けて説明している。隣接するv1との違いも読み取れる。 | low | yes：ページ内での整理という範囲を保持。 | 変更なし | スタイルガイド「自然さ、簡潔さ、語順」／AI-register：N・Fの機械的な誤検出を回避 |

### 11

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organizer_strategy_frozen_operation_active | OK | warning | safety-critical | 整理方法設定の変更不可状態 | 整理操作を実行中のため、中断してから整理方法を変更してください。 | — | 変更できない理由と、先に必要な中断操作を一文で示している。 | high | yes：整理の実行中は変更できず、先に中断が必要という順序を保持。自動中断は示していない。 | 変更なし | スタイルガイド「安全、失敗、復旧」 |

### 12

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| exchange_capability_flow | OK | summary | safety-critical | 外部AI連携の導入説明 | 相談はChat[sealed]。依頼文は1回送り、AIからの質問 (2〜4問) に答えて整理方針を確認すると、最終案を1回だけ受け取れます。会話の内容がNunuLauncherに送信されることはありません。 | — | 操作の順序と回数、会話の場所、プライバシー境界が具体的。必要な説明量であり、長さだけを理由に削らない。 | high | yes：外部AI内での相談、依頼1回、質問2〜4問、方針確認、最終案1回、会話内容をNunuLauncherへ送信しない保証を保持。 | 変更なし | スタイルガイド「安全、失敗、復旧」／AI-register：安全意味優先・単独の文末反復を誤検出しない |

### 13

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| exchange_discard_confirm_body | OK | dialog | safety-critical | 依頼の破棄確認ダイアログ | 破棄するとこの依頼は無効になり、AIの回答を取り込めなくなります。この操作は取り消せません。 | — | 破棄の対象、回答を取り込めなくなる結果、取り消せない制約を明示している。 | high | yes：依頼の無効化、以後の回答取り込み不可、不可逆性をすべて保持。 | 変更なし | スタイルガイド「安全、失敗、復旧」 |

### 14

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| exchange_status_clipboard_empty | OK | error | safety-critical | 回答読み込み画面の失敗表示 | クリップボードが空か、読み取れませんでした。AIアプリで回答をコピーし直し、このアプリに戻ってからもう一度読み込んでください。うまくいかない場合はファイル読み込みか手動貼り付けをお使いください。 | — | 原因候補、再試行の順序、失敗が続く場合の代替手段がそろっている。「お使いください」だけで過剰敬語とは判定しない。 | high | yes：空または読み取り失敗の両方、再コピー→帰還→再読み込み、ファイル・手動貼り付けの代替経路を保持。成功は保証していない。 | 変更なし | スタイルガイド「安全、失敗、復旧」／AI-register：単一表現だけでREVISEにしない |

### 15

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| exchange_import_from_clipboard | OK | action | normal | 回答読み込み画面 | クリップボードから読み込む | — | 入力元とタップ後の操作が分かる。ファイル読み込みとの区別も明確。 | low | yes：クリップボードの回答を読み込む操作を保持。 | 変更なし | スタイルガイド「CTA、タイトル、状態」／rubric：CTA predictability |

### 16

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| exchange_ai_consultation_toggle_label | REVISE | title | normal | 外部AI連携設定のトグルラベル | ja: missing<br>default: AI consultation | ja: AIへの相談<br>default: 変更なし | トグルの対象を短い名詞句で示す。相談中の適用制限は隣接説明に任せ、ラベルに未提示の動作を追加しない。 | medium | yes：AI相談モードの設定という意味を保持。自動送信・自動適用を示唆しない。 | 日本語表示が新規。トグル行を通常・拡大文字で確認。 | スタイルガイド「CTA、タイトル、状態」／rubric：context fit |

### 17

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_rejection_invalid_category_provenance | REVISE | diagnostic | diagnostic | 手動整理の拒否コード一覧 | ja: missing<br>default: Category source not allowed: %1$d | ja: カテゴリの出所が許可されていません: %1$d<br>default: 変更なし | カテゴリ自体の禁止ではなく、出所が許可されていない拒否理由を表す。日本語欠落のため指定どおりREVISEとする。 | low | yes：カテゴリのprovenanceに対する拒否を保持。%1$dは拒否コードIDであり、件数の助数詞を付けない。 | 日本語表示が新規。診断行を通常・拡大文字で確認。 | スタイルガイド「カタカナ・英語・技術語」「Android resource 互換性」／rubric：technical precision |

### 18

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organizer_diagnostics_recent_import_failure | TECHNICAL_ONLY | diagnostic | diagnostic | 診断画面の行ラベル | 直近の取り込み失敗 | — | 最新の取り込み失敗を参照する診断ラベルとして、短く正確。通常画面の操作ラベルへ統一する必要はない。 | low | yes：最も最近の失敗という対象を保持。 | 変更なし | スタイルガイド「Surface class：diagnostics / support / developer-facing」 |

### 19

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organizer_lock_screen_item_state_description | PRODUCT_DECISION | a11y | accessibility | 配置ロック確認リストの読み上げ | %1$s、%2$s | unknown（展開値確認後に決定） | 完全文に整えるには、%2$sが「ロック中」のような名詞句か、既に文になっているかの確認が必要。単純に「です」を足すと不自然になる可能性がある。担当ownerが全ロック状態の実値と読み上げ例を提示し、読み上げ文型を確定する。 | medium | unknown：現行のラベル＋状態の順序は把握できるが、実際の状態文字列が未提示。推測による変更は行わない。 | unknown。文型確定後にTalkBackで確認。 | スタイルガイド「accessibility-only text」「レビュー時の停止条件」／rubric：a11y read-aloud |

### 20

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| exchange_disclosure_hint | REVISE | summary | safety-critical | 送信前の説明 | ja: この内容を任意のAIアプリへ送信します。送信方法を選ぶまで何も送信されません。<br>default: Send this exact text to the AI app of your choice. Nothing is sent until you choose a send method. | ja: この内容をそのまま、使いたいAIアプリへ送信してください。送信方法を選ぶまで何も送信されません。<br>default: 変更なし | アプリの自動動作にも読める「送信します」を、利用者への操作案内にする。原文の「内容をそのまま送る」も明示する。 | high | yes：利用者が内容と送信先を選んで送る流れ、送信方法を選ぶまで何も送信しない保証を保持。選択直後の送信や自動送信は約束しない。 | 長文化。説明領域を通常・拡大文字で確認。 | スタイルガイド「settings / help descriptive copy」「安全、失敗、復旧」／rubric：行為者の明確さ |

### 21

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_replanning | REVISE | progress | normal | アイテム除外変更後のプレビュー | ja: 提案を再計算中<br>default: Replanning the proposal | ja: 整理案を再計算中<br>default: 変更なし | 再計算する対象を用語集の「整理案」にそろえる。進行中の短い状態表示は維持する。 | medium | yes：整理案の再計算中であることを保持。適用中・完了とは表現しない。 | 1文字増。進行表示を通常・拡大文字で確認。 | 用語集：organization proposal／スタイルガイド「CTA、タイトル、状態」 |

### 22

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| organizer_hub_proposal_row | REVISE | summary | normal | 整理機能ハブの状態行 | ja: 取り込み済みの提案があります<br>default: You have an imported proposal | ja: 取り込み済みの整理案があります<br>default: 変更なし | 確認対象を用語集の「整理案」で示し、プレビューや再計算表示との呼び方を統一する。 | medium | yes：取り込んだ案が存在する状態を保持。適用済みとは表現しない。 | 1文字増。行表示を通常・拡大文字で確認。 | 用語集：organization proposal／rubric：terminology・consistency |

### 23

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| homeedit_undo_error_no_space | REVISE | error | safety-critical | Undo失敗表示 | ja: 元に戻せません: 元の位置に空きがありません。<br>default: Can’t undo: no space at the original spot. | ja: 元の位置に空きがないため、元に戻せません。<br>default: 変更なし | 空き不足とUndo拒否の因果関係を、読みやすい一文で伝える。 | high | yes：元の位置の空き不足によりUndoを拒否する意味を保持。代替位置への移動や復元成功を示さず、適用済み編集が残る挙動と整合する。 | 短縮。 | スタイルガイド「安全、失敗、復旧」「自然さ、簡潔さ、語順」 |

### 24

| resource_name | disposition | ui_role | user_class | surface | current | proposed | reason | severity | meaning_preserved | layout_risk | rule |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| manual_organization_diagram_widget | OK | title | normal | 配置変更前後の図の凡例 | ウィジェット | — | Androidで定着した対象名であり、図の凡例として短く識別できる。 | low | yes：ウィジェット種別の凡例を保持。 | 変更なし | スタイルガイド「カタカナ・英語・技術語」 |

### Run footer

- **対象・件数:** 固定入力01–24の全24件。OK 9／REVISE 13／PRODUCT_DECISION 1／TECHNICAL_ONLY 1。スキップなし。
- **Base SHA:** `25f97aef4d353566b442a4c40552c49e77fbd9c0`。HEADは固定入力のpinと一致。
- **Inventory regeneration command:** `unknown`。指定ファイルに記載がないため、再生成せず固定24件を使用。
- **実行情報:** [sealed]／[identity redacted]、[identity redacted]。正確なモデルID・runtime session IDは未公開。日付：2026-10-15 JST。実行識別ラベル：`issue-324-bakeoff-2026-10-15-root`。入力のfamily rule上、[sealed]。
- **Resource契約:** 提案はresource名とplaceholderの数・型・順序を保持。plural categories・escaping・translatableは固定入力に詳細がなく、変更を提案していない。レンダリング確認は未実施。
- **使用参照:** `SKILL.md`、`review-rubric.md`、`input-schema.md`、`output-schema.md`、`ai-register-tells.md`、`ja-style-guide.md`、`ja-glossary.tsv`、`固定入力`。指定順で参照。Issues・specs・plansは未参照。
- **終了点検:** ファイル変更・プロセス起動・ポート使用・worktree作成なし。`git status --short`には既存の未追跡項目4件（`.agents/skills/product-language-review/`、`.[identity redacted]/agents/product-language-reviewer.toml`、`.[identity redacted]/`、固定入力ファイル）。削除せず保持。両`pgrep`はプロセス一覧取得不可、`adb devices`はコマンド未導入のため確認不能。使用ポートがないため`lsof`対象なし。
- **既存worktree一覧:** 以下11件を確認。今回作成したものはなく、削除していない。
## Candidate candidate-E34A output
# Candidate [sealed] review (identity withheld)

Fixed input: `docs/assessment/evidence/issue-324-bakeoff-context.md` (24 units, pinned to main `25f97aef4d`; audited tree HEAD matches). Canon: `docs/localization/ja-style-guide.md`, `docs/localization/ja-glossary.tsv`, skill references. Fields follow `references/output-schema.md` order. Safety-class units carry an explicit meaning-preservation note.

---

## 01 `homeedit_menu_move_to_page`

| field | value |
|---|---|
| resource_name | `homeedit_menu_move_to_page` |
| disposition | OK |
| ui_role | menu |
| user_class | normal |
| surface | per-item home edit bottom-sheet menu |
| current | ページへ移動… / `Move to page…` |
| proposed | — |
| reason | 対象（ページ）を含む短い動作句で、タップ後にピッカーが開く `…` 慣行とも整合する。隣接メニュー（フォルダに追加 / ホームから削除）と同じ簡潔さで、変更の必要がない。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「CTA、タイトル、状態」（動作句＋目的語）; rubric CTA predictability |

## 02 `homeedit_undo_label`

| field | value |
|---|---|
| resource_name | `homeedit_undo_label` |
| disposition | REVISE |
| ui_role | toast |
| user_class | normal |
| surface | edit confirmation snackbar (status + undo action chip) |
| current | 編集を適用しました / `Edit applied` |
| proposed | 編集を適用しました。 |
| reason | undo action chip を伴う完了通知文。canon の句読点表は progress/toast の完了文に `。` を付けることを基本とする。完了文としての区切りが視覚と読み上げの両方で明確になる。 |
| severity | low |
| meaning_preserved | yes — punctuation only; 適用済みという状態表示と undo 入口の関係は不変 |
| layout_risk | none（1文字増） |
| rule | style-guide「句読点と末尾」progress/toast 行 |

## 03 `homeedit_undo_error_stale`

| field | value |
|---|---|
| resource_name | `homeedit_undo_error_stale` |
| disposition | REVISE |
| ui_role | error |
| user_class | safety-critical |
| surface | undo failure snackbar/dialog (ADR-0013 undo contract) |
| current | 元に戻せません: アイコンがその後移動されています。 / `Can’t undo: the icon has moved since.` |
| proposed | 元に戻せません。編集後にアイコンが移動されています。 |
| reason | 半角コロン+スペースは英語構文の残骸で、日本語のエラー文としては文に分ける方が自然であり、読み上げでも文境界が明確になる。「その後」は基準時（いつの後か）が不明瞭なので「編集後」で特定する。 |
| severity | medium |
| meaning_preserved | yes — 拒否（undo 不可）と原因（編集後にアイコンが移動済み）の両構造をそのまま保持する。「適用済みの編集は残る」は source にも記述がなく追加しない（parity）。ADR-0013 の拒否契約（undo しない・適用は維持）は変化なし |
| layout_risk | low（ほぼ同長、snackbar/dialog で拡大フォント確認を推奨） |
| rule | ai-register-tells S「記号の残骸」（`:` + space）; style-guide「句読点と末尾」dialog/error 行 |

## 04 `manual_organization_exclude_action`

| field | value |
|---|---|
| resource_name | `manual_organization_exclude_action` |
| disposition | REVISE |
| ui_role | action |
| user_class | normal |
| surface | manual organization before/after preview, per-item row action |
| current | 提案から外す / `Exclude` |
| proposed | 整理案から外す |
| reason | glossary は organization proposal → 整理案 とし、CTA では「整理案を確認」形を指定する。同一プレビューFlow（07/21）と hub の「整理案の確認」と語を揃え、外す対象が何かが動詞だけの状態より予測しやすくなる。 |
| severity | low |
| meaning_preserved | yes — 同一の対象（organization proposal）と同一の動作（除外して再計画）を指す用語の統一のみ |
| layout_risk | low（+1文字。対になる include action `提案に含める` も `整理案に含める` へ同時変更すること） |
| rule | glossary `organization proposal` → 整理案; rubric terminology / redundancy-consistency |

## 05 `manual_organization_exclusion_blocked_structural`

| field | value |
|---|---|
| resource_name | `manual_organization_exclusion_blocked_structural` |
| disposition | REVISE |
| ui_role | warning |
| user_class | safety-critical |
| surface | preview exclusion refusal (disabled Exclude control の説明) |
| current | 除外できません: 再計画が自動的に決定する行です / `not excludable: replanning decides this row automatically` |
| proposed | 除外できません。この行は再計算で自動的に決まります。 |
| reason | 半角コロンを文分割に置換。「再計画」は 21 の「再計算」と不整合（同Flow内の用語統一）。「再計画が自動的に決定する行です」の関係節構造と硬い漢語（決定する）を、平明な能動文に組み替える。 |
| severity | medium |
| meaning_preserved | yes — 除外不可の拒否と理由（再計算で自動的に決まる行）を保持し、ブロックの条件・範囲は不変。「構造的に決まる行（ページ/Dock 枠）」という具体例は source に無いため追加しない |
| layout_risk | low（ほぼ同長） |
| rule | ai-register-tells S「記号の残骸」; style-guide「カタカナ・英語・技術語」（硬い漢語の平明化）; rubric redundancy-consistency（unit 21 再計算） |

## 06 `manual_organization_warning_duplicate_launch_target`

| field | value |
|---|---|
| resource_name | `manual_organization_warning_duplicate_launch_target` |
| disposition | REVISE |
| ui_role | warning |
| user_class | safety-critical |
| surface | preview warning list row |
| current | 重複のため移動させなかった配置: %1$d件 / `Duplicates of another icon were kept in place: %1$d` |
| proposed | 他のアイコンと重複するため、移動させなかった配置：%1$d件 |
| reason | 「重複のため」は何と重複したのかが分からない抽象表現。default の "Duplicates of another icon" にある「他のアイコンと」を復元し、警告の理由を具体化する。半角コロン+スペースを全角コロンに置換。 |
| severity | medium |
| meaning_preserved | yes — 「意図的に移動させなかった（勝手に動かしていない）」という #451 の保証は「移動させなかった」のまま、件数表示と placeholder（%1$d・件）も不変 |
| layout_risk | medium（+約8文字。プレビューの警告行は幅が制約されるため、通常+拡大フォントで折返し確認を推奨） |
| rule | ai-register-tells S「記号の残骸」; style-guide「安全、失敗、復旧」（警告意味を削らず具体化）; rubric naturalness |

## 07 `manual_organization_unplaced_candidates_heading`

| field | value |
|---|---|
| resource_name | `manual_organization_unplaced_candidates_heading` |
| disposition | REVISE |
| ui_role | summary |
| user_class | normal |
| surface | preview summary section heading |
| current | この提案では配置されない候補: %1$d件 / `Not placed by this proposal: %1$d` |
| proposed | この整理案では配置されない候補：%1$d件 |
| reason | 見出し文自体は自然。「提案」→「整理案」への用語統一（04/21/22 と同一の協調変更）と、半角コロン+スペースの全角化のみ。 |
| severity | low |
| meaning_preserved | yes — 見出しの述べる状態（この案では配置しない候補数）は不変 |
| layout_risk | low（+1文字） |
| rule | glossary `organization proposal` → 整理案; ai-register-tells S「記号の残骸」 |

## 08 `organizer_hub_title`

| field | value |
|---|---|
| resource_name | `organizer_hub_title` |
| disposition | REVISE |
| ui_role | title |
| user_class | normal |
| surface | organizer hub screen title |
| current | オーガナイザー / `Organizer` |
| proposed | 整理機能 |
| reason | glossary は organizer → 整理機能とし、「オーガナイザー」は診断/サポート表面のみ許容。hub は通常UIの代表タイトルなので利用者語に置き換える。隣接 summary（整理の状態、整理案の確認、材料を1か所で扱えます）とも語が揃う。default 英語 "Organizer" は feature 名として問題なく、修正対象は ja 側のみ。 |
| severity | medium |
| meaning_preserved | yes — 同一機能の名称を glossary の利用者語へ置換するだけで、画面の機能・導線は不変 |
| layout_risk | low（文字数は減る） |
| rule | glossary `organizer` → 整理機能; style-guide「カタカナ・英語・技術語」 |

## 09 `organizer_strategy_title`

| field | value |
|---|---|
| resource_name | `organizer_strategy_title` |
| disposition | REVISE |
| ui_role | title |
| user_class | normal |
| surface | strategy settings title |
| current | 整理ストラテジー / `Organization strategy` |
| proposed | 整理方法 |
| reason | 「ストラテジー」は英語名詞のカタカナ化で canon が禁止する形。同じ設定概念を述べる 11 の既存 ja がすでに「整理方法」であり、ここを揃えることでFlow内不整合が解消する。隣接 summary の「方針」は説明語として許容されるが、同一PRで「方法」への統一を推奨する。 |
| severity | medium |
| meaning_preserved | yes — 設定が選ぶのは同じもの（planning strategy）で、名称の日本語化のみ |
| layout_risk | low（文字数は減る） |
| rule | style-guide「カタカナ・英語・技術語」（カタカナ化しない）; rubric redundancy-consistency（unit 11 整理方法） |

## 10 `organization_strategy_tidy_v2_description`

| field | value |
|---|---|
| resource_name | `organization_strategy_tidy_v2_description` |
| disposition | REVISE |
| ui_role | summary |
| user_class | normal |
| surface | strategy picker row description |
| current | アプリを別のページへ移動せず、各ページのすき間を詰めます。ウィジェットも同じページ内で整えます。 / `Closes gaps on each screen without moving apps to other screens. Widgets are tidied within their screen as well.` |
| proposed | アプリを別のページへ移動せずに、各ページの隙間を閉じます。ウィジェットも同じページ内で整えます。 |
| reason | 兄弟行 tidy v1（「各ページの隙間を閉じます」）との表記揺れ（すき間/隙間）と動詞揺れ（詰めます/閉じます）を解消する。同一画面の radio 説明で同じ挙動に同じ語を使うことで、v1 と v2 の実差分（ウィジェットも整えるか）だけが読み取れるようになる。 |
| severity | medium |
| meaning_preserved | yes — ページ間移動なし・各ページの隙間を閉じる・ウィジェットもページ内で整える、の全挙動を保持 |
| layout_risk | low（ほぼ同長） |
| rule | rubric redundancy-consistency; ai-register-tells 前提の表記統一（N granularity・兄弟行との整合） |

## 11 `organizer_strategy_frozen_operation_active`

| field | value |
|---|---|
| resource_name | `organizer_strategy_frozen_operation_active` |
| disposition | OK |
| ui_role | warning |
| user_class | safety-critical |
| surface | strategy settings blocked state |
| current | 整理操作を実行中のため、中断してから整理方法を変更してください。 / `An organize operation is in progress. Interrupt it before changing the strategy.` |
| proposed | — |
| reason | 「中断してから…変更してください」が先に中断するという順序制約を正確に保持し、阻止状態の理由も自然な日本語で述べられている。警告文末の `。` も canon 準拠。改善点なし。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「安全、失敗、復旧」; rubric safety / naturalness |

## 12 `exchange_capability_flow`

| field | value |
|---|---|
| resource_name | `exchange_capability_flow` |
| disposition | REVISE |
| ui_role | summary |
| user_class | safety-critical |
| surface | exchange capability onboarding explanation |
| current | 相談はChat[sealed]。依頼文は1回送り、AIからの質問 (2〜4問) に答えて整理方針を確認すると、最終案を1回だけ受け取れます。会話の内容がNunuLauncherに送信されることはありません。 / `The conversation happens inside your external AI app (such as Chat[sealed]). Send the request once, answer a few short questions (2–4), confirm the plan, and receive one final proposal. Your conversation is never sent to NunuLauncher.` |
| proposed | 相談はChat[sealed]。依頼文は1回送り、AIからの質問（2〜4問）に答えて整理方針を確認すると、最終案を1回だけ受け取れます。会話の内容がNunuLauncherに送信されることはありません。 |
| reason | 半角括弧+前後スペース `(2〜4問)` は翻訳由来のタイポグラフィで、日本語では全角括弧（2〜4問）が標準的。「等」→「など」は書き言葉語気が強い regist を通常UIへ寄せる。本文の構成・情報量は変更しない。 |
| severity | low |
| meaning_preserved | yes — privacy 保証文（会話の内容がNunuLauncherに送信されることはありません）は一字も変更しない。フロー各段階（依頼文1回送信→2〜4問に回答→整理方針を確認→最終案を1回受け取り）も全て不変 |
| layout_risk | low（文字数ほぼ同数） |
| rule | ai-register-tells S「記号の残骸」; style-guide「句読点と末尾」・a11y 読み上げ自然性 |

## 13 `exchange_discard_confirm_body`

| field | value |
|---|---|
| resource_name | `exchange_discard_confirm_body` |
| disposition | OK |
| ui_role | dialog |
| user_class | safety-critical |
| surface | discard confirmation dialog |
| current | 破棄するとこの依頼は無効になり、AIの回答を取り込めなくなります。この操作は取り消せません。 / `Discarding invalidates this request; the AI's reply can no longer be imported. This cannot be undone.` |
| proposed | — |
| reason | 破棄の効果（依頼の無効化・回答取り込み不可）と不可逆性（取り消せません）が別の文として正確に訳し分けられており、canon の安全文の訳し分け規則に準拠。自然的で削れる要素もない。Flow内の 取り込み/読み込み 揺れは run footer の用語提案として扱う。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「安全、失敗、復旧」（状態の訳し分け）; rubric safety |

## 14 `exchange_status_clipboard_empty`

| field | value |
|---|---|
| resource_name | `exchange_status_clipboard_empty` |
| disposition | OK |
| ui_role | error |
| user_class | safety-critical |
| surface | import input UI failure with recovery guidance |
| current | クリップボードが空か、読み取れませんでした。AIアプリで回答をコピーし直し、このアプリに戻ってからもう一度読み込んでください。うまくいかない場合はファイル読み込みか手動貼り付けをお使いください。 / `The clipboard is empty or could not be read. Copy the reply in the AI app again, return to this app, and import once more. If it still fails, use the file import or paste the text manually.` |
| proposed | — |
| reason | 失敗内容と回復手順（コピーし直し→このアプリに戻る→再読み込み）と代替手段（ファイル読み込み/手動貼り付け）の全体が保たれ、文も過不足なく自然。回復意味を削る変更は不要・禁止。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「安全、失敗、復旧」; rubric safety / brevity-without-loss |

## 15 `exchange_import_from_clipboard`

| field | value |
|---|---|
| resource_name | `exchange_import_from_clipboard` |
| disposition | OK |
| ui_role | action |
| user_class | normal |
| surface | import input UI action |
| current | クリップボードから読み込む / `Import from clipboard` |
| proposed | — |
| reason | 出所（クリップボード）と動作（読み込む）がそのまま予測できるCTA。隣接するファイル読み込みとも対になる簡潔さ。Flow用語（読み込み/取り込み）の統一は footer 参照。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | rubric CTA predictability; style-guide「CTA、タイトル、状態」 |

## 16 `exchange_ai_consultation_toggle_label`

| field | value |
|---|---|
| resource_name | `exchange_ai_consultation_toggle_label` |
| disposition | REVISE |
| ui_role | title |
| user_class | normal |
| surface | exchange settings toggle label (ja resource missing) |
| current | *(ja なし — 英語フォールバック)* / `AI consultation` |
| proposed | AI相談 |
| reason | ja リソースが無く日本語環境で英語のまま表示される。トグルラベルは短い名詞句が適切。12 の「相談は…外部AIアプリ内で行われます」と同じ語を使い、このモードが外部AIアプリでの相談であることを指す。相談中の適用凍結という挙動は隣接 description が担うため、ラベルには載せない。 |
| severity | medium |
| meaning_preserved | yes — ラベルはモード名のみを指し、挙動の記述（freeze）は description 側に残る。トグルの on/off 意味は不変 |
| layout_risk | low（4文字。rows 幅の制約にも余裕） |
| rule | style-guide「CTA、タイトル、状態」（短い名詞句）; rubric terminology（unit 12 相談）; ja-style-guide「Android resource 互換性」（ja 追加は default と name・placeholder 整合を維持） |

## 17 `manual_organization_rejection_invalid_category_provenance`

| field | value |
|---|---|
| resource_name | `manual_organization_rejection_invalid_category_provenance` |
| disposition | REVISE |
| ui_role | diagnostic |
| user_class | diagnostic |
| surface | manual organization rejection code list row (ja resource missing) |
| current | *(ja なし — 英語フォールバック)* / `Category source not allowed: %1$d` |
| proposed | カテゴリの設定元が許可されていません：%1$d |
| reason | ja リソースが無いため追加する。診断表面では技術的精密さを優先してよい（canon の diagnostics class）。provenance＝カテゴリ値がどこから設定されたかを「設定元」とし、拒否理由とコードIDという行構造を default と同じ形で保持する。 |
| severity | low |
| meaning_preserved | yes — 拒否理由（カテゴリの設定元が許可されない）とコードID表示の意味・順序をそのまま写す。placeholder %1$d の数・型・順序は不変 |
| layout_risk | low（診断リスト行、折返し許容） |
| rule | style-guide「Surface class」diagnostics 行（技術的正確さ優先、TECHNICAL_ONLY 的語彙選択）; style-guide「Android resource 互換性」 |

## 18 `organizer_diagnostics_recent_import_failure`

| field | value |
|---|---|
| resource_name | `organizer_diagnostics_recent_import_failure` |
| disposition | OK |
| ui_role | diagnostic |
| user_class | diagnostic |
| surface | diagnostics screen row label |
| current | 直近の取り込み失敗 / `Recent import failure` |
| proposed | — |
| reason | 診断行ラベルとして短く、意味も正確。技術語の利用者語化は不要な表面。取り込み/読み込み のFlow内揺れは run footer の提案として扱う。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「Surface class」diagnostics 行 |

## 19 `organizer_lock_screen_item_state_description`

| field | value |
|---|---|
| resource_name | `organizer_lock_screen_item_state_description` |
| disposition | OK |
| ui_role | a11y |
| user_class | accessibility |
| surface | lock review list item, TalkBack state description |
| current | %1$s、%2$s / `%1$s, %2$s` |
| proposed | — |
| reason | format resource で日本語の読点による語順制御をしており、canon の読み上げ方針に準拠。「項目、ロック状態」として読み上げた際に自然な間が入る。placeholder 2件（%1$s, %2$s）の型・順序も正しく維持されている。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「accessibility-only text」・「Android resource 互換性」 |

## 20 `exchange_disclosure_hint`

| field | value |
|---|---|
| resource_name | `exchange_disclosure_hint` |
| disposition | REVISE |
| ui_role | summary |
| user_class | safety-critical |
| surface | pre-send disclosure hint (send consent) |
| current | この内容を任意のAIアプリへ送信します。送信方法を選ぶまで何も送信されません。 / `Send this exact text to the AI app of your choice. Nothing is sent until you choose a send method.` |
| proposed | このテキストをそのまま任意のAIアプリへ送信してください。送信方法を選ぶまで何も送信されません。 |
| reason | 現行の「送信します」は陳述文体で、利用者の操作指示なのかアプリ側の送信説明なのが読み分けにくい（文2で訂正されるが、同意を求める表面では一瞬の誤読余地を残す）。また "this exact text" の「そのまま（変更せず）送る」という要件が「この内容」では抜けている。祈使形と「そのまま」で指示文として明確にする。 |
| severity | medium |
| meaning_preserved | yes — 「送信方法を選ぶまで何も送信されません」という非自動送信の同意保証文は不改変。指示の対象（このテキスト）と範囲（手動送信）も不変で、自動送信の有無についての意味は変わらない |
| layout_risk | low（ほぼ同長） |
| rule | rubric CTA predictability / AI-register S（誰が何をするかの明示）; style-guide「表現の原則」 |

## 21 `manual_organization_replanning`

| field | value |
|---|---|
| resource_name | `manual_organization_replanning` |
| disposition | REVISE |
| ui_role | progress |
| user_class | normal |
| surface | preview after exclusion change, progress indicator |
| current | 提案を再計算中 / `Replanning the proposal` |
| proposed | 整理案を再計算中 |
| reason | 進行表現「〜中」として自然。変更は用語統一（04/07/22 と同一の協調変更）のみで、「再計算」はそのまま維持する（05 の提案はこの語に揃える）。 |
| severity | low |
| meaning_preserved | yes — 同じ対象（organization proposal）の再計算であることに変わりなし |
| layout_risk | low（+1文字） |
| rule | glossary `organization proposal` → 整理案; style-guide「CTA、タイトル、状態」（状態表示） |

## 22 `organizer_hub_proposal_row`

| field | value |
|---|---|
| resource_name | `organizer_hub_proposal_row` |
| disposition | REVISE |
| ui_role | result |
| user_class | normal |
| surface | organizer hub status row |
| current | 取り込み済みの提案があります / `You have an imported proposal` |
| proposed | 取り込み済みの整理案があります |
| reason | hub summary「整理案の確認」と語を揃える（04/07/21 と同一の協調変更）。状態（取り込み済み）と存在表明（があります）はそのまま保持する。default にない "pending review" の情報は追加しない。 |
| severity | low |
| meaning_preserved | yes — 同じ事実（取り込んだ提案が存在する）の表記統一のみ |
| layout_risk | low（+1文字） |
| rule | glossary `organization proposal` → 整理案; rubric redundancy-consistency（hub summary） |

## 23 `homeedit_undo_error_no_space`

| field | value |
|---|---|
| resource_name | `homeedit_undo_error_no_space` |
| disposition | REVISE |
| ui_role | error |
| user_class | safety-critical |
| surface | undo failure snackbar/dialog (ADR-0013) |
| current | 元に戻せません: 元の位置に空きがありません。 / `Can’t undo: no space at the original spot.` |
| proposed | 元に戻せません。元の位置に空きがありません。 |
| reason | 03 と同じ半角コロン+スペースの残骸を文分割に置換する。拒否と理由がそれぞれ独立した文になり、読み上げでも境界が明確になる。 |
| severity | medium |
| meaning_preserved | yes — 拒否（undo 不可）と原因（元の位置に空きがない）をそのまま保持。ADR-0013 の契約（undo しない・適用済み編集は維持）に変化なし |
| layout_risk | low（ほぼ同長） |
| rule | ai-register-tells S「記号の残骸」; style-guide「句読点と末尾」dialog/error 行 |

## 24 `manual_organization_diagram_widget`

| field | value |
|---|---|
| resource_name | `manual_organization_diagram_widget` |
| disposition | OK |
| ui_role | title |
| user_class | normal |
| surface | visual before/after diagram legend |
| current | ウィジェット / `Widget` |
| proposed | — |
| reason | 「ウィジェット」は canon が Android 利用者語として維持を定める用語。凡例ラベルとしても簡潔で適切。 |
| severity | n/a (OK) |
| meaning_preserved | n/a |
| layout_risk | none |
| rule | style-guide「カタカナ・英語・技術語」（既存利用者語の維持） |

---

## Run footer

- Base SHA of audited tree: `25f97aef4d353566b442a4c40552c49e77fbd9c0` (= evidence file pin `25f97aef4d`; verified `git rev-parse HEAD`).
- Inventory regeneration command: unknown — the fixed 24-unit inventory is `docs/assessment/evidence/issue-324-bakeoff-context.md`; no regeneration command was supplied to the reviewer and none was looked up (bake-off scope restriction).
- Reviewer runtime / model / provider / date / session: [identity redacted] subagent run; provider `[identity redacted]`; model `[identity redacted]`; date 2026-10-15; session id not exposed to the reviewer (recorded as unknown). Family eligibility and any candidate-to-worker relationship are deliberately NOT stated here; they are applied only at unseal time after the owner's scoring.
- Batches covered: single batch, units 01–24, all reviewed. Skip reasons: none.
- Disposition counts: OK 11 / REVISE 13 / PRODUCT_DECISION 0 / TECHNICAL_ONLY 0.
- Coordinated change sets (apply together, single worker pass):
  - Symbol/punctuation normalization: 03, 05, 06, 07, 12, 23 (half-width `: ` / half-width parens remnants).
  - 整理案 terminology alignment: 04, 07, 21, 22 — also align the sibling include action (`提案に含める` があれば `整理案に含める` へ) in the same change.
  - 整理方法 strategy term: 09 (recommend aligning `organizer_strategy_summary` 方針→方法 in the same PR; that resource is outside this unit set).
- Flow-level glossary proposals (for glossary addition, not per-unit rewrites):
  - import = 取り込み vs 読み込み is split across the exchange flow (13/18/22 取り込み, 14/15 読み込み). Pick one canonical user term (提案: 統一先を製品判断で決め、glossary に `import` 行を追加する) — recorded here rather than as piecemeal REVISE.
- References used: `.agents/skills/product-language-review/SKILL.md`; `references/review-rubric.md`; `references/input-schema.md`; `references/output-schema.md`; `references/ai-register-tells.md`; `docs/localization/ja-style-guide.md`; `docs/localization/ja-glossary.tsv`; `docs/assessment/evidence/issue-324-bakeoff-context.md`.
