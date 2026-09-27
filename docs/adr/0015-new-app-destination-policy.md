---
status: accepted
---

# 新規アプリの配置先ポリシー（ADR-0015）

> Status: Accepted（2026-09-27。#446の決定Issueで起草・受入を追跡した。出典は #446 付録の承認済み草案（2026-09-24）であり、本ADRがその正本である。Phase 1 review（#446 のreviewコメント）で確定した書込み構造（ADR-0013契約4のadmission内完結）、closed result意味論、policy snapshotのcapture/read境界、snapshot欠損・破損時の一意化を含む）
> Date: 2026-09-27（起草 2026-09-24）
> 対応: #446（方針判断メモ: 再焦点化方針メモ（2026-09-24に承認、Revision 5）§4.4、R-6、D-015。FR-008再定義。以下「メモ§x」はこのメモの出典を指す）

## Context

上流Lawnchair/Launcher3は、利用者がアプリをinstallしたとき、設定が有効ならホーム画面へアイコンを自動追加する。この経路はbaselineで次のように動作する。

1. `SessionCommitReceiver`が`ACTION_SESSION_COMMITTED`を受け、install reasonが`INSTALL_REASON_USER`でなければpromise iconを削除して追加しない（`src/com/android/launcher3/SessionCommitReceiver.java:76-87`）。追加の前提は`pref_add_icon_to_home`が有効であることと、ホーム画面ロックが無効であること（`SessionCommitReceiver.java:98-102`）。`pref_add_icon_to_home`の既定値はtrue（`lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:51`）。
2. install完了前のpromise iconは`InstallSessionHelper.tryQueuePromiseAppIcon`がqueueする（`src/com/android/launcher3/pm/InstallSessionHelper.java:224-242`）。検証条件はtrusted installer、USER reason、icon/label、未installであること（`InstallSessionHelper.java:244-259`）。unarchivalの場合はpromise iconを新規に置かない（`InstallSessionHelper.java:229-237`）。
3. queueは`ItemInstallQueue`を経て`addAndBindAddedWorkspaceItems`へ流れ（`src/com/android/launcher3/model/ItemInstallQueue.java:135-145`）、`AddWorkspaceItemsTask`が既存アイテムとの重複を`shortcutExists`で除外したうえで（`src/com/android/launcher3/model/AddWorkspaceItemsTask.java:100-107`）、`WorkspaceItemSpaceFinder.findSpaceForItem`で空きセルを探し（`AddWorkspaceItemsTask.java:123-126`）、`CONTAINER_DESKTOP`へ書き込む（`AddWorkspaceItemsTask.java:194-197`）。promise iconはsession検証を経て、installが済んでいれば本物のアイコンへ差し替わる（`AddWorkspaceItemsTask.java:139-191`）。install完了後の`PackageUpdatedTask.OP_ADD`は既存promise iconのintent/アイコンを更新するだけで、配置は動かさない（`src/com/android/launcher3/model/PackageUpdatedTask.java:261-320`）。

つまり上流は「追加するかどうか」と「どこへ置くか」をすでに決めており、後者は常に空きセルである。本ADRが決めるのは**配置先**だけである。追加するかどうかの判定（install reason、`pref_add_icon_to_home`、重複除外）は上流のまま変更しない。

背景として、forkは以前このeventをorganizerの増分整理の対象にしようとして、prior absence（以前存在しなかったことの証明）が取得できないという結論を出した（ADR-0005、`docs/adr/0005-fresh-install-presence-evidence.md`。観測事実の正本は`docs/engineering/package-provenance.md`）。また退役したDeckは`PackageUpdatedTask.OP_ADD`から新規アプリをカテゴリフォルダへ自動追加していた（`docs/assessment/lawnchair-deck-audit.md` §4.4）。これらとの関係を「Decision」で整理する。

## Decision

