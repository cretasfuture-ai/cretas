# DEBT-RN-UNUSED-LOCALS-780-20260907 — RN 侧未用局部变量/导入 780 条，**现在不做**

**日期**: 2026-09-07 · **线**: Line A · **状态**: 登记，等一个天然窗口 · **裁定**: organizer 2026-09-07

## 事情

2026-09-06 的坑（未用 `import { Alert }` 清理报了假读数）本质不是 `Alert`，是 **`tsconfig` 没开 `noUnusedLocals`** ——
这类东西编译器本来能抓，现在只能靠一道道窄闸去逼近。所以量了打开它的代价。

## 读数（[RUN] 2026-09-07，临时改 `frontend/CretasFoodTrace/tsconfig.json` 跑 `tsc --noEmit`，跑完还原）

| 配置 | 错误 | 文件 |
|---|---|---|
| 现状（两项都未设置，`strict: true`） | 0 | 0 |
| `+ noUnusedLocals` | **780** | **340** |
| `+ noUnusedLocals + noUnusedParameters` | 856 | 357 |

错误码分布：`TS6133` 740 / `TS6196` 34 / `TS6192` 6。

按目录（`noUnusedLocals` 单开）：

| 目录 | 条 |
|---|---|
| screens/platform | 153 |
| screens/factory-admin | 86 |
| screens/warehouse | 67 |
| screens/processing | 66 |
| screens/dispatcher | 60 |
| screens/smartbi | 59 |
| components | 46 |
| screens/management | 39 |
| 其余 | 合计约 200 |

## 为什么现在不做（organizer 裁定，理由要留着，否则下次有人重量一遍还得再纠结一次）

1. **代价不是「清理欠债」，是「让所有人停下来陪我清」**：340 个文件跨全部业务域，会与每一个在飞 PR 冲突。
2. **不做「只对已收敛目录开」的切片**（技术上可行，约 137 条）：那会造出一条
   「processing/warehouse/quality-inspector 干净、其它目录脏」的分界线，而**这条线不对应任何真实边界** ——
   下一个人看到会以为那是有意的架构划分。
3. 维持现状：**按类型加窄闸**（例如 `noRawAlertAlert.test.ts` 里那条「已收敛目录不许留未用的 `Alert` import」），
   窄而可信 > 宽而被关掉（形态 E）。

## 解冻条件

有一个**天然窗口**时一次性做：某个大重构本来就要碰那批文件（例如 platform 或 factory-admin 的整体改造），
届时连同 `noUnusedLocals`（是否含 `noUnusedParameters` 再议）一起开，并在同一个 PR 里清完。

⚠️ 重开这张票之前先**重量一遍**：780 这个数会随代码变化漂，⛔ 不要照抄本文档的数字去做决定。
