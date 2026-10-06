# Issue #528 検証エビデンス — targetSdk 37 LAN suggestion (ACCESS_LOCAL_NETWORK guard + typed outcome) / ECH・CT条件記録

- issue: #528
- spec: `specs/528-target37-lan-ech-ct/spec-lite.md` (accepted, tier M)
- app head SHA: `47400f0963` (`chore(528): debug限定NSCでLAN E2E検証基盤を追加`)
- APK: `Lawnchair.16.Dev.(47400f0).github.debug.apk` (`assembleLawnWithQuickstepGithubDebug`)
- applicationId: `app.lawnchair.debug` (debugsuffix)
- 確認日: 2026-10-07
- **修正後再取得 (2026-10-07)**: cleartext検出fix `1600e0c857` (`fix(528): クリアテキスト検出をOkHttp 5.5.0のUnknownServiceExceptionへ対応`) で Item 7a を再取得した。APK `Lawnchair.16.Dev.(1600e0c).github.debug.apk`、同一AVD `issue526_api37_pixel_9a` (serial emulator-5554)。修正後の証跡は `35`〜`38`。
- emulator matrix:
  - API 37: AVD `issue526_api37_pixel_9a` (sdk_gphone64_arm64, Android 17 / SDK 37, google_apis) — serial emulator-5554
  - API 36: AVD `issue142_api36` (sdk_gphone64_arm64, Android 16 / SDK 36, google_apis) — serial emulator-5556
- host側: opensslで生成したテストCA (`CN=Issue528 Test CA`) と `10.0.2.2` 用サーバー証明書 (SAN IP:10.0.2.2)。python3 http.server + ssl で `https://10.0.2.2:8443/suggest` (CA署名) と `:8444` (CA外の自己署名) を提供。OpenSearch JSON `["q",["suggestion one <q>","suggestion two <q>"]]` を返す。アクセスログ: `server-access-8443.log`。
- テストCAは両emulatorのSettings UI (検索 → Install a certificate → CA certificate → Downloads/ca.pem) で**ユーザーCA**としてインストールした。debug source set限定NSC (`src/debug/res/xml/network_security_config.xml` + `src/debug/AndroidManifest.xml`) の `debug-overrides` によりdebug buildのみユーザーCAを信頼する。release契約は不変 (`33-lan-release-manifest-no-debug-nsc.txt`: release merged manifestの `networkSecurityConfig` 属性数=0)。

## 実施結果サマリ

| # | 項目 | 結果 |
|---|---|---|
| 0 | テストCAをユーザーCAとしてインストール (API 37) | PASS |
| 1 | LAN grant E2E (main path) | PASS |
| 2 | 拒否 path (rationale-required + guard log + typed blocked) | PASS |
| 3 | 恒久拒否のsession限定案内 + process跨ぎreset | PASS |
| 4 | grant後のrevoke | PASS |
| 5 | 既定providerのpublic HTTPS (Google) | PASS |
| 6 | custom public HTTPS (DuckDuckGo) | PASS |
| 7 | typed failures (cleartext / tls-ct / generic / not-run lifecycle) | PASS (7aは初回CONTRADICTION → fix `1600e0c857`で**再取得PASS**、下記「修正後再取得」) 、7b/7c/7dはPASS |
| 8 | 初回未要求UX (Settingsへ誘導しない) | PASS |
| 9 | katbin bugreport upload 成功/失敗通知 | **NOT COMPLETED** (debug buildのLeakCanaryがcrash経路を block。原因と証跡は下記) |
| 10 | log redaction契約 | PASS |
| 11 | font scaling 2.0 | PASS |
| 12 | ECH/CT適用状態の記録 | PASS (記録)。CT固有失敗の再現は **not reproduced** (emulatorのbootloaderがlockされsystem store不可変更。specのfallback分析を記載) |
| 13 | API 36 回帰 (新規permission promptなし・LAN fetch成功・public fetch成功) | PASS |

## エビデンス一覧とspec紐付け

### API 37 (必須matrix)

