# Build and Verification

> Status: Verified
> Baseline: `43a21b43d7cc7850ab54e14b1a57dc9646685f35`
> Verified: 2026-10-13

## Required toolchain

| Tool | Verified version |
|---|---|
| JDK | 21.0.12（Homebrew openjdk@21） |
| Gradle Wrapper | 9.8.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |
| Android SDK Platform | 37（minor 2。ローカルSDK dir名 `android-37.2`） |
| Android SDK Build Tools | 37.0.0 |
| Android SDK Platform Tools | 37.0.1 |
| Source JVM target | 21 |

build configは targetSdk 37 / minSdk 26 / compileSdk 37（`compileSdk { release(37) { minorApiLevel 2 } }`）である（`build.gradle` の `allprojects` blockで一括指定、`libs.versions.toml` の `jdkRelease = "21"` がJava/Kotlin両targetの正本）。

上流GitHub ActionsもZulu JDK 21を使用する。Gradleはrepositoryのwrapperを使い、system Gradleを使わない。

## Checkout

```bash
git clone --recursive https://github.com/nunu1733/NunuLauncher.git
cd NunuLauncher
git submodule status --recursive
```

既存checkoutでsubmoduleが欠ける場合:

```bash
git submodule update --init --recursive
```

baselineのsubmodule `platform_frameworks_libs_systemui` はcommit `7d9e92bda50359dc66a530248e15f9748343524e`（upstream 16-dev側pin）である。fork旧pin `6a11ef767998885838a599331b5485f768b3d725` が持つicon shadow修正は16-dev側pinにも内容一致（diff 0行）で含まれるため、pin選択の停止・ADR改訂は不要と判断した（[Phase 2 replay-log §0](../../specs/516-16-rebase-phase2/replay-log.md)、ADR-0018 Decision 2）。

## Android SDK packages

SDK managerで最低限次を導入する。

```text
platform-tools
platforms;android-37.2
build-tools;37.0.0
```

`JAVA_HOME` はJDK 21、`ANDROID_HOME` / `ANDROID_SDK_ROOT` はこれらを導入したSDK rootを指すようにする。個人の絶対pathをrepositoryへcommitしない。

## Required checks

```bash
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
python3 tools/repo-contract/test_validate_high_risk_evidence.py
```

organizer unit test commandは `.github/workflows/ci.yml` の `organizer-unit-tests` job（Issue #41）がsource PRで同じsurfaceを実行する。repo-contract validatorと高リスクgate validatorのself-testは、同じく `ci.yml` の `validate-repo-contract` job（Issue #8、#43）が全PRで実行する。localでの詳細は [quality-strategy.md](./quality-strategy.md) を参照する。

## Planner generated-property runs

The normal planner property run is intentionally bounded and deterministic:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests '*PlannerGeneratedPropertyTest*'
```

To explore a larger reproducible corpus locally, provide a fixed seed and a
positive test-only count:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests '*PlannerGeneratedPropertyTest*' \
  -Dplanner.seed=1314213461 \
  -Dplanner.count=512
```

To rerun one failure, add its zero-based case index. For a non-default count,
retain the count in the command so the selected case exists in the same
corpus:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests '*PlannerGeneratedPropertyTest*' \
  -Dplanner.seed=1314213461 \
  -Dplanner.case=511 \
  -Dplanner.count=512
