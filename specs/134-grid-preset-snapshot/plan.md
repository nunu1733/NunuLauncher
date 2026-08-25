# Implementation Plan: 端末種別に整合するグリッドプリセット目録

> Issue: [#134][1]
> Spec: [spec.md](./spec.md)
> Status: accepted — 2026-08-25にIssue #134でStage A承認。Stage B実装は本planに従う。

## Current evidence

確認済み(実読したcode pathと現行振る舞い):

- `lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt:20` — singleton構築時に`InvariantDeviceProfile.parseAllGridOptions(context)`をsnapshotする。以後の`getGridInfo(name)` / `getGridName(info)` / `getCurrentGridName()`は全てこの凍結Listを使う。
- `src/com/android/launcher3/InvariantDeviceProfile.java:149` — `public static @DeviceType int deviceType;` 初期値0(`TYPE_PHONE`)。
- 同`:270-277` — grid drivenな2つのconstructorが、最初の処理として`DeviceProfileOverrides.INSTANCE.get(context)`を呼ぶ。static `deviceType`はその後の`initGrid`内(`:443`)で初めて設定されるため、snapshot時点では常に電話判定になる。
- 同`:682-687` / `:692-709` — `parseAllGridOptions(context)`は宣言カタログ全件を返す`parseAllDefinedGridOptions(context)`をstatic `deviceType`でfilterする。有効性判定の正本は`GridOption.isEnabled(deviceType)`(`:1100`、category bitmask)。
- 同`:376-378` / `:559-562` — `getCurrentGridName(context)`と`setCurrentGrid(context, name)`は`DeviceProfileOverrides`へ委譲し、後者はrows / columns / hotseat 3 key書き込み後に`onConfigChanged`で再初期化する。
- `src/com/android/launcher3/util/DisplayController.java:522` — `Info.getDeviceType()`。grid初期化chain(`InvariantDeviceProfile.java:281`, `:382`)が既に使用する権威と同一。
- runtime証跡: Issue #134本文(API 36.1 Pixel Tablet AVD、`NoSuchElementException`、永続化probe)、[#108 assessment](../../docs/assessment/issue-108-organizer-mvp-compatibility.md)のgrid節。test-only等価harnessが`tests/organizer-instrumentation/app/lawnchair/organizer/integration/Issue108GridEvidenceInstrumentationTest.kt`(named seamは#134によりBLOCKEDである旨のKDoc付き)。

推測と未検証:

- `DisplayController` singletonの初期化がIDP / `DeviceProfileOverrides`初期化へ逆依存**しない**ことは未検証(query時解決の前提)。実装冒頭の確認itemとし、成立しなければstop condition(S-1)。

## Design

### Modules and interfaces

production変更はlawnchair側1 module(`DeviceProfileOverrides`)に留める。`src/`(Launcher3由来)は**変更しない**(零bridge)。

- public surface不変: `getGridInfo()` / `getGridInfo(gridName)` / `getGridName(gridInfo)` / `getCurrentGridName()` / `setCurrentGrid(gridName)` / `getOverrides(...)` / `getTextFactors()`。
- constructor時snapshot廃止。目録はquery時点で解決する:
  1. authoritative device typeを`DisplayController.Info`から取得(initGridと同一権威)。
  2. `InvariantDeviceProfile.parseAllDefinedGridOptions(context)`で宣言カタログ取得。
  3. `GridOption.isEnabled(deviceType)`でfilterし、宣言順のListとして使用。
- 単一正本の維持: カタログparseと有効性判定は既存IDP API / `GridOption.isEnabled`をそのまま使う。新規に書くのは「filterを今の権威で適用する」ことのみであり、並列のgrid制御pathにはならない。
- JVM test用pure seam: `DeviceProfileOverrides`のcompanion objectに、Android型を持たない純粋関数を置く(入力: 宣言プリセットのplain data(name, rows, columns, hotseat列数, device category bitmaskまたはtype別有効性)+ device type / 出力: 有効目録、天井合わせ名)。production call siteで`GridOption`→plain data変換して渡す。仮想interfaceやproduction/test二重実体は追加しない(AGENTS.md設計規約)。
- 呼び出し側とtestが使うseam: production振る舞いは`DeviceProfileOverrides` public methods(instrumentation経由)、近似・filter規約はpure関数(JVM unit test経由)。内部実装(XML parse等)を直接検証しない。

### Data flow

照会 → 権威device type解決(`DisplayController.Info`) → 宣言カタログparse → 有効filter(宣言順維持) →
- `getGridInfo(name)`: 目録内の完全一致。不在ならfail-closed例外(rows / columns / hotseatのどのkeyも書かない)。diagnosticに要求名と当該hostの有効名集合を含める。
- `getGridName(info)`: 宣言順での天井合わせ(`numRows >= info.numRows && numColumns >= info.numColumns`の最初)、全て未満なら最終要素。spec Scenario「現在グリッド名」の規約通り。
- `setCurrentGrid(name)`: 目録内検証後に既存通り3 key書き込み(IDP側`onConfigChanged`は現状維持)。

### Alternatives rejected

1. **IDP constructor順序の入替え / static早期設定** — 大域staticの時系列結合を解消せず隠蔽するだけ。bridge範囲も広がる。採否。
2. **`parseAllGridOptions(Context, int)` overload追加(Launcher3 bridge)** — 機能的に等価だがupstream patch surface(#110 baseline)を増やす。既存public API(`parseAllDefinedGridOptions` + `GridOption.isEnabled`)で同一正本を使って達成できるため不要。採否。
3. **目録の永続化 / deviceType別cache** — query頻度は低(起動、config change、設定UI)で、状態追加と失効管理のコストが勝る。stateless解決を既定とし、計測根拠が出た時点で別Issueへ分離。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt` | snapshot廃止→query時解決、pure helper抽出、fail-closed diagnostic整備 | プリセット目録の唯一の消費module。Launcher3 bridge不要 |
| `tests/unit/app/lawnchair/organizer/...`(JVM unit root) | pure mapping test: deviceType別有効filter(0/1/2全分岐+未知type=false)、天井合わせの決定性・全域性(返却名は常に有効集合要素)、電話目録の現行同等fixture | JVM gateで契約を固定 |
| `tests/organizer-instrumentation/.../integration/`(新規`Issue134GridPresetInstrumentationTest`) | tablet named-seam切替 + force-stop/restart耐性、negative path(pref不変assert)、phone parity | Launcher-host oracle(AC-1/AC-3/AC-4) |
| 同`Issue108GridEvidenceInstrumentationTest.kt` | KDoc更新のみ(named seam BLOCKED記述がfix後に陳腐化するため)。pref-key path自体は変更しない | #108 harnessの歴史的比較可能性を保持 |
| `docs/assessment/issue-108-organizer-mvp-compatibility.md` | #134セルへのdated addendum(fix/evidence link) | AC-5 |

## Migration and recovery

- schema / preference format migrationなし。DB書き込みは本修正自身は行わない(grid切替時のDBファイル切替はLawnchair既存機構)。
- failure中の挙動: 目録解決・検証失敗はfail-closed例外で、preference書き込み前に起こるため部分適用されない。
- release rollback / downgrade: PR revertで凍結snapshot挙動へ戻る。タブレットでは#134症状へ戻るのみ(既知・許容)。データ破壊なし。
- backup / restore compatibility: `reinitializeAfterRestore` chainは変更せず、「名前解決が有効目録内へ収束する」本契約に従う。restore中の`allowDisabledGrid` pathはinitGrid側既有で本変更外。

## Verification

標準gate(AGENTS.md検証済みcommand + CI task):

```bash
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
./gradlew testLawnWithQuickstepGithubDebugUnitTest
./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest
```

host実行(building guideおよび#108 repro block準拠):

```bash
adb install -r -t -g 'build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(<sha>).github.debug.apk'
adb install -r -t 'build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug/NunuLauncher-lawn-withQuickstep-github-debug-androidTest.apk'
adb shell am instrument -w -r \
  -e class app.lawnchair.organizer.integration.Issue134GridPresetInstrumentationTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
```

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 新instrumentation: named-seamで`6_by_5`適用→live寸法assert→force-stop/再起動→寸法・現在グリッド名・`idp_grid_name`一致assert。テスト内で元寸法へ復帰 | API 36.1 Pixel Tablet AVD(`issue108_api36_pixel_tablet`相当、TYPE_TABLET)上のam instrument command |
| AC-2 | JVM pure test(type別filter全域、天井合わせ決定性)+ phone/tablet instrumentation assertion。`TYPE_MULTI_DISPLAY`はpure levelのみで、実機claimはしない(residual limitationをPR明記) | `./gradlew testLawnWithQuickstepGithubDebugUnitTest` + 上記instrumentation |
| AC-3 | 既存phone lane無修正green: `Issue108GridEvidenceInstrumentationTest`(4x5→3x3、4x5→5_by_5) | API 36.1 phone AVD(`nunu_qpr2_api36_1`相当) |
| AC-4 | negative-path instrumentation: 無効名要求後、3 pref keyがpre-call値とbyte同等であるassert + diagnostic内容assert | 上記tablet AVD |
| AC-5 | harness KDoc差分 + 行列document dated addendum(evidence run出力link) | PR差分 |
| AC-6 | audited head SHA上でCI merge gate(`final-status`)成功。実装sessionと別のaudit sessionが`docs/assessment/pr-<N>-issue134-grid-preset-snapshot.md`(対象head SHA、参照spec受入条件、実行test表面、CI run link)を作成 | `high-risk-gate` workflow + assessment記録 |

含めるべき観点: contract(unit pure)、integration(host再起動耐性)、failure injection(negative preset名)、determinism(pure天井合わせのproperty: 全入力で返却名は有効集合要素かつ決定的)、regression(phone parity)、performance(query時XML parseのコスト差分を起動一回分で計測し記録。閾値問題化した場合はcache導入を別Issueへ)。

Stop conditions(発生したら実装を止めIssueで判断):

- S-1: `DisplayController`解決が初期化循環(IDP ↔ overrides ↔ DisplayController)を起こす。代替権威取得方法をIssueで決定する。
- S-2: 到達可能なdevice typeで有効目録が空になり、既存の補間chain(「No display option with canBeDefault=true」RuntimeException)が発火し得る。product判断が必要。
- S-3: 零bridge方針では契約を満たせず`src/`変更が必要になることが判明。patch surface理由ごとIssueで再決定する。
- S-4: 再起動耐性検証中に既存durability chain(`DeviceGridState.writeToPrefs`等)との干渉証跡が出た場合。

## Documentation updates

- [x] spec status/history(2026-08-25承認、`accepted`)
- [x] CONTEXT.md: 「有効プリセット (enabled preset)」追記(承認時)
- [ ] DESIGN.md: 更新不要の見込み(§4.4 launcher integration adapterの既有範囲内。system structure不変)。この判断をPRで記録
- [ ] ADR: 作らない見込み(単一の変更困難判断に至らず、spec/planで固定可能)。権威source選択が後日争われた場合はADR昇格を検討
- [ ] AGENTS.md: 変更なし(新必須commandなし)

## Execution checklist

- [x] Current behavior reproduced(tablet AVD: 凍結目録probe + named-seam `NoSuchElementException`再現はIssue本文・#108行列の記録どおり。Stage Bではfix後の逆証としてnegative pathがgreen)
- [x] Tests fail for the missing behavior(JVM pure + tablet named-seam instrumentationを追加。実装前のred確認はIssue本文の再現証跡と、テスト側が静的フィルタ版inventoryを使った際の失敗(下記Execution notes)で代替確認)
- [x] Minimal implementation completed(lawnchair 1 module、零bridge。`git diff --stat main -- src/ quickstep/`が空であることを確認)
- [x] Migration/recovery verified(該当なしを明記、revert可能性はbuild gateで担保)
- [x] Full relevant verification completed(spotlessCheck / testLawnWithQuickstepGithubDebugUnitTest / assembleLawnWithQuickstepGithubDebug / assembleLawnWithQuickstepGithubDebugAndroidTest全green。tablet AVD: Issue134 class 3/3 + apply→force-stop→verify→restore phased run全OK。phone AVD: Issue108 lanes 2/2 + Issue134 parity 2/2)
- [ ] PR evidence and remaining risks recorded(PR作成時に`risk: layout-data` label、独立audit sessionのassessment、patch surface計測不変の記録)

## Execution notes (Stage B, 2026-08-25)

- 実装review(2026-08-25, HEAD `48e6d406`への指摘)への対応: (1) acceptance pathを`InvariantDeviceProfile.INSTANCE.get(context).setCurrentGrid(context, name)`経由へ変更(delegate書き込み+`MAIN_EXECUTOR`でのproduction再初期化まで含む)。negative pathも同seam経由を追加。commit `95f2ab6c9b`。(2) 証跡SHAを修正 — 旧記録のbuild id `6b89df5`は実装前commitで、audit trailとして不正確だったため、`95f2ab6c9b`で全host証跡を再実行してaddendumを正しいSHAへ差し替え。
- 検証中の教訓: instrumentation test自身が静的フィルタ版`parseAllGridOptions`で有効集合を求めると、初期化前プロセスでは凍結電話目録を拾い#134と同じ誤りを踏む。テストはauthoritative解決(`parseAllDefinedGridOptions` + `Info.getDeviceType` + `isEnabled`)へ統一済み。これはspec Non-goalsの「他のstatic読み手」残存リスクの実例でもある。
- spotlessApplyが2式を単一行化したのみで、意味変更なし。
- stop条件S-1〜S-4は発火せず(`DisplayController`はIDP/overridesに逆依存しないことをhost上で実証)。

[1]: https://github.com/nunu1733/NunuLauncher/issues/134
