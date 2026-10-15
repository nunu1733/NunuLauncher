# Assessment evidence: Issue #324 — AI register（AI 感）の検出・除去に関する Web 調査

> Status: research complete（Phase 1 調査成果物。spec AC-324-15 の入力）
> Research date: 2026-10-15
> Method: 英語・日本語それぞれの観点で並列実行した Web 調査（検索 + 本文取得）を、準備 session が統合・重複排除した。出典 URL は調査日時点の取得結果。
> Verification: 一部の主要出典（SmartHR / Microsoft）は準備 session が本文を直接取得して内容を確認した（本文中に ✔ 直接確認 と記す）。それ以外は調査 agent の取得結果として記録する。
> Scope: user-visible UI copy / microcopy から生成文体特有の違和感（AI 感）を検出・除去するための手法。Product Language Reviewer の評価軸（AC-324-15）と skill reference（`ai-register-tells.md`）の根拠。
> Out of scope: reviewer model の選定（spec D1 bake-off）、#161 の style guide / glossary が既に定める規則そのもの、runtime 機構。

## 1. 前提: tell は統計的傾向であり機械的禁止リストではない

すべての調査源に共通する注意:

- 個々の tell 語・構文は孤立すれば誤りではなく、問題は**密度と反復**である（同一画面・同一フローでの密集）。
- 人間の AI 文言検出精度は「コイン投げと同程度」とする報告もあり（Towards Data Science 2026-08-05）、tell は verdict ではなく **editing trigger** として使う。
- 禁止語リストを運用すると語彙だけ置き換わりリズムの均質さが残る、また lint 通過自体が目的化して新たな定型が生まれる（Goodhart の法則）という運用上の知見が複数源で一致している。最終判断は人間（owner）の通読を経る。

## 2. 英語 UI copy の AI tell

### 2.1 語彙（過剰出現語）

研究ベースの excess-vocabulary（ChatGPT 普及後に急増した語群。Kobak et al. は 319 語を特定）:

- 学術系上位語: delve(s), showcasing, underscores, potential, findings, crucial, intricate, meticulously, pivotal, realm, notably, comprehensive, enhancing, exhibited, insights, particularly, additionally, across, within
- 識別力の高い一般語（Kobak et al.）: across, additionally, comprehensive, crucial, enhancing, exhibited, insights, notably, particularly, within
- 製品・マーケティング系: elevate, seamless, unleash, empower, unlock, supercharge, effortlessly, hassle-free, robust, leverage, "navigate the world of…", journey, tapestry, game-changer, ever-evolving, "in today's fast-paced world", "in the digital age"

Nuance: 「語そのものが誤り」ではなく「1 段落に 3–4 個の密度・ロボット的な反復」が tell。

### 2.2 構文・構造

- **対比否定構文**: "not just X but Y" / "it's not X — it's Y"。最も頻繁に指摘される構文 tell。対処: 1 画面〜1 フローに高々 1 回。
- **三項並列（rule of three）の強迫**: 3 個の形容詞・3 項目リストを至る所で使う。
- **均質な文リズム**: 同長の短文が並ぶ（burstiness 欠如）。
- **要約足場**: "In summary" / "Overall" / "It's important to note" / "It's worth noting" / Additionally-Moreover-Furthermore 連鎖。
- **完璧な均質性**: 実際の human writing は一貫しない。全画面で同じ CTA 式・同じ構成は機械的。

### 2.3 句読法・メカニクス

- em dash の濫用（「AI 生成文の代名詞」と広く指摘）。ただし 2026-07 の Economist 分析は「em dash より句読法 profile 全体の異常（comma 欠落・run-on 等）の方が信頼できる指標」と反転報告している。
- Title Case の見出し（主要 style guide はすべて sentence case を要求）。
- 縮約形の欠如（cannot / do not / you will）。
- 感嘆符・emoji の過剰。

### 2.4 UI copy 特有の tone tell

- 空状態・CTA での過剰な約束（"Experience seamless productivity!"）。
- 過剰な丁寧さ: please / kindly / thank you / 検証 error ごとの謝罪（GOV.UK は明文で禁止）。
- 抽象化: "Manage your preferences"（具体を言えば "Choose what we email you"）、"the following options"（GitHub は "the following" 禁止）。
- 名詞化・受動態: utilize / facilitate / "is being processed"。
- 会話 UI の追従語: "Great question!" / "Certainly!" / "I'd be happy to help…"。

