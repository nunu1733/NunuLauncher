# AI register (AI 感) tells — detection checklist

Provenance: `docs/assessment/evidence/issue-324-ai-register-research.md` (2026-10-15) is the evidence canon (sources, retrieval dates, verification status). This file is the operating summary; when they conflict, the evidence file and the #161 canon win.

## Operating principles

1. **Tells are statistical editing triggers, not verdicts.** Never mark `REVISE` on a single tell occurrence alone. What justifies a finding is density (several tells on one screen) or repetition (the same formula across a flow).
2. **Counter-myth list (do not flag these by themselves).** 「これにより」「です。です。」sentence-final repetition、「最後に」「まさに」、use of 体言止め itself、sentence-initial repetition — all are common in human Japanese. Absence of 体言止め (zero across a flow) is the signal, not its use.
3. **Safety semantics always win.** Removing AI register must never shorten warnings, weaken recovery guidance, or drop constraints (canon precedence tier 1).
4. **Lexicon sweeps do not fix rhythm.** Prefer restructuring the sentence or cutting filler over synonym substitution, which just creates a new template.
5. **Granularity is part of each tell.** Classify every tell at exactly one granularity and use it only there:
   - `S` single-string — judgeable within the one string (lexical tells, boilerplate openers, hedging endings, symbol remnants, 名詞構文 like 「〜することができます」, register mismatches like 「本機能では」/「以下の通り」).
   - `N` neighboring-copy — judgeable against other visible copy on the same screen (duplicate wording, uniform sentence endings, triads built from sibling strings).
   - `F` flow-level — judgeable only across a flow (identical CTA formula everywhere, uniform construction of every status line).
   - `L` long-form — corpus signals for articles/docs (paragraph uniformity, burstiness, 表記揺れ perfection). **UI copy review never uses `L` tells, and `N`/`F` tells never justify a single-string `REVISE` on their own.**
6. **Unverified external signals are auxiliary.** Signals from the evidence file that were not directly verified (research-agent retrieval) inform judgment but are never normative rules.
7. **UI-role style rules and the #161 canon outrank this checklist** whenever they interact.

## Japanese tells

- `S` 定型の前置き・構造宣言（「本機能では」「以下の通り」「〜についてご案内します」「順に」）— UI copy states the thing, not the document.
- `S` 言い切らない文末（「〜と考えられます」「〜な可能性があります」単独で対処を示さない、「注意が必要です」で止める）。
- `S` 名詞化・形式名詞の冗長さ（「〜することができます」→「できます」、「削除を行う」→「削除する」）。
- `S` 過剰敬語（「ご〜いただけますようお願いいたします」→「〜してください」）。
- `S` 抽象語・万能語（「さまざまな」「重要です」「効率的に」が具体に置き換え可能なとき）。
- `S` 記号の残骸・濫用（`**`、`—`、全角 `/`、`：`+スペース、`※` の連打、未使用の `( )` 補足）。
- `S` 硬い漢語優先の直訳調・無生物主語（「データは〜を示しています」）。
- `N` 隣接 copy との重複（同じ修飾の反復、title と summary が同じ文）。
- `N` 対句・三項の強迫（同画面の sibling 文字列がすべて同構成）。
- `F` 語尾・構成の均一性（フロー全体で「〜しました」「〜できます」のみ、全 status 行が同一テンプレート）。

## English tells

- `S` high-density AI lexicon in one string or screen: delve, showcase, seamless, effortlessly, elevate, unleash, empower, leverage, robust, "in today's …".
- `S` contrastive negation ("not just X but Y") — at most once per flow.
- `S` summary scaffolding ("It's important to note", "Overall") and sycophantic openers in conversational UI.
- `S` over-promising adjectives in empty states / CTAs; excess politeness (please/please note) in errors.
- `N` rule-of-three built from sibling strings; abstraction where the concrete object could be named ("Manage your preferences" → "Choose what we email you").
- `F` uniform CTA formula and uniform sentence rhythm across a flow; missing contractions everywhere.
- Mechanics cross-check with the surface: sentence case, no trailing periods on labels — where the #161 canon's UI-role rules already govern Japanese, they govern; for Nunu-specific default English apply the same discipline.
