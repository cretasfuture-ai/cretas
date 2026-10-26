package com.cretas.aims.service.factorydata;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 工厂附件所在的对象存储 (生产是阿里云 OSS: 对象键以 {@code <工厂编号>/} 开头)。 */
public interface FactoryObjectStore {

    record StoredObject(String bucket, String key, long size, String etag) {}

    /** 按前缀列出全部对象 (含分页)。 */
    List<StoredObject> list(String prefix);

    /** 把对象内容原样写进 out。 */
    void copyTo(StoredObject object, OutputStream out) throws IOException;

    /** 删除这些对象 (整厂删除); 任何一个删不掉都抛错。 */
    void delete(List<StoredObject> objects);
}
