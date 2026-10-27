package com.cretas.aims.repository.quality;

import com.cretas.aims.entity.quality.SemiQcHoldLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 半成品待检分笔账 (缺口 11)。只查「还没放行的」那些笔。 */
@Repository
public interface SemiQcHoldLotRepository extends JpaRepository<SemiQcHoldLot, Long> {

    /** 这一行所有未放行的笔 (放行 / 判不合格时整行清零)。 */
    @Query("SELECT l FROM SemiQcHoldLot l WHERE l.factoryId = :factoryId AND l.semiFinishedId = :semiId "
            + "AND l.quantityOpen > 0 ORDER BY l.id")
    List<SemiQcHoldLot> findOpenBySemi(@Param("factoryId") String factoryId, @Param("semiId") Long semiId);

    /** 某次 App 报工在这一行上未放行的笔 (撤销 / 驳回 / 数量更正)。新的在前。 */
    @Query("SELECT l FROM SemiQcHoldLot l WHERE l.factoryId = :factoryId AND l.semiFinishedId = :semiId "
            + "AND l.reportId = :reportId AND l.quantityOpen > 0 ORDER BY l.id DESC")
    List<SemiQcHoldLot> findOpenBySemiAndReport(@Param("factoryId") String factoryId,
                                               @Param("semiId") Long semiId,
                                               @Param("reportId") Long reportId);

    /** 某条逐道录入凭据在这一行上未放行的笔 (重存 / 删除)。新的在前。 */
    @Query("SELECT l FROM SemiQcHoldLot l WHERE l.factoryId = :factoryId AND l.semiFinishedId = :semiId "
            + "AND l.holdRef = :holdRef AND l.quantityOpen > 0 ORDER BY l.id DESC")
    List<SemiQcHoldLot> findOpenBySemiAndRef(@Param("factoryId") String factoryId,
                                            @Param("semiId") Long semiId,
                                            @Param("holdRef") String holdRef);
}
