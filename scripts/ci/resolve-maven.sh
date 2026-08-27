#!/usr/bin/env bash
# 解析出「这台 runner 上可用的 Maven 命令」，把结果打到 stdout。
#
# ## 为什么需要它
#
# 托管 ubuntu runner 自带 `mvn`；**自建 macOS runner 没有**（实测：那台机器上
# `mvn` / `docker` / `psql` 一个都没有，只有 homebrew 的 node/python）。
# `actions/setup-java` 只装 JDK，不装 Maven。
# ⇒ 自建上唯一能用的是仓库自带的 Maven wrapper `./mvnw`。
#
# ⚠️ 这段逻辑此前只写在 `jpa-gate-main.yml` 里。第二个工作流要用时，
#    复制一份就是形态 D（同一个东西两份，它一定会漂）⇒ 抽成这里一处。
#
# ## 用法
#
#     MVN=$(bash "$GITHUB_WORKSPACE/scripts/ci/resolve-maven.sh")
#     "$MVN" -B package ...
#
# ⚠️ **必须在 Maven 模块目录里调用**（`./mvnw` 是相对当前目录的）。
#    在别处调用会解析不到 wrapper，然后静默回退到系统 `mvn` —— 如果系统也没有
#    就报错退出，⛔ 不会给你一个「看起来能用」的错答案。
set -uo pipefail

if command -v mvn >/dev/null 2>&1; then
    echo mvn
elif [ -x ./mvnw ]; then
    echo ./mvnw
else
    echo "✗ 既没有 mvn 也没有可执行的 ./mvnw (cwd=$(pwd))" >&2
    exit 1
fi
