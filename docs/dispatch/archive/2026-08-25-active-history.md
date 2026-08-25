# Dispatch 完成记录 — 2026-08-25

## `ML-LIUSHANMEN-TRAY-AWARE-HARD-NEGATIVE-20260825` — `review complete`

- **实现**：VisionLab 新增默认关闭的 `training.isolated_missing_repeat`。只对任务级划分后进入 train、人工确认缺一个类别且只有一个互补类别框的单目标裁图重复采样；validation 永不复制，多框邻盘歧义和两类都缺的空框图不加权，声明缺失类别却又存在该类别框时直接失败。旧数字 `0/1` 与新字符串类别格式均受支持；权重策略进入不可变 dataset ID，默认值 `1` 保持既有 ID，代码合入不会自动触发重训。
- **真实数据验证**：累计 573 个有效人工样本在 repeat=2 时形成 `label-27e393962ffa`：463 个 unique train + 63 个训练副本，110 个 validation 原样保留；累计识别 81 个单类 isolated missing，其中 train 63、validation 18。人工标注、保护集、原图、既有模型均未改写。
- **候选裁决**：候选 `label-27e393962ffa-20260825-010837` 的 PT/ONNX parity mismatch 为 0，但 label-presence 从基线 104/110 回退到 103/110；白标和彩标各新增 1 个 FP，虽彩标少 1 个 FN，仍按零新增误报门禁在生产回放前拒绝。最后 epoch 复核结果相同，不存在 checkpoint 误选。生产回放未执行、自动部署关闭、生产 v8 SHA-256 保持 `bfd8a3adccbbe0bf5ccbe84eec205858204f05013c97e9e1cc6fb192a9b1f3f2`，生产写入为 0。
- **验证**：`B:\anaconda3\python.exe -m py_compile tools/vision-lab/vision_lab.py` 通过；`B:\anaconda3\python.exe -m pytest tools/vision-lab/tests -q` 为 264 passed、27 subtests passed；`git diff --check` 通过；PR #41 的 tracked-secret-scan 通过。
- **运行时收尾**：共享 SQLite `last_trained_dataset_id` 已恢复为 `label-c2f6d81d9c9c`，避免旧 exact-main 计划任务重复训练；生产模型登记 SHA 仍为 `bfd8a3ad...`。实现 scope 已释放；失败候选和不可变数据集仅保留为本地审计证据，不能授权部署。

## `FIX-LIUSHANMEN-PRESENCE-OWNERSHIP-20260825` — `review complete`

- **预检修复**：label-presence 不再把 padded crop 边缘的邻盘预测直接计为目标托盘 FP；人工审核框始终保留为目标托盘真值，只有人工真值中缺失类别的边缘预测才按 `pad_ratio=0.14` 近似排除并逐项审计。baseline/candidate 的 rule、ratio、normalized margin 任一缺失或不一致都会 fail closed；完整原图生产回放继续作为最终 ownership 真值。
- **类别平衡能力**：新增 `training.isolated_missing_repeat_by_class`，允许对白标/彩标缺失样本分别设置 1–10 倍，仅复制 task-level train 中人工确认、单互补框的 isolated missing；validation、模糊/排除、unjudgeable、多框歧义和受保护回放样本不复制。未配置时继承旧 scalar 并保持历史默认 dataset ID。
- **真实数据与候选**：累计 573 张唯一人工样本中，train 的缺白标/缺彩标为 54/9。类别平衡数据集 `label-d707c55a9594` 保留 unique train/val 463/110，只增加 18 个缺彩标训练副本。候选 `label-d707c55a9594-20260825-022454` 的 PT/ONNX mismatch=0；修正后的 presence 从 baseline 104/110 提升到 106/110，两类 FP 均为 0。
- **生产链回放裁决**：同一候选在 310 张生产运行时回放仍将受保护缺彩标照片 `29c7e4bb-b9f3-4407-8eae-1c25174102ac` 从 `MISSING_COLOR_LABEL` 判成 `CLEAR`，protected/independent 均为 6/7；19+77+200 共 296 张正常图全部零误报。门禁失败，候选 `NOT_DEPLOYED`；生产模型 SHA-256 保持 `bfd8a3adccbbe0bf5ccbe84eec205858204f05013c97e9e1cc6fb192a9b1f3f2`，生产写入为 0，原图/人工真值/保护集均未改写。
- **验证与下一步**：`py_compile`、VisionLab 全套测试和 `git diff --check` 通过。现有缺彩标真例不足，后续只从新照片中挖掘模型判 `CLEAR` 但存在缺彩标差异/不确定性的目标托盘交人工审核；不得把已暴露保护样本回灌训练，也不得推全量照片给审核员。实现 scope 已释放。

## `SOP-RAG-SYNC-20260825` — `merged`

- **工厂实现与合并**：PR #54 已合入 `af4024ae7100ae0a55df83e9fd1a8a6385b929d5`；仅更新现有工厂在线 SOP、唯一 `f006-production-full-chain-sop.md` 知识源、工厂确定性回答与 AI Assist 工厂 tab。口径覆盖 Workflow 当前配置/已开工历史/旧会话冲突、撤销重结后的真实停产门禁、分次入库及幂等、质检证据和处置审批、成品盘点/报废/可售批次，以及含税价和长度采购单位。
- **生产部署**：从 clean exact `origin/main` 通过 release preflight；142 个 production SmartBI migration 与 main 逐文件 SHA-256 一致，当前 main runner dry-run 确认 pending=0。Python 服务发布并健康，工厂 `/lsmsop/` 与共享 `/aiassist.html` 从 main 原子替换并保留备份；仅工厂 canonical source 原子更新 76→79 块，`.NEW=0`。
- **独立验收**：Python 目标测试 138 passed，GitHub tracked-secret-scan 与 Web Admin vitest/typecheck 均通过；三页线上/仓库 SHA-256 一致。工厂真实回答 7/7 仅引用工厂 source；餐饮真实回答 2/2 仅引用餐饮三个 registered source，餐饮页面/tab/source 与块创建时间保持 no-op，正式块分别为 87、250、176。
- **安全与收尾**：Python、Embedding、PostgreSQL、Redis 健康；生产 ERP 业务写入为 0，未触碰测试环境与 `.env`，保留发布备份和两个 worktree。工厂 scope 已释放，餐饮 scope 从未占用。