### 2.5 対抗する style guide 原則（英語）

| Guide | AI tell へ対抗する規則 |
|---|---|
| Microsoft Writing Style Guide（Top 10 tips。✔ 直接確認、ms.date 2026-07-02） | "bigger ideas, fewer words"（例: "Ready to buy? Contact us."）、声に出して読む、縮約形、sentence-style caps、弱い書き出し（"you can" / "there is"）の編集削除、error は "Invalid ID" ではなく具体的な形式例示へ。 |
| Apple HIG — Writing | "you"/"we" の会話的使用、縮約形、positive tone、過剰な謝罪回避、要点先行（"Map View" でなく "Map"）、emoji は稀に名詞として。 |
| Google Material writing（M3 content design style guide） | "Be humble"（自慢・過剰約束をしない）、実装詳細・caveat の省略、positive framing（"must be less than 25" でなく "Use 24 characters or fewer"）、導入文の省略（"Would you like to save changes?" でなく "Save changes?"）、常用語優先、感嘆符なし・label の period/colon なし、要素型でなく label を参照（"the Continue button" でなく "Continue"）。 |
| GOV.UK — Writing for user interfaces | 1 文 1 要点・重要語を先頭に、error は率直（"Wrong password"）、please/sorry/thank you/please note 禁止、error で冗談禁止、sentence case。 |
| Mailchimp voice and tone | plainspoken（fluffy metaphor と cheap emotional appeal の禁止）、hyperbole 排除、明確さ > 面白さ、無理なユーモアはなしより悪い。 |
| Atlassian writing style | 縮約形、active voice、UI copy での感嘆符回避、e.g./i.e./etc. 回避（localization 非友好）、button の article 省略（"Create password"）、現在形。 |
| GitHub docs style guide | "the following"/"these" を使う非特定文の回避、地域 idiom 回避、明確さ > 文法、spell out（repo でなく Repository）。 |
| NN/g | genAI 出力は concise・scannable・inverted-pyramid・平易な言葉で（fluff と chattiness への不満が実測）、microcopy は Clear > Concise > Character、単一 tone 形容詞（"delightful" 等）は cartoony な出力を生むため複数の nuance ある記述詞 + 例文 + 複数案 + 人間編集を。 |

## 3. 日本語 UI copy の AI tell

### 3.1 語彙・表現

- 定型の前置き・構造宣言: 「本記事では〜解説します」「以下の3つの観点から」「順に見ていきましょう」「結論から言うと」「ステップ1: 」+半角スペース。
- 中身の薄い接続詞・決まり文句: 「さらに」「また」「そのため」「したがって」「一方で」「第一に・第二に」「まとめ/結論として」。
- 言い切らない文末（hedging）: 「〜と考えられます」「〜と言えるのではないでしょうか」「注意が必要です」「あまり推奨されません」「ケースバイケースです」。
- 抽象語・万能語・根拠のない評価語: 「〜が重要です」「さまざまな」「非常に有効です」。
- 冗長な形式名詞構文: 「〜することができます」（SmartHR が「することができる→できる」を明文ルール化。✔ 直接確認）、「削除を行う」型の名詞化。
- 大げさ・定番の比喩: 羅針盤/土台/車の両輪/潤滑油/パズルのピース/「つまずく」「切り分ける」。
- 英語直訳調: 名詞中心の硬い漢語優先、無生物主語（「データは〜を示しています」）、過剰な受動態。
- 過剰敬語: 「ご確認いただけますようお願いいたします」→「確認してください」（SmartHR「敬語を使いすぎない」。✔ 直接確認）。

### 3.2 記号・整形

- 消し忘れた `**`、`—` による強調、日本語を `"` で囲む、`:`+半角スペース、全角 `/` による並列、`( )` 補足の乱用、過剰な区切り線。
- 見出し・太字・箇条書きの過剰使用（地の文で語り切る場面が少ない）。
- 表記揺れが完璧にゼロ（人間の文章には揺れがあるため、不自然なほど完璧な一致もシグナル）。

