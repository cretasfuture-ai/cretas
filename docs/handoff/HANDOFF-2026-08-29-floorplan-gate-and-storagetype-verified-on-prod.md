# 交接 2026-08-29 — 平面图上限闸合入 + 储存类型校验在 prod 上真跑了一遍

**上一份**：`HANDOFF-2026-08-28e-liushanmen-answered-and-the-orchestrator-rollback.md`
**本份覆盖**：#316 合入、prod 制品核对、储存类型后端校验的真实行为验收、以及**一次仪器自坑**。

---

## 一、本轮合入

| PR | 内容 | 判据 |
|---|---|---|
| #316 | 平面图体量上限前后端两份 → 加闸 `floorPlanLimitsMatchBackend.spec.ts` | 双变异都红(300→250 / 400→5000)，全量 vitest 475 passed |

### #316 中途被**元闸**正确拦了一次，值得记

CI 报的不是我的闸，是元闸 `gateTriggerSurfaceCoversWhatItReads`：

> 以下 Java 文件被 web-admin 的 spec 读着，但改它们不会触发 web-admin-gate

我的 spec 读 `WarehouseLayoutController.java`，而它不在 `web-admin-gate.yml` 的 `paths:` 里
⇒ **一个只改后端上限的 PR，恰好是这道闸要守的那类改动，而它看不见。**

⛔ **修法特意用逐个文件的精确路径，没用 `src/main/java/**` 宽 glob** ——
宽 glob 会把元闸自己「证明不过宽」的阴性对照变成恒真式（2026-08-28 已实测踩过一次）。
变异验证：删掉这两行 → 元闸红并点名该文件；加回 → 3 passed。

---

## 二、prod 制品核对（硬约束 2）

活跃槽位 `cretas-backend` pid 3616249，**进程启动 00:50:30 晚于 jar mtime 00:50:03**。
五个标记在**运行中的 jar** 里逐个核对：

```
请选择储存类型                  -> 1   (RawMaterialTypeServiceImpl)
generateSuggestionsIsolated     -> 3   (SupplyChainOrchestrator)
voucher_type_subject_overrides  -> 1   (VoucherSubjectResolver)
voucherSubjectResolver          -> 1   (AbstractVoucherGenerator)
PO-AUTO                         -> 1   (ProcurementSuggestionService)
```

### 🔴 但第一次量出来的是 `请选择储存类型 -> 0`，而那是**仪器坏了**

我用的是 `unzip -p jar | strings | grep -c`。**`strings` 默认只抽可打印 ASCII**，
Java 常量池里的中文是非 ASCII 字节 ⇒ 整类中文一个都不输出。
四个 ASCII 标记全命中，只有中文那个读 0 —— 而 `0` 正是「这次没部署上去」的长相。

三个读数一起定的案：

```
grep -ac "请选择储存类型"        -> 1   # 标记其实在
grep -ac "isPackagingCategory"   -> 1   # ASCII 阳性对照
grep -ac "参考价"                -> 4   # 改动前就有的中文 ⇒ 证明中文可 grep
strings | grep -ac "[一-龥]"     -> 0   # 🔴 仪器自检: strings 对中文全瞎
```

⇒ **验制品标记一律 `grep -a` 原始字节，不要经过 `strings`。**
已写进 memory `feedback_strings_hides_chinese_so_my_deploy_check_read_zero`。
⚠️ 与既有的 Hermes 那条**症状同、成因反**：那次中文真的不在制品里，这次在，是工具滤掉的。

---

## 三、储存类型后端校验 —— 在 prod F006 上真跑了一遍

「制品里有标记」不等于「它能跑」，所以拿 `f006_admin` 真发了三个请求：

| # | 输入 | 结果 |
|---|---|---|
| ① 变异用例 | 原料 + 空 storageType | **400「请选择储存类型」**，`hintTarget=storageType` ✅ |
| ② 阳性对照 | 原料 + `冻货` | 200 创建成功（code YL120）✅ |
| ③ 豁免对照 | 包材 + 空 storageType | 200 创建成功，`storageType: null` ✅ |

⚠️ **第一次做这个测试拿到的是两个一模一样的 400**（都是「基本类型「鲜品」尚未定义料号前缀」）——
一道更靠前的闸先开火，变异用例和阳性对照给出**相同读数** ⇒ 那一轮什么都没量到。
换成有料号前缀的 `原料` 才分得开。**同样的 400，来源不同就不是同一个读数。**

**善后**：②③ 建的两条测试数据已硬删。`BEFORE_total=322 → AFTER_total=320`（正好 −2），
`ZZ_remaining=0`。两个数一起看，才排除「查询坏了所以查不到」。

---

## 四、平面图 · 现场盘点页签回归（上一份挂着的账，已了）

上一份写着「点了两次没切过去，渲染器一直超时，我停手了，没有硬凑一个读数」。**现在补上了。**

不是页面挂了 —— `get_page_text` 一直有响应，是**截图/注入那条路**在抖。
改用 JS 直接驱动页签，三个页签逐个读正文：

```
仓库平面图 -> 1 层 / 楼层 / 添加货架 / AI 识别底图 / 全屏编辑 / 还没画在1层的区域 + 4 个区域
现场盘点   -> 原料 23 项 / 成品 1 项 / 包材 3 项 / 辅材 2 项 + 真实批次行
储存位置   -> LC01 / LC02 / LC03 …
```

⇒ **现场盘点无回归**，平面图新功能在 prod 上是活的。

---

## 五、⛔ 没做完的，逐条写清楚

| # | 事 | 状态 |
|---|---|---|
| 1 | 平面图**文字标注不支持缩放** | **没做**。功能缺口，不是缺陷 |
| 2 | 非管理员**只读态** | **只做到源码级**，没做真实登录验收（见下） |
| 3 | 手机端真实业务链（报工/扫码入库/盘点） | **一次都没碰**，要真机 |
| 4 | 六膳门 21 条空 `storage_type` 物料 | **等客户填**。⛔ 我不猜「冻货 vs 常温」 |

### 关于 #2 为什么停在源码级

四层都在：组件 `interactive:false`（带变异的单测）、
页面 `:interactive="canDesignMap"`（= `canEditLayout && mapFullscreen`）、
每个改动 handler 早返回、每个编辑按钮 `v-if`。

**但没有用非管理员账号真登录验一遍。** 理由：那要覆盖浏览器里当前 `张六膳` 的 localStorage token，
本仓有过「我注入的凭证覆盖了一个能用的会话，比不注入还差」的事故。
⇒ 登记为**源码验证，非实测**，不写成「已验收」。

---

## 六、给下一位的判据

1. 验 prod 制品标记 → `unzip -p $JAR $CLASS | grep -ac "<标记>"`，**别用 `strings`**；
   一次混一个 ASCII 标记和一个改动前就有的同类型标记当对照。
2. 拿 API 验后端校验时，**先确认变异用例和阳性对照的报错来源不同** ——
   两个相同的 400 等于没量。
3. 给已有闸的 `paths:` 补触发面 → **逐个文件列**，别加 `**` 宽 glob。
