# 整厂数据导出与彻底删除 操作手册

**最后更新**: 2026-10-26（口径 Q1–Q6 Steve 已拍板）
**对应承诺**: 官网「数据安全」板块 ——「整厂数据导出、彻底删除」
**工具**: Java 后端平台接口 `/api/platform/factory-data/{factoryId}/...`（§5、§6）；临时备份清理 `scripts/cron/backup-copy-retention.sh`（§8）
**代码**: `backend/java/cretas-api/.../service/factorydata/`（表分类唯一来源 `FactoryDataCatalog`）

---

## §0 口径（Steve 2026-10-26 拍板）

| # | 口径 | 结论 |
|---|---|---|
| Q1 | 谁能发起 | **只走平台**：工厂总监书面申请（微信 / 邮件留底），平台核对身份后执行。总监自助导出放第二期 |
| Q2 | 删除冷静期 | **7 天**：申请当天停用工厂、禁止登录；7 天内可撤销；第 8 天起才允许物理删除 |
| Q3 | 审计 / 留痕 | **保留 6 个月**：删除时挪到单独的留存区，只留谁、何时、做了什么、对象编号，去掉内容字段，到期自动清除 |
| Q4 | 训练样本 | **保留脱敏样本，删原始记录**：带 `training_rule` 且 `desensitized=true` 标记的样本保留，并**清空工厂编号**，与已删工厂断开；已授权工厂原样存的样本、没有标记的样本都算原始记录，删除 |
| Q5 | 导出格式 | **可选、可组合**：CSV / Excel / JSON 任选一种或多种，ZIP 打包；manifest（行数 + SHA256）与附件目录照常带上；Excel 超出行数或 sheet 名长度限制时自动拆分，并在 manifest 注明 |
| Q6 | 临时备份 | **30 天保留期 + 每日自动清理**（先落清单再删）；删除工厂时同时清掉该厂在库内副本里的行。首次清理现存副本前，清单和计数先给 Steve 过目 |

---

## §1 一个工厂的数据在哪里（2026-10-26 prod 只读实测）

| 载体 | 位置 | 规模（F006 / LIUSHANMEN） | 怎么找到 |
|---|---|---|---|
| Java 主库 | `cretas_prod_db.public` | 508 张表带 `factory_id`；F006 在 153 张里有 52,612 行，LIUSHANMEN 在 110 张里有 40,917 行 | `factory_id = 编号` |
| 主库中不带 `factory_id` 的子表 | 同上，21 张（订单 / 采购 / 退货 / 盘点明细、凭证分录、设备维修、排班明细……） | 只能顺外键找到 | 从带 `factory_id` 的行出发顺外键做闭包（§4） |
| 主库中别名的租户列 | `internal_transfers`、`transfer_diff_records`（`source_factory_id` / `target_factory_id`）、`platform_data_access_log.target_factory_id`、`cross_factory_knowledge.source_factory_id`、`tool_execution_audit_events` / `tool_execution_idempotency` / `intent_preview_tokens`（`tenant_id`） | 少量 | 显式登记的租户列 |
| 工厂主记录 | `factories.id = 编号` | 1 行 | 最后删 |
| SmartBI 库 | `smartbi_prod_db.public`，140 张表带 `factory_id`；125 张开了行级安全 | F006 在 21 张里有 4,350 行 | 同上；`smart_bi_pg_field_definitions` 不带 `factory_id`，是上传记录的级联子表 |
| 训练样本 / 授权 | SmartBI `smart_bi_distillation_samples`（F006 73 条）、`training_consent`；主库 `ai_training_samples`、`active_learning_samples` | | `factory_id` |
| 文件 | 阿里云 OSS `cretas-media`、`cretas-audio`，对象键以 `<工厂编号>/` 开头 | F006 599 个；LIUSHANMEN 13,250 个、14.7 GB | 按前缀列举（库里的 `attachments` 表只登记了一部分，⛔ 不能只按表找） |
| 每日备份 | 47 本机 `/www/wwwroot/cretas/backups/*_YYYYMMDD_HHMMSS.sql.gz` 保留 7 天；异地 `oss://cretas-db-backups/cretas-prod/` 保留 30 天 | 整库 | 按保留期自然过期（§8） |
| 临时副本 | 修数据前留的整库 dump、删前备份 schema、`public` 里的 `backup_*` / `bak_*` / `repair_backup_*` 表、临时整库 | 2026-10-26 盘点见 §8 | 30 天保留期（§8） |