1. **対象**: 上流が既にホームへ追加するアイコン（`pref_add_icon_to_home`が有効、上流の追加条件を満たすもの）の配置先を、利用者が選んだポリシーに従って決める。追加するかどうかの判定は上流のまま変えない。
2. **prior absenceの証明は不要とする**。理由は次のとおり。
   - 上流の`AddWorkspaceItemsTask`は、同一intent・同一userのアイテムが既にワークスペースにあれば追加しない（`AddWorkspaceItemsTask.java:100-107`、`shortcutExists`はpromise iconも同一packageとして照合する。`AddWorkspaceItemsTask.java:274-281`）。再installで重複アイコンが量産される経路は上流に存在しない。
   - 本ポリシーは「新規アプリの検出と提案」ではなく「上流が追加を決めた1アイテムの置き場所」の決定である。既存アイテムを動かす提案ではないため、ADR-0005が防ごうとした「誤った増分整理の提案」（reinstallをfresh installと誤分類してproposalを出す）は構造上起きない。
   - したがってADR-0005の再開条件（authoritative install-history source、event-correlation protocol）は、本ポリシーには適用しない。ADR-0005の適用範囲は「**既存アイテムを動かす増分整理提案**（organizerのplanner/application経路から出すproposal）」である（メモ§4.4）。ADR-0005と`docs/engineering/package-provenance.md`の本文は変えず、本ADRを受け入れるPRで関連リンクだけを足す（メモ§4.4、§10）。ADR-0005本文の対象は「incremental fresh-install proposal path」であり（`docs/adr/0005-fresh-install-presence-evidence.md` Decision冒頭「The baseline does not enable an incremental fresh-install proposal path」）、そのまま維持する。package-provenance文書の分類表（`docs/engineering/package-provenance.md` §4）はproposal分類の正本として残す。
3. **配置先の選択肢は3つ**（最初の実装範囲）:
   1. **上流の既定**（空きセル。既定値）: `WorkspaceItemSpaceFinder`に任せる。挙動は現行と変わらない。
   2. **指定フォルダ**: 利用者が指定した1つのフォルダへ追加する。
   3. **ホームへ追加しない**: 既存の`pref_add_icon_to_home`をOFFにするのと同じ結果をポリシーとして表す。設定の重複は作らない（「利用者向け設定」参照）。
   - カテゴリの一致するフォルダ、指定ページへの配置はNext（メモ§4.5）。分類を使う場合はorganizerの分類seamを使い、並行する分類機構を作らない（AGENTS.md設計規約）。
4. **指定フォルダが使えない場合は上流の既定へ戻す**。戻す理由を後から確認できる形で記録する。fallbackの発火条件は、副作用のない計画関数がtypedに検出する観測可能な制約違反に限定する。条件は次のとおり。
   - フォルダが存在しない（利用者が削除した、restore後に復活しない）。
   - フォルダが別profileにある。work profileのアプリを個人側フォルダへ入れない。profile分離は書込み検証の必須条件である（メモ§4.2条件2、ADR-0004は`profileId`をrevision/preconditionの一部とする。`docs/adr/0004-organizer-lock-persistence.md` Identity rules表）。
   - フォルダが配置制約を満たせない（device profile外、重なり）。書込み前に副作用のない計画関数で検証し、満たせなければ書かずに既定へ戻す（メモ§4.2条件2）。
   - フォルダがDockにある場合も、上流の既定へ戻す。**Dockにあるフォルダは指定できない**（メモ§4.4で確定。メモ§11 B-7）。Dockへの自動追加は上流の追加経路（`CONTAINER_DESKTOP`書込み、`AddWorkspaceItemsTask.java:194-197`）にない操作であり、第1段では新種の書込みを作らない。
   - **「満杯」をfallback条件としない**（Phase 1 reviewで確定）。上流のfolderにハードな上限item数の定数は存在せず、`FolderPagedView`は`FolderGridOrganizer.getMaxItemsPerPage()`（ページあたり`numFolderColumns×numFolderRows`。既定`4_by_5`グリッドでは3×3。`lawnchair/res/xml/device_profiles.xml:56-58`）を超えるとページングで拡張する（`src/com/android/launcher3/folder/FolderPagedView.java:558`、`src/com/android/launcher3/folder/FolderGridOrganizer.java:50-53,91`。`calculateGridSize`はmax gridで張り付くがページ数の上限はない）。したがって「満杯」は定義できない非決定的な状態であり、指定フォルダへの追加は常に末尾rankへの追加として成立する。fallback条件は、書込み前の計画関数がtypedに検出できる制約違反のみとする。
   - fallback理由の記録について、organizer-diagnosticsのrun journal（`docs/engineering/organizer-diagnostics.md` §1）は「organization run / recovery操作だけを記録する」scopeであり、新規アプリ配置はorganizer runではないため、`RunEvent`（`Trigger`/`PhaseCode`のclosed集合）をそのまま使うことはできない。本ADRは「fallback理由が後から確認できる形で記録される」ことを要求し、記録方法（FileLog、settings側の表示用state、diagnostics契約の拡張等）の確定は実装Issueのspecが行う。package名はorganizer-diagnostics §7の**Never**分類に従い、出力面に含めない。
