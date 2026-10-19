package com.cretas.aims.repository.inventory;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShipmentCostRowsTest {
    static Object[] row(String nativeQty, String cost, String batch) {
        return new Object[]{LocalDate.of(2026,10,17), new BigDecimal(nativeQty), cost==null?null:new BigDecimal(cost),
                "sku",batch,"盒装","OUT-1",1L,new BigDecimal("10"),"盒","盒",new BigDecimal("500"),
                null,null,null,null,null,null,"edge-"+batch,2L,"盒"};
    }
    @Test void bothBatchesAndOnlyTheChangedBatchContributeWithoutPrematureRounding() {
        var a=row("4","6.4165","a");var b=row("6","6.9165","b");
        var before=ShipmentCostRows.movements(List.of(a,b));
        assertEquals(new BigDecimal("67.1650"),ShipmentCostRows.aggregate(before,false)[0]);
        b[2]=new BigDecimal("7.4165");
        assertEquals(new BigDecimal("70.1650"),ShipmentCostRows.aggregate(ShipmentCostRows.movements(List.of(a,b)),false)[0]);
        assertEquals(2,before.size());
        assertEquals(1L,ShipmentCostRows.aggregate(before,false)[2]);
    }
    @Test void nativeKgQuantitiesAreNotMultipliedAsBoxes() {
        var a=row("2","12.8330","a");a[10]="kg";a[20]="kg";
        var b=row("6","6.9165","b");
        assertEquals(new BigDecimal("67.1650"),ShipmentCostRows.aggregate(ShipmentCostRows.movements(List.of(a,b)),false)[0]);
    }

    @Test void nativeGramQuantityReconcilesToBoxUsingTheMassUnit() {
        var a=row("500","0.012833","a");
        a[9]="box";
        a[10]="g";
        a[11]=new BigDecimal("500");
        a[20]="g";
        a[8]=new BigDecimal("1");
        var rows=ShipmentCostRows.movements(java.util.Collections.singletonList(a));
        assertEquals(0, new BigDecimal("6.4165").compareTo((BigDecimal) ShipmentCostRows.aggregate(rows,false)[0]));
        assertEquals(new BigDecimal("500"), rows.get(0)[1]);
        a[20]="box";
        assertThrows(RuntimeException.class, () -> ShipmentCostRows.movements(java.util.Collections.singletonList(a)));
    }
    @Test void incompleteOrDuplicateActualSourcesCannotCloseAtTheFirstBatchCost() {
        assertThrows(RuntimeException.class,()->ShipmentCostRows.movements(java.util.Collections.singletonList(row("4","6.4165","a"))));
        var a=row("10","6.4165","a");a[18]=null;
        assertThrows(RuntimeException.class,()->ShipmentCostRows.movements(java.util.Collections.singletonList(a)));
        assertThrows(RuntimeException.class,()->ShipmentCostRows.movements(List.of(row("10","6.4165","a"),row("10","6.4165","a"))));
    }
    @Test void unknownCostRemainsUnknownAndIsCountedSeparately() {
        var rows=ShipmentCostRows.movements(List.of(row("4","6.4165","a"),row("6",null,"b")));
        assertNull(rows.get(1)[2]);assertEquals(1L,ShipmentCostRows.aggregate(rows,true)[1]);
        assertEquals(new BigDecimal("6"),ShipmentCostRows.aggregate(rows,true)[0]);
    }
}
