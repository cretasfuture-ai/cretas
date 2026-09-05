---
name: server-operations
description: cretas 服务器运维规范(47.100.235.168 主服务器 + 139.196.165.140 网关), 内含全部 6 台机器的全局地图(cretas 2 台 + pomelo-x 2 台 + AWS 东京 VPN 2 台)。触发: SSH 上服务器; 部署/切流/健康检查/看日志/查端口/磁盘内存; systemd 服务与启动依赖链; 蓝绿槽位; smartbi 数据库 schema 变更硬规则(migration runner, 禁手动 psql DDL); 环境变量 .env.prod; 阿里云安全组; 数据库备份; 查服务器/数据库/阿里云凭证。凡涉及"服务器/部署/运维/systemd/日志/migration/凭证/安全组"必读。
---

# 服务器运维规范

**最后更新**: 2026-09-05（合并原 `aliyun-operations` skill；删下线的 test 环境与历史叙事）

## 全局服务器地图（6 台）

| # | 地址 | 规格 | 角色 | 登录 | 权威文档 |
|---|---|---|---|---|---|
| 1 | `47.100.235.168` | 8C / 16GB / 100GB | **cretas 主** Java + Python + PG + Redis | `root` 默认 key | 本文件 |
| 2 | `139.196.165.140` | 4C / 8GB / 40GB | **cretas 网关** Nginx + Web-Admin + Showcase + Mall | `root` 默认 key | 本文件 |
| 3 | `106.14.165.234` | 2C / 3.5GB / 40GB | **vita-test** API 8085 + MySQL + 宝塔 | `root` + `~/.ssh/id_rsa` | `D:\pomelox` 的 deploy skill |
| 4 | `101.132.17.37` | 2C / 7.4GB / 40GB | **vita-prod** API (vitaglobal.icu) + MySQL | `root` + `~/.ssh/id_ed25519_prod` | `D:\pomelox` 的 deploy skill |
| 5 | `52.196.123.155` | 2C / 0.9GB / 38GB | **东京 tokyo-a** VPN 主节点 + 制品缓存 | `ubuntu` + `~/.ssh/ai-egress-tokyo-windows_ed25519` 或 `LightsailDefaultKeyPair-ap-northeast-1.pem` | 无 |
| 6 | `57.183.75.185` | 2C / 0.4GB / 19GB | **东京 tokyo-b** VPN 备节点 | `ubuntu` + `LightsailDefaultKeyPair-ap-northeast-1.pem` | 无 |

1–4 阿里云，5–6 AWS Lightsail。⛔ 5 和 6 都在跑 VPN（openvpn / xray / arcway-agent），不是空闲备机，动它们 = 断网风险。规格是快照，可用内存 / 磁盘当场重测（`nproc; free -h; df -h /; uptime`）；四台里三台 systemd journal 无上限，根治是设 `SystemMaxUse=`。

## 🔴 蓝绿双槽：查 47 上的 Java 之前先读 upstream

prod Java 是 `10010` / `10020` 交替 active。**不要**照本文出现的 `10010` 直接 curl，那可能正是停用的那一槽；哪槽在跑的唯一权威是网关 upstream：

```bash
ssh root@139.196.165.140 'cat /www/server/panel/vhost/nginx/_upstream_cretas.conf'
# 例: server 47.100.235.168:10020;  # ACTIVE=10020 — auto-synced by deploy-backend.sh
```

⛔ 别 grep `/www/server/panel/vhost/nginx/` 整个目录：里面混着历史 `.conf.bak*`，只看 `*.conf`（2026-08-19 曾据此数出 6 处「转发到 10010」而生效配置里一处都没有，差点报「生产挂了」）。

## 内容分布（禁止搞混）

| 内容 | 服务器 | 路径 | 访问 |
|---|---|---|---|
| Java 后端 | **47** | `/www/wwwroot/cretas/`（jar `aims-0.0.1-SNAPSHOT.jar`，`.env.prod`，`restart-prod.sh`，`cretas-prod.log`，`logs/`） | 蓝绿 10010 / 10020 |
| Python 服务 | **47** | `/www/wwwroot/cretas/code/backend/python/`（venv38，`python-services.log`） | 8083 |
| Embedding gRPC | **47** | `/www/wwwroot/cretas/embedding-service/` | 9090 |
| ONNX 模型 | **47** | `/www/wwwroot/cretas/models/gte-base-zh-finetuned-onnx-fixed/` | |
| PostgreSQL / Redis | **47** | systemd | localhost only |
| **Web-Admin** | **139** | `/www/wwwroot/web-admin/` | 139:8086 / admin.cretaceousfuture.com |
| **Showcase** | **139** | `/www/wwwroot/showcase/cretaceousfuture/`（本地 `platform/`） | www.cretaceousfuture.com |
| Nginx 网关 | **139** | 宝塔 Nginx | API / Python → 47 |
| 餐饮平台模拟器 | **139** | `/www/wwwroot/mock-platform/`（服务 `cretas-mock-platform`，绑 `127.0.0.1:9200`，nginx 反代 `/mock/`） | 139/mock/ |
| Mall 后端 | **139** | `mall-backend` service | 8080 |

