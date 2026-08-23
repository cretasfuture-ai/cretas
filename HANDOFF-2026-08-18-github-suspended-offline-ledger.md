# 离线台账 —— GitHub 账号停用期间攒的分支（2026-08-18 晚）

**状态** GitHub 账号被停用，`git push` / `gh` 全线 **403**
（`remote: Your account is suspended`）。实测：`git ls-remote` 与 `gh api user` 都是 403。
**同形前例** 2026-08-07 也发生过一次，协议见 `.claude/rules/worktree-and-main-only-deploy.md`
的「🔌 GitHub 不可用时」一节。

---

## ⛔ 本次没有离线部署

规则允许离线合并到**本地 main** + `SKIP_GIT_CHECK=1` 从 detached worktree 部署，
**但那条逃生门只在有紧急理由时用**。本次没有：

- **没有 prod 事故在计时**
- 攒下的改动**碰运行路径**（`synthesis_engine.py` / `factbook.py` / `queries.py`），
  按「合入通道双轨」这类**强制 PR**
- 那条线自己说：「CI 一个 job 都没跑过，**这条我不敢报绿**」

⇒ **等 GitHub 恢复，逐条 push + 开 PR + 走 CI。**

---

## 一 · 停用**之前**已经合并并部署的（已在 prod 上）

| PR | 内容 | 部署 |
|---|---|---|
| #2868 | 词表棘轮扩到正则 + 单串比较 + 载体名；22→88 冻结 | ⛔ 不需要（scripts/tests） |
| #2865 | 探针 `b_口径` 改读产品的 `phrasing.REQUIRED_TOKENS` | ⛔ 不需要 |
| **#2872** | **归一 ≠ 核对**：消解不到的提及不再当门店名去拒答 | ✅ **已部署**，三条判据全过 |
| #2873 | #15 降级 🔴（通道在、无人调）；#2 十轮未复现 | ⛔ 不需要 |

📏 #2872 的部署判据（留痕）：

```
① 标记   _mentions_backed_by_catalogue = 3 处
② 进程   PID 3632516 → 3644370，启动 20:54:08 晚于 mtime 20:53:18
③ 真跑   「有几家店是亏钱的」 kind=clarification → kind=answer（3/3 轮）
```

## 二 · 停用**之后**开着但没合的 PR

| PR | 内容 | 状态 |
|---|---|---|
| #2875 | 门店核对回归探针 + 两条既有缺陷登记 | 已 push、已开 PR，**CI 结果未知** |

## 三 · 🔴 只在**本地**的分支（恢复后逐条 push）

| worktree | 分支 | commit 数 | 内容 | 碰运行路径 |
|---|---|---|---|---|
| `../cretas-attr-steps` | `codex/claude-attribution-steps` | 5（tip `24f115571d`） | 归因链 ③⑤ 步 | ✅ `synthesis_engine.py` / `factbook.py` / `queries.py` |
| `../cretas-dish-catalogue` | `codex/claude-dish-catalogue` | 2+（在飞） | #1 菜单核对 + #13 菜品归一 | ✅ `restaurant_intent*.py` |
| `../cretas-followup` | `codex/claude-followup-capability` | 2+（在飞） | ③追问真值 + 能力表裁定 | 待定 |
| `../cretas-false-alarm` | `codex/claude-false-alarm-census` | 0+（在飞） | 误发提示普查（只读） | ⛔ 否 |

⚠️ **本地 commit 不会丢** —— 同一个仓的所有 worktree 共享同一个 `.git` 对象库，
`origin/main` 只是个本地缓存的 ref，GitHub 断了它不会消失，只是不再前进。

### 恢复后的动作（逐条核销）

```bash
# ✅ 各分支分别 push → 开 PR → 走 CI → 正常合进 origin/main
git push -u origin codex/claude-attribution-steps && gh pr create ...
git push -u origin codex/claude-dish-catalogue    && gh pr create ...
git push -u origin codex/claude-followup-capability && gh pr create ...
git push -u origin codex/claude-false-alarm-census  && gh pr create ...
# 然后确认 #2875 的 CI 结果
```

⛔ **不要 `git push origin main`** —— 那会把这些 commit 绕过 CI 一次性推上去，
而它们含 backend 代码（按双轨必须走 PR）。

---

## 四 · 🔴 恢复后**第一件要做**的（不是 push，是这个）

### `period_comparison` 的 `mom_pct` 正在报一个方向相反的数

📏 归因线实测（MOCK_REST，prod）：

```
产品报    revenue.mom_pct = +42.0
真相      current 窗 30/30 天，mom 窗只有 21/30 天（租户数据 2026-06-29 才开始）
按营业天数折算  = -0.6%
```

⇒ 老板问一句「这个月比上个月怎么样」，看到 **+42%**，而真相是**基本持平略降**。
▎方向相反 ⇒ 它不是「数字准不准」那一档，是**会让他做出错误决定**那一档。

**为什么可以等**：#15 实测证明 **3 周零真实用户调用**，全表唯一租户是 `MOCK_REST`
且像自动化题库在跑 ⇒ **现在没有真实老板会看到那个 +42%**。
▎这就是那个「看起来只是账本纠偏」的读数的实际价值：
▎它让我能给一个正在线上的错数**降级**，而不是慌着在无法验 CI 时改核心路径。

**修它的材料已经齐了**：归因线在 `queries.py` 里加的 `days_with_data`
（`COUNT(DISTINCT a.date)`）正是它缺的分母。
⇒ 归因线合并后**立刻做**，落点 `factbook.py` 的 `_seg`（约 623/991 行）。
⛔ 现在不做的理由是它会造成分支依赖（`factbook.py` 归因线也改了 +249 行），
   而 GitHub 挂着无法验 CI。

---

## 五 · 还欠的两件（与 GitHub 无关，但要记）

1. **基线重测** —— 24 问句 × 2 轮，硬指标「答上 41 次」。
   ⚠️ **必须放在所有分支合并 + 部署之后**，否则测完就作废。
2. **④ contract-repair 覆盖洞** —— 已登记不做，三条解冻条件写在
   `docs/decisions/2026-08-18-有几家店在亏钱-两个根因拆开.md`。
