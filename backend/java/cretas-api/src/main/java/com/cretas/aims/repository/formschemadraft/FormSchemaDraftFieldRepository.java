package com.cretas.aims.repository.formschemadraft;

import com.cretas.aims.entity.formschemadraft.FormSchemaDraftField;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormSchemaDraftFieldRepository
        extends JpaRepository<FormSchemaDraftField, String> {
    List<FormSchemaDraftField> findByDraftIdAndFactoryIdOrderByFormIdAscOrdinalAsc(
            String draftId, String factoryId);
}
