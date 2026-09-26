package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Entity Card Hunt itself: the drop rolls, the discovery celebrations, the
 * leaderboard and the timer.
 *
 * <p>It owns the {@link CardRegistry} (what can drop), the {@link CardStore}
 * (who has what) and the {@link CardDiscoveryMessages} (what is said about it),
 * and is the single object the plugin and the menus talk to.
 *
 * <p>Everything about the timer is derived from one persistent timestamp, so a
 * restart, a reload or a player logging out and back in all read the same
 * answer. Nothing is ever counted down in a variable.
 */
final class CardService {

    /** One leaderboard row. */
    record Ranked(UUID uuid, String name, int unique, int copies, int rank) {
    }

    /** The shipped event length, in hours (14 days). */
    private static final double DEFAULT_DURATION_HOURS = 336.0;

    private final Planets plugin;
    private final CardRegistry registry;
    private final CardStore store;
    private final CardDiscoveryMessages messages;
    private final CardLuckProtection luck;

    private List<Ranked> leaderboardCache = List.of();
    private long leaderboardCacheAt;
    private boolean leaderboardBuilt;
    private BukkitTask refreshTask;

    CardService(Planets plugin) {
        this.plugin = plugin;
        this.registry = new CardRegistry(plugin);
        this.store = new CardStore(plugin);
        this.messages = new CardDiscoveryMessages(plugin);
        this.luck = new CardLuckProtection(plugin);

        this.store.load();
        ConfigurationSection cards = plugin.getConfig().getConfigurationSection("cards");
        if (cards == null) {
            cards = plugin.getConfig().createSection("cards");
        }
        boolean seeded = this.registry.load(cards);
        this.messages.load(cards);
        this.luck.load(cards);
        applyDefaultDuration();
        if (seeded) {
            // The shipped card list was written into the config so it can be
            // edited in place; store it now.
            plugin.saveConfigQuietly();
        }
    }

