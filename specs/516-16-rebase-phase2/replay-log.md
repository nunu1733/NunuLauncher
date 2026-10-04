# Replay log: 16-dev rebase Phase 2（Issue #532 / plan.md §5）

> Status: replay完了 / G1 gateで設計判断が必要なため一時停止（2026-10-04）
> rebase branch: `issue-516-rebase-16-dev`（anchor upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35` 起点）
> 単位番号: main first-parent 時系列index（唯一の実行順。A/B/Cは分類ラベル）
>REBASE_HEAD=7f46ab6466075ebf5a39f954cf3376d2968e6353（S0〜S3c+G1/G2/G3完了head。G4開始前に確定記録）

## 0. submodule pin判断（plan §2の停止点判定 — 解決済み、停止不要）

- baseline後のmain first-parent 300単位には、`platform_frameworks_libs_systemui` のgitlink差分を含む単位が**1件も存在しない**（各単位のfirst-parent diffで確認）。forkのpin `6a11ef76` を設定したcommit `aae5fbbc`（icon shadow fix）はbaseline `505dbc40` より前の既存（ancestor確認済み）であり、replay対象外。
- anchor pin `7d9e92bd`（16-dev）はicon shadow fix `ce33201` を含み、その内容はfork側 `6a11ef76` のpatchと**完全一致（diff 0行）**。よって**pinはanchor側 `7d9e92bd` を維持**とし、plan §2の停止・ADR-0018改訂は不要と判断した（判断根拠: 内容一致確認 2026-10-04）。

## 1. 分類サマリ

- candidate ownership inventory（初版）: **579 path**（`git diff --name-only 43a21b43 88af5218` の1,828 pathから、`docs/assessment/upstream-patch-surface-baseline.json` のexplicit exclusion規約（`.github/` `.idea/` `docs/` `specs/` `tests/` `tools/` prefix、`.md` suffix、明示paths）を除外したproduction差分。S4のG3で正式計測する。詳細計測はS4）
- merge単位 234 / squash単位 50 / direct単位 16（合計300、PR番号持たないsquash/directはdocs/specマーカーや縦切りの直接commit）
- A（非production）144 / B（production・adapt path非接触暫定）156。C判定（adapt path接触）はreplay実行時のconflict発生で確定し、結果列へ記録する。

## 2. 単位一覧と進行

| # | PR | 種別 | 分類 | 元commit | 結果 | 状態 | 備考 |
|---|---|---|---|---|---|---|---|
| 001 | #7 | SQUASH | B | `40e437943c` | applied | done | conflict=CONTRIBUTING/README→fork content維持 |
| 002 | #19 | SQUASH | A | `39a1819785` | applied | done | conflict=.github/workflows 3files→fork content維持 |
| 003 | #18 | SQUASH | A | `2ab2ac7c71` | applied | done | clean |
| 004 | #20 | SQUASH | A | `c5747fb52f` | applied | done | clean |
| 005 | #21 | SQUASH | A | `6f5c12f5c8` | applied | done | clean |
| 006 | #25 | MERGE | A | `7117d1b98c` | applied | done | clean |
| 007 | #26 | MERGE | A | `26d7ef082a` | applied | done | clean |
| 008 | #28 | MERGE | A | `e254bffcb0` | applied | done | clean |
| 009 | #27 | MERGE | A | `4766833f2b` | applied | done | clean |
| 010 | #29 | MERGE | B | `05bee5363d` | applied | done | conflict=build.gradle+libs.versions.toml→fork deps維持+PR29のjunit4/test srcDirs/spotless target統合 |
| 011 | #30 | MERGE | B | `93517aaf22` | applied | done | clean |
| 012 | #32 | MERGE | B | `4dd8ee7a9d` | applied | done | clean |
| 013 | #34 | MERGE | B | `2fe774b4f3` | applied | done | clean |
| 014 | #35 | MERGE | A | `e08ede6f8e` | applied | done | clean |
| 015 | #36 | SQUASH | B | `69066e7ca3` | applied | done | clean |
| 016 | #37 | SQUASH | A | `228cc0c5b6` | applied | done | clean |
| 017 | #39 | SQUASH | A | `8788cb7f97` | applied | done | clean |
| 018 | #40 | MERGE | A | `866d231ffd` | applied | done | clean |
| 019 | #47 | SQUASH | C | `090e3a4910` | applied | done | conflict=8 prod files→PR47 version wholesale採用（LauncherModel/Provider/LoaderTask/ModelDbController/GridSizeMigrationUtil等） |
| 020 | #45 | SQUASH | B | `d50a79d261` | applied | done | clean |
| 021 | direct | SQUASH | B | `f97343aa74` | applied | done | conflict=build.gradle→planner Test task inputs block統合 |
| 022 | direct | SQUASH | A | `46f2319976` | applied | done | clean |
| 023 | #61 | SQUASH | A | `918f79ddcd` | applied | done | clean |
| 024 | #62 | MERGE | B | `215c444136` | applied | done | clean |
| 025 | #63 | MERGE | A | `ad4f213109` | applied | done | clean |
| 026 | #65 | MERGE | A | `c94356e8db` | applied | done | clean |
| 027 | #66 | MERGE | A | `dc5d072840` | applied | done | clean |
| 028 | #68 | MERGE | A | `1ad4f2a385` | applied | done | clean |
| 029 | #70 | MERGE | A | `6bfad79bd9` | applied | done | clean |
| 030 | #69 | MERGE | A | `98ccdb24ea` | applied | done | clean |
| 031 | #71 | SQUASH | A | `bea108b92c` | applied | done | clean |
| 032 | #72 | SQUASH | A | `9090874532` | applied | done | clean |
| 033 | #73 | MERGE | C | `818c90a82e` | applied | done | conflict=5files→PR73 version wholesale（toml compose-ui-test命名は後続単位が正規化） |
| 034 | #74 | MERGE | A | `a0f3d28e0d` | applied | done | clean |
| 035 | #75 | MERGE | B | `72322c3f91` | applied | done | clean |
| 036 | #76 | MERGE | A | `cfc665c19c` | applied | done | clean |
| 037 | #77 | MERGE | C | `3663f3157d` | applied | done | conflict=NovaBackupConverter/LawndeckManager/RestoreDbTask→PR77 version wholesale |
| 038 | #78 | MERGE | B | `187c7bfc54` | applied | done | clean |
| 039 | #79 | MERGE | C | `8bb16367ca` | applied | done | conflict=65files（res 57+prod 8）→PR79 tree wholesale（deck退役本体、disposition keep=deck削除維持） |
| 040 | #80 | MERGE | A | `1d55fe4479` | applied | done | clean |
| 041 | #82 | MERGE | B | `082fce5145` | applied | done | clean |
| 042 | #87 | MERGE | A | `bc60ee52e2` | applied | done | clean |
| 043 | #88 | MERGE | B | `16bdf6ec29` | applied | done | clean |
| 044 | #90 | MERGE | B | `4adb0411d8` | applied | done | clean |
| 045 | #91 | MERGE | A | `884b8f5544` | applied | done | clean |
| 046 | #92 | MERGE | B | `a6bf5024e8` | applied | done | clean |
| 047 | #93 | MERGE | A | `c7cbfa4789` | applied | done | clean |
| 048 | #94 | MERGE | B | `836ccff7d9` | applied | done | clean |
| 049 | #95 | MERGE | C | `78a1eb687c` | applied | done | conflict=OnboardingPrefs.kt→PR95 version wholesale |
| 050 | #98 | MERGE | B | `9480f75332` | applied | done | clean |
| 051 | #97 | MERGE | A | `cfa8c69b92` | applied | done | clean |
| 052 | direct | SQUASH | C | `dc84b77147` | applied | done | conflict=res/values/strings.xml→direct commit version wholesale |
| 053 | #107 | SQUASH | B | `4f09eea892` | applied | done | clean |
| 054 | #111 | SQUASH | A | `4ec0eb3dc6` | applied | done | clean |
| 055 | #114 | SQUASH | B | `d16a1efa32` | applied | done | clean |
| 056 | #121 | SQUASH | B | `f337fb9f7a` | applied | done | clean |
| 057 | #122 | SQUASH | C | `93889336e7` | applied | done | conflict=DatabaseHelper.java→PR122 version wholesale（transaction ownership spec118） |
| 058 | direct | SQUASH | A | `79c1a7db6f` | applied | done | clean |
| 059 | #112 | SQUASH | A | `880d489a8f` | applied | done | clean |
| 060 | #124 | SQUASH | B | `903800a1d4` | applied | done | clean |
| 061 | #125 | SQUASH | B | `ac63565f2a` | applied | done | clean |
| 062 | #126 | SQUASH | B | `44b25d325a` | applied | done | clean |
| 063 | #127 | SQUASH | B | `f381d26b05` | applied | done | clean |
| 064 | #128 | SQUASH | B | `51940f3dfc` | applied | done | clean |
| 065 | direct | SQUASH | B | `8316333347` | applied | done | clean |
| 066 | #133 | SQUASH | B | `534d0f32db` | applied | done | clean |
| 067 | #135 | MERGE | B | `05329be2d7` | applied | done | clean |
| 068 | #139 | MERGE | A | `eb682a0e66` | applied | done | clean |
| 069 | #140 | MERGE | B | `ad4bab004f` | applied | done | clean |
| 070 | #143 | MERGE | C | `cd2cfaac8f` | applied | done | conflict=DeviceProfileOverrides.kt→PR143 version wholesale |
| 071 | direct | SQUASH | A | `8c5b0d5d25` | applied | done | clean |
| 072 | direct | SQUASH | A | `cd49710af2` | applied | done | clean |
| 073 | #144 | MERGE | B | `8f1519e873` | applied | done | clean |
| 074 | direct | SQUASH | A | `33e2b8edf1` | applied | done | clean |
| 075 | #145 | MERGE | B | `d35604411d` | applied | done | clean |
| 076 | direct | SQUASH | A | `1efcf1fd78` | applied | done | clean |
| 077 | #147 | MERGE | A | `718995320c` | applied | done | clean |
| 078 | #149 | MERGE | B | `fd6ed98f92` | applied | done | clean |
| 079 | #148 | MERGE | B | `74c2156767` | applied | done | clean |
| 080 | #151 | MERGE | A | `7ba2194ce7` | applied | done | clean |
| 081 | #154 | MERGE | C | `a84cbb7451` | applied | done | conflict=res/values-ja/strings.xml→PR154 wholesale |
| 082 | direct | SQUASH | A | `d1f89246d4` | applied | done | clean |
| 083 | #158 | MERGE | B | `2d811b701c` | applied | done | clean |
| 084 | #157 | MERGE | B | `6fd276b50d` | applied | done | clean |
| 085 | #159 | MERGE | A | `fd3dad799d` | applied | done | clean |
| 086 | #160 | MERGE | B | `8740f8c136` | applied | done | clean |
| 087 | direct | SQUASH | A | `0a43f616b4` | applied | done | clean |
| 088 | #162 | MERGE | A | `c68abcce62` | applied | done | clean |
| 089 | direct | SQUASH | B | `92a490a2f8` | applied | done | clean |
| 090 | #165 | MERGE | B | `5b00c46b69` | applied | done | clean |
| 091 | direct | SQUASH | A | `090d3efdc5` | applied | done | clean |
| 092 | direct | SQUASH | A | `3b079da0a8` | applied | done | clean |
| 093 | direct | SQUASH | A | `343037ce6e` | applied | done | clean |
| 094 | #169 | MERGE | C | `7c81d7ee56` | applied | done | conflict=InvariantDeviceProfile.java→PR169 wholesale（Nova restore authoritative） |
| 095 | direct | SQUASH | A | `afb7618144` | applied | done | clean |
| 096 | #173 | MERGE | A | `0e442f7d03` | applied | done | clean |
| 097 | #175 | MERGE | B | `4712124d40` | applied | done | clean |
| 098 | #176 | MERGE | A | `de2d33f551` | applied | done | clean |
| 099 | #179 | MERGE | A | `647b122d86` | applied | done | clean |
| 100 | #180 | MERGE | B | `256fb6525d` | applied | done | clean |
| 101 | #183 | MERGE | B | `ff46fa20dc` | applied | done | clean |
| 102 | #184 | MERGE | B | `50ddb86148` | applied | done | clean |
| 103 | direct | SQUASH | A | `ac09d27bae` | applied | done | clean |
| 104 | #186 | MERGE | B | `667e8915f2` | applied | done | clean |
| 105 | #190 | MERGE | B | `1e7781f221` | applied | done | clean |
| 106 | #188 | MERGE | B | `050987e9a6` | applied | done | clean |
| 107 | #189 | MERGE | B | `0e0bcae9d3` | applied | done | clean |
| 108 | #191 | MERGE | B | `c47c9d9464` | applied | done | clean |
| 109 | #196 | SQUASH | A | `b494cd09e7` | applied | done | clean |
| 110 | #193 | MERGE | B | `f8b1626c14` | applied | done | clean |
| 111 | #197 | MERGE | B | `f465067699` | applied | done | clean |
| 112 | #198 | MERGE | B | `2af9450a69` | applied | done | clean |
| 113 | #200 | MERGE | B | `a849ee3ea3` | applied | done | clean |
| 114 | #202 | MERGE | B | `5fdab48082` | applied | done | clean |
| 115 | #207 | MERGE | B | `05f37443a4` | applied | done | clean |
| 116 | #221 | MERGE | B | `c50ec0cea1` | applied | done | clean |
| 117 | #222 | MERGE | B | `10c74b89b4` | applied | done | clean |
| 118 | #223 | MERGE | B | `caf600aa6e` | applied | done | clean |
| 119 | #224 | MERGE | B | `db96dd34de` | applied | done | clean |
| 120 | #225 | MERGE | B | `b16390f5a7` | applied | done | clean |
| 121 | #226 | MERGE | B | `d136027926` | applied | done | clean |
| 122 | #227 | MERGE | B | `efeef4265b` | applied | done | clean |
| 123 | #229 | MERGE | B | `5b606b587c` | applied | done | clean |
| 124 | direct | SQUASH | A | `5ec5e592ca` | applied | done | clean |
| 125 | #236 | SQUASH | B | `bf1886d578` | applied | done | clean |
| 126 | #238 | SQUASH | B | `7eeb947efb` | applied | done | clean |
| 127 | #239 | SQUASH | B | `a84aab807f` | applied | done | clean |
| 128 | #240 | SQUASH | B | `38f9639163` | applied | done | clean |
| 129 | direct | SQUASH | A | `65daff2978` | applied | done | clean |
| 130 | #241 | MERGE | B | `3e49ac6871` | applied | done | clean |
| 131 | #243 | MERGE | B | `24e11eb97d` | applied | done | clean |
| 132 | #244 | MERGE | B | `0f3d1d3a8f` | applied | done | clean |
| 133 | #245 | MERGE | A | `d36b109e98` | applied | done | clean |
| 134 | #246 | MERGE | B | `f16b1db0c1` | applied | done | clean |
| 135 | direct | SQUASH | A | `3e55507b53` | applied | done | clean |
| 136 | #254 | MERGE | B | `d0d25e51cc` | applied | done | conflict=.github/ISSUE_TEMPLATE 2files→PR254で削除（fork routing） |
| 137 | #255 | MERGE | A | `983657ee7e` | applied | done | clean |
| 138 | #257 | MERGE | B | `4da41ef1bb` | applied | done | clean |
| 139 | #258 | MERGE | A | `241fa3963c` | applied | done | clean |
| 140 | #259 | MERGE | B | `72cfebc401` | applied | done | conflict=SECURITY.md→PR259 wholesale |
| 141 | #260 | SQUASH | A | `cf25735ff4` | applied | done | clean |
| 142 | #261 | MERGE | B | `2411c76082` | applied | done | clean |
| 143 | #262 | SQUASH | B | `0f86386921` | applied | done | clean |
| 144 | #263 | SQUASH | A | `b25f20ca7c` | applied | done | clean |
| 145 | #264 | SQUASH | B | `1f1ead86fd` | applied | done | clean |
| 146 | #266 | SQUASH | A | `cc41f3bb1b` | applied | done | clean |
| 147 | #267 | MERGE | A | `2c80f2142c` | applied | done | clean |
| 148 | #268 | MERGE | B | `3355b57165` | applied | done | clean |
| 149 | #272 | MERGE | A | `a96da39505` | applied | done | clean |
| 150 | #273 | MERGE | B | `e7929c9a99` | applied | done | clean |
| 151 | #275 | MERGE | A | `a38256976e` | applied | done | clean |
| 152 | #274 | MERGE | B | `6b6bf8dd9f` | applied | done | clean |
| 153 | #277 | MERGE | A | `62a3c0a40a` | applied | done | clean |
| 154 | #276 | MERGE | B | `69eb1c3e55` | applied | done | clean |
| 155 | #279 | MERGE | A | `ed7ce5a205` | applied | done | clean |
| 156 | #280 | MERGE | A | `74b39d5361` | applied | done | clean |
| 157 | #281 | MERGE | A | `39070caf86` | applied | done | clean |
| 158 | #282 | MERGE | A | `a9cb58f5dc` | applied | done | clean |
| 159 | #278 | MERGE | B | `1f2be5573d` | applied | done | clean |
| 160 | #284 | MERGE | A | `b761839479` | applied | done | clean |
| 161 | #286 | MERGE | A | `8612b98baa` | applied | done | clean |
| 162 | #290 | MERGE | B | `806bef2450` | applied | done | clean |
| 163 | #291 | MERGE | A | `d0f40446c7` | applied | done | clean |
| 164 | #289 | MERGE | B | `8504724468` | applied | done | clean |
| 165 | #294 | MERGE | A | `3e113302b9` | applied | done | clean |
| 166 | #295 | MERGE | A | `81bad4de72` | applied | done | clean |
| 167 | #297 | MERGE | B | `f95fbfed3d` | applied | done | clean |
| 168 | #301 | MERGE | A | `a16cc2b28e` | applied | done | clean |
| 169 | #296 | MERGE | B | `463b25d217` | applied | done | clean |
| 170 | #302 | MERGE | B | `39214aef02` | applied | done | clean |
| 171 | #303 | MERGE | A | `f9afd8bfde` | applied | done | clean |
| 172 | #306 | MERGE | B | `cbb329f095` | applied | done | clean |
| 173 | #307 | MERGE | A | `a4d7a26457` | applied | done | clean |
| 174 | direct | SQUASH | B | `9f4568862f` | applied | done | clean |
| 175 | #305 | MERGE | B | `0ea17a238b` | applied | done | clean |
| 176 | #310 | SQUASH | B | `3aa6e83a1f` | applied | done | clean |
| 177 | #311 | SQUASH | A | `37e3dd8feb` | applied | done | clean |
| 178 | #312 | MERGE | A | `881c6e238e` | applied | done | clean |
| 179 | #313 | MERGE | B | `c5274b5d0d` | applied | done | clean |
| 180 | #314 | MERGE | B | `9821dec073` | applied | done | clean |
| 181 | #316 | MERGE | B | `2af57b3ad8` | applied | done | clean |
| 182 | #318 | MERGE | A | `79dafd05e3` | applied | done | clean |
| 183 | #317 | MERGE | A | `397d3fd957` | applied | done | clean |
| 184 | #319 | MERGE | B | `9326792583` | applied | done | clean |
| 185 | #320 | MERGE | A | `f12d67bcb6` | applied | done | clean |
| 186 | #321 | MERGE | B | `9ea2ba0eb4` | applied | done | clean |
| 187 | #322 | MERGE | B | `0cf82bc1e6` | applied | done | clean |
| 188 | #325 | MERGE | B | `3df9c7afe4` | applied | done | clean |
| 189 | #326 | MERGE | A | `4f555450bd` | applied | done | clean |
| 190 | #333 | MERGE | B | `addb25d818` | applied | done | clean |
| 191 | #334 | MERGE | A | `aab0d293d1` | applied | done | clean |
| 192 | #335 | MERGE | B | `dce8f5779c` | applied | done | clean |
| 193 | #338 | MERGE | A | `15f4f0209f` | applied | done | clean |
| 194 | #339 | MERGE | B | `3170c57e32` | applied | done | clean |
| 195 | #340 | MERGE | A | `08b0f0ebb3` | applied | done | clean |
| 196 | #341 | MERGE | B | `45711f53dd` | applied | done | clean |
| 197 | #343 | MERGE | A | `9290afc2be` | applied | done | clean |
| 198 | #344 | MERGE | B | `90f18294b2` | applied | done | clean |
| 199 | #346 | MERGE | A | `132dfd787d` | applied | done | clean |
| 200 | #347 | MERGE | B | `703afe3f4c` | applied | done | clean |
| 201 | #349 | MERGE | B | `8fd05a40d5` | applied | done | clean |
| 202 | #350 | MERGE | B | `a9ec3c2cf9` | applied | done | clean |
| 203 | #353 | MERGE | B | `34ba8ff447` | applied | done | clean |
| 204 | #354 | MERGE | A | `de77e280b7` | applied | done | clean |
| 205 | #355 | MERGE | B | `b728ed4d9f` | applied | done | clean |
| 206 | #363 | MERGE | A | `ce848f3486` | applied | done | clean |
| 207 | #364 | MERGE | A | `3076bdae7e` | applied | done | clean |
| 208 | #378 | MERGE | A | `a2b6aba318` | applied | done | clean |
| 209 | #379 | MERGE | A | `ec34dd3fa6` | applied | done | clean |
| 210 | #380 | MERGE | B | `32c72094a4` | applied | done | clean |
| 211 | #381 | MERGE | A | `1285c13cc6` | applied | done | clean |
| 212 | #382 | MERGE | B | `37a0b44bf1` | applied | done | clean |
| 213 | #383 | MERGE | A | `ca9c171e91` | applied | done | clean |
| 214 | #384 | MERGE | B | `b84d277f81` | applied | done | clean |
| 215 | #385 | MERGE | A | `0142e87379` | applied | done | clean |
| 216 | #386 | MERGE | A | `171d0bcf10` | applied | done | clean |
| 217 | #387 | MERGE | B | `7ec9e9d3fe` | applied | done | clean |
| 218 | #388 | MERGE | A | `adeebe6fb1` | applied | done | clean |
| 219 | #389 | MERGE | B | `13c95eafe6` | applied | done | clean |
| 220 | #390 | MERGE | A | `b21b186495` | applied | done | clean |
| 221 | #391 | MERGE | B | `8b78ebc131` | applied | done | clean |
| 222 | #392 | MERGE | A | `3731fc3dd7` | applied | done | clean |
| 223 | #393 | MERGE | B | `42efffd6aa` | applied | done | clean |
| 224 | #394 | MERGE | A | `dcaecf6913` | applied | done | clean |
| 225 | #395 | MERGE | A | `5a57fa3695` | applied | done | clean |
| 226 | #396 | MERGE | B | `04684e765b` | applied | done | clean |
| 227 | #397 | MERGE | A | `c05435a947` | applied | done | clean |
| 228 | #400 | MERGE | A | `065ac8ccc5` | applied | done | clean |
| 229 | #399 | MERGE | B | `9dc3ec8fed` | applied | done | clean |
| 230 | #401 | MERGE | A | `203919b48b` | applied | done | clean |
| 231 | #402 | MERGE | A | `5065ce3b10` | applied | done | clean |
| 232 | #403 | MERGE | B | `eb032d75f4` | applied | done | clean |
| 233 | #404 | MERGE | A | `8272f5c0ee` | applied | done | clean |
| 234 | #405 | MERGE | B | `f4783d57c0` | applied | done | clean |
| 235 | #406 | MERGE | A | `c52d5fcc15` | applied | done | clean |
| 236 | #408 | MERGE | A | `b4ad019085` | applied | done | clean |
| 237 | #409 | MERGE | B | `f9c95272d4` | applied | done | clean |
| 238 | #410 | MERGE | B | `b4a2012640` | applied | done | clean |
| 239 | #411 | MERGE | A | `83831909cd` | applied | done | clean |
| 240 | #412 | MERGE | B | `6157721272` | applied | done | clean |
| 241 | #414 | MERGE | A | `17d883a012` | applied | done | clean |
| 242 | #415 | MERGE | A | `3b5c926e43` | applied | done | clean |
| 243 | #424 | MERGE | A | `9f949901e6` | applied | done | clean |
| 244 | #425 | MERGE | B | `24fdf0e0e8` | applied | done | clean |
| 245 | #433 | MERGE | A | `b146a63557` | applied | done | clean |
| 246 | #432 | MERGE | B | `c7bcc26e06` | applied | done | clean |
| 247 | #434 | MERGE | A | `0c2914c144` | applied | done | clean |
| 248 | #436 | MERGE | B | `22c163814b` | applied | done | clean |
| 249 | #437 | SQUASH | B | `e9c93e5dff` | applied | done | clean |
| 250 | #455 | SQUASH | A | `a9b9454b91` | applied | done | clean |
| 251 | #457 | MERGE | B | `bcd383e845` | applied | done | clean |
| 252 | #459 | SQUASH | B | `7508bbf0d5` | applied | done | clean |
| 253 | #462 | MERGE | B | `d9f2531791` | applied | done | clean |
| 254 | #463 | MERGE | A | `a57403b5a5` | applied | done | clean |
| 255 | #464 | MERGE | C | `668d803c03` | applied | done | conflict=settings.gradle→PR464 wholesale（benchmark接続） |
| 256 | #465 | MERGE | A | `42037b6097` | applied | done | clean |
| 257 | #466 | MERGE | A | `9f6a6d697d` | applied | done | clean |
| 258 | #467 | SQUASH | B | `ea8d57d068` | applied | done | clean |
| 259 | #468 | MERGE | A | `b40888ae17` | applied | done | clean |
| 260 | #469 | MERGE | A | `824b468614` | applied | done | clean |
| 261 | #470 | MERGE | A | `6705275264` | applied | done | clean |
| 262 | #471 | MERGE | A | `d3b5aba550` | applied | done | clean |
| 263 | #472 | MERGE | B | `f35ff4494f` | applied | done | clean |
| 264 | #474 | MERGE | A | `74fe5abaf5` | applied | done | clean |
| 265 | #475 | MERGE | A | `c5a7840b88` | applied | done | clean |
| 266 | #478 | MERGE | B | `2ac104aa93` | applied | done | clean |
| 267 | #476 | MERGE | C | `29476b10a0` | applied | done | conflict=LauncherOptionsPopup.kt→PR476 wholesale（edit surface） |
| 268 | #480 | MERGE | A | `be7576c30a` | applied | done | clean |
| 269 | #481 | MERGE | C | `5a78a01935` | applied | done | conflict=Folder.java→PR481 wholesale（edit undo、ADR-0013契約） |
| 270 | #483 | MERGE | A | `3bde0c7562` | applied | done | clean |
| 271 | #484 | MERGE | A | `092c44b46e` | applied | done | clean |
| 272 | #485 | MERGE | B | `56624406fd` | applied | done | clean |
| 273 | #488 | MERGE | A | `e744031aca` | applied | done | clean |
| 274 | #489 | MERGE | B | `5936936759` | applied | done | clean |
| 275 | #486 | MERGE | B | `833ded935f` | applied | done | clean |
| 276 | #491 | SQUASH | B | `4bc1d6c696` | applied | done | clean |
| 277 | #492 | MERGE | A | `5755236d68` | applied | done | clean |
| 278 | #493 | MERGE | B | `0f766403ad` | applied | done | clean |
| 279 | #495 | MERGE | A | `c3bdaf0bf8` | applied | done | clean |
| 280 | #496 | MERGE | A | `3d8f4dcca5` | applied | done | clean |
| 281 | #494 | MERGE | B | `8b8b5e3abb` | applied | done | clean |
| 282 | #498 | MERGE | C | `2b71b79f7b` | applied | done | conflict=AddWorkspaceItemsTask/LawnchairProcessInitializer/PreferenceManager→PR498 wholesale（destination policy） |
| 283 | #501 | MERGE | A | `87a2eb3bb4` | applied | done | clean |
| 284 | #502 | MERGE | B | `6916fd0ded` | applied | done | clean |
| 285 | #503 | MERGE | A | `53f90652cf` | applied | done | clean |
| 286 | #504 | SQUASH | B | `63c71833ca` | applied | done | clean |
| 287 | #506 | SQUASH | A | `ec3f8805be` | applied | done | clean |
| 288 | #505 | SQUASH | B | `10ee58336e` | applied | done | clean |
| 289 | #511 | MERGE | B | `eb831de5e5` | applied | done | clean |
| 290 | #512 | MERGE | A | `e9964d6f07` | applied | done | clean |
| 291 | #510 | MERGE | A | `6184020e3a` | applied | done | clean |
| 292 | #513 | MERGE | B | `66b6811083` | applied | done | clean |
| 293 | #514 | MERGE | A | `8508c14182` | applied | done | clean |
| 294 | #515 | MERGE | B | `8db762a360` | applied | done | clean |
| 295 | #517 | MERGE | A | `aac74df8e2` | applied | done | clean |
| 296 | #518 | MERGE | A | `0b4db97a9a` | applied | done | clean |
| 297 | #523 | MERGE | A | `a65a1d169c` | applied | done | clean |
| 298 | #529 | MERGE | A | `80e10bb866` | applied | done | clean |
| 299 | #525 | MERGE | A | `87f8793067` | applied | done | clean |
| 300 | #530 | MERGE | A | `38262fb74d` | applied | done | clean |


## 3. 実行サマリ（2026-10-04）

- **300単位すべて適用完了**。conflict発生は20単位（上表のconflict=行）。残り280単位はclean適用。
- conflict解消はすべてplan §4に従い、fork側のaccepted契約（PR単位のtree内容）を正として復元する方式（keep相当）。16-dev側の新構造へのadaptが必要なsemantic解消は、**Phase 2内（S1〜S3）の未完項目**としてplan §4.1で扱う（plan revision 2 / ADR-0018 Decision 9 acceptedに伴う訂正。旧記載の「Phase 3検証…で扱う」は誤り）。T4/T5/T7/T8追加oracle実装PRのgate前提（plan §7）は変更なし。
- 挙動変更を意図した解消は発生していない（plan §4.6の停止条件に該当なし）。submodule pin判断点（§2）は到達しなかった（§0参照）。
- commit messageは `PR #<番号>: <title>` 形式。direct commitは `Direct: <title>` 形式。


