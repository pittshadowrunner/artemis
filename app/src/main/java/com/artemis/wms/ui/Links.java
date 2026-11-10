package com.artemis.wms.ui;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where each kind of thing lives. Templates never build a URL by hand: they
 * ask for go.slot(code), go.pallet(lpn), go.order(number)… so a name on any
 * screen links to the same page, and a page can move without a hunt through
 * the templates. Everything is keyed by what is printed on the label.
 */
public final class Links {
    private static String seg(Object v) { return URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8).replace("+", "%20"); }

    public String slot(Object code)        { return "/slots/" + seg(code); }
    public String pallet(Object lpn)       { return "/pallets/" + seg(lpn); }
    public String item(Object sku)         { return "/items/" + seg(sku); }
    public String lot(Object sku, Object lot) { return "/lots/" + seg(sku) + "?lot=" + seg(lot); }
    public String order(Object key)        { return "/orders/" + seg(key); }
    public String wave(Object key)         { return "/waves/" + seg(key); }
    public String assignment(Object key)   { return "/assignments/" + seg(key); }
    public String manifest(Object key)     { return "/receiving/" + seg(key); }
    public String shipment(Object key)     { return "/shipments/" + seg(key); }
    public String customer(Object code)    { return "/customers/" + seg(code); }
    public String operator(Object userId)  { return "/operators/" + seg(userId); }
    public String equipment(Object code)   { return "/equipment/" + seg(code); }
    public String search(Object q)         { return "/search?q=" + seg(q); }

    // ---- temperature-zone presentation: one place decides the tag and the colour
    public String tag(Object tempZone) {
        return switch (String.valueOf(tempZone)) {
            case "FROZEN" -> "FRZ"; case "DEEP_FROZEN" -> "DFZ"; case "REFRIGERATED" -> "CHL";
            case "AMBIENT" -> "DRY"; case "CONTROLLED_AMBIENT" -> "CTL"; case "HEATED" -> "HOT";
            default -> "—";
        };
    }
    public String css(Object tempZone) {
        return switch (String.valueOf(tempZone)) {
            case "FROZEN", "DEEP_FROZEN" -> "frozen"; case "REFRIGERATED" -> "chill"; case "HEATED" -> "hot";
            default -> "";
        };
    }
    /** Short tag for a non-rack location, like the placard on the post. */
    public String locTag(Object locType) {
        return switch (String.valueOf(locType)) {
            case "RECEIVING_DOCK" -> "DCK"; case "SHIPPING_DOCK" -> "SHP"; case "CROSS_DOCK" -> "XDK";
            case "DROP" -> "DRP"; case "STAGING" -> "STG"; case "STANDARD" -> "RCK";
            default -> "LOC";
        };
    }
}
