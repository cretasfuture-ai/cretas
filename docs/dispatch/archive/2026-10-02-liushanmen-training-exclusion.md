# ML-LIUSHANMEN-TRAIN-EXCLUSION-20261002

Owner: `/root`; Base SHA: `b99cc9d175e97e44948d3db293b99685764f6a64`.
状态：代码与本地验收完成，等待本提交 PR 门禁和合并回读。本批 scope 在合入时释放；原有 ROI 任务不变。

历史人工纠正排除的照片仍保留在磁盘中。追加完整照片时重新枚举物理文件、使用 `images/train`，会让这些排除照片进入训练，即使 manifest 声称忽略区域已排除。构建器现保留排除语义，在需要时写明确训练清单，并按实际参与训练的照片和框计数。原图和历史标注均保留。

共享读取门禁检查 data.yaml 的实际目录或文件清单，将每张训练图绑定到 train provenance，拒绝排除照片、缺失或歧义来源、越界路径。候选 CLI 和直接 tray_workflow 训练入口均在加载模型前执行门禁。没有模型、生产、调度器、CI runner 或共享计费配置变更。

验证：Windows `B:/anaconda3/python.exe -m pytest tools/vision-lab/tests/test_label_qc_tray_full_image_dataset.py tools/vision-lab/tests/test_train_tray_candidate.py tools/vision-lab/tests/test_tray_workflow.py -q`，48 项通过。新增场景覆盖旧版本故障、原图保留而训练排除、目录/清单读取、直接训练入口和模糊来源。实际 Ultralytics 本地读取新准入候选 256 图、4867 框；4 张隔离样本未读入；验证 34 图、792 框字节及分组不变。旧目录中的排除照片被门禁拒绝。

本批是训练数据和工具修复，不是识别精度验收。新准入版本未训练；完整保护与预留样本检查、真正独立样本验收、托盘与缺标无漏检、完整框、ONNX 和生产发布仍需继续。GPU 被其他任务占用，未启动训练或影响该任务。所有原图、人工 JSON 和包含照片信息的回执只存本机，不随代码上传。

本地证据：`D:/CretasVisionLab/audits/hybrid-vlm-20261002/local-data-audit-04/receipt.json`、`local-training-admission-02/admission-receipt.json`、`training-exclusion-live-readback-02.json`、`training-exclusion-final-tests.log`。工具不提供 Goal 编辑/恢复接口；新版执行目标已保存，本回合已获用户继续授权，应用内 Goal 状态仍为暂停。