5. **指定フォルダはアイテムのidで覚える（名前では覚えない）**（メモ§4.4、§10）。削除されたら上流の既定に戻し、設定の行で一度だけ知らせる。削除後に同名フォルダを作っても指定は復活しない（idで保持するため）。再指定を促すUIの詳細は実装Issueのspecで決める。
6. **ロックの意味論はADR-0004に従う**。これは自動の変更であるため、ロック済みアイテムを動かさない制約の対象になる（メモ§4.2「ロック（既定案）」）。指定フォルダへの追加は既存フォルダの子を1つ増やす操作であり、次のように扱う。
   - フォルダ親が`LOCKED`の場合、ADR-0004は「parent lock protects the parent cell and every child's captured container/rank」（`docs/adr/0004-organizer-lock-persistence.md` Identity rules表）と定める。これは既存の子のrankを保護する意味論であり、新規の子の追加そのものを禁止する語ではない。ただし追加で既存子のrankがずれる実装はしてはならない。末尾rankへの追加など、既存子のcaptured rankを変えない方法に限る。
   - 子自身の`LOCKED`はその子のcontainer/rankを保護するだけであり、兄弟の追加を妨げない（同表）。
   - フォルダ内の空きrank計算はgrid非依存に行わない。Deckの`index % 4`固定計算（`docs/assessment/lawnchair-deck-audit.md` §6.5）を繰り返さない。
7. **書込み契約はADR-0013に従い、書込み構造はADR-0013契約4どおりとする**。1アイテムの追加は「上流が行う単一アイテムの追加の配置先決定」（メモ§4.2対象(b)）であり、1 DB transaction、model writer経路、`LayoutWriteCoordinator`との排他、organizer runとの同時書込み禁止をADR-0013に委ねる。本ADRは契約を重複定義しない。そのうえで、指定フォルダへの書込みは、既存の`ModelWriter.addItemToDatabase`をそのまま使う形ではなく、ADR-0013契約4どおり「validation（契約2の一段階目）→ MODEL_WRITER admission → admission後の再検証（契約2の二段階目）→ model/DB変更」の順序が保たれる構造、すなわち**admissionの内側で検証と変更が完結する最小の`ModelWriter`操作（同経路に追加する）**として実装する。既存の`addItemToDatabase`はadmission前に`updateItemInfoProps`・ID採番・bindItems callbackを実行する（`src/com/android/launcher3/model/ModelWriter.java:290-313`）ため、この構造を踏まない。具体的要求:
   - (a) admission成立より前に、`ItemInfo`の変更・ID採番・bindItems callback・DB書込みのいずれも発生しない。
   - (b) admission後に、同一の純粋計画関数を現状態へ再実行する（契約2の二段階目）。
   - (c) 指定folderがstale（削除・別profile・Dock・制約違反）な場合は、検証失敗として無変更で終えるのではなく、`UpstreamDefault(reason)`という有効planへ再計画し、default配置自体のbounds/container等を同じadmission内で検証してから1 transactionで書く（次項のclosed result）。
