# Claude 交叉分析与本地模型提示蒸馏

任务 `SMARTBI-CLAUDE-TEACHER-20261104`；Owner Codex/root；Base `bb1a811201c5a1f8df8d0f06bb5fd149bde863ad`；草稿 PR #1390；不部署生产。

## 实验与边界

- 用户授权直接调用 Sonnet、Opus，整轮 API 预算不超过 10 美元。
- 仅重放六膳门已采集快照的 2026-10-29 至 11-03 完工工单；源数据只读，不重新采集黑湖，不写黑湖业务数据。
- 实际 Java FULL 成本授权和工厂身份复核通过后才读取成本。教师包只有匿名事实代号、统计类别和数值，不含原始记录、工厂/人员/产品名称、工单号、账号、工资明细或认证信息。
- 12 个案例主题：覆盖与选择偏差、低成本边界、同产品差异、人工与工时、产出与损耗、领退料、半成品与单位、库存、跨期、正常对照、单位不可比较对照、小样本。正常与单位对照明确标注为合成数据，不冒充六膳门现场事实。
- 每个案例 Sonnet 初评、二次追查，共 24 次。Opus 复核其中 5 组，另做 1 次方法整理，共计划 30 次付费调用。
- 正常、单位、小样本 3 组留作独立检验，不进入最终提示词整理输入。教师输出只是候选方法，经过人工核验、本地 27B 重放后才考虑接入；不自动改写经营指标或生产规则。

## 预算与恢复

`backend/python/blacklake/teacher_eval.py` 使用固定官方域名 `https://api.anthropic.com`；不使用转发服务、快速模式、搜索、外部工具或提示缓存。先通过 Models API 核对已知且已定价的模型；不存在时停止，不猜模型别名。

官方标准输入/输出单价（美元/百万 token）：Sonnet 5.5 为 2/10，Opus 5.5 为 4/20；可用性回退仅限已知 Sonnet 4.6 的 3/15 与 Opus 4.6 的 5/25。价格核对于 2026-11-04，依据 [官方价格表](https://platform.claude.com/docs/en/about-claude/pricing)。

每次请求以文本 UTF-8 字节上界加协议余量、输出最大 token 数和标准单价，在 SQLite `BEGIN IMMEDIATE` 事务内预留最高费用；总账上限固定为 10,000,000 微美元。只有预算通过才发送。返回实际 token 后结算；网络超时、缺失用量、未知收费、截断输出均不自动重试。未知费用保留预留额并停止后续付费调用，避免重复扣费。重启复用同一总账，不重置预算。完整响应绑定请求内容散列缓存，再次打开不重复付费。

预算只涵盖本实验官方 API token 费，不含其他程序使用同一 Key 的消费；其他程序也不占用本实验账本。

## 安全配置与运行

检查 Process/User/Machine 环境变量和 Claude 常用 settings 后，本机没有可复用 API Key。用户通过隐藏输入脚本将 Key 以 Windows CurrentUser DPAPI 加密保存，文件 ACL 只授权当前用户，位于仓库外。Codex 桌面 AppData 可能重定向，外部 PowerShell 的配置助手使用已解析的实际保存路径，不依赖两个进程的同名 AppData。

本机助手：`C:/Users/Steve/Configure-ClaudeTeacher.ps1`。不把 Key 放在参数、聊天、日志、Git 或 Claude Code 配置中。运行器也支持进程 `ANTHROPIC_API_KEY`，仅连接官方域名。

```powershell
# 在 backend/python 目录运行；不带 --run 只准备匿名案例，不付费
python -m blacklake.teacher_eval
# --run 表示执行用户已经授权的付费实验；总账仍硬性限额 10 USD
python -m blacklake.teacher_eval --run
```

私有案例、响应、预算与实验日志位于 `%LOCALAPPDATA%/Cretas/SmartBITeacher/experiment-20261104`（Codex 进程解析后的路径）；不进入 Git。

## 验证

目标测试：`python -m pytest tests/test_blacklake_teacher_eval.py -q`，12 项通过。覆盖并发预算、防重置/重复调用、实际 token 结算、网络未知结果保留预算、未知收费/模型拒绝、截断缓存拒绝、匿名字段白名单与检验案例隔离。

用户要求长文保留后，汇总调用支持显式 `allow_partial`：保留已返回正文并标记 incomplete/needsLocalSummary，不将不完整 JSON 冒充正常结构化结果。默认调用仍拒绝将截断输出直接作为完整结论。

本文件记录方法和约束。实际付费完成数、token、费用、教师发现和本地重放结果以本轮完成回执为准；不能以代码/离线测试通过代替真实模型完成。
