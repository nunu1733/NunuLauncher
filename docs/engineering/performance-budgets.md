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
- 測定対象phaseと、現時点の測定可否・将来hook (§3)。
- workload matrixとsynthetic data生成方針 (§4)。
- phase別metric (§5) と測定手順・統計法 (§6–§7)。
- regression比較方法 (§8) と暫定budget (§9)。
- CI/benchmark automationを別Issueに分離する基準 (§11)。

**Out of scope**

- benchmark harness実装とCI組み込み。判定基準 (§11) を満たした時点で別Issueにする。
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
(benchmark harnessを別途作るまでの暫定測定面。§11)。

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

| Phase | 測定に必要なseam | 計測面 |
|---|---|---|
| snapshot capture | integration snapshot adapter ([DESIGN](../../DESIGN.md) §4.4) | adapter実装後にon-device計測。JVM側は`OrganizationInput`構築コストの代理測定のみ |
| plan | `OrganizationPlanner.plan` (pure JVM) | R-JVM-1のunit-test seam。emulator上はinstrumentation後に追加 |
| checkpoint (A4) | apply protocol + recovery store (productionまたはtest double) | test doubleを使ったJVM protocol timing、production store統合後にon-device計測 |
| apply transaction (A5–A6) | apply protocol + layout writer | test DB / fake writer経由のJVM protocol timing。本物Launcher DB transactionはmodel write adapter統合後にon-device計測 |
| bind / model reload (A7前半) | model load request/generation待ち | 実装後にon-deviceで測定 |
| verify (A7–A8) | apply protocolのverify + DB recapture adapter | JVM seamはprotocol実装直後から、on-device再読込を含むverifyはadapter統合後 |
| UI-thread block / frame | organizer UI (preview/confirm/result) | UI実装後にon-device計測。hookは§10 |

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
| page数 | PW2 / PW4 / PW8 / PW15 (workspace page数) | item数と独立にpage構成を固定する。folder・widget span・lock配置は同じitem数でも占有率を変えるため、page数を明示的な軸とする (S=PW2、M=PW4、L=PW8、XL=PW15を標準組合せとする) |
| profile | P1 = personal only、P2 = personal + work | P1をbaseline。P2はM cellのみで開始する |
| mix | app 80%、folder 10% (3メンバ)、widget 5% (span 1–2)、shortcut 5%、locked 5% | item type網羅とfree space断片化を同時に与える暫定mix |

標準matrixは P1 × {G1,G2,G3} × {S,M,L,XL} × 標準page数の12 cell、追加で
P2 × G1 × M × PW4 の1 cellとする。reference cell (budget固定・regression基準に
使う) は **M × G1 × P1 × PW4** とする。dockは全cellで満杯 (gridのcolumn数分)
とし、folderは配置後のworkspace占有率に1 itemとして数える。

### 4.2 Cell定義と再現

cellは次の順で番号を振り、seedを固定する。workload builder (§4.3) は
cell番号とこの表だけから同一入力を再構築できなければならない。

| Cell index | 軸 | seed |
|---|---|---|
| 0 | G1 × S × P1 × PW2 | 0x4E554E55 |
| 1 | G1 × M × P1 × PW4 | 0x4E554E56 |
| 2 | G1 × L × P1 × PW8 | 0x4E554E57 |
| 3 | G1 × XL × P1 × PW15 | 0x4E554E58 |
| 4–7 | G2 × {S,M,L,XL} × P1 × 標準PW | 0x4E554E59–0x4E554E5C |
| 8–11 | G3 × {S,M,L,XL} × P1 × 標準PW | 0x4E554E5D–0x4E554E60 |
| 12 | G1 × M × P2 × PW4 | 0x4E554E61 |

`0x4E554E55` は `SyntheticFixtureGenerator.DEFAULT_SEED` と同じ値をcell 0に
充て、以降は軸の並び順に従って1ずつ増やす。seedは32bit範囲で一意である。

### 4.3 再現可能な実行recipe

workloadの再現には2階層がある。

**Layer A — 合成corpusの再生 (現時点で実行可能)。**
seedとcase数を固定したsynthetic corpusの生成・検証は、既存のunit-test seamが
`planner.seed` / `planner.count` を受け付けるため、次で再現できる
([building](./building.md) §Planner generated-property runsと同じ仕組み)。

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests '*PlannerGeneratedPropertyTest*' \
  -Dplanner.seed=<cell seed> \
  -Dplanner.count=64
