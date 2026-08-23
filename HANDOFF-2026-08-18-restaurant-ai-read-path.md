# 交接 · 餐饮 AI 读路径（2026-08-18 晚）

**给下一棒**。每个数字都带口径；没做完的都写了**解冻条件**。
⚠️ 先读「〇 · 现在的环境」——**GitHub 停用中，工作流和平时不一样**。

---

## 〇 · 现在的环境（⛔ 不读这一节会踩坑）

### GitHub 账号被停用

`git push` / `gh` 全线 **403**（`remote: Your account is suspended`）。
实测：`git ls-remote` 与 `gh api user` 都是 403。协议见
`.claude/rules/worktree-and-main-only-deploy.md` 的「🔌 GitHub 不可用时」一节。

⇒ **汇合点是本地 `main`**。`origin/main` 是本地缓存的 ref（停在 `f635e6b65e` = PR #2873），
GitHub 断了它不会消失，只是不再前进。

### 🔴 两条 session 在同一个本地 main 上合，已经互相覆盖过一次

```
21:4x  我 merge 进 main（db324029cb）
21:48  另一条 session 对 main 做 `branch: Reset to 62f80087a8`  ← 把我的整块覆盖
       同时把 cretas-vision-main 踢成 detached
```

**我的没丢，只是因为四条 feature 分支的 ref 还在。**
▎**如果当时只在 main 上合、没留分支，就真丢了。**

⇒ 现在 `main = e7dee3b109`，**两条 session 的成果都在**（双向 `merge-base --is-ancestor` 验过），
领先 `origin/main` **27 个 commit**，且 **`main` 分支没有任何 worktree 占用**。

⛔ **离线期往 main 上放东西只用 `git merge --no-ff <分支>`，⛔ 不用 reset / `branch -f`。**

### 恢复后要做的

```bash
# ✅ 各 feature 分支分别 push → 开 PR → 走 CI → 正常合进 origin/main
git push -u origin codex/claude-attribution-steps && gh pr create ...
# 四条分支名见下面「二」
# 全部合完之后再
git checkout main && git reset --hard origin/main
```

⛔ **不要 `git push origin main`** —— 27 个 commit 绕过 CI，且含 backend 代码。
台账：`HANDOFF-2026-08-18-github-suspended-offline-ledger.md`

---

## 一 · 停用**之前**已合并 + 已部署到 prod 的

| PR | 内容 | 部署 |
|---|---|---|
| #2868 | 词表棘轮扩到**正则 + 单串比较 + 载体名**；22→88 冻结 | ⛔ 不需要 |
| #2865 | 探针 `b_口径` 改读产品的 `phrasing.REQUIRED_TOKENS` | ⛔ 不需要 |
| **#2872** | **归一 ≠ 核对**：消解不到的提及不再当门店名去拒答 | ✅ **已部署**，三条判据全过 |
| #2873 | #15 降级 🔴（通道在、无人调）；#2 十轮未复现 | ⛔ 不需要 |
| #2875 | 门店核对回归探针（已 push、开了 PR，**CI 结果未知**） | ⛔ 不需要 |

📏 #2872 的部署判据（留痕）：
```
① 标记  _mentions_backed_by_catalogue = 3 处
② 进程  PID 3632516 → 3644370，启动 20:54:08 晚于 mtime 20:53:18
③ 真跑  「有几家店是亏钱的」 clarification → answer（3/3 轮）
```

## 二 · 已合入本地 main、**⛔ 还没部署**的四条线

| 分支 | 内容 | 碰运行路径 |
|---|---|---|
| `codex/claude-attribution-steps` | 归因链 ③⑤ 步 | ✅ `synthesis_engine` / `factbook` / `queries` |
| `codex/claude-false-alarm-census` | 误发提示普查（5 🔴 / 3 🟡 / 6 ✅） | ⛔ 代码零改动 |
| `codex/claude-catalogue-regression` | 门店核对回归 + 北极星 1/5 实测 | ⛔ 纯探针 |
| `codex/claude-followup-capability` | 追问真值 7/12 + 能力表裁定「不造」 | ⛔ 只加测试 |

| `codex/claude-dish-catalogue` | #1 菜单核对 + #13 菜品归一 | ✅ `restaurant_intent*.py` / `restaurant_ops_router.py` |
| `codex/claude-daysdata-contract` | 契约桩补 `days_with_data`（修部署阻塞第 1 条） | ⛔ 只改测试 |

⚠️ 后两条 off 的是 `origin/main`，**不含上面 27 个 commit**，合之前先确认冲突。

### 🔴 菜单核对线（`codex/claude-dish-catalogue`）—— 它推翻了任务书的落点

**owner 交待的落点（「把 `_catalogue_says_not_a_dish` 接到 `restaurant_intent` 的三个 dish 调用点」）
不是 prod 症状的成因**，而且照字面接**会把一句诚实拒答换成一张不相干的排行表**。