8. **配置先の決定は、同一の純粋計画関数のclosed resultとして行う**（Phase 1 reviewで確定）。計画関数は単一操作の入出力（現状態 + 対象アイテム + policy snapshot）に対する純関数であり、結果はclosedな3値とする:
   - `FolderTarget(folderId)`: 指定フォルダへの追加が成立する。
   - `UpstreamDefault(reason)`: 上流の既定（空きセル）へ置く。`reason`はfallback理由（`FOLDER_MISSING`、`PROFILE_MISMATCH`、`DOCK_FOLDER`、`CONSTRAINT_VIOLATION`、`SNAPSHOT_INVALID`等）である。
   - `Reject(reason)`: 書かない。書込み経路のinvariant failure（default配置すら成立しない場合等）に限る。無変更・typed failureとする。
   - admission後に同じ関数を現状態へ再実行し、指定folderがstaleなら検証失敗ではなく`UpstreamDefault(reason)`という有効planへ再計画する。default配置自体のbounds/container等を同じadmission内で検証してから1 transactionで書く。default側も成立しない真のinvariant failureだけが`Reject`である。「新規アプリの追加がどこにも置かれない」状態は`pref_add_icon_to_home`が有効である限り作らない。`Reject`は書込み経路のinvariant failureであり、意図的な追加抑制ではない。
9. **bridgeは最小にする**。配置先の決定は`ItemInstallQueue`→`AddWorkspaceItemsTask`の追加経路の1箇所に置く。`PackageUpdatedTask`（AOSP由来）には分岐を追加しない。Deckが`PackageUpdatedTask.OP_ADD`へ直接deck分岐を書いたことはNFR-010違反のpatch surfaceとして退役理由の1つであり（`docs/assessment/lawnchair-deck-audit.md` §6.6、ADR-0006）、この失敗を繰り返さない。bridgeの具体位置（`ItemInstallQueue`のflush時か`AddWorkspaceItemsTask`内か）は実装Issueのplanが確定する。
10. **再flush決定性: policy snapshotのcapture/read境界**（Phase 1 reviewで確定）。配置先ポリシーの決定に使うcanonical inputは、**queue投入（enqueue/`queuePendingShortcutInfo`、`src/com/android/launcher3/model/ItemInstallQueue.java:194-215`）時点でcaptureしたpolicy snapshot（policy選択、指定folder id、user、package）**とし、これをqueue永続化（`PersistedItemArray`、`ItemInstallQueue.java:87-88`）とともに保持して再flushでも同じsnapshotを使う。flush/`getItemInfo`時（`ItemInstallQueue.java:135`）はpersist済みsnapshotを**読むだけ**であり、current policyから再生成しない。process死の間にpolicy/folder指定が変わっても、同じinstallの再flush結果は変わらない。policy snapshotの保持方法（`PendingInstallShortcutInfo`への追加field、別の永続file等）は実装Issueのplanが決める。**snapshot欠損・破損時もcurrent policyを再読しない**: snapshotのidentity（policy選択、指定folder id、user、package）が読める範囲なら`UpstreamDefault(SNAPSHOT_INVALID)`へ明示fallbackし、default配置を通常と同じadmission内で検証して書く。identity自体が信頼できない場合だけ`Reject(SNAPSHOT_INVALID)`として無変更・typed failureにする。
11. **promise iconの段階から同じ配置先を使う**。上流はinstall完了前にpromise iconをワークスペースへ置き（§Context 2-3）、完了後に`PackageUpdatedTask`がその場で本物のアイコンへ差し替える（`PackageUpdatedTask.java:261-320`。配置を動かさない）。配置先をinstall完了後に変えると「promise iconが空きセルに置かれた後に別フォルダへ移動する」という2段階の移動が生じ、見た目とDBの両方が不安定になる。そこでpromise iconの追加時点で配置先を決める。promise iconは`isPromise`/`FLAG_AUTOINSTALL_ICON`で識別でき（`AddWorkspaceItemsTask.java:139`、`PackageUpdatedTask.java:290-292`）、指定フォルダ内でも差し替えは`OP_ADD`が既存rowのintent/iconを更新するだけで成立する。（メモ§11 B-7で採用済み。）
12. **ポリシーを所有するmodule**: 配置先ポリシーはorganizerとは別の、直接編集と同じ側（メモ§4.8の仮称`app.lawnchair.homeedit`）のmoduleが所有する。organizerのplanner/application/locks/recovery protocolには依存しない。Nextで分類を使うときだけ、organizerの分類seamを呼ぶ。
13. **Deckとの違いを明記する**。Deckの新規アプリ自動追加（`addNewlyInstalledApp`）は、(a) `PackageUpdatedTask.java`への直接追記、(b) 既存フォルダtitle一致への暗黙の追加、(c) 固定4列rank計算、(d) profile検証なし、(e) 固定遅延`postDelayed(800)`に依存する全体配置と同じruntime上にあった（`docs/assessment/lawnchair-deck-audit.md` §4.4、§6.4-6.6）。本ポリシーは (a) を行わず（Decision 9）、(b) は利用者が明示的に指定した1フォルダに限定し、(c) を行わず（Decision 6）、(d) はprofile分離検証を必須とし（Decision 4）、(e) に依存する仕組みを新たに作らない（ADR-0013の1 transaction書込み）。固定遅延・DBの直接複製・`exitProcess`再起動（ADR-0006が退役させた問題群）には依存しない。
14. **Undoは対象外**（メモ§4.1、§10、§11 B-1/B-8）。新規アプリの配置は利用者の操作ではなく、多くはランチャーが裏にいる間に起きるため、Undoの対象にしない。置き場所を変えたい場合は、項目単位の編集アクション（#448/FR-018）で動かす。ADR-0013の対象(c)（Undo）と本判断は揃っている。
15. **利用者向け設定**: 選択肢は「上流の既定 / 指定フォルダ / 追加しない」の3択である。「追加しない」は既存の`pref_add_icon_to_home`（`lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:51`、設定UIは`lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt:74-82`。ホーム画面ロック中は無効化される）と同じ結果を指すため、独立したtoggleを新設しない。ポリシー設定は既存の「ホームにアイコンを追加」設定の近傍に置く。ホーム画面ロックが有効な間は、上流と同様に追加自体が行われない（`SessionCommitReceiver.java:98-102`）。

