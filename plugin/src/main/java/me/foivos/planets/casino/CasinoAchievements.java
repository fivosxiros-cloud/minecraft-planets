package me.foivos.planets.casino;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The achievements the casino ships with, read from {@code casino.achievements}.
 *
 * <p>Every entry can be turned off, re-worded or re-priced from config.yml, and
 * the shipped set is only ever used to <b>seed</b> the section and to fill in
 * any achievement a config.yml written earlier has never heard of — the same
 * way the sidebar grows a line that a later version added. Nothing is unlocked
 * here: the manager asks {@link #qualified} and does the unlocking, so the
 * bookkeeping stays in one place.
 */
final class CasinoAchievements {

    private final Map<String, CasinoAchievement> all = new LinkedHashMap<>();

    /**
     * Reads the achievements, adding any shipped one that is not in the
     * section yet.
     *
     * @return whether the section had to be written to, so the caller saves
     */
    boolean load(ConfigurationSection section) {
        all.clear();
        if (section == null) {
            for (CasinoAchievement shipped : CasinoDefaults.achievements()) {
                all.put(shipped.id(), shipped);
            }
            return false;
        }
        boolean written = false;
        // Read what the server has...
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            CasinoAchievement shipped = CasinoDefaults.achievement(id);
            if (entry == null || shipped == null) {
                continue;
            }
            all.put(id, read(id, entry, shipped));
        }
        // ...then add the ones added since the file was written.
        for (CasinoAchievement shipped : CasinoDefaults.achievements()) {
            if (all.containsKey(shipped.id())) {
                continue;
            }
            all.put(shipped.id(), shipped);
            ConfigurationSection entry = section.createSection(shipped.id());
            CasinoDefaults.writeAchievement(entry, shipped);
            written = true;
        }
        return written;
    }

    private static CasinoAchievement read(String id, ConfigurationSection entry, CasinoAchievement shipped) {
        Material icon = shipped.icon();
        String iconName = entry.getString("icon");
        if (iconName != null && !iconName.isBlank()) {
            Material parsed = Material.matchMaterial(iconName);
            if (parsed != null) {
                icon = parsed;
            }
        }
        return new CasinoAchievement(id,
                entry.getString("name", shipped.name()),
                entry.getString("description", shipped.description()),
                icon,
                CasinoAchievement.kind(entry.getString("kind"), shipped.kind()),
                Math.max(1, entry.getInt("goal", shipped.goal())),
                CasinoRarity.parse(entry.getString("rarity"), shipped.rarity()),
                entry.getBoolean("enabled", true));
    }

    /** Every achievement, in config order. */
    List<CasinoAchievement> all() {
        return List.copyOf(all.values());
    }

    /** Only the achievements that are switched on. */
    List<CasinoAchievement> enabled() {
        List<CasinoAchievement> result = new ArrayList<>();
        for (CasinoAchievement achievement : all.values()) {
            if (achievement.enabled()) {
                result.add(achievement);
            }
        }
        return result;
    }

    CasinoAchievement byId(String id) {
        return id == null ? null : all.get(id);
    }

    int enabledCount() {
        return enabled().size();
    }

    int goalFor(CasinoAchievement.Kind kind, int fallback) {
        for (CasinoAchievement achievement : all.values()) {
            if (achievement.enabled() && achievement.kind() == kind) {
                return achievement.goal();
            }
        }
        return fallback;
    }

    /**
     * The tier that counts as a lucky prize: whatever the lucky achievement
     * asks for, so a server can decide that "rare" means epic without a
     * rebuild.
     */
    CasinoRarity rareTier(CasinoRarity fallback) {
        for (CasinoAchievement achievement : all.values()) {
            if (achievement.enabled() && achievement.kind() == CasinoAchievement.Kind.RARE_PRIZE) {
                return achievement.rarity();
            }
        }
        return fallback;
    }

    /**
     * The achievements a player's record now satisfies.
     *
     * @param gamesAvailable how many games the hub currently offers, for the
     *                       "played them all" achievement
     * @param hasRarePrize   whether they have ever collected a rare or better
     *                       prize
     */
    List<CasinoAchievement> qualified(CasinoStats stats, int gamesAvailable, boolean hasRarePrize) {
        List<CasinoAchievement> result = new ArrayList<>();
        for (CasinoAchievement achievement : all.values()) {
            if (achievement.enabled() && achievement.metBy(stats, gamesAvailable, hasRarePrize)) {
                result.add(achievement);
            }
        }
        return result;
    }
}
