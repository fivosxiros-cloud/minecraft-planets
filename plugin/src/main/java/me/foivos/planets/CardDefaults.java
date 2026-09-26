package me.foivos.planets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The card list the event ships with: the vanilla animals, monsters and bosses,
 * each with its own drop chance.
 *
 * <p>This is only ever used to <b>seed</b> {@code cards.entities} in config.yml
 * the first time the plugin runs (or when that section is emptied). After that
 * the config is the source of truth — the list can be trimmed, extended or
 * re-priced without touching any code, and this class is never consulted again
 * unless the section is removed.
 *
 * <p>Names are written as text rather than as {@code EntityType} constants on
 * purpose: the list is resolved against whatever server it is loaded on, so a
 * card for a mob that version does not have is skipped instead of failing.
 */
final class CardDefaults {

    private CardDefaults() {
    }

    /** Every shipped card, ready to be written into the config. */
    static List<Map<String, Object>> entities() {
        List<Map<String, Object>> cards = new ArrayList<>();

        // ── 🐑 Animals ─────────────────────────────────────────────────────
        animal(cards, "allay", 6, CardRarity.UNCOMMON);
        animal(cards, "armadillo", 12, CardRarity.COMMON);
        animal(cards, "axolotl", 10, CardRarity.UNCOMMON);
        animal(cards, "bat", 14, CardRarity.COMMON);
        animal(cards, "bee", 12, CardRarity.COMMON);
        animal(cards, "camel", 10, CardRarity.COMMON);
        animal(cards, "cat", 10, CardRarity.COMMON);
        animal(cards, "chicken", 16, CardRarity.COMMON);
        animal(cards, "cod", 14, CardRarity.COMMON);
        animal(cards, "cow", 16, CardRarity.COMMON);
        animal(cards, "dolphin", 8, CardRarity.UNCOMMON);
        animal(cards, "donkey", 10, CardRarity.COMMON);
        animal(cards, "fox", 12, CardRarity.COMMON);
        animal(cards, "frog", 12, CardRarity.COMMON);
        animal(cards, "glow_squid", 8, CardRarity.UNCOMMON);
        animal(cards, "goat", 10, CardRarity.COMMON);
        animal(cards, "horse", 10, CardRarity.COMMON);
        animal(cards, "iron_golem", 5, CardRarity.RARE);
        animal(cards, "llama", 10, CardRarity.COMMON);
        animal(cards, "mooshroom", 6, CardRarity.RARE);
        animal(cards, "mule", 8, CardRarity.UNCOMMON);
        animal(cards, "ocelot", 8, CardRarity.UNCOMMON);
        animal(cards, "panda", 6, CardRarity.RARE);
        animal(cards, "parrot", 10, CardRarity.COMMON);
        animal(cards, "pig", 16, CardRarity.COMMON);
        animal(cards, "polar_bear", 6, CardRarity.RARE);
        animal(cards, "pufferfish", 12, CardRarity.COMMON);
        animal(cards, "rabbit", 14, CardRarity.COMMON);
        animal(cards, "salmon", 14, CardRarity.COMMON);
        animal(cards, "sheep", 15, CardRarity.COMMON);
        animal(cards, "sniffer", 4, CardRarity.RARE);
        animal(cards, "snow_golem", 8, CardRarity.UNCOMMON);
        animal(cards, "squid", 14, CardRarity.COMMON);
        animal(cards, "strider", 8, CardRarity.UNCOMMON);
        animal(cards, "tadpole", 14, CardRarity.COMMON);
        animal(cards, "trader_llama", 6, CardRarity.RARE);
        animal(cards, "tropical_fish", 12, CardRarity.COMMON);
        animal(cards, "turtle", 8, CardRarity.UNCOMMON);
        animal(cards, "villager", 8, CardRarity.UNCOMMON);
        animal(cards, "wandering_trader", 4, CardRarity.RARE);
        animal(cards, "wolf", 12, CardRarity.COMMON);
        animal(cards, "zombie_horse", 5, CardRarity.RARE);
        animal(cards, "happy_ghast", 3, CardRarity.EPIC);

        // ── 👹 Monsters ────────────────────────────────────────────────────
        monster(cards, "blaze", 6, CardRarity.UNCOMMON);
        monster(cards, "bogged", 6, CardRarity.UNCOMMON);
        monster(cards, "breeze", 4, CardRarity.RARE);
        monster(cards, "cave_spider", 10, CardRarity.COMMON);
        monster(cards, "creaking", 3, CardRarity.EPIC);
        monster(cards, "creeper", 8, CardRarity.UNCOMMON);
        monster(cards, "drowned", 10, CardRarity.COMMON);
        monster(cards, "enderman", 6, CardRarity.UNCOMMON);
        monster(cards, "endermite", 10, CardRarity.COMMON);
        monster(cards, "evoker", 4, CardRarity.RARE);
        monster(cards, "ghast", 6, CardRarity.UNCOMMON);
        monster(cards, "giant", 2, CardRarity.EPIC);
        monster(cards, "guardian", 8, CardRarity.UNCOMMON);
        monster(cards, "hoglin", 6, CardRarity.UNCOMMON);
        monster(cards, "husk", 10, CardRarity.COMMON);
        monster(cards, "illusioner", 3, CardRarity.EPIC);
        monster(cards, "magma_cube", 8, CardRarity.UNCOMMON);
        monster(cards, "phantom", 8, CardRarity.UNCOMMON);
        monster(cards, "piglin", 6, CardRarity.UNCOMMON);
        monster(cards, "piglin_brute", 4, CardRarity.RARE);
        monster(cards, "pillager", 8, CardRarity.UNCOMMON);
        monster(cards, "ravager", 4, CardRarity.RARE);
        monster(cards, "shulker", 5, CardRarity.RARE);
        monster(cards, "silverfish", 10, CardRarity.COMMON);
        monster(cards, "skeleton", 10, CardRarity.COMMON);
        monster(cards, "slime", 10, CardRarity.COMMON);
        monster(cards, "spider", 10, CardRarity.COMMON);
        monster(cards, "stray", 8, CardRarity.UNCOMMON);
        monster(cards, "vex", 6, CardRarity.UNCOMMON);
        monster(cards, "vindicator", 8, CardRarity.UNCOMMON);
        monster(cards, "witch", 6, CardRarity.UNCOMMON);
        monster(cards, "wither_skeleton", 4, CardRarity.RARE);
        monster(cards, "zoglin", 5, CardRarity.RARE);
        monster(cards, "zombie", 8, CardRarity.COMMON);
        monster(cards, "zombie_villager", 8, CardRarity.UNCOMMON);
        monster(cards, "zombified_piglin", 8, CardRarity.UNCOMMON);

        // ── 👑 Bosses & special ────────────────────────────────────────────
        // Bosses carry no explicit icon either: like every other card they are
        // drawn as the mob's own spawn egg, so the collection reads as one set.
        card(cards, "elder_guardian", "ELDER_GUARDIAN", "Elder Guardian", 3,
                CardCategory.BOSSES, CardRarity.RARE, null, true);
        card(cards, "warden", "WARDEN", "Warden", 1,
                CardCategory.BOSSES, CardRarity.MYTHIC, null, true);
        card(cards, "wither", "WITHER", "Wither", 2,
                CardCategory.BOSSES, CardRarity.LEGENDARY, null, true);
        // The Ender Dragon's card exists in every collection as the one entry
        // that can never be earned: obtainable false, so its roll never happens.
        card(cards, "ender_dragon", "ENDER_DRAGON", "Ender Dragon", 0,
                CardCategory.BOSSES, CardRarity.UNOBTAINABLE, null, false);

        return cards;
    }

    private static void animal(List<Map<String, Object>> cards, String entity, double chance,
                               CardRarity rarity) {
        card(cards, entity, entity.toUpperCase(java.util.Locale.ROOT), pretty(entity), chance,
                CardCategory.ANIMALS, rarity, null, true);
    }

    private static void monster(List<Map<String, Object>> cards, String entity, double chance,
                                CardRarity rarity) {
        card(cards, entity, entity.toUpperCase(java.util.Locale.ROOT), pretty(entity), chance,
                CardCategory.MONSTERS, rarity, null, true);
    }

    private static void card(List<Map<String, Object>> cards, String id, String entity, String name,
                             double chance, CardCategory category, CardRarity rarity,
                             String icon, boolean obtainable) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("entity", entity);
        entry.put("name", name);
        entry.put("chance", chance);
        entry.put("category", category.key());
        entry.put("rarity", rarity.name().toLowerCase(java.util.Locale.ROOT));
        if (icon != null) {
            entry.put("icon", icon);
        }
        if (!obtainable) {
            entry.put("obtainable", false);
        }
        cards.add(entry);
    }

    /** "zombie_villager" -> "Zombie Villager". */
    private static String pretty(String snake) {
        StringBuilder out = new StringBuilder();
        for (String word : snake.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
