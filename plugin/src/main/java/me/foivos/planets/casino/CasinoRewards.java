package me.foivos.planets.casino;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The prize pool every casino game draws from.
 *
 * <p>A prize is a name, a tier, an icon and a weight; the odds of any one of
 * them coming out are its weight against the total, so {@code casino.rewards}
 * in config.yml is the only place the odds live. Set a weight to 0 to keep a
 * prize listed in the cabinet without it ever rolling again.
 */
final class CasinoRewards {

    private final Map<String, CasinoReward> pool = new LinkedHashMap<>();
    private int totalWeight;
    private int rollable;

    /** Reads the pool from {@code casino.rewards} in config.yml. */
    void load(ConfigurationSection section) {
        pool.clear();
        totalWeight = 0;
        rollable = 0;
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            int weight = Math.max(0, entry.getInt("weight", 0));
            CasinoReward reward = new CasinoReward(id,
                    entry.getString("name", id),
                    CasinoRarity.parse(entry.getString("rarity"), CasinoRarity.COMMON),
                    material(entry.getString("icon")),
                    entry.getStringList("lore"),
                    weight);
            pool.put(id, reward);
            if (reward.rollable()) {
                totalWeight += weight;
                rollable++;
            }
        }
    }

    private static Material material(String name) {
        if (name == null || name.isBlank()) {
            return Material.PAPER;
        }
        Material parsed = Material.matchMaterial(name);
        return parsed == null || parsed == Material.AIR ? Material.PAPER : parsed;
    }

    /** Every prize, in config order. */
    List<CasinoReward> all() {
        return List.copyOf(pool.values());
    }

    /** One prize by id, or null when the pool has never heard of it. */
    CasinoReward byId(String id) {
        return id == null ? null : pool.get(id);
    }

    int size() {
        return pool.size();
    }

    /** How many prizes can actually come out of a roll. */
    int rollableCount() {
        return rollable;
    }

    /**
     * Picks a prize, weighted by the configured odds, or null when nothing is
     * rollable — which is how a server turns the prize side of the casino off
     * without disabling the games.
     */
    CasinoReward roll() {
        if (totalWeight <= 0) {
            return null;
        }
        int pick = ThreadLocalRandom.current().nextInt(totalWeight);
        for (CasinoReward reward : pool.values()) {
            if (!reward.rollable()) {
                continue;
            }
            pick -= reward.weight();
            if (pick < 0) {
                return reward;
            }
        }
        return null;
    }

    /**
     * Whether a player has ever collected a prize of {@code rarity} or better
     * — the question the lucky achievement asks.
     */
    boolean hasAtLeast(CasinoStats stats, CasinoRarity rarity) {
        if (stats == null || rarity == null) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : stats.rewards().entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            CasinoReward reward = pool.get(entry.getKey());
            if (reward != null && reward.rarity().atLeast(rarity)) {
                return true;
            }
        }
        return false;
    }

    /** The ids in the pool, for the "collected" tally in the cabinet. */
    List<String> ids() {
        return new ArrayList<>(pool.keySet());
    }
}
