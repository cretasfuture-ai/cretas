package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.dto.material.CreateMaterialCodeSegmentRequest;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.entity.AlertThreshold;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.entity.config.FormTemplate;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.recipe.ProcessMaterialRecipe;
import com.cretas.aims.entity.recipe.ProcessMaterialRecipeItem;
import com.cretas.aims.blueprint.canonical.CanonicalBlueprintActorAuthority;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.repository.AlertThresholdRepository;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.interview.FactoryBuildPlanSnapshotRepository;
import com.cretas.aims.entity.interview.FactoryBuildPlanSnapshot;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.FormTemplateRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.canvas.ThresholdKeyCatalog;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.factory.FactoryWarehouseService;
import com.cretas.aims.service.material.MaterialCodeSegmentService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.util.EntityNameNormalizer;
import com.cretas.aims.util.ErrorSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 建厂配置<b>落库</b> —— 「生成与落库分离」里的确认那一步。
 *
 * <h2>为什么是回传配置而不是按草稿 ID 落库</h2>
 *
 * 平台管理员<b>应该</b>能在落库前改 AI 给的配置(那正是预览的意义)。所以入参是
 * 客户端回传的配置, 但服务端用与生成侧同一套规则<b>重新校验</b>, 客户端改坏了照样拒。
 *
 * <h2>全量校验先于任何写入</h2>
 *
 * ⛔ 不做「能写的先写、坏的跳过」—— 那会留下一个配了一半的工厂, 而且没人知道差哪儿。
 * 任何一条不合规 → 整个请求 400 并逐条列出原因, 一行都不写。
 * {@code @Transactional} 是第二重保险(写入中途失败也整体回滚)。
 *
 * <h2>2026-08-12 从 controller 抽出</h2>
 *
 * 原文逐字搬运, 事务跨度未变(见 {@link #apply} 的注释)。
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class FactoryBuildApplyService {

    @Autowired
    private CanonicalBlueprintActorAuthority canonicalActorAuthority;

    @Autowired
    private FactoryBuildPlanSnapshotRepository planSnapshotRepository;

    @Autowired
    private FormTemplateRepository formTemplateRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private ConversionRepository conversionRepository;

    @Autowired
    private WorkProcessRepository workProcessRepository;

    @Autowired
    private ProcessMaterialRecipeRepository processMaterialRecipeRepository;

    @Autowired
    private FactoryWarehouseService factoryWarehouseService;

    @Autowired
    private ProductWorkProcessRepository productWorkProcessRepository;

    /**
     * 后端 API 门禁那一层 —— 与 {@code featureConfigRepository}(前端可见性)是**两层**。
     * 见 {@link InterviewModuleConfigCatalog} 类注释「关掉一个模块其实要关两层」。
     */
    @Autowired
    private com.cretas.aims.repository.config.FactoryConfigurationRepository factoryConfigurationRepository;

    @Autowired
    private com.cretas.aims.repository.config.FactoryModuleConfigRepository factoryModuleConfigRepository;

    /**
     * 指标定义 —— 客户说「不用看」的那些在这里置 {@code is_active=false}。
     * ⚠️ 可选注入: 与 {@code FactoryServiceImpl} 里那个同理, 没部署制造模块的部署没有这个 bean。
     */
    @Autowired(required = false)
    private com.cretas.aims.repository.indicator.IndicatorRepository indicatorRepository;

    /**
     * 单位落库口径的**全系统唯一承载点**(Steve 2026-08-03 拍板, 7 个 PR 已上线)。
     *
     * ⛔ 建厂原先完全不走它 —— 产品/工序的单位直通落库(实测 8 个调用点里没有建厂)。
     *    自己再判一遍等于第二套口径, 而 UnitContractService 的四条规则里
     *    有一条是拿事故换来的: 别名组含多个中文写法时**保用户字面** ——
     *    「只」被归一成 pcs 造成 LIUSHANMEN 2026-07-30 报工看不见整批 501 只。
     */
    @Autowired
    private UnitContractService unitContractService;

    /**
     * 包装换算的唯一承载点 —— {@code product_unit_conversions} 的 PACKAGING/NET_CONTENT 行
     * 只能由它产生。⛔ 不要改用 ProductUnitConversionService: 那是给人工 UI 的,
     * 要 system:read_write 权限 + 乐观锁, 且会写成 MANUAL 行挡住后续同步。
     */
    @Autowired
    private ProductSpecificationConversionSyncService specificationConversionSyncService;

    /**
     * 箱规({@code product_packaging_specs})的唯一承载点。
     *
     * <p>⛔ 2026-08-12 实测漏接: 上面那个 sync 产出的是 {@code product_unit_conversions},
     * 而**同一份数据的另一个载体**是 {@code product_packaging_specs} ——
     * 两者由 {@code ProductTypeServiceImpl} 在 create/update 时**成对**维护
     * (它是 {@code synchronizeLegacyDefault} 仅有的两个调用点)。
     * 建厂 apply 直接 {@code repository.save} 绕过了那个 service, 于是只接上了一半:
     * 实测建完的厂 {@code product_types} 十行都有 level1_unit+系数,
     * 而 {@code product_packaging_specs} <b>0 行</b>。
     *
     * <p>后果不报错但会**静默丢信息**: {@code resolveSelection} 见空集合直接
     * {@code PackagingSelection.none()}, 销售订单行的 packagingSpecId/Unit/Factor 全留 null
     * —— 客户在访谈里说的「一箱装20袋」到不了销售那张单子上。
     */
    @Autowired
    private ProductPackagingSpecService productPackagingSpecService;

    @Autowired
    private RawMaterialTypeService rawMaterialTypeService;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private AlertThresholdRepository alertThresholdRepository;

    /**
     * {@code factory_feature_config} 的建行入口(2026-08-12 从 ② 迁入)。
     *
     * ⛔ 迁移前应用层唯一能建这张表的行的是 {@code OnboardingController};
     *    {@code FeatureConfigController} 的 PUT 只改已存在的行。② 删掉之前
     *    必须先有这里, 否则留下「3 个活读者 + 一张再也填不满的表」。
     */
    @Autowired
    private FactoryFeatureConfigRepository featureConfigRepository;

    @Autowired
    private MaterialCodeSegmentService codeSegmentService;

    @Autowired
    private MaterialCodeSegmentRepository codeSegmentRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private FactorySettingsRepository factorySettingsRepository;

    @Autowired
    private ThresholdResolverService thresholdResolver;

    @Autowired
    private AssemblyThresholdWriter assemblyThresholdWriter;

    @Autowired
    private HaccpCheckpointRepository haccpCheckpointRepository;

    /** ⛔ 与 preview 共用同一份校验与同一份计划计算, 不再各写一份。 */
    @Autowired
    private FactoryBuildApplyValidator validator;

    @Autowired
    private FactoryBuildPlanService planner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 把(经人工确认/修改的)配置真正写进库。
     *
     * <h2>⛔ 事务跨度与拆分前<b>逐字相同</b></h2>
     *
     * 拆分前 {@code @Transactional} 挂在 controller 方法上, 于是「校验 → 算计划 →
     * 十几段写入」全在一个事务里。现在它挂在本方法上, 而 controller 在调本方法之前
     * <b>只做三件不碰库的事</b>: 取 token / 查角色 / 从 token 里取 operatorId。
     * 所以事务的起点与终点没有移动。
     *
     * <p>⛔ {@code operatorId} 允许为 null 传进来, 由本方法在<b>校验之后</b>再判 ——
     * 拆分前的语句顺序就是「先 400 再 401」。把 null 检查提到 controller 会让
     * 「载荷不合规 + 令牌里没有用户标识」这种请求从 400 变成 401, 那是行为改动。
     *
     * <p>⚠️ 内部那几个 {@code try/catch} 的语义也没变: 它们仍然跑在本方法的事务里,
     * 接住异常<b>不代表</b>能挡住回滚(撞约束时 Hibernate 已把事务标成 rollback-only)。
     * 这一点在原注释里写着, 搬迁时逐字保留。
     */
    @Transactional
    @CacheEvict(value = {"productTypes", "productTypeOptions"}, key = "#factoryId")
    public ApplyAIInitResult apply(String factoryId, ApplyAIInitRequest request, Long operatorId) {
        return applyAsActor(factoryId, request, operatorId == null ? null
                : new ActorRef(AuthenticatedActorType.FACTORY_USER, operatorId));
    }

    @Transactional
    @CacheEvict(value = {"productTypes", "productTypeOptions"}, key = "#factoryId")
    public ApplyAIInitResult applyAsActor(String factoryId, ApplyAIInitRequest request, ActorRef actor) {
        if (canonicalActorAuthority == null) {
            throw new IllegalStateException("Canonical blueprint authority is not configured");
        }
        canonicalActorAuthority.requireAuthorized(factoryId, actor);
        // 2. 全量校验(先于任何写入)
        List<String> problems = new ArrayList<>(validator.validateApplyRequest(request, factoryId));
        problems.addAll(explicitRouteApplyProblems(request));
        if (!problems.isEmpty()) {
            log.warn("落库 AI 配置被拒: factoryId={}, 问题 {} 条: {}",
                    factoryId, problems.size(), problems);
            throw new BusinessException(400,
                    "配置不合规, 未写入任何内容 (" + problems.size() + " 处): "
                    + String.join("; ", problems))
                    .withHint("请修正后重新提交").withSeverity("error");
        }

        // product_types.created_by 是 NOT NULL 且**无默认值**(实体注解上没标, 库才是权威 ——
        // 2026-08-10 第一次落库就是撞在这条上: 23502 null value in column "created_by")。
        // 记录的是「谁点的确认」, 所以取 JWT 里的操作人。
        if (actor == null) {
            throw new BusinessException(401, "令牌中缺少用户标识, 无法记录操作人")
                    .withSeverity("error");
        }

        boolean overwrite = Boolean.TRUE.equals(request.getOverwrite());
        ApplyAIInitResult result = new ApplyAIInitResult();
        result.setFactoryId(factoryId);

        // ⛔ 计划必须在**动库之前**算, 与预览端点是同一个方法。
        //
        // 第一版我放在所有写入之后, 于是回报的是「写完后的状态」——
        // 刚建好的产品类型变成「跳过」、刚写的工序链变成「覆盖」, 与预览逐条对不上。
        // 闸当场抓到: 预览说新建, 落库回报跳过。
        // 判据: **「会发生什么」只有在它发生之前算, 算出来的才是它本身。**
        result.setPlanned(planner.planChanges(factoryId, request,
                Boolean.TRUE.equals(request.getOverwrite())));

        // 「客户说了而生成端没配出来」的那几条 —— 原样带进回执, 好让它进快照。
        //
        // ⛔ 服务端不产生也不校验它: 它是生成端的产物(存放区没认出来的名字、
        //    工序链对不上的成品、缺阈值的指标…), 这里只负责让它**活过这一次响应**。
        // 🔴 不带的话, 复核界面上这些东西**一处都不出现** —— planned/written/skipped
        //    三份全都对得上, 而客户说的东西少了两件(2026-08-17 实测存放区就是这个形状)。
        // ⛔ **逐字段拷贝**, 不是把请求里的对象直接塞进回执:
        //    这段文字是客户端送来的, 而它会被**存进快照**、日后原样显示给客户。
        //    原样透传等于把一个没看过的对象存下来再展示。
        // ⚠️ 顺带满足 check-apply-field-origin 的第四层判据(「DTO 上有字段 ≠ apply 真的读它」)
        //    —— 那道闸抓到过我第一版就是原样透传。
        if (request.getNotBuilt() != null) {
            List<ApplyAIInitRequest.NotBuiltItem> copied = new ArrayList<>();
            for (ApplyAIInitRequest.NotBuiltItem n : request.getNotBuilt()) {
                if (n == null || isBlank(n.getItem())) {
                    continue;   // 没有名字的条目对客户没有意义, ⛔ 不存进去当噪音
                }
                ApplyAIInitRequest.NotBuiltItem c = new ApplyAIInitRequest.NotBuiltItem();
                c.setGroup(trimOrNull(n.getGroup()));
                c.setItem(n.getItem().trim());
                c.setWhy(trimOrNull(n.getWhy()));
                c.setHowToFix(trimOrNull(n.getHowToFix()));
                // ⚠️ kind 也要逐字段拷 —— 漏了它，「配出来了你没说过」那一类
                //    会在复核里退化成「你说了没配上」，措辞正好相反。
                //    (这一行就是「三处缺一不可」里最容易漏的那一处。)
                c.setKind(trimOrNull(n.getKind()));
                copied.add(c);
            }
            result.setNotBuilt(copied);
            log.info("建厂: 客户说了而没配上的 {} 条 {}", copied.size(),
                    copied.stream().map(ApplyAIInitRequest.NotBuiltItem::getItem).toList());
        }

        // 载荷里的物料码 → 库里实际的码。经 service 建的物料由 service 生成编码,
        // 与载荷不同; 转换率按码连接, 没这张映射下一步就解析不到。
        Map<String, String> materialCodeRemap = new HashMap<>();
        try {
            // 3. schemas → form_templates
            for (ApplyAIInitRequest.SchemaItem s : request.getSchemas()) {
                Optional<FormTemplate> existing = formTemplateRepository
                        .findActiveByFactoryIdAndEntityType(factoryId, s.getEntityType());
                if (existing.isPresent() && !overwrite) {
                    result.getSkippedTemplates().add(s.getEntityType());
                    continue;
                }
                boolean created = existing.isEmpty();
                FormTemplate t = existing.orElseGet(FormTemplate::new);
                if (t.getId() == null) {
                    t.setId(UUID.randomUUID().toString());
                }
                t.setFactoryId(factoryId);
                t.setEntityType(s.getEntityType());
                t.setName(s.getEntityName());
                t.setSchemaJson(objectMapper.writeValueAsString(
                        Map.of("entityType", s.getEntityType(),
                               "entityName", s.getEntityName(),
                               "description", s.getDescription() == null ? "" : s.getDescription(),
                               "fields", s.getFields())));
                t.setDescription(s.getDescription());
                // 这一条正是 countAIGeneratedByFactoryId 一直在等的来源标记
                t.setSource("AI_ASSISTANT");
                t.setIsActive(true);
                if (created) {
                    t.setCreatedActor(actor);
                }
                if (existing.isPresent()) {
                    t.incrementVersion();
                } else {
                    t.setVersion(1);
                }
                formTemplateRepository.save(t);
                result.getWrittenTemplates().add(s.getEntityType());
            }

            // 4. productTypes → product_types (按 factoryId+code 幂等)
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getProductTypes() != null) {
                for (ApplyAIInitRequest.TypeItem p : request.getSuggestedData().getProductTypes()) {
                    Optional<ProductType> ex =
                            productTypeRepository.findByFactoryIdAndCode(factoryId, p.getCode());
                    if (ex.isPresent() && !overwrite) {
                        result.getSkippedProductTypes().add(p.getCode());
                        continue;
                    }
                    boolean created = ex.isEmpty();
                    ProductType pt = ex.orElseGet(ProductType::new);
                    if (pt.getId() == null) {
                        pt.setId(UUID.randomUUID().toString());
                    }
                    pt.setFactoryId(factoryId);
                    pt.setCode(p.getCode());
                    // ⛔ 名字必须过归一 —— LLM 抽取时会在连字符两侧与数字/中文交界处插空格
                    //    (2026-08-12 实测 25 条真实产品名有 17 条被改写)。落库不归一的话,
                    //    ProductTypeRepository#findByFactoryIdAndName(「用于 Excel 导入时按名称
                    //    解析产品类型」)会**查不到, 且静默失败**。
                    pt.setName(EntityNameNormalizer.normalize(p.getName()));
                    pt.setUnit(unitContractService.storageUnit(factoryId, p.getUnit()));
                    pt.setProductCategory(resolveProductCategory(p, pt));
                    // ⛔ level1Unit 存**原文**不过 storageUnit —— 与 ProductTypeServiceImpl 一致,
                    //    归一在 synchronize 内部做(它按 normalize().code() 建图节点键)。
                    pt.setLevel1Unit(p.getLevel1Unit());
                    pt.setBoxConversionCoefficient(p.getBoxConversionCoefficient());
                    pt.setIsActive(true);
                    if (created) {
                        pt.setActor(actor);
                    }
                    productTypeRepository.save(pt);
                    // ⛔ 包装换算不许自己写 product_unit_conversions ——
                    //    PACKAGING/NET_CONTENT 两类行**只能**由 synchronize 产生;
                    //    人工 CRUD 建的是 MANUAL 行, 会把后续 SKU 编辑时的 synchronize 顶成 409。
                    //    这与「建厂物料改走 RawMaterialTypeService」「单位改走 UnitContractService」
                    //    是同一条判据: 域里已有唯一承载点, 新写入口必须接它。
                    //
                    // ⛔ 无条件调, 不要拿「载荷带没带包装规格」当开关(2026-08-12 实测缺陷):
                    //    synchronize 是**同步**不是追加 —— 客户把包装规格清空时, 正是靠它
                    //    退役掉旧的换算边。加了 if 就只在"有规格"时才跑, 于是清空规格后
                    //    product_types 的两列是空的、换算边却还活着, 换算接口照样答
                    //    「1 箱 = 24 斤」。ProductTypeServiceImpl 一直是无条件调的, 这里对齐它。
                    //
                    // ⚠️ 冲突不阻断整次建厂 —— 靠的是 trySynchronize **不抛异常**, 不是 try/catch。
                    //    catch 在这里没用: 异常从内层 @Transactional 出来时事务已被标
                    //    rollback-only, 接住了也回不来(实测整次建厂全量回滚 + 通用 409)。
                    // ⛔ 箱规先于换算边 —— 与 ProductTypeServiceImpl 的顺序一致
                    //    (那边是 synchronizeLegacyDefault 然后 specificationConversionSync)。
                    //    同样用不抛的变体: 一个 SKU 的箱规问题不该炸掉整厂配置,
                    //    而且这条路会撞三条 DB CHECK(系数必须整数 / 两端单位不能相同),
                    //    访谈开始采集客户口述系数之后, 「一箱装2.5斤」是完全可能出现的输入。
                    productPackagingSpecService.synchronizeLegacyDefault(pt);
                    boolean packaging = specificationConversionSyncService.synchronize(pt);
                    if (packaging) {
                        result.getWrittenPackagingConversions().add(p.getCode());
                    }
                    result.getWrittenProductTypes().add(p.getCode());
                }
            }

            // 4.5 codeSegments → material_code_segments
            //
            // ⛔ **必须排在 materialTypes 之前** —— 2026-08-12 实测缺陷。
            //    物料分类走 FactoryBuildApplyValidator#resolveL1Label, 而它是**去库里查**
            //    material_code_segments(level=1)的 —— 段码不在库里, 每个物料都分不出类,
            //    退回直写, category / primary_code 全空。
            //    这一段原来是第 12 步(排在第 5 步 materialTypes 后面 7 步),
            //    于是**新工厂第一次建时段码还不存在**, 分类必然全失败。
            //    (那正是第 5 步注释里「实测库里 17 条物料这两列全空」的成因;
            //     在 codeSegments 变得可产出之前, 这个顺序问题一直被更上游的空值掩盖着。)
            //
            // ⚠️ 下游全是**静默**失败: 库存台账按 category 筛选时 equalsIgnoreCase(null)
            //    永远 false, 那批物料一条都不出现; 调味台账 "003".equals(primaryCode) 同理。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getCodeSegments() != null) {
                for (ApplyAIInitRequest.CodeSegmentItem c : request.getSuggestedData().getCodeSegments()) {
                    String label = c.getLabel() == null ? "" : c.getLabel().trim();
                    boolean exists = codeSegmentRepository.findByFactoryIdAndLevelOrderBySortOrderAscIdAsc(factoryId, (short) 1)
                            .stream().anyMatch(s -> s.getSegmentLabel() != null && s.getSegmentLabel().trim().equalsIgnoreCase(label));
                    if (exists) {
                        result.getSkippedCodeSegments().add(label);
                        continue;
                    }
                    CreateMaterialCodeSegmentRequest req = new CreateMaterialCodeSegmentRequest();
                    req.setLevel((short) 1);
                    req.setSegmentLabel(label);
                    req.setIsActive(true);
                    codeSegmentService.create(factoryId, req);
                    result.getWrittenCodeSegments().add(label);
                }
                // ⛔ 必须 flush: resolveL1Label 是**查库**的, 而上面的写入还攒在 session 里。
                //    不 flush 的话下一步照样查不到, 这次移动等于没做。
                codeSegmentRepository.flush();
            }

            // 5. materialTypes → raw_material_types
            //
            // ⚠️ 2026-08-11 起**优先走 RawMaterialTypeService**, 不再直写仓储。
            //    直写的后果是 category / primary_code 为空, 而下游全是静默失败:
            //    库存台账按 category 筛选时 equalsIgnoreCase(null) 永远 false, 那批物料
            //    一条都不出现; 调味台账 "003".equals(primaryCode) 同样永远 false。
            //    (实测: 库里 17 条物料这两列全空, 全是从这里写进去的。)
            //
            // ⛔ 判定不出分类时**不阻断建厂**, 退回直写并如实记进 unclassifiedMaterialTypes ——
            //    但预览里已经把「未分类」显示出来了, 不是悄悄发生的。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getMaterialTypes() != null) {
                Map<String, String> roles = validator.deriveMaterialRoles(request.getSuggestedData(), new HashSet<>());
                for (ApplyAIInitRequest.TypeItem m : request.getSuggestedData().getMaterialTypes()) {
                    Optional<RawMaterialType> ex =
                            rawMaterialTypeRepository.findByFactoryIdAndCode(factoryId, m.getCode());
                    // ⛔ code 查不到还要**按名字再查一次** —— 2026-08-13 实测缺陷:
                    //    重复 apply 同一个工厂会 409「原料类型名称已存在」。
                    //    幂等键原来只有 code, 而**模型每次生成的 code 都不同、名字相同**,
                    //    于是第二次 apply 查不到就去建, 撞 RawMaterialTypeServiceImpl
                    //    的重名守卫 -> 异常穿过事务边界 -> 整次建厂回滚 + 通用 409。
                    //
                    // ⚠️ 这个守卫是 2026-08 才有的。在它之前(以及分类走不通、退回直写的
                    //    那段时间)重复 apply 会**静默建出一堆同名重复物料** —— 更糟,
                    //    只是没人看见。判重要按**下游真正用来判重的那个键**, 那就是名字。
                    //
                    // ⛔ 名字要过同一个归一器再比: 下游 findByFactoryIdAndName 是按名字解析的,
                    //    LLM 插的空格会让「同一个物料」在两次生成里变成两条。
                    if (ex.isEmpty()) {
                        String normalized = EntityNameNormalizer.normalize(m.getName());
                        ex = rawMaterialTypeRepository.findByFactoryId(factoryId).stream()
                                .filter(row -> normalized.equals(EntityNameNormalizer.normalize(row.getName())))
                                .findFirst();
                        if (ex.isPresent()) {
                            // ⛔ 按名字命中就**一律跳过**, 连 overwrite 也不改它 ——
                            //    这一条在库里是另一个 code。改它的 code 会把外部所有按码
                            //    引用它的地方(单据/台账/导入模板)一次性打断, 而客户的本意
                            //    只是「再跑一次建厂」, 不是给物料改编号。
                            // ⛔ 重映射必须指向**库里那条的码**, 不是载荷里的 ——
                            //    下一步换算率按 materialCodeRemap 解析, 指错就 orElseThrow。
                            log.info("物料按名字命中已有记录, 跳过(载荷码={}, 库里码={}): factoryId={}, name={}",
                                    m.getCode(), ex.get().getCode(), factoryId, normalized);
                            result.getSkippedMaterialTypes().add(m.getCode());
                            materialCodeRemap.put(m.getCode(), ex.get().getCode());
                            continue;
                        }
                    }
                    if (ex.isPresent() && !overwrite) {
                        result.getSkippedMaterialTypes().add(m.getCode());
                        // 已存在的也要能被转换率找到 —— 用它自己的码
                        materialCodeRemap.put(m.getCode(), m.getCode());
                        continue;
                    }
                    boolean created = ex.isEmpty();
                    String l1 = created ? validator.classifyMaterial(factoryId, m, roles) : null;
                    if (l1 != null) {
                        RawMaterialTypeDTO dto = new RawMaterialTypeDTO();
                        // ⛔ 过归一器 —— 与下面的直写分支同一口径。
                        //    service 侧的 normalizeRequiredName 只 trim, 不去 LLM 插的空格;
                        //    两条路存出不同形状的名字, 下一次 apply 按名字判重就对不上,
                        //    于是又建一条同名的(或撞重名守卫 409)。
                        dto.setName(EntityNameNormalizer.normalize(m.getName()));
                        dto.setUnit(m.getUnit());
                        dto.setCategory(l1);
                        dto.setStorageType(m.getStorageType());
                        dto.setCreatedBy(actor.factoryUserId());
                        RawMaterialTypeDTO saved = rawMaterialTypeService.createMaterialTypeAsActor(factoryId, dto, actor);
                        // ⛔ service 自己生成编码(用户配的规则或 UUID), 与载荷里的码不同 ——
                        //    转换率是按码连接的, 不记这张映射就会在下一步解析不到。
                        materialCodeRemap.put(m.getCode(), saved.getCode());
                        // ⛔ 回执记的是**客户在预览上看到并确认的那个码**, 不是 service 生成的码。
                        //    2026-08-16 实测(PreviewMatchesApplyTest#parityMaterialTypes…):
                        //    这里原来记 saved.getCode(), 于是预览列「M-NEW」、回执列「RM…001」——
                        //    客户手上两张单子**没有一个共同的标识符, 对不起来**,
                        //    而目标③ 要的正是「预览 = 实际写入 = 完成回执, 三者对得上」。
                        // ⚠️ 同一个方法里另外三处(skipped ×2、unclassified)记的一直都是 m.getCode(),
                        //    这一处是唯一的异类 —— 同一张清单里混着两套键空间。
                        // ⛔ service 生成的真码没有丢: materialCodeRemap 拿着它(转换率按它解析),
                        //    上面那条 log 也把「载荷码 / 库里码」一起打了出来。
                        result.getWrittenMaterialTypes().add(m.getCode());
                        continue;
                    }
                    RawMaterialType rt = ex.orElseGet(RawMaterialType::new);
                    if (rt.getId() == null) {
                        rt.setId(UUID.randomUUID().toString());
                    }
                    rt.setFactoryId(factoryId);
                    rt.setCode(m.getCode());
                    // ⛔ 同产品名: 过归一, 否则下游按名字的解析会静默失配。
                    rt.setName(EntityNameNormalizer.normalize(m.getName()));
                    rt.setUnit(unitContractService.storageUnit(factoryId, m.getUnit()));
                    rt.setIsActive(true);
                    applyMaterialStorage(rt, m);
                    if (created) {
                        rt.setActor(actor);
                    }
                    rawMaterialTypeRepository.save(rt);
                    materialCodeRemap.put(m.getCode(), m.getCode());
                    result.getWrittenMaterialTypes().add(m.getCode());
                    // ⛔ 2026-08-12: 原来这里是 `if (!ex.isPresent())` —— **已存在的物料
                    //    永远不计入未分类**。而已存在时上面那句 `ex.isPresent() ? null : ...`
                    //    直接跳过了分类, 于是「重复 apply」的读数是
                    //    「写入 14 / 未分类 0」, 而库里 14 条 category 全空 —— **报告在骗人**。
                    //    判据: 这一栏的语义是「这条落库时没有分类」, 与它是不是新建无关。
                    if (isBlank(rt.getCategory())) {
                        result.getUnclassifiedMaterialTypes().add(m.getCode());
                    }
                }
            }

            // 6. conversionRates → material_product_conversions
            // code → id 的解析: 该表的 material_type_id / product_type_id 是 NOT NULL 的 id,
            // 而 AI 给的是 code。能走到这里说明 checkConversions 已经确认每个 code 都可解析
            // (要么在本次请求里, 要么库里已有), 所以这里 orElseThrow 只是兜底不变式,
            // 正常不会触发 —— 真触发了说明校验与写入两处口径漂了, 那要当缺陷查。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getConversionRates() != null) {
                for (ApplyAIInitRequest.ConversionItem c
                        : request.getSuggestedData().getConversionRates()) {
                    String fromCode = c.getFromCode().trim();
                    String toCode = c.getToCode().trim();
                    // ⛔ 走映射: 经 service 建的物料码是 service 生成的, 不是载荷里那个
                    String resolvedFrom = materialCodeRemap.getOrDefault(fromCode, fromCode);
                    String materialId = rawMaterialTypeRepository
                            .findByFactoryIdAndCode(factoryId, resolvedFrom)
                            .map(RawMaterialType::getId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "校验已放行但原料 code 解析不到 id: " + fromCode + "(实际码 " + resolvedFrom + ")"));
                    String productId = productTypeRepository
                            .findByFactoryIdAndCode(factoryId, toCode)
                            .map(ProductType::getId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "校验已放行但产品 code 解析不到 id: " + toCode));

                    // ⛔ 键的写法只有一处定义(FactoryBuildPlanService#conversionKey)——
                    //    这里原来自己拼 `from->to`, 而预览拼的是 `from→to`,
                    //    两张单子对不起来。见那个方法的注释。
                    String pair = FactoryBuildPlanService.conversionKey(fromCode, toCode);
                    Optional<MaterialProductConversion> ex = conversionRepository
                            .findByFactoryIdAndMaterialTypeIdAndProductTypeId(
                                    factoryId, materialId, productId);
                    if (ex.isPresent() && !overwrite) {
                        result.getSkippedConversions().add(pair);
                        continue;
                    }
                    boolean created = ex.isEmpty();
                    MaterialProductConversion mc = ex.orElseGet(MaterialProductConversion::new);
                    if (mc.getId() == null) {
                        mc.setId(UUID.randomUUID().toString());
                    }
                    mc.setFactoryId(factoryId);
                    mc.setMaterialTypeId(materialId);
                    mc.setProductTypeId(productId);
                    mc.setConversionRate(c.getRate());
                    mc.setNotes(c.getNote());
                    mc.setIsActive(true);
                    if (created) {
                        mc.setCreatedActor(actor);
                    }
                    mc.setUpdatedActor(actor);
                    conversionRepository.save(mc);
                    result.getWrittenConversions().add(pair);
                }
            }

            // 7. workProcesses → work_processes (+ 辅料/包材 → process_material_recipes)
            applyWorkProcesses(factoryId, request, overwrite, result);

            // 8. modules → factory_settings, thresholds → factory_thresholds(经 resolver)
            applyTableAnswers(factoryId, request, result);

            // 9. warehouses → factory_warehouses(经 service, 有撞号防呆)
            applyWarehouses(factoryId, request, result);

            // 10. productProcesses → product_work_processes(每个产品自己的工序链)
            applyProductProcesses(factoryId, request, result);

            // 14. metricWatches → alert_thresholds(异常检测那套; 定时任务一直在遍历空表)
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getMetricWatches() != null) {
                for (ApplyAIInitRequest.MetricWatchItem w : request.getSuggestedData().getMetricWatches()) {
                    InterviewAnomalyMetricCatalog.Entry e =
                            InterviewAnomalyMetricCatalog.find(w.getMetricName());
                    if (e == null || Boolean.FALSE.equals(w.getEnabled()) || w.getThreshold() == null) {
                        continue;
                    }
                    if (!alertThresholdRepository.findByFactoryIdAndMetricName(
                            factoryId, e.metricName()).isEmpty()) {
                        result.getSkippedMetricWatches().add(e.label());
                        continue;
                    }
                    AlertThreshold t = AlertThreshold.builder()
                            .factoryId(factoryId)
                            .metricName(e.metricName())
                            // ⚠️ alertType 在这张表里是个自由字符串, 评估器不读它;
                            //    与 metricName 一致是 OnboardingController 既有做法, 沿用。
                            .alertType(e.metricName())
                            .level(InterviewAnomalyMetricCatalog.DEFAULT_LEVEL)
                            .comparison(e.comparison())
                            .staticThreshold(w.getThreshold().doubleValue())
                            .enabled(true)
                            .description("建厂访谈配置: " + e.label())
                            .build();
                    alertThresholdRepository.save(t);
                    result.getWrittenMetricWatches().add(e.label());
                }
            }

            // 15. moduleConfigs → factory_feature_config
            //
            // ⚠️ 口径(别读成 bugfix): 这张表实测 0 行, 但 0 行的含义是
            //    「所有工厂都在跑兜底默认值」, 不是「有东西坏了」——
            //    RN isModuleEnabled() 查不到配置就 return true(全部模块视为启用),
            //    web-admin 那页则显示一个空列表。今天线上没有任何东西在流血。
            //    这一段补的是「① 能不能产出模块配置」这个**从来没有过**的能力。
            //
            // ⛔ 为什么必须在删 ② 之前落地: ② 的 OnboardingController 是应用层
            //    唯一的 FactoryFeatureConfig.builder(); FeatureConfigController 的 PUT
            //    只能改已存在的行(existing.isEmpty() → 400)。先删 ② = 留下
            //    3 个活读者 + 一张再也填不满的表。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getModuleConfigs() != null) {
                for (ApplyAIInitRequest.ModuleConfigItem m
                        : request.getSuggestedData().getModuleConfigs()) {
                    InterviewModuleConfigCatalog.Entry e =
                            InterviewModuleConfigCatalog.find(m.getModuleId());
                    // 校验已在 validateApplyRequest 拦过一遍; 这里是第二道, 不认识就跳过
                    if (e == null) {
                        continue;
                    }
                    // ⛔ 幂等按 (factory_id, module_id) —— 与实体上的唯一约束同一个键。
                    //    已有就跳过而不是覆盖: 客户在「功能模块配置」页关掉过的模块
                    //    也算「已经有了」, 不能被重跑建厂又改回启用。
                    if (featureConfigRepository
                            .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, e.moduleId())
                            .isPresent()) {
                        result.getSkippedModuleConfigs().add(e.label());
                        continue;
                    }
                    // ⚠️ 这个 try 只挡得住「非持久化」的错(比如 config 里塞了序列化不了的东西)。
                    //    真撞到约束冲突时 Hibernate 会把事务标成 rollback-only, 整个 apply
                    //    仍然会在 commit 时回滚 —— 不假装它是万能兜底。
                    try {
                        FactoryFeatureConfig cfg = FactoryFeatureConfig.builder()
                                .factoryId(factoryId)
                                .moduleId(e.moduleId())
                                .moduleName(isBlank(m.getModuleName())
                                        ? e.label() : m.getModuleName().trim())
                                .enabled(!Boolean.FALSE.equals(m.getEnabled()))
                                .config(m.getConfig() != null
                                        ? new HashMap<>(m.getConfig()) : new HashMap<>())
                                .build();
                        featureConfigRepository.save(cfg);
                        result.getWrittenModuleConfigs().add(e.label());
                    } catch (BusinessException be) {
                        throw be;
                    } catch (Exception ex) {
                        log.warn("落库模块配置失败(不阻断建厂): factoryId={}, moduleId={}, err={}",
                                factoryId, e.moduleId(), ErrorSanitizer.sanitize(ex));
                        result.getFailedModuleConfigs().add(e.label());
                    }
                }
            }

            // 15b. moduleConfigs → factory_module_configs(**后端 API 门禁**那一层)
            //
            // ⛔ 15 写的是 factory_feature_config, 那只管**前端可见性**:
            //    RN factoryFeatureStore 拿它门禁屏幕, web-admin 拿它展示。
            //    后端 395 处 @RequireModule 走的是**另一条链**, 从不读那张表。
            //    => 修之前: 客户关掉一个模块, 屏幕藏了, **接口照通**(拿 token 直接打就绕过)。
            //
            // ⚠️ 这一层要生效需要三样, 缺一不可(逐行读 getEffectiveConfig 确认):
            //      ① module_schemas 有这个码            —— PR#82 已把 15 个码补齐
            //      ② 该工厂有 PUBLISHED 的 factory_configurations
            //      ③ factory_module_configs 行的 config_version **精确等于**那个版本号
            //    少了 ②, ③ 写多少行都是死的 —— 实测本地 PUBLISHED 配置 0 个。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getModuleConfigs() != null) {
                applyModuleGateSwitches(factoryId, request, actor, result);
            }

            // 15c. indicatorOptOut → indicators.is_active=false
            //
            // ⛔ 与模块那条成对的减法入口, 但动作不同: 四项定义**建厂时就创建了**
            //    (FactoryIndicatorProvisioner#provision 跑在访谈之前),
            //    所以这里是**停用**不是「不种」。软停用可逆 —— 客户以后想看回来
            //    在 Canvas 里打开即可, 数据不丢。
            // ⚠️ 代价比模块那条轻: 少一格看板 vs 接口 403。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getIndicatorOptOut() != null) {
                applyIndicatorOptOut(factoryId, request, result);
            }

            // 13. alerts → alert_rules(补上 triggerAlert 那句 rules.isEmpty() 的入口)
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getAlerts() != null) {
                for (ApplyAIInitRequest.AlertItem a : request.getSuggestedData().getAlerts()) {
                    InterviewAlertCatalog.Entry entry = InterviewAlertCatalog.find(a.getAlertType());
                    if (entry == null || Boolean.FALSE.equals(a.getEnabled())) {
                        continue;
                    }
                    // ⛔ 幂等查 AndAlertType 而不是 AndEnabledTrue: 客户停用过的规则
                    //    也算「已经有了」, 不能又建一条启用的把人家的决定推翻。
                    if (!alertRuleRepository.findByFactoryIdAndAlertTypeAndEnabledTrue(factoryId, entry.type()).isEmpty()) {
                        result.getSkippedAlerts().add(entry.label());
                        continue;
                    }
                    AlertRule rule = AlertRule.builder()
                            .factoryId(factoryId)
                            .alertType(entry.type())
                            .ruleName(entry.ruleName())
                            .severity(entry.severity())
                            .enabled(true)
                            .notifyChannels(new ArrayList<>(InterviewAlertCatalog.DEFAULT_NOTIFY_CHANNELS))
                            .notifyRoles(new ArrayList<>(entry.notifyRoleCodes()))
                            // SpEL 只有金额类才拼; 其余留 null = 用该类型内置逻辑(见 evaluateRule)
                            .triggerConditionSpel(entry.needsAmount() && a.getAmountThreshold() != null
                                    ? InterviewAlertCatalog.amountSpel(a.getAmountThreshold())
                                    : null)
                            .build();
                    alertRuleRepository.save(rule);
                    result.getWrittenAlerts().add(entry.label());
                }
            }

            // 11. 默认账号 —— ⛔ 只有显式要求才建, 且 provisionDefaultUsers 自身幂等
            //     (本厂已有账号就整体跳过, 不会重复建)。
            if (request.getSuggestedData() != null
                    && Boolean.TRUE.equals(request.getSuggestedData().getProvisionDefaultUsers())) {
                List<com.cretas.aims.dto.user.UserDTO> provisioned =
                        userService.provisionDefaultUsers(factoryId);
                if (provisioned.isEmpty()) {
                    // 🔴 2026-08-17 补的缺口: 预览这一侧会说「跳过(本厂已有 N 个账号,
                    //    不会重复建)」, 而回执**连一个字段都没有** —— 客户看不到这一条的结局。
                    //    目标③ 要求「预览 = 实际写入 = 完成回执」三者对得上。
                    //
                    // ⛔ 「返回空」不用猜: UserServiceImpl#provisionDefaultUsers 返回空
                    //    **只有一个原因** —— 本厂已有 users(幂等跳过)。其余情况要么建 3 个、
                    //    要么抛 EntityNotFoundException。
                    result.getSkippedUsers().add("默认账号(本厂已有账号, 不会重复建)");
                } else {
                    provisioned.forEach(u -> result.getWrittenUsers().add(u.getUsername()));
                }
            }

            // 16. haccpCheckpoints → haccp_checkpoints
            //
            // 🔴 库里 critical_limit_min / critical_limit_max / unit **三列都非空**,
            //    所以「客户没给限值就留空」在这张表上**做不到** —— 缺任一个整条插不进去。
            // ⛔ 也**不许补默认值**: 食品安全的限值编错不是误报, 是安全风险
            //    (与 metricWatches 刻意没有默认阈值同口径, 但后果更重)。
            // ⇒ 唯一正确的行为: **整条跳过, 并且告诉客户跳了哪些** ——
            //    否则就成了「他说了而系统装作没听见」。
            if (request.getSuggestedData() != null
                    && request.getSuggestedData().getHaccpCheckpoints() != null) {
                for (ApplyAIInitRequest.HaccpItem h
                        : request.getSuggestedData().getHaccpCheckpoints()) {
                    // 🔴 ⛔ 这一句不能省, 而且它与其余 15 类的写法**不一样**:
                    //
                    //    haccpCheckpoints 是唯一没有 FactoryBuildApplyValidator 分支的类型
                    //    (那是已登记的刻意不对称, 见 apply-field-reachability.txt),
                    //    ⇒ **本循环是唯一的防线**。其余 15 类的循环体可以不判空
                    //      (例: 存放区那段直接 `w.getName().trim()`), 因为 validator 兜住了。
                    //
                    //    ⚠️ 2026-08-15 **实发**过, 不是推理: 载荷 haccpCheckpoints:[null]
                    //    经 Jackson 绑成含 null 的 list, 走到下一行 h.getName() ⇒ NPE ⇒ 500。
                    //    而这条路径的整个设计就是「整条跳过 + 告诉客户」—— 500 是它最不想要的那一态。
                    //    python 侧对这一段**零校验**(实测含非 dict 元素也放行), 所以 [null]
                    //    是上游真的会送来的形状, 不是构造出来吓自己的。
                    //
                    //    📌 给 HaccpItem 新增字段时, 防御也要写在**这个循环里** ——
                    //      按其余 15 类的习惯写(依赖 validator)在这一类上是错的。
                    if (h == null) {
                        result.getSkippedHaccpCheckpoints().add("(空条目)");
                        continue;
                    }
                    String name = h.getName() == null ? "" : h.getName().trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    // 🔴 2026-08-15 内容审补的三条。此前只判「限值缺不缺」, 而:
                    //  ① hazardType 完全没校验 —— 而**同一张表的另一条写入路径**
                    //     (CanvasFoodSafetyController#createCheckpoint)有白名单且不合法就 400。
                    //     同一件事两条路一条有闸一条没有 ⇒ 模型输出「生物性」会原样落库。
                    //     ⛔ 也不再默认成 BIOLOGICAL —— 那和「编一个限值」是同一种编。
                    //  ② 限值**反了**(min > max)照样能写进去, 而下游算偏离时全错。
                    //  ③ skipped 原先只给名字, 客户看到「跳过:杀菌」不知道为什么。
                    String skipReason = null;
                    if (h.getCriticalLimitMin() == null || h.getCriticalLimitMax() == null
                            || h.getUnit() == null || h.getUnit().trim().isEmpty()) {
                        skipReason = "没给临界限值";
                    } else if (h.getCriticalLimitMin().compareTo(h.getCriticalLimitMax()) > 0) {
                        skipReason = "限值反了(下限 " + h.getCriticalLimitMin()
                                + " > 上限 " + h.getCriticalLimitMax() + ")";
                    } else if (h.getHazardType() == null
                            || !AssemblyHaccpContract.HAZARD_TYPES.contains(h.getHazardType().trim())) {
                        skipReason = "危害类型不合法: " + h.getHazardType()
                                + "(只能是 " + String.join("/", new java.util.TreeSet<>(
                                        AssemblyHaccpContract.HAZARD_TYPES)) + ")";
                    }
                    if (skipReason != null) {
                        result.getSkippedHaccpCheckpoints().add(name + "(" + skipReason + ")");
                        continue;
                    }
                    // ⛔ 幂等键只有一处定义(HaccpCheckpoint#checkpointCode)——
                    //    预览侧要按**同一个**键判「已存在」, 在这里自己拼就必然漂。
                    String code = AssemblyHaccpContract.checkpointCode(name);
                    if (haccpCheckpointRepository
                            .findByFactoryIdAndCheckpointCode(factoryId, code).isPresent()) {
                        result.getSkippedHaccpCheckpoints().add(name + "(已存在)");
                        continue;
                    }
                    HaccpCheckpoint cp = new HaccpCheckpoint();
                    cp.setFactoryId(factoryId);
                    cp.setCheckpointCode(code);
                    cp.setName(name);
                    cp.setHazardType(h.getHazardType().trim());   // 已过白名单
                    cp.setCriticalLimitMin(h.getCriticalLimitMin());
                    cp.setCriticalLimitMax(h.getCriticalLimitMax());
                    cp.setUnit(h.getUnit().trim());
                    cp.setMonitoringProcedure(h.getMonitoringProcedure());
                    haccpCheckpointRepository.save(cp);
                    result.getWrittenHaccpCheckpoints().add(name);
                }
            }

            // ⛔ 把**同一份**计划回给调用方: 闸据此逐条比对「预览说的」与「实际做的」。
            //    两者对不上就是预览在骗人, 而这正是本仓发生过的那类缺陷。

            result.setSuccess(true);
            // 🔴 2026-08-17(owner 批准建表): 把预览那一份**存下来**。
            //    在这之前它只活在这一次响应里(setPlanned 调用点 1 处、落库路径 0 处),
            //    客户把页面一关, 「他当时说要什么」就没了 —— 而段③ 复核的每一屏
            //    要把「说的 / 做的 / 库里的」三份并排, 少了第一份就只剩两份。
            //
            // ⛔ 只在**成功**这一支存: 校验不通过时整次 400、一条都没写,
            //    那时存下来的是「没发生过的事」, 客户复核会看到一份假记录 ——
            //    比取不回更糟。
            savePlanSnapshot(factoryId, result);
            result.setMessage(String.format(
                    "已写入: 表单模板 %d, 产品类型 %d, 原料类型 %d, 转换率 %d, 工序 %d; "
                    + "跳过(已存在且未选覆盖): %d",
                    result.getWrittenTemplates().size(),
                    result.getWrittenProductTypes().size(),
                    result.getWrittenMaterialTypes().size(),
                    result.getWrittenConversions().size(),
                    result.getWrittenWorkProcesses().size(),
                    result.getSkippedTemplates().size()
                        + result.getSkippedProductTypes().size()
                        + result.getSkippedMaterialTypes().size()
                        + result.getSkippedConversions().size()
                        + result.getSkippedWorkProcesses().size()));

            log.info("落库 AI 配置成功: factoryId={}, {}", factoryId, result.getMessage());
            return result;

        } catch (JsonProcessingException je) {
            log.error("落库 AI 配置失败(schema 序列化): factoryId={}", factoryId, je);
            throw new BusinessException(500, "配置序列化失败: " + ErrorSanitizer.sanitize(je), je);
        }
    }

    /**
     * Onboarding-created SKUs must be usable by downstream Workflow selectors.
     * An omitted category means an ordinary finished product for a new row, while
     * an overwrite without this field preserves the existing business identity.
     */
    static void applyMaterialStorage(RawMaterialType material, ApplyAIInitRequest.TypeItem item) {
        if (com.cretas.aims.entity.material.MaterialMainCategory.PACKAGING.equals(material.getMainCategory())) material.setStorageType(null);
        else if (item.getStorageType() != null) material.setStorageType(item.getStorageType());
    }

    static String resolveProductCategory(ApplyAIInitRequest.TypeItem item, ProductType productType) {
        String requested = item == null ? null : item.getProductCategory();
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase(Locale.ROOT);
        }
        if (productType != null && productType.getProductCategory() != null
                && !productType.getProductCategory().isBlank()) {
            return productType.getProductCategory();
        }
        return ProductCategory.FINISHED_PRODUCT;
    }

    /**
     * 显式复杂路线当前只属于 canonical blueprint 的草稿/校验契约。
     *
     * <p>旧 apply 只会把 {@code processNames} 写成线性的 {@code process_order}；如果在这里
     * 忽略 {@code routeNodes/routeEdges}，branch/merge/rework 会在最后一层静默消失，但接口
     * 仍返回 200。必须在任何业务写入前 fail closed，直到后续由版本化 Workflow 合同显式承接。
     */
    static List<String> explicitRouteApplyProblems(ApplyAIInitRequest request) {
        if (request == null || request.getSuggestedData() == null
                || request.getSuggestedData().getProductProcesses() == null) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        List<ApplyAIInitRequest.ProductProcessItem> routes =
                request.getSuggestedData().getProductProcesses();
        for (int index = 0; index < routes.size(); index++) {
            ApplyAIInitRequest.ProductProcessItem route = routes.get(index);
            if (route == null || (route.getRouteNodes() == null && route.getRouteEdges() == null)) {
                continue;
            }
            String endpoints = route.getRouteEdges() == null ? "无连线" : route.getRouteEdges().stream()
                    .filter(Objects::nonNull)
                    .map(edge -> String.valueOf(edge.getFromNodeId()) + "→"
                            + String.valueOf(edge.getToNodeId()))
                    .limit(4)
                    .collect(java.util.stream.Collectors.joining("、"));
            problems.add("productProcesses[" + index + "] 产品「"
                    + Objects.toString(route.getProductCode(), "未命名")
                    + "」包含显式复杂路线（" + endpoints + "）；当前 apply 仅支持线性工序链，"
                    + "必须先由 Workflow 版本合同承接，未写入任何内容");
        }
        return problems;
    }

    /**
     * 模块开关与阈值落库。
     *
     * <h3>⛔ 阈值必须走 resolver.upsert, 不能直接 save</h3>
     *
     * {@code ThresholdResolverService#upsert} 负责落库<b>加缓存失效</b>。
     * 直接 {@code repository.save} 会写进去但读出来还是旧值 ——
     * 库里对、界面上不对, 而且没有任何一层报错。
     */
    private void applyTableAnswers(String factoryId,
                                   ApplyAIInitRequest request,
                                   ApplyAIInitResult result) {
        ApplyAIInitRequest.SuggestedData sd = request.getSuggestedData();
        if (sd == null) {
            return;
        }
        if (sd.getModules() != null && !sd.getModules().isEmpty()) {
            FactorySettings fs = factorySettingsRepository.findByFactoryId(factoryId)
                    .orElseGet(() -> {
                        FactorySettings created = new FactorySettings();
                        created.setFactoryId(factoryId);
                        return created;
                    });
            for (Map.Entry<String, Boolean> e : sd.getModules().entrySet()) {
                try {
                    String setter = "set" + Character.toUpperCase(e.getKey().charAt(0))
                            + e.getKey().substring(1);
                    FactorySettings.class.getMethod(setter, Boolean.class).invoke(fs, e.getValue());
                    result.getWrittenModules().add(e.getKey() + "=" + e.getValue());
                } catch (ReflectiveOperationException ex) {
                    // 校验已放行才会走到这 —— 真发生了说明校验与写入两处口径漂了, 当缺陷查
                    throw new IllegalStateException(
                            "校验放行的开关字段写不进去: " + e.getKey(), ex);
                }
            }
            factorySettingsRepository.save(fs);
        }
        if (sd.getThresholds() != null) {
            for (ApplyAIInitRequest.ThresholdItem t : sd.getThresholds()) {
                String key = t.getKey().trim();
                assemblyThresholdWriter.upsert(factoryId, key, t.getValue().trim());
                result.getWrittenThresholds().add(key + "=" + t.getValue().trim());
            }
        }
    }

    /**
     * 工序落库 —— 工序本体 + 辅料/包材配方。
     *
     * <h3>为什么这段值得存在</h3>
     *
     * 此前 apply 只写 form_templates / product_types / raw_material_types / conversions,
     * <b>work_processes 一行都不写</b>。而访谈每道工序问 8 项 ——
     * 问到了却没有落地表, 等于问完就丢(这个形状我今天已经在描述拼装上栽过一次)。
     *
     * <h3>幂等键与覆盖</h3>
     *
     * 键是 {@code factoryId + processName}(库里就是这么查重的)。已存在且未选覆盖 → 跳过并如实报告,
     * 与本方法之外的四类写入保持同一口径。
     *
     * <h3>⛔ 配方按「先删后插」重建, 不做逐条 merge</h3>
     *
     * 辅料/包材是<b>一整张清单</b>: 客户说「第 2 道加卤料包、香辛料、老汤」, 改口说
     * 「不用老汤了」时, 逐条 merge 会把老汤留在库里 —— 而访谈里没有「删除某条辅料」这个动作,
     * 客户永远删不掉它。整张替换才是访谈语义。
     */
    private void applyWorkProcesses(String factoryId,
                                    ApplyAIInitRequest request,
                                    boolean overwrite,
                                    ApplyAIInitResult result) {
        if (request.getSuggestedData() == null
                || request.getSuggestedData().getWorkProcesses() == null) {
            return;
        }
        for (ApplyAIInitRequest.WorkProcessItem w : request.getSuggestedData().getWorkProcesses()) {
            String name = w.getName().trim();
            List<WorkProcess> existing = workProcessRepository.findByFactoryIdAndProcessName(factoryId, name);
            if (!existing.isEmpty() && !overwrite) {
                result.getSkippedWorkProcesses().add(name);
                continue;
            }
            WorkProcess wp = existing.isEmpty() ? new WorkProcess() : existing.get(0);
            if (wp.getId() == null) {
                wp.setId(UUID.randomUUID().toString());
            }
            wp.setFactoryId(factoryId);
            wp.setProcessName(name);
            wp.setProcessCategory(trimOrNull(w.getCategory()));
            wp.setSortOrder(w.getSortOrder() == null ? 0 : w.getSortOrder());
            wp.setUnit(unitContractService.storageUnit(factoryId, w.getUnit()));
            wp.setOutputUnit(trimOrNull(w.getOutputUnit()));
            wp.setStandardYieldMin(w.getYieldMin());
            wp.setStandardYieldMax(w.getYieldMax());
            wp.setDescription(trimOrNull(w.getDescription()));
            wp.setIsActive(true);
            wp.setDefaultOutputMaterialKind(isBlank(w.getOutputKind())
                    ? WorkProcessOutputMaterialKind.SEMI_FINISHED
                    : WorkProcessOutputMaterialKind.valueOf(w.getOutputKind().trim()));
            // 校验已放行(见 checkWorkProcesses), 这里直接存。
            // null 表示「这次没提」, 与「明确清空(空列表)」不同 —— 与 WorkProcessServiceImpl 同语义。
            if (w.getCustomFields() != null) {
                wp.setCustomFieldSchema(w.getCustomFields());
            }
            if (w.getByproducts() != null) {
                wp.setExpectedByproducts(w.getByproducts());
            }
            workProcessRepository.save(wp);
            result.getWrittenWorkProcesses().add(name);

            // ⛔ costUnit 按 RecipeType 的口径给, 不能一律写 kg:
            //    枚举自己的注释写明 SEASONING = 元/kg「投入量」、PACKAGING = 元/盒「产出量」。
            //    包装工序的产出单位常常不是 kg(实测 F006 是「盒」、AQUA 是「箱」),
            //    写成 kg 会让每盒成本按公斤算 —— 数字照样出得来, 只是差一个数量级。
            writeRecipe(factoryId, wp.getId(), ProcessMaterialRecipe.RecipeType.SEASONING,
                    w.getAuxMaterials(), wp.getUnit());
            writeRecipe(factoryId, wp.getId(), ProcessMaterialRecipe.RecipeType.PACKAGING,
                    w.getPackMaterials(),
                    wp.getOutputUnit() == null ? wp.getUnit() : wp.getOutputUnit());
        }
    }

    /**
     * 存放区落库。走 {@link FactoryWarehouseService#create} 而不是直接存 repository ——
     * 那里有工厂内编号唯一的防呆(撞号抛 409), 绕过去会让两个同号仓静默共存。
     */
    private void applyWarehouses(String factoryId, ApplyAIInitRequest request,
                                 ApplyAIInitResult result) {
        if (request.getSuggestedData() == null || request.getSuggestedData().getWarehouses() == null) {
            return;
        }
        for (ApplyAIInitRequest.WarehouseItem w : request.getSuggestedData().getWarehouses()) {
            String code = w.getCode().trim();
            if (factoryWarehouseService.findByCode(factoryId, code).isPresent()) {
                result.getSkippedWarehouses().add(code);
                continue;
            }
            factoryWarehouseService.create(factoryId, code, w.getName().trim(),
                    FactoryWarehouse.WarehouseType.valueOf(w.getType().trim()),
                    Boolean.TRUE, w.getRemarks());
            result.getWrittenWarehouses().add(code);
        }
    }

    /**
     * 产品×工序绑定落库。
     *
     * <p>⛔ 按产品**整条链替换**, 不逐条 merge。客户改口说「这个产品不过油炸了」时,
     *    逐条 merge 会把油炸留在链里 —— 而访谈里没有「删除某道工序」这个动作,
     *    客户永远删不掉它。整条替换才是访谈语义(与辅料/包材配方同一口径)。
     */
    private void applyProductProcesses(String factoryId, ApplyAIInitRequest request,
                                       ApplyAIInitResult result) {
        if (request.getSuggestedData() == null
                || request.getSuggestedData().getProductProcesses() == null) {
            return;
        }
        for (ApplyAIInitRequest.ProductProcessItem pp : request.getSuggestedData().getProductProcesses()) {
            String code = pp.getProductCode().trim();
            String productId = productTypeRepository.findByFactoryIdAndCode(factoryId, code)
                    .map(ProductType::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "校验已放行但产品 code 解析不到 id: " + code));
            productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(factoryId, productId)
                    .forEach(productWorkProcessRepository::delete);
            // ⛔ 必须 flush —— 2026-08-12 实测: 少了这一行, **第二次 apply 必 409**。
            //    product_work_processes 上有 UNIQUE(factory_id, product_type_id, work_process_id),
            //    而 Hibernate 的动作队列是**先 INSERT 后 DELETE**: 删除攒在 session 里等提交,
            //    下面的 save 先落地 -> duplicate key -> 事务标 rollback-only ->
            //    整次建厂回滚, 用户拿到通用的「数据已存在，请勿重复提交」。
            //
            // ⚠️ 这段代码在 2026-08-12 之前**从来没跑过**: productProcesses 在生成链路上
            //    根本产不出来(见 apply-field-reachability), 所以这个缺陷一直潜伏。
            //    把那个字段接通的同一个 PR 让它变得可达 —— 首次 apply(0 行)看不出来,
            //    第二次才炸。判据: **改成「现在有数据流过来了」时, 要按【第二次】也跑一遍。**
            productWorkProcessRepository.flush();
            int order = 0;
            for (String name : pp.getProcessNames()) {
                String pn = name.trim();
                String processId = workProcessRepository
                        .findByFactoryIdAndProcessName(factoryId, pn).stream().findFirst()
                        .map(WorkProcess::getId)
                        .orElseThrow(() -> new IllegalStateException(
                                "校验已放行但工序名解析不到 id: " + pn));
                order++;
                ProductWorkProcess link = new ProductWorkProcess();
                link.setFactoryId(factoryId);
                link.setProductTypeId(productId);
                link.setWorkProcessId(processId);
                link.setProcessOrder(order);
                link.setIsActive(true);
                productWorkProcessRepository.save(link);
            }
            result.getWrittenProductProcesses().add(code + "×" + order + "道");
        }
    }

    /** 整张替换某工序某类型的配方; 传 null 表示这次没提, 不动库里已有的。 */
    /**
     * 把这一次的预览快照存下来 —— 段③ 复核的「说了什么」那一份。
     *
     * <p>⛔ <b>存不下来不许让建厂失败</b>：厂已经建好了，快照只是复核用的辅助记录。
     * 让它把一次成功的建厂翻成 500，是拿主功能给辅助功能背锅。
     * ⚠️ 但也<b>不许静默</b> —— 失败要留日志，否则复核那边只会看到「没有记录」
     * 而不知道为什么（那正是本仓「跳过的必须说清为什么」的同一条纪律）。
     */
    private void savePlanSnapshot(String factoryId, ApplyAIInitResult result) {
        try {
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(result.getPlanned());
            // 🔴 2026-08-17 补: 「实际写的」那一份和 planned **处境完全一样** ——
            //    也只活在这一次响应里。设计卡把它标成「已有」是错的(当时只查了 setPlanned)。
            //    而它恰恰带着「**为什么**跳过」(`杀菌(已存在)` 那种后缀), 少了它,
            //    复核只能说「要建 26 条」和「库里有多少」, 中间那句说不出来。
            String resultJson = om.writeValueAsString(result);
            planSnapshotRepository.save(FactoryBuildPlanSnapshot.builder()
                    .factoryId(factoryId)
                    .plannedJson(json)
                    .plannedCount(result.getPlanned() == null ? 0 : result.getPlanned().size())
                    .resultJson(resultJson)
                    .build());
            log.info("建厂预览快照已存: factoryId={}, 条数={}",
                    factoryId, result.getPlanned() == null ? 0 : result.getPlanned().size());
        } catch (Exception e) {                                  // noqa: BLE001
            log.error("建厂预览快照存失败(不影响本次建厂, 但复核会缺「说了什么」那一份): "
                    + "factoryId={}", factoryId, e);
        }
    }

    /**
     * 取回这个工厂<b>最后一次</b>建厂的预览快照；没有就返回空表。
     *
     * <p>⚠️ 返回空表与「那次预览确实是 0 条」<b>在这里不可区分</b> ——
     * 调用方要区分就看 {@code plannedCount}（快照自己存了一份条数）。
     */
    public List<PlannedChange> lastPlanSnapshot(String factoryId) {
        return planSnapshotRepository.findFirstByFactoryIdOrderByCreatedAtDesc(factoryId)
                .map(snap -> {
                    try {
                        return new ObjectMapper().readValue(
                                snap.getPlannedJson(),
                                new com.fasterxml.jackson.core.type.TypeReference
                                        <List<PlannedChange>>() {});
                    } catch (Exception e) {                      // noqa: BLE001
                        log.error("建厂预览快照读不出来: factoryId={}, id={}",
                                factoryId, snap.getId(), e);
                        return List.<PlannedChange>of();
                    }
                })
                .orElseGet(List::of);
    }

    /**
     * 段③ 复核要的那一份 —— 把「当时说的」与「当时做的」一起取回来。
     *
     * <h3>⛔ 读快照，不重算</h3>
     *
     * 这里<b>不</b>再调 {@code planChanges} —— 那会是「现在重新预览一次」，
     * 而客户要看的是<b>当时</b>说了什么。库已经被那次 apply 改过了，
     * 重算出来的东西每一条都会变成「已存在/跳过」，与他当时确认的那份对不上。
     *
     * <h3>⚠️ 这里**不做**跨两份的分组对齐</h3>
     *
     * 预览分组是中文（「产品类型」），回执清单是英文驼峰（{@code writtenProductTypes}），
     * 两者的对应关系收敛在 {@code scripts/tests/preview-group-to-result.txt} 里 ——
     * 而那是<b>给闸读的声明，不在运行时 classpath 上</b>。
     * ⛔ 在这里手写第二份映射就是形态 D（同一个东西两份，一定会漂）。
     *
     * <p>⇒ 本方法只把两份原样交出去，外加一个<b>按分组的条数统计</b>。
     * 真正的并排留给复核的每一屏 —— 那时每屏都是<b>具体某一类</b>的页面，
     * 它自己知道对应哪个清单，映射退化成每屏一个常量，不需要全局映射表。
     *
     * @return 三个键：{@code planned}（当时说的）/ {@code result}（当时做的）/
     *         {@code plannedByGroup}（按分组条数）。⚠️ 没有快照时三个都为空，
     *         ⛔ 调用方要把「没建过厂」与「建过但什么都没写」分开说 —— 见 {@code plannedCount}。
     */
    public Map<String, Object> reviewSnapshot(String factoryId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<PlannedChange> planned = lastPlanSnapshot(factoryId);
        out.put("planned", planned);

        Map<String, Integer> byGroup = new LinkedHashMap<>();
        for (PlannedChange c : planned) {
            String g = c.getGroup() == null ? "(未分组)" : c.getGroup();
            byGroup.merge(g, 1, Integer::sum);
        }
        out.put("plannedByGroup", byGroup);

        out.put("result", planSnapshotRepository
                .findFirstByFactoryIdOrderByCreatedAtDesc(factoryId)
                .map(snap -> {
                    // ⚠️ result_json 是 2026-08-17 之后才加的列, 老行是 null ——
                    //    ⛔ 「那次没存」(null)与「那次真的什么都没写」(各清单皆空)
                    //    必须分得开, 所以这里返回 null 而不是一个空对象。
                    if (snap.getResultJson() == null) {
                        log.info("这次快照没有「实际写的」那一份(在补这一列之前存的): "
                                + "factoryId={}, id={}", factoryId, snap.getId());
                        return null;
                    }
                    try {
                        return new ObjectMapper().readValue(
                                snap.getResultJson(), ApplyAIInitResult.class);
                    } catch (Exception e) {                      // noqa: BLE001
                        log.error("建厂回执快照读不出来: factoryId={}, id={}",
                                factoryId, snap.getId(), e);
                        return null;
                    }
                })
                .orElse(null));
        return out;
    }

    private void writeRecipe(String factoryId, String workProcessId, ProcessMaterialRecipe.RecipeType type,
                             List<ApplyAIInitRequest.RecipeItem> items, String costUnit) {
        if (items == null) {
            return;
        }
        List<ProcessMaterialRecipe> old = processMaterialRecipeRepository
                .findActiveByFactoryIdAndWorkProcessId(factoryId, workProcessId);
        for (ProcessMaterialRecipe r : old) {
            if (r.getRecipeType() == type) {
                r.setDeletedAt(LocalDateTime.now());
                r.setIsActive(false);
                processMaterialRecipeRepository.save(r);
            }
        }
        if (items.isEmpty()) {
            return;   // 明确清空
        }
        ProcessMaterialRecipe recipe = new ProcessMaterialRecipe();
        recipe.setFactoryId(factoryId);
        recipe.setWorkProcessId(workProcessId);
        recipe.setRecipeType(type);
        recipe.setIsActive(true);
        recipe.setCostUnit(costUnit);
        // ⛔ unit_cost 是 NOT NULL。访谈拿不到单价时置 0 并在 remark 里写明「待补」——
        //    置 0 本身不是「猜」, 但如果不写明来源, 下游会把 0 当成「这道工序不花钱」。
        BigDecimal total = BigDecimal.ZERO;
        List<ProcessMaterialRecipeItem> rows = new ArrayList<>();
        boolean anyPriced = false;
        for (ApplyAIInitRequest.RecipeItem it : items) {
            ProcessMaterialRecipeItem row = new ProcessMaterialRecipeItem();
            row.setRecipe(recipe);
            row.setMaterialName(it.getName().trim());
            row.setQuantity(it.getQuantity());
            row.setQuantityUnit(trimOrNull(it.getUnit()));
            row.setUnitPrice(it.getUnitPrice());
            row.setRemark(trimOrNull(it.getRemark()));
            if (it.getUnitPrice() != null && it.getQuantity() != null) {
                BigDecimal line = it.getUnitPrice().multiply(it.getQuantity());
                row.setLineCost(line);
                total = total.add(line);
                anyPriced = true;
            }
            rows.add(row);
        }
        recipe.setUnitCost(total);
        if (!anyPriced) {
            recipe.setRemark("对话建厂生成：单价待补，当前成本按 0 计");
        }
        recipe.setItems(rows);
        processMaterialRecipeRepository.save(recipe);
        log.info("工序配方写入: process={}, type={}, {} 条, 合计 {}",
                workProcessId, type, rows.size(), total);
    }

    /**
     * 把客户在访谈里关掉的模块, 落成**后端 API 门禁**开关(factory_module_configs)。
     *
     * <h3>⛔ 为什么要先确保有 PUBLISHED 配置</h3>
     *
     * {@code getEffectiveConfig} 的第二层整段包在 {@code publishedConfig.isPresent()} 里,
     * 而且行的 {@code config_version} 必须**精确等于**那个 published 版本号。
     * 没有 published 配置时, 写多少行开关都读不到。
     *
     * <h3>三种情形, 只在第三种才建配置版本</h3>
     * <ol>
     *   <li>已有 PUBLISHED → 用它的版本号写开关, <b>不新建、不改状态</b></li>
     *   <li>没有 PUBLISHED 但已有其它版本(DRAFT/PENDING_REVIEW/APPROVED) →
     *       <b>整段跳过</b>。工厂已经在走 Canvas 的审批流程, 建厂不该插一脚:
     *       表上有 {@code (factory_id, config_version)} 唯一索引, 而且
     *       直接塞一个 PUBLISHED 会把人家待审的版本架空。原因写进 {@code moduleGateNote}。</li>
     *   <li>一条配置版本都没有 → 建 v1 并直接 PUBLISHED</li>
     * </ol>
     *
     * ⚠️ 第三种是「把原设计补完」而不是新发明:
     * {@code OnboardingModuleConfigProvisioner.ONBOARDING_CONFIG_VERSION = 1},
     * 且 {@code FactoryConfiguration}/{@code FactoryModuleConfig} 的 configVersion
     * 默认都是 1 —— 「建厂产出 v1」本来就是这套东西的意图, 只是那一半没写完。
     *
     * <h3>安全性</h3>
     *
     * 写 {@code enabled=true} 的行是**行为无变化**的: 无行时 getEffectiveConfig 也按
     * true 处理, {@code renderingMode} 两边同为 {@code "LEGACY"}(实体默认值),
     * 而 fieldConfig/workflowConfig 写空 Map 走 deepMerge 也是恒等。
     * 真正改变行为的只有客户**明确关掉**的那些 —— 那正是本段的目的。
     */
    private void applyModuleGateSwitches(String factoryId, ApplyAIInitRequest request,
                                         ActorRef actor, ApplyAIInitResult result) {
        Optional<com.cretas.aims.entity.config.FactoryConfiguration> published =
                factoryConfigurationRepository.findLatestPublished(factoryId);
        int version;
        if (published.isPresent()) {
            version = published.get().getConfigVersion();
        } else if (!factoryConfigurationRepository.findByFactoryIdOrderByConfigVersionDesc(factoryId).isEmpty()) {
            result.setModuleGateNote("工厂已有未发布的配置版本(Canvas 审批流程在途), "
                    + "建厂不新建配置版本 —— 后端门禁开关本次未写, 请在 Canvas 发布后再配。");
            log.info("模块门禁开关跳过: factoryId={} 已有未发布配置版本", factoryId);
            return;
        } else {
            com.cretas.aims.entity.config.FactoryConfiguration cfg =
                    new com.cretas.aims.entity.config.FactoryConfiguration();
            cfg.setFactoryId(factoryId);
            cfg.setConfigVersion(1);
            cfg.setStatus("PUBLISHED");
            cfg.setPublishedAt(java.time.LocalDateTime.now());
            cfg.setPublishedActor(actor);
            cfg.setCreatedActor(actor);
            cfg.setChangeSummary("对话建厂产出的初始配置版本");
            factoryConfigurationRepository.save(cfg);
            version = 1;
            log.info("模块门禁: 工厂 {} 之前没有任何配置版本, 建 v1 并发布", factoryId);
        }

        for (ApplyAIInitRequest.ModuleConfigItem m : request.getSuggestedData().getModuleConfigs()) {
            InterviewModuleConfigCatalog.Entry e = InterviewModuleConfigCatalog.find(m.getModuleId());
            if (e == null) {
                continue;
            }
            boolean enabled = !Boolean.FALSE.equals(m.getEnabled());
            for (String gateCode : e.gateCodes()) {
                // ⛔ 幂等按 (factoryId, moduleCode, version) —— 与 getEffectiveConfig 查的是同一个键。
                //    已有就跳过而不是覆盖: 客户后来在 Canvas 改过的开关不能被重跑建厂改回去。
                if (factoryModuleConfigRepository
                        .findByFactoryIdAndModuleCodeAndConfigVersion(factoryId, gateCode, version)
                        .isPresent()) {
                    result.getSkippedModuleGates().add(gateCode);
                    continue;
                }
                com.cretas.aims.entity.config.FactoryModuleConfig fmc =
                        new com.cretas.aims.entity.config.FactoryModuleConfig();
                fmc.setFactoryId(factoryId);
                fmc.setModuleCode(gateCode);
                fmc.setConfigVersion(version);
                fmc.setEnabled(enabled);
                // ⛔ 九个 JSON 维度与 renderingMode 一律留实体默认 —— 尤其不动 renderingMode,
                //    动了会把这个模块的渲染从 LEGACY 切走。同 OnboardingModuleConfigProvisioner。
                factoryModuleConfigRepository.save(fmc);
                result.getWrittenModuleGates().add(gateCode);
            }
        }
        if (result.getWrittenModuleGates().isEmpty() && result.getSkippedModuleGates().isEmpty()) {
            result.setModuleGateNote("客户选的模块背后没有任何 @RequireModule 门禁码"
                    + "(smartbi / 物流 / 溯源 / 系统 这几个前端模块后端没上闸)。");
        }
    }

    /**
     * 把客户说「不用看」的指标置 {@code is_active=false}。
     *
     * <p>⛔ 不删行: 四项实际生产指标是建厂时由 {@code FactoryIndicatorProvisioner} 创建的默认定义,
     * 删了客户以后想看回来就得重建。软停用可逆, 而
     * {@code IndicatorRepository#findActiveByFactoryId} 正好按 {@code isActive} 过滤。
     *
     * <p>⚠️ 认不出的 code **记进结果**而不是静默跳过 ——
     * 空列表分不清「客户没要减」和「减了但一个都没认出来」。
     */
    private void applyIndicatorOptOut(String factoryId, ApplyAIInitRequest request,
                                      ApplyAIInitResult result) {
        if (indicatorRepository == null) {
            log.info("指标停用跳过: 该部署没有 IndicatorRepository, factoryId={}", factoryId);
            return;
        }
        for (String code : request.getSuggestedData().getIndicatorOptOut()) {
            String c = com.cretas.aims.service.indicator.FactoryIndicatorProvisioner.optOutCode(code);
            if (c == null) {
                continue;
            }
            var found = indicatorRepository.findByFactoryIdAndCode(factoryId, c);
            if (found.isEmpty()) {
                result.getUnknownIndicatorCodes().add(c);
                continue;
            }
            var ind = found.get();
            if (Boolean.FALSE.equals(ind.getIsActive())) {
                continue;                      // 已经停用了, 幂等
            }
            ind.setIsActive(false);
            indicatorRepository.save(ind);
            result.getDisabledIndicators().add(ind.getName());
        }
        log.info("指标停用: factoryId={}, 停用 {} 个 {}, 认不出 {} 个 {}",
                factoryId, result.getDisabledIndicators().size(), result.getDisabledIndicators(),
                result.getUnknownIndicatorCodes().size(), result.getUnknownIndicatorCodes());
    }

    private static String trimOrNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
