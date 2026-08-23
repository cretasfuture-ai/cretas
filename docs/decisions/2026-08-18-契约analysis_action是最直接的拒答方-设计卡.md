# 契约的 `analysis_action` 是最直接的拒答方 —— 设计卡

**日期** 2026-08-18／19 · **决策人** 本 chat（全权）· **状态** 已裁定，已实现，⛔ 未部署

---

## 〇 · 先说一件事：上一份读数已经过期

`docs/decisions/2026-08-17-追问被叙事接地闸毙掉-设计卡.md` 第九节报的
**「契约 `analysis_action` 4/8」**，commit 是 `a693e74c52`，时间 **2026-08-17 11:16**。

而认「诚实说明这一层做不到」的修复 `cb57be0798`（#2753）落在**同一天 12:08** ——
**晚 52 分钟**。

▎那份归因图量的是**没有 `_explicit_analysis_gap` 的代码**。
▎拿它给修复后的代码排靶心，等于用修复前的读数定优先级。

⇒ 本卡的第一步不是动手，是**在当前代码上重测同一件事**。
   （「某某为 N」类结论有保质期 —— 这次的保质期是 52 分钟。）

---

## 一 · 实测（⛔ 不是推断）

**口径**：租户 `MOCK_REST`／10 条归因·建议型问句 × 2 轮／共 20 个样本、16 次
`answer_contract.validate`／prod Python 8083／探针
`smartbi/scripts/_contract_action_probe.py`（钩 `validate`，**只记录不改判定**）。
每轮开跑前清 4 个缓存并把清了哪几个打进日志
（`clear_semantic_plan_cache` / `clear_route_cache` / `clear_tenant_gate_cache` /
`clear_promoted_routes_cache`），⇒ 两轮都是冷编译。
每条读数带来源标记 `source_tier` / `planner_authority`（实测 20/20 全是 `llm`）。

### 1.1 拒答归因

| 拦截点 | 数量 |
|---|---|
| 作答 | 12/20 |
| **契约 · `analysis_action`** | **4/20** |
| 契约 · 其它任何一项 | **0/20** |
| 规划器自己反问 | 2/20 |
| 未走到契约（维度闸等） | 2/20 |

▎**契约缺项直方图里只有一项：`analysis_action`（4 次）。**
▎在这类问句上，它不只是「最直接的拒答方」，它是**唯一的**契约拒答方。

### 1.2 按 `analysis_action` 拆开 —— 这一格才是结论

| `analysis_action` | validate 次数 | 被 `analysis_action` 拦下 |
|---|---|---|
| `optimize` | 7 | **0** |
| `diagnose` | 6 | **4** |
| `lookup` | 3 | 0 |

**6 条 `diagnose` 里，命中现有白名单的是 0 条。** 过了的那 2 条走的是
`_explicit_analysis_gap`，也就是**「我做不到」那条逃生口**。

▎**在 LLM 合成这条路上，`diagnose` 只有承认失败才过得去；真的做出了归因反而过不去。**

### 1.3 被拦下的是什么东西（这一条决定了本卡的裁定）

问句「为什么这周的订单量少了」，被拦下的正文 **1796 字**，逐字节选：

> 核心结论：这周订单量（3,956单）比上周少了约0.3%，但10家门店的单量几乎一模一样
> （395-396单），所以不是某家店突然没人了。**真正拖后腿的是模拟·普陀真如社区店，
> 它营收比平均少4,733元，主要原因是客人花得少了（每单平均消费347元 vs 平均358元），
> 而不是客人少了（只差1单）。**
>
> \#\#\# 差距出在「客单价」——接下来往哪儿查
> - 系统里有数、可以接着查……- 现在查不了……
> - 上面这些都查过、还是解释不了，才轮到怀疑「这家店自己的运营」—— 现在还没到那一步。

有主因、有拖后腿门店、有支撑数字、有 10 店明细表、有排查顺序。
**这就是 goal 定义里「答得上」的样子。** 而老板拿到的是：

> 本轮要求的原因分析或优化动作我这次跑出来了，但里面有几句话没达到我们
> 「不把推测说成结论」的要求，所以我没发给你 ——……

