# 黑湖只读连接器：测试环境 PoC

本地分析蒸馏：[Claude 教师实验](CLAUDE-TEACHER-EXPERIMENT.md) · [结果与费用](CLAUDE-TEACHER-REPORT.md)。

最新切片：[成本差异追溯与来源补查](COST-TRACE-REPORT.md)，已复用现有SmartBI候选与事实生成模块，并核实225条用料父级查询；142连接器/分析测试与72现有SmartBI测试通过，最终UI/构建证据见对应JSON。

连接器将黑湖正式环境的只读查询接入本机隔离测试库，不改 Cretas 现有生产表，不自动启动定时任务。后续已把13类历史数据接入本机 SmartBI 测试库，并注册显式配置后才可用的分析API和Vue页面；原料到成品成本结果、独立测试入口及常规外壳未通过项见 [SMARTBI-REPORT.md](SMARTBI-REPORT.md)。Java与RN源码未改动。

## 安装与配置

在 `backend/python` 使用项目 Python 环境：

```powershell
python -m pip install -r blacklake/requirements.txt
python -m blacklake --help
```

环境变量模板为 `blacklake/.env.example`。本模块不会自动加载 `.env`；由运行进程环境或密钥管理器注入。不要把真实手机号、密码、哈希、Token、数据库密码写入聊天、Git、请求 JSON 或命令行参数。

| 变量 | 用途 |
|---|---|
| BLACKLAKE_PHONE / BLACKLAKE_PASSWORD | 接入专用账号；密码在内存中用标准 SHA3-224 哈希 |
| BLACKLAKE_SECRET_FILE | 可替代上面两项，Windows CurrentUser DPAPI 文件绝对路径 |
| BLACKLAKE_TENANT_ID | Cretas 测试目的租户；不得直接映射到既有客户 |
| BLACKLAKE_SOURCE_FACTORY_ID | 预先批准的黑湖来源 orgId；与本次登录和每条可用 orgId 校验，不因换账号自动重认领 |
| BLACKLAKE_REDIS_URL | 私有 Redis；缓存 Token、账户级限流和锁；服务环境使用 ACL/TLS |
| BLACKLAKE_DATABASE_URL | 独立测试 PostgreSQL；运行角色不能是超级用户或 BYPASSRLS |
| BLACKLAKE_ALLOW_RAW_STORAGE | 通用 CLI 写入原始业务 JSON 前须为 true |
| BLACKLAKE_BASE_URL | 当前仅接受 https://liteweb.blacklake.cn |

`configure-account.ps1` 弹出手机号、密码窗口，将凭据加密保存到 `%LOCALAPPDATA%/Cretas/Blacklake/account.dpapi`，不发起登录。Windows PowerShell 运行示例：

```powershell
powershell.exe -NoProfile -STA -File .\blacklake\configure-account.ps1
$env:BLACKLAKE_SECRET_FILE = Join-Path $env:LOCALAPPDATA 'Cretas/Blacklake/account.dpapi'
```

`secrets.py` 只解密到当前进程内存，秘密不经 shell 标准输出。Python/框架不能保证内存中每份字符串均被擦除；不得开启 HTTP 请求体/请求头调试日志。加密文件只能由同一个 Windows 用户解密，不提交、不共享。

## 连接器与存储

- 一个白名单出口只允许登录及用户列出的十个查询 POST 路径；拒绝其他路径、HTTP 重定向和非批准域名。
- 真实登录成功条件是 `statusCode == 200`，Token 路径为 `data`；业务查询成功条件是 `code == "01000000"`。
- HTTP 401 仅触发一次重新登录。403、普通业务错误不重登；未观测的业务失效码不猜测。429/502/503/504 留待下一轮恢复，不进行登录风暴。
- Redis 使用账号级锁、续租、滚动 QPS/小时配额；工单/任务相同参数至少间隔 10.5 秒，登录每小时最多 75 次。这里的限流只覆盖本连接器，不能约束账号被其他应用调用；应使用接入专用账号。
- Token 本地缓存保留上限 24 小时，这不是黑湖服务端到期承诺。缓存同时保存已验证的来源 orgId；不把黑湖 Token 当作 Cretas 身份认证。
- PostgreSQL 原始记录保存每个内容版本，标准化记录保存最新版本，游标与本窗口全部写入同事务提交；任一页错误整窗口回滚，并另记失败回执。
- 每张表都有 tenant_id、source_factory_id 和外部身份键；复合主键防止跨厂冲突，FORCE RLS 防止漏写查询谓词造成越租户读取。运行角色不得是超级用户/BYPASSRLS。
- 使用 `migrations/001_blacklake.sql` 显式安装到独立 PoC 数据库；不接入 Java Flyway，不改现有业务 schema。由数据库 Owner 授予运行角色本 schema 的 USAGE/SELECT/INSERT/UPDATE。

