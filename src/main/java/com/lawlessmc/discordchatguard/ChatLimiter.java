package com.lawlessmc.discordchatguard;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sliding windows. No player-facing side effects. */
final class ChatLimiter {

    private final Map<UUID, ArrayDeque<Long>> playerHits = new ConcurrentHashMap<>();
    private final Map<UUID, Repeat> lastRepeat = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> globalHits = new ArrayDeque<>();

    private final long playerWindowNanos;
    private final int playerMax;
    private final long repeatWindowNanos;
    private final long globalWindowNanos;
    private final int globalMax;

    ChatLimiter(long playerWindowSeconds, int playerMax, long repeatWindowSeconds,
                long globalWindowSeconds, int globalMax) {
        this.playerWindowNanos = Math.max(1, playerWindowSeconds) * 1_000_000_000L;
        this.playerMax = Math.max(1, playerMax);
        this.repeatWindowNanos = Math.max(1, repeatWindowSeconds) * 1_000_000_000L;
        this.globalWindowNanos = Math.max(1, globalWindowSeconds) * 1_000_000_000L;
        this.globalMax = Math.max(1, globalMax);
    }

    boolean allow(UUID uuid, String message) {
        long now = System.nanoTime();
        String normalized = message == null ? "" : message.trim();

        Repeat prev = lastRepeat.get(uuid);
        if (prev != null && prev.text.equals(normalized) && now - prev.atNanos < repeatWindowNanos) {
            return false;
        }

        ArrayDeque<Long> hits = playerHits.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (hits) {
            prune(hits, now, playerWindowNanos);
            if (hits.size() >= playerMax) {
                return false;
            }
            synchronized (globalHits) {
                prune(globalHits, now, globalWindowNanos);
                if (globalHits.size() >= globalMax) {
                    return false;
                }
                hits.addLast(now);
                globalHits.addLast(now);
            }
        }
        lastRepeat.put(uuid, new Repeat(normalized, now));
        return true;
    }

    private static void prune(ArrayDeque<Long> hits, long now, long window) {
        long cutoff = now - window;
        Iterator<Long> it = hits.iterator();
        while (it.hasNext()) {
            if (it.next() >= cutoff) {
                break;
            }
            it.remove();
        }
    }

    private record Repeat(String text, long atNanos) {}
}