⚠️ 那句话**还是假的**：驳回它的是契约的 `analysis_action`，不是叙事接地闸
（这一轮接地闸对这条问句一条 violation 都没记）。见「六 · 没做的部分」第 1 条。

---

## 二 · 为什么会这样（两侧都查了出处，⛔ 不从注释推行为）

### `optimize` 这一支：由代码自己满足

白名单是 `("优化目标", "优化建议", "优化动作", "验证指标")`。而
`restaurant_intent_service.py:1743-1747`：

```python
if not any(marker in answer
           for marker in ("优化建议", "优化动作", "行动建议", "接下来可以怎么做")):
    answer = f"优化建议\n{answer}".strip()
```

**无条件前缀。** 实测语料里 **7/7** 条 `optimize` 文本都以 `优化建议\n` 开头。
⇒ 这一支在 LLM 合成路上**接近恒真**：判据要的那个词是**判据的下游自己写上去的**。

### `diagnose` 这一支：在这条路上不可达

白名单是 `原因拆解` / `计算构成` / `驱动因素` / `前提成立` / `不能证明因果` …
逐个查出处，**全部**是 `restaurant_ops_router.py:2831-2907` 那个
**单菜确定性 resolver** 的抬头字面量。LLM 合成路径**一个都不产出**（实测 0/6）。

### 一句话

▎判据认得**某一个 resolver** 的归因抬头，认不得**另一个产出点**的。
▎**这不是「标准定得太高」，是清单没列全。** 两支各自坏在相反的方向上。

---

## 三 · 三个选项

### 选项 A：把 `diagnose` 白名单扩成「LLM 实际写的那些词」

例：加 `主要原因` / `核心结论` / `拖后腿`。

**代价 —— 已实测证伪，⛔ 不是担心。** 我先写了一版这种判据
（「同一句里有因果词 + 有数字」），在同一批真实语料上跑：

```
diagnose 拦 0/6 ✓        optimize 拦 4/7 ✗（把今天能答的拦了）
```

而且 **4 条命中的是一句代码自己写的页脚**：

> 还没有数据的方面当前保持为空，不按 0 处理，也不参与原因判断。

它含「原因」也含数字「0」，在 16 条语料里出现 **12 次，含 3 条 `lookup`**。

▎**任何词表判据都会被产品自己的样板句满足。** 这不是收紧，是再造一个 `优化建议`。

### 选项 B：把判据抬到结构 —— 契约改读 `meta` 里的归因产物

与 `margin_integrity` / `execution_consistency` 同形（那两项读 `meta` 不读散文）。
`FactBook.attribution` 里就有 `laggard.store_name` + `primary_cause`。

**代价**：要把 `attribution` 投影进 `meta`，跨 `factbook.py` /
`synthesis_engine.py` / `restaurant_intent_service.py` 三个文件；且这三个文件
本轮有别的 session 在动。⇒ **方向对，本刀太宽。** 登记为下一刀（见「六」第 2 条）。

### 选项 C（裁定采用）：把**另一个产出点的抬头**也列进清单

`synthesis_engine._append_investigation_directions`（`synthesis_engine.py:3387`）
逐字拼这一行：

```python
lines = [answer.rstrip(), "", f"### 差距出在「{cause}」——接下来往哪儿查"]
```

**为什么认它不是放宽**（两条都来自那个函数自己，⛔ 不是我的推断）：

1. 它的 docstring 原话是「**确定性追加，⛔ 不经过 LLM**」
   ⇒ 这一行不可能是模型编出来的措辞。
2. 它有**四道门**，任何一道不过就一个字都不说：这一轮没跑归因 ／
   归因没给出拖后腿门店 ／ `primary_cause` 不在登记表里 ／ 能查与查不了都是空。
   ⇒ **它出现 ⇒ 产品真的算出了一个主因。**

📏 判别力（同一批 16 条真实语料）：

| | 命中 |
|---|---|
| `diagnose` | **6/6** |
| `lookup` | **0/3** |
| `optimize` | 1/7 |

