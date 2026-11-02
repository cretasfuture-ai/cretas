package com.cretas.aims.repository.shortage;

import com.cretas.aims.entity.shortage.RescheduleProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RescheduleProposalRepository extends JpaRepository<RescheduleProposal, String> {

    Optional<RescheduleProposal> findByIdAndFactoryIdAndCaseId(String id, String factoryId, String caseId);

    List<RescheduleProposal> findTop5ByFactoryIdAndCaseIdOrderByCreatedAtDesc(String factoryId, String caseId);
}
