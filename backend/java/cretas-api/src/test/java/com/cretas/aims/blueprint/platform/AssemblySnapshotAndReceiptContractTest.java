package com.cretas.aims.blueprint.platform;

import com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality;
import com.cretas.aims.blueprint.rule.FactoryRuleUnitResolver;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.service.unit.UnitDimension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class AssemblySnapshotAndReceiptContractTest {
    @Test void packagingSelfContainmentUsesTheFactoryAuthorityForAliases() {
        var authority=org.mockito.Mockito.mock(com.cretas.aims.service.unit.UnitContractService.class);
        org.mockito.Mockito.when(authority.areEquivalent("F1","公斤","kg")).thenReturn(true);
        var command=new com.cretas.aims.service.packagingdraft.PackagingDraftCommand(
                null,null,null,null,null,null,null,null,null,authority,null);
        var same=new com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingItem(
                null,com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingRole.PRIMARY_CONTAINER,
                "包装","公斤","kg",java.math.BigDecimal.ONE,null,"",false,0,null,"确认包装单位");
        var issues=new java.util.ArrayList<com.cretas.aims.service.packagingdraft.PackagingDraftModels.DraftIssue>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(command,"validateItemShape","F1",same,"item",issues);
        assertThat(issues).anyMatch(issue->issue.code().equals("PACKAGING_SELF_CONTAINMENT_FORBIDDEN"));
        org.mockito.Mockito.verify(authority).areEquivalent("F1","公斤","kg");
        var distinct=new com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingItem(
                null,com.cretas.aims.service.packagingdraft.PackagingDraftModels.PackagingRole.PRIMARY_CONTAINER,
                "包装","个","件",java.math.BigDecimal.ONE,null,"",false,0,null,"确认独立计数单位");
        issues.clear();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(command,"validateItemShape","F1",distinct,"item",issues);
        assertThat(issues).noneMatch(issue->issue.code().equals("PACKAGING_SELF_CONTAINMENT_FORBIDDEN"));
    }

    @Test void quantityAliasesUseAuthorityButConfirmedMetadataRemainsExact() {
        FactoryRuleUnitResolver resolver = new FactoryRuleUnitResolver() {
            public String catalogDigest(String factory) { return "snapshot"; }
            public Optional<UnitDescriptor> describe(String factory, String text) {
                return switch(text) {
                    case "kg", "公斤" -> Optional.of(new UnitDescriptor("kg", UnitDimension.MASS));
                    case "g" -> Optional.of(new UnitDescriptor("g", UnitDimension.MASS));
                    case "个", "件" -> Optional.of(new UnitDescriptor(text, UnitDimension.COUNT));
                    default -> Optional.empty();
                };
            }
            public Conversion convert(String factory, java.math.BigDecimal value, String from, String to, java.time.Instant at) {
                throw new AssertionError("identity checks must not invoke conversion");
            }
        };
        assertThat(resolver.sameQuantityUnit("F1","kg","公斤")).isTrue();
        assertThat(resolver.sameQuantityUnit("F1","kg","g")).isFalse();
        assertThat(resolver.sameQuantityUnit("F1","个","件")).isFalse();
        assertThat(resolver.sameQuantityUnit("F1","unknown","unknown")).isFalse();
        assertThat(FactoryBlueprintSnapshotEquality.sameConfirmedValue("kg","公斤")).isFalse();
        assertThat(FactoryBlueprintSnapshotEquality.sameConfirmedValue("digest-v1","digest-v2")).isFalse();
        assertThat(FactoryBlueprintSnapshotEquality.sameConfirmedValue("kg","kg")).isTrue();
        assertThat(FactoryBlueprintSnapshotEquality.sameConfirmedValue(null,null)).isTrue();
    }

    @Test void nestedReplyReceiptKeepsItsPublicJsonAndBuilderContract() throws Exception {
        var mapper=new ObjectMapper();
        var receipt=FactoryAssemblyConversationSession.ReplyReceipt.builder()
                .currentRound(2).maxRounds(5).status("ACTIVE").build();
        var json=mapper.valueToTree(receipt);
        assertThat(json.path("currentRound").asInt()).isEqualTo(2);
        assertThat(json.path("maxRounds").asInt()).isEqualTo(5);
        assertThat(json.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.has("receiptRound")).isFalse();
        assertThat(json.has("receiptMaxRounds")).isFalse();
        assertThat(json.has("receiptStatus")).isFalse();
        var restored=mapper.treeToValue(json,FactoryAssemblyConversationSession.ReplyReceipt.class);
        assertThat(restored.getCurrentRound()).isEqualTo(2);
        assertThat(restored.getMaxRounds()).isEqualTo(5);
        assertThat(restored.getStatus()).isEqualTo("ACTIVE");
    }
}
