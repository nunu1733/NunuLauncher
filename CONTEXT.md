# Home Layout Organization

ユーザーのホーム画面を、安全かつ再現可能な規則で整理するためのドメイン用語を定義する。ここでは実装クラスやデータベース構造を扱わない。

## Language

**ホームレイアウト (Home Layout)**:
ユーザーがホーム画面で利用する、ページ、Dock、フォルダ、および配置アイテムの完全な配置状態。
_Avoid_: Workspace（platform実装を指す場合を除く）、ホーム構成

**配置アイテム (Layout Item)**:
ホームレイアウトに存在し、位置または親フォルダを持つアプリ、ショートカット、フォルダ、ウィジェット等の要素。
_Avoid_: App（配置対象がアプリだけでない場合）、Favorite

**配置 (Placement)**:
配置アイテムと、そのページ・領域・セルまたは親フォルダとの対応。
_Avoid_: Position（座標だけを意味する場合）

**対象集合 (Target Set)**:
1回の整理で移動、保持、または新規配置を検討する配置アイテムの明示的な集合。
_Avoid_: 全アプリ（対象範囲が曖昧な場合）

**整理ルール (Organization Rules)**:
対象集合をどのような配置へ変換するかを記述する、version付きの検証可能な規則。
_Avoid_: 設定、XMLルール

**レイアウトストラテジー (Layout Strategy)**:
対象集合をどのような配置方針へ変換するかを決める、version付きの組み込み計画戦略。folder形成、対象unit、unit順序、page範囲、cell探索をcurated catalogの1メンバーとして固定し、選択identityがpolicy provenanceへ参加する ([spec 182](./specs/182-layout-strategy-catalog/spec.md))。
_Avoid_: 並べ替え設定 (組合せ式toggleを想起させる)、Theme、OrderingPolicy (旧単一値の型名)

**ストラテジー固定unit (strategy-fixed unit)**:
あるlayout strategyが「本来はmovableだが、そのstrategyの意図として動かさない」と決めたtop-level unit。配置上の占有を維持し、自然に保持されたunitとは別の理由として扱う ([spec 237](./specs/237-global-compact-v2-folder-relocation/spec.md))。
_Avoid_: 保存済み (naturally preservedとの混同)、スキップ対象

**セマンティック配置role (semantic placement role)**:
計画対象のtop-level unitを、配置意味論上の種別 (app/shortcut、folder、widget) として分類したもの。strategyはroleごとにpreferred regionとmovement intentを別個に宣言し、widgetは専用のwidget streamとしてapp/folder streamより先に消費される ([spec 235](./specs/235-widget-strategy-placement/spec.md))。
_Avoid_: movable flag (widgetをgeneric movable unitへ退化させる)、item kind (capture側の型名との混同)

**ウィジェット配置ポリシー (widget placement policy)**:
widget移動に対応したstrategyがwidget roleに対して宣言する、span不変の再配置意思。preferred region (ウィジェット帯 / page全域)、決定的cell走査、不変key順の処理順、page affinity、配置不能時のdegradeからなる純data ([spec 235](./specs/235-widget-strategy-placement/spec.md))。
_Avoid_: widget settings (user設定との混同)、movement cost (数値costや探索を想起させる)

**ウィジェット帯 (widget band)**:
1つのcaptured page上で、そのpageのeligible widget群がcapture時点で占有する行の閉区間。[minRow, maxRow]。stable/tidy系のwidget再配置領域として使う ([spec 235](./specs/235-widget-strategy-placement/spec.md))。
_Avoid_: widget area (領域サイズが固定であるような誤解)、widget zone

**レイアウトsnapshot (Layout Snapshot)**:
ある時点のホームレイアウト、端末能力、およびrevisionを固定した読み取り専用の入力。
_Avoid_: Backup、DB dump

**レイアウトplan (Layout Plan)**:
snapshotから提案された変更と、その理由・警告を含む、まだ適用されていない結果。
_Avoid_: Layout（現在状態と混同する場合）、Result

