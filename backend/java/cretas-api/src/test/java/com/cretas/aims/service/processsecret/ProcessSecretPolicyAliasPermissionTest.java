package com.cretas.aims.service.processsecret;

import com.cretas.aims.controller.ProcessSecretController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalPackageRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专属别名 (Steve 2026-11-03 Q1 选 A) 只走现有的保密策略写入口, 权限与改策略完全相同:
 * 首次启用 = 工厂总监; 之后 = 该产品的保密负责人 + 当前版本号。别人改不了别名, 也看不到别名。
 */
class ProcessSecretPolicyAliasPermissionTest {

    static final String F = "F-A";
    static final String P = "P17";
    static final long OWNER = 10L;
    static final long SUPER = 11L;
    static final long STAFF = 20L;

    ProcessSecretPolicyRepository policies;
    ProcessSecretAccessService access;
    ProcessSecretPolicyService service;
    UserRepository users;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        users = mock(UserRepository.class);
        access = mock(ProcessSecretAccessService.class);
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        service = new ProcessSecretPolicyService(policies, mock(ProcessSecretGrantRepository.class),
                mock(ProcessSecretAccessEventRepository.class), users, access,
                mock(ProcessSecretExternalPackageRepository.class));
        user(OWNER, "factory_super_admin");
        user(SUPER, "factory_super_admin");
        user(STAFF, "operator");
        when(policies.save(any(ProcessSecretPolicy.class))).thenAnswer(i -> i.getArgument(0));
        when(policies.saveAndFlush(any(ProcessSecretPolicy.class))).thenAnswer(i -> i.getArgument(0));
    }

    void user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(F);
        u.setRoleCode(role);
        u.setIsActive(true);
        when(users.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(u));
    }

    static AuthenticatedActor actor(long id, String role) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, role);
    }

    ProcessSecretPolicy existing(String aliasesJson) {
        ProcessSecretPolicy p = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(P)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).policyVersion(4L)
                .createdBy(SUPER).aliases(aliasesJson).build();
        when(policies.findForUpdate(F, P)).thenReturn(Optional.of(p));
        when(policies.findByFactoryIdAndProductTypeId(F, P)).thenReturn(Optional.of(p));
        return p;
    }

    static void assertCode(int code, Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertEquals(code, e.getCode(), e.getMessage());
    }

    @Test
    void onlyTheOwnerCanChangeAliasesAndOnlyWithTheCurrentVersion() {
        ProcessSecretPolicy p = existing("[\"旧别名\"]");
        // another factory super admin / a staff member: 403, nothing written
        assertCode(403, () -> service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L, List.of("偷改的别名")));
        assertCode(403, () -> service.upsertPolicy(actor(STAFF, "operator"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L, List.of("偷改的别名")));
        assertCode(409, () -> service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 3L, List.of("过期版本")));
        assertThat(p.getAliases()).isEqualTo("[\"旧别名\"]");

        var view = service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L, List.of(" 五香虎皮猪肘 ", "五香虎皮猪肘", "", "SX-PORK"));
        assertThat(p.getAliases()).isEqualTo("[\"五香虎皮猪肘\",\"SX-PORK\"]");     // trimmed, de-duplicated, blanks dropped
        assertThat(p.getPolicyVersion()).isEqualTo(5L);
        assertThat(view.aliases()).containsExactly("五香虎皮猪肘", "SX-PORK");
        verify(access).audit(eq(F), eq(P), any(), any(), eq("ALLOW"), argThat(s -> s.contains("aliases 1->2")
                && !s.contains("五香虎皮猪肘")), any());       // count only, never the alias text
        verify(access).invalidate(F);
    }

    @Test
    void omittingAliasesKeepsThemAndAnEmptyListClearsThem() {
        ProcessSecretPolicy p = existing("[\"五香虎皮猪肘\"]");
        service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L);                 // old signature: unchanged
        assertThat(p.getAliases()).isEqualTo("[\"五香虎皮猪肘\"]");
        service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 5L, List.of());
        assertThat(p.getAliases()).isNull();
    }

    @Test
    void firstEnableWithAliasesNeedsTheFactorySuperAdmin() {
        when(policies.findForUpdate(F, P)).thenReturn(Optional.empty());
        assertCode(403, () -> service.upsertPolicy(actor(STAFF, "operator"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, null, List.of("五香虎皮猪肘")));
        service.upsertPolicy(actor(SUPER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, null, List.of("五香虎皮猪肘"));
        verify(policies).saveAndFlush(argThat(p -> "[\"五香虎皮猪肘\"]".equals(p.getAliases())));
    }

    @Test
    void aliasesAreHiddenFromEveryoneButTheOwner() {
        ProcessSecretPolicy p = existing("[\"五香虎皮猪肘\"]");
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(p));
        assertThat(service.listPolicies(actor(OWNER, "factory_super_admin"), F).get(0).aliases())
                .containsExactly("五香虎皮猪肘");
        assertThat(service.listPolicies(actor(SUPER, "factory_super_admin"), F).get(0).aliases()).isNull();
    }

    @Test
    void tooManyOrTooLongAliasesAreRejected() {
        existing(null);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < ProcessSecretPolicyService.MAX_ALIASES + 1; i++) {
            many.add("别名" + i);
        }
        assertCode(400, () -> service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L, many));
        assertCode(400, () -> service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L,
                List.of(String.join("", Collections.nCopies(ProcessSecretPolicyService.MAX_ALIAS_LENGTH + 1, "长")))));
    }

    @Test
    void aliasesTheMatcherWouldSilentlyDropAreRejected() {
        // second review: a category-word / pure-numeric / one-character alias used to return 200 and do nothing
        ProcessSecretPolicy p = existing("[\"旧别名\"]");
        for (String bad : List.of("鸭脖", "牛腱", "20251103", "肘")) {
            assertCode(400, () -> service.upsertPolicy(actor(OWNER, "factory_super_admin"), F, P,
                    ProcessSecretStorageMode.CLOUD_RESTRICTED, OWNER, 4L, List.of("五香虎皮猪肘", bad)));
        }
        assertThat(p.getAliases()).isEqualTo("[\"旧别名\"]");
        assertThat(p.getPolicyVersion()).isEqualTo(4L);
    }

    @Test
    void theOnlyHttpWayToWriteAliasesIsThePolicyUpsert() {
        // no new write endpoint: the controller's only methods taking aliases are the existing PUT /policies/{id}
        int carriers = 0;
        for (Method m : ProcessSecretController.class.getDeclaredMethods()) {
            for (Class<?> t : m.getParameterTypes()) {
                if (t == ProcessSecretController.PolicyRequest.class) {
                    carriers++;
                    assertThat(m.getName()).isEqualTo("upsertPolicy");
                    assertThat(m.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class).value())
                            .containsExactly("/policies/{productTypeId}");
                }
            }
        }
        assertThat(carriers).isEqualTo(1);
    }
}
