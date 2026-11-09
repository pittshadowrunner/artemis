package com.artemis.wms.tenancy;

import java.util.UUID;

/** Request-scoped identity: who is calling, which tenant they belong to, and whether they're platform tier. */
public final class TenantContext {
    public record Principal(UUID userId, UUID corporationId, boolean sysadmin, String email) {}

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private TenantContext() {}

    public static void set(Principal p) { CURRENT.set(p); }
    public static void clear() { CURRENT.remove(); }
    public static Principal current() { return CURRENT.get(); }
    public static UUID user() { Principal p = CURRENT.get(); return p == null ? null : p.userId(); }
    public static UUID corp() { Principal p = CURRENT.get(); return p == null ? null : p.corporationId(); }
    public static boolean sysadmin() { Principal p = CURRENT.get(); return p != null && p.sysadmin(); }

    /** Sysadmins operate inside a tenant by choosing one; this pins the corp for the rest of the request. */
    public static void enterTenant(UUID corporationId) {
        Principal p = CURRENT.get();
        CURRENT.set(new Principal(p == null ? null : p.userId(), corporationId, p != null && p.sysadmin(), p == null ? null : p.email()));
    }
}
