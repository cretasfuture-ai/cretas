# 按工厂备份套餐：手册

最后更新：2026-10-27（Steve 2026-10-26 拍板 8 项口径）

## 两层备份

| 层 | 是什么 | 谁有 | 收费 |
|---|---|---|---|
| 平台整库灾备 | `scripts/ops/backup-db.sh` 每天 03:00 整库，加密异地 7 天（见 `backup-encryption-and-restore-runbook.md`） | 所有工厂 | 不收费，底线 |
| 按工厂备份套餐 | 本手册：按工厂定时导出、加密、异地、按保留期清理 | 开通了套餐的工厂 | 写进合同，系统不计费 |

## 口径（Steve 2026-10-26）

| # | 口径 |
|---|---|
| Q1 | 频率（每天 / 每周 / 每月）× 保留期（7 / 30 / 90 / 365 天）自由组合 |
| Q2 | 价格打包进合同，系统里不计费、不显示价格 |
| Q3 | 一律加密；默认平台钥匙（私钥 Steve 离线保管）；可选「客户自持钥匙」——我们解不开，客户丢了私钥就无法恢复 |
| Q4 | 首期「恢复」= 交付导出包；导入隔离库、人工找回是按次人工服务；不承诺回滚生产 |
| Q5 | 平台端看全部；工厂管理员（factory_super_admin / permission_admin）只读看本厂记录；只有客户自持钥匙的件工厂能自己下载 |
| Q6 | 只有平台能改套餐，必须填原因，改动留痕 |
| Q7 | 定时任务以配置项指定的平台管理员身份在进程内调用导出；不放长期 token、不开 HTTP 入口；审计区分（`trigger_source = BACKUP_SCHEDULER`）；只对已开通的工厂执行 |
| Q8 | 默认附件只存清单（INDEX_ONLY）；带原文件（INCLUDE）是可选项 |
| 默认 | 2026-10-27 之后新建的工厂自动开「每天 × 7 天」（赠送，平台可关）；之前的工厂由平台逐个开。⛔ LIUSHANMEN 不开，等 Steve 定 |

## 一次备份做了什么

1. `FactoryDataService.startExport(actor, factoryId, "BACKUP_SCHEDULER")`：导出回执记触发来源。
2. `writeExport(..., JSON, INDEX_ONLY|INCLUDE)` 的输出流直接接进 `age -r <公钥>`（`/usr/local/bin/age`），明文不落盘；加密件写到 `/www/wwwroot/cretas/backups/tenant-tmp/`。
3. 核对加密件文件头是 `age-encryption.org/v1`；平台公钥取自 `/etc/cretas-backup/age-recipient.txt`，缺失或格式不对 → 失败，⛔ 不传明文。
4. 上传 `oss://cretas-db-backups/tenants/<工厂编号>/<工厂编号>_<yyyyMMdd_HHmmss>.zip.age`（应用 AccessKey，内网 endpoint），回读 OSS 侧大小与本地比对。
5. 写执行记录 `tenant_backup_runs`（状态、导出回执号、对象键、明文 / 加密字节数、加密件 SHA-256），删本机临时件。

## 调度

- `TenantBackupScheduler`：01:20 / 04:20 / 05:20（Asia/Shanghai，避开 03:00 整库），ShedLock 保证蓝绿只有一个执行。每轮依次：
  1. 补新厂默认套餐；
  2. 把超过 6 小时还停在 RUNNING 的记录标为失败；
  3. 逐个处理到期的工厂（距上次成功 ≥ 频率天数 − 6 小时）；失败的下一轮自动重试；
  4. 清理过期件。
- 清理规则：只看 `tenants/<工厂编号>/`。工厂已不存在（被彻底删除）→ 整个目录删除；有套餐 → 删超过保留期的，但永远留最新一份；没有套餐 → 不动；文件名解析不出时间 → 不动。

## 配置（`application-pg-prod.properties`）

| 项 | 值 |
|---|---|
| `cretas.tenant-backup.enabled` | `TENANT_BACKUP_ENABLED`，默认 true |
| `cretas.tenant-backup.scheduler-admin-id` | `TENANT_BACKUP_SCHEDULER_ADMIN_ID`（服务器 `.env.prod` 设置）。必须是 active 的平台 super_admin，且在 `cretas.factory-data.operator-admin-ids` 白名单里 |
| bucket / endpoint | `cretas-db-backups` / `oss-cn-shanghai-internal.aliyuncs.com` |
| `auto-enable-created-after` | `2026-10-27T00:00:00` |

## 告警

- 日志标记 `TENANT_BACKUP_ALERT`：单厂备份失败、清理失败、整轮没跑（调度身份没配 / 不在白名单 / 未启用）。
- 平台「系统管理 → 工厂数据备份」页面状态列：最近失败（红）/ 逾期未备份（黄，超过频率 + 1 天没有成功）。
- ⚠️ 目前没有推送渠道（短信 / 企业微信），失败要靠页面或日志发现。

## 页面与接口

| 谁 | 页面 | 接口 |
|---|---|---|
| 平台 super_admin | 系统管理 → 工厂数据备份 | `GET/PUT/POST /api/platform/tenant-backups[/{factoryId}[/plan|/run]]` |
| 工厂管理员 | 系统管理 → 数据备份 | `GET /api/mobile/{factoryId}/data-backups`，`GET .../{runId}/download`（仅客户钥匙） |

## 交付导出包（A 级恢复，人工）

1. 平台页「记录」里找到那一天的对象键。
2. 47 上取件：`ossutil64 cp oss://cretas-db-backups/<对象键> /tmp/x.zip.age -f`（⛔ 不用 `cat`）。
3. 在有离线私钥的机器上：`age -d -i <私钥> x.zip.age > x.zip`；`manifest.json` 里有逐表行数和每个文件的 SHA-256，对一遍再交付。
4. 交付后删除本地明文。

## 删除工厂时

三张表登记在 `FactoryDataCatalog` 的 RECEIPT 类（不导出、不删），证明「这个厂的备份删干净了」。工厂行被彻底删除后，下一轮调度把 `tenants/<工厂编号>/` 整个删掉（最迟约 1 天）。
