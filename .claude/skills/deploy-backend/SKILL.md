---
name: deploy-backend
description: 全栈部署工作流。当用户说"部署"、"deploy"、"发布"、"上线"、"回滚"时触发。Java 后端 / Python 服务 / Web 前端的统一发布入口、单组件入口、成败判据与逃生路。
---

# 部署

prod 只从 clean 且 `HEAD == origin/main` 的树部署，原因和 GitHub 不可用时的离线协议见 `.claude/rules/worktree-and-main-only-deploy.md`。常规部署验证绿就部，不再请示（Steve 2026-08-21）；判据照常一条不省。服务器上的东西放在哪、怎么查，见 `server-operations` skill。

## 统一发布入口（Java / Web）

先 build 再合并（Steve 2026-09-15）。唯一一次构建放在合并之前，合并后的发布窗口只剩约 1 分钟，main 在这期间被别人推进而让发布作废的机会就小很多。

```bash
# build 阶段：合并前，在干净的已审查候选 worktree（HEAD = PR head）。构建 + 把 Java 制品预传到服务器缓存，
# 不安装、不重启、不切流。manifest 按 backend/java/cretas-api 的 git tree hash 记账。
./scripts/deploy/release-cretas.sh --phase build --base-sha '<dispatch Base SHA>' --tests '<Maven 选择器>' --stage-backend YES-STAGE
# deploy 阶段：合并后立刻，在 clean 的 origin/main 检出点。命中 build 阶段的 manifest ⇒ build=reused、零 Maven、零上传，≈1 min
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<同上选择器>' --no-prefer-ci-artifact --confirm-prod YES-PROD
```

不跑 `prewarm-main-artifact.sh`，也不打开 `--prefer-ci-artifact`：CI 制品车道在本组织不通（制品存储配额满、私有仓无 attestation），等它只会白等几分钟再回落本地构建。来历见 `references/lock-and-history.md`。

合并别人的 PR（本机没建过那棵树）时，在合并后的 main 上先跑 build 阶段再跑 deploy 阶段。

复用条件，三条同时成立才 `reused`；任一不成立就回退本地 Maven（≈4 min），脚本会明说 `RELEASE_BUILD_MODE=java-fallback`：

1. 合并后 `origin/main:backend/java/cretas-api` 的 tree hash 等于 build 阶段时的 tree hash，也就是 build 到合并之间没有别的后端改动落 main。合并前 `git fetch && git log --oneline <base>..origin/main -- backend/java/cretas-api` 为空即可；不为空就在合并后的 main 上重跑 build 阶段。
2. 两个阶段的 `--tests` 选择器完全一致。
3. 本机 `~/.cache/cretas/java-deploy/current/release-jar.manifest` 没被别的树的 build 覆盖。并发发布线各用各的 `CRETAS_JAVA_RELEASE_MANIFEST`。

判据：`DEPLOY_EXIT=0` 且日志里 `RELEASE_FINAL_STATUS` 恰好 1 次。不出现就是失败：收尾 printf 在 `set -e` 下会被掐掉，后台任务通知里的 exit code 也不可信。

- 被 exact-main 闸拒绝是静默的：BUILD SUCCESS、测试全绿，然后戛然而止，回执 `deploy_mode=none`。处理是 `git fetch && git checkout --detach origin/main` 后重跑。
- 回执里 `build="reused"` / `java_build=0` / `build_count=0` 是正常成功。deploy 阶段的 validate 不比对测试选择器，要审计选择器看 manifest 的 `target_tests`。

部署后：入口已调用 `verify-release.sh`（真实 upstream / systemd / 直连健康 / Web 四方哈希）并写进回执，回执完整且成功时不必重复手工检查；单独验证用 `./scripts/deploy/verify-release.sh --target backend|web-admin|all --env prod`。然后按 measurement-and-wiring 硬约束 2，在运行中的制品里 grep 这次改动的 ASCII 标记，并真跑一次那条路径。

