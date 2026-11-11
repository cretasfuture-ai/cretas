# PROTO-UNIFIED-20261111 原型终版（融合 + 规格 + 实现）

Owner：organizer 会话 `local_f2ec35f8`（2026-11-11 起）。Base：`9bfd4ed652`（缺口清单 `docs/progress/2026-11-11-prototype-gaps.md` 已在 main）。

## 目标
把三套原型融合成一个统一原型终版：味美（ydwm_admin）为底座，六膳门（黑湖真实数据）与黑湖成本分析并入，顶部「工作区」切换隔离数据；Steve 的重点是使用逻辑清晰、信息内容清晰，该由 AI 处理的部分接入 AI 能力。

## 位置（仓库外，非 git）
- 原型：`C:\Users\Steve\Documents\Cretas-admin-双角色完整原型-20261028\samples-v5\accounts\{ydwm-v1,liushanmen-v1}`
- 规格：`samples-v5\unified-spec\`（`TEMPLATE.md`、`LEDGER.md`、`A-production-chain.md`、`B-cost-analysis.md`、`C-supply.md`、`D-home-analytics.md`）
- 黑湖成本（只读）：`C:\Users\Steve\cretas-blacklake-20261103`，草稿 PR #1390

## 阶段与执行者
| 阶段 | 执行者 | 模型 / effort | 写入范围 | 状态 |
|---|---|---|---|---|
| 融合骨架 | 后台子代理（general-purpose） | Opus；effort 不能按次设，未核实 | 原型源码/数据、`evidence/unified/`、融合交付记录 | in-progress |
| 规格 A 生产链 | 会话 `local_72a060bd` proto-spec-a-production | Opus 5.5 / medium | 仅 `unified-spec/A-production-chain.md` | in-progress |
| 规格 B 成本分析 | 会话 `local_041b7ac4` proto-spec-b-cost | Opus 5.5 / medium | 仅 `unified-spec/B-cost-analysis.md` | in-progress |
| 规格 C 采购销售库存 | 会话 `local_8c7ef4dd` proto-spec-c-supply | Opus 5.5 / medium | 仅 `unified-spec/C-supply.md` | in-progress |
| 规格 D 首页与分析 + 跨页 AI 协议 | 会话 `local_da26b083` proto-spec-d-home | Opus 5.5 / medium | 仅 `unified-spec/D-home-analytics.md` | in-progress |
| 实现 | 待派：按规格与融合交付的「模块 → 文件」对照表分配 scope 锁 | Opus / medium 起步 | 待定 | queued |
| 终版验收 | 待派：独立对抗审计，不由各线自审 | Opus | 只读 | queued |

## 共用约束
原版（19061）、账号源数据、Steve 有意关闭的模块、受保护文件不动；生产写入 0；不回写黑湖；不提交、不推送 blacklake 仓库；不发布远程 ydwm-prototype 域名，除非静态发布门禁全过。

## 记录
- 2026-11-11 规格线起初继承 Sonnet / medium，先改 Opus high，再按现行 organizer 规则（独立会话 Opus + medium 起步）改为 medium，均停掉旧一轮重起。融合代理第一次 Sonnet 起，停后换 Opus 接续。

## 收口（2026-11-11 23:1x，organizer `local_50a51890` 接手后）
状态：**done（验收通过）**，ACTIVE 条目与 scope 锁已移除。Owner 由 `local_f2ec35f8` 交接给 `local_50a51890`（交接 `~/.claude/context-handoff/handoff/20261111-proto-unified.md`）。

- 统一入口：`http://127.0.0.1:19063/open`（依赖 19060 味美、19062 六膳门）；原型、规格、回执、裁定都在仓库外 `samples-v5\unified-spec\`。
- 实现五线：A 生产链 `local_72a060bd`、B 成本 `local_041b7ac4`、C 采购销售库存 `local_8c7ef4dd`、D 首页/分析/外壳/AI 协议 `local_da26b083`、E 质量检验与协同任务 `local_24ba6fde`（新建线）；回执 `impl-A…E.md`。
- 裁定：`DECISIONS.md` 第一至第八节（R1–R9：19063 用 classic 组装不动 fast-ui；锁内允许 nuxt 构建；nuxt-bridge 归 D；味美页面级改造写新视图走 FRAME_ROUTES；R6 精简视图默认原页面、销售订单默认精简；R7 本轮收口范围；R8 fusion-state/test-* 允许、按会话隔离必修）。
- 终版验收：独立只读对抗审计三轮（`FINAL-AUDIT.md`）。第一轮退回 17 条 → 第二轮 13 修好/4 部分 + 新退回 10 条 → 第三轮 4 条必修全过、9/10 修好，剩 2 条（C 精简视图写死会话、D9 状态名）由 organizer 读源码与回执核对后通过。保护校验三轮读数一致（味美 1364/1370、3896 口径 3873/3896、六膳门 3877/3880，差异全在允许范围），无写入原版 19061、账号数据、六膳门 private、黑湖、生产。
- 仍未验证：A 报工撤回审批人名映射（原型引擎拒绝造撤回申请）；B 味美侧成本保存回读（测试会话无成本数据）；AiFloat 真实问答/拖动调宽/排队/用量窗口；N9 缺失值不计入合计；D7 有值时两位小数；hash 会话写法下真实保存（按只读边界未点）。
- 下一轮（R7/R9）：C 规格 1–23 其余味美精简视图、金额按价格权限隐藏、stock/document explain；D7 页签与图表解读合并、D10 步骤顺序、D13 并入经营总览、App.vue 改共享 AiFloat、可选新 AI 入口；A 小结撤回并入撤回审批；BL-borrow 第 9–26 条；审计「轻微」项（N9 同页切会话不刷新、D5 加载闪原版按钮、精简视图详情不交悬浮窗、若干面包屑原名、375 宽搜索重叠、采购精简视图 1440 横滚、库存精简按钮位置）。
- 事件：A 旧会话误触发 test-iab-chain 建批次（R5 登记）；构建锁被手写命令误删两次后改脚本强制；organizer 会话 worktree 被自动回收清空后一条 `git merge --ff-only` 落在主目录（detached HEAD 快进到 origin/main，与同步钩子等效，无文件损失）。
