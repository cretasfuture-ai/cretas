package com.cretas.aims.repository.platform;

import com.cretas.aims.entity.platform.PlatformAiAnalysisGrantAudit;
import org.springframework.data.repository.Repository;

import java.util.List;

/** ⛔ 特意不继承 JpaRepository: 留痕只追加, 这里没有 delete, 也没有批量改写的入口。 */
public interface PlatformAiAnalysisGrantAuditRepository extends Repository<PlatformAiAnalysisGrantAudit, Long> {

    PlatformAiAnalysisGrantAudit save(PlatformAiAnalysisGrantAudit row);

    List<PlatformAiAnalysisGrantAudit> findTop50ByOrderByChangedAtDescIdDesc();
}