## 4. G1 gate実行で判明した設計判断要求（plan §4.6 相当 — 停止中）

### 現象
- replay自体は300単位すべて適用済み（本ログ§2）。conflict解消でfork側tree内容を正として復元した結果、commitツリーは`main`のproductionコード状態に近づいた。
- 一方anchor側のビルド土台（settings.gradleの新module include、Gradle 9.8、AGP/Kotlin/Baselineprofile新版、`ModuleDbController`のCRUDシグネチャ変更、`BgDataModel.kt`の新データ層、daggerグラフの`@Inject`化）と、main側のモデル層（`LauncherModel.java`/`LauncherAppState.java`/`BgDataModel.java`のINSTANCEパターン、旧CRUDシグネチャ）は、plan §4.3の想定を超えて相互に噛み合わず、`assembleLawnWithQuickstepGithubDebug`が通らない。

### 構造的对立（どちらか一方への統一が必要）
1. **anchorモデル層を正**（`LauncherModel.kt`/`BgDataModel.kt`/dagger `@Inject`）: この場合、forkの`ModelWriter`/`RestoreDbTask`/`ModelDbController`契約（spec 118のlease/transaction所有、ADR-0013直接編集の書込み契約）をanchor構造へ移植する大規模adaptが必要。モデルAPIの差分は約200行相当。
2. **mainモデル層を正**（現状の`src/`javaスタック）: anchorのdaggerグラフ・`PreviewAppComponent`・`BgDataModel.kt`依存（`WidgetModule`、`ModelInitializer`等）をmain方式に書き換える必要がある。quickstepのtaskbar/recentsもanchorのdaggerに寄っているため影響が広い。