Showcase 与 mock-platform **只上 139，绝不上 47**（模拟器扮演「外部世界」，放到 47 破坏隔离前提；139 安全组只放行 80/443/8086，自开端口从 47 打过去一律 TIMEOUT）。模拟器健康判据用 `generator: running`，不能只看 `status: ok`。

## 环境

47 只跑 **prod**：主库 `cretas_prod_db`、SmartBI 库 `smartbi_prod_db`。test 环境 2026-07-13 下线（内存紧张，prod Java OOM 后腾出 ~1.5G；prod JVM `-Xmx3g`，`HeapDumpOnOutOfMemoryError` 已开，dump 落 `/www/wwwroot/cretas/logs/`）。现在的测试环境是加拿大 Mac mini，见 memory `project_macmini_test_env_is_live_with_f006`。

## systemd

| 服务 | 端口 | 单元 | 备注 |
|---|---|---|---|
| gRPC Embedding | 9090 | `cretas-embedding` | Java 依赖它（`After=cretas-embedding.service`） |
| Java 后端 | 10010 / 10020 | `cretas-backend` / `cretas-backend-green` | `EnvironmentFile=/www/wwwroot/cretas/.env.prod` |
| Python 服务 | 8083 | `cretas-python` | 环境变量内联（含 LLM 模型分配） |
| Redis / PostgreSQL | 6379 / 5432 | `redis` / `postgresql` | |

依赖链：`redis` + `postgresql` → `cretas-embedding` → `cretas-backend`；`cretas-python` 并行。生产环境变量集中在 `/www/wwwroot/cretas/.env.prod`（600）；改模型配置要同时改 systemd 单元与 `.env.prod`；改单元文件后 `systemctl daemon-reload` 再 `restart`。

```bash
ssh root@47.100.235.168 "systemctl status cretas-backend cretas-backend-green cretas-python cretas-embedding --no-pager"
ssh root@47.100.235.168 "journalctl -u cretas-backend --since '5 min ago' --no-pager"     # 或 -green, 看 active 槽
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|10020|8083|9090|6379'"
ssh root@47.100.235.168 "df -h && free -h"
curl -s http://47.100.235.168:<active 槽>/api/mobile/health; curl -s http://47.100.235.168:8083/health
```

常规发布和切流必须走仓库发布入口（见 `deploy-backend` skill）；手工 `systemctl restart` 只用于明确的故障处置，且先确认 active 槽位。

## 部署入口

| 目标 | 命令 |
|---|---|
| Java / Web 常规发布 | `./scripts/deploy/release-cretas.sh --phase build|deploy --base-sha <SHA> --tests '<tests>' --confirm-prod YES-PROD`（deploy 阶段强制 clean HEAD == origin/main；合并后先 `prewarm-main-artifact.sh`） |
| 发布证据（只读） | `./scripts/deploy/verify-release.sh --target backend|web-admin|all --env prod` |
| Java 单组件 / 排查 | `./scripts/deploy/deploy-backend.sh --env prod` |
| Python | `./scripts/deploy/deploy-smartbi-python.sh --env prod` |
| Web | `./scripts/deploy/deploy-web-admin.sh --env prod` |
| mock-platform | `./scripts/deploy/deploy-mock-platform.sh` |

禁止手动 rsync / ssh 拼命令部署。传输主通道 rsync（scp 兜底）；CI 制品走 `GitHub → 东京 Lightsail → 上海 OSS → ECS 内网`（`Publish-GitHubArtifactViaLightsailOss.ps1`），细节 `scripts/deploy/RELEASE-ARTIFACT-TRANSPORT.md`。R2 / OSS 上传通道默认禁用（`ENABLE_R2=1` 紧急 opt-in）；⛔ 47 出网到 Cloudflare R2 极慢（10MB 200 秒没传完，阿里云 OSS 内网 1 秒），不要统一到 R2。

