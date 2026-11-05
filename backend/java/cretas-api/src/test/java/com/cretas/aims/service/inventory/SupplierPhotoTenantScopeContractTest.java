package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.SupplierDeliveryNote;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SupplierDeliveryNoteRepository;
import com.cretas.aims.service.inventory.impl.SupplierDeliveryNoteServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierPhotoTenantScopeContractTest {
    @Mock SupplierDeliveryNoteRepository repository;
    @InjectMocks SupplierDeliveryNoteServiceImpl service;

    @Test
    void duplicateLookupCannotReadAnotherFactoryAndOnlyLinksAnOwnedNote() {
        SupplierDeliveryNote own = new SupplierDeliveryNote();
        own.setId("OWN-NOTE");
        own.setFactoryId("F006");
        when(repository.findByFactoryIdAndPhotoHash(eq("F006"), anyString())).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.parseAndDraft("F006", 7L, new byte[]{1, 2, 3}, "image/png", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(409);
                    org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("OWN-NOTE");
                });
        verify(repository).findByFactoryIdAndPhotoHash(eq("F006"), anyString());
        verifyNoMoreInteractions(repository);
    }
}
