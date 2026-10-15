package com.cretas.aims.repository.formschemadraft;

import com.cretas.aims.entity.formschemadraft.FormSchemaDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormSchemaDraftRepository extends JpaRepository<FormSchemaDraft, String> {
    Optional<FormSchemaDraft> findByFactoryIdAndIdempotencyKey(
            String factoryId, String idempotencyKey);
    Optional<FormSchemaDraft> findByIdAndFactoryId(String id, String factoryId);
}