**整理run (Organization Run)**:
snapshot取得からplan作成、検証、確認、適用、結果検証までの一連の試行。
_Avoid_: Task、Job

**plan preview (プランプレビュー)**:
確認より前に、planning snapshot と同じ revision を read-only に再 capture し、executable action plan を materialize して semantic plan の rationale と対応付けた、process-local な変更一覧。書込み、checkpoint、recovery 操作を行わない。
_Avoid_: visual preview (UI scope とは区別)、Backup、Undo

**全体整理 (Full Organization)**:
対象集合全体について新しいレイアウトplanを作る整理run。
_Avoid_: Reset、洗い替え

**増分配置 (Incremental Placement)**:
新しい配置アイテムを既存レイアウトへ加え、全体整理の規則と整合するplanを作る整理run。
_Avoid_: Auto add

**スコープ合成整理 (Scope-Composed Organization)**:
既存配置の全体再整理と、ユーザーが明示的に選択したホーム未配置アプリを候補追加として、1つのplanで扱う整理run ([spec 228](./specs/228-organizer-missing-app-selection/spec.md))。未選択の候補はplanに現れない。
_Avoid_: 全体整理＋追加 (別々のrunの連結と混同する場合)、自動追加 (選択は常に明示的)

**ロック配置 (Locked Placement)**:
整理runが変更してはならない配置。配置アイテム自体だけでなく、その占有領域も制約となる。
_Avoid_: Locked item（何が固定されるか曖昧な場合）

**カテゴリ割当 (Category Assignment)**:
配置アイテムを整理ルール上の1つのカテゴリへ対応付けた、根拠と確信度を持つ判断。
_Avoid_: Play Store category（情報源を指す場合を除く）、Theme

**カテゴリidentity (Category Identity)**:
分類・override・groupingの正本となる閉じたカテゴリ識別子。組み込みtaxonomyの `CategoryId` と、stable local opaque IDを持つユーザー定義カテゴリの2種からなる。canonical順序・等価はIDのみで決まり、表示名は参与しない ([spec 336](./specs/336-user-defined-categories/spec.md))。
_Avoid_: CategoryId（組み込み側のみを指す場合）、カテゴリ名（表示名をidentityと混同する場合）

**ユーザー定義カテゴリ (User-defined Category)**:
ユーザーが作成・rename・削除するカテゴリ。stable local opaque ID（UUID v4形式）と表示名を持ち、表示名はidentityではなく正規化・長さ上限・catalog内一意のpresentationである。割当はS1 overrideのみで受け、自動分類(S2–S6)は決して向けられない ([spec 336](./specs/336-user-defined-categories/spec.md))。
_Avoid_: カスタムタグ (分類identityとしての重みを曖昧にする)、自由カテゴリ (検証なき作成を示唆する)

**アクティブカテゴリカタログ (Active Category Catalog)**:
plannerが受け取る実行時のカテゴリ表面。組み込みv1 taxonomy（bundle不変の正本）と現在のユーザー定義エントリの和としてmembershipとfallbackを提供する。組み込みtaxonomy identityとは別の、動的でcontent-addressedなpolicy sourceであり、composition provenanceと動的cutに参加する ([spec 336](./specs/336-user-defined-categories/spec.md))。
_Avoid_: taxonomy (組み込みbundle内容との混同)、カテゴリ一覧 (UI表示との混同)

**recovery point**:
整理runの適用前へアプリ内操作で戻すために保存された、検証済みの復旧状態。
_Avoid_: Backup（長期保存用バックアップと混同する場合）、Undo（操作そのものを指す場合）

**organizer durable status (永続整理状態)**:
application moduleがrecovery storeの永続recordとtombstoneから導出する、閉じた語彙の状態表示。永続化せず毎回導出するため、記述対象のrecordより長く生存しない。recordの中身、revision、digest、アイテム識別子を含まない。
_Avoid_: Organizer status（process-localなrun状態と混同する場合）、Backup state

