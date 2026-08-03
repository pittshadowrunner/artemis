package com.artemis.wms.security;

import java.util.UUID;

/** ThreadLocal tenant + user identity for the current request. */
public final class TenantContext {
    private static final ThreadLocal<UUID> CORP = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID corporationId, UUID userId) { CORP.set(corporationId); USER.set(userId); }
    public static UUID corp() { return CORP.get(); }
    public static UUID user() { return USER.get(); }
    public static void clear() { CORP.remove(); USER.remove(); }
}
