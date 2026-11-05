# 食品DAG20 AI工艺标准参数

任务ID FOOD-DAG20-AI-STANDARDS-20261105；Base 41f58a822cf3a739db8b9c2a8c1ea281522d4913。
唯一根Owner 01a1a954-13e5-7182-9572-00ff306c8d31；执行food_dag_canvas_proof。独立worktree C:/Users/Steve/cretas-food-dag-ota-20261105，branch codex/food-dag-ai-process-standards-20261105。

原2026-11-04T05:56:31主会话明确DAG20仍欠AI带入#1373工艺标准字段；目前WorkProcessCatalogTool与专用抽取prompt均无customFieldSchema，属于真实遗漏。补已存在number字段key/label/type/enabled/unit/standardValue/min/max，从自然语言提取至预览、人可读确认、创建/修改DTO和既有Service；复用WorkProcessCustomFieldSchema唯一校验，不改变共享校验/Entity/Repository/数据库，不扩大至静置时间、设备、秤绑定。

Scope锁：WorkProcessCatalogTool.java；CanvasAIController.java仅WORK_PROCESS_CATALOG_SYSTEM_PROMPT；WorkProcessCatalogToolTest.java；WorkProcessCatalogChatContractTest.java；新增WorkProcessAiStandardsContractTest.java；本归档。ACTIVE仅根Owner写。

目标：WorkProcessCatalogToolTest,WorkProcessCatalogChatContractTest,WorkProcessAiStandardsContractTest,CanvasAIWorkflowConfigTest,ProcessParamStandardsContractTest。使用-Dcretas.testIncludes=**/*.java保证不误空跑。生产与测试最终冻结前只跑目标，无自行commit/push/部署。返回文件、命令、计数、日志和风险。新源码读取门禁必须*ContractTest。
根协调风险终审补充scope（原P1未解决前不提交）：新增字段改变旧raw参数的写语义，版本必须1.1.0，避免旧1.0.0 proof携带此前被忽略的schema升级后产生新作用。仅额外允许Controller对应WORK_PROCESS_CATALOG_VERSION、resources/ai/tool/gateway/tool-descriptors.yaml与runtime-tool-policies.yaml中该工具的version项，及相关精确版本测试；不改任何其他工具策略/权限/全局配置。新增typed AI transport边界拒绝非Boolean enabled，以及非String key/label/type，继续由共享schema验证工艺标准业务规则。create+[]应显示不配置字段，update+[]确认后清空，预览不说已清空。当前中间文案版本Maven31221须先安全结束，再改以上冻结字节并统一最终验证。

追加目标门禁：ToolDescriptorCatalogTest,RuntimeToolPolicyLoaderTest,ToolDescriptorInventoryDriftTest,RuntimeToolDescriptorRegistryTest；既有ToolRuntimeRegistryTest/ToolRuntimeClassificationContractTest在最终整合选择器中保留。两个yaml只改本工具版本，不降低治理，不增权限。
## 最终冻结验证

- 原9类最终70 tests/0 failures/0 errors/0 skipped，2026-11-05 14:27:51 BUILD SUCCESS，日志dag20-ai-standards-versioned.log；receipt dag20-versioned-receipt.json绑定生产4文件hash与逐类XML。之前42/43为中间字节，不能替代最终证据。
- 仅随后更新两类版本夹具：ToolRuntimeRegistryTest 8、ToolDescriptorPolicyResolverTest 4，合12/0/0/0，14:32:10 BUILD SUCCESS，日志dag20-runtime-version-accepted.log与新鲜XML。Factory/permission_admin用1.1.0通过；operator与RESTAURANT在正确1.1.0下仍拒绝；旧1.0.0明确拒绝。未放松任何runtime解析/权限断言。
- 合11类82唯一用例通过；shared validator保持原样，typed入口拒绝非字符串key/label/type、非布尔enabled；创建[]不称清空，更新[]确认后清空及整组替换提示。
- 版本四方均1.1.0，两个yaml仅该工具version项变化，未改其他工具治理与权限。新写测试后缀ContractTest确保会执行；整合候选后另用57类统一制品门禁覆盖Canvas真实proof链与本次字段。
- 根协调归档本地ACTIVE临时行，不改其他任务行。未宣称PR合并/生产部署/业务验收；生产验收仅预备新F006独立QA工序创建/读回/重放拒绝/官方清理。
整合门禁补充scope：WorkProcessPreviewApplyContractTest.java仅对应新1.1.0工序工具的claim夹具与标准字段断言。首次57类构建413项中3失败，均旧夹具硬编码1.0.0（实际版本保护正确拒绝），没有放松校验；Web构建成功但统一回执failed/deploy none，不能记stage成功。修后该3类场景实际3/0/0/0，2026-11-05 14:57:58 BUILD SUCCESS，日志dag20-proof-persistence-accepted.log；原真实Service→Entity保存链增油温unit/standard/min/max预览及保存完全相等、proof1.1.0断言，未新增模拟“成功”旁路。11类82+整合3共12类85唯一覆盖，最终57类制品需重建。