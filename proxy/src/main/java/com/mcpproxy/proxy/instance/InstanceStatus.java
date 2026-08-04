package com.mcpproxy.proxy.instance;

public final class InstanceStatus {

    public static final int CREATED = 0;
    public static final int PREPARING = 1;
    public static final int NORMAL = 2;
    public static final int FAILED = 3;
    public static final int DELETED = 4;

    private InstanceStatus() {
    }
}
