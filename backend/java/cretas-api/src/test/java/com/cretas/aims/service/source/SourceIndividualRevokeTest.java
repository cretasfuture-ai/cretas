package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.CreateSourceIndividualRequest;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SourceIndividualRevokeTest {

    private final SourceIndividualRepository repo = mock(SourceIndividualRepository.class);
    private final SourceIndividualService service = new SourceIndividualService(repo);

    @Test
    void revokeWritesReasonBeforeDelete() {
        SourceIndividual e = SourceIndividual.builder().id("i1").factoryId("F1").individualCode("C1").build();
        when(repo.findByIdAndFactoryId("i1", "F1")).thenReturn(Optional.of(e));
        service.revoke("F1", "i1", "  录入错误  ");
        assertEquals("录入错误", e.getRevokeReason());
        var order = inOrder(repo);
        order.verify(repo).saveAndFlush(e);
        order.verify(repo).delete(e);
    }

    @Test
    void revokeWithoutReasonRejectedAndNothingDeleted() {
        assertThrows(BusinessException.class, () -> service.revoke("F1", "i1", " "));
        verify(repo, never()).delete(any(SourceIndividual.class));
    }

    @Test
    void createRejectsUnknownTypeAndStoresTypeAndEntryDate() {
        CreateSourceIndividualRequest bad = new CreateSourceIndividualRequest();
        bad.setIndividualCode("C2");
        bad.setIndividualType("DEAD");
        assertThrows(BusinessException.class, () -> service.create("F1", bad));

        CreateSourceIndividualRequest ok = new CreateSourceIndividualRequest();
        ok.setIndividualCode("C3");
        ok.setIndividualType("carcass");
        ok.setEntryDate(LocalDate.of(2026, 11, 1));
        when(repo.saveAndFlush(any(SourceIndividual.class))).thenAnswer(i -> i.getArgument(0));
        var dto = service.create("F1", ok);
        assertEquals("CARCASS", dto.getIndividualType());
        assertEquals(LocalDate.of(2026, 11, 1), dto.getEntryDate());
    }
}
