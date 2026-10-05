---
name: server-operations
description: cretas 服务器运维：6 台机器地图、SSH 登录、systemd 与启动依赖、日志、端口、蓝绿槽位判定、smartbi 库 schema 迁移规则、数据库备份、阿里云安全组、凭证文件位置。发布流程见 deploy-backend。
---

# 服务器运维

**最后更新**: 2026-10-05（部署入口并入 `deploy-backend`，去掉重复与过期的发布说明）

## 全局服务器地图（6 台）

| # | 地址 | 规格 | 角色 | 登录 | 权威文档 |
|---|---|---|---|---|---|
| 1 | `47.100.235.168` | 8C / 16GB / 100GB | **cretas 主** Java + Python + PG + Redis | `root` 默认 key | 本文件 |
| 2 | `139.196.165.140` | 4C / 8GB / 40GB | **cretas 网关** Nginx + Web-Admin + Showcase + Mall | `root` 默认 key | 本文件 |
| 3 | `106.14.165.234` | 2C / 3.5GB / 40GB | **vita-test** API 8085 + MySQL + 宝塔 | `root` + `~/.ssh/id_rsa` | `D:\pomelox` 的 deploy skill |
| 4 | `101.132.17.37` | 2C / 7.4GB / 40GB | **vita-prod** API (vitaglobal.icu) + MySQL | `root` + `~/.ssh/id_ed25519_prod` | `D:\pomelox` 的 deploy skill |
| 5 | `52.196.123.155` | 2C / 0.9GB / 38GB | **东京 tokyo-a** VPN 主节点 + 制品缓存 | `ubuntu` + `~/.ssh/ai-egress-tokyo-windows_ed25519` 或 `LightsailDefaultKeyPair-ap-northeast-1.pem` | 无 |
| 6 | `57.183.75.185` | 2C / 0.4GB / 19GB | **东京 tokyo-b** VPN 备节点 | `ubuntu` + `LightsailDefaultKeyPair-ap-northeast-1.pem` | 无 |

1–4 是阿里云，5–6 是 AWS Lightsail。

- 不在表里的：加拿大 Mac mini（自建 CI runner，见 `.claude/rules/ci-runner-routing.md`）和本机 Windows/RTX。YOLO / LIUSHANMEN 的筛查、训练与目标测试只在本机 Windows/RTX 跑（`scripts/windows/test-yolo-local.ps1`），不用 Mac mini；生产采集与门控上传的控制不变。
- 5 和 6 都在跑 VPN（openvpn / xray / arcway-agent），不是空闲备机，动它们会断网。
- 规格是快照，可用内存和磁盘当场重测（`nproc; free -h; df -h /; uptime`）。四台阿里云里有三台 systemd journal 没设上限，根治是设 `SystemMaxUse=`。

## 蓝绿双槽：查 47 上的 Java 之前先读 upstream

prod Java 在 `10010` / `10020` 之间交替 active。照着文档里的 `10010` 直接 curl，打到的可能正是停用的那一槽。哪槽在跑只看网关 upstream：

```bash
ssh root@139.196.165.140 'cat /www/server/panel/vhost/nginx/_upstream_cretas.conf'
# 例: server 47.100.235.168:10020;  # ACTIVE=10020 — auto-synced by deploy-backend.sh
```

别 grep `/www/server/panel/vhost/nginx/` 整个目录，只看 `*.conf`：目录里混着历史 `.conf.bak*`。2026-08-19 曾据此数出 6 处「转发到 10010」，而生效配置里一处都没有，差点报成「生产挂了」。

## 内容分布