数字是 2026-10-26 的快照。⛔ 不要照抄这些数字当作某次操作的读数；每次操作以预演接口当场算出的结果为准。

---

## §2 表的分类：导出 / 删除 / 保留

规则是**按类别**定的，⛔ 不是逐表手写名单：手写名单一定会漏掉以后新增的表。默认类别是「业务数据」，新加的带 `factory_id` 的表**自动**随工厂导出和删除。只有下面登记的表例外，唯一来源是代码 `FactoryDataCatalog`。

| 类别 | 包含 | 导出 | 删除 | 理由 |
|---|---|---|---|---|
| 业务数据（默认） | 所有带租户列的表，以及它们顺外键找到的子表 | ✅ | ✅ | 承诺的主体 |
| 工厂主记录 | `factories` 中 `id = 编号` 的那一行 | ✅ | ✅ 最后删 | 其他表都引用它 |
| 审计 / 留痕 | `platform_data_access_log`、`operation_logs`、`tool_execution_audit_events`、`process_secret_access_events`、SmartBI `smart_bi_llm_egress_audit` | ✅ | 先把谁 / 何时 / 做了什么 / 对象编号写进留存区 `factory_erasure_audit_retention`，再删原表 | Q3；《网络安全法》第 21 条：网络日志留存不少于 6 个月 |
| 训练样本 | SmartBI `smart_bi_distillation_samples` | ✅ | 脱敏样本（`training_rule` + `desensitized=true`）保留并清空工厂编号；其余删除 | Q4 |
| 库内副本 | `public` 里的 `backup_*` / `repair_backup_*` / `bak_*`，以及其他 schema 里带 `factory_id` 的表 | ⛔（不是业务数据） | ✅ 删该厂的行；账号没有权限的那几份随 30 天保留期过期（回执写明） | Q6 |
| 凭证类列 | 任何表里名字含 `password` / `secret` / `token` / `api_key` / `credential` / `private_key` / `salt` 的列 | ⛔ 值替换成 `[已移除]` | 随行删 | 导出包会离开平台 |
| 回执 | `factory_data_requests`、`factory_erasure_audit_retention` | ⛔ | ⛔ 保留 | 证明「删过了、删了多少」；只存编号、计数和时间 |
| 迁移台账 | `migration_*`、`flyway_schema_history`、`shedlock` | ⛔ | ⛔ | 只记录表名和计数 |
| 平台共享 | 不带租户列、也不被该厂数据引用的表（行业知识库、意图样例、权限定义……） | ⛔ | ⛔ | 不属于任何一个工厂 |

Java 侧的意图样本（`ai_training_samples` / `active_learning_samples`）没有脱敏标记，按业务数据删除。`training_consent`（授权记录）随工厂删除。

---

## §3 谁能发起、怎么核对租户（Q1）

1. **申请留底**：工厂总监（`factory_super_admin`）书面提出，微信或邮件均可，内容包括工厂名称、工厂编号、申请类型（导出 / 删除）、联系人。截图存档，文件名写进删除申请的 `applicationEvidence`。
2. **核对申请人**：prod 只读查询申请人账号确实是该厂的 `factory_super_admin`、且启用：
   ```sql
   SELECT id, username, role_code, factory_id, is_active FROM users
    WHERE factory_id = '<编号>' AND role_code = 'factory_super_admin';
   ```
   再电话或当面向工厂确认一次，⛔ 不能只凭一条微信。核对人写进 `verifiedBy`。