⇒ 它不是一个到处都在的页脚（对照组那句页脚是 12/16），它跟着「真的跑了归因」走。

**这与 #2748「把『试点』认全」是同一件事**：⛔ 不是降低标准，是**认全同一个意思
的另一种写法** —— 只不过这次的「另一种写法」是**另一个产出点的抬头**。

---

## 四 · 裁定

采用 **选项 C**。实现在 `answer_contract.py`：

```python
_ANALYSIS_ATTRIBUTION_SECTION_MARKS = ("差距出在", "接下来往哪儿查")

def _attribution_section_present(answer_text: str) -> bool:
    for line in (answer_text or "").splitlines():
        if all(mark in line for mark in _ANALYSIS_ATTRIBUTION_SECTION_MARKS):
            return True
    return False
```

接进 `_analysis_action_present`，位置与 `_explicit_analysis_gap` 并列（**OR 支路**，
在两支白名单之前）。⛔ 两个标记必须**同一行** —— 产出侧就是一行拼出来的，跨行凑齐是巧合。

⚠️ 这仍然是**代理判据**（认的是产出点的字面量，不是语义）。**登记，不假装它不是。**
   要真正收敛，得走选项 B。

### 反目标自查

> ⛔ 不许为了让答上率好看而放宽契约。契约存在的理由是「不谎报」。

- 2026-08-10 事故形状（用一个数字回答「为什么」）**仍然被拒** —— 钉在
  `test_the_2026_08_10_incident_shape_is_still_rejected_after_this_change`。
- 那句 12/16 的页脚**不算数** —— 钉在
  `test_the_universal_footer_is_not_an_attribution_account`。
- 本次**没有**碰 `optimize` 那一支的恒真问题（那是收紧，风险另计）—— 登记在「六」。

---

## 五 · 变异对照（7 条，全部三层判据）

工具 `scratchpad/mutate.py`：锚点必须恰好 1 次 → 落盘 → `compile()` → 跑测试 → 还原。
⛔ 红在 `NameError`/`SyntaxError` 上的不算数（7 条一条都没有）。

| # | 变异 | 它守的行为 | 变红 | 干净吗 |
|---|---|---|---|---|
| M1 | `_attribution_section_present` → 恒 `True` | 这道闸不许放行任何东西 | **7 条**（两个文件的**全部**阴性对照） | ✓ |
| M2 | `all(...)` → `any(...)` | 要的是那一行的结构，不是一个词 | 2 条 | ✓ 只红结构类 |
| M3 | 去掉「同一行」要求 | 跨行凑齐是巧合 | **1 条**（`..._on_different_lines_do_not_count`） | ✓ 单靶 |
| M4 | 判据在、**不接进** `_analysis_action_present` | 形态 B「接上没有」 | **1 条**（`test_a_real_prod_diagnosis_passes_the_contract`） | ✓ 且 `test_the_attribution_section_is_detected` **没红** —— 正是「机制在／接上没」的分辨 |
| M5 | **产出侧**改措辞（`差距出在`→`差异出在`） | 形态 D′ 两份不许漂 | **1 条**（`test_the_producer_still_emits_what_the_contract_looks_for`） | ✓ 单靶 |
| M6 | `_explicit_analysis_gap` → 恒 `True` | 🔴 **加了 OR 支路之后，旧的阴性对照还红不红** | **5 条** | ✓ 没被短路 |
| M7 | `diagnose` 白名单打坏 | 旧路径坏了要看得出来 | 2 条（两个文件的 `..._existing_whitelist(s)_still_work`） | ✓ |

🔴 **M6 是本轮最该做的那一条。** 本次改动往判据里加的是 **OR** 支路，而
OR 支路最容易让原有阴性对照**因为新支路先放行而失去意义**。
M6 证明它们没有：把旧逃生口弄成恒真，5 条照样红。

⚠️ M6 的诚实说明：它是**前插式**变异（`return True` 插在原句之前），所以
「落盘后锚点残留」打的是 1 而不是 0。**这一条的生效证明不是锚点消失，是那 5 条变红**
（外加 `compile()` 通过）。⛔ 不拿「锚点残留 0」这个形式判据糊弄过去。

