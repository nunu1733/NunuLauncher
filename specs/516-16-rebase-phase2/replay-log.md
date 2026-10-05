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


## 6. G4実行表（2026-10-05、plan §7 G4 / AC-4）

- **REBASE_HEAD**=`7f46ab6466075ebf5a39f954cf3376d2968e6353`（本log冒頭でG4開始前に確定記録済み。以下の全実行はこのsourceと対にする）
- **G4実行head**=`794db5dd50b5578cc7ef9ae32ff103e5ad5eee4d`（= REBASE_HEAD `f24b315f05` に、G4直前に発見されたtest-infra欠陥の修復1件 `794db5dd50 G4(532): compile the Kotlin instrumentation sources against anchor APIs` を加えたhead。差分は `build.gradle` のandroidTest Kotlin source set配線と `tests/organizer-instrumentation` のanchor API追従のみで、**app production code非接触**）
- **実行環境**: ローカルAPI 36 emulator（Android 16 / arm64、AVD `issue108_api36_pixel_9_pro_fold`、serial `emulator-5556`）。CI正本（API 36 / google_apis / pixel_7_pro / x86_64）と同じAPI level。command形式は正本どおり `bash tools/ci/run-emulator-command-with-failure-capture.sh … -- ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…`（ci.ymlと同一entrypoint）。失敗時のfailure-time capture証跡は `build/{db-migration,shared-writer,reservation-recovery,restore-capture}-failure-time-evidence/`、per-class result XMLは `build/g4-evidence/G4-<class>.xml`。
- **実行方式（正本からの逸脱と理由）**: 正本のlane commandはcomma区切りの複数class filterだが、**anchor AGP（Gradle 9.8内蔵test runner）は `android.testInstrumentationRunnerArguments` を `k=v,k=v` ペアとしてparseするため、comma区切りfilterでは最初の1 classしかdispatchされない**（`--info` で発行済み `am instrument` 引数を確認。`\,` escapeも不可）。このため本G4は **同一wrapper commandのper-class独立invocation** で実行した（#299のper-class原則と整合）。**ci.ymlの既存複数class filter（db-migration 13 class等）もanchor AGP下では同じ静かなunder-runになる** → G5への必須引継ぎ事項（§6.4）。
- **総合結果: 非green（G4 gate未達）**。失敗はすべて§6.2の単一のproduction regressionに起因する。PASSしているclass（schema/rollback/profile remap/recovery/prefs）はcomponent非依存の実DB assert群であり、契約自体はanchor構造でも成立している。

### 6.1 T1〜T9実行表

| T | lane | class filter（per-class実行） | 結果 | command実体 / 失敗要約 |
|---|---|---|---|---|
| T1 | db-migration | `DatabaseHelperSchema33Test` | **PASS 6/6** | wrapper+connected、exit 0 |
| T1 | db-migration | `DowngradeSchema33Test` | **PASS 1/1** | 同上、exit 0 |
| T1 | db-migration | `InactiveGridDbNormalizationTest` | **PASS 1/1** | 同上、exit 0 |
| T2 | db-migration | `MigrationTransactionOwnershipTest` | **PASS 3/3** | 同上、exit 0 |
| T2 | db-migration | `rollback32.Schema32RollbackBinaryTest` | **PASS 2/2** | 同上、exit 0 |
| T2 | shared-writer | `NestedTransactionTest` | **FAIL 0/5** | 5件とも `StackOverflowError`（§6.2循環） |
| T3 | db-migration | `GridMigrationSuccessTest` | **FAIL 0/3** | setUpの `LauncherAppState.getIDP` でSO → JUnit4が@Afterを実行しtearDownのNPEが表面化（根因は§6.2） |
| T3 | db-migration | `GridMigrationFailureTest` | **FAIL 0/30** | 同上（tryMigrateDB/attemptMigrateDb両entryとも到達不能） |
| T4 | db-migration | `RestoreDbTaskSuccessPathTest`（新oracle） | **FAIL 0/1** | SO（§6.2） |
| T4 | shared-writer | `RestoreLeaseSerializationTest` | **FAIL 6/11（5 PASS）** | 6件SO（§6.2）。component非依存の5 caseはPASS |
| T4 | shared-writer | `ModelWriterTransactionReentryTest` | **FAIL 0/5** | SO（§6.2） |
| T4 | db-migration | `RestoreProfileRemapTest` | **PASS 1/1** | component非依存。profile remap/surviving lock契約は成立 |
| T5 | db-migration | `RealZipRestoreE2E`（新oracle） | **FAIL 0/2** | setUp SO → tearDown `lateinit launcher` 未初期化でマスク表示（根因§6.2） |
| T6 | unit Permanent | `RecoveryRecordCodecTest` | **PASS 9/9** | `testLawnWithQuickstepGithubDebugUnitTest --tests …` exit 0（G2のunit PermanentのG4側再証跡） |
| T6 | unit Permanent | `RecoveryManifestChunksTest` | **PASS 9/9** | 同上 |
| T6 | reservation-recovery | `RecoveryStoreLifecycleTest` | **PASS 22/22** | wrapper+connected、exit 0 |
| T6 | reservation-recovery | `RecoveryStoreChunkedManifestInstrumentationTest` | **PASS 7/7** | 同上 |
| T7 | db-migration | `PrefsLegacyXmlMigrationTest`（device半分。新oracle） | **PASS 2/2** | legacy XML→DataStore変換のdevice readback契約は成立。JVM半分はG2 green（`SharedPreferencesLegacyKeyMigrationTest`） |
| T7 | db-migration | `LauncherPrefsCommitTest` | **PASS 1/1** | §6.3のseam適応後の契約（全editor commit）をverify |
| T7 | db-migration | `DeckRetirementMigrationInstrumentationTest` | **FAIL 0/2** | SO（§6.2） |
| T8 | restore-capture（script正本） | script通し実行 | **stage 1で停止（正本どおりfirst-failure停止）** | `bash tools/ci/run-restore-capture-instrumentation.sh` をwrapper経由で実行。stage 1 `NovaRestoreCaptureControlTest` 1/1 FAIL |
| T8 | restore-capture（per-class継続） | `NovaRestoreCaptureControlTest` / `WidgetWindowTest` / `UnknownProviderTest` / `NoCallbacksTest` | **FAIL 0/1, 0/1, 0/1, 0/2** | いずれもSO→tearDown lateinitマスク（§6.2） |
| T8 | restore-capture（cross-process） | manual install + `am instrument` StageA → force-stop → StageB | **FAIL** | StageAは`ForkServiceModule.provideIdp`のSOでprocess crash。force-stop後StageBは `Tests run: 1, Failures: 1` |
| T8 | restore-capture | `NovaRestoreGridApplicationTest` | **FAIL 0/4** | SO（§6.2） |
| T8 | restore-capture | `NovaConverterBoundaryTest` / `NovaConverterBoundarySmartspaceOffTest`（新oracle） | **FAIL 0/2, 0/1** | SO（§6.2） |
| T9 | — | 実行不要 | **SKIP** | on-demand（API 36 emulator + Pixel 9a / API 37実機）。closureはPhase 4 owner decision（ADR-0018 Decision 5） |

合計: instrumented 20 class + cross-process 2 stage + unit 2 class。PASS 64 case / FAIL 65 case超（失敗は全て§6.2）。証跡XML 27件を `build/g4-evidence/` に保存。

