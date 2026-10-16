package com.cretas.aims.service.impl;

import com.cretas.aims.aspect.DataScopeAspect;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.testutil.MaterialBatchInventoryScopeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialBatchInventorySiblingDataScopeContractTest {
    private final com.cretas.aims.repository.UserRepository scopeUsers = mock(com.cretas.aims.repository.UserRepository.class);
    private final com.cretas.aims.repository.RoleDefinitionRepository scopeRoles = mock(com.cretas.aims.repository.RoleDefinitionRepository.class);
    private DataScopeResolver resolver;
    private com.cretas.aims.entity.User actor;
    private com.cretas.aims.entity.config.RoleDefinition definition;
    private MaterialBatchServiceImpl target;
    private MaterialBatchRepository repository;
    private MaterialBatchService service;
    private List<MaterialBatch> rows;

    @BeforeEach
    void setUp() {
        actor = user(101L, "F006", "RAW", null);
        when(scopeUsers.findById(101L)).thenReturn(java.util.Optional.of(actor));
        definition = new com.cretas.aims.entity.config.RoleDefinition();
        definition.setRoleCode("warehouse_worker"); definition.setDataScope("SELF");
        when(scopeRoles.findByRoleCode(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.of(definition));
        List<com.cretas.aims.entity.User> hierarchy = List.of(actor, user(202L, "F006", "RAW", 101L),
                user(303L, "F006", "OTHER", null), user(404L, "F007", "RAW", 101L));
        when(scopeUsers.findSubordinateIdsByReportsTo(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> hierarchy.stream().filter(u -> i.getArgument(0).equals(u.getFactoryId())
                        && i.getArgument(1).equals(u.getReportsTo())).map(com.cretas.aims.entity.User::getId).toList());
        when(scopeUsers.findUserIdsByDepartment(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> hierarchy.stream().filter(u -> i.getArgument(0).equals(u.getFactoryId())
                        && i.getArgument(1).equals(u.getDepartment())).map(com.cretas.aims.entity.User::getId).toList());
        resolver = new DataScopeResolver(scopeUsers, scopeRoles);
        repository = mock(MaterialBatchRepository.class);
        rows = List.of(
                MaterialBatchInventoryScopeFixture.batch("A", "F006", 101L,
                        MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(1)),
                MaterialBatchInventoryScopeFixture.batch("B", "F006", 202L,
                        MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(5)),
                MaterialBatchInventoryScopeFixture.batch("C", "F006", 303L,
                        MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(5)),
                MaterialBatchInventoryScopeFixture.batch("WIP-A", "F006", 101L,
                        MaterialBatchStatus.PRODUCING_RESERVED, null),
                MaterialBatchInventoryScopeFixture.batch("EXPIRED-A", "F006", 101L,
                        MaterialBatchStatus.AVAILABLE, LocalDate.now().minusDays(1)),
                MaterialBatchInventoryScopeFixture.batch("FOREIGN", "F007", 101L,
                        MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(1)));
        MaterialBatchInventoryScopeFixture.stubQueries(repository, rows);
        target = MaterialBatchInventoryScopeFixture.service(repository, resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "userRepository", scopeUsers);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new DataScopeAspect(resolver));
        service = (MaterialBatchService) proxyFactory.getProxy();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "101");
        request.setAttribute(com.cretas.aims.security.AuthenticatedActor.REQUEST_ATTRIBUTE,
                new com.cretas.aims.security.AuthenticatedActor(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER,101L,"F006","warehouse_worker"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
    }

    @Test
    void siblingReadsUseFactoryAndCreatorScopeAcrossStatusWipExpiryAndExpired() {
        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE))
                .extracting("id").containsExactly("A", "EXPIRED-A");
        assertThat(service.getWipBatches("F006")).extracting("id").containsExactly("WIP-A");
        assertThat(service.getExpiringBatches("F006", 7)).extracting("id").containsExactly("A");
        assertThat(service.getExpiredBatches("F006")).extracting("id").containsExactly("EXPIRED-A");
    }

    @Test
    void foreignFactoryContextIsRejectedBeforeRepositoryRead() {
        actor.setFactoryId("F007");

        assertThatThrownBy(() -> service.getWipBatches("F006"))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @Test
    void customScopeDoesNotFallThroughToFactoryWideRows() {
        definition.setDataScope("CUSTOM");

        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE))
                .isEmpty();
        assertThat(service.getWipBatches("F006")).isEmpty();
        assertThat(service.getExpiringBatches("F006", 7)).isEmpty();
        assertThat(service.getExpiredBatches("F006")).isEmpty();
    }

    @Test
    void unresolvedOrFactorylessContextNeverFallsBackToAllRows() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> service.getWipBatches("F006"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "101");
        request.setAttribute(com.cretas.aims.security.AuthenticatedActor.REQUEST_ATTRIBUTE,
                new com.cretas.aims.security.AuthenticatedActor(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER,101L,"F006","warehouse_worker"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        // A real resolver never emits a null scope; exercise that defensive branch
        // directly with a synthetic context and do not count it as resolver evidence.
        DataScopeContext.push(MaterialBatchInventoryScopeFixture.scope(null, 101L, "F006"));
        try {
            assertThatThrownBy(() -> target.getWipBatches("F006"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { DataScopeContext.pop(); }

        actor.setFactoryId(null);

        assertThatThrownBy(() -> service.getWipBatches("F006"))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DataScope.class)
    void actualResolverRoleAndHierarchyDriveAllSiblingPredicates(DataScope scope) {
        definition.setDataScope(scope.name());
        String[] statusIds = switch (scope) {
            case ALL -> new String[]{"A", "B", "C", "EXPIRED-A"};
            case SELF_AND_BELOW, DEPT_AND_BELOW -> new String[]{"A", "B", "EXPIRED-A"};
            case SELF -> new String[]{"A", "EXPIRED-A"};
            case CUSTOM -> new String[]{};
        };
        String[] expiringIds = switch (scope) {
            case ALL -> new String[]{"A", "B", "C"};
            case SELF_AND_BELOW, DEPT_AND_BELOW -> new String[]{"A", "B"};
            case SELF -> new String[]{"A"};
            case CUSTOM -> new String[]{};
        };
        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE))
                .extracting("id").containsExactlyInAnyOrder(statusIds);
        assertThat(service.getExpiringBatches("F006", 7)).extracting("id").containsExactlyInAnyOrder(expiringIds);
        assertThat(service.getWipBatches("F006")).extracting("id")
                .containsExactlyInAnyOrder(scope == DataScope.CUSTOM ? new String[]{} : new String[]{"WIP-A"});
        assertThat(service.getExpiredBatches("F006")).extracting("id")
                .containsExactlyInAnyOrder(scope == DataScope.CUSTOM ? new String[]{} : new String[]{"EXPIRED-A"});
        assertThat(DataScopeContext.current()).isNull();
    }

    @Test
    void actualBossScopePreservesOrdinaryFactoryWidePositive() {
        actor.setRoleCode("factory_super_admin"); definition.setRoleCode("factory_super_admin"); definition.setDataScope("ALL");
        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE))
                .extracting("id").containsExactlyInAnyOrder("A", "B", "C", "EXPIRED-A");
        assertThat(DataScopeContext.current()).isNull();
    }

    private static com.cretas.aims.entity.User user(Long id, String factory, String department, Long parent) {
        com.cretas.aims.entity.User u = new com.cretas.aims.entity.User();
        u.setId(id); u.setFactoryId(factory); u.setRoleCode("warehouse_worker"); u.setIsActive(true);
        u.setDepartment(department); u.setReportsTo(parent); return u;
    }

}