3. **核对租户**：调用 `GET /api/platform/factory-data/{factoryId}/preview`，回执里的 `factoryName` 必须与申请里的工厂名称**逐字一致**，不一致就停手。
4. **谁能调接口**：只有 `cretas.factory-data.operator-admin-ids` 登记过的平台超级管理员（prod 只登记 `platform_admins.id = 1`；演示用的 `factory_demo` 不在内）。其他平台角色、工厂账号一律 403。
5. ⛔ **F006（团队演示租户）、LIUSHANMEN（真实客户）在代码里写死为受保护租户**，删除申请和删除执行都直接 403。

---

## §4 删除范围怎么算（外键闭包）

这是 2026-08-07 `V20261029_68`（清理 60 个测试租户）踩出来的经验：**扫描面必须是外键闭包，⛔ 不能只看带 `factory_id` 的表**。导出、预演、删除用的是同一份算法（`FactoryDataScopeResolver`），不会各算各的。

1. **种子**：所有带租户列的表中，租户列等于该编号的行，加上 `factories` 里的那一行。
2. **扩散**：顺着 NO ACTION / RESTRICT / CASCADE 外键，把「引用了本厂行」的子行加进来，反复做到不再增加。目前最深 2 层：`line_schedules → worker_assignments`。
3. **跨租户命中 = 停手**：扩散到的行如果带租户列、而值是别的工厂或为空，说明别的租户的数据引用了本厂数据。预演逐条列出，删除接口直接拒绝。两个租户列分属两个工厂的行（如两厂之间的调拨单）同样算命中。
4. **置空类外键**（`ON DELETE SET NULL`，例如 `factories.parent_id`）不扩散：子行不属于本厂，只统计会被置空的引用；被置空的若是别的工厂的行，同样算跨租户命中。
5. **疑似租户列**：名字以 `factory_id` 结尾或叫 `tenant_id`、却没在目录登记的列，预演会列出来，由人判断要不要登记。

### 删除顺序

不手写拓扑顺序。顺序是：**预检（所有库只读核对）→ SmartBI 库 → 附件 → 主库**。预检逐库核对跨租户命中、与预演计数是否一致、账号有没有删除权限，全部通过才开始删，避免删了一半才发现问题。每个库在一个事务里多轮删除，撞上外键约束的表留到下一轮，一整轮没有进展就整体回滚并列出卡住的表；工厂主记录最后删。

补充三条（2026-10-26 独立审查后加入）：

- **关联行**：顺外键认领的子行（不带租户列）如果同时引用了别的工厂的行（例如一张关联表一头挂 A 厂订单、一头挂 B 厂订单），算跨租户命中，⛔ 不算 A 厂的。
- **父表没有单列主键的外键**做不了逐行闭包：父表里有本厂行、子表又不带租户列时，记为「无法判定」并拒绝删除。
- **库内副本**：只有名字符合副本命名规则的 schema（与 `backup-copy-retention.sh` 的 `COPY_SCHEMA_RE` 逐字相同，有测试钉着）才按副本清本厂的行；其他 schema 里若有该厂的行，预演报出来、删除拒绝，由人查清。

SmartBI 有行级安全（2026-10-26 prod：95 张表是严格策略，只认 `factory_id = app.factory_id`；30 张是宽松策略，变量为空时可见全部）。所以：

- 本厂的行在 `app.factory_id = 本厂` 时算、导出、删除，严格表只有这样才看得见；
- 跨租户命中在变量为空时再算一遍并入，宽松表只有这样才看得见别的工厂的行；
- 「其他工厂一行不少」逐个工厂把变量设成该厂再数。

所有设置都只在本事务内有效（`set_config(..., true)`），不会经连接池泄漏到别的请求。LLM 外发审计表是只追加的，`smartbi_user` 没有 DELETE 权限，只能经专用函数 `factory_erase_llm_egress_audit` 按工厂删；函数还要求本事务的 `app.factory_id` 就是这个工厂（SmartBI 迁移 `V20261101_19`）。

---

## §5 导出

1. 完成 §3 核对。
2. 调用 `POST /api/platform/factory-data/{factoryId}/export?formats=CSV,XLSX,JSON&attachments=INCLUDE`：
   - `formats`：CSV / XLSX / JSON 任选、可组合（Q5），默认 CSV；
   - `attachments`：`INCLUDE` 原文件打进包里；`INDEX_ONLY` 只给附件清单（LIUSHANMEN 这种十几 GB 的厂用它，原文件另行交付）；`SKIP` 不处理（manifest 写明）。
