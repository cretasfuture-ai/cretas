package com.cretas.aims.repository.platform;

import com.cretas.aims.entity.platform.PlatformAiAnalysisGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformAiAnalysisGrantRepository
        extends JpaRepository<PlatformAiAnalysisGrant, PlatformAiAnalysisGrant.Key> {

    /** 每次调用入口都现查, ⛔ 不加缓存: 撤销要在下一次调用就生效。 */
    boolean existsByGranteeTypeAndGranteeRef(String granteeType, String granteeRef);

    List<PlatformAiAnalysisGrant> findAllByOrderByGrantedAtDescGranteeRefAsc();

    /** @return 删掉的行数: 0 = 本来就没开通 (调用方据此不写留痕)。 */
    @Modifying
    @Query("DELETE FROM PlatformAiAnalysisGrant g WHERE g.granteeType = :type AND g.granteeRef = :ref")
    int revoke(@Param("type") String granteeType, @Param("ref") String granteeRef);
}
