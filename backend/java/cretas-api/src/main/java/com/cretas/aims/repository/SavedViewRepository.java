package com.cretas.aims.repository;

import com.cretas.aims.entity.SavedView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SavedViewRepository extends JpaRepository<SavedView, String> {

    Optional<SavedView> findByIdAndFactoryId(String id, String factoryId);

    /** 本人保存的 + 本厂共享的; 一律限定 factory_id。 */
    @Query("SELECT v FROM SavedView v WHERE v.factoryId = :factoryId "
            + "AND (v.ownerUserId = :userId OR v.shared = true) ORDER BY v.updatedAt DESC")
    List<SavedView> findVisible(@Param("factoryId") String factoryId, @Param("userId") Long userId);
}
