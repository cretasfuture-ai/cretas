# ARTIFACT-SOP-ACTOR-20261105 / Phase A

Owner food/root，Base eacf526cf33daa707f4f45f86eae471d076cf4a5，branch codex/sop-compatibility-phase-a-20261105。

仅后端兼容版本：V20261105_94扩展SOP枚举与独立上传actor namespace，工厂用户/平台管理员同号不混用；保留旧uploaded_by插入/正常元数据更新兼容，不允许身份漂移。SOP元数据无对象locator，下载另需VIEW_FULL+EXPORT，上传对象须private ACL、实际HEAD匹配大小/MIME及目标路径，中文key一次解码，不生成通用缩略图。

写入默认关闭，service所有SOP写端fail closed；既有只读资料安全可用，generic legacy numeric-user SOP写不能绕过typed actor。Phase A不含Web/RN，不能把兼容版上线说成SOP功能开放。Phase B取得真实OSS证明后再开启并交付Web/RN；其自动回滚目标必须包含本兼容版，禁止退到旧枚举/nullable不安全二进制。

审计8e7的WASTAGE inventory映射与Controller restaurant OR移除精确接入，同时补齐inventory粗/细权限闭环；精确单模块权限fixture覆盖WASTAGE全读写/批量、receipt写以及restaurant/跨模块拒绝，保留无entityType旧上传契约。

验证证据：12类首次101项100通过1fixture flush错误；修复并补权限后实际Spring21/0/0/0通过。真实PG migration1/JPA2和未变其他目标证据保留；最终官方完整目标/必需CI仍待终态，不宣称已部署。无App/OTA/用户签署。

ARTIFACT-SOP-OSS-PROOF-20261106：独立opt-in真实SDK测试只允许本地H2与随机QA_SOP对象，private签名PUT/ACL/HEAD/中文注册/匿名拒绝/精确finally清理；密钥仅环境，缺显式开关时skip不计通过。子代理只写单测试文件，未发OSS请求；root后续执行。全功能Web/RN源保留1f8fa45338，待Phase B取入，不混本PR。

2026-11-06最终阶段：head4c73官方13类105总项/104通过/0失败/0错误/1外部opt-in跳过，真实PG migration1/JPA2已执行；统一build/stage exit0/FINAL built一次，receipt cretas-1793897899-1378，未安装/重启/迁移。外部opt-in另行在同head实际启用并通过1/0/0/0：真实OSS签名private上传、中文key、ACL/HEAD、匿名拒绝、typed actor本地H2注册与精确finally对象删除，无生产DB访问；SDK proof不冒称生产业务UI。

已无冲突汇合新main1477/eacf，SOP产品/测试内容未改、旧通过证据保留；组合Java树变化须重新官方构建，不能把旧tree制品当新main可部署制品。PhaseA写默认disabled不变，发布仍需required CI、线上Flyway编号顺序/真实锁及主协调窗口。所有必要结果分别记录，不把PR或stage说成已部署。