## 要求するテスト（将来の実装PRが満たすべき要求）

テストの層は `docs/engineering/quality-strategy.md` の区分に従い、ADR-0013の要求テスト表と同じ既存surfaceへ割り付ける。新規CI laneは作らない。実装は配置先ポリシーの実装Issueが行い、本表はその実装PRが満たすべき要求である。

| 要求 | 層 | 内容 |
|---|---|---|
| admission後の再計画（defer後のstale検証） | Layout Application interface相当のJVM test（test DB使用。ADR-0013の要求テスト表「admission後の再検証（defer後のstale検証）」行と同じsurface） | ORGANIZER lease保持中にinstall queueがflushされる → organizer適用が指定フォルダを削除/変更する → lease解放後、追加書込みは同一の純粋計画関数を現状態へ再実行し、指定folderがstaleなら`UpstreamDefault(reason)`へ再計画してdefault配置を同じadmission内で検証・書込みする。lease保持中にmodel/DBへの先行変更（`ItemInfo`変更・ID採番・bindItems callbackを含む）が発生しない。 |
| policy snapshotの再flush一貫性 | 同上 | policy snapshot=Aでqueue投入 → policy設定をBへ変更 → process restart → 永続化されたqueueをflush → snapshot Aを使用する（current policy Bを再読しない）。 |
| snapshot欠損・破損時のclosed result | 同上 | snapshot欠損・破損時もcurrent policyを再読しない。identityが読める範囲なら`UpstreamDefault(SNAPSHOT_INVALID)`へ明示fallbackし、default配置を検証して書く。identity自体が信頼できない場合だけ`Reject(SNAPSHOT_INVALID)`として無変更・typed failureにする。 |

## Alternatives considered

### カテゴリの一致するフォルダへの自動配置（Deckと同型）

Rejected（第1段としては）。自動分類の品質が現状では不十分であり（Android category 8種＋OTHERのみ。メモ§2）、Deckが行ったtitle一致の暗黙追加は利用者の意図しないフォルダ成長を生む。Nextで分類seamの品質が確定してから再評価する。

### install完了後に配置先を決める（promise iconは既定位置のまま）

Rejected。2段階移動（空きセル→フォルダ）が生じ、即座に見える編集の応答性（NFR-013の系）を損なう。Decision 11のとおり。

### organizerのplannerに配置先判定を入れる

Rejected。配置先ポリシーは単一アイテムの追加であり、organizerのsnapshot/revision/recovery protocolの対象ではない（メモ§4.2「不要とするもの」）。organizerに依存すると、organizer runと新規installが互いの排他を複雑化する。