### 6.2 production regression（確定 — 変更せず報告）

**launcher appが本branchでは起動時クラッシュする**（エミュレータ実機確認: `am start -n app.lawnchair.debug/app.lawnchair.LawnchairLauncher` → 即座にFATAL）。全失敗の根因は単一のDI循環:

- `ForkServiceModule.provideIdp`（`lawnchair/src/app/lawnchair/dagger/ForkServiceModule.kt:44`）が `InvariantDeviceProfile.INSTANCE.get(context)` でIDPを供給する。
- anchorでは `InvariantDeviceProfile.INSTANCE` は `DaggerSingletonObject(LauncherAppComponent::getIDP)`（`InvariantDeviceProfile.java:106`）であり、`INSTANCE.get` は**component経由**でIDPを解決する。
- anchor IDPはS3で `@Inject` constructor（`InvariantDeviceProfile.java:291`）を既に持つが、Daggerはmodule `@Provides` を優先するため `provideIdp` が使われ、`component.getIDP() → provideIdp → INSTANCE.get → component.getIDP() → …` の再帰で `StackOverflowError` になる。Daggerの循環検出は通らない（DoubleCheckの自己再入）。
- `ForkServiceModule` のdoc comment（「IDP has no @Inject constructor yet」）はS3での `@Inject` 復活後に陳腐化した記述で、循環はS3のjavac収束時に混入したと考えられる。G1（compile/assemble）は通るが起動は誰も確認していなかった。
- 影響: componentを構築する全経路（app起動、`LauncherAppState.getIDP/getInstance`、IDP `INSTANCE.get` 全caller）。component非依存のtest（T1/T2のDB fixture群、`RestoreProfileRemapTest`、recovery lane、prefs 2 class、unit）はPASSしており、DB/schema/recovery/prefs契約そのもののregressionは本実行では検出されていない。
- 想定修正（**本G4では未実施**）: `ForkServiceModule.provideIdp` を削除しanchor `@Inject` constructorへ委譲させる（他のForkServiceModule providerは `MainThreadInitializedObject` 系で循環なし、削除影響はIDPのみ）。修正はowner reviewのうえ後続commitで行い、失敗classの再実行（本表と同一command）で確認する。

失敗表示の注意: `GridMigration*Test`（tearDown NPE）と `RealZipRestoreE2E` / Nova capture群（`lateinit property launcher`）のXML上のfailure messageは **setUp時SOのマスク表示** である（JUnit4は@Before失敗後も@Afterを実行する）。根因はすべて上記循環のSOで、`build/g4-evidence/` の `RestoreDbTaskSuccessPathTest` / `ModelWriterTransactionReentryTest` / `DeckRetirementMigrationInstrumentationTest` のXMLにフルスタック（`DaggerLauncherAppComponent.getIDP ↔ ForkServiceModule.provideIdp`）が記録されている。

### 6.3 G4でのtest-infra修復（1 commit、assert契約維持）

commit `794db5dd50`（REBASE_HEAD直後、本表の全実行より前）:

- **`build.gradle`**: androidTest source setへ `kotlin.directories.addAll('tests/organizer-instrumentation')` を追加。anchor build（Gradle 9.8内蔵Kotlin）は `java.srcDirs` をKotlin compile taskへmirrorしないため、Kotlin instrumentation source全体（runner `DeckRetirementTestRunner` 含む、T5/T7/T8 oracle群）が **NO-SOURCEでAPKから欠落** していた。初回G4実行で全laneが `tests="0"`（runner class不在でinstrumentation crash）となって発見。G2の修復（Java半分+tests/unit配線）の漏れ。
- 上記配線修復で顕在化した **Kotlin instrumentation約60件のanchor API追従**（G2のJava修復と同種・assert契約維持）: `bindCompleteModel` seam統一、`isModelLoaded()` 呼出し形、`LauncherAppState.model` property、anchor IDPでのgrid適用（`DeviceProfileOverrides.setCurrentGrid` + `onConfigChanged` 同期実行）、`parseAllDefinedGridOptions` へのdisplayInfo引数、`DeviceGridState` gridType（G2 S3a convention）、`LoaderCursor` @AssistedInject化、`SplitScreenConstants` の `wm.shell.shared.split` 移行（persisted値は不変: 1/2）、`WidgetPickerActivity` パッケージ移動、favorites固定query。
- **契約seamの消失1件（明示）**: anchor `LauncherPrefs.putSync()` はUnitを返す（S2/S3のproduction adapt `ModelDbController.writeGridPreferences` はreadback検証で代替済み）。`LauncherPrefsCommitTest` は「#59 集約commit boolean」のassert（`assertFalse(committed)`）を削除し、「全editorが両pref fileで正確に1回commitされる」契約のみを保持するよう改名・適応した。boolean契約の消失はS2/S3 production adaptに由来し、review対象としてここに記録する。

### 6.4 G5（CI）への引継ぎ

1. **anchor AGPの複数class filter制限**: ci.ymlの `android.testInstrumentationRunnerArguments.class=A,B,…` はanchor AGP下で最初の1 classしか実行しない。G5のCIはこのままだと全laneが静かにunder-runする。lane filterの分割実行（per-class invocation化、script化）またはrunner引数経路の修正が必要。**→ 対応済み（本branch）: `5cfb33d061` で `tools/ci/run-instrumentation-per-class.sh` を新設し、複数class 5 laneをper-class invocationへ変更（lane・surface edge・class集合は不変。`validate_ci_portfolio.py` / `test_validate_ci_portfolio.py` 21 tests / `test_compute_ci_gating.py` すべてOK）。G5はCI上で本変更を検証する。**
2. **production regression（§6.2）の修正と本表の再実行**: `ForkServiceModule.provideIdp` 修正commit後に、FAIL 65 caseの再実行（同一command）でG4をgreenにする必要がある。PASS済みclassの再実行も、最終head上でREBASE_HEAD対証跡を更新すること。**→ §6.5で実施。残存失敗は§6.6の新規欠陥群。**
3. **CI emulator**: 本G4はarm64ローカルAPI 36で実行。CI正本（x86_64 pixel_7_pro）での最終確認はG5のCI runで行う。
4. T9はon-demandのまま（Phase 4 owner closure）。

### 6.5 G4再実行（main agent承認の修正後。2026-10-05）

- **承認事項**: (1) `provideIdp` 削除、(2) CI lane filterのper-class化、(3) FAIL case同一command再実行、(4) `LauncherPrefsCommitTest` boolean seam削除の妥当性確認、(5) boot smoke。
- **修正commit（この順）**:
  - `24fc2cca70` — `ForkServiceModule.provideIdp` 削除（anchor IDP `@Inject` constructorへ委譲。循環の全数監査: 他のfork providerは全て `MainThreadInitializedObject` 系か直接構築で、component-backed accessorを返すproviderはIDPのみ）。
  - `5cfb33d061` — CI per-class化（§6.4-1参照）。
  - `7873c588d0` — `gridType` を `<resources>` scopeで定義しGridDisplayOption内はreferenceへ（§6.6 D0: 2番目のboot blocker）。
- **実行head**=`7873c588d0`（REBASE_HEAD=`7f46ab6466075ebf5a39f954cf3376d2968e6353` は不変。REBASE_HEADからの差分は上記3 commit＋§6.3 `794db5dd50`＋replay-log。production差分は `ForkServiceModule.kt` と `res/values/attrs.xml` の2 file）。emulator・command形式は§6.1と同一。証跡XMLは `build/g4-evidence2/`。

