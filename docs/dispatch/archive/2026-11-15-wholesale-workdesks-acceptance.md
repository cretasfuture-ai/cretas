# 批发商三岗数字员工验收（2026-11-15）

范围：WHOLESALE_0000_001（批发商试用），业务写入仅该租户，实时登录actor 1709。其他客户零业务写入，未修改其菜单配置。原资料的“先不要执行”是历史需求阶段；实际账号和本次修复以用户后续明确请求为准。

## 已部署的实现

PR #1742 → 914c9444b602fc017fbc851018eaf5c0be4110c3。三岗在采购/销售/仓储各自菜单内；复用11-14最新组件库的Nuxt Frame/Conversation与clerk/keeper形象，原有ERP表单保留。没有另设数字班组。

统一入口 /{factoryId}/ai-intents/execute，READ+session，自由提问与服务端澄清。写请求通过现有业务入口及一次性原话交接转入原生AI录入，人工核对后保存。权限、企业、操作人切换检查及过期/重放防错已覆盖。

修复销售取消编辑后AI开单误PUT旧单、采购AI漏供应关系/税率、保存后列表合计不刷新、空物料目录误称库存充足、预警正文与结构化明细矛盾、缺建议误显示0、待收查询范围提示不实及批次查询Gateway来源拒绝。

## 实际界面与业务读回

| 场景 | 证据与结果 |
|---|---|
| 销售 | AI预览→原表单→创建SO-20261115-0002，ID34f6abfa-02e0-47a2-8529-ba06c111475b，1kg/含税10；旧SO-0001的ID0d717ce2-b077-4b63-a670-0eabb88b3514、version1与updatedAt未变。列表2条/未税17.70一致。 |
| 原话交接 | 销售与采购工作台均实际带原话打开原AI录入，未自动解析/保存；关闭再普通打开输入为空。 |
| 采购查询 | “有哪些原料快不够用了”返回实际低库存：2kg/安全10kg/缺8kg，建议未提供；原材料批次查询返回MT-20261115-6820、账面2kg。 |
| 采购收货 | PO-20261115-0001 ID53fe5c1b-8ed8-464e-8b0c-48887b42d3e3，原生采购2kg→提交→收货；无凭证时按钮禁用，上传明确TEST ONLY的QA附件并核对二次确认后入仓。 |
| 仓储 | RCV-20261115-8478 IDb5aec35f-7470-44d6-bc84-4e76bc3c773a CONFIRMED；原PO COMPLETED/收2/待收0。批次6753a9dd-b419-4542-ae25-1efaacb5f6ef AVAILABLE/2kg，sourceDocId对应收货单。岗位澄清按钮→真实待收结果→原库存页1批2kg已点通；keeper图片实际加载64×64。 |
| 菜单 | 主线新增委外单曾让生产组重新出现；仅试用租户production.disabledMenuPaths补/production/outsource，写前/后菜单回读，其他模块逐字段保持；刷新后生产组/称重隐藏，SKU及数据分析保留。 |

## 当前线上意图架构

发布后实际Java green PID258533、Python PID4122122进程环境验证：AI_USE_PYTHON_MATCHER=true、工厂白名单空（全部）、AI_DECISION_LAYER_BUSINESS_TYPES=FACTORY。三岗READ探测HTTP200/SUCCESS，分别ORDER_LIST/MATERIAL_LOW_STOCK_ALERT/MATERIAL_TODAY_RECEIVING_QUERY；14:51:38-40 Python日志该factory的ai_decision真实模型调用200。规则快捷匹配可直接返回，非每问都调模型；Gateway按具体工具策略条件接入，不宣称全面自主AgentRuntime。

## 验证与发布

Web相关39files/335tests；最终库存文案追加4files/26tests；最终tsc exit0。主线合入后8个后端目标类51tests全通过。#1742当前头CI全绿，独立审查无阻断。

统一发布回执cretas-1794725034-10015.json：DEPLOY_EXIT=0、RELEASE_FINAL_STATUS=deployed恰1次，Java/Web均成功；backend green10020健康，Web四方index SHA均530f2eb7d8b50b2aff98ac5d8c262afc5f18056519e55d760d3954ff418bc5ab。构建前后AI_LIVE_PROBE=off，无F006探测写入。

## 采购报价限制与防错交付

线上AI 1kg×含税5@13%揭露旧计价算法先未税行四舍五入再含税，保存4.99。复现单PO-20261115-0002 ID2c3a4334-5e94-4edb-beaa-57f9872059e0已通过原UI取消，独立回读CANCELLED/version2，未提交/收货。

PR #1743 → d0e79ab9a89aa8eab480ca764b7257ff53a41604（候选fa4f0235822afb47ca7140d532cc1860babad4b8），仅新增前端AI预览confirmGuard：检测物料基础单位报价分差，保留原话和预览、禁用填表；AI税率与档案冲突明确阻断，其他税率可在普通表单核对。无单位/未知或异单位不推换算，交原链路。4files/56tests、tsc、标准Web构建、当前头全部CI通过，独立审查无阻断。

标准Web-only发布回执cretas-1794727246-1517.json：DEPLOY_EXIT=0、RELEASE_FINAL_STATUS=deployed恰1次，Web四方SHA均ddedb19d66f972d696d6584236b7b254965ffcf9d4a5a114dd6776020984d739；后端未重启，前次成功健康回执及本次发布前真实upstream10020复用。

