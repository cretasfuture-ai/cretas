package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShortageCaseEventRepository extends JpaRepository<ShortageCaseEvent, Long> {

    List<ShortageCaseEvent> findByCaseIdOrderByIdAsc(String caseId);
}