- `00-api37-launcher-home.png` — Lawnchair (Debug) をhome appとして設定した初期状態。
- `01-ca-install-01..05-*.png` — ユーザーCAインストール導線 (検索結果 → install options → warning → file picker → Trusted credentialsのUserタブに `Issue528 Test CA`)。Verification「LAN fetchのE2Eはdebug source set限定NSC + host側HTTPS server」の前提。
- `02-lan-item1-01-settings-custom-before-grant.png` — provider=Custom設定。
- `02-lan-item1-02-settings-lan-section-firsttime.png` — **初回未要求の表示** (`02`/`08`と同一画像を Item 8 証跡としても使用)。statically-local URL設定時にLAN section (rationale/要求button) が出るが、Settingsへの誘導は**しない**。typed outcome は `not-run`。← Scenario「初回未要求ではSettingsへ誘導せず要求buttonを出す」
- `03-lan-item1-03-system-permission-dialog.png` — 設定画面の要求buttonから起動した**system permission dialog** (NEARBY_DEVICES group: "Allow Lawnchair (Debug) to find, connect to, and determine the relative position of nearby devices?")。
- `04-lan-item1-04-settings-after-grant.png` — 許可後の設定UI: "Local network access is granted." (`dumpsys package`: `ACCESS_LOCAL_NETWORK: granted=true ... USER_SET`)。
- `05-lan-item1-05-drawer-search-suggestions.png` — drawer検索 "evidence" → **ローカルサーバーの候補 "suggestion one/two evidence" が表示**。host access log (`server-access-8443.log` 06:17の行, `UA=okhttp/5.5.0`) と対応。許可状態でのfetch成功 = Outcomeの「LAN許可時に提供が継続」。guard は skip されlog出力なし。← Scenario「LAN許可で取得継続」
- `06-lan-item2-01-settings-rationale-required-after-pmrevoke.png` — `pm revoke` 直後の設定UI: rationale text + 再要求button (**rationale-required** 観測状態)。← Scenario「拒否・取消時はsettingsの型付き案内」の観測3状態側
- `07-lan-item2-02-drawer-search-empty.png` — 拒否状態でのdrawer検索: web候補は**即時に空** (10秒hangなし、dialog外観なし)。
- `08-lan-item2-03-guard-log-redacted.txt` — guard log: `suggestion fetch short-circuited: reason=lnp-statically-local permissionGranted=false sdkInt=37` (連続7行)。**query "evidence" / URL / host は一切含まない**。fetchはnetwork呼び出し前に短絡 (server access logに新規行なし)。← Verification (b) logcat + log redaction契約
- `09-lan-item3-01-deny-dontask-dialog.png` — request実行→拒否のdialog。注: grant→revoke後のため、platformのdialogは2回目扱いの "Don't allow (don't ask again)" ボタンのみを提示した (初回拒否→rationale の遷移は `06` が同等の観測状態を示す)。
- `10-lan-item3-02-settings-denied-guidance-session.png` — 同一session内でrequest拒否後: typed outcome **`blocked-by-permission`** ("Suggestions: blocked, local network permission is not granted.") + Settings導線 ("Open app settings" / Nearby devices文言)。← Scenario「恒久拒否...」のsession内側 + Verification (a) session内settings導線切替
- `11-lan-item3-03-after-process-restart-request-button.png` — `am force-stop` + 再起動後: 表示は通常の要求buttonへ戻る (session情報はzero-write、`Suggestions: not run yet` にreset)。← Scenario「恒久拒否後にprocessを跨いでsettingsを再訪すると観測状態の表示へ戻る」
- Item 4 (grant後revoke): `04` → `pm revoke` → `06` の遷移がそのもの (settings再訪でdenied-equivalentのtyped状態、検索は空、guard log再出現)。
- `12-lan-item5-01-provider-google.png` — 既定provider (Google) への切替。custom欄が消える。
- `13-lan-item5-02-drawer-google-suggestions.png` — drawer検索 "weather" → Google public HTTPS候補が表示 (public CA + SCT でCT pass、回帰なし)。← Verification (c) public HTTPS suggestion fetch成功
- `14-lan-item6-01-drawer-ddg-suggestions.png` — custom public HTTPS: `https://duckduckgo.com/ac/?q=%s&type=list` でdrawer検索 "lawnchair" → DDG候補が表示 (OpenSearch list形式でparse成功)。← Verification (c) custom public HTTPS fetch成功
- `16-lan-item7a-01-cleartext-classified-generic-CONTRADICTS.png` — **要対応項目 (下記)**。`http://10.0.2.2:8443/...` + 権限granted → 設定UIは「Suggestions: failed, the address could not be reached.」(generic-network-failure) と表示。期待は `cleartext-blocked`。
- `17-lan-item7a-02-cleartext-logcat-signature.txt` — 上記fetchのlogcat (class名のみでredaction契約は満たす)。
- `18-lan-item7d-02-rogue-url-notrun.png` — URL変更 (`:8444`) 直後、outcomeが `not-run` にclear。← Verification「custom suggestions URL変更時の `not-run` clear」
- `19-lan-item7b-01-tls-ct-failure.png` — **tls-ct-failureの決定的検証**: CA外部の自己署名endpoint `https://10.0.2.2:8444/...` へのfetch → 設定UI「Suggestions: failed, TLS certificate check.」← Verification「tls-ct-failure分類の決定的検証は必須」+ Scenario「custom fetch失敗はsettingsに型付き表示」
- `20-lan-item7b-02-tls-ct-logcat-signature.txt` — `javax.net.ssl.SSLHandshakeException` (class名のみ)。
- `21-lan-item7d-03-url-tweak-resets-notrun.png` — 失敗outcome後にURLへ `&x=1` を追加 → outcomeは `not-run` に戻り、旧failureは表示されない。← Scenario「custom suggestions URLを変更した時点でoutcomeはnot-runへclear...旧URLのfailureが新設定の状態として表示されることはない」
- `22-lan-item7c-01-generic-network-failure.png` — **indeterminate hostname**: `https://no-such-host-528.invalid/...` → 設定UI「Suggestions: failed, the address could not be reached.」(silent emptyではない)。← Scenario「indeterminate hostnameのfetch失敗はsilent emptyでなくtyped状態になる」
- `23-lan-item7c-02-generic-logcat-signature.txt` — `java.net.UnknownHostException` (query "ghost"は出ない)。
- `25-lan-item9-01-bugreport-file-written.txt` — `am crash` 時にcrash handlerがreport fileを書いた証跡 (`Lawnchair (Debug) bug report 2026-10-07_06-53-11`, `commit: 47400f0`)。C4のcrash検知経路は動作。
- `26-lan-item9-02-leakcanary-blocked-trace.txt` — Item 9不成立の原因証跡 (ANR trace): main threadが `leakcanary.ServiceWatcher$install$4$2.invoke` → `IActivityManager$Stub$Proxy.handleApplicationCrash` でblockされ、upload通知導線へ到達する前にsystemがprocessをkill。
- `27-lan-item10-redaction-proof.txt` — **redaction契約の総括証跡**: guard log / UnknownServiceException / SSLHandshakeException / UnknownHostException の全log行に、URL・query・host・userinfo・response body を含まない (category + permission state + SDK_INT / exception class名のみ)。← Verification log redaction契約の確認
- `28-lan-item11-fontscale-2x-settings.png` — `font_scale 2.0` での設定LAN section: 文字の折返しはあるが切欠き・崩れなし。← Verification「TalkBack label / focus・font scaling をemulator matrixで確認」(font scaling 部分)
- `33-lan-release-manifest-no-debug-nsc.txt` — release merged manifest に `networkSecurityConfig` なし (debug CA trust不混入) + debug merged manifest/NSCの内容。← Verification「release merged manifest / artifactにdebug CA trustが入らないこと」