    /**
     * Starts the task that keeps an <i>open</i> leaderboard live. Only boards
     * that are actually on someone's screen are redrawn, so an idle server
     * does no work at all.
     */
    void start() {
        long seconds = Math.max(5L, plugin.getConfig()
                .getLong("cards.leaderboard-live-refresh-seconds", 20L));
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                CardLeaderboardMenu::refreshOpen, seconds * 20L, seconds * 20L);
    }

    /** Writes anything queued and stops the live-refresh task. */
    void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        CardLeaderboardMenu.clearOpen();
        store.save();
    }

    CardRegistry registry() { return registry; }

    CardStore store() { return store; }

    CardDiscoveryMessages messages() { return messages; }

    /** The bad-luck protection policy (off unless the config asks for it). */
    CardLuckProtection luck() { return luck; }

    /** Keeps a player's stored name fresh for the leaderboard. */
    void onJoin(Player player) {
        if (player != null) {
            store.rememberName(player.getUniqueId(), player.getName());
        }
    }

    // ── The event window ────────────────────────────────────────────────

    /**
     * If no end has been decided yet, one is set from
     * {@code cards.event-duration-hours}. A duration of 0 or less leaves the
     * event open-ended, which is how an event that never expires is set up.
     */
    private void applyDefaultDuration() {
        if (store.eventEndsAt() > 0) {
            plugin.getLogger().info("Entity Card Hunt ends at " + Planets.formatDate(store.eventEndsAt())
                    + " (" + remainingShort() + " left).");
            return;
        }
        double hours = plugin.getConfig().getDouble("cards.event-duration-hours", DEFAULT_DURATION_HOURS);
        if (hours <= 0) {
            plugin.getLogger().info("Entity Card Hunt has no end set — it stays open until one is.");
            return;
        }
        store.eventEndsAt(System.currentTimeMillis() + (long) (hours * 3_600_000L));
        store.save();
        plugin.getLogger().info("Entity Card Hunt ends at " + Planets.formatDate(store.eventEndsAt())
                + " (" + remainingShort() + " left).");
    }

    /** Whether cards can still drop right now. */
    boolean isActive() {
        long end = store.eventEndsAt();
        return end <= 0 || System.currentTimeMillis() < end;
    }

    /** Whether an end has been set at all. */
    boolean hasEnd() {
        return store.eventEndsAt() > 0;
    }

    /** Time left in ms, or -1 when the event is open-ended. */
    long remainingMillis() {
        long end = store.eventEndsAt();
        return end <= 0 ? -1 : Math.max(0, end - System.currentTimeMillis());
    }

    /** The end timestamp the whole event hangs off. */
    long endsAt() {
        return store.eventEndsAt();
    }

    /** Sets a new end, {@code hours} from now (the admin command). */
    void setDurationHours(double hours) {
        long millis = Math.max(1L, (long) (hours * 3_600_000L));
        store.eventEndsAt(System.currentTimeMillis() + millis);
        store.save();
    }

    /** Sets the end to an explicit timestamp (used when restoring a saved one). */
    void setEndsAt(long millis) {
        store.eventEndsAt(millis);
        store.save();
    }

    /** "13 Days", "7 Hours", "42 Minutes" — one line per unit, biggest first. */
    List<String> remainingParts() {
        long end = store.eventEndsAt();
        if (end <= 0) {
            return List.of("No end set", "Open-ended event");
        }
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            return List.of("EVENT ENDED");
        }
        long days = left / 86_400_000L;
        long hours = (left / 3_600_000L) % 24L;
        long minutes = (left / 60_000L) % 60L;
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + (days == 1 ? " Day" : " Days"));
        }
        if (days > 0 || hours > 0) {
            parts.add(hours + (hours == 1 ? " Hour" : " Hours"));
        }
        parts.add(minutes + (minutes == 1 ? " Minute" : " Minutes"));
        return parts;
    }

    /** A one-line version of the countdown, for lore and placeholders. */
    String remainingShort() {
        long end = store.eventEndsAt();
        if (end <= 0) {
            return "open-ended";
        }
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            return "ended";
        }
        long days = left / 86_400_000L;
        long hours = (left / 3_600_000L) % 24L;
        long minutes = (left / 60_000L) % 60L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    // ── Drops ───────────────────────────────────────────────────────────

    /**
     * Rolls every card that belongs to a killed entity, once each and
     * independently.
     *
     * <p>Each card is rolled at its own configured odds. If bad-luck protection
     * is switched on, a long run of failures for that particular card nudges
     * the odds up — the streak is counted per card, so hunting one stubborn mob
     * never improves the odds on a different one.
     *
     * <p>A card the player has already maxed out is rolled at plain odds and
     * keeps no streak: there is nothing left to earn from it.
     *
     * @return true when at least one copy was added
     */
    boolean handleKill(Player killer, EntityType type) {
        if (killer == null || type == null || !isActive()) {
            return false;
        }
        List<CardDefinition> candidates = registry.forEntity(type);
        if (candidates.isEmpty()) {
            return false;
        }
        // Keep the stored name current so the leaderboard can show offline
        // collectors without a second lookup.
        UUID uuid = killer.getUniqueId();
        store.rememberName(uuid, killer.getName());
        boolean gained = false;
        for (CardDefinition card : candidates) {
            if (!card.drops()) {
                continue; // the Ender Dragon lives here
            }
            boolean maxed = store.count(uuid, card.id()) >= card.maxCopies();
            int streak = maxed ? 0 : store.streak(uuid, card.id());
            double chance = maxed ? card.chance() : luck.chance(card.chance(), streak);
            boolean hit = (!maxed && luck.guaranteed(streak))
                    || ThreadLocalRandom.current().nextDouble() < chance;
            if (!hit) {
                // Nothing won, so the dry streak grows — but only for a card
                // that still has something to give.
                if (!maxed) {
                    store.missStreak(uuid, card.id());
                }
                continue;
            }
            switch (store.give(uuid, card)) {
                case NEW -> {
                    store.clearStreak(uuid, card.id());
                    announce(killer, card);
                    gained = true;
                }
                case DUPLICATE -> {
                    store.clearStreak(uuid, card.id());
                    duplicate(killer, card);
                    gained = true;
                }
                case CAPPED -> capped(killer, card);
                case UNKNOWN -> {
                    // Not a card this server knows; nothing to do.
                }
            }
        }
        if (gained) {
            invalidateLeaderboard();
        }
        return gained;
    }

    /**
     * The odds a card is really rolled at for this player right now, which is
     * its configured chance unless bad-luck protection has started to help.
     */
    double effectiveChance(UUID uuid, CardDefinition card) {
        if (card == null) {
            return 0;
        }
        if (uuid == null || !luck.enabled()) {
            return card.chance();
        }
        return luck.chance(card.chance(), store.streak(uuid, card.id()));
    }

    /** How many kills in a row have failed to drop this card for this player. */
    int streak(UUID uuid, CardDefinition card) {
        if (uuid == null || card == null || !luck.enabled()) {
            return 0;
        }
        return store.streak(uuid, card.id());
    }

    /** The full celebration, only ever for a card the player did not have. */
    private void announce(Player player, CardDefinition card) {
        UUID uuid = player.getUniqueId();
        int unique = store.unique(uuid);
        int total = registry.size();
        int copies = store.copies(uuid);
        int held = store.count(uuid, card.id());
        CardDiscoveryMessages.Discovery discovery = messages.pick();

        if (discovery.tier().banner() != null) {
            player.sendMessage(Component.text(discovery.tier().banner())
                    .color(discovery.tier().color())
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));
        }
        player.sendMessage(Component.text("\uD83C\uDCCF NEW CARD \u2014 ").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(card.displayName()).color(card.rarity().color())
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("  " + card.rarity().label()).color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        player.sendMessage(Component.text(discovery.message()).color(discovery.tier().color())
                .decoration(TextDecoration.ITALIC, false));
        player.sendMessage(Component.text("Unique Cards: ").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(unique + " / " + total).color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("   Total Copies: ").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text(String.valueOf(copies)).color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("   \u25B6 Open /cards").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                        .clickEvent(ClickEvent.runCommand("/cards"))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Open your collection").color(NamedTextColor.AQUA)))));
        if (held >= card.maxCopies()) {
            player.sendMessage(Component.text("\uD83C\uDCCF That card is now maxed out ("
                            + card.maxCopies() + "x).").color(NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
        }

        Sound sound = switch (discovery.tier()) {
            case ULTRA_RARE -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
            case RARE -> Sound.ENTITY_FIREWORK_ROCKET_TWINKLE;
            case NORMAL -> Sound.ENTITY_PLAYER_LEVELUP;
        };
        player.playSound(player.getLocation(), sound, 0.9f, 1.4f);
        plugin.sendPriorityBar(player, Component.text("\uD83C\uDCCF NEW: ")
                .color(NamedTextColor.GOLD)
                .append(Component.text(card.displayName()).color(card.rarity().color()))
                .append(Component.text("  (" + unique + "/" + total + ")")
                        .color(NamedTextColor.GRAY)));
    }

    /** A quiet line for another copy of a card they already had. */
    private void duplicate(Player player, CardDefinition card) {
        int held = store.count(player.getUniqueId(), card.id());
        plugin.sendPriorityBar(player, Component.text("\uD83C\uDCCF +1 ")
                .color(NamedTextColor.AQUA)
                .append(Component.text(card.displayName()).color(card.rarity().color()))
                .append(Component.text("  (" + held + "/" + card.maxCopies() + "x)")
                        .color(NamedTextColor.GRAY)));
    }

    /** Told once when a card is already at the copy cap. */
    private void capped(Player player, CardDefinition card) {
        plugin.sendPriorityBar(player, Component.text("\uD83C\uDCCF ")
                .color(NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(card.displayName()).color(card.rarity().color()))
                .append(Component.text(" is already at " + card.maxCopies() + "x")
                        .color(NamedTextColor.GRAY)));
    }

    // ── Collection reads ────────────────────────────────────────────────

    /** Unique cards held — the only thing the leaderboard ranks on. */
    int unique(UUID uuid) {
        return store.unique(uuid);
    }

    /** Every copy of every card, added together. */
    int copies(UUID uuid) {
        return store.copies(uuid);
    }

    /** How many cards exist in total (the denominator everywhere). */
    int total() {
        return registry.size();
    }

    int count(UUID uuid, String cardId) {
        return store.count(uuid, cardId);
    }

    boolean has(UUID uuid, String cardId) {
        return store.count(uuid, cardId) > 0;
    }

    /** "47/82" — unique cards over the full catalogue. */
    String uniqueLabel(UUID uuid) {
        return unique(uuid) + "/" + total();
    }

    // ── Leaderboard ─────────────────────────────────────────────────────

    /**
     * The ranking, biggest unique collection first. Copies never change a
     * position, which is why two players with 64 of the same cards are still
     * worth exactly one each.
     */
    List<Ranked> leaderboard() {
        long ttl = Math.max(0, plugin.getConfig()
                .getLong("cards.leaderboard-refresh-seconds", 60L)) * 1000L;
        if (leaderboardBuilt && ttl > 0 && System.currentTimeMillis() - leaderboardCacheAt < ttl) {
            return leaderboardCache;
        }
        List<Ranked> ranked = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<UUID, CardStore.Record> entry : store.ranked()) {
            rank++;
            String name = entry.getValue().name();
            if (name == null || name.isBlank()) {
                name = plugin.homesOwnerName(entry.getKey());
            }
            Player online = plugin.getServer().getPlayer(entry.getKey());
            if (online != null) {
                name = online.getName();
            }
            ranked.add(new Ranked(entry.getKey(), name, entry.getValue().unique(),
                    entry.getValue().copies(), rank));
        }
        leaderboardCache = ranked;
        leaderboardCacheAt = System.currentTimeMillis();
        leaderboardBuilt = true;
        return leaderboardCache;
    }

    /** A player's position on the leaderboard, or 0 when they have no cards. */
    int positionOf(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        for (Ranked entry : leaderboard()) {
            if (entry.uuid().equals(uuid)) {
                return entry.rank();
            }
        }
        return 0;
    }

    /** How many players hold at least one card. */
    int participants() {
        return store.participants();
    }

    /** How many rows the leaderboard menu shows (config). */
    int leaderboardSize() {
        return Math.max(1, plugin.getConfig().getInt("cards.leaderboard-size", 10));
    }

    /** Drops the cached ranking so the next read is fresh. */
    void invalidateLeaderboard() {
        leaderboardBuilt = false;
        leaderboardCacheAt = 0L;
    }

    // ── Config helpers for the menus ────────────────────────────────────

    /** A configured GUI title, falling back to the shipped one. */
    String title(String key, String fallback) {
        String value = plugin.getConfig().getString("cards.gui." + key, fallback);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The label of the back button every secondary menu carries. */
    String backLabel() {
        return title("back-label", "\u2B05 Back to Card Collection");
    }

    /** The permission that gates the admin command. */
    String adminPermission() {
        String value = plugin.getConfig().getString("cards.admin-permission", "planets.cards.admin");
        return value == null || value.isBlank() ? "planets.cards.admin" : value;
    }

    /** Whether the entity behind a card may be shown before it is discovered. */
    boolean revealUndiscovered() {
        return plugin.getConfig().getBoolean("cards.reveal-undiscovered", false);
    }

    /**
     * Whether the card menus reopen where a player left them — the collection's
     * layout, category, filter and page, and the leaderboard's page — rather
     * than always starting back at the beginning.
     */
    boolean rememberFilters() {
        return plugin.getConfig().getBoolean("cards.remember-filters", true);
    }

    /** Any extra rules lines the owner added under {@code cards.info.lines}. */
    List<String> infoLines() {
        return plugin.getConfig().getStringList("cards.info.lines").stream()
                .filter(line -> line != null && !line.isBlank())
                .toList();
    }

    /** Writes the countdown out in chat (used by the clock and the command). */
    void printTimer(Player player) {
        player.sendMessage(Component.text("\u23F0 ENTITY CARD HUNT").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        for (String part : remainingParts()) {
            player.sendMessage(Component.text("  " + part)
                    .color(isActive() ? NamedTextColor.AQUA : NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        player.sendMessage(Component.text("  Unique Cards: ").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(uniqueLabel(player.getUniqueId()))
                        .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
                .append(Component.text("   Total Copies: ").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text(String.valueOf(copies(player.getUniqueId())))
                        .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)));
    }

    // ── Opening the menus ───────────────────────────────────────────────

    void openMain(Player player) {
        new CardsMenu(plugin, player, this).open(player);
    }

    void openCollection(Player player) {
        new CardCollectionMenu(plugin, player, this).open(player);
    }

    void openInfo(Player player) {
        new CardEventInfoMenu(plugin, player, this).open(player);
    }

    void openLeaderboard(Player player) {
        new CardLeaderboardMenu(plugin, player, this).open(player);
    }

    /** "1.5x" style odds text for the info page and the collection lore. */
    static String percent(double value) {
        return (value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 10) / 10.0)) + "%";
    }

    /** Lower-cases and trims a name (used for admin tab completion). */
    static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
