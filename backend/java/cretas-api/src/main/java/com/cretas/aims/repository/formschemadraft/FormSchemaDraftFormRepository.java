package com.cretas.aims.repository.formschemadraft;

import com.cretas.aims.entity.formschemadraft.FormSchemaDraftForm;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormSchemaDraftFormRepository extends JpaRepository<FormSchemaDraftForm, String> {
    List<FormSchemaDraftForm> findByDraftIdAndFactoryIdOrderByOrdinal(
            String draftId, String factoryId);
}
