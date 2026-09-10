---
issue: "#203"
status: draft
requirements:
  - FR-013
  - D-010
updated: 2026-09-10
---

# Versioned local usage / implicit-preference signal snapshot for Organizer personalization

> Status: draft — このspecは Issue #203 の準備として作成された。本タスクでは自己承認しない。採用signal集合・permission UX・retentionの最終判断は Issue owner の review を要する (「Unresolved decisions」参照)。

## Problem

Organizer (spec 10/12/83/182) の計画入力は、`LayoutSnapshot` / category / profile / 既存配置という authoritative なローカル入力だけで構成されている。`OrganizationInput.signals` は分類 (category assignment) 専用の `ClassificationSignals` であり、usage / recency / 現在配置が示す暗黙の preference を運ぶ欄を持たない。そのため #182 の strategy catalog は usage-based strategy を first delivery から除外せざるを得ず、#204/#205/#206 (AI personalization) および #228 (未配置アプリ追加) は normalized usage signal を前提に設計されているのに、それを供給する authoritative input が存在しない。

FR-013 は usage signal を Later、D-010 は usage access を optional とし、拒否・取得不能時にも deterministic fallback を要求する。raw Android usage API (usage stats / `UsageEvents` stream) や millisecond timestamp を planner や AI adapter へ直接渡す構造は、determinism (P-09)、privacy (diagnostics 契約)、provenance (ADR-0007) のすべてと衝突する。

## Outcome