### 修正後再取得 (fix `1600e0c857`、API 37 / 同一AVD)

- `35-lan-item7a-03-https-userCA-positive-control-fixedbuild.png` — **https positive control**: 同一build・同一user CA (`Issue528 Test CA`を`/data/misc/keychain/cacerts-added/d5f0c30b.0`として再導入) で `https://10.0.2.2:8443/suggest?q=%s` のdrawer検索 "evidence" → ローカル候補が表示 (server access log 07:49の行、`UA=okhttp/5.5.0`)。settingsのtyped statusは「Suggestions: last fetch succeeded.」(SUCCESS)。fix buildでCA経路が壊れていないことの対照証跡。
- `36-lan-item7a-04-settings-cleartext-blocked-after-fix.png` — **7a再取得の決定的証跡**: URLを `http://10.0.2.2:8443/suggest?q=%s` に変更してdrawer検索発火後、settings UIは **`cleartext-blocked`** ("Suggestions: blocked, cleartext (HTTP) traffic is not allowed. Use an HTTPS address.") を表示。初回runの `16-*.png` (generic-network-failureと誤分類) がfixで解消された。LAN sectionは「Local network access is granted.」のまま (guard短絡ではなくfetch failure側の分類であることも示す)。
- `37-lan-item7a-05-cleartext-logcat-after-fix.txt` — 上記fetchのlogcat (`CustomWebSearchProvider` tagのみ)。**`java.net.UnknownServiceException`** (class名のみ。メッセージ内容・URL・queryは出ない) でredaction契約も維持。okhttp 5.5.0の予測どおりの例外型。
- `38-lan-item7a-06-drawer-empty-cleartext.png` — 同一fetch時のdrawer検索: web候補は**即時に空** ("Search on Custom"のみ)。cleartext拒否はnetwork I/O前の短絡で、server access logに新規行なし (最終行は07:49のhttps fetch)。1行メモ: **検索flowは空候補で高速に完了し、hang・dialog・crashなし**。

