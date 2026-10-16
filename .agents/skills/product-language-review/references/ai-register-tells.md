# AI register (AI 感) tells — detection checklist

Provenance: `docs/assessment/evidence/issue-324-ai-register-research.md` (2026-10-15) is the evidence canon (sources, retrieval dates, verification status). This file is the operating summary; when they conflict, the evidence file and the #161 canon win.

## Operating principles

1. **Tells are statistical editing triggers, not verdicts.** Never mark `REVISE` on a single tell occurrence alone. What justifies a finding is density (several tells on one screen) or repetition (the same formula across a flow).
2. **Counter-myth list (do not flag these by themselves).** 「これにより」「です。です。」sentence-final repetition、「最後に」「まさに」、use of 体言止め itself、sentence-initial repetition — all are common in human Japanese. Absence of 体言止め (zero across a flow) is the signal, not its use.
3. **Safety semantics always win.** Removing AI register must never shorten warnings, weaken recovery guidance, or drop constraints (canon precedence tier 1).
4. **Lexicon sweeps do not fix rhythm.** Prefer restructuring the sentence or cutting filler over synonym substitution, which just creates a new template.
5. **Granularity is part of every tell id.** Each tell id encodes exactly one granularity and is used only there:
   - `S` single-string — the occurrence itself is judgeable within the one string (lexical tells, boilerplate openers, hedging endings, symbol remnants, register mismatches). An `S` tell marks the occurrence; whether it warrants `REVISE` still follows principle 1.
   - `N` neighboring-copy — judgeable only against other visible copy on the same screen (density of `S` occurrences, duplicate wording, sibling-built triads). An `N` finding cites the sibling strings.
   - `F` flow-level — judgeable only across a flow (the same CTA formula or construction recurring on multiple screens of one flow).
   - `L` long-form — corpus signals for articles/docs (paragraph uniformity, burstiness, 表記揺れ perfection). **UI copy review never uses `L` tells. `N`/`F` evidence never justifies revising a single string in isolation — the proposal must name the sibling/flow strings it harmonizes with.**
6. **Unverified external signals are auxiliary.** Signals from the evidence file that were not directly verified (research-agent retrieval) inform judgment but are never normative rules.
7. **UI-role style rules and the #161 canon outrank this checklist** whenever they interact.

## Japanese tells

- `JA-S-01` 定型の前置き・構造宣言（「本機能では」「以下の通り」「〜についてご案内します」「順に」）— UI copy states the thing, not the document.
- `JA-S-02` 言い切らない文末（「〜と考えられます」「〜な可能性があります」単独で対処を示さない、「注意が必要です」で止める）。
- `JA-S-03` 名詞化・形式名詞の冗長さ（「〜することができます」→「できます」、「削除を行う」→「削除する」）。
- `JA-S-04` 過剰敬語（「ご〜いただけますようお願いいたします」→「〜してください」）。
- `JA-S-05` 抽象語・万能語（「さまざまな」「重要です」「効率的に」が具体に置き換え可能なとき）。
- `JA-S-06` 記号の残骸・濫用（`**`、`—`、全角 `/`、`：`+スペース、`※` の連打、未使用の `( )` 補足）。
- `JA-S-07` 硬い漢語優先の直訳調・無生物主語（「データは〜を示しています」）。
- `JA-N-01` 隣接 copy との重複（同じ修飾の反復、title と summary が同じ文）。
- `JA-N-02` 対句・三項の強迫（同画面の sibling 文字列がすべて同構成）。
- `JA-N-03` 同画面での `JA-S-*` 出現の密集（density — 単独では指摘しない）。
- `JA-F-01` 語尾・構成の均一性（フロー全体で「〜しました」「〜できます」のみ、全 status 行が同一テンプレート）。
- `JA-F-02` フロー全体での語彙テンプレート反復（同じ修飾句・同じ区切り方の再利用）。

## English tells

- `EN-S-01` AI lexicon occurrence within the string (delve, showcase, seamless, effortlessly, elevate, unleash, empower, leverage, robust, "in today's …"). Mark the occurrence; density is `EN-N-03`.
- `EN-S-02` contrastive negation occurrence ("not just X but Y", "it's not X — it's Y").
- `EN-S-03` summary scaffolding ("It's important to note", "Overall") and sycophantic openers in conversational UI.
- `EN-S-04` over-promising adjective in an empty state / CTA; excess politeness (please / please note) in errors.
- `EN-S-05` abstraction where the concrete object could be named ("Manage your preferences" → "Choose what we email you").
- `EN-N-01` rule-of-three built from sibling strings on the same screen.
- `EN-N-02` duplicated wording across siblings (title repeats the summary).
- `EN-N-03` density: several `EN-S-*` occurrences on one screen.
- `EN-F-01` uniform CTA formula across a flow; `EN-S-02` recurring on multiple screens of a flow.
- `EN-F-02` uniform sentence rhythm / missing contractions across a flow's status copy.
- Mechanics cross-check with the surface: sentence case, no trailing periods on labels — where the #161 canon's UI-role rules already govern Japanese, they govern; for Nunu-specific default English apply the same discipline.
