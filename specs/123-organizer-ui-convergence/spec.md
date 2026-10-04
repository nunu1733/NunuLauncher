---
issue: "#123"
status: implemented
requirements: []
updated: 2026-08-27
---

# オーガナイザーUIをLawnchairのビジュアル言語とローカリゼーションへ収束させる

> 本specは[Issue #123][1]のpresentation/localization収束を拘束契約として定義する。
> organizerのrun-state machine、適用semantics、safety messaging、accessibility semanticsは
> 変更しない。動作変更が必要と判明した時点で本specのscopeから分離し、別Issueのspecへ移す。

## Problem

オーガナイザー機能はbehavior・safety・accessibility要件を満たす形で実装が進んだ結果、ユーザー可視surfaceが周辺のLawnchair UIと見た目に不整合を起こし、日本語実行時にNunu固有文字列の大部分が英語fallbackしている。

確認済みの観測事実（baseline `505dbc40e6` 時点、2026-08-26確認）:

1. **日本語fallback**: baseline以降に追加されたorganizer固有stringは、`lawnchair/res` に168個、Launcher3側の `res` に54個（#99カテゴリtaxonomyとoverride authoring）の計222個ある。実装開始時点で `values-ja` を持つのは #138由来の6個のみであり、残り216個は日本語実行時にdefault（英語）リソースへ解決される。
2. **テーマ外のonboarding proposal**: onboarding proposal popup（`OrganizationOnboardingProposalContent` / `OrganizationOnboardingProposalView`）は背景 `Color.WHITE`、title `Color.BLACK`、summary `Color.DKGRAY`、`GradientDrawable` 角丸24dp、標準 `Button` をハードコードする。dark appearanceでも白背景のままであり、周辺Lawnchair/Launcher3のfloating viewと外観が乖離する。
3. **素のText行**: manual organization画面（`ManualOrganizationPreferences`）のstatus・preview summary行は `PreferenceLazyColumn` 内の裸 `Text` であり、近隣surfaceが持つpreference row規約（heading、typography、spacing）と揃っていない。
4. **画面間の作法差**: diagnostics画面（`OrganizerDiagnosticsPreferences`）はdescriptionを裸 `Text` で表示し、placement lock画面のstate badgeはpadding指定の素のTextである等、surfaceごとに場当たり的なpresentationが存在する。

一方で、既存surfaceの多くは既に `PreferenceScaffold` / `PreferenceLazyColumn` / `PreferenceTemplate` / `ClickablePreference` を再利用しており、dialogも上流Lawnchairと同様のMaterial3/platform構成である。問題は新規デザインの不在ではなく、部分的な未収束と文字列の未翻訳である。

## Outcome

Nunu固有オーガナイザーUIが、周辺のLawnchair 15設定/ランチャーUIの自然な延長として見え、振る舞う。具体的には:

- 各surfaceが既存Lawnchairコンポーネント/theme規約へ収束し、説明のない独自表現がなくなる。dark/light両appearanceで正しく描画される。
- Nunu固有オーガナイザー文字列はすべてAndroid resource由来となり、日本語実行時に英語fallbackしない。
- 代表surfaceについて、locale × appearance × font scaleを横断する再現可能な視覚証拠が取得できるようになる。

## Scope

1. **Visual convergence audit**: baseline以降に追加された全Nunu固有organizer可視surfaceのインベントリと、「Nunu surface → Lawnchair参照component/screen」のmappingを作成する。対象は少なくとも:
   - manual organization（start / progress / preview / result / recovery）
   - placement lock authoring/review画面とlong-press popup entry
   - onboarding proposal popup
   - category override authoring画面
   - organizer diagnostics/export画面とexport control
   - HomeScreen設定内のorganizer navigation entries
   - organizer由来のdialog / banner / status message / Toast
2. **視覚収束**: mappingに基づき、既存Lawnchair component / theme tokenでの再利用置換を行う。説明のないNunu-only表現（hardcoded色、独自shape/padding、素Text行）を除去する。dark appearance非対応箇所（onboarding proposal）をtheme解決へ移行する。
3. **Localization completion**:
   - organizerのuser-visible文字列とaccessibility読み上げ文をすべてAndroid resource由来とする。Kotlin code内のhardcoded English literalの除去に加え、app/profile/state等の複数部品から構築される表示文・読み上げ文（連結・補間による生成）をformat resource（`%1$s` 等）へ移行し、localeごとの語順・区切りの変更を可能にする。
   - 全Nunu固有organizer文字列のうちuser-visibleかつtranslatableなものすべての日本語訳を `values-ja` へ追加する。正本はdead resource整理・複合文format resource化後の最終name集合であり、調査時点の固定件数ではない。format placeholderの保持と `translatable="false"` の意味論の尊重を含む。
   - 参照されないorganizer string resource（5個確認済み）を整理する。
4. **再現可能な視覚検証**: 既存のinstrumentation screenshot captureの前例（`ManualOrganizationPreferencesInstrumentationTest.captureReviewScreenshot`）を拡張し、代表surfaceについてdefault locale / 日本語 / `en-XA` pseudo locale × light/dark × 代表的font scaleの証拠取得手順を確立する。これが本Issue完了判定の既定経路である。Roborazzi等の新dependencyは本Issueの完了条件外であり、任意spikeで安定性・維持コスト・CI実行時間の価値が実証された場合にのみ、別Issueとして提案する。

## Non-goals

- リブランド・ redesign、新しいdesign language、Figma-first design systemの導入。
- Lawnchair 16へのupdate（専用Epic + ADRが必要）。
- 上流Lawnchair文字列の翻訳、またはja以外の多言語へのNunu文字列展開（Crowdin/translation managementが必要ならsplit Issue）。
- organizerのplanning/application semantics、safety保証、persistence、layout挙動の変更。
- accessibility semantics（focus順序 #137、liveRegion、touch target、Switch Access/TalkBack対応）やsafety messagingの弱体化。
- visual-regression toolingを安定性とCI costの実証前にmerge gate化すること。
- 上流由来の翻訳のスタイル上の好みによる改変（継承欠陥を確認した場合はsplitして根拠付きで対応）。

## Domain language

実装語のみのため空。新しいproduct/domain用語は導入しない。

## Behavior scenarios

### Scenario: 日本語実行でorganizer surfaceが英語fallbackしない

Given 端末のsystem localeが日本語であり、manual organization / placement lock / category override / diagnostics / onboarding proposalのいずれかのsurfaceを開いている
When 各surfaceのtitle、説明、状態表示、reason/warning行、action label、dialog文言を観察する
Then 表示される文字列はすべてNunu固有organizer文字列については日本語resource由来である
And `translatable="false"` とした技術識別子およびruntime data（実file名等）を除き、英語のままのorganizer文字列は存在しない

### Scenario: dark appearanceでorganizer surfaceがthemeに従う

Given system appearanceがdarkである
When onboarding proposal popupおよび各設定surfaceを表示する
Then 背景・文字・shapeがactivity theme由来の色/tokenで描画され、light固定の配色（白背景・黒文字）が残存しない
And light appearanceでは従来どおり可読である

### Scenario: en-XA pseudo localeで未翻訳文字列が露出せず操作可能である

Given `en-XA` pseudo localeが有効である
When organizer surfaceを開き、代表flow（start → preview → confirm/cancel、recovery preview）を操作する
Then organizer固有のraw/hardcoded英語文字列がpseudo展開されずに素通過する箇所がない
And 文字列拡張後もcritical action（confirm / cancel / recovery）がviewport内で到達可能である

### Scenario: font scale拡張で操作が維持される

Given font scaleを200%に設定する
When manual organizationのpreview/recoveryとonboarding proposalを表示する
Then 既存の200% font scale回帰test（#52/#53/#137由来）が引き続きpassし、critical actionが到達可能である

### Scenario: 収束変更がorganizerの意味論を変えない

Given 視覚収束・翻訳追加のPRが適用されている
When 既存のunit gate（`app.lawnchair.organizer.*`）、instrumentation lane、accessibility semantics assertionを実行する
Then すべての既存testがbehavior変更なしでpassする
And run-state遷移、確認・復旧の契約、recovery point、lock判定へのdiffが存在しない

### Scenario: failure — 固定technical identifierとruntime dataの区別

Given エクスポート既定file名のような技術的文言がorganizer UIに現れる
When それがuser-visibleな固定文言である
Then `translatable="false"` のAndroid resourceとして供給されており、Kotlin constant・literalから直接UIへ渡されていない
And 実file名・package名のようなruntime data（そもそも固定UI文言ではない実値）のみが例外であり、その区別がPR/evidence文書に記録されて未着手の翻訳として誤認されない

## Data and state

- 読む状態: 変更なし。既存のrun state、lock listing、category taxonomy、diagnostics portの読み出し契約はそのまま。
- 永続化: 本Issueが新規に永続化するものはない。string resourceの追加・削除、composable/Viewのpresentation変更のみである。onboarding proposalのoutcome store（`SKIPPED/DEFERRED/REVIEWED`）のkey・意味論は不変であり、restyleにより保存済みoutcomeの扱いが変わらない。
- migration / backup / restore / rollback: 対象外。schema・preference format変更はなく、Launcher layout DB / `favorites` への接触はないためホームレイアウト安全規約の適用対象外である。PR revertで現行挙動へ戻る。

## Permissions, privacy, and security

None — 新しいpermission、外部送信、sensitive dataの扱い追加はない。diagnostics exportのデータ集合は #67契約から広げない。en-XA / ja検証はlocal emulator/device上で完結する。

## Accessibility and localization

- 既存のCompose semantics testとaccessibility instrumentation（liveRegion、focus traversal、contentDescription合成）が正本であり、視覚収束はこれらを回帰させない。
- 日本語文字列はplaceholder（`%1$s` 等）を保持し、複数形がある場合は `plurals` として扱う。現在の追加文字列に `plurals` / `translatable="false"` は存在しない。
- 複数情報を組み合わせるdescription・contentDescription・status文は、Kotlin側の連結・補間ではなくformat resourceで構成する。区切り子と語順もresource側で制御し、単純なlabel並置以外の文構造をKotlinで生成しない。
- TalkBack読み上げがja localeで自然であること（label + stateの合成descriptionが意味をなすこと）。

## Acceptance criteria

- [ ] **AC-1**: baseline以降の全Nunu固有organizer可視surfaceがインベントリされ、各surfaceが既存Lawnchair参照patternへmappingされているか、参照不存在の理由が記録されたevidence文書が存在する。（issue受入1）
- [ ] **AC-2**: organizer screensが、Lawnchairに等価物が存在するspacing/typography/container/color/action表現について、説明のないstandalone表現を導入していない。残るcustom presentationは目的付きで記録されている。（issue受入2, 3）
- [ ] **AC-3**: onboarding proposalを含む全organizer surfaceがlight/dark両appearanceでtheme整合に描画される（hardcoded `Color.WHITE/BLACK/DKGRAY` 等が除去されている）。（issue受入2の具体化）
- [ ] **AC-4**: organizerのuser-visible文字列とaccessibility読み上げ文がすべてAndroid resource由来である。hardcoded English literalの不在に加え、Kotlin側での可視文字列・読み上げ文の構築（連結・補間・`joinToString` による文生成）が残っておらず、複合文はformat resource（`%1$s` 等）へ移行されている。参照されないorganizer stringが整理されている。（issue受入4）
- [ ] **AC-5**: 実装後の全active/user-visible/translatable Nunu organizer resource（dead resource整理・複合文format resource化後の最終name集合）に対応する日本語resourceが存在し、placeholderを保持している。（issue受入5）
- [ ] **AC-6**: 日本語実行において、covered organizer文字列が英語fallbackしない（emulator evidence）。（issue受入6）
- [ ] **AC-7**: `en-XA` 実行において、unintendedなraw organizer文字列が露出せず、代表画面でcritical action/textがclipping無しに使用可能である。（issue受入7）
- [ ] **AC-8**: 代表organizer surfaceごとに、locale（default / 日本語 / `en-XA`）、appearance（light/dark）、代表的font scaleの組み合わせからなるcapture matrixがevidence文書へ明文化され、それに基づく再現可能なscreenshotまたはdevice evidenceが記録されている。全条件の完全直積までは要求しないが、default localeのbefore/reference/afterを省略してはならない。（issue受入8）
- [ ] **AC-9**: 既存のautomated accessibility/behavior regression test（JVM unit gate、organizer instrumentation lanes）がすべてgreenであり、既に取得済みのa11y evidenceと矛盾する挙動変化がない。新規およびfullなSwitch Access実行・実機matrix evidenceは #109 のownershipであり、本Issueの完了条件に含めない。（issue受入9）
- [ ] **AC-10**: visual-regression dependency/toolを追加した場合、選定理由・CI/runtime cost・coverage限界・baseline更新方法がPRに記録されている。追加しない場合、その判断と既存evidence手順への集約理由が記録されている。（issue受入10）
- [ ] **AC-11**: organizerのfunctional/safety semantics変更が本作業へ混在していない（diff scope review + 既存contract testの無編集で証明）。（issue受入11）

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | evidence文書（`docs/assessment/evidence/issue-123-ui-mapping.md`、surface→参照→差分→before/after） |
| AC-2 | 同文書のmapping table + 実装PR差分review（token/component再利用の確認） |
| AC-3 | dark/light capture（emulator）+ hardcoded色定数のdiff消失確認 |
| AC-4 | hardcoded literal grep + Nunu UI pathにおけるKotlin側文字列構築の洗い出し（`Text(` への補間、`contentDescription` 合成、`buildString` / `joinToString`）+ 複合文format resource化のdiff + 未参照resource整理のdiff |
| AC-5 | 検証集合を `required = active Nunu organizer default resources where user-visible && translatable != false` と明示定義し、`required ⊆ values-ja name集合` とplaceholder一致を機械確認。dead resource・format resource化後の最終集合ベース |
| AC-6 | ja locale API 36 emulatorでの代表flow screenshot一式 |
| AC-7 | `en-XA` 実行のscreenshot + instrumentation assertion（pseudo展開の有無、action到達性） |
| AC-8 | evidence文書内のsurface別capture matrix表（default/ja/en-XA × light/dark × font scaleの適用組み合わせを明記）+ 再現command列 + 取得画像。既存 `captureReviewScreenshot` 前例の拡張 |
| AC-9 | CI run URL（`final-status` green）+ local command結果をPR本文へ記録。#109向けの新規Switch Access実行は行わない |
| AC-10 | PR本文のtooling判断記録（追加時はdependency diffとcost試算を添付） |
| AC-11 | PR diff scope記述 + `app.lawnchair.organizer.*` JVM gateが無編集でpassすることのrun URL |

## Open questions

None are blocking. 非blocking事項:

1. Roborazziは本Issueの完了条件外である。任意spikeとして試行し、価値が実証された場合にのみ別Issueで提案する。本Issueは既存instrumentation captureの標準手順化により完了判定を受ける。
2. ja訳の最終文言は実装PRのstring diffで確定する（承認blockerではない）。安全関連メッセージ（rollback/recovery結果）の訳は既存en文の意味論を縮退させない範囲でreviewする。
3. placement lock popup / dialogが上流規約（platform `AlertDialog.Builder` / M3 AlertDialog）に既に整合することを踏まえ、AC-2のmappingで「変更不要」と判定する可能性がある。その場合は理由をevidence文書に記録する。

## Change history

- 2026-08-26: Draft created for #123。Issue本文、baseline `505dbc40e6` とのres差分（168追加/6個ja coverage/5個未参照）、organizer UI source inventory（`lawnchair/src/app/lawnchair/organizer/ui/`、`ui/preferences/destinations/*`、`ui/popup/OrganizerLockShortcut.kt`）、既存screenshot capture前例（`ManualOrganizationPreferencesInstrumentationTest`）の調査結果を入力に作成。
- 2026-08-26: Issue #123 review対応。P1: AC-4をhardcoded English除去から「user-visible text・accessibility読み上げ文のformat resource化」要件へ強化し、連結許容記述を撤回。P1: AC-8へdefault localeを復帰させ、surface別capture matrix明文化を要求。P2: AC-9を既存automated regression green＋既得evidenceと矛盾しないことに限定し、full Switch Access実行を #109 ownershipとして分離。Roborazziの扱いを「完了条件外の任意spike」に一本化。behavior・scopeのその他の部分は不変。
- 2026-08-26: 2nd review対応。P1: failure scenarioから「定数として意図的に固定」の例外を削除し、固定technical identifierは `translatable="false"` resource経由のみ、runtime data（実file名・package名等の実値）のみを翻訳対象外として区別。P2: scope 3とplan changesetの「162名」固定件数を廃止し、dead resource整理・format resource化後の最終name集合被覆に定義替え（AC-5のname-set比較を正本）。minor: plan changesetのinstrumentation行へdefault locale表記を復帰。
- 2026-08-26: 3rd review対応。AC-5から「baseline差分168個中」の固定baseline件数表現を除去し、「実装後の全active/user-visible/translatable Nunu organizer resourceの最終name集合」への日本語被覆という契約へ揃えた。scope 3 / plan changeset / test oracleとの一貫化のみで、他の変更はない。
- 2026-08-26: 4th review対応。AC-5のtest oracle（spec/plan両方）を検証集合定義ベースへ修正: `required = user-visibleかつtranslatableなNunu organizer default resource` を明示定義し、`required ⊆ values-ja name集合` とplaceholder一致を確認する形へ統一。`translatable="false"` 固定文言がja被覆検証から除外されることを保証。他の変更はない。
- 2026-08-26: Issue #123上で承認（4回のreview対応後、head `5b9ad9fa`時点）。`accepted`へ更新。実装開始。
- 2026-08-26: 実装中の調査訂正。Problem事実1の件数を修正: baseline差分は `lawnchair/res` 168個に加え、初回調査で取りこぼしたLauncher3側 `res` の54個（#99カテゴリ系）を含む計222個、ja欠落216個。AC定義は最終name集合ベースのため変更不要。
- 2026-08-27: [PR #154](https://github.com/nunu1733/NunuLauncher/pull/154) merge（head `523f2e88dd`、CI `final-status` green、visual evidence群をrepo evidence配下へ収録）。`implemented`へ更新。

[1]: https://github.com/nunu1733/NunuLauncher/issues/123
