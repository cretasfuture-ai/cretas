package com.cretas.aims.repository;

import com.cretas.aims.entity.material.RawMaterialTypeAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 物料别名。所有查询都带 factoryId —— 别名是租户内的检索入口。 */
@Repository
public interface RawMaterialTypeAliasRepository extends JpaRepository<RawMaterialTypeAlias, String> {

    List<RawMaterialTypeAlias> findByFactoryIdAndMaterialTypeIdOrderByCreatedAtAsc(
            String factoryId, String materialTypeId);

    List<RawMaterialTypeAlias> findByFactoryIdAndMaterialTypeIdIn(
            String factoryId, Collection<String> materialTypeIds);

    Optional<RawMaterialTypeAlias> findByFactoryIdAndNormalizedAlias(String factoryId, String normalizedAlias);

    Optional<RawMaterialTypeAlias> findByIdAndFactoryId(String id, String factoryId);
}