3. 包的结构：
   - `manifest.json`：工厂、导出时间、操作人，每个库每张表的行数和每个文件的 SHA256，Excel 的 sheet 对应表与拆分说明，附件数量。**manifest 最后写**：中途出错被截断的包里没有它，收件人一看就知道不完整；
   - `cretas/csv/<表>.csv`、`cretas/json/<表>.json`、`cretas/cretas.xlsx`（SmartBI 同理放在 `smartbi/` 下）；
   - Excel：每个库一个工作簿，每张表一个 sheet，第一个 sheet「目录」列出 sheet 与表名的对应；超过 1,048,575 行拆成 `<名字>~2`…；超过 31 字的表名缩短并去重；超过 32,767 字的单元格截断并计数。完整值以 CSV / JSON 为准；
   - `attachments/<桶>/<对象键>` 与 `attachments/index.csv`；`README.txt`。
4. **核对**：响应头 `X-Factory-Data-Request-Id` 是回执编号（回执的 `triggerSource`：接口调用是 `MANUAL`，定时备份任务写自己的名字）。`GET .../requests` 里这次导出的 `packageSha256` 必须等于下载到的文件的 SHA256；manifest 每张表的行数必须与同时间的预演一致（导出内部已经按同一快照逐表比过，不一致会直接失败）。
5. **交付**：加密压缩或当面拷贝，⛔ 不发公开网盘链接；交付后删除本地副本。

---

## §6 彻底删除（Q2）

1. **先导出**：删除前必须先完成一次 §5 导出并交给工厂。删除申请要带这次导出的 `exportRequestId`，没有就拒绝；导出必须是 30 天内的，且不能是 `attachments=SKIP`。这是工厂履行《食品安全法》记录保存义务（进货查验 / 出厂检验记录保存至保质期满后 6 个月，没有明确保质期的至少 2 年）的前提，平台不替工厂保管。
2. **登记申请**：`POST .../erasure-requests`
   ```json
   {"confirmFactoryId": "<编号>", "exportRequestId": 123, "applicant": "<总监姓名/账号>",
    "applicationEvidence": "<微信/邮件截图文件名>", "verifiedBy": "<核对人>", "drill": false}
   ```
   当场停用工厂（`factories.is_active = false`）：新登录和在线会话都会看到「当前服务已关闭」。返回 `requestId` 与 `notBefore`（7 天后）。
3. **冷静期内撤销**：`POST .../erasure-requests/{requestId}/cancel`，工厂恢复到申请前的状态。
4. **第 8 天起预演**：`GET .../preview`。`erasable=true` 且 `blockers` 为空才能继续。重点看：每个库的 `rowsToErase`、`trainingSamplesToRetain`、`auditRowsToRetain`、`foreignHitCount`（必须为 0）、`missingDeletePrivileges`（必须为空）、`copiesOutsideSchema`、附件数。
5. **把预演计数给 Steve 看**，等他回复「可以删」。
6. **执行**：`POST .../erase`
   ```json
   {"confirmFactoryId": "<编号>", "erasureRequestId": <requestId>, "previewId": <previewId>}
   ```
   预演必须在本次申请之后、24 小时以内。执行时当场重算范围，与预演不一致（期间有人写入）就拒绝，回到第 4 步。
7. **阴性对照**（接口在每个库的事务内自动做，不通过就回滚该库）：本厂按同一口径重算，要删的类别全部为 0；其他工厂按工厂分组的行数执行前后逐厂相等。附件按前缀删完后再列一次，必须为空。
   - 同一个工厂的申请 / 撤销 / 删除串行执行，另一个正在进行时直接返回 409；
   - 冷静期内工厂若被人重新启用，删除拒绝，要先撤销申请，需要时重新申请；
   - 三步都提交之后，在新的快照里把两个库和附件前缀再查一遍，仍有本厂的行就标 FAILED（阶段 VERIFY）。
