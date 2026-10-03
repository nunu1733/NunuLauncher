---
status: draft
---

# 新規アプリ配置先ポリシーへのカテゴリ別配置の追加（ADR-0017）

> Status: Proposed（受入は[#509](https://github.com/nunu1733/NunuLauncher/issues/509)の実装PR mergeで完了する。起草: #509 Phase A feasibility調査（`docs/assessment/issue-509-category-destination-feasibility.md`、2026-10-04）とPhase 1 spec受入 review）
> Date: 2026-10-04
> 対応: #509
> 置換関係: [ADR-0015](./0015-new-app-destination-policy.md) Decision 3（配置先の選択肢は3つ）とDecision 15（利用者向け設定の3択）を**拡張**する。Decision 1（追加するかどうかは上流）、Decision 4（typed fallback条件）、Decision 5（idで保持）、Decision 6（ロック意味論）、Decision 7（ADR-0013契約4の書込み構造）、Decision 8（closed result）、Decision 9（bridge最小化）、Decision 10（policy snapshotのcapture/read境界）、Decision 14（Undo対象外）は**不変**である。ADR-0015本文は上書きせず、Change historyへ本ADRへの関連リンクを1行追加する。

## Context

ADR-0015は配置先の選択肢を「上流の既定／指定フォルダ／追加しない」の3つに限定し、カテゴリの一致するフォルダへの配置をNext（ADR-0015 Alternatives: 分類品質が不十分）とした。#497（spec 497、implemented）が固定1フォルダを実装した後、#509がカテゴリ別配置を既存seam上で再評価する調査を要求した。

#509 Phase A調査（対象commit `8508c14182412c2a9cf6f6240eadfe30b5322047`、2026-10-04）で確認した事実:

- capture時の分類→folderId解決はfork-owned module（`app.lawnchair.homeedit`のresolver）だけで完結し、既存4field snapshot・queue protocol・書込み経路の拡張は不要である（assessment §2）。
- 現行platformではinstall-session callbackが第三者launcherへ配信されない（AOSP `PackageInstallerService.Callbacks`の`shouldFilterSession`＝installer uid一致またはtarget packageの`canQueryPackage`。targetはinstall完了まで存在しないため不成立。API 35/36エミュレータで実証）。自動追加は常に`SessionCommitReceiver`（install完了後）でenqueueされ、capture時点でS2/S5 platform evidenceが読める（assessment §3.1）。
- 分類が成功する対象は「manifestで`appCategory`を宣言するアプリ」「新規の`com.google.*`アプリ」、および再install時にS1 overrideが存在するアプリに限られる。解決不能は上流既定へ落ちる設計がOutcomeに含まれる（assessment §3.3）。
- ADR-0015 AlternativesがRejectedとした「カテゴリの一致するフォルダへの自動配置（Deckと同型）」は、(a) 自動分類品質への依存、(b) title一致の暗黙のfolder成長を含む。本ADRの対象は利用者が明示したmappingと解決不能時の既定fallbackであり、(b)を含まない。

## Decision

1. **配置先の選択肢に「カテゴリ別」を追加する**。ADR-0015 Decision 3の3択を4択へ拡張する。カテゴリモードでは、capture時に分類signal（既存優先順位 S1→S2→S5）を解決し、利用者が明示設定した`CategoryIdentity + profile → 既存フォルダ（favorites行id）`のmappingでfolderIdへ変換したうえで、既存の`FOLDER` snapshotとして永続化する。4field wire format・kind 2種は不変である（Decision 9/10の再確認）。
2. **解決不能は上流既定へ静かに落ちる**。未mapping・分類不可・対応カテゴリ消失のいずれも、capture時に既存`UPSTREAM` snapshotを返す（＝ADR-0015 Decision 3の「上流の既定」と同じ結果）。誤配送・後追い移動は発生しない。分類fallbackはFileLogへtypedに記録し（package名なし）、設定行での通知は行わない（分類不能は正常系であるため。mapping自体の無効状態は設定行のsummaryで扱う）。
3. **mapping対象カタログはcapture時に到達するカテゴリへ限定する**。S2の8種＋TOOLS＋S1経由のuser-definedカテゴリ。capture時に到達しないbuilt-in（`OTHER`等）は露出しない。folder title一致・子の多数決・暗黙のfolder成長は採用しない（ADR-0015 Decision 13(b)の再確認）。
4. **Decision 11（promise iconの段階から同じ配置先）は契約として維持し、runtime到達性の注記を加える**。promise経路（install完了前のenqueue）は現行platformでは第三者launcherに到達しない（前述のplatform事実）。到達した環境では分類signalが読めないため、captureは解決不能として上流既定へ落ちる。これは予防的fallback契約であり、通常経路の品質主張には使わない。
5. **設定は既存ポリシー行の4択拡張とする**（ADR-0015 Decision 15の拡張）。mapping管理（カテゴリ→フォルダ選択）は既存のフォルダ選択dialogと同型のdialogで行う。「追加しない」⇔`pref_add_icon_to_home` OFFの整合、ホーム画面ロック中の無効化は不変。

## Alternatives considered

### 分類をflush時に行う（captureをUPSTREAMのままにする）

Rejected。ADR-0015 Decision 10（capture/read境界）とfirst enqueue winsを破り、同じinstallの再flush結果がmapping変更で変わる。#509 Issue本文が禁止する論点である。

### install完了後に分類して移動する（promise iconは既定位置のまま）

Rejected。2段階移動が生じる（ADR-0015 Alternativesと同型）。かつ現行platformではpromise経路自体が到達しないため、解決すべき実態がない。

### 分類結果から既存フォルダを自動推定する（title一致・子の多数決・provenance転用）

Rejected。ADR-0015 Alternatives（Deckと同型の暗黙追加）と#509 Issue本文の禁止事項。organizerの生成planのnaming semanticは任意のfolder行への永続対応の証明にならない。

### mapping対応をorganizerのplan provenanceへ載せる

Rejected。配置先ポリシーはADR-0015 Decision 12どおりorganizerとは独立のmoduleが所有する。organizer run/provenanceはここでは起動しない。

## Consequences

- カテゴリモードopt-in時、`appCategory`宣言アプリと新規`com.google.*`アプリ（およびS1 overrideが存在する再install）が対応フォルダへ追加操作0で置かれる。それ以外は上流既定のまま。
- 変更surfaceはfork-owned moduleと設定UI、resourcesに限られ、`src/`のupstream patch増分は0である。
- spec 509の実装が本ADRのDecisionを参照して書かれる。判断を重複定義しない。

## Change history

- 2026-10-04: Proposed。#509 Phase 1（assessment §9とspec受入reviewで起草）。