### 3.3 リズム・構造

- 文長の均質性（burstiness 欠如。定量実測のある報告あり）。
- 段落構造の均質さ（全段落が同文数）。
- 語尾が単調・温度が一定（「〜です。〜です。」、重要箇所と補足が同テンション）。
- 三段構成の強迫: 予告→本論→「以上のように」再要約、最後は一般論・呼びかけで締める。
- **体言止めゼロ**が tell（人間の日本語文の約 6 割は体言止めを含むのに対し AI 406 本中 0% という定量報告あり。「体言止めの乱用」ではない点に注意）。

### 3.4 検証の結果「実は違う」と判明した通説（誤検出防止）

- 「これにより」は AI 特有 → **誤り**（人間側がむしろ多用の定量報告）。
- 「です。です。」語尾連続は AI 特有 → **誤り**（丁寧な日本語では人間でも自然に連続する）。
- 「最後に」「まさに」は AI 語 → **誤り**（該当 lint の誤検知 63% を占め除外された報告）。
- 体言止めの乱用が AI → **逆**（ゼロなのが AI）。
- 文頭反復は AI → **誤り**（人間の方が多い: 93% vs 41% の定量報告）。

### 3.5 対抗する style guide 原則（日本語）

| Guide | AI tell へ対抗する規則 |
|---|---|
| SmartHR Design System ライティングガイドライン（✔ 直接確認） | 9 原則: 冗長な日本語を避ける（「することができます」→「できます」、「削除を行う」→「削除する」）、正しい文法（「ご参考ください」は誤り）、表外読み不使用（「想い」→「思い」）、読みやすい漢字はひらがな、カタカナ語は漢字語への言い換えを先に検討、**敬語を使いすぎない**、同じことを複数表現で書かない、**格助詞をむやみに省略しない**（「項目名変更します」×「項目名を変更します」○）、一般的でない言葉を使わない。error は「事象・原因・対処」3 要素。 |
| JTF 日本語標準スタイルガイド（翻訳用）第 4.0 版（2026-07-25、CC BY 4.0） | 敬体/常体の統一、句読点の種類、常用漢字、送り仮名本則、カタカナ語末尾長音、全角/半角使い分け、記号・単位表記の統一（12 ルール）。 |
| WordPress.org 日本語チーム翻訳スタイルガイド | 能動態・簡潔、error の "Sorry, ..." は訳さず削除、「下さい」→「ください」等の表記統一、**ボタン label は語尾を省略・箇条書きは常体・見出しは体言止め or「する」**（UI パーツ別の文語尾規定）、カタカナ長音ルール（4 文字以下のみ「ー」付き）、中黒「・」は原則使わず「の」で区切る、和英間に半角スペース。 |
| wordrabbit UX ライティングガイド | error 4 原則: 文章で書く、ユーザーを非難しない（「間違っています」禁止）、プログラム目線でなくユーザー目線、何が起きたか+解決策。button は 1〜2 語の動詞（「保存を実施する」×「保存する」◎）。 |
| U-Site（NN/g 日本語版）error message guidelines | 人間が読める言葉、簡潔かつ正確、建設的助言、ユーザーに責任を負わせない（「無効」「不正」等の非難語回避）。 |
| WebA11y.jp | 「入力エラーです」は悪例。「項目名+理由+修正方法」を明示（WCAG 3.3.1/3.3.3）。 |

ベンダー公式の状況: Apple Style Guide 日本語版は存在するが日本語固有の表記規定を含まない。Microsoft 旧 MSDN 日本語スタイルガイドは消失。Google 翻訳者向け日本語スタイルガイド PDF は現在 404（2026-10 確認）。実務上は JTF ガイドが代替標準。

## 4. 公開されている検出 rubric・ツール