| 内容 | 服务器 | 路径 | 访问 |
|---|---|---|---|
| Java 后端 | **47** | `/www/wwwroot/cretas/`（jar `aims-0.0.1-SNAPSHOT.jar`，`.env.prod`，`restart-prod.sh`，`logs/`；systemd 输出 `cretas-prod.log` / green 槽 `cretas-prod-green.log`） | 蓝绿 10010 / 10020 |
| Python 服务 | **47** | `/www/wwwroot/cretas/code/backend/python/`（venv38，应用日志 `python-service.log`；systemd 输出 `/www/wwwroot/cretas/python-prod.log`） | 8083 |
| Embedding gRPC | **47** | `/www/wwwroot/cretas/embedding-service/` | 9090 |
| ONNX 模型 | **47** | `/www/wwwroot/cretas/models/gte-base-zh-finetuned-onnx-fixed/` | |
| PostgreSQL / Redis | **47** | systemd | localhost only |
| **Web-Admin** | **139** | `/www/wwwroot/web-admin/` | 139:8086 / admin.cretaceousfuture.com |
| **Showcase** | **139** | `/www/wwwroot/showcase/cretaceousfuture/`（本地 `platform/`） | www.cretaceousfuture.com |
| Nginx 网关 | **139** | 宝塔 Nginx | API / Python → 47 |
| 餐饮平台模拟器 | **139** | `/www/wwwroot/mock-platform/`（服务 `cretas-mock-platform`，绑 `127.0.0.1:9200`，nginx 反代 `/mock/`） | 139/mock/ |
| Mall 后端 | **139** | `mall-backend` service | 8080 |

Showcase 和 mock-platform 只上 139，不上 47：模拟器扮演「外部世界」，放到 47 就破坏了隔离前提；而且 139 安全组只放行 80/443/8086，自开端口从 47 打过去一律 TIMEOUT。模拟器健康看 `generator: running`，光有 `status: ok` 不够。

## 环境

47 只跑 prod：主库 `cretas_prod_db`、SmartBI 库 `smartbi_prod_db`。test 环境 2026-07-13 下线（prod Java OOM 后为它腾出 ~1.5G；prod JVM `-Xmx3g`，`HeapDumpOnOutOfMemoryError` 已开，dump 落 `/www/wwwroot/cretas/logs/`）。业务测试环境当前分配以 memory `project_macmini_test_env_is_live_with_f006` 为准。cretas 的库已全部是 PostgreSQL，MySQL 只在 vita 两台上。

## systemd

| 服务 | 端口 | 单元 | 备注 |
|---|---|---|---|
| gRPC Embedding | 9090 | `cretas-embedding` | Java 依赖它（`After=cretas-embedding.service`） |
| Java 后端 | 10010 / 10020 | `cretas-backend` / `cretas-backend-green` | `EnvironmentFile=/www/wwwroot/cretas/.env.prod` |
| Python 服务 | 8083 | `cretas-python` | 环境变量内联（含 LLM 模型分配） |
| Redis / PostgreSQL | 6379 / 5432 | `redis` / `postgresql` | |

依赖链：`redis` + `postgresql` → `cretas-embedding` → `cretas-backend`；`cretas-python` 并行。生产环境变量集中在 `/www/wwwroot/cretas/.env.prod`（600）。Python 的模型配置在 systemd 单元里内联了一份，改模型要单元和 `.env.prod` 两处一起改；改完单元文件先 `systemctl daemon-reload` 再 `restart`。

```bash
ssh root@47.100.235.168 "systemctl status cretas-backend cretas-backend-green cretas-python cretas-embedding --no-pager"
ssh root@47.100.235.168 "journalctl -u cretas-backend --since '5 min ago' --no-pager"     # 或 -green, 看 active 槽
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|10020|8083|9090|6379'"
ssh root@47.100.235.168 "df -h && free -h"
curl -s http://47.100.235.168:<active 槽>/api/mobile/health; curl -s http://47.100.235.168:8083/health
```

发布、切流和单组件部署都走 `deploy-backend` skill 里的入口。手工 `systemctl restart` 只用于明确的故障处置，且先确认 active 槽位。

## Smartbi 数据库 schema 变更

`smartbi_prod_db` 的 schema 变更走迁移文件，不手动跑 DDL。原因：2026-05-06 有八个迁移漏跑 prod，canary 4 小时后才发现 9 个 error；由 runner 统一 apply 才能保证 prod 与仓库一致、可追溯。

