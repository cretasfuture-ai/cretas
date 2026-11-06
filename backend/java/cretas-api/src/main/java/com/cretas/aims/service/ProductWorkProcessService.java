package com.cretas.aims.service;

import com.cretas.aims.dto.ProductWorkProcessDTO;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public interface ProductWorkProcessService {

    ProductWorkProcessDTO create(String factoryId, ProductWorkProcessDTO dto);

    List<ProductWorkProcessDTO> listByProduct(String factoryId, String productTypeId);

    ProductWorkProcessDTO update(String factoryId, Long id, ProductWorkProcessDTO dto);

    void delete(String factoryId, Long id);

    void batchSort(String factoryId, List<ProductWorkProcessDTO.SortItem> items);

    /**
     * 一键复制工序链: 把 source 产品的整条工序链复制到 target 产品 (事务原子).
     *
     * <p>复制工序结构 (workProcessId/顺序/成本类别/覆写/辅料配置), <b>不</b>复制人员指派
     * (防呆: 各产品班组不同, responsibleWorkerId/assignees 由用户另配)。
     * <p>幂等防呆: target 已有工序链 → 409; source 无工序链 → 400。
     *
     * @return 复制的工序道数
     */
    int copyChain(String factoryId, String sourceProductId, String targetProductId);

    /**
     * Captures the exact source chain used by a governed product-create preview.
     * The snapshot intentionally excludes assignment/is-active metadata and contains
     * only the fields copied by {@link #copyChain(String, String, String)}.
     */
    PreparedChain prepareChain(String factoryId, String sourceProductId);

    /** Re-reads the source/master rows and fails closed if the issued snapshot drifted. */
    void validatePreparedChain(String factoryId, String sourceProductId, PreparedChain preparedChain);

    /** Copies the already-issued snapshot; committed readback is a separate call. */
    int copyPreparedChain(String factoryId, String sourceProductId, String targetProductId,
                          PreparedChain preparedChain);

    /** Verifies the committed target chain in a new read transaction. */
    void verifyPreparedChainReadback(String factoryId, String targetProductId,
                                     PreparedChain preparedChain);

    /** Freshly verifies that a target with no prepared source has an empty chain. */
    void verifyEmptyChainReadback(String factoryId, String targetProductId);

    record PreparedChain(
            String sourceProductId,
            List<PreparedProcess> processes,
            List<PreparedMaster> masters,
            String digest,
            List<String> processNames) {
        public PreparedChain {
            processes = processes == null ? List.of() : List.copyOf(processes);
            masters = masters == null ? List.of() : List.copyOf(masters);
            processNames = processNames == null ? List.of() : List.copyOf(processNames);
        }

        public PreparedChain(String sourceProductId, List<PreparedProcess> processes,
                             List<PreparedMaster> masters, String digest) {
            this(sourceProductId, processes, masters, digest, List.of());
        }
    }

    record PreparedProcess(
            String workProcessId,
            Integer processOrder,
            String unitOverride,
            Integer estimatedMinutesOverride,
            Boolean reportingRequired,
            Boolean allowSemiFinishedInjection,
            Boolean allowMultipleUpstreamSources,
            Boolean allowFinishedGoodsSource,
            String defaultCostCategory,
            List<Map<String, Object>> packagingTemplate,
            String auxAllocMethod,
            BigDecimal standardYieldRate,
            BigDecimal auxUnitPrice,
            String auxBasis) {
        public PreparedProcess {
            packagingTemplate = packagingTemplate == null ? null : immutablePackaging(packagingTemplate);
        }

        private static List<Map<String, Object>> immutablePackaging(
                List<Map<String, Object>> source) {
            List<Map<String, Object>> copy = source.stream()
                    .map(PreparedProcess::immutableMap)
                    .toList();
            return Collections.unmodifiableList(copy);
        }

        private static Map<String, Object> immutableMap(Map<String, Object> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, immutableValue(value)));
            return Collections.unmodifiableMap(copy);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(String.valueOf(key), immutableValue(item)));
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                return Collections.unmodifiableList(list.stream()
                        .map(PreparedProcess::immutableValue).toList());
            }
            return value;
        }
    }

    record PreparedMaster(String id, Long lockVersion, boolean selectable) {
    }
}
