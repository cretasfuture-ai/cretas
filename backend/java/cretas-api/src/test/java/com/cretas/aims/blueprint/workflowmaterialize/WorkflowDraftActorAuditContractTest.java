package com.cretas.aims.blueprint.workflowmaterialize;

import com.cretas.aims.blueprint.workflowmaterialize.FactoryWorkflowDraftMaterializationModels.*;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowDraftPreviewCompiler;
import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.*;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.workflow.FactoryWorkflowDraftMaterializationLedger;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.workflow.FactoryWorkflowDraftMaterializationLedgerRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WorkflowDraftActorAuditContractTest {
    @ParameterizedTest
    @EnumSource(value=DraftMode.class,names={"MERGE_EXISTING","SNAPSHOT_AND_REPLACE"})
    void editingCarriesTheAuthoritativeIdDefinitionVersionAndLockVersion(DraftMode mode) {
        var workflows=mock(ProductProcessWorkflowRepository.class);
        var writer=mock(ProductProcessWorkflowService.class);
        var compiler=mock(FactoryWorkflowDraftPreviewCompiler.class);
        var row=new ProductProcessWorkflow();row.setId(81L);row.setFactoryId("TEST");row.setProductTypeId("bread");
        row.setStatus(ProductProcessWorkflow.Status.DRAFT);row.setDefinitionVersion(1);row.setLockVersion(1L);
        when(workflows.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(anyString(),anyString(),any())).thenReturn(Optional.of(row));
        when(workflows.lockByIdAndFactoryId(81L,"TEST")).thenReturn(Optional.of(row));
        when(writer.getEditorDefinition("TEST","bread")).thenReturn(Optional.of(definition(81L)));
        var next=definition(82L);next.setVersion(2);next.setLockVersion(0L);
        when(writer.snapshot("TEST","bread",1L)).thenReturn(next);
        var desired=definition(null);
        when(compiler.compile(any())).thenReturn(new WorkflowDraftPreview(null,null,"PREVIEW_READY","source","preview",null,"layout",desired,
                new WorkflowChangeSet("TEST","bread",DiffAction.UPDATE,1L,"revision","preview",List.of(),1,0,null,0),
                new ValidationReceipt(true,"formal",List.of(),0),List.of(),null,null,null,0));
        long expectedId=mode==DraftMode.MERGE_EXISTING?81L:82L;
        int expectedVersion=mode==DraftMode.MERGE_EXISTING?1:2;
        long expectedLock=mode==DraftMode.MERGE_EXISTING?1L:0L;
        when(writer.saveDraft(eq("TEST"),eq("bread"),any())).thenAnswer(i->{
            ProductProcessWorkflowDTO input=i.getArgument(2);
            assertThat(input.getId()).isEqualTo(expectedId);assertThat(input.getVersion()).isEqualTo(expectedVersion);
            assertThat(input.getLockVersion()).isEqualTo(expectedLock);
            var output=definition(expectedId);output.setVersion(expectedVersion);return output;
        });
        var service=new FactoryWorkflowDraftMaterializationService(workflows,mock(BomRecipeRepository.class),writer,
                mock(BomWorkflowRevisionService.class),mock(FactoryWorkflowDraftMaterializationLedgerRepository.class),new ObjectMapper().findAndRegisterModules(),compiler);
        var catalog=new ResolutionCatalog("TEST","catalog","digest",List.of(),List.of(new MaterialRef("bread","TEST","面包",MaterialKind.FINISHED_GOOD,"个",true)));
        var compile=new CompileRequest("TEST","source","catalog","digest","unit","bread",null,catalog,List.of(),List.of(),null);
        var preview=service.preview(new DraftMaterializationRequest("edit","","",mode,1L,"revision",7L,AuthenticatedActorType.PLATFORM_ADMIN,compile,List.of()));
        var confirmed=new DraftMaterializationRequest("edit",preview.previewDigest(),preview.operationDigest(),mode,1L,"revision",7L,AuthenticatedActorType.PLATFORM_ADMIN,compile,List.of());
        assertThat(assertDoesNotThrow(()->service.materialize(confirmed)).status()).isEqualTo("DRAFTED");
    }

    @ParameterizedTest
    @EnumSource(value=AuthenticatedActorType.class, names={"PLATFORM_ADMIN", "FACTORY_USER"})
    void persistedLedgerKeepsNamespaceAndOnlyFactoryUsersEnterLegacyColumn(AuthenticatedActorType type) {
        var workflows=mock(ProductProcessWorkflowRepository.class);
        var writer=mock(ProductProcessWorkflowService.class);
        var ledger=mock(FactoryWorkflowDraftMaterializationLedgerRepository.class);
        var compiler=mock(FactoryWorkflowDraftPreviewCompiler.class);
        var saved=new AtomicReference<FactoryWorkflowDraftMaterializationLedger>();
        when(ledger.findByFactoryIdAndIdempotencyKey(anyString(),anyString())).thenAnswer(i->Optional.ofNullable(saved.get()));
        when(ledger.saveAndFlush(any())).thenAnswer(i->{saved.set(i.getArgument(0));return saved.get();});
        var desired=definition(null);
        when(compiler.compile(any())).thenReturn(new WorkflowDraftPreview(null,null,"PREVIEW_READY","source","preview",null,"layout",desired,
                new WorkflowChangeSet("TEST","bread",DiffAction.CREATE,null,"","preview",List.of(),1,0,null,0),
                new ValidationReceipt(true,"formal",List.of(),0),List.of(),null,null,null,0));
        when(writer.saveDraft(eq("TEST"),eq("bread"),any())).thenReturn(definition(81L));
        var service=new FactoryWorkflowDraftMaterializationService(workflows,mock(BomRecipeRepository.class),writer,
                mock(BomWorkflowRevisionService.class),ledger,new ObjectMapper().findAndRegisterModules(),compiler);
        var catalog=new ResolutionCatalog("TEST","catalog","digest",List.of(),
                List.of(new MaterialRef("bread","TEST","面包",MaterialKind.FINISHED_GOOD,"个",true)));
        var compile=new CompileRequest("TEST","source","catalog","digest","unit","bread",null,catalog,List.of(),List.of(),null);
        var preview=service.preview(new DraftMaterializationRequest("key","","",DraftMode.CREATE_NEW_ONLY,null,"",7L,type,compile,List.of()));
        var confirmed=new DraftMaterializationRequest("key",preview.previewDigest(),preview.operationDigest(),DraftMode.CREATE_NEW_ONLY,null,"",7L,type,compile,List.of());
        var receipt=service.materialize(confirmed);
        assertThat(receipt.status()).isEqualTo("DRAFTED");
        assertThat(saved.get().getActorType()).isEqualTo(type);
        assertThat(saved.get().getActorId()).isEqualTo(7L);
        assertThat(saved.get().getOperatorId()).isEqualTo(type==AuthenticatedActorType.FACTORY_USER ? 7L : null);
        assertThat(service.materialize(confirmed).thisInvocationBusinessWriteCount()).isZero();
        verify(writer).saveDraft(eq("TEST"),eq("bread"),any());
    }

    private ProductProcessWorkflowDTO definition(Long id) {
        var d=new ProductProcessWorkflowDTO(); d.setId(id);d.setFactoryId("TEST");d.setProductTypeId("bread");
        d.setSchemaVersion(1);d.setStatus(id==null?"DRAFT_PREVIEW":"DRAFT");d.setVersion(1);d.setLockVersion(1L);
        d.setRevisionId(id==null?null:901L);d.setRevisionHash(id==null?null:"revision");
        d.setNodes(new ArrayList<>(List.of(new ProductProcessWorkflowDTO.Node("finished","FINISHED_GOOD",
                new ProductProcessWorkflowDTO.Position(1D,2D),new LinkedHashMap<>(Map.of("skuId","bread"))))));
        d.setEdges(new ArrayList<>());d.setViewport(new ProductProcessWorkflowDTO.Viewport(0D,0D,1D)); return d;
    }
}
