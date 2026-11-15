package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.entity.inventory.PurchaseRequisitionApprovalRecord;
import com.cretas.aims.entity.inventory.PurchaseRequisitionApprovalRecord.Action;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseRequisitionRepository;
import com.cretas.aims.service.inventory.impl.PurchaseRequisitionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-5 请购单被驳回后允许改量重提, 并保留多轮审批历史 (Steve 2026-11-15 裁决)。
 * 真实 Hibernate / H2 (PG 兼容), 真实服务实现; 只有「转 PO」依赖的 PurchaseService 是桩 (本类不转单)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import(PurchaseRequisitionServiceImpl.class)
@DisplayName("请购单: 驳回后改量重提 + 多轮审批历史")
class PurchaseRequisitionResubmitContractTest {

    private static final String F = "F-PRRS-1";
    private static final String F_OTHER = "F-PRRS-2";
    private static final Long REQUESTER = 11L;
    private static final Long APPROVER = 22L;
    private static final Long STRANGER = 33L;

    @Autowired PurchaseRequisitionServiceImpl service;
    @Autowired PurchaseRequisitionRepository repo;
    @MockBean PurchaseService purchaseService;

    private static Map<String, Object> line(String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("materialTypeId", "MT-1");
        m.put("materialName", "面粉");
        m.put("quantity", qty);
        m.put("unit", "kg");
        return m;
    }

