# SOP-PG-PROD-ROLLOUT-20261106

Owner food/root DIRECT；Base342dc816fd24deecb85ea0cdbe60a0ae9247daf4，WT C:/Users/Steve/cretas-food-dag-ota-20261105，branch codex/sop-pg-prod-enablement-20261106。1490已合并/官方Java与Web发布/OTA注册，但F006真实上传仍503；运行进程实证--spring.profiles.active=pg-prod，无外置SOP覆盖，而1490仅在application-prod.properties启用。真实QA工序e2705b93-0611-476a-978f-ef75e31cac91保留，文件未上传，未登记附件。

唯一scope：application-pg-prod.properties的SOP写开关叶子与SopAttachmentRolloutContractTest的实际profile绑定/显式false覆盖；本archive。不得改默认无配置false、权限/typed actor/SDK/Controller/Entity/迁移/其他组/发布脚本。prod与pg-prod都需真实ConfigData上下文测试，明确false覆盖仍拒绝；不是把安全门禁移除。PhaseA兼容0955/94已部署，新main342d支持typed actor，回滚floor沿用兼容版本。

验证完成后官方Java-only build/stage、必需CI、正常PR/merge、clean exact-main发布，再同QA工序UI上传/下载/模拟器验收。无前端/native依赖或DDL变更，既有342d Web/Android OTA内容不变，不重复发布相同OTA。真实未通过记录保留，不冒称1490已业务完成。

真实ConfigData双profile绑定与显式false覆盖已6/0/0/0通过（sop-pg-prod-binding-target.log，37秒）；代码默认false未变，之前prod profile测试保留并参数化到pg-prod。最终仅3文件改动，源码冻结；官方3类（绑定+真实Spring安全边界+CI selector契约）构建/stage、required CI、合并/发布及同工序续验分别待，不重复发相同Web/OTA。
