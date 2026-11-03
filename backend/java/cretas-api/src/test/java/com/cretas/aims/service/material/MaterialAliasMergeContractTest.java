package com.cretas.aims.service.material;

import com.cretas.aims.controller.RawMaterialTypeController;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.dto.material.MaterialSuggestDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.RawMaterialTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 物料别名 + 重复物料合并 —— 真 Spring 上下文 + H2, 走产品真实入口 ({@link RawMaterialTypeController})。
 *
 * <p>Owner 2026-11-03 裁定: 被合并物料还在配方 / 工艺图 / 供应商供货 / 在途采购里用着, 或有库存,
 * 一律拦住并列出位置; 历史单据不动。这里每一类拦截各造一条引用 (阳性), 并且每条都配阴性对照
 * (已完成的采购单 / 已用完的批次 / 非当前版本配方 / 别厂的同名引用都不该拦)。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("物料别名与重复物料合并: 别名检索 / 撞名拦截 / 合并前五类引用拦截 / 合并后别名生效")
class MaterialAliasMergeContractTest {

    private static final String F = "ALIAS-FACTORY";
    private static final String OTHER = "ALIAS-OTHER";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RawMaterialTypeController controller;
    @Autowired private RawMaterialTypeService materialTypeService;
    @Autowired private MaterialAliasMergeService aliasMergeService;
    @Autowired private RawMaterialTypeRepository materialRepo;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        material("M-MASTER", F, "LY-001", "酱油", "kg");
        material("M-DUP", F, "LY-002", "生抽酱油", "kg");
        material("M-OTHER-FACTORY", OTHER, "LY-001", "大骨", "kg");
    }

    @Test
    @DisplayName("别名: 能搜到主物料、详情带别名; 别名不能与物料名或别的别名撞; 新建物料不能用别名当名字")
    void aliasesAreSearchableAndUnique() {
        controller.addAlias(F, "M-MASTER", new MaterialMergeDTO.AliasRequest("  海天 酱油 "));

        RawMaterialTypeDTO detail = controller.getMaterialTypeById(F, "M-MASTER").getData();
        assertThat(detail.getAliases()).containsExactly("海天 酱油");

        PageRequest page = PageRequest.of(1, 20);
        List<String> hits = materialTypeService.searchMaterialTypes(F, "海天", page).getContent().stream()
                .map(RawMaterialTypeDTO::getId).toList();
        assertThat(hits).containsExactly("M-MASTER");
        // 阴性对照: 别名只在本厂生效
        assertThat(materialTypeService.searchMaterialTypes(OTHER, "海天", page).getContent()).isEmpty();
        assertThat(materialTypeService.filterMaterialTypes(F, null, "海天", page).getContent())
                .extracting(RawMaterialTypeDTO::getId).containsExactly("M-MASTER");

        // 全角 / 大小写 / 多空格算同一个写法
        assertThatThrownBy(() -> controller.addAlias(F, "M-DUP", new MaterialMergeDTO.AliasRequest("海天  酱油")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已是物料「酱油」的别名");
        assertThatThrownBy(() -> controller.addAlias(F, "M-DUP", new MaterialMergeDTO.AliasRequest("酱油")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经是物料「酱油」的名称");
        // 别厂可以有同样的别名
        controller.addAlias(OTHER, "M-OTHER-FACTORY", new MaterialMergeDTO.AliasRequest("海天酱油"));

        RawMaterialTypeDTO create = RawMaterialTypeDTO.builder().name("海天 酱油").category("调味料").unit("kg").build();
        assertThatThrownBy(() -> materialTypeService.createMaterialType(F, create))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已是物料「酱油」的别名");

        // 输入别名时, 建议直接指向那个物料 (精确命中)
        MaterialSuggestDTO suggest = materialTypeService.suggestFields(F, "海天酱油", null);
        assertThat(suggest.getMatchedMaterialName()).isEqualTo("酱油");
        assertThat(suggest.getUnitConfidence()).isEqualTo("HIGH");

        assertThat(aliasMergeService.resolveByName(F, "海天酱油")).map(RawMaterialType::getId).contains("M-MASTER");
        assertThat(aliasMergeService.resolveByName(OTHER, "海天 酱油")).map(RawMaterialType::getId).contains("M-OTHER-FACTORY");
    }

    @Test
    @DisplayName("合并前: 库存 / 配方 / 工艺图 / 供应商 / 在途采购 各自拦住并列出位置; 已了结的引用不拦")
    void mergeIsBlockedByEveryLiveReference() {
        // 阴性对照先铺: 这些都不该拦
        batch("B-USED-UP", F, "M-DUP", 10, 10, "USED_UP");
        batch("B-OTHER", OTHER, "M-DUP", 50, 0, "AVAILABLE");
        purchaseOrder("PO-DONE", F, "COMPLETED", "M-DUP");
        recipe("R-OLD", F, "旧版卤牛肉", false, "M-DUP");
        workflow(F, "SNAPSHOT", "M-DUP");
        supplierMaterial("SM-OFF", F, "S-1", "M-DUP", false);
        assertThat(check().isCanMerge()).isTrue();

        batch("B-LIVE", F, "M-DUP", 12.5, 2.5, "AVAILABLE");
        recipe("R-CUR", F, "卤牛肉", true, "M-DUP");
        workflow(F, "PUBLISHED", "M-DUP");
        supplierMaterial("SM-ON", F, "S-2", "M-DUP", true);
        purchaseOrder("PO-OPEN", F, "APPROVED", "M-DUP");

        MaterialMergeDTO.MergeCheck check = check();
        assertThat(check.isCanMerge()).isFalse();
        Map<String, MaterialMergeDTO.Blocker> byCode = check.getBlockers().stream()
                .collect(java.util.stream.Collectors.toMap(MaterialMergeDTO.Blocker::getCode, b -> b));
        assertThat(byCode).containsOnlyKeys("STOCK", "BOM", "WORKFLOW", "SUPPLIER", "PURCHASE_ORDER");
        assertThat(byCode.get("STOCK").getMessage()).isEqualTo("还有库存 10 kg（1 个批次）");
        assertThat(byCode.get("BOM").getItems()).containsExactly("卤牛肉");
        assertThat(byCode.get("WORKFLOW").getItems()).containsExactly("卤牛肉产品");
        assertThat(byCode.get("SUPPLIER").getItems()).containsExactly("供应商S-2");
        assertThat(byCode.get("PURCHASE_ORDER").getItems()).containsExactly("PO-OPEN");

        assertThatThrownBy(() -> controller.merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("现在还不能合并")
                .hasMessageContaining("还有库存 10 kg")
                .hasMessageContaining("还有 1 张采购单没完成");
        RawMaterialType dup = materialRepo.findById("M-DUP").orElseThrow();
        assertThat(dup.getMergedIntoId()).isNull();
        assertThat(dup.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("合并后: 被合并物料停用、名字和别名挂到主物料; 搜旧名找到主物料; 不能再启用; 不能再当主物料")
    void mergeTurnsTheOldNameIntoAnAlias() {
        controller.addAlias(F, "M-DUP", new MaterialMergeDTO.AliasRequest("生抽"));
        RawMaterialType bottled = materialRepo.findById("M-DUP").orElseThrow();
        bottled.setUnit("瓶");
        materialRepo.saveAndFlush(bottled);

        MaterialMergeDTO.MergeCheck check = check();
        assertThat(check.isCanMerge()).isTrue();
        assertThat(check.getAliasesToAdd()).containsExactly("生抽酱油", "生抽");
        assertThat(check.getWarnings()).singleElement().asString().contains("单位不同");

        MaterialMergeDTO.MergeResult result = controller
                .merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", "同一种酱油, 品牌不同")).getData();
        assertThat(result.getAliasesAdded()).containsExactlyInAnyOrder("生抽", "生抽酱油");

        RawMaterialType dup = materialRepo.findById("M-DUP").orElseThrow();
        assertThat(dup.getMergedIntoId()).isEqualTo("M-MASTER");
        assertThat(dup.getIsActive()).isFalse();
        assertThat(dup.getMergeReason()).isEqualTo("同一种酱油, 品牌不同");

        assertThat(controller.listAliases(F, "M-MASTER").getData())
                .extracting(MaterialMergeDTO.AliasItem::getAlias).containsExactlyInAnyOrder("生抽", "生抽酱油");
        assertThat(controller.listAliases(F, "M-DUP").getData()).isEmpty();

        List<RawMaterialTypeDTO> active = materialTypeService.getActiveMaterialTypes(F);
        assertThat(active).extracting(RawMaterialTypeDTO::getId).contains("M-MASTER").doesNotContain("M-DUP");
        assertThat(materialTypeService.searchMaterialTypes(F, "生抽", PageRequest.of(1, 20)).getContent())
                .extracting(RawMaterialTypeDTO::getId).contains("M-MASTER");
        // 物料名精确命中已合并的旧物料时, 顺着合并去向落到主物料
        assertThat(aliasMergeService.resolveByName(F, "生抽酱油")).map(RawMaterialType::getId).contains("M-MASTER");

        RawMaterialTypeDTO merged = controller.getMaterialTypeById(F, "M-DUP").getData();
        assertThat(merged.getMergedIntoId()).isEqualTo("M-MASTER");
        assertThat(merged.getMergedIntoName()).isEqualTo("酱油");

        assertThatThrownBy(() -> materialTypeService.updateMaterialTypesStatus(F, List.of("M-DUP"), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已合并到别的物料，不能再启用");
        assertThatThrownBy(() -> controller.merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经合并过了");
        material("M-THIRD", F, "LY-003", "老抽", "kg");
        assertThatThrownBy(() -> controller.merge(F, "M-THIRD", new MaterialMergeDTO.MergeRequest("M-DUP", null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能当主物料");
        // 跨厂: 别厂的物料当主物料 = 不存在
        assertThatThrownBy(() -> controller.merge(F, "M-THIRD", new MaterialMergeDTO.MergeRequest("M-OTHER-FACTORY", null)))
                .isInstanceOf(com.cretas.aims.exception.ResourceNotFoundException.class);
    }

    // ---------- seed helpers ----------

    private MaterialMergeDTO.MergeCheck check() {
        return controller.checkMerge(F, "M-DUP", "M-MASTER").getData();
    }

    private void material(String id, String factoryId, String code, String name, String unit) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(factoryId);
        m.setCode(code);
        m.setName(name);
        m.setCategory("调味料");
        m.setUnit(unit);
        m.setIsActive(true);
        materialRepo.saveAndFlush(m);
    }

    private void batch(String id, String factoryId, String materialId, double receipt, double used, String status) {
        jdbc.update("INSERT INTO material_batches (id, factory_id, batch_number, material_type_id, inbound_date, "
                        + "warehouse_id, version, receipt_quantity, quantity_unit, used_quantity, reserved_quantity, "
                        + "status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_DATE, 'W1', 0, ?, 'kg', ?, 0, ?, 1, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, factoryId, id, materialId, receipt, used, status);
    }

    private void recipe(String id, String factoryId, String productName, boolean current, String materialId) {
        jdbc.update("INSERT INTO bom_recipes (id, factory_id, recipe_code, product_type_id, product_name, version, "
                        + "is_current, output_quantity_per_unit, output_unit, status, source_type, seasoning_revision, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'PT-1', ?, 1, ?, 1, 'kg', 'ACTIVE', 'MANUAL', 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, factoryId, id, productName, current);
        jdbc.update("INSERT INTO bom_recipe_items (recipe_id, factory_id, material_type_id, yield_rate, unit, "
                        + "quantity_to_price_factor, material_category, sort_order, is_optional, per_portion, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 100, 'kg', 1, 'RAW_MATERIAL', 1, FALSE, FALSE, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, factoryId, materialId);
    }

    /** H2 的 jsonb 列要用 FORMAT JSON 存成真 JSON (否则存成 JSON 字符串, 节点里的 id 被转义, 测不到真实形态)。 */
    private void workflow(String factoryId, String status, String materialId) {
        jdbc.update("INSERT INTO product_types (id, factory_id, code, name, unit, inspection_required, is_active, created_at, updated_at) "
                + "SELECT 'PT-WF', ?, 'PT-WF', '卤牛肉产品', 'kg', FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "WHERE NOT EXISTS (SELECT 1 FROM product_types WHERE id = 'PT-WF')", factoryId);
        jdbc.update("INSERT INTO product_process_workflows (factory_id, product_type_id, schema_version, status, "
                        + "definition_version, nodes_json, edges_json, viewport_json, unit_review_required, lock_version, "
                        + "created_at, updated_at) VALUES (?, 'PT-WF', 1, ?, 1, ? FORMAT JSON, '[]', '{}', FALSE, 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                factoryId, status, "[{\"id\":\"n1\",\"data\":{\"materialTypeId\":\"" + materialId + "\"}}]");
    }

    private void supplierMaterial(String id, String factoryId, String supplierId, String materialId, boolean active) {
        jdbc.update("INSERT INTO suppliers (id, factory_id, supplier_code, code, name, is_active, created_by, version, created_at, updated_at) "
                + "SELECT ?, ?, ?, ?, ?, TRUE, 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE id = ?)",
                supplierId, factoryId, supplierId, supplierId, "供应商" + supplierId, supplierId);
        jdbc.update("INSERT INTO supplier_materials (id, factory_id, supplier_id, material_type_id, currency, "
                        + "purchase_unit, preferred, active, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CNY', 'kg', FALSE, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, factoryId, supplierId, materialId, active);
    }

    private void purchaseOrder(String id, String factoryId, String status, String materialId) {
        jdbc.update("INSERT INTO purchase_orders (id, factory_id, order_number, purchase_type, order_date, status, "
                        + "created_by, version, purchase_invoice_status, vflag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DIRECT', CURRENT_DATE, ?, 1, 0, 'NOT_RECEIVED', 'UNCREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, factoryId, id, status);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, quantity, unit, price_unit, "
                        + "quantity_to_price_factor, received_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, 10, 'kg', 'kg', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, materialId);
    }
}
