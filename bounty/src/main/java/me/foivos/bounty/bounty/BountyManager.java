package me.foivos.bounty.bounty;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.storage.BountyStorage;
import me.foivos.bounty.storage.StorageException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The bounties themselves: what is on whose head, and who put it there.
 *
 * <p>Everything is served from memory, so a click on the board never waits for
 * a database; changes mark the bounty as dirty and {@link #flush()} writes those
 * out — on a timer, and once more when the server stops. A write that fails is
 * left dirty and retried, so a locked database file cannot silently eat money.
 *
 * <p>Money is never taken or paid here. The caller takes it with Vault first
 * ({@link #add}) or pays it out after reading the total ({@link #total}), which
 * keeps a failed transaction from changing who owes what.
 */
public final class BountyManager {

    private final BountyPlugin plugin;
    private final BountyStorage storage;
    /** Everyone with money on their head. */
    private final Map<UUID, Bounty> bounties = new HashMap<>();
    /** target -> contributor -> what they put in (only used for self-refunds). */
    private final Map<UUID, Map<UUID, BountyContribution>> contributions = new HashMap<>();
    /** Bounties changed since the last flush. */
    private final Set<UUID> dirty = new HashSet<>();
    /** Bounties cleared since the last flush. */
    private final Set<UUID> cleared = new HashSet<>();

    public BountyManager(BountyPlugin plugin, BountyStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ── Loading and saving ──────────────────────────────────────────────

    /** Reads every stored bounty. Called once, while the plugin enables. */
    public int load() {
        int loaded = 0;
        for (BountyStorage.Snapshot snapshot : storage.load()) {
            Bounty bounty = snapshot.bounty();
            if (!bounty.active()) {
                continue; // a row left at zero is not a bounty
            }
            bounties.put(bounty.target(), bounty);
            if (!snapshot.contributions().isEmpty()) {
                Map<UUID, BountyContribution> byContributor = new LinkedHashMap<>();
                for (BountyContribution contribution : snapshot.contributions()) {
                    byContributor.put(contribution.contributor(), contribution);
                }
                contributions.put(bounty.target(), byContributor);
            }
            loaded++;
        }
        return loaded;
    }

    /**
     * Writes out everything that changed since the last time. Safe to call at
     * any point, including during shutdown.
     */
    public void flush() {
        for (UUID target : new ArrayList<>(cleared)) {
            cleared.remove(target);
            try {
                storage.delete(target);
            } catch (StorageException ex) {
                cleared.add(target); // try again on the next flush
                plugin.getLogger().log(Level.SEVERE, "Could not clear a claimed bounty", ex);
            }
        }
        for (UUID target : new ArrayList<>(dirty)) {
            Bounty bounty = bounties.get(target);
            if (bounty == null) {
                dirty.remove(target);
                continue;
            }
            dirty.remove(target);
            try {
                storage.save(bounty, contributions.getOrDefault(target, Map.of()).values());
            } catch (StorageException ex) {
                dirty.add(target); // it is still only in memory: keep trying
                plugin.getLogger().log(Level.SEVERE,
                        "Could not save " + bounty.targetName() + "'s bounty of " + bounty.total(), ex);
            }
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** One player's bounty, or null when there is nothing on their head. */
    public Bounty bounty(UUID target) {
        return target == null ? null : bounties.get(target);
    }

    public boolean has(UUID target) {
        Bounty bounty = bounty(target);
        return bounty != null && bounty.active();
    }

    public double total(UUID target) {
        Bounty bounty = bounty(target);
        return bounty == null ? 0.0 : bounty.total();
    }

    /** How much of a bounty the target put there themselves (never shown). */
    public double selfTotal(UUID target) {
        Bounty bounty = bounty(target);
        return bounty == null ? 0.0 : bounty.selfTotal();
    }

    /** Every bounty worth showing, in no particular order. */
    public Collection<Bounty> all() {
        List<Bounty> active = new ArrayList<>();
        for (Bounty bounty : bounties.values()) {
            if (bounty.active()) {
                active.add(bounty);
            }
        }
        return active;
    }

    public int size() {
        return all().size();
    }

    /**
     * A page of bounties, biggest first or smallest first.
     *
     * <p>The order is stable: equal totals fall back to the name, so the board
     * does not shuffle itself on every redraw.
     */
    public List<Bounty> sorted(boolean highestFirst) {
        List<Bounty> list = new ArrayList<>(all());
        Comparator<Bounty> byTotal = Comparator.comparingDouble(Bounty::total);
        Comparator<Bounty> byName = Comparator.comparing(Bounty::targetName, String.CASE_INSENSITIVE_ORDER);
        list.sort(highestFirst ? byTotal.reversed().thenComparing(byName) : byTotal.thenComparing(byName));
        return list;
    }

    /** The stored name of a target, for messages about an offline player. */
    public String nameOf(UUID target) {
        Bounty bounty = bounty(target);
        return bounty == null ? "unknown" : bounty.targetName();
    }

    /** Finds a bounty by name, case-insensitively, for /bounty check <name>. */
    public Bounty byName(String name) {
        if (name == null) {
            return null;
        }
        for (Bounty bounty : all()) {
            if (bounty.targetName().equalsIgnoreCase(name)) {
                return bounty;
            }
        }
        return null;
    }

    // ── Changing ────────────────────────────────────────────────────────

    /**
     * Adds money to a target's bounty. The money must already have been taken
     * from whoever placed it: this only records who it came from and how much,
     * so a self-bounty can be refunded later.
     *
     * @param actor     who placed it (the target themselves for a self-bounty)
     * @param target    whose head it goes on
     * @param targetName the target's name right now, for the board and messages
     * @param amount    how much was taken from the actor
     * @return the target's bounty after the addition
     */
    public Bounty add(UUID actor, String actorName, UUID target, String targetName, double amount) {
        Bounty bounty = bounties.computeIfAbsent(target, key -> Bounty.empty(key, targetName));
        bounty.targetName(targetName);
        bounty.add(amount, actor != null && actor.equals(target));
        long now = System.currentTimeMillis();
        contributions.computeIfAbsent(target, key -> new LinkedHashMap<>())
                .merge(actor, new BountyContribution(actor, actorName, amount, now),
                        (old, fresh) -> old.add(fresh.amount(), fresh.updatedAt()));
        dirty.add(target);
        cleared.remove(target);
        return bounty;
    }

    /**
     * Forgets a bounty: it was claimed, or an admin cleared it.
     *
     * <p>It is only dropped from memory here — the row goes on the next flush,
     * after the money has actually changed hands.
     */
    public void clear(UUID target) {
        if (bounties.remove(target) == null) {
            return;
        }
        contributions.remove(target);
        dirty.remove(target);
        cleared.add(target);
    }

    /** Keeps the stored name in step when a player joins under a new one. */
    public void refreshName(UUID target, String name) {
        Bounty bounty = bounties.get(target);
        if (bounty == null || name == null || name.equals(bounty.targetName())) {
            return;
        }
        bounty.targetName(name);
        dirty.add(target);
    }

    /** Everything a flush needs to write, for the console at shutdown. */
    public int pendingWrites() {
        return dirty.size() + cleared.size();
    }
}