#### 再実行表（初回FAIL 65 caseのうち）

| T | class | 初回 | 再実行 | 残存失敗の要約 |
|---|---|---|---|---|
| T2 | `NestedTransactionTest` | 0/5 | **5/5 PASS** | — |
| T3 | `GridMigrationSuccessTest` | 0/3 | **3/3 PASS** | — |
| T3 | `GridMigrationFailureTest` | 0/30 | 10/30（**20 FAIL**） | §6.6 D1 |
| T4 | `RestoreDbTaskSuccessPathTest` | 0/1 | 0/1 | **guard: 非default main-user serialが要求される（本機serial=0）**。oracle設計かlane環境の修正がowner判定事項 |
| T4 | `RestoreLeaseSerializationTest` | 6/11 | 8/11（**3 FAIL**） | `no such table: issue120_replacement_marker` x2、`db file must be deletable while helper is closed` x1（§6.6 D2） |
| T4 | `ModelWriterTransactionReentryTest` | 0/5 | **5/5 PASS** | — |
| T5 | `RealZipRestoreE2E` | 0/2 | 0/1 | `SQLiteReadOnlyDatabaseException (READONLY_DBMOVED)` at `DatabaseHelper.createEmptyDB` → `LauncherDbUtils.dropTable`（§6.6 D3） |
| T7 | `DeckRetirementMigrationInstrumentationTest` | 0/2 | **2/2 PASS** | — |
| T8 | capture 4 stage（Control/WidgetWindow/UnknownProvider/NoCallbacks） | 0/5 | 1/5（**4 FAIL**） | Control/WidgetWindow/UnknownProviderはD3と同一。NoCallbacksは1/2（1 case新規PASS） |
| T8 | `NovaRestoreGridApplicationTest` | 0/4 | 3/4（**1 FAIL**） | `applyGridInfoBindsConvertedDbNameForMismatchAndMatch`: 期待 `launcher_6_5_4` 実 `launcher_6_4_4`。anchorのgrid適用入口はpreset ceiling matchで、変換grid (6,5,4) presetが本AVDに無く列数が合わない — **G4のapplyGridInfo適応（preset経由）はforkの「exact DBGridInfo束縛」契約を表現できない**（§6.6 E2） |
| T8 | `NovaConverterBoundaryTest` / `SmartspaceOffTest` | 0/3 | 0/2 | D3と同一 |
| T8 | cross-process StageA / force-stop / StageB | crash+1 | **FAIL 1+1** | StageA: `restore completion barrier must persist a capture-valid workspace expected:<(0, 1)> but was:<(1, 0)>`（復元内容の不正、§6.6 D4）。StageB: 1 failure |

合計: **37 caseが新規にPASS**。残存FAILは32 case + StageA crash。§6.2のDI循環・§6.3のtest-infra・D0は全て解消済み。

### 6.6 再実行後に残存したproduction欠陥・判定事項（変更せず報告）

- **D0（解消済み, `7873c588d0`）**: 2番目のboot blocker。compile Rには存在する `GridDisplayOption_gridType` がruntime R（aapt2 link の `runtime_symbol_list` R.txt）のstyleable配列から欠落（attr ID `0x7f0402f6` はtableに存在）。AGP 9.4.1のvalues mergeがdeclare-styleable内定義のflag付きattrを落とす。bridge は意味的に中立（AOSP標準のresources scope定義+styleable内reference）。**owner判断: 本layoutの維持 or AGP upstream起票。**
- **D1（grid migration failure injection）**: `GridMigrationFailureTest` 20件。代表: `A simulated process death must escape normal RuntimeException compensation`（6件。注入したprocess death相当が汎用RuntimeException補償に捕捉される）、`expected:<RESTORE_FAILED> but was:<null>`（5件。失敗メタデータが記録されない）、delegate-then-throw系のreconciliation失敗（4件）、`expected:<3> but was:<4>`（2件）、`no such table: _issue59_target_favorites_backup`（1件）等。**S3移植版grid migrationのfailure-injection契約（spec 118 / #458 / #461）が成立していない疑い — owner分析が必須の最重要cluster。**
- **D2（restore lease）**: `RestoreLeaseSerializationTest` 3件。`issue120_replacement_marker` table不在（#120契約のmarker tableが移植 helperで作成されない）、helper close中のdb削除可否assert失敗。
- **D3（restore時のDB file lifecycle）**: `RealZipRestoreE2E` + Nova capture 6 class。restore経路でDB file移動後も旧接続が生きたまま `createEmptyDB` が書込みし `SQLITE_READONLY_DBMOVED`。lease/quiesce/cleanUpDatabasesの順序契約（#168/#299系）がanchor構造で未成立の疑い。
- **D4（restore内容）**: cross-process StageA/StageB。復元後workspaceの座標が不正（(0,1) vs (1,0)）。restore content契約のregression疑い。
- **D5（boot blocker, 未修正）**: launcher UI起動時に `ClassCastException: LawnchairLauncher cannot be cast to androidx.activity.ComponentActivity`（`LawnchairAlphabeticalAppsList.kt:42`。同型のcastが `WallpaperCarouselView.kt:41`）。**anchor 16-devは `BaseActivity` をframework `Activity` 継承に変更した一方、forkはlauncher contextへのandroidx `viewModels()` を2 viewで使用する。** anchor構造を正にする適応（LauncherへのViewModelStore提供等）かfork契約の復活かは層の契約判断を伴うため **§4.6相当のowner決定が必要**（model/DI循環とは異なり適応方式に選択肢がある）。boot smoke（承認事項5）の結果: `am start` はDI循環・R欠落を通過し `Launcher.onCreate` の `setupViews` まで到達するが、all_apps inflateでcrash。証跡: `build/g4-evidence2/boot-smoke.txt`。
- **E1（oracle設計）**: `RestoreDbTaskSuccessPathTest` は非default main-user serialを要求するが標準emulatorのmain userはserial 0。lane環境（secondary user作成）かoracle guardの修正がowner判定事項。
- **E2（G4適応の契約限界）**: §6.5表のとおり。anchorにexact DBGridInfo束縛の入口が無く、preset ceiling matchで代替したため、preset不在deviceで契約assertが成立しない。oracle前提のowner reviewが必要。

### 6.7 ADR/spec追認の要否（承認事項4の回答）

1. **`LauncherPrefsCommitTest` boolean seam削除 — 追認が必要**。#59の「集約commit boolean」契約はS2/S3のproduction adapt（`ModelDbController.writeGridPreferences` のreadback検証方式、ModelDbController.java:1027に記録済み）によりanchor seam上で表現不能となった。spec 59 ownerが「readback検証による代替を#59契約の正式な後継とする」ことを追認するか、boolean契約の復活（拡張）をrequireするかの判断を要する。replay-log §6.3の記録は維持。
2. **D5（BaseActivity base class）**: ADR-0018 Decision 9の及ぶ範囲明確化（model/DI層のみか、activity base classもanchor正とするか）をADR-0018改訂または補足ADRで決定する必要。
3. **D0（gridType bridge）とE2（applyGridInfo適応のsemantics）**: 実装review packetでの確認事項としてreviewerへ提示。

### 6.8 G4残存defect cluster修復（D1〜D5・E1・E2、2026-10-05）

