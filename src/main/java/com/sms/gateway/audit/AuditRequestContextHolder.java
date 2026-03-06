package com.sms.gateway.audit;

public final class AuditRequestContextHolder {

    private static final ThreadLocal<AuditRequestContext> CONTEXT = new ThreadLocal<>();

    private AuditRequestContextHolder() {
    }

    public static void set(AuditRequestContext context) {
        CONTEXT.set(context);
    }

    public static AuditRequestContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
