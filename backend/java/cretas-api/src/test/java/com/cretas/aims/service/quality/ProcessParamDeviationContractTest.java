package com.cretas.aims.service.quality;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.CcpMonitoringController;
import com.cretas.aims.controller.ProcessParamDeviationController;
import com.cretas.aims.controller.YieldReportController;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.quality.ProcessParamDeviationDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 工艺参数偏离汇总 —— 真 Spring + H2, 数据走 App 的真实报工入口 ({@link YieldReportController#submitReport}) 产生,
 * 读走新接口的真实 controller 入口 ({@link ProcessParamDeviationController})。
 *
 * <p>种子 (本厂 F): 工序「炒制」配 oilTemp(150~160) 与 fryMinutes(20~30), 工序「包装」配 sealTemp(120~140)。
 * <pre>
 *   批次 A1 (产品 牛肉干): 炒制 oilTemp=165 HIGH, fryMinutes=25 OK
 *   批次 A2 (产品 牛肉干): 炒制 oilTemp=155 OK,   fryMinutes=15 LOW
 *   批次 B1 (产品 猪肉脯): 炒制 oilTemp=140 LOW,  fryMinutes 没填
 *   批次 A1: 包装 报工但没填任何参数
 * </pre>
 * ⇒ 炒制/oilTemp: 3 次填了, 2 次偏离 (66.7%); 炒制/fryMinutes: <b>2</b> 次填了 (不是 3 条炒制报工), 1 次偏离 (50.0%);
 * 包装没有任何汇总行。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("工艺参数偏离汇总: 分母=填了该参数的次数 / 跨租户隔离 / 无数据 / 窗口与状态校验")
class ProcessParamDeviationContractTest {

    private static final String F = "PPD-FACTORY";
    private static final String OTHER = "PPD-OTHER";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private YieldReportController yieldReportController;
    @Autowired private ProcessParamDeviationController controller;
    @Autowired private WorkProcessRepository processRepo;
    @Autowired private WorkProcessTaskRepository taskRepo;
    @Autowired private ProductionBatchRepository batchRepo;
    @Autowired private ProductionReportRepository reportRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @MockBean private ProcessSecretAggregateExclusions aggregateExclusions;

    private WorkProcess fry;
    private WorkProcess pack;
    private ProductionBatch a1;
    private ProductionBatch a2;
    private ProductionBatch b1;

    @BeforeEach
    void setUp() {
        when(aggregateExclusions.forCurrentCaller(anyString(), anyString()))
                .thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        seedProduct("PPD-PT-A", "牛肉干");
        seedProduct("PPD-PT-B", "猪肉脯");
        fry = process(F, "炒制", List.of(
                field("oilTemp", "油温", "℃", 155, 150, 160),
                field("fryMinutes", "炒制时间", "min", null, 20, 30)));
        pack = process(F, "包装", List.of(field("sealTemp", "封口温度", "℃", 130, 120, 140)));
        a1 = batch(F, "PPD-A1", "PPD-PT-A");
        a2 = batch(F, "PPD-A2", "PPD-PT-A");
        b1 = batch(F, "PPD-B1", "PPD-PT-B");

        submit(a1, fry, 1, params("oilTemp", 165, "fryMinutes", "25"));
        submit(a2, fry, 1, params("oilTemp", 155, "fryMinutes", 15));
        submit(b1, fry, 1, params("oilTemp", 140));
        submit(a1, pack, 2, null);
    }

    @Test
    @DisplayName("工艺保密: 有隐藏范围时产品和计划均无法解析的历史脏报工 fail-closed, 可归属的普通产品仍可读")
    void hiddenScopeFailsClosedForUnresolvedReportOwnership() {
        seedProduct(OTHER, "PPD-FOREIGN", "他厂产品");
        seedProduct(F, "PPD-DELETED", "已删产品");
        ProductType deleted = productTypeRepo.findById("PPD-DELETED").orElseThrow();
        deleted.setDeletedAt(LocalDateTime.now());
        productTypeRepo.saveAndFlush(deleted);
        reportRepo.saveAndFlush(unresolvedParamCheckReport("STALE-X"));
        reportRepo.saveAndFlush(unresolvedParamCheckReport("PPD-FOREIGN"));
        reportRepo.saveAndFlush(unresolvedParamCheckReport("PPD-DELETED"));
        when(aggregateExclusions.forCurrentCaller(F, "process param deviation"))
                .thenReturn(new ProcessSecretAggregateExclusions.Scope(List.of("PPD-PT-A"), List.of()));

        ProcessParamDeviationDTO.SummaryView view = controller.summary(F, null, null, null, null, null).getData();

        assertThat(view.getTotalChecked()).isEqualTo(1);
        assertThat(view.getTotalDeviations()).isEqualTo(1);
        assertThat(view.getRows()).extracting(ProcessParamDeviationDTO.SummaryRow::getParamKey)
                .containsExactly("oilTemp");
        assertThat(row(view, "炒制", "oilTemp").getDeviationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("工艺保密: 排除范围为空时, 历史脏报工仍按普通数据可读")
    void emptyExclusionScopeKeepsUnresolvedReportVisible() {
        reportRepo.saveAndFlush(unresolvedParamCheckReport(null));

        ProcessParamDeviationDTO.SummaryView view = controller.summary(F, null, null, null, null, null).getData();

        assertThat(view.getTotalChecked()).isEqualTo(6);
        assertThat(view.getTotalDeviations()).isEqualTo(4);
        assertThat(row(view, "炒制", "oilTemp").getReportCount()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ 汇总: 分母

    @Test
    @DisplayName("分母 = 填了该参数的次数: 炒制 3 条报工里 fryMinutes 只有 2 条填了 ⇒ 2, 不是 3; 没填参数的包装不出行")
    void denominatorIsTimesTheParameterWasFilled() {
        // 阳性读数: 炒制确实有 3 条报工, 否则「2 不是 3」没有意义
        assertThat(reportRepo.findYieldReportsByBatch(F, a1.getId())).hasSize(2);
        assertThat(yieldReports("炒制")).as("炒制的全部报工次数").isEqualTo(3);

        ProcessParamDeviationDTO.SummaryView view = controller.summary(F, null, null, null, null, null).getData();

        ProcessParamDeviationDTO.SummaryRow oil = row(view, "炒制", "oilTemp");
        assertThat(oil.getReportCount()).isEqualTo(3);
        assertThat(oil.getDeviationCount()).isEqualTo(2);
        assertThat(oil.getHighCount()).isEqualTo(1);
        assertThat(oil.getLowCount()).isEqualTo(1);
        assertThat(oil.getDeviationRate()).isEqualByComparingTo("66.7");
        assertThat(oil.getParamLabel()).isEqualTo("油温");
        assertThat(oil.getUnit()).isEqualTo("℃");

        ProcessParamDeviationDTO.SummaryRow minutes = row(view, "炒制", "fryMinutes");
        assertThat(minutes.getReportCount()).as("fryMinutes 只有 2 条报工填了, 分母不是炒制的全部报工 3").isEqualTo(2);
        assertThat(minutes.getDeviationCount()).isEqualTo(1);
        assertThat(minutes.getLowCount()).isEqualTo(1);
        assertThat(minutes.getDeviationRate()).isEqualByComparingTo("50.0");

        assertThat(view.getRows()).as("包装那条报工没填参数, 不出汇总行").extracting("processName")
                .containsOnly("炒制");
        assertThat(view.getTotalChecked()).isEqualTo(5);
        assertThat(view.getTotalDeviations()).isEqualTo(3);
        // 没传时间范围 = 最近 30 天, 并如实回显
        assertThat(view.getWindow().getEndDate()).isEqualTo(LocalDate.now());
        assertThat(view.getWindow().getStartDate()).isEqualTo(LocalDate.now().minusDays(29));
    }

    @Test
    @DisplayName("OK 不算偏离: 全部在范围内的参数偏离次数 0、偏离率 0.0 (有分母, 所以是真 0 不是没数据)")
    void okIsNotADeviation() {
        ProcessParamDeviationDTO.SummaryView view = controller.summary(F, null, null, null, null, "PPD-A2").getData();
        ProcessParamDeviationDTO.SummaryRow oil = row(view, "炒制", "oilTemp");
        assertThat(oil.getReportCount()).isEqualTo(1);
        assertThat(oil.getDeviationCount()).isZero();
        assertThat(oil.getDeviationRate()).isEqualByComparingTo("0.0");
    }

    // ------------------------------------------------------------------ 明细

    @Test
    @DisplayName("明细只列偏离: 内容完整 (批次/产品/工序/参数/实测/单位/标准/范围/方向/报工人), OK 的不出现")
    void detailsListOnlyDeviationsWithFullContent() {
        PageResponse<ProcessParamDeviationDTO.DetailRow> page =
                controller.details(F, null, null, null, null, null, null, null, null).getData();
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ProcessParamDeviationDTO.DetailRow::getStatus)
                .doesNotContain("OK").containsOnly("HIGH", "LOW");

        ProcessParamDeviationDTO.DetailRow high = page.getContent().stream()
                .filter(r -> "HIGH".equals(r.getStatus())).findFirst().orElseThrow();
        assertThat(high.getBatchNumber()).isEqualTo("PPD-A1");
        assertThat(high.getBatchId()).isEqualTo(a1.getId());
        assertThat(high.getProductTypeId()).isEqualTo("PPD-PT-A");
        assertThat(high.getProductName()).isEqualTo("牛肉干");
        assertThat(high.getProcessName()).isEqualTo("炒制");
        assertThat(high.getParamKey()).isEqualTo("oilTemp");
        assertThat(high.getParamLabel()).isEqualTo("油温");
        assertThat(high.getMeasuredValue()).isEqualByComparingTo("165");
        assertThat(high.getUnit()).isEqualTo("℃");
        assertThat(high.getStandardValue()).isEqualByComparingTo("155");
        assertThat(high.getRangeMin()).isEqualByComparingTo("150");
        assertThat(high.getRangeMax()).isEqualByComparingTo("160");
        assertThat(high.getReporterName()).isEqualTo("ppd_operator");
        assertThat(high.getReportDate()).isEqualTo(LocalDate.now());
        assertThat(high.getReportedAt()).isNotNull();
    }

    @Test
    @DisplayName("状态筛选: HIGH / LOW 各自只出自己的; 但汇总不受 status 影响 (筛掉 OK 后分母就没了)")
    void statusFilterAppliesToDetailsOnly() {
        assertThat(controller.details(F, null, null, null, null, null, "HIGH", null, null).getData().getContent())
                .extracting(ProcessParamDeviationDTO.DetailRow::getParamKey, ProcessParamDeviationDTO.DetailRow::getStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("oilTemp", "HIGH"));
        assertThat(controller.details(F, null, null, null, null, null, "low", null, null).getData().getContent())
                .extracting(ProcessParamDeviationDTO.DetailRow::getStatus).containsOnly("LOW").hasSize(2);
        assertThatThrownBy(() -> controller.details(F, null, null, null, null, null, "OK", null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("LOW");
    }

    @Test
    @DisplayName("筛选: 产品 / 批次(包含) / 工序; 筛完汇总与明细同口径")
    void filtersByProductBatchAndProcess() {
        // 产品 B: 只有 B1 的 oilTemp=140 LOW
        PageResponse<ProcessParamDeviationDTO.DetailRow> forB =
                controller.details(F, null, null, null, "PPD-PT-B", null, null, null, null).getData();
        assertThat(forB.getContent()).extracting(ProcessParamDeviationDTO.DetailRow::getBatchNumber)
                .containsExactly("PPD-B1");
        ProcessParamDeviationDTO.SummaryView sumB = controller.summary(F, null, null, null, "PPD-PT-B", null).getData();
        assertThat(sumB.getRows()).hasSize(1);
        assertThat(row(sumB, "炒制", "oilTemp").getReportCount()).isEqualTo(1);

        // 批次号包含 (大小写不敏感)
        assertThat(controller.details(F, null, null, null, null, "ppd-a", null, null, null).getData().getTotalElements())
                .isEqualTo(2);   // A1 HIGH + A2 LOW(fryMinutes)
        // 工序不存在 ⇒ 空, 不是报错也不是别的工序的数据
        assertThat(controller.details(F, null, null, "烘烤", null, null, null, null, null).getData().getTotalElements())
                .isZero();
        assertThat(controller.summary(F, null, null, "包装", null, null).getData().getRows()).isEmpty();
    }

    @Test
    @DisplayName("分页: size=1 逐页翻, 总数与页数正确, 翻到的行合起来恰好是全部偏离 (不重不漏)")
    void pagination() {
        PageResponse<ProcessParamDeviationDTO.DetailRow> p1 =
                controller.details(F, null, null, null, null, null, null, 1, 1).getData();
        PageResponse<ProcessParamDeviationDTO.DetailRow> p2 =
                controller.details(F, null, null, null, null, null, null, 2, 1).getData();
        PageResponse<ProcessParamDeviationDTO.DetailRow> p3 =
                controller.details(F, null, null, null, null, null, null, 3, 1).getData();
        PageResponse<ProcessParamDeviationDTO.DetailRow> p4 =
                controller.details(F, null, null, null, null, null, null, 4, 1).getData();
        assertThat(p1.getTotalElements()).isEqualTo(3);
        assertThat(p1.getTotalPages()).isEqualTo(3);
        assertThat(p4.getContent()).as("超出最后一页是空页, 不是报错").isEmpty();
        assertThat(List.of(p1, p2, p3)).allSatisfy(p -> assertThat(p.getContent()).hasSize(1));
        assertThat(List.of(p1, p2, p3)).extracting(p -> p.getContent().get(0).getReportId() + p.getContent().get(0).getParamKey())
                .doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------ 跨租户

    @Test
    @DisplayName("跨租户: 另一个工厂同名工序同日的偏离不进本厂汇总/明细, 本厂的也不进对方; 对方引用本厂批次 id 也读不到本厂批号")
    void otherFactoryDataNeverAppears() {
        ProductionBatch otherBatch = batch(OTHER, "PPD-X1", "PPD-PT-X");
        reportRepo.saveAndFlush(snapshotReport(OTHER, otherBatch.getId(), "HIGH"));
        // 对方一条报工 batch_id 指向「本厂」的批次 (构造连接泄漏): 对方查询时不能带出本厂批号 / 产品名
        reportRepo.saveAndFlush(snapshotReport(OTHER, a1.getId(), "HIGH"));

        // 阳性对照: 对方的数据确实在库里, 且对方自己查得到
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_reports WHERE factory_id = ?",
                Integer.class, OTHER)).isEqualTo(2);
        ProcessParamDeviationDTO.SummaryView otherView = controller.summary(OTHER, null, null, null, null, null).getData();
        assertThat(row(otherView, "炒制", "oilTemp").getReportCount()).isEqualTo(2);

        // 本厂读数不变
        ProcessParamDeviationDTO.SummaryView mine = controller.summary(F, null, null, null, null, null).getData();
        assertThat(row(mine, "炒制", "oilTemp").getReportCount()).isEqualTo(3);
        assertThat(mine.getTotalChecked()).isEqualTo(5);
        assertThat(controller.details(F, null, null, null, null, null, null, null, null).getData().getTotalElements())
                .isEqualTo(3);

        // 对方的明细里没有本厂的批号 / 产品名 / 本厂用户
        List<ProcessParamDeviationDTO.DetailRow> theirs =
                controller.details(OTHER, null, null, null, null, null, null, null, null).getData().getContent();
        assertThat(theirs).hasSize(2);
        assertThat(theirs).extracting(ProcessParamDeviationDTO.DetailRow::getBatchNumber)
                .doesNotContain("PPD-A1", "PPD-A2", "PPD-B1");
        assertThat(theirs).extracting(ProcessParamDeviationDTO.DetailRow::getProductName).doesNotContain("牛肉干");
    }

    // ------------------------------------------------------------------ 无数据 / 窗口

    @Test
    @DisplayName("无数据: 没有任何报工的厂 ⇒ 汇总 0 行 totalChecked=0、明细 0 条, 不报错不编数")
    void noData() {
        ProcessParamDeviationDTO.SummaryView view = controller.summary("PPD-EMPTY", null, null, null, null, null).getData();
        assertThat(view.getRows()).isEmpty();
        assertThat(view.getTotalChecked()).isZero();
        assertThat(view.getTotalDeviations()).isZero();
        PageResponse<ProcessParamDeviationDTO.DetailRow> page =
                controller.details("PPD-EMPTY", null, null, null, null, null, null, null, null).getData();
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("有数据但没偏离: totalChecked>0 而明细为 0 —— 页面据此区分「没有偏离」和「没有参数数据」")
    void checkedButNoDeviation() {
        ProductionBatch ok = batch(F, "PPD-OK", "PPD-PT-A");
        submit(ok, fry, 1, params("oilTemp", 155, "fryMinutes", 25));
        ProcessParamDeviationDTO.SummaryView okView =
                controller.summary(F, null, null, null, null, "PPD-OK").getData();
        assertThat(okView.getTotalChecked()).isEqualTo(2);
        assertThat(okView.getTotalDeviations()).isZero();
        assertThat(controller.details(F, null, null, null, null, "PPD-OK", null, null, null).getData().getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("时间范围: 窗口外的报工不进; 起止颠倒 / 超过 92 天 ⇒ 400 带提示")
    void windowIsEnforced() {
        // 把 B1 的报工挪到 60 天前: 默认 30 天窗口看不到, 显式 90 天窗口看得到
        jdbc.update("UPDATE production_reports SET report_date = ? WHERE factory_id = ? AND batch_id = ?",
                LocalDate.now().minusDays(60), F, b1.getId());
        assertThat(row(controller.summary(F, null, null, null, null, null).getData(), "炒制", "oilTemp")
                .getReportCount()).isEqualTo(2);
        ProcessParamDeviationDTO.SummaryView wide = controller.summary(F, LocalDate.now().minusDays(90),
                LocalDate.now(), null, null, null).getData();
        assertThat(row(wide, "炒制", "oilTemp").getReportCount()).isEqualTo(3);
        assertThat(wide.getWindow().getStartDate()).isEqualTo(LocalDate.now().minusDays(90));

        // 边界: 92 天可以, 93 天不行
        controller.summary(F, LocalDate.now().minusDays(91), LocalDate.now(), null, null, null);
        assertThatThrownBy(() -> controller.summary(F, LocalDate.now().minusDays(92), LocalDate.now(), null, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("92");
        assertThatThrownBy(() -> controller.details(F, LocalDate.now(), LocalDate.now().minusDays(1), null, null, null,
                null, null, null)).isInstanceOf(BusinessException.class).hasMessageContaining("不能晚于");
    }

    // ------------------------------------------------------------------ 权限声明

    @Test
    @DisplayName("权限: 两个读接口声明的权限码与 CCP 监控读接口同一组 (生产 / 质量读权限任一)")
    void permissionMatchesCcpReadEndpoints() throws Exception {
        List<String> ccp = Arrays.asList(CcpMonitoringController.class
                .getMethod("listForBatch", String.class, String.class)
                .getAnnotation(RequirePermission.class).value());
        assertThat(ccp).as("阳性对照: CCP 读接口确实声明了权限").isNotEmpty();
        for (String name : List.of("summary", "details")) {
            Method m = Arrays.stream(ProcessParamDeviationController.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            assertThat(ann).as("%s 没有 @RequirePermission ⇒ 没有门禁", name).isNotNull();
            assertThat(List.of(ann.value())).as(name).containsExactlyInAnyOrderElementsOf(ccp);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ProcessParamDeviationDTO.SummaryRow row(ProcessParamDeviationDTO.SummaryView view, String process, String key) {
        return view.getRows().stream()
                .filter(r -> process.equals(r.getProcessName()) && key.equals(r.getParamKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("汇总里没有 " + process + "/" + key + ": " + view.getRows()));
    }

    private int yieldReports(String processName) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_reports pr
                JOIN work_process_tasks t ON t.id = pr.work_process_task_id
                JOIN work_processes wp ON wp.id = t.work_process_id
                WHERE pr.factory_id = ? AND pr.report_type = 'YIELD' AND wp.process_name = ?
                """, Integer.class, F, processName);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void submit(ProductionBatch batch, WorkProcess process, int order, Map<String, Object> customFields) {
        WorkProcessTask task = new WorkProcessTask();
        task.setFactoryId(batch.getFactoryId());
        task.setProductionBatchId(batch.getId());
        task.setProductWorkProcessId((long) order);
        task.setWorkProcessId(process.getId());
        task.setProductTypeId(batch.getProductTypeId());
        task.setProcessOrder(order);
        task.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        task = taskRepo.saveAndFlush(task);

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(task.getId());
        req.setReportKind("OUTPUT");
        req.setOutputQuantity(new BigDecimal("80"));
        req.setOutputUnit("kg");
        req.setReporterName("ppd_operator");
        req.setCustomFields(customFields);
        yieldReportController.submitReport(batch.getFactoryId(), batch.getId(), req, 7L, "ppd_operator");
    }

    private ProductionReport snapshotReport(String factory, Long batchId, String status) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("key", "oilTemp");
        check.put("label", "油温");
        check.put("unit", "℃");
        check.put("value", new BigDecimal("170"));
        check.put("standardValue", new BigDecimal("155"));
        check.put("min", new BigDecimal("150"));
        check.put("max", new BigDecimal("160"));
        check.put("status", status);
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("processEntryProcessName", "炒制");
        custom.put("paramChecks", List.of(check));
        return ProductionReport.builder().factoryId(factory).batchId(batchId).workerId(9L).reportType("YIELD")
                .reportDate(LocalDate.now()).customFields(custom).build();
    }

    private ProductionReport unresolvedParamCheckReport(String rawProductTypeId) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("key", "oilTemp");
        check.put("label", "油温");
        check.put("unit", "℃");
        check.put("value", new BigDecimal("170"));
        check.put("standardValue", new BigDecimal("155"));
        check.put("min", new BigDecimal("150"));
        check.put("max", new BigDecimal("160"));
        check.put("status", "HIGH");
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("processEntryProcessName", "炒制");
        custom.put("paramChecks", List.of(check));
        return ProductionReport.builder().factoryId(F).workerId(9L).reportType("YIELD")
                .reportDate(LocalDate.now()).productTypeId(rawProductTypeId).customFields(custom).build();
    }

    private void seedProduct(String id, String name) {
        seedProduct(F, id, name);
    }

    private void seedProduct(String factory, String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factory);
        p.setCode(id);
        p.setName(name);
        p.setUnit("kg");
        p.setCategory("SEMI_FINISHED");
        p.setProductCategory("SEMI_FINISHED");
        p.setIsActive(true);
        p.setCreatedBy(1L);
        productTypeRepo.saveAndFlush(p);
    }

    private ProductionBatch batch(String factory, String number, String productTypeId) {
        return batchRepo.saveAndFlush(ProductionBatch.builder()
                .factoryId(factory).batchNumber(number)
                .productTypeId(productTypeId).quantity(new BigDecimal("100")).unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS).build());
    }

    private WorkProcess process(String factory, String name, List<Map<String, Object>> schema) {
        WorkProcess wp = new WorkProcess();
        wp.setId("PPD-WP-" + UUID.randomUUID().toString().substring(0, 8));
        wp.setFactoryId(factory);
        wp.setProcessName(name);
        wp.setProcessCategory("加工");
        wp.setUnit("kg");
        wp.setDefaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED);
        wp.setCustomFieldSchema(schema);
        return processRepo.saveAndFlush(wp);
    }

    private static Map<String, Object> field(String key, String label, String unit,
                                             Integer standard, Integer min, Integer max) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", "number");
        f.put("enabled", true);
        f.put("unit", unit);
        if (standard != null) f.put("standardValue", standard);
        f.put("min", min);
        f.put("max", max);
        return f;
    }
}
