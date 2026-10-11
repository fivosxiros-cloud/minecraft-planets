import io

def patch(path, edits):
    raw = io.open(path, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    for old, new in edits:
        o, n = old.replace('\n', nl), new.replace('\n', nl)
        assert raw.count(o) == 1, (path, raw.count(o), old[:90])
        raw = raw.replace(o, n)
    io.open(path, 'w', encoding='utf-8', newline='').write(raw)
    print('patched', path, '(%s)' % ('CRLF' if nl == '\r\n' else 'LF'))


patch('src/main/java/me/foivos/planets/casino/CasinoStats.java', [
(
r"""    private static final String ACHIEVEMENTS = "achievements";""",
r"""    private static final String ACHIEVEMENTS = "achievements";
    private static final String DISPLAY = "display";"""
),
(
r"""    private final Set<String> achievements = new LinkedHashSet<>();""",
r"""    private final Set<String> achievements = new LinkedHashSet<>();
    private String display = "";"""
),
(
r"""    /** The achievements the player has unlocked, by id. */""",
r"""    /**
     * The id of the prize this player has put on display, or "" for none. A
     * displayed prize is a trophy: it follows its owner into the hub, the
     * record and the cabinet, and it is the one thing a collected prize can be
     * <i>used</i> for — nothing is handed over and nothing is tradeable.
     */
    public String display() {
        return display;
    }

    /** Puts a prize on display, or takes it off with a blank id. */
    void display(String rewardId) {
        this.display = rewardId == null ? "" : rewardId;
    }

    /** The achievements the player has unlocked, by id. */"""
),
(
r"""        achievements.addAll(section.getStringList(ACHIEVEMENTS));""",
r"""        achievements.addAll(section.getStringList(ACHIEVEMENTS));
        display = section.getString(DISPLAY, "");"""
),
(
r"""        if (!achievements.isEmpty()) {
            section.set(ACHIEVEMENTS, new java.util.ArrayList<>(achievements));
        }""",
r"""        if (!achievements.isEmpty()) {
            section.set(ACHIEVEMENTS, new java.util.ArrayList<>(achievements));
        }
        // Written even when blank, so taking a prize off display is remembered
        // rather than coming back on the next restart.
        section.set(DISPLAY, display == null || display.isBlank() ? null : display);"""
),
])


patch('src/main/java/me/foivos/planets/casino/CasinoManager.java', [
(
r"""import me.foivos.planets.Planets;
import me.foivos.planets.casino.games.RpsGame;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;""",
r"""import me.foivos.planets.Planets;
import me.foivos.planets.casino.games.RpsGame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;"""
),
(
r"""    private boolean hideDisabled;
    private int cooldownSeconds;""",
r"""    private boolean hideDisabled;
    private int cooldownSeconds;
    private boolean cosmeticsEnabled;
    private int showOffRadius;"""
),
(
r"""        hideDisabled = casino.getBoolean("menu.hide-disabled", false);""",
r"""        hideDisabled = casino.getBoolean("menu.hide-disabled", false);
        cosmeticsEnabled = casino.getBoolean("cosmetics.enabled", true);
        showOffRadius = Math.max(0, Math.min(128, casino.getInt("cosmetics.show-off-radius", 24)));"""
),
(
r"""        if (!casino.isConfigurationSection("messages")) {
            ConfigurationSection messages = casino.createSection("messages");
            for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
                messages.set(entry.getKey(), entry.getValue());
            }
            written = true;
        }
        return written;""",
r"""        if (!casino.isConfigurationSection("messages")) {
            ConfigurationSection messages = casino.createSection("messages");
            for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
                messages.set(entry.getKey(), entry.getValue());
            }
            written = true;
        }
        if (!casino.isConfigurationSection("cosmetics")) {
            // Prizes have always been keepsakes; this is the switch that lets a
            // player put one on display, and how far away that is announced.
            ConfigurationSection cosmetics = casino.createSection("cosmetics");
            cosmetics.set("enabled", true);
            cosmetics.set("show-off-radius", 24);
            written = true;
        }
        return written;"""
),
(
r"""        messages.put("info-intro", "&8\u2500\u2500 &6\uD83C\uDFB0 Casino &8\u2500\u2500");""",
r"""        messages.put("cosmetic-none", "&7You haven't collected &f%prize%&7 yet.");
        messages.put("cosmetic-off", "&7Prize display is switched off right now.");
        messages.put("cosmetic-on", "&7\uD83C\uDF96 Displaying &f%prize%&7 \u2014 click it again to put it away.");
        messages.put("cosmetic-cleared", "&7\uD83C\uDF96 You put your prize away.");
        messages.put("info-intro", "&8\u2500\u2500 &6\uD83C\uDFB0 Casino &8\u2500\u2500");"""
),
(
r"""    public void openStats(Player player) {
        if (player == null) {
            return;
        }
        new CasinoStatsMenu(plugin, this, player).open(player);
    }""",
r"""    public void openStats(Player player) {
        if (player == null) {
            return;
        }
        new CasinoStatsMenu(plugin, this, player).open(player);
    }

    /** Opens the casino's own admin panel: the games, the layout and the pool. */
    public void openAdmin(Player player) {
        if (player == null) {
            return;
        }
        new AdminCasinoMenu(plugin, this, player).open(player);
    }"""
),
(
r"""    // ── Lifecycle ───────────────────────────────────────────────────────""",
r"""    // ── Running the casino from the admin panel ─────────────────────────
    //
    // Everything here writes config.yml and then re-reads it, so an edit made
    // in the panel is in force on the very next click: what the hub draws and
    // what the file says never drift apart. Each method answers whether it
    // changed anything, so the panel can report a refusal honestly instead of
    // pretending a click worked.

    /** Whether the casino is open for play at all ({@code casino.enabled}). */
    public boolean isOpen() {
        return enabled;
    }

    /** Closes or opens the whole casino. */
    public boolean setOpen(boolean open) {
        if (open == enabled) {
            return false;
        }
        section().set("enabled", open);
        persist();
        return true;
    }

    /** Whether collected prizes may be put on display ({@code casino.cosmetics}). */
    public boolean cosmeticsEnabled() {
        return cosmeticsEnabled;
    }

    public boolean setCosmeticsEnabled(boolean allowed) {
        if (allowed == cosmeticsEnabled) {
            return false;
        }
        section().set("cosmetics.enabled", allowed);
        persist();
        return true;
    }

    /** How far away a prize going on display is announced, in blocks. */
    public int showOffRadius() {
        return showOffRadius;
    }

    public boolean nudgeShowOffRadius(int step) {
        int wanted = Math.max(0, Math.min(128, showOffRadius + step));
        if (wanted == showOffRadius) {
            return false;
        }
        section().set("cosmetics.show-off-radius", wanted);
        persist();
        return true;
    }

    /** How long a player must wait between two rounds of one game. */
    public boolean nudgeCooldown(int step) {
        int wanted = Math.max(0, Math.min(600, cooldownSeconds + step));
        if (wanted == cooldownSeconds) {
            return false;
        }
        section().set("cooldown-seconds", wanted);
        persist();
        return true;
    }

    public boolean setHideDisabled(boolean hide) {
        if (hide == hideDisabled) {
            return false;
        }
        section().set("menu.hide-disabled", hide);
        persist();
        return true;
    }

    /** Switches one game on or off for everyone. */
    public boolean setGameEnabled(String id, boolean on) {
        Entry entry = entries.get(id);
        if (entry == null || entry.section() == null || entry.enabled() == on) {
            return false;
        }
        entry.section().set("enabled", on);
        persist();
        return true;
    }

    /** Switches every game on or off at once. */
    public boolean setEveryGameEnabled(boolean on) {
        boolean changed = false;
        for (Entry entry : entries.values()) {
            if (entry.section() != null && entry.enabled() != on) {
                entry.section().set("enabled", on);
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
        return changed;
    }

    /**
     * Moves a game to another slot of the hub. A game already sitting in the
     * target slot takes the mover's old slot, so a move is always a swap and
     * two games can never end up stacked on one another.
     */
    public boolean setGameSlot(String id, int slot) {
        Entry entry = entries.get(id);
        if (entry == null || entry.section() == null || slot < 0 || slot >= size
                || slot == entry.slot()) {
            return false;
        }
        int was = entry.slot();
        entry.section().set("slot", slot);
        Entry other = entryAt(slot);
        if (other != null && other.section() != null) {
            other.section().set("slot", was);
        }
        persist();
        return true;
    }

    /** The game drawn in a slot, or null when nothing sits there. */
    public Entry entryAt(int slot) {
        for (Entry entry : entries.values()) {
            if (entry.slot() == slot) {
                return entry;
            }
        }
        return null;
    }

    /** The icons the admin panel steps through, so nobody has to type a name. */
    private static final List<Material> ICON_PALETTE = List.of(
            Material.GOLD_NUGGET, Material.BONE, Material.SLIME_BALL, Material.COMPASS,
            Material.PAPER, Material.CHEST, Material.SUNFLOWER, Material.ENDER_EYE,
            Material.KNOWLEDGE_BOOK, Material.STONE, Material.ENCHANTING_TABLE,
            Material.SADDLE, Material.RED_CONCRETE, Material.DIAMOND, Material.NETHER_STAR,
            Material.TOTEM_OF_UNDYING, Material.ECHO_SHARD, Material.HEART_OF_THE_SEA,
            Material.LILY_OF_THE_VALLEY, Material.IRON_NUGGET, Material.OAK_SAPLING,
            Material.CLOCK, Material.WRITABLE_BOOK, Material.FIREWORK_ROCKET);

    /** The palette the panel cycles a game's icon through. */
    public static List<Material> iconPalette() {
        return ICON_PALETTE;
    }

    /** Steps a game's icon on to the next material of the palette. */
    public boolean cycleGameIcon(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.section() == null) {
            return false;
        }
        Material next = ICON_PALETTE.get((ICON_PALETTE.indexOf(entry.icon()) + 1) % ICON_PALETTE.size());
        if (next == entry.icon()) {
            return false;
        }
        entry.section().set("icon", next.name());
        persist();
        return true;
    }

    /** Puts a game's icon, name and description back to what this jar ships. */
    public boolean resetGameAppearance(String id) {
        Entry entry = entries.get(id);
        CasinoDefaults.GameDefault def = CasinoDefaults.game(id);
        if (entry == null || entry.section() == null || def == null) {
            return false;
        }
        CasinoDefaults.writeGame(entry.section(), def);
        entry.section().set("slot", entry.slot());     // keep where the admin put it
        entry.section().set("enabled", entry.enabled());
        persist();
        return true;
    }

    /** Puts every shipped game back on the slot this jar ships it with. */
    public boolean resetLayout() {
        boolean changed = false;
        for (Entry entry : entries.values()) {
            CasinoDefaults.GameDefault def = CasinoDefaults.game(entry.id());
            if (def == null || entry.section() == null || entry.slot() == def.slot()) {
                continue;
            }
            entry.section().set("slot", def.slot());
            changed = true;
        }
        if (changed) {
            persist();
        }
        return changed;
    }

    /** Sets how likely one prize is to come out of a roll (0 = never again). */
    public boolean setPrizeWeight(String id, int weight) {
        CasinoReward reward = rewards.byId(id);
        int wanted = Math.max(0, Math.min(1000, weight));
        if (reward == null || wanted == reward.weight()) {
            return false;
        }
        ConfigurationSection entry = prizeSection(id);
        if (entry == null) {
            return false;
        }
        entry.set("weight", wanted);
        persist();
        return true;
    }

    /** Sets the tier a prize is announced under. */
    public boolean setPrizeRarity(String id, CasinoRarity rarity) {
        CasinoReward reward = rewards.byId(id);
        if (reward == null || rarity == null || reward.rarity() == rarity) {
            return false;
        }
        ConfigurationSection entry = prizeSection(id);
        if (entry == null) {
            return false;
        }
        entry.set("rarity", rarity.name().toLowerCase(java.util.Locale.ROOT));
        persist();
        return true;
    }

    private ConfigurationSection prizeSection(String id) {
        if (root == null || id == null) {
            return null;
        }
        ConfigurationSection pool = root.getConfigurationSection("rewards");
        return pool != null && pool.isConfigurationSection(id)
                ? pool.getConfigurationSection(id) : null;
    }

    /** Writes config.yml and reads it back, so an edit shows up at once. */
    private void persist() {
        plugin.saveConfigQuietly();
        configure();
    }

    // ── Trophies: what a collected prize is actually for ────────────────

    /** The prize a player has on display, or null when they display none. */
    public CasinoReward displayReward(UUID uuid) {
        CasinoStats stats = store.get(uuid);
        return stats.display().isBlank() ? null : rewards.byId(stats.display());
    }

    /**
     * Puts a collected prize on display, or takes it off when it is already
     * the one displayed.
     *
     * <p>This is the whole use of a casino cosmetic: nothing is handed over and
     * nothing can be traded — a prize on display follows its owner into the
     * hub, the record and the cabinet, and is shown off to whoever is standing
     * nearby when it goes on.
     *
     * @return false when the prize has not been collected (or display is off),
     *         in which case the player has already been told why
     */
    public boolean toggleDisplay(Player player, String rewardId) {
        if (player == null || rewardId == null) {
            return false;
        }
        CasinoReward reward = rewards.byId(rewardId);
        if (reward == null) {
            return false;
        }
        if (!cosmeticsEnabled) {
            deny(player, "cosmetic-off", DEFAULT_MESSAGES.get("cosmetic-off"));
            return false;
        }
        CasinoStats stats = store.get(player.getUniqueId());
        if (stats.rewardCount(reward.id()) <= 0) {
            deny(player, "cosmetic-none", DEFAULT_MESSAGES.get("cosmetic-none"),
                    "%prize%", reward.name());
            return false;
        }
        if (reward.id().equalsIgnoreCase(stats.display())) {
            stats.display("");
            store.markDirty();
            notice(player, "cosmetic-cleared", DEFAULT_MESSAGES.get("cosmetic-cleared"));
            return true;
        }
        stats.display(reward.id());
        store.markDirty();
        notice(player, "cosmetic-on", DEFAULT_MESSAGES.get("cosmetic-on"),
                "%prize%", reward.name());
        showOff(player, reward);
        return true;
    }

    /** Announces a prize going on display to everyone standing close enough. */
    private void showOff(Player player, CasinoReward reward) {
        CasinoFeedback.prize(player, reward.rarity());
        if (showOffRadius <= 0) {
            return;
        }
        Component line = Component.text("[\uD83C\uDF96] ").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(player.getName() + " shows off their ")
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .append(reward.displayName());
        Location at = player.getLocation();
        double range = (double) showOffRadius * showOffRadius;
        for (Player nearby : player.getWorld().getPlayers()) {
            if (!nearby.equals(player) && nearby.getLocation().distanceSquared(at) <= range) {
                nearby.sendMessage(line);
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────"""
),
])
