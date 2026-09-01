---
status: accepted
issue: "#185"
updated: 2026-09-01
---

# QSB予約行item重複のinterop意味論と受容policyのstaleness保証

## Decision

**1. 予約重複itemはpreserved空間へ射影する。** QSB予約(workspace reservation)とセル重複するdesktop itemは、capture時点で表現可能な状態として扱う。`RowManifestCodec` は重複をrequireでfail-closeせず、行をlosslessに保持する。plannerは当該itemを全既存preserve reasonに先行する `Preserved(RESERVED_REGION)` として扱い、動かさない。plan materializationの予約target guardは「targetが当該itemのcaptured placementと同一」の場合(その場保存)のみ免除し、予約セルへの新規移動は引き続き拒絶する。apply/recoveryのexact precondition(item identity、container/screen、cell/span、行contentの完全一致)は一切緩和しない。

**2. 予約geometryは狭小化しない。** capture側の予約は `LoaderCursor.checkItemPlacement` がload時にmarkする矩形(`numSearchContainerColumns × 1`)と一致させ続ける。5x5 gridでは `numSearchContainerColumns == numColumns` であり狭小化は効果がなく、capture側だけ狭めると#155の相関再読込削除を再発させる。

**3. 受容policyのstaleness検出はstate-based predicateで行う。** 予約重複itemの受容可否(`allowWidgetOverlap` が `LoaderCursor` に与える影響)は、revision/digest/recovery formatには含めない。代わりに、(a) compose時に受容gateとして1回評価し、(b) A5(applyのtransaction内)とrecovery適用時に現在の受容policyを再読し、**intended stateが予約重複desktop行を含む場合は現在の受容が必須**というpredicateで再評価する。不成立ならtyped `PreconditionFailed(OVERLAP_POLICY_REJECTED)` でno-writeとする。これは「pref変更が必ず先にworkspace revisionを変える」という未保証の仮定に依存しない。

**4. acceptance判定は共通predicateを正本とし、`LoaderCursor` との一致はcontract testで守る。** organizer内の受容判定を複数箇所にboolean複製せず、単一の内部predicate(composer gate・A5・recoveryの全経路で共有)として定義する。`LoaderCursor` からの直接参照(bridge)は、loader hot pathの変更とflavor/JVM test compile構成への影響を考慮し**行わない**。代わりに、organizerのpredicateと `LoaderCursor.checkItemPlacement` のQSB行重複acceptance ruleが同一の入力で同じ結果を返す等価contract testを必須とし、driftを検知する。本ADRはADR-0008の帰結のうち「real item overlap は typed non-write failure」の部分を置換する。予約自身の不正geometry、予約↔予約重複、item↔item重複のfail-closedは不変である。

## Context

Issue #185は、Nova 5x5 restoreでQSB予約行内にitemが残ったworkspace( favorites row 115、`screen=0 cell(2,0)` )でmanual organizerのcaptureが恒久的に `CAPTURE_INVALID` となる問題を追跡する(#172 assessmentで実証)。`LoaderCursor` はQSB行をmarkした上で重複itemの存続を `allowWidgetOverlap` に委ねるため、platformが受容している状態でorganizerだけが拒絶するinterop不一致である。

`allowWidgetOverlap` の `onSet` はgrid reloadを伴うが、`LayoutState` が変化しない限りrevisionは不変であり得る。compose後に受容が反転した場合、古い計算結果(重複itemをpreservedするplan)をrevision検証だけで適用すると、correlated reloadが当該itemを削除しA7/recovery検証を壊す。受容policyの真実はcapture時点の端末設定であり、layoutのcanonical contentではないため、digestに混入させること自体が誤りである。

## Alternatives