### 求める判断
- ADR-0018のDecision（per-PR replay方式）は本対立を解消しない。**ADR-0018改訂または補足ADR**として、上記1/2のいずれをPhase 2の統一方針とするか（および中間のハイブリッド容認可否）を決定すること。
- 作業再開の前提: 上記判断のreview/accept後、rebase branch `issue-516-rebase-16-dev`上のWIP commit（`7bd1c681`〜`bb832b3863`）を前提に統一作業を継続する。

### 補足
- 本判断はPhase 0 assessment §5のdisposition（keep 57 / adapt 48）が前提とした「adapt可能」の粒度を超える（ファイル単位ではなく層単位の統一が必要）。
- submodule pin、settings.gradle/tomlの統合方針（anchor土台+fork追加）は確定済みで本判断の対象外。

### 判断の解決（2026-10-04、plan §4.1 S0で追記）

- **ADR-0018 Decision 9（方針1: anchor構造を正）がaccepted**（ADR-0018 revision 6 / plan revision 2。PR #534 merge、main `15ae5bf91700a5765ce48f80584469ab965ebb24`）。§4の「求める判断」は解決済み。本branchはplan §4.1のS0〜S4で作業を継続する。
- モデル層の固定source比較の正本は [docs/assessment/issue-532-model-architecture-decision.md](../../docs/assessment/issue-532-model-architecture-decision.md)。
- WIP（`4022708e33`〜`88af5218ce` の追加修復10 commit、253 path）は一括採択/一括revert禁止。path単位の採否初版は [wip-adoption-table.md](./wip-adoption-table.md)、source→replay対応表は [source-replay-map.md](./source-replay-map.md)。


