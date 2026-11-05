package com.cretas.aims.service.material;

import com.cretas.aims.controller.RawMaterialTypeController;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.material.MaterialMergeDTO;
import com.cretas.aims.dto.material.MaterialSuggestDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.RawMaterialTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
    @Autowired private UserRepository userRepo;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private AuthenticatedActor.ActorScope actorScope;

    @BeforeEach
    void setUp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("INSERT INTO factories (id, name, type, level, is_active, manually_verified, ai_weekly_quota, "
                        + "created_at, updated_at) VALUES (?, ?, 'FACTORY', 0, TRUE, FALSE, 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                        + "(?, ?, 'FACTORY', 0, TRUE, FALSE, 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                F, "Alias Merge Factory", OTHER, "Alias Merge Other Factory");
        User actor = new User();
        actor.setId(990001L);
        actor.setFactoryId(F);
        actor.setUsername("alias-merge-admin");
        actor.setPasswordHash("test");
        actor.setIsActive(true);
        actor.setRoleCode("factory_super_admin");
        userRepo.saveAndFlush(actor);
        actorScope = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, actor.getId(), F, actor.getRoleCode()));
        material("M-MASTER", F, "LY-001", "酱油", "kg");
        material("M-DUP", F, "LY-002", "生抽酱油", "kg");
        material("M-OTHER-FACTORY", OTHER, "LY-001", "大骨", "kg");
    }

    @AfterEach
    void clearActor() {
        if (actorScope != null) actorScope.close();
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
    @DisplayName("合并前: 库存 / 当前配方 / 已发布工艺图 / 已开始收货的采购单各自拦住并列出位置; 已了结的引用不拦")
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
        purchaseOrder("PO-PART", F, "PARTIAL_RECEIVED", "M-DUP", 3);

        MaterialMergeDTO.MergeCheck check = check();
        assertThat(check.isCanMerge()).isFalse();
        Map<String, MaterialMergeDTO.Blocker> byCode = check.getBlockers().stream()
                .collect(java.util.stream.Collectors.toMap(MaterialMergeDTO.Blocker::getCode, b -> b));
        assertThat(byCode).containsOnlyKeys("STOCK", "BOM", "WORKFLOW", "PURCHASE_ORDER");
        assertThat(byCode.get("STOCK").getMessage()).isEqualTo("还有库存 10 kg（1 个批次）");
        assertThat(byCode.get("BOM").getItems()).containsExactly("卤牛肉");
        assertThat(byCode.get("WORKFLOW").getItems()).containsExactly("卤牛肉产品");
        assertThat(byCode.get("PURCHASE_ORDER").getItems()).containsExactly("PO-PART");

        assertThatThrownBy(() -> controller.merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("现在还不能合并")
                .hasMessageContaining("还有库存 10 kg")
                .hasMessageContaining("还有 1 张采购单已开始收货");
        RawMaterialType dup = materialRepo.findById("M-DUP").orElseThrow();
        assertThat(dup.getMergedIntoId()).isNull();
        assertThat(dup.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("单位等价: 草稿工艺图 / 供货关系 / 没收货的采购单行换到主物料; 已发布、已完成、别厂的一行不动")
    void mergeReplacesDraftsSuppliersAndUnreceivedOrderLines() {
        long draft = realWorkflow(F, "DRAFT", "M-DUP", "LY-002", "生抽酱油");
        long snapshot = realWorkflow(F, "SNAPSHOT", "M-DUP", "LY-002", "生抽酱油");
        long otherFactory = realWorkflow(OTHER, "DRAFT", "M-DUP", "LY-002", "生抽酱油");
        supplierMaterial("SM-MOVE", F, "S-1", "M-DUP", true);
        supplierMaterial("SM-OLD", F, "S-2", "M-DUP", true);
        supplierMaterial("SM-KEEP", F, "S-2", "M-MASTER", true);
        supplierMaterial("SM-OFF", F, "S-3", "M-DUP", true);
        supplierMaterial("SM-BACK", F, "S-3", "M-MASTER", false);
        supplierMaterial("SM-PREF-DUP", F, "S-4", "M-DUP", true);
        supplierMaterial("SM-PREF-MASTER", F, "S-5", "M-MASTER", true);
        jdbc.update("UPDATE supplier_materials SET preferred = TRUE WHERE id IN ('SM-PREF-DUP', 'SM-PREF-MASTER')");
        supplierMaterial("SM-OTHER", OTHER, "S-9", "M-DUP", true);
        purchaseOrder("PO-OPEN", F, "APPROVED", "M-DUP");
        purchaseOrder("PO-DONE", F, "COMPLETED", "M-DUP");
        purchaseOrder("PO-OTHER", OTHER, "APPROVED", "M-DUP");

        MaterialMergeDTO.MergeCheck check = check();
        assertThat(check.isCanMerge()).isTrue();
        assertThat(check.getReplacements()).extracting(MaterialMergeDTO.Replacement::getMessage)
                .containsExactly("1 张草稿工艺图", "4 家供应商的供货关系", "1 张没收货的采购单");

        MaterialMergeDTO.ReplacementResult replaced = controller
                .merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", null)).getData().getReplaced();
        entityManager.flush();
        entityManager.clear();

        assertThat(replaced.getDraftWorkflows()).isEqualTo(1);
        assertThat(replaced.getSuppliers()).isEqualTo(4);
        assertThat(replaced.getPurchaseOrderItems()).isEqualTo(1);

        String nodes = nodes(draft);
        assertThat(nodes).doesNotContain("M-DUP").doesNotContain("LY-002").doesNotContain("生抽酱油");
        assertThat(nodes).contains("M-MASTER").contains("LY-001").contains("酱油");
        // 同一张图里别的物料不受影响
        assertThat(nodes).contains("M-BEEF").contains("NR-1").contains("牛肉");
        assertThat(nodes(snapshot)).contains("M-DUP");
        assertThat(nodes(otherFactory)).contains("M-DUP");

        assertThat(supplierRow("SM-MOVE")).containsEntry("MATERIAL_TYPE_ID", "M-MASTER").containsEntry("ACTIVE", true);
        // 这家已在供主物料: 不能有两条, 旧的停用, 主物料的价格不动
        assertThat(supplierRow("SM-OLD")).containsEntry("MATERIAL_TYPE_ID", "M-DUP").containsEntry("ACTIVE", false);
        assertThat(supplierRow("SM-KEEP")).containsEntry("ACTIVE", true);
        // 主物料那条是停用的: 重新启用, 否则这家就不供了
        assertThat(supplierRow("SM-OFF")).containsEntry("ACTIVE", false);
        assertThat(supplierRow("SM-BACK")).containsEntry("ACTIVE", true);
        // 一个物料只能有一条首选
        assertThat(supplierRow("SM-PREF-DUP")).containsEntry("MATERIAL_TYPE_ID", "M-MASTER").containsEntry("PREFERRED", false);
        assertThat(supplierRow("SM-PREF-MASTER")).containsEntry("PREFERRED", true);
        assertThat(supplierRow("SM-OTHER")).containsEntry("MATERIAL_TYPE_ID", "M-DUP");
        assertThat(replaced.getNotes()).hasSize(3);

        assertThat(orderItem("PO-OPEN")).containsEntry("MATERIAL_TYPE_ID", "M-MASTER").containsEntry("MATERIAL_NAME", "酱油");
        assertThat(orderItem("PO-DONE")).containsEntry("MATERIAL_TYPE_ID", "M-DUP");
        assertThat(orderItem("PO-OTHER")).containsEntry("MATERIAL_TYPE_ID", "M-DUP");
    }

    @Test
    @DisplayName("工艺图 JSON 只替换物料身份字段; id/source/target 等自由文本即使同值也不动")
    void mergeDoesNotRewriteUnrelatedJsonIds() {
        long draft = realWorkflow(F, "DRAFT", "M-DUP", "LY-002", "生抽酱油");
        String nodes = "[{\"id\":\"M-DUP\",\"source\":\"M-DUP\",\"target\":\"M-DUP\","
                + "\"data\":{\"skuId\":\"M-DUP\",\"skuCode\":\"LY-002\",\"name\":\"生抽酱油\"}}]";
        jdbc.update("UPDATE product_process_workflows SET nodes_json = ? FORMAT JSON WHERE id = ?", nodes, draft);

        controller.merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", null));

        String replaced = nodes(draft);
        assertThat(replaced).contains("\"id\":\"M-DUP\"")
                .contains("\"source\":\"M-DUP\"")
                .contains("\"target\":\"M-DUP\"")
                .contains("\"skuId\":\"M-MASTER\"")
                .contains("\"skuCode\":\"LY-001\"")
                .contains("\"name\":\"酱油\"");
    }

    @Test
    @DisplayName("主物料存在同供应商软删记录时预检拒绝, 不停用重复物料; 无软删记录的正向迁移由已有用例覆盖")
    void mergeBlocksSupplierTombstoneWithoutMutatingDuplicate() {
        supplierMaterial("SM-TOMBSTONE", F, "S-1", "M-MASTER", true);
        jdbc.update("UPDATE supplier_materials SET deleted_at = CURRENT_TIMESTAMP WHERE id = 'SM-TOMBSTONE'");
        supplierMaterial("SM-DUP", F, "S-1", "M-DUP", true);

        MaterialMergeDTO.MergeCheck check = check();
        assertThat(check.isCanMerge()).isFalse();
        assertThat(check.getBlockers()).extracting(MaterialMergeDTO.Blocker::getCode)
                .contains("SUPPLIER_TOMBSTONE");

        assertThatThrownBy(() -> controller.merge(F, "M-DUP",
                new MaterialMergeDTO.MergeRequest("M-MASTER", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("现在还不能合并")
                .hasMessageContaining("主物料供货记录已删除");
        assertThat(materialRepo.findById("M-DUP").orElseThrow().getMergedIntoId()).isNull();
        assertThat(supplierRow("SM-DUP"))
                .containsEntry("MATERIAL_TYPE_ID", "M-DUP")
                .containsEntry("ACTIVE", true);
    }

    @Test
    @DisplayName("阴性对照: 单位不同不自动替换, 草稿 / 供应商 / 采购单都拦住, 一行不动")
    void differentUnitsKeepBlocking() {
        RawMaterialType bottled = materialRepo.findById("M-DUP").orElseThrow();
        bottled.setUnit("瓶");
        materialRepo.saveAndFlush(bottled);
        long draft = realWorkflow(F, "DRAFT", "M-DUP", "LY-002", "生抽酱油");
        supplierMaterial("SM-ON", F, "S-2", "M-DUP", true);
        purchaseOrder("PO-OPEN", F, "APPROVED", "M-DUP");

        MaterialMergeDTO.MergeCheck check = check();
        assertThat(check.isCanMerge()).isFalse();
        assertThat(check.getReplacements()).isEmpty();
        assertThat(check.getBlockers()).extracting(MaterialMergeDTO.Blocker::getCode)
                .containsExactly("DRAFT_WORKFLOW", "SUPPLIER", "OPEN_PURCHASE_ORDER");
        assertThat(check.getBlockers().get(1).getHint()).contains("单位不同");

        assertThatThrownBy(() -> controller.merge(F, "M-DUP", new MaterialMergeDTO.MergeRequest("M-MASTER", null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("现在还不能合并");
        entityManager.flush();
        assertThat(nodes(draft)).contains("M-DUP");
        assertThat(supplierRow("SM-ON")).containsEntry("MATERIAL_TYPE_ID", "M-DUP");
        assertThat(orderItem("PO-OPEN")).containsEntry("MATERIAL_TYPE_ID", "M-DUP");
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

    /** 线上真实节点形态: 物料节点 data 带 skuId / skuCode / name, 工序节点 ports 带 skuId / materialName。 */
    private long realWorkflow(String factoryId, String status, String materialId, String code, String name) {
        workflow(factoryId, status, "IGNORED");
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM product_process_workflows", Long.class);
        String nodes = "[{\"id\":\"material:raw\",\"kind\":\"RAW_MATERIAL\",\"data\":{\"name\":\"" + name
                + "\",\"bound\":true,\"skuId\":\"" + materialId + "\",\"skuCode\":\"" + code + "\",\"baseUnit\":\"kg\"}},"
                + "{\"id\":\"material:other\",\"kind\":\"RAW_MATERIAL\",\"data\":{\"name\":\"牛肉\",\"skuId\":\"M-BEEF\",\"skuCode\":\"NR-1\"}},"
                + "{\"id\":\"process:1\",\"data\":{\"ports\":[{\"id\":\"in\",\"unit\":\"kg\",\"skuId\":\"" + materialId
                + "\",\"materialName\":\"" + name + "\"}]}}]";
        jdbc.update("UPDATE product_process_workflows SET nodes_json = ? FORMAT JSON WHERE id = ?", nodes, id);
        return id;
    }

    private String nodes(long workflowId) {
        return jdbc.queryForObject("SELECT CAST(nodes_json AS VARCHAR) FROM product_process_workflows WHERE id = ?",
                String.class, workflowId);
    }

    private Map<String, Object> supplierRow(String id) {
        return jdbc.queryForMap("SELECT material_type_id, active, preferred FROM supplier_materials WHERE id = ?", id);
    }

    private Map<String, Object> orderItem(String orderId) {
        return jdbc.queryForMap("SELECT material_type_id, material_name FROM purchase_order_items WHERE purchase_order_id = ?", orderId);
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
        purchaseOrder(id, factoryId, status, materialId, 0);
    }

    private void purchaseOrder(String id, String factoryId, String status, String materialId, double received) {
        jdbc.update("INSERT INTO purchase_orders (id, factory_id, order_number, purchase_type, order_date, status, "
                        + "created_by, version, purchase_invoice_status, vflag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DIRECT', CURRENT_DATE, ?, 1, 0, 'NOT_RECEIVED', 'UNCREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, factoryId, id, status);
        jdbc.update("INSERT INTO purchase_order_items (purchase_order_id, material_type_id, quantity, unit, price_unit, "
                        + "quantity_to_price_factor, received_quantity, created_at, updated_at) "
                        + "VALUES (?, ?, 10, 'kg', 'kg', 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, materialId, received);
    }
}