正式线上反例：1kg×含税5@13%，预览明确5.00对4.99，填表按钮真禁用，原话保留；独立读回采购数量仍2。点击“继续修改”把同份信息改2kg，保留供应商/原料/税率/日期后警告消失，核对原表单再保存PO-20261115-0003，ID5737b38b-a0ee-429a-8e88-c930aaf1acfa，DRAFT/createdBy1709/2kg/未税8.85+税1.15=含税10，supplierMaterialId精确对应已创建供应关系。列表3条/总未税22.12（含取消验收记录）与独立回读一致。

15:24最终final-business-readback.json断言全部PASS：采购集合2→3恰1个新ID，正例供应关系/金额/日期；反例单CANCELLED/version2；原PO COMPLETED/version5/实收2；原销售version1/updatedAt未变；新销售DRAFT/1kg/含税10；原库存批次AVAILABLE/2kg且sourceDocId对应原收货单。

不改DTO/Entity/后端财务算法或历史金额。完整的含税报价保真仍为待开发。原批次单价两位小数估值8.84与采购未税8.85的既有估值精度差也未改变；本轮未声称财务勾稽完成。

自动按规则分单、销售商品与采购原料统一映射、上游直送执行尚未接入，界面明确显示待开发。QA样例曾保留供核对，后按用户选择完成下述精确清理；未做清空租户、跨租户写入或真实付款/对外发送。

完整截图、原始失败、独立读回及发布日志保存在本机 .codex/tmp/wholesale-acceptance-20261115，凭证未进入仓库。

## 16:45 QA样例清理后续

用户选择“验完清理样例”后，精确备份并删除WHOLESALE_0000_001本轮固定ID的19表28行；不是清空租户。执行exit0、stderr空，独立事务回读19表目标全0；14个账号/配置/规则类表count和fingerprint一致，15个审计/runtime表所有原PK保留且count不下降，独立审查重复验证通过。恢复材料存服务器/root/cretas-task-backups/WH-QA-CLEANUP-20261115-164508（700/600），OSS测试附件对象作为备份保留，业务元数据已删。账号、菜单、密码和数字工作台均保留。原料列表缓存另按单租户键精确失效并回读，不触及其他客户缓存。

## 20:38 当前数字专员版本收尾

用户要求先推送当前内容并释放占用，本轮停止继续扩修，以下边界如实保留。

- #1753 合入 `2af10177ed1d13c1aad5cda2850c35f7523f4ee7`，候选 `42335b2ab16bc422e0dca5c99515a479f797730d` 适用CI全部通过。九类现有工作台统一岗位标题、形象与共享组件，保留原业务办理入口；批发商限制独立控制。补齐factory/actor/role/导航切换隔离、撤权后的确认及导出边界。
- 修复短消息重复限宽导致竖排、旧READ会话终态吞新问题。严格匹配终态后仅重建READ会话重问一次；重复终态明确报错。默认查询改用已可用订单/库存/预测/利润表；财务采用原生事实和实际期间，缺原始结果不展示生成摘要；采购补充查询保持同一busy轮次。非餐饮销售页不再请求餐饮POS统计。
- 目标边界30项、菜单/权限门禁32项、只读脚本12项通过；产品与门禁类型检查通过；合入主线后的集成24项通过。权限分析器真实建模各岗canWrite，预算未提高。最终只读脚本增加标量诊断，要求业务入口实际跳转，不能用缺链接或空回复判通过。
- #1759 将发布Base绑定实际合并父提交 `22dbc455df925bdbd343a628f3725df08b4d0f72`，排除其他任务Java。规范Web-only发布SHA `2421721bc1c576ebdf0e729855e1c9a8e3153dc6`，回执 `cretas-1794744415-4141.json`：exit0、DEPLOY_EXIT=0，RELEASE_FINAL_STATUS=deployed恰1次，四方index SHA均 `13f53e2d50db62ececeb236dac0ec92bdd29b5bd0c4ea2f43d251c9486486980`，Java not-selected。本次未重启后端。
- **线上通过**：批发商三岗的标题/形象、原业务入口及跳转、销售新回复和单行短消息；HTTP/body-read错误0，业务写0、拦截尝试0。记录在主目录 `.playwright-mcp/production-readonly/unified-specialists-trial-20261115/report.json`，截图同目录 `sales-specialist-live.png`。不宣称“我是谁”的身份语义已正确。
- **线上未验收通过**：F006八页实际遍历，`unified-specialists-factory-v2-20261115/report.json` 标记 finance、ai-value。财务原生利润表事实已显示，但财务三表入口跳转断言未通过；AI价值页出现“加载失败”，未取到头像和业务入口。HTTP/body-read错误0、业务写0。根因未定位，不能声称八页全部正常。其余页检查通过；三个非必点页面仅核入口存在，不能扩大为全部入口点击通过。
- **未覆盖**：物流账号真实UI（无可用账号），由组件/状态/撤权测试覆盖；其他工厂审批、起产、放行等生产业务写闭环未执行。本次发布不代表按规则自动分单、直送执行等待开发能力已完成。

私有原始日志、清理恢复材料与各版失败证据保留；主工作区原有未提交内容保持不变。相关在飞scope随本次收尾归档释放，后续两项验收问题另行继续。