    private static CreatePurchaseRequisitionRequest req(String qty) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(line(qty));
        return new CreatePurchaseRequisitionRequest(null, items, null, "补货", null);
    }

    /** 直接落库建草稿: createRequisition 的单号生成用 date() 函数, H2 不支持, 与本类要测的状态机无关。 */
    private int seqNo = 0;

    private PurchaseRequisition newDraft(String qty) {
        PurchaseRequisition pr = new PurchaseRequisition();
        pr.setFactoryId(F);
        pr.setRequisitionNumber("PR-TEST-" + (++seqNo));
        pr.setRequesterId(REQUESTER);
        pr.setRequestedItems(new ArrayList<>(req(qty).getRequestedItems()));
        pr.setStatus(PurchaseRequisitionStatus.DRAFT);
        return repo.saveAndFlush(pr);
    }

    private PurchaseRequisition newPending() {
        PurchaseRequisition pr = newDraft("10");
        return service.submitRequisition(F, pr.getId(), REQUESTER);
    }

    @Test
    @DisplayName("驳回→改量→重提→再驳回→再重提→通过: 历史共 3 轮 8 条, 顺序/原因/每轮快照都在, 谁也没被覆盖")
    void rejectEditResubmitRejectResubmitApprove() {
        PurchaseRequisition pr = newDraft("10");
        String id = pr.getId();

        service.submitRequisition(F, id, REQUESTER);
        service.rejectRequisition(F, id, APPROVER, "量太大");
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo(PurchaseRequisitionStatus.REJECTED);

        service.reopenRequisition(F, id, REQUESTER);
        service.updateDraft(F, id, REQUESTER, req("6"));
        service.submitRequisition(F, id, REQUESTER);
        service.rejectRequisition(F, id, APPROVER, "还是偏多");

        service.reopenRequisition(F, id, REQUESTER);
        service.updateDraft(F, id, REQUESTER, req("5"));
        service.submitRequisition(F, id, REQUESTER);
        PurchaseRequisition approved = service.approveRequisition(F, id, APPROVER);

        assertThat(approved.getStatus()).isEqualTo(PurchaseRequisitionStatus.APPROVED);
        assertThat(approved.getSubmitRound()).isEqualTo(3);
        assertThat(approved.getRejectionReason()).as("重提后上一轮的驳回结论不再挂在单据上").isNull();
        assertThat(approved.getRequestedItems().get(0).get("quantity")).isEqualTo("5");

        List<PurchaseRequisitionApprovalRecord> h = service.getApprovalHistory(F, id);
        assertThat(h).extracting(PurchaseRequisitionApprovalRecord::getAction).containsExactly(
                Action.SUBMITTED, Action.REJECTED, Action.REOPENED,
                Action.SUBMITTED, Action.REJECTED, Action.REOPENED,
                Action.SUBMITTED, Action.APPROVED);
        assertThat(h).extracting(PurchaseRequisitionApprovalRecord::getRound)
                .containsExactly(1, 1, 1, 2, 2, 2, 3, 3);
        assertThat(h).extracting(PurchaseRequisitionApprovalRecord::getSeq)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(h.get(1).getReason()).isEqualTo("量太大");
        assertThat(h.get(4).getReason()).isEqualTo("还是偏多");
        assertThat(h.get(1).getActorId()).isEqualTo(APPROVER);
        // 每轮「提交了什么」: 快照是当时的内容, 没被之后的改量改掉
        assertThat(h.get(0).getItemsSnapshot().get(0).get("quantity")).isEqualTo("10");
        assertThat(h.get(3).getItemsSnapshot().get(0).get("quantity")).isEqualTo("6");
        assertThat(h.get(6).getItemsSnapshot().get(0).get("quantity")).isEqualTo("5");
    }

    @Test
    @DisplayName("越权: 非请购人不能重开/改草稿/重新提交; 被拒后单据状态与历史都没动")
    void onlyRequesterMayReopenEditResubmit() {
        PurchaseRequisition pr = newPending();
        service.rejectRequisition(F, pr.getId(), APPROVER, "不行");
        long before = service.getApprovalHistory(F, pr.getId()).size();

        assertThatThrownBy(() -> service.reopenRequisition(F, pr.getId(), STRANGER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(repo.findById(pr.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseRequisitionStatus.REJECTED);

        service.reopenRequisition(F, pr.getId(), REQUESTER);
        assertThatThrownBy(() -> service.updateDraft(F, pr.getId(), STRANGER, req("1")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> service.submitRequisition(F, pr.getId(), STRANGER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(repo.findById(pr.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseRequisitionStatus.DRAFT);
        assertThat(service.getApprovalHistory(F, pr.getId())).hasSize((int) before + 1); // 仅多出本人那条 REOPENED
    }

    @Test
    @DisplayName("只有 REJECTED 能重开: 待审批 / 已通过 / 已转 PO / 草稿 都 400, 已转 PO 不能回退")
    void onlyRejectedCanBeReopened() {
        PurchaseRequisition pending = newPending();
        assertThatThrownBy(() -> service.reopenRequisition(F, pending.getId(), REQUESTER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

        PurchaseRequisition approved = newPending();
        service.approveRequisition(F, approved.getId(), APPROVER);
        assertThatThrownBy(() -> service.reopenRequisition(F, approved.getId(), REQUESTER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

        PurchaseRequisition converted = newDraft("3");
        converted.setStatus(PurchaseRequisitionStatus.CONVERTED_TO_PO);
        converted.setConvertedPoId("PO-1");
        repo.saveAndFlush(converted);
        assertThatThrownBy(() -> service.reopenRequisition(F, converted.getId(), REQUESTER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThat(repo.findById(converted.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseRequisitionStatus.CONVERTED_TO_PO);
        assertThat(repo.findById(converted.getId()).orElseThrow().getConvertedPoId()).isEqualTo("PO-1");

        PurchaseRequisition draft = newDraft("3");
        assertThatThrownBy(() -> service.reopenRequisition(F, draft.getId(), REQUESTER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
    }

    @Test
    @DisplayName("草稿以外不能改内容(含被驳回未重开); 空行项目被拒; 重开前后的行项目不会被悄悄改写")
    void updateOnlyInDraftAndNeverEmpty() {
        PurchaseRequisition pr = newPending();
        assertThatThrownBy(() -> service.updateDraft(F, pr.getId(), REQUESTER, req("1")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        service.rejectRequisition(F, pr.getId(), APPROVER, "改");
        assertThatThrownBy(() -> service.updateDraft(F, pr.getId(), REQUESTER, req("1")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        service.reopenRequisition(F, pr.getId(), REQUESTER);
        assertThatThrownBy(() -> service.updateDraft(F, pr.getId(), REQUESTER,
                new CreatePurchaseRequisitionRequest(null, new ArrayList<>(), null, null, null)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThat(repo.findById(pr.getId()).orElseThrow().getRequestedItems().get(0).get("quantity"))
                .isEqualTo("10");
    }

    @Test
    @DisplayName("租户: 他厂不能重开/改/查本厂请购单的审批历史")
    void tenantIsolation() {
        PurchaseRequisition pr = newPending();
        service.rejectRequisition(F, pr.getId(), APPROVER, "x");
        assertThatThrownBy(() -> service.reopenRequisition(F_OTHER, pr.getId(), REQUESTER))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> service.getApprovalHistory(F_OTHER, pr.getId()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        assertThat(repo.findById(pr.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseRequisitionStatus.REJECTED);
    }

    @Test
    @DisplayName("存量单据(submit_round=0, 机制上线前已提交)首次驳回按第 1 轮记入, 之后重提是第 2 轮")
    void legacyRowCountsAsRoundOne() {
        PurchaseRequisition pr = newDraft("10");
        pr.setStatus(PurchaseRequisitionStatus.PENDING_APPROVAL); // 老数据: 已待审批却无轮次/流水
        pr.setSubmitRound(0);
        repo.saveAndFlush(pr);

        service.rejectRequisition(F, pr.getId(), APPROVER, "老单驳回");
        service.reopenRequisition(F, pr.getId(), REQUESTER);
        service.submitRequisition(F, pr.getId(), REQUESTER);

        List<PurchaseRequisitionApprovalRecord> h = service.getApprovalHistory(F, pr.getId());
        assertThat(h).extracting(PurchaseRequisitionApprovalRecord::getAction)
                .containsExactly(Action.REJECTED, Action.REOPENED, Action.SUBMITTED);
        assertThat(h).extracting(PurchaseRequisitionApprovalRecord::getRound).containsExactly(1, 1, 2);
        assertThat(BigDecimal.valueOf(repo.findById(pr.getId()).orElseThrow().getSubmitRound())).isEqualByComparingTo("2");
    }
}
