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

## 验收边界

- 初次 WSL 全脚本检查 69 项中 64 通过、4 失败、1 跳过。新增失败分别为 Windows PATH 中的 Docker CLI 误探测、缺 Linux Node.js、系统校时。修复后的目标检查和真实 GitHub 结果另留证据，不能把初次结果写成全绿。
- AI 注册表仍有真实的定时复审红灯：`_REGISTRY_AUDIT_DATE=2026-08-13` 已过 21 天。需要当前控制台额度/到期日/用完即停证明及活性探针，不通过改日期、缩小测试或伪造余额清除。用户已收到缺失资料询问，其他 CI 修复继续执行。
- Docker 依赖的 fresh-db / 手动全栈工作流尚未证明可在当前隔离 runner 运行，不能只改 runs-on 或开放生产权限来伪装接入。
- 本批不修改生产业务数据、AI 密钥或既有生产发布。未做 Windows 主机重启验证。

证据目录：`D:/Temp/cretas-ci-recovery-20260909/`。CI 最终结果须以对应 commit、runner、steps 和结论为准。
