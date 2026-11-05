package com.cretas.aims.service.material;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Serializes new material references with material merge.  Callers must acquire
 * their owning row (PO/workflow) first where one already exists; a new PO has
 * no header to lock and therefore calls this guard before inserting it. This
 * class deliberately owns only the deterministic raw-material lock and the
 * fail-closed state check.
 */
@Component
public class MaterialReferenceWriteGuard {

    private final RawMaterialTypeRepository repository;

    public MaterialReferenceWriteGuard(RawMaterialTypeRepository repository) {
        this.repository = repository;
    }

    public List<RawMaterialType> lockActive(String factoryId, Collection<String> materialIds) {
        List<String> ids = materialIds == null ? List.of() : materialIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        if (ids.isEmpty()) return List.of();

        List<RawMaterialType> locked = repository.lockByFactoryIdAndIdIn(factoryId, ids);
        if (locked.size() != ids.size()) {
            throw conflict("采购/工艺引用的物料不存在或不属于当前工厂");
        }
        for (RawMaterialType material : locked) {
            if (!Boolean.TRUE.equals(material.getIsActive())) {
                throw conflict("物料「" + material.getName() + "」已停用，不能创建新引用");
            }
            if (material.getMergedIntoId() != null) {
                throw conflict("物料「" + material.getName() + "」已合并到别的物料，不能创建新引用");
            }
        }
        return locked;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, message)
                .withCode("MATERIAL_REFERENCE_CONFLICT")
                .withHint("请刷新物料主数据后重试");
    }
}
