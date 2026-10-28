package com.cretas.aims.service.factorydata;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.time.Clock;
import java.util.List;
import java.util.Set;

/** 给别的包的真实 PG 测试用: 用测试 schema 构造整厂导出服务 (包内构造器)。 */
public final class FactoryDataServiceFixture {

    private FactoryDataServiceFixture() {}

    public static FactoryDataService create(DataSource main, DataSource smartbi, Set<Long> operators, String schema) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("smartbiPostgresDataSource", smartbi);
        ObjectProvider<DataSource> provider = beans.getBeanProvider(DataSource.class);
        return new FactoryDataService(main, provider, new NoAttachments(),
                new FactoryDataRequestLedger(main, Clock.systemDefaultZone()), operators, Clock.systemDefaultZone(),
                schema);
    }

    public static String mainSchema(String schema) {
        return FactoryDataScopePostgresContractTest.mainSchema(schema);
    }

    public static String smartbiSchema(String schema) {
        return FactoryDataScopePostgresContractTest.smartbiSchema(schema);
    }

    /** 没有附件的对象存储。 */
    static final class NoAttachments implements FactoryObjectStore {
        @Override public List<StoredObject> list(String prefix) { return List.of(); }
        @Override public void copyTo(StoredObject object, OutputStream out) { throw new IllegalStateException("no objects"); }
        @Override public void delete(List<StoredObject> objects) { if (!objects.isEmpty()) throw new IllegalStateException(); }
    }
}
