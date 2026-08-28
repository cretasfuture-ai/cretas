# `/smart-bi/analysis/*` 404 —— 不是生产缺陷，是 dev/prod 代理不一致

**日期**: 2026-08-28
**结论**: ✅ **生产正常。** 404 只出现在本地/测试环境，成因是 vite dev 代理没复现生产 nginx 的分流。
**prod 改动**: **零**（过程中我改过 nginx 文件但**从未 reload**，随后完整回滚，运行态全程未受影响）

> ⚠️ 这份文档的第一版结论是「生产缺陷，nginx 规则丢了」—— **那是错的**。
> 保留这份记录是因为我在同一个问题上连错三次，成因都是「读错了权威来源」。
> 见 §3。

---

## 1. 真相

生产 nginx 有一个**被 3 个 vhost 共享 include** 的 snippet：

```
/www/server/nginx/conf/snippets/smart-bi-routing.conf     （2026-05-15 建，单一真相源）
  location ~ ^/api/mobile/(F00[1-46]|DEMO_*|FOOD_3101_*|…)/smart-bi/analysis/
              (finance|sales|department|region|inventory|procurement)(/.*)?$ {
      proxy_pass http://cretas_python;
  }
```

`F00[1-46]` **包含 F006**，规则一直在工作。

**实测判据（不需要 prod 凭证）—— 响应时间戳的小数位**：

| 请求 | 时间戳 | 上游 |
|---|---|---|
| `F006/smart-bi/analysis/finance` | `...53.236837` （6 位） | **Python**（`datetime.isoformat()`）|
| `ZZ_NOT_IN_LIST/…/analysis/finance` | `...53.644326347` （9 位） | Java（`LocalDateTime` 纳秒）|
| `F006/smart-bi/analysis/dynamic` | `...53.795497975` （9 位） | Java |

⛔ **不要用响应体的 JSON 形状判上游** —— `smartbi_compat` 顾名思义就是在**模仿 Java 的
信封格式**，两边的 401 长得一模一样。我因此误判了一轮。

## 2. 真正的问题与修复

`web-admin/vite.config.ts` 把 `/api/*` **全部**代理给 Java，没有复现 snippet 的分流。
⇒ 本地开发和 Mac mini 测试环境上这些页面 404，而生产正常 —— **一个只在非生产环境
出现的假故障**，最容易被误报成产品缺陷（本轮就是）。

已修：在 `/api` 之前加一条镜像 snippet 的代理规则。

```
修复后经 vite 实测:
  smart-bi/analysis/finance        200
  smart-bi/analysis/sales          200
  smart-bi/mapping-review/pending  200
阴性对照:
  smart-bi/analysis/dynamic        400「缺少必要参数: uploadId」← Java 的错误,
                                       证明正则没把 Java 的路径抢走
```

⛔ 正则**不能**放宽成 `smart-bi/analysis/` 通配 —— `analysis/dynamic` 与
`analysis/dynamic/kpis` 由 Java 的 `SmartBIDashboardController` 提供。

## 3. 我在同一个问题上连错三次，成因是同一类

| # | 我的结论 | 错在哪 |
|---|---|---|
| 1 | 「Java 没这个端点 ⇒ 该补后端或改前端，要产品裁定」 | grep 的是**拼接后的完整路径** `smart-bi/analysis`，而类级 `@RequestMapping(".../smart-bi")` 与方法级 `/analysis/...` **分处两地**，那个串从不以字面量出现。系统性枚举 68 个端点才拿到真相 |
| 2 | 「是我环境不全，nginx 有规则我没复现」 | 读的是 **`.bak` 备份**，不是现行配置 —— 方向蒙对了，依据是错的 |
| 3 | 「规则确实丢了 ⇒ 生产缺陷，要恢复 nginx」 | grep `location` **看不进 `include`**。规则在被 include 的 snippet 里，一直都在 |

▎三次都是**问错了权威来源**：问文本而不是结构 / 问备份而不是现行 / 问文件而不是**展开后的**配置。

▎并且三次的错误方向不同（一次说该改代码、一次说是我的环境、一次说是生产坏了）——
▎**读数在摇摆本身就是信号**：说明判据不够硬，而不是事实在变。

### 差一步就动了生产

我已经把 location 块写进两个 vhost 并 `nginx -t` 通过。**没有 reload**，
因为在 reload 前多看了一眼插入位置上方两行 —— 那里正是 `include` 那一行。

⇒ 保住这次的是「改完先肉眼看一眼上下文再 reload」，不是任何自动判据。
   `nginx -t` 对此毫无帮助：**重复的 location 块语法完全合法**。

## 4. 留下的闸

`web-admin/src/__tests__/gates/smartBiPathsNeedAnNginxSplit.spec.ts`

把「这些路径依赖 nginx 分流」从基础设施里的隐性事实，变成仓库里的**显式登记**：
前端打的每条 `/smart-bi/{analysis,mapping-review}/*`，要么 Java 服务，要么必须登记。
新增未登记的在 CI 红。

⚠️ 它**不能证明 nginx 规则存在**。真正的验证只能对着环境打一次 URL，
并用**时间戳小数位**判上游（见 §1）。

## 5. 运维备忘

- snippet 的仓库副本：`ops/nginx-vhosts-139/smart-bi-routing.conf`（snippet 头部注明要同步）
- snippet 头部明确写着：**新增 location 会移动线上客户流量，须有 cutover spec 授权**
- 我这次的临时改动已回滚，备份文件保留：
  `*.conf.bak.smartbi-split.20260828_151758`（可删）
