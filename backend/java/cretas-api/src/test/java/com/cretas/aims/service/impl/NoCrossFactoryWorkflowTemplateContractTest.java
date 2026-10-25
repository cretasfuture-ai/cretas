package com.cretas.aims.service.impl;

import com.cretas.aims.entity.WorkflowTemplate;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.StateMachineRepository;
import com.cretas.aims.repository.WorkflowTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Steve 2026-10-24: 流程建议只来自公开资料和平台自编模板; 平台不分析、不比对任何工厂的工艺流程。
 * factory_config_agent 的 save_template 曾把调用方工厂已发布的状态机复制进全局 workflow_templates
 * (无 factory_id), list_templates 再把所有工厂的模板名和描述列给任何工厂。
 */
@ExtendWith(MockitoExtension.class)
class NoCrossFactoryWorkflowTemplateContractTest {

    @Mock WorkflowTemplateRepository repository;
    @Mock StateMachineRepository stateMachineRepository;
    @InjectMocks WorkflowTemplateServiceImpl service;

    @Test
    void aFactoryWorkflowCannotBeSavedAsAPlatformTemplate() {
        assertThatThrownBy(() -> service.extractFromStateMachine("F006", "PRODUCTION_WORKFLOW",
                "卤制线", "腌制后滚揉再卤制", "[]"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不再保存为平台通用模板");
        verifyNoInteractions(stateMachineRepository);
        verify(repository, never()).save(any());
    }

    @Test
    void listingReturnsOnlyPlatformAuthoredTemplates() {
        WorkflowTemplate platform = WorkflowTemplate.builder().templateName("通用卤味流程").isSeedData(true).build();
        when(repository.findByIsSeedDataTrue()).thenReturn(List.of(platform));

        assertThat(service.search(null)).containsExactly(platform);
        assertThat(service.search("卤味")).containsExactly(platform);
        assertThat(service.search("别家")).isEmpty();
        verify(repository, never()).findByReviewStatusIn(any());
        verify(repository, never()).findByTemplateNameContainingIgnoreCase(any());
    }
}
