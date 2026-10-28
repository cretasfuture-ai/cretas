package com.cretas.aims.service.tenantbackup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/** 备份件所在的对象存储 (生产是阿里云 OSS 备份桶, 对象键以 {@code tenants/<工厂编号>/} 开头)。 */
public interface TenantBackupStore {

    record StoredBackup(String key, long size) {}

    /** 上传文件; 返回对象存储侧回读到的大小 (调用方拿它和本地大小比对, ⛔ 不以「没报错」为准)。 */
    long put(Path file, String key) throws IOException;

    /** 按前缀列出全部对象 (含分页)。 */
    List<StoredBackup> list(String prefix);

    /** {@code prefix} 下一级的「目录」名 (不带前缀和结尾斜杠)。 */
    List<String> listChildren(String prefix);

    void delete(List<String> keys);

    void copyTo(String key, OutputStream out) throws IOException;
}
