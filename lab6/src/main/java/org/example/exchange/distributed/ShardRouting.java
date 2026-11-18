package org.example.exchange.distributed;

import java.util.Locale;
import java.util.Objects;

public final class ShardRouting {
    private ShardRouting() {
    }

    public static int index(String base, String quote, int count) {
        if (count < 1) throw new IllegalArgumentException("Shard count must be positive");
        String key = Objects.requireNonNull(base).toUpperCase(Locale.ROOT)
                + ":" + Objects.requireNonNull(quote).toUpperCase(Locale.ROOT);
        return Math.floorMod(key.hashCode(), count);
    }

    public static long publicId(long localId, int shard, int count) {
        if (localId <= 0 || count <= 0 || shard < 0 || shard >= count) {
            throw new IllegalArgumentException("Invalid shard ID");
        }
        return Math.addExact(Math.multiplyExact(localId - 1, count), shard + 1);
    }
}
