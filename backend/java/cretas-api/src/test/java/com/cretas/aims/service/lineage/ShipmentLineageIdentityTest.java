package com.cretas.aims.service.lineage;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ShipmentLineageIdentityTest {
    BatchLineageEdge edge(long item,String origin,String key){var e=new BatchLineageEdge();e.setFactoryId("F");e.setEdgeType("FINISHED_TO_SHIPMENT");e.setSourceType("FINISHED_BATCH");e.setSourceId("b");e.setTargetType("SHIPMENT_RECORD");e.setTargetId("d");e.setMeta(Map.of("origin",origin,"refKey",key,"shipmentCarrier","SALES_DELIVERY_RECORD","deliveryItemId",item));return e;}
    @Test void backfillAfterLiveShipmentAndOldKeysCannotDuplicateTheSamePhysicalLine(){
        var repo=mock(BatchLineageEdgeRepository.class);var live=edge(7,"SALES_DELIVERY_SHIPMENT","delivery:d:b");
        when(repo.findMatchingEdges(any(),any(),any(),any(),any(),any())).thenReturn(List.of(live));
        var writer=new LineageEdgeWriter(repo);var incoming=edge(7,"BACKFILL","backfill:delivery:d:7:b");
        assertEquals(LineageRecordOutcome.ALREADY_EXISTS,writer.previewInNewTransaction(incoming,"backfill:delivery:d:7:b"));
        assertEquals(LineageRecordOutcome.ALREADY_EXISTS,writer.writeInNewTransaction(incoming,"backfill:delivery:d:7:b"));verify(repo,never()).saveAndFlush(any());
    }
    @Test void twoLinesUsingOneBatchRemainTwoIndependentEvents(){
        var repo=mock(BatchLineageEdgeRepository.class);when(repo.findMatchingEdges(any(),any(),any(),any(),any(),any())).thenReturn(List.of(edge(7,"SALES_DELIVERY_SHIPMENT","delivery:d:7:b")));
        var incoming=edge(8,"SALES_DELIVERY_SHIPMENT","delivery:d:8:b");assertEquals(LineageRecordOutcome.INSERTED,new LineageEdgeWriter(repo).writeInNewTransaction(incoming,"delivery:d:8:b"));verify(repo).saveAndFlush(incoming);
    }
    @Test void syntheticBackfillCannotSuppressANewActualDeduction(){
        var repo=mock(BatchLineageEdgeRepository.class);when(repo.findMatchingEdges(any(),any(),any(),any(),any(),any())).thenReturn(List.of(edge(7,"BACKFILL","backfill:delivery:d:7:b")));
        var incoming=edge(7,"SALES_DELIVERY_SHIPMENT","delivery:d:7:b");assertEquals(LineageRecordOutcome.INSERTED,new LineageEdgeWriter(repo).writeInNewTransaction(incoming,"delivery:d:7:b"));verify(repo).saveAndFlush(incoming);
    }
}
