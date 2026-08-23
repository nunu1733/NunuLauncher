# CI Test Portfolio and Runtime Baseline

> Status: Implemented
> Scope: Issue #96 source-changing pull-request CI
> Updated: 2026-08-21

この文書は、source-changing Pull Request における CI テストの所有範囲、実行理由、および実測した時間の正本である。テストを短縮する目的は coverage を削ることではなく、同等の回帰検出能力、API 互換性、および状態隔離を保ったまま、不要な直列待機を除くことである。[1] [2]

## Baseline

基準値は、Issue #53 を含む PR #95 の成功した `pull_request` event の CI 実行である。workflow は `2026-08-21T12:09:27Z` に開始し、`2026-08-21T12:24:41Z` に成功したため、最終 status までの wall time は **15分14秒** だった。単一の `organizer-instrumentation-tests` job が 14分33秒で最長 job であり、source-changing PR の critical path を支配していた。[3]

| Job / step | Wall time | 基準上の意味 |
|---|---:|---|
| `validate-repo-contract` | 0分18秒 | すべての PR に必要な repository contract 検証。 |
| `check-style` | 1分10秒 | source の整形・lint gate。 |
| `build-debug-apk` | 3分46秒 | GitHub Debug APK の独立した build evidence。 |
| `organizer-unit-tests` | 4分03秒 | organizer JVM contract/property regression evidence。 |
| `organizer-instrumentation-tests` | **14分33秒** | 最長の required connected-test lane。 |
| Issue #83 API 35 step | 6分52秒 | API 35 の production input seam evidence。 |
| Issue #52 API 36 step | 4分14秒 | manual organization の DB/apply/recovery と UI evidence。 |
| Issue #53 API 36 step | 2分47秒 | Launcher-host onboarding proposal evidence。 |

## Ownership and retained protection

| CI surface | 保護する契約・回帰 | PR での扱い | Issue #96 の判断 |
|---|---|---|---|
| `validate-repo-contract` | 文書リンク、Issue form、project contract | 全 PR | 維持する。 |
| `check-style` | source formatting | source-changing PR | 維持する。 |
| `build-debug-apk` | GitHub Debug APK の独立 buildability | source-changing PR | 維持する。artifact reuse の安全性が実証されるまで別 job のまま維持する。 |
| `organizer-unit-tests` | pure planner/application の contract/property regression | source-changing PR | 維持する。 |
| `organizer-instrumentation-shared-writer-tests` | shared-writer seam の coordinator 回帰。Issue #113 の `MODEL_WRITER` self-deadlock regression (`ModelWriterTransactionReentryTest`)、lease admission/FIFO (`LayoutWriteCoordinatorTest`)、Binder future (`BinderOperationFutureTest`) を API 36 emulator で常設実行する | source-changing PR | PR #114 レビューで追加。それまで coordinator suite は compile-only であり、#60 の回帰逃しと同種の escape を許していた。isolated fixture DB のみを使い、UI lane の app state とは独立した clean emulator を与える。 |
| `organizer-instrumentation-db-migration-tests` | schema upgrade/downgrade の transaction ownership 失敗意味論。Issue #118 の poisoning regression (`MigrationTransactionOwnershipTest`) と #14 由来の schema-33 fixture (`DatabaseHelperSchema33Test`, `DowngradeSchema33Test`, `InactiveGridDbNormalizationTest`) を API 36 emulator で常設実行する | source-changing PR | Issue #118 で追加。#115 が、DB migration suite が compile-only のまま実 platform 契約の下で失敗し続けていたことを実証したのと同じ escape を防ぐ。isolated fixture DB のみを使い、UI lane の app state とは独立した clean emulator を与える。 |
| `organizer-instrumentation-api35-tests` | `ProductionOrganizationInputComposer` と実 platform evidence adapter の互換性 | source-changing PR | **API 35 lane を維持する**。この compatibility requirement を API 36 に置換できる根拠はまだない。[4] |
| `organizer-instrumentation-issue52-tests` | production Launcher DB capture、apply、recovery、UI/recreation | source-changing PR | 維持する。fresh fixture/recovery cleanup を伴う高リスク evidence である。[5] |
| `organizer-instrumentation-issue53-tests` | 実際の Launcher host における onboarding proposal、Back、focus、recreation、Review admission | source-changing PR | 維持する。Issue #52 と同じ app/process state では実行しない。[6] |

## Implemented orchestration change

