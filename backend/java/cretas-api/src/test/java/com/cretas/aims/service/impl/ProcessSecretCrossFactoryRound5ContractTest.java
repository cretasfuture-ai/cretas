package com.cretas.aims.service.impl;

import com.cretas.aims.controller.ActiveLearningController;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.config.AIIntentConfigHistory;
import com.cretas.aims.entity.learning.AnnotationQueue;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.AIIntentConfigHistoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.AnnotationQueueRepository;
import com.cretas.aims.repository.learning.CrossFactoryKnowledgeAdoptionRepository;
import com.cretas.aims.repository.learning.CrossFactoryKnowledgeRepository;
import com.cretas.aims.service.ActiveLearningService;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.intent.IntentConfigWriteGuard;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第五轮复审 (2026-10-29): 平台级行回滚先在副本上查保密产品名 (受管行不被改); 标注分配只回本厂这次分到的;
 * 审批人 / 应用人 / 标注人取登录身份; 跨厂知识打分要先采用。每组带阴性对照。
 */
class ProcessSecretCrossFactoryRound5ContractTest {

    static final String FA = "FA";
    static final String SECRET = "秘制酱牛肉";

    IntentConfigWriteGuard guard;
    AIIntentConfigRepository intents;
    ProcessSecretAccessService access;