- **修復head**=`bb14aeb0c1`（REBASE_HEAD=`7f46ab6466075ebf5a39f954cf3376d2968e6353` は不変。§6.5実行head `7873c588d0` から7 commit: `f6d525e56d` D5 / `d049f07370` D1 / `e0dc750668` D2+D3 / `0638101561` D4 / `697d1ad615` E2 / `20f1b68bc1` E1 / `bb14aeb0c1` NE）。emulator・command形式は§6.1と同一（per-class invocation）。証跡XML 13件＋boot smoke＋cross-process記録を `build/g4-evidence3/` に保存。`./gradlew spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` は最終headでgreen。
- すべてのproduction修正は契約移植の補完であり、ADR-0018 Decision 9（anchor構造を正）に依拠する。testのassert契約は緩めていない（E1は前提をエミュレータ再現可能にしただけで、guardを含む全assertを維持。NEは誤っていたrowid比較をより強い等値assertへ修正）。§6.5表の全FAIL caseが解消された。

#### 6.8.1 根因と修復（cluster順）

| cluster | 根因（確定） | 修復 | 検証 |
|---|---|---|---|
| D5 | anchor 16-dev `BaseActivity` はframework `Activity` 継承。forkの2 viewが `context as ComponentActivity).viewModels()` を要求し起動時 `ClassCastException` | owner決定どおりanchor構造を維持しfork契約を移植: `LawnchairLauncher` が `ViewModelStoreOwner` + `HasDefaultViewModelProviderFactory` を実装（androidx `ViewModelStore` をactivity destroyでclear、`AndroidViewModelFactory` 既定）、`app.lawnchair.viewModels()` 拡張を新設し、2 call siteは `ViewModelStoreOwner` castへ適応。androidx `LifecycleOwner` はanchor既存（`ActivityContext extends SavedStateRegistryOwner extends LifecycleOwner`、`BaseActivity` の `LifecycleRegistry`）で提供済み | boot smoke: `am start` 成功、`mCurrentFocus=LawnchairLauncher`、crash buffer空（`build/g4-evidence3/boot-smoke.txt`、最終headで再取得） |
| D1 | S3a移植版 `tryMigrateDB` がanchorのreset-to-empty fallback（`createEmptyDB` + `EMPTY_DATABASE_CREATED` 記録 + grid prefs上書き）をforkのpublic entryに持ち込んだ。fork契約（Issue #59 / spec 118監査表面）は「Migration failed: **retaining** launcher database」。失敗時にソースDBが破壊され、flag永続化で同一プロセス内の後続testが「new DB already created」早期bail → 20件の多様なfailure（journal未記録 / `expected:<3> but was:<4>` / process death未伝播） | `tryMigrateDB` をforkのretain契約へ復元（メトリクス送信のみ）。anchor reset pathは `attemptMigrateDb`（`gridMigrationRefactor` flag entry）に限定 | `GridMigrationFailureTest` **30/30 PASS**（10/30 → 30/30） |
| D2 | S3a移植版 `createDbIfNotExists` が2引数 `createDatabaseHelper(false, dbFile)` を直接呼び、forkのprotected 1引数seam（`RestoreLeaseSerializationTest.HelperProbeController` / `GridMigrationSuccessTest.Controller` がoverride）をバイパス。test doubleが実DBを開き `issue120_replacement_marker` 不在・file削除可否assert失敗 | `createDbIfNotExists` を1引数seam経由へ復元。既定実装は目的別derive（migration target = IDP grid state、active DB はD3の単一権威） | `RestoreLeaseSerializationTest` **11/11 PASS**（8/11 → 11/11） |
| D3 | active helperのdbFile束縛がanchor由来の「DB_FILE pref優先」で、forkのfile-lifecycle契約（`renameRestoredDb` / `migrateDbName` / `cleanUpDatabases` はすべて `idp.dbFile` 権威）と分裂。restore/grid-pref seamがDB_FILE prefを書くと `cleanUpDatabases` が開いたままのhelperのfileを削除 → 次の `createEmptyDB` で `SQLITE_READONLY_DBMOVED`（実機logcatで `launcher_5_4_4.db` 削除/active `launcher_6_4_4.db` を直接観測）。forkはDB_FILE prefをactive束縛に使わない（書き込み専用metadata） | active helperを `mIdp.dbFile` に束縛（単一権威） | `RealZipRestoreE2E` **2/2 PASS**、capture 4 class + boundary 2 class 計 **9/9 PASS** |
| D4 | S3 portで `LawnchairApp` のsupertypeが `LauncherApplication` から `Application` に変わった。`LauncherComponentProvider.get()` がfallback component（**`setSafeModeEnabled(true)` ハードコード**）を作り、アプリ全体がdagger safe modeで動作 → `WidgetInflater.inflateAppWidget` が全widget行を `TYPE_PENDING` 早期returnし、loader修復（allocate+bind+DB update）が不発。`widgetRowCount` (0,1)期待 vs (1,0)実測＝「座標入れ替わり」ではなくwidget修復不発（§6.6の座標読みは訂正） | `LawnchairApp : LauncherApplication()` へ復帰（anchor構造どおり）。real safe-mode flagがcomponentへ流れる | `NovaRestoreCaptureWidgetWindowTest` PASS（`widgetIdValid=1`、`bindAppWidgetId()` 成功をlogcatで確認）、cross-process **StageA/StageB ともOK**（force-stop跨ぎ） |
| E1 | oracle guard `assertNotEquals(0, serial)` が標準emulator（main-user serial=0）で恒久不成立。remap/default-column rebuildがno-op化する前提条件だった | fixtureが「旧device」を忠実に模擬: favorites既定profileIdを10に rebuild（production `changeDefaultColumn` と同一recipe）し、remapとrebuildをserial=0でも実行させる。guard・全8 assert契約は不変（guardは「live serial ≠ 旧device既定」を同じ形で主張） | `RestoreDbTaskSuccessPathTest` **1/1 PASS** |
| E2 | S3a適応がpreset ceiling round-trip（`setCurrentGrid(ceiling)` + `onConfigChanged`、converter側はdbFile直接代入のみ）で、preset不在gridのexact DBGridInfo束縛を表現できなかった | fork `IDP.applyGridInfo(context, DBGridInfo)`（Issue #168 bridge）をanchor IDPへ再移植（既存private `initGrid(gridName, dbGridInfo)` へのdelegate、exact束縛・listener非通知）。converterと2 oracleがproduction seamを直接呼ぶ | `NovaRestoreGridApplicationTest` **4/4 PASS**（3/4 → 4/4） |
| NE | `RealZipRestoreE2E.seedFavorite` が `insertOrThrow(...) >= 0` をassert。explicit `_id`（旧epoch markerは負id）では返却rowidが負になり構成的に不成立（§6.3と同種のtest-infra欠陥。T5 oracleは一度もgreenになっていなかった） | 要求rowidとの等値assertへ置換（旧比較より強い） | `RealZipRestoreE2E` **2/2 PASS**（最終headで再実行） |

#### 6.8.2 再実行成績（§6.5残存FAIL 32 case + StageA crash のうち）

