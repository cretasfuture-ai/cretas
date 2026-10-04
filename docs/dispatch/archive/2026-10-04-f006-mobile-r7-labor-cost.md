# F006-MOBILE-R7-LABOR-COST-20261004

Base bafc7fcb55e062e71ed7ce6677c2d3f04318571a；WT C:\Users\Steve\cretas-f006-mobile-r2-20261003；Owner根协调者/执行代理。允许七文件，均位于frontend/CretasFoodTrace/：

- src/screens/hr/analytics/LaborCostScreen.tsx
- src/services/api/timeStatsApiClient.ts
- src/services/api/wageApiClient.ts
- src/utils/laborCostData.ts
- src/__tests__/unit/api/timeStatsApiClient.test.ts
- src/__tests__/unit/api/wageApiClient.test.ts
- src/__tests__/integration/screens/LaborCostScreenReadback.test.tsx

仅cost convenience真实wire/nullable类型与屏幕；绩效/班次/导航/hrNavigation/HR中英文locale属于R6，禁止修改。需要新增译文时先回传精确key/value，由根在R6释放后串行添加。不得改后端/Repository/Entity/权限/角色/业务数据/依赖/Git/台账。

验收：三套真实API/屏幕Jest，必要相关消费者测试、完整TypeScript、diff --check；含真实同窗口工时/wage成本、未知vs0、403/5xx显式error、actor/period旧请求首帧/回包/刷新、DENY/GRANT不扩canonical、Top10只为样本不代替全体，dept仅实际hours非cost。当前1020h等模拟后端由R9独立修复，不能提前声称真机闭环。

root最终源码里程碑：三页旧owner刷新/重试先于state预检、错误首帧归属和同日删除确认反例已闭合；R7真实wire/NULL及actor/序号/焦点反例闭合。联合八套95项通过，最终TSC通过、diff检查通过；R6 59项/R7 36项，不代表完整CI、发布或真机验收。冻结21源文件供后续main整合；工资workerCount来自工时效率样本，不能推断工资记录是否存在，工资权限下后端无记录0语义仍待审。

最终整合归档：包含两个 Personnel 请求次序反例的 EA 源已与 fresh main 88de770f5d 整合，所有 frontend 文件与已验证 EA 完全相同（git diff 为零），21源 SHA 与 APK9 构建回执逐项一致。当前7套93项及单独正确 .ts 路径 shift API 1套4项均通过，共97个不同目标测试；完整 TSC通过。日志 r6-r7-integrated-core-final.log、r6-shift-api-integrated-core-final.log、r6-r7-integrated-core-typescript-final.log。APK9已安装隔离 auditf006 包并保留原应用，HR完整真机流程仍待验；后端R9工时修复尚未发布。源码冻结/移出在飞 scope，PR/CI/合入仍待完成，不宣称这些阶段已完成。
