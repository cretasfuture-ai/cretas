package com.cretas.aims.controller;

import com.cretas.aims.controller.inventory.PriceListController;
import com.cretas.aims.entity.inventory.PriceList;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.inventory.PriceListItemRepository;
import com.cretas.aims.repository.inventory.PriceListRepository;
import com.cretas.aims.service.MobileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceListFactoryScopeContractTest {
    private static final String FACTORY = "F006";

    @Mock PriceListRepository lists;
    @Mock PriceListItemRepository items;
    @Mock MobileService mobile;
    @InjectMocks PriceListController controller;

    private PriceList stored(String factoryId) {
        PriceList row = new PriceList();
        row.setId("audit-price-list");
        row.setFactoryId(factoryId);
        when(lists.findByIdAndFactoryId(row.getId(), FACTORY)).thenReturn(Optional.of(row));
        return row;
    }

    private void verifyScopedLookup(String id) {
        verify(lists).findByIdAndFactoryId(id, FACTORY);
        verify(lists, never()).findById(any());
    }

    @Test void ownFactoryDetailRetainsTheRealEntity() {
        PriceList own = stored(FACTORY);
        assertThat(controller.getPriceList(FACTORY, own.getId()).getData()).isSameAs(own);
        verifyScopedLookup(own.getId());
        verify(lists, never()).delete(any(PriceList.class));
    }

    @Test void ownFactoryDeleteRemainsAllowed() {
        PriceList own = stored(FACTORY);
        assertThat(controller.deletePriceList(FACTORY, own.getId()).getSuccess()).isTrue();
        verifyScopedLookup(own.getId());
        verify(lists).delete(own);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = "F007")
    void detailRejectsForeignOrUnscopedEntity(String factoryId) {
        PriceList other = stored(factoryId);
        assertThatThrownBy(() -> controller.getPriceList(FACTORY, other.getId()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("价格表不存在");
        verifyScopedLookup(other.getId());
        verify(lists, never()).delete(any(PriceList.class));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = "F007")
    void deleteRejectsForeignOrUnscopedEntityBeforeMutation(String factoryId) {
        PriceList other = stored(factoryId);
        assertThatThrownBy(() -> controller.deletePriceList(FACTORY, other.getId()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("价格表不存在");
        verifyScopedLookup(other.getId());
        verify(lists, never()).delete(any(PriceList.class));
        verifyNoInteractions(items, mobile);
    }

    @Test void missingDetailUsesTheSameNotFoundResult() {
        when(lists.findByIdAndFactoryId("missing", FACTORY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.getPriceList(FACTORY, "missing"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("价格表不存在");
        verifyScopedLookup("missing");
    }

    @Test void missingDeleteNeverMutates() {
        when(lists.findByIdAndFactoryId("missing", FACTORY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.deletePriceList(FACTORY, "missing"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("价格表不存在");
        verifyScopedLookup("missing");
        verify(lists, never()).delete(any(PriceList.class));
    }
}
