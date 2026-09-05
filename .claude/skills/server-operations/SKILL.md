---
name: server-operations
description: cretas 服务器运维规范(47.100.235.168 主服务器 + 139.196.165.140 网关)。**内含全部 6 台机器的全局地图**(cretas 2 台 + pomelo-x vita-test/vita-prod 2 台 + AWS Lightsail 东京 VPN 2 台):要问"我们有哪些服务器/哪台闲/用哪把 key 登录/放新服务选哪台"先看本 skill 的「全局服务器地图」。触发场景:SSH 上服务器操作;部署 Java 后端/Python 服务/Web-Admin/Showcase(内容分布禁止搞混,Showcase 只上 139);systemd 服务管理(cretas-backend/cretas-python/cretas-embedding)与启动依赖链;健康检查/看日志/查端口/磁盘内存;双环境(prod 10010/8083,test 已于 2026-07-13 下线);smartbi 数据库 schema 变更硬规则(migration runner,禁手动 psql DDL);环境变量 .env.prod;rsync/scp 传输规范;查服务器/数据库/阿里云凭证。凡涉及"服务器/部署/运维/systemd/日志/migration/凭证"必读本 skill。
---

# 服务器运维规范

**最后更新**: 2026-08-19

## 全局服务器地图 (6 台)

⚠️ **本节存在的理由**: 2026-08-19 要选一台机器放自建 git 服务时, 全仓找不到一份
完整清单 —— cretas 的两台在本文件里, pomelo-x 的两台在**另一个仓** (`D:\pomelox`),
东京两台**哪都没有**, 只能靠 `known_hosts` 里翻 IP + 逐把 key 试登录才认出来。

| # | 地址 | 规格 | 角色 | 登录 | 权威文档 |
|---|---|---|---|---|---|
| 1 | `47.100.235.168` | 8C / 16GB / 100GB | **cretas 主** Java+Python+PG+Redis | `root` 默认 key | 本文件 |
| 2 | `139.196.165.140` | 4C / 8GB / 40GB | **cretas 网关** Nginx+Web-Admin+Showcase | `root` 默认 key | 本文件 |
| 3 | `106.14.165.234` | 2C / 3.5GB / 40GB | **vita-test** 测试 API 8085 + MySQL + 宝塔 | `root` + `~/.ssh/id_rsa` | `D:\pomelox` 的 deploy skill |
| 4 | `101.132.17.37` | 2C / 7.4GB / 40GB | **vita-prod** 生产 API (vitaglobal.icu) + MySQL | `root` + `~/.ssh/id_ed25519_prod` | `D:\pomelox` 的 deploy skill |
| 5 | `52.196.123.155` | 2C / 0.9GB / 38GB | **东京 tokyo-a** VPN 主节点 (openvpn/xray/wg + cloudflared) | `ubuntu` + `~/.ssh/ai-egress-tokyo-windows_ed25519` **或** `LightsailDefaultKeyPair-ap-northeast-1.pem` | 无 |
| 6 | `57.183.75.185` | 2C / 0.4GB / 19GB | **东京 tokyo-b** VPN 备节点 (openvpn/xray/arcway-agent) | `ubuntu` + `LightsailDefaultKeyPair-ap-northeast-1.pem` | 无 |

1~4 是阿里云, 5~6 是 AWS Lightsail (ap-northeast-1)。

🔴 **查 47 上的 Java 之前先看这一条**: prod Java 是**蓝绿双槽** `10010` / `10020` 交替 active,
**不要**照本文其它地方出现的 `10010` 直接 curl —— 那可能正是停用的那一槽。
当前哪一槽在跑, 唯一权威是网关的 upstream:

```bash
ssh root@139.196.165.140 'cat /www/server/panel/vhost/nginx/_upstream_cretas.conf'
# 例: server 47.100.235.168:10020;  # ACTIVE=10020 — auto-synced by deploy-backend.sh
```

