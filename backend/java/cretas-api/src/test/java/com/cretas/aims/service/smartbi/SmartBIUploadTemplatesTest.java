package com.cretas.aims.service.smartbi;

import com.cretas.aims.exception.BusinessException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartBIUploadTemplatesTest {

    @Test
    void everySupportedTypeProducesHeaderOnlyWorkbook() throws Exception {
        assertThat(SmartBIUploadTemplates.supportedTypes())
                .containsExactly("sales", "finance", "inventory", "customer", "product");
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(SmartBIUploadTemplates.build("sales")))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("销售数据");
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("客户名称");
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 5);
        }
    }

    @Test
    void unknownOrMissingTypeIsRejectedNotDefaulted() {
        assertThatThrownBy(() -> SmartBIUploadTemplates.build("bogus")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SmartBIUploadTemplates.build(null)).isInstanceOf(BusinessException.class);
    }
}
