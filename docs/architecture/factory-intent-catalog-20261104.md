# 工厂意图目录收敛（2026-11-04）

规范源为 `backend/java/cretas-api/src/main/resources/ai/factory-intent-catalog.json`；Python 部署携带字节相同的 `backend/python/ai/data/factory-intent-catalog.json`。处置依据为同目录的 `factory-intent-retirement-20261104.json`。本批仅准备源码和迁移，不部署、不替换线上模型。

| 项目 | 数量/处理 |
|---|---|
| 原活动平台目录 | 347 |
| 退役活动餐饮专属意图 | 81；历史 `RESTAURANT_*` 同样拒绝 |
| 原餐饮域共用配置 | 29：28 项直接转正，1 项别名并入既有标签 |
| 确认合并的别名 | `EFFICIENCY_COMPARE_ALIAS → EFFICIENCY_COMPARISON` |
| 最终规范标签 | 265：254 条 Tool 路由、11 条 Skill 路由 |
| Tool 绑定 | 251 个唯一名称；Skill 内工具另有真实注册与只读闭包检查 |

餐饮专属 Tool 实现、内置 Skill、Python AI resolver/路由注册和 Java 自然语言/SSE 委派退出运行目录。已有业务单据、审计、共享 ETL、日期/实体/租户解析 helpers 保留。`TieredIntentDelegate` 仅留无外呼的兼容入口，不能访问旧 Python 餐饮 API。

工厂共用能力补齐真实绑定：赠品入库、已审批正差盘点应用、销售产品排名、发货行批次分配、自动发票状态/税率分组查询、追溯码格式。赠品数量与公斤重量分开，来源单据与来源行建立幂等键；复用现有活行来源事件唯一索引，软删后重收契约不变。成本重建失败终止入库，同物料通过数据库行锁串行重建成本。产品排名按产品总金额排序，不同单位的数量分别保留，不能伪相加。

税率分组入口默认只读查询系统自动生成的发票，缺销售订单 ID 时澄清；手工 `ISSUE` 仍拒绝，不能绕过自动开票政策。批次分配的 AI 确认入口支持 `AUTO_FIFO`；`EXPLICIT` 预览明确阻断，不铸确认令牌，人工指定批次继续使用既有发货明细业务页面。

11 条目录 Skill 校验真实 Tool 注册及 READ 属性；食品召回评估不包含冻结/通知/监管上报写链，留样联查不包含创建/销毁写链。全局和工厂停用配置、绑定意图 inactive/tombstone 都必须在实际执行入口生效；缺参和 Tool 失败逐层传播，不以失败触发猜测性写路由。三个治理意图仅允许可信平台管理主体，不能靠用户传入角色字符串取得全局权限。

召回/短语/RAG/执行/训练导出拒绝退役标签，别名只归一到已确认的规范标签。工厂配置的停用行遮蔽平台默认项，业务域投影先于已有 domain gate。目录不是全局租户自定义标签白名单，非退役的工厂自定义能力仍保留。

训练目录通过以下命令单独生成，不改现有模型头的标签 ID、权重、保护集或微调测试集：

```text
python scripts/finetune/generate_factory_intent_catalog.py <output_dir>
```

现有 259 头分类器与新 265 类规范目录是不同契约；本批不重新编号旧模型，不把新目录生成冒充模型已迁移。

新迁移为 `V20261104_43__factory_intent_catalog_retirement.sql`，由 `scripts/ai/generate_factory_intent_migration.py` 生成，`--check` 检查漂移。它退役配置、归并别名短语、重分类共用能力并更新明确的 Tool/Skill 绑定；保持工厂隔离与实际业务权限。人工开票的拒绝/只读查询路由及密码导航取消旧写分类及写审批标记，实际开票/密码写权限不变。迁移兼容旧 bootstrap 的 TEXT JSON 文档列，非法旧 JSON 明确失败。

`scripts/ai/verify_factory_catalog_migrations.py --dsn <loopback_test_dsn>` 在隔离 PostgreSQL schema 验证迁移、短语冲突、旧列类型、别名域、权限保持、来源行唯一性和软删重收，并验证数据库行锁协议。真实 Service 的事务回滚及并发验证另由 `FreeGiftCostAtomicityRepositoryQueryValidationTest` 执行，支持独占的 PostgreSQL 测试数据库。

验证记录位于本机 `C:/Users/Steve/Documents/Cretas工厂意图目录-20261104/`。首次完整 Python Gate 的旧餐饮成功断言失败保留于原日志，清理用例逐项记录在 `python-retired-test-manifest.md`；不新增 skip 或 CI 排除项。全新空库 Flyway 在既有 `V20260827_01` 因缺少 `production_settlements` 阻断，早于本批迁移，不能声明全量 fresh-install 已通过。生产迁移、迁移前备份/恢复验证与真实业务验收均未执行。
