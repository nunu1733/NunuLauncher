---
issue: "#203"
status: draft
requirements:
  - FR-013
  - D-010
updated: 2026-09-13
---

# Versioned local usage / implicit-preference signal snapshot for Organizer personalization

> Status: draft — このspecは Issue #203 の準備として作成された。本タスクでは自己承認しない。採用signal集合・permission UX・retentionの最終判断は Issue owner の review を要する (「Unresolved decisions」参照)。
> 2026-09-12 re-entry: baseline `6b6bf8dd` 以降の main 差分 (#228/#235 の実装、composer 拡張、ADR-0007 追記) を反映して見直した。未決定事項 (U-1〜U-6) は変わりなく draft のままである。
> 2026-09-13 re-entry + review response: baseline `f9afd8bf` 以降の main 差分 (#283/#287/#300/#304 関連) を確認した — 本specが依存する composer / provenance / requirements / ADR に変更はない。2026-09-13T10:32Z の owner review (Changes requested) を反映し: (1) `generation` を snapshot identity から削除して content-addressed identity に変更、(2) personalization を mandatory dynamic cut の外側に置く optional source 契約へ変更 (source 失敗・churn は `NotReady` ではなく personalization field のみ typed `unavailable` へ downgrade)、(3) canonicalization に usage access state / per-profile availability / field 三値 state を追加、(4) rank universe・time window semantics を U-5 に追加、(5) install age の事実誤り (`firstInstallTime` は `PackageInfo` field) を修正し first delivery から defer、(6) launcher-origin signal の観測単位を contract として固定した。owner review の recommendation (affinity は consumer 側 projection、first delivery の system usage signal 集合を 7d/30d foreground bucket + recency + active-days に絞る等) を draft 判断として反映しているが、spec 自体は draft のままである。

## Problem

Organizer (spec 10/12/83/182) の計画入力は、`LayoutSnapshot` / category / profile / 既存配置という authoritative なローカル入力だけで構成されている。`OrganizationInput.signals` は分類 (category assignment) 専用の `ClassificationSignals` であり、usage / recency / 現在配置が示す暗黙の preference を運ぶ欄を持たない。そのため #182 の strategy catalog は usage-based strategy を first delivery から除外せざるを得ず、#204/#205/#206 (AI personalization) は draft contract で normalized usage signal を optional 入力として前提にしている (詳細は #203 の確定に委ねられている)。#228 (未配置アプリ追加) は実装済みだが usage signal を使わず、usage access がなくても deterministic に動作する。

FR-013 は usage signal を Later、D-010 は usage access を optional とし、拒否・取得不能時にも deterministic fallback を要求する。raw Android usage API (usage stats / `UsageEvents` stream) や millisecond timestamp を planner や AI adapter へ直接渡す構造は、determinism (P-09)、privacy (diagnostics 契約)、provenance (ADR-0007) のすべてと衝突する。

## Outcome

Organizer personalization が消費できる **versioned / provenance-bearing な `PersonalizationSignalSnapshot`** を、capture/composition 時点で正規化・固定した typed local snapshot として定義する。planner および将来の AI adapter (#204) はこの snapshot (またはその projection) だけを読み、Android usage API を直接読まない。usage access は明示的 opt-in であり、拒否・未設定・取得不能・unsupported 環境でも Organizer は現在の deterministic な挙動のまま利用可能である。dynamic な usage 変化は既存の immutable policy bundle identity を書き換えない。

## Scope

- `PersonalizationSignalSnapshot` の型・値域・identity (schemaVersion / contentDigest / capturedAtClass) の定義。
- snapshot を構成する signal 候補の採用/不採用判断と根拠 (取得可能性・privacy cost・保持期間)。
- usage access permission の opt-in / 拒否 / revoke / unsupported の typed な状態モデルと、deterministic fallback 構成。
- launcher-origin launch signal (NunuLauncher 経由の起動に基づく local counter/recency) の source identity。
- snapshot の privacy 制約 (local-only、diagnostics 非流出、retention)。
- #182 seam (`OrganizationPlanner.plan(OrganizationInput)`) がこの input を消費するための composition 上の接続点の定義 (provenance 参加方法)。
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
PersonalizationSignalSnapshot
  schemaVersion                    // "personalization-signals-v1"
  contentDigest                    // canonical rows への sha256 (後述の grammar)
  capturedAtClass                  // coarse 時間区分のみ (例: 日次 bucket)。
                                   // raw timestamp は持たない。diagnostic 表示用 metadata であり
                                   // contentDigest 計算からは除外する (下記決定性を参照)
  usageAccess: UsageAccessState    // GRANTED / NOT_GRANTED / UNAVAILABLE (後述)
  profileAvailability: per profile:
    SYSTEM_USAGE_AVAILABLE / SYSTEM_USAGE_UNAVAILABLE   // profile 単位の取得可否
  entries: per (package, profile):
    foreground30dBucket: FieldValue?   // 三値 state (value/absent/unavailable) を型で表現
    foreground7dBucket: FieldValue?
    recencyBucket: FieldValue?
    activeDaysBucket: FieldValue?
    launcherOrigin: LauncherOriginSignal?  // count class + recency class
  sources: per-field source identity (SYSTEM_USAGE_V1 / LAUNCHER_ORIGIN_V1)
```

- `generation` は持たない。snapshot は ephemeral (composition 毎に再構築され、capture 結果の canonical rows から決定論的に合成される) であるため、identity は **content-addressed** (`schemaVersion` + `contentDigest`) とする。monotonic generation を要求すると、composer が同一 composition 内で source を再読みした際に「内容不変でも identity が変わり、cut が安定しない」自己矛盾が生える (2026-09-13 review Blocking 1)。persistent な可変 store (override store 等) と異なり、この snapshot には read 回数を数える対象の永続 state が存在しない。将来 launcher-origin counter store 等に楽観的並行制御が必要になった場合も、その revision は store 内部に留め、snapshot identity へ露出しない (露出する場合は別の accepted decision を要する)。
- `SignalValue` は closed な小さい値域 (bucket ordinal) とし、raw millisecond・絶対回数・timestamp を含まない。
- **absence vs failure**: 各 field は三値で扱う — `value` (有効値) / `absent` (source が稼働しているが当該 app に観測がない。valid zero/absence) / `unavailable` (source 全体または当該 profile が取得不能)。`unavailable` を `0` や「未使用」と同一視しない。
- **相対順位の母集団 (rank universe)**: 7d/30d bucket が相対順位である以上、母集団の定義が bucket 値を決める。母集団は composition の request 集合 (今 run の対象 app 列挙) であってはならない — 対象 app が1件増減するだけで既存 app の bucket が変わり、signal が layout 変化の用途以外の要因で動く。母集団は request 集合から独立・決定論的な universe (候補: launcher から launchable な全 app / usage record が1件でも存在する app) から選び、tie 処理・少数サンプル時の挙動・timezone / calendar boundary・`queryUsageStats` の interval 境界 semantics (request した begin/end より interval 境界まで広がり得るため、単純な rolling 7x24h/30x24h とは一致しない) を含めて U-5 で確定する。**ここが未確定のまま実装してはならない** (2026-09-13 review Required specification)。
- **canonicalization (contentDigest の入力)**: digest は snapshot の意味内容を完全に表す canonical row 列から計算し、少なくとも次を含む (2026-09-13 review Blocking 3)。
  1. header 行: `schemaVersion` と `usageAccess` state
  2. per-profile 行: 各 profile の `profileAvailability`
  3. entry 行: `profile | package | field名 | state(value/absent/unavailable) | 値(value時の bucket ordinal) | source identity`
  行は決定論的に sort し `\n` join して既存 `sha256Canonical` へ渡す。`usageAccess=NOT_GRANTED` と query failure (`UNAVAILABLE`)、また「全 field が absent」と「全 field が unavailable」が同一 digest になることはない。`usageAccess != GRANTED` の場合は entries を空にし、header 行の state が「system usage 由来 field がすべて利用不能」を運ぶ (全 app 分の row を列挙しない)。
- **同一入力からの決定性**: 同じ schemaVersion・同じ入力 (usage stats 読み取り結果・同じ window anchor・同じ launcher-origin counter 状態) からは同じ contentDigest が得られる。bucket 計算は pure 関数とし、clock は source が read 開始時に1回だけ読む単一の window anchor を通じてのみ入る。`capturedAtClass` は clock 由来の表示 metadata であるため digest から **除外することを確定** する (window 定義自体の詳細は U-5)。
- **identity**: snapshot は `PolicyInputIdentity(source: PERSONALIZATION_SIGNAL_SNAPSHOT, versionOrGeneration: schemaVersion 文字列, sha256: contentDigest)` で identify される。dynamic input であり immutable bundle identity には参加しない (spec 182 の selection snapshot と同じ分割)。既存の platform evidence identity (`PLATFORM_CLASSIFICATION_EVIDENCE` + schema 文字列 + canonical rows digest) と同型の content-addressed 形態である。

### UsageAccessState

```text
sealed UsageAccessState
  GRANTED       // app-op granted かつ query 成功
  NOT_GRANTED   // 明示的に未許可、または未設定
  UNAVAILABLE   // 許可はあるが query 失敗 / profile で取得不能 / 端末が unsupported
```

- `NOT_GRANTED` と `UNAVAILABLE` は共に「system usage 由来 field がすべて `unavailable`」を意味するが、diagnostics と permission UI の分岐のため型として区別する。
- `GRANTED` の場合でも profile 単位で取得失敗があり得る。その場合 `usageAccess` は `GRANTED` のまま、失敗した profile の `profileAvailability` を `SYSTEM_USAGE_UNAVAILABLE` とし、当該 profile の system usage 由来 field のみを `unavailable` にする (全体を `UNAVAILABLE` に切り下げない)。`usageAccess=UNAVAILABLE` は「1つも profile で取得できなかった」場合に使う。U-6 で最終確定するが、いずれの解でも profileAvailability は canonicalization に含まれる。
- revoked (許可が後から取り消された) 場合は次の capture で `NOT_GRANTED` に遷移する。永続化した古い snapshot を流用しない。

## Composition and provenance

- `PersonalizationSignalSnapshot` は capture/composition 時点で構築・固定され、planner および AI adapter はこの snapshot のみを読む。planner が Android usage API・`UsageStatsManager`・app-op 状態に直接触れる経路は存在しない (AGENTS.md 設計規約・#182 と同じ purity 要求)。
- **personalization は optional source であり、mandatory dynamic cut の外側に置く** (2026-09-13 review Blocking 2)。composer の既存 cut (`dynamicCutIdentity`: bundle / overrides / evidence / selection) と `MAX_DYNAMIC_ATTEMPTS` retry → `NotReady(InconsistentPolicyRead)` の semantics は mandatory source 専用のまま変更しない。personalization snapshot は composition attempt ごとに **1回だけ** 読む:
  - usage access が `NOT_GRANTED` / `UNAVAILABLE` の場合、または query・profile 読みが失敗した場合、対応する field / profile を typed `unavailable` として snapshot に表現し、composition 自体は既存 deterministic input だけで `Ready` になる (FR-013 / D-010)。
  - personalization 由来の如何なる失敗・不安定も `NotReady` を生んではならず、`InputReadinessReason` / `InputCompositionCode` の mandatory 失敗系に流用しない。
  - snapshot が composition 中の usage 変化を跨いだ (capture と snapshot の時点が僅かにずれる) 場合も、それは failure ではない。stale 判定は capture `RevisionId` が正本であり、usage の変化は captured plan を stale にしないため、single read で足りる。将来 usage を planner が直接消費する戦略を導入する際に consistency 保証を強める場合は、[ADR-0007](../../docs/adr/0007-authoritative-organization-policy-sources.md) §6 の「future source は accepted decision を通じてのみ protocol を置換できる」に従い別 decision とする。
- snapshot の `PolicyInputIdentity` は `InputProvenance` (現行7 field: revision + 6 identity) に **optional な追加参加** をする (形態の最終確定は U-4)。`NOT_GRANTED` の snapshot も「system usage 由来 field がすべて利用不能」を意味内容として運む valid snapshot であり、canonicalization (Blocking 3 対応) により安定した digest を持つため、provenance へ常に参加してよい。#228 の `scopeComposedTargetsIdentity` (canonical 追加内容による target identity 拡張、ADR-0007 §targets) と platform evidence identity (schema 文字列 + rows digest) が同型の先例である。
- **personalization snapshot を消費しない構成も正**: usage access がなくても composition は成功し、#182 のすべての strategy は現行どおり動作する。personalization snapshot は optional input であり、required source の欠落は `NotReady` にならない (D-010)。

## Permission and fallback behavior

| 状態 | 観測可能な挙動 |
|---|---|
| 未許可 (default) | Organizer は現行完全機能で動作。personalization snapshot は常に構築され、`usageAccess=NOT_GRANTED` / entries 空 (system usage 由来 field はすべて利用不能を header state が運ぶ) として表現される。deterministic fallback は「absence」扱い |
| opt-in (初回) | 明示的な user action (settings/manual run 内の明示的な許可導線) のみ。許可 flow は system の usage-access settings (`ACTION_USAGE_ACCESS_SETTINGS`) への遷移。rationale 文言を定義する |
| 拒否 | 拒否自体は失敗ではない。Organizer は引き続き利用可能。再表示の頻度・タイミングは騒がせない (draft: 同一 run では再促しない) |
| 許可後の revoke | 次 capture で `NOT_GRANTED`。永続 signal の使用を停止し、fallback 構成へ戻る。既存 plan preview への影響なし |
| `UNAVAILABLE` (許可あるが query 失敗・unsupported) | system usage field を `unavailable` として合成。Organizer は継続。diagnostics には typed code のみ (理由の文字列・package は含めない) |
| work profile / private space | profile ごとに usage query が失敗し得る。失敗 profile のみ `profileAvailability=SYSTEM_USAGE_UNAVAILABLE` とし、他 profile と Organizer 自体は影響を受けない (表現の詳細は U-6 で最終確定。`UNAVAILABLE` への全体切り下げは「1つも取得できない場合」に限定) |

## Privacy / security

- usage 由来情報は **local-only**。network transport・backup・diagnostics export に出さない。
- diagnostics (run journal, [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) の契約) へは: `usageAccessState` の typed code と snapshot identity (schemaVersion/digest) のみを出す。package 名・profile identity・raw usage 値・timestamp・bucket 値自体も出さない。
- raw `UsageEvents` / event-level 履歴を永続化しない。永続化するのは bucket 化された snapshot (および launcher-origin counter) のみ。
- retention: personalization snapshot は現在 run の composition 入力としてのみ保持し、composer が都度再構築する。launcher-origin counter は count class / recency class への投影に必要な最小限 (package ごとの coarse counter + last-launch class) に限り、ユーザーが opt-out すれば停止し消去する。詳細な保持期間・消去 timing は Unresolved (U-3)。
- 将来 AI (#204) へ渡す場合も、この snapshot の normalized field のみを export する構造にする。raw usage 値を export する契約は作らない。

## Launcher-origin signal

- **観測単位 (contract)**: counter が数えるのは「NunuLauncher の UI から発せられた launch request が、platform への dispatch で **同期失敗せずに受け付けられた**」という事実である。foreground へ実際に遷移したことの確認 (実 foreground 成功) は含まない — launcher は dispatch 後の foreground 遷移を同期観測できず、確認を要求すると system usage access と同等の権限を事実上要求することになる (2026-09-13 review Required specification)。
  - **対象に含む**: workspace / all-apps / folder 内の app icon tap、deep shortcut (所有 package へ attribution)、`FLAG_START_FOR_RESULT` 付き shortcut launch、app pair 起動 (member app ごとに 1 observation。platform が実行中 task を再利用した場合も「launcher が user の操作でその app を引き当てた」ことには変わりがないため数える)。
  - **対象外**: promise icon / install session の market 導線 (`ActivityNotFoundException` 系の同期失敗を含む)、widget 操作、folder open、NunuLauncher 外で発生した起動 (通知・deep link・他 launcher・assistant — 仕様上の既知の blind spot)。
- hook は既存の観測点を優先する。Launcher3 の `ActivityContext.startActivitySafely` は platform dispatch の成功時にのみ既存の `logAppLaunch` を呼ぶ (dispatch が同期失敗した場合は呼ばない) ため、「dispatch 成功」の既定義 observation point として使用できる。app pair は `AppPairsController` 経由で別経路であるため個別の hook を要する。hook の配置は bridge となる最小箇所とし、Launcher3/AOSP 由来 file を直接編集するより fork 所有 class の override で賄える点を plan.md で確認済み。Issue 番号と理由を近傍に残す (AGENTS.md)。
- この signal は `LAUNCHER_ORIGIN_V1` という独立 source identity を持ち、system usage (`SYSTEM_USAGE_V1`) とは集計・解釈で混ぜない。通知・deep link・他 launcher 経由の起動を観測できないことが仕様上の制約であることを明示する。
- counter の更新は dispatch 成功時に限り、UI thread を block しない。counter 破損時は fail-closed (`LAUNCHER_ORIGIN` field を `unavailable` 化) とし、推測で補完しない。

## Relationship to #182 / #204 / #228

- **#182** (implemented): 本specは #182 の seam (`OrganizationPlanner.plan`) が将来消費する新しい authoritative input を所有する。#182 の first delivery・runtime-supported set・bundle identity は本specの影響を受けない (本specも `organization-policy-v2.6` の bundle identity を書き換えない)。usage-based strategy の追加は #182 の child issue 形式で別途行う。
- **#204/#205/#206**: いずれも draft spec/plan snapshot が専用 branch に存在するだけで未accept (2026-09-12 時点)。exchange contract は本snapshot の normalized field を対象に定義される。本specは「export できる構造」の要件のみを定め、exchange 形状は #204 が所有する。#204 draft は `usageSignals` を optional とし詳細を本specの確定に委ねているため、本specの確定は #204 の受入前提を満たす。
- **#228** (implemented): usage signal なしで deterministic に動作する。usage があれば候補提示の改善に使ってもよいが、`unavailable` を「追加すべきでない」証拠として扱ってはならない。#228 が導入した `RunMode.ScopeComposedOrganization` と candidate identity 拡張 (`scopeComposedTargetsIdentity`) は本snapshot の provenance 参加に影響しない。

## Compatibility / migration

- 新規 local state の導入のみ。Launcher DB・recovery・backup/restore は変更しない。
- personalization snapshot に schema version を持たせ、store-aware binary が newer schema を読んだ場合は fail-closed (spec 182 の選択store と同じ三ケースdowngrade model)。
- bundle version・`rule-v2`・selection store schema は本specでは変更しない。

## Recovery / rollback

- 本機能は plan apply・recovery に一切関与しない。opt-out (usage access 撤回) は次回 composition から deterministic fallback に戻るだけであり、既存 layout・recovery point への影響はない。
- launcher-origin counter の消去は user から見える操作で行い、消去後は `absent` 扱いに戻る。

## Accessibility

- permission opt-in 導線・rationale・opt-out は TalkBack / Switch Access / 200% font scaling に対応 (spec 52/195 と同等の要求)。
- 許可状態は視覚のみでなく text でも示す。

## Acceptance criteria

- [ ] AC-1: signal候補の採用/不採用と根拠 (取得可能性・privacy cost・保持) が本specで承認済みである。
- [ ] AC-2: `PersonalizationSignalSnapshot` の型・値域・identity が確定し、同一入力から同一 contentDigest が得られることが interface test で示されている。canonicalization には `usageAccess` state・per-profile availability・field 三値 state が含まれ、`NOT_GRANTED` と query failure、`absent` と `unavailable` が同一 digest にならないことが test で示されている。
- [ ] AC-3: usage access denied/unavailable で deterministic fallback が成立する (Organizer 全機能が現行どおり動作する) ことが unit/integration/device evidence で示されている。
- [ ] AC-4: source `unavailable` と valid zero/absence が typed に区別されることが contract test で示されている。
- [ ] AC-5: snapshot が content-addressed identity (schemaVersion + digest) を持ち、stale/replan semantics (capture revision が正本、usage 変化は plan を stale にしない) が test で示されている。
- [ ] AC-6: planner / AI adapter が Android usage API を直接読まないことが purity guard / architecture test で検証される。
- [ ] AC-7: default diagnostics に raw usage/package/profile identity が流出しないことが diagnostics contract test で検証される。
- [ ] AC-8: launcher-origin signal が system usage と別 source identity として扱われ、混ざらないことが test で示されている。
- [ ] AC-9: FR-013/D-010 の traceability が [requirements.md](../../docs/product/requirements.md) で更新されている。
- [ ] AC-10: dynamic usage 変化が immutable policy bundle identity を書き換えないことが provenance test で示されている。
- [ ] AC-11: personalization source の query 失敗・profile 失敗・読み取り時点の churn があっても composition が `Ready` で完了し、personalization field のみ typed `unavailable` に downgrade されることが composer test で示されている (personalization 由来の `NotReady` が存在しないこと)。

## Unresolved decisions (実装開始前に owner 判断が必要)

2026-09-13 の owner review recommendation を draft 判断として反映済みのため、残っているのは recommendation の最終確認と、recommendation が及ばない詳細の確定である。いずれも本specの draft 状態を保つ限り実装を開始できない (AGENTS.md「未決定の製品判断が残る場合は実装せず」)。

- **U-1 採用 signal 集合の最終確認**: first delivery = system usage 4信号 (foreground 30d / 7d bucket、recency、active-days) + launcher-origin (独立 source)。placement affinity は snapshot 非搭載 (consumer が `LayoutSnapshot` から pure projection)、install age は defer。owner review (2026-09-13) recommendation の通り。acceptance 時に確定記録を行う。
- **U-2 permission UX**: opt-in 導線の置き場所 (settings のみ / manual run 内 / onboarding)、rationale 文言、再提示 policy。
- **U-3 retention/消去**: launcher-origin counter の保持粒度と user-visible な消去操作の位置づけ。
- **U-4 provenance 参加形式**: optional 参加の型の形状 — `InputProvenance` への nullable 追加 field vs snapshot identity を載せる専用の別構造。mandatory dynamic cut への参加は 2026-09-13 review Blocking 2 により選択肢から除外済み (U-4 で選ぶのは record 形態のみ)。
- **U-5 bucket・rank semantics の確定**: 各 bucket の区分数・境界に加え (2026-09-13 review Required specification)、相対順位の **rank universe** (launchable 全 app / usage record 存在 app のいずれか — request 集合は不可)、tie 処理、少数サンプル時の挙動、timezone / calendar boundary、`queryUsageStats` の interval 境界 semantics (partial edge bucket の扱い)。`capturedAtClass` の digest 除外は本specで確定済み。
- **U-6 per-profile availability の型表現**: `profileAvailability` の具体的な型・集約規則 (draft: 失敗 profile のみ `SYSTEM_USAGE_UNAVAILABLE`、全体 `UNAVAILABLE` は全滅時のみ — UsageAccessState 節参照)。canonicalization への参加は確定済み。

## References

- [Issue #203](https://github.com/nunu1733/NunuLauncher/issues/203)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 83: production OrganizationInput sources](../83-production-organization-input-sources/spec.md)
- [Spec 99: user-authored category overrides](../99-user-authored-category-overrides/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [requirements.md (FR-013, D-010)](../../docs/product/requirements.md)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
