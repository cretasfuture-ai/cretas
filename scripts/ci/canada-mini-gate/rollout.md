# mini 共用槽池切换与恢复

此流程由运维执行。PR #1974 只提供代码和步骤。组织层 runner 访问隔离、迁移及验收仍需 Steve 裁定。裁定和验收前保持新路由关闭。

## 部署步骤

1. **关闭新路由。** 确认仓库变量 `MINI_POOL_V2_ENABLED` 缺失或不等于 `1`。记下旧 `refill.sh`、覆盖文件、槽 1–5 的 LaunchAgent 状态，以及三个标签分别在排队和运行的作业 ID。用完整分页读取仓库的 Actions runs/jobs 和 `actions/runners`，查询失败先停。合并后才读取 main 的工作流定义，旧 run 仍保留原标签。
2. **先验证双标签消费者。** 在开放新路由前，把同一个已审查版本的 `queue-snapshot.sh` 和 `refill.sh` 放到 `~/gh-gate/`，先放 helper，再原子替换 refill。保持其余旧槽接单，在槽 1–3 逐个确认无作业后重启，正在工作的槽等任务完成自行 `exec`。保留旧覆盖配置，避免提前启用槽 4。让预先创建的受控旧标签 Java 作业通过更新后的槽执行。从仓库 `actions/runners` 分页读数核实该槽 `online` 且同时注册 `cretas-mini`、`cretas-mini-pg`、`cretas-mini-fast`。从作业 API 记录实际 `runner_name` 和成功结论，并核对 `MINI_POOL_CAPACITY` 是新的 `时间:可接单槽:空闲槽`。只有启动日志或缓存文件时继续观察。
3. **排空槽 5。** 原子地从 `enabled-slots` 移除 5 并保留已验证的旧标签消费者。核实槽 5 收到停领状态，再等该槽的 `Runner.Listener` 和 `Runner.Worker` 都退出。重复读取完整 `actions/runners` 分页，核实槽 5 没有 `online` 或 `busy` 注册，并回读对应作业结论。任何一个读数缺失或槽 5 仍忙时保持等待。
4. **停止并清理槽 5。** 完成排空后，才 `launchctl bootout` 槽 5，移除它的 LaunchAgent，并按精确 runner ID 清理经复核离线且空闲的槽 5 注册。重新读 launchd、runner 列表与作业状态，确认槽 5 已退出且既有作业完成。如用 `setup.sh` 重装，应先完成上述读数并排空所有会被重启的槽。逐个核对槽 1–4 都已装入 launchd 并使用同一版 refill。撤掉临时覆盖后应白天启用 1–3、夜间启用 1–4。若仍有旧标签排队，必须继续保留它们的消费者。
5. **最后开放新路由。** 确认前四步完成、可信路由已在 main、组织层访问控制已验收后，才设 `MINI_POOL_V2_ENABLED=1` 并发起新的受控同仓 PR 检查。空闲时查 `ROUTE=mini`、JIT 注册、作业 API 的实际 `runner_name` 和 RN、Web、Java 三条作业的成功结论。用实际多 PR 合并组核对所有成员均同仓时允许 mini，且带 `ci-hosted`、含前序 fork 或成员不可验证的组整组托管。复核掉线与零空闲槽也走 `ubuntu-latest`，用受控作业演练 5 分钟恢复并回读托管 `runner_name`。失败时先关闭开关，继续用双标签池完成已排队作业。

回滚时先关闭新路由。等 `cretas-mini` 上正在排队或运行的作业完成，或按下面的恢复步骤改走托管，再恢复只消费旧标签的脚本和槽配置。重新核对两种旧标签均有可用消费者。

## 已派给 mini 的作业恢复

以下恢复步骤适用于使用新路由的 run。切换前创建的 run 按部署步骤用旧标签消费者排空。`runs-on` 在派发后固定。值班从首次确认目标 mini 作业为 `queued` 开始计时，**5 分钟**仍未被接单就启动托管恢复。发生在合并队列时，同时以队列的检查超时为上限，留出重新入队窗口。用 `gh run view <run-id> --json status,headSha` 核对 run，并分页读作业 API 的 `id`、`labels`、`status` 和 `runner_name`。

- **普通 PR**：加 `ci-hosted` 标签。RN、Web、Java 的 `labeled` 和现有 concurrency 会为同一 PR 创建托管 run 并取代等待的旧 run。标签已经存在或新 run 未创建时，走下面同 SHA 重跑流程。
- **仍在队列中的合并组，或普通 PR 的备用路径**：用 `gh run cancel <run-id>` 取消受影响的 run，等待它进入 `completed`，再 `gh run rerun <run-id>` 重跑完整工作流（包括 `changes`）。核对 `run_attempt > 1`、等于原值的 `head_sha` 和对应作业的 `runner_name`。开关仍开时路由日志应有 `ROUTE=hosted reason=retry-hosted`，已关时路由步骤可以跳过。需重跑完整工作流，才能重新求值 `changes` 的输出。
- **组 SHA 已过期或 GitHub 拒绝重跑**：对仍需要合入的 PR 先加 `ci-hosted`，再由维护者重新入队。确认新合并组 SHA 已生成。含该 PR 或成员无法核对的整组走托管，回读三个工作流的作业状态及 runner；所需检查保持有效。

这是值班恢复流程。五分钟是发起恢复的门槛，托管排队时间由 GitHub 决定。修改仓库变量及标签前已创建的 run 保留原快照，须按上述流程启动新 attempt 或新 run。
