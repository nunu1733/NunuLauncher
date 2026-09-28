# High-risk audit: PR #465 編集負担ベンチマークの終了条件改正（Revision 7）

> Status: accepted
> Audit date: 2026-09-26

- Auditor: 独立session（general-purpose subagent。PR #465の実装セッション外。solo保守のため独立sessionによる再実行・再確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/465
- Head SHA: `314b3c09a34f235a37193d8934d8bf04c50baf3c`
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36218156778（監査時点でcompleted/success。`validate-repo-contract` pass 52s、`final-status` pass、`high-risk-evidence` pass（run 36218156792）、source job群はdocs-onlyのためpath filterでskipping）
- Criteria: [specs/441-editing-burden-benchmark/spec.md](../../specs/441-editing-burden-benchmark/spec.md)（status: implemented）のAC-1〜AC-8、NFR-014（[docs/product/requirements.md](../../docs/product/requirements.md)）

## Scope

`git diff --stat origin/main..origin/issue-441-benchmark-revision`（merge-base `668d803c03` = PR #464 merge commit）の結果、変更は5ファイル・+133/−117で、すべてdocs配下であることを確認した:

- `docs/assessment/441-fixture-seeding-evidence.md`
- `docs/engineering/editing-burden-benchmark.md`
- `docs/product/requirements.md`
- `specs/441-editing-burden-benchmark/plan.md`
- `specs/441-editing-burden-benchmark/spec.md`

production/testコード・manifest・CI設定の変更はゼロ（docs-only）。runtime書き込み経路・migration対象への影響なし。`risk:` label不要の低リスク差分だが、`Closes #441` の最終PRであるため独立監査を実施した。

## Criteria check

Issue #441の経過（オーナー判断 [issuecomment-5842816844](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5842816844)、改正案起草、ChatGPT review 3回（Changes requested ×2 → Approved [issuecomment-5843130242](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5843130242)、head `e90376a4fe`）、保守者承認 [issuecomment-5843174869](https://github.com/nunu1733/NunuLauncher/issues/441#issuecomment-5843174869)）と本PRの内容は整合している。Issue本文のScope 3/4・Completion evidence・status blockquoteの改正も適用済みを確認した。

- **AC-1**: 定義文書が存在し `Status: Accepted`。B1〜B7、重み表（確定値）、操作数の定義、§1の指標の性質（手順コストの会計・人間実測対象外）、§5 fixture、§7 agent実行可能な検証手順、§6 baseline（決定的算出・操作数付き）、目標表（確定値）をすべて含む。**確認**
- **AC-2 / AC-3 / AC-4**: fixture seeding instrumentationとその実行記録はPR #464で構築・実施済み（[441-fixture-seeding-evidence](./441-fixture-seeding-evidence.md) §1/§8。2 test PASS、hotseat・予約領域保持、identity一意性・重複2組）。本PRはこれらのコードに触れず、evidence文書の見出し再編（§1〜§8の昇順・一意構成）と相互参照（§5→§8等）の同期のみ。**確認（既存実績の引用で妥当）**
- **AC-5**: 本PRはtest/CIを変更しないため新規審査は不要。既存lane統合とportfolio整合はPR #464で実施済み（evidence §8に `validate_ci_portfolio.py` OKの記録）。**確認**
- **AC-6**: docs-only差分でありspotless対象外。evidence §8にclean checkoutでの `./gradlew spotlessCheck` BUILD SUCCESSFULの記録あり。**確認（既存実績の引用）**
- **AC-7**: 下記「AC-7 照合表の独立再計算」のとおり、監査者が§2/§3/§4から全7課題を独立に再計算し、PR本文・§6の表と**全項目一致**を確認。**確認**
- **AC-8**: 目標表は確定値（B2≤24、B3≤10、B4≤12、B1=追加操作0、B5=1操作、B6/B7は#446/#451で確定）。NFR-014はrequirements.mdで `accepted（2026-09-26。旧: proposed（2026-09-24））` へ変更され、Decision historyに2026-09-26の判断追記あり。**確認**

### AC-7 照合表の独立再計算

監査者が定義文書§2の共通開始条件（ページ0表示。B5は誤操作の結果ページ、B6はinstall後にページ0へ戻すsetup）、§3の手順、§4の重み表（tap=1、長押し=2、同ページdrag=2、ページ跨ぎdrag=4+越えたページ数、swipe=1、undo tap=1）から独立に再計算した。重み付きコスト／操作数:

| 課題 | 監査者の再計算 | §6・PR本文 | 判定 |
|---|---|---|---|
| B1 | swipe×1（1）+ 長押し（2）+ 1ページ跨ぎdrag 2ページ目→1ページ目（4+1=5）= **8** / 操作数 **3** | 8 / 3 | 一致 |
| B2 | 1個目: 長押し2 + 2ページ跨ぎdrag（4+2=6）= 8。2〜5個目: drop後の視点は3ページ目（§3.1）のため各 2ページ分swipe（2）+ 長押し2 + drag 6 = 10 ×4 = 40。合計 **48** / 操作数 2 + 4×4 = **18** | 48 / 18 | 一致 |
| B3 | ページ1へswipe（1）+ フォルダ作成（長押し2 + 同ページdrag 2）= 5。ページ2の2個: 各 swipe 1 + 長押し2 + 1ページ跨ぎdrag ページ2→1（4+1=5、drop後の視点は1ページ目に戻るため次もswipeが必要）= 8 ×2 = 16。合計 **21** / 操作数 3 + 2×3 = **9** | 21 / 9 | 一致 |
| B4 | ページ0の3個 ×（長押し2 + 同ページdrag 2 = 4）= 12 + ページ1へswipe（1）+ ページ1の3個 ×4 = 12。合計 **25** / 操作数 3×2 + 1 + 3×2 = **13** | 25 / 13 | 一致 |
| B5 | 削除: undo tap **1** / **1**。移動: 長押し2 + 逆方向2ページ跨ぎdrag 6 = **8** / **2**（undoは削除のみ。§3.1/§3.3） | 1 / 8、1 / 2 | 一致 |
| B6 | 2ページ目の8個: 各 swipe 1 + 長押し2 + 1ページ跨ぎdrag（4+1=5）= 8 ×8 = 64。3ページ目の2個: 各 swipe 2 + 長押し2 + 2ページ跨ぎdrag（4+2=6）= 10 ×2 = 20。合計 **84** / 操作数 8×3 + 2×4 = **32** | 84 / 32 | 一致 |
| B7 | ページ0のペア削除（長押し2 + 同ページdrag 2）= 4 + ページ1へswipe（1）+ ページ1のペア削除 4 = **9** / 操作数 2+1+2 = **5**。探索は§4により数えない | 9 / 5 | 一致 |

