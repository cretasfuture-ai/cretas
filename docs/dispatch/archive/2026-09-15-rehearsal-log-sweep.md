# 2026-09-15 真机彩排日志扫描（10:15–10:52，Java green / Python / 手机 logcat）

窗口内**本次改动造成的**错误只有一类，且已修：`VisionVerifyService 视觉服务调用失败` ×2（10:28:42 / 10:32:19，502，elapsed 30042/30068 ms）→ PR #801/#802 已上 prod，修后 6 次 vision/verify 全 200（最慢 23.5 s 为 DashScope 长尾，已由 12 s/27 s 预算兜住）。

演示租户 `CATTLE_3701_001` 全天 request_audit：除上述 2 次 502 和 1 次预期 404（09:56 造数脚本先查 `labels/trace/{code}` 再建）外全部 2xx；非 vision 路由最慢 468 ms（`publish-and-activate`）。

## 与本次改动无关、但值得登记的（都在 prod 上，重启时或启动时出现）

| # | 现象 | 证据 | 影响 / 建议 |
|---|---|---|---|
| 1 | Python `python-service.log` 每次重启被重建 | 部署后 `head_ts=10:45:11`，10:28 那三条 `aliyun_*_vl timeout` 已不在文件里；旧文件只剩 `python-service.log.2026-03-09/03-31/04-08`（后两个 0 字节） | 事故证据只活到下一次部署；systemd 的 `python-prod.log`（append）是唯一持久流，排障先看它 |
| 2 | 启动 ERROR：`[platform-sync] PLATFORM_GOLD_PRODUCT_INTERVAL_SECONDS=None 非法, 回退 600s` | `smartbi.gold.live_refresh` 10:46:07 | prod `.env` 未配该变量；回退 600 s 可能不是想要的值。配上或把缺省改成 WARN |
| 3 | 启动 WARN：`LLM Router admin routes not registered: cannot import name 'llm_router_admin' from 'smartbi.api'` | `main` 10:38:21 / 10:38:38 ×2 / 10:45 | LLM 路由管理接口在 prod 上不可用；要么补模块，要么删这段注册代码 |
| 4 | `uvicorn --workers 2`，而 `restaurant_outliers` 警告「Module-level cache assumes single-worker uvicorn. If you switch to --workers > 1, add Redis backend」 | systemd `ExecStart … --workers 2`；WARN 每 worker 各一条 | 缓存在两个 worker 各一份，命中率减半且可能不一致；餐饮侧，按裁定只登记 |
| 5 | RN：`[PushNotification] 推送未配置: app.json 的 extra.eas.projectId 缺失, 所有平台都拿不到 Push Token` | logcat 10:27:37 / 10:50:44 | 现有 APK 推送永远不工作；OTA 改不了 app.json，要发新 APK |
| 6 | RN：`expo-image-picker MediaTypeOptions deprecated` ×8；`expo-av deprecated (SDK 54 移除)` | logcat | 升 SDK 前处理，现无影响 |
| 7 | logcat 10:30:18 `FATAL EXCEPTION` pid 32478 | uid 2000 = 我跑的 `uiautomator dump`（探针自身崩了一次），不是 App | 无 |

## 手机侧观察（不是缺陷，但影响下次操作）
- MIUI 在密码框聚焦后 `screencap` 返回 rc=1（无 FLAG_SECURE，仍拒绝）；改用 `uiautomator dump` 读 UI。
- `input text` 只能 ASCII → 建档时物种/品种留空了（`SL-CATTLE-007` species=null），周三手输。
- 在栈根按 Back 会退出 App 跳到手机上另一个 App（PomeloX，它自己在弹更新框，我没碰）。
