package com.cretas.aims.service.tenantbackup;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.DeleteObjectsResult;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.cretas.aims.config.OssConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份桶 (默认 {@code cretas-db-backups}, 与整库灾备同桶、不同前缀)。用应用自己的 AccessKey,
 * 走同区内网 endpoint (上传免费)。2026-10-27 实测应用 AccessKey 对该桶 list / put / delete 均可用。
 * OSS 未启用 ({@code aliyun.oss.enabled=false}) 时调用即报错, ⛔ 不当作「备份成功」或「没有备份」。
 */
@Component
public class OssTenantBackupStore implements TenantBackupStore {

    private final OssConfig config;
    private final boolean enabled;
    private final String endpoint;
    private final String bucket;
    private volatile OSS client;

    public OssTenantBackupStore(OssConfig config,
                                @Value("${aliyun.oss.enabled:false}") boolean enabled,
                                @Value("${cretas.tenant-backup.oss-endpoint:oss-cn-shanghai-internal.aliyuncs.com}") String endpoint,
                                @Value("${cretas.tenant-backup.bucket:cretas-db-backups}") String bucket) {
        this.config = config;
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.bucket = bucket;
    }

    private OSS client() {
        if (!enabled) throw new IllegalStateException("OSS 未启用 (aliyun.oss.enabled=false), 无法读写工厂备份");
        OSS c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = new OSSClientBuilder().build("https://" + endpoint, config.getAccessKeyId(),
                            config.getAccessKeySecret());
                }
                c = client;
            }
        }
        return c;
    }

    @PreDestroy
    void close() {
        if (client != null) client.shutdown();
    }

    @Override
    public long put(Path file, String key) {
        client().putObject(bucket, key, file.toFile());
        ObjectMetadata meta = client().getObjectMetadata(bucket, key);
        return meta.getContentLength();
    }

    @Override
    public List<StoredBackup> list(String prefix) {
        List<StoredBackup> out = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request req = new ListObjectsV2Request(bucket).withPrefix(prefix).withMaxKeys(1000);
            if (token != null) req.setContinuationToken(token);
            ListObjectsV2Result page = client().listObjectsV2(req);
            for (OSSObjectSummary s : page.getObjectSummaries()) out.add(new StoredBackup(s.getKey(), s.getSize()));
            token = page.isTruncated() ? page.getNextContinuationToken() : null;
        } while (token != null);
        return out;
    }

    @Override
    public List<String> listChildren(String prefix) {
        List<String> out = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request req = new ListObjectsV2Request(bucket).withPrefix(prefix).withDelimiter("/")
                    .withMaxKeys(1000);
            if (token != null) req.setContinuationToken(token);
            ListObjectsV2Result page = client().listObjectsV2(req);
            for (String p : page.getCommonPrefixes()) {
                String child = p.substring(prefix.length());
                if (child.endsWith("/")) child = child.substring(0, child.length() - 1);
                if (!child.isEmpty()) out.add(child);
            }
            token = page.isTruncated() ? page.getNextContinuationToken() : null;
        } while (token != null);
        return out;
    }

    @Override
    public void delete(List<String> keys) {
        for (int i = 0; i < keys.size(); i += 1000) {
            List<String> batch = keys.subList(i, Math.min(keys.size(), i + 1000));
            DeleteObjectsResult r = client().deleteObjects(
                    new DeleteObjectsRequest(bucket).withKeys(new ArrayList<>(batch)).withQuiet(false));
            if (r.getDeletedObjects().size() != batch.size()) {
                throw new IllegalStateException("OSS 删除不完整: 请求 " + batch.size() + " 个, 回执 "
                        + r.getDeletedObjects().size() + " 个");
            }
        }
    }

    @Override
    public void copyTo(String key, OutputStream out) throws IOException {
        try (OSSObject o = client().getObject(bucket, key); InputStream in = o.getObjectContent()) {
            in.transferTo(out);
        }
    }
}
