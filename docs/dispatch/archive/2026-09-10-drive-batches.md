# DRIVE-20260910-INTEGRATION 合并收尾

Owner: Codex task 01a08b76-18e8-7db3-8efb-424e3c4aadf0。
Base SHA: e03fc7cf0c7c100d35cb8e907067e4b7e7e118af。
Scope: B01–B05候选实际文件及目标测试；包含发布前Canvas结果协议兼容门禁、对应API/Controller测试。未修改Entity/Repository/迁移、其他已有Owner的业务范围。

本归档和ACTIVE移除随本PR合入main生效，届时本批状态为merged并释放候选scope。在PR尚未合并时，协调者仍持有本地写锁；归档不代表预先合并或部署。用户已于本任务明确要求“合并部署，然后继续”。

本批已完成五个独立task候选整合与交叉审查，并纠正验收发现的负优化。75行分类和状态已回填，详见 ../drive-20260910/INTEGRATION.md 与 CLASSIFICATION.md。旧页签的Canvas调用缺少协议2标记时在写前拒绝，保留刷新提示；新客户端按逐项回执处理，部署按backend-first串行。

合并后从clean exact origin/main调用统一入口，Java必须正式构建并记录可信manifest，Web仅在tree匹配时复用。App JS按实际runtime兼容性使用现有OTA入口。服务级部署验收与真实业务/真机验收分别记录；发布事实以统一JSON回执及OTA回读为准。

未完成的整行业务要求继续保留在Drive及各批次回执，不因本次merge/deploy自动标为已解决。后续任务必须重新登记唯一scope。