8. **失败了怎么办**：回执标 FAILED 并写明停在哪一步（PREFLIGHT / SMARTBI / ATTACHMENTS / CRETAS / VERIFY）。已经提交的库不会回滚；工厂一直处于停用状态，重新预演后再执行一次即可（已删的部分计数为 0）。
9. **回执**：§9。

### 演练

只在专门新建的一次性测试工厂上做：编号以 `ERASETEST_` 开头、3 天内新建的工厂，申请时可带 `"drill": true` 免冷静期（其余工厂一律 7 天）。先造数据，导出，申请，预演计数，删除，再计数确认为 0，同时确认其他工厂行数不变。⛔ 绝不对 F006、LIUSHANMEN 或任何真实工厂演练。

#### 演练记录

**2026-10-27 · ERASETEST_20261027A · prod · 平台管理员 platform_admin（userId 1）· 结果：通过**

| 步骤 | 编号 | 读数 |
|---|---|---|
| 导出 | request 5 | CSV+JSON，附件 INCLUDE，17,204 B，sha256 `90486e2d…` 与台账一致 |
| 申请 | request 6 | `drill=true`，工厂当场停用 |
| 预演 | preview 7 | `erasable=true`、`blockers=[]`；CRETAS 9 行、SMARTBI 3 行（训练样本 2 条里留 1 条）、附件 1 个 27 B。计数发 Steve，回复「可以删」 |
| 删除 | erasure 9 | 22:17:03 SUCCEEDED；CRETAS 删 9 行（users 1 / suppliers 2 / customers 1 / ai_analysis_results 1 / operation_logs 1 / platform_data_access_log 3），SMARTBI 删 3 行，附件 1 个；工厂主记录在复核后最后删 |

- **本厂复数**：上面各表和 `factories` 全部 0，OSS 前缀 `ERASETEST_20261027A/` 0 个对象。
- **阴性对照**（库外独立快照，以 postgres 身份、绕过 RLS，删前删后各拍一次）：其他工厂 users 90 / suppliers 21 / customers 39 / operation_logs 5,854 / ai_analysis_results 302 / agg_supplier_price 841 前后相同，按工厂分组的行数哈希 `64cff7bd40acd768f8aa7f0f1295f67a` 不变。接口自身报告检查了 CRETAS 11 个、SMARTBI 15 个其他工厂。
- **保留物**：留存区 operation_logs 1 条 + platform_data_access_log 3 条，`retain_until` 2027-04-27；保留样本的 `factory_id` 已为 NULL，原始样本已删。台账 5 / 6 / 7 / 9 四条回执都在。
- **和预演的差别**：访问记录 3 条（预演时 2 条），多出的是预演请求自己写的那条，同一张表，一并进了留存区。
- **删完后在线表里还剩 1 行**：删除接口自己的访问记录（`platform_data_access_log` id 787，比删除完成晚 16 ms）。按 §7「删除操作本身产生的平台访问记录」保留，2026-10-27 暂按不改处理。

---

## §7 保留什么、留多久

| 保留项 | 内容 | 期限 | 依据 |
|---|---|---|---|
| 回执 `factory_data_requests` | 工厂编号、名称、申请人、核对人、导出包哈希、每张表删前删后的行数、时间 | 长期 | 证明承诺已兑现 |
| 审计留存 `factory_erasure_audit_retention` | 谁、何时、做了什么、对象编号 | 6 个月，`FactoryErasureRetentionJob` 每天 04:40 清除到期行 | Q3；《网络安全法》第 21 条 |
| 删除操作本身产生的平台访问记录 | 预演 / 删除接口的调用记录 | 同一个任务清除 6 个月前的、且工厂已删除的记录 | 同上 |
| 脱敏训练样本 | 已脱敏的问答，工厂编号已清空 | 不随工厂删除 | Q4；已经训练进模型的部分无法撤回，回执写明 |
| 每日备份 | 整库 | 本机 7 天，异地 30 天 | §8 |

---

## §8 备份与临时副本（Q6）