| class | §6.5 | §6.8 |
|---|---|---|
| `GridMigrationFailureTest` | 10/30 | **30/30** |
| `RestoreLeaseSerializationTest` | 8/11 | **11/11** |
| `RealZipRestoreE2E` | 0/1 | **2/2** |
| `RestoreDbTaskSuccessPathTest` | 0/1（guard） | **1/1** |
| `NovaRestoreGridApplicationTest` | 3/4 | **4/4** |
| `NovaRestoreCaptureControlTest` / `WidgetWindowTest` / `UnknownProviderTest` / `NoCallbacksTest` | 1/5 | **5/5** |
| `NovaConverterBoundaryTest` / `SmartspaceOffTest` | 0/3 | **3/3** |
| cross-process StageA / StageB | FAIL | **OK / OK** |
| boot smoke | all_apps inflateでcrash | **launcher UI到達（crash buffer空）** |

合計: §6.5の残存FAIL 32 case + StageA/StageB + boot crash はすべて解消。`GridMigrationSuccessTest` 3/3を最終headで再確認（既存PASSのregressionなし）。

#### 6.8.3 判定の訂正と残置事項

1. **§6.6 D4の「座標(0,1)≠(1,0)」読みは訂正**: 実体は `widgetRowCount` の (negative, valid) 集合であり、復元workspaceの座標入れ替わりではない。D4の根因はsafe-mode component（上表）。Nova converterの座標丸め/rows補償（#522採用port）の数学は本実行では不変で正常。
2. **§6.6 D3の「lease/quiesce/cleanUpDatabases順序契約が未成立」仮説は訂正**: 順序契約（`prepareForRawFileRestore` quiesce→wipe、`runDbCleanupExclusively`）はanchor構造で成立していた。根因はヘルパー束縛の分裂（D2/D3）とsafe mode（D4）。
3. **E1の残置判断**: fixture-based旧device既定（10）により標準emulatorで成立した。二次userをlane側で作る方式は「非default main-user serial」をより直接に再現するが、CI権限・lane costのowner判断事項として残す（現行oracleはassert契約を満たす）。
4. **§6.7のowner追認事項は変わりなし**（`LauncherPrefsCommitTest` boolean seam追認 / D5のADR-0018範囲明確化 / D0 bridgeのreview確認）。D5はowner決定どおりanchor構造維持+fork契約移植で実装済みであり、§6.7-2のADR明確化は「activity base classもanchor正とする」方向の追認作業として残る。
5. **G5引継ぎ**: §6.4-1〜3に加え、本§の修復7 commitを含むheadでのCI実行（x86_64 pixel_7_pro）と、`Flags.gridMigrationRefactor()` 有効時の `attemptMigrateDb` reset pathがfork retain契約と二重に存在することの文書明確化（本§6.8.1 D1行）をreview packetへ含めること。


### 6.9 G5 CI初回実行の失敗と修復（2026-10-05）

- **対象CI run**: `37252942231`（workflow_dispatch、PR #535）。green: changes / check-style / organizer-unit-tests / validate-repo-contract / build-debug-apk / db-migration / production-input / method-choice / exchange-import / onboarding-proposal。**FAIL: shared-writer / manual-organization-ui / reservation-recovery**（各emulator lane、API 36 / google_apis / pixel_7_pro / x86_64）。
- **修復head**: `db28608f0c`（shared-writer）→ `075c3240d2`（reservation-recovery）→ `6f4f3bdc84`（manual-organization-ui）。REBASE_HEAD不変。検証はローカルG4環境（API 36 arm64 `issue108_api36_pixel_9_pro_fold`、per-class runner、wrapper同一entrypoint）。`spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` は最終headでgreen。
- **G4局部greenの訂正**: §6.5/§6.8のローカル実行は全laneの全classを網羅していない。今回失敗した3 class（`DirectEditModelWriterTest` / `SanitizerInstrumentationTest` / `ManualOrganizationProductionE2EInstrumentationTest`）はG4のローカル実行表に含まれておらず、「ローカルG4ではgreen」はこれらのclassについては未実行を意味していた。3件ともローカルarm64で再現した（CI環境固有ではない）。

#### 6.9.1 失敗classと根因（3根因）

| lane | 失敗class | 根因 | 分類 |
|---|---|---|---|
| shared-writer | `DirectEditModelWriterTest.deferredMoveRejectsStaleTargetWithoutWrite`（FAIL、コールバック内assertがloader threadでthrowしprocess crash、`directEditMoveDefersUntilOrganizerLeaseReleases` が実行されず欠落） | S2bのModelWriter anchor移植で `ModelTask.executeOnModelThread()` のIssue #14 coordinator gate（main: `runOrDefer(MODEL_WRITER)`）が脱落。gate無しではdirect-edit taskがorganizer lease保持中にstage-2 validatorを実行し、defers後もpre-deferral決定のまま競合書込みの上にDB writeが成功（ADR-0013 defer-stale契約違反） | production移植漏れ（契約移植の補完。test契約はADR-0013どおり不変） |
| reservation-recovery / manual-organization-ui | `SanitizerInstrumentationTest.correlatedReload...`（FAILED outcome）、`ManualOrganizationProductionE2EInstrumentationTest.manualRunUses...` / `recoveryConfirmation...`（`RecoveryFailed(MODEL_RELOAD_FAILED)`） | S2 commit `4081458f82` でcaptureが `itemsIdMap` 全体を走査するようになり、anchor loaderが毎load mergeする `PredictedContainerInfo`（非永続、itemType既定0=APPLICATION）を `WorkspaceItemInfo` にcastしてCCE → capture fail-closed → 全correlated reload失敗。#152のmodel-verifiable projectionはDB leg（非永続ref除外）とlike-with-likeで比較する契約であり、model legも非永続containerを除外するのが正 | production移植漏れ（同上） |
| manual-organization-ui | `ManualOrganizationProductionE2EInstrumentationTest.reservationlessLegacyTarget...`（`newFolders.single()` → `NoSuchElementException`） | 本classはLauncher activityをbindしないためloaderはsetUpが強制する初回loadでしか走らない。fresh app dataではその初回loadがanchor `attemptMigrateDb` reset（§6.8 D1で意図的に保持したgridMigrationRefactor path）+ `loadDefaultFavoritesIfNecessary` を同load内で実行し、default workspaceがfixture seedingと競合して plan inputに混入（diag: 15 item — default folder6+7 member、dock 4、fixtureは混在）。plannerは0 new folderを計画 | test環境前提の未追随（test側修正。全assert契約は不変） |

#### 6.9.2 修復（3 commit、いずれもfail-closed契約は緩めない）

1. `db28608f0c` — `ModelWriter.ModelTask.executeOnModelThread()` をmain形式の `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0, exactOrganizerToken=false, …)` gateへ復元（Issue #14契約の再移植）。検証: `DirectEditModelWriterTest` **6/6**（crashしていたcaseと欠落していたcaseの両方）、`ModelWriterTransactionReentryTest` / `DirectEditWriteShapeTest` / `DirectEditUndoModelWriterTest` / `HotseatRestoreAdmissionTest` green。
2. `075c3240d2` — `ModelProjectionCodec.capture` が `PredictedContainerInfo`（itemsIdMapとextraItems双方）を除外。DB legの `projectedToModelVerifiable` と対称。検証: `SanitizerInstrumentationTest` **2/2**（correlated reload COMPLETED）。
3. `6f4f3bdc84` — `ManualOrganizationProductionE2EInstrumentationTest.setUp` を「初回 `reloadAndWait()` でreset確定 → seeding前に `clearEmptyDbFlag()`」へ変更。検証: 同class **6/6**。

#### 6.9.3 残置リスク（G5報告）

