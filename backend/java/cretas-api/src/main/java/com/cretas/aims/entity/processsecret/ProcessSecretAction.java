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
    SUBMIT_RESULT
}
