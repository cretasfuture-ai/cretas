# 数据库备份：加密与恢复手册

最后更新：2026-10-26（Steve 拍板：服务器只放公钥，私钥由 Steve 离线保管；异地每天传、只留 7 天）

## 现行机制

| 项 | 现状 |
|---|---|
| 脚本 | 47 上 `/www/wwwroot/cretas/scripts/backup-db.sh`，源在仓库 `scripts/ops/backup-db.sh` |
| 时间 | 每天 03:00（root crontab），日志 `/www/wwwroot/cretas/logs/backup.log` |
| 备份对象 | `cretas_prod_db`、`smartbi_prod_db`（另有测试库 `cretas_db`，只在本机） |
| 本机副本 | `/www/wwwroot/cretas/backups/*_YYYYMMDD_HHMMSS.sql.gz`，**明文**，保留 7 天 |
| 异地副本 | 阿里云 OSS `oss://cretas-db-backups/cretas-prod/`（上海，同区内网上传），**只有加密件** `*.sql.gz.age`，保留 7 天 |
| 加密工具 | age v1.2.1（官方 GitHub 发行包，Windows 包与 winget 清单哈希一致；Linux 包 SHA-256 `7df45a6cc87d4da11cc03a539a7470c15b1041ab2b396af088fe9990f7c79d50`），服务器上在 `/usr/local/bin/age` |
| 公钥 | `/etc/cretas-backup/age-recipient.txt`：`age1ult429r6nqqe97lg385w8t70fqqlc6fwljqfx5smz77dggvm9pqsdxjg7j` |
| 私钥 | **不在服务器上**。由 Steve 离线保管（2026-10-26 生成，文件名 `cretas-backup-age-key-20261026.txt`） |
| 费用 | 两库合计约 180 MB/天，7 天约 1.3 GB，OSS 标准存储约 0.2 元/月 |

为什么本机副本不加密：数据库本身就在这台机器上，本机副本加密不增加保护，反而让本机快速恢复也要去取离线私钥。

### 失败时的行为

- age 不存在、或公钥文件里没有 `age1…` → **整段不上传**，日志写 `refusing to upload plaintext`，绝不退回去传明文。
- 加密失败、或加密件头不是 `age-encryption.org/v1` → 该库不上传。
- 上传后用 OSS 侧的 Content-Length 对比本地大小，不一致记 `SIZE MISMATCH`。
- 日志里 `OSS WARNING: 本次没有任何异地副本` 就是当天没有异地副本，要查。
- 2026-10-24～26 阿里云账号欠费导致上传报 `403 UserDisable`：**看到 UserDisable 先查费用中心**。

## 恢复步骤（从异地副本）

需要：离线私钥文件、一台装了 age 的机器（Windows 版在 `C:\Users\Steve\tools\age-dl\win\age\age.exe`；或从 https://github.com/FiloSottile/age/releases 下载 v1.2.1 并核对哈希）。

1. **取加密件**（在 47 上用 `cp`，不要用 `ossutil64 cat`——它会往标准输出追加一行耗时信息，文件就坏了，age 会拒绝解密）：
   ```bash
   ssh root@47.100.235.168 'ossutil64 cp oss://cretas-db-backups/cretas-prod/<文件名>.sql.gz.age /tmp/restore.age -f && sha256sum /tmp/restore.age'
   scp root@47.100.235.168:/tmp/restore.age .
   sha256sum restore.age          # 必须与上一步一致
   ```
2. **在有私钥的机器上解密并检查**：
   ```bash
   age -d -i <私钥文件> restore.age | gzip -t && echo OK
   ```
   age 带完整性校验：文件被改动或截断会直接报 `failed to decrypt and authenticate`，不会输出错误数据。
3. **恢复到新库**（⛔ 永远不要直接恢复到 `cretas_prod_db`；先恢复到新库核对，再决定怎么切换）。私钥不上服务器，解密后的明文经 SSH 流过去：
   ```bash
   ssh root@47.100.235.168 'sudo -u postgres psql -c "create database cretas_restore_<日期>"'
   age -d -i <私钥文件> restore.age | ssh root@47.100.235.168 'gunzip | sudo -u postgres psql -q -d cretas_restore_<日期>'
   ```
4. **核对**：表数量、最大几张表的行数、F006 的业务表行数、Flyway 最新版本号，与生产或事故前的已知数字对比。
5. 真要切换生产库属于事故处理，先停服务、按 `server-operations` skill 的流程走，不在本手册范围。

## 恢复演练记录

| 日期 | 备份文件 | 结果 |
|---|---|---|
| 2026-10-26 | `cretas_prod_db_20261026_195919.sql.gz.age`（99,356,845 字节，SHA-256 `1f7e84d6…de7f`） | ✅ 通过 |

### 2026-10-26 演练

- OSS 取回的加密件与 OSS 侧大小、SHA-256 一致；本机用离线私钥解密，`gzip -t` 通过。
- 恢复到临时库 `cretas_restore_drill_20261026`：91 秒，psql 0 个 ERROR，库大小 465 MB。
- 核对：public 表 593 = 593，schema 10 = 10，Flyway 最新版本 20261103.83 一致；生产最大的 12 张表里 11 张行数完全一致，`activity_events` 生产多 42 行（备份之后继续写入的操作记录，属正常）；F006 `production_batches` 44 = 44。
- 演练库已删除，本机下载的加密件已删除，私钥未上服务器。
- 顺带发现：`ossutil64 cat` 会往输出追加耗时信息，用它下载会让文件多 24 字节、age 拒绝解密——取件必须用 `ossutil64 cp`（已写进上面的步骤）。

建议每季度演练一次；私钥更换后必须立刻演练一次。

## 更换密钥

1. 在离线机器上 `age-keygen -o <新私钥文件>`，记下输出的公钥。
2. 把公钥写进 47 的 `/etc/cretas-backup/age-recipient.txt`（只放公钥）。
3. 第二天确认日志出现 `encrypted, size verified`，并用新私钥做一次恢复演练。
4. 旧私钥至少保留到旧加密件全部过期（7 天）之后再销毁。
