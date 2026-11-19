package com.cretas.aims.service.impl;

import com.cretas.aims.controller.CanvasEncodingRuleController;
import com.cretas.aims.controller.EncodingRuleController;
import com.cretas.aims.entity.config.EncodingRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EncodingRuleRepository;
import com.cretas.aims.service.EncodingRuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROTO-QC-NUMBER follow-up M2: 质检记录由数据库触发器自动编号, 编码规则对它不生效 ——
 * 两个新建入口都拒绝建这类规则, 可配类型列表里也不再出现。阳性对照: 别的类型照常能建。
 */
@ExtendWith(MockitoExtension.class)
class EncodingRuleSystemNumberedContractTest {

    private static final String FACTORY = "F006";

    @Mock private EncodingRuleRepository repository;
    @Mock private EncodingRuleService encodingRuleService;

    @Test
    void serviceCreateRejectsQualityInspectionBeforeAnyWrite() {
        EncodingRuleServiceImpl service = new EncodingRuleServiceImpl(repository);
        EncodingRule rule = rule("quality_inspection");

        assertThatThrownBy(() -> service.createRule(rule, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统自动编号");
        verify(repository, never()).save(any());
    }

    @Test
    void serviceCreateStillAcceptsOtherTypes() {
        EncodingRuleServiceImpl service = new EncodingRuleServiceImpl(repository);
        when(repository.existsByFactoryIdAndEntityType(FACTORY, "SALES_ORDER")).thenReturn(false);
        when(repository.save(any(EncodingRule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.createRule(rule("SALES_ORDER"), 1L).getEntityType()).isEqualTo("SALES_ORDER");
    }

    @Test
    void canvasCreateRejectsQualityInspectionBeforeAnyWrite() {
        CanvasEncodingRuleController controller = new CanvasEncodingRuleController(repository);
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "QUALITY_INSPECTION");
        body.put("ruleName", "质检");
        body.put("encodingPattern", "QC-{YYYY}{MM}{DD}-{SEQ:4}");

        assertThatThrownBy(() -> controller.create(FACTORY, body))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统自动编号");
        verify(repository, never()).save(any());
    }

    @Test
    void entityTypeListNoLongerOffersQualityInspection() {
        List<Map<String, String>> types = new EncodingRuleController(encodingRuleService).getEntityTypes(FACTORY).getData();
        assertThat(types).extracting(t -> t.get("code")).contains("MATERIAL_BATCH").doesNotContain("QUALITY_INSPECTION");
    }

    private static EncodingRule rule(String entityType) {
        EncodingRule r = new EncodingRule();
        r.setFactoryId(FACTORY);
        r.setEntityType(entityType);
        r.setRuleName("rule");
        r.setEncodingPattern("{PREFIX}-{YYYY}{MM}{DD}-{SEQ:4}");
        return r;
    }
}
