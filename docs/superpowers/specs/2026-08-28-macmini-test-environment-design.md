# 加拿大 Mac mini 测试环境 — 设计

**日期**: 2026-08-28
**分支**: `codex/claude-testenv-macmini`（off `origin/main` @ `1a44d7b5a7`）
**状态**: 设计待评审

---

## 1. 为什么要这个环境

现在所有验证都在 prod 上做，两个后果：

1. **测试账号密码不能往 prod 输** —— 安全上不成立，于是很多路径根本没法端到端验
2. 每次动 prod 都要人肉确认，来回成本高

2026-07-13 已经把 47 上的 test 环境下线（内存紧张，prod Java 在 2.56G 堆下被压 OOM），
当时的决定就是「后期用单独一台服务器做 test」。本设计就是兑现那句话。

⚠️ **明确不解决**：Claude Code 的 permission 反复提示。那是本地 harness 的 allowlist，
与服务器在哪无关，单独用 `/fewer-permission-prompts` 处理。

---

## 2. 已验证的基线（2026-08-27 实测，不是推断）

| 验的东西 | 读数 |
|---|---|
| Java 后端 in arm64 macOS + PG 17 | **24.5 秒启动**，`/api/mobile/health` → `{"status":"UP"}` |
| Maven 构建 | BUILD SUCCESS，177MB jar |
| Python 109 个依赖 | 全装上（`torch` 除外，见 §6），`import main` 成功 |
| prod schema-only 恢复 | **0 条 ERROR**；492 表 / 201 序列 / 671 flyway 行，逐项等于 prod |
| Flyway 在恢复后的库上 | `Current version: 20261031.02` → `No migration necessary` |
| 用 prod 同款 `pg-prod` profile | 启动干净，**零数据库错误**（只剩 embedding 服务未起的 warmup 告警） |
| 从国内经 Tailscale 访问 | `http://100.85.234.108:10010` → **200** |

机器：`Mac-mini-01`，M4 Pro / 14 核 / 64GB / 1.7Ti 空闲，macOS 26.4 arm64，
Tailscale `100.85.234.108`，用户 `rex`。已装 `openjdk@21` / `maven` / `pgvector` / `redis`，
PG 17.11 原生（与两个 CI runner 共存）。

### 2.1 一个必须知道的前提：库不能从 migration 建

空库上跑 `db/flyway` 会崩在 `V20260827_01`（`relation "production_settlements" does not exist`）——
那张表由**版本号更大**的 `V20261024_04` 创建。prod 上有 **406 条**版本号更高却装得更早的记录：
**prod 是按墙上时间长出来的，空库是按版本号重放的，两者不是同一个顺序。**

⇒ 本设计的数据库来源**只能**是「从 prod dump schema + 搬迁移历史行」，不是重放 migration。
详见 memory `project_fresh_db_cannot_be_replayed_from_flyway_migrations`。

---

## 3. 范围

**做**：部署通道、进程守护、测试数据、访问方式。
**不做**（各自独立，别混进来）：

- ⛔ **PG 版本对齐**（prod 13.23/Linux glibc ↔ mini 17.11/macOS libc）。真差距，但今天这套跑下来没被咬到；
  要对齐得装 colima 跑容器，是独立一件事
- ⛔ **修 Flyway 可重放性**。是个真问题，但那是重构三百多个 migration 的项目
- ⛔ **改 `deploy-backend.sh`**。见 §4.1

---

## 4. 架构

### 4.1 部署通道：新脚本，不动 prod 脚本

**测量结果决定了形状**：`deploy-backend.sh` 是 **2299 行**，62 处 `$SERVER`、**34 处 `systemctl`**。
systemd → launchd 不是变量替换能解决的，而它是 prod 的部署路径。

⇒ **新增 `scripts/deploy/deploy-testenv-macmini.sh`，`deploy-backend.sh` 一个字不动。**

```
scripts/lib/deploy-common.sh        (已存在, 459 行 — 唯一真相)
    log / wait_for_health / wait_for_health_via_ssh
    check_git_sync / acquire_deploy_lock / archive_backup
    graceful_kill / rollback_jar / check_disk_space
         |
         +-- deploy-backend.sh              (prod, Linux/systemd)   ← 不动
         +-- deploy-testenv-macmini.sh      (test, macOS/launchd)   ← 新增
```

