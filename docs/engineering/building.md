# Build and Verification

> Status: Verified
> Baseline: `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Verified: 2026-08-14

## Required toolchain

| Tool | Verified version |
|---|---|
| JDK | 21.0.12 |
| Gradle Wrapper | 9.3.0 |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.2.21 |
| Android SDK Platform | 36.1 |
| Android SDK Build Tools | 36.1.0 |
| Android SDK Platform Tools | 37.0.1 |
| Source JVM target | 17 |

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

baselineのsubmodule `platform_frameworks_libs_systemui` はcommit `6a11ef767998885838a599331b5485f768b3d725` である。

## Android SDK packages

SDK managerで最低限次を導入する。

```text
platform-tools
platforms;android-36.1
build-tools;36.1.0
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

macOS 26.5.2 arm64、Homebrew OpenJDK 21.0.12、Android command-line tools環境で確認した。

| Command | Result |
|---|---|
| `./gradlew --version` | Gradle 9.3.0 / JVM 21.0.12 |
| `./gradlew spotlessCheck` | `BUILD SUCCESSFUL` in 1m 39s |
| `./gradlew assembleLawnWithQuickstepGithubDebug` | `BUILD SUCCESSFUL` in 3m 22s |

organizer unit test commandは2026-08-14に同じ環境で確認した（`BUILD SUCCESSFUL` in 3m 5s、377 tests / 0 failures、Issue #41）。

生成物 `Lawnchair.15.Dev.(505dbc4).github.debug.apk` の検証時SHA-256は `197dc5e826d8107a6dfc8f253f26d409a1e36152c71d9e31ab643a84e2fa6404` だった。APKはbuild artifactでありcommitしない。

## Known upstream warnings

baselineはAGP built-in Kotlin migration、manifest namespace、deprecated API、translation format等のwarningを出すが、上記checksは成功する。warning解消をbootstrap PRへ混ぜず、behavior/riskが明確な別Issueで扱う。

commandが失敗した場合、最初にJDK version、SDK packages、submodule SHA、baseline commitを確認する。環境差を隠すためにsourceを変更しない。
