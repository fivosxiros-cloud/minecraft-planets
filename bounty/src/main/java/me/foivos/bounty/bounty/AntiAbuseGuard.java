package me.foivos.bounty.bounty;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stops a bounty being farmed by two players killing each other on purpose.
 *
 * <p>It only ever looks at one pair at a time: the same killer claiming from the
 * same victim twice inside {@code anti-abuse.kill-cooldown} seconds. A blocked
 * claim pays nothing <em>and leaves the bounty standing</em>, so nothing is
 * lost by the block — it just has to be earned later.
 *
 * <p>Which pair was claimed when is kept in memory only: a restart is not a
 * loophole anyone can plan for, and it keeps the database free of bookkeeping
 * that has nothing to do with bounties.
 */
public final class AntiAbuseGuard {

    private final Map<Key, Long> lastClaimAt = new HashMap<>();

    private record Key(UUID killer, UUID victim) {
    }

    /**
     * Whether this kill must not be paid out.
     *
     * @param cooldownSeconds 0 or less turns the guard off entirely
     */
    public boolean blocked(UUID killer, UUID victim, long cooldownSeconds) {
        if (killer == null || victim == null || cooldownSeconds <= 0L) {
            return false;
        }
        Long last = lastClaimAt.get(new Key(killer, victim));
        return last != null && System.currentTimeMillis() - last < cooldownSeconds * 1000L;
    }

    /** Remembers that this killer was paid from this victim, just now. */
    public void record(UUID killer, UUID victim) {
        if (killer == null || victim == null) {
            return;
        }
        lastClaimAt.put(new Key(killer, victim), System.currentTimeMillis());
    }

    /** How long until the same killer could claim from the same victim again. */
    public long remainingSeconds(UUID killer, UUID victim, long cooldownSeconds) {
        Long last = lastClaimAt.get(new Key(killer, victim));
        if (last == null) {
            return 0L;
        }
        long left = (cooldownSeconds * 1000L) - (System.currentTimeMillis() - last);
        return Math.max(0L, left / 1000L);
    }

    public void clear() {
        lastClaimAt.clear();
    }
}