## 5. S0〜S3 実行記録とG1結果（2026-10-04、ADR-0018 Decision 9 / plan §4.1）

### 実行commit（停止head `88af5218ce` 以降、push順）
| stage | commit | 内容 |
|---|---|---|
| S0 | `827251a398` | accepted文書（ADR rev6 / plan rev2 / assessment / spec）の同期 |
| S0 | `e1888b2e0b` | replay-log訂正 + source-replay-map（300件一対一）+ WIP採否表（253 path）+ candidate inventory初版（579 path） |
| S1 | `de06b3da1f`/`ee4d8385cd`/`792b792522`/`bf31d9a8d5` | anchorモデル層復元（`LauncherModel.kt`/`BgDataModel.kt`/`LoaderTask`/binder/`ModelDbController`/dagger/preview）。旧Java duplicate・ForkBridgeModule（旧model provider）削除。非model bindingは `ForkServiceModule` として分離（S2で個別review対象） |
| S2 | `51f0b6e339` | organizer/restore reload契約をanchor `LauncherModel.kt` へ移植（token入力はassisted、commit+close後queued完了、supersession/cancel、snapshot gate、`startLoaderWithoutCallbacks`） |
| S2 | `4081458f82` | `ModelProjectionCodec` をanchor `BgDataModel`（itemsIdMap/extraItems/getContents）で動作させる |
| S2 | `5e4645e273` | `ModelWriter` へのadmission（`LayoutWriteCoordinator`）+ DirectEdit契約（stage-2再検証、1 transaction、Undo、配置先）移植。`LoaderTask.run` を `runOrDefer` で包む（#298 thread affinity） |
| S2 | `468e5d0ff5`/`35a9fc02ed`/`abe589ee07` | anchor folder UI採用+fork folder契約port、#497 destination policy、startup hook（`LauncherAppState` initから `LawnchairApp.onLauncherAppStateCreated`、component構築中の再入なし、preview/sandbox非発火） |
| S3 | `20249df0e9`/`60162ea98d`/`b18a98af48` | grid migration journal/reconciliation両entry（`tryMigrateDB`/`attemptMigrateDb`）をanchor `ModelDbController` へ移植、`quiesceForRestore` 復元、Nova converter をanchor APIへ適合 |
| S3 | `3b5ae96cf0`〜`9636d7c0f9` | lawnchair UI層のanchor API追従（reorderable 3.1/icons mono pipeline/preferences keys/resources）+ src/ javac収束（IDP/PreviewOverrides統合、LauncherProvider/ModelWriter/RestoreDbTask適合、stale削除） |

