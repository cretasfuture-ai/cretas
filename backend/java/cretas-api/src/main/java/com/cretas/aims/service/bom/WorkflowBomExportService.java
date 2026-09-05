package com.cretas.aims.service.bom;

import com.alibaba.excel.EasyExcel;
import com.cretas.aims.dto.bom.WorkflowBomExportRowDTO;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.workflow.InputShareRatios;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把工艺画布 + BOM 摊成一张平表导出 Excel。
 *
 * <h3>会议依据</h3>
 *
 * <p>{@code [14:18]}「它这个可以转换成那种 Excel 的模式吗」/
 * {@code [14:48]}「好多客户他是要一个这种 Excel 表格，自己作为线下的存档备份」/
 * {@code [28:07]}「后面需要把这个 BOM 的形式给它导出来，也可以来做」—— 说了三次。</p>
 *
 * <h3>⛔ 它是投影，不是第二份定义</h3>
 *
 * <p>只读画布与 BOM，<b>不接受回写</b>。会上还有一句「你再复制一个模块」——
 * 那会做出两份可编辑的配方，而同一个东西有两份就一定会漂（本仓形态 D）。
 * 导出解决的是「看不清 / 要存档」，不是「换个地方维护」。</p>
 *
 * <h3>数据来源与「说不出来就说不出来」</h3>
 *
 * <p>工序名与投入产出<b>全部来自画布端口自身</b>（端口带
 * {@code materialName / materialKind / unit / inputShareRatio / ordinal}），
 * ⛔ 不去遍历边猜物料 —— 边只用来排工序先后。</p>
 *
 * <p>标准用量按 {@code workflowInputPortId} 从 BOM 明细行连过来；连不上就<b>留空并在备注里写明</b>，
 * ⛔ 不填 0：「用量 0」是个看起来很确定的假答案，而客户会拿这张表去备料。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowBomExportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final BomRecipeRepository bomRecipeRepository;
    private final BomRecipeItemRepository bomRecipeItemRepository;
    private final ProductTypeRepository productTypeRepository;

    /**
     * @param productTypeIds 为空 ⇒ 导出该工厂全部有工艺的成品
     */
    public byte[] export(String factoryId, List<String> productTypeIds) {
        List<WorkflowBomExportRowDTO> rows = buildRows(factoryId, productTypeIds);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EasyExcel.write(out, WorkflowBomExportRowDTO.class)
                    .sheet("工艺与BOM")
                    .doWrite(rows);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出工艺/BOM 失败: " + e.getMessage(), e);
        }
    }

    List<WorkflowBomExportRowDTO> buildRows(String factoryId, List<String> productTypeIds) {
        List<String> targets = resolveTargets(factoryId, productTypeIds);
        List<WorkflowBomExportRowDTO> rows = new ArrayList<>();
        for (String productTypeId : targets) {
            rows.addAll(buildRowsForProduct(factoryId, productTypeId));
        }
        return rows;
    }

    private List<String> resolveTargets(String factoryId, List<String> productTypeIds) {
        if (productTypeIds != null && !productTypeIds.isEmpty()) {
            return productTypeIds;
        }
        // 没指定 ⇒ 全厂有工艺修订的成品 (去重, 保持稳定顺序)
        return revisionRepository.findAll().stream()
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .map(ProductProcessWorkflowRevision::getProductTypeId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<WorkflowBomExportRowDTO> buildRowsForProduct(String factoryId, String productTypeId) {
        Optional<ProductProcessWorkflowRevision> revOpt = latestPublished(factoryId, productTypeId);
        String productName = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(pt -> pt.getName())
                .orElse(productTypeId);

        if (revOpt.isEmpty()) {
            // ⛔ 不是静默跳过: 客户拿到表看不到这个成品, 会以为它没配工艺还是导漏了?
            //    给一行说清楚, 那正是这张表该回答的。
            return List.of(WorkflowBomExportRowDTO.builder()
                    .productName(productName)
                    .note("没有已发布的工艺 —— 未导出工序明细 (草稿不导出)")
                    .build());
        }
        ProductProcessWorkflowRevision rev = revOpt.get();

        BigDecimal yieldPercent = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, BomRecipe.Status.ACTIVE)
                .map(BomRecipe::getOverallYieldRate)
                .orElse(null);

        // portId → 标准用量 / 单位 (BOM 明细行那一侧)
        Map<String, BomRecipeItem> bomByPort = new HashMap<>();
        for (BomRecipeItem item : bomRecipeItemRepository.findCurrentByProduct(factoryId, productTypeId)) {
            if (item.getWorkflowInputPortId() != null && !item.getWorkflowInputPortId().isBlank()) {
                bomByPort.put(item.getWorkflowInputPortId(), item);
            }
        }

        String revisionLabel = "v" + (rev.getRevisionNumber() == null ? "?" : rev.getRevisionNumber())
                + " / " + (rev.getCreatedAt() == null ? "?" : rev.getCreatedAt().format(STAMP));

        List<Map<String, Object>> nodes = parseArray(rev.getNodesJson());
        List<Map<String, Object>> edges = parseArray(rev.getEdgesJson());
        List<Map<String, Object>> processes = nodes.stream()
                .filter(n -> "PROCESS".equals(str(n.get("kind"))))
                .toList();
        List<Map<String, Object>> ordered = orderProcesses(processes, edges);

        List<WorkflowBomExportRowDTO> rows = new ArrayList<>();
        int order = 0;
        for (Map<String, Object> node : ordered) {
            order++;
            Map<String, Object> data = asMap(node.get("data"));
            String processName = str(data.get("processName"));
            for (Map<String, Object> port : asList(data.get("ports"))) {
                String portId = str(port.get("id"));
                BomRecipeItem bom = portId == null ? null : bomByPort.get(portId);
                BigDecimal share = InputShareRatios.parsePercentQuiet(port.get("inputShareRatio"));

                String note = null;
                if ("INPUT".equalsIgnoreCase(str(port.get("direction")))) {
                    if (bom == null && share == null) {
                        note = "既没有标准用量, 也没有配比 —— 投多少由报工当场决定";
                    } else if (share != null && yieldPercent == null) {
                        // 🔴 有份额但没有出成率 ⇒ 折不出绝对投入量。这是六膳门今天的实际状态
                        //    (8 个成品的标准出成率全空)。留白会被读成「不用填」。
                        note = "有配比但该成品未填标准出成率 ⇒ 折不出绝对投入量";
                    }
                }

                rows.add(WorkflowBomExportRowDTO.builder()
                        .productName(productName)
                        .processOrder(order)
                        .processName(processName)
                        .direction("INPUT".equalsIgnoreCase(str(port.get("direction"))) ? "投入" : "产出")
                        .materialName(str(port.get("materialName")))
                        .materialKind(str(port.get("materialKind")))
                        .standardQuantity(bom == null ? null : bom.getStandardQuantity())
                        .inputSharePercent(share)
                        .unit(firstNonBlank(str(port.get("unit")), bom == null ? null : bom.getUnit()))
                        .standardYieldPercent(yieldPercent)
                        .revisionLabel(revisionLabel)
                        .note(note)
                        .build());
            }
        }
        return rows;
    }

    private Optional<ProductProcessWorkflowRevision> latestPublished(String factoryId, String productTypeId) {
        return revisionRepository
                .findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId).stream()
                // ⛔ 草稿不导出: 客户拿去存档的必须是已发布的那一版, 否则两个人对账时
                //    各自导出的可能是不同的草稿。
                .filter(r -> r.getStatus() == ProductProcessWorkflowRevision.Status.PUBLISHED)
                .findFirst();
    }

    /**
     * 按边做拓扑排序 —— 工序先后是这张表最容易被读错的一列。
     *
     * <p>⚠️ 图有环 / 断开 / 边缺失时<b>退回节点数组原序</b>，并且<b>不假装</b>那是工艺顺序：
     * 调用方看到的仍是 1..N，但这一段本身不产生任何「顺序正确」的承诺。
     * ⛔ 不用 {@code position.x} 当顺序 —— 那是有人拖过节点就会变的视觉位置，
     * 拿它当工艺顺序是把布局误当成语义。</p>
     */
    List<Map<String, Object>> orderProcesses(List<Map<String, Object>> processes,
                                             List<Map<String, Object>> edges) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> p : processes) {
            String id = str(p.get("id"));
            if (id != null) {
                byId.put(id, p);
            }
        }
        if (byId.size() <= 1 || edges.isEmpty()) {
            return new ArrayList<>(byId.values());
        }

        Map<String, Set<String>> next = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        byId.keySet().forEach(id -> indegree.put(id, 0));
        for (Map<String, Object> e : edges) {
            String s = str(e.get("source"));
            String t = str(e.get("target"));
            // 只连「工序 → 工序」的可达关系; 物料节点不参与排序 (它们是端口已经带出来的信息)
            if (s == null || t == null || !byId.containsKey(s) || !byId.containsKey(t) || s.equals(t)) {
                continue;
            }
            if (next.computeIfAbsent(s, k -> new HashSet<>()).add(t)) {
                indegree.merge(t, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Deque<String> ready = new java.util.ArrayDeque<>();
        byId.keySet().stream().filter(id -> indegree.get(id) == 0).forEach(ready::add);
        while (!ready.isEmpty()) {
            String id = ready.poll();
            out.add(byId.get(id));
            for (String n : next.getOrDefault(id, Set.of())) {
                if (indegree.merge(n, -1, Integer::sum) == 0) {
                    ready.add(n);
                }
            }
        }
        if (out.size() != byId.size()) {
            // 有环或数据不完整 —— 退回原序, 并留痕。⛔ 不静默丢掉排不进去的工序。
            log.warn("[WorkflowBomExport] 工序拓扑排序未覆盖全部节点 ({}/{}), 退回节点原序",
                    out.size(), byId.size());
            return new ArrayList<>(byId.values());
        }
        return out;
    }

    // ── 解析小工具: 解析失败一律返回空, ⛔ 不抛 —— 导出不该因为一个坏节点整份失败 ──

    private static List<Map<String, Object>> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("[WorkflowBomExport] nodes/edges JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