做法：

1. 写 `backend/python/smartbi/database/migrations/V<YYYYMMDD>_<NN>__<description>.sql`。
2. 通过 `./scripts/deploy/deploy-smartbi-python.sh --env prod` 自动 apply。runner 失败时 deploy ABORT，Python 不重启。

紧急 hotfix 不得不 `ssh + psql -f` 时，事后立刻把 SQL 落成 `V*.sql` 提交，并手动 INSERT 进 `smartbi_migrations`（`filename, version, checksum, applied_by='manual-emergency'`，`ON CONFLICT (filename) DO NOTHING`），否则下次 runner 重新 apply 会报 DDL 已存在。tracker 的主键是 `filename` 不是 version（历史上 `V20260427_01` 有两个不同文件）。runner 自身有 bug 时的逃生门是 `SKIP_MIGRATIONS=1 ./scripts/deploy/deploy-smartbi-python.sh --env prod`，用完立刻修 runner。

Java 侧 Flyway 撞号闸见 `worktree-and-main-only-deploy.md`（离线协议第 3 条）。

## 数据库备份

`47:/www/wwwroot/cretas/scripts/backup-db.sh`，cron `0 3 * * *`。本地 `/www/wwwroot/cretas/backups` 保留 7 天；异地 `oss://cretas-db-backups/cretas-prod/` 保留 30 天，只备 `cretas_prod_db` + `smartbi_prod_db`（凭证 `47:/root/.ossutilconfig`，内网 endpoint `oss-cn-shanghai-internal`；上传后比对 `Content-Length` 与本地大小）。

```bash
ssh root@47.100.235.168 "sudo -u postgres psql cretas_prod_db -c '\dt'"
ssh root@47.100.235.168 "sudo -u postgres pg_dump cretas_prod_db > /tmp/cretas_backup_$(date +%Y%m%d).sql"
```

## 阿里云安全组

AK/SK、安全组 ID、已放行端口清单见本目录 `aliyun-credentials.md`（gitignored）。

```bash
aliyun ecs DescribeSecurityGroupAttribute --RegionId cn-shanghai --SecurityGroupId <sg-id>
aliyun ecs AuthorizeSecurityGroup --RegionId cn-shanghai --SecurityGroupId <sg-id> --IpProtocol tcp --PortRange 8083/8083 --SourceCidrIp <你的IP>/32
```

临时白名单用完 `RevokeSecurityGroup` 收回；访问后端优先 SSH tunnel（`ssh -L 10010:localhost:<active 槽> root@47.100.235.168`）。

## 故障排查

| 症状 | 排查 |
|---|---|
| Java 无法启动 | 先读 upstream 确定该看哪槽 → `journalctl -u cretas-backend(-green) -n 100` → `env | grep DB_` → `ss -tlnp | grep 1001` |
| Python 502 / 500 | `ps aux | grep uvicorn` → `tail -50 /www/wwwroot/cretas/python-prod.log` → `ss -tlnp | grep 8083` |
| 前端白屏 | 浏览器 Console 看 API 地址 / CORS → 139 上 `nginx -t` → `ls /www/wwwroot/web-admin/index.html` → nginx 日志 |
| DB 连接失败 | `systemctl status postgresql` → `pg_hba.conf` → `.env.prod` 密码 |
| 磁盘满 | `du -sh /www/wwwroot/cretas/logs/* | sort -rh | head`；jar 备份 `.bak.*` 自动只留 3 份，历史命名的要手动清 |

`/www/wwwroot` 下的目录先确认内容再删，那里同时放着 prod 制品、备份和配置。

## 凭证

本目录 `db-credentials.md` / `aliyun-credentials.md`（gitignored）：数据库密码、服务器登录、宝塔面板、阿里云 AccessKey。需要时直接读。真值不写进任何会 commit 的文件，包括审查文档里的「引用原文」：曾经有审查文档引用原文把宝塔口令带回 tracked 树，secret 闸还两次全绿。