---

## 六 · 没做的部分（⛔ 登记是留痕，不是豁免）

1. **拒答文案说了假话 —— 没改。**
   `restaurant_intent_service.py:2545-2568` 的 `produced_but_rejected` 分支
   一律说「里面有几句话没达到『不把推测说成结论』的要求」，
   而实测**驳回它的是契约的 `analysis_action`，不是接地闸**（这一轮那条问句
   接地闸一条 violation 都没记）。
   ⇒ 这是「一条误发的提示烧掉的是可信」的原形。
   **为什么这次不改**：那个文件本轮有别的 session 在动，且改它要先有
   「谁驳回的」这个信号，而信号目前不存在（要新增）。
   **什么条件下做**：本卡合入后，单独一刀，把「驳回方」作为一个字段带到那一格。

2. **`optimize` 那一支恒真 —— 没修。**
   实测 7/7 靠代码自己前缀的 `优化建议` 过关。修它是**收紧**，会产生新的拒答，
   而我这一刀不能既放行一类又拦住另一类还说得清哪个变化是哪一刀的。
   **什么条件下做**：本卡合入并部署、拿到新的答上率基线之后，单独一刀，
   并且**先在真实语料上量它会拦掉哪几条**再决定。

3. **选项 B（读 `meta` 的结构化归因）—— 没做。** 理由见「三」。
   它才是 `_explicit_analysis_gap` 和 `_permission_explained` 两处 docstring
   都写着的收敛方向（「把面向用户的分析结论收到一处产出，再让契约扫那一处」）。

4. **规划器反问（2/20）和维度闸 —— 没碰**，另有 agent 在做。

---

## 七 · prod 端到端复测（⛔ 没有部署）

**怎么在不部署的前提下量到修后行为**：探针支持 `PROBE_CONTRACT_OVERRIDE`，
在任何生产模块 import `answer_contract` **之前**把指定文件装进
`sys.modules["smartbi.gold.restaurant.answer_contract"]`。
于是 `restaurant_intent_service` 里那句 `_contract.validate(...)` 用的就是它
（那边写的是 `from ... import answer_contract as _contract` ⇒ 调用时才查模块属性，
够得着；若写成 `from ... import validate` 就够不着 —— 那正是本仓
`test_mutation_targets_are_reachable` 守的东西）。

⚠️ 部署树**一个字节都没动**：跑完核对
`/www/.../answer_contract.py` 仍是 `bb8be485…`（修后那份在 `/tmp` 里，`fa9570a1…`）。

### 7.1 两次 prod 跑（同一棵树 `f87cb1951e` / PID 3707050 / 前后脚）

| | 修前 | 修后 |
|---|---|---|
| 作答 | 11/20 | 17/20 |
| **契约 · `analysis_action`** | **3/20** | **0/20** |
| 契约 · 其它任何一项 | 0/20 | 0/20 |
| 规划器自己反问 | 4/20 | 1/20 |
| 未走到契约 | 2/20 | 2/20 |

🔴 **⛔ 不许把「11→17」整个记在这次改动头上。**
「规划器反问 4→1」与本改动**零关系**（契约在那条路上根本没被调用），
那是两次跑之间 LLM 自己写得不一样。**读数变了先分清：我改的 / 数据动了。**

### 7.2 隔离出「我改的」那一份 —— 冻结语料重放

契约是 `(spec, text)` 的**纯函数** ⇒ 把修后判据重放到**修前那批逐字文本**上，
差异就只能是我的改动（`scratchpad/replay.py`）：

```
corpus_B（修前语料，14 次 validate）
  [ 5] action=diagnose  修前拦=True  修后拦=False  归因小节=True
  [ 8] action=diagnose  修前拦=True  修后拦=False  归因小节=True
  [12] action=diagnose  修前拦=True  修后拦=False  归因小节=True
  修前被 analysis_action 拦: 3 → 修后: 0
  翻转: 拦→放 3 ; 放→拦 0
```

