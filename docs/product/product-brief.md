# Product Brief

> Status: Accepted — 再焦点化方針メモ（2026-09-24承認、Revision 5）による改訂
> Updated: 2026-09-25
> 出典: 再焦点化方針メモ（リポジトリ未収録のため [Issue #440](https://github.com/nunu1733/NunuLauncher/issues/440) 付録に全文）§1〜§4、[Epic #439](https://github.com/nunu1733/NunuLauncher/issues/439)

## Vision

ユーザーがホーム画面を日常的に編集し、散らからない状態を保つための手間を減らすAndroid launcherを提供する。既存の配置を守り、予測可能で説明可能な状態を保つ安全基盤の上に、日常の編集操作を直接・軽く行えるようにする。

## User problem

- アプリ追加によりホーム画面が散らかり、目的のアプリが見つけにくくなる。
- ホーム画面の編集操作（移動、フォルダ整理、項目を外す）が、ページをまたぐdragと反復操作を要求し、後回しになりやすい。
- 誤って動かした・外した項目を元に戻す手段がない。
- launcherによる自動変更は、位置を覚えたユーザーにとって不安と混乱を生む。

前2点が本プロジェクトの中心課題である。後2点は引き続き扱うが、従来どおり全体整理（Organizer）の安全基盤が所有する。

## Target users

- 多数のアプリを導入し、標準的なgrid homeを使うAndroidユーザー。
- 完全自動化より、予測可能なruleと例外指定を重視するユーザー。
- 自分またはAIでruleを編集・共有したいpower user。

編集負担を感じている一般ユーザーが第一の対象であり、power user向けのrule編集・共有は後続のcapabilityとして維持する。

## Value proposition

NunuLauncherは、ホーム画面を安全に保つ基盤（ローカルrule、ユーザーoverride、ロック配置、変更前に説明可能なplan、復旧）の上に、2つの整理手段を提供する。

1. **直接編集**: ユーザーが選んだ項目に対する編集アクション（ページへ移動、フォルダへ入れる、選択から新しいフォルダ、ホームから外す）を、即座に・1操作で行え、直前の操作を取り消せる（新規アプリの配置はUndo対象外。メモ§4.1）（ADR-0013、FR-018〜020）。
2. **全体整理（Organizer）**: 手動runで選択した対象集合について、安全なplanを提示・適用・復旧する。既存の安全契約（preview、explicit confirmation、stale-safe apply、recovery）はそのまま維持する。Organizerは廃止せず、手段の1つに位置づけ直す。

新規アプリが上流の条件でホームへ追加される場合、その配置先はユーザーが選んだ配置先ポリシーに従う（ADR-0015、FR-008再定義）。AI相談（外部agent交換）は機能開発を凍結し、実験的機能のtoggleの背後へ移す（D-016、FR-017）。

## Product principles

1. **Safety before neatness** — 並びの良さより既存layoutと復旧可能性を優先する。計画的な複数アイテムの書換えは既存の安全規約（snapshot revision照合、recovery point、適用後再検証）に従い、ユーザーが明示的に行う即時の編集操作と単一アイテムの追加は、直接編集の契約（ADR-0013）に従う。両者の契約を混同しない。
2. **User intent wins** — 明示的なlockとoverrideは推定より優先する。
3. **Predictable automation** — 同じ条件では同じ結果と理由を返す。
4. **Local first** — 通常動作はofflineで完結し、外部送信は明示的なopt-inとする。
5. **Progressive control** — defaultは簡単に使え、必要なユーザーだけ詳細へ進める。編集操作は段階的に増やす（第1段: 項目単位、第2段: 複数選択。ADR-0014）。Organizerは独立した作業領域（Organizer hub）として維持する。
6. **Upstream sustainable** — launcher本体の品質を上流から取り込み続けられる差分にする。中核UX（ワークスペース、drag、popup）へのbridgeが必要な場合は、それを記録付きで受け入れる。振る舞いはfork側moduleに置き、上流側は呼出しと表示の最小bridgeにし、patch surfaceの増加をNFR-010の記録・レビュー対象とする。

## Now outcome（旧MVP outcome）

MVP（organizer MVP）の成果条件は既に達成・検証済みであり（[mvp-release-readiness.md](./mvp-release-readiness.md)）、以降の成果判断は「編集負担の削減」を中心に置き直す。

Now段階（[Epic #439](https://github.com/nunu1733/NunuLauncher/issues/439)配下、Now-0〜Now-3）の成果条件:

- ユーザーが選んだ項目へ編集アクションを1操作で適用でき、直前の編集（項目単位のアクション、編集画面の確定）を取り消せる（FR-018〜020、D-013/D-014）。
- 上流が追加する新規アプリのアイコンを、ユーザーが選んだ配置先ポリシーに従って置ける（FR-008再定義、D-015）。
- Now段階の各機能は、編集負担ベンチマーク（[#441](https://github.com/nunu1733/NunuLauncher/issues/441)）の改善する課題と目標をspecに書き、実装後に再計測する（NFR-014）。
- Organizerの安く効く品質・導線の修正を行う（重複アイコンFR-021、ホームからの起動FR-022、方針の選択肢の削減FR-023）。
- AI相談の機能開発を凍結し、既定の導線から外す（FR-017、D-016）。

非対象（Now段階で行わない）: Lawnchair 16へのrebaseの着手（[#442](https://github.com/nunu1733/NunuLauncher/issues/442)の結論と専用Epic/ADRを経てから）、AI交換コードの削除、organizerの削除、階層Hの厳格さの緩和。

## Non-goals

- iOS home配置の自動変更。
- 常時context監視による動的なページ入替。
- root/accessibility automationによる他launcherの直接操作。
- online LLMがなければ動作しない分類。
- Lawnchair/Launcher3全体のUI framework置換。
- 全ユーザーに同じ「最適」layoutを強制すること。
- organizerのrecovery protocolやrun leaseに依存する直接編集の実装（§4.8。直接編集はorganizerと別のmoduleとし、不変条件の検証とlocksだけを共有する）。

## Success measures

数値はmeasurement Issueで確定する。少なくとも以下を計測可能にする。

- **編集負担ベンチマーク**（[#441](https://github.com/nunu1733/NunuLauncher/issues/441)が正本。北極星指標）: 重み付き操作コスト（主指標）と実測時間（副指標）。課題B1〜B7のbaseline計測と、Now段階機能の目標達成（NFR-014）。
- 適用後にrecoveryが必要になったrunの割合（organizerの安全基盤の健全性）。
- planが適用不能または未配置itemを残した割合と理由。
- 同一入力でplan hashが一致する割合。
- crash、layout破損、復旧失敗の件数。
- 直接編集の失敗率（書かなかった理由の分類を含む）とUndoの成功率。
- ユーザーが手動で再移動したitemの割合（収集する場合はprivacy review必須）。

## 未解決事項

- **Success measuresの「直接編集の失敗率」「Undoの成功率」**: 2026-09-24草案の追加項であり、[#441](https://github.com/nunu1733/NunuLauncher/issues/441)が測定対象に含めるか判断する。
- **旧briefの「新規アプリが追加から所定時間内に期待する場所へ配置された割合」**: FR-008の再定義（D-015）によりLater/deferredの注記は不要になったが、measurement条件（指定フォルダが使えない場合の上流既定へのfallback等）はADR-0015（[#446](https://github.com/nunu1733/NunuLauncher/issues/446)）の承認前に確定しないため、本書では数値を確定していない。
