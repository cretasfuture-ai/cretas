# BLACKLAKE-READONLY-20261103：测试环境 PoC 回执

- Owner：Codex/root；独立 worktree `C:/Users/Steve/cretas-blacklake-20261103`，branch `codex/blacklake-readonly-20261103`。
- Base SHA：090f5f7140380e2a2f3a67295fef7bde615f0716。
- 用户范围：黑湖正式账号仅只读，用户确认来源“六膳门”；目的为独立测试环境。没有生产 Cretas 写入/迁移/部署，不启用连续任务，不绑定既有 ERP 工厂。
- 修改：`backend/python/blacklake/**`、`backend/python/tests/test_blacklake_{connector,storage}.py`、`docs/integrations/blacklake/**`、本回执与 ACTIVE。
- 交付：查询白名单客户端、登录/Token/Redis 协调、独立 PostgreSQL migration/RLS/upsert/水位/运行回执、真实只读联调/测试入口、第一版确定性 BI、字段映射与联调报告。
- 验证：`python -m blacklake.test_local` exit 0，49 passed，0 skipped；真实 PostgreSQL SCRAM 和受密码保护 Redis；compileall/AST/JSON、dispatch-active、diff whitespace 通过。内容哈希见 `docs/integrations/blacklake/VALIDATION.json`。仅文档/PR 文案变化不重跑测试。
- 数据：七天半开区间 2026-10-27→2026-11-03，期间工单 76、报工 205。测试库最新标准记录 829（96工单/268任务/216报工/249库存单），3936数值比较差异0，216条报工关系缺失/不一致0。70工单/205报工更新时间窗口来源哈希一致。
- 安全：认证及真实响应只在仓库外本机 DPAPI 文件；当前源 orgId 固定于独立来源绑定，换账号不自动重认领；tracked 本批文件实际凭据扫描0命中。保留测试资源供复核，不自动清理。
- 独立风险复核：只读 risk reviewer 初次4项P1及次级窗口/缓存门禁均已修复，复核无剩余实质代码阻塞；最终49测试成功证据已收齐。
- Commit：实现 694dcab7ad；已推送。草稿 PR：[1390](https://github.com/cretasfuture-ai/cretas/pull/1390)。当前状态 review，没有合并或生产发布；CI 不以本地测试冒充。范围锁保留当前 Owner，后续修改先核对当前分支/PR。
- 下一步/未完成：按用户后续安排接测试 UI或继续六类参考接口；任务全局更新时间增量不可用、库存增量/删除完整性待验证，完整人工/制造成本和 P2 Agent 未验收。不得把本轮技术测试当作业务签收或生产上线。

## 后续全历史切片

- 用户追加全量需求后，扩展13类只读查询：109工单、301任务、272报工、332出入库、205产品、28工序、21路线、11仓库、14单位、69BOM、225工单用料、183余额、109当前工序回执，共1879。
- 目标测试63通过0跳过（真实PG SCRAM/受保护Redis）；原文哈希差异0、7984字段比较差异0；数据库002只安装本机隔离测试库，未改七天PoC游标。
- 全量不等于完整验收：225用料缺返回父ID、92当前工序缺标识、2072余额组合未返回，删除/孤立数据未证明；明确coverage_complete=false；旧扫描以版本2离线复验，不升级断点或重复拉源。
- 代码/测试内容哈希、计数与对账见FULL-VALIDATION.json；报告见FULL-REPORT.md；PR#1390仍草稿，无合并/生产发布/业务签收。
