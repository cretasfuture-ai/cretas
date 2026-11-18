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
    static final String RETRY_HINT="\n上一次输出未通过校验。请只返回一个 JSON 对象 {\"conclusions\":[{\"text\":\"...\",\"refs\":[\"指标id\"]}]}：不要加说明文字或代码围栏；text 里不要出现任何阿拉伯数字或「三成」「两倍」之类的数量词；{{指标id}} 必须逐字取自快照、值不为 null，且同时列在 refs 里。";
    /** 工艺保密: 发给模型的快照不带当前调用者 (定时任务 = 无权) 无权查看的保密产品的成本/毛利 (Steve 10-29); 单测直接 new 本类时用「没有保密产品」的默认实例。 */
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
            var secret=llmCostScope.forCurrentCaller(context.factoryId());
            // 与页面读取遮挡同一个集合: 保密产品、计划锚点、同计划联产的普通产品
            Map<String,Object> promptHigh=OperatingReportGrossMarginQuery.withoutProducts(input.marginHigh(),secret.withheldProducts(),secret.protectedProductTypeIds(),false);
            Map<String,Object> promptLow=OperatingReportGrossMarginQuery.withoutProducts(input.marginLow(),secret.withheldProducts(),secret.protectedProductTypeIds(),true);
            int excludedRows=((Number)promptHigh.get("excludedRowCount")).intValue();
            int excludedProducts=((Number)promptHigh.get("excludedProtectedProductCount")).intValue();
            OperatingReportSnapshot promptSnapshot=assembler.assemble(new OperatingReportAssembler.Input(input.type(),input.start(),input.end(),
                    input.fetchedAt(),input.receipts(),input.sales(),input.previous(),input.lastYear(),input.region(),promptHigh,promptLow,
                    input.aging(),input.income(),input.inventory(),input.inventoryCostCoverage())).withoutDetails();
            stage=OperatingReportSnapshot.Failure.MODEL_UNAVAILABLE;
            String system="你是食品工厂经营分析助手，面向老板写简短的经营判断和建议。只能依据下方「经营指标快照」里的指标写结论；快照里没有的事实一律不写。数字不许自己写：需要引用数字时写成 {{指标id}} 占位（指标id逐字取自快照），系统会替换成真实值；正文里不要出现任何阿拉伯数字或中文数量词（如「三成」「两倍」），可以写产品名、业务员名、地区名等快照里出现的名称。只返回 JSON 对象，形如 {\"conclusions\":[{\"text\":\"...\",\"refs\":[\"指标id\"]}]}，每条严格含 text 与 refs 两个字段，text 不超过 150 字，refs 列出该条用到的全部指标id，text 里的每个 {{指标id}} 必须在 refs 里。";
            String prompt="请写 3 到 6 条结论，每条是业务判断加可执行建议，例如：哪个产品毛利最高或最低、环比同比变化说明什么、应收账龄风险、成本覆盖不全对判断的影响、该做什么。环比或同比有变化时，要说清变化主要来自哪些业务员、地区或产品（只用快照里有的名称，快照里没有依据就说明依据不足，不要编原因）。用老板听得懂的业务语言，text 里不要出现指标id、字段名、接口名或英文代码，指标id 只能写在 {{ }} 占位和 refs 里。只用值不为 null 的指标做占位；成本或毛利数据缺失时就说明缺什么、先补什么，不要猜。若订单数为 0，说明本期没有订单这一事实，不要写「数据缺失」。禁止其他字段。\n";
            var config=prompts.findFirstByFactoryIdAndReportTypeAndIsActiveTrueOrderByPriorityDesc(context.factoryId(),type)
                    .orElseGet(()->prompts.findFirstByFactoryIdIsNullAndReportTypeAndIsActiveTrueOrderByPriorityDesc(type).orElse(null));
            system+=llmCostScope.harnessFor(context.factoryId());
            if(config!=null)prompt+="工厂关注方向（不改变上述输出合同）："+config.getPromptTemplate()+"\n";
            if(excludedRows>0)prompt+="产品毛利与毛利汇总"+com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.note(excludedProducts)+"\n";
            prompt+="经营指标快照：\n"+codec.encode(promptSnapshot);
            // 快照里的指标标签 / 工厂关注方向可能带客户、人名、保密产品名: 换代号再发, 脱敏不了按模型不可用记失败
            var redacted=llmEgressGuard.textsWithRecord(EGRESS_SITE,context.factoryId(),promptSnapshot,java.util.Map.of(),java.util.List.of(system,prompt));
            if(redacted.refused())throw new IllegalStateException("Operating report prompt could not be redacted");
            // Existing configured application model client; no process, credential or provider override.
            String output=redacted.restoreJsonText(model.chat(redacted.text(0),redacted.text(1)));
            stage=OperatingReportSnapshot.Failure.MODEL_OUTPUT_INVALID;
            java.util.List<OperatingReportSnapshot.Insight> insights;
            try{insights=codec.parseModelOutput(output,snapshot);}
            catch(IllegalArgumentException firstReject){
                // 小模型偶发漏字段 / 夹数字: 带着「上一版哪里不合格」重问一次; 仍不合格就如实记失败, 不降级、不造内容
                log.warn("Operating report output rejected, retrying once: factory={}",context.factoryId());
                stage=OperatingReportSnapshot.Failure.MODEL_UNAVAILABLE;
                String retryOutput=redacted.restoreJsonText(model.chat(redacted.text(0),redacted.text(1)+RETRY_HINT));
                stage=OperatingReportSnapshot.Failure.MODEL_OUTPUT_INVALID;
                insights=codec.parseModelOutput(retryOutput,snapshot);
            }
            snapshot=snapshot.withInsights(insights).withAudit(context.audit("SUCCESS"));
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
