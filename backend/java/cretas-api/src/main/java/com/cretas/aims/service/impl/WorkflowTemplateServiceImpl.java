package com.cretas.aims.service.impl;

import com.cretas.aims.entity.WorkflowTemplate;
import com.cretas.aims.entity.rules.StateMachine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.StateMachineRepository;
import com.cretas.aims.repository.WorkflowTemplateRepository;
import com.cretas.aims.service.WorkflowTemplateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowTemplateServiceImpl implements WorkflowTemplateService {

    /** 自愿共建 (书面同意) 上线前关闭: 不把任何工厂的流程复制进全局模板表。 */
    static final boolean CROSS_FACTORY_TEMPLATE_CONTRIBUTION_ENABLED = false;

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateServiceImpl.class);
    private final WorkflowTemplateRepository repository;
    private final StateMachineRepository stateMachineRepository;

    @Override
    public List<WorkflowTemplate> listApproved() {
        return repository.findByReviewStatus("approved");
    }

    @Override
    public List<WorkflowTemplate> listByStatus(String reviewStatus) {
        return repository.findByReviewStatus(reviewStatus);
    }

    @Override
    public Optional<WorkflowTemplate> getById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional
    public WorkflowTemplate create(WorkflowTemplate template) {
        if (template.getWorkflowJson() == null || template.getWorkflowJson().isEmpty()) {
            throw new BusinessException(400, "workflowJson is required")
                    .withHint("请提供工作流模板的 JSON 配置").withHintTarget("workflowJson");
        }
        return repository.save(template);
    }

    @Override
    @Transactional
    public WorkflowTemplate approve(Long id, Long reviewedBy, String reviewNotes) {
        WorkflowTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", "id", id.toString()));

        if (!"pending_review".equals(template.getReviewStatus())) {
            throw new BusinessException(409, "只有待审核状态的模板可以通过审核")
                    .withHint("请刷新模板列表查看最新审核状态");
        }

        template.setReviewStatus("approved");
        template.setReviewedBy(reviewedBy);
        template.setReviewedAt(LocalDateTime.now());
        template.setReviewNotes(reviewNotes);
        return repository.save(template);
    }

    @Override
    @Transactional
    public WorkflowTemplate reject(Long id, Long reviewedBy, String reviewNotes) {
        WorkflowTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowTemplate", "id", id.toString()));

        if (!"pending_review".equals(template.getReviewStatus())) {
            throw new BusinessException(409, "只有待审核状态的模板可以驳回")
                    .withHint("请刷新模板列表查看最新审核状态");
        }

        template.setReviewStatus("rejected");
        template.setReviewedBy(reviewedBy);
        template.setReviewedAt(LocalDateTime.now());
        template.setReviewNotes(reviewNotes);
        return repository.save(template);
    }

    /**
     * 只返回平台自编模板 (is_seed_data)。Steve 2026-10-24: 流程建议只来自公开资料和平台自编模板,
     * 平台不分析、不比对任何工厂的工艺流程 —— 工厂保存进来的模板不对其他工厂列出。
     */
    @Override
    public List<WorkflowTemplate> search(String keyword) {
        List<WorkflowTemplate> platform = repository.findByIsSeedDataTrue();
        if (keyword == null || keyword.trim().isEmpty()) {
            return platform;
        }
        String k = keyword.trim().toLowerCase();
        return platform.stream()
                .filter(t -> t.getTemplateName() != null && t.getTemplateName().toLowerCase().contains(k))
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public WorkflowTemplate extractFromStateMachine(String factoryId, String entityType,
                                                     String templateName, String description,
                                                     String industryTags) {
        // Steve 2026-10-24: 工厂的工艺流程不进入跨工厂模板库 (将来仅限书面同意「模板共建」的工厂)
        if (!CROSS_FACTORY_TEMPLATE_CONTRIBUTION_ENABLED) {
            throw new BusinessException(410, "工厂流程不再保存为平台通用模板")
                    .withHint("流程只在本厂使用；平台模板来自公开资料和平台自编模板");
        }
        StateMachine sm = stateMachineRepository
                .findByFactoryIdAndEntityTypeAndPublishStatus(factoryId, entityType, "published")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "StateMachine", "factoryId+entityType", factoryId + "/" + entityType));

        log.info("Extracting workflow template from StateMachine: factory={}, entity={}, version={}",
                factoryId, entityType, sm.getVersion());

        WorkflowTemplate template = WorkflowTemplate.builder()
                .templateName(templateName != null ? templateName : sm.getMachineName())
                .description(description != null ? description : sm.getMachineDescription())
                .industryTags(industryTags != null ? industryTags : "[]")
                .workflowJson(buildWorkflowJson(sm))
                .sourceCount(1)
                .reviewStatus("pending_review")
                .isSeedData(false)
                .build();

        return repository.save(template);
    }

    private String buildWorkflowJson(StateMachine sm) {
        return String.format(
                "{\"initialState\":\"%s\",\"states\":%s,\"transitions\":%s}",
                sm.getInitialState(),
                sm.getStatesJson(),
                sm.getTransitionsJson()
        );
    }
}
