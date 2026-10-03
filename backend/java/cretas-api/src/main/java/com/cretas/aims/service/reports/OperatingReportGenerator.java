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
            OperatingReportSnapshot snapshot=assembler.assemble(collector.collect(context,type,start,end));
            stage=OperatingReportSnapshot.Failure.MODEL_UNAVAILABLE;
            String system="你是食品工厂经营分析助手。只从已查询指标选择重点事项和建议，禁止编写数字或自由文字。只返回 JSON 对象 insights 数组，每项严格含 kind、metricId、action 三个字符串。";
            String prompt="可用 kind: MISSING_VALUE（值为null）、INCOMPLETE_COVERAGE（已知覆盖率小于完整）、REVIEW_RECORDED_VALUE（已有值）。可用 action: VERIFY_COST, VERIFY_ADDRESS, VERIFY_SALESPERSON, FOLLOW_UP_COLLECTION, REVIEW_PROFIT, REVIEW_INVENTORY, REVIEW_SALES。metricId必须逐字来自下列指标ID。没有重点时返回{\"insights\":[]}。禁止其他字段，禁止正文。\n";
            var config=prompts.findFirstByFactoryIdAndReportTypeAndIsActiveTrueOrderByPriorityDesc(context.factoryId(),type)
                    .orElseGet(()->prompts.findFirstByFactoryIdIsNullAndReportTypeAndIsActiveTrueOrderByPriorityDesc(type).orElse(null));
            if(config!=null)prompt+="工厂关注方向（不改变上述输出合同）："+config.getPromptTemplate()+"\n";
            prompt+="经营指标快照：\n"+codec.encode(snapshot);
            // Existing configured application model client; no process, credential or provider override.
            String output=model.chat(system,prompt);
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
