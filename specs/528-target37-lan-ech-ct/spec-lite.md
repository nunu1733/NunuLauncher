---
issue: "#528"
status: draft
tier: M
requirements: []
updated: 2026-10-07
---

# targetSdk 37の通信経路 — LAN suggestionのpermission guardとECH/CTの確認・説明

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
通信の追加（新endpoint・新DNS経路）も行わない。custom suggestion fetchへのLAN権限
guardと、Android 17で既定化するECH/CTの適用状態確認・失敗の説明のみを扱い、検索UI
構造と既存の適用・復旧経路の契約は変更しない。Android/platform APIの新規扱いのため
外部参照調査（External reference scan）の対象であり、記録はPrior artへ行う
（[GitHub workflow](../../docs/project/github-workflow.md#external-reference-scan設計時の外部参照調査)）。

## Problem

Android 17 (API 37) をtargetとするappでは、ローカルネットワーク通信がruntime権限
`ACCESS_LOCAL_NETWORK` の対象になり、ECH（Encrypted ClientHello）と
Certificate Transparency (CT) が既定化する
（[behavior changes 17](https://developer.android.com/about/versions/17/behavior-changes-17)、確認2026-10-07）。
新baselineでもcustom suggestion URLはOkHttpが任意URLをfetchし
（`CustomWebSearchProvider.kt:45,57-83`）、LAN URLも値として設定でき、LAN URL・
失敗はいずれも利用者に見える説明なしで空候補へ落ちる（`:74-82`）。target 37への
cutover後、target 36以下では動いていたLAN suggestionが無説明で失効する。

## Baseline（本specの前提事実）

- 16-dev採用baseline: upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`
  （[ADR-0018](../../docs/adr/0018-lawnchair-16-rebase.md)固定）。本spec実装branchは
  `e8aced7dbee4480c178a9cb994b8d55686ef41ce`（Phase 2 review closure後の
  `issue-532-phase2-restart` head）から分岐する。
- Gradle: compileSdk 37（minor 2）、buildTools 37.0.0、minSdk 26、targetSdk 37
  （`build.gradle:26-34`）。
- LAN権限のandroid.jar実測（確認2026-10-07）:
  `android.Manifest.permission.ACCESS_LOCAL_NETWORK` は android-37.0 / 37.2 に
  存在し、android-36.1 には存在しない。SDK 37 gateは
  `Build.VERSION_CODES.CINNAMON_BUN`（android-37.0実測）。
  `src/com/android/launcher3/Utilities.java` のATLEAST定数は `ATLEAST_BAKLAVA_1`
  （API 36 / minor 1。`:168-171`。`SDK_INT_FULL >= 3600001`）まででSDK 37用は
  未整備のため、fork側の検索・設定codeで直接比較する（上流に手を入れない）。
- LAN権限の意味論（公式、確認2026-10-07）: target 37でNEARBY_DEVICES groupの
  runtime（dangerous）権限。未許可ではOkHttpを含む全networking APIがローカル宛先
  （RFC1918、CGNAT 100.64/10、169.254/16、IPv6 link-local・ULA、`.local`名解決）
  へblockされ、blockされたTCPは多くの場合タイムアウトする。標準runtime dialogで
  許可し、取消後は再blockされる。LAN DNS（port 53）は免除、VPN tunnel内trafficは
  「local network」に数えない
  （[LNP](https://developer.android.com/about/versions/17/behavior-changes-17#local-network-protection-permission)、[local-network-permission](https://developer.android.com/privacy-and-security/local-network-permission)）。
- ECH: target 37ではplatform側の既定条件を満たすが、networking libraryがECHを
  統合している接続にしか適用されない。OkHttp 5.5.0
  （`gradle/libs.versions.toml:108`）のECHはopt-inで、DNS HTTPS (TYPE65)
  record取得の経路（AndroidDns / DnsOverHttps）を要求する。opt-inしない本appの
  構成ではECHもGREASE拡張も送られない。ECH統合libraryでのfailure時のdegrade先は
  GREASEであり、接続失敗にはならない
  （[ECH](https://developer.android.com/about/versions/17/behavior-changes-17#ech-by-default)、[OkHttp CHANGELOG](https://github.com/square/okhttp/blob/master/CHANGELOG.md)）。
- CT: target 37で既定有効。custom / user trust anchor とlocalhostは免除で、
  public CA（SCT付き）の証明書は通る。domain単位のopt-outは存在するが本specでは
  使わない（[CT](https://developer.android.com/about/versions/17/behavior-changes-17#ct-default)、[security config](https://developer.android.com/privacy-and-security/security-config)）。
- launcher appの権限はINTERNETのみ（`lawnchair/AndroidManifest.xml:22`）。
  network security config・`usesCleartextTraffic` はなく、cleartextはplatform既定
  （target 28+でblock）。URL templateとsettings UIは
  `PreferenceManager2.kt:662-686`（`web_suggestion_provider_*`）と
  `ui/preferences/components/search/WebSearchProvider.kt:54-79,90`
  （検証は赤枠のみでerror messageなし）が既存の面である。

## 通信経路一覧と対応方針（`e8aced7dbe` 固定列挙）

行番号はすべて `e8aced7dbe` のもの。全経路に「対応方針」または「対象外理由」を
付す。[#521 assessment](../../docs/assessment/issue-521-target-sdk-37-behavior-changes.md)
T14の「分ける」に従い、app自身がfetchするsuggestion URLとbrowserへ渡すsearch URLを
別扱いする。

| # | 経路 | site | 対応方針 / 対象外理由 |
|---|---|---|---|
| C1 | custom suggestion fetch（LAN guard対象） | `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/CustomWebSearchProvider.kt:45,57-83` | LAN分類URLにpermission guard + 設定画面の型付き案内（下記C1の詳細） |
| C2 | 既定suggestion fetch（Google/DDG等public HTTPS） | 同dir `BuiltInWebSearchProviders.kt` | 変更なし。API 37/36で取得成功の回帰確認のみ |
| C3 | 検索URLのbrowser handoff | `CustomWebSearchProvider.kt:85-92`（`getSearchUrl`。app側fetchなし） | 対象外。通信主体はbrowserであり、LAN権限・ECH/CTはbrowser側の契約に従う（T14の「分ける」） |
| C4 | katbin bugreport upload | `lawnchair/src/app/lawnchair/bugreport/KatbinService.kt:14,19`、`UploaderService.kt:53` | 変更なし。API 37/36で成功/失敗の確認のみ（失敗は既存 `action_upload_error` のtyped通知） |
| C5 | organizer diagnostics / exchange / backup | `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt`、`tools/repo-contract/validate_diagnostics_contract.py` | 対象外・維持確認。local / SAF専用でnetwork transportを持たない契約（DESIGN.md 4.5）をtestで強制。変更せずgreenを維持する |

### C1の詳細: 分類・guard・設定案内

- **分類**（`SuggestionUrlClassifier`。新file。`CustomWebSearchProvider.kt` 近傍）:
  URL文字列 → local-network要 / 不要 / 無効 の純粋関数で、platform型をinterfaceに
  出さない（java.net.URI等でのparse）。分類対象はsuggestion URLのみで、platformの
  ローカル宛先定義（RFC1918 / CGNAT 100.64/10 / link-local v4+v6 / IPv6 ULAの
  IP literalと `.local` hostname）に揃える。private IPへ解決されるhostnameは静的に
  分類できず、その失敗はfetch失敗として型付き案内で扱う（限界を明記する）。
- **fetch guard**（`CustomWebSearchProvider.getSuggestions`）: local分類かつ
  `ACCESS_LOCAL_NETWORK` 未許可のとき（SDK 37+ gateのみ。API 36以下では評価しない）、
  network呼び出しの前に空候補をemitしtyped logを出す。タイムアウト待ちを作らない。
  それ以外の経路（internet URL、権限あり、API 36以下）は現行どおりで変更しない。
- **設定UI**（custom suggestions URL欄）: URLがlocal分類で権限がないとき、inlineの
  型付き状態 + rationale + permission要求buttonを出す。要求は設定画面のuser操作
  文脈で行い（launcher可視。背景起動の新規経路は作らない）、拒否時はSettings path
  （App permissions > Nearby devices）の案内へ切替える。取消（revoke）後は次回の
  設定状態表示 / fetchで拒否と同等に扱う。権限ありなら通常のfetchに戻る。
  非HTTPS URLが入力されたときは既存 `isErrorCheck` 機構の延長でtyped messageを
  出す（`http://` LAN endpointはplatform既定のままblockされる）。
- **移行の限界**: 既存LAN-URL利用者はtarget 37 cutover後、settings訪問までfetchが
  停止する。settingsが唯一の説明surfaceであり、検索ポップアップ・drawer UI内の
  error表示はNon-goalsとする。Internet URLのみの利用者に新規permission promptは
  出ない（挙動不変）。

## Benchmark

対象課題: B1〜B7のうちIssue指定の **B1/B2**（検索・追加操作を含むflow。
[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）。
目標: **B1/B2（およびB3〜B7の依存flow）の作業中断・復帰コストの増分ゼロ**。
targetSdk 37の通信変更への追従が、検索ポップアップ / drawer検索からアプリ追加・
配置へ至る通常flowに新規操作・待ち時間（permission dialog、タイムアウト待ち）を
発生させないことをもって判定する。LAN権限要求は設定画面での明示的なfeature構成時に
限定されるため、ベンチマークflowには介入しない。platform契約追従であり、新たな
ベンチマーク課題の起票は不要と判断する（#527と同じ判断）。

## Prior art

- Android公式: [behavior changes 17 — Local network protection](https://developer.android.com/about/versions/17/behavior-changes-17#local-network-protection-permission)。確認日2026-10-07。採用: `ACCESS_LOCAL_NETWORK` のruntime権限化、block対象のローカル宛先定義、失敗shape（TCPはタイムアウト）をProblemとfetch guard（即時短絡）の根拠にする。
- Android公式: [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)。確認日2026-10-07。採用: 許可dialog・Settings path（Nearby devices）、取消時の扱い、`.local`名解決・LAN DNS port 53免除・VPN非対象の意味論を設定案内とVerificationに使う。
- Android公式: [behavior changes 17 — ECH by default](https://developer.android.com/about/versions/17/behavior-changes-17#ech-by-default)。確認日2026-10-07。採用: ECH適用をplatform条件とlibrary条件に分けて記録する枠組み（library未対応ではECH無効、failureはGREASEへのdegradeで接続失敗にならない）。
- Android公式: [behavior changes 17 — CT](https://developer.android.com/about/versions/17/behavior-changes-17#ct-default) と [Network Security Config](https://developer.android.com/privacy-and-security/security-config)。確認日2026-10-07。採用: target 37でのCT既定有効、custom / user trust anchor とlocalhostの免除、domain単位opt-outの存在（本specでは不使用）を確認面の根拠にする。
- OkHttp CHANGELOG 5.5.0（https://github.com/square/okhttp/blob/master/CHANGELOG.md）。確認日2026-10-07。採用: ECHがopt-in（DNS HTTPS record経路が前提）であることの確認。merged libraryの既定設定はECH不適合というVerification前提。
- Android公式: [Request runtime permissions](https://developer.android.com/training/permissions/requesting)。確認日2026-10-07。採用: 設定文脈（user操作起点）でのrationale付き要求とSettings導線。repo内の既存pattern（`SearchProviderPreference.kt:136` のcontacts行 `rememberPermissionState`、`ui/preferences/components/PermissionDialog.kt`）と整合させる。
- それ以外の外部実装例は省略（調査済み）。対応はplatform契約の追従であり、新規設計の採用がない。

## Outcome

target 37環境で、LAN宛先のcustom suggestionは `ACCESS_LOCAL_NETWORK` 許可時に
提供が継続し、拒否・取消時は黙示的な空候補ではなく設定画面の型付き案内で説明される
（fetchは即時短絡し、タイムアウト待ちを作らない）。Internet URLのみの利用者と
public HTTPS（既定suggestion、katbin upload）はAPI 37/36で現行どおり動作し、
新規permission promptは発生しない。ECHはopt-inせず、CTは無効化しない。両者の
適用状態はlibrary条件とplatform条件を分けてVerification evidenceへ記録する。
organizer / backupのlocal-only契約は不変である。

## Scope

- manifest権限宣言（`lawnchair/AndroidManifest.xml`。INTERNET `:22` の近傍）。
- `SuggestionUrlClassifier`（新file。`CustomWebSearchProvider.kt` 近傍。URL文字列 →
  local-network要 / 不要 / 無効の純粋関数。分類はRFC1918 / CGNAT 100.64/10 /
  link-local（v4+v6）/ IPv6 ULAのIP literalと `.local` hostname。unit test対象）。
- `CustomWebSearchProvider` のfetch guard（SDK 37+ gate。guard時は空候補 + typed
  log、タイムアウト待ちなし。guard対象はsuggestion fetchのみ）。
- 設定UI（custom suggestions URL欄のinline型付き案内 + permission要求button。
  非HTTPS URLへのtyped message。既存 `SearchPopupPreference` / `isErrorCheck`
  機構の延長。権限要求は設定画面のuser操作文脈で行う）。
- strings追加（en + ja。案内文・button。`values-ja/strings.xml` は既存）。
- `getSearchUrl`・`KatbinService`・organizer系はコード変更なし。

## Non-goals

- ECH opt-in（AndroidDns / DnsOverHttpsのDNS経路変更を伴う別判断。将来の通信path
  変更として別Issueへ分離する）。
- クリアテキスト許可・network security config追加（`http://` LAN endpointは
  platform既定のままblock。NSCはcleartextをCIDR単位でscopeできない）。
- CT・TLS検証の無効化（domain単位の例外が必要になった場合も別specで判断する）。
- 検索ポップアップ・drawer UI内のエラー表示（説明surfaceはsettings限定。upstream
  bridgeを増やさない）。
- `ACCESS_NETWORK_STATE` 追加（使用しない）。
- organizer diagnosticsのnetwork化・redaction契約変更（#521/C11、DESIGN.md）。
- #522（schema / migration / backup互換）、#526（fork UI）、#527（BAL）の対象面。
- hostnameがprivate IPへ解決されるcaseの静的分類（実測でfailure signatureを確認
  するにとどめる）。

## Behavior scenarios

### Scenario: API 37でLAN URL＋権限許可のsuggestion取得が成功する

Given API 37 emulatorでcustom suggestion URLにhost側HTTPS server（10.0.2.2。
RFC1918として分類される）を設定し、設定画面の要求buttonから
`ACCESS_LOCAL_NETWORK` を許可する
When 検索queryを入力する
Then suggestion取得が成功し候補が表示される。logcatにfetch guardのlogは出ない。

### Scenario: 権限拒否時はfetchが即時短絡し設定画面に型付き案内が出る

Given API 37 emulatorでLAN URLを設定し、permission dialogを拒否する
When 設定画面でcustom suggestions欄の状態を確認し、検索queryを入力する
Then 設定画面にはrationaleとSettings path（App permissions > Nearby devices）の
型付き案内が出る。fetchはnetwork呼び出しの前に空候補へ短絡し（guard logあり）、
タイムアウト待ちが発生しない。persistent変更はしない（zero-write）。

### Scenario: 取消（revoke）後は次回表示・fetchで拒否と同等になる

Given API 37 emulatorでLAN URLと権限を許可済みにする
When Settingsで権限を取り消し、設定画面を再度開く（または検索でfetchが走る）
Then 拒否時と同等の型付き状態になり、fetchは短絡する。

### Scenario: public HTTPS・katbinはAPI 37で現行どおり動く

Given API 37 emulatorで既定suggestion provider（public HTTPS）を使用する
When drawer検索でsuggestionを取得し、custom public HTTPS URLでも取得し、
bugreportをuploadする（失敗caseも試す）
Then いずれも現行どおり動作し、upload失敗時は既存 `action_upload_error` の
typed通知が出る。

### Scenario: API 36とInternet-only設定では新規promptなしで挙動不変

Given API 36 emulator（またはSDK 37未満のruntime）。およびInternet URLのみを
設定したAPI 37端末
When 検索と設定操作を行う
Then 新規permission promptは発生せず、挙動は現行と同じである（Internet URLの
利用者には全APIで新規promptなし）。

## Verification

- emulator matrix（主証跡。保守者実機Pixel 9a / API 37はEpic #516 Phase 3実機
  matrix（owner手順）へ引き継ぐ旨をPRへ明記。#526 / #527と同じ扱い）:
  - API 37 emulator（必須）: LAN許可 / 拒否 / 取消の3状態で、(a) 設定UIの状態表示、
    (b) logcat（fetch guard log、LNP block / timeout signature）、(c) public HTTPS
    suggestion fetch成功・custom public HTTPS fetch成功・katbin upload成功と失敗時
    表示、(d) ECH / CT適用状態の記録 — ECH非適合の根拠（library条件: merged
    OkHttp 5.5.0既定configはDNS HTTPS recordを取得しない）とCT passの根拠
    （public CAのSCT）をlibrary条件とplatform条件に分けて記録する。
  - LAN fetchのE2Eはdebug build + ユーザーCA（debug-overrides）+ host側HTTPS
    server（例: 10.0.2.2 をRFC1918として分類する経路）で行い、release挙動を
    変えないことを明記する。
  - API 36 emulator: 既存public fetch・katbinの回帰なし、新規permission promptなし。
- 既存test suite: organizer unit / repo-contract validator（diagnostics
  local-only契約を含む）がgreen。新規unit test: `SuggestionUrlClassifier`
  （純粋関数。test-audit判断をPRで記録する）。
- 実行コマンド: `./gradlew spotlessCheck`、
  `./gradlew testLawnWithQuickstepGithubDebugUnitTest`（organizer系は必須）、
  `./gradlew assembleLawnWithQuickstepGithubDebug`、
  `python3 tools/repo-contract/validate_repo_contract.py`。
- 書込み経路を追加しないことの確認: 変更diffはmanifest宣言
  （`lawnchair/AndroidManifest.xml`）・search provider配下
  （`lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/`）・
  preferences UI（`lawnchair/src/app/lawnchair/ui/preferences/components/search/`）・
  strings（`lawnchair/res/values/`、`lawnchair/res/values-ja/`）・新classifierに
  限る。DB / model / loader / backup pathは不変。
- `measure_upstream_patch_surface.py` は非該当（上流moduleに触れない）旨をPR本文へ
  記載する。

## Accessibility and localization

- 新規文字列あり（設定画面の型付き案内・permission要求button）。en + ja を同じPRで
  追加する。TalkBack label / focus・font scaling をemulator matrixで確認する。

## Change history

- 2026-10-07: Draft created for #528.
