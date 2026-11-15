package com.cretas.aims.entity.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 外部系统只读快照 (V20261115_51)。一行 = 工厂 × 系统 × 数据集 × 一次采集时点; 只追加。
 * {@code collectedAt} / {@code receivedAt} 存 UTC (推送端给的是带时区的时刻, 服务端统一换成 UTC 落库),
 * 页面展示一律换成北京时间。
 */
@Entity
@Table(name = "external_source_snapshots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalSourceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "external_system", nullable = false, length = 30)
    private String system;

    @Column(name = "dataset", nullable = false, length = 40)
    private String dataset;

    /** 采集时点 (UTC)。 */
    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    /** 47 收到的时点 (UTC)。 */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @Column(name = "collector_run_id", length = 80)
    private String collectorRunId;
}
