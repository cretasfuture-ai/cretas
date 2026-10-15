package com.cretas.aims.service.bom.impl;

import com.cretas.aims.blueprint.activation.AssemblyActivationActorResolver;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class BomActivationActorFamilyContractTest {
    @ParameterizedTest
    @EnumSource(value = AuthenticatedActorType.class, names = {"PLATFORM_ADMIN", "FACTORY_USER"})
    void auditsTheValidatedFamilyAfterActivationChangesItsStatus(AuthenticatedActorType type) {
        var repo = mock(BomRecipeRepository.class);
        var resolver = mock(AssemblyActivationActorResolver.class);
        var service = mock(BomRecipeServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "recipeRepo", repo);
        ReflectionTestUtils.setField(service, "actorResolver", resolver);
        var actor = new ActorRef(type, 77L);
        var first = member("one");
        var second = member("two");
        var addedDuringActivation = member("three");
        var actualFamily = List.of(first, second, addedDuringActivation);
        when(repo.findById("one")).thenReturn(Optional.of(first));
        when(repo.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                "TEST", "family", BomRecipe.Status.DRAFT)).thenAnswer(call ->
                List.of(first, second).stream().filter(row -> row.getStatus() == BomRecipe.Status.DRAFT).toList());
        when(repo.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                "TEST", "family", BomRecipe.Status.ACTIVE)).thenAnswer(call ->
                actualFamily.stream().filter(row -> row.getStatus() == BomRecipe.Status.ACTIVE).toList());
        doAnswer(call -> {
            for (var row : actualFamily) {
                row.setStatus(BomRecipe.Status.ACTIVE);
                row.setActivatedBy(actor.factoryUserId());
            }
            return first;
        }).when(service).activateRecipe("TEST", "one", actor.factoryUserId(), null);

        assertThatCode(() -> service.activateRecipeAsActor("TEST", "one", actor)).doesNotThrowAnyException();
        for (var row : actualFamily) {
            assertThat(row.getStatus()).isEqualTo(BomRecipe.Status.ACTIVE);
            assertThat(row.getActivatedActorType()).isEqualTo(type);
            assertThat(row.getActivatedActorId()).isEqualTo(77L);
            assertThat(row.getActivatedBy()).isEqualTo(actor.factoryUserId());
            verify(repo).save(row);
        }
        verify(service).activateRecipe("TEST", "one", actor.factoryUserId(), null);
        verify(resolver).authorize(eq(actor), eq("TEST"), anySet());
    }

    private BomRecipe member(String id) {
        var row = new BomRecipe();
        row.setId(id);
        row.setFactoryId("TEST");
        row.setBomFamilyId("family");
        row.setStatus(BomRecipe.Status.DRAFT);
        return row;
    }
}
