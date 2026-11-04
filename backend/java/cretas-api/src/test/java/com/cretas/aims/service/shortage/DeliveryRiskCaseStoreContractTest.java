package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.SourceType;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.DeliveryRiskCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.shortage.DeliveryRiskService.OrderAssessment;
import com.cretas.aims.service.shortage.DeliveryRiskService.SignalView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 实例闭环: 有风险才建、结果写过的同一风险不重开、不指派不能关、风险未解除时核对不算达成。 */
class DeliveryRiskCaseStoreContractTest {

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseActionRepository actions = mock(ShortageCaseActionRepository.class);
    private final ShortageCaseOutcomeCheckRepository checks = mock(ShortageCaseOutcomeCheckRepository.class);
    private final DeliveryRiskCaseDetailRepository details = mock(DeliveryRiskCaseDetailRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DeliveryRiskCaseStore store = new DeliveryRiskCaseStore(cases, mock(ShortageCaseEventRepository.class), actions,
            checks, details, users, new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ActorRef actor = new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L);

    @BeforeEach
    void setUp() {
        when(cases.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(actions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(checks.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(details.findById(any())).thenReturn(Optional.empty());
        when(cases.findOpenForUpdate(any(), any(), any())).thenReturn(Optional.empty());
        when(cases.findFirstByFactoryIdAndSourceTypeAndSourceIdAndClosedAtIsNotNullOrderByClosedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private static OrderAssessment assessment(String level, String... signals) {
        return new OrderAssessment("SO1", "SO-1", "客户", "处理中", LocalDate.of(2026, 11, 10), level,
                java.util.Arrays.stream(signals).map(s -> new SignalView(s, s)).toList(), List.of("证据"), List.of(), List.of(),
                List.of(), null, 6L, 0, null, 0, LocalDateTime.now());
    }

    private ShortageCase open(Stage stage) {
        ShortageCase c = new ShortageCase();
        c.setId("C1");
        c.setFactoryId("F1");
        c.setSourceType(SourceType.DELIVERY_RISK);
        c.setSourceId("SO1");
        c.setStage(stage);
        c.setInputFingerprint("old");
        when(cases.findByIdAndFactoryId("C1", "F1")).thenReturn(Optional.of(c));
        when(cases.findOpenForUpdate("F1", SourceType.DELIVERY_RISK, "SO1")).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void riskCreatesCaseOfDeliveryRiskSource() {
        assertThat(store.record("F1", assessment("RISK", "OVERDUE"), "fp", actor)).isEqualTo(DeliveryRiskCaseStore.Written.CREATED);
        verify(cases).saveAndFlush(argThat(c -> c.getSourceType() == SourceType.DELIVERY_RISK && c.getStage() == Stage.DETECTED));
    }

    @Test
    void okAndUnknownOrdersCreateNothing() {
        assertThat(store.record("F1", assessment("OK"), "fp", actor)).isEqualTo(DeliveryRiskCaseStore.Written.NONE);
        assertThat(store.record("F1", assessment("UNKNOWN"), "fp", actor)).isEqualTo(DeliveryRiskCaseStore.Written.NONE);
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void closedCaseWithSameRiskIsNotReopened() {
        ShortageCase last = new ShortageCase();
        last.setInputFingerprint("fp");
        when(cases.findFirstByFactoryIdAndSourceTypeAndSourceIdAndClosedAtIsNotNullOrderByClosedAtDesc("F1", SourceType.DELIVERY_RISK, "SO1"))
                .thenReturn(Optional.of(last));
        assertThat(store.record("F1", assessment("RISK", "OVERDUE"), "fp", actor)).isEqualTo(DeliveryRiskCaseStore.Written.NONE);
        assertThat(store.record("F1", assessment("RISK", "OVERDUE", "NO_PLAN"), "other", actor)).isEqualTo(DeliveryRiskCaseStore.Written.CREATED);
    }

    @Test
    void shortageCaseIsNotReachableAsDeliveryRisk() {
        ShortageCase c = new ShortageCase();
        c.setSourceType(SourceType.SALES_ORDER);
        when(cases.findByIdAndFactoryId("S1", "F1")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> store.own("F1", "S1")).isInstanceOf(BusinessException.class);
    }

    @Test
    void assignRejectsUserOfAnotherFactory() {
        open(Stage.DETECTED);
        when(users.findByIdAndFactoryId(9L, "F1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> store.assign("F1", "C1", 9L, "催货", actor)).isInstanceOf(BusinessException.class);
    }

    @Test
    void assignMovesToExecutingAndRecordsTask() {
        ShortageCase c = open(Stage.DETECTED);
        User u = new User();
        u.setFullName("张三");
        when(users.findByIdAndFactoryId(9L, "F1")).thenReturn(Optional.of(u));
        store.assign("F1", "C1", 9L, "催货", actor);
        assertThat(c.getStage()).isEqualTo(Stage.EXECUTING);
        assertThat(c.getOwnerUserId()).isEqualTo(9L);
        verify(actions).saveAndFlush(argThat(a -> a.getActionType() == ShortageCaseAction.ActionType.HANDLING_TASK && "催货".equals(a.getReason())));
    }

    @Test
    void closeNeedsAnAssignedOwnerButCancelDoesNot() {
        ShortageCase c = open(Stage.DETECTED);
        assertThatThrownBy(() -> store.finish("F1", "C1", ShortageCaseAction.Decision.CLOSE, "已处理", assessment("OK"), actor))
                .isInstanceOf(BusinessException.class);
        store.finish("F1", "C1", ShortageCaseAction.Decision.CANCEL, "误报", assessment("OK"), actor);
        assertThat(c.getStage()).isEqualTo(Stage.CANCELLED);
    }

    @Test
    void closeWhileStillRiskyIsRecordedAsNotAchieved() {
        ShortageCase c = open(Stage.EXECUTING);
        ShortageCaseOutcomeCheck k = store.finish("F1", "C1", ShortageCaseAction.Decision.CLOSE, "已和客户改期",
                assessment("RISK", "OVERDUE"), actor);
        assertThat(c.getStage()).isEqualTo(Stage.CLOSED);
        assertThat(k.getVerdict()).isEqualTo(ShortageCaseOutcomeCheck.Verdict.NOT_ACHIEVED);
    }

    @Test
    void unknownAtCloseIsNotAchievedOrAchieved() {
        open(Stage.EXECUTING);
        ShortageCaseOutcomeCheck k = store.finish("F1", "C1", ShortageCaseAction.Decision.CLOSE, "结果", assessment("UNKNOWN"), actor);
        assertThat(k.getVerdict()).isEqualTo(ShortageCaseOutcomeCheck.Verdict.UNKNOWN);
    }

    @Test
    void resultTextIsRequired() {
        open(Stage.EXECUTING);
        assertThatThrownBy(() -> store.finish("F1", "C1", ShortageCaseAction.Decision.CLOSE, " ", assessment("OK"), actor))
                .isInstanceOf(BusinessException.class);
    }
}