⚠️ 2026-08-19 实测教训: 照开头表格里的 `10010` 去 curl, 读到 `000 无响应` +
`cretas-backend.service inactive`, 差点报出「生产挂了」——**实际 green 槽在 10020 跑得好好的,
零重启零错误, 网关 upstream 也正确指着它**。详见本文第 272 行那条「禁止假设某槽永久停用」。
⛔ 另外**别 grep `/www/server/panel/vhost/nginx/` 整个目录**: 里面混着历史 `.conf.bak*`,
同一轮里我因此数出 6 处「转发到 10010」, 而**生效配置里一处都没有**。只看 `*.conf`。

⛔ **5 和 6 都在跑 VPN, 不是空闲备机** —— 一度以为 6 是"备用机", 实测它跑着
`tokyo-openvpn` / `xray` / `arcway-agent`。动这两台 = 断网风险。

### 规格是快照, 会过期 —— 用量当场重测

⛔ 上表的"可用内存/磁盘"不写进来, 因为那是会变的。要现状就跑:

```bash
for h in 47.100.235.168 139.196.165.140; do
  echo "── $h ──"
  ssh root@$h 'nproc; free -h|awk "NR==2{print \$2\" 总/\"\$7\" 可用\"}"; df -h /|awk "NR==2{print \$2\" 总/\"\$4\" 可用/\"\$5}"; uptime|sed "s/.*load average: /负载 /"'
done
ssh -i ~/.ssh/id_rsa root@106.14.165.234 '...'          # 同上
ssh -i ~/.ssh/id_ed25519_prod root@101.132.17.37 '...'  # 同上
ssh -i ~/.ssh/LightsailDefaultKeyPair-ap-northeast-1.pem ubuntu@52.196.123.155 '...'
ssh -i ~/.ssh/LightsailDefaultKeyPair-ap-northeast-1.pem ubuntu@57.183.75.185 '...'
```

### 2026-08-19 快照 (磁盘普遍偏紧)

| 服务器 | 磁盘可用 | 已用 | 负载 | 主要可回收 |
|---|---|---|---|---|
| 47 | 30 GB | 69% | 14% | `/tmp` 4.0G、docker 7.2G、journal 1.0G |
| 139 | **12 GB** | 69% | 2% | journal 2.0G、`/var/log` 2.2G、`.Recycle_bin` 641M |
| 106 | **8.9 GB** | 77% | **50%** | journal **3.7G**、`/var/log` 4.2G |
| 101 | **8.8 GB** | 77% | 2% | journal **3.5G**、`/var/log` 3.8G |

🔴 **四台里三台的 systemd journal 无上限** (1.0/2.0/3.7/3.5 GB)。一次性清理治标,
根治是设 `SystemMaxUse=`, 否则几周后照样涨回来。

## 数据库备份 (2026-08-19 起有异地副本)

| | |
|---|---|
| 脚本 | `47:/www/wwwroot/cretas/scripts/backup-db.sh`, cron `0 3 * * *` |
| 本地 | `/www/wwwroot/cretas/backups`, 保留 **7 天** (原为 2 天) |
| 异地 | **阿里云 OSS `oss://cretas-db-backups/cretas-prod/`**, 保留 30 天 |
| 上传哪些 | 只有 `cretas_prod_db` + `smartbi_prod_db`。⛔ test 库不做异地容灾 |
| 凭证 | `47:/root/.ossutilconfig` (已有), 内网 endpoint `oss-cn-shanghai-internal` |
| 校验 | 上传后比对 `Content-Length` 与本地 `stat -c %s`, 不一致算失败 |

⚠️ **改动前的风险**: 备份只在 47 本机, 47 丢了数据库和备份**一起没**。

### 🔴 不要试着改用 Cloudflare R2 —— 从 47 传不动 (2026-08-19 实测)

pomelo-x 的 101 用 R2 用得好好的, 很容易想到照搬。**同机对照实测**:

| 10 MB 上传 | 结果 |
|---|---|
| 47 → Cloudflare R2 | **200 秒没传完** |
| 101 → Cloudflare R2 | 78 秒 (131 KB/s), 可用 |
| **47 → 阿里云 OSS 内网** | **1 秒 (10 MB/s)** |

不是配置问题, 是 47 那条出网链路。⇒ **47 走 OSS, 101 保持 R2, 不要统一。**

⚠️ 试 R2 那次留下 **4 个未完成的分片上传**(会持续计费), 已 abort。
若以后再动 R2, 记得 `aws s3api list-multipart-uploads` 收尾。

