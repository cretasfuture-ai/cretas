package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ShortageCaseLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShortageCaseLineRepository extends JpaRepository<ShortageCaseLine, Long> {

    List<ShortageCaseLine> findByCaseIdOrderByIdAsc(String caseId);
}
