# P20 经营结果到既有成本明细的真实口径

任务P20-BATCH-COST-EVIDENCE-20261017，Owner root，Base 8ed277c435b7b8a9f3e87f3677d6d1374a8d8947。沿用原独立分支与新厂14-17批，本地验收；无push/PR/合并/部署或生产访问。

检查发现：按成品结果→追溯→“查看投入产出与成本”已有成熟批次详情。成本接口失败、hasData=false或缺少金额时，详情却会借旧batch金额显示总/单位成本；PARTIAL之外状态和未知缺数编码也可能误导。scope仅既有批次详情、成本展示helper和目标挂载测试、本档案/ACTIVE。复用既有cost-breakdown及补录入口，不开发另一套计算，不修改Java、指标公式、权限服务、业务数据或表结构。

完成条件：完整/不完整/暂无/失败/脱敏区分，不借旧批次金额，不把已归集合计当完整成本；中文缺数原因和重试可用；过期请求/身份变化不能恢复旧成本；同一新厂账户从成品结果走到现有批次成本明细核对组成和单位，原业务主表不变。必要目标Web测试与构建，保留真实UI/API/PG证据。

实现：仍使用既有批次详情的成本卡、cost-breakdown接口、原料消耗/逐道报工和设备其他成本补录入口。新增小型展示helper，原缺数编码表迁移至helper并把未知编码统一为中文；金额只来自当前批次接口，不再以batch.totalCost/unitCost等补值。读取失败/NO_DATA/状态矛盾/价格脱敏均不展示旧金额，完整成本与已归集合计分开；顶部单位成本同步处理。计价单位优先服务端costDenominatorUnit，不从历史批次单位推算。明确展示半成品/成品投料桶（存在时）；不新增或重复计算成本。

请求绑定工厂、路由批次、当前用户/角色与价格查看权限，响应另校验finishedBatchNumber；身份不符或金额格式错误显示错误。权限撤销、失活和卸载清理并废弃未完成请求；失败与无数据可重试。未获得有效成本前禁用既有补录按钮，避免带不明金额进入补录；本轮没有进行补录或其他业务写入。

先红后绿：实际挂载原批次详情的4条测试先失败，明确复现完整响应的null设备成本借旧777777、接口失败借旧999999、NO_DATA借旧值，以及未知缺数英文编码直接展示。修复后4条均绿，继续补齐16项成本挂载测试（真零/服务端单位、半成品投料、重试、权限隐藏与撤销、错误/错批数据、状态矛盾、旧用户响应、路由切换）。最终17文件142项目标测试全部通过；Vite构建通过，退出码0。源码与测试3文件SHA256冻结，文档收尾不重复构建。

真实本地UI走原链：经营指标→散装/盒装来源批次→追溯→“查看投入产出与成本”→批次详情。盒装17批原料100+人工3.33+辅料0+包材10+设备12+其他3=完整128.33元，单位6.42元/盒；散装16批包材4、其余相同，完整122.33元、单位15.29元/kg。单位小数沿用后端perBoxCost的既有舍入，不用舍入后的单位价反推合计。原料消耗记录与报工小计可在同页核对，报工小计未冒充完整成本。

HTTP原接口逐字段与P19冻结成本证据一致，finishedBatchNumber/COMPLETE/costComplete及计价单位正确。PG回读生产、库存版本、出货、应收、质检均与原基线逐值相同，旧指标快照保留；未建样本或重复出货。最终新厂账号user2停在17批成本明细，浏览器错误0，盒装/散装AX与截图已保存。

证据：私有目录`C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p20/`中web-red.log、web-cost-target.log、web-final.log、web-build.log、source-freeze.json、api-cost-sources.json、after-ui.json、boxed-cost/bulk-cost/final-page的txt/png。测试命令`npm test -- src/views/production/batches/__tests__ src/views/indicator-center/__tests__ src/api/__tests__/productProductionMetrics.spec.ts src/api/__tests__/indicatorHistory.spec.ts`，构建`npm run build`，PG/API复核verify_readback.py与check_business.py after-ui。故障/缺数/异步阴性使用实际挂载页面的模拟响应，未破坏原业务数据来制造失败，也未声称浏览器真实服务器故障演练已执行。

交付：本轮仅Web，P19 Java/Python/PG运行时原样复用，无重载、迁移、CI、合并或部署；不是完整全仓测试或生产验收。仍在独立分支等待用户体验。