**有効プリセット (enabled preset)**:
宣言カタログのうち、現在の端末種別に対してプラットフォーム宣言上有効と判定されたグリッドプリセット。
_Avoid_: 利用可能グリッド（設定UIの表示と混同する場合）、サポート対象（NFR-007のsupport範囲と混同する場合）

**生成フォルダ名 (Generated Folder Title)**:
Organizer が新規生成したフォルダへ、その grouping semantic から単一の resolver 経由で決定して適用される user-facing なタイトル。semantic naming identity が plan 内の正本であり、解決済み文字列は preview と apply の両方が同一 plan から消費する。
_Avoid_: フォルダ名の自動推論 (UI 側再計算を想起させる)、Folder (固定名)

**パーソナライゼーション文脈書き出し (Personalization Context Export)**:
1回のpersonalization試行のために、app内canonical入力から生成される、export-scopedなIDで項目を参照する読み取り専用の最小文脈 ([spec 204](./specs/204-ai-personalization-context-intent-contract/spec.md))。privacy tierを持ち、raw DB row・内部`ItemId`・package名・raw usage時刻を含まないことを既定とする。
_Avoid_: DB dump、Backup、snapshot (Layout Snapshotとの混同)

**パーソナライゼーション意図 (Personalized Intent)**:
AI/agentが `PersonalizationContextExportV1` に対して返す、semantic preference (優先度、 grouping、page/region親和、保持希望) のversion付き表現。v3 ([spec 330](./specs/330-partial-intent-authoring/spec.md)) からは部分authoringを許し、書かれなかったrefの意味は常にcanonical unresolved (判断なし) である。physical placementやDB mutationの指示ではない。acceptされると、全export refの状態が決定済みの完全分割 (complete canonical representation) が構成され、そのcontent digestがidentityとなるimmutable planning inputとなる。
_Avoid_: layout plan (最終配置結果との混同)、rule (整理ルールとの混同)、未言及refの推測補完 (禁止)

**export-scoped ID (Export Item Reference)**:
1つのcontext export内でのみ有効な、itemを指すopaqueな識別子。export生成ごとに新鮮な乱数から割り当てられ、内部`ItemId`・DB row IDとは無関係かつ逆算不可能である。対応付けはexport sessionのみが保持する。
_Avoid_: ItemId (内部正本IDとの混同)、package名、安定な仮名化identifier (pseudonym)

**export session (エクスポートセッション)**:
1つのcontext exportに対応する、app-privateで期限付きのdurableな対応記録 (`exportId`、ref↔内部`ItemId`のmap、privacy tier、structural source context digest、signal provenance、生成・失効時刻)。外部アプリ滞在中のprocess deathを跨いでintent取り込みを可能にする。backup対象外であり、label等のuser作成自由文を含まない。
_Avoid_: backup、永続layout入力 (planning入力との混同)

**外部エージェント交換 (External Agent Exchange)**:
`PersonalizationContextExportV1` を外部chat/agent環境へ持ち出し、 `PersonalizedIntentV1` として持ち帰る、NunuLauncherが提供するuser-driven workflow全体 ([spec 205](./specs/205-external-agent-exchange/spec.md))。agent実行部はNunuLauncherの外にあり、network通信・provider APIを含まない。
_Avoid_: AI連携 (provider依存を想起させる)、integration (managed AI pathとの混同)

**交換パッケージ (Exchange Package)**:
1回の外部agent受け渡しに使う、agent向けinstruction部と `PersonalizationContextExportV1` data部を明示分離した単一text (またはfile) 表現。data部はCONTEXT marker行 (`-----BEGIN/END NUNULAUNCHER CONTEXT-----`) で囲まれ、instruction/dataの区別が機械的に判別できる。生成時に完了するimmutableな値であり、送信前確認とtransportは同一値を扱う。
_Avoid_: prompt (data部も含む曖昧な呼称)、backup

