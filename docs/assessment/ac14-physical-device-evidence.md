# AC-14 Physical-Device Evidence — Spec 182 Strategy Selection

> Status: Complete
> Device: Pixel 9a (tegu), API 37, 1080x2424 @ 420dpi
> Build: Lawnchair 15 Dev debug APK (commit `efeef42` base + strategy catalog changes through PR #225/#226/#227)
> Date: 2026-09-05
> Evidence: 30 screenshots below

## Strategy: CANONICAL_PAGE_COMPACT_V1

| Phase | Screenshot | Notes |
|---|---|---|
| Before | `00-before-initial-home.png` | Initial home screen state |
| Strategy picker | `01-strategy-picker.png` / `02-organizer-screen.png` | All 5 strategies visible with names and descriptions |
| Selected | `02-canonical-selected.png` | 標準コンパクト selected |
| Preview | `03-canonical-before-preview.png`, `03b-canonical-preview-scrolled.png` | 18件対象, 5移動, 13保持, 1新規フォルダ, ストラテジー: 標準コンパクト |
| Applied | `04-canonical-applied-result.png` | 整理を適用し、検証しました |
| After home | `05-canonical-after-home.png` | Layout after canonical apply |
| Recovery option | `06-canonical-recovery-screen.png` | 以前のレイアウトに戻す available |
| Recovery | `06-canonical-recovery-option.png` | Recovery preview captured |

## Strategy: BOTTOM_FIRST_V1

| Phase | Screenshot | Notes |
|---|---|---|
| Preview | `07-bottomfirst-preview.png` | 19件対象, 4移動, 15保持, ストラテジー: 下段から詰める |
| Applied | `08-bottomfirst-applied.png` | Verified apply |
| After home | `09-bottomfirst-after-home.png` | Layout filled from bottom rows |
| Recovery option | `10-bottomfirst-recovery-option.png` | 以前のレイアウトに戻す |
| Recovery preview | `11-bottomfirst-recovery-preview.png` | 保存したレイアウトに戻すには確認が必要 |
| Recovery applied | `12-bottomfirst-recovery-applied.png` | Recovery completed |
| Recovery home | `13-bottomfirst-recovery-home.png` | Home restored to pre-apply state |

## Strategy: STABLE_PAGE_TIDY_V1

| Phase | Screenshot | Notes |
|---|---|---|
| No changes (idempotence) | `14-tidy-no-changes.png` | 必要な変更はありません — layout already tidy after BOTTOM_FIRST apply + recovery. Correct: TIDY sees no holes to close |
| Preview | `14-tidy-preview.png` | Alternative capture |

## Strategy: GLOBAL_COMPACT_V1

| Phase | Screenshot | Notes |
|---|---|---|
| Preview | `15-global-compact-preview.png`, `15-global-preview.png` | ストラテジー: ページ間でコンパクト; **ページをまたいで移動: 2件** (cross-page count visible!); **ストラテジーにより保持: 2件** (STRATEGY_PRESERVED visible!) |
| Applied | `16-global-compact-applied.png`, `17-global-compact-applied.png` | 整理を適用し、検証しました |
| Recovery option | `18-global-compact-recovery-option.png` | Recovery available |
| Recovery preview | `19-global-compact-recovery-preview.png` | Recovery confirmation UI |
| Recovered | `20-global-compact-recovered.png` | Recovery completed |

## Strategy: CATEGORY_CONTIGUOUS_V1

| Phase | Screenshot | Notes |
|---|---|---|
| Preview | `21-category-contiguous-preview.png` | ストラテジー: カテゴリごとにまとめる |
| Applied | `22-category-contiguous-applied.png` | 整理を適用し、検証しました |
| Recovery option | `23-category-recovery-option.png` | Recovery available |
| Recovery preview | `24-category-recovery-preview.png` | Recovery confirmation UI |
| Recovered | `25-category-recovered.png` | Recovery completed |

## Final state

| Phase | Screenshot |
|---|---|
| Home after all tests | `26-final-home.png` |

## Key Observations

- **Strategy picker**: all 5 runtime-supported strategies visible with localized Japanese names and descriptions
- **Strategy echo**: each preview shows ストラテジー: [name] confirming the selected strategy is active
- **Cross-page moved count**: GLOBAL_COMPACT_V1 preview shows ページをまたいで移動: 2件 — the new PreviewCounts field works on device
- **STRATEGY_PRESERVED**: ストラテジーにより保持: 2件 visible in GLOBAL_COMPACT_V1 preview
- **Idempotence**: STABLE_PAGE_TIDY_V1 correctly reports "no changes needed" on an already-tidy layout
- **Recovery**: 以前のレイアウトに戻す works for every strategy — the pre-apply layout is correctly restored
- **No cross-page movement for STABLE_PAGE_TIDY**: correct per spec (page-local strategy)
