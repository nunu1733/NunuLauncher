# Product Brief

> Status: Draft
> Updated: 2026-08-21

## Vision

ユーザーがホーム画面を継続的に手入れしなくても、重要な配置を守りながら、予測可能で説明可能な状態に保てるAndroid launcherを提供する。

## User problem

- アプリ追加によりホーム画面が散らかり、目的のアプリが見つけにくくなる。
- 手動整理は判断と反復操作が多く、後回しになりやすい。
- launcherによる自動変更は、位置を覚えたユーザーにとって不安と混乱を生む。
- 一般的なカテゴリ分類だけでは、個人の優先順位や固定したいwidget/folderを表現できない。

## Target users

- 多数のアプリを導入し、標準的なgrid homeを使うAndroidユーザー。
- 完全自動化より、予測可能なruleと例外指定を重視するユーザー。
- 自分またはAIでruleを編集・共有したいpower user。

## Value proposition

NunuLauncherは、ローカルrule、ユーザーoverride、ロック配置を使い、変更前に説明可能なplanを作る。新規アプリにも同じruleを適用し、必要なら直前の状態へ戻せる。

## Product principles

1. **Safety before neatness** — 並びの良さより既存layoutと復旧可能性を優先する。
2. **User intent wins** — 明示的なlockとoverrideは推定より優先する。
3. **Predictable automation** — 同じ条件では同じ結果と理由を返す。
4. **Local first** — 通常動作はofflineで完結し、外部送信は明示的なopt-inとする。
5. **Progressive control** — defaultは簡単に使え、必要なユーザーだけrule詳細へ進める。
6. **Upstream sustainable** — launcher本体の品質を上流から取り込み続けられる差分にする。

## MVP outcome

MVPでは、選択された対象集合について安全な全体整理planを提示・適用・復旧でき、ロック配置を守ることを目指す。手動全体整理とonboardingからのreviewは、preview、explicit confirmation、stale-safe apply、recoveryを伴う。

[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)のOption Bにより、package eventによる新規アプリのincremental placementはMVPの成果条件に含めない。これはFR-008/FR-009のLater/deferred capabilityであり、将来のaccepted product decision、authoritative prior-absence evidence、およびspecが成立するまで開始しない。

詳細なカテゴリ学習、外部LLM、rule共有marketplace、高度な美観最適化、package eventによるincremental placementはMVPの成果条件にしない。

## Non-goals

- iOS home配置の自動変更。
- 常時context監視による動的なページ入替。
- root/accessibility automationによる他launcherの直接操作。
- online LLMがなければ動作しない分類。
- Lawnchair/Launcher3全体のUI framework置換。
- 全ユーザーに同じ「最適」layoutを強制すること。

## Success measures

数値はmeasurement Issueで確定する。少なくとも以下を計測可能にする。

- 適用後にrecoveryが必要になったrunの割合。
- planが適用不能または未配置itemを残した割合と理由。
- 新規アプリが追加から所定時間内に期待する場所へ配置された割合（FR-008/FR-009のLater/deferred capabilityのmeasurementであり、MVP success criterionではない）。
- 同一入力でplan hashが一致する割合。
- crash、layout破損、復旧失敗の件数。
- ユーザーが手動で再移動したitemの割合（収集する場合はprivacy review必須）。
