# Issue #528 検証エビデンス — targetSdk 37 LAN suggestion (ACCESS_LOCAL_NETWORK guard + typed outcome) / ECH・CT条件記録

- issue: #528
- spec: `specs/528-target37-lan-ech-ct/spec-lite.md` (accepted, tier M)
- app head SHA: `47400f0963` (`chore(528): debug限定NSCでLAN E2E検証基盤を追加`)
- APK: `Lawnchair.16.Dev.(47400f0).github.debug.apk` (`assembleLawnWithQuickstepGithubDebug`)
- applicationId: `app.lawnchair.debug` (debugsuffix)
- 確認日: 2026-10-07
- **修正後再取得 (2026-10-07)**: cleartext検出fix `1600e0c857` (`fix(528): クリアテキスト検出をOkHttp 5.5.0のUnknownServiceExceptionへ対応`) で Item 7a を再取得した。APK `Lawnchair.16.Dev.(1600e0c).github.debug.apk`、同一AVD `issue526_api37_pixel_9a` (serial emulator-5554)。修正後の証跡は `35`〜`38`。
- **review round 1 再取得 (2026-10-07)**: PR #543 implementation review (blocking 1-3 + medium 4-6, すべてACCEPTED) の修正 `f102d72144` (`fix(528): review round 1対応`) で F1-F4 oracle と server log 一元化・TalkBack evidence を再取得した。APK `Lawnchair.16.Dev.(f102d72).github.debug.apk`、AVD `issue526_api37_pixel_9a` (emulator-5554) + `issue142_api36` (emulator-5556)。証跡は `45`〜`63` (下記「Review round 1 再取得」)。検証クエリは `timeoutoracle` / `invalidq` / `ctfail` / `positive` / `apitwosix` 等のtest用文字列のみ (redaction確認済み)。
- emulator matrix:
  - API 37: AVD `issue526_api37_pixel_9a` (sdk_gphone64_arm64, Android 17 / SDK 37, google_apis) — serial emulator-5554
  - API 36: AVD `issue142_api36` (sdk_gphone64_arm64, Android 16 / SDK 36, google_apis) — serial emulator-5556
- host側: opensslで生成したテストCA (`CN=Issue528 Test CA`) と `10.0.2.2` 用サーバー証明書 (SAN IP:10.0.2.2)。python3 http.server + ssl で `https://10.0.2.2:8443/suggest` (CA署名) と `:8444` (CA外の自己署名) を提供。OpenSearch JSON `["q",["suggestion one <q>","suggestion two <q>"]]` を返す。アクセスログ: 初回runは `server-access-8443.log` (05:44-06:17の窓のみ)。**review round 1 runでは新規に単一serverを起動し、完全なaccess logを `61-lan-r1-server-access.log` としてcommitした (10:35-11:59の単一窓。以下のserver log引用はすべてこのファイルを正本とする)**。
- テストCAは両emulatorのSettings UI (検索 → Install a certificate → CA certificate → Downloads/ca.pem) で**ユーザーCA**としてインストールした。debug source set限定NSC (`src/debug/res/xml/network_security_config.xml` + `src/debug/AndroidManifest.xml`) の `debug-overrides` によりdebug buildのみユーザーCAを信頼する。release契約は不変 (`33-lan-release-manifest-no-debug-nsc.txt`: release merged manifestの `networkSecurityConfig` 属性数=0)。
- **CA再導入の補足 (review round 1 run)**: 初回runのCA鍵はhost側に残っていないため、新規に `CN=Issue528 Test CA` を生成した。root file placement (`/data/misc/keychain/cacerts-added/<hash>.0`) だけではruntimeに信頼されず (SSLHandshakeException、reboot後も不変)、両AVDとも**Settings UI経由のユーザーCAインストールを行って初めて信頼された** (`63-lan-r1-api36-ca-installed.png` の "CA certificate installed" toast等)。

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
| 9 | katbin bugreport upload 成功/失敗通知 | PASS (**release署名buildで再取得**、下記「Item 9再取得 (release build)」) |
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
- `05-lan-item1-05-drawer-search-suggestions.png` — drawer検索 "evidence" → **ローカルサーバーの候補 "suggestion one/two evidence" が表示**。host access logとの対応は、**review round 1 runで同一build系のpositive controlとして `61-lan-r1-server-access.log` 11:33:54の `GET /suggest?q=positive...` 行群 (`UA=okhttp/5.5.0`) で再取得・検証済み** (初回runのlogは初回sessionの窓のみ保管のため `server-access-8443.log` に当該行なし。同logは参考として保持)。許可状態でのfetch成功 = Outcomeの「LAN許可時に提供が継続」。guard は skip されlog出力なし。← Scenario「LAN許可で取得継続」
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