```

このcommandは決定的な合成dataの生成とplanner契約の検証を実行する。
timing収集は行わない。

**Layer B — cell単位のworkload構築と測定 (benchmark実装Issue)。**
§4.2のcell表から`OrganizationInput`への変換は、workload builderが次の入力を
そのまま受け取ることとして定義する。

```text
WorkloadBuilder.build(
    seed: Long,            // §4.2のcell seed
    columns: Int, rows: Int, hotseatSlots: Int,   // grid軸
    workspacePages: Int,   // page軸
    totalItems: Int,       // item数軸
    profileCount: Int,     // 1 = P1、2 = P2
) -> OrganizationInput
```

builderの内部規則: §4.1のmix比率でitem kindを配分し (端数はappへ切り上げ)、
配置はseedを`java.util.Random`へ渡した決定的な順序で行う。合成identityは
`SyntheticFixtureGenerator` (spec 11 harness) と同じ方針 (合成package名・
component名・ID) とし、実端末のlayout・実在package名を入力に使わない。
builderの実装とtiming収集commandはbenchmark実装Issue (§11) が持つ。
本書はcell定義と入力変換の契約を固定する。

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
| UI-thread block | run window内のmain-threadの連続busy時間の最大値と16ms超の回数 | on-device全phase |
| frame | run window内のjank frame率とdropped frame数 | on-device (R-EMU-1では相対値のみ) |
| memory | run前後のRSS差とrun中のpeak (emulator: `dumpsys meminfo`) | 全phase |
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

- 標準: cellごとに **n = 100**。
- budget固定やregression基準の更新にはreference cellのみ **n = 300**。
- 根拠は§7の統計計算による。

### 6.4 統計量

- p50: median。p95: k番目のorder statistic、k = ceil(0.95n)。補間は行わない
  (保守側の値を取る)。
- 分散の報告: CVとIQRを必ず併記する。正規分布を仮定しない。
- cellのCV > 0.5の場合、結果に要再測定のflagを付け、そのcellの値でbudgetや
  regression判定を行わない。

### 6.5 journal retentionと無関係なsample取得

journal ([organizer-diagnostics](./organizer-diagnostics.md) §8) は
10 run / 7日 / 512 KiBで削除される。したがってsampleの保存先として
journalを使わない。on-device測定では次を守る。

- benchmark harnessは各iteration (1 run) のterminal event到達直後に、
  そのrunのjournal eventを読み出して計測artifact (§4.4の保存先) へ
  書き出す。次のiterationを開始する前に完了させる。
- 読み出し時、runの`journalSequence`が`RUN_STARTED`からterminal eventまで
  連続していることを検査する。欠落があればそのiterationを無効とし、
  再実行する。
- terminal eventに到達しないrun (crash等) は試行として記録するが、
  duration統計には含めない。
- 上記により、journalのretentionがn = 100/300の収集に影響しない
  (直近10 run分より前のeventは削除済みでも、計測artifactに全sampleが
  存在する)。

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

n = 100はCV = 0.30のとき約12%以上の差を検出できる。5%精度が必要な場合は
variance削減 (warmup増加、host負荷除去) かn = 141以上を要件として明示する。

## 8. Regression comparison method

- baselineは、直近の受領済み測定table (docs/assessment/配下の計測記録) とする。
  比較は**同じ環境class・同じcell**に限る。R-JVM-1とR-EMU-1、host間の
  cross比較をしない。
- regression flag条件: 新p95 / 旧p95 > 1.25、かつbootstrap (1000 resample) の
  percentile区間が重ならないこと。両方満たした場合のみ調査必須とする。
  mean比は補助指標として併記する。
- budget未確定・測定不能phaseも、計測値が存在するならPRに値を残し
  比較可能にする ([quality-strategy](./quality-strategy.md) §Performance
  measurementの既存取り決め)。
- **Big-O表示やimplementation上の見積りだけで合否を決めない**
  (Issue #15受入条件)。合否は常に測定値に対して行う。

## 9. Provisional budgets (NFR-006)

### 9.1 暫定budget表

全行が**provisional**である。2026-08-15時点でon-device測定が存在しないため、
数値は実測能力ではなくUX要件から導いた上限値である。cellはreference cell
(M = 120 items × G1 = 5×4 × P1)。

| Metric | p50 | p95 | 根拠とstatus |
|---|---|---|---|
| plan (pure compute) | 250ms | 1000ms | preview表示までの対話遅延として約1秒を上限と置いた。provisional |
| checkpoint (A4) | — | 500ms | apply全体の1/4以内を暫定上限とした。provisional |
| apply transaction (A5–A6) | — | 2000ms | 同上。provisional |
| verify (A7–A8) | — | 2000ms | 同上。provisional |
| end-to-end (USER_CONFIRMED→APPLY_VERIFIED) | — | 5000ms | 確認後5秒以内の完了をUX上の要求と仮定。provisional |
| UI-thread単一連続block | ≤16ms (目標) | 50ms (上限) | 60fpsのframe budget由来。provisional |
| frame jank率 (run window) | — | 5% | R-EMU-1では相対比較のみ。provisional |
| 追加peak RSS | — | 50MB | 想定workload (M) のsnapshot+plan+recovery dataサイズに対する暫定余裕。provisional |
| recovery record size | — | 10MB | 同上。provisional |

「—」はp50を暫定として固定しないものであり、測定後にp95と併せて固定する。

### 9.2 見直し条件

次のいずれかが成立した時点で、該当行を測定値に基づいて更新する。
暫定statusの解除には、§6の手順に沿った測定と、reference cellでの
n = 300の実施を要件とする。

1. on-device計測 (R-EMU-1) が最初に実施されたとき。
2. 物理端末またはhardware-accelerated emulatorの環境が追加されたとき
   (frame系・UI-thread系の絶対値)。
3. snapshot adapter、bind/model reload、UIの実装により測定不能phaseが
   測定可能になったとき。
4. workload matrixの軸 (XL追加、P2本格化、mix変更) が変わったとき。
5. 現行budgetに対するregression flagが発生し、budget側の根拠が失効したとき。

budgetの変更は本書の更新として行い、PRで根拠となる測定にリンクする。
無通告での変更・測定な引き上げをしない。

## 10. Unmeasurable phases and future hooks

| 測定不能項目 | 将来hook |
|---|---|
| snapshot capture (on-device) | integration snapshot adapterの実装Issue ([DESIGN](../../DESIGN.md) §4.4)。phase境界はjournal timestamp |
| bind / model reload (A7) | model load generation完了待ちをprotocolが返す時点で計測可能になる |
| UI-thread block / frame (on-device) | organizer UI実装後にStrictMode (penalty系detect) とChoreographer/JankStatsをrun windowに適用 |
| 実SQLite recovery store / Launcher DB transaction | model write adapter・production DB adapterの統合後、instrumentation計測として追加 |
| grid変更・migrationとの相互作用 | grid migrationを扱うIssueで別途matrixを定義する |

benchmark harness実装Issueは、これらのhookが実装済みかどうかを測定対象の
前提として明記し、測定可能phaseの追加を本表へ反映する。

## 11. CI/benchmark automationの分離基準

benchmark実装・CI自動化は本Issueでは行わない。次のいずれかが成立した時点で
専用Issueを起票して移行する。

1. 同一milestone内で3件以上のPRが手動測定を引用したとき。
2. regression flagがmerge判断を実際に変更したとき。
3. 手動での1回の測定に30分以上を要するようになったとき。

移行までの暫定測定面 (R-JVM-1) は既存のorganizer unit-test seamを流用し、
Gradle依存追加 (JMH等) はbenchmark Issueのspecで判断する。budgetがprovisional
の間、benchmarkをmerge gateにしない。

## 12. Acceptance criteria mapping

| Issue #15受入条件 | 本書の根拠 |
|---|---|
| Big-Oだけで合否を決めない | §8「測定値に対して行う」、§1 out of scope |
| synthetic dataで再現できる | §4.2–§4.3 (cell表、固定seed、Layer A/B recipe)、§6.1 (seed記録) |
| machine/device metadataを結果に含める | §6.1 (記録なしは無効) |
| planner/apply未実装phaseも測定不能箇所と将来hookを明記 | §3 (seam要件)、§10。research時点の実装状態はPR #68 |
| UI threadとend-to-end latencyを分離する | §5 (別metric、独立報告) |
| budgetは根拠または明示的provisional statusを持つ | §9.1 (全行provisional + 根拠)、§9.2 (見直し条件) |

Deliverable対応: 本書自体が`docs/engineering/performance-budgets.md`、
reference環境と再現command参照 (§2)、workload matrix (§4)、phase別metric (§5)、
warmup/sample/統計/regression方法 (§6–§8)、暫定budgetと見直し条件 (§9)、
automation分離基準 (§11)。

## Change history

- 2026-08-15: Issue #15のresearch成果物として初版。reference環境、測定可否の
  現在地、workload matrix、metric、統計法、暫定budgetと見直し条件、
  automation分離基準を定義した。
- 2026-08-15: review対応。実装状態と実行結果をPR #68へ分離し、文書は
  seam要件のみに固定。page軸、固定seedとcell表、Layer A/Bの再現recipe、
  journal retention非依存のsample取得規則 (§6.5)、統計計算の閉形式を追加した。