## 服务器架构 (cretas 自己的两台)

| 服务器 | IP | 配置 | 用途 | 状态 |
|--------|-----|------|------|------|
| **新服务器 (主)** | `47.100.235.168` | 8C/16GB/100GB | Java + Python + DB | 运行中 |
| **旧服务器 (网关)** | `139.196.165.140` | 4C/8GB/40GB | Nginx 网关 + Web-Admin + Showcase 静态站 | 运行中 |

### 内容分布 — 禁止搞混

| 内容 | 所在服务器 | 路径 | 域名 |
|------|-----------|------|------|
| Java 后端 | **47** (新) | `/www/wwwroot/cretas/` | `47.100.235.168:10010` |
| Python 服务 | **47** (新) | `/www/wwwroot/cretas/code/backend/python/` | `47.100.235.168:8083` |
| PostgreSQL / Redis | **47** (新) | systemd 管理 | localhost only |
| **Web-Admin 前端** | **139** (网关) | `/www/wwwroot/web-admin/` | `139.196.165.140:8086` |
| **Showcase 展示站** | **139** (网关) | `/www/wwwroot/showcase/cretaceousfuture/` | `www.cretaceousfuture.com` |
| Nginx 网关 | **139** (网关) | 宝塔 Nginx | API→47, Python→47 |
| **餐饮平台模拟器** | **139** (网关) | `/www/wwwroot/mock-platform/` | `139.196.165.140/mock/` |

**关键规则**: Showcase 相关文件（HTML、截图、CSS）只部署到 **139**，不要传到 47。

### 本地目录 → 服务器路径映射

| 本地目录 | 部署目标 | 服务器路径 |
|---------|---------|-----------|
| `platform/` | **139** (旧) | `/www/wwwroot/showcase/cretaceousfuture/` |
| `backend/java/cretas-api/` | **47** (新) | `/www/wwwroot/cretas/` |
| `backend/python/` | **47** (新) | `/www/wwwroot/cretas/code/backend/python/` |
| `web-admin/` | **139** (网关) | `/www/wwwroot/web-admin/` |
| `mock-platform/` | **139** (网关) | `/www/wwwroot/mock-platform/code/` |

**餐饮平台模拟器 (2026-07-29)**: 假 POS 开放平台，**只上 139，绝不上 47** —— 它扮演「外部世界」，
放到 47 就破坏了隔离前提。绑 `127.0.0.1:9200` 不对外，由 139 已有 nginx 从 80 反代 `/mock/`
（139 的阿里云安全组只放行 80/443/8086，自开端口从 47 打过去一律 TIMEOUT）。
部署：`./scripts/deploy/deploy-mock-platform.sh`；服务名 `cretas-mock-platform`。
健康判据用 `generator: running`，**不能只看 `status: ok`** —— 生成器没挂时那一档也是 ok。

**`platform/` 目录说明**: 包含 www.cretaceousfuture.com 网站的全部内容 — 主站页面 + showcase 演示子页 (factorybi-example, client-request-example, restaurantbi-example)。

---

## 环境

47 只跑 **prod**：Java 蓝绿双槽 10010 / 10020、Python 8083；主库 `cretas_prod_db`、SmartBI 库 `smartbi_prod_db`；启动脚本 `restart-prod.sh`，日志 `cretas-prod.log` / `python-prod.log`。

test 环境已于 2026-07-13 下线（47 内存紧张，prod Java 并发压力下 OOM 后停 test 腾出 ~1.5G；prod JVM 堆 `-Xmx3g`，`-XX:+HeapDumpOnOutOfMemoryError` 已开，dump 落 `/www/wwwroot/cretas/logs/`）。现在的测试环境是加拿大 Mac mini，见 memory `project_macmini_test_env_is_live_with_f006`。

---

## 新服务器目录结构 (47.100.235.168)

