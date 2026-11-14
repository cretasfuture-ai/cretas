package com.cretas.aims.repository.intent;

import com.cretas.aims.entity.intent.IntentFlywheelTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 意图飞轮记录 (Flyway V20261114_87)。只有飞轮写入在用; 导出 / 缺口汇总走只读脚本。 */
public interface IntentFlywheelTurnRepository extends JpaRepository<IntentFlywheelTurn, Long> {

    /** 同一会话最近几轮 (新的在前): 上下文摘要 + 回填上一轮的反应。 */
    List<IntentFlywheelTurn> findTop3ByFactoryIdAndUserIdAndSessionIdAndCreatedAtAfterOrderByIdDesc(
            String factoryId, Long userId, String sessionId, LocalDateTime after);

    /** 确认入口: 本厂出过这张确认卡、还没判过反应的那一轮。 */
    Optional<IntentFlywheelTurn> findFirstByFactoryIdAndConfirmDigestAndReactionIsNullOrderByIdDesc(
            String factoryId, String confirmDigest);
}
