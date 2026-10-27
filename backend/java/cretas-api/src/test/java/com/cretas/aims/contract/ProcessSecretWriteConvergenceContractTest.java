package com.cretas.aims.contract;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工艺保密写入侧收敛闸 (Steve 2026-10-27: 不逐个补口子, 收敛)。
 *
 * <p>主代码里经「配方类仓库」(转换率、工序链、配方及明细 / 调料 / 注入 / 替代料、工艺图及版本、运行端口) 写库的类,
 * 要么自己引用工艺保密判定 (守卫 / 路由注解 / 判定服务), 要么登记在 {@link #LEDGER} 并写明谁替它把关。
 * 新增一个绕开编辑闸的写入口 → CI 红。⚠️ 这是文件级的判据 (形态 E: 窄而可信): 一个类里有判定不等于每个方法都判,
 * 所以本 PR 新补的几处再用 {@link #guardRunsBeforeTheFirstWriteOnTheBypassPaths} 钉住「先判后写」。
 */
class ProcessSecretWriteConvergenceContractTest {

    static final Path MAIN = Paths.get("src/main/java/com/cretas/aims");

    static final List<String> PROTECTED_REPOSITORIES = List.of(
            "ConversionRepository", "ProductWorkProcessRepository", "BomRecipeRepository", "BomRecipeItemRepository",
            "BomSeasoningItemRepository", "BomProcessInjectionConfigRepository", "BomItemSubstituteRepository",
            "ProductProcessWorkflowRepository", "ProductProcessWorkflowRevisionRepository",
            "WorkflowTaskPortRepository");

    static final Pattern WRITE = Pattern.compile(
            "\\.(save|saveAll|saveAndFlush|saveAllAndFlush|delete|deleteAll|deleteById|deleteAllById|deleteInBatch"
                    + "|deleteAllInBatch)\\b|::(save|saveAll|delete|deleteAll)\\b");
    static final Pattern GUARDED = Pattern.compile(
            "ProcessSecretRecipeGuard|ProcessSecretGuard|processSecretGuard|ProcessSecretAccessService");

    /** 没有自带判定的写入类 → 谁替它把关 (逐条核实过, 见 PR 描述)。 */
    static final Map<String, String> LEDGER = Map.ofEntries(
            Map.entry("service/bom/BomWorkflowRevisionService.java",
                    "配方钉工艺图版本: 路由 BomWorkflowRevisionController 有 EDIT 注解; 建厂实体化调用前已判"),
            Map.entry("service/bom/impl/BomCopyServiceImpl.java",
                    "复制到草稿: 路由 copy-to-draft 有注解, 源产品要完整查看 + 导出 (V1)"),
            Map.entry("service/bom/impl/BomItemSubstituteServiceImpl.java",
                    "替代料替换 / 克隆只由已带 V1 编辑闸的配方服务调用"),
            Map.entry("service/bom/impl/BomRecipeServiceImpl.java",
                    "配方增删改 / 激活 / 归档: BomRecipeController 每个写路由都有 EDIT 注解; 建厂转正调用前已判"),
            Map.entry("service/bom/impl/BomSeasoningWorkspaceServiceImpl.java",
                    "调料绑定增删改: BomRecipeController 的 seasoning 写路由都有 EDIT 注解"),
            Map.entry("service/impl/ProductProcessWorkflowServiceImpl.java",
                    "工艺图草稿 / 发布 / 激活: ProductProcessWorkflowController 写路由有 EDIT 注解; 建厂发布与实体化调用前已判"),
            Map.entry("service/impl/ProductionPlanServiceImpl.java",
                    "改钉计划时重建运行端口: 运行时实例数据, 由已发布的工艺图生成, 不改工艺本身"),
            Map.entry("service/workflow/WorkflowRevisionSnapshotService.java",
                    "工艺图版本快照: 保存草稿 / 发布时由工艺图服务内部调用, 只是把当前定义存成不可变副本"),
            Map.entry("service/workflow/WorkflowUnitReviewService.java",
                    "只打「单位待复核」标记 (unitReviewRequired), 不改工艺内容"),
            Map.entry("service/workflow/impl/ProductProcessWorkflowRuntimeServiceImpl.java",
                    "运行端口记实际投料 / 产出, 是生产记录, 不改工艺"));

    static Map<String, Boolean> writers;   // 相对路径 -> 是否自带判定

    @BeforeAll
    static void scan() throws IOException {
        writers = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Set<String> names = new TreeSet<>();
                for (String type : PROTECTED_REPOSITORIES) {
                    Matcher m = Pattern.compile("\\b" + type + "\\s+(\\w+)\\s*[;,)=]").matcher(source);
                    while (m.find()) names.add(m.group(1));
                }
                boolean writes = false;
                for (String name : names) {
                    Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(" + WRITE.pattern() + ")")
                            .matcher(source);
                    if (m.find()) {
                        writes = true;
                        break;
                    }
                }
                if (writes) {
                    String rel = MAIN.relativize(file).toString().replace('\\', '/');
                    writers.put(rel, GUARDED.matcher(source).find());
                }
            }
        }
    }

    @Test
    void theScannerSeesTheKnownWriters() {
        // 阳性对照: 量具自己得看得见已知的写入类, 否则下面「全部有着落」是空话
        assertThat(writers).containsKeys("service/impl/ConversionServiceImpl.java",
                "service/impl/ProductWorkProcessServiceImpl.java", "blueprint/interview/FactoryBuildApplyService.java",
                "service/bom/impl/BomRecipeServiceImpl.java");
        assertThat(writers.get("service/impl/ConversionServiceImpl.java")).isTrue();
        assertThat(writers).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void everyRecipeWriterIsGuardedOrLedgered() {
        Set<String> unaccounted = new TreeSet<>();
        writers.forEach((file, guarded) -> {
            if (!guarded && !LEDGER.containsKey(file)) unaccounted.add(file);
        });
        assertThat(unaccounted)
                .as("新的配方写入口既没有工艺保密编辑判定, 也没有登记谁替它把关")
                .isEmpty();
    }

    @Test
    void theLedgerHasNoStaleEntries() {
        Set<String> stale = new TreeSet<>();
        LEDGER.keySet().forEach(file -> {
            Boolean guarded = writers.get(file);
            if (guarded == null || guarded) stale.add(file);
        });
        assertThat(stale).as("登记的类已不再写配方仓库, 或已自带判定: 删掉登记").isEmpty();
    }

    @Test
    void guardRunsBeforeTheFirstWriteOnTheBypassPaths() throws IOException {
        assertBefore("blueprint/interview/FactoryBuildApplyService.java",
                "requireProcessSecretEdit(factoryId, request, overwrite);", "result.setPlanned(");
        assertBefore("blueprint/activation/BlueprintWorkflowPublicationService.java",
                "processSecretGuard.requireRecipe(", "recipes.activateRecipeAsActor(");
        assertBefore("blueprint/workflowmaterialize/FactoryWorkflowDraftMaterializationService.java",
                "processSecretGuard.requireProduct(", "ledgerRepository.saveAndFlush(ledger)");
        assertBefore("blueprint/promotion/BomDraftPromoter.java",
                "processSecretGuard.requireProduct(", "recipeService.createRecipe(");
        assertBefore("service/bom/impl/BomYieldEstimateServiceImpl.java",
                "processSecretGuard.requireRecipes(", "bomRecipeRepository.save(recipe)");
        // 过期 409 会带回库里的标准出成率: 判定也必须在它之前 (独立审查 V2.1 H1)
        assertBefore("service/bom/impl/BomYieldEstimateServiceImpl.java",
                "processSecretGuard.requireRecipes(", "throw new BomYieldStaleException(");
        // 没有事务: 判定必须在建任何档之前 (独立审查 V2.1 M1)
        assertBefore("controller/AIController.java", "public ResponseEntity<AIBusinessDataResponse> initializeBusinessData(",
                "processSecretGuard.requireProduct(", "productTypeRepository.save(");
        assertBefore("service/bom/impl/BomPriceAdjustmentServiceImpl.java",
                "processSecretGuard.requireRecipe(", "itemRepository.save(item)");
        assertBefore("service/impl/ProductTypeServiceImpl.java", "public ProductTypeDTO updateProductType(",
                "processSecretGuard.requireProduct(factoryId, id,", "productType = productTypeRepository.save(productType);");
    }

    private static void assertBefore(String file, String guardCall, String firstWrite) throws IOException {
        assertBefore(file, null, guardCall, firstWrite);
    }

    /** {@code methodAnchor} 非空时从该方法开始找 (同一个类里别的方法也有同样的写法)。 */
    private static void assertBefore(String file, String methodAnchor, String guardCall, String firstWrite)
            throws IOException {
        String source = Files.readString(MAIN.resolve(file), StandardCharsets.UTF_8);
        int from = methodAnchor == null ? 0 : source.indexOf(methodAnchor);
        assertThat(from).as(file + " 找不到方法锚点 " + methodAnchor).isNotNegative();
        int guard = source.indexOf(guardCall, from);
        int write = source.indexOf(firstWrite, from);
        assertThat(guard).as(file + " 缺编辑判定").isPositive();
        assertThat(write).as(file + " 找不到写入锚点 (量具失效)").isPositive();
        assertThat(guard).as(file + " 要先判后写").isLessThan(write);
    }
}
