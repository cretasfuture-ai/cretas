package com.cretas.aims.blueprint.platform;

import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.repository.config.FactoryTypeBlueprintRepository;
import com.cretas.aims.repository.conversation.FactoryAssemblyConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Instantiates real Hibernate and every Repository proxy, including all new draft queries. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PlatformAssemblyRepositoryQueryValidationTest {
    @Autowired FactoryTypeBlueprintRepository blueprints;
    @Autowired FactoryAssemblyConversationSessionRepository conversations;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Test void assemblySoftDeleteSqlBindsTheMappedIdAndOptionalVersion() throws Exception {
        java.util.Set<String> owned;
        try (var stream=getClass().getResourceAsStream("/platform-assembly-soft-delete-entities.txt")) {
            assertThat(stream).isNotNull();
            owned=new java.io.BufferedReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8))
                    .lines().filter(line->!line.isBlank()&&!line.startsWith("#")).collect(java.util.stream.Collectors.toSet());
        }
        int checked=0;
        for (var entity : entityManager.getMetamodel().getEntities()) {
            var type=entity.getJavaType();
            if (!owned.contains(type.getName())) continue;
            var sql=type.getDeclaredAnnotation(org.hibernate.annotations.SQLDelete.class);
            assertThat(sql).as(type.getName()).isNotNull();
            assertThat(sql.check()).isEqualTo(org.hibernate.annotations.ResultCheckStyle.COUNT);
            var table=type.getAnnotation(jakarta.persistence.Table.class);
            var fields=java.util.Arrays.asList(type.getDeclaredFields());
            var id=fields.stream().filter(f->f.isAnnotationPresent(jakarta.persistence.Id.class)).findFirst().orElseThrow();
            var version=fields.stream().filter(f->f.isAnnotationPresent(jakarta.persistence.Version.class)).findFirst();
            var pk=id.getAnnotation(jakarta.persistence.Column.class);
            String column=pk==null||pk.name().isBlank()?id.getName():pk.name();
            String expected="UPDATE "+table.name()+" SET deleted_at = NOW()";
            String versionColumn=version.map(f->f.getAnnotation(jakarta.persistence.Column.class).name()).orElse(null);
            if (versionColumn!=null) expected+=", "+versionColumn+" = "+versionColumn+" + 1";
            expected+=" WHERE "+column+" = ?";
            if (versionColumn!=null) expected+=" AND "+versionColumn+" = ?";
            assertThat(sql.sql()).as(type.getName()).isEqualTo(expected);
            checked++;
        }
        assertThat(owned).hasSize(70);
        assertThat(checked).isEqualTo(owned.size());
    }

    private com.cretas.aims.entity.blueprintactivation.FactoryActivationExecution executionInput() {
        var row = new com.cretas.aims.entity.blueprintactivation.FactoryActivationExecution();
        row.setId(UUID.randomUUID().toString()); row.setFactoryId("ASSEMBLY-SOFT-DELETE");
        row.setIdempotencyKey(UUID.randomUUID().toString()); row.setRequestDigest("a".repeat(64));
        row.setStatus("CLAIMED"); row.setSuccessfulCount(0); row.setFailedCount(0);
        row.setBlockedCount(0); row.setBusinessWriteCount(0);
        row.setExecutedActor(new com.cretas.aims.security.ActorRef(AuthenticatedActorType.PLATFORM_ADMIN,62L));
        return row;
    }

    private com.cretas.aims.entity.blueprintactivation.FactoryActivationExecution execution() {
        var row=executionInput(); entityManager.persist(row); entityManager.flush(); return row;
    }

    @Test void versionedAndUnversionedAssemblyDeletesRetainPhysicalRowsAndHideThem() {
        var parent = execution();
        var child = new com.cretas.aims.entity.blueprintactivation.FactoryActivationExecutionModule();
        child.setId(UUID.randomUUID().toString()); child.setExecutionId(parent.getId());
        child.setFactoryId(parent.getFactoryId()); child.setOrdinal(0); child.setCapabilityId("workflow");
        child.setStatus("BLOCKED_DEPENDENCY"); child.setBusinessWriteCount(0);
        child.setFailureCode("BOM_NOT_PUBLISHED");
        entityManager.persist(child); entityManager.flush();
        entityManager.remove(child); entityManager.remove(parent); entityManager.flush(); entityManager.clear();
        assertThat(entityManager.find(parent.getClass(),parent.getId())).isNull();
        assertThat(entityManager.find(child.getClass(),child.getId())).isNull();
        assertThat(((Number)entityManager.createNativeQuery("select count(*) from factory_activation_executions where id=:id and deleted_at is not null")
                .setParameter("id",parent.getId()).getSingleResult()).intValue()).isEqualTo(1);
        assertThat(((Number)entityManager.createNativeQuery("select count(*) from factory_activation_execution_modules where id=:id and deleted_at is not null")
                .setParameter("id",child.getId()).getSingleResult()).intValue()).isEqualTo(1);
    }

    @Test void staleVersionCannotSoftDeleteAnExecution() {
        var row=execution();
        entityManager.createNativeQuery("update factory_activation_executions set lock_version=lock_version+1 where id=:id")
                .setParameter("id",row.getId()).executeUpdate();
        entityManager.remove(row);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->entityManager.flush())
                .isInstanceOf(jakarta.persistence.OptimisticLockException.class);
    }

    @Test void committedSoftDeleteRejectsAStaleUpdateAndCannotResurrectTheRow() {
        var a=entityManagerFactory.createEntityManager();
        var b=entityManagerFactory.createEntityManager();
        var input=executionInput();
        try {
            a.getTransaction().begin(); a.persist(input); a.getTransaction().commit(); a.clear();
            a.getTransaction().begin();
            var stale=a.find(input.getClass(),input.getId());
            b.getTransaction().begin();
            b.remove(b.find(input.getClass(),input.getId())); b.getTransaction().commit();
            stale.setRecoverySummary("stale writer must fail");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->a.flush())
                    .isInstanceOf(jakarta.persistence.OptimisticLockException.class);
            a.getTransaction().rollback();
            Object[] tombstone=(Object[])b.createNativeQuery("select deleted_at,lock_version,recovery_summary from factory_activation_executions where id=:id and factory_id=:factory")
                    .setParameter("id",input.getId()).setParameter("factory",input.getFactoryId()).getSingleResult();
            assertThat(tombstone[0]).isNotNull();
            assertThat(((Number)tombstone[1]).longValue()).isEqualTo(input.getLockVersion()+1);
            assertThat(tombstone[2]).isNull();
        } finally {
            if(a.getTransaction().isActive()) a.getTransaction().rollback();
            if(b.getTransaction().isActive()) b.getTransaction().rollback();
            b.getTransaction().begin();
            b.createNativeQuery("delete from factory_activation_executions where id=:id and factory_id=:factory")
                    .setParameter("id",input.getId()).setParameter("factory",input.getFactoryId()).executeUpdate();
            b.getTransaction().commit(); a.close(); b.close();
        }
    }

    @Test
    void migratedCanonicalReceiptsHistoryAndBindingFlushAndReload() {
        String factory = "ASSEMBLY-" + UUID.randomUUID().toString().substring(0, 8);
        FactoryTypeBlueprint blueprint = new FactoryTypeBlueprint();
        blueprint.setName("canonical"); blueprint.setFactoryId(factory); blueprint.setScopeType("FACTORY");
        blueprint.setIsCurrent(true); blueprint.setRequestDigest("a".repeat(64));
        blueprints.saveAndFlush(blueprint);
        var application = new com.cretas.aims.entity.config.BlueprintApplication();
        application.setBlueprintId(blueprint.getId()); application.setFactoryId(factory);
        application.setOperationType("APPLY"); application.setIdempotencyKey("apply-62");
        application.setStatus(com.cretas.aims.entity.config.BlueprintApplication.ApplicationStatus.PARTIAL_FAILED);
        application.setRecoveryPlan("{\"remaining\":true}");
        application.setCreatedAt(java.time.LocalDateTime.now()); application.setUpdatedAt(java.time.LocalDateTime.now());
        entityManager.persist(application);
        var history = new com.cretas.aims.entity.config.BlueprintVersionHistory();
        history.setBlueprintId(blueprint.getId()); history.setFactoryId(factory); history.setVersion(1);
        history.setSchemaVersion("factory-blueprint/v1"); history.setLifecycleState("PUBLISHED");
        history.setCreatedAt(java.time.LocalDateTime.now()); history.setUpdatedAt(java.time.LocalDateTime.now());
        entityManager.persist(history);
        var binding = new com.cretas.aims.entity.config.FactoryBlueprintBinding();
        binding.setFactoryId(factory); binding.setBlueprintId(blueprint.getId()); binding.setAppliedVersion(null); binding.setPublishedVersion(1);
        binding.setCreatedAt(java.time.LocalDateTime.now()); binding.setUpdatedAt(java.time.LocalDateTime.now());
        entityManager.persist(binding); entityManager.flush(); entityManager.clear();
        assertThat(entityManager.find(com.cretas.aims.entity.config.BlueprintApplication.class, application.getId()).getRecoveryPlan()).contains("remaining");
        assertThat(entityManager.find(com.cretas.aims.entity.config.BlueprintVersionHistory.class, history.getId()).getFactoryId()).isEqualTo(factory);
        assertThat(entityManager.find(com.cretas.aims.entity.config.FactoryBlueprintBinding.class, binding.getId()).getPublishedVersion()).isEqualTo(1);
        assertThat(entityManager.find(com.cretas.aims.entity.config.FactoryBlueprintBinding.class, binding.getId()).getAppliedVersion()).isNull();
        assertThat(blueprints.findById(blueprint.getId()).orElseThrow().getRequestDigest()).isEqualTo("a".repeat(64));
    }

    @Test
    void exhaustedConversationStatusSurvivesRealDatabaseRoundTrip() {
        var session = FactoryAssemblyConversationSession.create("ASSEMBLY-MAX", 62L, AuthenticatedActorType.PLATFORM_ADMIN, "平台建厂");
        session.setStatus(FactoryAssemblyConversationSession.SessionStatus.MAX_ROUNDS_REACHED);
        conversations.saveAndFlush(session); entityManager.clear();
        assertThat(conversations.findById(session.getSessionId()).orElseThrow().getStatus())
                .isEqualTo(FactoryAssemblyConversationSession.SessionStatus.MAX_ROUNDS_REACHED);
    }

    @Test
    void canonicalFactoryBlueprintsNeverEnterHistoricalTemplateLists() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        FactoryTypeBlueprint template = new FactoryTypeBlueprint();
        template.setName("historical-template-" + suffix);
        template.setIndustryType("ASSEMBLY-" + suffix);
        blueprints.saveAndFlush(template);
        FactoryTypeBlueprint canonical = new FactoryTypeBlueprint();
        canonical.setName("canonical-" + suffix);
        canonical.setFactoryId("FACTORY-" + suffix);
        canonical.setScopeType("FACTORY");
        canonical.setIndustryType(template.getIndustryType());
        canonical.setIsCurrent(true);
        canonical.setDraftIdempotencyKey("draft-" + suffix);
        blueprints.saveAndFlush(canonical);

        assertThat(blueprints.findByIndustryTypeAndDeletedAtIsNull(template.getIndustryType()))
                .extracting(FactoryTypeBlueprint::getId).containsExactly(template.getId());
        assertThat(blueprints.findByIsActiveTrueAndDeletedAtIsNull())
                .extracting(FactoryTypeBlueprint::getId).contains(template.getId()).doesNotContain(canonical.getId());
        assertThat(blueprints.findByFactoryIdAndIdAndDeletedAtIsNull(canonical.getFactoryId(), canonical.getId())).isPresent();
        assertThat(blueprints.findByIdAndDeletedAtIsNull(canonical.getId())).isEmpty();
        assertThat(blueprints.findByIdAndDeletedAtIsNull(template.getId())).isPresent();
        assertThat(blueprints.findByFactoryIdAndIdAndDeletedAtIsNull("OTHER-" + suffix, canonical.getId())).isEmpty();
        assertThat(blueprints.lockCurrentByFactoryId(canonical.getFactoryId())).isPresent();
        assertThat(blueprints.findByFactoryIdAndDraftIdempotencyKeyAndDeletedAtIsNull(canonical.getFactoryId(), canonical.getDraftIdempotencyKey())).isPresent();
    }

    @Test
    void conversationLookupSeparatesSameNumericActorAndOtherFactory() {
        String factory = "ASSEMBLY-" + UUID.randomUUID().toString().substring(0, 8);
        var platform = FactoryAssemblyConversationSession.create(factory, 62L, AuthenticatedActorType.PLATFORM_ADMIN, "平台建厂");
        var member = FactoryAssemblyConversationSession.create(factory, 62L, AuthenticatedActorType.FACTORY_USER, "工厂会话");
        conversations.saveAndFlush(platform);
        conversations.saveAndFlush(member);
        assertThat(conversations.findBySessionIdAndFactoryIdAndUserIdAndActorType(platform.getSessionId(), factory, 62L, AuthenticatedActorType.PLATFORM_ADMIN)).isPresent();
        assertThat(conversations.findBySessionIdAndFactoryIdAndUserIdAndActorType(platform.getSessionId(), factory, 62L, AuthenticatedActorType.FACTORY_USER)).isEmpty();
        assertThat(conversations.findBySessionIdAndFactoryIdAndUserIdAndActorType(platform.getSessionId(), "OTHER", 62L, AuthenticatedActorType.PLATFORM_ADMIN)).isEmpty();
        assertThat(conversations.findActiveByFactoryAndActor(factory, 62L, AuthenticatedActorType.PLATFORM_ADMIN)).extracting(FactoryAssemblyConversationSession::getSessionId).containsExactly(platform.getSessionId());
    }
}