Organizer personalization が消費できる **versioned / provenance-bearing な `PersonalizationSignalSnapshot`** を、capture/composition 時点で正規化・固定した typed local snapshot として定義する。planner および将来の AI adapter (#204) はこの snapshot (またはその projection) だけを読み、Android usage API を直接読まない。usage access は明示的 opt-in であり、拒否・未設定・取得不能・unsupported 環境でも Organizer は現在の deterministic な挙動のまま利用可能である。dynamic な usage 変化は既存の immutable policy bundle identity を書き換えない。

## Scope

- `PersonalizationSignalSnapshot` の型・値域・identity (generation / content digest / capturedAtClass) の定義。
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
- #228 (未配置アプリ追加) の実装。#228 は本specなしで deterministic に動作する。

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
| current page / region affinity | `LayoutSnapshot` (既存) | 常に利用可能 | なし (既存入力) | **A**。ただし planner は既に snapshot を持つため、snapshot 側の projection として定義し二重入力にしない |
| current folder membership / grouping affinity | `LayoutSnapshot` (既存) | 常に利用可能 | なし | **A**。同上 |
| explicit category override | `CategoryOverrideSnapshot` (既存 S1) | 常に利用可能 | なし | **R** (本specでは再定義しない)。既存 S1 が authoritative。personalization engine は既存 signal を優先する旨のみ規定 |
| install age / newly-installed | `LauncherApps` / `ApplicationInfo` | firstInstallTime は取得可能。ただし update で reset される端末差がある | 低 | **D**。privacy は問題ないが semantics が不安定。bucket 化 (新規/非新規) なら採用可。owner 判断 |
| launcher-origin launch count/recency | NunuLauncher 経由起動の hook | 実装依存。launcher 経由以外 (通知・deep link・他launcher・assistant) を観測できない | 低 (local counter のみ) | **A** (`launcher-origin` として別 source identity)。完全な usage の代替とはしない |

不採用の理由は恒久的不採用ではなく first-delivery からの除外である。再検討は新しい spec 変更で行う。

## Snapshot contract

### 型 (draft、plan.md で確定)

```text
PersonalizationSignalSnapshot
  schemaVersion                    // "personalization-signals-v1"
  generation                       // monotonic、captureごとに増加
  contentDigest                    // canonical rows への sha256
  capturedAtClass                  // coarse 時間区分のみ (例: 日次 bucket)。
                                   // raw timestamp は持たない
  usageAccess: UsageAccessState    // GRANTED / NOT_GRANTED / UNAVAILABLE (後述)
  entries: per (package, profile):
    foreground30dBucket: SignalValue?   // null = absence (source は value と区別)
    foreground7dBucket: SignalValue?
    recencyBucket: SignalValue?
    activeDaysBucket: SignalValue?
    launcherOrigin: LauncherOriginSignal?  // count class + recency class
    placementAffinity: PlacementAffinity?  // LayoutSnapshot からの projection
  sources: per-field source identity (SYSTEM_USAGE_V1 / LAUNCHER_ORIGIN_V1 / SNAPSHOT_DERIVED_V1)
```

- `SignalValue` は closed な小さい値域 (bucket ordinal) とし、raw millisecond・絶対回数・timestamp を含まない。
- **absence vs failure**: 各 field は三値で扱う — `value` (有効値) / `absent` (source が稼働しているが当該 app に観測がない。valid zero/absence) / `unavailable` (source 全体が取得不能)。`unavailable` を `0` や「未使用」と同一視しない。
- **同一 canonical capture からの決定性**: 同じ schemaVersion・同じ入力 (usage stats 読み取り結果の canonical rows・同じ LayoutSnapshot revision・同じ launcher-origin counter 状態) からは同じ snapshot contentDigest が得られる。bucket 計算に clock 依存の境界を入れない (capturedAtClass はdigest 計算から除外するか、coarse 境界のみ使う — plan.md で固定)。
- **identity**: snapshot は `PolicyInputIdentity` と同じ契約 (source kind・versionOrGeneration・sha256) で identify される。dynamic input であり immutable bundle identity には参加しない (spec 182 の selection snapshot と同じ分割)。

### UsageAccessState

```text
sealed UsageAccessState
  GRANTED       // app-op granted かつ query 成功
  NOT_GRANTED   // 明示的に未許可、または未設定
  UNAVAILABLE   // 許可はあるが query 失敗 / profile で取得不能 / 端末が unsupported
```

- `NOT_GRANTED` と `UNAVAILABLE` は共に「system usage 由来 field がすべて `unavailable`」を意味するが、diagnostics と permission UI の分岐のため型として区別する。
- revoked (許可が後から取り消された) 場合は次の capture で `NOT_GRANTED` に遷移する。永続化した古い snapshot を流用しない。

## Composition and provenance

- `PersonalizationSignalSnapshot` は capture/composition 時点で構築・固定され、planner および AI adapter はこの snapshot のみを読む。planner が Android usage API・`UsageStatsManager`・app-op 状態に直接触れる経路は存在しない (AGENTS.md 設計規約・#182 と同じ purity 要求)。
- snapshot の `PolicyInputIdentity` は `InputProvenance` への追加参加 (6番目の dynamic input) として composer の stable cut に加わる。dynamic cut identity (`dynamicCutIdentity`) は personalization snapshot の generation+digest を含め、cut 不安定時は既存どおり bounded retry の後 `NotReady(InconsistentPolicyRead)` とする。参加形態の最終形状 (provenance field 追加 vs signals identity への統合) は plan.md で固定する。
- **personalization snapshot を消費しない構成も正**: usage access がなくても composition は成功し、#182 のすべての strategy は現行どおり動作する。personalization snapshot は optional input であり、required source の欠落は `NotReady` にならない (D-010)。
- stale 判定は既存どおり capture `RevisionId` が正本。usage の変化そのものは captured plan を stale にしない — snapshot identity が変われば別の composition として次回 run に反映される。

## Permission and fallback behavior

| 状態 | 観測可能な挙動 |
|---|---|
| 未許可 (default) | Organizer は現行完全機能で動作。personalization snapshot は `usageAccess=NOT_GRANTED` で system usage field がすべて `unavailable` の snapshot として構築されるか、または構築そのものを property として表現する (plan で固定)。いずれにせよ deterministic fallback は「absence」扱い |
| opt-in (初回) | 明示的な user action (settings/manual run 内の明示的な許可導線) のみ。許可 flow は system の usage-access settings (`ACTION_USAGE_ACCESS_SETTINGS`) への遷移。rationale 文言を定義する |
| 拒否 | 拒否自体は失敗ではない。Organizer は引き続き利用可能。再表示の頻度・タイミングは騒がせない (draft: 同一 run では再促しない) |
| 許可後の revoke | 次 capture で `NOT_GRANTED`。永続 signal の使用を停止し、fallback 構成へ戻る。既存 plan preview への影響なし |
| `UNAVAILABLE` (許可あるが query 失敗・unsupported) | system usage field を `unavailable` として合成。Organizer は継続。diagnostics には typed code のみ (理由の文字列・package は含めない) |
| work profile / private space | profile ごとに usage query が失敗し得る。profile 単位で `unavailable` を表現できるか、全体 `UNAVAILABLE` に切り下げるかは plan で固定 (現行 evidence source の per-profile fail-closed 慣行に合わせる) |

## Privacy / security

- usage 由来情報は **local-only**。network transport・backup・diagnostics export に出さない。
- diagnostics (run journal, [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) の契約) へは: `usageAccessState` の typed code と snapshot identity (schemaVersion/generation/digest) のみを出す。package 名・profile identity・raw usage 値・timestamp・bucket 値自体も出さない。
- raw `UsageEvents` / event-level 履歴を永続化しない。永続化するのは bucket 化された snapshot (および launcher-origin counter) のみ。
- retention: personalization snapshot は現在 run の composition 入力としてのみ保持し、composer が都度再構築する。launcher-origin counter は count class / recency class への投影に必要な最小限 (package ごとの coarse counter + last-launch class) に限り、ユーザーが opt-out すれば停止し消去する。詳細な保持期間・消去 timing は Unresolved (U-3)。
- 将来 AI (#204) へ渡す場合も、この snapshot の normalized field のみを export する構造にする。raw usage 値を export する契約は作らない。

## Launcher-origin signal

- NunuLauncher が app launch を引き当てる経路 (Launcher3 `ItemClickHandler` 由来の launch path) において、package + profile 単位の local counter / last-launch class を更新する hook を置く。hook は bridge となる最小箇所とし、Issue 番号と理由を近傍に残す (AGENTS.md)。
- この signal は `LAUNCHER_ORIGIN_V1` という独立 source identity を持ち、system usage (`SYSTEM_USAGE_V1`) とは集計・解釈で混ぜない。通知・deep link・他 launcher 経由の起動を観測できないことが仕様上の制約であることを明示する。
- counter の更新は launch 成功時に限り、UI thread を block しない。counter 破損時は fail-closed (`LAUNCHER_ORIGIN` field を `unavailable` 化) とし、推測で補完しない。

## Relationship to #182 / #204 / #228

- **#182**: 本specは #182 の seam (`OrganizationPlanner.plan`) が将来消費する新しい authoritative input を所有する。#182 の first delivery・runtime-supported set・bundle identity は本specの影響を受けない。usage-based strategy の追加は #182 の child issue 形式で別途行う。
- **#204/#205/#206**: exchange contract は本snapshot の normalized field を対象に定義する。本specは「export できる構造」の要件のみを定め、exchange 形状は #204 が所有する。
- **#228**: #228 は本specなしで deterministic に動作する。usage があれば候補提示の改善に使ってもよいが、`unavailable` を「追加すべきでない」証拠として扱ってはならない。

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
- [ ] AC-2: `PersonalizationSignalSnapshot` の型・値域・identity が確定し、同一入力から同一 contentDigest が得られることが interface test で示されている。
- [ ] AC-3: usage access denied/unavailable で deterministic fallback が成立する (Organizer 全機能が現行どおり動作する) ことが unit/integration/device evidence で示されている。
- [ ] AC-4: source `unavailable` と valid zero/absence が typed に区別されることが contract test で示されている。
- [ ] AC-5: snapshot に generation/digest identity があり、stale/replan semantics (capture revision が正本) が test で示されている。
- [ ] AC-6: planner / AI adapter が Android usage API を直接読まないことが purity guard / architecture test で検証される。
- [ ] AC-7: default diagnostics に raw usage/package/profile identity が流出しないことが diagnostics contract test で検証される。
- [ ] AC-8: launcher-origin signal が system usage と別 source identity として扱われ、混ざらないことが test で示されている。
- [ ] AC-9: FR-013/D-010 の traceability が [requirements.md](../../docs/product/requirements.md) で更新されている。
- [ ] AC-10: dynamic usage 変化が immutable policy bundle identity を書き換えないことが provenance test で示されている。

## Unresolved decisions (実装開始前に owner 判断が必要)

- **U-1 採用 signal の最終確定**: 上記 draft 表 (特に install age の bucket 化、placementAffinity を本snapshot に載せるか planner が LayoutSnapshot から導出するか)。
- **U-2 permission UX**: opt-in 導線の置き場所 (settings のみ / manual run 内 / onboarding)、rationale 文言、再提示 policy。
- **U-3 retention/消去**: launcher-origin counter の保持粒度と user-visible な消去操作の位置づけ。
- **U-4 provenance 参加形式**: `InputProvenance` への新 field vs 既存 signals identity への統合 (composer 変更範囲に直結)。
- **U-5 bucket 値域**: 各 bucket の具体的な区分数・境界。capturedAtClass の digest 参加要否 (決定性との両立)。
- **U-6 profile ごとの usage 取得可否**: per-profile `unavailable` 表現 vs 全体切り下げ。

いずれも本specの draft 状態を保つ限り実装を開始できない (AGENTS.md「未決定の製品判断が残る場合は実装せず」)。

## References

- [Issue #203](https://github.com/nunu1733/NunuLauncher/issues/203)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 83: production OrganizationInput sources](../83-production-organization-input-sources/spec.md)
- [Spec 99: user-authored category overrides](../99-user-authored-category-overrides/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [requirements.md (FR-013, D-010)](../../docs/product/requirements.md)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