真因是同一形态（B：机制在、没接上）的**更彻底版本**：
`_catalogue_says_not_a_dish` **早就接在抽取器里**（`restaurant_ops_router:1470`），
但 `dish_catalogue_scope` **只包住 `parse_restaurant_query`**，执行期在作用域外。
探针逐条打「每次调用时目录在不在」：

```
'香辣蟹煲卖得怎么样'  目录=10   ->  None      ← 在规划里, 正确否决
…(共 7 次)…
'香辣蟹煲卖得怎么样'  目录=None ->  '香辣蟹煲'  ← 在执行里, 放行
```

▎**只有最后那一次的结果到得了老板面前。**

判「摘不摘菜名」用 **`ranking_direction`**（LLM 自己编译出来的计划，⛔ 不是词表）：
**摘掉菜名后这个计划还成不成立**。

📏 prod 原文（改动 scp 上去跑完立刻恢复，md5 逐字复原 + `grep=0` + 探针已删）：

| 问句 | 前 | 后 |
|---|---|---|
| 那最差的呢 | 「没有找到名为「最差」的菜品」 | **answer** 销量排行 + 表 |
| 最好的那道菜毛利多少 | 同款拒答 | **answer** 毛利分析 + 拖累归因 |
| 香辣蟹煲卖得怎么样 | 「…请核对菜名」 | 「菜单里没查到「香辣蟹煲」…**是刚上架的新菜吗？**…在售的菜有：…」 |
| 米饭卖得怎么样 / 这个月营业额多少 | — | **逐字不变**（零误伤） |

**③ 实测：唯一匹配那一支本来就做完了**（`木耳→凉拌木耳` 等，抬头全是库里全名）——
owner 预判的「MOCK_REST 上量不出收益」证实，**而且原因更彻底：不是收益小，是这一支不缺**。

#### 🔴 它差点做出来的坑（值得记）

第一版把 `tiered_answer` 拆成「薄壳 + `_tiered_answer_execute`」，全量回归当场 **+11 条失败** ——
仓里有 **6 处 `inspect.getsource(svc.tiered_answer)` 的闸，其中 2 处是 `assert X not in src`**，
拆函数会让那两道**静默变成恒真式**。已改成在原本就覆盖整个函数体的 `try` 上加 `finally`，
并加了一条断言把这件事机械化。

⚠️ **它的回归口径与我的不同**（78 failed / 8063 passed vs 我的 20 failed / 9078 passed）——
它用的选择器和排除表与 python-gate 不一样。⛔ 两组数字**不可直接比**，
但它自己做了同口径的基线对照：**新增 0、消失 0**。

---

## 三 · 本轮最重的五条读数（都带口径）

### 1. 🔴 北极星那一问只有 **1/5 轮**答得上

📏 MOCK_REST / prod 活代码 / 5 轮 / 阳性对照通过：

```
「我要不要关掉最差的那家店」  kind==answer  1/5
  r1 r2 r3 r5   intent=BUSINESS_OPTIMIZATION  dims=('store',)  → 反问
  r4            intent=STORE_MARGIN           dims=()          → ✅ 门店毛利对比表 n=1307
```

**同一句话，LLM 每轮挑了不同的 intent。** `BUSINESS_OPTIMIZATION` 声明服务全部六个维度
⇒ 永远不被维度闸拦，只会反问。四轮反问问的都是「哪个指标判断最差」+「哪个时间范围」，
而**产品自己都有默认**（r4 就是用默认答出来的）。

⛔ **方向不动**：「有默认值就压掉澄清」是 2026-08-07 撤回过的方向（代价五条交互契约）。
▎但值得指出它换了个角度：问题不是「该不该澄清」，是**同一句话为什么会走到两条完全不同的路**
▎—— 那是 intent 判定的稳定性，⛔ 不是澄清策略。

⇒ 探针 `smartbi/scripts/_northstar_rate.py` 可复跑。
⚠️ 它的**叙述缓存绕过失败了**（「一个都没找到」），下次要修对。

### 2. 🔴 交付定义③「追问接得住」真值 **7/12**，省略式 **0/3 全灭**

📏 4 种追问形态各 3 条，3 轮，75/75 turn `source_tier == "llm"`（两侧都真编译）：

```
换时间  3/3 ✅    换指标  3/3 ✅    换维度  1/3    省略式  0/3 🔴
```

省略式 =「那最低的呢」「是哪几家」「最低的那道呢」—— **老板最自然的追问方式**。
⚠️ 上一轮读出 5/10 是因为**10 对全是换维度**，读不到省略式。
⚠️ 判据换成了产品自己的 `refusal_queue.is_refusal()` ——
   ⛔ 只看 `kind == "answer"` 会把 `DATA_GAP`/`OUT_OF_DOMAIN` 这种「诚实地说答不了」读成「接住了」。