### G1 gate結果（本head `9636d7c0f9`）
- `./gradlew spotlessCheck`: **BUILD SUCCESSFUL**
- `./gradlew assembleLawnWithQuickstepGithubDebug`: **BUILD SUCCESSFUL**

### S2/S3での解消判断（plan §4.1「具体的な実装境界」対応）
- organizer tokenは `LoaderTask` のassisted入力で運搬、通常/preview loaderはtokenless
- `WorkspaceData` のversion/modification IDと `RevisionId` は同一視せず、codecはanchor `itemsIdMap` 列挙+`FolderInfo.getContents()` でprojection契約を維持
- CRUD呼出しはanchorのfavorites固定APIへadapt。旧table指定CRUDは復活させず必要分のみbridge（`LauncherProvider` 経由）
- startup hookは `LauncherAppState` init末尾（component構築完了後）から `onLauncherAppStateCreated(this)` へ引数渡し。preview/sandbox processはsafe-castで非発火
- spec 118（DB transaction所有）とspec 14（process-wide lease）は別実装で維持（`SQLiteTransaction` lease-owning ctor / `LayoutWriteCoordinator` admission）

### 未完（S3c/S4で対応）
- T4/T5/T7/T8追加oracle実装と `ci_portfolio_map.yml`/portfolio同期
- G2（organizer unit test gate）、G3（candidate ownership inventory正式計測）、G4（T1〜T9実行表+`REBASE_HEAD`記録）、G5（CI merge gate）
