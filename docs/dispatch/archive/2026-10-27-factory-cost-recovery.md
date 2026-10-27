# FADCOSTREC27 — 补齐投入成本后恢复联产成本

2026-10-27 收尾登记：PR1243 已以 2536e9d454b49751657b535db798e89851851082 合并；八类 27 项测试全部通过，适用 CI 全部通过。开发 Base e20c391 保留为历史；最终集成/生产发布 Base SHA 为 9178dc7769b5211735e958d50443277cc5d0da5c，仅部署其后的成本恢复 Java 差异，不扩大为发布其他 Owner 的 Web。当前最终后端树 3afe5abe1b168a017a447b22cd2d9ba52814467b，因父提交加入租户备份功能，须为此集成树构建一次可信制品，不能复用旧 b06 树制品。

Java 问答 409 依赖已由完整成功回执 cretas-1793108236-61056.json 证明在线：source9178dc、backend tree db6686f2e7e69ccdaf65652899615982c78f618d、exit0、no-op、active blue10010、backend_health=pass。705d5fd 的问答接口与 Python 文件相对9178均无差异；此证明只用于允许 Python 共享上下文发布，不宣称2536成本修复已部署。最终 Java 与 Python 发布及业务验收仍以后续完整回执记录。

状态 review；Base SHA e20c39173039dcd53409e8a56b296f4a58fb941d；协调者/执行 Owner Root DIRECT。源码/测试冻结，独立风险复核通过；后续仅Root进行制品/CI/合并发布，合并后释放scope。未完成的测试/发布不记通过。

用户授权完成 AI 汇总/多表分析依赖问题、验证后合并部署，仅F006只读验收。本任务修复实际发现的前置成本恢复缺陷：PR1237已Merged，旧cost worktree clean，目标源码与Main相同，当前ACTIVE没有同文件在飞锁。不能以保留的Squash分支/历史分叉推断旧写Owner仍占用；不修改旧cost worktree。

仅允许 backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ProcessSheetServiceImpl.java 和 src/test/java/com/cretas/aims/service/processentry/ProcessSheetMultiOutputUnknownCostTest.java。已知成本多产出分支保存前清除costIncomplete；测试必须从两个输出均true/NULL状态恢复，断言false且total/unit非NULL、总额140.80；未知分支保持true/NULL。不改Entity、Repository、迁移、权限、API、依赖或业务数据，不执行回填/重算生产记录。

验收：Root冻结后一次官方artifact build，选择器 ProcessSheetMultiOutputUnknownCostTest,FactoryChartAnalysisContractTest,FactoryChartAnalysisGatewayContractTest,FactoryChartAnalysisFailureRelayContractTest,FactoryChartAnalysisControllerContractTest,RuntimeToolPolicyLoaderTest,RuntimeToolDescriptorRegistryTest,ToolDescriptorInventoryDriftTest。执行者本轮不单独跑Maven，避免同内容测试后再clean package；只做最小改动/diff check并回传，Root统一运行真实测试与制品构建。context_risk_review独立终审；无本批Entity/Repository改动，复用Main既有真实JPA门禁证据，不冒称新跑。

Root唯一台账写者、提交者与发布者。子代理不得提交/台账/代理/生产操作。Root维护当前完整验收与部署状态，旧预上传制品后端树a913已因Main推进安全拒绝，不能以旧SHA部署新Main。后续从clean exact main官方发布，Java在Python之前；本任务生产业务写入保持0。

实际修改：known batch保存前清false；测试先真实allocate未知投入并调用同一carrier/sibling的calculateMetrics验证true/NULL/NULL，再以已知投入重分摊并调用生命周期，验证false、非NULL成本与140.80=112.64+28.16。删掉修复行会确定失败。冻结SHA256：service d231252640bba868ee839c4ceeb178ca1bbaaa5180a636eade4d7f311bf8b2c2；test 099073d19b0aa24d0c04ef9186489b310356018fd61ec4ea56e4194ce266b064。最终八类执行测试待官方制品构建，PR说明/本机回执记录实测；同内容不重复全量。
