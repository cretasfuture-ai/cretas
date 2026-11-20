package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.*;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.crm.OrderQueryTool;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.security.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.impl.*;
import com.cretas.aims.service.intent.*;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import okhttp3.OkHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;

/** Local servlet transport proof. Actual Controller/facade/entries/Tools and real models;
 * local test authentication/state/RBAC; current declarations and real authenticated business reads. Not full Spring/JPA deployment or independent acceptance. */
public class CompleteReadHttpProbe {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    static long ACTOR_ID;
    static AIIntentConfigController controller;
    static JwtUtil jwt;
    static final AtomicReference<IntentExecuteRequest> LAST=new AtomicReference<>();
    static final AtomicReference<Map<String,Object>> RECOGNITION=new AtomicReference<>(Map.of());
    static final AtomicReference<Map<String,Object>> TOOL_PARAMETERS=new AtomicReference<>();
    static <T extends ToolExecutor> T observedTool(T real)throws Exception {
        T observed=spy(real);
        doAnswer(call->{
            com.cretas.aims.ai.dto.ToolCall command=call.getArgument(0);
            Map<String,Object> raw=JSON.readValue(command.getFunction().getArguments(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            Map<String,Object> business=new LinkedHashMap<>();
            if(real.getParametersSchema().get("properties") instanceof Map<?,?> properties)
                properties.keySet().forEach(k->{if(k instanceof String key&&raw.containsKey(key))business.put(key,raw.get(key));});
            TOOL_PARAMETERS.set(business);
            return call.callRealMethod();
        }).when(observed).execute(any(),anyMap());
        return observed;
    }
    @Configuration @EnableWebMvc
    public static class Config {
        @Bean public AIIntentConfigController controller(){return controller;}
        @Bean public IntentConfigWriteGuard configWriteGuard(){return mock(IntentConfigWriteGuard.class);}
    }
    static com.cretas.aims.entity.config.AIIntentConfig intent(String code,String tool){
        return com.cretas.aims.entity.config.AIIntentConfig.builder().id("probe-"+tool).factoryId("F006")
                .intentCode(code).toolName(tool).intentName(code).intentCategory("DATA_OPERATION")
                .businessType("FACTORY").isActive(true).sensitivityLevel("LOW").build();
    }

    public static void main(String[]args)throws Exception {
        String output=args[0]; int port=18770; ACTOR_ID=authenticatedReadActor();
        try(var h=new SalesRankingSemanticContractTest.Harness(); var realActor=AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,ACTOR_ID,"F006","factory_super_admin"))) {
            h.owner.setUserId(ACTOR_ID);h.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
            when(h.memory.getOrCreateContext("F006",ACTOR_ID,"sales-session")).thenReturn(h.owner);
            doAnswer(c->{synchronized(h.owner){var current=h.owner.getSlot(EntitySlot.SlotType.READ_QUERY);Object version=current==null||current.getMetadata()==null?null:current.getMetadata().get(ConversationMemoryService.READ_QUERY_VERSION_KEY);if(!Objects.equals(ACTOR_ID,c.getArgument(1))||!Objects.equals(version,c.getArgument(3)))return false;h.owner.setSlot(EntitySlot.SlotType.READ_QUERY,c.getArgument(4));return true;}}).when(h.memory).compareAndSetReadQuery(anyString(),eq(ACTOR_ID),eq("sales-session"),any(),any());
            doCallRealMethod().when(h.dispatch).extractParametersWithLLM(anyString(),anyString(),any(),anyMap());
            h.owner.setLastActiveAt(LocalDateTime.now());
            var entry=new SalesRankingEntryParityContractTest.Entry(h,false);
            // Restore actual network SSE emission; the service test fixture captures it by default.
            doCallRealMethod().when(entry.sse).sendSseEvent(any(),anyString(),any());
            var context=new SemanticConversationContextService(h.memory,Clock.system(ZoneId.of("Asia/Shanghai")));
            ReflectionTestUtils.setField(context,"conversations",h.conversations);
            ReflectionTestUtils.setField(context,"toolRegistry",h.registry);
            var support=new SalesRankingConversationSupport(h.memory,h.conversations,Clock.system(ZoneId.of("Asia/Shanghai")));
            for(Object service:List.of(entry.ordinary,entry.sse)){
                ReflectionTestUtils.setField(service,"semanticConversationContextService",context);
                ReflectionTestUtils.setField(service,"salesRankingConversationSupport",support);
            }
            ReflectionTestUtils.setField(h.dispatch,"salesRankingConversationSupport",support);
            var configs=(IntentConfigManagementService)ReflectionTestUtils.getField(entry.ordinary,"configService");
            var order=intent("ORDER_LIST","order_list");var material=intent("MATERIAL_BATCH_QUERY","material_batch_query");
            var detail=intent("ORDER_STATUS","order_query");
            Map<String,com.cretas.aims.entity.config.AIIntentConfig> actualCatalog=new LinkedHashMap<>();
            for(var row:JSON.readTree(Files.readString(Path.of(args[2])))){
                var config=JSON.treeToValue(row,com.cretas.aims.entity.config.AIIntentConfig.class);
                if(Boolean.TRUE.equals(config.getIsActive()))actualCatalog.put(config.getIntentCode(),config);
            }
            order.setIntentName("销售订单列表");material.setIntentName("原料批次查询");detail.setIntentName("销售订单详情");
            when(configs.hasPermission(anyString(),anyString(),anyString())).thenReturn(true);
            when(configs.hasPermission(anyString(),anyString())).thenReturn(true);
            when(configs.getIntentByCode("F006","ORDER_LIST")).thenReturn(Optional.of(order));
            when(configs.getIntentByCode("F006","MATERIAL_BATCH_QUERY")).thenReturn(Optional.of(material));
            when(configs.getIntentByCode("F006","ORDER_STATUS")).thenReturn(Optional.of(detail));
            when(configs.getIntentByCode("F006",SalesRankingParameters.INTENT)).thenReturn(Optional.of(h.intent));
            when(configs.getIntentByCode(eq("F006"),anyString())).thenAnswer(c->Optional.ofNullable(actualCatalog.get(c.getArgument(1))));
            var choices=new ClarificationCandidateService(configs,new IntentPermissionGate(),
                    new IntentAccessModeFilter(configs,new com.cretas.aims.ai.tool.WriteGuardService(),h.registry),
                    new com.cretas.aims.ai.tool.WriteGuardService(),null,h.registry);
            ReflectionTestUtils.setField(entry.sse,"clarificationCandidateService",choices);
            var pipeline=mock(IntentRecognitionPipelineService.class);
            when(pipeline.recognizeIntentWithConfidence(anyString(),anyString(),anyInt(),any(),any(),any()))
                    .thenAnswer(c->IntentMatchResult.empty(c.getArgument(0)));
            var ai=spy(new AIIntentServiceImpl(pipeline,configs,mock(IntentFeedbackLearningService.class)));
            String modelUrl="http://127.0.0.1:18766";
            ReflectionTestUtils.setField(ai,"pythonClient",new PythonAiMatcherClient(new RestTemplate(),modelUrl,"synthetic-loopback-only"));
            ReflectionTestUtils.setField(ai,"intentResultCache",new IntentResultCache(100,300));
            ReflectionTestUtils.setField(ai,"usePythonMatcher",true);
            ReflectionTestUtils.setField(ai,"pythonMatcherFactories","");
            doAnswer(c->{
                var match=(IntentMatchResult)c.callRealMethod();var observed=new LinkedHashMap<String,Object>();
                if(match!=null){observed.put("selectedIntent",match.getBestMatch()==null?null:match.getBestMatch().getIntentCode());
                    observed.put("confidence",match.getConfidence());observed.put("requiresConfirmation",match.getRequiresConfirmation());}
                RECOGNITION.set(observed);return match;
            }).when(ai).recognizeIntentWithConfidence(anyString(),eq("F006"),anyInt(),any(),anyString(),any(),any(),any(),anyList(),anyMap());
            ReflectionTestUtils.setField(entry.ordinary,"aiIntentService",ai);
            ReflectionTestUtils.setField(entry.sse,"aiIntentService",ai);
            var llm=new DashScopeClient(new PythonLLMClient(modelUrl,new OkHttpClient(),h.mapper));
            ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",llm);
            var repair=new ConversationTaskRepairSupport(h.conversations,llm,mock(PreviewTokenService.class));
            for(Object service:List.of(entry.ordinary,entry.sse))ReflectionTestUtils.setField(service,"conversationTaskRepairSupport",repair);
            ReflectionTestUtils.setField(h.dispatch,"conversationTaskRepairSupport",repair);
            SalesService sales=(SalesService)java.lang.reflect.Proxy.newProxyInstance(SalesService.class.getClassLoader(),new Class[]{SalesService.class},(proxy,method,a)->{
                if(method.getName().equals("toString"))return "RealReadonlySalesApiAdapter";
                Map<String,Object> q=new LinkedHashMap<>();
                if(method.getName().equals("getSalesOrders")){q.put("page",a[1]);q.put("size",a[2]);}
                else if(method.getName().equals("getSalesOrdersFiltered")){if(a[2]!=null||a[3]!=null)throw new UnsupportedOperationException("date-filter transport unavailable");if(a[1]!=null)q.put("status",a[1].toString());q.put("page",a[4]);q.put("size",a[5]);}
                else throw new UnsupportedOperationException("Readonly adapter refuses this method");
                q.put("_candidateSort",JSON.writeValueAsString(com.cretas.aims.service.inventory.impl.SalesServiceImpl.stableOrderReadSort().stream()
                        .map(o->Map.of("property",o.getProperty(),"direction",o.getDirection().name())).toList()));
                return businessPage("orders",q,SalesOrder.class);
            });
            var orderTool=observedTool(new OrderListTool(sales));ReflectionTestUtils.setField(orderTool,"objectMapper",h.mapper);
            var detailTool=observedTool(new OrderQueryTool(sales,mock(SalesOrderRepository.class)));ReflectionTestUtils.setField(detailTool,"objectMapper",h.mapper);
            MaterialBatchService batches=(MaterialBatchService)java.lang.reflect.Proxy.newProxyInstance(MaterialBatchService.class.getClassLoader(),new Class[]{MaterialBatchService.class},(proxy,method,a)->{
                if(method.getName().equals("toString"))return "RealReadonlyStockApiAdapter";
                if(method.getName().equals("getExpiredBatches")){
                    List<com.cretas.aims.dto.material.MaterialBatchDTO> rows=new ArrayList<>();
                    for(var item:businessJson("stock-expired",Map.of()))rows.add(JSON.treeToValue(item,com.cretas.aims.dto.material.MaterialBatchDTO.class));
                    return rows;
                }
                if(!method.getName().equals("getMaterialBatchList"))throw new UnsupportedOperationException("Readonly adapter refuses this method");
                var page=(com.cretas.aims.dto.common.PageRequest)a[1];
                if(a.length>2){var filter=(com.cretas.aims.dto.material.MaterialBatchListFilter)a[2];if(filter.materialTypeId()!=null||filter.receiptFrom()!=null||filter.receiptTo()!=null)throw new UnsupportedOperationException("extended-filter transport unavailable");}
                if(page.getKeyword()!=null)throw new UnsupportedOperationException("keyword adapter unavailable");
                Map<String,Object> q=new LinkedHashMap<>();q.put("page",page.getPage());q.put("size",page.getSize());if(page.getStatus()!=null)q.put("status",page.getStatus());
                return businessPage("stock",q,com.cretas.aims.dto.material.MaterialBatchDTO.class);
            });
            var materialTool=new MaterialBatchQueryTool();ReflectionTestUtils.setField(materialTool,"materialBatchService",batches);
            ReflectionTestUtils.setField(materialTool,"objectMapper",h.mapper);
            var observedMaterial=observedTool(materialTool);
            when(h.registry.getExecutor("order_list")).thenReturn(Optional.of(orderTool));
            when(h.registry.getExecutor("material_batch_query")).thenReturn(Optional.of(observedMaterial));
            when(h.registry.getExecutor("order_query")).thenReturn(Optional.of(detailTool));
            when(h.registry.getExecutor(SalesRankingParameters.TOOL)).thenReturn(Optional.empty());
            var resume=new com.cretas.aims.ai.tool.impl.system.ContextContinueTool();
            ReflectionTestUtils.setField(resume,"conversationMemoryService",h.memory);
            var nextPage=new com.cretas.aims.ai.tool.impl.system.PaginationNextTool();
            ReflectionTestUtils.setField(nextPage,"conversationMemoryService",h.memory);
            var previousPage=new com.cretas.aims.ai.tool.impl.system.PaginationPreviousTool();
            ReflectionTestUtils.setField(previousPage,"conversationMemoryService",h.memory);
            var expired=new com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool();
            ReflectionTestUtils.setField(expired,"materialBatchService",batches);
            var qc=new com.cretas.aims.ai.tool.impl.workdesk.ReceiveQualityCheckTodayTool();
            var realReadRepository=(com.cretas.aims.repository.MaterialBatchRepository)java.lang.reflect.Proxy.newProxyInstance(
                    com.cretas.aims.repository.MaterialBatchRepository.class.getClassLoader(),new Class[]{com.cretas.aims.repository.MaterialBatchRepository.class},(proxy,method,a)->{
                if(method.getName().equals("toString"))return "AuthenticatedReadonlyMaterialRepositoryAdapter";
                if(method.getName().equals("findByFactoryIdAndStatus")){
                    List<com.cretas.aims.entity.MaterialBatch> rows=new ArrayList<>();
                    for(var item:businessJson("stock-all-status",Map.of("status",a[1].toString())))rows.add(JSON.treeToValue(item,com.cretas.aims.entity.MaterialBatch.class));
                    return rows;
                }
                if(method.getName().equals("countByFactoryIdAndReceiptDateAfter"))
                    return businessJson("stock-receipt-count",Map.of("from",a[1].toString())).path("count").asLong();
                throw new UnsupportedOperationException("Readonly repository adapter refuses "+method.getName());
            });
            ReflectionTestUtils.setField(qc,"materialBatchRepository",realReadRepository);
            for(ToolExecutor tool:List.of(resume,nextPage,previousPage,expired,qc)){
                ReflectionTestUtils.setField(tool,"objectMapper",h.mapper);
                ToolExecutor observed=observedTool(tool);
                when(h.registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(observed));
            }
            var wiring=new LinkedHashMap<String,Object>();
            for(var name:List.of("order_list","order_query","material_batch_query","context_continue","pagination_next","pagination_previous","material_expired_query","receive_quality_check_today")){
                var tool=h.registry.getExecutor(name).orElseThrow(()->new IllegalStateException("Missing tool "+name));
                wiring.put(name,Map.of("class",tool.getClass().getName(),"parameters",tool.getParametersSchema(),"access",tool.getAccessMode().name()));
            }
            Files.writeString(Path.of(args[0]+".wiring.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wiring));
            if(args.length>3 && "--wiring-only".equals(args[3])){
                Map<String,Object> proof=new LinkedHashMap<>();
                for(String name:List.of("material_expired_query","receive_quality_check_today")){
                    String value=h.registry.getExecutor(name).orElseThrow().execute(
                            com.cretas.aims.ai.dto.ToolCall.of("readonly-preflight",name,"{}"),
                            Map.of("factoryId","F006","userId",ACTOR_ID,"userRole","factory_super_admin"));
                    var parsed=JSON.readTree(value);
                    if(!parsed.path("success").asBoolean())throw new IllegalStateException("Readonly tool preflight failed "+name);
                    proof.put(name,parsed);
                }
                Files.writeString(Path.of(args[0]+".preflight.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof));
                return;
            }
            var facade=spy(new IntentExecutorServiceImpl(entry.ordinary,entry.sse,mock(MultiIntentExecutionService.class),h.memory));
            doAnswer(c->{LAST.set(c.getArgument(1));RECOGNITION.set(Map.of());TOOL_PARAMETERS.set(null);return c.callRealMethod();}).when(facade).execute(anyString(),any(),anyLong(),anyString());
            doAnswer(c->{LAST.set(c.getArgument(1));RECOGNITION.set(Map.of());TOOL_PARAMETERS.set(null);return c.callRealMethod();}).when(facade).executeStream(anyString(),any(),anyLong(),anyString());
            jwt=new JwtUtil();ReflectionTestUtils.setField(jwt,"secret",UUID.randomUUID().toString()+UUID.randomUUID());
            ReflectionTestUtils.setField(jwt,"expiration",600000L);
            controller=new AIIntentConfigController(ai,facade,mock(KeywordEffectivenessService.class),mock(IntentConfigRollbackService.class),
                    mock(ParameterExtractionLearningService.class),jwt,configs);
            String token=jwt.generateToken(ACTOR_ID,"F006","synthetic-local-user","factory_super_admin");
            final var liveToken=new AtomicReference<String>(token);
            Tomcat server=new Tomcat();server.setBaseDir(Files.createTempDirectory("semantic-http-").toString());
            server.setHostname("127.0.0.1");server.setPort(port);server.getConnector().setProperty("address","127.0.0.1");
            var web=server.addContext("",Files.createTempDirectory("semantic-http-web-").toString());
            FilterDef filter=new FilterDef();filter.setFilterName("isolated-actor");filter.setAsyncSupported("true");
            filter.setFilter(new Filter(){public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws java.io.IOException,ServletException{
                var r=(HttpServletRequest)req;var p=(HttpServletResponse)res;
                if(!"POST".equals(r.getMethod())||!Set.of("/api/mobile/F006/ai-intents/execute","/api/mobile/F006/ai-intents/execute/stream").contains(r.getRequestURI())
                        ||!("Bearer "+liveToken.get()).equals(r.getHeader("Authorization"))){p.sendError(403);return;}
                r.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,ACTOR_ID,"F006","factory_super_admin"));
                chain.doFilter(req,res);
            }});web.addFilterDef(filter);FilterMap fm=new FilterMap();fm.setFilterName("isolated-actor");fm.addURLPattern("/*");web.addFilterMap(fm);
            var app=new AnnotationConfigWebApplicationContext();app.register(Config.class);
            var servlet=Tomcat.addServlet(web,"mvc",new DispatcherServlet(app));servlet.setAsyncSupported(true);servlet.setLoadOnStartup(1);web.addServletMappingDecoded("/*","mvc");
            List<Map<String,Object>> trace=new ArrayList<>();
            try {
                server.start();var client=HttpClient.newHttpClient();
                var plans=JSON.readTree(Files.readString(Path.of(args[1]))).path("dialogues");
                int caseIndex=0;
                for(var plan:plans){
                    caseIndex++;
                    for(boolean streaming:List.of(false,true)){
                    token=jwt.generateToken(ACTOR_ID,"F006","synthetic-local-user","factory_super_admin");liveToken.set(token);
                    h.owner.getEntitySlots().clear();h.owner.setLastIntentCode(null);h.owner.setMessageCount(0);
                    if(h.owner.getRecentMessages()!=null)h.owner.getRecentMessages().clear();
                    h.owner.setLastActiveAt(LocalDateTime.now());
                    List<Map<String,String>> history=new ArrayList<>();
                    for(int i=0;i<plan.path("turns").size();i++){
                        String input=plan.path("turns").get(i).path("input").asText();

                        var body=Map.of("userInput",input,"sessionId","sales-session","mode","READ","context",Map.of("recentTurns",history));
                        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/mobile/F006/ai-intents/execute"+(streaming?"/stream":"")))
                                .timeout(Duration.ofSeconds(100)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
                        boolean authBefore=jwt.validateToken(token);long authExpiry=jwt.getExpirationDateFromToken(token).getTime();
                        long start=System.nanoTime();Map<String,Object> row=new LinkedHashMap<>();row.put("case_id",plan.path("id").asText());row.put("channel",streaming?"sse":"normal");row.put("turn",i+1);row.put("input",input);
                        try{
                            var http=client.send(req,HttpResponse.BodyHandlers.ofString());row.put("http_status",http.statusCode());
                            com.fasterxml.jackson.databind.JsonNode result=null;
                            if(!streaming)result=JSON.readTree(http.body()).get("data");
                            else for(String event:http.body().replace("\r","").split("\n\n"))if(event.contains("event:result"))
                                for(String line:event.split("\n"))if(line.startsWith("data:"))result=JSON.readTree(line.substring(5));
                            var request=LAST.get();row.put("result",result);row.put("resolution_parameters",request.getSemanticParameters());row.put("parameters",TOOL_PARAMETERS.get());row.put("sources",request.getSemanticParameterSources());
                            row.put("resolution_sources",request.getSemanticParameterSources());
                            var completedRead=h.owner.getSlot(EntitySlot.SlotType.READ_QUERY);
                            if(result!=null&&"SUCCESS".equals(result.path("status").asText())&&completedRead!=null
                                    &&completedRead.getMetadata()!=null&&Objects.equals(request.getSemanticReadVersion(),
                                        completedRead.getMetadata().get(SalesRankingConversationSupport.TURN_KEY))
                                    &&completedRead.getMetadata().get("parameterSources") instanceof Map<?,?> finalSources)
                                row.put("sources",finalSources);
                            row.put("recognition",RECOGNITION.get());
                            row.put("executedTool",result==null?null:result.path("metadata").path("lastAction").path("toolName").asText(null));
                            row.put("effective_context",request.getSemanticContext());
                            var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));var params=TOOL_PARAMETERS.get();
                            var expected=plan.path("turns").get(i);
                            var actual=JSON.valueToTree(params);
                            boolean ok=http.statusCode()==200&&result!=null&&"SUCCESS".equals(result.path("status").asText())&&expected.path("intent").asText().equals(result.path("intentCode").asText())&&expected.path("parameters").equals(actual);
                            row.put("task_success",ok);
                        }catch(Exception failure){row.put("task_success",false);row.put("error",failure.getClass().getSimpleName());}
                        row.put("local_test_auth_valid_before",authBefore);row.put("local_test_auth_valid_after",jwt.validateToken(token));row.put("local_test_auth_expires_at",authExpiry);row.put("latency_ms",(System.nanoTime()-start)/1e6);trace.add(row);history.add(Map.of("role","user","content",input));
                        System.out.println("HTTP_TRACE "+JSON.writeValueAsString(row));
                    }
                }}
            } finally {server.stop();server.destroy();entry.sse.destroy();}
            Files.writeString(Path.of(output),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("evidence","Frozen candidate Controller/facade/normal/SSE/resolver/local Tool + real model IO and authenticated production business GET APIs; local state/auth dependencies controlled; synthetic cases NOT human gold", "trace",trace)));
        }
    }
    static long authenticatedReadActor()throws Exception{
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18766/actor")).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Actual read actor unavailable");
        var actor=JSON.readTree(response.body());
        if(!"F006".equals(actor.path("factoryId").asText())||!"factory_super_admin".equals(actor.path("role").asText())||actor.path("id").asLong()<=0)
            throw new IllegalStateException("Unexpected actual read scope");
        return actor.path("id").asLong();
    }
    static com.fasterxml.jackson.databind.JsonNode businessJson(String kind,Map<String,Object> params)throws Exception{
        String query=params.entrySet().stream().map(e->e.getKey()+"="+java.net.URLEncoder.encode(String.valueOf(e.getValue()),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18766/business/"+kind+"?"+query)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IllegalStateException("Real business read failed "+r.statusCode());
        return JSON.readTree(r.body());
    }
    static <T> PageResponse<T> businessPage(String kind,Map<String,Object> params,Class<T> type)throws Exception{
        String query=params.entrySet().stream().map(e->e.getKey()+"="+java.net.URLEncoder.encode(String.valueOf(e.getValue()),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18766/business/"+kind+"?"+query)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IllegalStateException("Real business read failed "+r.statusCode());
        var body=JSON.readTree(r.body());List<T> content=new ArrayList<>();
        for(var row:body.path("content"))content.add(JSON.treeToValue(row,type));
        return PageResponse.of(content,body.path("page").asInt(),body.path("size").asInt(),body.path("totalElements").asLong());
    }
}
