package com.cretas.aims.entity.processsecret;

/**
 * 工艺保密两档。
 *
 * <ul>
 *   <li>{@link #CLOUD_RESTRICTED} — A 档「工艺保密访问」: 完整工艺仍存云端, 只有负责人和获授权者能看/导出/改。</li>
 *   <li>{@link #CUSTOMER_HELD} — B 档「核心工艺不入云」: 云端对任何人都不返回、不接收完整工艺,
 *       只登记客户侧工艺包引用和经签名的批次结果。</li>
 * </ul>
 */
public enum ProcessSecretStorageMode {
    CLOUD_RESTRICTED,
    CUSTOMER_HELD
}
