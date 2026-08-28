# Mac mini 测试环境 + F006 全路由视觉验收

**日期**: 2026-08-28
**分支**: `codex/claude-testenv-macmini`
**环境**: `http://100.85.234.108:10010`（Tailscale）· 全部账号密码 `123456`
**prod 改动**: **零**（全程只读；所有写操作都发生在 Mac mini 上）

---

## 1. 交付了什么

### 1.1 测试环境（可用）

| 组件 | 状态 |
|---|---|
| Java 后端 10010 | main `2298ba90`，profile **`pg-prod`**，`-Duser.timezone=Asia/Shanghai` |
| Python SmartBI 8083 | `postgres: connected`，10 模块，0 启动错误 |
| `cretas_db` | 492 表 / **65,162 行** / 与 prod 逐表行数**差异 0** |
| `smartbi_db` | 149 表 / **932,122 行** / 与 prod 逐表行数**差异 0** |
| 账号 | 79 个全部密码 `123456`，**53 个可用账号覆盖 20 个角色** |
| 租户 | `F006` 六膳门食品科技 · `LIUSHANMEN` 六膳门 · `MOCK_REST` |

⛔ **profile 只能用 `pg-prod`。** `pg` 是 `ddl-auto=update`，实测会
①造 6 张 prod 没有的表 ②改 63 张表结构 ③把软删感知的**部分**唯一索引
（`WHERE deleted_at IS NULL`）换成无条件的，于是**合法的 prod 数据反而灌不进去**。

⚠️ **已知缺口（登记，不是当成产品缺陷）**：
- 无 `CRETAS_AI_DASHSCOPE_API_KEY` ⇒ LLM 端点不可用
- 无 `CAPABILITY_ROLLOUT_FACTORIES` ⇒ capability 层对 F006 未开（503，见 §3）

### 1.2 修掉的两个缺陷

