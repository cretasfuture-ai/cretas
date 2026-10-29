package com.cretas.aims.entity.processsecret;

/** 工艺保密判定与审计的动作。查看 / 导出 / 编辑分别授权, 互不蕴含。 */
public enum ProcessSecretAction {
    VIEW_FULL,
    EXPORT,
    EDIT,
    MANAGE_POLICY,
    MANAGE_GRANT,
    READ_AUDIT,
    REGISTER_PACKAGE,
    RETIRE_PACKAGE,
    SUBMIT_RESULT,
    /** AI 请求的访问记录 (每个请求一条): 调用者、档位、数据是否牵涉保密产品、入口。不是授权判定。 */
    AI_ACCESS
}