### API 36 (回帰)

- `29-lan-item13-api36-no-lan-section.png` — **SDK 36ではLAN URL (statically-local) を設定してもLAN guard sectionが出ない** (要求button・rationale・granted表示なし。typed status `not-run` のみ)。guardがSDK 37+ gateであることのUI証跡。新規permission promptは一切出ない。← Verification「API 36 emulator: 既存public fetch・katbinの回帰なし、新規permission promptなし」+ Scenario「API 36・Internet-onlyは挙動不変」
- `30-lan-item13-api36-drawer-lan-suggestions.png` — API 36で同一LAN URLのdrawer検索 → ローカル候補が表示 (LNP不存在のためpermission不要の現行動作)。host access log 07:18の行と対応。`34-lan-item13-api36-logcat.txt`: CustomWebSearchProviderのlog行ゼロ (guard不発火)。
- `31-lan-item13-api36-provider-google.png` / `32-lan-item13-api36-google-suggestions.png` — 既定Google providerのpublic HTTPS取得の回帰なし。

### katbin (Item 9) — NOT COMPLETED の詳細

`am crash app.lawnchair.debug` によるcrash毎にLawnchairBugReporterのcrash handlerは起動しreport fileを書く (`25-*.txt`) が、**katbin upload通知 (action: "Upload crash log") は表示されない**。原因: debug buildのみ含まれるLeakCanaryがuncaught exception経路をwrapし、main threadが `handleApplicationCrash` のbinder呼び出しでblockされる (`26-*.txt` のANR trace)。その間にsystemがcrash dialog/stack収集を行いprocessがkillされるため、crash通知からのupload導線 (成功: katb.in URL、失敗: `action_upload_error`) をUIから実行できない。`notifications.size > 3` のanti-spam guard (既存仕様) もsystem通知が多い環境ではpostingをskipする。**debug-onlyのLeakCanary起因であり、#528の変更 (C4は変更なし) とは無関係**。release build / 保守者実機 (Pixel 9a / API 37, Epic #516 Phase 3 owner手順) での成功・失敗通知の確認への引き継ぎを推奨する。airplane modeを使う失敗caseも同導線のため未実施 (airplane modeは最終的に有効化していないため復元作業も不要)。

## 要対応 → 解消済み (fix `1600e0c857`)

**cleartext-blocked が generic-network-failure に分類される** (`16-*.png`, `17-*.txt`) — **初回runで検出、`1600e0c857` で修正し再取得PASS (`35`〜`38`)**

- 初回runの手順と現象: 権限granted / suggestions URLに `http://10.0.2.2:8443/suggest?q=%s` を設定 / drawer検索でfetch発火 → 設定UIは「Suggestions: failed, the address could not be reached.」(generic-network-failure) と表示。期待は `cleartext-blocked`。
- 原因 (確定): `CustomWebSearchProvider.mapFailureToOutcome` が例外メッセージの `"Cleartext HTTP traffic"` を見ていたが、merged OkHttp 5.5.0 のcleartext拒否は `java.net.UnknownServiceException("CLEARTEXT communication to <host> not permitted by network security policy")` で一致せず、`SSLException` でもないためfallbackの `GENERIC_NETWORK_FAILURE` に入っていた。
- 修正 (`1600e0c857`): 分類を `java.net.UnknownServiceException` の**型ベース**検出へ変更 (大文字小文字を無視する `"CLEARTEXT"` message-category fallbackを併設、メッセージ内容はlogに出さない契約は維持)。`SSLException` → TLS_CT_FAILURE とgeneric fallbackは不変。分類はpure関数 `mapFailureToOutcome` (`SuggestionFetchOutcome.kt`) としてunit testで回帰担保 (`SuggestionFetchOutcomeLifecycleTest`、pre-fix logicで3 testがfailすることをred-greenで確認)。
- 再取得結果: settings UIは `cleartext-blocked` を表示 (`36`)、logcatは `java.net.UnknownServiceException` のみ (`37`)、drawer検索は即時に空でserver access logに新規行なし (`38`)。specのtyped outcome契約どおり。