| # | 位置 | 症状 | commit |
|---|---|---|---|
| 1 | `web-admin/src/api/aiValueSummary.ts:43` | `/api/mobile` 前缀拼两遍 → 403，AI 价值面板**从上线起没加载过** | ⚠️ **不是我修的** —— 同日另一条线已在 main 上修掉 (#255 `7695a46781`)，本分支已丢弃重复 commit。他们的闸 `noDoubleApiPrefix.source.spec.ts` 比我写的那道覆盖更全（扫 `.ts`+`.vue`、先剥注释、带正反对照），保留他们的 |
| 2 | `backend/python/smartbi/api/sales_preset.py` ×3 处 | `# noqa: E501` 写进 SQL 字符串 → `PostgresSyntaxError`，两个销售报表端点**每次调用都 500** | `3e60d5883d` |

#2 配了变异验证过的闸，并做了**行为验证**（不是只看测试绿）：
端点实测 500→200，`product-rank` 返回「黄油鸡成品 616 盒 / ¥30,506」。

⚠️ #1 我独立查到并修了一遍，**推之前才发现 main 上已经有了**（同日 #255）。
教训在 memory `feedback_zero_open_prs_expires_in_minutes_so_recheck_before_writing_code`
里写过 —— 开工前查过一次不够，动手写代码前要再查一次远端。这次没查。

---

## 2. 视觉验收读数（f006_admin，287 条可访问路由）

```
OK                     172
ERRORS                  10      ← 详见 §3
FORBIDDEN /403          63      ← 权限/租户类型门控
REDIRECT                36
NAV_ERROR                4      ← 慢页面超时，跨轮次不稳定
NOT_FOUND /404           1      （/404 页面本身）
BOUNCED_LOGIN            1      （/login 页面本身）
UNMEASURED               0
参数化路由未访问        22      ← 显式登记，不是静默丢弃
仪器自检: 去重后 224/287 张不同截图；31 条路由零 API 调用
```

---

## 3. 剩余 10 条错误的归因

| 类型 | 条数 | 归因 |
|---|---|---|
| `503 /smartbi-api/api/smartbi/capability/F006` | 5 | **环境**：`"Capability layer not enabled for factory=F006 (gradual rollout)"`，灰度开关未配 |
| `404` Java `smart-bi/analysis/finance` · `analysis/sales` · `mapping-review/pending` | 5 | 🔴 **待你裁定**：Java 侧**没有**这些端点（只有 `analysis/production` / `analysis/quality`），而 `DashboardFinance.vue:88` 和 `RestaurantFinanceContent.vue:187` 在调。是补 Java 端点还是改前端，需要产品决定，不是一行修复 |

---

## 4. 🔴 这一轮最重要的教训：我先交了一份假绿的验收

第一版 sweep 报 **「287 屏 / 280 干净 / 0 错误」**，并且我**已经做过阳性对照**
（强制一个 404 确认监听器记得下来）。读数看起来无懈可击。

真相：**289 张截图只有 96 个不同图像，最大一组 22 张逐字节相同** ——
截到的是「白垩纪 AI Agent / 正在加载应用…」启动闪屏。

成因：`page.goto()` 每条路由都**整页刷新**、重新引导整个 SPA（约 8 秒），
而我只等 2.2 秒。于是每一项判据都"正确地"指向干净：

| 判据 | 页面没渲染时 | 被读成 |
|---|---|---|
| `location.pathname` | 已是目标路由（URL 先变） | 没被跳走 ✅ |
| console errors | 0（什么都没执行） | 没报错 ✅ |
| API 4xx/5xx | 0（还没发请求） | 接口正常 ✅ |

▎**「没渲染」和「渲染了且没问题」在我的每一项判据上长得一模一样。**

**阳性对照为什么没救我**：它证明的是**监听器**活着，不是**页面**渲染了。
⇒ 阳性对照要对准你担心的那个失效，不是对准仪器的某个零件。

**修法**：SPA 内部导航（`router.push`）+ 等闪屏消失 + **第三态 `UNMEASURED`**
（还在闪屏不许算干净）+ **把「去重后还剩几张」当成常设判据** ——
那个数是唯一抓到 v1 的东西，而它当初只是顺手加的。

▎**截图数不是覆盖数。报「N 屏通过」之前先报「N 屏里有几张是不一样的」。**

### 4.1 同轮其它仪器故障（每一个都会把失败读成成功）

1. `wc -l` 数 CSV 物理行 —— 字段内嵌换行让"预期行数"虚高，18 张表被误判成丢数据
2. `grep "^ERROR:"` 锚了行首 —— psql 报错带 `psql:文件:行:` 前缀，实际 3 条我数出 0
3. 从 494 字节的空 nohup 日志里数出「0 ERROR」—— `pg-prod` 的 logback 写文件，stdout 只有 banner
4. `\copy TO` 把制表符转义成字面 `\t` —— 对账循环全部 `ERR`
5. 用 `/api/mobile/health` 验证 vite 代理 —— **本机另有一个后端，两者对该端点回答一模一样**，验不出区别；改用登录（本机 401 / mini 200）才发现
6. 分类器把 403 页面（正文 34 字）当成"还在闪屏" —— **顺序错**：先判落地页面，再猜闪屏
7. Python 缺 `JWT_SECRET` ⇒ 38 次 401，**长得完全像「接口坏了」**
8. 过滤"非密钥值"时写了 `SMARTBI_[A-Z_]*=`，**把 `SMARTBI_DB_PASSWORD` 一起打了出来**

---

## 5. 复现方式

```bash
ssh canada-mini                         # 直连，不用再经 codex-mac 中转
bash /tmp/start_and_login.sh            # 起 Java + 自检登录
bash /tmp/start_python.sh               # 起 Python 8083

# 前端（本机）
cd web-admin && npx vite --port 5173    # .env.local 里 VITE_BACKEND_URL 指向 mini
#   ⚠️ vite 的 loadEnv 只读 .env 文件，不读 process.env —— 命令行前缀无效

# 视觉验收
OUT_DIR=<dir> E2E_USER=f006_admin node .e2e-scratch/visual_sweep2.cjs
```

---

## 6. 未做 / 待办

- 只跑了 `f006_admin` 一个角色的全量验收。`liushanmen_admin`（label QC 1542 个任务）、
  `mock_rest`（餐饮租户，能打开 F006 打不开的 restaurant/*）、`f006_operator1`
  （低权限路径）尚未跑
- 22 条参数化路由（`/sales/orders/:id` 等）未访问 —— 需要真实 id 注入
- 部署通道、launchd 守护未做，设计见
  `docs/superpowers/specs/2026-08-28-macmini-test-environment-design.md`
- §3 那 5 条 Java 404 待产品裁定

---

关联 memory：
`project_macmini_test_env_is_live_with_f006` ·
`feedback_my_e2e_sweep_screenshotted_the_loading_splash_and_called_it_clean` ·
`project_fresh_db_cannot_be_replayed_from_flyway_migrations`
