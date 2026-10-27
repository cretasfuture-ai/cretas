# FADAXIS27 — 原生图表窄屏数值轴

状态 review；Base SHA d94becee2a792db2b23fe702fc2f220ff9af9fa6；Owner native_axis_fix，Root 唯一台账写者。两文件源码冻结，scope释放；Root后续负责单独分支/PR、OTA与真机复验。

F006 真机 sales SKU 横条图已显示产品名，但长品名挤压图面后 x 数值轴标签重叠。仅允许 frontend/CretasFoodTrace/src/components/smartbi/FactoryChartCanvas.tsx 和 src/__tests__/unit/components/FactoryChartCanvas.test.ts。对窄屏 value 轴处理标签避让，保留真实数值、单位、产品名、点击明细与导出；不动接口、业务数值、共用后端 option 或依赖。验收：该 canvas 测试、相关 FactoryAnalysisChatScreen 测试、TypeScript；Root 真机截图复验。只编辑本 scope、不提交、不改台账、不派生代理。完成测试即冻结，不能扩大美化。

执行回执：Canvas 8/8 passed、TypeScript exit0、diff check通过；Root已审查实际HTML/VM测试断言，value轴对象/数组均避让，category和既有formatter保留。原生问答屏源码未改，复用本批已通过的14项问答屏证据，不重复相同内容测试。发布前后截图、实际OTA指纹与原生全流程验收另行记录，不能把单测当真机通过。