```

The dedicated `Planner stress` workflow runs the fixed 8 × 512 matrix (4,096
cases) weekly and through manual dispatch. It complements, but does not
replace, the fast 64-case PR evidence. The complete matrix and its seeds are
defined in [Issue #46's spec](../../specs/46-reproducible-planner-stress-coverage/spec.md).

Repository documentation、Issue form、required project filesを変更した場合は、同じcheckoutで次も実行する。

```bash
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
```

localにPyYAMLがない場合、Issue formは構造smoke checkとなる。GitHub ActionsはPyYAMLを導入して完全なYAML parseを行う。

生成APK:

```text
build/outputs/apk/lawnWithQuickstepGithub/debug/
```

## Baseline evidence

macOS 26.5.2 arm64、Homebrew OpenJDK 21.0.12、Android command-line tools環境で確認した（2026-10-13、ADR-0018 Phase 4 cutoverの統合head `20f7b9f33d` に対するgate実行。Phase 4 gate log: G1 `spotlessCheck` + `assembleLawnWithQuickstepGithubDebug`、G2 unit gate、S gates。`./gradlew spotlessCheck` 単独の所要時間は今回分離計測していないため要実測）。

| Command | Result |
|---|---|
| `./gradlew spotlessCheck assembleLawnWithQuickstepGithubDebug`（G1 gate） | `BUILD SUCCESSFUL` in 22m 30s |
| `./gradlew assembleLawnWithQuickstepGithubDebug spotlessCheck testLawnWithQuickstepGithubDebugUnitTest --tests ...`（S gates。CI `organizer-unit-tests` jobと同一filter集合） | `BUILD SUCCESSFUL` in 8m 4s |
| organizer unit test（G2 gate、同一filter集合での実行時） | `BUILD SUCCESSFUL` in 5m 44s。うち `app.lawnchair.organizer.*` は1705 tests / 0 failures / 0 skipped（160 classes、2026-10-13実測） |

unit testの実測（2026-10-13）はCI `organizer-unit-tests` jobと同一のfilter集合で実行したものである（`-Pnunu.excludeAiExchangeUnitTests` 指定なし。凍結中のAI exchange suiteを含む、やや広い表面）。15 baseline時代の2026-08-14計測値 377 testsは当時の `app.lawnchair.organizer.*` のみの表面である。

生成物 `Lawnchair.16.Dev.(20f7b9f).github.debug.apk` の検証時SHA-256は `c5ae1ae0d859352be07c4991f33cb849bac2a99a7daa64e7852f90c0c46633e8` だった（`build/outputs/apk/lawnWithQuickstepGithub/debug/`）。APKはbuild artifactでありcommitしない。

## Known upstream warnings

baselineはAGP built-in Kotlin migration、manifest namespace、deprecated API、translation format等のwarningを出すが、上記checksは成功する。warning解消をbootstrap PRへ混ぜず、behavior/riskが明確な別Issueで扱う。

commandが失敗した場合、最初にJDK version、SDK packages、submodule SHA、baseline commitを確認する。環境差を隠すためにsourceを変更しない。

### `NoSuchFieldError: No field $stable`の出た場合の診断手順

Compose/Kotlin toolchainが更新された直後のローカルビルドで、実行時に `java.lang.NoSuchFieldError: No field $stable ... in class ...` が出た場合、まず「stale/incremental出力+build cache起因」（Issue #537と同型）を疑う。ただし同じ例外signatureはsource/dependency binary mismatchや別のtoolchain defectでも起こり得るため、この手順だけでsource defectを結論づけない。判定は2段に分ける:

```bash
# 第1段: up-to-date判定とbuild cacheを迂回してtaskを再実行する
./gradlew :compileLawnWithQuickstepGithubDebugKotlin --rerun-tasks
# 第2段: build cacheを消す（GRADLE_USER_HOMEを独自設定している場合はその配下のcaches/build-cache-1）
rm -rf ~/.gradle/caches/build-cache-1
```

そのうえで同一source・同一コマンドで生成したビルドを再実測する。**例外が消えた場合のみ**障害系の判定へ進む:

- `--rerun-tasks`だけで治った場合 → 「再生成で治るstale/incremental/generated output系の残留」まで判定できる（旧toolchain出力のincremental残留を含む）。
- cache削除単独で治った場合、または対象compile taskの`FROM-CACHE`行を観測できた場合 → build cacheが古い出力のcarrierだったことまで疑える。

改善しない場合はsource・dependency・toolchainの通常調査へ戻る。実例と証跡は[Issue #537の調査記録](../assessment/issue-537-general-route-crash-investigation.md)を参照する。
