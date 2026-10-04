# Source→replay対応表（plan §4.1 S0終了条件）

> Status: draft（S0初版。plan §4.1の「元300件→replayed commitの一対一対応」に対応）
> 元300件: `git rev-list --first-parent --reverse 505dbc40e6154c05158b5d0271c45f6a885a411b..38262fb74d144f4655dfbf01c0084e44c86560be`
> replayed commit: `git rev-list --reverse 43a21b43d7cc7850ab54e14b1a57dc9646685f35..8b35f1ff7ae35bca8e9e09f9650435f68d5c44a6` の最初の300件（first-parent時系列indexで一対一対応）
> 単位番号・分類（A/B/C）の正本は [replay-log.md](./replay-log.md) §2。

| # | 元commit | 元件名 | replay commit | replay件名 |
|---|---|---|---|---|
| 001 | `40e437943c` | docs(project): bootstrap issue-driven development (#7) | `9bcae934f0` | PR #7: docs(project): bootstrap issue-driven development (#7) |
| 002 | `39a1819785` | ci(project): harden fork repository contracts (#19) | `de5b74e13e` | PR #19: ci(project): harden fork repository contracts (#19) |
| 003 | `2ab2ac7c71` | docs(assessment): audit Lawnchair 15 Deck layout (#18) | `ea9f283b48` | PR #18: docs(assessment): audit Lawnchair 15 Deck layout (#18) |
| 004 | `c5747fb52f` | docs(assessment): record Android emulator smoke baseline (#20) | `66a1a47abd` | PR #20: docs(assessment): record Android emulator smoke baseline (#20) |
| 005 | `6f5c12f5c8` | docs(project): synchronize post-bootstrap issue map (#21) | `a1b40c87dc` | PR #21: docs(project): synchronize post-bootstrap issue map (#21) |
| 006 | `7117d1b98c` | Merge pull request #25 from nunu1733/issue-3-item-preservation-policy | `d36ff566f8` | PR #25: Merge pull request #25 from nunu1733/issue-3-item-preservation-policy |
| 007 | `26d7ef082a` | Merge pull request #26 from nunu1733/issue-4-organization-run-ux | `b848be2c20` | PR #26: Merge pull request #26 from nunu1733/issue-4-organization-run-ux |
| 008 | `e254bffcb0` | Merge pull request #28 from nunu1733/issue-6-category-taxonomy-v1 | `cf23597318` | PR #28: Merge pull request #28 from nunu1733/issue-6-category-taxonomy-v1 |
| 009 | `4766833f2b` | Merge pull request #27 from nunu1733/issue-5-layout-strategy-v1 | `43e9bb6d43` | PR #27: Merge pull request #27 from nunu1733/issue-5-layout-strategy-v1 |
| 010 | `05bee5363d` | Merge pull request #29 from nunu1733/codex/issue-10-planning-contract | `b795af2758` | PR #29: Merge pull request #29 from nunu1733/codex/issue-10-planning-contract |
| 011 | `93517aaf22` | Merge pull request #30 from nunu1733/codex/issue-11-planner-harness | `b1615b3a8f` | PR #30: Merge pull request #30 from nunu1733/codex/issue-11-planner-harness |
| 012 | `4dd8ee7a9d` | Merge pull request #32 from nunu1733/codex/issue-31-already-canonical | `4c0f1fb6d4` | PR #32: Merge pull request #32 from nunu1733/codex/issue-31-already-canonical |
| 013 | `2fe774b4f3` | Merge pull request #34 from nunu1733/codex/issue-33-page-order | `0ad09ad6ba` | PR #34: Merge pull request #34 from nunu1733/codex/issue-33-page-order |
| 014 | `e08ede6f8e` | Merge pull request #35 from nunu1733/codex/issue-12-stage-a | `3c0969b237` | PR #35: Merge pull request #35 from nunu1733/codex/issue-12-stage-a |
| 015 | `69066e7ca3` | Implement deterministic layout planner v1 (#36) | `532c0bdd10` | PR #36: Implement deterministic layout planner v1 (#36) |
| 016 | `228cc0c5b6` | Specify safe layout application and recovery (#37) | `36fb8208d0` | PR #37: Specify safe layout application and recovery (#37) |
| 017 | `8788cb7f97` | Decide organizer lock persistence (#39) | `24619d1ede` | PR #39: Decide organizer lock persistence (#39) |
| 018 | `866d231ffd` | Merge pull request #40 from nunu1733/codex/issue-14-safe-application-plan | `4d415873ae` | PR #40: Merge pull request #40 from nunu1733/codex/issue-14-safe-application-plan |
| 019 | `090e3a4910` | Implement transactional layout application and recovery (#47) | `0cd5775790` | PR #47: Implement transactional layout application and recovery (#47) |
| 020 | `d50a79d261` | Prevent silent planner allocation drops (#45) | `610a62893a` | PR #45: Prevent silent planner allocation drops (#45) |
| 021 | `f97343aa74` | Add reproducible planner stress coverage | `a8ff43c31e` | Direct: Add reproducible planner stress coverage |
| 022 | `46f2319976` | Record remote planner stress evidence | `187fe3ae9f` | PR #-: Record remote planner stress evidence |
| 023 | `918f79ddcd` | Run organizer unit tests as a CI gate (#61) | `2c02b23eac` | PR #61: Run organizer unit tests as a CI gate (#61) |
| 024 | `215c444136` | Merge pull request #62 from nunu1733/codex/issue-44-audit | `786ae79be5` | PR #62: Merge pull request #62 from nunu1733/codex/issue-44-audit |
| 025 | `ad4f213109` | Merge pull request #63 from nunu1733/codex/issue-43-high-risk-gate | `5df222d825` | PR #63: Merge pull request #63 from nunu1733/codex/issue-43-high-risk-gate |
| 026 | `c94356e8db` | Merge pull request #65 from nunu1733/docs/42-sync-design-backlog | `d7471f4c48` | PR #65: Merge pull request #65 from nunu1733/docs/42-sync-design-backlog |
| 027 | `dc5d072840` | Merge pull request #66 from nunu1733/issue-16-organizer-diagnostics | `34c005f782` | PR #66: Merge pull request #66 from nunu1733/issue-16-organizer-diagnostics |
| 028 | `1ad4f2a385` | Merge pull request #68 from nunu1733/issue-15-performance-budgets | `1d47c8c23f` | PR #68: Merge pull request #68 from nunu1733/issue-15-performance-budgets |
| 029 | `6bfad79bd9` | Merge pull request #70 from nunu1733/issue-15-perf-budgets-followup | `ae3a02475e` | PR #70: Merge pull request #70 from nunu1733/issue-15-perf-budgets-followup |
| 030 | `98ccdb24ea` | Merge pull request #69 from nunu1733/issue-24-empty-folder-policy | `cd1b7d053a` | PR #69: Merge pull request #69 from nunu1733/issue-24-empty-folder-policy |
| 031 | `bea108b92c` | docs: decide fail-closed fresh-install provenance (#71) | `7307d37181` | PR #71: docs: decide fail-closed fresh-install provenance (#71) |
| 032 | `9090874532` | Define Deck runtime retirement decision and contract (#72) | `47d3035654` | PR #72: Define Deck runtime retirement decision and contract (#72) |
| 033 | `818c90a82e` | Merge pull request #73 from nunu1733/issue-38-lock-authoring-unknown-review | `912dac246c` | PR #73: Merge pull request #73 from nunu1733/issue-38-lock-authoring-unknown-review |
| 034 | `a0f3d28e0d` | Merge pull request #74 from nunu1733/issue-38-spec-status | `65f7b97b89` | PR #74: Merge pull request #74 from nunu1733/issue-38-spec-status |
| 035 | `72322c3f91` | Merge pull request #75 from nunu1733/issue-59-preserve-source-grid-migration-failure | `5c5e1d3b40` | PR #75: Merge pull request #75 from nunu1733/issue-59-preserve-source-grid-migration-failure |
| 036 | `cfc665c19c` | Merge pull request #76 from nunu1733/issue-59-spec-status | `afe58e435c` | PR #76: Merge pull request #76 from nunu1733/issue-59-spec-status |
| 037 | `3663f3157d` | Merge pull request #77 from nunu1733/issue-58-serialize-runtime-restores | `e215ca69dd` | PR #77: Merge pull request #77 from nunu1733/issue-58-serialize-runtime-restores |
| 038 | `187c7bfc54` | Merge pull request #78 from nunu1733/issue-60-executor-writer-admission | `416c1d745b` | PR #78: Merge pull request #78 from nunu1733/issue-60-executor-writer-admission |
| 039 | `8bb16367ca` | Merge pull request #79 from nunu1733/issue-57-deck-runtime-retirement | `570b1eab17` | PR #79: Merge pull request #79 from nunu1733/issue-57-deck-runtime-retirement |
| 040 | `1d55fe4479` | Merge pull request #80 from nunu1733/issue-67-organizer-diagnostics-spec | `d73e9ec974` | PR #80: Merge pull request #80 from nunu1733/issue-67-organizer-diagnostics-spec |
| 041 | `082fce5145` | Merge pull request #82 from nunu1733/issue-67-organizer-diagnostics | `eae258033e` | PR #82: Merge pull request #82 from nunu1733/issue-67-organizer-diagnostics |
| 042 | `bc60ee52e2` | Merge pull request #87 from nunu1733/issue-86-authoritative-policy-sources | `9af3d0fbf5` | PR #87: Merge pull request #87 from nunu1733/issue-86-authoritative-policy-sources |
| 043 | `16bdf6ec29` | Merge pull request #88 from nunu1733/issue-83-stage-a-input-sources | `01fdd13a7a` | PR #88: Merge pull request #88 from nunu1733/issue-83-stage-a-input-sources |
| 044 | `4adb0411d8` | Merge pull request #90 from nunu1733/issue-89-inspection-safe-recovery-store-read | `42ab26d052` | PR #90: Merge pull request #90 from nunu1733/issue-89-inspection-safe-recovery-store-read |
| 045 | `884b8f5544` | Merge pull request #91 from nunu1733/docs/issue-89-close-and-84-resume | `331bd388e0` | PR #91: Merge pull request #91 from nunu1733/docs/issue-89-close-and-84-resume |
| 046 | `a6bf5024e8` | Merge pull request #92 from nunu1733/issue-84-recovery-preview-seam | `345790a444` | PR #92: Merge pull request #92 from nunu1733/issue-84-recovery-preview-seam |
| 047 | `c7cbfa4789` | Merge pull request #93 from nunu1733/docs/issue-84-completion | `d1f7d16cc2` | PR #93: Merge pull request #93 from nunu1733/docs/issue-84-completion |
| 048 | `836ccff7d9` | Merge pull request #94 from nunu1733/issue-52-manual-full-organization-vertical-slice | `a1f79ee4d4` | PR #94: Merge pull request #94 from nunu1733/issue-52-manual-full-organization-vertical-slice |
| 049 | `78a1eb687c` | Merge pull request #95 from nunu1733/issue-53-onboarding-organization-proposal | `cc0c5e757c` | PR #95: Merge pull request #95 from nunu1733/issue-53-onboarding-organization-proposal |
| 050 | `9480f75332` | Merge pull request #98 from nunu1733/issue-85-mvp-incremental-deferral | `d396bb1d10` | PR #98: Merge pull request #98 from nunu1733/issue-85-mvp-incremental-deferral |
| 051 | `cfa8c69b92` | Merge pull request #97 from nunu1733/issue-96-ci-test-runtime-audit | `a109f0ab41` | PR #97: Merge pull request #97 from nunu1733/issue-96-ci-test-runtime-audit |
| 052 | `dc84b77147` | feat(organizer): finalize Issue #99 category override authoring | `d847bb7c48` | Direct: feat(organizer): finalize Issue #99 category override authoring |
| 053 | `4f09eea892` | test: close diagnostics residual evidence (#107) | `71a2d773e1` | PR #107: test: close diagnostics residual evidence (#107) |
| 054 | `4ec0eb3dc6` | docs: reconcile MVP release readiness (#111) | `99b2e0af8a` | PR #111: docs: reconcile MVP release readiness (#111) |
| 055 | `d16a1efa32` | fix: add bounded MODEL_WRITER same-thread re-entry to prevent folder-bind self-deadlock (#114) | `93d7892f8b` | PR #114: fix: add bounded MODEL_WRITER same-thread re-entry to prevent folder-bind self-deadlock (#114) |
| 056 | `f337fb9f7a` | fix: keep OrganizationEntry stable for minified preference navigation (#121) | `91780e0131` | PR #121: fix: keep OrganizationEntry stable for minified preference navigation (#121) |
| 057 | `93889336e7` | fix: make SQLite migration transaction ownership deterministic (#122) | `cc08d00f64` | PR #122: fix: make SQLite migration transaction ownership deterministic (#122) |
| 058 | `79c1a7db6f` | docs: mark spec 118 implemented | `24e4b97a57` | PR #-: docs: mark spec 118 implemented |
| 059 | `880d489a8f` | docs(issue-110): establish upstream patch-surface baseline (#112) | `bf8615e885` | PR #112: docs(issue-110): establish upstream patch-surface baseline (#112) |
| 060 | `903800a1d4` | test: correct nested transaction whole-unit rollback contract (#124) | `3f1eaf6b31` | PR #124: test: correct nested transaction whole-unit rollback contract (#124) |
| 061 | `ac63565f2a` | test: make reload supersession deterministic (#125) | `20982c92e8` | PR #125: test: make reload supersession deterministic (#125) |
| 062 | `44b25d325a` | test: cover production-style restore file replacement (#126) | `ecadffca9d` | PR #126: test: cover production-style restore file replacement (#126) |
| 063 | `f381d26b05` | Revert "test: cover production-style restore file replacement (#126)" (#127) | `a64ef650fc` | PR #127: Revert "test: cover production-style restore file replacement (#126)" (#127) |
| 064 | `51940f3dfc` | test: cover production-style restore file replacement (#128) | `67151da567` | PR #128: test: cover production-style restore file replacement (#128) |
| 065 | `8316333347` | fix: compose cross-profile classification evidence through LauncherApps | `491da116d9` | PR #-: fix: compose cross-profile classification evidence through LauncherApps |
| 066 | `534d0f32db` | fix: preserve two-panel orientation in organizer canonical capture (#130) (#133) | `4391303d06` | PR #133: fix: preserve two-panel orientation in organizer canonical capture (#130) (#133) |
| 067 | `05329be2d7` | Merge pull request #135 from nunu1733/issue-108-organizer-compatibility-matrix | `34f2fad7b5` | PR #135: Merge pull request #135 from nunu1733/issue-108-organizer-compatibility-matrix |
| 068 | `eb682a0e66` | Merge pull request #139 from nunu1733/codex/issue-132-dogfooding | `ae54531daa` | PR #139: Merge pull request #139 from nunu1733/codex/issue-132-dogfooding |
| 069 | `ad4bab004f` | Merge pull request #140 from nunu1733/issue-136-default-layout-planning-rejection | `c0b72000fc` | PR #140: Merge pull request #140 from nunu1733/issue-136-default-layout-planning-rejection |
| 070 | `cd2cfaac8f` | Merge pull request #143 from nunu1733/issue-134-grid-preset-snapshot | `cba89a2ac2` | PR #143: Merge pull request #143 from nunu1733/issue-134-grid-preset-snapshot |
| 071 | `8c5b0d5d25` | docs(issue-137): draft spec and plan for proposal touch activation | `26f369c429` | PR #-: docs(issue-137): draft spec and plan for proposal touch activation |
| 072 | `cd49710af2` | docs(issue-137): address spec/plan review findings | `b944680d5a` | PR #-: docs(issue-137): address spec/plan review findings |
| 073 | `8f1519e873` | Merge pull request #144 from nunu1733/issue-137-proposal-touch-activation | `26ec9a957b` | PR #144: Merge pull request #144 from nunu1733/issue-137-proposal-touch-activation |
| 074 | `33e2b8edf1` | docs(issue-137): mark spec implemented after PR #144 merge | `499c94c5cb` | PR #-: docs(issue-137): mark spec implemented after PR #144 merge |
| 075 | `d35604411d` | Merge pull request #145 from nunu1733/codex/issue-138-stage-a | `d1e1283638` | PR #145: Merge pull request #145 from nunu1733/codex/issue-138-stage-a |
| 076 | `1efcf1fd78` | docs(issue-138): mark spec implemented after PR #145 merge | `cc82555d5d` | PR #-: docs(issue-138): mark spec implemented after PR #145 merge |
| 077 | `718995320c` | Merge pull request #147 from nunu1733/issue-146-release-apk-workflow | `0652db7867` | PR #147: Merge pull request #147 from nunu1733/issue-146-release-apk-workflow |
| 078 | `fd6ed98f92` | Merge pull request #149 from nunu1733/issue-142-proposal-resume-choreography-flake | `db64c9fb6c` | PR #149: Merge pull request #149 from nunu1733/issue-142-proposal-resume-choreography-flake |
| 079 | `74c2156767` | Merge pull request #148 from nunu1733/issue-141-app-pair-snap-position | `3b1dea0c68` | PR #148: Merge pull request #148 from nunu1733/issue-141-app-pair-snap-position |
| 080 | `7ba2194ce7` | Merge pull request #151 from nunu1733/issue-104-105-106-device-evidence | `372959dc1e` | PR #151: Merge pull request #151 from nunu1733/issue-104-105-106-device-evidence |
| 081 | `a84cbb7451` | Merge pull request #154 from nunu1733/issue-123-organizer-ui-convergence | `eb254aa34c` | PR #154: Merge pull request #154 from nunu1733/issue-123-organizer-ui-convergence |
| 082 | `d1f89246d4` | docs(spec-123): mark spec/plan implemented after PR #154 merge | `9f82bf718a` | PR #-: docs(spec-123): mark spec/plan implemented after PR #154 merge |
| 083 | `2d811b701c` | Merge pull request #158 from nunu1733/issue-155-qsb-reservation-spec-plan | `83b973ab55` | PR #158: Merge pull request #158 from nunu1733/issue-155-qsb-reservation-spec-plan |
| 084 | `6fd276b50d` | Merge pull request #157 from nunu1733/issue-156-hotseat-tokenless-writer-deferral | `b58efc24aa` | PR #157: Merge pull request #157 from nunu1733/issue-156-hotseat-tokenless-writer-deferral |
| 085 | `fd3dad799d` | Merge pull request #159 from nunu1733/docs/issue-156-implemented | `a3ed860def` | PR #159: Merge pull request #159 from nunu1733/docs/issue-156-implemented |
| 086 | `8740f8c136` | Merge pull request #160 from nunu1733/codex/issue-150-a7-verification-diagnosis | `caaa140b4a` | PR #160: Merge pull request #160 from nunu1733/codex/issue-150-a7-verification-diagnosis |
| 087 | `0a43f616b4` | docs(spec-150): mark spec/plan implemented after PR 160 merge | `89a44e0231` | PR #-: docs(spec-150): mark spec/plan implemented after PR 160 merge |
| 088 | `c68abcce62` | Merge pull request #162 from nunu1733/docs/issue-104-point-origin-runid-evidence | `310691ec02` | PR #162: Merge pull request #162 from nunu1733/docs/issue-104-point-origin-runid-evidence |
| 089 | `92a490a2f8` | feat(localization): complete Issue 161 Japanese UI copy LQA | `6e21b9dd32` | PR #-: feat(localization): complete Issue 161 Japanese UI copy LQA |
| 090 | `5b00c46b69` | Merge pull request #165 from nunu1733/docs/issue-164-new-folder-a7-item-order | `811ca6f74c` | PR #165: Merge pull request #165 from nunu1733/docs/issue-164-new-folder-a7-item-order |
| 091 | `090d3efdc5` | docs(spec-164): mark spec/plan implemented after PR 165 merge | `6afba12f3e` | PR #-: docs(spec-164): mark spec/plan implemented after PR 165 merge |
| 092 | `3b079da0a8` | docs(assessment): issue 167 Nova two-pass restore investigation — repro, root cause, fix issue 168 | `e270714d95` | PR #-: docs(assessment): issue 167 Nova two-pass restore investigation — repro, root cause, fix issue 168 |
| 093 | `343037ce6e` | docs(assessment): separate confirmed evidence from hypothesis in issue 167 findings | `c2710dbd70` | PR #-: docs(assessment): separate confirmed evidence from hypothesis in issue 167 findings |
| 094 | `7c81d7ee56` | Merge pull request #169 from nunu1733/fix/issue-168-nova-restore-authoritative | `d2bb0ce84a` | PR #169: Merge pull request #169 from nunu1733/fix/issue-168-nova-restore-authoritative |
| 095 | `afb7618144` | docs(spec-168): mark spec/plan implemented after PR 169 merge | `9b3404b535` | PR #-: docs(spec-168): mark spec/plan implemented after PR 169 merge |
| 096 | `0e442f7d03` | Merge pull request #173 from nunu1733/docs/issue-171-investigation-assessment | `49281a096e` | PR #173: Merge pull request #173 from nunu1733/docs/issue-171-investigation-assessment |
| 097 | `4712124d40` | Merge pull request #175 from nunu1733/docs/issue-174-stage-a-spec-plan | `1b584ecb54` | PR #175: Merge pull request #175 from nunu1733/docs/issue-174-stage-a-spec-plan |
| 098 | `de2d33f551` | Merge pull request #176 from nunu1733/docs/issue-174-implemented | `09ba235de2` | PR #176: Merge pull request #176 from nunu1733/docs/issue-174-implemented |
| 099 | `647b122d86` | Merge pull request #179 from nunu1733/docs/issue-152-spec-plan | `fe0f537b62` | PR #179: Merge pull request #179 from nunu1733/docs/issue-152-spec-plan |
| 100 | `256fb6525d` | Merge pull request #180 from nunu1733/fix/152-reload-model-snapshot | `52600ea6a2` | PR #180: Merge pull request #180 from nunu1733/fix/152-reload-model-snapshot |
| 101 | `ff46fa20dc` | Merge pull request #183 from nunu1733/issue-166-recovery-tombstone-lockout | `4cce7cf2d3` | PR #183: Merge pull request #183 from nunu1733/issue-166-recovery-tombstone-lockout |
| 102 | `50ddb86148` | Merge pull request #184 from nunu1733/issue-172-spec-plan | `3bc2b239b8` | PR #184: Merge pull request #184 from nunu1733/issue-172-spec-plan |
| 103 | `ac09d27bae` | docs(assessment): issue-172 AC-3 reproduction (CAPTURE_INVALID root cause: QSB-row reservation overlap; fix split to #185) and spec implemented | `be6da2e299` | PR #-: docs(assessment): issue-172 AC-3 reproduction (CAPTURE_INVALID root cause: QSB-row reservation overlap; fix split to #185) and spec implemented |
| 104 | `667e8915f2` | Merge pull request #186 from nunu1733/issue-185-implementation | `959e4a68f3` | PR #186: Merge pull request #186 from nunu1733/issue-185-implementation |
| 105 | `1e7781f221` | Merge pull request #190 from nunu1733/docs/issue-153-spec-plan | `f99143b0a5` | PR #190: Merge pull request #190 from nunu1733/docs/issue-153-spec-plan |
| 106 | `050987e9a6` | Merge pull request #188 from nunu1733/docs/issue-187-spec-plan | `55d1399ed6` | PR #188: Merge pull request #188 from nunu1733/docs/issue-187-spec-plan |
| 107 | `0e0bcae9d3` | Merge pull request #189 from nunu1733/issue-177-smoke-reconcile-settle | `de62739c7a` | PR #189: Merge pull request #189 from nunu1733/issue-177-smoke-reconcile-settle |
| 108 | `c47c9d9464` | Merge pull request #191 from nunu1733/issue-181-audit-followups | `83f69291a6` | PR #191: Merge pull request #191 from nunu1733/issue-181-audit-followups |
| 109 | `b494cd09e7` | docs(192): organizer concrete change preview investigation record (#196) | `0ff029548a` | PR #196: docs(192): organizer concrete change preview investigation record (#196) |
| 110 | `f8b1626c14` | Merge pull request #193 from nunu1733/issue-178-gate-criteria-grammar | `7fc7c09158` | PR #193: Merge pull request #193 from nunu1733/issue-178-gate-criteria-grammar |
| 111 | `f465067699` | Merge pull request #197 from nunu1733/issue-194-plan-preview-seam | `3901fdab19` | PR #197: Merge pull request #197 from nunu1733/issue-194-plan-preview-seam |
| 112 | `2af9450a69` | Merge pull request #198 from nunu1733/issue-195-organizer-confirmation-change-list | `58d243079c` | PR #198: Merge pull request #198 from nunu1733/issue-195-organizer-confirmation-change-list |
| 113 | `a849ee3ea3` | Merge pull request #200 from nunu1733/codex/issue-199-ux-visual-review | `1c5788e669` | PR #200: Merge pull request #200 from nunu1733/codex/issue-199-ux-visual-review |
| 114 | `5fdab48082` | Merge pull request #202 from nunu1733/issue-201-generated-folder-semantic-naming | `6b7d1410f0` | PR #202: Merge pull request #202 from nunu1733/issue-201-generated-folder-semantic-naming |
| 115 | `05f37443a4` | Merge pull request #207 from nunu1733/issue-182-layout-strategy-catalog | `d624f581a8` | PR #207: Merge pull request #207 from nunu1733/issue-182-layout-strategy-catalog |
| 116 | `c50ec0cea1` | Merge pull request #221 from nunu1733/issue-213-canonical-strategy-extraction | `c3efbd992d` | PR #221: Merge pull request #221 from nunu1733/issue-213-canonical-strategy-extraction |
| 117 | `10c74b89b4` | Merge pull request #222 from nunu1733/issue-214-stable-page-tidy-strategy | `aa4e05de74` | PR #222: Merge pull request #222 from nunu1733/issue-214-stable-page-tidy-strategy |
| 118 | `caf600aa6e` | Merge pull request #223 from nunu1733/issue-215-bottom-first-strategy | `b71bc6ad82` | PR #223: Merge pull request #223 from nunu1733/issue-215-bottom-first-strategy |
| 119 | `db96dd34de` | Merge pull request #224 from nunu1733/issue-216-global-compact-strategy | `a3ea5c761f` | PR #224: Merge pull request #224 from nunu1733/issue-216-global-compact-strategy |
| 120 | `b16390f5a7` | Merge pull request #225 from nunu1733/issue-217-category-contiguous-strategy | `ee1a922f3b` | PR #225: Merge pull request #225 from nunu1733/issue-217-category-contiguous-strategy |
| 121 | `d136027926` | Merge pull request #226 from nunu1733/issue-218-strategy-selection-ui | `3c219134f9` | PR #226: Merge pull request #226 from nunu1733/issue-218-strategy-selection-ui |
| 122 | `efeef4265b` | Merge pull request #227 from nunu1733/issue-219-cross-strategy-evidence | `f70d4098b2` | PR #227: Merge pull request #227 from nunu1733/issue-219-cross-strategy-evidence |
| 123 | `5b606b587c` | Merge pull request #229 from nunu1733/issue-182-editorial-cleanup | `025e6a6bf7` | PR #229: Merge pull request #229 from nunu1733/issue-182-editorial-cleanup |
| 124 | `5ec5e592ca` | docs(219): physical-device AC-14 evidence for all 5 strategies | `6b64ab943a` | PR #-: docs(219): physical-device AC-14 evidence for all 5 strategies |
| 125 | `bf1886d578` | Investigation (212): organizer destination verification — Case A verdict, characterization evidence (#236) | `0770e384c4` | PR #236: Investigation (212): organizer destination verification — Case A verdict, characterization evidence (#236) |
| 126 | `7eeb947efb` | feat(208): Organizer proposalの同名placement行を descriptor で区別する (#238) | `148e541288` | PR #238: feat(208): Organizer proposalの同名placement行を descriptor で区別する (#238) |
| 127 | `a84aab807f` | fix(209): Organizerの決定操作をbutton化し、ApplyとCancelをdecision時点で揃って視認できるようにする (#239) | `169085130d` | PR #239: fix(209): Organizerの決定操作をbutton化し、ApplyとCancelをdecision時点で揃って視認できるようにする (#239) |
| 128 | `38f9639163` | Report stale apply outcome with origin-aware wording (#210) (#240) | `31f87710c0` | PR #240: Report stale apply outcome with origin-aware wording (#210) (#240) |
| 129 | `65daff2978` | Mark spec 210 implemented after merge | `d09986275e` | PR #-: Mark spec 210 implemented after merge |
| 130 | `3e49ac6871` | Merge pull request #241 from nunu1733/agent/issue-237-global-compact-v2-spec | `b50c4ffc8b` | PR #241: Merge pull request #241 from nunu1733/agent/issue-237-global-compact-v2-spec |
| 131 | `24e11eb97d` | Merge pull request #243 from nunu1733/agent/issue-234-destination-anchor-specificity | `9f364f7d57` | PR #243: Merge pull request #243 from nunu1733/agent/issue-234-destination-anchor-specificity |
| 132 | `0f3d1d3a8f` | Merge pull request #244 from nunu1733/issue-230-restore-confirmation-context | `3901160854` | PR #244: Merge pull request #244 from nunu1733/issue-230-restore-confirmation-context |
| 133 | `d36b109e98` | Merge pull request #245 from nunu1733/issue-230-plan-review-fixes | `84904d6b67` | PR #245: Merge pull request #245 from nunu1733/issue-230-plan-review-fixes |
| 134 | `f16b1db0c1` | Merge pull request #246 from nunu1733/issue-230-restore-confirmation-impl | `87fdf3101b` | PR #246: Merge pull request #246 from nunu1733/issue-230-restore-confirmation-impl |
| 135 | `3e55507b53` | docs(230): record restore-confirmation-target implementation | `1c6f302b47` | PR #-: docs(230): record restore-confirmation-target implementation |
| 136 | `d0d25e51cc` | Merge pull request #254 from nunu1733/issue-248-fork-github-routing | `c3d43c2572` | PR #254: Merge pull request #254 from nunu1733/issue-248-fork-github-routing |
| 137 | `983657ee7e` | Merge pull request #255 from nunu1733/issue-249-main-merge-gate | `9d9b9bf151` | PR #255: Merge pull request #255 from nunu1733/issue-249-main-merge-gate |
| 138 | `4da41ef1bb` | Merge pull request #257 from nunu1733/issue-247-closing-keyword-lifecycle | `d345530c1c` | PR #257: Merge pull request #257 from nunu1733/issue-247-closing-keyword-lifecycle |
| 139 | `241fa3963c` | Merge pull request #258 from nunu1733/issue-251-worker-review-handoff | `8b08154e36` | PR #258: Merge pull request #258 from nunu1733/issue-251-worker-review-handoff |
| 140 | `72cfebc401` | Merge pull request #259 from nunu1733/issue-252-security-reporting | `0a6eed09b4` | PR #259: Merge pull request #259 from nunu1733/issue-252-security-reporting |
| 141 | `cf25735ff4` | docs(issue-250): reconcile document state integrity (#260) | `38c431b343` | PR #260: docs(issue-250): reconcile document state integrity (#260) |
| 142 | `2411c76082` | Merge pull request #261 from nunu1733/issue-242-bugreport-crash-evidence | `864faef5bc` | PR #261: Merge pull request #261 from nunu1733/issue-242-bugreport-crash-evidence |
| 143 | `0f86386921` | Report verified apply outcome in the completed tense (#231) (#262) | `1cc32c4990` | PR #262: Report verified apply outcome in the completed tense (#231) (#262) |
| 144 | `b25f20ca7c` | docs(231): mark spec 231 implemented after merge (#263) | `0b69c1c5ba` | PR #263: docs(231): mark spec 231 implemented after merge (#263) |
| 145 | `1f1ead86fd` | Name the tapped placement in the lock confirmation dialog (211) (#264) | `f0b7cc94fe` | PR #264: Name the tapped placement in the lock confirmation dialog (211) (#264) |
| 146 | `cc41f3bb1b` | docs(211): mark spec 211 implemented after merge (#266) | `b7bcb33eaf` | PR #266: docs(211): mark spec 211 implemented after merge (#266) |
| 147 | `2c80f2142c` | Merge pull request #267 from nunu1733/spec/issue-232-organizer-reentry-discoverability | `7ccb1b584c` | PR #267: Merge pull request #267 from nunu1733/spec/issue-232-organizer-reentry-discoverability |
| 148 | `3355b57165` | Merge pull request #268 from nunu1733/issue-232-organizer-reentry-discoverability | `17e7423ffc` | PR #268: Merge pull request #268 from nunu1733/issue-232-organizer-reentry-discoverability |
| 149 | `a96da39505` | Merge pull request #272 from nunu1733/docs/issue-232-spec-implemented | `7b7fbfc4e1` | PR #272: Merge pull request #272 from nunu1733/docs/issue-232-spec-implemented |
| 150 | `e7929c9a99` | Merge pull request #273 from nunu1733/issue-270-recovery-preview-capture-failure | `05933dd4be` | PR #273: Merge pull request #273 from nunu1733/issue-270-recovery-preview-capture-failure |
| 151 | `a38256976e` | Merge pull request #275 from nunu1733/docs/issue-270-spec-implemented | `f743f6e4ce` | PR #275: Merge pull request #275 from nunu1733/docs/issue-270-spec-implemented |
| 152 | `6b6bf8dd9f` | Merge pull request #274 from nunu1733/issue-269-folder-workspace-representability | `85022575b6` | PR #274: Merge pull request #274 from nunu1733/issue-269-folder-workspace-representability |
| 153 | `62a3c0a40a` | docs(233): spec and plan for multi-page backup restore preview (#277) | `bf650d9848` | PR #277: docs(233): spec and plan for multi-page backup restore preview (#277) |
| 154 | `69eb1c3e55` | Merge pull request #276 from nunu1733/issue-271-organizer-durable-status-projection | `5f1d4a1590` | PR #276: Merge pull request #276 from nunu1733/issue-271-organizer-durable-status-projection |
| 155 | `ed7ce5a205` | Merge pull request #279 from nunu1733/docs/issue-271-spec-implemented | `7edfbcff53` | PR #279: Merge pull request #279 from nunu1733/docs/issue-271-spec-implemented |
| 156 | `74b39d5361` | Merge pull request #280 from nunu1733/issue-265-spec-plan | `8891c30a81` | PR #280: Merge pull request #280 from nunu1733/issue-265-spec-plan |
| 157 | `39070caf86` | Merge pull request #281 from nunu1733/docs/issue-265-spec-accepted | `e312c83786` | PR #281: Merge pull request #281 from nunu1733/docs/issue-265-spec-accepted |
| 158 | `a9cb58f5dc` | Merge pull request #282 from nunu1733/docs/issue-265-close-record | `619fc46a94` | PR #282: Merge pull request #282 from nunu1733/docs/issue-265-close-record |
| 159 | `1f2be5573d` | Merge pull request #278 from nunu1733/issue-233-backup-preview-page-summary | `f35bdca307` | PR #278: Merge pull request #278 from nunu1733/issue-233-backup-preview-page-summary |
| 160 | `b761839479` | Merge pull request #284 from nunu1733/docs/issue-233-spec-implemented | `96585ce3e3` | PR #284: Merge pull request #284 from nunu1733/docs/issue-233-spec-implemented |
| 161 | `8612b98baa` | Merge pull request #286 from nunu1733/issue-228-spec-plan | `8ff35ef4bd` | PR #286: Merge pull request #286 from nunu1733/issue-228-spec-plan |
| 162 | `806bef2450` | Merge pull request #290 from nunu1733/docs/issue-288-spec-plan | `e9644c0fdb` | PR #290: Merge pull request #290 from nunu1733/docs/issue-288-spec-plan |
| 163 | `d0f40446c7` | Merge pull request #291 from nunu1733/docs/issue-288-spec-implemented | `8ec240b1a6` | PR #291: Merge pull request #291 from nunu1733/docs/issue-288-spec-implemented |
| 164 | `8504724468` | Merge pull request #289 from nunu1733/issue-228-missing-app-selection | `7004226a77` | PR #289: Merge pull request #289 from nunu1733/issue-228-missing-app-selection |
| 165 | `3e113302b9` | Merge pull request #294 from nunu1733/docs/issue-228-spec-implemented | `74a0e40947` | PR #294: Merge pull request #294 from nunu1733/docs/issue-228-spec-implemented |
| 166 | `81bad4de72` | Merge pull request #295 from nunu1733/issue-235-spec-plan | `5d215c64b6` | PR #295: Merge pull request #295 from nunu1733/issue-235-spec-plan |
| 167 | `f95fbfed3d` | Merge pull request #297 from nunu1733/issue-292-orientation-row-stability | `49790fe89a` | PR #297: Merge pull request #297 from nunu1733/issue-292-orientation-row-stability |
| 168 | `a16cc2b28e` | Merge pull request #301 from nunu1733/docs/issue-292-spec-implemented | `6d619efae7` | PR #301: Merge pull request #301 from nunu1733/docs/issue-292-spec-implemented |
| 169 | `463b25d217` | Merge pull request #296 from nunu1733/issue-235-widget-strategy-placement | `22984134d7` | PR #296: Merge pull request #296 from nunu1733/issue-235-widget-strategy-placement |
| 170 | `39214aef02` | Merge pull request #302 from nunu1733/issue-235-widget-production-fix | `1143a69a5f` | PR #302: Merge pull request #302 from nunu1733/issue-235-widget-production-fix |
| 171 | `f9afd8bfde` | Merge pull request #303 from nunu1733/docs-issue-235-implemented | `87ab529fb0` | PR #303: Merge pull request #303 from nunu1733/docs-issue-235-implemented |
| 172 | `cbb329f095` | Merge pull request #306 from nunu1733/issue-287-grid-change-unknown-lock-recovery | `d23f4ecbc2` | PR #306: Merge pull request #306 from nunu1733/issue-287-grid-change-unknown-lock-recovery |
| 173 | `a4d7a26457` | Merge pull request #307 from nunu1733/docs-issue-287-implemented | `8232cdc153` | PR #307: Merge pull request #307 from nunu1733/docs-issue-287-implemented |
| 174 | `9f4568862f` | feat(283): show selected organization strategy | `4cb332fd75` | PR #-: feat(283): show selected organization strategy |
| 175 | `0ea17a238b` | Merge pull request #305 from nunu1733/issue-300-api36-ui-lane-window-focus-precondition | `05b0fdb582` | PR #305: Merge pull request #305 from nunu1733/issue-300-api36-ui-lane-window-focus-precondition |
| 176 | `3aa6e83a1f` | fix: stabilize Issue #308 Compose focus synchronization (#310) | `7142e78451` | PR #310: fix: stabilize Issue #308 Compose focus synchronization (#310) |
| 177 | `37e3dd8feb` | docs(304): record API 36 window-focus occluder investigation (#311) | `cf0572b589` | PR #311: docs(304): record API 36 window-focus occluder investigation (#311) |
| 178 | `881c6e238e` | Merge pull request #312 from nunu1733/issue-304-root-cause-followup | `c1d864384f` | PR #312: Merge pull request #312 from nunu1733/issue-304-root-cause-followup |
| 179 | `c5274b5d0d` | Merge pull request #313 from nunu1733/issue-304-failure-evidence-preservation | `44433cf723` | PR #313: Merge pull request #313 from nunu1733/issue-304-failure-evidence-preservation |
| 180 | `9821dec073` | Merge pull request #314 from nunu1733/issue-299-spec-plan | `446aad72b0` | PR #314: Merge pull request #314 from nunu1733/issue-299-spec-plan |
| 181 | `2af57b3ad8` | Merge pull request #316 from nunu1733/issue-315-bounded-failure-evidence-capture | `d9d0becdfe` | PR #316: Merge pull request #316 from nunu1733/issue-315-bounded-failure-evidence-capture |
| 182 | `79dafd05e3` | Merge pull request #318 from nunu1733/docs/315-spec-implemented | `cff5b42f4b` | PR #318: Merge pull request #318 from nunu1733/docs/315-spec-implemented |
| 183 | `397d3fd957` | Merge pull request #317 from nunu1733/issue-298-phase1-spec-plan | `c3d5b66cd2` | PR #317: Merge pull request #317 from nunu1733/issue-298-phase1-spec-plan |
| 184 | `9326792583` | Merge pull request #319 from nunu1733/issue-298-implementation | `02ff641c6a` | PR #319: Merge pull request #319 from nunu1733/issue-298-implementation |
| 185 | `f12d67bcb6` | Merge pull request #320 from nunu1733/docs/issue-298-spec-implemented | `0070946294` | PR #320: Merge pull request #320 from nunu1733/docs/issue-298-spec-implemented |
| 186 | `9ea2ba0eb4` | Merge pull request #321 from nunu1733/issue-203-implementation | `9b14a7dac1` | PR #321: Merge pull request #321 from nunu1733/issue-203-implementation |
| 187 | `0cf82bc1e6` | Merge pull request #322 from nunu1733/issue-204-implementation | `a7ea6e9746` | PR #322: Merge pull request #322 from nunu1733/issue-204-implementation |
| 188 | `3df9c7afe4` | Merge pull request #325 from nunu1733/issue-205-implementation | `04b8a97e3d` | PR #325: Merge pull request #325 from nunu1733/issue-205-implementation |
| 189 | `4f555450bd` | Merge pull request #326 from nunu1733/docs/issue-205-implemented | `8f47e1f15e` | PR #326: Merge pull request #326 from nunu1733/docs/issue-205-implemented |
| 190 | `addb25d818` | Merge pull request #333 from nunu1733/issue-331-spec-plan | `ad9dc9fe5e` | PR #333: Merge pull request #333 from nunu1733/issue-331-spec-plan |
| 191 | `aab0d293d1` | Merge pull request #334 from nunu1733/docs-331-implemented | `c5af3e9a2c` | PR #334: Merge pull request #334 from nunu1733/docs-331-implemented |
| 192 | `dce8f5779c` | Merge pull request #335 from nunu1733/issue-330-spec-plan | `06cd5b4c18` | PR #335: Merge pull request #335 from nunu1733/issue-330-spec-plan |
| 193 | `15f4f0209f` | Merge pull request #338 from nunu1733/docs-330-implemented | `1bdf801022` | PR #338: Merge pull request #338 from nunu1733/docs-330-implemented |
| 194 | `3170c57e32` | Merge pull request #339 from nunu1733/issue-329-spec-plan | `b4475bb24e` | PR #339: Merge pull request #339 from nunu1733/issue-329-spec-plan |
| 195 | `08b0f0ebb3` | Merge pull request #340 from nunu1733/docs/issue-329-implemented | `b55502e710` | PR #340: Merge pull request #340 from nunu1733/docs/issue-329-implemented |
| 196 | `45711f53dd` | Merge pull request #341 from nunu1733/issue-336-spec-plan | `a5e34f5ada` | PR #341: Merge pull request #341 from nunu1733/issue-336-spec-plan |
| 197 | `9290afc2be` | Merge pull request #343 from nunu1733/docs-336-implemented | `4fdfd7df3a` | PR #343: Merge pull request #343 from nunu1733/docs-336-implemented |
| 198 | `90f18294b2` | Merge pull request #344 from nunu1733/issue-332-spec-plan | `f334185072` | PR #344: Merge pull request #344 from nunu1733/issue-332-spec-plan |
| 199 | `132dfd787d` | Merge pull request #346 from nunu1733/docs-332-implemented | `d7d184b13e` | PR #346: Merge pull request #346 from nunu1733/docs-332-implemented |
| 200 | `703afe3f4c` | Merge pull request #347 from nunu1733/issue-345-evidence | `e4127f3c1d` | PR #347: Merge pull request #347 from nunu1733/issue-345-evidence |
| 201 | `8fd05a40d5` | Merge pull request #349 from nunu1733/issue-348-ai-facing-contract | `f810089cf1` | PR #349: Merge pull request #349 from nunu1733/issue-348-ai-facing-contract |
| 202 | `a9ec3c2cf9` | Merge pull request #350 from nunu1733/issue-327-spec-plan | `5c1b7742dd` | PR #350: Merge pull request #350 from nunu1733/issue-327-spec-plan |
| 203 | `34ba8ff447` | Merge pull request #353 from nunu1733/issue-328-spec-plan | `1251ea6db8` | PR #353: Merge pull request #353 from nunu1733/issue-328-spec-plan |
| 204 | `de77e280b7` | Merge pull request #354 from nunu1733/issue-337-spec-plan | `7dc3d91f3d` | PR #354: Merge pull request #354 from nunu1733/issue-337-spec-plan |
| 205 | `b728ed4d9f` | Merge pull request #355 from nunu1733/issue-337-impl | `10c1c39e45` | PR #355: Merge pull request #355 from nunu1733/issue-337-impl |
| 206 | `ce848f3486` | Merge pull request #363 from nunu1733/docs/356-organizer-as-is-audit | `34dec9a942` | PR #363: Merge pull request #363 from nunu1733/docs/356-organizer-as-is-audit |
| 207 | `3076bdae7e` | Merge pull request #364 from nunu1733/issue-361-organizer-to-be-ux | `47515e8491` | PR #364: Merge pull request #364 from nunu1733/issue-361-organizer-to-be-ux |
| 208 | `a2b6aba318` | Merge pull request #378 from nunu1733/issue-362-organizer-disposition | `e793d2b8e3` | PR #378: Merge pull request #378 from nunu1733/issue-362-organizer-disposition |
| 209 | `ec34dd3fa6` | Merge pull request #379 from nunu1733/issue-365-to-be-authoritative-docs | `09edaf50c8` | PR #379: Merge pull request #379 from nunu1733/issue-365-to-be-authoritative-docs |
| 210 | `32c72094a4` | Merge pull request #380 from nunu1733/issue-366-organizer-hub | `72e8ab360b` | PR #380: Merge pull request #380 from nunu1733/issue-366-organizer-hub |
| 211 | `1285c13cc6` | Merge pull request #381 from nunu1733/issue-366-implemented-docs | `ff6898f75b` | PR #381: Merge pull request #381 from nunu1733/issue-366-implemented-docs |
| 212 | `37a0b44bf1` | Merge pull request #382 from nunu1733/issue-367-materials-relocation | `7f4ee7040f` | PR #382: Merge pull request #382 from nunu1733/issue-367-materials-relocation |
| 213 | `ca9c171e91` | Merge pull request #383 from nunu1733/issue-367-implemented-docs | `f02014abbb` | PR #383: Merge pull request #383 from nunu1733/issue-367-implemented-docs |
| 214 | `b84d277f81` | Merge pull request #384 from nunu1733/issue-368-strategy-picker-material-relocation | `b9e9bbd529` | PR #384: Merge pull request #384 from nunu1733/issue-368-strategy-picker-material-relocation |
| 215 | `0142e87379` | Merge pull request #385 from nunu1733/issue-368-implemented-docs | `ce172134b3` | PR #385: Merge pull request #385 from nunu1733/issue-368-implemented-docs |
| 216 | `171d0bcf10` | Merge pull request #386 from nunu1733/issue-369-spec-plan | `d1c11d06f0` | PR #386: Merge pull request #386 from nunu1733/issue-369-spec-plan |
| 217 | `7ec9e9d3fe` | Merge pull request #387 from nunu1733/issue-369-run-display-integration | `a62df7fc51` | PR #387: Merge pull request #387 from nunu1733/issue-369-run-display-integration |
| 218 | `adeebe6fb1` | Merge pull request #388 from nunu1733/issue-369-implemented-docs | `b809d325f6` | PR #388: Merge pull request #388 from nunu1733/issue-369-implemented-docs |
| 219 | `13c95eafe6` | Merge pull request #389 from nunu1733/issue-370-onboarding-hub-connection | `ec4a02a438` | PR #389: Merge pull request #389 from nunu1733/issue-370-onboarding-hub-connection |
| 220 | `b21b186495` | Merge pull request #390 from nunu1733/issue-372-spec-plan-r2 | `19da193037` | PR #390: Merge pull request #390 from nunu1733/issue-372-spec-plan-r2 |
| 221 | `8b78ebc131` | Merge pull request #391 from nunu1733/issue-371-usage-access-jit-request | `2b8b5317e4` | PR #391: Merge pull request #391 from nunu1733/issue-371-usage-access-jit-request |
| 222 | `3731fc3dd7` | Merge pull request #392 from nunu1733/issue-371-implemented-docs | `f7bd4ff164` | PR #392: Merge pull request #392 from nunu1733/issue-371-implemented-docs |
| 223 | `42efffd6aa` | Merge pull request #393 from nunu1733/issue-372-request-flow | `40f3a7c794` | PR #393: Merge pull request #393 from nunu1733/issue-372-request-flow |
| 224 | `dcaecf6913` | Merge pull request #394 from nunu1733/issue-372-implemented-docs | `4de212bc78` | PR #394: Merge pull request #394 from nunu1733/issue-372-implemented-docs |
| 225 | `5a57fa3695` | Merge pull request #395 from nunu1733/issue-373-spec-plan-r2 | `7733c5b2c9` | PR #395: Merge pull request #395 from nunu1733/issue-373-spec-plan-r2 |
| 226 | `04684e765b` | Merge pull request #396 from nunu1733/issue-373-implementation | `38247abd94` | PR #396: Merge pull request #396 from nunu1733/issue-373-implementation |
| 227 | `c05435a947` | Merge pull request #397 from nunu1733/issue-373-implemented-docs | `750ae8c697` | PR #397: Merge pull request #397 from nunu1733/issue-373-implemented-docs |
| 228 | `065ac8ccc5` | Merge pull request #400 from nunu1733/issue-376-spec-plan-r2 | `20b1da8b6f` | PR #400: Merge pull request #400 from nunu1733/issue-376-spec-plan-r2 |
| 229 | `9dc3ec8fed` | Merge pull request #399 from nunu1733/issue-374-spec-plan | `14e087cf3c` | PR #399: Merge pull request #399 from nunu1733/issue-374-spec-plan |
| 230 | `203919b48b` | Merge pull request #401 from nunu1733/issue-374-implemented-docs | `a6a3be9322` | PR #401: Merge pull request #401 from nunu1733/issue-374-implemented-docs |
| 231 | `5065ce3b10` | Merge pull request #402 from nunu1733/issue-375-spec-plan-r2 | `f4c6e75b84` | PR #402: Merge pull request #402 from nunu1733/issue-375-spec-plan-r2 |
| 232 | `eb032d75f4` | Merge pull request #403 from nunu1733/issue-376-recovery-entry | `e6956051f0` | PR #403: Merge pull request #403 from nunu1733/issue-376-recovery-entry |
| 233 | `8272f5c0ee` | Merge pull request #404 from nunu1733/issue-376-implemented-docs | `52b304e004` | PR #404: Merge pull request #404 from nunu1733/issue-376-implemented-docs |
| 234 | `f4783d57c0` | Merge pull request #405 from nunu1733/issue-375-implementation | `080838f0a2` | PR #405: Merge pull request #405 from nunu1733/issue-375-implementation |
| 235 | `c52d5fcc15` | Merge pull request #406 from nunu1733/issue-375-implemented-docs | `b7bf71de0c` | PR #406: Merge pull request #406 from nunu1733/issue-375-implemented-docs |
| 236 | `b4ad019085` | Merge pull request #408 from nunu1733/issue-377-spec-plan | `bb8df5614e` | PR #408: Merge pull request #408 from nunu1733/issue-377-spec-plan |
| 237 | `f9c95272d4` | Merge pull request #409 from nunu1733/issue-407-implementation | `b943a14c27` | PR #409: Merge pull request #409 from nunu1733/issue-407-implementation |
| 238 | `b4a2012640` | Merge pull request #410 from nunu1733/issue-377-implementation | `bef5c7c90c` | PR #410: Merge pull request #410 from nunu1733/issue-377-implementation |
| 239 | `83831909cd` | Merge pull request #411 from nunu1733/issue-377-implemented-docs | `b4252563d9` | PR #411: Merge pull request #411 from nunu1733/issue-377-implemented-docs |
| 240 | `6157721272` | Merge pull request #412 from nunu1733/issue-398-spec | `a9fdba753a` | PR #412: Merge pull request #412 from nunu1733/issue-398-spec |
| 241 | `17d883a012` | Merge pull request #414 from nunu1733/issue-398-implemented-docs | `d1faa448da` | PR #414: Merge pull request #414 from nunu1733/issue-398-implemented-docs |
| 242 | `3b5c926e43` | Merge pull request #415 from nunu1733/issue-352-exchange-receipt-test-determinism | `05c9af83f7` | PR #415: Merge pull request #415 from nunu1733/issue-352-exchange-receipt-test-determinism |
| 243 | `9f949901e6` | Merge pull request #424 from nunu1733/issue-422-spec-plan | `b11463ae10` | PR #424: Merge pull request #424 from nunu1733/issue-422-spec-plan |
| 244 | `24fdf0e0e8` | Merge pull request #425 from nunu1733/issue-422-implementation | `f321a0e856` | PR #425: Merge pull request #425 from nunu1733/issue-422-implementation |
| 245 | `b146a63557` | Merge pull request #433 from nunu1733/issue-422-followup-docs | `aeb77776dd` | PR #433: Merge pull request #433 from nunu1733/issue-422-followup-docs |
| 246 | `c7bcc26e06` | Merge pull request #432 from nunu1733/issue-417 | `1c446a0b6d` | PR #432: Merge pull request #432 from nunu1733/issue-417 |
| 247 | `0c2914c144` | Merge pull request #434 from nunu1733/issue-417-implemented-docs | `6b9abf2c1b` | PR #434: Merge pull request #434 from nunu1733/issue-417-implemented-docs |
| 248 | `22c163814b` | Merge pull request #436 from nunu1733/issue-342 | `9d484ab690` | PR #436: Merge pull request #436 from nunu1733/issue-342 |
| 249 | `e9c93e5dff` | ci: capture API 36 evidence before emulator teardown (#437) | `b1715144be` | PR #437: ci: capture API 36 evidence before emulator teardown (#437) |
| 250 | `a9b9454b91` | docs(304): record PR #437 post-merge evidence and run 36082413664 attempt 2 classification (#455) | `de20ff5b9c` | PR #455: docs(304): record PR #437 post-merge evidence and run 36082413664 attempt 2 classification (#455) |
| 251 | `bcd383e845` | Merge pull request #457 from nunu1733/issue-456-test-audit-skill | `711e1a2ab8` | PR #457: Merge pull request #457 from nunu1733/issue-456-test-audit-skill |
| 252 | `7508bbf0d5` | ci(438): capture live failure evidence in the remaining seven instrumentation lanes (#459) | `a5ecebb2d0` | PR #459: ci(438): capture live failure evidence in the remaining seven instrumentation lanes (#459) |
| 253 | `d9f2531791` | Merge pull request #462 from nunu1733/issue-458-test-portfolio-semantic-audit | `5bd4c432c5` | PR #462: Merge pull request #462 from nunu1733/issue-458-test-portfolio-semantic-audit |
| 254 | `a57403b5a5` | Merge pull request #463 from nunu1733/issue-440-product-docs-refocus | `33d5c96fd3` | PR #463: Merge pull request #463 from nunu1733/issue-440-product-docs-refocus |
| 255 | `668d803c03` | Merge pull request #464 from nunu1733/issue-441-editing-burden-benchmark | `053bc50e16` | PR #464: Merge pull request #464 from nunu1733/issue-441-editing-burden-benchmark |
| 256 | `42037b6097` | Merge pull request #465 from nunu1733/issue-441-benchmark-revision | `8723fecc5a` | PR #465: Merge pull request #465 from nunu1733/issue-441-benchmark-revision |
| 257 | `9f6a6d697d` | Merge pull request #466 from nunu1733/issue-442-android16-17-fitness | `d776a9a4aa` | PR #466: Merge pull request #466 from nunu1733/issue-442-android16-17-fitness |
| 258 | `ea8d57d068` | feat(443): AI相談（外部AI交換）の凍結適用 — 実験的機能toggle（既定OFF）と既定導線からの除外 (#467) | `dbb490fdaa` | PR #467: feat(443): AI相談（外部AI交換）の凍結適用 — 実験的機能toggle（既定OFF）と既定導線からの除外 (#467) |
| 259 | `b40888ae17` | Merge pull request #468 from nunu1733/issue-444-risk-tiered-workflow | `7a4da0d4cf` | PR #468: Merge pull request #468 from nunu1733/issue-444-risk-tiered-workflow |
| 260 | `824b468614` | Merge pull request #469 from nunu1733/issue-445-direct-edit-write-contract | `300c45b29e` | PR #469: Merge pull request #469 from nunu1733/issue-445-direct-edit-write-contract |
| 261 | `6705275264` | Merge pull request #470 from nunu1733/issue-446-new-app-destination-policy | `b7cd3a4a63` | PR #470: Merge pull request #470 from nunu1733/issue-446-new-app-destination-policy |
| 262 | `d3b5aba550` | Merge pull request #471 from nunu1733/issue-447-edit-surface-adr | `6712e7c3a1` | PR #471: Merge pull request #471 from nunu1733/issue-447-edit-surface-adr |
| 263 | `f35ff4494f` | Merge pull request #472 from nunu1733/issue-448-edit-actions-per-item | `17fb782206` | PR #472: Merge pull request #472 from nunu1733/issue-448-edit-actions-per-item |
| 264 | `74fe5abaf5` | Merge pull request #474 from nunu1733/issue-442-final-conclusion | `4e34b00e8c` | PR #474: Merge pull request #474 from nunu1733/issue-442-final-conclusion |
| 265 | `c5a7840b88` | Merge pull request #475 from nunu1733/issue-447-adr-0014-accept | `2e71652e32` | PR #475: Merge pull request #475 from nunu1733/issue-447-adr-0014-accept |
| 266 | `2ac104aa93` | Merge pull request #478 from nunu1733/issue-477-manual-lane-flake | `737f0ab03c` | PR #478: Merge pull request #478 from nunu1733/issue-477-manual-lane-flake |
| 267 | `29476b10a0` | Merge pull request #476 from nunu1733/issue-449-multi-select-surface | `1717d32681` | PR #476: Merge pull request #476 from nunu1733/issue-449-multi-select-surface |
| 268 | `be7576c30a` | Merge pull request #480 from nunu1733/issue-449-ac14-docs | `0a6e3f95f5` | PR #480: Merge pull request #480 from nunu1733/issue-449-ac14-docs |
| 269 | `5a78a01935` | Merge pull request #481 from nunu1733/issue-450-spec-plan | `1f8b42c19b` | PR #481: Merge pull request #481 from nunu1733/issue-450-spec-plan |
| 270 | `3bde0c7562` | Merge pull request #483 from nunu1733/issue-482-external-reference-scan | `f5a53dde06` | PR #483: Merge pull request #483 from nunu1733/issue-482-external-reference-scan |
| 271 | `092c44b46e` | Merge pull request #484 from nunu1733/issue-482-review-fixes | `83d0a78d5b` | PR #484: Merge pull request #484 from nunu1733/issue-482-review-fixes |
| 272 | `56624406fd` | Merge pull request #485 from nunu1733/issue-451-spec-plan | `693d28903f` | PR #485: Merge pull request #485 from nunu1733/issue-451-spec-plan |
| 273 | `e744031aca` | Merge pull request #488 from nunu1733/issue-487-spec | `d62ae3c2a5` | PR #488: Merge pull request #488 from nunu1733/issue-487-spec |
| 274 | `5936936759` | Merge pull request #489 from nunu1733/issue-487-fix | `9b79789ee0` | PR #489: Merge pull request #489 from nunu1733/issue-487-fix |
| 275 | `833ded935f` | Merge pull request #486 from nunu1733/issue-452-spec-plan | `762fcb6d02` | PR #486: Merge pull request #486 from nunu1733/issue-452-spec-plan |
| 276 | `4bc1d6c696` | fix(461): fail closed on unreadable durable grid-migration recovery source (#491) | `a5c9492d48` | PR #491: fix(461): fail closed on unreadable durable grid-migration recovery source (#491) |
| 277 | `5755236d68` | Merge pull request #492 from nunu1733/issue-453-strategy-choice-reduction | `f7cb685d60` | PR #492: Merge pull request #492 from nunu1733/issue-453-strategy-choice-reduction |
| 278 | `0f766403ad` | Merge pull request #493 from nunu1733/issue-453-strategy-reduction-impl | `61a6a5ad23` | PR #493: Merge pull request #493 from nunu1733/issue-453-strategy-reduction-impl |
| 279 | `c3bdaf0bf8` | Merge pull request #495 from nunu1733/issue-453-spec-implemented | `bf21e99b98` | PR #495: Merge pull request #495 from nunu1733/issue-453-spec-implemented |
| 280 | `3d8f4dcca5` | Merge pull request #496 from nunu1733/issue-444-ac7-spec-implemented | `0f9b1b285e` | PR #496: Merge pull request #496 from nunu1733/issue-444-ac7-spec-implemented |
| 281 | `8b8b5e3abb` | Merge pull request #494 from nunu1733/issue-479-hub-request-row-visibility | `748972edf4` | PR #494: Merge pull request #494 from nunu1733/issue-479-hub-request-row-visibility |
| 282 | `2b71b79f7b` | Merge pull request #498 from nunu1733/issue-497-new-app-destination-policy-impl | `1c53811dd5` | PR #498: Merge pull request #498 from nunu1733/issue-497-new-app-destination-policy-impl |
| 283 | `87a2eb3bb4` | Merge pull request #501 from nunu1733/issue-497-ac-sync | `1605a01a6e` | PR #501: Merge pull request #501 from nunu1733/issue-497-ac-sync |
| 284 | `6916fd0ded` | Merge pull request #502 from nunu1733/issue-435-impl | `5bab895c35` | PR #502: Merge pull request #502 from nunu1733/issue-435-impl |
| 285 | `53f90652cf` | Merge pull request #503 from nunu1733/issue-435-close | `297fbe3ddd` | PR #503: Merge pull request #503 from nunu1733/issue-435-close |
| 286 | `63c71833ca` | refactor(418): confine organizer run UI state publication to the main thread (#504) | `6087bb23d0` | PR #504: refactor(418): confine organizer run UI state publication to the main thread (#504) |
| 287 | `ec3f8805be` | docs(418): sync spec/plan to implemented with AC evidence (#506) | `572da94b32` | PR #506: docs(418): sync spec/plan to implemented with AC evidence (#506) |
| 288 | `10ee58336e` | fix(490): serialize category UI test effects (#505) | `246b73a0af` | PR #505: fix(490): serialize category UI test effects (#505) |
| 289 | `eb831de5e5` | Merge pull request #511 from nunu1733/issue-293-impl | `2215e153f5` | PR #511: Merge pull request #511 from nunu1733/issue-293-impl |
| 290 | `e9964d6f07` | Merge pull request #512 from nunu1733/issue-293-close | `47afb19bb9` | PR #512: Merge pull request #512 from nunu1733/issue-293-close |
| 291 | `6184020e3a` | Merge pull request #510 from nunu1733/codex/seed-6-1-8-issues | `0ccdf0aa16` | PR #510: Merge pull request #510 from nunu1733/codex/seed-6-1-8-issues |
| 292 | `66b6811083` | Merge pull request #513 from nunu1733/issue-507-duplicate-removal | `c12d6cfd86` | PR #513: Merge pull request #513 from nunu1733/issue-507-duplicate-removal |
| 293 | `8508c14182` | Merge pull request #514 from nunu1733/issue-507-device-evidence | `4cbd73a635` | PR #514: Merge pull request #514 from nunu1733/issue-507-device-evidence |
| 294 | `8db762a360` | Merge pull request #515 from nunu1733/issue-508-visual-preview-item-exclusion | `e38e148dda` | PR #515: Merge pull request #515 from nunu1733/issue-508-visual-preview-item-exclusion |
| 295 | `aac74df8e2` | Merge pull request #517 from nunu1733/issue-508-closing-docs | `bd7cd2f260` | PR #517: Merge pull request #517 from nunu1733/issue-508-closing-docs |
| 296 | `0b4db97a9a` | Merge pull request #518 from nunu1733/issue-509-category-new-app-destination | `aa13d79a9a` | PR #518: Merge pull request #518 from nunu1733/issue-509-category-new-app-destination |
| 297 | `a65a1d169c` | Merge pull request #523 from nunu1733/issue-519-16-rebase-phase0 | `d7da44cdac` | PR #523: Merge pull request #523 from nunu1733/issue-519-16-rebase-phase0 |
| 298 | `80e10bb866` | Merge pull request #529 from nunu1733/issue-521-target-sdk-37-research | `d3cfd0296f` | PR #529: Merge pull request #529 from nunu1733/issue-521-target-sdk-37-research |
| 299 | `87f8793067` | Merge pull request #525 from nunu1733/issue-520-quickstep-api37 | `946cbba190` | PR #525: Merge pull request #525 from nunu1733/issue-520-quickstep-api37 |
| 300 | `38262fb74d` | Merge pull request #530 from nunu1733/issue-522-rebase-data-compatibility | `8b35f1ff7a` | PR #530: Merge pull request #530 from nunu1733/issue-522-rebase-data-compatibility |


## 追加修復10 commitのattribution（8b35f1ff..88af5218）

300単位replay完了後、G1 build gateの失敗修復として追加された10 commit。WIP 253 pathの採否は [wip-adoption-table.md](./wip-adoption-table.md)。

| commit | 件名 |
|---|---|
| `4022708e33` | docs(532): record Phase 2 replay log — 300 units applied, 20 conflict resolutions |
| `1ea513d3cd` | fix(532): keep fork kotlin.android plugin application across upstream removal (plan §4.3 adapt) |
| `113df5d20d` | Revert "fix(532): keep fork kotlin.android plugin application across upstream removal (plan §4.3 adapt)" |
| `5c20ccc810` | fix(532): restore anchor-side build structure (settings includes, version catalog) with fork additions (plan §4.3 adapt) |
| `8faeac27df` | fix(532): restore fork-accepted model seam (LauncherModel/AppState .java), remove anchor .kt duplicates — adapt detail deferred to Phase 3 |
| `4198c0a6e7` | fix(532): repair empty-blob regressions from wholesale restores (SystemUiProxy, StatsLogCompatManager, BgDataModel, GridSizeMigrationDBController, wmshell configs) |
| `d581c85b2e` | fix(532): converge dagger graph and preview renderer to fork-accepted main state (remove anchor-only components) |
| `7bd1c6818d` | fix(532): converge fork UI/organizer code onto anchor model APIs (WIP G1) |
| `bb832b3863` | wip(532): G1 build convergence — anchor build scaffolding + fork model/UI stack, ForkBridgeModule DI bridges (WIP, compile not yet green) |
| `88af5218ce` | docs(532): record G1 blocker — layer-level model architecture decision required (plan §4.6 stop) |
