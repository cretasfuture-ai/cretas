package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityDefect;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DefectType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityDefectRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.QualityDefectService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.impl.QualityDefectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.needMoreInfoQuestions;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.parse;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 质检两个写工具 (quality_check_create / quality_defect_record) 的只读预览。
 *
 * <p>真实入口 {@code preview(ToolCall, context)}, 只桩掉 service / repository。每条「能做」配一条「不能做」的对照,
 * 每条断言写方法 (createInspection / recordDefect) 一次都没被调; 「必发生」的对照是同样输入走 execute 时它们确实被调了。
 */
class QualityToolsPreviewTest {

    private static final String FACTORY = "F006";
    private static final long USER = 42L;
    private static final long BATCH_ID = 12L;
    private static final String INSPECTION_ID = "qi-1";

    private QualityInspectionService inspectionService;
    private QualityDefectService defectService;
    private ProductionBatchRepository batchRepo;
    private UserRepository userRepo;
    private RawMaterialTypeService materialTypeService;
    private QualityCheckCreateTool createTool;
    private QualityDefectRecordTool defectTool;

    @BeforeEach
    void setUp() throws Exception {
        inspectionService = mock(QualityInspectionService.class);
        defectService = mock(QualityDefectService.class);
        batchRepo = mock(ProductionBatchRepository.class);
        userRepo = mock(UserRepository.class);
        materialTypeService = mock(RawMaterialTypeService.class);

        createTool = new QualityCheckCreateTool();
        ReflectionTestUtils.setField(createTool, "qualityInspectionService", inspectionService);
        ReflectionTestUtils.setField(createTool, "productionBatchRepository", batchRepo);
        ReflectionTestUtils.setField(createTool, "userRepository", userRepo);
        injectMapper(createTool);

        defectTool = new QualityDefectRecordTool();
        ReflectionTestUtils.setField(defectTool, "qualityDefectService", defectService);
        ReflectionTestUtils.setField(defectTool, "qualityInspectionService", inspectionService);
        ReflectionTestUtils.setField(defectTool, "productionBatchRepository", batchRepo);
        ReflectionTestUtils.setField(defectTool, "rawMaterialTypeService", materialTypeService);
        injectMapper(defectTool);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("B20261006-01");
        batch.setProductName("卤猪蹄");
        when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(batch));
    }

    // ==================== quality_check_create ====================

    @Test
    @DisplayName("建质检任务: 参数对 → canDo=true, 确认卡写批次号（产品）/ 中文类型 / 检验员 / 计划日期 / 待检验; 不调 createInspection")
    void checkCreateAllowed() throws Exception {
        Map<String, Object> d = data(createTool.preview(call("quality_check_create", createArgs()), ctx(FACTORY, USER)));

        assertThat(createTool.supportsPreview()).isTrue();
        assertThat(d.get("canDo")).isEqualTo(true);
        assertThat(d.get("status")).isEqualTo("PREVIEW");
        assertThat(rows(d, "currentValues")).containsEntry("生产批次", "B20261006-01（卤猪蹄）").containsEntry("质检任务", "还没有");
        assertThat(rows(d, "newValues")).containsEntry("质检类型", "感官检验").containsEntry("检验员", "你本人")
                .containsEntry("计划检验日期", LocalDate.now().toString()).containsEntry("质检任务", "新建一条（待检验）");
        assertThat(String.valueOf(d.get("message"))).contains("B20261006-01（卤猪蹄）").contains("「感官检验」").contains("待检验")
                .doesNotContain("SENSORY");
        assertPlainChinese(String.valueOf(d.get("message")));
        verify(inspectionService, never()).createInspection(anyString(), any(QualityInspection.class));
        verifyNoMoreInteractions(inspectionService);
    }

    @Test
    @DisplayName("建质检任务: 批次不属于本厂 → canDo=false; 同一个批次编号在本厂存在就放行 (对照)")
    void checkCreateBatchMustBelongToThisFactory() throws Exception {
        Map<String, Object> other = createArgs();
        other.put("batchId", "99");
        when(batchRepo.findByIdAndFactoryId(99L, FACTORY)).thenReturn(Optional.empty());
        Map<String, Object> d = data(createTool.preview(call("quality_check_create", other), ctx(FACTORY, USER)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("BATCH_NOT_FOUND");
        assertThat(String.valueOf(d.get("message"))).contains("找不到这个生产批次").doesNotContain("99");

        assertThat(data(createTool.preview(call("quality_check_create", createArgs()), ctx(FACTORY, USER))).get("canDo"))
                .isEqualTo(true);
        verify(inspectionService, never()).createInspection(anyString(), any());
    }

    @Test
    @DisplayName("建质检任务: 批次编号不是数字 → canDo=false (execute 同样拒绝); 数字就放行")
    void checkCreateBatchIdMustBeNumeric() throws Exception {
        Map<String, Object> bad = createArgs();
        bad.put("batchId", "B20261006-01");
        Map<String, Object> d = data(createTool.preview(call("quality_check_create", bad), ctx(FACTORY, USER)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(d.get("message"))).contains("没有认出这个生产批次");
        verify(batchRepo, never()).findByIdAndFactoryId(anyLong(), anyString());
        assertThat(data(createTool.preview(call("quality_check_create", createArgs()), ctx(FACTORY, USER))).get("canDo"))
                .isEqualTo(true);
    }

    @Test
    @DisplayName("建质检任务: 质检类型认五个代码 (大小写不拘) 与它们的中文名; 认不出 → canDo=false 并列出可选项")
    void checkCreateTypes() throws Exception {
        Set<String> shown = new LinkedHashSet<>();
        for (String type : new String[]{"SENSORY", "physical", "化学检验", "MICROBIOLOGICAL", "MATERIAL"}) {
            Map<String, Object> a = createArgs();
            a.put("checkType", type);
            Map<String, Object> d = data(createTool.preview(call("quality_check_create", a), ctx(FACTORY, USER)));
            assertThat(d.get("canDo")).as(type).isEqualTo(true);
            shown.add(String.valueOf(rows(d, "newValues").get("质检类型")));
        }
        assertThat(shown).containsExactly("感官检验", "物理检验", "化学检验", "微生物检验", "原材料检验");

        Map<String, Object> junk = createArgs();
        junk.put("checkType", "随便看看");
        Map<String, Object> d = data(createTool.preview(call("quality_check_create", junk), ctx(FACTORY, USER)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(String.valueOf(d.get("message"))).contains("不认识的质检类型「随便看看」").contains("感官检验 / 物理检验");
        verify(inspectionService, never()).createInspection(anyString(), any());
    }

    @Test
    @DisplayName("建质检任务: 计划日期四种写法都认, 写错 → canDo=false; 检验员指定别人 → 显示姓名, 查不到姓名 → 「你指定的检验员」")
    void checkCreateDateAndInspector() throws Exception {
        for (String date : new String[]{"2026-10-08", "2026-10-08 09:30:00", "2026/10/08", "20261008"}) {
            Map<String, Object> a = createArgs();
            a.put("scheduledTime", date);
            Map<String, Object> d = data(createTool.preview(call("quality_check_create", a), ctx(FACTORY, USER)));
            assertThat(rows(d, "newValues")).as(date).containsEntry("计划检验日期", "2026-10-08");
        }
        Map<String, Object> badDate = createArgs();
        badDate.put("scheduledTime", "下周三");
        assertThat(data(createTool.preview(call("quality_check_create", badDate), ctx(FACTORY, USER))).get("canDo"))
                .isEqualTo(false);

        User wang = new User();
        wang.setId(77L);
        wang.setFullName("王质检");
        when(userRepo.findById(77L)).thenReturn(Optional.of(wang));
        Map<String, Object> other = createArgs();
        other.put("inspectorId", 77);
        assertThat(rows(data(createTool.preview(call("quality_check_create", other), ctx(FACTORY, USER))), "newValues"))
                .containsEntry("检验员", "王质检");
        Map<String, Object> unknown = createArgs();
        unknown.put("inspectorId", 78);
        assertThat(rows(data(createTool.preview(call("quality_check_create", unknown), ctx(FACTORY, USER))), "newValues"))
                .containsEntry("检验员", "你指定的检验员");
        Map<String, Object> self = createArgs();
        self.put("inspectorId", (int) USER);
        assertThat(rows(data(createTool.preview(call("quality_check_create", self), ctx(FACTORY, USER))), "newValues"))
                .containsEntry("检验员", "你本人");
    }

    @Test
    @DisplayName("建质检任务: 缺批次 / 缺类型 → NEED_MORE_INFO, 追问是人话 (没有枚举码、没有「ID」)")
    void checkCreateAsksInPlainChinese() throws Exception {
        Map<String, Object> noBatch = createArgs();
        noBatch.remove("batchId");
        Map<String, Object> noType = createArgs();
        noType.remove("checkType");
        String q1 = needMoreInfoQuestions(createTool.preview(call("quality_check_create", noBatch), ctx(FACTORY, USER))).get(0);
        String q2 = needMoreInfoQuestions(createTool.preview(call("quality_check_create", noType), ctx(FACTORY, USER))).get(0);
        assertThat(q1).isNotEqualTo(q2).contains("哪个生产批次").doesNotContain("ID");
        assertThat(q2).contains("感官检验").contains("原材料检验").doesNotContain("SENSORY").doesNotContain("MATERIAL");
        verify(inspectionService, never()).createInspection(anyString(), any());
    }

    @Test
    @DisplayName("必发生对照: 预览放行的输入走 execute, createInspection 真的被调一次; 预览本身一次都没调")
    void checkCreateExecuteReallyWrites() throws Exception {
        when(inspectionService.createInspection(anyString(), any(QualityInspection.class))).thenAnswer(inv -> {
            QualityInspection in = inv.getArgument(1);
            in.setId("qi-new");
            return in;
        });
        data(createTool.preview(call("quality_check_create", createArgs()), ctx(FACTORY, USER)));
        verify(inspectionService, never()).createInspection(anyString(), any());

        Map<String, Object> exec = parse(createTool.execute(call("quality_check_create", createArgs()), ctx(FACTORY, USER)));
        assertThat(exec.get("success")).isEqualTo(true);
        verify(inspectionService, times(1)).createInspection(anyString(), any(QualityInspection.class));
    }

    // ==================== quality_defect_record ====================

    @Test
    @DisplayName("登记不良: 参数对 → canDo=true, 确认卡写质检记录标题 / 中文不良类型 × 数量 / 待处理; 不调 recordDefect")
    void defectAllowed() throws Exception {
        stubInspection(INSPECTION_ID, FACTORY, "PENDING");

        Map<String, Object> d = data(defectTool.preview(call("quality_defect_record", defectArgs()), ctx(FACTORY, USER)));

        assertThat(defectTool.supportsPreview()).isTrue();
        assertThat(d.get("canDo")).isEqualTo(true);
        String title = LocalDate.of(2026, 10, 6) + " 的质检（B20261006-01（卤猪蹄））";
        assertThat(rows(d, "currentValues")).containsEntry("质检记录", title).containsEntry("质检结果", "待检验");
        assertThat(rows(d, "newValues")).containsEntry("新增不良", "外观缺陷 × 3").containsEntry("处理状态", "待处理")
                .containsEntry("不良原因", "封口不严").containsEntry("质检结果", "待检验");
        assertThat(String.valueOf(d.get("message"))).contains("登记不良：外观缺陷 3").doesNotContain("APPEARANCE")
                .doesNotContain(INSPECTION_ID);
        assertPlainChinese(String.valueOf(d.get("message")));
        verify(defectService, never()).recordDefect(anyString(), anyString(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(defectService);
    }

    @Test
    @DisplayName("登记不良: 质检记录不存在 / 属于别的厂 → canDo=false (不回显 id); 在本厂存在就放行 (对照)")
    void defectInspectionMustExistInThisFactory() throws Exception {
        stubInspection(INSPECTION_ID, FACTORY, "PENDING");
        when(inspectionService.getInspectionById(FACTORY, "qi-x")).thenThrow(new EntityNotFoundException("QualityInspection", "qi-x"));

        Map<String, Object> missing = defectArgs();
        missing.put("qualityInspectionId", "qi-x");
        Map<String, Object> d = data(defectTool.preview(call("quality_defect_record", missing), ctx(FACTORY, USER)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("INSPECTION_NOT_FOUND");
        assertThat(String.valueOf(d.get("message"))).contains("找不到这条质检记录").doesNotContain("qi-x");

        assertThat(data(defectTool.preview(call("quality_defect_record", defectArgs()), ctx(FACTORY, USER))).get("canDo"))
                .isEqualTo(true);
        verify(defectService, never()).recordDefect(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("登记不良: 九种不良代码 (大小写不拘) 都放行并显示中文名; 中文名 / 乱写 → canDo=false (execute 同样不认中文名)")
    void defectTypes() throws Exception {
        stubInspection(INSPECTION_ID, FACTORY, "PENDING");
        Set<String> shown = new LinkedHashSet<>();
        for (DefectType t : DefectType.values()) {
            Map<String, Object> a = defectArgs();
            a.put("defectType", t.ordinal() % 2 == 0 ? t.name() : t.name().toLowerCase());
            Map<String, Object> d = data(defectTool.preview(call("quality_defect_record", a), ctx(FACTORY, USER)));
            assertThat(d.get("canDo")).as(t.name()).isEqualTo(true);
            shown.add(String.valueOf(rows(d, "newValues").get("新增不良")));
        }
        assertThat(shown).hasSize(DefectType.values().length);   // 参数化那一维真的在变

        for (String bad : new String[]{"外观缺陷", "SCRATCH"}) {
            Map<String, Object> a = defectArgs();
            a.put("defectType", bad);
            Map<String, Object> d = data(defectTool.preview(call("quality_defect_record", a), ctx(FACTORY, USER)));
            assertThat(d.get("canDo")).as(bad).isEqualTo(false);
            assertThat(String.valueOf(d.get("message"))).contains("不认识的不良类型「" + bad + "」").contains("外观缺陷 / 重量不达标");
        }
        verify(defectService, never()).recordDefect(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("登记不良: 数量 0 / 负数 → canDo=false; 关联物料能查到就显示名字, 查不到显示「已指定的物料」(不回显 id)")
    void defectQuantityAndMaterial() throws Exception {
        stubInspection(INSPECTION_ID, FACTORY, "PENDING");
        for (Object bad : new Object[]{0, -1}) {
            Map<String, Object> a = defectArgs();
            a.put("quantity", bad);
            Map<String, Object> d = data(defectTool.preview(call("quality_defect_record", a), ctx(FACTORY, USER)));
            assertThat(d.get("canDo")).as(String.valueOf(bad)).isEqualTo(false);
            assertThat(String.valueOf(d.get("message"))).contains("不良数量必须大于 0");
        }
        com.cretas.aims.dto.material.RawMaterialTypeDTO type = new com.cretas.aims.dto.material.RawMaterialTypeDTO();
        type.setName("猪蹄原料");
        when(materialTypeService.getMaterialTypeById(FACTORY, "mat-1")).thenReturn(type);
        when(materialTypeService.getMaterialTypeById(FACTORY, "mat-x")).thenThrow(new BusinessException(403, "无权限"));
        Map<String, Object> known = defectArgs();
        known.put("materialId", "mat-1");
        assertThat(rows(data(defectTool.preview(call("quality_defect_record", known), ctx(FACTORY, USER))), "newValues"))
                .containsEntry("关联物料", "猪蹄原料");
        Map<String, Object> unknown = defectArgs();
        unknown.put("materialId", "mat-x");
        assertThat(rows(data(defectTool.preview(call("quality_defect_record", unknown), ctx(FACTORY, USER))), "newValues"))
                .containsEntry("关联物料", "已指定的物料");
    }

    @Test
    @DisplayName("登记不良: 缺质检记录 / 缺类型 / 缺数量 → 各自 NEED_MORE_INFO, 追问互不相同且是人话")
    void defectAsksInPlainChinese() throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        for (String missing : new String[]{"qualityInspectionId", "defectType", "quantity"}) {
            Map<String, Object> a = defectArgs();
            a.remove(missing);
            String q = needMoreInfoQuestions(defectTool.preview(call("quality_defect_record", a), ctx(FACTORY, USER))).get(0);
            assertThat(seen.add(q)).as(missing).isTrue();
            assertThat(q).doesNotContain("ID");
        }
        verify(defectService, never()).recordDefect(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("与真实 QualityDefectServiceImpl 逐格比对: 质检记录 {本厂, 别厂, 不存在} × 数量 {0, 1}, 预览 canDo == 真实写路径放行")
    void defectParityWithRealService() throws Exception {
        QualityDefectRepository defectRepo = mock(QualityDefectRepository.class);
        QualityInspectionRepository inspectionRepo = mock(QualityInspectionRepository.class);
        QualityDefectServiceImpl real = new QualityDefectServiceImpl(defectRepo, inspectionRepo);
        when(defectRepo.save(any(QualityDefect.class))).thenAnswer(inv -> inv.getArgument(0));
        // 预览那一侧读质检记录的方式 = 同一个仓库、同一个「必须属于本工厂」口径
        when(inspectionService.getInspectionById(anyString(), anyString())).thenAnswer(inv -> {
            String factory = inv.getArgument(0);
            String id = inv.getArgument(1);
            return inspectionRepo.findById(id).filter(i -> i.getFactoryId().equals(factory))
                    .orElseThrow(() -> new EntityNotFoundException("QualityInspection", id));
        });
        when(inspectionRepo.findById("qi-mine")).thenReturn(Optional.of(inspection("qi-mine", FACTORY)));
        when(inspectionRepo.findById("qi-theirs")).thenReturn(Optional.of(inspection("qi-theirs", "F999")));
        when(inspectionRepo.findById("qi-none")).thenReturn(Optional.empty());

        int allowed = 0;
        int refused = 0;
        for (String id : new String[]{"qi-mine", "qi-theirs", "qi-none"}) {
            for (int qty : new int[]{0, 1}) {
                Map<String, Object> a = defectArgs();
                a.put("qualityInspectionId", id);
                a.put("quantity", qty);
                boolean previewYes = Boolean.TRUE.equals(
                        data(defectTool.preview(call("quality_defect_record", a), ctx(FACTORY, USER))).get("canDo"));
                boolean realYes;
                try {
                    real.recordDefect(FACTORY, id, null, DefectType.APPEARANCE, BigDecimal.valueOf(qty), "x", USER);
                    realYes = true;
                } catch (RuntimeException e) {
                    realYes = false;
                }
                assertThat(previewYes).as("质检记录=%s 数量=%s", id, qty).isEqualTo(realYes);
                if (realYes) {
                    allowed++;
                } else {
                    refused++;
                }
            }
        }
        assertThat(allowed).isEqualTo(1);   // 只有「本厂 × 数量1」
        assertThat(refused).isEqualTo(5);
        verify(defectRepo, times(1)).save(any(QualityDefect.class));   // 必发生: 真实写路径放行的那一格确实落库了
    }

    // ==================== helpers ====================

    private void stubInspection(String id, String factory, String result) {
        QualityInspection inspection = inspection(id, factory);
        inspection.setResult(result);
        when(inspectionService.getInspectionById(factory, id)).thenReturn(inspection);
    }

    private static QualityInspection inspection(String id, String factory) {
        return QualityInspection.builder()
                .id(id)
                .factoryId(factory)
                .productionBatchId(BATCH_ID)
                .inspectionDate(LocalDate.of(2026, 10, 6))
                .result("PENDING")
                .build();
    }

    private static Map<String, Object> createArgs() {
        Map<String, Object> a = new HashMap<>();
        a.put("batchId", String.valueOf(BATCH_ID));
        a.put("checkType", "SENSORY");
        return a;
    }

    private static Map<String, Object> defectArgs() {
        Map<String, Object> a = new HashMap<>();
        a.put("qualityInspectionId", INSPECTION_ID);
        a.put("defectType", "APPEARANCE");
        a.put("quantity", 3);
        a.put("cause", "封口不严");
        return a;
    }
}