```
/www/wwwroot/
├── cretas/                          # Cretas 食品溯源 (主项目)
│   ├── aims-0.0.1-SNAPSHOT.jar      # Java 后端 JAR (两套共享)
│   ├── .env.prod                    # 生产环境变量 (DB密码/JWT/LLM Key)
│   ├── restart.sh                   # 入口 (test 已下线, 实际只调 restart-prod.sh)
│   ├── restart-prod.sh              # 生产环境启动 (systemd 版)
│   ├── cretas-prod.log              # 生产 Java 日志
│   ├── embedding-service/           # gRPC 向量嵌入服务
│   │   ├── embedding-service-1.0.0.jar
│   │   └── embedding-service.log
│   ├── models/                      # ONNX 模型文件
│   │   └── gte-base-zh-finetuned-onnx-fixed/
│   └── code/backend/python/         # Python 服务代码
├── web-admin/                       # Web 前端 (Vue dist)
└── python-services/                 # Python 独立服务 (food_kb)
```

---

## 服务管理 (systemd)

**生产环境所有服务均由 systemd 管理，开机自启 + 崩溃自动重启。**

| 服务 | 端口 | systemd 服务名 | 状态 |
|------|------|---------------|------|
| gRPC Embedding | 9090 | `cretas-embedding` | enabled |
| Java 后端 (prod, 蓝绿) | 10010 / 10020 | `cretas-backend` / `cretas-backend-green` | enabled |
| Python 服务 (prod) | 8083 | `cretas-python` | enabled |
| Redis | 6379 | `redis` | enabled |
| PostgreSQL | 5432 | `postgresql` | enabled |

### 启动依赖链

```
redis(6379) ─┐
postgresql ──┤
              ├─ cretas-embedding(9090) ─→ cretas-backend(10010)
              └─ cretas-python(8083)
```

Java 后端依赖 Embedding 服务 (`After=cretas-embedding.service`)。

### systemd 配置文件

| 文件 | 位置 |
|------|------|
| `cretas-embedding.service` | `/etc/systemd/system/` |
| `cretas-backend.service` | `/etc/systemd/system/` — 使用 `EnvironmentFile=/www/wwwroot/cretas/.env.prod` |
| `cretas-python.service` | `/etc/systemd/system/` — 环境变量内联 |

### 常用管理命令

```bash
# 查看状态 (先读 139 的 upstream 知道哪槽 active)
systemctl status cretas-backend cretas-backend-green cretas-python cretas-embedding

# 重启单个服务 (⚠️ 常规发布/切流走发布脚本, 手工重启只用于故障处置, 先确认 active 槽位)
systemctl restart cretas-python

# 实时日志
journalctl -u cretas-backend -f
journalctl -u cretas-backend-green -f
```

### 环境变量管理

- 生产环境变量集中在 `/www/wwwroot/cretas/.env.prod` (权限 600)
- Java 服务通过 `EnvironmentFile` 引用
- Python 服务通过 `Environment=` 内联（含 LLM 模型分配）
- **修改模型配置后**需同时更新 systemd service 文件和 `.env.prod`，然后 `systemctl daemon-reload`

---

## 常用运维命令

```bash
# 健康检查 (Java 先读 upstream 再 curl 那个槽; 直连 10010 可能正是停用槽)
ssh root@139.196.165.140 'cat /www/server/panel/vhost/nginx/_upstream_cretas.conf'
curl -s http://47.100.235.168:<active 槽>/api/mobile/health
curl -s http://47.100.235.168:8083/health                # Python

# systemd 状态
ssh root@47.100.235.168 "systemctl status cretas-backend cretas-python cretas-embedding --no-pager"

# 端口监听 (含 9090 Embedding)
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|10020|8083|9090|6379'"

# 磁盘和内存
ssh root@47.100.235.168 "df -h && echo '---' && free -h"

# 查看日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/cretas-prod.log"
ssh root@47.100.235.168 "journalctl -u cretas-backend --since '5 min ago' --no-pager"
```

---

## 部署规范

**部署必须使用项目内的部署脚本，禁止手动 rsync/ssh 拼命令：**

