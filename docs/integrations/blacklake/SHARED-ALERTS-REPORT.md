# SmartBI 通用异常提醒与本地 AI

2026-11-03；PR #1390 的后续实现，未合并、未部署生产。

## 能力和接线

`smartbi.dataset-alerts/1` 把规则编号、类别、严重程度、文字说明、影响字段、证据、来源行、责任角色、下一步和核对动作放在同一结构中。规则无需 AI；AI 只能在允许的动作中选择核对建议和优先级，不修改事实、金额和计算结果，不宣称已找到真实原因。

- 质量：缺失数字、非法数字、完全重复行候选、字段映射歧义、样本截断。重复候选不自动删除，缺失不补零。
- 生产成本：明确的生产工单汇总数据才启用；识别缺少必需字段、产量非正、负成本、重复工单导致粒度不明、同产品同单位的单位成本偏高等。重复工单先排除；至少三个独立可比工单才做同组比较；产品名称冲突、单位不同不混比。
- 普通运营、销售和财务数据保留一般质量检查，不提示用户凭空补生产工单。这里只能解释可识别直接成本，不能冒充完整制造成本或毛利。
- 常规 `SmartBIAnalysis.vue` Excel 分析页使用公共 `DataAlertsPanel`，独立测试页使用同一组件。现有 `/api/analysis/*` 内联 POST 和数据库 overview 返回 `dataAlerts`；黑湖保留 `costAlerts` 并增加兼容别名。
- 其他连接器可调用统一 API；没有把所有连接器的独立存储流程自动改造为成本模型。系统数据同步的销售/财务专表不属于本次动态行加载器。

Java 小文件上传在启用 PostgreSQL 动态存储时，经 `SmartBIUploadFlowServiceImpl` → `DynamicDataPersistenceServiceImpl` 写入 `SmartBiPgExcelUpload`、动态行和字段表；与 Python 大文件异步上传共享 `smart_bi_pg_excel_uploads`、`smart_bi_dynamic_data`、`smart_bi_pg_field_definitions`。旧的非 PostgreSQL 上传记录不作为这些 ID 的替代来源。本次未改 Entity、Repository 或生产 Schema，不需要生产迁移。

## 接口与输入

认证使用现有有效 JWT 或内部身份，工厂来自已验证身份，禁止调用者越权指定工厂。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/smartbi/data-alerts/analyze` | 内联 Excel/接口/连接器数据，默认一般质量检查 |
| GET | `/api/smartbi/data-alerts/uploads/{id}` | 读取本工厂已完成上传，规则分析 |
| POST | `/api/smartbi/data-alerts/uploads/{id}/local-review` | 同一上传的可选本地 AI 核对 |

合成输入示例：

```json
{
  "source_type": "api",
  "source_id": "synthetic-demo",
  "dataset_kind": "generic",
  "columns": [{"originalName":"测量值","standardName":"测量值","isMeasure":true,"fieldType":"number"}],
  "rows": [{"流水号":"SYNTH-1","测量值":100},{"流水号":"SYNTH-2","测量值":null}],
  "with_ai": false
}
```

调用者明确提供生产工单汇总时才设置 `dataset_kind=production_cost`；可用 `field_mapping` 映射 `workOrderCode`、`finishedGood`、`materialCost`、`laborCost`、`productCode`、`unit`。这不会替调用者建立用料、退料、工序和报工的原始关联，也不能处理任意粒度明细的成本分摊。返回 `capabilities` 说明适用性，不能把不适用显示为零成本。

最多检查 5,000 行、200 列；API 请求最多 10,000 行及 10MB JSON，超出分析范围明确提示截断。提醒中的来源行定位至当前检查范围；数据库读取保留实际行 ID。没有持久化新的异常工作流或确认状态。

## 权限和本地 AI

已保存上传先按工厂核验归属，再读取字段和动态行，使用只读事务；不存在和其他工厂记录均为 404。一般明细角色之外的用户不显示提醒明细。已保存成本字段还要求既有制造成本完整授权；销售/财务还要求明确的全工厂财务授权。不确定时清空派生类别、数量、明细和指纹，不进行模型调用；数据库 overview 在聚合前执行同一边界，非法数字也在聚合前停止。

配置见根目录 `.env.smartbi-alerts.example`，默认禁用 AI。可选已配置 SSH 主机 `canada-mini`/`aurora-mini`，或 HTTP 回环地址端口 11434；不接受任意远端地址、重定向或云端回退。默认 `qwen3.8:27b`，另允许 `qwen3:8b`、`qwen3:14b`。

模型只收到有限的规则类型、匿名提醒编号、有限比例和允许动作，不发送工厂原始记录、产品名称、凭据或金额。每次最多 24 类代表问题；每个提交的代表问题都必须返回合法动作，超过范围标为部分完成。失败、忙碌、限额均明确提示，规则仍可用。缓存十分钟且绑定报告和配置。

共享 SQLite 状态文件仅保存哈希身份、租约和时间。所有 Python worker 必须使用同一 `SMARTBI_ALERT_AI_STATE_FILE`。同一模型主机只准一个推理请求，忙时立即返回；每小时每租户 12 次、每用户 6 次，失败的已接纳尝试计数。180 秒租约覆盖最长 160 秒推理，异常退出后可恢复。未安装模型、不修改其他任务运行配置。

## 当前验证和限制

- Python 最终目标集合：257 passed、0 skipped，含真实独立 SCRAM PostgreSQL 和受保护 Redis、租户隔离、权限失败、AI 完整性及并发限流。
- 前端目标测试、类型检查和测试模式构建通过；独立浏览器使用真实 Java 登录，验证 Excel/API 同规则、展开证据、27B 核对、权限拒绝、390px 布局，黑湖业务写入为零。
- 实际合成 XLSX 解析后进入独立测试库，与接口输入得到相同提醒；数据库 overview 用真实 JWT 和 PostgreSQL 验证非法数字不进入 SUM、跨工厂 404、无成本/财务授权不展示 KPI。
- 黑湖公共本地核对接口真实使用 27B 完成 15 类代表问题，规则未改。未向黑湖发送业务写请求。
- 8B/27B 同题仅四组合成规则事实：8B 有一次格式失败，27B 四项全部通过。这是结构化核对的小样本，不证明深层原因分析能力。当前以质量为先选 27B；8B 可继续评估。
- 现用 Java 测试服务的成本授权接口为 404，财务响应没有明确完整范围；因此受保护上传被正确拒绝。其常规上传页面的权限入口也未完成正常验证，不能声称“普通 Java 上传到完整成本分析”已验收。没有通过模拟授权绕过这些限制。
- 当前浏览器和 API 验收使用隔离合成数据；不代表生产发布、业务负责人确认、全产品关联补齐或新模型复杂归因验收。

完整私有日志与截图在本机 `%LOCALAPPDATA%/Cretas/SmartBIAlerts`，旧黑湖目标测试和模型对比在 `%LOCALAPPDATA%/Cretas/Blacklake/validation`。真实账号、JWT、连接串、工厂原始记录均不入 Git。