- `35-lan-item7a-03-https-userCA-positive-control-fixedbuild.png` — **https positive control**: 同一build・同一user CA (`Issue528 Test CA`を`/data/misc/keychain/cacerts-added/d5f0c30b.0`として再導入) で `https://10.0.2.2:8443/suggest?q=%s` のdrawer検索 "evidence" → ローカル候補が表示。settingsのtyped statusは「Suggestions: last fetch succeeded.」(SUCCESS)。fix buildでCA経路が壊れていないことの対照証跡。server access logの行は初回runのlogには残っていないため、**review round 1 runで同条件のpositive controlを `61-lan-r1-server-access.log` 11:33:54行群として再取得した** (`54-lan-r1-https-positive-control.png`、`UA=okhttp/5.5.0`)。
- `36-lan-item7a-04-settings-cleartext-blocked-after-fix.png` — **7a再取得の決定的証跡**: URLを `http://10.0.2.2:8443/suggest?q=%s` に変更してdrawer検索発火後、settings UIは **`cleartext-blocked`** ("Suggestions: blocked, cleartext (HTTP) traffic is not allowed. Use an HTTPS address.") を表示。初回runの `16-*.png` (generic-network-failureと誤分類) がfixで解消された。LAN sectionは「Local network access is granted.」のまま (guard短絡ではなくfetch failure側の分類であることも示す)。
- `37-lan-item7a-05-cleartext-logcat-after-fix.txt` — 上記fetchのlogcat (`CustomWebSearchProvider` tagのみ)。**`java.net.UnknownServiceException`** (class名のみ。メッセージ内容・URL・queryは出ない) でredaction契約も維持。okhttp 5.5.0の予測どおりの例外型。
- `38-lan-item7a-06-drawer-empty-cleartext.png` — 同一fetch時のdrawer検索: web候補は**即時に空** ("Search on Custom"のみ)。cleartext拒否はnetwork I/O前の短絡。1行メモ: **検索flowは空候補で高速に完了し、hang・dialog・crashなし**。server access log上の「新規行なし」確認は初回runのlogには残っていないため、**同型のnegative windowを `61-lan-r1-server-access.log` で再現している** (11:21のINVALID template検索窓: guard短絡のため `/suggest` `/slow` 以外のpathが一切記録されていない。`50-lan-r1-f3-logcat-invalid-guard.txt` と対照)。

### Item 9再取得 (release署名build、fix `1600e0c857` / API 37 同一AVD)

debug buildのLeakCanary block (旧 `24`〜`26` の記録) を回避するため、**release variant** (`assembleLawnWithQuickstepGithubRelease`、applicationId `app.lawnchair`、NSCなし) を debug keystoreでzipalign+apksigner署名して同一AVDへ追加installし、`am crash` → 通知のaction tapでE2Eを取得した。C4 (katbin upload経路) は#528で変更していないため、これは既存経路の動作確認である。

- `39-lan-item9-03-release-crash-notification.png` — release build (`app.lawnchair`) で `am crash` → crash pre-handlerがreport fileを書き (**POST_NOTIFICATIONSを `pm grant` で付与する必要があった** — 未grantでは通知が `numBlocked` になりevidence取得不可)、shadeに "Lawnchair crashed" + **"Upload crash log"** actionが表示される。
- `40-lan-item9-04-katbin-success-copy-link.png` — network ONで "Upload crash log" をtap → UploaderService (FGS, `:bugReport` process) が起動しkatbin upload成功 → 通知のactionが **"Copy link"** に切替 (`report.link != null` のtyped状態、`BugReportReceiver.notify` の分岐どおり)。
- `41-lan-item9-05-katbin-paste-live.png` — 通知本体をtap → Chrome が **`https://katb.in/sevufucejik`** を開き、paste実体を表示: `Lawnchair bug report 2026-10-07_08-02-17` / `version: 16.Dev.(1600e0c) (1600020100)` / `commit: 1600e0c`。**URLが実在し、中身もfix commitのreportであることまで確認**。
- `42-lan-item9-06-katbin-upload-logcat.txt` — 成功flowのlogcat抜粋 (crash → UploaderService FGS起動)。
- `43-lan-item9-07-katbin-upload-failed-typed.png` — **失敗case**: `cmd connectivity airplane-mode enable` → 同手順で再発火 → 通知actionが **"Upload failed"** (`action_upload_error` のtyped表記) に切替。
- `44-lan-item9-08-katbin-upload-error-logcat.txt` — 失敗flowのlogcat抜粋 (`UploaderService: failed to upload bug report`、FGS起動/停止)。airplane modeはcapture後に復元 (OFF確認済み)。
- 観測の補足: (a) 通知のanti-spam guard (`notifications.size > 3`) は実働を確認 — system通知が4件ある状態でのcrashはreport fileなしでskipされた (既存仕様どおり)。(b) `am crash` は長時間起動済みprocessに対して効果がない場合があり、`am force-stop` → `am start` → `am crash` の手順で安定した (test harness側の事情でapp変更ではない)。(c) upload失敗から復帰した後も通知actionは `Upload failed` のまま残る (再uploadはnotificationからは再試行しない既存挙動)。

