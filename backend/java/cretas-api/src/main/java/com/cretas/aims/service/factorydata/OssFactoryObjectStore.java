package com.cretas.aims.service.factorydata;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.cretas.aims.config.OssConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体桶与音频桶两个都列 —— 上传代码按类别分别写进这两个桶 ({@code OssServiceImpl})。
 * OSS 未启用 ({@code aliyun.oss.enabled=false}) 时调用即报错, ⛔ 不当作「没有附件」。
 */
@Component
public class OssFactoryObjectStore implements FactoryObjectStore {

    private final ObjectProvider<OSS> oss;
    private final OssConfig config;

    public OssFactoryObjectStore(ObjectProvider<OSS> oss, OssConfig config) {
        this.oss = oss;
        this.config = config;
    }

    private OSS client() {
        OSS client = oss.getIfAvailable();
        if (client == null) throw new IllegalStateException("OSS 未启用 (aliyun.oss.enabled=false), 无法列出附件");
        return client;
    }

    @Override
    public List<StoredObject> list(String prefix) {
        OSS client = client();
        List<StoredObject> out = new ArrayList<>();
        for (String bucket : List.of(config.getMediaBucket(), config.getAudioBucket())) {
            String token = null;
            do {
                ListObjectsV2Request req = new ListObjectsV2Request(bucket).withPrefix(prefix).withMaxKeys(1000);
                if (token != null) req.setContinuationToken(token);
                ListObjectsV2Result page = client.listObjectsV2(req);
                for (OSSObjectSummary s : page.getObjectSummaries()) {
                    out.add(new StoredObject(bucket, s.getKey(), s.getSize(), s.getETag()));
                }
                token = page.isTruncated() ? page.getNextContinuationToken() : null;
            } while (token != null);
        }
        return out;
    }

    @Override
    public void copyTo(StoredObject object, OutputStream out) throws IOException {
        try (OSSObject o = client().getObject(object.bucket(), object.key()); InputStream in = o.getObjectContent()) {
            in.transferTo(out);
        }
    }
}