    @BeforeEach
    void setUp() {
        access = mock(ProcessSecretAccessService.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        when(access.factoriesWithProtectedProducts()).thenReturn(Set.of(FA));
        when(access.protectedProductTypeIds(FA)).thenReturn(Set.of("pA"));
        ProductType p = new ProductType();
        p.setId("pA");
        p.setFactoryId(FA);
        p.setName(SECRET);
        when(products.findAllById(Set.of("pA"))).thenReturn(List.of(p));
        intents = mock(AIIntentConfigRepository.class);
        guard = new IntentConfigWriteGuard(intents, new ProcessSecretSampleFilter(access, products));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    static AIIntentConfig platformRow(String id, String code, String keywords, String previousKeywords) {
        AIIntentConfig c = new AIIntentConfig();
        c.setId(id);
        c.setIntentCode(code);
        c.setKeywords(keywords);
        c.setPreviousSnapshot("{\"keywords\":\"" + previousKeywords.replace("\"", "\\\"") + "\"}");
        return c;
    }

    IntentConfigRollbackService rollbackService() {
        IntentConfigRollbackService s = new IntentConfigRollbackService(intents,
                mock(AIIntentConfigHistoryRepository.class), new ObjectMapper());
        ReflectionTestUtils.setField(s, "intentConfigWriteGuard", guard);
        when(intents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return s;
    }

    // ---------------------------------------------------------------- H1 批量回滚: 受管行不被改

    @Test
    void platformRollbackAllLeavesARefusedRowUntouchedAndReportsIt() {
        IntentConfigRollbackService service = rollbackService();
        AIIntentConfig secret = platformRow("p1", "SECRET_CODE", "[\"产量\"]", "[\"" + SECRET + "\"]");
        String secretSnapshotBefore = secret.getPreviousSnapshot();
        Integer secretVersionBefore = secret.getConfigVersion();
        AIIntentConfig plain = platformRow("p2", "PLAIN_CODE", "[\"产量\"]", "[\"出货\"]");
        when(intents.findGlobalIntents()).thenReturn(List.of(secret, plain));
        when(intents.findById("p1")).thenReturn(Optional.of(secret));
        when(intents.findById("p2")).thenReturn(Optional.of(plain));

        Map<String, Object> result = service.rollbackFactoryToLastVersion(null, 1L, "admin", "r");

        // 受管的那一行 (提交时会被刷进库的对象) 一个字段都没动
        assertThat(secret.getKeywords()).isEqualTo("[\"产量\"]");
        assertThat(secret.getPreviousSnapshot()).isEqualTo(secretSnapshotBefore);
        assertThat(secret.getConfigVersion()).isEqualTo(secretVersionBefore);
        assertThat(result).containsEntry("refusedCount", 1).containsEntry("refused", List.of("SECRET_CODE"))
                .containsEntry("successCount", 1);
        // 阴性对照: 不提保密产品的那一行照常回滚
        assertThat(plain.getKeywords()).isEqualTo("[\"出货\"]");
    }

    /**
     * 复审 M1 (第六轮): 批量回滚的全厂保密产品名只在动任何一行之前取一次; 取不到 → 503, 一行都不碰
     * (以前循环里逐行查库, 查库异常把外层事务标成只能回滚, 前面成功的也被撤销、报 500)。
     */
    @Test
    void platformRollbackAllLooksUpNamesOnceAndFailsWholeBatchBeforeTouchingRows() {
        IntentConfigRollbackService service = rollbackService();
        AIIntentConfig secret = platformRow("p1", "SECRET_CODE", "[\"产量\"]", "[\"" + SECRET + "\"]");
        AIIntentConfig plain = platformRow("p2", "PLAIN_CODE", "[\"产量\"]", "[\"出货\"]");
        AIIntentConfig plain2 = platformRow("p3", "PLAIN_CODE_2", "[\"产量\"]", "[\"库存\"]");
        when(intents.findGlobalIntents()).thenReturn(List.of(secret, plain, plain2));
        when(intents.findById("p1")).thenReturn(Optional.of(secret));
        when(intents.findById("p2")).thenReturn(Optional.of(plain));
        when(intents.findById("p3")).thenReturn(Optional.of(plain2));

        service.rollbackFactoryToLastVersion(null, 1L, "admin", "r");
        verify(access, Mockito.times(1)).factoriesWithProtectedProducts();   // 一批只查一次

        // 查不到名单: 503, 一行都没动
        when(access.factoriesWithProtectedProducts()).thenThrow(new IllegalStateException("db down"));
        AIIntentConfig fresh = platformRow("p4", "PLAIN_CODE_3", "[\"产量\"]", "[\"出货\"]");
        when(intents.findGlobalIntents()).thenReturn(List.of(fresh));
        when(intents.findById("p4")).thenReturn(Optional.of(fresh));
        Mockito.clearInvocations(intents);
        assertThatThrownBy(() -> service.rollbackFactoryToLastVersion(null, 1L, "admin", "r"))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .satisfies(e -> assertThat(((com.cretas.aims.exception.BusinessException) e).getCode()).isEqualTo(503));
        assertThat(fresh.getKeywords()).isEqualTo("[\"产量\"]");
        verify(intents, never()).findById(anyString());
        verify(intents, never()).save(any());
    }

    /** 复审 L3: 快照字段类型不对时不会把行恢复一半 (本厂行也先在副本上恢复)。 */
    @Test
    void aBadSnapshotNeverHalfRestoresAFactoryRow() {
        IntentConfigRollbackService service = rollbackService();
        AIIntentConfig own = platformRow("f1", "OWN_CODE", "[\"产量\"]", "[\"出货\"]");
        own.setFactoryId(FA);
        own.setPreviousSnapshot("{\"keywords\":\"[\\\"出货\\\"]\",\"quotaCost\":\"not-a-number\"}");
        when(intents.findById("f1")).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.rollbackToLastVersion("f1", 1L, "u", "r"))
                .isInstanceOf(ClassCastException.class);
        assertThat(own.getKeywords()).isEqualTo("[\"产量\"]");       // keywords 在 quotaCost 之前, 以前已被改掉
        verify(intents, never()).save(any());
    }

    @Test
    void rollbackToVersionDoesNotTouchTheRowWhenRefused() {
        AIIntentConfigHistoryRepository history = mock(AIIntentConfigHistoryRepository.class);
        IntentConfigRollbackService service = new IntentConfigRollbackService(intents, history, new ObjectMapper());
        ReflectionTestUtils.setField(service, "intentConfigWriteGuard", guard);
        AIIntentConfig row = platformRow("p1", "SECRET_CODE", "[\"产量\"]", "[\"出货\"]");
        String before = row.getPreviousSnapshot();
        when(intents.findById("p1")).thenReturn(Optional.of(row));
        AIIntentConfigHistory h = new AIIntentConfigHistory();
        h.setSnapshot("{\"keywords\":\"[\\\"" + SECRET + "\\\"]\"}");
        when(history.findByIntentConfigIdAndVersionNumber("p1", 3)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.rollbackToVersion("p1", 3, 1L, "admin", "r"))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        assertThat(row.getKeywords()).isEqualTo("[\"产量\"]");
        assertThat(row.getPreviousSnapshot()).isEqualTo(before);
        verify(intents, never()).save(any());
    }

    // ---------------------------------------------------------------- M1 标注分配

    @Test
    void assignReturnsOnlyTheRowsJustAssignedInThisFactory() {
        AnnotationQueueRepository annotations = mock(AnnotationQueueRepository.class);
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "annotationRepository", annotations);
        AnnotationQueue a = AnnotationQueue.builder().id(11L).factoryId(FA).userInput("今天产量").build();
        when(annotations.findPendingForAssignment(eq(FA), any())).thenReturn(List.of(a));
        // 别厂同名标注人名下的行: 以前会被一起返回
        AnnotationQueue foreign = AnnotationQueue.builder().id(99L).factoryId("FB").userInput(SECRET + "多少").build();
        when(annotations.findByAssignedToAndAnnotationStatus(anyString(), any())).thenReturn(List.of(a, foreign));
        // 这一次真正领到的 (库里回读, 复审 M2)
        AnnotationQueue claimed = AnnotationQueue.builder().id(11L).factoryId(FA).userInput("今天产量")
                .assignedTo("alice").annotationStatus(AnnotationQueue.AnnotationStatus.ASSIGNED).build();
        when(annotations.assignToAnnotator(eq(List.of(11L)), eq("alice"), any(), any())).thenReturn(1);
        when(annotations.findByIdInAndFactoryIdAndAssignedToAndAssignedAt(eq(List.of(11L)), eq(FA), eq("alice"), any()))
                .thenReturn(List.of(claimed));

        List<AnnotationQueue> assigned = service.assignAnnotations(FA, "alice", 5);

        assertThat(assigned).containsExactly(claimed);
        assertThat(a.getAssignedTo()).isNull();          // 手里的旧实体没被改 (提交时不会写回覆盖别人的领取)
        verify(annotations, never()).findByAssignedToAndAnnotationStatus(anyString(), any());
    }

    @Test
    void assignWithNothingActuallyClaimedReturnsNothing() {
        AnnotationQueueRepository annotations = mock(AnnotationQueueRepository.class);
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "annotationRepository", annotations);
        when(annotations.findPendingForAssignment(eq(FA), any())).thenReturn(List.of(
                AnnotationQueue.builder().id(11L).factoryId(FA).userInput("今天产量").build()));
        when(annotations.assignToAnnotator(any(), anyString(), any(), any())).thenReturn(0);   // 并发被别人领走

        assertThat(service.assignAnnotations(FA, "alice", 5)).isEmpty();
        verify(annotations, never()).findByIdInAndFactoryIdAndAssignedToAndAssignedAt(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- Low 1 操作人取登录身份

    @Test
    void reviewerApplierAndAnnotatorComeFromTheToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("username", "alice");
        request.setAttribute("userId", 7L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ActiveLearningService service = mock(ActiveLearningService.class);
        ActiveLearningController controller = new ActiveLearningController(service);

        controller.approveSuggestion(FA, 5L, "mallory", "ok");
        controller.rejectSuggestion(FA, 5L, "mallory", "no");
        controller.applySuggestion(FA, 5L, "mallory");
        controller.assignAnnotations(FA, "mallory", 3);
        controller.completeAnnotation(FA, 6L, "I", "mallory", null);

        verify(service).approveSuggestion(FA, 5L, "alice", "ok");
        verify(service).rejectSuggestion(FA, 5L, "alice", "no");
        verify(service).applySuggestion(FA, 5L, "alice");
        verify(service).assignAnnotations(FA, "alice", 3);
        verify(service).completeAnnotation(FA, 6L, "I", "alice", null);
        verify(service, never()).approveSuggestion(anyString(), anyLong(), eq("mallory"), any());
    }

    // ---------------------------------------------------------------- Low 2 知识打分先采用

    @Test
    void knowledgeFeedbackRequiresTheFactorysAdoption() {
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        CrossFactoryKnowledgeRepository knowledge = mock(CrossFactoryKnowledgeRepository.class);
        CrossFactoryKnowledgeAdoptionRepository adoptions = mock(CrossFactoryKnowledgeAdoptionRepository.class);
        ReflectionTestUtils.setField(service, "knowledgeRepository", knowledge);
        ReflectionTestUtils.setField(service, "adoptionRepository", adoptions);
        when(adoptions.findByKnowledgeIdAndFactoryId(1L, FA)).thenReturn(Optional.empty());

        // 没采用 → 和「不存在」同一个 404 (复审 L5)
        assertThatThrownBy(() -> service.recordKnowledgeFeedback(1L, FA, true))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessage("知识不存在")
                .satisfies(e -> assertThat(((com.cretas.aims.exception.BusinessException) e).getCode()).isEqualTo(404));
        verifyNoInteractions(knowledge);
        com.cretas.aims.entity.learning.CrossFactoryKnowledgeAdoption adopted =
                com.cretas.aims.entity.learning.CrossFactoryKnowledgeAdoption.createNew(2L, FA);
        when(adoptions.findByKnowledgeIdAndFactoryId(2L, FA)).thenReturn(Optional.of(adopted));
        when(knowledge.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordKnowledgeFeedback(2L, FA, true))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class).hasMessage("知识不存在");

        // 阴性对照: 采用了的工厂照常打分
        com.cretas.aims.entity.learning.CrossFactoryKnowledgeAdoption adoption =
                com.cretas.aims.entity.learning.CrossFactoryKnowledgeAdoption.createNew(1L, FA);
        when(adoptions.findByKnowledgeIdAndFactoryId(1L, FA)).thenReturn(Optional.of(adoption));
        when(knowledge.findById(1L)).thenReturn(Optional.of(
                com.cretas.aims.entity.learning.CrossFactoryKnowledge.builder().content("说法").build()));
        service.recordKnowledgeFeedback(1L, FA, true);
        verify(knowledge).save(any());
    }
}