- Kobak et al., "Delving into ChatGPT usage in academic writing through excess vocabulary"（arXiv 2406.07016、Science Advances 2025-07）— word-frequency-delta 法、excess words の全データは公開。
- Liang et al., "Monitoring AI-Modified Content at Scale"（arXiv 2403.07183）— 5 つの syntactic/semantic predictor。
- Reinhart et al., "Ghost writers? Predictors of LLM usage"（arXiv 2410.08869）— 語彙・style・token entropy・punctuation・acronym/contraction の 5 predictor（LLM 文は punctuation・acronym 過剰、contraction 過少）。
- Towards Data Science "Is This Slop?"（2026-08-05）— 500 語あたりの対比構文数、human sample との比較、readability fingerprint 等の実用 rubric。
- PR Daily "4 reasons your writing accidentally sounds AI-generated"（2026-04-09）— 文構造反復・語反復・否定構文・em dash の 4 項 rubric。
- Atlassian "guidelines for writing with AI"（2026-06-25）— 人間が初稿/最終稿を主導、事実検証、"pay as much attention to what it removes as what it adds"。
- took.jp「AIっぽい文章チェッカー」（0–100 点スコア化 Web ツール）。
- zenn coji「natural-japanese」lint（人間 137 本 + AI 406 本で校正。検出項目: 低 burstiness、文長変動係数、段落構造均質、体言止めゼロ、「〜ではなく」反復、翻訳調品詞列）。
- オープンデータラボ AI 文章検出（予告・三段構成・「以上のように」・一般論の締めで判定。マークダウン記号非依存）。
- その他: もとやま「AIっぽい文章表現大全」、ソリシオ「直し方7原則」（名詞構文を動詞で言い切る/無生物主語を人に戻す/硬い漢語→和語/定型句削除/誇張禁止/記号整理/用語統一+具体追加）、conmark、Media Analytics Blog、writers-hub、munyoru、robitan。

## 5. Product Language Reviewer への統合運用原則（本 evidence から導出）

1. **tell は editing trigger であって verdict ではない。** 単独の語・構文の出現で機械的に `REVISE` にしない。密度（同一画面で複数 hit）・反復（フロー全体で同じ式）を理由に指摘する。
2. **誤検出リストを checklist に明記する。** 「これにより」「です。です。」連続、「最後に」「まさに」、体言止めの使用、文頭反復は人間にも普通であり、それ alone で指摘しない（§3.4）。
3. **語彙掃除より構造・リズムを見る。** 語彙 ban だけではリズムの均質さが残る。同一フロー内の文長・語尾・構成の均一性、対句・三項並列の強迫を画面単位で評価する。
4. **UI レジスタとの適合を問う。** 解説調（「本機能では」「〜することが可能です」「以下の通り」）・文書調の前置き・要約足場は、UI copy としては AI register 以前に register 不適合として指摘する（#161 style guide の「実装語を出さない」「短く、行動と結果が予測できる」原則と整合）。
5. **安全意味論は常に優先。** AI 感の除去のために warning/failure/recovery の意味・制約・復旧手順を削らない（spec Authority の優先順位 1 > 3）。
6. **最終判断は人間。** checklist は reviewer と owner の編集起点であり、lint スコアや tell 数を merge gate にしない（Goodhart 回避）。

## 6. 出典 index（取得日: 2026-10-15）

### 英語