## ⛔ Smartbi 数据库 schema 变更（HARD RULE）

**所有** smartbi 库（`smartbi_prod_db`）schema 变更必须：
1. 写 `backend/python/smartbi/database/migrations/V<YYYYMMDD>_<NN>__<description>.sql`
2. 通过 `./scripts/deploy/deploy-smartbi-python.sh --env prod` 自动 apply（runner 失败 → deploy ABORT，Python 不重启）

**禁止**手动 `ssh + psql -f` 跑 DDL，除非紧急 hotfix；hotfix 后必须立即把 SQL 落 `V*.sql` 提交，并手动 INSERT 进 `smartbi_migrations`（`filename, version, checksum, applied_by='manual-emergency'`，`ON CONFLICT (filename) DO NOTHING`），否则下次 runner re-apply 会报 DDL 已存在。逃生门 `SKIP_MIGRATIONS=1 ./scripts/deploy/deploy-smartbi-python.sh --env prod`（runner 自身有 bug 时），用完立刻修 runner。Tracker PRIMARY KEY 是 `filename` 不是 version（历史上 `V20260427_01` 有两个不同文件）。起因：2026-05-06 八个迁移漏跑 prod，canary 4 小时后才发现 9 个 error。

Java 侧 Flyway：合并后、部署前 `mvn -o test -Dtest='FlywayVersionUniquenessTest,*RepositoryQueryValidationTest'`（git 对撞号一个字不报）。

## 数据库备份

`47:/www/wwwroot/cretas/scripts/backup-db.sh`，cron `0 3 * * *`；本地 `/www/wwwroot/cretas/backups` 保留 7 天；异地 `oss://cretas-db-backups/cretas-prod/` 保留 30 天（只备 `cretas_prod_db` + `smartbi_prod_db`；凭证 `47:/root/.ossutilconfig`，内网 endpoint `oss-cn-shanghai-internal`；上传后比对 `Content-Length` 与本地大小）。

```bash
ssh root@47.100.235.168 "sudo -u postgres psql cretas_prod_db -c '\dt'"
ssh root@47.100.235.168 "sudo -u postgres pg_dump cretas_prod_db > /tmp/cretas_backup_$(date +%Y%m%d).sql"
```

## 阿里云安全组

AK/SK、安全组 ID、已放行端口清单见 `aliyun-credentials.md`（gitignored）。

```bash
aliyun ecs DescribeSecurityGroupAttribute --RegionId cn-shanghai --SecurityGroupId <sg-id>
aliyun ecs AuthorizeSecurityGroup --RegionId cn-shanghai --SecurityGroupId <sg-id> --IpProtocol tcp --PortRange 8083/8083 --SourceCidrIp <你的IP>/32
```

临时开白名单用完 `RevokeSecurityGroup` 收回；访问后端优先 SSH tunnel（`ssh -L 10010:localhost:<active 槽> root@47.100.235.168`）。

## 故障排查

| 症状 | 排查 |
|---|---|
| Java 无法启动 | 先读 upstream 知道该看哪槽 → `journalctl -u cretas-backend(-green) -n 100` → `env | grep DB_` → `ss -tlnp | grep 1001` |
| Python 502 | `ps aux | grep uvicorn` → `tail -50 python-services.log` → `ss -tlnp | grep 8083` |
| 前端白屏 | 139 上 `nginx -t` → `ls /www/wwwroot/web-admin/index.html` → nginx 日志 |
| DB 连接失败 | `systemctl status postgresql` → `pg_hba.conf` → `.env.prod` 密码 |
| 磁盘满 | `du -sh /www/wwwroot/cretas/logs/* | sort -rh | head`；jar 备份 `.bak.*` 自动只留 3 份，历史命名的要手动清 |

## 注意事项

- 不要直接删 `/www/wwwroot` 下的目录，先确认内容。
- 数据库已是 PostgreSQL，不再有 MySQL。
- 本地启动 Java 用 `mvn spring-boot:run` 不用 `java -jar`（后者 mmap 锁 fat jar 阻断 deploy）。

## 凭证

本目录 `db-credentials.md` / `aliyun-credentials.md`（gitignored）：数据库密码、服务器登录、宝塔面板、阿里云 AccessKey。需要时直接读，⛔ 不把真值写进任何会 commit 的文件（含审查文档里的「引用原文」）。
