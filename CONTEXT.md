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

**レイアウトsnapshot (Layout Snapshot)**:
ある時点のホームレイアウト、端末能力、およびrevisionを固定した読み取り専用の入力。
_Avoid_: Backup、DB dump

**レイアウトplan (Layout Plan)**:
snapshotから提案された変更と、その理由・警告を含む、まだ適用されていない結果。
_Avoid_: Layout（現在状態と混同する場合）、Result

**整理run (Organization Run)**:
snapshot取得からplan作成、検証、確認、適用、結果検証までの一連の試行。
_Avoid_: Task、Job

**全体整理 (Full Organization)**:
対象集合全体について新しいレイアウトplanを作る整理run。
_Avoid_: Reset、洗い替え

**増分配置 (Incremental Placement)**:
新しい配置アイテムを既存レイアウトへ加え、全体整理の規則と整合するplanを作る整理run。
_Avoid_: Auto add

**ロック配置 (Locked Placement)**:
整理runが変更してはならない配置。配置アイテム自体だけでなく、その占有領域も制約となる。
_Avoid_: Locked item（何が固定されるか曖昧な場合）

**カテゴリ割当 (Category Assignment)**:
配置アイテムを整理ルール上の1つのカテゴリへ対応付けた、根拠と確信度を持つ判断。
_Avoid_: Play Store category（情報源を指す場合を除く）、Theme

**recovery point**:
整理runの適用前へアプリ内操作で戻すために保存された、検証済みの復旧状態。
_Avoid_: Backup（長期保存用バックアップと混同する場合）、Undo（操作そのものを指す場合）

**有効プリセット (enabled preset)**:
宣言カタログのうち、現在の端末種別に対してプラットフォーム宣言上有効と判定されたグリッドプリセット。
_Avoid_: 利用可能グリッド（設定UIの表示と混同する場合）、サポート対象（NFR-007のsupport範囲と混同する場合）

**モデルスナップショット (Model Snapshot)**:
相関リロード生成の完了時に読み取った、メモリ上のホームレイアウト状態のcanonical表現。検証ではmodel-verifiable projectionとして比較され、DB再取得の同じprojectionと突き合わされる。検証専用の一時データであり、永続化しない。
_Avoid_: Backup（永続データと混同）、Layout Snapshot（入力captureと混同）

**モデル検証可能projection (Model-verifiable Projection)**:
メモリ上のmodelが忠実に表現するレイアウト状態のフィールド部分集合(配置アイテムのidentity、container、配置、種別、folder構成、widget identityとbind状態、profile identity、種別ごとの意味的起動対象identity)。modelが表現しないフィールドはDB側の検証だけで担保する。
_Avoid_: Model LayoutState（完全なcanonical stateと混同）

**相関リロード生成 (Correlated Reload Generation)**:
適用後の検証のために要求された1回のmodel reloadが生んだloader生成。要求と完了が同一tokenで結ばれ、loader transactionのcommit/close後の因果的完了境界でのみ完了する生成を指す。
_Avoid_: Load ID（診断値であり相関の正本ではない）