| 部署目标 | 脚本 | 说明 |
|----------|------|------|
| **正常 Java/Web 发布（首选）** | `./scripts/deploy/release-cretas.sh --phase build\|deploy --base-sha <SHA> --tests '<tests>' --confirm-prod YES-PROD` | 统一入口：自动检测变更范围、构建一次 + manifest/tree-cache 复用、默认安全串行（并行需 `YES-INDEPENDENT-SERVICES` 口令）、JSON 回执；deploy 阶段强制 clean HEAD == origin/main |
| 发布证据（只读） | `./scripts/deploy/verify-release.sh --target backend\|web-admin\|all --env prod` | 汇总真实 upstream / systemd / 直连健康 / Web 四方哈希；回执成功不重复手工检查 |
| Java 单组件/排查 | `./scripts/deploy/deploy-backend.sh --env prod` | Maven 打包 → rsync 主 (scp 兜底) → 备份 → Blue-Green 部署 → 健康检查 |
| Python 服务 | `./scripts/deploy/deploy-smartbi-python.sh --env prod` | rsync 增量同步 → 安装依赖 → 重启 → 健康检查 |
| Web 单组件/排查 | `./scripts/deploy/deploy-web-admin.sh --env prod` | npm build → tar+scp → 139 网关原子切换 |
| 全栈部署 | 使用 `/deploy-backend` skill | 根据指令自动选择部署范围（其 Phase -1 即统一入口） |

**蓝绿槽位**：prod Java `10010`/`10020` 交替 active，部署/核对前先读 `139:/www/server/panel/vhost/nginx/_upstream_cretas.conf`，禁止假设某槽永久停用。

---

## ⛔ Smartbi 数据库 schema 变更 (HARD RULE)

**触发**: task #30 (2026-05-06) — 8 个 data fabric C 系列 migrations 当初部署漏跑 prod, T6.2 canary 4h 才发现 9 errors。
**Spec**: `docs/superpowers/specs/2026-05-07-smartbi-migration-runner-spec.md`

**所有** smartbi 数据库 (smartbi_db / smartbi_prod_db) schema 变更**必须**:

1. 写 `backend/python/smartbi/database/migrations/V<YYYYMMDD>_<NN>__<description>.sql`
2. 部署通过 `./scripts/deploy/deploy-smartbi-python.sh --env <env>` 自动 apply
   - Step 3.5 调用 `apply-smartbi-migrations.sh --env $env` runner
   - Runner 失败 → deploy ABORT,Python NOT restarted (旧 schema + 旧 code 继续跑)

**禁止** 手动 `ssh + psql -f` 直接跑 schema DDL,**除非**紧急 hotfix。完后**必须立即**:
1. 把 SQL 落 V*.sql 文件 commit 进 repo
2. 手动 INSERT 进 `smartbi_migrations` tracker (per spec §3.8 escape hatch):
   ```sql
   INSERT INTO smartbi_migrations (filename, version, checksum, applied_by)
   VALUES ('V20260507_99__hotfix.sql', 'V20260507_99',
           '<sha256 of file>', 'manual-emergency')
   ON CONFLICT (filename) DO NOTHING;
   ```
3. 否则下次 deploy 跑 runner 会 try re-apply → DDL 已存在报错。

**escape hatch**: 紧急绕过 runner (e.g. runner 自身有 bug):
```bash
SKIP_MIGRATIONS=1 ./scripts/deploy/deploy-smartbi-python.sh --env prod
```
WARN level 日志 + 跳过 Step 3.5。完后立即修 runner 重新部署。

如果 schema 在 prod / test 之间出现 drift:
1. `comm -23 <(test schema dump) <(prod schema dump)` 找差异
2. 单一 transaction (`BEGIN; ALL; COMMIT;`) apply 缺失 migrations 到落后的 env
3. Backfill tracker 表 (per `scripts/migrations/backfill-applied.sh`)

**Tracker schema 关键点** (per spec §3.2): PRIMARY KEY = `filename` (not version),因为 历史上 `V20260427_01` 有两个不同文件。filename PK 防 future dupe 静默跳过。

---

## 注意事项