**新脚本只负责 macOS 特有的那部分**：目标 `rex@100.85.234.108`、路径 `/Users/rex/cretas-testenv`、
`launchctl kickstart -k` 代替 `systemctl restart`。

⛔ **防漂移（形态 D）**：新脚本**不许**自己实现 health check / 锁 / 备份 / 回滚。
加一道闸：断言 `deploy-testenv-macmini.sh` source 了 `deploy-common.sh`，
且**没有**自定义 `wait_for_health` / `acquire_deploy_lock` / `rollback_jar` 同名函数。
（这条闸要先让它红一次再合 —— 未经证伪的闸不算闸。）

**制品来源**：与 prod 同源，从 `origin/main` 构建。测试环境**不从 feature 分支部署**，
理由与 `worktree-and-main-only-deploy.md` 铁律 3 相同（共享路径 last-write-wins）。

### 4.2 进程守护：launchd

三个服务，与这台机器上 CI runner / PG 同一套生命周期（`~/Library/LaunchAgents` + `RunAtLoad`）：

| 服务 | 端口 | plist |
|---|---|---|
| Java 后端 | 10010 | `com.cretas.testenv.backend.plist` |
| Python SmartBI | 8083 | `com.cretas.testenv.python.plist` |
| Embedding | 9090 | `com.cretas.testenv.embedding.plist` |

⚠️ **两个 CI runner 在同一台机器上**，任何「按 runner 隔离」的东西按 `RUNNER_NAME` 分。
测试环境用**独立的库**（`cretas_db` / `smartbi_db`），与 CI 的 `cretas_r10_it_*` 不重叠。

**profile 用 `pg-prod`**（与 prod 完全相同的那个），不是 `pg`：
- `pg-prod` 是 `ddl-auto=none`；`pg` 是 `update` ⇒ 后者会让 Hibernate 拿 Entity 硬改 schema，
  实测产生 **86 条** `PSQLException`（16 条是 "column cannot be cast to boolean"）
- `pg-prod` 的数据源全是环境变量驱动，默认值恰好是 `localhost / cretas_db / smartbi_db` ⇒ **零配置改动**

**时区钉 `-Duser.timezone=Asia/Shanghai`**，让它像 prod。
⚠️ 时区 bug 的照妖镜作用**继续交给 CI 那条线**（自建 runner 在另一时区，接入当天照出 3 个真 bug）——
测试环境要的是「复现 prod」，两个目标相反，不要压在一个环境上。

### 4.3 测试数据

现在库里**只有结构，没有数据**。分两层：

1. **结构**：`pg_dump --schema-only --schema=public --no-owner --no-privileges` 自 prod
   + `flyway_schema_history` / `smartbi_migrations` 的**行**（元数据，非业务数据）
   ⚠️ 只搬结构不搬迁移历史行 ⇒ 下次启动会以为什么都没跑过，从 baseline 重放，撞回 §2.1 那个失败
2. **数据**：仓里已有的 seed（`scripts/seed-restaurant-test-accounts.sql`、
   `scripts/e2e/liushanmen-demo/seed-demo-chain.py`）+ 固定测试账号

**刷新节奏**：结构随部署走（每次部署重新对一次 schema 差异并告警，⛔ 不自动改）；
数据由人手动重置，提供 `--reset-data` 开关。

⛔ **不从 prod 拉业务数据。** 生产数据出境到加拿大是合规决定，本设计不含它。

### 4.4 访问

**Tailscale-only**，不开公网。实测从国内 `264ms`（4 包丢 1）。

- Agent / 开发机：装 Tailscale 直连 `100.85.234.108`
- 手机上的 RN app：装 Tailscale
- ⛔ 不开 Tailscale Funnel —— 一个装着测试凭证、`ddl-auto` 可写、无 WAF 的环境不该有公网入口

---

## 5. 验收判据

