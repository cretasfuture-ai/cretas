package com.cretas.aims.dto.material;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 物料别名与重复物料合并的请求 / 响应。 */
public final class MaterialMergeDTO {

    private MaterialMergeDTO() {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AliasRequest {
        @NotBlank(message = "请填写别名")
        @Size(max = 100, message = "别名不能超过 100 个字")
        private String alias;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AliasItem {
        private String id;
        private String alias;
        /** MANUAL = 手工添加; MERGE = 合并重复物料时由原物料名生成。 */
        private String source;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeRequest {
        @NotBlank(message = "请选择要保留的主物料")
        private String masterId;
        @Size(max = 500, message = "合并原因不能超过 500 个字")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialBrief {
        private String id;
        private String code;
        private String name;
        private String unit;
        private String mainCategory;
    }

    /** 一类挡住合并的引用, 例如「还在 2 张配方里用着」。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Blocker {
        /** STOCK / BOM / WORKFLOW / SUPPLIER / PURCHASE_ORDER / CATEGORY */
        private String code;
        /** 给人看的一句话, 例如「还有库存 12 kg（3 个批次）」。 */
        private String message;
        /** 具体位置 (配方的产品名 / 采购单号 / 供应商名), 最多列 10 条。 */
        private List<String> items;
        /** 去哪里处理。 */
        private String hint;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeCheck {
        private boolean canMerge;
        private MaterialBrief duplicate;
        private MaterialBrief master;
        private List<Blocker> blockers;
        /** 不挡合并但要让人看见的差异, 例如单位不同。 */
        private List<String> warnings;
        /** 合并后会自动加到主物料上的别名 (被合并物料的名字和它原有的别名)。 */
        private List<String> aliasesToAdd;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MergeResult {
        private String duplicateId;
        private String masterId;
        private List<String> aliasesAdded;
    }
}
