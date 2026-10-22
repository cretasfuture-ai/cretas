package com.cretas.aims.annotation;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个接口会读出 / 写入某个产品的完整工艺, 由 {@code ProcessSecretGuardInterceptor}
 * 在进入 controller 之前按工艺保密策略判定。产品没有保密策略时不做任何事。
 *
 * <p>{@link #from()} 说明产品 ID 从哪里解析, {@link #name()} 是对应的路径变量 / 查询参数名。
 * 解析不到 (资源不存在) 时放行, 由 controller 自己返回 404 —— 不在这里泄露「存在与否」。</p>
 *
 * <p>覆盖台账: {@code ProcessSecretRouteLedgerContractTest} 要求 BOM / 工艺控制器的每个接口
 * 要么带本注解, 要么在台账里写明为什么不带。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProcessSecretGuard {

    enum Source {
        /** 路径变量就是 productTypeId。 */
        PRODUCT_PATH,
        /** 路径变量是 BOM 配方 ID, 经配方解析产品。 */
        RECIPE_PATH,
        /** 路径变量是 BOM 配方明细 ID, 经明细 → 配方解析产品。 */
        RECIPE_ITEM_PATH,
        /** 路径变量是工艺 Workflow ID, 经 Workflow 解析产品。 */
        WORKFLOW_PATH,
        /** 查询参数就是 productTypeId。 */
        PRODUCT_PARAM,
        /** 路径变量是 BOM 版本 ID (快照), 经版本 → 配方解析产品。 */
        BOM_VERSION_PATH,
        /** 路径变量是生产批次 ID。 */
        BATCH_PATH,
        /** 路径变量是生产计划 ID (打印单据带锁定 BOM)。 */
        PLAN_PATH,
        /** 查询参数是生产计划 ID 列表 (逗号分隔或重复参数)。 */
        PLAN_PARAM_LIST,
        /** 路径变量是领料单 ID, 经领料单 → 生产计划解析产品。 */
        MATERIAL_REQUISITION_PATH
    }

    ProcessSecretAction action();

    Source from();

    String name();
}
