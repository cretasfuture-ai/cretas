package com.cretas.aims.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 单字方言键不得替换掉业务复合词里的字（intent-routing eval 基线 L004/M003/M013/S005 的成因）。
 * 每条断言都带一个阴性对照：标准写法应得到什么、旧写法会得到什么。
 */
class DialectNormalizationCompoundGuardTest {

    @Test
    @DisplayName("出货/发货/退货 里的「货」保留，独立的「货」仍标准化为「物料」")
    void compoundsKeepTheirCharacter() {
        assertEquals("昨天的出货明细", DialectNormalizationServiceImpl.replaceOutsideCompounds("昨天的出货明细", "货", "物料"));
        assertEquals("我要创建一张发货单", DialectNormalizationServiceImpl.replaceOutsideCompounds("我要创建一张发货单", "货", "物料"));
        assertEquals("这批原料要退货", DialectNormalizationServiceImpl.replaceOutsideCompounds("这批原料要退货", "货", "物料"));
        // 独立的「货」照旧替换
        assertEquals("这批物料能不能用", DialectNormalizationServiceImpl.replaceOutsideCompounds("这批货能不能用", "货", "物料"));
        assertEquals("今天到了啥物料", DialectNormalizationServiceImpl.replaceOutsideCompounds("今天到了啥货", "货", "物料"));
    }

    @Test
    @DisplayName("阴性对照：不受保护的字仍按旧行为整体替换")
    void unprotectedKeysBehaveAsBefore() {
        assertEquals("查询库存", DialectNormalizationServiceImpl.replaceOutsideCompounds("查查库存", "查查", "查询"));
        assertEquals("一个任务", DialectNormalizationServiceImpl.replaceOutsideCompounds("一个活儿", "活儿", "任务"));
    }

    @Test
    @DisplayName("同一句里既有复合词又有独立字：只换独立的那个")
    void mixedOccurrences() {
        assertEquals("出货后剩下的物料", DialectNormalizationServiceImpl.replaceOutsideCompounds("出货后剩下的货", "货", "物料"));
    }
}