- Microsoft Writing Style Guide — Top 10 tips for style and voice（✔ 直接確認、ms.date 2026-07-02）: https://learn.microsoft.com/en-us/style-guide/top-10-tips-style-voice
- Microsoft — Brand voice above all, simple and human: https://learn.microsoft.com/en-us/style-guide/brand-voice-above-all-simple-human
- Apple HIG — Writing: https://developer.apple.com/design/human-interface-guidelines/foundations/writing
- Google Material 3 — Content design style guide: https://m3.material.io/foundations/content-design/style-guide / M1 writing: https://m1.material.io/style/writing.html
- GOV.UK — Writing for user interfaces: https://www.gov.uk/service-manual/design/writing-for-user-interfaces
- Mailchimp — Voice and tone: https://styleguide.mailchimp.com/voice-and-tone/
- Atlassian — Writing style: https://atlassian.design/content/writing-style/ / Language and grammar: https://atlassian.design/foundations/content/language-and-grammar / Writing with AI: https://www.atlassian.com/blog/ai-at-work/atlassians-guidelines-for-writing-with-ai
- GitHub Docs — Style guide: https://docs.github.com/en/contributing/style-guide-and-content-model/style-guide
- NN/g — Product-Specific GenAI Needs to Write for the Web（2025-04-04）: https://www.nngroup.com/articles/genai-write-for-the-web/ / The 3 C's of Informational Microcopy（2026-03-20）: https://www.nngroup.com/articles/3-cs-microcopy/ / ChatGPT and Tone（2024-03-08）: https://www.nngroup.com/articles/chatgpt-and-tone/ / AI Can Help Write an Article, but It Can't Stand Behind It（2026-09-11）: https://www.nngroup.com/articles/ai-editorial-process/
- Kobak et al. — Delving into ChatGPT usage in academic writing（arXiv 2406.07016）: https://arxiv.org/abs/2406.07016 / data: https://github.com/berenslab/chatgpt-excess-words
- Liang et al. — Monitoring AI-Modified Content at Scale（arXiv 2403.07183）: https://arxiv.org/abs/2403.07183
- Reinhart et al. — Ghost writers?（arXiv 2410.08869）: https://arxiv.org/abs/2410.08869
- Towards Data Science — Is This Slop?（2026-08-05）: https://towardsdatascience.com/is-this-slop-detecting-ai-generated-content-without-a-model-2/
- The Economist — How to spot AI writing（2026-07-30、要登録）: https://www.economist.com/ / Fast Company coverage（2026-08-03）
- PR Daily — 4 reasons your writing accidentally sounds AI-generated（2026-04-09）: https://www.prdaily.com/4-reasons-your-writing-accidentally-sounds-ai-generated-and-how-to-fix-it/

### 日本語

- SmartHR Design System — ライティング 基本的な考え方（✔ 直接確認）: https://smarthr.design/products/contents/writing-style/ / 用字用語: https://smarthr.design/products/contents/idiomatic-usage/ / エラーメッセージ: https://smarthr.design/products/contents/error-messages/overview/
- JTF 日本語標準スタイルガイド（翻訳用）第 4.0 版（2026-07-25）: https://www.jtf.jp/tips/styleguide / PDF: https://www.jtf.jp/pdf/jtf_style_guide.pdf
- WordPress.org 日本語チーム — 翻訳スタイルガイド: https://ja.wordpress.org/team/handbook/translation/translation-style-guide/ / WordPress.com 版: https://wordpress.com/ja/support/translation-style-guide/
- wordrabbit — UX ライティングガイド: https://wordrabbit.jp/writing_uxes/ （error: /writing_uxes/uxw4081889724、button: /writing_uxes/uxw5891086609）
- U-Site — エラーメッセージ設計ガイドライン: https://u-site.jp/alertbox/error-message-guidelines / https://u-site.jp/alertbox/hostile-error-messages
- WebA11y.jp — 分かりやすいエラーメッセージ: https://weba11y.jp/know-how/guidelines/descriptive-error-message/
- took.jp — AIっぽい文章チェッカー: https://took.jp/tools/aicheck
- zenn coji — natural-japanese（AI スメル lint）: https://zenn.dev/coji/articles/natural-japanese-ai-smell-lint
- オープンデータラボ — AIらしい日本語文章の分析（2026-07-09）: https://odl.or.jp/2026/07/09/ai-like-japanese-writing-analysis/
- もとやま — AIっぽい文章表現大全: https://note.com/yusuke_motoyama/n/n2a1218636f56
- ソリシオ — AIっぽい文章の直し方7原則: https://solicio.co.jp/blog/ai-like-writing/
- conmark — AIっぽい文章の特徴5選: https://www.conmark.jp/column/223
- Media Analytics Blog — 生成AI特有表現リスト: https://blog.media-analytics.jp/generative-ai/generative-ai-text/
- writers-hub — AIっぽい文章の特徴: https://writers-hub.co.jp/services/edico/blog/ai-like-writing
- munyoru — AIっぽい語彙15項目: https://note.com/munyoru/n/nd524ff97457f
- robitan（Qiita）— AI文章の見分け方10: https://qiita.com/robitan/items/76af9fa8b82c0f9c739f
- Apple Style Guide 日本語版（日本語固有規定なしの確認）: https://support.apple.com/ja-jp/guide/applestyleguide/welcome/web