1. **fail-fast per-classの未走査範囲**: CI run `37252942231` は各laneの最初の失敗classで停止したため、shared-writerの第3class以降・reservation-recoveryの第4class以降はanchor rebase後のCIを一度も通っていない。ローカル再確認は本§の対象classと隣接4 classのみ。残りclassの初回CI実行が次のdispatchで初めて行われる。
2. **manual-org laneのcomma filter**: `tools/ci/run-manual-organization-ui-instrumentation.sh` はcomma区切り12 class filterのまま（§6.4-2のanchor AGP `k=v,k=v` parse問題）。anchor AGP下では **第1class（本class）しかdispatchされない** — 本laneは第1classがgreenでも残り11 classが静かに未実行。per-class runnerへの切替（§6.4-2引継ぎ事項）はlane構成変更のためowner判断として残す。
3. **fresh app dataでのreset path**: `attemptMigrateDb` reset（EMPTY_DATABASE_CREATED + default workspace load）はfresh app dataでの初回loadごとに発生する（DB_FILE pref未書込みのため `isCompatible` がfalse → target==current → reset）。fresh install後の実質状態はdefault workspaceなので実害はないが、`Flags.gridMigrationRefactor()` 有効時のこの経路とfork retain契約（`tryMigrateDB`）の二重存在の文書明確化は§6.8.3-5のままowner review待ち。本§のtest修正はこの動作に依存しない形（reset済みであることを確認してからseeding）にした。


### 6.10 G5 CI x86_64 residual failuresの修復（2026-10-05）

- **対象CI run**: `37258841252`（workflow_dispatch、PR #535）。多数lane green。失敗は3 class: shared-writer `EditSurfaceUndoInstrumentationTest`（8/18）、reservation-recovery `LoaderCursorOverlapAcceptanceContractTest`（1/1、fail-fastで同lane第6class）、restore-capture `NovaConverterBoundarySmartspaceOffTest`（tearDown内、fail-fastで同lane最終class）。REBASE_HEAD=`7f46ab6466075ebf5a39f954cf3376d2968e6353` は不変。検証はローカルG4環境（API 36 arm64 `issue108_api36_pixel_9_pro_fold`、per-class runner）。x86_64最終確認は本headでの次回dispatch。
- **調査手法の記録**: CI artifacts（per-test logcat / XML）だけでは確定しなかったため、ローカルarm64で失敗を再現した上で、(a) poll timeout時の診断（module束縛の同一性・gate状態・直接capture）をtest error messageに一時組み込み、(b) `kill -3` thread dumpでスレッド状態を観測して根因を確定した。診断のうち恒久化に値するもの（capture例外の #172 observer接続、poll timeout時のtypedReason/gate/直接capture通知）はtestに残した。

#### 6.10.1 失敗classと根因

| lane | 失敗class | 根因（確定） | 分類 |
|---|---|---|---|
| shared-writer | `EditSurfaceUndoInstrumentationTest` 8/18（6件「no selectable item on the diagram」+ 2件undo表示がbusy） | **(1) production**: S2cの `LauncherAppState` ブリッジが、unscoped Daggerバインディングの **getInstance()呼び出し毎の新wrapper構築のたびに** `onLauncherAppStateCreated` を発火し、`LawnchairApp.layoutApplicationModule` を **gate IDLE・未reconcileの新module** で都度置換していた。edit-surfaceのprocess単一access（`ManualOrganizationModule.editSurfaceApplication`）は初回束縛時にgate IDLEのmoduleを掴み、`inspectCapture` が恒久fail-closed（null → capture_unavailable → 「no selectable item」）、recoverが恒久ConcurrentRun/WriterBusy → busy表示。診断で `sameModule=false, gate=IDLE, inspect=null(0ms)` を直接観測。 | production移植漏れ（S2c橋の「moduleはprocess単一instance」契約の復元。`LayoutApplicationModule` 自身の契約文言およびfork v15のonPostInit＝process1回に整合） |
| | | **(2) test**: pollループがinstrumentation thread（=main）で `Thread.sleep` し、activityの `runOnUiThread`（capture結果のdiagram反映）がpoll中に一度も実行されない。初回captureが(1)やstartup reconciliationとのmutex競合で失敗するとreopenがなく復旧不能。またundoのsingle-shot tryAcquire（RecoveryProtocol）がlauncher起動load・startup reconciliation・前回undoの相関reload待ちと競合しbusy表示になる。 | test堅牢化（reopenはproductionのreloadCapture seam、pollはmainを塞がない形。typed display・raw reason・zero-write assertは不変） |
| reservation-recovery | `LoaderCursorOverlapAcceptanceContractTest.loaderAcceptanceMatchesOrganizerPredicateForQsbRowOverlap`（expected true, was false） | loaderの判定（`LoaderCursor.checkItemPlacement` のoverlap許容）は `firstCached`（`PreferenceManager2` のin-memory cache）を読む。cacheはDataStore収集で **非同期** 更新であり、`setBlocking(true)` 直後のloader読取がCIエミュレータ上で旧値を観測する（1巡目 tolerance=false はcache元値と一致するため恒定PASS、2巡目 true のみ競合）。grid/display依存ではない（fixture cellはlive QSB geometryから導出済み）。 | test堅牢化（cache settle待ち。前提「policy=loaderが観測する値」をassert化。契約本体のassert不変） |
| restore-capture | `NovaConverterBoundarySmartspaceOffTest` tearDown `SQLITE_READONLY_DBMOVED` | **D3残置**: active helperのdbFile束縛（D3修正）は「生成時のlive IDP」のみで、生成後のIDP切替に追従しない。converter restoreが `applyConvertedGrid` でIDP dbFileを切替（CI pixel_7_pro既定 `launcher_5_4_4.db` → fixture `launcher_6_5_5.db`）した後、同一load内の `cleanUpDatabases`（IDP権威）がprocess helperの開いた旧fileを削除（logcatで `Deleting unmatched launcher database file: launcher_5_4_4.db (active: launcher_6_5_5.db)` を直接観測）→ tearDownの書込み（reset path `createEmptyDB` / `restoreFavorites`）でDBMOVED。ローカルfold AVDも同一grid遷移で再現条件一致（pre-fixローカルはhelper再open timingの競合で偶発green — CIは毒側に落ちた。再束縛は競合窗口そのものを除去する）。 | production移植漏れ（D3単一権威の完全化: 束縛のlive追従） |

#### 6.10.2 修復（4 commit）

1. **shared-writer cluster**: `LawnchairApp.onLauncherAppStateCreated` を冪等化（最初の構築でのみmoduleを生成し、以後のwrapper構築では維持。`activityHandler` 登録も1回化）+ `LayoutApplicationModule.inspectCapture` のcapture例外をIssue #172の `captureFailureObserver` へ接続（debug buildでcapture失敗がorganizer tagに現れる。composer capture経路と同契約）+ `EditSurfaceUndoInstrumentationTest` 堅牢化: (a) confirm flow前にproduction readiness gateのREADY待ち（FAILEDならreconcile再試行）、(b) pollをバックグラウンドスレッドへ移動（mainを塞がず、activityのcapture反映が走る）+ diagram null時の `recaptureForTest()`（production reopen seam）、(c) seedingをreload **generation** 待ちへ（`bindCompleteModel` latch。`isModelLoaded()` はreload中もtrueを返すため）、(d) undo前にmodel settle待ち、transient busy（WriterBusy/ConcurrentRun）時は同一session entryの再装填（2回tap相当）で再試行し最終displayのみassert、(e) 最初のundoのrecovery返却をobserverで待ってから2回目のundo、(f) shared store実態に合わせたraw reason期待値（MISSING→ALREADY_RESTORED）、(g) launcher instance依存を自前launchへ。
2. **reservation-recovery cluster**: `LoaderCursorOverlapAcceptanceContractTest` にcache settle待ち（`firstCached` がpinned値に収束するまでpoll、10s上限でassert）。
3. **restore-capture cluster**: `ModelDbController.migrateGridIfNeeded`（loaderのgrid reconciliation入口、writer/restore leaseの後ろにdeferされる）でactive helperをlive `mIdp.dbFile` へ再束縛（close→createDbIfNotExists再open）。SandboxContextと、overrideされた1引数seamで意図的にfixtureを束縛するgrid-migration test double（`mActiveHelperTracksLiveIdpFile` flagで識別）は対象外。
4. **docs**: 本節。

