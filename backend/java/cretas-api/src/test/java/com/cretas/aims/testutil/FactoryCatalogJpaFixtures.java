package com.cretas.aims.testutil;

import com.cretas.aims.entity.*;
import jakarta.persistence.EntityManager;
import java.util.List;

/** Constraint-valid synthetic references for catalog repository gates. */
public final class FactoryCatalogJpaFixtures {
    private FactoryCatalogJpaFixtures() { }
    public record Owners(Long first, Long second) { }
    public static Owners seed(EntityManager em) {
        for (String id : List.of("F006", "F007")) {
            var factory = new Factory(); factory.setId(id); factory.setName("Catalog test " + id);
            em.persist(factory);
        }
        var a = user(em, "catalog-owner-a", "F006");
        var b = user(em, "catalog-owner-b", "F007");
        for (String id : List.of("F006", "F007")) {
            Long owner = id.equals("F006") ? a.getId() : b.getId();
            var supplier = new Supplier();
            supplier.setId("SUP-" + id); supplier.setFactoryId(id);
            supplier.setCode("SUP-" + id); supplier.setSupplierCode("SUP-" + id);
            supplier.setName("Catalog supplier " + id); supplier.setCreatedBy(owner);
            supplier.setVersion(0L); em.persist(supplier);
            var customer = new Customer();
            customer.setId("CUSTOMER-" + id); customer.setFactoryId(id);
            customer.setCode("CUSTOMER-" + id); customer.setCustomerCode("CUSTOMER-" + id);
            customer.setName("Catalog customer " + id); customer.setCreatedBy(owner);
            em.persist(customer);
        }
        var sku = new ProductType(); sku.setId("SKU-A"); sku.setFactoryId("F006");
        sku.setCode("CATALOG-SKU-A"); sku.setName("Catalog product"); sku.setUnit("kg");
        sku.setCreatedBy(a.getId()); em.persist(sku);
        em.flush(); return new Owners(a.getId(), b.getId());
    }
    private static User user(EntityManager em, String name, String factoryId) {
        var user = new User(); user.setFactoryId(factoryId); user.setUsername(name);
        user.setPasswordHash("synthetic-test-only"); em.persist(user); em.flush(); return user;
    }
}