⛔ 「部署脚本 exit 0」**不是**判据（`RELEASE_FINAL_STATUS=deployed` 同理）。逐条：

1. **进程启动时间晚于制品 mtime**，且**在运行中的 jar 里 grep 得到本次改动的标记**
2. `curl http://100.85.234.108:10010/api/mobile/health` → `{"status":"UP"}`
3. Java 日志里 Flyway 打出 `No migration necessary`（不是 `Migrating schema`）
   —— 出现 `Migrating` 说明迁移历史行没搬对
4. **真日志文件**里 `ERROR` 计数 = 已登记的基线
   ⚠️ `pg-prod` 的 logback 写文件，stdout 只有 494 字节 banner。
   **看 `logs/cretas-backend-error.log`，不要看 nohup 的那个** —— 从空文件里数出来的 0 是假读数（实测踩过）
5. 用固定测试账号**真登录一次**，拿到 token 并调一个业务接口
   —— 「制品里有标记」只证明发上去了，不证明跑得起来

**三态退出码**：`rc=0` 正常 / `rc=1` 有问题 / `rc=2` **没量到**（阳性对照没过、健康检查连不上）。
`rc=2` 必须用与 `rc=1` 不同的措辞单独告警，且**要主动构造一次**确认它到得了。

---

## 6. 前置依赖（独立小 PR，先做）

**`backend/python/requirements.txt:99` 的 `torch==2.4.1+cpu` 在 arm64 macOS 上不存在。**
实测 pip 可用版本列表里只有不带 `+cpu` 的 `2.4.1`。改成带 platform marker：

```
torch==2.4.1+cpu ; python_version >= "3.8" and sys_platform != "darwin"
torch==2.4.1     ; sys_platform == "darwin"
```

⚠️ 它是**唯一**的平台阻塞点 —— 其余 108 个依赖 arm64 轮子齐全（实测全装成功）。

---

## 7. 已知不保真之处（登记，不是豁免）

| # | 差异 | 影响 | 处置 |
|---|---|---|---|
| 1 | PG **13.23/Linux glibc** ↔ **17.11/macOS libc** | 中文 `ORDER BY` 排序可能不同；PG 17 接受的语法 PG 13 可能拒绝（**测试绿了 prod 才炸**，是危险方向） | 登记，暂不处理。⛔ 涉及排序/索引行为的结论不要只在这里下 |
| 2 | 运行时 **macOS** ↔ **Linux** | 路径、进程模型、文件大小写敏感性 | 登记 |
| 3 | 无业务数据 | 任何依赖真实数据分布的判断在这里做不了 | 用 seed；涉及真实数据的验证仍在 prod 只读做 |
| 4 | 与 2 个 CI runner 共享机器与 PG 实例 | CI 跑批时性能抖动 | 独立库隔离；性能类读数不在这里量 |

▎**这个环境的定位是「功能对不对、流程通不通」，不是「性能/排序/数据分布」。**

---

## 8. 未决 —— 需要 owner 拍板

1. **仓库读权限**：这台机的 gh 账号 `canadaremote001-coder` 看不到 `cretasfuture-ai/cretas`
   （runner 用的是临时 token）。当前用 runner checkout 本地克隆凑合。
   §4.1 的方案是**从这边推制品**，所以**不强制**需要它 —— 但若要 mini 自己拉代码就需要。
   **对外授权动作，等 owner 决定，不自行申请。**
2. **测试账号口令的存放位置**：现在临时用 `cretas_pass` / `smartbi_testenv_pass`（PG 只听 127.0.0.1）。
   要不要收进 `.claude/skills/server-operations/db-credentials.md`（gitignored）那套？

---

## 9. 相关

- `.claude/rules/worktree-and-main-only-deploy.md` — 只从 main 部署
- `.claude/rules/measurement-and-wiring.md` — §5 的判据形状来自这里
- memory `project_canada_mac_mini_test_environment_bringup` — 实测底账
- memory `project_fresh_db_cannot_be_replayed_from_flyway_migrations` — §2.1 的依据
- memory `project_ci_runner_is_a_canada_mac_mini_over_tailscale` — 这台机的 CI 角色