#### 6.10.3 再実行成績（ローカルarm64、per-class runner）

| class | 修復前 | 修復後 |
|---|---|---|
| `EditSurfaceUndoInstrumentationTest` | 10/18（CI 10/18と同一fail集合） | **18/18** |
| `LoaderCursorOverlapAcceptanceContractTest` | 0/1（CI 0/1） | **1/1** |
| `NovaConverterBoundarySmartspaceOffTest` | 0/1（CI 0/1） | **1/1**（`Rebinding the active helper to the live IDP dbFile: launcher_6_5_5.db -> launcher_5_4_4.db` をlogcatで確認。同runで `cleanUpDatabases` は閉じた後のfileのみ削除） |
| 回帰: `GridMigrationSuccessTest` / `GridMigrationFailureTest` | 3/3 / 30/30 | 3/3 / 30/30（初回実装ではFailureTest 1件が再束縛と干渉 → override seamのfixture bindingを対象外とするguard追加で解消。guard無しでの当該1件FAILとguard後30/30を両方確認） |
| 回帰: `ManualOrganizationProductionE2EInstrumentationTest` / `SanitizerInstrumentationTest` / `RestoreLeaseSerializationTest` / `EditSurfaceApplyInstrumentationTest` | 6/6 / 2/2 / 11/11 / 3/3 | 6/6 / 2/2 / 11/11 / 3/3 |
| `spotlessCheck` / `assemble` (app+androidTest) | green | green |

#### 6.10.4 残置リスク（G5報告）

1. **fail-fast per-classの未走査範囲（引継ぎ）**: reservation-recoveryは第6classで停止したため第7class以降（Issue265ManualEditRecovery等5 class）、shared-writerは第16classで停止したため第17〜18class（`HomeEditUndoAvailabilityInstrumentationTest` / `AppDestinationNoticeTest`）がanchor rebase後のCI未実行。次回dispatchで初走査。restore-captureは最終classまで到達済み。
2. **manual-org laneのcomma filter**: §6.9.3-2のまま（owner判断待ち）。
3. **`LauncherAppState` のunscoped binding自体はanchor構造どおり（Decision 9）**: getInstance()毎に新wrapperが生成される頻度は本節では変えていない。`onLauncherAppStateCreated` の冪等化のみで、Dagger構造・S2c橋の形状は不変。wrapper頻度自体の見直しはADR-0018範囲のowner判断事項。
4. **`inspectCapture` の #172 observer接続**: 本節の診断目的に加え、#172の「capture失敗は単一organizer tagに出る」契約のinspect seam適用漏れ補完でもある（debug buildのみ出力）。
5. **grid migration test doubleの再束縛除外**: `mActiveHelperTracksLiveIdpFile` flagは「overrideされた1引数seamのfixture binding」を再束縛から除外するための識別である。productionでは常にdefault seam経由なので挙動は変わらない。


### 6.11 G5 CI x86_64 residual failuresの修復 第2次 — Issue265ManualEditRecoveryInstrumentationTest（2026-10-05）

- **対象CI run**: `37271140884`（reservation-recovery lane）。同lane第6classまでfail-fastで停止していた§6.10の修復後、`Issue265ManualEditRecoveryInstrumentationTest` が **初回走査** で5/6失敗。全失敗は `IllegalStateException: model has no ItemInfo for row N`（N=20/27/33/39/51、throw箇所は `moveRowToDesktopViaWriter` 430行 / `moveRowToHotseatViaWriter` 461行 / `appPairSource...` 242行のlookup）。greenは `pathB_controlWithoutManualEdit` のみ（6 test中 `modelItemOf` を呼ばない唯一のtest）。REBASE_HEAD=`7f46ab6466075ebf5a39f954cf3376d2968e6353` は不変。修復は本commit（REBASE_HEAD対）。検証はローカルG4環境（API 36 arm64 `issue108_api36_pixel_9_pro_fold`、per-class runner、failure-capture wrapper同一entrypoint）。x86_64最終確認は次回dispatch。

#### 6.11.1 根因（2層）

| 層 | 内容 | 分類 |
|---|---|---|
| 1. test側API未追随（CI失敗の直接根因） | `modelItemOf` が反射で取得した `itemsIdMap` の値を `IntSparseArrayMap<*>` にcastしていた。S1のanchor model core復元（`de06b3da1f`、ADR-0018 Decision 9）で `BgDataModel.itemsIdMap` は旧java版の `public final IntSparseArrayMap<ItemInfo>` からKotlin版の `@JvmField val itemsIdMap: WorkspaceData`（`MutableWorkspaceData`）に変わっており、castは **lookup毎にClassCastException** → `catch (Throwable)` が握りつぶし（logcatに `model item lookup failed` 出力）→ `found=null` → `checkNotNull` が「model has no ItemInfo for row N」をthrow。対象rowはDBにもmodelにも存在し（pathBがgreen、直前のDB読取・organize Appliedも成立）、壊れていたのはlookup機構のみ。G4のcompile修復（`794db5dd50`）で検出されなかったのは、`IntSparseArrayMap` 型自体は現存するためcastがcompile成功する（実行時のみ失敗する）ため。 | test環境前提の未追随（test側修正。assert契約は不変） |
| 2. production移植漏れ（1の修復後に顕在化） | spec 269 D1/D3（受入済み）の「`ModelWriter.moveItemInDatabase` は既存 `WorkspaceItemInfo` iconのdesktop entryでin-memory spanとDB書込みを1×1へ正規化」がS1のanchor ModelWriter復元で脱落（pre-S1実装は `ca14f42a6a`）。CI runではlookup失敗が先に停止するため未発火。anchor loader（`WorkspaceItemProcessor` 176行）はiconをin-memory 1×1へ復元するためin-memory側assertは成立するが、正規化書込みが無いと `querySpan` がdesktop移動後もraw span（NULL / 2×2 / legacy値）を返し、legacy hotseat系・pathA/C・appPair系の `assertEquals(1, querySpan(...))` 契約を満たせない（assert連鎖から確定的）。 | production移植漏れ（契約移植の補完。spec 269 D1/D3どおり） |

#### 6.11.2 修復（1 commit）

