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


patch('src/main/java/me/foivos/planets/Planets.java', [
(
r"""    /** Whether a player may use the admin panel: op, or the explicit permission. */
    boolean canUseAdmin(Player player) {""",
r"""    /**
     * Whether a player may use the admin panel: op, or the explicit permission.
     * Public because the casino's own admin panel lives in another package and
     * has to ask exactly the same question.
     */
    public boolean canUseAdmin(Player player) {"""
),
(
r"""    /** Opens the admin lobby list (Dashboard → Lobby Admin, or a planet panel). */
    void openAdminLobbies(Player player) {""",
r"""    /** Opens the casino admin panel (Dashboard → Casino, or /casino admin). */
    public void openAdminCasino(Player player) {
        if (casino == null || !canUseAdmin(player)) {
            return;
        }
        player.closeInventory();
        casino.openAdmin(player);
    }

    /** Opens the admin lobby list (Dashboard → Lobby Admin, or a planet panel). */
    void openAdminLobbies(Player player) {"""
),
])


patch('src/main/java/me/foivos/planets/AdminOverviewMenu.java', [
(
r""" *   └─ Browse(29) ─ Lobby Admin(32) ─ Clean up(31) ─ Rescan(33) ─ Back to /planets(43) ─┘""",
r""" *   └─ Browse(29) ─ Casino(28) ─ Clean up(31) ─ Lobby Admin(32) ─ Back to /planets(43) ─┘"""
),
(
r"""    private static final int HUD_SLOT = 25;""",
r"""    private static final int HUD_SLOT = 25;
    private static final int CASINO_SLOT = 28;"""
),
(
r"""            case HUD_SLOT -> {
                plugin.openHudEditor(player);
                return;
            }""",
r"""            case HUD_SLOT -> {
                plugin.openHudEditor(player);
                return;
            }
            case CASINO_SLOT -> {
                plugin.openAdminCasino(player);
                return;
            }"""
),
(
r"""        inventory.setItem(BROWSE_SLOT, actionItem(Material.CHEST, "Browse Planets",""",
r"""        inventory.setItem(CASINO_SLOT, actionItem(Material.GOLD_INGOT, "\uD83C\uDFB0 Casino",
                "Every game: switch it on or off, move its slot, edit its icon",
                "The casino open or closed, the cooldown, prize display",
                "The prize pool: tiers and odds",
                "Every click is written to config.yml at once"));
        inventory.setItem(BROWSE_SLOT, actionItem(Material.CHEST, "Browse Planets","""
),
])


patch('src/main/java/me/foivos/planets/casino/CasinoCommand.java', [
(
r"""            if (sub.equals("reload")) {
                return reload(sender);
            }""",
r"""            if (sub.equals("reload")) {
                return reload(sender);
            }
            if (sub.equals("admin") || sub.equals("manage")) {
                return admin(sender);
            }"""
),
(
r"""    private boolean canManage(CommandSender sender) {""",
r"""    /**
     * {@code /casino admin} — the panel that runs the casino: every game
     * switched on or off, moved about the hub, and the pool edited, all
     * written straight to config.yml.
     */
    private boolean admin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the casino panel.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!canManage(sender)) {
            noPermission(player);
            return true;
        }
        casino.plugin().openAdminCasino(player);
        return true;
    }

    private boolean canManage(CommandSender sender) {"""
),
(
r"""        line(sender, "/casino reload", "re-read the casino section of config.yml");""",
r"""        line(sender, "/casino reload", "re-read the casino section of config.yml");
        line(sender, "/casino admin", "games on and off, slots, icons, the pool");"""
),
(
r"""        List<String> options = new ArrayList<>();
        if (sender.hasPermission(RELOAD) || sender.hasPermission(ADMIN)) {
            options.add("reload");
        }""",
r"""        List<String> options = new ArrayList<>();
        if (sender.hasPermission(RELOAD) || sender.hasPermission(ADMIN)) {
            options.add("reload");
            options.add("admin");
        }"""
),
])


patch('src/main/java/me/foivos/planets/casino/CasinoMenu.java', [
(
r"""        lore.add(casino().gamesAvailable() + " games open \u00B7 every round is free");
        return MenuStyle.header("Casino", NamedTextColor.GOLD, lore);""",
r"""        lore.add(casino().gamesAvailable() + " games open \u00B7 every round is free");
        CasinoReward trophy = casino().displayReward(viewerId());
        if (trophy != null) {
            lore.add("\uD83C\uDF96 " + trophy.name() + " on display");
        }
        return MenuStyle.header("Casino", NamedTextColor.GOLD, lore);"""
),
])