### 上流の追加条件（install reason等）もforkで変える

Rejected。追加するかどうかは上流の製品判断であり、`pref_add_icon_to_home`という既存の設定で既に制御されている。変更するとADR-0005/package-provenanceの分類境界まで再検証が必要になる。

### 既存の`ModelWriter.addItemToDatabase`をそのまま使う（admission前の変更を許容する）

Rejected（Phase 1 reviewで確定）。`addItemToDatabase`はadmission前に`updateItemInfoProps`・ID採番・bindItems callbackを実行する（`ModelWriter.java:290-313`）。organizer lease保持中のdefer時にmodel-visible stateがadmission成立前に変わる構造は、ADR-0013契約4が「直接編集の書込み構造は踏まない」と明示した性質と同型であり、対象(b)にも適用されない。admissionの内側で検証と変更が完結する最小の操作を同経路に追加する（Decision 7）。

### stale時を検証失敗として無変更で終える

Rejected（Phase 1 reviewで確定）。ADR-0013契約2の二段階検証は「どちらの段階でも満たせなければ書かない」を定めるが、#446のOutcomeは指定folderが使えない場合も「必ず上流既定へ戻す」を要求する。folder staleをvalidation failureとして扱うと両者が矛盾する。同一の純粋計画関数のclosed result（Decision 8）により、folder staleは`UpstreamDefault(reason)`という有効planへの再計画であり、真のinvariant failure（`Reject`）と区別する。

### policy snapshotをflush時にcurrent policyから再生成する

Rejected（Phase 1 reviewで確定）。process死の間にpolicy/folder指定が変わった場合、同じinstallの再flush結果が変わる。snapshotはqueue投入時にcaptureして永続化し、flush時は読むだけとする（Decision 10）。snapshot欠損・破損時もcurrent policyを再読しない（`UpstreamDefault(SNAPSHOT_INVALID)`または`Reject(SNAPSHOT_INVALID)`）。

## Consequences

- 新規install（再installを含む）のアイコンは、利用者が指定フォルダを選んでいればそこへ置かれる。B1（「新しいアプリを入れ、決めた場所に置く」）の追加操作0の目標（`docs/engineering/editing-burden-benchmark.md` §6）に直接効く。
- ADR-0005は廃止されない。既存アイテムを動かす増分整理提案（FR-009、Later）に対するfail-closed結論はそのまま維持される。適用範囲の狭めは本ADR Decision 2が正本である。
- `AddWorkspaceItemsTask`（AOSP由来）への変更が最小限必要になる見込みであり、そのbridgeの場所と理由を近傍文書とpatch-surfaceの記録に残す（NFR-010）。bridgeの具体位置は実装Issueのplanで確定させる。
- 指定フォルダへの追加は、ロック意味論（Decision 6）とprofile分離（Decision 4）を満たす検証を書込み前に行う。失敗時は常に上流の既定へ落ちるため、新規アプリの追加が「どこにも置かれない」状態は作らない（`pref_add_icon_to_home`が有効である限り）。`Reject`は書込み経路のinvariant failureに限る。
- 指定フォルダへの書込みは`risk: layout-data`の対象になる見込みである（favorites行の追加を伴う。ADR-0013の契約で扱う）。最初の実装PRは独立auditと`final-status`を必要とする。
- 実装が新たなDB書込みfileを追加した場合、`tools/repo-contract/validate_writer_inventory.py`のsource-scan allowlistの更新がCIで要求される（既存の仕組みで自動検出されるbackstop）。
- 配置先ポリシーの実装Issueのspecは、本ADRのDecisionを参照して書かれる。判断を重複定義しない（AGENTS.md正本の分担）。

## Change history

- 2026-09-27: Accepted。#446の決定Issueで起草・受入（出典: #446 付録の承認済み草案2026-09-24）。Phase 1 review（#446 のreviewコメント）で確定した書込み構造（Decision 7）、closed result意味論（Decision 8）、policy snapshotのcapture/read境界とsnapshot欠損・破損時の一意化（Decision 10）、「満杯」のfallback条件からの除外（Decision 4）を含む。
