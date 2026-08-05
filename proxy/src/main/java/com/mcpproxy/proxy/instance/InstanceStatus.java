package com.mcpproxy.proxy.instance;

/**
 * 实例状态机常量（存库值，与华为 ShowProgress 状态码区分开，响应时再映射）。
 *
 * <p>流转见 design.md §7：CREATED -> PREPARING -> NORMAL/FAILED -> DELETED。
 *
 * @author hubin
 * @since 2026-08-04
 */
public final class InstanceStatus {

    /** 已订阅，未准备 */
    public static final int CREATED = 0;
    /** 准备中（排队，waitingCount 递减） */
    public static final int PREPARING = 1;
    /** 就绪（healthz 判活通过，可代理 MCP） */
    public static final int NORMAL = 2;
    /** 准备失败（就绪判活未通过） */
    public static final int FAILED = 3;
    /** 已退订（逻辑删除） */
    public static final int DELETED = 4;

    private InstanceStatus() {
    }
}
