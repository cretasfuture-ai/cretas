---
name: deploy-backend
description: 全栈部署工作流。当用户说"部署"、"deploy"、"发布"、"上线"、"回滚"时触发。Java 后端 / Python 服务 / Web 前端的统一发布入口、单组件入口、成败判据与逃生路。
---

# 部署 Skill

只从 clean 且 `HEAD == origin/main` 的树部署 prod（`.claude/rules/worktree-and-main-only-deploy.md`）。常规部署不问「要不要部」：验证绿就部（Steve 2026-08-21 裁定）；判据一条不省。

## 统一发布入口（Java / Web 常规发布）

```bash
# ① 合并前（干净的已审查候选 worktree）：唯一一次构建 + Java 制品预上传，不安装 / 不重启 / 不切流
./scripts/deploy/release-cretas.sh --phase build --base-sha '<dispatch Base SHA>' --tests '<Maven 选择器>' --stage-backend YES-STAGE
# ② 合并后先预热（幂等，可丢后台）：把 CI 制品的跨境运输挪出发布窗口；看到 PREWARM=done / already-warm 再往下
#    ⚠️ PREWARM=unavailable（rc=3）= 本组织这条车道结构性不可用（私有仓签不上 attestation，ECS 按设计拒收）：
#       **照常往下发布**，构建走本地 Maven（慢约 4 分钟但正确）。⛔ 不要重跑预热、也不要因此停下。
./scripts/deploy/prewarm-main-artifact.sh --tests '<同③选择器或更窄>' --wait 420
# ③ 发布：自动检测 Java/Web 范围，复用 manifest / tree cache / CI 制品，部署或安全 no-op，输出 JSON 回执
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<tests>' --confirm-prod YES-PROD
```

**判据**：`DEPLOY_EXIT=0` 且日志里 `RELEASE_FINAL_STATUS` 恰好 1 次（不出现就是失败：收尾 printf 在 `set -e` 下被掐掉；后台任务通知里的 exit code 不可信）。被 exact-main 闸拒绝是**静默**的：BUILD SUCCESS、测试全绿、然后戛然而止，回执 `deploy_mode=none` → `git fetch && git checkout --detach origin/main` 重跑。回执里 `build="reused"` / `java_build=0` / `build_count=0` 是正常成功（复用可信 manifest，选择器一变必重编）；但 deploy 阶段的 validate 不比对测试选择器，审计选择器要看 manifest 的 `target_tests`。

**为什么 ② 不能省**：预热后 Java 零次 Maven ∥ Web 19s ≈ 25s，不预热 ≈ 204s；main 上相邻合并常 ≤4 分钟，200s 的本地构建期间 origin/main 被推进就整体作废（07-30、07-31 各一次）。本机刚建过这棵树时预热看不出效果（validate 直接命中本地 manifest），价值在合并别人的 PR 之后。

**部署后**：入口已调 `verify-release.sh`（真实 upstream / systemd / 直连健康 / Web 四方哈希）写入回执，回执完整且成功时不重复手工检查；单独验证 `./scripts/deploy/verify-release.sh --target backend|web-admin|all --env prod`。再按 measurement-and-wiring 硬约束 2：在运行中的制品里 grep 这次改动的 ASCII 标记，并真跑一次那条路径。

## 蓝绿 / 锁 / 并行

- prod Java `10010` / `10020` 交替 active，部署 / 核对前必读 `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`，禁止假设某槽永久停用。
- `deploy-backend.sh` 的 idle readiness 判据三态：`UP:*` 健康；`TIMEOUT` / `RESTART_LIMIT:*` 真失败；空串 = ssh 断线没量到（脚本先换连接重试，再独立核验；2026-09-02 曾把断线当挂了触发回滚）。
- 三把本机锁：`cretas-release` / `cretas-backend-deploy` / `cretas-web-admin-deploy`（flock 模式，Linux/macOS；Windows Git Bash 无 flock 走 PID 文件模式，那里 stale PID 自动回收）。被拒时看拒绝信息分两种：(a)「另一个 deploy 进程持有锁, PID x 活着」= 真并发，等它；(b)「登记持有者已死 / 已正常释放, 但锁仍被按着」= 它的孤儿子进程（rsync/ssh/mvn）继承了 fd 200 还没退，拒绝信息会逐行列出 `pid:cmdline`，并给出可直接复制的 `kill <pids>` 与一行复核（打印 `checked` 且无 `still alive` 即可重发）。逃生路：先看每行 cmdline 里 rsync/ssh 的目标，确认孤儿不再写生产制品；⚠️ **pid 会被系统回收再分配**，拒绝信息里那份 cmdline 是「拒绝那一刻」的快照，几分钟后再粘贴时同一个数字可能已经是别的进程 —— 所以先跑拒绝信息给的 `ps -o pid,args -p <pids>` 确认还是那几个孤儿，**再** `kill`，然后跑复核行，最后重发。⛔ 任何模式下都不要 `rm` 锁文件：flock 模式 rm 只会造出第二把互不可见的锁（5/30 形状）；正常释放由 `release_deploy_lock` 只关 fd、不 unlink。
- 默认安全串行；只有显式 `--parallel-if-independent YES-INDEPENDENT-SERVICES` 且迁移 / Entity / Security / 跨端契约风险检测全部未命中才并行。

