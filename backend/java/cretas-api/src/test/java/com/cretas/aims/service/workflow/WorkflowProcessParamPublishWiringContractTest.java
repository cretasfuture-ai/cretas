package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工艺参数落库「接上没有」: materializer 单测绕过了谁来调它。这里用源码顺序钉住发布入口
 * (与 PublishBomSyncOrderContractTest 同一套做法):
 * 校验闸(含待确认 blockers) → 工艺参数落库 → CCP 台账 → 草稿置 PUBLISHED。
 * 把 processParamMaterializer 的调用删掉, 本类变红。
 */
class WorkflowProcessParamPublishWiringContractTest {

    private String read(String rel) throws Exception {
        return Files.readString(Path.of("src/main/java/com/cretas/aims/" + rel));
    }

    @Test
    void publishCallsTheMaterializerAfterTheGateAndBeforeAnythingElseIsWritten() throws Exception {
        String src = read("service/impl/ProductProcessWorkflowServiceImpl.java");
        int gate = src.indexOf("validator.validateForPublish(definition)");
        int params = src.indexOf("processParamMaterializer.materialize(");
        int ccp = src.indexOf("ccpMaterializer.materialize(");
        int status = src.indexOf("draft.setStatus(ProductProcessWorkflow.Status.PUBLISHED)");
        assertTrue(gate > 0 && params > 0 && ccp > 0 && status > 0, "四个锚点都应存在");
        assertTrue(gate < params, "工艺参数落库必须在待确认闸之后(闸拦住就走不到落库)");
        assertTrue(params < ccp, "可能 409 的工艺参数先判, CCP 台账之后写");
        assertTrue(ccp < status, "草稿置 PUBLISHED 最后");
        assertTrue(src.contains("@Transactional\n    public ProductProcessWorkflowDTO publish("),
                "publish 在事务里, 409 会连台账与草稿状态一起回滚");
    }

    @Test
    void theGateIncludesTheProcessParamBlockers() throws Exception {
        String validator = read("service/validation/ProductProcessWorkflowValidator.java");
        assertTrue(validator.contains("WorkflowPendingConfirmations.blockers(definition)"),
                "validateForPublish 必须调用 blockers, 否则说不清的参数拦不住发布");
        String pending = read("service/workflow/WorkflowPendingConfirmations.java");
        assertTrue(pending.contains("processParamBlockers(process, params)"), "blockers 必须包含工艺参数判定");
    }

    @Test
    void publishServiceHasTheMaterializerInjectedByAutowiring() throws Exception {
        // required=false 时没注入会静默跳过落库: 至少钉住字段存在、类型对、带 @Autowired
        var field = com.cretas.aims.service.impl.ProductProcessWorkflowServiceImpl.class
                .getDeclaredField("processParamMaterializer");
        assertTrue(field.getType() == WorkflowProcessParamMaterializer.class);
        assertTrue(field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class),
                "字段必须带 @Autowired, 否则 Spring 不会注入, 落库静默不发生");
    }

    @Test
    void theMaterializerIsAnInjectedSpringComponent() throws Exception {
        String src = read("service/workflow/WorkflowProcessParamMaterializer.java");
        assertTrue(src.contains("@Component"), "必须是 Spring 组件, 否则 Autowired(required=false) 永远是 null、落库静默不发生");
    }
}
