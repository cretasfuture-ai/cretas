package com.cretas.aims.service.supplierreceivingdraft;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import com.cretas.aims.repository.supplier.SupplierAdmissionRuleConfigRepository;
import com.cretas.aims.repository.supplierreceiving.*;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.supplierreceivingdraft.SupplierReceivingPolicyDraftModels.DraftRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 平台管理员做对话建厂是允许的操作人；工厂账号必须是请求所属工厂的有效成员。 */
class SupplierReceivingOperatorAuthorizationTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SupplierReceivingPolicyDraftCommand command = new SupplierReceivingPolicyDraftCommand(
            mock(SupplierReceivingPolicyDraftRepository.class), mock(SupplierReceivingPolicyDraftItemRepository.class),
            mock(SupplierReceivingPolicyDraftQualificationRepository.class), mock(FactoryRepository.class), users,
            mock(SupplierRepository.class), mock(SupplierMaterialRepository.class), mock(SupplierQualificationRepository.class),
            mock(RawMaterialTypeRepository.class), mock(MaterialTypeDraftRepository.class),
            mock(SupplierAdmissionRuleConfigRepository.class));

    private static final Set<String> PERMS = Set.of(SupplierReceivingPolicyDraftCommand.REQUIRED_PERMISSION);

    private void authorize(AuthenticatedActorType type, long id, String factory, String requestFactory) throws Throwable {
        var request = new DraftRequest(requestFactory, "bp", 1, "a".repeat(64), id, "k", null, List.of());
        var operator = new AuthenticatedOperator(new ActorRef(type, id), factory, PERMS);
        Method method = SupplierReceivingPolicyDraftCommand.class.getDeclaredMethod("authorize", DraftRequest.class, AuthenticatedOperator.class);
        method.setAccessible(true);
        try { method.invoke(command, request, operator); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @Test
    void platformAdminIsAnAllowedOperatorWithoutFactoryMembership() {
        when(users.findByIdAndFactoryId(anyLong(), anyString())).thenReturn(Optional.empty());
        assertThatCode(() -> authorize(AuthenticatedActorType.PLATFORM_ADMIN, 7, "F1", "F1")).doesNotThrowAnyException();
    }

    @Test
    void activeMemberOfTheRequestFactoryIsAllowed() {
        var user = new User();
        user.setIsActive(true);
        when(users.findByIdAndFactoryId(5L, "F1")).thenReturn(Optional.of(user));
        assertThatCode(() -> authorize(AuthenticatedActorType.FACTORY_USER, 5, "F1", "F1")).doesNotThrowAnyException();
    }

    @Test
    void factoryUserOfAnotherFactoryIsRefused() {
        when(users.findByIdAndFactoryId(anyLong(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authorize(AuthenticatedActorType.FACTORY_USER, 5, "F2", "F1"))
                .isInstanceOf(BusinessException.class);
        // 同工厂声明但并非成员（例如被停用/不存在）也拒绝，且是成员资格这道闸拒的
        assertThatThrownBy(() -> authorize(AuthenticatedActorType.FACTORY_USER, 5, "F1", "F1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不是当前工厂有效成员");
    }

    @Test
    void platformAdminStillNeedsThePermissionAndTheRightFactoryScope() throws Exception {
        var request = new DraftRequest("F1", "bp", 1, "a".repeat(64), 7, "k", null, List.of());
        Method method = SupplierReceivingPolicyDraftCommand.class.getDeclaredMethod("authorize", DraftRequest.class, AuthenticatedOperator.class);
        method.setAccessible(true);
        var noPermission = new AuthenticatedOperator(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7), "F1", Set.of());
        assertThatThrownBy(() -> { try { method.invoke(command, request, noPermission); } catch (InvocationTargetException e) { throw e.getCause(); } })
                .hasMessageContaining("缺少来料策略模板权限");
        var wrongFactory = new AuthenticatedOperator(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7), "F9", PERMS);
        assertThatThrownBy(() -> { try { method.invoke(command, request, wrongFactory); } catch (InvocationTargetException e) { throw e.getCause(); } })
                .hasMessageContaining("身份与工厂范围不一致");
    }

    private final MaterialTypeDraftRepository drafts = mock(MaterialTypeDraftRepository.class);
    private final RawMaterialTypeRepository live = mock(RawMaterialTypeRepository.class);
    private final SupplierReceivingPolicyDraftCommand inferring = new SupplierReceivingPolicyDraftCommand(
            mock(SupplierReceivingPolicyDraftRepository.class), mock(SupplierReceivingPolicyDraftItemRepository.class),
            mock(SupplierReceivingPolicyDraftQualificationRepository.class), mock(FactoryRepository.class), users,
            mock(SupplierRepository.class), mock(SupplierMaterialRepository.class), mock(SupplierQualificationRepository.class),
            live, drafts, mock(SupplierAdmissionRuleConfigRepository.class));

    private SupplierReceivingPolicyDraftModels.RequestedPolicy infer(String materialRefId) throws Exception {
        var policy = new SupplierReceivingPolicyDraftModels.RequestedPolicy("sup", null, materialRefId, null, null, null,
                1, 1, true, true, "basis", List.of());
        Method method = SupplierReceivingPolicyDraftCommand.class.getDeclaredMethod("withInferredMaterialRefType",
                String.class, SupplierReceivingPolicyDraftModels.RequestedPolicy.class);
        method.setAccessible(true);
        return (SupplierReceivingPolicyDraftModels.RequestedPolicy) method.invoke(inferring, "F1", policy);
    }

    @Test
    void materialReferenceTypeIsDerivedFromTheChosenMaterial() throws Exception {
        when(drafts.findByIdAndFactoryId("draft-1", "F1")).thenReturn(Optional.of(new com.cretas.aims.entity.material.MaterialTypeDraft()));
        when(live.findByIdAndFactoryId("rmt-1", "F1")).thenReturn(Optional.of(new com.cretas.aims.entity.RawMaterialType()));
        assertThat(infer("draft-1").materialRefType()).isEqualTo(SupplierReceivingPolicyDraftModels.MaterialRefType.MATERIAL_DRAFT);
        assertThat(infer("rmt-1").materialRefType()).isEqualTo(SupplierReceivingPolicyDraftModels.MaterialRefType.LIVE_MATERIAL);
        assertThat(infer("unknown").materialRefType()).as("认不出就保持为空，由校验说清楚").isNull();
        assertThat(infer("").materialRefType()).isNull();
    }
}
