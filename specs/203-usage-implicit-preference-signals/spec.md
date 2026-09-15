---
issue: "#203"
status: draft
requirements:
  - FR-013
  - D-010
updated: 2026-09-15
---

# Versioned local usage / implicit-preference signal snapshot for Organizer personalization

> Status: draft — このspecは Issue #203 の準備として作成された。本タスクでは自己承認しない。採用signal集合・permission UX・retention・bucket semantics の最終判断は Issue owner の review (acceptance) を要する (「Decisions」節参照)。
> 2026-09-12 re-entry: baseline `6b6bf8dd` 以降の main 差分 (#228/#235 の実装、composer 拡張、ADR-0007 追記) を反映して見直した。未決定事項 (U-1〜U-6) は変わりなく draft のままである。
> 2026-09-13 re-entry + review response: baseline `f9afd8bf` 以降の main 差分 (#283/#287/#300/#304 関連) を確認した — 本specが依存する composer / provenance / requirements / ADR に変更はない。2026-09-13T10:32Z の owner review (Changes requested) を反映し: (1) `generation` を snapshot identity から削除して content-addressed identity に変更、(2) personalization を mandatory dynamic cut の外側に置く optional source 契約へ変更 (source 失敗・churn は `NotReady` ではなく personalization field のみ typed `Unavailable` へ downgrade)、(3) canonicalization に usage access state / per-profile availability / field 三値 state を追加、(4) rank universe・time window semantics を U-5 に追加、(5) install age の事実誤り (`firstInstallTime` は `PackageInfo` field) を修正し first delivery から defer、(6) launcher-origin signal の観測単位を contract として固定した。owner review の recommendation (affinity は consumer 側 projection、first delivery の system usage signal 集合を 7d/30d foreground bucket + recency + active-days に絞る等) を draft 判断として反映しているが、spec 自体は draft のままである。
> 2026-09-14 re-entry + re-review response: baseline `c5274b5d` 以降の main 差分 (#298/#299/#315 関連) を確認 — composer への変更は `CaptureFailureObserver` signature への `CaptureInvariantCategory` 追加 (diagnostics) のみで、本specが依存する stable cut / provenance / `OrganizationInput` の形状に変更はない。2026-09-14T12:43Z の owner re-review (Changes requested) を反映し: (1) snapshot の実体を `OrganizationInput` の non-null field として downstream へ渡す契約に変更 (Blocking 1 — identity だけ provenance に残り実体が届かない状態を解消)、(2) `usageAccess` を **system usage source 専用**の state とし、`NOT_GRANTED` / `UNAVAILABLE` でも launcher-origin entry を保持 (Blocking 2)、(3) app pair を first delivery の launcher-origin 対象から除外 (Blocking 3 — `launchSplitTasks()` まで到達する非同期 dispatch のため同期観測点が存在しない)、(4) launcher-origin counter は相対 recency class ではなく coarse な絶対 day anchor を永続化し read 時に bucket へ投影 (Required)、(5) `PersonalizationSignalSnapshot` 自体は非永続化・永続対象は launcher-origin 最小 state のみと明記 (Required)、(6) fallback の「Absent」表現を `Unavailable` 維持 + planner が evidence 不使用の表現へ修正 (Minor)。U-1 / U-6 の draft 判断は re-review で妥当と確認されたが、acceptance までは未確定のままとする。
> 2026-09-15 re-entry + re-review response: baseline `397d3fd` 以降の main 差分 (#298 関連: LoaderTask / test / CI / docs) を再確認 — 本specが依存する composer / provenance / `OrganizationInput` の形状に変更はない。2026-09-15T02:36Z の owner re-review (Changes requested) を反映し: (1) field の三値 contract を non-null な closed 型として明示し、`FieldValue?` 等 nullable 表記を排除した (Blocking 1 — snapshot 実体が `OrganizationInput` 経由で downstream に露出するため第四の状態 `null` を許さない)、(2) taskbar を first delivery の launcher-origin 対象に **含める** と決め、surface 別の観測時点を contract として明記した (Blocking 2 — 2026-09-15 実読みにより `LauncherTaskbarUIController.onTaskbarIconLaunched` が `Launcher.logAppLaunch` へ合流し、seam 上に taskbar 由来の判別手段が存在しないため「含める」のみが契約と実装が一致する選択であることを確認)、(3) taskbar 上の app pair・promise icon が同一 seam に合流するため、item 型による明示的な除外フィルタを契約に追加、(4) U-4 を provenance identity の non-null 参加として確定 (re-review recommendation 採用)、(5) decision 解消を目的とした probe / instrumentation を実装開始禁止の対象外と明記 (U-5 の循環回避)、(6) U-2 / U-3 / U-5 に具体的 draft 値を提示した。U-1 / U-6 は 2026-09-15 re-review で妥当と確認済み。
> 2026-09-15 (2nd) re-review response: snapshot `e55051cdaa` への review (Changes requested、[Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674398728)) を反映し: (1) **Blocking 1** — recency bucket 境界を system usage / launcher-origin 共通の半開区間 `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)` で統一し、launcher-origin は同一境界の day 量子化 (day anchor 保存による解像度制限) であることを明記。「8–30d / ≥30d」の 30d 重複表記を除去、(2) **Blocking 2** — snapshot 型から `capturedAtClass` を除去し (clock 由来の表示 metadata は diagnostics が snapshot の外側で記録)、さらに **sparse object 契約** を導入: entries は availability のある section のみを持ち、unavailable section の package-level entry は object 側にも構築しない。canonical rows の省略規則と snapshot object の形状が一致し、contentDigest が snapshot の consumer-observable projection を完全に identify する (「異なる package 集合が同一 digest になる」のはその差が観測不可能な場合のみで、その場合 object 自体が同一であるため正しい)、(3) **Required** — quintile algorithm を nearest-rank の数式で確定 (境界 `B_k = x_⌈kN/5⌉`、`bucket(v) = |{k : B_k ≤ v}|`、N=5/6/7/11 の期待値を interface test に記載可能な粒度)、probe を acceptance 前提ではなく **実装ゲート** (完了条件を明記、逸脱時は U-5 依存実装 PR の merge 前に spec 変更) へ再位置づけし、U-5 の確定を acceptance 時の owner 判断へ統一した。entry field 型は `SignalField<T> = Value / Absent` (closed 二値) となり、`Unavailable` は section-level availability state 専用となる。
> 2026-09-15 (3rd) re-review response: snapshot `7cfeef92bb` への review (Changes requested、[Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/203#issuecomment-5674510548)) を反映し: (1) **Blocking 1** — active な本文に残っていた旧 `Unavailable` semantics (UsageAccessState 節の per-profile 失敗、Composition and provenance 節、fallback 説明、plan の revoke 記述) をすべて「section-level availability state を設定し、該当 section の package-level entry を構築しない」へ統一した。さらに部分 availability (system usage 利用不能 + launcher-origin 利用可能等) を表現できるよう snapshot 型を **section 分離の sealed 型** (`SystemUsageSection = Available(entries) / Unavailable`、`LauncherOriginSection = Available(entries) / Unavailable`) へ確定し、nullable な section 参照・null sentinel を持たない構造とした、(2) **Blocking 2** — nearest-rank の bucket 対応を `bucket(v) = |{k : B_k < v}|` (境界と同値は下位側に分類) へ修正し、N=5 かつ相異なる値で `x_1→0 … x_5→4` となることを明示 (bucket 0 = least used と一致。境界値と同値の app も一意に分類される)、(3) **Required** — plan の Testing strategy へ probe 完了条件 (d) (launcher-origin day anchor の DST / timezone-change 挙動) を追加し、(a)〜(d) を揃えた。

## Problem

Organizer (spec 10/12/83/182) の計画入力は、`LayoutSnapshot` / category / profile / 既存配置という authoritative なローカル入力だけで構成されている。`OrganizationInput.signals` は分類 (category assignment) 専用の `ClassificationSignals` であり、usage / recency / 現在配置が示す暗黙の preference を運ぶ欄を持たない。そのため #182 の strategy catalog は usage-based strategy を first delivery から除外せざるを得ず、#204/#205/#206 (AI personalization) は draft contract で normalized usage signal を optional 入力として前提にしている (詳細は #203 の確定に委ねられている)。#228 (未配置アプリ追加) は実装済みだが usage signal を使わず、usage access がなくても deterministic に動作する。

FR-013 は usage signal を Later、D-010 は usage access を optional とし、拒否・取得不能時にも deterministic fallback を要求する。raw Android usage API (usage stats / `UsageEvents` stream) や millisecond timestamp を planner や AI adapter へ直接渡す構造は、determinism (P-09)、privacy (diagnostics 契約)、provenance (ADR-0007) のすべてと衝突する。

## Outcome

Organizer personalization が消費できる **versioned / provenance-bearing な `PersonalizationSignalSnapshot`** を、capture/composition 時点で正規化・固定した typed local snapshot として定義する。snapshot の **実体** は `OrganizationInput` の non-null field として composition result に載り、`OrganizationPlanner.plan(OrganizationInput)` および将来の AI adapter (#204) はそこからこの snapshot (またはその projection) のみを読み、Android usage API を直接読まない。usage access は明示的 opt-in であり、拒否・未設定・取得不能・unsupported 環境でも Organizer は現在の deterministic な挙動のまま利用可能である (`NOT_GRANTED` / `UNAVAILABLE` も valid snapshot として構築される)。dynamic な usage 変化は既存の immutable policy bundle identity を書き換えない。

## Scope

- `PersonalizationSignalSnapshot` の型・値域・identity (schemaVersion / contentDigest) の定義。
- snapshot を構成する signal 候補の採用/不採用判断と根拠 (取得可能性・privacy cost・保持期間)。
- usage access permission の opt-in / 拒否 / revoke / unsupported の typed な状態モデルと、deterministic fallback 構成。
- launcher-origin launch signal (NunuLauncher 経由の起動に基づく local counter/recency) の source identity。
- snapshot の privacy 制約 (local-only、diagnostics 非流出、retention)。
- `OrganizationInput` への `personalization` field 追加 (既存 strategy は無視する) と、#182 seam (`OrganizationPlanner.plan(OrganizationInput)`) がこの input を消費するための composition 上の接続点の定義 (provenance 参加方法)。
- #204 exchange contract が normalized signal を export できる構造の要件定義。

## Non-goals

- AI / LLM provider integration、external ChatGPT/Gemini 連携、AI が最終 layout を生成する contract (#204/#205/#206 の対象)。
- usage-based layout strategy 自体の実装 (#182 の future catalog member。本specは入力のみを所有する)。
- usage を必須 permission にすること。拒否時に Organizer を使えなくすること。
- raw `UsageEvent` / event-level 履歴の永続化。
- package/profile identity の外部送信。
- #228 (未配置アプリ追加) の変更。#228 は usage signal なしで実装済みであり、本specもその挙動を変えない。

## Domain language (CONTEXT.md 追加候補、承認時)

**パーソナライゼーションsignal (Personalization Signal)**:
アプリごとの usage / recency / 配置に由来する暗黙の preference を、bounded な normalized 値として表したもの。capture 時点で固定され、raw usage 値や timestamp を含まない。
_Avoid_: usage data (raw API 出力を想起させる)、頻度統計 (millisecond 値を想起させる)

**usage access (Usage Access)**:
ユーザーが system usage情報への読み取りを NunuLauncher に明示的に許可した状態 (app-op ベースの `PACKAGE_USAGE_STATS`)。runtime permission ではなく、許可・未許可・取得不能を typed に区別する。
_Avoid_: permission (runtime permission model との混同)

**launcher-origin usage**:
NunuLauncher 自身を経由した app launch の観測に基づく local signal。system usage access の代替ではなく、独立した source identity を持つ。

## Signal candidates: adoption comparison

各候補を「取得可能性 / privacy cost / determinism 影響 / 保持必要性」で比較する。採用 (A) / 不採用 (R) / 要調査 (D) は draft 判断であり、owner review で確定する。

| Candidate | Source | 取得可能性 | Privacy cost | Draft 判断と根拠 |
|---|---|---|---|---|
| foreground 30d 相対順位/bucket | usage stats (opt-in) | `UsageStatsManager.queryUsageStats` の日次集計から合成可能。ただし端末再起動や集計期間の切れ目で undercount があり得る | 中。総量ではなく相対順位への投影で低減 | **A**。personalization の主 signal。bucket (例: 0-4 の相対五分位) へ投影 |
| foreground 7d 相対順位/bucket | 同上 | 同上 | 中 | **A**。短期変化の検出 |
| recency / last-used bucket | 同上 | `queryUsageStats` の lastTimeUsed から導出 | 低 (bucket 化) | **A**。ただし bucket は粗く (例: 24h未満/7d未満/30d未満/それ以上/不明) |
| active days / habitual-use | 同上 | 日次 usage stats の presence から導出。精度は system 集計依存 | 低 | **A**。bucket 化 (例: 0-3) |
| 短期間 launch/resume frequency | `UsageEvents` | event stream 読み取りは重く、保持期間が端末依存 (概ね数日)。信頼性が機種差で不明 | 高 (event に近い) | **R** (first delivery)。foreground bucket + active days で同等の意図をカバー |
| current page / region affinity | `LayoutSnapshot` (既存) | 常に利用可能 | なし (既存入力) | **R (snapshot 非搭載)**。owner review (2026-09-13) の recommendation に従い、本snapshot には載せない。planner / 将来 strategy は既に保有する `LayoutSnapshot` から pure projection して導出し、二重 authoritative 化しない |
| current folder membership / grouping affinity | `LayoutSnapshot` (既存) | 常に利用可能 | なし | **R (snapshot 非搭載)**。同上。consumer 側の projection とする |
| explicit category override | `CategoryOverrideSnapshot` (既存 S1) | 常に利用可能 | なし | **R** (本specでは再定義しない)。既存 S1 が authoritative。personalization engine は既存 signal を優先する旨のみ規定 |
| install age / newly-installed | `PackageInfo` (`PackageManager.getPackageInfo` / `LauncherApps` 経由) | `firstInstallTime` は取得可能。ただし `firstInstallTime` と `lastUpdateTime` は別 field であり、「update で reset される」という従前記述には本repo内に根拠がなかった (2026-09-13 review 指摘)。semantics は要実測 | 低 | **R (first delivery から defer)**。owner review recommendation。privacy cost は低いが install-age の意味論 (update 後の挙動を含む) が未検証のため、候補として再検討する場合のみ別 spec 変更で扱う |
| launcher-origin launch count/recency | NunuLauncher 経由起動の hook | 実装依存。launcher 経由以外 (通知・deep link・他launcher・assistant) を観測できない | 低 (local counter のみ) | **A** (`launcher-origin` として別 source identity)。観測単位は「Launcher-origin signal」節の contract で固定。完全な usage の代替とはしない |

不採用の理由は恒久的不採用ではなく first-delivery からの除外である。再検討は新しい spec 変更で行う。

2026-09-13 の owner review recommendation を受け、first delivery の system usage signal 集合は **foreground 30d bucket + foreground 7d bucket + recency bucket + active-days bucket** に絞る draft 判断とした。launcher-origin は system usage と独立した source として first delivery に含む。この集合の最終確定は spec acceptance で行う (U-1)。

## Snapshot contract

### 型 (draft、plan.md で確定)

```text
// entry field は availability のある section 内のみ存在し、non-null な closed 二値である
// (null は存在しない。2026-09-15 review Blocking 1):
SignalField<T> = Value(value: T) / Absent

// section の構造的不存在は sealed 型で表現する (2026-09-15 (2nd) re-review Blocking 1。
// nullable な section 参照や null sentinel は使わない):
SystemUsageSection    = Available(entries) / Unavailable
LauncherOriginSection = Available(entries) / Unavailable

PersonalizationSignalSnapshot
  schemaVersion                    // "personalization-signals-v1"
  contentDigest                    // canonical rows への sha256 (後述の grammar)。
                                   // snapshot object の consumer-observable projection 全体を
                                   // identify する (下記 sparse object 契約)
  usageAccess: UsageAccessState    // GRANTED / NOT_GRANTED / UNAVAILABLE (後述)。
                                   // system usage section の可否を表す state であり
                                   // launcher-origin section の可否を表さない
  launcherOriginAvailability:      // launcher-origin section の可否
    LAUNCHER_ORIGIN_AVAILABLE / LAUNCHER_ORIGIN_UNAVAILABLE
  profileAvailability: per profile:
    SYSTEM_USAGE_AVAILABLE / SYSTEM_USAGE_UNAVAILABLE   // profile 単位の system usage 取得可否
  systemUsage: SystemUsageSection
  launcherOrigin: LauncherOriginSection

// Available(entries) の entries は per (profile, package) であり、
// 利用可能な profile の launchable app 全体を対象にする (request 集合から独立。U-5 参照):
SystemUsageEntry
  foreground30dBucket: SignalField<Bucket>        // system usage section 由来
  foreground7dBucket: SignalField<Bucket>
  recencyBucket: SignalField<RecencyClass>
  activeDaysBucket: SignalField<ActiveDaysClass>
LauncherOriginEntry
  countClass: SignalField<CountClass>             // launcher-origin section 由来
  recencyClass: SignalField<RecencyClass>         // (read 時に day anchor から投影)
```

- **sparse object 契約 (2026-09-15 (2nd) review Blocking 2)**: section が利用不能な場合、その section は `Unavailable` (sealed 値) として表現され、**当該 section の package-level entry は snapshot object 側にも構築しない** (system usage section が利用不能なら `systemUsage = Unavailable` であり、`usageAccess` / `profileAvailability` の state が理由を運ぶ。launcher-origin section も同様)。したがって canonical rows の省略規則 (下記) と snapshot object の形状が一致し、`contentDigest` は snapshot の consumer-observable projection を **完全に** identify する。unavailable section の package 集合の違いは consumer から観測不可能であるため意味内容に含めず、同一 digest ↔ object 等価が成立する。部分 availability (system usage 利用不能 + launcher-origin 利用可能等) は section 型の分離により表現される。
- field と source の対応は静的である (system usage 4 field = `SYSTEM_USAGE_V1`、`launcherOrigin` = `LAUNCHER_ORIGIN_V1`)。canonical entry 行 (下記) が source identity を運ぶため、entries 内に別途 source を並べる欄は持たない。
- `generation` は持たない。snapshot は ephemeral (composition 毎に再構築され、capture 結果の canonical rows から決定論的に合成される) であるため、identity は **content-addressed** (`schemaVersion` + `contentDigest`) とする。monotonic generation を要求すると、composer が同一 composition 内で source を再読みした際に「内容不変でも identity が変わり、cut が安定しない」自己矛盾が生える (2026-09-13 review Blocking 1)。persistent な可変 store (override store 等) と異なり、この snapshot には read 回数を数える対象の永続 state が存在しない。将来 launcher-origin counter store 等に楽観的並行制御が必要になった場合も、その revision は store 内部に留め、snapshot identity へ露出しない (露出する場合は別の accepted decision を要する)。
- `SignalValue` は closed な小さい値域 (bucket ordinal) とし、raw millisecond・絶対回数・timestamp を含まない。
- **absence vs failure**: entry field は `SignalField<T>` の二値で扱う — `Value` (有効値) / `Absent` (source section が稼働しており、当該 app に観測がない。valid zero/absence)。**source の取得不能 (failure) は field ではなく section-level availability state が運ぶ** — section が利用不能なら当該 section の entry 自体が存在しない (sparse object 契約)。`Unavailable` を `0` や「未使用」と同一視しない。`null` は contract 上存在せず、consumer が `null` と区別を扱う必要はない (2026-09-15 review Blocking 1)。
- **相対順位の母集団 (rank universe)**: 7d/30d bucket が相対順位である以上、母集団の定義が bucket 値を決める。母集団は composition の request 集合 (今 run の対象 app 列挙) であってはならない — 対象 app が1件増減するだけで既存 app の bucket が変わり、signal が layout 変化の用途以外の要因で動く。母集団・tie 処理・少数サンプル時の挙動・timezone / calendar boundary・`queryUsageStats` の interval 境界 semantics (request した begin/end より interval 境界まで広がり得るため、単純な rolling 7x24h/30x24h とは一致しない) は **Decisions 節 U-5 の確定値** (rank universe = window 内に usage record を持つ launchable app、nearest-rank 5 分位、partial edge interval 除外等) に従う (acceptance 時に owner が承認し、probe は実装ゲート)。
- **canonicalization (contentDigest の入力)**: digest は snapshot の意味内容を完全に表す canonical row 列から計算し、少なくとも次を含む (2026-09-13 review Blocking 3、2026-09-14 review Blocking 2)。各 source の semantic state は digest 入力に含まれるため、`usageAccess=NOT_GRANTED` と query failure (`UNAVAILABLE`)、また「system usage section が利用不能 (entries に当該 section の行なし)」と「system usage section が利用可能で全 field が `Absent`」が同一 digest になることはない。
  1. header 行: `schemaVersion` と `usageAccess` state、`launcherOriginAvailability` state (両 section の semantic state)
  2. per-profile 行: 各 profile の `profileAvailability` (system usage section の availability)
  3. entry 行: `profile | package | field名 | state(Value/Absent) | 値(Value時の bucket ordinal) | source identity` — availability のある section のみ出力する
  行は決定論的に sort し `\n` join して既存 `sha256Canonical` へ渡す。
  **entry 行の省略規則は section ごとに独立であり、snapshot object の形状と一致する** (2026-09-14 review Blocking 2 + 2026-09-15 (2nd) review Blocking 2 の sparse object 契約): `usageAccess != GRANTED` (または当該 profile が `SYSTEM_USAGE_UNAVAILABLE`) の場合、省略されるのは **system usage section の行のみ** であり、object 側にも当該 section の package-level entry は存在しない。「system usage section が利用不能」は header 行と per-profile 行の state が運ぶ。`LAUNCHER_ORIGIN_AVAILABLE` である限り、`usageAccess` の state にかかわらず launcher-origin section の entry 行は常に存在する。
- **同一入力からの決定性**: 同じ schemaVersion・同じ入力 (usage stats 読み取り結果・同じ window anchor・同じ launcher-origin counter 状態) からは同じ contentDigest が得られる。bucket 計算は pure 関数とし、clock は source が read 開始時に1回だけ読む単一の window anchor を通じてのみ入る。clock 由来の表示用 metadata (capture 時刻区分等) は snapshot 型から除去した (2026-09-15 (2nd) review Blocking 2) — snapshot は semantic content のみを持ち、表示目的の情報は diagnostics が snapshot の外側で記録する。window 定義は Decisions 節 U-5。
- **identity**: snapshot は `PolicyInputIdentity(source: PERSONALIZATION_SIGNAL_SNAPSHOT, versionOrGeneration: schemaVersion 文字列, sha256: contentDigest)` で identify される。dynamic input であり immutable bundle identity には参加しない (spec 182 の selection snapshot と同じ分割)。既存の platform evidence identity (`PLATFORM_CLASSIFICATION_EVIDENCE` + schema 文字列 + canonical rows digest) と同型の content-addressed 形態である。

### UsageAccessState

```text
sealed UsageAccessState
  GRANTED       // app-op granted かつ query 成功
  NOT_GRANTED   // 明示的に未許可、または未設定
  UNAVAILABLE   // 許可はあるが query 失敗 / profile で取得不能 / 端末が unsupported
```

- `NOT_GRANTED` と `UNAVAILABLE` は共に「**system usage section が利用不能** (entries に system usage section の package-level entry が object / canonical rows ともに存在しない — sparse object 契約)」を意味するが、diagnostics と permission UI の分岐のため型として区別する。この state は **system usage section にのみ適用され、launcher-origin section の可否には影響しない** (2026-09-14 review Blocking 2 — Usage Access を未許可にしただけで NunuLauncher 自身が保持する launcher-origin signal が消えることはない)。
- `GRANTED` の場合でも profile 単位で取得失敗があり得る。その場合 `usageAccess` は `GRANTED` のまま、失敗した profile の `profileAvailability` を `SYSTEM_USAGE_UNAVAILABLE` とし、**当該 profile の system usage entry のみを構築しない** (section-level availability。全体を `UNAVAILABLE` に切り下げない)。`usageAccess=UNAVAILABLE` は「1つも profile で取得できなかった」場合に使う。U-6 は 2026-09-14 re-review で妥当と確認済み (Decisions 節)、profileAvailability は canonicalization に含まれる。
- revoked (許可が後から取り消された) 場合は次の capture で `NOT_GRANTED` に遷移する。永続化した古い snapshot を流用しない。

## Composition and provenance

- `PersonalizationSignalSnapshot` は capture/composition 時点で構築・固定され、planner および AI adapter はこの snapshot のみを読む。planner が Android usage API・`UsageStatsManager`・app-op 状態に直接触れる経路は存在しない (AGENTS.md 設計規約・#182 と同じ purity 要求)。
- **snapshot の実体は `OrganizationInput` 経由で downstream へ渡される** (2026-09-14 review Blocking 1)。`OrganizationInput` に non-null な `personalization: PersonalizationSignalSnapshot` field を追加し、composer が各 composition attempt で構築した snapshot を `Ready(input, provenance)` の `input` に載せる。`OrganizationInputComposition.Ready` 自体は変更しない (snapshot は `input` 経由で届く)。`NOT_GRANTED` / `UNAVAILABLE` / per-profile 失敗も「該当 section を availability state で表現し、該当 section の package-level entry を構築しない valid snapshot」(sparse object 契約) であるため、field は nullable にしない。identity だけが provenance に残り実体が downstream に届かない構成は本specの Outcome を満たさない。usage-based strategy 自体の実装は引き続き別 Issue であり、既存 strategy は新 field を無視する。
- **personalization は optional source であり、mandatory dynamic cut の外側に置く** (2026-09-13 review Blocking 2)。composer の既存 cut (`dynamicCutIdentity`: bundle / overrides / evidence / selection) と `MAX_DYNAMIC_ATTEMPTS` retry → `NotReady(InconsistentPolicyRead)` の semantics は mandatory source 専用のまま変更しない。personalization snapshot は composition attempt ごとに **1回だけ** 読む:
  - usage access が `NOT_GRANTED` / `UNAVAILABLE` の場合、または query・profile 読みが失敗した場合、対応する **section を availability state で表現し、当該 section の package-level entry は snapshot object に構築しない** (sparse object 契約)。composition 自体は既存 deterministic input だけで `Ready` になる (FR-013 / D-010)。
  - personalization 由来の如何なる失敗・不安定も `NotReady` を生んではならず、`InputReadinessReason` / `InputCompositionCode` の mandatory 失敗系に流用しない。
  - snapshot が composition 中の usage 変化を跨いだ (capture と snapshot の時点が僅かにずれる) 場合も、それは failure ではない。stale 判定は capture `RevisionId` が正本であり、usage の変化は captured plan を stale にしないため、single read で足りる。将来 usage を planner が直接消費する戦略を導入する際に consistency 保証を強める場合は、[ADR-0007](../../docs/adr/0007-authoritative-organization-policy-sources.md) §6 の「future source は accepted decision を通じてのみ protocol を置換できる」に従い別 decision とする。
- snapshot の `PolicyInputIdentity` は `InputProvenance` へ **non-null な追加 field** として参加する (U-4 確定 — 2026-09-15 review recommendation の採用): `InputProvenance` (現行7 field) に `personalization: PolicyInputIdentity` を 8番目の field として追加し、すべての `Ready(input, provenance)` で non-null の snapshot identity を運ぶ。「optional source」の optional 性は **Organizer readiness に対するもの** であり、composition result 上の snapshot / identity の存在まで optional にはしない (snapshot 実体が `OrganizationInput.personalization` に常に non-null で載ることと対称である)。`NOT_GRANTED` の snapshot も「system usage 由来 field がすべて利用不能」を意味内容として運む valid snapshot であり、canonicalization (Blocking 3 対応) により安定した digest を持つため、常に参加できる。#228 の `scopeComposedTargetsIdentity` (canonical 追加内容による target identity 拡張、ADR-0007 §targets) と platform evidence identity (schema 文字列 + rows digest) が同型の先例である。`InputProvenance` の field 追加に伴う constructor caller の機械的更新は plan.md の Change set に含める。
- **personalization evidence を使わない構成も正**: usage access がなくても composition は成功し、#182 のすべての strategy は現行どおり動作する。strategy は `OrganizationInput.personalization` を受け取るが first delivery の既存 strategy はこれを無視する (読まない)。personalization snapshot は optional input であり、required source の欠落は `NotReady` にならない (D-010)。deterministic fallback の意味は「既存 deterministic planner が personalization evidence を使用せず従来挙動で動作する」ことであり、section 単位の利用不能は availability state が運び、無観測 (`Absent`) とは混同しない (2026-09-14 review Minor)。

## Permission and fallback behavior

| 状態 | 観測可能な挙動 |
|---|---|
| 未許可 (default) | Organizer は現行完全機能で動作。personalization snapshot は常に構築され、`usageAccess=NOT_GRANTED` (system usage section は利用不能 — 当該 section の entry は object / rows ともに存在せず、header state が意味を運ぶ) として表現されるが、**launcher-origin section は保持される**。deterministic fallback は「既存 deterministic planner が personalization evidence を使用せず従来挙動で動作する」ことであり、section 単位の利用不能は `Absent` (無観測) とは表現しない — 2026-09-14 review Minor |
| opt-in (初回) | 明示的な user action のみ。導線の置き場所は **settings の Organizer セクションに常設** (U-2 draft — manual run 内での自動的な再促しは行わない)。許可 flow は system の usage-access settings (`ACTION_USAGE_ACCESS_SETTINGS`) への遷移。rationale 文言は「usage 情報は端末内でのみ使用される (local-only)」「整理精度の向上が目的」「許可しなくても Organizer の全機能は利用可能」を含む |
| 拒否 | 拒否自体は失敗ではない。Organizer は引き続き利用可能。再表示の頻度・タイミングは騒がせない (draft: 同一 run では再促しない) |
| 許可後の revoke | 次 capture で `NOT_GRANTED`。永続 signal の使用を停止し、fallback 構成へ戻る。既存 plan preview への影響なし |
| `UNAVAILABLE` (許可あるが query 失敗・unsupported) | system usage section を利用不能として合成 (当該 section の entry は構築しない)。Organizer は継続。diagnostics には typed code のみ (理由の文字列・package は含めない) |
| work profile / private space | profile ごとに usage query が失敗し得る。失敗 profile のみ `profileAvailability=SYSTEM_USAGE_UNAVAILABLE` とし、他 profile と Organizer 自体は影響を受けない (表現の詳細は Decisions 節 U-6 — 確定済み。`UNAVAILABLE` への全体切り下げは「1つも取得できない場合」に限定) |

## Privacy / security

- usage 由来情報は **local-only**。network transport・backup・diagnostics export に出さない。
- diagnostics (run journal, [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) の契約) へは: `usageAccessState` の typed code と snapshot identity (schemaVersion/digest) のみを出す。package 名・profile identity・raw usage 値・timestamp・bucket 値自体も出さない。
- raw `UsageEvents` / event-level 履歴を永続化しない。
- retention: **`PersonalizationSignalSnapshot` 自体は永続化しない**。snapshot は現在 run の composition 入力としてのみ保持され、composer が都度再構築する (bucket 値を含む snapshot を書き込む store・cache は作らない — 2026-09-14 review Required)。**永続化対象は launcher-origin の最小 state のみ** である (Launcher-origin signal 節参照)。launcher-origin state は package ごとの coarse counter (U-3 draft: lifetime 累積 count) + 絶対 day anchor に限る。counter store は local-only であり、backup / restore・export の対象外とする (U-3 draft)。ユーザーは settings で記録の停止 (opt-out — 記録停止と既存記録の消去をセット、default は記録 ON) と既存記録の明示的な消去ができる (U-3 draft)。
- 将来 AI (#204) へ渡す場合も、この snapshot の normalized field のみを export する構造にする。raw usage 値を export する契約は作らない。

## Launcher-origin signal

- **観測単位 (contract)**: counter が数えるのは「NunuLauncher の UI から発せられた launch request が、platform への dispatch で **同期失敗せずに受け付けられた**」という事実である。foreground へ実際に遷移したことの確認 (実 foreground 成功) は含まない — launcher は dispatch 後の foreground 遷移を同期観測できず、確認を要求すると system usage access と同等の権限を事実上要求することになる (2026-09-13 review Required specification)。
  - **対象に含む (launcher activity surface)**: workspace / all-apps / folder 内の app icon tap、deep shortcut (所有 package へ attribution)、`FLAG_START_FOR_RESULT` 付き shortcut launch。これらは `ActivityContext.startActivitySafely` の dispatch 成功時 (`logAppLaunch` 呼び出し、同期失敗 (`NullPointerException` / `ActivityNotFoundException` / `SecurityException`) では呼ばれない) または `ItemClickHandler` の FLAG_START_FOR_RESULT path を通じて単一の seam (`Launcher.logAppLaunch`) に合流し、観測時点は「dispatch 成功」である。
  - **対象に含む (taskbar — 2026-09-15 review Blocking 2 で含めることを確定)**: taskbar 上の app icon tap (taskbar 由来の workspace item / deep shortcut / all-apps item)。2026-09-15 の実読みにより、upstream の `LauncherTaskbarUIController.onTaskbarIconLaunched` (`LauncherTaskbarUIController.java:337`) が `Launcher.logAppLaunch` へ合流すること、およびこの合流を seam 上で taskbar 由来と判別する手段が存在しないこと (`getStatsLogManager()` は `StatsLogManager.newInstance` の都度生成であり instance 比較で判別できず、taskbar item は hotseat 等と container 値を共有し taskbar 固有の marker も存在しない) を確認した。したがって taskbar を **含める** のみが本契約と実装が一致する選択であり、除外は追加の fork 変更 (判別機構の導入) を伴って初めて可能になる。taskbar の合流は upstream の既存挙動であり、本specは fork 側でこれを変更・遅延させない。
  - **観測時点の surface 別意味 (taskbar)**: taskbar 由来の観測時点は upstream 観測点が提供する時点そのものである。overview taskbar mode では実 platform dispatch が `findLastActiveTasksAndRunCallback` の非同期 callback 内で行われるため、観測は dispatch 発火 **前** の「taskbar UI が起動要求を発火した」時点になり、in-app taskbar mode の同期 dispatch 失敗 (`startItemInfoActivity` 内 catch) も観測後に発生して補正されない。すなわち taskbar 由来の観測が表す意味は「起動要求発火」であり「dispatch 成功」を保証しない。launcher-origin signal は本節末尾のとおり過小・過大報告の既知 blind spot を持つ local signal であり、surface 間の時点差を許容する。
  - **対象外**: **app pair 起動 (first delivery は対象外 — 2026-09-14 review Blocking 3)**。`QuickstepLauncher.launchAppPair()` は `AppPairsController.launchAppPair()` を呼ぶだけで、実 platform dispatch (`launchSplitTasks()`) は `findLastActiveTasksAndRunCallback` の **非同期 callback 内** で行われる。そのため呼び出し戻り時点での観測は「dispatch 成功」より早くなり、観測単位 contract を満たせない。**taskbar 上の app pair は `LauncherTaskbarUIController` 経由で同一 seam に合流するため、hook 実装は item 型 (`AppPairInfo`) による明示的な除外フィルタを要求する** (合流するが counting 対象から外す)。実 dispatch point への観測 seam 追加は Launcher3 bridge を伴うため first delivery では行わず、app pair の観測が必要になった場合に別の accepted decision で扱う。加えて、**taskbar の recents 起動 (`startActivityFromRecents` — seam に合流しない)**、promise icon / install session の market 導線 (launcher / taskbar 両経路で合流するため item 型による明示的な除外フィルタを要求する。`ActivityNotFoundException` 系の同期失敗を含む)、widget 操作、folder open、NunuLauncher 外で発生した起動 (通知・deep link・他 launcher・assistant — 仕様上の既知の blind spot) も対象外である。
- hook は既存の観測点を優先する。Launcher3 の `ActivityContext.startActivitySafely` は platform dispatch (`startShortcut` / `startActivity` / `startMainActivity`) の成功時にのみ既存の `logAppLaunch` を呼び (dispatch が同期失敗した場合は呼ばない)、「dispatch 成功」の既定義 observation point として使用できる。hook の配置は bridge となる最小箇所とし、Launcher3/AOSP 由来 file を直接編集するより fork 所有 class (`LawnchairLauncher` / `QuickstepLauncher` subclass) の override で賄える点を plan.md で確認済み。Issue 番号と理由を近傍に残す (AGENTS.md)。
- **hook (override) は既存の `logAppLaunch` 挙動を保存しなければならない** (2026-09-15 review Required): 現行の `QuickstepLauncher.logAppLaunch()` は単なる event log ではなく、All Apps session の InstanceId 補正、prediction rank の付与、`LAUNCHER_APP_LAUNCH_TAP` logging、hotseat prediction ranking info の記録を行う。override は **`super.logAppLaunch(...)` をすべての path で exactly once 呼び**、これら既存の logging / prediction side effect を変更しない。counter 書き込みは best-effort (非同期・例外を吸収) であり、書き込み失敗が既存の launch flow / logging を壊してはならない。
- この signal は `LAUNCHER_ORIGIN_V1` という独立 source identity を持ち、system usage (`SYSTEM_USAGE_V1`) とは集計・解釈で混ぜない。通知・deep link・他 launcher・app pair・taskbar の recents 経由の起動を観測できない (または counting 対象外である) ことが仕様上の制約であることを明示する。taskbar icon tap を包摂しても、taskbar 由来は「起動要求発火」時点であり、launcher-origin は依然 system usage の下位集合であり過小報告になる。
- **recency の永続表現 (2026-09-14 review Required)**: counter store は相対的な recency class を直接永続化しない — 相対 class は時間経過だけで変化する (例: `<24h` を保存しても翌日には `1–7d` になるべき) ので、保存しても正しくない。store は **coarse な絶対 day anchor (epoch-day 相当)** と count の最小 state を永続化し、recency bucket は snapshot 構築時に anchor から pure 投影する。count class の意味論は Decisions 節 U-3 draft (lifetime 累積 count の bounded bucket) に従う。
- counter の更新は観測点が発火した時点に限り (surface 別の観測時点は上記のとおり)、UI thread を block しない。counter 破損時は fail-closed (`launcherOriginAvailability=LAUNCHER_ORIGIN_UNAVAILABLE` とし launcher-origin section の entry を構築しない — sparse object 契約) とし、推測で補完しない。

## Relationship to #182 / #204 / #228

- **#182** (implemented): 本specは #182 の seam (`OrganizationPlanner.plan`) が将来消費する新しい authoritative input を所有する。本specは `OrganizationInput` に `personalization` field を追加するが、#182 の first delivery・runtime-supported set・bundle identity は影響を受けない (既存 strategy は新 field を無視する。本specも `organization-policy-v2.6` の bundle identity を書き換えない)。usage-based strategy の追加は #182 の child issue 形式で別途行う。
- **#204/#205/#206**: いずれも draft spec/plan snapshot が専用 branch に存在するだけで未accept (2026-09-12 時点)。exchange contract は本snapshot の normalized field を対象に定義される。本specは「export できる構造」の要件のみを定め、exchange 形状は #204 が所有する。#204 draft は `usageSignals` を optional とし詳細を本specの確定に委ねているため、本specの確定は #204 の受入前提を満たす。
- **#228** (implemented): usage signal なしで deterministic に動作する。usage があれば候補提示の改善に使ってもよいが、usage signal の利用不能 (section-level availability) を「追加すべきでない」証拠として扱ってはならない。#228 が導入した `RunMode.ScopeComposedOrganization` と candidate identity 拡張 (`scopeComposedTargetsIdentity`) は本snapshot の provenance 参加に影響しない。

## Compatibility / migration

- 新規 local state の導入のみ。Launcher DB・recovery・backup/restore は変更しない。
- `OrganizationInput` への `personalization` field 追加と `InputProvenance` への `personalization: PolicyInputIdentity` 追加 (U-4) は organizer module 内部の data class 変更であり、constructor caller (composer とその tests) に機械的な更新を要求する。planner 側の既存 strategy の挙動は不変である (新 field を読まない)。
- personalization snapshot に schema version を持たせ、store-aware binary が newer schema を読んだ場合は fail-closed (spec 182 の選択store と同じ三ケースdowngrade model)。snapshot 自体は永続化しないため、この model が対象にするのは launcher-origin counter store の schema である。
- bundle version・`rule-v2`・selection store schema は本specでは変更しない。

## Recovery / rollback

- 本機能は plan apply・recovery に一切関与しない。opt-out (usage access 撤回) は次回 composition から deterministic fallback に戻るだけであり、既存 layout・recovery point への影響はない。
- launcher-origin counter の消去は user から見える操作で行い、消去後は `Absent` 扱いに戻る。

## Accessibility

- permission opt-in 導線・rationale・opt-out は TalkBack / Switch Access / 200% font scaling に対応 (spec 52/195 と同等の要求)。
- 許可状態は視覚のみでなく text でも示す。

## Acceptance criteria

- [ ] AC-1: signal候補の採用/不採用と根拠 (取得可能性・privacy cost・保持) が本specで承認済みである。
- [ ] AC-2: `PersonalizationSignalSnapshot` の型・値域・identity が確定し、同一入力から同一 contentDigest が得られることが interface test で示されている。entry field は non-null な closed 二値 `SignalField<T>` (Value / Absent)、`entries` は availability のある section のみを持つ (sparse object 契約) であり、canonicalization には `usageAccess` state・`launcherOriginAvailability` state・per-profile availability・entry 行が含まれ、`NOT_GRANTED` と query failure、section 利用不能と `Absent` が同一 digest にならないことが test で示されている。**contentDigest が snapshot object の consumer-observable projection と 1:1 に対応すること (同一 digest ↔ object 等価、異なる observable 内容が同一 digest にならないこと) が property/interface test で示されている** (2026-09-15 (2nd) review Blocking 2)。
- [ ] AC-3: usage access denied/unavailable で deterministic fallback が成立する (Organizer 全機能が現行どおり動作する) ことが unit/integration/device evidence で示されている。
- [ ] AC-4: source の取得不能 (section-level availability state) と valid zero/absence (entry field の `Absent`) が typed に区別されることが contract test で示されている。
- [ ] AC-5: snapshot が content-addressed identity (schemaVersion + digest) を持ち、stale/replan semantics (capture revision が正本、usage 変化は plan を stale にしない) が test で示されている。
- [ ] AC-6: planner / AI adapter が Android usage API を直接読まないことが purity guard / architecture test で検証される。
- [ ] AC-7: default diagnostics に raw usage/package/profile identity が流出しないことが diagnostics contract test で検証される。
- [ ] AC-8: launcher-origin signal が system usage と別 source identity として扱われ、混ざらないことが test で示されている。
- [ ] AC-9: FR-013/D-010 の traceability が [requirements.md](../../docs/product/requirements.md) で更新されている。
- [ ] AC-10: dynamic usage 変化が immutable policy bundle identity を書き換えないことが provenance test で示されている。
- [ ] AC-11: personalization source の query 失敗・profile 失敗・読み取り時点の churn があっても composition が `Ready` で完了し、personalization の該当 section のみが利用不能化 (該当 section の entries が構築されない、section availability state で表現) されることが composer test で示されている (personalization 由来の `NotReady` が存在しないこと)。
- [ ] AC-12: すべての run mode の `Ready` において `OrganizationInput.personalization` が non-null snapshot を運び、既存 strategy の挙動が personalization field 追加前後で不変であることが composer / planner test で示されている (2026-09-14 review Blocking 1)。
- [ ] AC-13: `usageAccess=NOT_GRANTED` / `UNAVAILABLE` でも launcher-origin section の entry が snapshot に保持され、system usage section のみが entries から除外される (sparse object 契約どおり) ことが test で示されている (2026-09-14 review Blocking 2)。
- [ ] AC-14: app pair 起動が launcher-origin counter を進めないこと (launcher activity 経由は観測 hook が存在しない、taskbar 経由は item 型フィルタで除外) が test で示されている (2026-09-14 review Blocking 3、2026-09-15 review)。
- [ ] AC-15: `LawnchairLauncher.logAppLaunch` override が `super.logAppLaunch(...)` をすべての path で exactly once 呼び、All Apps session の InstanceId 補正・prediction rank 付与・`LAUNCHER_APP_LAUNCH_TAP` logging・hotseat prediction ranking info の既存挙動が override 前後で不変であること、counter 書き込み失敗が launch flow / logging を壊さないことが test で示されている (2026-09-15 review Required)。
- [ ] AC-16: taskbar 由来の icon tap が launcher-origin counter に記録されること (観測時点は surface 別 contract のとおり「起動要求発火」)、taskbar の recents 起動が seam に合流しないため記録対象にならないこと、promise icon / market 導線が item 型フィルタで除外されることが test で示されている (2026-09-15 review Blocking 2)。

## Decisions (acceptance 判定の対象)

2026-09-13 の owner review recommendation を draft 判断として反映し、以降の re-review で方向性を確認してきた。2026-09-15 re-review で U-1 / U-6 は妥当と確認され、U-4 は recommendation の採用により確定した。U-2 / U-3 / U-5 は本 revision で具体的値を提示する。全項目の最終確定は spec acceptance で行う。

**probe / instrumentation の位置づけ (2026-09-15 review、(2nd) review で再位置づけ)**: decision 解消を目的とした probe / instrumentation (一時的な instrumentation test、実 `UsageStatsManager` の実機計測など。production code への変更を伴わない) は「実装開始禁止」の対象 **外** とする (spec 未確定ゆえに probe できず、probe できないゆえに U-5 を確定できない循環を避けるため)。ただし probe は **acceptance の前提ではない** — U-5 の contract 値は acceptance 時の owner 判断で確定し、probe は **実装ゲート** として U-5 に依存する実装 PR が merge する前に環境前提を検証する (完了条件は U-5 内に定義)。probe が環境前提との逸脱を検出した場合は、当該実装 PR の merge 前に spec 変更で契約を修正する。

- **U-1 採用 signal 集合 — 確定 (2026-09-15 re-review で妥当と確認)**: first delivery = system usage 4信号 (foreground 30d / 7d bucket、recency、active-days) + launcher-origin (独立 source、taskbar icon tap を含む — Launcher-origin signal 節)。placement affinity は snapshot 非搭載 (consumer が `LayoutSnapshot` から pure projection)、install age は defer。acceptance 時に確定記録を行う。
- **U-2 permission UX — draft**: 導線は **settings の Organizer セクションに常設** (usage access の現状表示 + rationale + system usage-access settings への遷移)。manual run 内での自動的な再促しは行わない (初回 draft の「騒がせない」方針を維持)。rationale 文言の要件は Permission and fallback behavior 節のとおり。accessibility 要件は Accessibility 節のとおり。
- **U-3 retention / count class の意味論 — draft**: count class は **lifetime 累積 count の bounded bucket** (draft 値: 0 / 1–4 / 5–19 / 20–99 / ≥100)。飽和 (≥100) は recency bucket との組合せで解釈する前提であり、window 内頻度は first delivery では持たない (永続 state を count + day anchor の最小構成に保つ — 2026-09-14 review Required)。recency は day anchor から 当日 / 1–6日前 / 7–29日前 / ≥30日前 へ read 時に投影する。retention: counter store は local-only・backup / restore・export 対象外。settings に launcher-origin 記録の toggle (default ON、OFF で記録停止 + 既存記録の消去) と明示的な消去操作を置く (Privacy 節と整合)。
- **U-4 provenance 参加形式 — 確定 (2026-09-15 review recommendation 採用)**: `InputProvenance` への non-null な `personalization: PolicyInputIdentity` 追加 (8番目の field)。nullable 参加枠・snapshot identity 専用の別構造は採らない。「optional source」の optional 性は readiness に対するものであり、composition result 上の identity の存在は常に必須。mandatory dynamic cut への参加は 2026-09-13 review Blocking 2 により選択肢から除外済み (変更なし)。
- **U-5 bucket・rank semantics — 確定値提示 (acceptance で確定、probe は実装ゲート)**: 各 bucket の区分数・境界に加え、相対順位の **rank universe**、tie 処理、少数サンプル時の挙動、timezone / calendar boundary、`queryUsageStats` の interval 境界 semantics、launcher-origin day anchor の day boundary を含む。確定値:
  - **rank universe**: 当該 window 内に usage record を持つ launchable app (request 集合は不可 — 2026-09-13 review Required specification)。usage record の有無が変わるのは当該 app の usage 自体が変化したときのみであり、request 集合由来の不穏当な bucket 変動を避ける。
  - **foreground 30d / 7d bucket (quantile algorithm — 2026-09-15 (2nd) review Required で確定)**: universe 内の各 app の foreground 総時間を昇順ソートした列を `x_1 ≤ x_2 ≤ … ≤ x_N` とし、**nearest-rank** で境界を `B_k = x_{⌈k·N/5⌉}` (k = 1..4) と定義し、**bucket への対応は `bucket(v) = |{ k ∈ {1..4} : B_k < v }|` ∈ {0..4} (境界と同値は下位側に分類)** とする。**bucket 0 = 最も使用時間が短い側** であり、N=5 かつ全値が相異なる場合の対応は `x_1→0, x_2→1, x_3→2, x_4→3, x_5→4` である (2026-09-15 (2nd) re-review Blocking 2 で不等号を確定)。同値は境界式より必然的に同 bucket になる (tie を序数で分割しない。境界値と同値の app も同式で一意に分類される)。この式により `bucket` は pure 関数として interface test に期待値を記載できる (境界の例: N=5 の場合 `x_1..x_4`、N=6 の場合は `x_2,x_3,x_4,x_5`、N=7 の場合は `x_2,x_3,x_5,x_6`、N=11 の場合は `x_3,x_5,x_7,x_9`)。30d / 7d とも同一手順を用いる。
  - **少数サンプル**: universe が 5 未満の場合、30d / 7d foreground bucket は構築しない (当該 app の `SignalField` は `Absent` — 順位付け不能)。recency / active-days は絶対しきい値のため影響しない。
  - **recency bucket (境界 — 2026-09-15 (2nd) review Blocking 1 で統一)**: 最終使用からの経過時間を半開区間 `[0,1d) / [1d,7d) / [7d,30d) / [30d,∞)` の 4 段階で分類する (1d = 24h)。観測なしは `Absent`。
  - **active-days bucket (30d)**: 観測日数 0 / 1–3 / 4–9 / 10–19 / 20–30 の 5 段階。
  - **launcher-origin recency (system recency と同境界の day 量子化)**: launcher-origin は day anchor を永続化するため解像度が暦日単位である。上記と同一の半開区間境界を暦日差へ量子化して投影する — `当日` (= [0,1d) 相当) / `1–6日前` (= [1d,7d)) / `7–29日前` (= [7d,30d)) / `≥30日前` (= [30d,∞))。system recency と境界が一致していることを明示する (暦日量子化により [0,1d) 内の実時刻の差は保存しない — これは day anchor 永続化の既知の解像度制限である)。
  - **window semantics**: 単一 window anchor (capture 時の device local time) を基準に **local calendar day 単位**の過去 N 日 (anchor 日を含む)。`queryUsageStats(INTERVAL_DAILY)` の interval が window 境界に跨る場合、**window に完全に含まれる interval のみ**集計する (partial edge は除外 — 決定論的。境界日の過少計上は既知 limitation として記録する)。timezone 変更後の capture は現行 device timezone で再計算する (snapshot は永続しないため自然に従う)。
  - **launcher-origin day anchor**: device local calendar day の epoch-day (day boundary は device 現行 timezone の暦日)。
  - **probe の完了条件 (実装ゲート)**: U-5 に依存する実装 PR は merge 前に次を記録すること — (a) `queryUsageStats(INTERVAL_DAILY)` の実 returns の interval 境界・粒度の実測記録、(b) full-containment フィルタの実データでの動作確認、(c) 30日分の取得可否 (保持期間) の確認、(d) launcher-origin day anchor の DST / timezone 変更時の挙動確認。逸脱を検出した場合は、当該 PR の merge 前に spec 変更で契約を修正する。probe 自体は実装開始禁止の対象外 (本節先頭)。
- **U-6 per-profile availability の型表現 — 確定 (2026-09-14 re-review で妥当と確認)**: `profileAvailability` は失敗 profile のみ `SYSTEM_USAGE_UNAVAILABLE`、全体 `UNAVAILABLE` への切り下げは「1つも取得できない場合」のみ (UsageAccessState 節参照)。canonicalization への参加は確定済み。

## References

- [Issue #203](https://github.com/nunu1733/NunuLauncher/issues/203)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 83: production OrganizationInput sources](../83-production-organization-input-sources/spec.md)
- [Spec 99: user-authored category overrides](../99-user-authored-category-overrides/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [requirements.md (FR-013, D-010)](../../docs/product/requirements.md)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