### 3. 🔴 我写进设计卡的一句**事实错误**（已知，未更正）

我写过「仓里没有 resolver → metric 的能力表」。**是错的**，表已存在**两份**：
`_DEFAULT_METRICS_BY_CODE`（19 个 resolver 全覆盖）+ `MARGIN_CAPABLE_INTENTS`。
第一份**早于那张设计卡 14 小时**就被 contract-repair 自己当判据用了，
且 `restaurant_intent.py:2660` 明令「⛔ 不新造第二张」。

▎我犯的是形态 A：**我 grep 了四个猜的名字都没命中，就下了「没有这张表」的结论** ——
▎我量的是「这四个名字存不存在」，不是「有没有这张表」。

⇒ **要更正的文件**：`docs/decisions/2026-08-18-有几家店在亏钱-两个根因拆开.md` 第四节。

### 4. 🔴 PR #2859 修的和漏的**在同一个函数里**（我的缺口）

📏 MOCK_REST prod，`resolve_store_margin` 的答案**同屏自相矛盾**：
```
10 家店的毛利率差得很少（相差 0.3 个百分点）—— 按门店查多半找不到东西   ← 7864 行，已挂阈值 ✅
建议动作: 1. 把第一名门店的高毛利菜品组合…作为其他门店对标模板。        ← 7943 行，无条件 🔴
```
▎硬约束 8 的现场：这条路上说「按门店去查」的有**两处**，那次只数了一处。
⚠️ 同一天同一条硬约束我犯了两次（另一次：接线断言只数「有没有调用」，
   而 `store_mention` 有**两个来源**，删掉一处断言纹丝不动）。

### 5. 🔴 `period_comparison` 的 `mom_pct` 正在报一个**方向相反**的数

```
产品报  revenue.mom_pct = +42.0
真相    current 窗 30/30 天，mom 窗只有 21/30 天（租户数据 2026-06-29 才开始）
        按营业天数折算 = -0.6%
```
⇒ 老板问「这个月比上个月怎么样」看到 **+42%**，真相是**基本持平略降**。

**为什么可以等**：#15 实测 **3 周零真实用户调用**，全表唯一租户 `MOCK_REST` 且像自动化题库
⇒ **现在没有真实老板会看到它**。
**材料已齐**：归因线在 `queries.py` 加的 `days_with_data`（`COUNT(DISTINCT a.date)`）就是缺的分母。
⇒ 落点 `factbook.py` 的 `_seg`（约 623/991 行）。

---

## 三·五 · 🔴🔴 部署阻塞项（**先修这个，再谈部署**）

📏 全量基线对照（python-gate 自己的选择器 + 排除表，**串行**跑，⛔ 不并发抢 CPU）：

```
基线 origin/main   20 failed / 9078 passed
合并后 main        22 failed / 9139 passed
comm -13 逐条比 → 新增 2 条，两条都是我这条线引入的
```

### ✅ 第 1 条已修（分支 `codex/claude-daysdata-contract`）

`KeyError: 'days_with_data' @ queries.py:2416` —— 归因链③ 给 `store_comparison`
的 SQL 加了 `COUNT(DISTINCT a.date)`，而 `ContractProbePool` 的桩行没跟上。

⚠️ **那个文件里已经有一条注释记着同一形态**（2026-08-10，`KeyError 'shortage_cost'`）：
「探针缺列会**看起来像生产代码崩了**」。▎写了 8 天，拦不住。

⛔ **没有用 `.get(k, 0)` 兜过去** —— 这一列是按天折算的**分母**，
缺失时当成 0 会把「我不知道有几天」翻译成「0 天」，而那正是这次改动要修的病。

### 🔴 第 2 条**未修**，⛔ 修完才能部署

```
tests/agent_eval/test_runtime_shadow_production_wiring.py
  ::test_runtime_shadow_production_wiring_is_server_derived_and_zero_write

E  assert actual["tools"] == trusted_case["requiredTools"]
E  Right contains one more item: 'restaurant_dish_margin_mix_read.v1'
```

**已定性**（⛔ 不是猜的）：

```
我的树     1 failed        ← 单独跑也复现，⛔ 不是被第 1 条的 KeyError 污染的
基线树     2 passed
```

**成因线索**（缩到这一步，没往下追）：
- 我这条线**没碰** `smartbi/agent/runtime/`（`git diff --stat` 为空）
- 缺的那个 tool 是路由计划的**第 2 轮**（`routes.py:46`，`round_number=2`）
- 第 1 轮读 `store_comparison` —— **正是我加了 `days_with_data` 的那个**
- ⇒ 怀疑新列让第 1 轮的产出变形，第 2 轮因此不触发