1. **test**: `Issue265ManualEditRecoveryInstrumentationTest.modelItemOf` をanchor APIへ追随。`LauncherModel.mBgDataModel` の反射取得は維持（private constructor propertyで公開accessor無し）し、`BgDataModel` にcastして `itemsIdMap[rowId]`（`WorkspaceData.get`）で解決するよう変更。`checkNotNull` メッセージと以降のspan assert契約は不変。
2. **production**: `ModelWriter.moveItemInDatabase` へspec 269 D1/D3の正規化を再移植。`ca14f42a6a` のhunkと同一形（destinationが `CONTAINER_DESKTOP` かつ `WorkspaceItemInfo` の場合のみ、`notifyItemModified` 前にin-memory `spanX/spanY=1` を設定し、正規化時のみpending DB書込みへ `SPANX`/`SPANY` を追加。widget・folder・`moveItemsInDatabase`・hotseat中継・anchorの他writer methodは不変）。

#### 6.11.3 再実行成績（ローカルarm64、per-class runner、2026-10-05）

| class | 修復前 | 修復後 |
|---|---|---|
| `Issue265ManualEditRecoveryInstrumentationTest` | 1/6（CI run 37271140884と同一fail集合） | **6/6**（XML確認: pathA/pathB/pathC、legacy null/positive、appPairSource） |
| 回帰: `OverlapAcceptanceGateSeamInstrumentationTest` / `LoaderCursorOverlapAcceptanceContractTest` | 2/2 / 1/1 | 2/2 / 1/1（XML確認） |
| 回帰（2のproduction変更seam）: `DirectEditModelWriterTest` / `ModelWriterTransactionReentryTest` / `DirectEditWriteShapeTest` / `DirectEditUndoModelWriterTest` / `HotseatRestoreAdmissionTest` | green（§6.9.2-1検証set） | green（5 classともBUILD SUCCESSFUL、fail-fast契約exit 0。最終class `HotseatRestoreAdmissionTest` は14/14をXMLで確認） |
| `spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` | green | green |

#### 6.11.4 残置リスク（G5報告）

1. **reservation-recovery laneの残り未走査class**: 本classは同lane第7class。fail-fastのため第8class以降がanchor rebase後のCI未実行のまま（次回dispatchで初走査）。shared-writer第17〜18class（§6.10.4-1）も同様。
2. **x86_64最終確認**: 本節の修復のCI確認は次回dispatch（x86_64 lane）で行う。本節の実行はローカルarm64。
3. **desktop-entry正規化の将来干渉**: destination-desktopの `WorkspaceItemInfo` 移動は常に1×1で永続化される。anchorではiconはloaderが常に1×1で復元するため観測差は無いが、anchor側にicon resize等の将来変更が入る場合はspec 269 D1との整合をIssue #269側で再確認する。


## 7. G4完了（再実行）とS4準備（2026-10-04）

- **D1〜D5・E1・E2 すべて解消**、再実行は **全green**: GridMigrationFailure 30/30、Lease 11/11、RealZipRestoreE2E 2/2、SuccessPath 1/1、NovaGrid 4/4、capture 5/5、converter boundary 3/3、cross-process StageA/StageB OK、GridMigrationSuccess 3/3、Nested 5/5、WriterReentry 5/5、DeckRetirement 2/2、PrefsLegacy 2/2、PrefsCommit 1/1。
- **D5修復**: LawnchairLauncher を ViewModelStoreOwner + HasDefaultViewModelProviderFactory にし、fork契約（viewModels()）をanchor activity構造へ移植（ADR-0018 rev7 ①）。boot smoke OK。
- **D1修復**: tryMigrateDB をfork契約「Migration failed: retaining launcher database」（Issue #59/spec 118）へ復元。anchor reset pathは attemptMigrateDb に限定。
- **D2/D3**: helper構築をprotected seam経由へ復元、active helper束縛を mIdp.dbFile 単一権威へ復元（SQLITE_READONLY_DBMOVED 解消）。
- **D4**: LawnchairApp supertype復元（LauncherApplication）。safe-mode fallback component回避。
- **CI filter per-class化**: tools/ci/run-instrumentation-per-class.sh 新設、5 laneを同一class集合のままper-classへ。新規恒久laneなし。
- **文書同期**: ADR-0018 revision 7（activity base class = anchor正、#59 readback後継）、spec 452 revision note（DEFAULT_ORDER 10項目）、spec 59 revision note。
- 最終head: `cf76631499a9ac161742a722c2c7aacb7ad33db2`（REBASE_HEAD=7f46ab6466075ebf5a39f954cf3376d2968e6353）
- G1再確認: assemble + spotlessCheck **green**（本head）。G2: organizer unit gate 1935 tests green。G3: ownership inventory 570 path全分類PASS。


## 6.13. G5 CI compose-idle hangの恒久修復（2026-10-04、runs 37295512259/37299029278）

- `CategoryOverridePreferencesInstrumentationTest` の `cancelRestoresFocusAndLongAppLabelRemainsReachableAtTwoHundredPercentFontScale` がCI x86_64で2/3回、`performScrollToNode` のimplicit idle-waitに刺さり（200% fontScaleでcancel行がfold外）、per-class 20分capが作動（fail-fast+証跡。設計どおり）。
- 修復: scrollを明示的なboundedループ（waitUntil+page swipe、10秒上限、失敗時はunreachable-node契約違反としてassert）へ置換。到達可能性契約は不変。
- 同一commitでRuleChain/Timeoutラップ（`createComposeRule` のtest-thread契約を壊す）をrevert済み（`47a76a33e4`）。
- onboarding laneの `launcher-resume-timeout`（frontmost=NexusLauncher）はCI環境flakeとして1回記録（retryでgreen）。manual-orgの `IndexOutOfBoundsException` は1回のみでretry green（compose list差分、監視継続）。


## 6.14. bounded scrollの最終形（2026-10-05、run 37305919600）

- §6.13のpage-swipe方式は200% fontScaleでcancel行に届かず「not reachable after bounded scroll」（raw swipeはnested scrollableに当たらない場合がある）。
- 最終形: `performScrollToNode`（authoritative scroll機構）を10秒bounded `waitUntil` 内でretryし、到達できなければ既存のunreachable-node契約違反として失敗。失敗時も20分hangではなく即時に証跡付きで落ちる。
- 残る2 test（`targetUnavailableReturnsToFreshDestinationAndRestoresFocus` / `editorIsReadableAtTwoHundredPercentFontScale`）は `InterruptedException in Espresso.onIdle` — 他test methodのThread.interrupt伝播（ComposeTestRule環境）であり、method順序依存のflake。retryでgreen確認済み（run 37291814239では同class green）。監視継続。


## 6.15. CategoryOverrideの残hang（run 37310028169）とdispatcher撤去（2026-10-05）

- run 37310028169: `CategoryOverridePreferencesInstrumentationTest` の第1classが20分capに到達。activity-top evidenceは **NexusLauncherActivity が前面**（LawnchairLauncherはHOME resolveされていない）。test bodyに入る前のcompose初期化（idle同期）で停止。
- 判断: 6.14のscroll修正は保持。残余要因として `createComposeRule(effectContext = StandardTestDispatcher())` を撤去し、green実績のある `ManualOrganizationPreferencesInstrumentationTest` と同一の既定compose ruleへ統一（StandardTestDispatcherは本classで未使用、test意図不変）。
- HOME resolveの不一致（NexusLauncherが前面）はCI emulatorの初期状態起因であり、compose初期化のidle同期がHOME切替と交差して刺さる形。dispatcher撤去後も再現する場合は、次のowner判断事項（CI setupでのLawnchair既定HOME付与、lane分割）。