| Alternative | Advantages | Disqualifying cost or risk |
|---|---|---|
| 予約重複itemのpreserved射影 + state-based predicate再評価(採用) | loader受容状態でorganizerだけが拒絶する不一致を解消。apply/recovery契約・recovery format・revision計算を不変に保つ。反転・入替・occupant追加をexact preconditionとpredicateで閉じる | A5/recoveryに新preconditionと `PreWriteRejection` 値の追加が必要 |
| 予約の狭小化 | captureの変更のみで済む | 5x5でno-op。`LoaderCursor` とgeometryがずれ、#155を再発させる |
| import時の拒絶・修復(Nova converter / grid migration) | producer側で根絶できる | 既にimport済みのworkspaceを救済できない。import時の無黙知な移動は#168のauthoritative import契約とlayout fidelityに反する |
| 受容policyをrevision/digest/context resourceに埋め込む | stale検出がrevision機構に統合される | revision formula変更がrecovery pointのpre/post digest一致判定に波及し、build更新を跨ぐpending recovery pointが `NEITHER` 分類となりRecoverability(#13)を損なう |
| capture時の受容値をplan/writeSetに保存しA5で値比較 | 反転を確実に検出できる | writeSet/protocol型の拡張が必要。「重複行を含まないintended state」まで不要に拒絶する |
| 予約重複itemをMovableとして移動させる | organizerが重複を解消できる | planner target生成・#83分類契約・UI copyへの影響が大きく、bug fixの射程を超える。将来の拡張候補 |
| `LoaderCursor` からorganizer predicateを直接参照するbridge | driftが構造的に不可能になる | loader hot pathの再構成とflavor/JVM test compile構成への影響が、本bug fixに対して過大。contract testで同等のdrift検知を得られる |
| organizer側booleanの複製(contract testなし) | 実装が最小 | loader側条件変更時にsemantic driftし、#155型の破壊が再発する |

## Consequences

- 予約重複itemを含むworkspaceで、loader受容時はmanual全体整理が成立する。organizerは当該itemをその場保存し、他のitemを整理できる。
- loader非受容(`allowWidgetOverlap=false`)では、composeが新理由 `CAPTURE_RESERVED_OVERLAP` でtyped non-writeとなり、retryはユーザー起点のみである。受容が反転した場合はA5/recoveryのpredicateがtyped no-writeを返す。
- `PreWriteRejection` と `InputCompositionCode` に新値が追加される(journalはschemaVersion 1のまま、新定数追加は#172 §3/§8のversioning規定に従う)。
- 受容無効環境では、recovery targetに予約重複行を含むrecoveryはtyped failureでno-writeとなり、recovery pointは保持される。ユーザーが受容を戻すか当該行が消えるまでrecoveryはunavailableである。
- Nova converter / grid migrationのimport時修復は本ADRの対象外であり、将来の別Issueで判断する。

## References

- [Issue #185](https://github.com/nunu1733/NunuLauncher/issues/185) — 本問題の追跡
- [#172 assessment](../../docs/assessment/issue-172-input-unavailable-diagnostics.md) — root cause実証(row 115、`screen=0 cell(2,0)`)
- [Spec #185](../../specs/185-qsb-row-item-interop/spec.md) — 採用semanticsと受入条件の正本
- [ADR-0008](0008-qsb-reservation-context-and-recovery-compatibility.md) — 置換対象の帰結を含む予約context契約
- [Spec #155](../../specs/155-qsb-reservation-reload/spec.md) — 予約geometryと相関再読込の契約
- [Spec #83](../../specs/83-production-organization-input-sources/spec.md) — 分類precedenceの拡張対象となるtarget membership契約
- `src/com/android/launcher3/model/LoaderCursor.java:569-595` — QSB行markと `allowWidgetOverlap` 受容

## Change history

- 2026-09-01: Issue #185のspec review(Changes requested → 指摘なし確認)を受けて、preserved射影・state-based predicate再評価・contract test方式の受容判定をacceptedとして作成。ADR-0008の「real item overlap は typed non-write failure」帰結を部分的に置換する。