## 单组件 / 排查入口

| 目标 | 命令 | 备注 |
|---|---|---|
| Java | `./scripts/deploy/deploy-backend.sh --env prod`（`--rollback` 回滚最近备份） | Maven 打包 → rsync（scp 兜底）→ 备份 → 蓝绿 → 健康检查。本地 `cretas-backend-system-1.0.0.jar`，服务器 `aims-0.0.1-SNAPSHOT.jar`；服务器 JAVA_HOME 必须显式指 Dragonwell 21（默认 javac 是 17） |
| Python | `./scripts/deploy/deploy-smartbi-python.sh --env prod` | rsync 增量 → `pip install` → migration runner（失败即 ABORT、不重启）→ 重启 → `curl :8083/health`。⛔ 不覆盖服务器 `.env`；smartbi schema 变更硬规则见 server-operations |
| Web | `./scripts/deploy/deploy-web-admin.sh --env prod` | web-admin 在 **139**（`/www/wwwroot/web-admin/`，139:8086 / admin.cretaceousfuture.com），不在 47。npm build → tar+scp 原子替换（避免新旧 chunk 混合）；❌ 不手动 scp dist（丢权限 → 403，易传错服务器）；nginx 无需重启 |
| 只重启 | `ssh root@47.100.235.168 "systemctl restart cretas-python"` | 手工重启只用于故障处置，先确认 active 槽 |

test 环境 2026-07-13 已下线（`--env test` / 10011 / 8084 不可用；脚本若仍打印「防御 ping test 异常」忽略即可）。旧「服务器拉分支现场编译」路径已删除，生产发布必须走可信制品。

## 本地开发

本地后端用 `mvn spring-boot:run`，⛔ 不用 `java -jar`（fat jar 被 mmap 锁住，`mvn package` 的 repackage 无法 rename，deploy 必失败）：

```bash
cd backend/java/cretas-api && DB_PASSWORD="$DB_PASSWORD" POSTGRES_SMARTBI_PASSWORD="$POSTGRES_SMARTBI_PASSWORD" \
JAVA_HOME="C:/Program Files/Zulu/zulu-21" SPRING_PROFILES_ACTIVE=pg,dev SPRING_JPA_HIBERNATE_DDL_AUTO=none \
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/cretas_db?sslmode=disable" SERVER_PORT=10010 \
./mvnw.cmd spring-boot:run -Dmaven.test.skip=true -Dspring-boot.run.jvmArguments="-Xms512m -Xmx1280m"
```

`ProcessWorkReportingServiceImplTest` 签名不匹配，本地打包用 `-Dmaven.test.skip=true`（`-DskipTests` 只跳执行不跳编译）。

## 东京中转不可达时（AmneziaVPN 停用）

症状 `PREWARM=failed` / `WEB_CI_ARTIFACT_UNAVAILABLE reason=tokyo_stage_failed`，底下 `Connection closed by 10.66.66.1 port 22`。别读成基础设施故障：`10.66.66.1` 是东京主机在隧道内的私网地址，公网 SSH `52.196.123.155` 一直通（ED25519 指纹已核对一致）；缓存服务绑在 `10.66.66.1:18081` 不监听回环，用端口转发：

```bash
KEY="$USERPROFILE/.ssh/ai-egress-tokyo-windows_ed25519"
ssh -f -N -L 18081:10.66.66.1:18081 -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes ubuntu@52.196.123.155
CRETAS_TOKYO_SSH_HOST='ubuntu@52.196.123.155' CRETAS_TOKYO_CACHE_ENDPOINT='http://127.0.0.1:18081/artifacts' ./scripts/deploy/prewarm-main-artifact.sh --tests '<tests>' --wait 420
```

⛔ 不启停 AmneziaVPN / Clash·Mihomo，不改 sshd / 安全组 / 密钥；指纹对不上就停，⛔ 不用 `StrictHostKeyChecking=no`。`WEB_CI_ARTIFACT=already-cached` 不证明运输链通（可能被本地 by-tree 缓存短路），要看到 `used`。

制品链路 `GitHub → 东京 Lightsail → 上海 OSS → ECS 内网`（`Publish-GitHubArtifactViaLightsailOss.ps1`，字节不经过本机）是常规默认路径；未命中才回退本地 Maven + rsync delta（两条路各有占优场景）。`transport_verified` ≠ `deployable_trust_verified`。细节见 `scripts/deploy/RELEASE-ARTIFACT-TRANSPORT.md`。

## 故障排查

| 症状 | 看哪 | 常见原因 |
|---|---|---|
| Java 启动后立即退出 | `journalctl -u cretas-backend -n 100`（或 `-green`，看 active 槽） | DB 密码错、端口占用 |
| Python 500 | `/www/wwwroot/cretas/code/backend/python/python-services.log` | 依赖缺失、`.env` |
| 前端白屏 | 浏览器 Console | API 地址、CORS、nginx |
| 健康检查超时 | 等 60s 重试 | Spring Boot 冷启动 |
| JAR 上传 MD5 不匹配 | 重跑 `deploy-backend.sh` | 网络不稳 |
