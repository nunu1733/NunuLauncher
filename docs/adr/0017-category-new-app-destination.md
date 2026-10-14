---
status: draft
---

# 新規アプリ配置先ポリシーへのカテゴリ別配置の追加（ADR-0017）

> Status: Proposed（受入は[#594](https://github.com/nunu1733/NunuLauncher/issues/594)の実装PR mergeで完了する。起草: #509 Phase A feasibility調査（`docs/assessment/issue-509-category-destination-feasibility.md` Revision 7。対象commit `391ad7eaa0f84f43af67f7101071f91325c998d9`）とowner判断（[Issue #509 comment](https://github.com/nunu1733/NunuLauncher/issues/509#issuecomment-6163435986)、2026-10-14））
> Date: 2026-10-14
> 対応: #594（前身: #509）
> 置換関係: [ADR-0015](./0015-new-app-destination-policy.md) Decision 3（配置先の選択肢は3つ）とDecision 15（利用者向け設定の3択）を**拡張**する。Decision 1（追加するかどうかは上流）、Decision 4（typed fallback条件）、Decision 5（idで保持）、Decision 6（ロック意味論）、Decision 7（ADR-0013契約4の書込み構造）、Decision 8（closed result）、Decision 9（bridge最小化）、Decision 10（policy snapshotのcapture/read境界）、Decision 14（Undo対象外）は**不変**である。ADR-0015本文は上書きせず、Change historyへ本ADRへの関連リンクを1行追加する。
> **ADR-0015 Decision 11との関係（明文化）**: Decision 11（promise iconの段階から同じ配置先。same-destination / no-move invariant）は**維持する**。本ADRが追加するのは、promise経路のruntime到達条件と、promise段階capture時のCategory mode分類fallback意味論である（Decision 4）。install完了前のpromise captureで分類signalが読めない場合、Category modeは既存`UPSTREAM` snapshotへ落ち、以後このinstallの配置を動かさない（Decision 10のcapture/read境界とDecision 11のno-moveを満たす）。これによりADR-0015 Decision 11とspec #497の契約差分は閉じる。

## Context

ADR-0015は配置先の選択肢を「上流の既定／指定フォルダ／追加しない」の3つに限定し、カテゴリの一致するフォルダへの配置をNext（ADR-0015 Alternatives: 分類品質が不十分）とした。#497（spec 497、implemented）が固定1フォルダを実装した後、#509がカテゴリ別配置を既存seam上で再評価した（Phase A feasibility、Revision 7。対象commit `391ad7eaa0`）。

#509 Phase A（Lawnchair 16 baseline再評価を含む）で確定した事実:

- capture時の分類→folderId解決はfork-owned module（`app.lawnchair.homeedit`のresolver。`lawnchair/src/app/lawnchair/homeedit/AppDestinationAdapter.kt:121-174`）だけで完結し、既存4field snapshot・queue protocol・書込み経路の拡張は不要である（assessment §2。upstream比のfork patch増分0）。
- **実機の主要install経路（badging付きtrusted-installer promise session）では、captureがinstall完了前に発生し、その時点でS2/S5 signalは読めない**（assessment §3.3/§3.6。AOSP＋launcherコード＋probe実証から強く示唆される。emulator上の構成限界〔§3.6 E4の帰結4〕を踏まえ、実機直接確認は実装PRの実機検証で二重化する）。非system installer経由のinstall（信頼できないinstaller経由等）では、captureがinstall後の`SessionCommitReceiver`で起こり、S2/S5が読める。
- owner判断（2026-10-14）により、promise経路を成功対象外とする縮小scopeが受容された。分類成功対象は「`appCategory`宣言アプリ＋新規`com.google.*`アプリ（＋S1 overrideが存在する再install）」に限る。
- S2（Android `appCategory`）で到達するカテゴリは8種（GAME/MUSIC/VIDEO/PHOTOGRAPHY/SOCIAL/NEWS/MAPS/PRODUCTIVITY。`lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt:22-35`）。S5は`com.google.*`→TOOLS、system→OTHER。user-defined categoryはplatform signalを持たないため、capture時の自動分類では到達しない（S1 override経由でのみ到達）。
- ADR-0015 AlternativesがRejectedとした「カテゴリの一致するフォルダへの自動配置（Deckと同型）」は、(a) 自動分類品質への依存、(b) title一致の暗黙のfolder成長を含む。本ADRの対象は利用者が明示したmappingと解決不能時の既定fallbackであり、(b)を含まない。

## Decision

1. **配置先の選択肢に「カテゴリ別」を追加する**。ADR-0015 Decision 3の3択を4択へ拡張する。カテゴリモードでは、capture時に分類signal（既存優先順位 S1→S2→S5。`OrganizationInputComposer.materializeSignals`と同一順序。`lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt:527-531`）を解決し、利用者が明示設定した`CategoryIdentity + profile → 既存フォルダ（favorites行id）`のmappingでfolderIdへ変換したうえで、既存の`FOLDER` snapshotとして永続化する。4field wire format・kind 2種は不変である（Decision 9/10の再確認。`DirectEditContract.java`への差分0）。
2. **解決不能は上流既定へ静かに落ちる**。未mapping・分類不可・対応カテゴリ消失・mapping対象フォルダ解決不能のいずれも、capture時に既存`UPSTREAM` snapshotを返す（＝ADR-0015 Decision 3の「上流の既定」と同じ結果）。誤配送・後追い移動は発生しない。分類fallbackはFileLogへtypedに記録し（`AppDestination` tag + reasonキー。package名なし。既存慣行 `AppDestinationAdapter.kt:196,200`）、設定行での通知は行わない（分類不能は正常系であるため。mapping自体の無効状態は設定行のsummaryで扱う）。
3. **mapping対象カタログはcapture時に到達するカテゴリへ限定する**。S2の8種＋TOOLS（S5）＋S1経由のuser-definedカテゴリ。capture時に到達しないbuilt-in（`OTHER`等）と、user-defined categoryのうちS1 overrideが存在しないものはmapping対象として露出しない。folder title一致・子の多数決・暗黙のfolder成長は採用しない（ADR-0015 Decision 13(b)の再確認）。
4. **ADR-0015 Decision 11は契約として維持し、runtime到達性とfallback意味論を明確化する**。promise経路（install完了前のenqueue。badging付きtrusted-installer sessionで発生）では分類signalが読めないため、Category modeのcaptureは解決不能として既存`UPSTREAM` snapshotへ落ちる。capture後は4field snapshotが不変であり（Decision 10）、完了後の再captureは`alreadyAddedPromiseIcon`で抑止されるため、このinstallの配置が後から動くことはない（no-move継承）。これは予防的fallback契約であり、通常経路の品質主張には使わない。
5. **設定は既存ポリシー行の4択拡張とする**（ADR-0015 Decision 15の拡張）。mapping管理（カテゴリ→フォルダ選択）は既存のフォルダ選択dialogと同型のdialogで行う。「追加しない」⇔`pref_add_icon_to_home` OFFの整合、ホーム画面ロック中の無効化は不変。mappingの保存はfork-owned pref（既存`pref_new_app_destination`の値域拡張。旧値`upstream`/`folder:<id>`のdecode互換を維持）で行い、schema/migrationは発生しない。

## Alternatives considered

### 分類をflush時に行う（captureをUPSTREAMのままにする）

Rejected。ADR-0015 Decision 10（capture/read境界）とfirst enqueue winsを破り、同じinstallの再flush結果がmapping変更で変わる。#509 Issue本文が禁止する論点である。

### install完了後に分類して移動する（promise iconは既定位置のまま）

Rejected。2段階移動が生じる（ADR-0015 Alternativesと同型）。かつ主要経路ではpromise段階のcaptureが`UPSTREAM` snapshotを永続化するため、flush時に再分類してもfirst enqueue wins契約と矛盾する。

### 分類結果から既存フォルダを自動推定する（title一致・子の多数決・provenance転用）

Rejected。ADR-0015 Alternatives（Deckと同型の暗黙追加）と#509 Issue本文の禁止事項。organizerの生成planのnaming semanticは任意のfolder行への永続対応の証明にならない。

### mapping対応をorganizerのplan provenanceへ載せる

Rejected。配置先ポリシーはADR-0015 Decision 12どおりorganizerとは独立のmoduleが所有する。organizer run/provenanceはここでは起動しない。

### capture時分類の限界を埋めるため、flush時や完了後に分類をやり直す

Rejected。Decision 10（capture/read境界）とDecision 11（no-move）の両方を破る。#509 owner判断（縮小scope受容）は、この拡張を採用しないことを前提に成立している。

## Consequences

- カテゴリモードopt-in時、`appCategory`宣言アプリと新規`com.google.*`アプリ（およびS1 overrideが存在する再install）が対応フォルダへ追加操作0で置かれる。promise経路のinstall（実機主要経路）と分類不能アプリは上流既定のまま。この縮小は設定面で利用者に明示される。
- 変更surfaceはfork-owned moduleと設定UI、resourcesに限られ、`src/`のupstream patch増分は0である。
- spec 594の実装が本ADRのDecisionを参照して書かれる。判断を重複定義しない。

## Change history

- 2026-10-14: Proposed。#594起草（前身: #509 Phase A Revision 7とowner判断。履歴`59aa164a62`のobsolete draftを、review round 1指摘論点〔D11 relation明文化・oracle設計〕と新baseline実測を反映して起草し直したもの）。
