# `/smart-bi/analysis/*` 的 nginx 分流规则丢了 —— 需要 owner 决定怎么恢复

**日期**: 2026-08-28
**发现方式**: F006 全路由视觉验收，`/finance/reports` 等 4 个页面报 404
**性质**: 🔴 **生产缺陷**（不是测试环境的问题 —— 我一开始判断错了两次，见文末）

---

## 现象

前端这 4 个页面调 `/api/mobile/{factoryId}/smart-bi/analysis/finance`（和 `sales`）→ **404**：

```
/finance/reports          /finance/sku-margin
/analytics/alert-dashboard    /smart-bi/food-kb-feedback
```

## 根因：一条依赖跨了三个系统，而中间那一环不在仓库里

| 环节 | 状态 |
|---|---|
| ① 前端用共享 `get()` 打 `/{factoryId}/smart-bi/analysis/finance`（baseURL `/api/mobile`）| 正常 |
| ② Java 服务它吗 | ❌ **没有** —— 系统性枚举了 5 个 `/smart-bi` 控制器的 68 个端点，只有 `analysis/dynamic` 和 `analysis/dynamic/kpis` |
| ③ Python 服务它吗 | ✅ 有 —— `smartbi_compat/api/analysis_finance.py:3488` 注册的正是这个路径 |
| ④ 谁把请求从 Java 分流给 Python | 🔴 **nginx，而当前配置里这条规则不在了** |

`.bak`（2026-05-15）里还留着，注释说明了设计意图：

```nginx
# Phase 2A SmartBI (BUG-R1B-01 / PR #441): route smart-bi/analysis/* for cutover
# factories to Python prod 8083. Java handlers deleted in T6.5 Phase A->C (May 9,
# PR #205/#236).
location ~ ^/api/mobile/(F00[1-46]|FOOD_3101_(00[1-9]|0[1-3][0-9]|04[0-8])|MEAT_3101_00[12]
            |OTHER_3101_001|RES_3101_00[1-9]|RES_GML_001|R001|R_(GML_DEMO|XMX_(CHAIN|FRESH[123]?)
            |YHDJ_DEMO|YJJ_DEMO)|TEST_0000_001)/smart-bi/analysis/(finance|sales|department
            |region|inventory|procurement)(/.*)?$ {
    proxy_pass http://cretas_python;
}
```

⚠️ `F00[1-46]` **包含 F006** —— 也就是说这条规则在的时候，这些页面是好的。

**当前两个 vhost 都没有它**（139 网关，2026-08-28 实测）：

```
web-admin.conf                      唯一的 /api/mobile/*/smart-bi/* regex 是 upload-batch-stream
admin.cretaceousfuture.com.conf     同上
⇒ 其余全部落到 location /api/mobile/ → Java → 404
```

---

## 两条出路

| | A 恢复 nginx 规则 | B 改前端走 `/smartbi-api` |
|---|---|---|
| 改动面 | 1 个 location 块（`.bak` 里有已验证的正则） | 4 个组件、12+7+2 处调用 |
| 风险 | 前端零改动，组件本来就是对的 | **换鉴权通道**（Java axios 实例 → Python 客户端，Bearer 取自 localStorage）+ 错误处理要跟着改 |
| 符合设计意图 | ✅ Java handler 是被有意删掉的 | ❌ 等于承认 Java 通道废弃 |
| 长期脆弱性 | ⚠️ 路由仍活在基础设施里，**可能再丢一次** | ✅ 依赖写进代码 |

**推荐 A**（改动最小、最贴合设计），但**必须配 §「运维清单」那条部署后验证** ——
否则同一条规则丢第三次时，同样没有任何东西会告诉你。

⛔ **我没有动 prod 的 nginx。** 那是基础设施变更，需要 owner 授权。

---

## 我做了什么（仓库侧，已提交）

`web-admin/src/__tests__/gates/smartBiPathsNeedAnNginxSplit.spec.ts`

把这个隐形依赖**变成显式登记**：前端打的每一条 `/smart-bi/{analysis,mapping-review}/*`，
要么 Java 服务它，要么必须登记在 `PYTHON_SERVED_VIA_NGINX` 里。新增一条没登记的，
在 CI 红，而不是上线后 404。

⚠️ **这道闸不能证明 nginx 规则存在** —— nginx 不在仓库里。它证明的是
「这条路径依赖 nginx 分流」这件事被写下来了。真正的验证只能在部署后打一次 URL。

闸迭代了两版，第一版误报：它去找拼接后的完整串，而 Python 侧写的是
`_BASE = ".../smart-bi/mapping-review"` 加 `_BASE + "/pending"` ——
**完整路径在源码里从不以字面量出现**。

---

## 运维清单（恢复规则后必做）

```bash
# 光看 nginx -t 通过不算 —— 那只证明语法对。要真打一次:
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  "https://admin.cretaceousfuture.com/api/mobile/F006/smart-bi/analysis/finance?startDate=2026-07-01&endDate=2026-08-01"
# 期望 200。404 = 规则没生效; 401 = token 问题，不是路由问题
```

⛔ **不要用不带 token 的请求判路由存不存在** —— 鉴权过滤器在路由之前跑，
已知能用的端点不带 token 也返回 401，这个判据**没有鉴别力**（本轮实测踩过）。

---

## 我在这条上判断错了两次，记下来

1. 先说「Java 没有这个端点 ⇒ 该补 Java 或改前端，要产品裁定」——
   **错在 grep 的是拼接后的完整路径** `smart-bi/analysis`，而类级
   `@RequestMapping("/api/mobile/{factoryId}/smart-bi")` 与方法级 `/analysis/...`
   分处两地，那个串**从来不会以字面量出现**。系统性枚举才拿到真相。
2. 再说「是我环境不全，nginx 有分流规则我没复现」——
   **错在读的是 `.bak` 备份文件**，当前生效的配置里那条规则已经没了。

▎两次都是**读错了"权威来源"**：第一次问的是文本而不是结构，第二次问的是备份而不是现行。
