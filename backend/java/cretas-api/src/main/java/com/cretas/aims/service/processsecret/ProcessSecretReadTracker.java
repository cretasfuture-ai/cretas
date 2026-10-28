package com.cretas.aims.service.processsecret;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.entity.ConversionChangeHistory;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomProcessInjectionConfig;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftItem;
import com.cretas.aims.entity.bomdraft.BomRecipeDraftSubstitute;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.entity.inventory.SalesOrderShortageReport;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工艺保密的数据层打标 (V2): 一次请求 / 一次 AI 工具调用里, 只要从数据库读到某个产品的配方内容
 * (配方明细、调料、注入、替代料、转换率、工序链、工艺图版本 / 实例 / 端口、出成率建议、缺料快照),
 * 就把「哪个工厂的哪个产品 (或配方 / 工艺实例)」记进当前作用域。出口 (JSON 响应、AI 工具结果)
 * 据此判断这次输出里有没有受保护工艺, 交给 {@link ProcessSecretPayloadScrubber} 统一裁剪。
 *
 * <p>⛔ 只记录, 不改实体、不抛异常: 实体是托管对象, 在这里改值会被脏检查写回库;
 * 领料 / 扣料 / 结算 / 报工提交这些内部计算必须照常拿到真实数据。</p>
 *
 * <p>作用域: 显式打开的 {@link Scope} (AI 工具) 优先, 否则当前 HTTP 请求的属性;
 * 两者都没有 (定时任务、后台线程) 时不记录 —— 那里没有对外出口, 也避免线程变量越攒越多。</p>
 */
public final class ProcessSecretReadTracker implements PostLoadEventListener {

    static final String REQUEST_ATTRIBUTE = ProcessSecretReadTracker.class.getName() + ".reads";

    /** 读到的工艺内容来源。{@code kind} 决定 {@code id} 怎么换成产品。 */
    public enum Kind { PRODUCT, RECIPE, WORKFLOW_INSTANCE, WORKFLOW_OUTPUT, PRODUCTION_PLAN, PRODUCTION_BATCH, MATERIAL_REQUISITION, BOM_DRAFT }

    /**
     * 一笔读数。{@code member} 只用于计划 / 批次: 这一行自己的产品 (半成品 / 副产 / 锚点), 出口把它当作
     * 该计划的成员 (兄弟产出, 独立审查第三轮 C)。
     */
    public record Read(Kind kind, String factoryId, String id, String member) {
        public Read(Kind kind, String factoryId, String id) {
            this(kind, factoryId, id, null);
        }
    }

    private static final ThreadLocal<Set<Read>> EXPLICIT = new ThreadLocal<>();

    /** 打开一个显式作用域 (AI 工具执行)。嵌套时沿用外层, 关闭内层不清外层。 */
    public static Scope open() {
        if (EXPLICIT.get() != null) return new Scope(false);
        EXPLICIT.set(new LinkedHashSet<>());
        return new Scope(true);
    }

    public static final class Scope implements AutoCloseable {
        private final boolean owner;

        private Scope(boolean owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (owner) EXPLICIT.remove();
        }
    }

    /** 当前作用域已经读到的工艺内容 (快照)。 */
    public static Set<Read> reads() {
        Set<Read> sink = sink(false);
        return sink == null ? Set.of() : Set.copyOf(sink);
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
        record(event.getEntity());
    }

    /** 包内可见, 供测试和不经过 Hibernate 的读取 (如 JSON 快照) 直接调用。 */
    static void record(Object entity) {
        if (entity == null) return;
        // 先看有没有人在记: 没有就不算 (工艺图要解析 JSON, 不能让每次加载都付这个钱; 独立审查 V2.1 复核)
        Set<Read> sink = sink(true);
        if (sink == null) return;
        sink.addAll(readsOf(entity));
    }