▎**本改动的净效果 = 3/14 次 validate 从「拦」变「放」，`0` 条从「放」变「拦」。**
▎三条全部是 `diagnose`，且三条**都真的带着那一段归因小节**。

⚠️ 阳性对照：修后那一轮的 17 次 validate 里，`analysis_action` 缺项直方图**为空**，
   与重放结论一致 ⇒ 不是「探针没量到」。

### 7.3 回归

基线 = **本地 `main`**（`eb4b0b9dc5`，也就是我这条分支的 merge-base；⛔ 不用
`origin/main` —— prod 早就不是它了）。`git worktree add --detach` 出一棵干净树，
**同一条命令**串行各跑一遍（⛔ 不并发，会互相抢 CPU 把读数弄坏）：

```bash
pytest smartbi tests -q --tb=line --continue-on-collection-errors -p no:cacheprovider
```

| | 基线（main） | 本分支 |
|---|---|---|
| failed | 79 | 79 |
| errors | 2 | 2 |
| passed | 9135 | **9146**（+11 = 本卡新增的 11 条） |

`comm -13` 逐条比失败名（`grep -E "^FAILED \|^ERROR [a-z]"`，两侧各 81 条）：

```
新增失败: 0 条
修好的  : 0 条
```

⇒ **新增失败 0。**

⛔ 本卡不部署，deploy 由 owner 统一做。

---

## 八 · 🔴 这次修复**不会**让全景队列里那 6 条变绿（实测，⛔ 不是推测）

全景重测后队列里老板还拿不到数的 6 条：
`这周比上周怎么样` / `哪家店缺货最严重` / `我要不要关掉最差的那家店` /
`哪个供应商报价最贵` / `翻台率怎么样` / `食材成本占营收多少`。

拿**同一个探针**跑这 6 条（`PROBE_QS` 覆盖问句集，MOCK_REST × 2 轮 = 12 样本）：

```
② analysis_action∈(diagnose,optimize) 的样本 = 0/12
   逐条: compare, lookup, lookup, lookup, lookup, lookup ×2 轮
rc=2 没量到 —— 阳性对照未通过, 本次读数作废
```

⚠️ **这里的 `rc=2` 不是仪器坏了，它就是答案。** 那条阳性对照是给「归因型问句」
校准的；把它跑在一批 `lookup` 问句上，它**正确地拒绝出读数** ——
而「拒绝出读数」这件事本身回答了问题：**这 6 条根本不走 `analysis_action`。**

逐条核对 5 次 `validate` 调用的 `required`（⛔ 不看汇总）：

| intent | `analysis_action` | `required` | `missing` |
|---|---|---|---|
| `SUPPLIER_PRICE` ×2 | `lookup` | `[execution_consistency]` | `[]` |
| `RECIPE_COST` ×2 | `lookup` | `[execution_consistency, request_coverage]` | `[]` |
| `STORE_MARGIN` | `lookup` | `[execution_consistency, store_name]` | `[]` |

▎**`required` 里出现 `analysis_action` 的次数 = 0。契约对这 5 次全部放行。**

它们卡在别处，逐条：

| 问句 | 卡在哪（实测读数） |
|---|---|
| 这周比上周怎么样 | 规划器反问，**契约调用 0** |
| 哪家店缺货最严重 | `contract_pass=False` 而**契约调用 0** ⇒ 是**执行前维度闸**，不是契约 |
| 我要不要关掉最差的那家店 | 路由不稳（r1 反问 / r2 `STORE_MARGIN` 作答） |
| 哪个供应商报价最贵 | `kind=answer` / `contract_pass=True` —— 契约放行了，问题是正文「比不了价」（口径二） |
| 翻台率怎么样 | `intent=∅` |
| 食材成本占营收多少 | 同「供应商报价」，契约放行，正文算不出 |

⇒ **⛔ 不许把这次修复算进全景答上率的改进。** 它修的是**另一批问句**
（归因·建议型）上的一类假拒答，收益在那批上是 3/14 次 validate。
📏 想验收这一刀，要跑的是归因型问句集，⛔ 不是全景那 24 条。