## 蓝绿 / 锁 / 并行

- prod Java 在 `10010` / `10020` 之间交替 active，部署或核对前先读网关 upstream，不要假设哪一槽长期停用。命令和坑见 `server-operations` 的「蓝绿双槽」一节。
- `deploy-backend.sh` 的 idle readiness 三态（空串是 ssh 断线没量到，不是挂了）见 `worktree-and-main-only-deploy.md` 铁律 3。
- 三把本机锁 `cretas-release` / `cretas-backend-deploy` / `cretas-web-admin-deploy`。被拒时拒绝信息会区分「真并发」和「孤儿子进程按着锁」，处置步骤见 `references/lock-and-history.md`。不要 `rm` 锁文件：flock 模式下那只会造出第二把互不可见的锁。
- 默认串行；只有显式 `--parallel-if-independent YES-INDEPENDENT-SERVICES`，且迁移 / Entity / Security / 跨端契约风险检测全部未命中时才并行。

## 单组件 / 排查入口

| 目标 | 命令 | 备注 |
|---|---|---|
| Java | `./scripts/deploy/deploy-backend.sh --env prod`（`--rollback` 回滚最近备份） | Maven 打包 → rsync（scp 兜底）→ 备份 → 蓝绿 → 健康检查。本地 `cretas-backend-system-1.0.0.jar`，服务器 `aims-0.0.1-SNAPSHOT.jar`；服务器默认 javac 是 17，JAVA_HOME 要显式指 Dragonwell 21 |
| Python | `./scripts/deploy/deploy-smartbi-python.sh --env prod` | rsync 增量 → `pip install` → migration runner（失败即 ABORT、不重启）→ 重启 → `curl :8083/health`。不覆盖服务器 `.env`；smartbi schema 变更规则见 `server-operations` |
| Web | `./scripts/deploy/deploy-web-admin.sh --env prod` | web-admin 在 139（`/www/wwwroot/web-admin/`），不在 47。npm build → tar+scp 原子替换，避免新旧 chunk 混合；手动 scp dist 会丢权限变 403，也容易传错服务器；nginx 无需重启 |
| mock-platform | `./scripts/deploy/deploy-mock-platform.sh` | 只上 139 |
| 只重启 | `ssh root@47.100.235.168 "systemctl restart cretas-python"` | 只用于故障处置，先确认 active 槽 |

不要手拼 rsync / ssh 部署：那会绕过备份、蓝绿切流和健康检查。test 环境 2026-07-13 已下线（`--env test` / 10011 / 8084 不可用；脚本若打印「防御 ping test 异常」可忽略）。

## 本地开发

本地后端用 `mvn spring-boot:run`，不用 `java -jar`：fat jar 会被 mmap 锁住，之后 `mvn package` 的 repackage 无法 rename，deploy 失败。

```bash
cd backend/java/cretas-api && DB_PASSWORD="$DB_PASSWORD" POSTGRES_SMARTBI_PASSWORD="$POSTGRES_SMARTBI_PASSWORD" \
JAVA_HOME="C:/Program Files/Zulu/zulu-21" SPRING_PROFILES_ACTIVE=pg,dev SPRING_JPA_HIBERNATE_DDL_AUTO=none \
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/cretas_db?sslmode=disable" SERVER_PORT=10010 \
./mvnw.cmd spring-boot:run -Dmaven.test.skip=true -Dspring-boot.run.jvmArguments="-Xms512m -Xmx1280m"
```

## 部署失败排查

| 症状 | 处理 |
|---|---|
| 健康检查超时 | Spring Boot 冷启动慢，等 60s 重试 |
| JAR 上传 MD5 不匹配 | 网络不稳，重跑 `deploy-backend.sh` |
| 服务起不来 / 500 / 白屏 | 按 `server-operations` 的「故障排查」表查日志 |