前提の確認:

- **B2/B6のdrop後視点移動**: `src/com/android/launcher3/Workspace.java:2303-2313` の `snapScreen != mCurrentPage` 判定と `snapToPage(snapScreen)` をローカルtreeで実確認した。drop先ページへ視点が移るため、次の操作に必要なswipeが内訳に含まれる記載は妥当。
- **B6の8+2配置**: `docs/assessment/441-fixture-seeding-evidence.md` §5のDB oracle実証（screen 1に8個: (0,3)〜(3,3)・(0,4)〜(3,4)、screen 2に2個: (0,2)・(1,2)）と、§5 fixtureの空きcell数（ページ1空き8、ページ2空き12）および§3.4の走査規則（`WorkspaceItemSpaceFinder.java:55-66` の1ページ目除外）から、8+2は決定的に導かれる。**整合**。
- **50%削減目標の整数丸め**: 48→≤24、21→≤10、25→≤12。§4.1の初期目標の確定値化として妥当。

## Executed test surface

- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` を確認。
- `gh pr view 465 -R nunu1733/NunuLauncher --json headRefOid,...` → head SHA `314b3c09a34f235a37193d8934d8bf04c50baf3c`、PR本文の照合表を取得。
- `gh pr diff 465 -R nunu1733/NunuLauncher` + `git fetch origin main issue-441-benchmark-revision` + `git diff --stat origin/main..origin/issue-441-benchmark-revision` → docs配下5ファイルのみを確認。
- `git show origin/issue-441-benchmark-revision:docs/engineering/editing-burden-benchmark.md`（全文精読、§2/§3/§4/§6/§7からAC-7を再計算）。
- `git show origin/issue-441-benchmark-revision:specs/441-editing-burden-benchmark/spec.md` / `plan.md`（AC-1〜AC-8、Revision 7節、A-13のbaseline修正根拠を確認）。
- `git show origin/issue-441-benchmark-revision:docs/assessment/441-fixture-seeding-evidence.md`（見出し構造 §1〜§8の昇順・一意性、§4/§5の実証記録、§7の参照更新 `§1/§2/§4/§5/§8` を確認）。
- `sed -n 2295,2320p src/com/android/launcher3/Workspace.java`（drop後のsnapToPage前提を実コードで確認）。
- `gh issue view 441 -R nunu1733/NunuLauncher --json body` + 全コメント（オーナー判断・改正案・review 3回・承認記録・Issue本文改正の確認）。
- `gh pr checks 465 -R nunu1733/NunuLauncher`（初回はvalidate-repo-contract pending、再実行でpassを確認。全jobの最終状態は上記CI run欄のとおり）。

## Findings

- **重大な問題は検出しなかった。** AC-7の照合表は7課題すべて（重み付きコスト・操作数の両列）で監査者の独立再計算と一致した。B2の復帰swipe前提（drop後の視点移動）とB6の8+2配置前提は、それぞれ実コード（`Workspace.java:2303-2313`）とDB oracle実証記録（evidence §5）で裏付けを確認した。
- 残課題なしを確認: ChatGPT review最終回（issuecomment-5843099727）で指摘されたevidence §6/§7の旧参照・旧「計測」表現2か所は、head `e90376a4fe` 以降のdocs-only対応（issuecomment-5843124432）で解消済み。本監査でも現行文に残骸がないことを確認した。
- 軽微な注記（blockerなし）: evidence §8のclean checkout検証対象headは `7102b0d4bf`（PR #464系）であり、本PR head `314b3c09` とは異なる。ただし§8本文が「本節以降の追記はdocumentation差分のみ」と明記しており、docs-only差分である本監査のdiff範囲確認（production/testコード変更ゼロ）と矛盾しない。
- CI状態: 監査開始時点で `validate-repo-contract` がpendingだったが、監査中にcompleted/passへ遷移した。`final-status` passを確認済みであり、merge要件のCI側条件は満たしている状態にある。
- B6/B7の目標は#446/#451のspecで確定する従属項目であり、本PRの範囲外（Issue本文どおり）。

## Findings事後確認（2026-09-26）

PR #465のChatGPT review（[判定](https://github.com/nunu1733/NunuLauncher/pull/465#issuecomment-5843265042)）が指摘した2件の文書同期残件（evidence headerの旧AC-7記載、§7(1)の実機実績provenance）を対応済み。本確認以降のhead差分はdocs配下のみであり、監査の実質確認（AC-1〜AC-8、照合表算術）への影響はない。
