package me.foivos.planets;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The card catalogue: every collectible entity the event offers, read from
 * {@code cards.entities} in config.yml.
 *
 * <p>Everything is resolved <b>by name</b>. An entry naming a mob this server
 * does not have is dropped with a warning, an unknown material falls back to a
 * sensible icon, and an entry with no id gets one derived from its entity — so
 * a card list can be edited by hand without the event ever failing to start.
 *
 * <p>The registry is also what "the total number of cards" means: the
 * collection always shows {@code x / registry.size()}, so adding a card to the
 * config immediately widens everybody's collection.
 */
final class CardRegistry {

    /** Default cap on copies of a single card. */
    static final int DEFAULT_MAX_COPIES = 64;

    /** Old names the server may still use, accepted so shared configs keep working. */
    private static final Map<String, String> ENTITY_ALIASES = Map.of(
            "MUSHROOM_COW", "MOOSHROOM",
            "MOOSHROOM", "MUSHROOM_COW",
            "SNOWMAN", "SNOW_GOLEM",
            "SNOW_GOLEM", "SNOWMAN",
            "PIG_ZOMBIE", "ZOMBIFIED_PIGLIN",
            "ZOMBIE_PIGMAN", "ZOMBIFIED_PIGLIN",
            "ZOMBIFIED_PIGLIN", "PIG_ZOMBIE");

    /** Mobs with a real head item, which always beats the derived fallback. */
    private static final Map<String, String> VANILLA_HEADS = Map.ofEntries(
            Map.entry("ZOMBIE", "ZOMBIE_HEAD"),
            Map.entry("HUSK", "ZOMBIE_HEAD"),
            Map.entry("DROWNED", "ZOMBIE_HEAD"),
            Map.entry("ZOMBIE_VILLAGER", "ZOMBIE_HEAD"),
            Map.entry("GIANT", "ZOMBIE_HEAD"),
            Map.entry("SKELETON", "SKELETON_SKULL"),
            Map.entry("STRAY", "SKELETON_SKULL"),
            Map.entry("BOGGED", "SKELETON_SKULL"),
            Map.entry("WITHER_SKELETON", "WITHER_SKELETON_SKULL"),
            Map.entry("CREEPER", "CREEPER_HEAD"),
            Map.entry("ENDER_DRAGON", "DRAGON_HEAD"),
            Map.entry("PIGLIN", "PIGLIN_HEAD"),
            Map.entry("PIGLIN_BRUTE", "PIGLIN_HEAD"),
            Map.entry("ZOMBIFIED_PIGLIN", "PIGLIN_HEAD"));

    private final Planets plugin;

    private final List<CardDefinition> cards = new ArrayList<>();
    private final Map<String, CardDefinition> byId = new LinkedHashMap<>();
    private final Map<EntityType, List<CardDefinition>> byEntity = new EnumMap<>(EntityType.class);

    private int maxCopies = DEFAULT_MAX_COPIES;
    private Material unknownIcon = Material.GRAY_STAINED_GLASS_PANE;
    private String unknownName = "???";
    /** Whether cards are drawn as spawn eggs (default) rather than mob heads. */
    private boolean spawnEggIcons = true;

    CardRegistry(Planets plugin) {
        this.plugin = plugin;
    }

    // ── Loading ─────────────────────────────────────────────────────────

    /**
     * Reads (and if needed seeds) the card list.
     *
     * @return true when the config was changed and should be saved
     */
    boolean load(ConfigurationSection section) {
        cards.clear();
        byId.clear();
        byEntity.clear();

        if (section == null) {
            section = plugin.getConfig().createSection("cards");
        }
        this.maxCopies = Math.max(1, section.getInt("max-copies", DEFAULT_MAX_COPIES));
        this.unknownIcon = material(section.getString("unknown-icon"), Material.GRAY_STAINED_GLASS_PANE);
        this.unknownName = section.getString("unknown-name", "???");
        String style = section.getString("icon-style", "spawn-egg");
        this.spawnEggIcons = style == null || !style.trim().equalsIgnoreCase("mob-head");

        boolean seeded = false;
        List<Map<?, ?>> raw = section.getMapList("entities");
        if (raw.isEmpty()) {
            // First run on this server, or the list was emptied by hand: write
            // the shipped cards in so the owner has something to edit.
            section.set("entities", CardDefaults.entities());
            raw = section.getMapList("entities");
            seeded = true;
        }

        int skipped = 0;
        for (Map<?, ?> entry : raw) {
            CardDefinition card = parse(entry);
            if (card == null) {
                skipped++;
                continue;
            }
            if (byId.containsKey(card.id())) {
                plugin.getLogger().warning("Ignoring duplicate card id '" + card.id() + "'.");
                skipped++;
                continue;
            }
            cards.add(card);
            byId.put(card.id(), card);
            if (card.entity() != null) {
                byEntity.computeIfAbsent(card.entity(), key -> new ArrayList<>()).add(card);
            }
        }
        if (skipped > 0) {
            plugin.getLogger().warning("Skipped " + skipped + " card(s) that could not be read.");
        }
        plugin.getLogger().info("Loaded " + cards.size() + " entity card(s) ("
                + obtainableCount() + " obtainable).");
        return seeded;
    }

