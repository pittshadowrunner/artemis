package com.artemis.wms.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tolerant coercion of loosely-typed input (JSON maps, CSV cells, YAML) into typed values. */
public final class Values {
    private Values() {}

    public static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
    public static String upper(Object o) { String s = str(o); return s == null ? null : s.toUpperCase(); }
    public static boolean blank(Object o) { return str(o) == null; }

    public static UUID uuid(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (Exception e) { throw ApiException.badRequest("'" + s + "' isn't a UUID."); }
    }

    public static Boolean bool(Object o) {
        String s = str(o);
        if (s == null) return null;
        if (o instanceof Boolean b) return b;
        return switch (s.toLowerCase()) {
            case "true", "yes", "y", "1", "t" -> true;
            case "false", "no", "n", "0", "f" -> false;
            default -> throw ApiException.badRequest("'" + s + "' isn't a yes/no value.");
        };
    }
    public static boolean is(Object o) { return Boolean.TRUE.equals(bool(o)); }

    public static Integer integer(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return new BigDecimal(s).intValueExact(); }
        catch (Exception e) { throw ApiException.badRequest("'" + s + "' isn't a whole number."); }
    }

    public static BigDecimal number(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return new BigDecimal(s); }
        catch (Exception e) { throw ApiException.badRequest("'" + s + "' isn't a number."); }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"), DateTimeFormatter.ofPattern("yyyyMMdd"));

    public static LocalDate date(Object o) {
        if (o instanceof LocalDate d) return d;
        String s = str(o);
        if (s == null) return null;
        java.util.regex.Matcher rel = java.util.regex.Pattern.compile("(?i)^(today)?\\s*([+-]\\s*\\d+)?\\s*d?$").matcher(s);
        if (rel.matches() && (rel.group(1) != null || rel.group(2) != null))      // today, today+8, +250d, -3
            return LocalDate.now().plusDays(rel.group(2) == null ? 0 : Long.parseLong(rel.group(2).replace(" ", "")));
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(s, f); } catch (Exception ignored) {}
        }
        throw ApiException.badRequest("'" + s + "' isn't a date (use YYYY-MM-DD).");
    }

    /** Lists arrive as JSON arrays or as "A, B; C" strings. */
    public static List<String> strings(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(Object::toString).map(String::trim).filter(s -> !s.isEmpty()).toList();
        String s = o.toString().trim();
        return s.isEmpty() ? List.of() : Arrays.stream(s.split("\\s*[,;|]\\s*")).filter(x -> !x.isEmpty()).toList();
    }
    public static String[] array(Object o) { List<String> l = strings(o); return l.isEmpty() ? null : l.toArray(String[]::new); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(x -> x instanceof Map).map(x -> (Map<String, Object>) x).toList();
    }

    /** JDBC array → List; Jackson would otherwise serialize the PgArray's whole connection graph. */
    public static List<String> pgList(Object o) {
        try {
            if (o instanceof java.sql.Array a) { Object arr = a.getArray(); return arr == null ? List.of() : Arrays.asList((String[]) arr); }
            if (o instanceof String[] sa) return Arrays.asList(sa);
            if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
            return List.of();
        } catch (java.sql.SQLException e) { return List.of(); }
    }
    /** Replace every java.sql.Array value in a row with a List. */
    public static Map<String, Object> deArray(Map<String, Object> row) {
        row.replaceAll((k, v) -> v instanceof java.sql.Array ? pgList(v) : v);
        return row;
    }

    public static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        // Postgres prefixes "ERROR: " and appends "Detail:/Where:" — keep the first line.
        return m.replaceFirst("^ERROR: ", "").lines().findFirst().orElse(m);
    }
}