`prepare_test_database` 只创建本机新容器，不重置现有库。它通过子进程环境传递随机测试密码，恢复信息保存在 DPAPI 文件中。不得把该测试入口用于生产；真实服务器迁移仍遵守项目发布规则。

## 运行与恢复

`verified_contract.json` 是七天 BI 同步的四个对象配置。后续历史归档使用 `full_contract.json`，已验证原十类查询并补充仓库、单位、BOM 三个只读接口；结果与边界见 [FULL-REPORT.md](FULL-REPORT.md)。新工厂必须重新确认身份、时区和字段，不应直接宣称复用即验收。

```powershell
python -m blacklake probe --contract blacklake/verified_contract.json --resource project --request-file C:/private/query.json
python -m blacklake sync --contract blacklake/verified_contract.json --resource project --start 2026-10-27T00:00:00+08:00 --end 2026-11-03T00:00:00+08:00
python -m blacklake reconcile --contract blacklake/verified_contract.json --resource project --start 2026-10-27T00:00:00+08:00 --end 2026-11-03T00:00:00+08:00
python -m blacklake metrics --contract blacklake/verified_contract.json --resource task
```

请求文件仅包含查询条件，不含认证材料。任务必须带工单编号或 projectIds；入库/出库必须分别传 `{"orderType":10}` / `{"orderType":20}`，不同方向、不同父查询范围拥有独立游标，同一源记录不会因不同查询条件重复存储。

已配置本轮加密测试连接信息时，`python -m blacklake.run_poc` 执行连续七个完整自然日的全量/补齐/增量/关联对账；`--checks-only` 复用同一期间的已有成功同步证据，只复查对账和指标。默认测试租户为 BLACKLAKE_POC_TEST，来源期待值保存在独立的 source-binding.dpapi，换账号不自动覆盖；测试租户不等价于既有 ERP 工厂绑定。`--refresh-plan-cohort` / `--refresh-report-flow` 仅用于分别补齐计划开始或报工结束时间口径，复用同期间的其他成功证据。

`live_probe`、`live_campaign`、`live_guard_probe`、`live_filter_control` 用于只读真实联调，输出结构和计数，真实响应仅存 DPAPI。`bootstrap` 只进行一次登录结构检查，需额外配置 BLACKLAKE_LOGIN_EVIDENCE_FILE。不要把结构检查的成功等同于登录或数据归属验收。本轮 PoC 入口使用 readiness 验证后的 test-cache.dpapi 中的受密码保护缓存；不自动使用无认证缓存。

`python -m blacklake.worker --contract blacklake/verified_contract.json --resource project --start <首次水位> --interval 600` 是显式启动的 5–15 分钟增量 worker，每日对账一次；output 可单独运行。尚未安装服务、计划任务或自动启动。task 必须由已知父工单范围刷新，stock_order 当前仅支持分方向的有界窗口重放，不能冒充全局更新时间增量。

恢复时保留源查询条件和合同哈希，修复原因后重跑失败窗口；已通过窗口不重复全量。更新时间增量有五分钟重叠，拒绝游标缺口、超过七天窗口、映射改变后继续沿用旧游标以及查询没有应用更新时间过滤的响应。映射变化需要有计划的重新基线，不自动丢弃游标。

## 数据生命周期与限制

取消、结束、审批和字段修改以最新查询值覆盖，旧原始版本保留。查询缺席不代表删除；没有已验证 tombstone/删除日志前，不删除标准化记录，删除完整性始终标为 unverified。需要人工对账或后续官方删除事件契约才能关闭此缺口。

原始记录可能含人员、工资和自定义字段。只在授权的本地测试环境保存，运行角色和访问面限于本连接器，不提供公开 API 或导出。使用工资时仅从任务累计工资取数，不再叠加报工 estimatedSalary。保留期、按厂销毁和业务负责人确认尚未执行，不能把原始版本无限保留当作正式生产策略。

本次只交付测试环境的工程与数据验证，不包含生产上线、完整制造成本、毛利、P2 AI Agent 或业务负责人验收。具体证据和未验证项见 REPORT.md。

2026-11-04 的日期范围、夜间定时、持久报告、历史复用和逐项追问见 [周期分析报告](PERIOD-ANALYSIS-REPORT.md)。该能力当前运行于本机隔离测试服务，AI 配额等待、来源时点和库存历史缺口均单独披露。

后续按用户要求取消 AI 小时次数限制，改为串行排队，全部当前等待报告已完成并开始记录实际 token；见 [AI 排队与用量回执](AI-QUEUE-REPORT.md)。历史报告无用量时保持未知。

新增本地模型经营解读：指标、统计依据、白话解释、假设、核对动作与边界共用版本化框架；见 [分析方法](ANALYST-PLAYBOOK.md) 和 [实际验证](LOCAL-ANALYST-REPORT.md)。新模板已用六膳门一段数据验证，旧历史报告按需升级。