1. **不要直接删除** `/www/wwwroot` 下的目录，先确认内容
2. **备份 jar 包**会自动生成 `.bak.*` 文件，保留最近 3 份，定期清理旧的
3. **日志文件**在 `logs/` 目录，会持续增长，需定期清理
4. **数据库**: 已迁移到 PostgreSQL，不再使用 MySQL
5. **旧服务器 (139)**: 后端已停用，仅保留 Nginx 反代 + **Showcase 静态站** (www.cretaceousfuture.com)
6. **Showcase 只部署到 139**: 不要向 47 传 showcase 文件，47 是纯后端服务器
7. **文件传输: rsync 为主, scp 兜底** (deploy script v5.0, Steve 2026-05-28; 全 SSH-based 谁快谁赢). `rsync` (主, 更长久更快) + `rsync+compress` + `scp` (兜底, 任何环境都能跑, 实测 10.85 MB/s). **R2/OSS/GitHub 默认禁用** (代码保留, `ENABLE_R2=1` 紧急 opt-in). ⚠️ **`SKIP_RSYNC=1` 已于 2026-06-07 从 `~/.bashrc` 移除** —— 旧"rsync 被 RST 永久禁用"结论已过期, 残留的 flag 一直在 forcing scp 兜底; 现 deploy 默认走 rsync 主通道.
7b. **从 GitHub 取制品是另一条链路, 不走上面的 rsync/scp**: `GitHub → 东京 Lightsail → 上海 OSS → ECS 内网`, 入口 `scripts/deploy/Publish-GitHubArtifactViaLightsailOss.ps1`(release asset 用 `-AssetId`, CI artifact 用 `-ArtifactId`, zip 在东京解包)。制品字节**完全不经过 Windows**(实测 168MB 本机只收 0.30%); 东京→OSS 实测 22.6–23.8 MB/s, OSS→ECS 内网 276–415 MB/s。详见 `scripts/deploy/RELEASE-ARTIFACT-TRANSPORT.md`。⚠️ **2026-07-31 起它已是常规发布的默认路径**(此处原写"不加速常规发布", 已随 PR#2061 作废): `--prefer-ci-artifact` 默认开, `release-cretas.sh` → `release-ci-artifact.sh` → 这个 ps1 复用 CI 已构建的 JAR, 命中时 Java 侧**零次 Maven**(构建段 204s → 25s); 命中前提是制品已产出, 所以**合并后先跑 `prewarm-main-artifact.sh --tests '<tests>' --wait 420`**。未命中才回退本地 Maven + 上面的 rsync delta。它同时买到"Windows 退出数据通路 + 制品可追溯"; 且 `transport_verified` ≠ `deployable_trust_verified`。⚠️ 排查用的 `~/github-cache-tools/fetch-ci-artifact.sh` 终点是本机, **已降级为人工回退**(并发上限 64 + 聚合限速 4 MiB/s), 曾因 128 路无约束并发打满上行导致全网断流。
9. **修改 systemd 服务文件后**: 必须 `systemctl daemon-reload` 再 `systemctl restart <service>`
10. **生产环境变量**: 集中在 `.env.prod`，修改后需重启对应服务才生效
11. **本地启动 Java 后端**: 用 `mvn spring-boot:run` 不要用 `java -jar` (后者 mmap 锁 fat jar 会阻断 deploy 的 mvn package). 见 `feedback_deploy_pipeline.md`.
12. **R2/OSS 凭证位置 (legacy, 默认禁用)**: R2 在 `~/.r2-env` (NTFS ACL 仅 Steve+SYSTEM); OSS 在 `~/.ossutilconfig` (账号 B, **`cretas-media` bucket 属账号 B 不是 A**). 这些是 Steve 在国外期间为绕过跨境 RST 用的中转通道; 现已回国, rsync 主 + scp 兜底, R2/OSS 默认禁用 (`ENABLE_R2=1` 紧急 opt-in). `SKIP_RSYNC=1` 已移除 (见注意事项 7). deploy script 启动时仍自动 source ~/.bashrc (取 R2 凭证供紧急 opt-in).
13. **Backup 文件清理**: deploy script 自动保留最近 3 份 `*.bak.YYYYMMDD_HHMMSS`. 历史命名 (`.bak4/5/6/.broken/.bak.pre_fix`) 不会被自动清理, 需手动 rm.

---

## 凭证文件位置

凭证见本目录 `db-credentials.md` / `aliyun-credentials.md`(本地文件, gitignored, 不入库)。含数据库密码、服务器登录、阿里云 AccessKey 等真值; 需要凭证时直接读这两个文件, 不要把真值写进任何会被 commit 的文件。