**送信前確認 (Pre-send Disclosure)**:
exchange packageをclipboard・share・file等でapp外へ出す直前に、外部へ出る情報の種別と内容をuserに提示し、明示的な同意を得る確認step。確認なしに外部送信経路を開かない。
_Avoid_: privacy policy (静的文書との混同)

**持ち帰りIntent取り込み (Intent Import)**:
外部agentの返答textから `PersonalizedIntentV1` を認識し、#204 validatorへ渡す取り込みstep。入力はまずインポート正規化 (Import Normalizer、[spec 329](./specs/329-import-normalizer/spec.md)) が受け持ち、accepted framingのうちmarker形式以外 (単一fenced `json` block・standalone JSON object) をcanonical化する。曖昧なJSON拾い上げやpartial解釈を行わない。import text全体へのenvelope上限 (1 MiB、UTF-8 byte基準) はnormalizerより前の #205所有gateである。
_Avoid_: auto-apply、paste-to-layout

**インポート正規化 (Import Normalizer)**:
import textの外形 (framing/transport表現) のみを認識・canonical化する境界層 ([spec 329](./specs/329-import-normalizer/spec.md))。accepted framingはmarker形式 (canonical)・単一fenced `json` code block・standalone JSON objectの3種で、それ以外はtyped失敗 (曖昧/認識不能) でzero-write rejectする。fuzzy extraction (複数候補からの推測選択・`{...}` の任意拾い) は禁止で、payloadは正規化済入力の部分文字列 (semantic無変更) に限られる。
_Avoid_: 意味レベルcanonicalization (field値・ref集合・schemaVersionの書換え)、markdown全体実装、provider固有formatへの密結合

**交換フレーミング (Exchange Framing)**:
外部agentの返答text内で `PersonalizedIntentV1` 本体を囲むmarker対 (完全行marker `-----BEGIN/END NUNULAUNCHER INTENT-----`) と、そこから本体を一意に抽出する規則 ([spec 205](./specs/205-external-agent-exchange/spec.md) 所有)。framing内のpayloadのschema解釈は行わない (それは #204)。framingの不成立・曖昧性は #205側のtyped parse失敗である。marker形式以外の外形受理 (fenced `json` block・standalone JSON) は [spec 329](./specs/329-import-normalizer/spec.md) が導入したインポート正規化層の所有であり、本規則はcanonical formとして不変。
_Avoid_: schema (payload本体の契約は #204)、system prompt (instruction部の一部との混同)

**交換セッション置換確認 (Session Replacement Confirmation)**:
activityなexport sessionが存在する状態で新規exchange package生成を開始するとき、#204 single-active-session規則により既存exchangeが無効化されることを明示し、userの承認を得る確認step。承認なしには生成を開始しない。
_Avoid_: 上書き保存 (既存exchange宛回答が以降import不可となる破壊的操作であることの表示を省く呼称)

**候補subject (candidate subject)**:
External Agent Exchangeのexportにおいて、現在Homeに配置されていない未配置アプリ候補を表すexchange subject ([spec 331](./specs/331-exchange-target-scope-coupling/spec.md))。placed itemと同一の乱数seamによるexport-scoped `ref` を持ち、`subject: CANDIDATE` とmobility `CANDIDATE` で区別される。内部対応先はcandidate安定identity (`ComponentKey` + `ProfileId`) であり、raw identifierはexport文書に現れない。
_Avoid_: 仮配置 (配置の作成を示唆する)、新規アイテム (Add行というplan表現と混同)

**scope binding gate (scope束縛検証)**:
validated intentをorganizer runへ適用する時点で、runの確定した対象scopeのcandidate集合がexchange exportの対象scopeと完全一致し、各候補の投影 (identity + availability + 解決済み分類) がexport時と一致することを検証するfail-closedな検証step ([spec 331](./specs/331-exchange-target-scope-coupling/spec.md))。違反はtyped失敗 `SCOPE_MISMATCH` としてzero-write処理される。
_Avoid_: staleチェック (配置構造変化の検出とは別段)、再検証 (availability再検証と混同)
