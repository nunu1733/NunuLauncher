---
issue: "#507"
status: accepted
tier: M
requirements:
  - FR-019
  - NFR-014
updated: 2026-10-03
---

# 編集画面で重複を確認し、選んだアイコンをまとめてホームから外す

> Risk tier: **M** — 既存の編集画面（#449）の前段（表示・選択・確認面・入口）のみを変える。新しいLauncher DB書込み経路を作らず、削除は既存の `RemoveFromHome` → 編集画面確定（organizer安全経路。1適用 = 1復元点）→ 既存Undo（#450）へそのまま通す。`src/**`、`organizer/application/**`、DB・migration・recovery storeには触れない。planning moduleの検出関数の一般化は振る舞い不変の委譲refactorであり、spec 451の代表規則・警告・冪等性（planner入出力・golden corpus）を変えない。実装中に適用・復旧契約の変更が必要になった場合は階層Hへ格上げする（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層) の格上げ規則）。
> 出典: seed-backlog order 6（重複の削除提案）。起票Issue [#507](https://github.com/nunu1733/NunuLauncher/issues/507)。#451が明示した「発見と削除」の操作コスト（B7 baseline 9）のNext引き取り。
> 前提の状態（2026-10-03に照合。base `main` `6184020e3a`）: #449 implemented（PR #476。編集画面・選択・Remove・確定・stale開き直し）、#450 implemented（PR #481。Undo）、#451 implemented（PR #485。重複検出 `DuplicateLaunchTargets.kt`、preview警告、policy bundle v2.8）。#439の共通着手ゲートは[close記録](https://github.com/nunu1733/NunuLauncher/issues/439#issuecomment-5945643365)で解消済み。
> ベンチマークの正本: [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441確定。B7 baseline = 重み付き9・操作数5）。Requirements接続: FR-018/FR-020（既存Remove/Undo契約の再利用）、FR-021（重複の定義の再利用。FR-021は発生防止・警告の要件であり、本成果で `implemented` 追記とは扱わない。要件追跡の扱いはspec受入時にrequirements側で確定する）、NFR-001/002（既存適用経路の不変条件）、NFR-009/010（アクセシビリティ/patch surface）。

## Problem

ホームに残った既存の重複アイコン（同一起動先の複数配置）は、利用者が自力で在処を探して1個ずつ外すしかない。#451は新規フォルダへの重複流入防止とpreview警告を実装したが、既存重複の削除操作は対象外であり、B7の操作コスト（重み付き9・操作数5。個別の長押し+drag削除）が残っている。#449の編集画面には複数選択と「ホームから外す」があるが、どれが重複かを示す手掛かりがなく、利用者は重複と気づけない。

## Benchmark

改善する課題は **B7（重複しているアイコンを見つけて1つにする）** である（[editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §2/§5。fixtureは重複2組。重複の在処はidentity表で既知であり、探索は数えない）。

- **本specが確定するB7目標**: 重複確認 → 明示選択 → 既存Remove → 確定までの固定手順の **重み付き操作コスト9未満**（§4の重み表による#441の決定的会計。操作数の目標は置かない。baselineの手順はdrag中心で重みが乗るのに対し、本経路はtap中心の安全な手順になるため、主指標の重み付きコストで判定する）。
- **固定手順と会計（fixture §5。重複2組。各組から1個ずつ外す）**:
  1. workspace空きスペース長押し（2）+「編集画面」tap（1）= **3**（#449と同一の入口。ADR-0014）
  2. 重複サマリ行tap（1）= **1**
  3. 各組で外す側をtap（2組 × 1）= **2**
  4. 確認面内の「ホームから外す」tap（1）= **1**（面が閉じ、既存セッション計画へ反映）
  5. 確定tap（1）= **1**
  - 合計: 重み付き **8**（手順は長押し1回（重み2）+ 以降はtapのみ。drag・swipe・追加の確認stepはなし）、操作数8。**9未満を満たす。**
- Undo（確定直後の既存snackbar）はB7の勘定に含めない（B5 = #450の対象）。
- #451が確定したB7目標（構造保証: 新規フォルダ内の重複0組。可視性: preview警告行での識別）は不変であり、本specはその「発見と削除」の残りを担当する。50%削減目標や探索時間の目標を追加しない（根拠なし）。

## Prior art

- Samsung One UIの重複アイコン削除案内（対象: Samsung support "Remove duplicate apps from my Home screen" / URL: https://www.samsung.com/ae/support/mobile-devices/remove-duplicate-apps-from-my-home-screen-on-my-galaxy-device/ / 確認日: 2026-10-03）— 長押し →「Select」→ 各重複をtap → 削除、の複数選択削除が案内されている。採用: 「重複を識別してまとめて外す」は複数選択+既存Removeで成立するというUX筋立て。ただし本リポジトリには既に同じ契約（#449の編集画面の選択+Remove+確定）があるため、新規の選択・適用機構は作らず既存経路へ乗せる（workflow正本「repository内に既に同じ契約がある場合は既存architectureとの整合を優先」）。
- Nova Launcherコミュニティの重複対処（対象: Reddit r/NovaLauncher "Duplicate Icon" / URL: https://www.reddit.com/r/NovaLauncher/comments/cv5uk7/duplicate_icon/ / 確認日: 2026-10-03）— 検出・削除提案の機能は存在せず、重ねてフォルダ化する応用で対処されている。採用: 主要launcherに重複検出・削除提案の実装が無いことを確認。削除提案の選択規則（最後の1個を守る等）に転用できる外部patternはなく、NunuLauncher固有の契約（#449/#451）に従う。

## Outcome

編集画面を開くと、現在のcaptureに同一起動先の重複があれば「重複アイコン N組」のサマリ行が現れる。tapすると重複確認面（dialog）が開き、各グループの各配置が名前・ページ/位置・フォルダ所属・profile区別で識別されて一覧になる。利用者は残す配置を確認し、外す側を明示的に選んで（自動選択なし）、面内の「ホームから外す」で既存のセッション計画へ入れ、既存の確定でまとめて外せる。確定後は既存のUndo（#450）で戻せる。表示・選択・キャンセルは零書込みであり、削除の書込みは既存#449適用経路（1適用 = 1復元点）のみを通る。通常のOrganizer整理（全体整理run）は不要である。

## Scope

- **検出の共有（planning module内の小さな一般化）**: [DuplicateLaunchTargets.kt](../../lawnchair/src/app/lawnchair/organizer/planning/DuplicateLaunchTargets.kt) の検出を、kind/targetのaccessorを引数に取るinternalな純粋関数へ一般化し、既存 `duplicateSurplusIds` はその委譲に置き換える。参加種別（`ItemKind.APPLICATION`/`DEEP_SHORTCUT` かつ `TargetKey.AppKey`/`ShortcutKey`）・target値等価・サイズ≥2の規則は同fileの1箇所に留まり、判定規則の2実装を作らない。homeedit側は図のitemをplanningのkind語彙へ写す小さなadapter（既存 `EditSurfaceProjection.itemTypeCode` の逆写像）だけで接続する。同一Gradle module内のinternal参照であり、検出関数のpublic化・新規module・`src/` 変更はしない。#451の振る舞い（代表規則・警告・冪等性・golden corpus）は不変であることを既存test群で確認する。
- **重複グループの計算（homeedit内の純粋投影）**: 編集画面のcaptureから投影した図のitems（作業投影。capture + セッション計画。単一の権威）を入力に、重複グループを決定的に計算する純粋関数をhomeedit内に追加する。入力は図のitem（id、種別、targetKey、label、位置、container、profile、lock）のみで、Android型・DB行をinterfaceへ出さない。グループはサイズ≥2の同一起動先集合であり、セッション計画で既に除かれた（Removal済み）itemはグループから消える（残す個体の確認と一致させる）。グループとメンバーの並びは決定的（視覚順基準。JVM testで固定）。
- **入口（サマリ行）**: 編集画面の上部（選択数表示の下）に「重複アイコン N組」の行を置く。重複グループが0組の間は行を出さない。零書込みの表示であり、tapで重複確認面を開く。
- **重複確認面（dialog）**: 各グループを節として示し、各メンバー行に (1) 名前（label。無い場合は既存の既定表示）、(2) 位置（ページN・行/列、または所属フォルダ名、またはDock）、(3) profile区別（main userでないprofileは区別表示）、(4) 選択状態、(5) 選択不可の理由（ロック中/ロック状態不明/Dock・フォルダ内等。既存eligibility語彙）を出す。メンバー行のtapは既存の選択toggle（#449のselection。eligibility判定・touched guard・ロック扱いは既存と同一）へ流す。別の選択stateは作らない。
- **最後の1個を守る選択規則（提案経路の選択規則。Issue未決事項の確定）**: 確認面において、当該グループの全メンバーが選択（removal対象）になる状態を作る操作は、typedな理由表示で受け付けない（零書込み。グループに少なくとも1個が残る）。guardは **2つの境界** で同一の純粋関数により適用する: (1) 確認面からの選択toggle時、(2) 確認面内の「ホームから外す」を既存 `RemoveFromHome` へ流す直前（dispatch時）。(2)は、図上で事前に全メンバーを選択してから確認面を開き、面内ではtoggleせずRemoveを実行する経由でguardを迂回しないための同一検証であり、現行selectionがグループの全メンバーを含む場合は同一のtyped理由で拒否し、零書込み・確認面保持とする。guardの判定はduplicate groupのメンバーのみを対象とし、重複以外の項目を含む「選択全体へ既存Removeを適用」の通常意味論は変えない。guardを共有の `EditSurfaceSessionPlanner.plan(RemoveFromHome)` 側へ入れない（図上の通常Remove契約（#449）が「重複を1個残す」契約に変わってしまうため）。この規則は重複確認面からの選択・実行にのみ適用され、図上のtap → 既存Removeの通常契約（#449）は変更しない。guardはhomeedit内の純粋関数とし、JVM testで検証する。
- **対象外のみのグループ（全メンバーが選択不可）は理由を示して選択不可とする。**
- **確認面内の「ホームから外す」**: 既存の `RemoveFromHome` アクション（#449。図のアクションバーと同一アクション）への共通入口を確認面内に置く。新規アクション種別・新規planner intentは作らない。選択全体（重複以外を含みうる既存選択）に適用され、成功時は確認面を閉じて図へ戻す（確定ボタンが届く）。実行直前には最後の1個のguard（上記のdispatch時検証）を通し、拒否時は零書込みで理由を表示し確認面を保持する。
- **確定とUndo**: 既存#449経路（確定時の再captureとrevision・状態一致検証、recovery point 1個、1 transaction、相関reload、適用後検証）と既存#450 snackbarをそのまま使う。削除専用Undo・追加確認step・自動選択は作らない。
- **stale/キャンセル/capture失敗**: 既存契約どおり零書込み。確認面の表示は常に現行capture由来の図から再計算し、確定時のstaleはセッション（選択を含む）破棄+最新captureでの開き直し（既存flow。重複表示も再計算される）。古いpreviewの`ItemId`を新セッションへ無検証で持ち込む経路は存在しない（選択はprocess内セッション状態のみ。永続化しない）。

## Non-goals

- 自動削除・「全超過分の自動選択」・アンインストール。
- Organizer plannerの削除disposition新設、汎用的な削除提案エンジン、order 7の穴詰め（seed-backlogの別order）。
- 図上の重複バッジ表示、重複に限定しない検索・フィルタ・並べ替え（第1版は確認面のみ）。
- folder/Dock/widget等への選択範囲拡張、folder中身の削除・rank再編成・folder collapse（#449の選択対象限定のまま）。
- #451の検出意味論・代表規則・警告・planner振る舞い・policy bundle versionの変更（一般化は振る舞い不変の委譲refactorに限る）。
- 通常のRemove契約（#448/#449）とUndo契約（#450）の変更。
- 新規CI lane・新規書込み経路・`src/**` 変更・上流dragの変更。

## Behavior scenarios

### Scenario: 重複があるとサマリ行が現れ、確認面で各配置を識別できる

Given 同一起動先の重複2組（ページ0のペア、ページ1のペア）があるホームで編集画面を開いた
When 「重複アイコン 2組」のサマリ行をtapする
Then 重複確認面が開き、各グループの各メンバーが名前・ページ/位置・profile区別つきで一覧される
And いずれの書込みも発生しない

### Scenario: 名前が同じだけでは重複としない（profile違いも重複としない）

Given 同名の別起動先アプリ（component不同）と、同一componentのpersonal/work配置がcaptureにある
When 重複確認面を開く
Then 同名別起動先は別の起動先として重複グループにならず、personal/workも重複グループにならない
And 判定は#451と同一の `TargetKey` 値等価である（アプリ = component + profile、deep shortcut = package + shortcut id + profile）

### Scenario: 残す配置を確認して外す側を選び、既存Removeでまとめて外す

Given 重複グループ1組（同じページに2個）で確認面が開いている
When 利用者が片方をtapして選択し、面内の「ホームから外す」をtapする
Then 図からその配置が除かれた表示に切り替わり（既存セッション計画の反映）、確認面が閉じて確定に進める
And 確定すると1回の適用でその行が削除され、1個の復元点が作られる。残りの1個は変化しない
And 確定直後の既存Undo snackbarで元に戻せる

### Scenario: グループの最後の1個を外す選択は受け付けない

Given 重複グループ1組（メンバー2個とも選択可能）で確認面が開いている
When 1個目を選んだ後、もう1個もtapする
Then 2個目の選択は受け付けられず、各グループで少なくとも1個残す必要がある旨のtypedな理由が表示される（零書込み）
And 既存の1個目の選択は保持される

### Scenario: 図上で事前に全メンバーを選択してから確認面のRemoveを実行しても拒否される

Given 図上で重複グループのメンバー2個を事前に選択してから確認面を開いた
When 面内の「ホームから外す」をtapする
Then dispatch直前のguardが適用され、選択は実行されず、typedな理由が表示され、零書込みで確認面は保持される（セッション計画は変化しない）
And その後1個の選択を解除すると、残り1個のremovalは既存のRemove経路で計画できる

### Scenario: guardは重複メンバーのみを判定する

Given 図上で重複グループのメンバー1個と、重複でない別アイテム1個を事前に選択してから確認面を開いた
When 面内の「ホームから外す」をtapする
Then 重複グループには選択されていないメンバーが残るため、選択全体（重複メンバー+別アイテム）への既存Removeが通常どおり実行される
And guardは「選択全体への既存Remove」の通常意味論を変更しない

### Scenario: 対象外のみのグループは理由を示して選択できない

Given 重複グループのメンバーがフォルダ内1個とDock上1個（いずれも#449の選択対象外）である
When 重複確認面を開く
Then 両メンバーは所属（フォルダ名/Dock）つきで示されるが選択できず、このグループには選択できる個体がない旨の理由が表示される
And このグループに対する削除は発生しない

### Scenario: ロック中・ロック状態不明のメンバーは残る個体として示される

Given 重複グループにロック中のメンバーとロック状態不明のメンバーが含まれる
When 重複確認面を開く
Then 両者はロック中/ロック状態不明の理由つきで示され、選択できない
And 同グループ内の選択可能な他メンバーの削除は通常どおり行える

### Scenario: 3個の重複は2個まで外せる

Given 同一起動先の3個の重複がある
When 2個を選んで「ホームから外す」→ 確定する
Then 3行のうち2行が削除され、1個が残る
And 開き直し後の確認面に残り1個は重複グループとして現れない（サイズ1は重複でない）

### Scenario: セッション計画で既に除いた配置はグループから消える

Given 確認面で片方を選び「ホームから外す」を実行した直後である
When 確認面を再度開く
Then 残り1個のみが表示され、当該グループは重複として現れない

### Scenario: 選択・キャンセル・capture失敗は零書込み

Given 確認面で選択を変更している
When 面を閉じる／編集画面をキャンセルする／captureが失敗する
Then いずれもfavorites DBは変化しない
And capture失敗時は既存の理由表示で再試行できる

### Scenario: capture後の変更は既存のstale判定で止まる

Given 確認面で選択と「ホームから外す」を実行した後、ホームへ戻って手動drag等でレイアウトを変えた
When 確定する
Then 既存の適用経路の再captureでrevision・状態一致検証が失敗し、favorites DBは一切変化しない
And セッション（選択を含む）は破棄され、最新のcaptureで編集画面が開き直される（重複表示も最新captureから再計算される）

## Data and state

- 読むdata: 編集画面の既存capture（`HomeEditSurfaceAccess.inspectCapture`）から投影した図のitem（targetKey、種別、label、位置、container、profile、lock）。新しい読み込み・権限・権威は作らない。
- 書くdata: なし（新規）。削除の書込みは既存#449適用経路のみを通る。`DuplicateLaunchTargets.kt` の一般化はplanner入出力・永続化を変えない。
- 一時的なstate: 重複確認面の開閉状態と選択は編集セッションのprocess内state（既存selectionと同一実体）であり、永続化しない。process死・画面離脱で消失する（確定まで無書込みのため復旧不要）。

## Permissions, privacy, and security

- None。新規permission・外部通信・sensitive dataの追加なし。確認面の表示は端末上の図のメタデータ（label、位置、所属、profile区別）のみを使い、log・diagnosticsへの新規出力は行わない（organizer-diagnostics契約のredaction慣行に従う）。

## Accessibility and localization

- サマリ行はTalkBackで読める単一のbutton（重複の存在と組数をlabelに含む）。確認面の各メンバー行は名前・位置・所属・profile・選択状態・選択不可の理由を、既存 `editSurfaceItemSemantics` と同じ「純粋descriptorがsemantics供給の単一の権威」の構成で供給し、JVM testのoracle対象とする。
- 確認面の開閉・選択・外す・閉じる・理由表示が支援技術で読めて操作できる。dialogのfocusはCompose標準のmodal dialog挙動に従う。
- 200% font scaleで行が崩れないこと、ja/en両localeで文言が解決し空でないこと（resource oracle）。新規文字列は `edit_surface_duplicate_*` 接頭辞で `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` へ追加する（fork文字列慣行）。

## Verification

- 重複グループ計算（名前違い/profile違い/deep shortcut/対象外メンバー/セッション除済み/並びの決定性）と最後の1個のguardはinterface経由のJVM test（`tests/unit/app/lawnchair/homeedit/`。既存 `organizer-unit-tests` gateに自動加入。新laneは作らない）。`DuplicateLaunchTargets.kt` の一般化は既存planning test群（`DuplicateInjectionPropertyTest` 等）とgolden corpus（`sha256.txt`）の不変で回帰確認する。
- 確認面メンバー行のsemantics descriptor（名前・位置・所属・profile・選択状態・理由）のJVM test。
- エミュレータのスクリーンショット（サマリ行、確認面、選択 → 外す → 確定 → ホーム反映、Undo snackbar、200% font scale）を取得し、実機での表示・操作・TalkBack読み上げをowner確認に含める（階層Mの実機確認。emulatorは補助証跡）。
- ベンチマークB7: Benchmark節の固定手順の会計をPR本文に記録し、fixture（§5のseeding instrumentation）を使ったエミュレータ実行で固定手順が成立することを確認する。
- 書込み経路を追加しないことの確認: diffが触れるpathは `lawnchair/src/app/lawnchair/homeedit/**`（純粋module+UI）、`lawnchair/src/app/lawnchair/organizer/planning/DuplicateLaunchTargets.kt`（振る舞い不変の委譲refactor）、string resource、およびtestに限る。`src/**`・`organizer/application/**`・`ModelWriter` 等を含まない。
- 同一PRで `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreportする（`src/` 側変更が発生した場合はNFR-010として記録し、階層判定をやり直す）。

## Dependencies

- #449（implemented、PR #476）: 編集画面・選択・Remove・確定・stale開き直しの正本。本specはその前段のみを変える。
- #450（implemented、PR #481）: 確定後のUndo。本specはUndoを作らない。
- #451（implemented、PR #485）: 重複検出意味論の所有者。一般化は同file内の委譲refactorであり、spec 451の受入条件（代表規則・警告・P-09/P-10）を変えない。
- #441（closed）: B7のbaseline・重み表・fixtureの正本。
- 被依存: なし。

## Acceptance criteria

- [ ] AC-1: 重複が存在するcaptureでの編集画面に「重複アイコン N組」のサマリ行が現れ、0組では現れない。tapで重複確認面が開き、各グループの各メンバーが名前・ページ/位置・フォルダ所属・profile区別・選択状態・選択不可の理由つきで一覧される（エミュレータスクリーンショット+実機owner確認）。
- [ ] AC-2: 重複判定が#451と同一である: 名前だけの一致は重複としない、profile違いは重複としない、deep shortcutはpackage + shortcut id + profile、widget/folder自身/app pair/legacy shortcut/session作成行は参加しない。planningの一般化関数への委譲構成（判定規則の2実装を作らない）をdiffで確認し、既存planning test群とgolden corpusの不変をtestで確認する。
- [ ] AC-3: 確認面からの選択が既存の選択機構と同一実体で動く（eligibility、touched guard、ロック扱い、選択解除）。自動選択は行わない。
- [ ] AC-4: 最後の1個のguard（確認面の2境界で同一の純粋関数を適用）: (1) 確認面からのtoggleでグループ全メンバーが選択される場合、および(2) 確認面内の「ホームから外す」を既存`RemoveFromHome`へ流す直前に現行selectionがグループ全メンバーを含む場合、いずれもtypedな理由表示で受け付けず、零書込みである（図上での事前全選択経由の迂回を含む）。guardはduplicateメンバーのみを判定し、重複以外を含む「選択全体への既存Remove」の通常意味論を変えない。guardを共有planner（`EditSurfaceSessionPlanner.plan`）へ入れず、図上の通常Remove契約が不変であることをdiffとtestで確認する。対象外のみのグループは理由を示して選択不可。3個の重複では2個まで外せる。JVM testで検証する。
- [ ] AC-5: 確認面内の「ホームから外す」が既存 `RemoveFromHome` と同一アクションであること（新規アクション種別・intentなし）。成功時は面が閉じて確定に進め、拒否時は零書込みで理由表示する。確定・Undoが既存#449/#450契約のままであること（既存test群が回帰を担保。新規instrumentation laneは作らない）。
- [ ] AC-6: 表示・選択・キャンセル・capture失敗が零書込みであり、stale時は既存どおりセッション破棄+最新captureでの開き直し（重複表示の再計算を含む）である（JVM test+エミュレータ操作記録）。
- [ ] AC-7: ベンチマークB7: Benchmark節の固定手順の重み付きコストが9未満（8）であることを#441の重み表で会計し、fixtureでのエミュレータ実行記録をPRに残す。実装PRで `docs/engineering/editing-burden-benchmark.md` のB7行へ削除提案経路の目標（重み付きコスト9未満）を反映する（#451の構造保証・可視性目標は不変）。
- [ ] AC-8: アクセシビリティ: サマリ行とメンバー行のsemanticsがリソース由来かつ空でないこと（en/ja）の自動検証、200% font scaleでの崩れなし、TalkBackでの操作可能性（エミュレータでの構造確認+実機owner確認）。
- [ ] AC-9: 書込み経路の追加なし: diff path列挙（homeedit、planning検出file、strings、testsのみ）をPRに記録する。同一PRで `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreportする。`src/**` 変更が発生した場合は本specの階層判定を再開する。
- [ ] AC-10: `validate_repo_contract.py` 成功。spec statusと正本文書の同期（requirements側の要件追跡の扱いは受入時に確定して反映）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 / AC-3 | エミュレータスクリーンショット（サマリ行・確認面・選択状態）+ 実機owner確認。グループ計算のJVM test |
| AC-2 | homeedit JVM test（名前違い・profile違い・deep shortcut・対象外・session作成行の非参加）+ 既存planning test群（`DuplicateInjectionPropertyTest`）とgolden corpusのgreen + diff確認（委譲refactor） |
| AC-4 | homeedit JVM test（guardのtyped拒否: toggle時+dispatch直前、図上事前全選択（2個組・3個組）からの面内Remove拒否、1個解除後の計画成功、unrelated選択混在でduplicateメンバーのみ判定、対象外のみグループ、3個の重複、決定性） |
| AC-5 / AC-6 | homeedit JVM test（アクション種別の同一性・零書込み経路）+ エミュレータ操作記録（外す → 確定 → ホーム反映 → Undo）。既存shared-writer instrumentation laneが#449/#450契約の回帰を担保 |
| AC-7 | ベンチマーク§5 fixtureでのエミュレータ実行記録 + 会計表（PR本文）+ `editing-burden-benchmark.md` のdiff |
| AC-8 | semantics descriptorのJVM test + strings resource oracle（en/ja非空）+ エミュレータ200% font scaleのスクリーンショット |
| AC-9 | PR diffのpath列挙 + `measure_upstream_patch_surface.py` の出力（PR本文） |
| AC-10 | `python3 tools/repo-contract/validate_repo_contract.py` 成功 |

## Change history

- 2026-10-03: Draft created for #507（seed-backlog order 6。Issue本文の未確定事項 — 入口の形式・グループ表示・対象外の理由・最後の1個の選択規則・B7の固定手順 — を本specで確定）。
- 2026-10-03: Revision 2 — Phase 1 review round 1（[判定](https://github.com/nunu1733/NunuLauncher/pull/513#issuecomment-5967339973): accepted化前に修正が必要、指摘2件）への対応。指摘1（高）: 最後の1個のguardの適用境界を「確認面からのtoggle」に加えて「確認面内Removeのdispatch直前」へ拡張（図上で事前に全メンバーを選択してから面内Removeを実行する迂回を塞ぐ。guardを共有 `EditSurfaceSessionPlanner.plan` へ入れず図上の通常Remove契約を不変に保つことを明記）。Scope・Behavior scenario（事前選択迂回・unrelated混在の2 scenario追加）・AC-4・Test oracleへ同期。指摘2（低）: Benchmark節の会計説明を「長押し1回 + 以降はtapのみ」へ文言修正（重み付き8・操作数8の数値不変）。
- 2026-10-03: **accepted** — Phase 1 re-review round 2（[判定](https://github.com/nunu1733/NunuLauncher/pull/513#issuecomment-5967402221): **Clear**。round 1の2指摘はいずれも解消済み、新規blocking findingなし）を経て受理。受入revisionは本commit（Revision 2 + status遷移）。Phase 2実装は同branchで継続し、実装完了時にPR本文のpacketを最終PRとして更新する。
