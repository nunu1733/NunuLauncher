# Organizer Performance Budgets and Measurement Protocol

> Status: Accepted (research output of [Issue #15](https://github.com/nunu1733/NunuLauncher/issues/15))
> Updated: 2026-08-15
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: NFR-006
> Research evidence (対象commit・確認日・統計計算script・実行結果): [PR #68](https://github.com/nunu1733/NunuLauncher/pull/68)
> Related: [android-emulator-smoke-baseline](../assessment/android-emulator-smoke-baseline.md) (reference emulator profile),
> [organizer-diagnostics](./organizer-diagnostics.md) (phase timestamp共用),
> [spec 12](../../specs/12-deterministic-full-layout-planner-v1/spec.md) (planner),
> [spec 13](../../specs/13-safe-layout-application/spec.md) (apply protocol A0–A8),
> [quality-strategy](./quality-strategy.md)

## 1. Problem and scope

organizer run の性能を、端末class・item数・grid/profile別に再現可能に測る手順と
p50/p95 budget を定義する (NFR-006)。本書は測定protocolと暫定budgetの正本である。

**In scope**

- reference計測環境と再現commandの参照 (§2)。
- 測定対象phaseのseam要件と将来hook (§3)。
- workload matrixとsynthetic data生成方針 (§4)。
- phase別metric (§5) と測定手順・統計法 (§6–§7)。
- regression比較方法 (§8) と暫定budget (§9)。
- CI/benchmark automationを別Issueに分離する基準 (§10)。

**Out of scope**

- benchmark harness実装とCI組み込み。判定基準 (§10) を満たした時点で別Issueにする。
- telemetry。計測結果の収集・送信は[organizer-diagnostics](./organizer-diagnostics.md) §12の
  default-off境界の外に出ない。
- planner/applyの実装変更、Gradle依存追加。本Issueでは行わない。

## 2. Reference environments

### 2.1 R-EMU-1: Pixel 6 emulator (on-device系phaseのreference)

[Issue #9](https://github.com/nunu1733/NunuLauncher/issues/9) が確定したprofileをそのまま使う。
再現command (SDK install、AVD作成、headless boot、APK build/install) は
[android-emulator-smoke-baseline](../assessment/android-emulator-smoke-baseline.md)
§Exact reproduction commandsを正本とし、本書では複製しない。

```text
Device class:  Pixel 6 emulator (google_apis, arm64-v8a)
API:           35 (Android 15)
Screen:        1080x2400 @ 420dpi
Grid:          5 rows x 4 columns (default InvariantDeviceProfile)
GPU:           swiftshader_indirect (software)
Host:          Apple Silicon (darwin arm64)
```

制約: software GPUのため、frame timing・rendering性能の絶対値は実機参考値に
ならない。R-EMU-1上のframe系metricは相対比較とregression検知のみに使う。
実機同等の絶対値が必要になった時点で物理端末またはhardware-accelerated
emulatorの環境を追加し、budgetを見直す (§9.2)。

### 2.2 R-JVM-1: host JVM (pure module系phaseのreference)

planning moduleはAndroid frameworkに依存しないため、JVM上で直接測定できる。
測定面は既存のorganizer unit-test seamと同一のtest source setを使う
(benchmark harnessを別途作るまでの暫定測定面。§10)。

```text
JDK:     OpenJDK 21 (building.mdの環境)
Host:    計測時のhost OS・CPU・JDKを結果に必ず記録する (§6.1)
```

JVM計測値はhost依存である。R-JVM-1とR-EMU-1、そして異なるhost間の値を
直接比較しない。比較は同じ環境class内に限る (§8)。

## 3. Measured phases and measurement seams

organizer run のphase ([organizer-diagnostics](./organizer-diagnostics.md) §4、
[spec 13](../../specs/13-safe-layout-application/spec.md) A0–A8) ごとに、
どのseamが実装されたときにどの計測面で測定可能になるかを固定する。
各seamの実装の現在状態はIssue/PRが正本であり ([AGENTS](../../AGENTS.md)
「正本の分担」)、本書には持たない。research時点の実装状態の調査結果は
PR #68に記録する。

| Phase | 測定に必要なseam | 計測面 | 将来hook (seam実装後に追加する計測面) |
|---|---|---|---|
| snapshot capture | integration snapshot adapter ([DESIGN](../../DESIGN.md) §4.4) | adapter実装後にon-device計測。JVM側は`OrganizationInput`構築コストの代理測定のみ | adapterの実装Issue。phase境界はjournal timestamp |
| plan | `OrganizationPlanner.plan` (pure JVM) | R-JVM-1のunit-test seam | emulator上の計測はinstrumentation実装後に追加 |
| checkpoint (A4) | apply protocol + recovery store (productionまたはtest double) | test doubleを使ったJVM protocol timing | 実SQLite recovery store統合後にon-device計測 |
| apply transaction (A5–A6) | apply protocol + layout writer | test DB / fake writer経由のJVM protocol timing | model write adapter・production DB adapter統合後、本物Launcher DB transactionをon-device計測 |
| bind / model reload (A7前半) | model load request/generation待ち | 実装後にon-deviceで測定 | model load generation完了待ちをprotocolが返す時点 |
| verify (A7–A8) | apply protocolのverify + DB recapture adapter | JVM seamはprotocol実装直後から | on-device再読込を含むverifyはDB recapture adapter統合後 |
| UI-thread block / frame | organizer UI (preview/confirm/result) | UI実装後にon-device計測 | §6.7のLooper dispatch計測 (主) とPerfetto (副)。frame系はChoreographer/JankStats。StrictModeは違反検出のみでmetric取得には使わない |

phaseに紐づかない追加hook:

- in-process memory peak sampler (§6.6): benchmark harness実装時に
  `Debug.getMemoryInfo`の100ms samplerとして追加。250msの`dumpsys meminfo`
  定期採取より細かいpeakが必要になった場合の切り替え先。
- grid変更・migrationとの相互作用: grid migrationを扱うIssueで別途matrixを
  定義する。

benchmark harness実装Issueは、これらのhookが実装済みかどうかを測定対象の
前提として明記し、測定可能phaseの追加を本表へ反映する。

on-device計測のphase境界timestampは、diagnostics journalの
`recordedAtWallMillis` ([organizer-diagnostics](./organizer-diagnostics.md) §14の
共用取り決め) をdata sourceとして再利用する。benchmark harnessは
第二のphase計測経路を作らない。journalは時刻源であってsampleの保存先では
なく、retention (10 run / 7日 / 512 KiB) に依存しない取得規則は§6.5で
定義する。

## 4. Workload matrix

### 4.1 軸

| 軸 | 値 | 根拠 |
|---|---|---|
| grid | G1 = 5×4 (portrait)、G2 = 6×5、G3 = 4×4 | G1はR-EMU-1のdefault。G2/G3は設定可能な代表的な密度の上下 |
| item数 | S = 40、M = 120、L = 300、XL = 600 | Mを実ユーザーの典型的な範囲と仮定した暫定値。S/L/XLは外挿確認用 |
| page数 | PW = ceil(workspace配置cell数 / floor(0.8 × rows × columns)) | folder・widget span・lock配置は同じitem数でも占有率を変えるため、page数を独立軸とする。値は§4.3の割当algorithmから一意に導出する (占有率を80%以下に抑える) |
| profile | P1 = personal only、P2 = personal + work | P1をbaseline。P2はM cellのみで開始する |
| mix | item kind配分: folder 10%、widget 5%、shortcut 5%、app = 残り (約80%、端数込み)。lockedはkindではなく**直交属性**として配置済みitemの5%に付与 | item type網羅とfree space断片化を同時に与える暫定mix |

標準matrixは P1 × {G1,G2,G3} × {S,M,L,XL} × 導出PW の12 cell、追加で
P2 × G1 × M の1 cellとする。reference cell (budget固定・regression基準に
使う) は **M × G1 × P1 × PW6** とする。

### 4.2 Cell定義と再現

cellは次の順で番号を振り、seedを固定する。workload builder (§4.3) は
cell番号とこの表だけから同一入力を再構築できなければならない。
PWは§4.3の割当algorithmが他の軸から導出する値であり、builderは導出結果が
この表と一致することをtestで検証する。

| Cell index | 軸 | PW | seed |
|---|---|---|---|
| 0 | G1 × S × P1 | 2 | 0x4E554E55 |
| 1 | G1 × M × P1 | 6 | 0x4E554E56 |
| 2 | G1 × L × P1 | 15 | 0x4E554E57 |
| 3 | G1 × XL × P1 | 29 | 0x4E554E58 |
| 4 | G2 × S × P1 | 2 | 0x4E554E59 |
| 5 | G2 × M × P1 | 4 | 0x4E554E5A |
| 6 | G2 × L × P1 | 10 | 0x4E554E5B |
| 7 | G2 × XL × P1 | 20 | 0x4E554E5C |
| 8 | G3 × S × P1 | 3 | 0x4E554E5D |
| 9 | G3 × M × P1 | 8 | 0x4E554E5E |
| 10 | G3 × L × P1 | 20 | 0x4E554E5F |
| 11 | G3 × XL × P1 | 39 | 0x4E554E60 |
| 12 | G1 × M × P2 | 6 | 0x4E554E61 |

`0x4E554E55` は `SyntheticFixtureGenerator.DEFAULT_SEED` と同じ値をcell 0に
充て、以降はcell indexに従って1ずつ増やす。seedは32bit範囲で一意である。

### 4.3 再現可能な実行recipe

workloadの再現には2階層がある。

**Layer A — seedと合成corpus生成機構の再生検証 (現時点で実行可能)。**
既存のunit-test seamは`planner.seed` / `planner.count`を受け付けるため、
次で決定的な合成corpusの生成とplanner契約検証を再現できる
([building](./building.md) §Planner generated-property runsと同じ仕組み)。

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests '*PlannerGeneratedPropertyTest*' \
  -Dplanner.seed=<cell seed> \
  -Dplanner.count=64
```

このcommandは**cellのworkloadそのものではなく**、cell seedを使う合成data
生成機構 (決定性、合成identity、planner契約) の再生検証である。
cell定義からの`OrganizationInput`再構築はLayer Bが担う。

**Layer B — cell単位のworkload構築と測定 (benchmark実装Issue)。**
§4.2のcell表から`OrganizationInput`への変換は、workload builderが次の入力を
そのまま受け取ることとして定義する。

```text
WorkloadBuilder.build(
    seed: Long,            // §4.2のcell seed
    columns: Int, rows: Int,   // grid軸。dock slot数 = columns
    totalItems: Int,       // item数軸
    profileCount: Int,     // 1 = P1、2 = P2
) -> OrganizationInput     // workspace page数は下記algorithmが導出する
```

builderはentropy源として`java.util.Random(seed)`だけを使い、次の順で消費する。
各stepは決定的であり、同じseedから異なる入力を生成しない。

1. **kind配分**: `f = floor(0.10N)`、`w = floor(0.05N)`、`s = floor(0.05N)`
   (N = totalItems)、`a = N - f - w - s`。kind列
   `[App×a, Folder×f, Widget×w, Shortcut×s]`を作り、`Random`でshuffleする。
2. **profile割当 (P2のみ、membershipより先)**: kind列の**末尾**から
   `floor(0.2 × a)`個のAppをwork profileとし、残りのAppと他kind全てを
   personal profileとする。P1では全itemをpersonalとする。
3. **dock**: kind列順で最初の`columns`個の**personal** Appをdockへ配置する
   (dock slot 0から順に)。dock itemはworkspace cellを消費しない。
4. **folder membership**: Folderはすべてpersonalとし、Folderそれぞれに
   kind列順で未使用の**personal** Appを3つずつmemberとして割り当てる
   (dock分を除く)。memberはworkspaceに個別配置しない。
   folderとmemberは常に同一profileであり、work profileのAppはloose item
   としてのみ存在する。これによりspec 10の`SAME_PROFILE_ONLY`に反する
   入力を生成しない。
5. **widget span**: kind列順でj番目 (0-based) のWidgetは`j`が偶数なら2×2、
   奇数なら1×1とする。
6. **page数の導出**: workspace配置cell数
   `P = (a - 3f) + f + s + (w + 3×ceil(w/2))`、
   `PW = ceil((P - columns) / floor(0.8 × rows × columns))`。
   導出値が§4.2の表と一致しない場合はbuilderのbugである。
7. **page分散**: dock/memberを除いたworkspace item列を、順序を保ったまま
   PW個の連続区分へ分割する。区分sizeはできるだけ等分し、余りは前側の区分へ
   1つずつ配る。各区分を担当pageのcell (0,0)からrow-majorのfirst-fitで
   配置する。spanの関係で担当pageに収まらないitemは次pageの空きcellへ
   移す (最終pageの溢れはworkload構築errorとする)。
8. **locked**: workspaceに配置されたitemのうち、配置順で最初の
   `floor(0.05 × N)`個をlocked placementとする。
9. **分類signal**: category、confidence、frequency signalは
   `SyntheticFixtureGenerator`と同じ合成規則で、以降の`Random`消費として
   生成する。

合成identityは`SyntheticFixtureGenerator` (spec 11 harness) と同じ方針
(合成package名・component名・ID) とし、実端末のlayout・実在package名を
入力に使わない。builderの実装、この契約への一致検証test、timing収集commandは
benchmark実装Issue (§10) が持つ。本書はcell定義と入力変換の契約を固定する。

### 4.4 privacy

- 計測結果artifactに含めてよいのは、timing・件数・環境metadataのみ。
  [organizer-diagnostics](./organizer-diagnostics.md) §7のNever分類
  (package名、座標、内容由来ID等) は計測結果にも適用する。
- 計測結果の保存先はPR本文またはdocs/assessment/であり、journalや
  layout DBに測定記録を書き戻さない。

## 5. Metrics

phaseごとに次を測る。UI-threadとend-to-endは必ず分離して報告する
(Issue #15受入条件)。

| Metric | 定義 | 対象 |
|---|---|---|
| phase duration | phase境界間のwall time。JVM seamでは操作呼び出し前後、on-deviceではjournal timestamp差 | 全phase |
| UI-thread block | run window内のmain-threadにおける**message dispatch 1回の継続時間** (dispatch開始〜終了) の最大値と、16msを超えたdispatchの回数。取得方法は§6.7 | on-device全phase |
| frame | run window内のjank frame率とdropped frame数 | on-device (R-EMU-1では相対値のみ) |
| memory | phase別PSS差とrun中のpeak PSS (on-device。`dumpsys meminfo`のTOTAL PSS)、またはheap使用量 (R-JVM-1)。採取手順は§6.6で固定する | 全phase |
| DB write | Launcher DB write transaction数とstatement数、recovery DB transaction数 | checkpoint/apply/recovery |
| recovery size | recovery recordの永続化byte数 | checkpoint |
| determinism guard | 計測中に同一入力から同一planが得られることを再確認 (benchmarkが結果を破壊していないことの証明) | plan |

end-to-end latencyは `USER_CONFIRMED` から `APPLY_VERIFIED` (またはterminal
failure) までのjournal timestamp差とし、phase別durationの和とは独立に報告する。

## 6. Measurement procedure

### 6.1 環境metadataの記録 (必須)

結果ごとに次を記録する。記録がない測定は無効として扱う。

```text
host OS / CPU / memory、JDK version、Gradle version
emulator build、system image、AVD名、GPU mode (R-EMU-1)
build variant、commit SHA、APK SHA-256 (on-device計測時)
workload seed、generator版、cell定義
計測日時
```

### 6.2 warmup

- **R-JVM-1**: cellごとに最初の5反復を破棄する (JIT warmup)。破棄分も
  「cold」として別欄に記録してよいが、統計には混ぜない。
- **R-EMU-1**: boot直後の初回runを破棄する。2回目以降を「warm」として測定する。
  cold-boot値が必要な場合は別のrunとして分けて報告する。
- 測定中はhost上の他の重い負荷を止める。emulatorの周波数pinningはできない
  ため、varianceが大きいことはCVで検出して報告する (§6.4)。

### 6.3 反復数

- 標準: cell・**metricごと**に、有効なmeasurement iterationを **n = 100**
  集める。1 iterationがあるmetricに有効でも、別metricの欠測を補うものではない。
- budget固定やregression基準の更新にはreference cellの**各budget対象metricごと**に
  **n = 300**の有効measurement iterationを集める。
- harnessはwarmup後の試行に単調増加の`iterationOrdinal`を割り当て、metric値、
  有効/無効、無効理由をartifactへ残す。crash、terminal event欠落、journal slice/phase
  完備検査失敗はjournal由来のphase duration・end-to-end・run windowを使うmemory metricを
  無効にする。memoryでは、境界値欠測は該当phaseのdeltaのみ、pre-run baseline欠測は
  `addedPeak`のみを無効にする。これらの無効値は他metricの有効値を取り消さない。
- 無効または欠測のmetric値は、その**同じmetric**の有効nを満たすまで後続iterationで
  補充する。crash/欠測の試行回数と理由は別に報告する。測定環境・harnessが有効nを
  到達不能にした場合、そのmetricは「測定不能」として報告し、必要な有効nなしに
  budgetを固定・更新してはならない。
- bootstrapを使うregression判定は、比較するmetricについて旧・新の各群が少なくとも
  30件の有効measurement iterationを持つ場合だけに行う。この30件は記述統計の
  minimumであって、標準n=100またはbudget固定n=300を置き換えない。
- 根拠は§7の統計計算による。

### 6.4 統計量

- p50: median。値を昇順に並べ、nが奇数なら1-based `(n + 1) / 2`番目、nが偶数なら
  1-based `n / 2`番目と`n / 2 + 1`番目の**算術平均**をmedianとする。
  p95はk番目のorder statistic、k = ceil(0.95n)。補間は行わない
  (保守側の値を取る)。
- 分散の報告: CVとIQRを必ず併記する。正規分布を仮定しない。
- cellのCV > 0.5の場合、結果に要再測定のflagを付け、そのcellの値でbudgetや
  regression判定を行わない。

### 6.5 journal retentionと無関係なsample取得

journal ([organizer-diagnostics](./organizer-diagnostics.md) §8) は
10 run / 7日 / 512 KiBで削除される。したがってsampleの保存先として
journalを使わない。on-device測定では次を守る。

- **排他実行**: 測定中は他のorganizer run (onboarding proposal、
  incremental proposal等の自動trigger) を発生させない。自動triggerを無効化
  し、測定操作以外のorganizer起動を行わない。
- benchmark harnessは各iteration (1 run) のterminal event到達直後に、
  **journal slice全体** (測定window内の全event。対象run以外のrunのeventを
  含む) を読み出して計測artifact (§4.4の保存先) へ書き出す。
  次のiterationを開始する前に完了させる。
- 読み出し時の検査は2段で行う。`journalSequence`はjournal全体の連番であり
  run単位の連番ではない。
  1. **journal全体のslice検査**: 読み出したslice**全体**で
     `journalSequence`が連続していることを確認する。slice全体に隙間が
     あれば、runIdに関係なくjournal書き込み欠落であるため、そのiterationを
     無効にする。隙間がない場合のみ、runIdで対象runのeventを抽出する。
     排他実行 (前項) に反して他runのeventが介在していても、連続性が
     保たれていれば抽出時に除外するのみでよい。
  2. **runのphase完備検査**: 抽出した対象runのevent列が、期待される必須
     phase順序 ([organizer-diagnostics](./organizer-diagnostics.md) §4。例:
     `RUN_STARTED`→`CAPTURED`→`PLANNED`→`PREVIEWED`→`USER_CONFIRMED`→
     `CHECKPOINTED`→`APPLY_COMMITTED`→`APPLY_VERIFIED`) から1つも欠落して
     いないことを確認する。欠落があればそのiterationを無効とし、再実行する。
- terminal eventに到達しないrun (crash等) は試行として記録するが、
  journal由来のduration/end-to-end統計、およびterminal eventで閉じるrun windowを
  必要とするmemory統計には含めない。§6.3に従い、影響したmetricだけを後続iterationで
  補充する。
- 上記により、journalのretentionがn = 100/300の収集に影響しない
  (直近10 run分より前のeventは削除済みでも、計測artifactに全sampleが
  存在する)。

### 6.6 memory採取手順

`dumpsys meminfo`は瞬間値であり、journal eventの読み出しはrun終了後に
行われるため遡及採取はできない。そこでrunとは独立に動く並行samplerを
使い、事後的にphase境界へ対応付ける。

- **並行sampler**: run開始前から終了後まで、host側timerが250ms間隔
  (fixed-rate) で採取を開始する。tick時点で前回の採取が**まだ完了して
  いない**場合はそのtickをskipしてmissとして記録し、遅延を次以降に
  持ち越さない (完了している場合は常に採取する)。採取は
  別process (adb shell) から行い、計測対象processに負荷を加えない。
- **RUN_STARTED前のbaseline gate**: samplerを`RUN_STARTED`予定時刻の少なくとも
  5秒前に起動する。`RUN_STARTED`は、直前の半開区間`[T - 5000ms, T)`
  (`T` = `RUN_STARTED`時刻) にあるfixed-rate 250msの全20 tickが、skip/miss/overrun
  なしの有効sampleを1件ずつ得た後にだけ発行してよい。すなわち5秒待ったという
  任意の待機では足りず、5秒窓全体のcadence coverageと20件の有効sampleが必要である。
  この条件を満たせない場合はrunを開始せず、欠測を記録してsampler baselineを取り直す。
- **1 sampleの採り方 (bracket + midpoint)**: 1回の`adb shell`呼び出し内で
  次を実行し、sample時刻 = `(t0 + t1) / 2` (midpoint) とする。
  `dumpsys`の実行時間が可変のため、実行後に時刻を取る方式は観測点から
  ずれる。値は出力の`TOTAL PSS`行を使う。

  ```sh
  t0=$(date +%s%3N); dumpsys meminfo <package>; t1=$(date +%s%3N); echo $t0 $t1
  ```

- **時刻は同一clock domain (device wall clock) で取る**: `date +%s%3N`は
  deviceのtoybox dateであり、journalの`recordedAtWallMillis`と同じdevice
  wall clock (millis) を出力する。R-EMU-1 (AVD `nunu_smoke_api35`、
  API 35 google_apis) で13桁ms出力および対象packageの`dumpsys meminfo`
  所要24–44msを実測確認した (2026-08-15、PR #68にtranscript)。
  host時計は照合に使わない。
- **取得時間上限 (overrun) と欠測**: `t1 - t0 > 1000ms`のsampleは破棄して
  欠測として記録する (参考: 同環境の`system`など重いprocessでは305–1239ms
  を観測しており、上限は対象packageの通常所要の20倍超を異常とみなす線)。
- **境界値の選択規則**: phase境界b (journal eventのtimestamp `t_b`) に対し、
  境界値 `V(b)` は「`t_b`に最も近いsample」のPSSとする。距離が等しい
  (tie) 場合は**時刻が早い方**を採る。`|t_sample - t_b| > 500ms`の場合は
  境界値なしとし、その境界を欠測として報告して補間しない。
- **phase別PSS差**: phase P (入力境界`b_in`、出力境界`b_out`) について
  `delta(P) = V(b_out) - V(b_in)` とする。両端の境界値が揃わないphaseの
  deltaは報告しない。
- **run中peak**: run window (最初のevent timestampから最後のevent timestamp
  まで) 内の有効sampleの最大値 (`runPeak`) とする。peakは境界値の有無に依存
  しないが、有効sampleが0件なら`runPeak`は欠測/無効とし、§6.3に従って
  `runPeak`と`addedPeak`を後続iterationで補充する。
- **追加peak PSS (budget判定値)**: budget (§9.1) の「追加peak PSS」は
  `addedPeak = runPeak - baseline`で判定する。baselineは、`RUN_STARTED`の
  timestampから遡る**5秒間のpre-run window**の、baseline gateで確認済みの
  20有効sampleの**median (§6.4の偶数n規則)**とする。gate後にsampleの無効化が
  判明した場合はbaselineを算出せず、また`runPeak`が欠測の場合も、
  そのiterationの`addedPeak`を欠測として§6.3に従い補充する
  (絶対peak `runPeak`は、run window内に有効sampleがある場合だけ報告する)。
- **用語**: on-device memory metricの取得値は`dumpsys meminfo`の
  `TOTAL PSS`であり、本書では**PSS**とだけ呼ぶ。RSSという名称は使わない
  (§5、§9.1のmetric名もこれに合わせる)。
- **既知の限界**: 定期採取の間 (250ms) に発生した短期のpeakは観測されない。
  この粒度は暫定扱いとし、in-processの`Debug.getMemoryInfo` sampler
  (100ms間隔、benchmark harness内thread) を将来hookとして§3に置く。
  harness実装時に粒度要件が上がった場合はこちらへ切り替える。
- R-JVM-1では、harnessがphase境界を同期的に把握できるため、境界でGC実行後
  のheap使用量 (`Runtime`のtotal/committed/used) を直接記録し、別threadに
  よる同間隔の定期採取でpeakを取る。JVM値はon-device値との比較対象に
  しない (§2.2)。

### 6.7 UI-thread block取得手順

§5のUI-thread block metric (message dispatch 1回の継続時間) は、次の
いずれかで取得する。両方取得した場合は併記し、不一致を調査する。

- **Looper dispatch計測 (主)**: benchmark harnessがmain looperのmessage
  logging (dispatch開始/終了のpair) をrun中に記録する。開始・終了の
  timestampは**`SystemClock.elapsedRealtimeNanos()` (monotonic)** で採り、
  継続時間 = 終了 − 開始とする (同一monotonic clock内の差分のため補正不要)。
  max blockと16ms超回数はこの値から算出する。organizer UI実装のrun flowに
  組み込む。
- **wall/monotonic anchor**: journalのphase境界はwall clock
  (`recordedAtWallMillis`) であり、dispatch記録はmonotonic clockである。
  harnessはrun開始 (`RUN_STARTED` event発行点) とterminal event発行点の
  両方で `System.currentTimeMillis()` と `elapsedRealtimeNanos()` を
  同時に読んでanchor pairを作る。run windowの境界は**開始点のanchor**で
  monotonicへ変換する。2つのanchor間のwall/monotonic差のずれが50msを
  超える場合は、run中に時計が補正されたとしてiterationを無効にする。
- **境界を跨ぐdispatchの包含規則**: dispatchの**開始時刻 (monotonic)**
  がmonotonic run window内にある場合のみ、そのdispatchをrun windowに
  含める。window開始より前に始まりwindow内で終わるdispatchは除外し、
  除外件数を結果に報告する。
- **Perfetto / atrace (副)**: Looper dispatchの開始・終了をtrace section
  (track event / `Trace` section) として記録し、dispatch継続時間は
  **sectionの区間**から復元する。main threadのsched sliceは1 dispatchが
  複数sliceへ分割され得るため、継続時間の復元には使わず、dispatch区間内の
  CPU実行・待機の分析のみに使う。R-EMU-1のgoogle_apis imageは`adb root`
  が使えるため、system traceが取得できる。

StrictModeはdisk/network違反の検出のみに使い、CPU busy時間やdispatch
継続時間の測定には使わない (測れないものを測ろうとしない)。

## 7. Sample size rationale

order statisticによるp95推定の被覆確率は、次の閉形式で任意のnについて
再計算できる。

```text
P(X_(k) ≤ x_q) = 1 - F_Bin(n, q)(k - 1)     # k = ceil(0.95n)
```

```python
from math import comb

def coverage(n, q):
    k = -(-95 * n // 100)  # ceil(0.95n)
    return 1 - sum(comb(n, i) * q**i * (1 - q)**(n - i) for i in range(k))
```

regression検出の必要sample数はtwo-sample normal近似
`n = 2 (z_{α/2} + z_β)^2 CV^2 / δ^2` (α = 0.05、power = 0.80) で計算する。
本書の数値表はこの手順で計算した。計算scriptの実行記録と結果の根拠は
PR #68のresearch evidenceに置く。

採用時点で計算した値:

| n | k | P(est ≤ x_0.90) | P(est ≤ x_0.95) | P(est ≤ x_0.99) |
|---|---|---|---|---|
| 60 | 57 | 0.137 | 0.647 | 0.997 |
| 100 | 95 | 0.058 | 0.616 | 0.999 |
| 200 | 190 | 0.008 | 0.583 | 1.000 |
| 300 | 285 | 0.001 | 0.568 | 1.000 |

n = 100で、p95推定値が真のp90以下に落ちる確率は約6%、真のp99を超える
確率はほぼ0である。すなわち推定値は約94%の確率でp90–p99の帯に収まり、
実務上十分である。n = 300で下側tailがさらに締まるため、budget固定に使う。

regression検出の必要sample数 (two-sided α=0.05、power=0.80、
two-sample normal近似、CV = 0.30の場合):

| 検出したい差 | n (per group) |
|---|---|
| 5% | 565 |
| 10% | 141 |
| 20% | 35 |

n = 100はCV = 0.30のとき約12%以上の差を検出できる。10%の差を検出するには
n ≥ 141、5%の差を検出するにはn ≥ 565が必要である。5%精度が必要な場合は、
variance削減 (warmup増加、host負荷除去) でCVを下げるか、そのcellに限って
n = 565以上を要件として明示する。

## 8. Regression comparison method

- baselineは、直近の受領済み測定table (docs/assessment/配下の計測記録) とする。
  比較は**同じ環境class・同じcell**に限る。R-JVM-1とR-EMU-1、host間の
  cross比較をしない。
- regression flag条件: **新p95 / 旧p95 > 1.25**、かつ**両群のp95に対する
  90% bootstrap percentile区間が重ならない**こと。両方満たした場合のみ
  調査必須とする。mean比は補助指標として併記する。
- **bootstrap手順 (判定を再現可能にする固定parameter)**:

  ```text
  対象    : 旧・新それぞれの、§6.5/§6.6で無効化されていないiterationの
            **当該metricの**測定値のみ。無効iterationはmetricごとに除外し、
            除外件数を結果に記録する。各群の値はresample前に
            `iterationOrdinal`昇順（同一ordinalがあり得るartifactでは記録順を
            第二key）でcanonical sortする。有効nがどちらかの群で30未満の場合は
            bootstrap判定を行わず、要再測定として記述統計のみ報告する。
  統計量  : resampleごとにp95を計算。p95は§6.4と同じorder statistic
            (k = ceil(0.95n)、補間なし) を使う。
  resample: iid復元抽出。回数1000。RNGは java.util.Random(0x4E554E55L)。
            消費順は b = 1..1000 の各回で「旧群のn_old個 → 新群のn_new個」
            の順に `random.nextInt(n_old)`、`random.nextInt(n_new)` を各抽出に
            1回ずつ呼び、一様乱数でindexを選ぶ。canonical sort後の配列indexを
            そのまま使い、seed・群順・この消費順を変更しない。
  区間    : 各群の1000個のbootstrap p95を昇順に並べ、50番目と950番目の
            値を90% percentile区間 [下限, 上限] とする。
  判定    : 区間が重ならない = 旧上限 < 新下限、または新上限 < 旧下限。
  ```

  同じ2群のsampleからは同じ判定が得られる。
- budget未確定・測定不能phaseも、計測値が存在するならPRに値を残し
  比較可能にする ([quality-strategy](./quality-strategy.md) §Performance
  measurementの既存取り決め)。
- **Big-O表示やimplementation上の見積りだけで合否を決めない**
  (Issue #15受入条件)。合否は常に測定値に対して行う。

## 9. Provisional budgets (NFR-006)

### 9.1 暫定budget表

全行が**provisional**である。2026-08-15時点でon-device測定が存在しないため、
数値は実測能力ではなくUX要件から導いた上限値である。cellはreference cell
(M = 120 items × G1 = 5×4 × P1 × PW6)。

| Metric | p50 | p95 | 根拠とstatus |
|---|---|---|---|
| plan (pure compute) | 250ms | 1000ms | preview表示までの対話遅延として約1秒を上限と置いた。provisional |
| checkpoint (A4) | — | 500ms | apply全体の1/4以内を暫定上限とした。provisional |
| apply transaction (A5–A6) | — | 2000ms | 同上。provisional |
| verify (A7–A8) | — | 2000ms | 同上。provisional |
| end-to-end (USER_CONFIRMED→APPLY_VERIFIED) | — | 5000ms | 確認後5秒以内の完了をUX上の要求と仮定。provisional |
| UI-thread単一連続block | ≤16ms (目標) | 50ms (上限) | 60fpsのframe budget由来。provisional |
| frame jank率 (run window) | — | 5% | R-EMU-1では相対比較のみ。provisional |
| 追加peak PSS (on-device) | — | 50MB | 想定workload (M) のsnapshot+plan+recovery dataサイズに対する暫定余裕。provisional |
| recovery record size | — | 10MB | 同上。provisional |

「—」はp50を暫定として固定しないものであり、測定後にp95と併せて固定する。

### 9.2 見直し条件

次のいずれかが成立した時点で、該当行を測定値に基づいて更新する。
暫定statusの解除には、§6の手順に沿った測定と、reference cellでの
各budget対象metricについてn = 300の**有効measurement iteration**を集めることを
要件とする。欠測・crashを含む試行数だけでは満たさず、§6.3の必要有効nを欠く
budget固定・更新は禁止する。

1. on-device計測 (R-EMU-1) が最初に実施されたとき。
2. 物理端末またはhardware-accelerated emulatorの環境が追加されたとき
   (frame系・UI-thread系の絶対値)。
3. snapshot adapter、bind/model reload、UIの実装により測定不能phaseが
   測定可能になったとき。
4. workload matrixの軸 (XL追加、P2本格化、mix変更) が変わったとき。
5. 現行budgetに対するregression flagが発生し、budget側の根拠が失効したとき。

budgetの変更は本書の更新として行い、PRで根拠となる測定にリンクする。
無通告での変更・測定なしの引き上げをしない。

## 10. CI/benchmark automationの分離基準

benchmark実装・CI自動化は本Issueでは行わない。次のいずれかが成立した時点で
専用Issueを起票して移行する。

1. 同一milestone内で3件以上のPRが手動測定を引用したとき。
2. regression flagがmerge判断を実際に変更したとき。
3. 手動での1回の測定に30分以上を要するようになったとき。

移行までの暫定測定面 (R-JVM-1) は既存のorganizer unit-test seamを流用し、
Gradle依存追加 (JMH等) はbenchmark Issueのspecで判断する。budgetがprovisional
の間、benchmarkをmerge gateにしない。

## 11. Acceptance criteria mapping

| Issue #15受入条件 | 本書の根拠 |
|---|---|
| Big-Oだけで合否を決めない | §8「測定値に対して行う」、§1 out of scope |
| synthetic dataで再現できる | §4.2–§4.3 (cell表、固定seed、Layer A/B recipe)、§6.1 (seed記録) |
| machine/device metadataを結果に含める | §6.1 (記録なしは無効) |
| planner/apply未実装phaseも測定不能箇所と将来hookを明記 | §3 (seam要件と将来hook)。research時点の実装状態はPR #68 |
| UI threadとend-to-end latencyを分離する | §5 (別metric、独立報告) |
| budgetは根拠または明示的provisional statusを持つ | §9.1 (全行provisional + 根拠)、§9.2 (見直し条件) |

Deliverable対応: 本書自体が`docs/engineering/performance-budgets.md`、
reference環境と再現command参照 (§2)、workload matrix (§4)、phase別metric (§5)、
warmup/sample/統計/regression方法 (§6–§8)、暫定budgetと見直し条件 (§9)、
automation分離基準 (§10)。

## Change history

- 2026-08-15: Issue #15のresearch成果物として初版。reference環境、phase毎の
  seam要件と将来hook、workload matrix (page軸・固定seed・決定的workload
  builder契約)、metric、統計法、暫定budgetと見直し条件、automation分離基準
  を定義した。
