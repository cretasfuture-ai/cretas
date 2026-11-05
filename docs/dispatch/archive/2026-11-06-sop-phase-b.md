# ARTIFACT-SOP-PHASE-B-20261106

Owner food/root；Base 09555864bf513a87285c96f7fd6b137dd164f150；worktree C:/Users/Steve/cretas-food-dag-ota-20261105，branch codex/sop-frontend-enablement-20261106。

唯一范围：复用已冻结1f8的13个SOP Web/RN文件，工序/设备管理抽屉和报工当前工序只读入口；既有docs/specs/sop-attachments.md的UX Flow Analysis继续适用，不重造流程。不覆盖其他组质量/返工/发货/路由/主布局。根协调者保留最终diff、测试、commit、PR、合并、发布。

此分支暂基于PhaseA候选并汇合最新main，写默认disabled保持；PhaseA兼容后端+94实际部署成功之前不得启用或发布本分支。PhaseB开启SOP写入时再精确更新rollout默认值及对应Contract，不改PhaseA实体/迁移。回滚下限必须是已部署PhaseA或更晚兼容jar，不能退回不识别typed platform actor/新enum的旧二进制。

冻结后目标：Web SopFilesDrawer tests/type/build，RN ProcessSopEntry/屏幕/utility tests/type，Java rollout与SOP权限/真实PG/JPA适用门禁，required CI；官方Java/Web部署与main Android OTA后仅自有F006 QA工序/文件验收。真实OSS SDK proof已在PhaseA4c73通过，但不冒充生产UI/平台具名授权/App验收。无真人审批/签署替代。实体手机永久禁用，新模拟器须明确serial且不占验收5558/保全5554。

准备阶段验证：13文件Git blob逐个等于冻结1f8；新main组合Web抽屉23项通过/完整vue-tsc exit0、RN完整tsc exit0。RN七文件与已通过3套28项内容完全一致，复用sop-actor-rn-target.log，不重复同内容测试。尚未开启写入/发布/OTA/业务验收；PhaseA先行与兼容回滚下限不变。

2026-11-06进入启用切片：PhaseA已normal merge09555864bf并官方deployed/迁移94成功，6639旧附件actor回填异常0，当前SOP行0；已满足先兼容后启用前置。已汇合正式main0955，保留其所有代码/归档，本分支不回退平台/质量/返工主线。

唯一新增配置范围：application-prod.properties显式cretas.sop-attachments.write-enabled=true；SopAttachmentRollout代码缺省false保持，新增真实ConfigDataApplicationContextInitializer的prod绑定阳性与显式false覆盖拒绝Contract。prod开启开关之前沿用原身份/权限/工艺保密/ACL/HEAD/路径/审计全部门禁，不生成额外例外。回滚下限精确09555864bf或其后兼容jar，原PhaseA源码与测试留Git历史。冻结后的官方Java/Web制品、required CI、合并/生产部署、main Android OTA/5560 App与F006受控业务验收必须分别完成。

最终主线组合Web完整type exit0；旧RN28与type内容未变复用，Web抽屉23通过。已恢复原独立SOP spec（含完整UX Flow Analysis）到本切片，不生成Superpowers制品。实际Java prod配置绑定新两项仍待官方13类，未把前端或旧PhaseA测试当新配置已验证。

交付依赖冻结：父PR1487（Material/同food Owner）稳定源687b3已合入本feature，1490临时base到其分支，父正常合main后retarget main；B仅17文件增量，不重新接管或重复修改Supplier/物料源码。仍从Base0955定义官方release范围，避免squash后feature父SHA不在main祖先链；最终B制品包含父物料与本SOP界面/配置，13类SOP门禁与combined required CI分别记录，父212目标内容复用。

父修复已精确汇合224c49c104（生产单位修复d60b3ddc80与六fixture），组合主线Base更新2de52fb66e7caaf892d9430afba23e8b81d5e57d，B仍17文件增量，不改Supplier。旧父212不背书新单位源码；父官方32类与新CI独立完成，B新增ConfigData四项本地4/0/0/0已过，B官方13类/新CI与发布仍待。