patch('src/main/java/me/foivos/planets/casino/CasinoStatsMenu.java', [
(
r"""import java.util.ArrayList;
import java.util.List;
import java.util.Map;""",
r"""import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;"""
),
(
r"""    /** False draws the record, true the prize cabinet. */
    private boolean cabinetPage;""",
r"""    /** False draws the record, true the prize cabinet. */
    private boolean cabinetPage;
    /** Which prize each cabinet slot holds, so a click knows what it picked. */
    private final Map<Integer, String> rewardAtSlot = new HashMap<>();"""
),
(
r"""        return MenuStyle.header(cabinetPage ? "Prize Cabinet" : "Your Record",
                cabinetPage ? NamedTextColor.GOLD : NamedTextColor.AQUA,
                List.of(stats.name().isBlank() ? "Casino regular" : stats.name(),
                        stats.played() + " rounds \u00B7 " + stats.totalRewards() + " prizes"));""",
r"""        List<String> lines = new ArrayList<>();
        lines.add(stats.name().isBlank() ? "Casino regular" : stats.name());
        lines.add(stats.played() + " rounds \u00B7 " + stats.totalRewards() + " prizes");
        CasinoReward trophy = casino().displayReward(viewerId());
        lines.add(trophy == null
                ? "Nothing on display \u2014 click a prize in the cabinet"
                : "Displaying " + trophy.name() + " (" + trophy.rarity().label() + ")");
        return MenuStyle.header(cabinetPage ? "Prize Cabinet" : "Your Record",
                cabinetPage ? NamedTextColor.GOLD : NamedTextColor.AQUA, lines);"""
),
(
r"""    private void drawCabinet() {
        Map<CasinoReward, Integer> cabinet = casino().cabinet(viewerId());
        int slot = 0;""",
r"""    private void drawCabinet() {
        rewardAtSlot.clear();
        Map<CasinoReward, Integer> cabinet = casino().cabinet(viewerId());
        int slot = 0;"""
),
(
r"""            lore.add(grey(""));
            lore.add(owned > 0
                    ? CasinoText.legacy("&aCollected \u00D7" + owned, NamedTextColor.GREEN)
                    : grey("Not collected yet"));
            if (!reward.rollable()) {
                lore.add(grey("No longer in the pool"));
            }
            set(CABINET_SLOTS[slot++], item(reward.icon(),
                    CasinoText.legacy(reward.name(), owned > 0
                            ? reward.rarity().colour() : NamedTextColor.DARK_GRAY),
                    lore));
        }
    }""",
r"""            lore.add(grey(""));
            boolean shown = reward.id().equalsIgnoreCase(stats().display());
            lore.add(owned > 0
                    ? CasinoText.legacy("&aCollected \u00D7" + owned, NamedTextColor.GREEN)
                    : grey("Not collected yet"));
            if (!reward.rollable()) {
                lore.add(grey("No longer in the pool"));
            }
            if (shown) {
                lore.add(CasinoText.legacy("&6\uD83C\uDF96 On display", NamedTextColor.GOLD));
            }
            if (owned > 0 && casino().cosmeticsEnabled()) {
                lore.add(grey(""));
                lore.add(shown
                        ? CasinoText.legacy("&e\u2794 Click to put it away", NamedTextColor.YELLOW)
                        : CasinoText.legacy("&e\u2794 Click to put it on display", NamedTextColor.YELLOW));
            }
            int at = CABINET_SLOTS[slot++];
            rewardAtSlot.put(at, reward.id());
            set(at, item(reward.icon(),
                    CasinoText.legacy(reward.name(), owned > 0
                            ? reward.rarity().colour() : NamedTextColor.DARK_GRAY),
                    lore));
        }
    }"""
),
(
r"""        set(PRIZES_SLOT, statItem(Material.CHEST, "\uD83C\uDF81 Prizes Collected",
                List.of(grey(stats.totalRewards() + " in total"),
                        grey(stats.rewards().size() + " different ones"),
                        grey("Nothing here is tradeable"))));""",
r"""        List<Component> prizeLore = new ArrayList<>();
        prizeLore.add(grey(stats.totalRewards() + " in total"));
        prizeLore.add(grey(stats.rewards().size() + " different ones"));
        prizeLore.add(grey("Nothing here is tradeable"));
        CasinoReward trophy = casino().displayReward(viewerId());
        prizeLore.add(grey(""));
        prizeLore.add(trophy == null
                ? grey("Nothing on display \u2014 open the cabinet")
                : CasinoText.legacy("&6\uD83C\uDF96 " + trophy.name(),
                        trophy.rarity().colour()));
        set(PRIZES_SLOT, statItem(Material.CHEST, "\uD83C\uDF81 Prizes Collected", prizeLore));"""
),
(
r"""        switch (slot) {
            case CLOSE_SLOT -> player.closeInventory();
            case BACK_SLOT -> {""",
r"""        if (cabinetPage && rewardAtSlot.containsKey(slot)) {
            if (casino().toggleDisplay(player, rewardAtSlot.get(slot))) {
                render();
            } else {
                CasinoFeedback.deny(player);
            }
            return;
        }
        switch (slot) {
            case CLOSE_SLOT -> player.closeInventory();
            case BACK_SLOT -> {"""
),
])