### Review round 1 再取得 (fix `f102d72144` / API 37 emulator-5554 + API 36 emulator-5556)

PR #543 implementation review round 1 (blocking 1-3 + medium 4-6、すべてACCEPTED) の修正 (`f102d72144`) をf102d72 debug buildで検証した。suggestions URLの設定はすべて設定UI (Search → App drawer → Web suggestions → Search suggestions URL dialog) から行い、server は単一process (:8443 CA署名 + :8444 rogue、`/slow` は9s sleep、access logは完了時刻で記録)。

| # | review finding | 証跡 | 結果 |
|---|---|---|---|
| R1-1 | **F2: timeoutがtyped outcomeを迂回** (高) | `45`〜`47` | PASS。URLを `https://10.0.2.2:8443/slow?q=%s` (9s) に設定し drawer検索 `timeoutoracle` (delay 4500ms) → drawerは空候補 (`45`)。settingsは **「Suggestions: failed, the address could not be reached.」= generic-network-failure** (`46`)。logcat: `WebSuggestionProvider: Web suggestion request timed out` + `TimeoutCancellationException` / 遅延完了callの `JobCancellationException` (いずれもclass名のみでredaction契約維持) (`47`)。`/slow` の完了行は server log 11:14:15-17 に記録 (fetch開始 + 9s = timeout後の遅延完了) — **完了後も settings は generic-network-failure のまま反転しない** (SUCCESS publish が取消済みcoroutineの `publishIfActive` gateで拒否されることをE2Eで確認) |
| R1-2 | **F3: INVALID templateがruntimeでfetchされ得る** (高) | `48`〜`50` + `61` | PASS。URLを `https://example.com/s?q=%s&x=%s` (classifier INVALID、unit testと同一例) に設定 → settingsは **invalid templateメッセージ** + `not-run` のまま (outcome未publish) で、**LAN permission sectionは出ない** (INVALID は STATICALLY_LOCAL でないため prompt も出ない) (`48`)。drawer検索 `invalidq` は即時に空 (`49`)。logcat: `suggestion fetch short-circuited: reason=invalid-template sdkInt=37` (連続、query/URL不含) (`50`)。**server access log に当該fetchの行は皆無** (11:14の `/slow` 行と11:33の `/suggest` 行の間に11:21台の行が存在しない) (`61`) |
| R1-3 | **F4: A→blank→A で旧outcome復活** (中) | `51`〜`53` | PASS。URLを `https://10.0.2.2:8444/suggest?q=%s` (rogue自己署名) にして検索 `ctfail` → settings **「Suggestions: failed, TLS certificate check.」(tls-ct-failure)** (`51`)。URLを **blank** に保存 → status UI非表示 (`52`)。**同一の失敗URLを再入力 → 「Suggestions: not run yet for the current address.」** (旧failureは再表面化しない。template-change hook が親scopeでblankも含め常時起動) (`53`) |
| R1-4 | **F1: `requestedThisSession` がprocessを跨ぐ** (高) | `58` + `62` | PASS。session内で request → 拒否 → "Don't allow" 2回目で USER_SET|USER_FIXED 確定 → settings-guidance 表示を確認後、processを停止し (`62` の方法注記参照: `am kill` はhome processをkillしないため root SIGKILL を使用。**icicle-restore経路は既存infraのMainThreadInitializedObjectデッドロックでANR — #528 diff外、`62` にstack記録**) → task再起動後のsettingsは **plain request button + not-run** に戻り、guidance文字列はdump内0件 (`58`、`59` 末節) |
| R1-5 | **Finding 5: server logの正本不整合** (中) | `61` | PASS。単一server・単一窓 (10:35-11:59) の**完全なaccess logをcommit**。含まれる窓: (a) API 37 https positive control (fix build) = 11:33:54 `GET /suggest?q=positive...` 行群 (`54`)、(b) F3 negative window = 11:21台の行なし (INVALID templateでserver到達なし)、(c) API 36 LAN fetch = 11:59:06 `GET /suggest?q=apitwosix...` 行群 (`60`)、加えて F2 の `/slow` 遅延完了行群 (11:14:15-17)。query はtest用文字列のみ。旧 `server-access-8443.log` は初回session (05:44-06:17) の窓のみで、旧READMEが引用した07:18/07:49の行を含んでいなかった — 本READMEの該当引用は `61` の該当行へ修正済み |
| R1-6 | **Finding 6: TalkBack label / focus未実施** (中) | `55`〜`59` | PASS。API 37でTalkBack有効化 (`settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService` + `accessibility_enabled 1`、`dumpsys accessibility` で `Bound services:{Service[label=TalkBack...]}` と `touchExplorationEnabled=true` を確認)。3状態を取得: **state 1** fresh LAN URL = request button (`55`)、**state 2** rationale-required (`56`: rationale text + 再request button)、**state 3** settings-guidance (`57`: guidance text + "Open app settings")。focus/label evidence は各状態の `uiautomator dump` XML (accessibility node tree) から status text / button label / bounds を `59` に抽出 (focus到達性はboundsと`55`〜`58`のfocus rectangleで確認)。font scaling (`28`) は文字列変更なしのため本runの再確認対象外 (review指示どおり問題出た場合のみ再確認)。TalkBackはcapture後に無効化して復元 |

その他の記録:

- `59-lan-r1-talkback-focus-label-evidence.txt` — TalkBack 3状態 + F1決定の uiautomator dump 抜粋 (label + bounds)。
- `60-lan-r1-api36-lan-suggestions.png` — API 36 (fix build) で LAN URL のdrawer検索 `apitwosix` → ローカル候補表示。server log 11:59:06行群と対応 (R1-5(c))。API 36ではLAN section・permission promptは出ない (SDK 37+ gate、`29` 再確認)。
- `61-lan-r1-server-access.log` — **新正本server access log** (単一窓・完全commit、上述のとおり)。
- `62-lan-r1-process-death-oracle-method-note.txt` — F1 oracleの実施方法の正確な記録 (`am kill` 不成立 → SIGKILL、icicle-restore ANRのstack、resolver再選択)。
- `63-lan-r1-api36-ca-installed.png` — API 36 へのユーザーCAインストール (Settings UI、"CA certificate installed" toast)。API 37も同経路で再インストールした (root file placementのみでは信頼されない観察をREADME先頭に記載)。

### API 36 (回帰)

- `29-lan-item13-api36-no-lan-section.png` — **SDK 36ではLAN URL (statically-local) を設定してもLAN guard sectionが出ない** (要求button・rationale・granted表示なし。typed status `not-run` のみ)。guardがSDK 37+ gateであることのUI証跡。新規permission promptは一切出ない。← Verification「API 36 emulator: 既存public fetch・katbinの回帰なし、新規permission promptなし」+ Scenario「API 36・Internet-onlyは挙動不変」
- `30-lan-item13-api36-drawer-lan-suggestions.png` — API 36で同一LAN URLのdrawer検索 → ローカル候補が表示 (LNP不存在のためpermission不要の現行動作)。host access logの行は初回runのlogには残っていないため、**review round 1 runで同一条件 (API 36・LAN URL・fix build `f102d72`) のfetchを `61-lan-r1-server-access.log` 11:59:06行群として再取得した** (`60-lan-r1-api36-lan-suggestions.png`)。`34-lan-item13-api36-logcat.txt`: CustomWebSearchProviderのlog行ゼロ (guard不発火)。
- `31-lan-item13-api36-provider-google.png` / `32-lan-item13-api36-google-suggestions.png` — 既定Google providerのpublic HTTPS取得の回帰なし。

### katbin (Item 9) — 初回run (debug build) は NOT COMPLETED、release buildで再取得済み

初回run (`47400f0963` debug build) では `am crash` 毎にcrash handlerはreport fileを書いた (`25-*.txt`) が、**upload通知は表示されない**ままだった。原因: debug buildのみ含まれるLeakCanaryがuncaught exception経路をwrapし、main threadが `handleApplicationCrash` のbinder呼び出しでblockされる (`26-*.txt` のANR trace)。**この不成立はdebug-onlyのLeakCanary起因であり、#528の変更 (C4は変更なし) とは無関係**。再取得は上記「Item 9再取得 (release build)」のとおり成功・失敗両caseともPASS (`39`〜`44`)。

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

- ~~Item 9 (katbin通知の成功/失敗E2E)~~ — release署名buildで成功・失敗とも再取得済み (上記)。emulator (release署名・user build相当のNSC) での確認のため、保守者実機 (Pixel 9a / API 37, Epic #516 Phase 3) での再確認は任意の tolerance として残す (#526/#527と同じ扱い)。
- Item 12のCT失敗signature — not reproduced (上記)。
- indeterminateの「hostname→private IP解決」subcase — spec明記の限界のままとする (typed failure `generic-network-failure` での扱いは `22-*.png` で確認済み)。