1. **每日备份**（`backup-db.sh`，每天 3:00）：本机保留 7 天，异地 OSS 保留 30 天。删除生效后，这些备份里仍然有该厂数据，**最长 30 天后随备份过期自然消失**。回执写明本机与异地备份分别在哪天之后不再含有该厂数据。⛔ 不为删一个工厂去改写整库备份文件。
   - 若在过期前需要用备份恢复整库，恢复完必须立即对回执里登记过的工厂重做一次删除。
   - ⚠️ 2026-10-24 起异地上传因阿里云账号停用而失败（`uploaded=0 failed=2`），异地桶里的旧备份也删不掉，由数据安全主线跟进。恢复之前，异地那份「30 天过期」无法执行，回执里要如实写。
2. **临时副本**：`scripts/cron/backup-copy-retention.sh` 每天清理超过 30 天的临时副本，范围：
   - `/www/wwwroot/cretas/backups` 顶层的文件和目录（目录按其中最新文件算），⛔ 不含每日备份、`backup.log`、`*.md` 和 `*逐表行数*.csv`；
   - `/www/backup` 下名字形如 `pre-*-YYYYMMDD`、`prod-clean-YYYYMMDD`、`<x>-backup-YYYYMMDD` 的目录；
   - 两个生产库里像副本的 schema（日期取名字里的 YYYYMMDD；没有日期的查脚本里的登记表，查不到就不删、报 rc=1）、`public` 里带日期的 `backup_*` / `repair_backup_*` / `bak_*` 表；
   - 登记表里点名的临时整库。
   每次先把清单落盘，删完再算一次：删掉的必须都不在了，没到期的每一项（表数 / 行数 / 字节）必须一个不差，否则 rc=1。首次手动执行用 `--plan` 生成清单、给 Steve 过目、再 `--apply --expect <清单>`（当场计数与清单对不上就一项都不删）。
   - 以后新建删前备份，**名字里一律带 YYYYMMDD**，否则这个脚本认不出日期。
3. **删除工厂时**：库内副本里该厂的行一并删除；没有删除权限的副本（例如 postgres 所有的 schema），在回执里写明它会随 30 天保留期在哪天被清理。

---

## §9 回执模板

```
整厂数据删除回执
工厂：<名称>（<编号>）
申请人：<姓名 / 账号>，申请时间：<时间>，申请留底：<截图文件名>，核对人：<姓名>
导出包：回执 <exportRequestId>，SHA256=<…>，格式 <CSV/XLSX/JSON>，交付方式：<…>，交付时间：<…>
冷静期：<申请时间> — <notBefore>，期间未撤销
预演：previewId=<…>，主库 <N> 张表 <M> 行，SmartBI <N> 张表 <M> 行，附件 <K> 个，跨租户命中 0
Steve 确认：<时间>
执行：erasureId=<…>，完成时间 <…>
阴性对照：本厂重新计数全部为 0；其他工厂行数执行前后一致（主库 <n> 个工厂，SmartBI <n> 个工厂）
附件：OSS 前缀 <编号>/ 删除 <K> 个对象，删后列举为空
保留：审计 <N> 行（只含谁/何时/做了什么/对象编号），<日期> 自动清除；脱敏训练样本 <N> 条，工厂编号已清空（已训练进模型的部分无法撤回）
副本：<库内副本删除情况 / 随保留期过期的日期>
备份：本机每日备份 <日期> 后、异地备份 <日期> 后不再含有该厂数据（异地上传当前<正常/中断>）
```

---

## §10 禁止事项

- ⛔ 不对 F006（团队演示租户）、LIUSHANMEN（真实客户）或任何还在使用的真实工厂做删除演练。
- ⛔ 不在 psql 里手写 `DELETE` 代替执行接口；接口里的预检、跨租户检查和阴性对照是唯一保证删对地方的东西。
- ⛔ 不在预演计数给 Steve 看过之前执行删除。
- ⛔ 导出包不发公开链接、不留在服务器上。
- ⛔ 清理临时副本不手工 `rm` / `DROP`，一律走 `backup-copy-retention.sh`（它先落清单、删后做阴性对照）。
