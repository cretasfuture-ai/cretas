# CI 恢复与真实失败处理

任务 `FIX-CI-RECOVERY-20260909`，Base `83c0e2a2023487c3033d757d7912f58d3ef01d21`。用户要求继续解决原 #755 的 CI 红灯及剩余脚本失败。

## 事实与修复

- 仓库级 runner 返回 0 不等于没有可用 runner。组织级 `windows-wsl-ci`（id 16）已注册；Windows 保活任务此前退出，WSL 生命周期中断并留下短暂重复 session。已启动保活，保留登录触发与 IgnoreNew，增加每 5 分钟恢复触发、失败重试；配置前后 XML 留本地。保留 ciworker 的内存、CPU、文件及生产网络隔离。
- 原 #755 的 tracked-secret-scan 已在恢复的 runner 上真实通过（run 34227439475，job 102064915294）；不能用 runner online 代替作业证据。
- `script-tests` / `scan-surface-in-sync` 托管任务因 GitHub billing 未启动。两者一起经统一切换脚本接入现有 Linux runner；Ops 显式准备 Node.js，补齐 Web 制品脚本的运行依赖。
- Windows 的 merge-result 检查把含 UTF-8 的注释误计为 local action；改为匹配 YAML 实际键，保留注释阴性及真实配置阳性对照。两个 shell 的 12 组断言均通过。
- Windows pidfile 锁测试的 exec 改变 Windows PID；无 flock 时让 shell 自身存活，有 flock 时保留 exec 语义。Windows/Linux 互斥及释放测试均通过。
- 并行发布测试耗时改用单调时钟，保留既有反应时限、完成标记及不得中断不可逆阶段的断言，避免 WSL 校时导致 15 秒被读成 14 秒。
- Python 的独立测试库认证失败是环境错误。新增命令包装器创建随机端口、只监听 localhost 的短生命 PostgreSQL 集群，测试结束始终停止并删除自己的目录，保留测试命令失败码。Python 39 项真实 PG 测试通过，包装器成功/失败及清理验证通过；Java PG 车道共用此隔离方法，不再依赖共享 cretas_user/5432。
- Python venv 从解释器真实路径创建，避免 relocated CPython 经 /usr/local/bin 符号链接生成错误 home。CI 使用 3.11；Linux 保留 CPU 版 Torch，仅 macOS 去掉 +cpu 标识。
- 实际 Ops CI 首轮在 JDK 版本探测中失败：`JAVA_TOOL_OPTIONS` 提示占据首行。解析改为读取版本行，实测保留该环境变量时得到 `JAVA_MAJOR=21`。
- Java 首轮 2,013 项测试中唯一失败为 `JavaPrGateTieringContractTest` 仍钉住旧 5432/共享用户配置。已更新为检查新包装器接入及隔离、缺件失败、退出清理和原命令失败码契约，最终验证由真实 Java Gate 执行。
- Web 旧 PR 全量 8 项失败；7 个涉及文件在 2 worker 下的 67 项用例通过。CI 限制为 2 worker，与可见 16 CPU / 实际 6 CPU 配额相匹配，不扩大超时或排除测试。临时副本的全量运行暴露缺少跨语言输入，已补齐；该次运行不能作为最终全量通过证据。

## 验收边界

- PR #758 首轮 Python Gate（commit `27572cba81b04dc642ebe6da411b4c89382f6ba5`，run `34259128685`）实际运行完成：9,650 passed / 1 failed / 166 skipped / 153 deselected / 2 xfailed；唯一失败为 `test_registry_audit_date_is_not_stale`。PostgreSQL 认证错误已消失，未删除任何原有测试。修复 PR 的最终检查须另按最终 head 核验。
- 初次 WSL 全脚本检查 69 项中 64 通过、4 失败、1 跳过。新增失败分别为 Windows PATH 中的 Docker CLI 误探测、缺 Linux Node.js、系统校时。修复后的目标检查和真实 GitHub 结果另留证据，不能把初次结果写成全绿。
- AI 注册表仍有真实的定时复审红灯：`_REGISTRY_AUDIT_DATE=2026-08-13` 已过 21 天。用户 09-09 明确指示“这一点先不要管”，因此从本批修复范围排除；保留测试，不改日期或伪造额度。
- Ops 的数据库基线重放改为显式支持独立原生 PG（Docker 默认入口保留）：实测 502 表 / 2,044 索引 / 181 唯一约束 / 227 外键 / 8,812 字段全部匹配。错误 SQL 检测及篡改文件头索引计数必须失败的阴性对照通过；未连接生产。
- Docker 依赖的 fresh-db / 手动全栈工作流尚未证明可在当前隔离 runner 运行，不能只改 runs-on 或开放生产权限来伪装接入。
- 本批不修改生产业务数据、AI 密钥或既有生产发布。未做 Windows 主机重启验证。
- WSL 时钟实测存在倒退（10 秒内 2 次，最大 935 ms），会触发 Vue 事件时间戳守卫。单独更换时钟源或暂停客体 NTP 均未消除，均已恢复原配置（`tsc`、`systemd-timesyncd active/enabled`）。Windows 网络时间对照显示宿主约快 0.93 秒，W32Time 未运行，当前进程启动服务被权限拒绝；已请求用户在 Windows 设置中校时。未用修改测试事件时间戳或放宽断言掩盖该问题。

证据目录：`D:/Temp/cretas-ci-recovery-20260909/`。CI 最终结果须以对应 commit、runner、steps 和结论为准。
