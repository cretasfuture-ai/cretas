package com.cretas.aims.repository.quality.firstarticle;

import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FirstArticlePolicyRepository extends JpaRepository<FirstArticlePolicy, String> {

    /** 本厂的首检开关; 没有行返回 null —— 调用方按 OFF 处理 (现有工厂行为不变)。 */
    @Query("SELECT p.mode FROM FirstArticlePolicy p WHERE p.factoryId = :factoryId")
    FirstArticlePolicy.Mode findModeByFactoryId(@Param("factoryId") String factoryId);
}
