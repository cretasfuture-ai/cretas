# CI 暂留自建 runner —— 理由、证据、触发条件、owner

**日期**: 2026-09-07
**决定**: **暂不切回托管**（organizer 拍板）
**Owner**: **organizer**（🔴 见文末：这条规则此前**没有 owner**，所以警告喊了 7 天）

---

## 规则怎么说

`.claude/rules/ci-runner-routing.md`：**默认走托管；额度耗尽切自建；每月 1 号切回托管。**

今天是 **09-07**，而 `switch_ci_runner.py --check` 每次都在喊：

```
ci-runner: 当前 = self-hosted (10 个工作流一致)
::warning title=CI 可以切回托管了::托管 Actions 额度每月 1 号重置, 而 CI 仍停在自建 runner 上。
```

⇒ 欠了 **7 天**。

---

## 证据：托管额度**确实**恢复了（行为读数，⛔ 不是账单）

```
script-tests  runner=GitHub Actions 1000001025  labels=ubuntu-latest
              155 秒 / 10 个 step / success
对照(自建)     final-full-suite  runner=canada-mac-mini  labels=self-hosted,cretas  steps=14
```

⚠️ **额度耗尽的签名是「2–4 秒 / 0 个 step / 无日志」**（`ci-runner-routing.md` 记的那次事故）。
现在托管上一个 job 跑满 155 秒、执行了 10 个 step ⇒ 额度可用。

📌 这是**行为读数**，不是账单读数 —— 对「还能不能跑」这个问题它比账单更直接。

---

## ⇒ 但现在不切，理由是**成本**，不是证据

切回会把 `java-full-nightly`（靠 `java-full-ready` 标签调的那条全量）从 M4 Pro 挪到 2 核托管：

| | 自建 M4 Pro | 托管 ubuntu |
|---|---|---|
| 全量套件 | **6–7 分钟** | 按规则里记的比例 **20 分钟以上** |

⇒ 当前有三条线在持续出 Java PR，切了等于给每个 PR 加 ~15 分钟。

---

## 🔒 触发条件（写死，⛔ 不是「以后再说」）

> **当前这批（fresh-db 线 + 软删批 3-B）收口、没有在飞 Java PR 的那一刻，由 organizer 执行切回。**

```bash
python scripts/ci/switch_ci_runner.py hosted   # 然后 git commit && git push（它改的是工作流文件）
python scripts/ci/switch_ci_runner.py --check  # 判据: 当前 = hosted, 10 个工作流一致
```

⚠️ 切回是 **organizer 的动作**，⛔ 不是执行线的。

---

## 🔴 这件事真正的教训：一个没有 owner 的定期动作，等于没有这个动作

这条规则有**日历触发条件**（每月 1 号）却**没有 owner** ——
所以脚本每次 `--check` 都在喊，喊了 7 天没人动。

⚠️ 与同日登记的另一族同形：47 上 **13 个 cron 脚本写 11 个告警文件、零消费方**。
⇒ **告警有没有人读，和告警写得对不对，是两件独立的事**，而前者更容易被默认成真。

▎判据：任何带日历/周期触发条件的规则，**必须写明 owner**；写不出 owner 就说明它不会被执行。

⇒ 本文件把 owner 定为 **organizer**，并把触发条件写死。⛔ 不留「以后再说」。

关联：`.claude/rules/ci-runner-routing.md`、`scripts/ci/switch_ci_runner.py`。
