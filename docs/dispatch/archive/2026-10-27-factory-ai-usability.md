# FADUX27 — F006 实际问答验收修复

状态：review；协调者 Root；Base SHA：`b97c81ae6f37cbbe05f56ee637ac52d8ee5c4e13`。源码冻结并归档，写 scope 释放；后续合并、发布和验收分别记录真实回执。

本批使用 `codex/factory-ai-usability-20261027`；两个执行者仅修改各自不重叠范围，不修改台账、不提交、不派生代理。

- `FADUX27-PY`：Owner `sku_labels`；仅 `backend/python/smartbi/services/factory_charts/business_metrics.py`、`operational_sources.py` 及对应已有 Python 测试。按产品 ID 聚合并显示实际名称，同名不同 ID 不合并，缺名明确说明。验收：相关 pytest，保留命令、退出码和测试数。
- `FADUX27-RN`：Owner `native_summary`；仅 `frontend/CretasFoodTrace/src/screens/smartbi/FactoryAnalysisChatScreen.tsx`、`src/__tests__/integration/screens/FactoryAnalysisChatScreen.test.tsx`。分析说明默认折叠、可展开收起，不删除事实、来源、限制、图表或明细。验收：目标 Jest 与必要类型门禁。

禁止：生产数据写入、凭据、登录自动化、Entity/Repository、迁移、权限、共享发布脚本、白名单和其他文件。执行者不是唯一写者，不回退另一执行者的内容。Root 负责审查、最终验证、PR、合并、部署与 F006 只读验收。

完成条件：目标测试和适用 CI 通过；准确记录发布结果与 Web/原生真实验收，数据不足不冒充正向联表通过。

`FADUX27-PY` 范围补充：`conversation.py`、`test_conversation.py`。实际回答把普通合计/极值（findings.attention）全部复制成“限制”。只将真实缺失条件/来源错误/口径约束列作限制，不将业务值当限制；保留联合关系缺失的显式提示。使用已有结构化事实、结论与受控目录，不引入任意 SQL 或模型计算。

Root DIRECT 补充：`engine.py`、`cross_table.py`，仅为现有明确日期/财务备注/关账库存/联表覆盖和分母约束增加显式 `findings.limitations`，不修改计算、关联或权限。目标验证复用工厂图表 pytest，保证改汇总分类时未遗失真实约束。
对应回归测试允许 `test_cross_table.py` 与已有 `test_conversation.py`；两个执行者已完成回执并冻结，Root 接管最终合并和验证。

最终验证：全部工厂图表非 PostgreSQL 专项共 260 项通过、23 warnings（109.97s）；本批未修改数据库结构或真实 JPA 查询。RN 目标 Jest 14 项与完整类型检查通过。折叠变异将默认行数移除后，新增用户行为断言实际失败（1 failed / 13 passed），随后精确恢复源码 SHA256。差异检查与 dispatch 检查通过；必需 CI、合并、生产发布与真机验收仍以各自实际状态为准。

两个执行者的范围与命令回执已回读。Root 补齐显式约束后执行统一 Python 验证；未以普通合计或极值充当限制，未改变原计算和联表分母。
