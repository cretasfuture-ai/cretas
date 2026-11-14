package com.cretas.aims.service.datasuspect;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.unit.UnitContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 迁移 P5: 只读。① 本厂最近 N 天称重报工里的数据疑点 (给「发现的问题 › 数据疑点」);
 * ② 一道工序任务的逐笔称重 (给称重页, 每笔带疑点)。⛔ 不改报工原值, 不建异常。
 */
@Service
@RequiredArgsConstructor
public class DataSuspectService {

    public static final String YIELD = "YIELD";
    public static final int MAX_SCAN = 500;
    public static final int DEFAULT_DAYS = 14;
    public static final int MAX_DAYS = 60;

    private final ProductionReportRepository reportRepository;
    private final UnitContractService unitContractService;

    public record WeighingEntry(Long reportId, Long batchId, Long workProcessTaskId, Integer processOrder,
                                String reportKind, LocalDate reportDate, LocalDateTime createdAt,
                                BigDecimal inputQuantity, String inputUnit,
                                BigDecimal outputQuantity, String outputUnit,
                                BigDecimal tareWeight, BigDecimal defectQuantity,
                                List<Map<String, Object>> laborSegments,
                                List<String> suspects, String whoToCheck) {
    }

    /**
     * @param scanned   实际读了多少条报工 (口径: 报工日期在窗口内的称重报工)
     * @param truncated 窗口内超过 {@link #MAX_SCAN} 条, 只扫了最近的 {@link #MAX_SCAN} 条 —— 页面要写明, 不能当成「没有」
     */
    public record SuspectList(int windowDays, LocalDate from, LocalDate to, int scanned, boolean truncated,
                              List<WeighingEntry> items) {
    }

    @Transactional(readOnly = true)
    public SuspectList listSuspects(String factoryId, Integer days, LocalDate today) {
        int window = days == null ? DEFAULT_DAYS : Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate from = today.minusDays(window - 1L);
        Page<ProductionReport> page = reportRepository.findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
                factoryId, YIELD, from, today,
                PageRequest.of(0, MAX_SCAN, Sort.by(Sort.Order.desc("reportDate"), Sort.Order.desc("id"))));
        List<WeighingEntry> items = page.getContent().stream()
                .map(this::toEntry)
                .filter(e -> !e.suspects().isEmpty())
                .toList();
        return new SuspectList(window, from, today, page.getNumberOfElements(), page.getTotalElements() > MAX_SCAN, items);
    }

    @Transactional(readOnly = true)
    public List<WeighingEntry> listTaskWeighings(String factoryId, Long workProcessTaskId) {
        return reportRepository.findYieldReportsByTask(factoryId, workProcessTaskId).stream()
                .map(this::toEntry)
                .toList();
    }

    WeighingEntry toEntry(ProductionReport r) {
        List<String> suspects = DataSuspectRules.reasonsOf(r, unitContractService);
        return new WeighingEntry(r.getId(), r.getBatchId(), r.getWorkProcessTaskId(), r.getProcessOrder(),
                r.getReportKind(), r.getReportDate(), r.getCreatedAt(),
                r.getInputQuantity(), r.getInputUnit(), r.getOutputQuantity(), r.getOutputUnit(),
                r.getTareWeight(), r.getDefectQuantity(), r.getLaborSegments(),
                suspects, suspects.isEmpty() ? null : DataSuspectRules.WHO_TO_CHECK);
    }
}