`.github/workflows/**` の変更を専用 `ci` path filter として扱い、通常の source path と同じ build、JVM、connected-test jobs を起動する。workflow-only PR が更新した command、job dependency、path filter、自動 emulator provisioning を検証なしに merge gate へ導入することを防ぐ。

Issue #83、#52、#53 の focused connected suite を、共通の `changes` gate の後に開始する**独立した emulator job**へ分割する。各 job は own checkout、Gradle setup、KVM setup、emulator provisioning、target-app state、instrumentation process、failure report artifact を持つ。したがって #52 の database-heavy fixture state を #53 が引き継がず、#53 の Launcher-owned proposal state も #52 の高リスク DB evidence を汚染しない。[5] [6]

この選択は、共有 emulator の package-state reset が active launcher package で許可されない CI image でも、clean-state isolation を推測で補うことなく実行できる。最適化対象は emulator provisioning の**直列待機**であり、テスト class、API baseline、Gradle class filter、必要な evidence は削除または統合しない。

> Issue #52 と #53 を同一の dirty process や同一 target-app state で実行しない。各 suite は新しい emulator 上で開始する。

## Deferred candidates

| Candidate | 現時点の判断 | 再評価に必要な証拠 |
|---|---|---|
| API 35 と API 36 の統合 | 保留 | API 35 固有の production adapter compatibility を API 36 の evidence で代替できる受入済み根拠。 |
| `build-debug-apk` と connected lane の artifact reuse | 保留 | variant、signing、test APK、installation inputs と failure artifact が同等であることの再現可能な検証。 |
| source path filter の狭小化 | 保留 | organizer shared Launcher surface を見落とさない conservative な path-to-contract inventory。 |
| test/check の削除 | 実施しない | 同じ failure mode を適切な layer で検出する、明示的で reviewable な coverage equivalence。 |
| 共有 emulator / 異なる class filter の Gradle invocation 統合 | 実施しない | Android Test Orchestrator 等により、suite 間の deterministic isolation と failure attribution を実証する設計。 |

## Post-change measurement result

PR #97 の `pull_request` event CI は commit `c4a0727276db40634b7fc3d4f6f7d364b3da2d37` で成功した。workflow は `2026-08-21T14:09:01Z` から `2026-08-21T14:18:03Z` までの **9分02秒** で完了した。これは `.github/workflows/ci.yml` を含む CI configuration change であり、`ci` path filter により build、JVM、3つの focused connected-test job の全てが skip されずに成功した。[7]

| Metric | PR #95 baseline | PR #97 result | Difference | 判定 |
|---|---:|---:|---:|---|
| Critical-path wall time | 15分14秒 | **9分02秒** | **6分12秒短縮（40.7%）** | Pass |
| Connected critical path | 14分33秒 | **8分37秒**（Issue #52） | **5分56秒短縮（40.7%）** | Pass |
| Total runner work | 24分20秒 | 33分10秒 | **8分50秒増加（36.3%）** | wall-clock 改善と引き換えに増加。明示的に受容し、追加最適化の対象とする。 |
| Required focused evidence | #83 API 35、#52 API 36、#53 API 36 | 3つの独立 job がすべて success | coverage 削除なし | Pass |
| State isolation | 単一 job 内で順次実行 | clean emulator を持つ別 job | #52/#53 の process・target state を非共有 | Pass |

この結果は critical path の material reduction を示す一方、emulator/job を3つに分けたことで total runner work は増えている。したがって、API 35 consolidation、artifact reuse、path-filter narrowing、または test removal をこの結果だけで正当化しない。これらは各々に追加の coverage/isolation evidence を必要とする。

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/96 "Issue #96 — Audit and reduce CI test/runtime overhead after #53"
[2]: ../../AGENTS.md "Repository work rules — test and isolation requirements"
[3]: https://github.com/nunu1733/NunuLauncher/actions/runs/32480533543 "PR #95 successful CI run — baseline"
[4]: ../../specs/83-production-organization-input-sources/plan.md "Issue #83 plan — API 35 production input evidence"
[5]: ../../specs/52-manual-full-organization-vertical-slice/plan.md "Issue #52 plan — high-risk connected evidence and fixture restoration"
[6]: ../../specs/53-onboarding-organization-proposal/plan.md "Issue #53 plan — onboarding isolation and connected acceptance evidence"
[7]: https://github.com/nunu1733/NunuLauncher/actions/runs/32490574667 "PR #97 successful CI run — post-change measurement"
