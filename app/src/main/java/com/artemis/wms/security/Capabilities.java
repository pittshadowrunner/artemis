package com.artemis.wms.security;

/** Capability constants — mirrors the role_capability rows seeded in migration V4. */
public final class Capabilities {
    private Capabilities() {}

    public static final String ORG_MANAGE        = "ORG_MANAGE";
    public static final String USER_MANAGE       = "USER_MANAGE";
    public static final String ITEM_MANAGE       = "ITEM_MANAGE";
    public static final String LOCATION_MANAGE   = "LOCATION_MANAGE";
    public static final String CUSTOMER_MANAGE   = "CUSTOMER_MANAGE";

    public static final String RECEIVING_EXECUTE = "RECEIVING_EXECUTE";
    public static final String PUTAWAY_EXECUTE   = "PUTAWAY_EXECUTE";
    public static final String REPLEN_EXECUTE    = "REPLEN_EXECUTE";
    public static final String SELECTION_EXECUTE = "SELECTION_EXECUTE";
    public static final String SHIPPING_EXECUTE  = "SHIPPING_EXECUTE";

    public static final String WAVE_PLAN         = "WAVE_PLAN";
    public static final String INVENTORY_ADJUST  = "INVENTORY_ADJUST";

    /** Admin-only: access to the dashboards section. */
    public static final String DASHBOARD_VIEW    = "DASHBOARD_VIEW";
    /** Admin-only: metrics across all users. */
    public static final String METRICS_VIEW_ALL  = "METRICS_VIEW_ALL";
    /** Any authenticated user: their own productivity numbers. */
    public static final String METRICS_VIEW_SELF = "METRICS_VIEW_SELF";
}
