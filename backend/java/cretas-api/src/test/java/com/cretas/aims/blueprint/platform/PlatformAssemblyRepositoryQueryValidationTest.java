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
        binding.setFactoryId(factory); binding.setBlueprintId(blueprint.getId()); binding.setAppliedVersion(1); binding.setPublishedVersion(1);
        binding.setCreatedAt(java.time.LocalDateTime.now()); binding.setUpdatedAt(java.time.LocalDateTime.now());
        entityManager.persist(binding); entityManager.flush(); entityManager.clear();
        assertThat(entityManager.find(com.cretas.aims.entity.config.BlueprintApplication.class, application.getId()).getRecoveryPlan()).contains("remaining");
        assertThat(entityManager.find(com.cretas.aims.entity.config.BlueprintVersionHistory.class, history.getId()).getFactoryId()).isEqualTo(factory);
        assertThat(entityManager.find(com.cretas.aims.entity.config.FactoryBlueprintBinding.class, binding.getId()).getPublishedVersion()).isEqualTo(1);
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
