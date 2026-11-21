package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.RoleDefinitionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tenant and document reference data for the trade JPA tests. */
public final class TradeJpaFixtures {
    private TradeJpaFixtures() {
    }

    public static Long createFactoryUser(EntityManager em, String factoryId) {
        Factory factory = new Factory();
        factory.setId(factoryId);
        factory.setName(factoryId);
        em.persist(factory);
        return createUser(em, factoryId, factoryId, null);
    }

    static Long createUser(EntityManager em, String factoryId, String username, String roleCode) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("unused-test-fixture");
        user.setRoleCode(roleCode);
        em.persist(user);
        return user.getId();
    }

    static ScopedHttp scopedHttp(UserRepository users, RoleDefinitionRepository roles, String factoryId, String role) {
        // The slice builds its business tables from JPA, so supply only the factory scope config row.
        JdbcTemplate config = mock(JdbcTemplate.class);
        when(config.queryForObject("SELECT data_scope FROM factory_role_data_scopes WHERE factory_id=? AND role_code=?",
                String.class, factoryId, role)).thenReturn("SELF");
        DataScopeResolver resolver = new DataScopeResolver(users, roles, config);
        return new ScopedHttp(new BusinessRowScope(resolver, config), new DataScopeRouteInterceptor(resolver, config),
                factoryId, role);
    }

    record ScopedHttp(BusinessRowScope scope, DataScopeRouteInterceptor interceptor, String factoryId, String role) {
        MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, long userId) {
            return request.requestAttr("userId", userId).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, factoryId, role));
        }
    }

    static void createCustomer(EntityManager em, String factoryId, Long userId, String id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFactoryId(factoryId);
        customer.setCode(id);
        customer.setCustomerCode(id);
        customer.setName(name);
        customer.setCreatedBy(userId);
        em.persist(customer);
    }

    public static void createSupplier(EntityManager em, String factoryId, Long userId, String id, String name) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFactoryId(factoryId);
        supplier.setCode(id);
        supplier.setSupplierCode(id);
        supplier.setName(name);
        supplier.setCreatedBy(userId);
        em.persist(supplier);
    }

    public static void createMaterial(EntityManager em, String factoryId, String id) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId(factoryId);
        material.setCode(id);
        material.setName("盐");
        material.setUnit("kg");
        em.persist(material);
    }
}