    /** 哪些实体算「配方内容」。新增敏感实体要加在这里, 并登记进 ProcessSecretDataLayerContractTest。 */
    static List<Read> readsOf(Object entity) {
        if (entity instanceof BomRecipe r) return product(r.getFactoryId(), r.getProductTypeId());
        if (entity instanceof BomRecipeItem i) return recipe(i.getFactoryId(), i.getRecipeId());
        if (entity instanceof BomSeasoningItem s) return recipe(s.getFactoryId(), s.getRecipeId());
        if (entity instanceof BomProcessInjectionConfig c) return recipe(c.getFactoryId(), c.getRecipeId());
        if (entity instanceof BomItemSubstitute s) return recipe(s.getFactoryId(), s.getRecipeId());
        if (entity instanceof BomYieldSuggestion y) {
            return y.getProductTypeId() != null ? product(y.getFactoryId(), y.getProductTypeId())
                    : recipe(y.getFactoryId(), y.getBomRecipeId());
        }
        if (entity instanceof MaterialProductConversion c) return product(c.getFactoryId(), c.getProductTypeId());
        if (entity instanceof ConversionChangeHistory h) return product(h.getFactoryId(), h.getProductTypeId());
        if (entity instanceof ProductWorkProcess p) return product(p.getFactoryId(), p.getProductTypeId());
        if (entity instanceof ProductProcessWorkflow w) {
            return workflow(w.getFactoryId(), w.getProductTypeId(), w.getNodesJson(), w.getEdgesJson());
        }
        if (entity instanceof ProductProcessWorkflowRevision w) {
            return workflow(w.getFactoryId(), w.getProductTypeId(), w.getNodesJson(), w.getEdgesJson());
        }
        if (entity instanceof ProductionWorkflowInstance w) {
            return workflow(w.getFactoryId(), w.getProductTypeId(), w.getNodesJson(), w.getEdgesJson());
        }
        if (entity instanceof WorkflowTaskPort p) {
            return p.getWorkflowInstanceId() == null ? List.of()
                    : List.of(new Read(Kind.WORKFLOW_INSTANCE, p.getFactoryId(), String.valueOf(p.getWorkflowInstanceId())));
        }
        // 报工 / 批次: 落库的配比核对快照、批次实测出成都挂在这里 (行上的 productTypeId 是本道产出的半成品,
        // 不能当归属, 所以按计划 / 批次的产品记)
        if (entity instanceof ProcessSheetRow row) {
            if (row.getPlanId() == null || row.getPlanId().isBlank()) return List.of();
            List<Read> found = new java.util.ArrayList<>();
            found.add(new Read(Kind.PRODUCTION_PLAN, row.getFactoryId(), row.getPlanId()));
            // 行上的半成品 / 副产 / 次品 SKU 不在计划的产品集合里, 却挂着配比快照: 记成计划的成员,
            // 计划受限时它们按兄弟产出受限 (独立审查第三轮 C)
            for (String member : payloadProducts(row.getRowPayload())) {
                found.add(new Read(Kind.PRODUCTION_PLAN, row.getFactoryId(), row.getPlanId(), member));
            }
            return found;
        }
        if (entity instanceof ProductionBatch b) {
            List<Read> found = new java.util.ArrayList<>(product(b.getFactoryId(), b.getProductTypeId()));
            if (b.getProductionPlanId() != null && !b.getProductionPlanId().isBlank()) {
                found.add(new Read(Kind.PRODUCTION_PLAN, b.getFactoryId(), b.getProductionPlanId()));
            }
            return found;
        }
        if (entity instanceof MaterialConsumption c) {
            // 消耗记录里有报工自动带出的调料 / 包材和 BOM 自动扣料 (数量 = 配比 × 投入; 独立审查 V2.1 M4),
            // 也有本单实际总量 (平台 / 他厂不给): 按它的计划记, 没有计划时按生产批次记
            if (c.getProductionPlanId() != null && !c.getProductionPlanId().isBlank()) {
                return List.of(new Read(Kind.PRODUCTION_PLAN, c.getFactoryId(), c.getProductionPlanId()));
            }
            return c.getProductionBatchId() == null ? List.of()
                    : List.of(new Read(Kind.PRODUCTION_BATCH, c.getFactoryId(), String.valueOf(c.getProductionBatchId())));
        }
        if (entity instanceof ProcessSheetRowChangeLog log) {
            // 报工行操作记录的前后快照就是 row_payload (含自动带出行和回料自动扣减)
            if (log.getPlanId() == null || log.getPlanId().isBlank()) return List.of();
            List<Read> found = new java.util.ArrayList<>();
            found.add(new Read(Kind.PRODUCTION_PLAN, log.getFactoryId(), log.getPlanId()));
            // 快照里的半成品 / 副产 SKU 同报工行一样记成计划的成员 (独立审查 V2.1 复核 L1)
            Set<String> members = new LinkedHashSet<>();
            for (java.util.Map<String, Object> snapshot : java.util.Arrays.asList(log.getBeforeValue(), log.getAfterValue())) {
                if (snapshot == null) continue;
                try {
                    members.addAll(payloadProducts(PAYLOAD_READER.writeValueAsString(snapshot)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // 读不出成员: 快照里的产品归属不可信, 记一个未知成员, 出口按受保护处理
                    members.add(UNKNOWN_MEMBER);
                }
            }
            for (String member : members) {
                found.add(new Read(Kind.PRODUCTION_PLAN, log.getFactoryId(), log.getPlanId(), member));
            }
            return found;
        }
        if (entity instanceof ProductionReport r) {
            // 多产出计划的报工行 productTypeId 是原料锚点 (id 就是原料 id), 只按它记会把受保护成品漏掉
            // (独立审查第三轮 A): 再按批次记一笔, 出口经批次 → 计划解析计划牵涉的全部产品
            List<Read> found = new java.util.ArrayList<>(product(r.getFactoryId(), r.getProductTypeId()));
            if (r.getBatchId() != null) {
                found.add(new Read(Kind.PRODUCTION_BATCH, r.getFactoryId(), String.valueOf(r.getBatchId()),
                        r.getProductTypeId() == null || r.getProductTypeId().isBlank() ? null : r.getProductTypeId()));
            }
            return found;
        }
        if (entity instanceof FactoryMaterialRequisition r) {
            // 领料单明细带配比 / 单位需求量, 属于它的生产计划 (多产出计划牵涉多个产品, 由出口解析)
            if (r.getProductionPlanId() == null || r.getProductionPlanId().isBlank()) return List.of();
            return List.of(new Read(Kind.PRODUCTION_PLAN, r.getFactoryId(), r.getProductionPlanId()));
        }
        if (entity instanceof FactoryMaterialRequisitionItem item) {
            // 明细可能脱离主单单独加载; 主单还是代理时不在 PostLoad 里初始化它, 只记主单 ID, 出口再解析
            Object parent = item.getRequisition();
            if (parent instanceof FactoryMaterialRequisition r && org.hibernate.Hibernate.isInitialized(r)) {
                return readsOf(r);
            }
            if (parent instanceof org.hibernate.proxy.HibernateProxy proxy) {
                Object id = proxy.getHibernateLazyInitializer().getInternalIdentifier();
                return id == null ? List.of() : List.of(new Read(Kind.MATERIAL_REQUISITION, null, String.valueOf(id)));
            }
            return List.of();
        }
        if (entity instanceof BomRecipeDraft draft) {
            boolean liveProduct = "LIVE_PRODUCT".equals(draft.getTargetProductRefType());
            return liveProduct ? product(draft.getFactoryId(), draft.getTargetProductRefId()) : List.of();
        }
        if (entity instanceof BomRecipeDraftItem i) return draft(i.getFactoryId(), i.getDraftId());
        if (entity instanceof BomRecipeDraftSubstitute s) return draft(s.getFactoryId(), s.getDraftId());
        if (entity instanceof SalesOrderShortageReport report) {
            // 缺料是落库的快照, 读快照时不会再读配方: 按快照里的产品打标
            if (report.getAvailable() == null) return List.of();
            return report.getAvailable().stream()
                    .map(LineItemMatch::getProductTypeId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .map(id -> new Read(Kind.PRODUCT, report.getFactoryId(), id))
                    .toList();
        }
        return List.of();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 报工行 payload 里出现的全部产品 (本行产出、多产出行): 出口按 productTypeId 认归属, 这里同一口径。 */
    static Set<String> payloadProducts(String payload) {
        if (payload == null || !payload.contains("productTypeId")) return Set.of();
        try {
            Set<String> found = new LinkedHashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode value : PAYLOAD_READER.readTree(payload)
                    .findValues("productTypeId")) {
                if (value.isTextual() && !value.asText().isBlank()) found.add(value.asText());
            }
            return found;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 读不出成员就只记计划: 行的产品归属不可信, 出口按整份受限处理 (见裁剪器 distrustAttribution)
            return Set.of();
        }
    }

    /** 成员未知 (图解析不了): 出口当作「无法确认的产品」。 */
    static final String UNKNOWN_MEMBER = "";

    /**
     * 本厂有没有保密产品 (由 Spring 启动时接到判定服务的缓存名单上)。没有就不解析工艺图 JSON ——
     * 请求里总有记录处, 不能让无保密产品的工厂每次加载工艺图都付解析的钱 (独立审查 V2.1 复核)。
     * 未接上时默认「有」: 宁可多解析, 不漏。
     */
    private static volatile java.util.function.Predicate<String> factoryHasProtectedProducts = factoryId -> true;

    public static void useProtectedFactoryProbe(java.util.function.Predicate<String> probe) {
        factoryHasProtectedProducts = probe == null ? factoryId -> true : probe;
    }

    /**
     * 工艺图 (定义 / 版本 / 运行实例): 所属产品 + 图里出现的每个产品记成该图的成员。多产出图挂在原料锚点上,
     * 锚点不受保护, 受保护的是它产出的成品; 出口把成员当兄弟产出 (独立审查 V2.1 M3)。
     */
    private static List<Read> workflow(String factoryId, String ownerProductTypeId, String nodesJson, String edgesJson) {
        List<Read> found = new java.util.ArrayList<>(product(factoryId, ownerProductTypeId));
        if (ownerProductTypeId == null || ownerProductTypeId.isBlank()) return found;
        if (factoryId == null || !factoryHasProtectedProducts.test(factoryId)) return found;
        java.util.Optional<Set<String>> products = ProcessSecretWorkflowOutputs.productsInGraph(nodesJson);
        if (products.isEmpty()) {
            // 图解析不了: 无法确认牵涉哪些产品 —— 记一个「未知成员」, 出口按受保护处理 (fail-closed)
            found.add(new Read(Kind.WORKFLOW_OUTPUT, factoryId, ownerProductTypeId, UNKNOWN_MEMBER));
            return found;
        }
        for (String output : products.get()) {
            if (!output.equals(ownerProductTypeId)) {
                found.add(new Read(Kind.WORKFLOW_OUTPUT, factoryId, ownerProductTypeId, output));
            }
        }
        return found;
    }

    private static List<Read> product(String factoryId, String productTypeId) {
        if (productTypeId == null || productTypeId.isBlank()) return List.of();
        return List.of(new Read(Kind.PRODUCT, factoryId, productTypeId));
    }

    private static List<Read> draft(String factoryId, String draftId) {
        if (draftId == null || draftId.isBlank()) return List.of();
        return List.of(new Read(Kind.BOM_DRAFT, factoryId, draftId));
    }

    private static List<Read> recipe(String factoryId, String recipeId) {
        if (recipeId == null || recipeId.isBlank()) return List.of();
        return List.of(new Read(Kind.RECIPE, factoryId, recipeId));
    }

    @SuppressWarnings("unchecked")
    private static Set<Read> sink(boolean create) {
        Set<Read> explicit = EXPLICIT.get();
        if (explicit != null) return explicit;
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object existing = attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof Set<?> set) return (Set<Read>) set;
        if (!create) return null;
        Set<Read> created = Collections.synchronizedSet(new LinkedHashSet<>());
        attributes.setAttribute(REQUEST_ATTRIBUTE, created, RequestAttributes.SCOPE_REQUEST);
        return created;
    }
}