    /** Builds one card from a config map, or null when it cannot be used. */
    private CardDefinition parse(Map<?, ?> entry) {
        String entityName = text(entry.get("entity"));
        if (entityName == null) {
            plugin.getLogger().warning("A card entry has no 'entity' — ignoring it.");
            return null;
        }
        EntityType entity = resolveEntity(entityName);
        if (entity == null) {
            plugin.getLogger().warning("This server has no entity '" + entityName
                    + "' — that card is skipped.");
            return null;
        }
        String id = text(entry.get("id"));
        if (id == null || id.isBlank()) {
            id = entityName.toLowerCase(Locale.ROOT);
        }
        id = id.toLowerCase(Locale.ROOT).replace(' ', '_');
        String name = text(entry.get("name"));
        if (name == null || name.isBlank()) {
            name = pretty(entityName);
        }
        double chance = number(entry.get("chance"), 5.0);
        CardCategory category = CardCategory.byName(text(entry.get("category")));
        CardRarity rarity = CardRarity.byName(text(entry.get("rarity")));
        boolean obtainable = !Boolean.FALSE.equals(entry.get("obtainable"));
        int perCardMax = (int) number(entry.get("max-copies"), maxCopies);
        String headTexture = text(entry.get("head-texture"));
        String iconName = text(entry.get("icon"));
        Material icon = iconName == null ? null : Material.matchMaterial(iconName);
        if (icon != null && icon.isAir()) {
            icon = null;
        }
        if (icon == null && headTexture != null && !headTexture.isBlank()) {
            icon = Material.PLAYER_HEAD;
        }
        if (icon == null) {
            icon = deriveIcon(entity, entityName);
        }
        return new CardDefinition(id, entityName, entity, name, icon, headTexture,
                Math.max(0, chance), category, rarity, obtainable, Math.max(1, perCardMax));
    }

    /**
     * A sensible icon for a mob.
     *
     * <p>Which of the two wins is {@code cards.icon-style}. By default every
     * card is drawn as the mob's <b>spawn egg</b>, which vanilla ships for all
     * but two of the shipped mobs: the Giant falls back to its zombie head and
     * the Illusioner, which has no head item either, to a blank head.
     * {@code icon-style: mob-head} flips the preference back the other way.
     * Either way a mob always gets an item, so an icon is never null and no
     * menu can be broken by a missing one.
     */
    private Material deriveIcon(EntityType entity, String configuredName) {
        String key = entity.name();
        Material egg = spawnEgg(key);
        Material head = material(VANILLA_HEADS.get(key), null);
        if (head == null) {
            head = material(VANILLA_HEADS.get(configuredName.toUpperCase(Locale.ROOT)), null);
        }
        // The style picks which item is preferred; the other still covers a mob
        // that vanilla has no item of the preferred kind for.
        Material preferred = spawnEggIcons ? egg : head;
        Material spare = spawnEggIcons ? head : egg;
        if (preferred != null) {
            return preferred;
        }
        return spare == null ? Material.PLAYER_HEAD : spare;
    }

    /** The spawn egg for an entity by name, trying its old spelling as well. */
    private static Material spawnEgg(String key) {
        Material egg = material(key + "_SPAWN_EGG", null);
        if (egg == null) {
            String alias = ENTITY_ALIASES.get(key);
            if (alias != null) {
                egg = material(alias + "_SPAWN_EGG", null);
            }
        }
        return egg;
    }

    /** An EntityType by name, trying the old spelling when the new one is unknown. */
    static EntityType resolveEntity(String name) {
        String key = name.trim().toUpperCase(Locale.ROOT);
        EntityType type = byName(key);
        if (type != null) {
            return type;
        }
        String alias = ENTITY_ALIASES.get(key);
        return alias == null ? null : byName(alias);
    }

    private static EntityType byName(String key) {
        try {
            return EntityType.valueOf(key);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return parsed == null || parsed.isAir() ? fallback : parsed;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            String text = String.valueOf(value).trim().replace("%", "");
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String pretty(String snake) {
        StringBuilder out = new StringBuilder();
        for (String word : snake.replace('-', '_').split("_")) {
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

    // ── Reads ───────────────────────────────────────────────────────────

    /** Every card, in config order. */
    List<CardDefinition> all() {
        return List.copyOf(cards);
    }

    /** How many cards exist — the denominator of "x / y". */
    int size() {
        return cards.size();
    }

    /** How many of them can actually be earned. */
    int obtainableCount() {
        int count = 0;
        for (CardDefinition card : cards) {
            if (card.obtainable()) {
                count++;
            }
        }
        return count;
    }

    /** One card by id, or null. */
    CardDefinition byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** The cards an entity can drop — normally one, but a config may share a mob. */
    List<CardDefinition> forEntity(EntityType type) {
        List<CardDefinition> list = type == null ? null : byEntity.get(type);
        return list == null ? List.of() : list;
    }

    /** Every card of one category, in config order. */
    List<CardDefinition> byCategory(CardCategory category) {
        if (category == null) {
            return all();
        }
        List<CardDefinition> result = new ArrayList<>();
        for (CardDefinition card : cards) {
            if (card.category() == category) {
                result.add(card);
            }
        }
        return result;
    }

    /** The categories that actually have cards on this server, in declaration order. */
    List<CardCategory> categories() {
        List<CardCategory> result = new ArrayList<>();
        for (CardCategory category : CardCategory.values()) {
            for (CardDefinition card : cards) {
                if (card.category() == category) {
                    result.add(category);
                    break;
                }
            }
        }
        return result;
    }

    /** The cap on copies of one card (config: {@code cards.max-copies}). */
    int maxCopies() {
        return maxCopies;
    }

    Material unknownIcon() { return unknownIcon; }

    String unknownName() { return unknownName; }
}
