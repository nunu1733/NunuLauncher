# Assessment: Issue #537 — 16-dev debug buildのGeneral設定routeクラッシュ（`BasePreferenceManager$FontPref` `$stable` NoSuchFieldError）

> Status: investigation complete — 根因特定・修復手順実証済み。source変更は不要（障害対応記録）
> Date: 2026-10-06
> Issue: [#537](https://github.com/nunu1733/NunuLauncher/issues/537)（Refs #526 / #535 / #516）
> Environment: emulator `issue526_api37_pixel_9a`（API 37 Pixel 9a、`sdk_gphone64_arm64`、serial `emulator-5554`）、debug build（`assembleLawnWithQuickstepGithubDebug`）
> 証跡: [537-general-route-crash-evidence/](./537-general-route-crash-evidence/)

## Outcome in one line

クラッシュは **source defectではなく、ローカルビルド環境のKotlin incremental compilation出力に残存した古い`FontPref.class`（Compose compiler生成の`$stable`フィールド欠落）が原因**。依存側（`GeneralPreferences.kt:103`の呼び出し site）だけが現行toolchainで再コンパイルされ`$stable`読み出しを期待したため、実行時に`NoSuchFieldError`で落ちる。**決定的な増幅要因として、破損したcompile task出力がlocal Gradle build cacheに保存されており**、`gradle clean`や新規worktreeでの「クリーンビルド」でも同じ破損出力がcache復元され、クラッシュが再生産される（本調査で実際に再現）。1回の真の再コンパイル（`--rerun-tasks`等）でcache entryが正常出力に置き換わり、以後は全ビルド形式で健全になる（2つのfresh worktree + 実機で実証）。

## 事実の経緯

Issue #537は#526のemulator検証で、lineage build `Lawnchair.16.Dev.(575e37b).github.debug.apk`（ローカルビルド。CI buildは`Dev.(#<run番号>)`命名、[build.gradle:178](../../build.gradle)）においてGeneral routeが`NoSuchFieldError: No field $stable in ...BasePreferenceManager$FontPref`（`GeneralPreferences.kt:103`）で使用不能になったことを記録し、「clean build / incrementalキャリア削除で解消するか」の判定を依頼していた。

本調査は当初Issue記載の仮説（「Compose stable field解決のstalenessアーティファクト」）を検証する意図で、クラッシュcommit `575e37baad`をfresh worktreeでクリーンビルドしたところ**同クラッシュが再現した**。ここから「cache復元によるエコー」を切り分け、最終的に以下を確定した。

## 根因チェーン（証跡付き）

1. **再現**: fresh worktreeで`575e37baad`を通常ビルド → General route tapで`NoSuchFieldError` → process kill。スタックはIssue報告と同一（[general-route-crash-logcat.txt](./537-general-route-crash-evidence/general-route-crash-logcat.txt)、2026-10-06 21:18再現分）。
2. **dex比較**: クラッシュAPKの`BasePreferenceManager$FontPref`は**`$stable`フィールドを持たない**（[fontpref-no-stable-crash-apk-dexdump.txt](./537-general-route-crash-evidence/fontpref-no-stable-crash-apk-dexdump.txt)）。一方、同じdex内の具象siblingクラス（`BoolPref`/`IntPref`等）は`$stable`を持ち、抽象クラス（`StringBasedPref`）は両APKとも持たない。すなわち破損は`FontPref`1クラスのみ。
3. **呼び出し側は同一**: クラッシュAPKと正常APKのclasses14.dexはともに6箇所の`sget FontPref;.$stable`を持ち（同じコンパイル済み呼び出し site）、差はクラス側のフィールド生成のみ。
4. **真の再コンパイルでは生成される**: 同一worktreeで`:compileLawnWithQuickstepGithubDebugKotlin --rerun-tasks`（up-to-date/build cache無視の401 task全実行）→ `javap`で`FontPref`に`public static final int $stable`が確認できる。**sourceは健全**。
5. **増幅要因=Gradle build cache**: 初回のfresh worktreeビルドは「266 executed / 377 from cache」で、Kotlin compile taskの入力hashが一致したため、**main worktreeの過去ビルド（クラッシュAPKを作ったincrementalビルド）がcacheへ保存した破損出力を復元**した。`gradle clean`はbuild cacheを消さないため、「クリーンビルドでも直らない」ように見える。
6. **修復の実証**: `--rerun-tasks`の実行がcache entryを正常出力で上書き → 別のfresh worktree（2つ目）で通常ビルドしたAPKでは`$stable`が存在し（[fontpref-stable-healed-apk-dexdump.txt](./537-general-route-crash-evidence/fontpref-stable-healed-apk-dexdump.txt)）、実機でGeneral routeがクラッシュ0で動作する（[537-general-route-ok-healed-575e37b.png](./537-general-route-crash-evidence/537-general-route-ok-healed-575e37b.png)）。

補助事実: `git diff 575e37baad..HEAD`はpreferences/font領域（`lawnchair/src/app/lawnchair/ui/preferences/`、`lawnchair/src/app/lawnchair/preferences/`、`lawnchair/src/app/lawnchair/font/`）に対して**空**。クラッシュ領域のsourceはクラッシュ時点から現headまで無変更であり、「後続commitで偶然直った」可能性はない。

## CI/他環境への影響

- CI（`ci.yml`の`build-debug-apk`）はfresh checkoutだが、`gradle/actions/setup-gradle`が`~/.gradle`（build cache含む）をrun間でcacheする。ただし破損entryはローカルビルド由来であり、CIのcacheには存在しないため現時点で影響はない。
- 上流（Kotlin/Compose compiler）への再現報告は、最小再現の切り出しが必要なため本Issueの範囲外とする（必要になったら上流Issue chooserから別途報告）。

## 解決手順（再発時）

```bash
# 破損したcompile出力のcache entryを正常出力で上書きする
./gradlew :compileLawnWithQuickstepGithubDebugKotlin --rerun-tasks
# または cacheごと消す
rm -rf ~/.gradle/caches/build-cache-1
```

恒久文書として[building guide](../engineering/building.md#known-upstream-warnings)へ同手順を追記した（同じPR内）。

## #526検証matrix追補（backup route）

クラッシュ解消後、PR #538の検証matrixで未完了だったPreferenceActivity配下のbackup/restore route確認を、HEAD `3019ca6782` の健康なビルド（API 37 Pixel 9a emulator）で完了した。証跡は[526-target37-ui-evidence/](./526-target37-ui-evidence/)へ。

| 項目 | 結果 | 証跡 |
|---|---|---|
| backup routeの可視導線（Settings ⋮メニュー: Create backup / Restore backup / Restore Nova backup） | PASS | [overflow menu](./526-target37-ui-evidence/api37-phone-backup-overflow-menu.png) |
| Create backup route 縦 | PASS（preview・What to back up・Create fully表示） | [portrait](./526-target37-ui-evidence/api37-phone-backup-route-portrait.png) |
| Create backup route 横（回転） | PASS（reflow・inset解決） | [landscape](./526-target37-ui-evidence/api37-phone-backup-route-landscape.png) |
| resize（`wm size 720x1616`へ縮小） | PASS（reflow、復元確認済み） | [resize-720](./526-target37-ui-evidence/api37-phone-backup-route-resize-720.png) |
| fontScale 2.0 縦/横 | PASS（ラベル切断なし） | [portrait](./526-target37-ui-evidence/api37-phone-backup-route-fontscale20-portrait.png) / [landscape](./526-target37-ui-evidence/api37-phone-backup-route-fontscale20-landscape.png) |
| ja表示 | PASS（バックアップを作成 / レイアウトと設定 / 壁紙 / 作成） | [ja](./526-target37-ui-evidence/api37-phone-backup-route-ja.png) |
| Restore backup導線（SAF document picker起動） | PASS | [picker](./526-target37-ui-evidence/api37-phone-backup-restore-picker.png) |

回帰は実測されなかったため、spec 526の「実測した回帰のみ修正」どおりコード変更は生じていない。

## 一般routeの健全性確認（修復後）

- HEAD `3019ca6782` ビルド: General route正常（Font (experimental)配下5項目+Icons表示、クラッシュ0）→ [537-general-route-ok-head-3019ca6.png](./537-general-route-crash-evidence/537-general-route-ok-head-3019ca6.png)
- 修復後の`575e37baad`ビルド（クラッシュcommitそのもの）: 同正常 → [537-general-route-ok-healed-575e37b.png](./537-general-route-crash-evidence/537-general-route-ok-healed-575e37b.png)

## 終了条件の判定（Issue #537の依頼に対して）

- 「16-dev lineage上でGeneral route crashを再現・修正する」: **再現**（2形式: cache-echoビルドでの実機再現+スタック一致）・**修正**（source変更不要を確定。build cache上書きによりローカル環境は修復済み）。
- 「clean build/incrementalキャリア削除で解消するか」: **解消する**。ただし`gradle clean`のみでは不十分（build cacheが残る）という条件付き。真の再コンパイルまたは`~/.gradle/caches/build-cache-1`削除で解消。
- 「解消後、#526の検証matrixで未完了のbackup route確認（回転/resize・fontScale）を追補」: **完了**（上表、全PASS、回帰なし）。
