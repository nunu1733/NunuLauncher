---
issue: "#528"
status: accepted
tier: M
requirements: []
updated: 2026-10-07
---

# targetSdk 37の通信経路 — LAN suggestionのpermission guardとECH/CTの確認・説明

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
通信の追加（新endpoint・新DNS経路）も行わない。custom suggestion fetchへのLAN権限
guard、fetch結果の型付きoutcome分類とsettings表示、Android 17で既定化するECH/CTの
適用状態確認・失敗の説明のみを扱い、検索UI構造と既存の適用・復旧経路の契約は変更しない。
Android/platform APIの新規扱いのため外部参照調査（External reference scan）の対象であり、
記録はPrior artへ行う
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
  なおplatformのlocal network定義はlink-local / directly-connected / stub network /
  multicast・broadcastもlocalに含み、URL文字列だけでは完全判定できない
  （[local-network-definition](https://developer.android.com/privacy-and-security/local-network-definition)）。
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
| C1 | custom suggestion fetch（LAN guard対象） | `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/CustomWebSearchProvider.kt:45,57-83` | URL template分類に基づくpermission guard + fetch結果の型付きoutcome管理とsettings表示（下記C1の詳細） |
| C2 | 既定suggestion fetch（Google/DDG等public HTTPS） | 同dir `BuiltInWebSearchProviders.kt` | 変更なし。API 37/36で取得成功の回帰確認のみ |
| C3 | 検索URLのbrowser handoff | `CustomWebSearchProvider.kt:85-92`（`getSearchUrl`。app側fetchなし） | 対象外。通信主体はbrowserであり、LAN権限・ECH/CTはbrowser側の契約に従う（T14の「分ける」） |
| C4 | katbin bugreport upload | `lawnchair/src/app/lawnchair/bugreport/KatbinService.kt:14,19`、`UploaderService.kt:53` | 変更なし。API 37/36で成功/失敗の確認のみ（失敗は既存 `action_upload_error` のtyped通知） |
| C5 | organizer diagnostics / exchange / backup | `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt`、`tools/repo-contract/validate_diagnostics_contract.py` | 対象外・維持確認。local / SAF専用でnetwork transportを持たない契約（DESIGN.md 4.5）をtestで強制。変更せずgreenを維持する |

### C1の詳細: 分類・guard・outcome・設定案内

- **分類**（`SuggestionUrlClassifier`。新file。`CustomWebSearchProvider.kt` 近傍）:
  入力はsuggestion URL **template**（`%s` placeholderを含む文字列）とし、
  canonicalizationを固定する — literalな `%s` を安全なdummy値へ**一度だけ**置換して
  からparseする（例: `java.net.URI`）。settings UIとfetch guardはこの同一
  canonicalizationを共有し、有効/無効判定が食い違わない。出力は4値の純粋関数で、
  platform型をinterfaceに出さない。各値は「address文字列だけで断定できる集合」で
  定義する:
  - `statically-local` — address文字列だけでlocalと断定できる集合。IPv4 RFC1918
    （10/8、172.16/12、192.168/16）、CGNAT 100.64/10、link-local v4（169.254/16）、
    IPv4 multicast（224.0.0.0/4）、IPv4 broadcast（255.255.255.255）、IPv6
    link-local（fe80::/10）、IPv6 multicast（ff00::/8）、および `.local` hostname。
    multicast / broadcastを含むのは、platformのlocal network定義がIPv4/IPv6
    multicastとIPv4 broadcastをlocalに含むためである。
  - `statically-public` — address文字列だけで非localと断定できる集合。上記local
    集合のいずれにも一致しないIPv4 unicast。
  - `indeterminate` — `.local`以外のhostname（すべて）と、route依存のIPv6 unicast
    literal（global unicast・ULA fc00::/7を含む）。Android 17のIPv6判定は
    directly-connected / stub route依存であり、ULAだから常にpermission対象とは
    限らず（VPN trafficの除外もある）、address文字列だけではlocal / 非localを
    断定できない。**indeterminateをpublicとして扱わない**ことを契約とする。
  - `無効` — parse不能・scheme不在等（URL・placeholderとも）。
  platformのlocal network定義（directly-connected / stub networkを含む）への完全
  一致は主張しない。静的判定の限界であり、収まらないcaseは `indeterminate` と
  typed failure契約で扱う（Baseline参照）。
- **fetch guard**（`CustomWebSearchProvider.getSuggestions`）: `statically-local` かつ
  `ACCESS_LOCAL_NETWORK` 未許可のとき（SDK 37+ gateのみ。API 36以下では評価しない）、
  network呼び出しの前に空候補をemitしtyped logを出す。タイムアウト待ちを作らない。
  `indeterminate` はguardで短絡せずfetchを試み、その結果は下記のtyped failure契約へ
  流す（権限未許可でのtimeout / connect失敗は「LAN権限不足の可能性を含む到達失敗」
  として型付き扱いし、断定はしない）。`statically-public`・権限あり・API 36以下の
  経路は現行どおりで変更しない。
- **fetch結果の型付きoutcome**: fetch結果を最低でも
  `success / blocked-by-permission(LNP) / cleartext-blocked / tls-ct-failure /
  http-error / generic-network-failure` の型へ分類する。providerはメモリ内に
  「現在の設定に対する最終outcome」を保持し、設定画面のcustom provider欄がそれを
  読んで型付き状態として表示する。outcomeのlifecycleは次のとおり: custom
  suggestions URL（template）の変更時にoutcomeを `not-run` へclearし、fetch開始時に
  使用したcanonicalized templateが現在値と一致する場合のみ結果をpublishする
  （遅い旧callが新設定のoutcomeを上書きしない）。検索ポップアップ・drawer UIは
  変更しない（Non-goals。settingsが失敗説明のsurface）。TLS / CT failureはgeneric
  でなく `tls-ct-failure` としてsettingsへ出る。outcomeはメモリのみでpersistent
  書込みはしない（zero-write維持）。
- **設定UI**（custom suggestions URL欄）: 権限状態は観測可能なplatform情報のみから
  導出する3状態で扱う — `granted`（`isGranted`）/ `rationale-required`（未granted
  かつ `shouldShowRequestPermissionRationale()` がtrue。rationale表示 + 再request
  button）/ `denied-no-rationale`（未grantedかつrationaleがfalse。**初回未要求と
  恒久拒否の両方を含む** — 公開APIでは両者を区別できない）。settings導線
  （既存のapp permission settings導線pattern — `PermissionDialog` の
  onGoToSettings、`openAppPermissionSettings()`）への切替はsession内のみの契約とし
  （zero-write維持。persistentな履歴保持はしない）、同一session内でユーザーが
  requestを実行し拒否された結果としてrationale==falseへ遷移した場合に限り、その
  sessionではsettings導線を優先案内する。初回未要求の既定表示はrationale付き要求
  buttonとし、Settingsへは誘導しない。`revoked-or-reset` は独立状態として設けない
  — 権限取消・reset後は再観測で上記3状態のいずれか（拒否相当）へ自然に戻る。
  rationale-requiredではSettings path（App permissions > Nearby devices）の文言は
  補助説明に留める。NEARBY_DEVICES groupの別permission（Bluetooth等）が既知付済み
  の場合、dialogが出ずに即grantedになり得る。権限要求は設定画面のuser操作文脈で
  行い（launcher可視。背景起動の新規経路は作らない）。URL欄のvalidation（非HTTPS・
  無効URL/placeholder）も同一canonicalization結果を使い、既存 `isErrorCheck` 機構の
  延長でtyped messageを出す（`http://` LAN endpointはplatform既定のままblockされ
  る）。
- **log契約**: logcatに出すのは `reason category + permission state + SDK_INT`
  のみ。raw query / full URL / userinfo / response body は出さない。hostを出す
  場合は最小限・sanitizedに限る。既存catch log
  （`CustomWebSearchProvider` の `Error during suggestion retrieval`）もこの基準へ
  合わせて見直す。
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
- Android公式: [Local network definition](https://developer.android.com/privacy-and-security/local-network-definition)。確認日2026-10-07。採用: platform判定がlink-local / directly-connected / stub network / multicast・broadcastを含みURL文字列だけでは完全再現できないという限界の根拠。classifierを `statically-local / statically-public / indeterminate / 無効` の4値にとどめ、`statically-local` / `statically-public` をaddress文字列だけで断定できる集合に限定する（multicast / broadcastをlocalへ、route依存のIPv6 unicast literalをindeterminateへ）。indeterminateをpublic扱いしない契約の根拠にする。
- Android公式: [behavior changes 17 — ECH by default](https://developer.android.com/about/versions/17/behavior-changes-17#ech-by-default)。確認日2026-10-07。採用: ECH適用をplatform条件とlibrary条件に分けて記録する枠組み（library未対応ではECH無効、failureはGREASEへのdegradeで接続失敗にならない）。
- Android公式: [behavior changes 17 — CT](https://developer.android.com/about/versions/17/behavior-changes-17#ct-default) と [Network Security Config](https://developer.android.com/privacy-and-security/security-config)。確認日2026-10-07。採用: target 37でのCT既定有効、custom / user trust anchor とlocalhostの免除、domain単位opt-outの存在（本specでは不使用）を確認面の根拠にする。debug source set限定NSCの `debug-overrides`（ユーザーCA信頼）も同文書を根拠にする（release契約は不変）。
- OkHttp CHANGELOG 5.5.0（https://github.com/square/okhttp/blob/master/CHANGELOG.md）。確認日2026-10-07。採用: ECHがopt-in（DNS HTTPS record経路が前提）であることの確認。merged libraryの既定設定はECH不適合というVerification前提。
- Android公式: [Request runtime permissions](https://developer.android.com/training/permissions/requesting)。確認日2026-10-07。採用: 設定文脈（user操作起点）でのrationale付き要求、`shouldShowRequestPermissionRationale()` が初回未要求と恒久拒否を区別できないことを踏まえた観測3状態（granted / rationale-required / denied-no-rationale）の導出、同一session内のrequest結果に基づくsettings導線への切替。repo内の既存pattern（`SearchProviderPreference.kt:136` のcontacts行 `rememberPermissionState`、`ui/preferences/components/PermissionDialog.kt` の `isPermanentlyDenied` / `onGoToSettings`、`app.lawnchair.util.openAppPermissionSettings`）と整合させる。
- それ以外の外部実装例は省略（調査済み）。対応はplatform契約の追従であり、新規設計の採用がない。

## Outcome

target 37環境で、LAN宛先（statically-localと分類されるURL）のcustom suggestionは
`ACCESS_LOCAL_NETWORK` 許可時に提供が継続し、拒否・取消時は黙示的な空候補ではなく
設定画面の型付き案内で説明される（guard対象は即時短絡し、タイムアウト待ちを
作らない）。hostname（`.local`を除く）とroute依存のIPv6 unicast literal
（indeterminate）はlocal / 非localを断定せず、custom fetchの結果は
`success / blocked-by-permission / cleartext-blocked / tls-ct-failure /
http-error / generic-network-failure` の型付きoutcomeとしてsettingsへ表示され、
TLS / CT不適合時の失敗もgenericな空候補への黙示的劣化で終わらない。outcomeは
custom suggestions URLの変更時に `not-run` へclearされ、fetch開始時のtemplateが
現在値と一致する場合のみpublishされる。Internet URL
のみの利用者とpublic HTTPS（既定suggestion、katbin upload）はAPI 37/36で現行どおり
動作し、新規permission promptは発生しない。ECHはopt-inせず、CTは無効化しない。
両者の適用状態はlibrary条件とplatform条件を分けてVerification evidenceへ記録する。
releaseのnetwork security config / trust policyは不変である。
organizer / backupのlocal-only契約は不変である。

## Scope

- manifest権限宣言（`lawnchair/AndroidManifest.xml`。INTERNET `:22` の近傍）。
- `SuggestionUrlClassifier`（新file。`CustomWebSearchProvider.kt` 近傍。URL
  **template** — `%s` を安全なdummyへ一度だけ置換してからparseし、settings UIと
  fetch guardで共有 — → `statically-local / statically-public / indeterminate /
  無効` の純粋関数。`statically-local` の分類対象はaddress文字列だけでlocalと
  断定できるIPv4 RFC1918 / CGNAT 100.64/10 / link-local v4 / IPv4 multicast /
  IPv4 broadcast / IPv6 link-local / IPv6 multicastのIP literalと `.local`
  hostname、`statically-public` はこれらに一致しないIPv4 unicast、route依存の
  IPv6 unicast literal（global / ULA）はindeterminate。unit test対象は
  `224.0.0.1` / `255.255.255.255` / `[ff02::1]` / `fd00::1` → indeterminate /
  `2001:db8::1` → indeterminate を含む）。
- `CustomWebSearchProvider` のfetch guard（SDK 37+ gate。guard対象は
  `statically-local` + 権限未許可のみ。guard時は空候補 + typed log、タイムアウト
  待ちなし。`indeterminate` は短絡せずfetchし結果をtyped outcomeへ流す）。
- fetch結果の型付きoutcome契約（provider内のメモリ保持と設定画面のcustom provider
  欄からの読み取り表示。`success / blocked-by-permission(LNP) / cleartext-blocked /
  tls-ct-failure / http-error / generic-network-failure`。lifecycleはcustom
  suggestions URL変更時の `not-run` clearと、fetch開始時のcanonicalized templateが
  現在値と一致する場合のみのpublishを含む。既存catch logのredaction見直しを含む）。
- debug source set限定のnetwork security config（`lawnchair/src/debug/res/xml/` 新規
  + debug用manifest overlayで `android:networkSecurityConfig` を参照。
  `debug-overrides` でユーザーCAを信頼。release artifactには入らない）。
- 設定UI（custom suggestions URL欄のinline型付き案内 + permission要求button。
  観測可能情報のみで導出する3状態permission表示（granted / rationale-required /
  denied-no-rationale）と、同一session内のrequest結果に基づくsettings導線切替
  （persistent履歴なし・zero-write）。非HTTPS・無効templateへのtyped message。既存
  `SearchPopupPreference` / `isErrorCheck` 機構の延長。権限要求は設定画面のuser操作
  文脈で行う）。
- log契約（reason category + permission state + SDK_INTのみ。raw query / full URL /
  userinfo / response body禁止。hostは最小限・sanitized）。
- strings追加（en + ja。案内文・button・失敗状態の文言。`values-ja/strings.xml` は
  既存）。
- `getSearchUrl`・`KatbinService`・organizer系はコード変更なし。

## Non-goals

- ECH opt-in（AndroidDns / DnsOverHttpsのDNS経路変更を伴う別判断。将来の通信path
  変更として別Issueへ分離する）。
- クリアテキスト許可・**release**のnetwork security config / trust policy変更
  （debug source set限定のNSC追加は行う。`http://` LAN endpointはplatform既定の
  ままblock。NSCはcleartextをCIDR単位でscopeできない）。
- CT・TLS検証の無効化（domain単位の例外が必要になった場合も別specで判断する）。
- 検索ポップアップ・drawer UI内のエラー表示（説明surfaceはsettings限定。upstream
  bridgeを増やさない）。
- route-levelのLNP完全再現（directly-connected / stub network / multicast・
  broadcastを含むplatform判定をURL文字列から再現しない。indeterminateのtyped扱いと
  限界明記で対応する）。
- `ACCESS_NETWORK_STATE` 追加（使用しない）。
- organizer diagnosticsのnetwork化・redaction契約変更（#521/C11、DESIGN.md）。
- #522（schema / migration / backup互換）、#526（fork UI）、#527（BAL）の対象面。
- hostnameがprivate IPへ解決されるcaseの事前静的分類（解決前はindeterminateとして
  fetchし、結果をtyped outcomeで扱う。実測でfailure signatureを確認するにとどめる）。

## Behavior scenarios

### Scenario: API 37でLAN URL（statically-local）＋権限許可のsuggestion取得が成功する

Given API 37 emulatorでcustom suggestion URLにhost側HTTPS server（10.0.2.2。
statically-localと分類される）を設定し、設定画面の要求buttonから
`ACCESS_LOCAL_NETWORK` を許可する
When 検索queryを入力する
Then suggestion取得が成功し候補が表示される。logcatにfetch guardのlogは出ない。

### Scenario: 権限拒否時はfetchが即時短絡し設定画面に型付き案内が出る

Given API 37 emulatorでLAN URLを設定し、permission dialogを拒否する
When 設定画面でcustom suggestions欄の状態を確認し、検索queryを入力する
Then `shouldShowRequestPermissionRationale()` がtrueのrationale-required状態では
rationaleと再request buttonが出る。同一session内でrequestを実行し拒否された結果
rationale==falseへ遷移した場合に限り、そのsessionではapp permission settingsへの
導線へ切替わる。fetchはnetwork呼び出しの前に空候補へ短絡し、settingsには
`blocked-by-permission(LNP)` の型付き状態が出る（guard logあり）。タイムアウト
待ちとpersistent変更は発生しない（zero-write）。

### Scenario: 取消（revoke）後は再観測で拒否相当の観測状態へ戻る

Given API 37 emulatorでLAN URLと権限を許可済みにする
When Settingsで権限を取り消し、設定画面を再度開く（または検索でfetchが走る）
Then 独立状態は設けず、再観測で未grantedの観測状態（rationale-required /
denied-no-rationaleのいずれか）として拒否時と同等の型付き状態になり、fetchは
短絡する。

### Scenario: 初回未要求ではSettingsへ誘導せず要求buttonを出す

Given API 37 emulatorでLAN URLを設定し、`ACCESS_LOCAL_NETWORK` を一度も要求して
いない
When 設定画面でcustom suggestions欄の状態を確認する
Then `shouldShowRequestPermissionRationale()` がfalseでも初回未要求と恒久拒否は
公開APIでは区別できないため、既定表示はrationale付きの要求buttonとし、app
permission settingsへは誘導しない。settings導線への切替は同一session内でrequestを
実行し拒否された結果rationale==falseへ遷移した場合に限る（同一session限定）。

### Scenario: 恒久拒否後にprocessを跨いでsettingsを再訪すると観測状態の表示へ戻る

Given API 37 emulatorでLAN URLを設定し、同一session内でrequestを拒否して
settings導線へ切替わった後、processを終了して再起動する
When 設定画面を再度開く
Then 観測上はdenied-no-rationaleであり、session情報が保持されていないため表示は
通常の要求button（rationale付き要求）へ戻ることを正とする。settings導線の再確認は
そのsession内でrequestを実行した結果に基づいて再度行う。

### Scenario: public HTTPS・katbinは現行どおりで、custom fetch失敗はsettingsに型付き表示される

Given API 37 emulatorで既定suggestion provider（public HTTPS）を使用する
When drawer検索でsuggestionを取得し、custom public HTTPS URLでも取得し、
bugreportをuploadする（失敗caseも試す）。さらにcustom URLでTLS / CT不適合や
HTTP errorになるfetchを起こす
Then 既定providerとkatbinは現行どおり動作し、upload失敗時は既存
`action_upload_error` のtyped通知が出る。custom fetchの失敗は検索UIを汚さず、
settingsのcustom provider欄に `tls-ct-failure` / `http-error` 等の型付き状態として
表示される。custom suggestions URLを変更した時点でoutcomeは `not-run` へclearされる
ため、旧URLのfailureが新設定の状態として表示されることはない。

### Scenario: indeterminate hostnameのfetch失敗はsilent emptyでなくtyped状態になる

Given API 37 emulatorで存在しないhostnameを含むcustom suggestion URL
（例: `https://no-such-host.invalid/suggest?q=%s`）を設定する
When 検索queryを入力し、設定画面を開く
Then fetchはguardで短絡されず実行され、失敗は空候補とlogのみで終わらず、settings
に `generic-network-failure` 等の型付き状態として表示される（解決前のpublic断定は
しない）。

### Scenario: API 36・Internet-onlyは挙動不変で、NEARBY_DEVICES事前付済みではdialogなしでgranted

Given API 36 emulator（またはSDK 37未満のruntime）。およびInternet URLのみを設定
したAPI 37端末。さらにNEARBY_DEVICES groupの別permission（Bluetooth等）を既に許可
したAPI 37端末
When 検索と設定操作を行う
Then API 36 / Internet-onlyでは新規permission promptは発生せず挙動は現行と同じ
である。NEARBY_DEVICES事前付済みのLAN URL設定ではdialogが出ずに即grantedになる
（prompt回数の受入はこのcaseを含めて判定する）。

## Verification

- emulator matrix（主証跡。保守者実機Pixel 9a / API 37はEpic #516 Phase 3実機
  matrix（owner手順）へ引き継ぐ旨をPRへ明記。#526 / #527と同じ扱い）:
  - API 37 emulator（必須）: LAN許可 / 拒否 / 取消の3状態で、(a) 設定UIの状態表示
    （観測3状態permission + session内settings導線切替 + 型付きoutcome。初回未要求
    ではSettingsへ誘導しないこと、恒久拒否後にprocessを跨いでsettingsを再訪した
    場合は通常の要求button表示へ戻ることを含む）、(b) logcat（fetch guard log、log
    redaction契約の確認、LNP block / timeout signature）、(c) public HTTPS
    suggestion fetch成功・custom public HTTPS fetch成功・katbin upload成功と失敗時
    表示・custom fetch失敗時のsettings型付き表示、(d) ECH / CT適用状態の記録 —
    ECH非適合の根拠（library条件: merged OkHttp 5.5.0既定configはDNS HTTPS recordを
    取得しない）とCT passの根拠（public CAのSCT）をlibrary条件とplatform条件に分けて
    記録する。
  - LAN fetchのE2Eはdebug source set限定NSC（`lawnchair/src/debug/res/xml/` +
    debug用manifest overlayで `android:networkSecurityConfig` を参照。
    `debug-overrides` でユーザーCAを信頼）+ host側HTTPS server（例: 10.0.2.2 を
    statically-localとして分類する経路）で行う。releaseのNSC / trust policyは
    不変であり、**release merged manifest / artifactにdebug CA trustが入らない
    こと**をmerged manifest確認で検証する。
  - indeterminate実機確認は存在しないhostnameでtyped状態になることで契約を検証する。
    「hostname→private IP解決」のsubcaseは限界として明記のままとする。
  - `tls-ct-failure` 分類の決定的検証は必須: 通常のTLS handshake / certificate
    failure（例: 信頼されない自己署名endpoint）で `tls-ct-failure` への分類と
    settings表示を検証する。CT固有のplatform rejectionの再現（system store版test
    CA、adb root可能なgoogle_apis emulator image）はbest-effortの追加evidenceとし、
    成功した場合はsignatureを記録する。不成立の場合はplatform条件とlibrary条件の
    分析を代替evidenceとして明示する方針をPRへ記載する。
  - API 36 emulator: 既存public fetch・katbinの回帰なし、新規permission promptなし。
- 既存test suite: organizer unit / repo-contract validator（diagnostics
  local-only契約を含む）がgreen。新規unit test: `SuggestionUrlClassifier` の
  canonicalization契約（`%s` template置換、IPv6 literal、IPv4/IPv6 multicast・
  IPv4 broadcast（`224.0.0.1` / `255.255.255.255` / `[ff02::1]` →
  statically-local）、ULA / global IPv6 literal（`fd00::1` / `2001:db8::1` →
  indeterminate）、`.local`、public IPv4、壊れたURL / placeholder、indeterminate
  hostname → 各state）、outcome lifecycle契約（custom suggestions URL変更時の
  `not-run` clear、fetch開始時のcanonicalized template一致時のみpublish —
  URL変更後に旧failureを表示しない）、log redaction契約（純粋関数。test-audit判断を
  PRで記録する）。
- 実行コマンド: `./gradlew spotlessCheck`、
  `./gradlew testLawnWithQuickstepGithubDebugUnitTest`（organizer系は必須）、
  `./gradlew assembleLawnWithQuickstepGithubDebug`、
  `python3 tools/repo-contract/validate_repo_contract.py`。
- 書込み経路を追加しないことの確認: 変更diffはmanifest宣言
  （`lawnchair/AndroidManifest.xml`）・search provider配下
  （`lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/`。outcome
  契約を含む）・preferences UI
  （`lawnchair/src/app/lawnchair/ui/preferences/components/search/`）・
  strings（`lawnchair/res/values/`、`lawnchair/res/values-ja/`）・新classifier・
  debug source set（`lawnchair/src/debug/`。NSC XML + manifest overlay）に限る。
  DB / model / loader / backup pathとrelease manifest / trust policyは不変。
- `measure_upstream_patch_surface.py` は非該当（上流moduleに触れない）旨をPR本文へ
  記載する。

## Accessibility and localization

- 新規文字列あり（設定画面の型付き案内・permission要求button・失敗状態の文言。
  permission 3状態 + outcome 6型の表示分だけbaseline比で増える）。en + ja を同じPRで
  追加する。TalkBack label / focus・font scaling をemulator matrixで確認する。

## Change history

- 2026-10-07: Draft created for #528.
- 2026-10-07: Review round 1（PR #542コメント）対応 — classifier契約をtemplate入力+4値（statically-local / statically-public / indeterminate / 無効）へ再定義（%s canonicalization共通化）、typed fetch failure契約とsettings surfaceを追加、debug source set限定NSCでLAN E2Eを成立させrelease契約を不変に明確化、permission state machineを4状態へ定義、log redaction契約を追加、Scenario/Verificationを同期。
- 2026-10-07: Review round 2（PR #542コメント）対応 — classifierのstatically集合を「addressだけで断定できる範囲」へ限定（multicast/broadcastをlocalへ、route依存のIPv6 global/ULAをindeterminateへ）、permission状態を観測可能情報のみで導出する3状態+session内遷移へ再定義（revoked-or-reset廃止・zero-write維持）、outcomeのlifecycle（template変更時clear・一致時のみpublish）を追加、tls-ct-failureの決定的検証を必須化、Change historyの状態数表記を修正。
- 2026-10-07: Review round 3（PR #542コメント）で全指摘resolved・新規指摘なしを確認
  （head `72517bc165` のCI green確定後）。acceptedへ遷移。
