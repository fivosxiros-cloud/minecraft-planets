package me.foivos.planets;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One collectible card: the entity it belongs to, how likely it is to drop,
 * and how it is drawn.
 *
 * <p>Cards are <b>digital</b>: nothing is ever put in a player's inventory, a
 * card is just a count in their collection. The icon is only ever used inside
 * the menus.
 *
 * <p>The entity and the icon are both resolved <i>by name</i> from config.yml
 * rather than referenced as constants, so a card list written for one server
 * version keeps loading on another — a name this server does not know is
 * skipped with a warning instead of breaking the whole event.
 */
public record CardDefinition(
        String id,
        String entityName,
        EntityType entity,
        String displayName,
        Material icon,
        String headTexture,
        double chancePercent,
        CardCategory category,
        CardRarity rarity,
        boolean obtainable,
        int maxCopies) {

    /**
     * Textured heads are built once per texture and reused: a page of cards
     * would otherwise build 28 identical profiles every time it is drawn.
     */
    private static final Map<String, PlayerProfile> HEAD_CACHE = new ConcurrentHashMap<>();

    /** Whether this card can ever come out of a kill. */
    public boolean drops() {
        return obtainable && entity != null && chancePercent > 0;
    }

    /** The drop chance as a fraction (0.15 for 15%). */
    public double chance() {
        return chancePercent / 100.0;
    }

    /** "15%" or "1.5%" — however many decimals the configured chance needs. */
    public String chanceLabel() {
        return (chancePercent == Math.rint(chancePercent)
                ? String.valueOf((long) chancePercent)
                : String.valueOf(chancePercent)) + "%";
    }

    /** A fresh icon stack for the menus. */
    public ItemStack iconStack() {
        ItemStack item = new ItemStack(icon == null ? Material.PLAYER_HEAD : icon);
        if (headTexture != null && !headTexture.isBlank()) {
            applyHeadTexture(item, headTexture);
        }
        return item;
    }

    /**
     * Turns a plain player head into the given skin. A texture that the server
     * cannot build is ignored, leaving the head blank rather than throwing —
     * a bad texture in config must never break a menu.
     */
    public static void applyHeadTexture(ItemStack item, String texture) {
        if (item == null || texture == null || texture.isBlank()
                || !(item.getItemMeta() instanceof SkullMeta meta)) {
            return;
        }
        PlayerProfile profile = HEAD_CACHE.computeIfAbsent(texture, value -> {
            try {
                PlayerProfile created = Bukkit.createProfile(UUID.randomUUID());
                created.setProperty(new ProfileProperty("textures", value.trim()));
                return created;
            } catch (RuntimeException | LinkageError ex) {
                return null;
            }
        });
        if (profile == null) {
            return;
        }
        meta.setPlayerProfile(profile);
        item.setItemMeta(meta);
    }

    /** Replaces the cache entry for a texture (not needed at runtime; used by reloads). */
    public static void clearHeadCache() {
        HEAD_CACHE.clear();
    }
}
