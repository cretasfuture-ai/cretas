# Dispatch 完成记录 — 2026-08-25

## `ML-LIUSHANMEN-TRAY-AWARE-HARD-NEGATIVE-20260825` — `review complete`

- **实现**：VisionLab 新增默认关闭的 `training.isolated_missing_repeat`。只对任务级划分后进入 train、人工确认缺一个类别且只有一个互补类别框的单目标裁图重复采样；validation 永不复制，多框邻盘歧义和两类都缺的空框图不加权，声明缺失类别却又存在该类别框时直接失败。旧数字 `0/1` 与新字符串类别格式均受支持；权重策略进入不可变 dataset ID，默认值 `1` 保持既有 ID，代码合入不会自动触发重训。
- **真实数据验证**：累计 573 个有效人工样本在 repeat=2 时形成 `label-27e393962ffa`：463 个 unique train + 63 个训练副本，110 个 validation 原样保留；累计识别 81 个单类 isolated missing，其中 train 63、validation 18。人工标注、保护集、原图、既有模型均未改写。
- **候选裁决**：候选 `label-27e393962ffa-20260825-010837` 的 PT/ONNX parity mismatch 为 0，但 label-presence 从基线 104/110 回退到 103/110；白标和彩标各新增 1 个 FP，虽彩标少 1 个 FN，仍按零新增误报门禁在生产回放前拒绝。最后 epoch 复核结果相同，不存在 checkpoint 误选。生产回放未执行、自动部署关闭、生产 v8 SHA-256 保持 `bfd8a3adccbbe0bf5ccbe84eec205858204f05013c97e9e1cc6fb192a9b1f3f2`，生产写入为 0。
- **验证**：`B:\anaconda3\python.exe -m py_compile tools/vision-lab/vision_lab.py` 通过；`B:\anaconda3\python.exe -m pytest tools/vision-lab/tests -q` 为 264 passed、27 subtests passed；`git diff --check` 通过；PR #41 的 tracked-secret-scan 通过。
- **运行时收尾**：共享 SQLite `last_trained_dataset_id` 已恢复为 `label-c2f6d81d9c9c`，避免旧 exact-main 计划任务重复训练；生产模型登记 SHA 仍为 `bfd8a3ad...`。实现 scope 已释放；失败候选和不可变数据集仅保留为本地审计证据，不能授权部署。
