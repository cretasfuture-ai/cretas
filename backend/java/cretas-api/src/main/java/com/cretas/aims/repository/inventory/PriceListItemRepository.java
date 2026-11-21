package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PriceListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceListItemRepository extends JpaRepository<PriceListItem, Long> {

    Optional<PriceListItem> findByIdAndPriceListIdAndPriceList_FactoryId(Long id, String priceListId, String factoryId);

    List<PriceListItem> findByPriceListId(String priceListId);

    List<PriceListItem> findByPriceListIdAndMaterialTypeId(String priceListId, String materialTypeId);

    List<PriceListItem> findByPriceListIdAndProductTypeId(String priceListId, String productTypeId);
}
