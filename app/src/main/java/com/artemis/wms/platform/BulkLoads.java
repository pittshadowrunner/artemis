package com.artemis.wms.platform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which tenants are in the middle of a bulk load. Unattended jobs (the
 * replenishment scan) stay out of a building while it is being stocked —
 * half-loaded racks look exactly like ten thousand empty pick faces — and
 * for a short settling period afterwards, so a load split across several
 * files is treated as one.
 */
@Component
public class BulkLoads {
    private static final long SETTLE_MS = 120_000;
    private final Map<UUID, Integer> running = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEnded = new ConcurrentHashMap<>();

    public <T> T during(UUID corp, Supplier<T> work) {
        if (corp == null) return work.get();
        running.merge(corp, 1, Integer::sum);
        try { return work.get(); }
        finally {
            running.computeIfPresent(corp, (k, n) -> n <= 1 ? null : n - 1);
            lastEnded.put(corp, System.currentTimeMillis());
        }
    }

    /** True while a load is running for this tenant, or one finished moments ago. */
    public boolean busy(UUID corp) {
        if (corp == null) return false;
        if (running.containsKey(corp)) return true;
        Long ended = lastEnded.get(corp);
        return ended != null && System.currentTimeMillis() - ended < SETTLE_MS;
    }
}
