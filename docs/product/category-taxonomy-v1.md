# Category Taxonomy v1

> Status: Proposed (research output of Issue #6)
> Reviewed: 2026-08-09
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-010, FR-011, FR-015, NFR-003, NFR-005, NFR-008
> Decision gate: D-008 (category taxonomy)
> Depends on: Issue #2 Deck audit, Issue #3 item preservation policy
> Primary scope/dependency record: Issue #6

## 1. Question and scope

この文書は、外部送信なしで説明可能かつ決定的な category taxonomy と local classification v1 を定義する。project taxonomy と Android application category 等の local signal を独立に定義し、その優先順位と conflict 解決規則を定める。

### Scope

- NunuLauncher project taxonomy v1（category 一覧と定義）
- Android application category 等の local signal の利用方法
- Signal の優先順位と conflict 解決規則
- User override の優先順位
- Unknown / undefined の fallback 動作
- Explainability（分類理由の説明方法）
- 複数 profile における分類の扱い

### Non-goals

- 外部 LLM や network 分類器の利用（FR-014 で別途検討）
- Usage frequency に基づく分類（FR-013 で別途検討）
- Flowerpot rule file の採否決定（D-009 で別途検討）
- 分類結果の planner への統合（Issue #10 以降）

## 2. Project taxonomy v1

### 2.1 定義方針

- Flowerpot の 32 category を参考に、project taxonomy を独立定義する。
- Android の `ApplicationInfo.category` 9 種を signal の 1 つとして取り込む。
- System / Google の分類は taxonomy に含めず、別の signal として扱う（§3）。
- 各 category には display name、説明、代表的な package 例を付ける。

### 2.2 Category 一覧

| ID | Display name | 説明 | Android category 対応 | 代表的な package |
|---|---|---|---|---|
| `GAME` | Game | ゲームアプリ | `CATEGORY_GAME` | `com.supercell.clashofclans` |
| `SOCIAL` | Social | ソーシャルネットワーキング、コミュニケーション | `CATEGORY_SOCIAL` | `com.whatsapp`, `com.facebook.katana` |
| `MUSIC` | Music | 音楽プレイヤー、ストリーミング | `CATEGORY_AUDIO` | `com.spotify.music` |
| `VIDEO` | Video | 動画プレイヤー、ストリーミング | `CATEGORY_VIDEO` | `com.google.android.youtube` |
| `PHOTOGRAPHY` | Photography | 写真・カメラ | `CATEGORY_IMAGE` | `com.google.android.apps.camera` |
| `NEWS` | News | ニュース | `CATEGORY_NEWS` | `com.google.android.apps.magazines` |
| `MAPS` | Maps & Navigation | 地図・ナビゲーション | `CATEGORY_MAPS` | `com.google.android.apps.maps` |
| `PRODUCTIVITY` | Productivity | 生産性ツール | `CATEGORY_PRODUCTIVITY` | `com.google.android.apps.docs` |
| `COMMUNICATION` | Communication | 電話・メール・連絡先 | — | `com.google.android.dialer` |
| `ENTERTAINMENT` | Entertainment | エンターテイメント | — | `com.netflix.mediaclient` |
| `SHOPPING` | Shopping | ショッピング | — | `com.amazon.mShop.android.shopping` |
| `FINANCE` | Finance | 金融 | — | `com.chase.sig.android` |
| `HEALTH` | Health & Fitness | 健康・フィットネス | — | `com.google.android.apps.fitness` |
| `EDUCATION` | Education | 教育 | — | `com.duolingo` |
| `BOOKS` | Books & Reference | 書籍・リファレンス | — | `com.google.android.apps.books` |
| `BUSINESS` | Business | ビジネス | — | `com.microsoft.teams` |
| `TOOLS` | Tools | ツール・ユーティリティ | — | `com.google.android.apps.photos` |
| `PERSONALIZATION` | Personalization | カスタマイズ・壁紙・アイコンパック | — | `com.google.android.apps.wallpaper` |
| `WEATHER` | Weather | 天気 | — | `com.google.android.apps.weather` |
| `TRAVEL` | Travel & Local | 旅行・地域情報 | — | `com.airbnb.android` |
| `FOOD` | Food & Drink | 食べ物・飲み物 | — | `com.ubereats` |
| `SPORTS` | Sports | スポーツ | — | `com.espn.scorecenter` |
| `AUTO` | Auto & Vehicles | 車・乗り物 | — | `com.tesla` |
| `ART` | Art & Design | アート・デザイン | — | `com.adobe.ps` |
| `DATING` | Dating | デート | — | `com.tinder` |
| `EVENTS` | Events | イベント | — | `com.eventbrite.attendee` |
| `HOUSE` | House & Home | 住宅・ホーム | — | `com.houzz` |
| `LIFESTYLE` | Lifestyle | ライフスタイル | — | `com.pinterest` |
| `MEDICAL` | Medical | 医療 | — | `com.webmd.android` |
| `PARENTING` | Parenting | 子育て | — | `com.babycenter` |
| `BEAUTY` | Beauty | ビューティー | — | `com.ultabeauty` |
| `COMICS` | Comics | 漫画・コミック | — | `com.webtoons` |
| `LIBRARIES` | Libraries & Demo | ライブラリ・デモ | — | `com.example.demo` |
| `OTHER` | Other | その他（未分類） | — | — |

合計 34 category（33 具体 category + 1 `OTHER`）。

### 2.3 Flowerpot との差異

- `COMMUNICATION` と `SOCIAL` を明確に分離する（Flowerpot では重複が多い）。
- 各 category に `Android category` 対応を明記する。
- `OTHER` を明示的な category として定義する（未分類 item が暗黙的に無視されることを防ぐ）。
- 各 category に bundle 名の代わりに ID を割り当てる（locale 非依存の識別子）。

## 3. Local signal と優先順位

### 3.1 Signal 一覧

| # | Signal | 種別 | 説明 | 取得方法 |
|---|---|---|---|---|
| S1 | User override | 明示的 | ユーザーが指定した category 割当 | 別途定義する永続化層 |
| S2 | Android `ApplicationInfo.category` | システム | 開発者が AndroidManifest で指定した category | `ApplicationInfo.category` |
| S3 | Package name rule | 静的 rule | package name の known list 一致 | 出荷時同梱の rule file |
| S4 | Intent action/category | 静的 rule | 特定の intent を handle する app | `PackageManager.queryIntentActivities` |
| S5 | System/Google flag | 分類 signal | System app または Google app か | `isSystemApp()`, `com.google.` prefix（S5a/S5b の順で評価、§3.2 参照） |
| S6 | Default | fallback | どの signal も一致しない場合 | 常に `OTHER` |

### 3.2 優先順位

Signal は **S1 > S2 > S3 > S4 > S5 > S6** の順に評価する。上位の signal が確定した category を採用し、下位の signal は評価しない。

| 優先順位 | Signal | 決定方法 |
|---|---|---|
| 1 (最高) | S1: User override | ユーザーが明示的に指定した category を常に採用する。override がない場合は S2 へ進む。 |
| 2 | S2: Android `appCategory` | `ApplicationInfo.category` が `CATEGORY_UNDEFINED` 以外で、かつ §2 の対応表にある値の場合に taxonomy ID を導出する。対応表外の値は diagnostic に記録し、S3 へ進む。 |
| 3 | S3: Package name rule | 出荷時同梱の known list で package name が一致する場合、その category を採用する。 |
| 4 | S4: Intent action/category | 特定の intent action/category を handle する app の場合、対応する category を採用する。 |
| 5a | S5a: Google app flag | package name が `com.google.` で始まる場合、`TOOLS` を default とする（§5.1 の暫定対処）。 |
| 5b | S5b: System app flag | S5a が不該当で `isSystemApp()` が真の場合、`OTHER` を割り当てる。 |
| 6 (最低) | S6: Default | S5a/S5b ともに不該当の場合、`OTHER` を割り当てる。 |

### 3.3 決定性の保証

- Signal 評価は常に S1 → S6 の順で行う。
- S3/S4 の rule 評価順序は、rule file の行順に依存しない canonical な順序（category ID の辞書順）で行う。
- 同一 package に対する S3 の一致が複数ある場合、category ID の辞書順で最初のものを採用する。
- 上記により、同じ入力から常に同じ category 割当を生成する。

## 4. Conflict 解決規則

### 4.1 Signal 間の conflict

優先順位（S1 > S2 > ... > S6）で解決する。下位の signal の結果は上位の signal で上書きする。

### 4.2 同一 profile 内の重複

同一 package が複数の profile に存在する場合、各 profile で独立に category を割り当てる。割当結果は profile ごとに独立して保存する。

### 4.3 同一 signal 内の複数一致

同じ signal 内で複数の category が一致する場合、category ID の辞書順で最初のものを採用する。これにより決定性を保証する。

### 4.4 未定義 category の fallback

- どの signal も category を確定できない場合、`OTHER` を割り当てる。
- Diagnostic には「未分類（OTHER）」とその理由を記録する。
- `OTHER` を含む category の配置・folder 化は本 taxonomy の対象外とし、layout strategy（Issue #5）と planner integration（Issue #10）で定義する。

## 5. System / Google 分類の扱い

### 5.1 現在の暫定対処

- Lawnchair の `categorizeAppsWithSystemAndGoogle` は、System app と Google app を独立した category として扱う。
- 本 taxonomy ではこれらを独立した category とせず、通常の signal 評価の一部として扱う。
- S5 は S5a と S5b の 2 段階で決定的に評価する:
  1. **S5a**: package name が `com.google.` で始まる場合、`TOOLS` を割り当てる。Google app が同時に system app であっても S5a が優先される。
  2. **S5b**: S5a が不該当で `isSystemApp()` が真の場合、`OTHER` を割り当てる。
- これにより、Google app かつ system app（例: `com.google.android.dialer`）の tie-break は常に S5a → `TOOLS` に確定する。
- S2-S4 の signal が一致すれば、S5 より上位のためその category に分類される。

### 5.2 将来の拡張

- 明示的な System / Google の category 分割が必要な場合、別 Issue で taxonomy を拡張する。
- その際も、S5 の優先順位は S2-S4 より低いままとする。

## 6. User override

### 6.1 保存内容

- 永続化層には次の情報を保存する。
  - Package name
  - Profile serial
  - Category ID
  - 設定日時
  - 変更前の category（diagnostic 用）

### 6.2 優先順位

- User override は常に S1 として評価され、他の全 signal より優先される。
- Override を解除した場合、S2 以降の signal で再評価する。

### 6.3 有効範囲

- Override は指定された package + profile の組み合わせに対してのみ有効。
- 同一 package の別 profile には影響しない。
- Override が適用された item は、diagnostic に「user override」と記録する。

### 6.4 データ取扱い（NFR-008）

Category override の保存データに関する取扱いを次の通り定義する。

| 項目 | 規定 |
|---|---|
| 保存対象 | package name、profile serial、category ID、設定日時、変更前 category ID |
| 保存先 | 端末内の local storage のみ。外部送信しない。 |
| 外部送信 | 常に **default off**。将来の機能で送信を追加する場合、明示的な user opt-in と privacy review（Issue #16）を必須とする。 |
| 保持期間 | ユーザーが override を解除するか、対象 app をアンインストールするまで。 |
| バックアップ | Lawnchair backup（ZIP）に含まれる可能性がある。restore 先で profile serial が変化する場合は §8.1 に従い再評価する。 |
| Diagnostic への露出 | package name は生のままで local diagnostic へ記録するが、user-visible 表示では必要に応じて mask する（Issue #16 で具体化）。 |

## 7. Explainability

### 7.1 分類理由の記録

各 item の category 割当に対して、次の情報を diagnostic として記録する。

| 項目 | 説明 |
|---|---|
| Category ID | 割り当てられた category |
| Signal 種別 | どの signal が決定したか（S1-S6） |
| Signal 詳細 | 決定に使用した具体的な値（`appCategory=game`, `package matched GAME rule` 等） |
| User override 有無 | User override が適用されたか |
| 確信度 | `explicit`（S1-S2）、`rule`（S3-S4）、`fallback`（S5-S6） |

### 7.2 表示

- ユーザー向けには「分類理由」として表示する。
- 例: 「Game（Android カテゴリより）」「Other（未分類）」「Tools（ユーザー設定）」

## 8. 複数 profile の扱い

### 8.1 Profile 独立性

- Personal / Work / Private の各 profile の app は、profile ごとに独立して category を割り当てる。
- 同一 package 名でも profile が異なれば別の category を持てる。
- Category 割当の結果は profile の識別子と共に記録する。

### 8.2 Planner との境界

- 本 taxonomy は profile ごとの category assignment だけを定義し、folder 化や配置は定義しない。
- profile をまたぐ folder 化を含む planner の制約は layout strategy（Issue #5）および planner integration（Issue #10）で定義する。

## 9. D-008 の提案

D-008 の提案: **Android category を signal の 1 つとし、project taxonomy を独立定義する。**

- Project taxonomy は 34 category（33 具体 + 1 OTHER）で構成する。
- Signal 優先順位は S1（user override）> S2（Android appCategory）> S3（package rule）> S4（intent rule）> S5（system/Google flag）> S6（default）とする。
- Flowerpot の 32 category は参考とするが、直接採用しない。代わりに project taxonomy を独立定義し、Flowerpot の rule file と同様の package rule を S3 で利用可能とする。
- 決定性を保証するため、signal 評価順序と tie-break を明示する。

## 10. 代表的な fixture

| # | Fixture | 期待結果 |
|---|---|---|
| C-01 | `com.supercell.clashofclans` (appCategory=GAME) | S2 → `GAME` |
| C-02 | `com.whatsapp` (appCategory=SOCIAL) | S2 → `SOCIAL` |
| C-03 | `com.spotify.music` (appCategory=AUDIO) | S2 → `MUSIC` |
| C-04 | `com.google.android.youtube` (appCategory=VIDEO) | S2 → `VIDEO` |
| C-05 | `com.unknown.app` (appCategory=UNDEFINED, no rule match) | S6 → `OTHER` |
| C-06 | `com.unknown.app` (user override → `TOOLS`) | S1 → `TOOLS` |
| C-07 | `com.google.android.dialer` (appCategory=UNDEFINED, Google app) | S5a → `TOOLS`（暫定） |
| C-08 | `com.android.settings` (system app, appCategory=UNDEFINED, Google app ではない) | S5b → `OTHER` |
| C-09 | 同一 package が personal/work 両方に存在 | profile ごとに独立して category 割当 |
| C-10 | 複数の S3 rule が一致（`com.example.app` → GAME と TOOLS） | 辞書順で最初の `GAME` を採用 |
| C-11 | User override 設定後、解除 | 再評価で S2 以降の結果を採用 |
| C-12 | Hybrid app（appCategory=GAME かつ user override → SOCIAL） | S1 → `SOCIAL` |
| C-13 | `com.google.android.apps.maps` (appCategory=MAPS) | S2 → `MAPS` |
| C-14 | `com.netflix.mediaclient` (appCategory=UNDEFINED, system app ではない, Google app ではない) | S6 → `OTHER` |
| C-15 | 全 app の category 割当結果 | 全 app が 34 category のいずれかに割り当てられる（`OTHER` も含む） |
| C-16 | `com.example.futurecategory` (appCategory=未対応値、S3-S5 不一致) | S2 は diagnostic に未対応値を記録して S3 へ進み、S6 → `OTHER` |

## 11. 未決定事項と後続 Issue への制約

| 項目 | 制約 / open point |
|---|---|
| Flowerpot の採否 | 本 taxonomy は Flowerpot と独立。Flowerpot を S3 の rule source として採用するかは D-009 で判断する。 |
| S3 rule file の形式 | package rule の出荷時同梱形式は別 Issue で決定する。Flowerpot の asset file 形式を参考にする。 |
| S5a/S5b の System/Google 暫定対処 | 現状は暫定値（S5a Google → `TOOLS`, S5b system → `OTHER`）。本格的な対処は別 Issue で検討する。 |
| Usage signal の統合 | FR-013 の対応時に S1-S6 の優先順位を見直す可能性がある。 |
| 外部 LLM 分類器 | FR-014 の対応時に、S1 と同等以上の優先順位で統合するか検討する。 |

## 12. 根拠

全ての source 参照は baseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b` に固定する。確認日: 2026-08-09。

- [Flowerpot.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/flowerpot/Flowerpot.kt) — rule-based categorization engine、32 asset categories
- [AppCategorizationUtils.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/util/AppCategorizationUtils.kt) — System/Google/Other 3-way split
- [CodeRules.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/flowerpot/CodeRules.kt) — `isGame`, `category` code rules
- [ApplicationInfo](https://developer.android.com/reference/android/content/pm/ApplicationInfo#category) — `category` field（Android SDK）。確認日: 2026-08-09。
- [LauncherSettings.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/LauncherSettings.java) — `Favorites` table item types
- [LawndeckManager.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/src/app/lawnchair/deck/LawndeckManager.kt) — Deck category usage、`findFolderByCategory`
- [device_profiles.xml](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/lawnchair/res/xml/device_profiles.xml) — grid options（reference）
