package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.DeliveryNoteStatus;
import com.cretas.aims.entity.inventory.PriceAnomalyApprovalStatus;
import com.cretas.aims.entity.inventory.SupplierDeliveryNote;
import com.cretas.aims.repository.inventory.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.inventory.impl.SupplierDeliveryNoteServiceImpl;
import com.cretas.aims.controller.inventory.FactorySupplierDeliveryController;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SupplierDeliveryPriceAnomalyRejectContractTest {

    @Mock
    private SupplierDeliveryNoteRepository noteRepository;

    @InjectMocks
    private SupplierDeliveryNoteServiceImpl service;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t\n"})
    void missingOrBlankRejectCommentLeavesPendingNoteUnchanged(String comment) {
        SupplierDeliveryNote note = pendingNote();
        when(noteRepository.findByIdAndFactoryId("NOTE-1", "F006")).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.rejectPriceAnomaly(
                "F006", "NOTE-1", comment, 7L, "procurement_manager"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getErrorCode()).isEqualTo("PRICE_ANOMALY_REJECT_COMMENT_REQUIRED");
                });

        assertThat(note.getPriceAnomalyApprovalStatus()).isEqualTo(PriceAnomalyApprovalStatus.PENDING);
        assertThat(note.getPriceAnomalyRejectedBy()).isNull();
        assertThat(note.getPriceAnomalyRejectedAt()).isNull();
        assertThat(note.getPriceAnomalyApprovalComment()).isNull();
        verify(noteRepository, never()).save(any(SupplierDeliveryNote.class));
    }

    @Test
    void controllerMissingBodyCannotBypassTheRealServiceReasonGuard() {
        SupplierDeliveryNote note = pendingNote();
        when(noteRepository.findByIdAndFactoryId("NOTE-1", "F006")).thenReturn(Optional.of(note));
        FactorySupplierDeliveryController controller =
                new FactorySupplierDeliveryController(service, noteRepository, new ObjectMapper());
        assertThatThrownBy(() -> controller.rejectPriceAnomaly(
                "F006", "NOTE-1", 7L, "procurement_manager", null))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getErrorCode()).isEqualTo("PRICE_ANOMALY_REJECT_COMMENT_REQUIRED");
                });
        assertThat(note.getPriceAnomalyApprovalStatus()).isEqualTo(PriceAnomalyApprovalStatus.PENDING);
        verify(noteRepository, never()).save(any(SupplierDeliveryNote.class));
    }

    @Test
    void nonBlankRejectCommentPersistsTrimmedReason() {
        SupplierDeliveryNote note = pendingNote();
        when(noteRepository.findByIdAndFactoryId("NOTE-1", "F006")).thenReturn(Optional.of(note));
        when(noteRepository.save(note)).thenReturn(note);

        service.rejectPriceAnomaly("F006", "NOTE-1", "  价格不符  ", 7L, "procurement_manager");

        assertThat(note.getPriceAnomalyApprovalStatus()).isEqualTo(PriceAnomalyApprovalStatus.REJECTED);
        assertThat(note.getPriceAnomalyApprovalComment()).isEqualTo("价格不符");
        assertThat(note.getPriceAnomalyRejectedBy()).isEqualTo(7L);
        verify(noteRepository).save(note);
    }

    private static SupplierDeliveryNote pendingNote() {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId("NOTE-1");
        note.setFactoryId("F006");
        note.setStatus(DeliveryNoteStatus.DRAFT);
        note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.PENDING);
        return note;
    }
}