## 観測 (契約適合の范围内だが記録する)

1. 初回未要求の表示は「要求buttonのみ」でrationale textは表示されない (`02-*.png`)。rationale textは `shouldShowRequestPermissionRationale()==true` のrationale-required状態 (`06-*.png`) で表示される。Settings誘導しないというspecの核心契約は満たす。
2. 初回のsystem permission dialog表示中、dialog背面の設定UIが一瞬 "Open app settings" 案内に切替わって見える (`03-*.png` 背景)。dialog表示中は `shouldShowRequestPermissionRationale()` がfalseを返すため。dismiss後の状態遷移は正しい。装飾的な過渡状態。
3. 既定の "Maximum web suggestion delay" (1000ms) はemulator上の初回TLS handshakeでtimeoutし得る (logcatに `TimeoutCancellationException`)。検証では設定を4500msに上げた (test harness設定でありアプリ変更ではない)。実機のLAN (常時接続) ではエンドユーザー設定の範囲内。
4. grant→`pm revoke`後の再requestでは、platformのdialogが初回から "Don't allow (don't ask again)" のみを提示した (platform仕様)。単純「1回目の拒否→rationale」の遷移はdialog経由では作れず、rationale-required状態はrevoke直後の観測 (`06`) で確認した (specの3状態定義は観測情報のみで導出され、到達経路に依存しない)。

## Item 12: ECH / CT適用状態の記録

- **ECH — platform条件は満たす、library条件は不成立 → ECH非適用**:
  - platform条件: API 37 (Android 17) emulatorでtargetSdk 37のappとして動作 (behavior changes 17のECH by default適用対象)。
  - library条件: merged OkHttp 5.5.0 (`gradle/libs.versions.toml:108`) の既定configはDNS HTTPS (TYPE65) record取得経路 (AndroidDns / DnsOverHttps) を持たず、ECHはopt-in。本appのcustom suggestion fetch (`CustomWebSearchProvider` のplain `OkHttpClient()`) も既定provider fetchもECH/GREASEを送らない。
  - 結論: ECH library条件が未統合のためECH failure modes (接続失敗・degrade) はこの構成では発生し得ない。opt-inしない方針どおり。
- **CT — platform条件で既定有効、public endpointはpass、custom/user anchorは免除**:
  - pass証跡: (5) Google既定provider、(6) DuckDuckGo custom public HTTPS の両方がAPI 37で取得成功 (public CA + SCT)。API 36でも (13) でGoogle取得成功。
  - 免除証跡: (1) ユーザーCA (test CA) でLAN endpoint `https://10.0.2.2:8443` が取得成功 — custom / user trust anchorはCT免除のplatform ruleどおり。
  - domain単位のopt-outは使用していない (releaseのNSC/trust policy不変、`33-*.txt`)。
- **CT固有のplatform rejectionの再現 — not reproduced (best-effort・不成立)**: `adb root` は成功したが、`adb remount` / `adb disable-verity` が "Device must be bootloader unlocked" で失敗し、test CAをsystem storeへinstallする再現手順がこのemulator imageでは不可能。specのfallbackどおり、上記のplatform条件 (target 37でCT既定有効、custom/user anchor免除) とlibrary条件の分析を代替evidenceとする。

## 未実施・引き継ぎ

- Item 9 (katbin通知の成功/失敗E2E) — 上記のとおり。保守者実機 (Pixel 9a / API 37) とrelease buildでの確認をEpic #516 Phase 3へ引き継ぐ (#526/#527と同じ扱い)。
- Item 12のCT失敗signature — not reproduced (上記)。
- indeterminateの「hostname→private IP解決」subcase — spec明記の限界のままとする (typed failure `generic-network-failure` での扱いは `22-*.png` で確認済み)。
