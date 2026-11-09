package com.artemis.wms.identity;

/** Capability constants — mirror the role_capability rows seeded in V1. */
public final class Capabilities {
    private Capabilities() {}
    public static final String ORG_MANAGE        = "ORG_MANAGE";
    public static final String USER_MANAGE       = "USER_MANAGE";
    public static final String ITEM_MANAGE       = "ITEM_MANAGE";
    public static final String LOCATION_MANAGE   = "LOCATION_MANAGE";
    public static final String CUSTOMER_MANAGE   = "CUSTOMER_MANAGE";
    public static final String EQUIPMENT_MANAGE  = "EQUIPMENT_MANAGE";
    public static final String RECEIVING_EXECUTE = "RECEIVING_EXECUTE";
    public static final String PUTAWAY_EXECUTE   = "PUTAWAY_EXECUTE";
    public static final String REPLEN_EXECUTE    = "REPLEN_EXECUTE";
    public static final String SELECTION_EXECUTE = "SELECTION_EXECUTE";
    public static final String SHIPPING_EXECUTE  = "SHIPPING_EXECUTE";
    public static final String WAVE_PLAN         = "WAVE_PLAN";
    public static final String INVENTORY_ADJUST  = "INVENTORY_ADJUST";
    public static final String DASHBOARD_VIEW    = "DASHBOARD_VIEW";
    public static final String METRICS_VIEW_ALL  = "METRICS_VIEW_ALL";
    public static final String METRICS_VIEW_SELF = "METRICS_VIEW_SELF";
    public static final String DATA_IMPORT       = "DATA_IMPORT";
}