**复现命令**：
```bash
cd <worktree>/backend/python
python -m pytest tests/agent_eval/test_runtime_shadow_production_wiring.py -q
```

⚠️ 这条能溜进来，是因为归因线只跑了**窄回归**（717 passed）——
▎又一次「窄 `-k` 让『没有失败』和『我没跑到它』变成同一个读数」。
⇒ **下一棒改完任何碰 `queries.py` / `factbook.py` 的东西，跑全量 + 基线对照，⛔ 别只跑窄的。**

---

## 四 · 下一棒建议的顺序

0. 🔴 **先修「三·五」那条部署阻塞项** —— ⛔ 带着已知回归不许部署
1. **部署**（本轮四条线还没上线）→ 三条判据（标记 / 进程 / **真跑一次读原文**）
   ⚠️ 部署前把 `codex/claude-daysdata-contract`（契约桩修复）和
      `codex/claude-dish-catalogue`（菜单核对，见下）一并合进本地 main
2. **④ contract-repair 覆盖洞** —— **现在可以修了，且不需要造表**：
   能力表线已证明两条解冻条件都满足，判据跑在亏损句实测 spec 上给出「不该 repair，保留
   STORE_MARGIN」（正确），四条阴性对照全 False。
   🔴 **但有个坑**：`_default_metrics_for_code(code, wants_margin=True)` 在
   `wants_margin=True` 时给**任何** code 追加 `gross_margin` ——
   照字面用它实现，**毛利问句上判据恒真、改动静默无效而单测全绿**。
   已钉成闸（`test_resolver_metric_capability_tables_agree.py`）。
3. **菜品槽 fallthrough**（#2872 只接了门店槽，菜品槽同形未修）
   —— 实测「量子纠缠火箭发射器」现在被当成**菜名**
4. **#2859 漏的那一半**（上面第 4 条）
5. **更正设计卡的事实错误**（上面第 3 条）
6. **基线重测** —— 24 问句 × 2 轮，硬指标「答上 41 次」。**⚠️ 必须放最后**，否则测完就作废。

⛔ **不要做**：#4 菜单/能力表走检索（owner 说排最后）；#6 冲突批量重试（owner 说别为
观察不到的场景做分支，多冲突实测 0/24）。

---

## 五 · 环境与纪律速查

```
租户      只用 MOCK_REST
prod      47.100.235.168:8083   部署树 /www/wwwroot/cretas/code/backend/python
解释器    venv-current/bin/python
PID       ss -lntp | grep :8083 | grep -oP 'pid=\K[0-9]+'      ⛔ 不用 pgrep -f
环境变量  eval "$(tr '\0' '\n' < /proc/$PID/environ | grep -E '^(POSTGRES_|FOOD_KB_POSTGRES_|DASHSCOPE_|LLM_|AISTORE_|DEEPSEEK_)' | sed 's/^/export /')"
部署      ./scripts/deploy/deploy-smartbi-python.sh --env prod
          ⛔ 纯 Python 改动不要用 release-cretas.sh（它只发 Java + web-admin）
          离线期加 SKIP_GIT_CHECK=1，从 detached worktree 部署
提交      ./scripts/safe-commit.sh "msg" 文件1 文件2      ⛔ 不用裸 git commit
          ⚠️ 新文件先 git add -N；变异对照前先 commit
```

### 🔴 脏样本没了（推翻一条既有纪律）

📏 实测：`DEMO_REST` / `RES_3101_009` / `QHJ_PROD` / `R_GML_DEMO` / `R_XMX_CHAIN`
在 prod 上**全部 0 行**（普查线用不开 RLS 的表独立佐证，餐饮侧只剩 `MOCK_REST`）。

⇒ 「MOCK_REST 只做冒烟、验收上脏样本」这条纪律**现在没有载体**。
⚠️ 我今天写进设计卡的「`R_GML_DEMO` 有 132 家桂满陇」**是过期信息**。
⚠️ 我**没有**独立复核这一条（查到一半被打断）——⇒ **下一棒引用前先当场重测**。

### 本轮踩过的（都有实例）

- **CI 的同名常量漂移闸**会拦通名（`QS` / `CASES` / `SLOTS`）—— 今天栽了三条线
- **窄 `-k` 让「没有失败」和「我没跑到它」变成同一个读数** —— 那道漂移闸本地窄跑就没跑到
- **变异不红先证明变异生效了** —— 今天两次「行为没变」都是**我的到达探针死了**
  （一次 `sys.path` 少一条 `No module named 'services'`，一次子进程读数与手工复现不一致）
- **`sed` / heredoc 改文件会吃掉转义和缩进** —— 用 Write/Edit 直接落盘
- **`pg_class` 查表要带 schema** —— 同名表可能在多个 schema，RLS 状态不同，结论相反
