# 新会话可直接粘贴的 Prompt

---

接手一轮工作。先读交接件，再动任何东西：

`C:\Users\Steve\cretas-handoff\docs\handoff\HANDOFF-2026-08-27-reachability-round-2.md`

上一棒（可达性第二轮）已合入 11 个 PR，主线是「功能做好了但用户碰不到」。
现在**只剩一件真正悬着的事**：一次 OTA 推送的**送达**没确认。

## 🔴 第一件事（在做任何改动之前）

```bash
# ① 你在不在落后的分支上 —— 本仓踩过两次(落后 1251 / 1424 个 commit)
git rev-list --count HEAD..origin/main      # 非 0 就停下

# ② OTA 送达查了没有 —— 这是上一棒唯一没闭环的
ssh root@47.100.235.168 "grep OTA_PULL /www/wwwroot/cretas/python-prod.log | tail -5"
```

判据（⛔ 三条缺一不可）：

- 看 **`ip=121.228.99.40`** 那台（现场设备）。
  ⛔ `ip=127.0.0.1` 那条 `outcome=update` **是上一棒的探针，不是送达** ——
  上上轮就是把自己的出口 IP 数成了现场设备。
- 要看到它的 `outcome=update`，且 `current` 从 **`c72f63b3`** 变成别的值。
- 再杀再开 App 仍是新值（`no-update`）才算真落地。

若还是 `no-update` 且 `current=c72f63b3`：**不是失败，是设备还没打开 App**。
它是 `checkAutomatically: "NEVER"` + 切前台才检查，催不动，等着即可。

## 开工前

```bash
cd C:\Users\Steve\cretas-handoff          # RN + web-admin 的 node_modules 都装好了
cd frontend/CretasFoodTrace && npx jest src/__tests__/gates/
# 期望: 16 suites 全绿, 且 [NAV_MOUNT_CTX] ... dead=1 budget=1
```

⛔ 别引用交接件里的数字当结论 —— **当场重测**。上一棒就靠这条抓到一句过期判据
（「审批实例 0 行」今天是 44 行）。

## ⛔ 挂账的别顺手做（各有理由，交接件 §4 有全表）

- **web-admin 83 条孤儿路由** —— 已普查：单据都在流转、零待批、审批走 App，
  **它们不在挡人**。⛔ 不要批量接线。等有人真说「我找不到 X 页面」再接那一条。
- **13 个 IoT/摄像头屏** —— owner 说将来会启用，已登记在
  `retiredScreensStayRetired.test.ts` 的 `PARKED_UNTIL_IOT_LAUNCH`，
  解冻条件写死在那里。⛔ 不要当死代码删。
- **审批 Tab** —— 要恢复得先把 `factory_super_admin` 加进 `ROLE_TYPES`
  **并**确认它真有待办，⛔ 不是看到「审批有实例了」就接回来。

## 接任何东西之前，先过这三问（上一棒最有用的一条）

```
① 生产上有没有数据?    表行数 / 端点实打
② 有没有别的可用路径?  web-admin 有没有 / App 有没有
③ 有没有卡住的单子?    status 分布里有没有堆积的 PENDING
```

三问把两件长得一样的事分开了：报表（后端 21/22 有数、手机侧唯一路径恒假）**该接**；
HR/审批/付款/领料（单据全在流转、零待批）**不该接**。

## 硬边界

- ⛔ 提交一律 `./scripts/safe-commit.sh "msg" <逐个列出文件>`
- ⛔ 开 PR 必须带 `-R cretasfuture-ai/cretas`
- ⛔ squash 合并后必须开新分支（`safe-commit.sh` 会拦）
- ⛔ 含转义字符的内容用 Write/Edit 落盘，不经 shell
- ⛔ 变异撤回用整份快照写回，不要 `replace(new, old, 1)`
- ⛔ 每条判据都要有能让它红的对照；变异不红时先证明变异真的生效了
- 打生产端点从**服务器本机** `127.0.0.1` 打（本机 HTTP 直连 47 返回 `000`，SSH 通）
- 测试凭证在 `.codex/rules/test-credentials.md`（gitignored）

## 报数纪律

报任何数字时**把口径一起报**。上一棒有一次「可达屏 397 → 364」，
屏一个都没删，是口径变准了 —— 不带口径会被读成「删了 33 个屏」。
