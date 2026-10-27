package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductEquipmentRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductEquipmentRequirementRepository extends JpaRepository<ProductEquipmentRequirement, Long> {

    List<ProductEquipmentRequirement> findByFactoryIdAndProductTypeId(String factoryId, String productTypeId);

    List<ProductEquipmentRequirement> findByFactoryId(String factoryId);
}
