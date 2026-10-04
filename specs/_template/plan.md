# Implementation Plan: <outcome>

> Issue: #<number>
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- 関連code pathと現在の振る舞い。
- 再現command、fixture、log、upstream commit。
- 推測と確認済み事実を分ける。

## Design

### Modules and interfaces

- 変更するmoduleとinterface。
- seamとproduction/test adapter。
- interfaceの外へ漏らさないcomplexity。

### Data flow

入力、状態遷移、出力、errorを簡潔に記載する。

### Alternatives rejected

実際に比較した代替だけを書く。変更困難な判断になった場合はADRを作る。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| | | |

## Migration and recovery

- schema/rule migration。
- failure中のrollback。
- release rollback/downgrade。
- backup/restore compatibility。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | | |

含めるべき観点: unit/contract、property、DB/integration、UI/accessibility、performance、failure injection。

## Documentation updates

- [ ] spec status/history
- [ ] CONTEXT.md（domain language変更時）
- [ ] DESIGN.md（system structure変更時）
- [ ] ADR（3条件を満たす判断時）
- [ ] AGENTS.md（workflow/verified command変更時）

## Execution checklist

- [ ] Current behavior reproduced.
- [ ] Tests fail for the missing behavior.
- [ ] Minimal implementation completed.
- [ ] Migration/recovery verified.
- [ ] Full relevant verification completed.
- [ ] PR evidence and remaining risks recorded.
