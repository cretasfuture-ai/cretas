package com.cretas.aims.service.reports;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIReportPromptConfigRepository;
import com.cretas.aims.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class OperatingReportGenerator {
    private final FactoryRepository factories;
    private final PermissionService permissions;
    private final OperatingReportCollector collector;
    private final OperatingReportAssembler assembler;
    private final OperatingReportCodec codec;
    private final OperatingReportStore store;
    private final PythonLLMClient model;
    private final AIReportPromptConfigRepository prompts;
    /** 发给模型前脱敏; 单测直接 new 本类时用默认实例。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.consent.LlmEgressGuard llmEgressGuard=com.cretas.aims.service.consent.LlmEgressGuard.standalone();
    static final String EGRESS_SITE="operating-report";
    /** 工艺保密: 发给模型的快照不带保密产品的成本/毛利 (Steve 10-28); 单测直接 new 本类时用「没有保密产品」的默认实例。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope llmCostScope=com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.standalone();
    public OperatingReportGenerator(FactoryRepository factories,PermissionService permissions,OperatingReportCollector collector,
                                    OperatingReportAssembler assembler,OperatingReportCodec codec,OperatingReportStore store,
                                    PythonLLMClient model,AIReportPromptConfigRepository prompts){
        this.factories=factories;this.permissions=permissions;this.collector=collector;this.assembler=assembler;
        this.codec=codec;this.store=store;this.model=model;this.prompts=prompts;
    }
    public boolean isFactory(String factoryId){return factories.findById(factoryId).map(f->f.getType()==FactoryType.FACTORY).orElse(false);}
    /** Legacy contracts are retained only for an explicitly known non-factory tenant. */
    public boolean usesLegacyReportContract(String factoryId){
        return factories.findById(factoryId).map(f->f.getType()!=null&&f.getType()!=FactoryType.FACTORY).orElse(false);
    }
    /** Only the existing scheduled application entry calls this; no HTTP actor can fall back here. */
    public AIAnalysisResult scheduled(String factoryId,String type,LocalDate start,LocalDate end){
        Factory authorizedFactory=factories.findById(factoryId).orElseThrow(()->new com.cretas.aims.exception.BusinessException(403,"Factory capability unavailable"));
        OperatingReportContext context=OperatingReportContext.scheduled(authorizedFactory,MDC.get("correlationId"));
        validatePeriod(type,start,end);
        AIAnalysisResult report=generate(context,type,start,end,true);
        if(codec.decode(report.getAnalysisText()).orElseThrow().status()==OperatingReportSnapshot.Status.FAILED)
            throw new IllegalStateException("Scheduled operating report failed; reason saved in report history");
        return report;
    }
    public AIAnalysisResult manual(String factoryId,User actor,String type,LocalDate start,LocalDate end){
        OperatingReportContext context=OperatingReportContext.manual(factoryId,actor,permissions,MDC.get("correlationId"));
        if(!isFactory(factoryId))throw new com.cretas.aims.exception.BusinessException(403,"Operating report factory scope required");
        validatePeriod(type,start,end);
        return generate(context,type,start,end,false);
    }
    private AIAnalysisResult generate(OperatingReportContext context,String type,LocalDate start,LocalDate end,boolean automatic){
        OperatingReportSnapshot.Failure stage=OperatingReportSnapshot.Failure.SOURCE_UNAVAILABLE;
        try {
            OperatingReportAssembler.Input input=collector.collect(context,type,start,end);
            OperatingReportSnapshot snapshot=assembler.assemble(input);
            // 工艺保密: 给模型的是另一份快照 —— 产品毛利榜去掉保密产品, 毛利汇总只在剩下的产品上重算;
            // 保存/展示的快照不变。取不到保密范围就按取数失败记 (不发)。
            var secret=llmCostScope.forFactory(context.factoryId());
            Map<String,Object> promptHigh=OperatingReportGrossMarginQuery.withoutProducts(input.marginHigh(),secret.productTypeIds(),secret.protectedProductTypeIds(),false);
            Map<String,Object> promptLow=OperatingReportGrossMarginQuery.withoutProducts(input.marginLow(),secret.productTypeIds(),secret.protectedProductTypeIds(),true);
            int excludedRows=((Number)promptHigh.get("excludedRowCount")).intValue();
            int excludedProducts=((Number)promptHigh.get("excludedProtectedProductCount")).intValue();
            OperatingReportSnapshot promptSnapshot=assembler.assemble(new OperatingReportAssembler.Input(input.type(),input.start(),input.end(),
                    input.fetchedAt(),input.receipts(),input.sales(),input.previous(),input.lastYear(),input.region(),promptHigh,promptLow,
                    input.aging(),input.income(),input.inventory(),input.inventoryCostCoverage()));
            stage=OperatingReportSnapshot.Failure.MODEL_UNAVAILABLE;
            String system="你是食品工厂经营分析助手。只从已查询指标选择重点事项和建议，禁止编写数字或自由文字。只返回 JSON 对象 insights 数组，每项严格含 kind、metricId、action 三个字符串。";
            String prompt="可用 kind: MISSING_VALUE（值为null）、INCOMPLETE_COVERAGE（已知覆盖率小于完整）、REVIEW_RECORDED_VALUE（已有值）。可用 action: VERIFY_COST, VERIFY_ADDRESS, VERIFY_SALESPERSON, FOLLOW_UP_COLLECTION, REVIEW_PROFIT, REVIEW_INVENTORY, REVIEW_SALES。metricId必须逐字来自下列指标ID。没有重点时返回{\"insights\":[]}。禁止其他字段，禁止正文。\n";
            var config=prompts.findFirstByFactoryIdAndReportTypeAndIsActiveTrueOrderByPriorityDesc(context.factoryId(),type)
                    .orElseGet(()->prompts.findFirstByFactoryIdIsNullAndReportTypeAndIsActiveTrueOrderByPriorityDesc(type).orElse(null));
            if(config!=null)prompt+="工厂关注方向（不改变上述输出合同）："+config.getPromptTemplate()+"\n";
            if(excludedRows>0)prompt+="产品毛利与毛利汇总"+com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.note(excludedProducts)+"\n";
            prompt+="经营指标快照：\n"+codec.encode(promptSnapshot);
            // 快照里的指标标签 / 工厂关注方向可能带客户、人名、保密产品名: 换代号再发, 脱敏不了按模型不可用记失败
            var redacted=llmEgressGuard.textsWithRecord(EGRESS_SITE,context.factoryId(),promptSnapshot,java.util.Map.of(),java.util.List.of(system,prompt));
            if(redacted.refused())throw new IllegalStateException("Operating report prompt could not be redacted");
            // Existing configured application model client; no process, credential or provider override.
            String output=redacted.restoreJsonText(model.chat(redacted.text(0),redacted.text(1)));
            stage=OperatingReportSnapshot.Failure.MODEL_OUTPUT_INVALID;
            snapshot=snapshot.withInsights(codec.parseInsights(output,snapshot)).withAudit(context.audit("SUCCESS"));
            stage=OperatingReportSnapshot.Failure.GENERATION_FAILED;
            return store.save(context.factoryId(),snapshot,automatic);
        } catch(Exception exception){
            log.warn("Operating report failed: factory={}, run={}, code={}",context.factoryId(),context.audit("FAILED").runId(),stage);
            return store.failure(context.factoryId(),type,start,end,stage,automatic,context.audit("FAILED"));
        }
    }
    static void validatePeriod(String type,LocalDate start,LocalDate end){
        if(!Set.of("daily","weekly","monthly").contains(type)||start==null||end==null||start.isAfter(end))throw new IllegalArgumentException("Invalid operating report period");
        if(type.equals("daily")&&!start.equals(end))throw new IllegalArgumentException("Daily report requires one date");
        if(type.equals("weekly")&&java.time.temporal.ChronoUnit.DAYS.between(start,end)!=6)throw new IllegalArgumentException("Weekly report requires seven days");
        if(type.equals("monthly")&&(!start.equals(YearMonth.from(start).atDay(1))||!end.equals(YearMonth.from(start).atEndOfMonth())))throw new IllegalArgumentException("Monthly report requires one complete calendar month");
    }
}
