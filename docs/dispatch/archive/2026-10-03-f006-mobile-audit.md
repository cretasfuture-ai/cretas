# F006 手机功能核验（进行中）

- Owner: 当前 Codex 手机测试任务，DIRECT；Base SHA: `c3eefe0c6af48cd690f770d7a6ec890d3aa8cebf`。
- Worktree: `C:\Users\Steve\cretas-f006-mobile-audit-20261003`。
- 设备：Mi 11 Pro，Android 14；安装版 `com.cretas.foodtrace` 1.0.5 / 16。
- 当前手机端点：生产网关下的 F006 测试租户。其他租户业务写入为 0；截至本记录 F006 业务写入也为 0，登录与操作遥测另计。
- 本机证据：`C:\Users\Steve\Documents\Cretas-F006-手机测试-20261003`。不提交账号口令、令牌、数据库密码、原始用户数据或设备位置。

## 修改范围

- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ResultFormatterServiceImpl.java` 及 `ResultFormatterMaterialInventoryTest.java`。
- `frontend/CretasFoodTrace/src/components/ai/{RichContentRenderer.tsx,richListPresentation.ts,__tests__/RichContentRenderer.test.tsx,__tests__/richListPresentation.test.ts}`。
- `frontend/CretasFoodTrace/src/screens/attendance/TimeClockScreen.tsx`、`src/utils/currentPosition.ts`、对应目标测试、`src/i18n/locales/{zh-CN,en-US}/hr.json`。
- 当前台账条目和本记录。后端权限、实体、Repository、全局配置、历史关联账务不在当前修复范围。

## 当前证据与限制

1. 原料库存查询读取 `quantity`，实际 DTO 返回 `currentQuantity`；真机回答显示 `?kg`。表格只取 DTO 前五个字段，数量和单位被元数据挤掉。修复优先读账面数量和批次 `quantityUnit`，零值保留，未知数量明确提示，状态显示中文。
2. 成本问题进入 `report_cost_query` 后被 W0 写确认保护拦截。该工具会生成分析报告；当前手机无可见后续确认操作。保护规则未修改，尚待确认链完整核验。
3. 考勤页面截屏在 19:10 与 19:18 都显示定位中。页面没有获取位置的截止时间，权限拒绝/失败后 `gpsLocation=null` 仍被渲染为定位中。修复须有超时、取消和重试，保持无真实定位时禁止打卡。
4. 独立加拿大 Mac mini 测试环境配置指向 `cretas_testenv`；实际 PostgreSQL 5432、Java 10010 均拒绝连接，launchd 明确 disabled。没有将不存在的实时账号读数当成空账号表，也没有启动停用服务。
5. 角色记录仅为 L1 登录/首页/主要页签观察；页面许可弹窗或测试选择器中断不能计为业务失败或完整通过。L2 表单/CRUD、L3 跨模块、L4 生命周期和完整权限拦截尚未全部完成。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

工厂操作员及其他需要打卡的岗位；手机熟练度有限，工作场所可能在室内，定位信号差或权限未开启。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|---|---|---|---|
| 1 | 考勤页面与定位状态 | 首次允许定位 | 使用真实位置，成功后开放合法的打卡按钮 |
| 2 | 定位中 | 等待至多 15 秒 | 无定位时显示明确原因与重试入口 |
| 3 | 定位失败/权限未授予 | 开启定位后点击重试 | 保留今日记录，重新请求位置，不自动提交打卡 |
| 4 | 返回其他页面 | 离开页面 | 取消定位监听；迟到结果不覆盖下一次请求 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|---|---|---|
| F1 | 定位请求无时间边界，加载可能持续数分钟 | HIGH | Rule 1 与错误恢复 |
| F2 | 拒绝权限/定位失败仍显示定位中 | HIGH | 明确错误，禁止伪装状态 |
| F3 | 刷新按钮只刷新考勤记录，没有定位重试入口 | MEDIUM | Rule 5，避免死路 |

### 每个摩擦点的设计回应

- F1：真实位置监听设置 15 秒截止时间，成功、失败、取消均清理监听，不使用假坐标或历史位置代替。
- F2：区分加载、成功、权限拒绝与失败；无位置时保持打卡按钮禁用，不自动保存业务数据。
- F3：失败状态显示现有 NeoButton 重试入口，触摸高度至少 44；保留原页面、时间与今日记录，刷新同时重试定位。
- 其他防呆规则：没有新增自由输入或默认勾选；不改变打卡确认及用户/工厂身份绑定。

## 验收命令与状态

- `mvn -Dtest=ResultFormatterMaterialInventoryTest -Dcretas.testIncludes=**/ResultFormatterMaterialInventoryTest.java test`：5 项通过。
- `node node_modules/jest/bin/jest.js --runInBand --runTestsByPath src/components/ai/__tests__/richListPresentation.test.ts src/components/ai/__tests__/RichContentRenderer.test.tsx`：6 项通过。
- `node node_modules/jest/bin/jest.js --runInBand --runTestsByPath src/utils/__tests__/currentPosition.test.ts src/__tests__/screens/TimeClockLocationRecovery.test.tsx`：9 项通过，覆盖超时、取消、迟到监听清理、权限拒绝与重试；不会自动打卡。
- `node node_modules/typescript/bin/tsc --noEmit`：库存与定位修复后均通过。
- 18 个活跃角色已完成 L1 登录和主要页签观察，设备管理员的许可弹窗页面需补核验。首道报工空表提交正确拦截，提示选择领料批次，未录入历史批次投入。
- 手机 Cretas 前台定位权限已开启（fine/coarse），USB 相关权限原本均开启；没有授予其他应用权限。
- 修复后的真机验收、PR、合并和发布尚未完成。
