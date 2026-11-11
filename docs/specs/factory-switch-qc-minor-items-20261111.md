# 动态质检表次要项不合格：做成工厂开关（2026-11-11）

来源：业务验收第二班交接单第一节第 2 条；Steve 11-11 拍板「做成开关」，默认取交接单的 B。

## 开关

`factory_thresholds` 全局默认行 `quality.inspection.fail_scope`（迁移 `V20261111_92`），读取只走 `FactoryDisciplinePolicy.inspectionFailScope`。

| 值 | 含义 |
|---|---|
| `ANY_ITEM` | 任一项不合格整张 FAIL（原行为；写错的值也按它处理） |
| `MINOR_FOR_REVIEW`（默认） | 关键 / 主要项不合格整张 FAIL；只有次要项不合格 ⇒ 条件合格（CONDITIONAL），标出这些项交质量经理决定 |
| `CRITICAL_ONLY` | 只有关键项不合格整张 FAIL；主要 / 次要项不合格 ⇒ 条件合格，交质量经理决定 |

没标严重程度的项按关键项算。

## 不变的

- 整张 FAIL：照旧建告警、隔离本计划成品、走处置复核。
- Steve 11-04「不合格批次只能走处置和复核改状态」：已判 FAILED / 返工 / 报废的批次，后补的条件合格不改批次状态（`QualityInspectionServiceImpl.propagateToProductionBatch`，`QualityBatchDispositionContractTest.pendingAndConditionalCannotClearFailedBatch` 钉着）。
- 质量经理的决定入口就是现有的处置：条件合格的检验记录可以发起处置（放行 / 条件放行 / 返工 / 报废），结果页「处置」按钮对 CONDITIONAL 本来就显示。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
质检员（quality_inspector），技术素养偏低，在车间用手机按动态质检表逐项填实测值，提交后看结果页决定下一步。

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 质检表，每项有标准范围 | 填实测值，有不合格项时写原因 | 能提交 |
| 2 | 提交中 | 等待 | 进入结果页（以前遇到条件合格会报「服务端未返回完整的质检结果」） |
| 3 | 结果页：「有条件合格，待处置」+ 点名哪几项不合格、由质量经理决定 | 返回首页或继续检下一批 | 知道这批没有判不合格，也知道不归自己拍板 |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 表单只认 PASS / FAIL，条件合格时报错，像是提交失败 | HIGH | fool-proof Rule 5（Dead-end） |
| F2 | 结果页只说「有条件合格」，不说是哪几项、谁来决定 | MED | fool-proof Rule 2（上下文必带身份信息） |
| F3 | 原有条件合格文案写「完成处置审批前不得进入包装或出货」，对次要项场景不准确 | MED | 错误恢复：发生了什么 + 怎么解决 |

### 每个摩擦点的设计回应
- F1 → `QIFormScreen` 接受 CONDITIONAL，跳结果页时带上 `reviewItems`。
- F2 → `QIResultScreen` 有 `reviewItems` 时写「X、Y 不合格。按本厂设置不判整批不合格，已交质量经理决定；质量经理可在本记录发起处置。」
- F3 → 只在有 `reviewItems` 时换成上面的文案；没有标出项目的条件合格（旧入口）文案不变。
- 不新增按钮、不加字段：主操作仍是唯一的「返回 / 继续质检」，触摸目标沿用原组件。
