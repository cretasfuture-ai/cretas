package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShortageCaseOutcomeCheckRepository extends JpaRepository<ShortageCaseOutcomeCheck, Long> {

    List<ShortageCaseOutcomeCheck> findByFactoryIdAndCaseIdOrderByIdAsc(String factoryId, String caseId);
}
