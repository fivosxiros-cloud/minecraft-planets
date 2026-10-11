package me.foivos.planets.casino;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.Planets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The casino as the server owner sees it: three pages, opened from
 * {@code /casino admin} and from the Casino button on the admin dashboard.
 *
 * <pre>
 *   ┌────────────── Casino Admin: Games (4) ──────────────┐
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣        every game where the HUB draws it,
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣        so the page and the hub read the same
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   └─ Games ─ Layout ─ Prizes ─ Open hub ─ Close ─┘
 * </pre>
 *
 * <ul>
 *   <li><b>Games</b> — every registered game at the slot the hub gives it.
 *       Left-click switches it on or off for the whole server. Right-click
 *       picks it up: the free slots light up and the next click drops it
 *       there, swapping with whatever is already in the target slot. Shift-click
 *       steps its icon on, shift-right-click puts its shipped icon and name
 *       back.</li>
 *   <li><b>Layout</b> — the switches that are not about one game: the whole
 *       casino open or closed, the cooldown, whether switched-off games are
 *       hidden from the hub, prize display (the one thing a collected prize can
 *       be used for), how far that is announced, enabling or disabling every
 *       game at once, and putting the layout back the way this jar ships
 *       it.</li>
 *   <li><b>Prizes</b> — the pool: each prize's tier and its weight, which is
 *       its odds. Left-click moves a prize up a tier, right-click makes it more
 *       likely and shift-right-click less likely. Weight 0 keeps a prize in the
 *       cabinet but out of every roll.</li>
 * </ul>
 *
 * <p>Every click writes {@code config.yml} and reads it straight back, so the
 * file and the running casino agree from the moment the click lands: nothing
 * here needs a restart, and nothing here edits the file behind the server's
 * back.
 */
public final class AdminCasinoMenu extends CasinoScreenBase {

    private static final int SIZE = 54;
    private static final int HEADER_SLOT = 4;

    /** Which page is being drawn. */
    private static final int GAMES = 0;
    private static final int LAYOUT = 1;
    private static final int PRIZES = 2;

    private static final int GAMES_TAB = 45;
    private static final int LAYOUT_TAB = 46;
    private static final int PRIZES_TAB = 47;
    private static final int OPEN_HUB_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private static final int OPEN_SLOT = 19;
    private static final int COOLDOWN_SLOT = 20;
    private static final int HIDE_SLOT = 21;
    private static final int COSMETICS_SLOT = 22;
    private static final int SHOW_OFF_SLOT = 23;
    private static final int ENABLE_ALL_SLOT = 24;
    private static final int DISABLE_ALL_SLOT = 25;
    private static final int RELOAD_SLOT = 30;
    private static final int RESET_LAYOUT_SLOT = 31;

    /** The slots the panel's own controls sit in, which no game may be drawn over. */
    private static final Set<Integer> CONTROL_SLOTS =
            Set.of(GAMES_TAB, LAYOUT_TAB, PRIZES_TAB, OPEN_HUB_SLOT, CLOSE_SLOT);

    /** The slots a game may be dropped into when it is being moved. */
    private static final int[] FIELD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    /** Where the prizes are listed on the third page. */
    private static final int[] PRIZE_SLOTS = FIELD_SLOTS;

    private final Map<Integer, String> gameAtSlot = new HashMap<>();
    private final Map<Integer, String> prizeAtSlot = new HashMap<>();

    private int page = GAMES;
    /** The game picked up for moving, or null when nothing is being moved. */
    private String moving;

    public AdminCasinoMenu(Planets plugin, CasinoManager casino, Player viewer) {
        super(plugin, casino, viewer, SIZE,
                CasinoText.legacy("&8\u2699 " + CasinoText.plain(casino.title()) + " \u00B7 Admin",
                        NamedTextColor.DARK_AQUA));
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    protected void render() {
        inventory().clear();
        gameAtSlot.clear();
        prizeAtSlot.clear();
        MenuStyle.decorate(inventory(), "\uD83C\uDFB0 Games", "\u2699 Admin");
        if (inventory().getSize() > HEADER_SLOT) {
            inventory().setItem(HEADER_SLOT, header());
        }
        switch (page) {
            case LAYOUT -> renderLayout();
            case PRIZES -> renderPrizes();
            default -> renderGames();
        }
        // The panel's own controls are drawn last, so nothing can cover them.
        inventory().setItem(GAMES_TAB, MenuStyle.tab(Material.CHEST, "Games", page == GAMES,
                "Every game, at its hub slot",
                "Switch them on and off, move them"));
        inventory().setItem(LAYOUT_TAB, MenuStyle.tab(Material.COMPARATOR, "Layout & rules",
                page == LAYOUT, "Open or closed, cooldown, cosmetics",
                "Bulk switches for every game"));
        inventory().setItem(PRIZES_TAB, MenuStyle.tab(Material.DIAMOND, "Prizes", page == PRIZES,
                "The pool, its tiers and its odds",
                "What a finished round can hand out"));
        inventory().setItem(OPEN_HUB_SLOT, item(Material.COMPASS,
                CasinoText.legacy("&b\uD83C\uDFB0 Open the hub", NamedTextColor.AQUA),
                List.of(grey("See the layout as a player does"),
                        grey("Opens " + CasinoText.plain(casino().title())))));
        inventory().setItem(CLOSE_SLOT, closeButton());
    }

    private ItemStack header() {
        List<String> lore = new ArrayList<>();
        lore.add("Casino is " + (casino().isOpen() ? "open" : "closed")
                + " \u00B7 " + casino().gamesAvailable() + " of " + casino().entries().size()
                + " games playable");
        lore.add("Hub: " + casino().size() + " slots \u00B7 cooldown "
                + casino().cooldownSeconds() + "s \u00B7 prize display "
                + (casino().cosmeticsEnabled() ? "on" : "off"));
        switch (page) {
            case LAYOUT -> {
                lore.add("");
                lore.add("Everything here writes config.yml, under casino.");
                lore.add("A click is in force at once \u2014 no reload, no restart.");
            }
            case PRIZES -> {
                lore.add("");
                lore.add("A prize's weight is its odds: bigger is likelier.");
                lore.add(rollablePrizes() + " of " + casino().prizes().size()
                        + " prizes can come out of a roll.");
            }
            default -> {
                lore.add("");
                if (moving != null) {
                    lore.add("Moving " + name(moving) + " \u2014 click a light slot to drop it there.");
                } else {
                    lore.add("Left-click a game to switch it on or off for everyone.");
                    lore.add("Right-click to pick it up, then click where it should sit.");
                }
                lore.add("Shift-click steps the icon on; shift-right restores it.");
            }
        }
        return MenuStyle.header("Casino Admin",
                page == GAMES ? NamedTextColor.AQUA : NamedTextColor.GOLD, lore);
    }

    // ── Page one: the games ─────────────────────────────────────────────

    private void renderGames() {
        Set<Integer> used = new HashSet<>(CONTROL_SLOTS);
        List<CasinoManager.Entry> homeless = new ArrayList<>();
        for (CasinoManager.Entry entry : casino().entries()) {
            int slot = entry.slot();
            if (slot < 0 || slot >= SIZE || CONTROL_SLOTS.contains(slot)) {
                // A slot outside the panel, or one of its own buttons: the game
                // is put in the first free field slot so it stays clickable and
                // the admin can move it somewhere legal.
                homeless.add(entry);
                continue;
            }
            drawGame(slot, entry);
            used.add(slot);
        }
        for (CasinoManager.Entry entry : homeless) {
            int slot = freeFieldSlot(used);
            if (slot < 0) {
                break;
            }
            drawGame(slot, entry);
            used.add(slot);
        }
        if (moving != null) {
            for (int slot : FIELD_SLOTS) {
                if (!gameAtSlot.containsKey(slot)) {
                    inventory().setItem(slot, placeMarker());
                }
            }
        }
    }

    private void drawGame(int slot, CasinoManager.Entry entry) {
        gameAtSlot.put(slot, entry.id());
        inventory().setItem(slot, gameItem(entry));
    }

    private int freeFieldSlot(Set<Integer> used) {
        for (int slot : FIELD_SLOTS) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack gameItem(CasinoManager.Entry entry) {
        List<Component> lore = new ArrayList<>();
        for (String line : entry.description()) {
            lore.add(CasinoText.legacy(line, NamedTextColor.GRAY));
        }
        lore.add(grey(""));
        lore.add(grey("Game id: " + entry.id() + " \u00B7 hub slot " + entry.slot()));
        lore.add(grey("Icon: " + entry.icon().name()
                + " \u00B7 your rounds here: " + casino().stats(viewerId()).plays(entry.id())));
        lore.add(grey("config.yml: casino.games." + entry.id()));
        lore.add(grey(""));
        if (entry.comingSoon()) {
            lore.add(CasinoText.legacy("&8\u23F3 A placeholder \u2014 no game code claims it",
                    NamedTextColor.DARK_GRAY));
            lore.add(grey("Nothing to switch on until a game registers this id"));
        } else if (entry.enabled()) {
            lore.add(CasinoText.legacy("&a\u2714 Switched on for everyone", NamedTextColor.GREEN));
        } else {
            lore.add(CasinoText.legacy("&c\u26D4 Switched off \u2014 nobody can play it",
                    NamedTextColor.RED));
        }
        lore.add(grey(""));
        if (moving != null && moving.equals(entry.id())) {
            lore.add(CasinoText.legacy("&e\u2794 Picked up \u2014 click a light slot, or this game again",
                    NamedTextColor.YELLOW));
        } else if (!entry.comingSoon()) {
            lore.add(CasinoText.legacy("&e\u2794 Left-click: switch it on or off", NamedTextColor.YELLOW));
            lore.add(grey("Right-click: pick it up and move it"));
            lore.add(grey("Shift-click: next icon \u00B7 Shift-right: shipped icon and name"));
        }
        NamedTextColor colour = entry.playable() ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY;
        String suffix = entry.comingSoon() ? " \u23F3" : entry.enabled() ? "" : " \u26D4";
        return item(entry.icon(), CasinoText.legacy(entry.name() + suffix, colour), lore);
    }

    private ItemStack placeMarker() {
        return item(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                CasinoText.legacy("&7Drop it here", NamedTextColor.GRAY),
                List.of(grey("Click to put the game in this slot"),
                        grey("A game already here swaps places")));
    }

    // ── Page two: the switches that are not about one game ──────────────

    private void renderLayout() {
        set(OPEN_SLOT, switchItem(casino().isOpen() ? Material.LEVER : Material.TRIPWIRE_HOOK,
                "Casino " + (casino().isOpen() ? "Open" : "Closed"), casino().isOpen(),
                "enabled",
                "Off: nobody can start a round of anything, whatever the games say",
                "Click to " + (casino().isOpen() ? "close the casino" : "open the casino")));
        set(COOLDOWN_SLOT, valueItem(Material.CLOCK,
                "Cooldown: " + casino().cooldownSeconds() + "s", "cooldown-seconds",
                "How long one player waits between two rounds of one game",
                "Left-click: one second less \u00B7 Right-click: one more"));
        set(HIDE_SLOT, switchItem(Material.BARRIER,
                "Hide switched-off games: " + (casino().hideDisabled() ? "yes" : "no"),
                casino().hideDisabled(), "menu.hide-disabled",
                "Yes: the hub draws only what can be played",
                "No: every game stays visible, switched-off ones greyed out"));
        set(COSMETICS_SLOT, switchItem(Material.NETHER_STAR,
                "Prize display: " + (casino().cosmeticsEnabled() ? "on" : "off"),
                casino().cosmeticsEnabled(), "cosmetics.enabled",
                "Lets a player put a collected prize on display in their cabinet",
                "It is the only thing a prize can be used for \u2014 nothing is handed over"));
        set(SHOW_OFF_SLOT, valueItem(Material.BELL,
                "Show-off radius: " + casino().showOffRadius() + " blocks",
                "cosmetics.show-off-radius",
                "How far away a prize going on display is announced",
                "Left-click: 8 blocks less \u00B7 Right-click: 8 more",
                "0 = only the owner hears about it"));
        set(ENABLE_ALL_SLOT, button(Material.LIME_DYE, "\u2714 Switch every game on",
                "Turns every registered game on at once",
                "The same as clicking each one, without the clicking"));
        set(DISABLE_ALL_SLOT, button(Material.GRAY_DYE, "\u26D4 Switch every game off",
                "Turns every registered game off at once",
                "The casino stays open, with nothing to play"));
        set(RESET_LAYOUT_SLOT, button(Material.ITEM_FRAME, "\uD83D\uDDD2 Restore the shipped layout",
                "Puts every game back on the slot this jar ships it with",
                "Names, icons and on/off states are left alone"));
        set(RELOAD_SLOT, button(Material.WRITABLE_BOOK, "\u21BB Re-read config.yml",
                "Same as /casino reload",
                "Useful after editing the file by hand"));
    }

    /** A two-state switch, with the config key it writes in its lore. */
    private ItemStack switchItem(Material icon, String name, boolean on, String key,
                                 String... loreLines) {
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(grey(line));
        }
        lore.add(grey(""));
        lore.add(on
                ? CasinoText.legacy("&a\u2714 On", NamedTextColor.GREEN)
                : CasinoText.legacy("&c\u2716 Off", NamedTextColor.RED));
        lore.add(grey("config.yml: casino." + key));
        return item(icon, CasinoText.legacy(name, on ? NamedTextColor.AQUA : NamedTextColor.GRAY),
                lore);
    }

    /** A number with a step either way, and the config key it writes. */
    private ItemStack valueItem(Material icon, String name, String key, String... loreLines) {
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(grey(line));
        }
        lore.add(grey(""));
        lore.add(grey("config.yml: casino." + key));
        return item(icon, CasinoText.legacy(name, NamedTextColor.AQUA), lore);
    }

    // ── Page three: the pool ────────────────────────────────────────────

    private void renderPrizes() {
        int total = 0;
        for (CasinoReward reward : casino().prizes()) {
            total += reward.rollable() ? reward.weight() : 0;
        }
        int slot = 0;
        for (CasinoReward reward : casino().prizes()) {
            if (slot >= PRIZE_SLOTS.length) {
                break;
            }
            int at = PRIZE_SLOTS[slot++];
            prizeAtSlot.put(at, reward.id());
            set(at, prizeItem(reward, total));
        }
    }

    private int rollablePrizes() {
        int rollable = 0;
        for (CasinoReward reward : casino().prizes()) {
            if (reward.rollable()) {
                rollable++;
            }
        }
        return rollable;
    }

    private ItemStack prizeItem(CasinoReward reward, int total) {
        List<Component> lore = new ArrayList<>();
        lore.add(CasinoText.legacy("&7" + reward.rarity().label(), reward.rarity().colour()));
        for (String line : reward.lore()) {
            lore.add(grey(line));
        }
        lore.add(grey(""));
        String odds = reward.rollable() && total > 0
                ? " \u00B7 about " + Math.max(1, Math.round(reward.weight() * 100.0 / total)) + "% of rolls"
                : "";
        lore.add(grey("Weight: " + reward.weight() + odds));
        lore.add(grey(reward.rollable()
                ? (casino().cosmeticsEnabled() ? "Can be put on display" : "Display is switched off")
                : "Weight 0 \u2014 listed in the cabinet, never rolled"));
        lore.add(grey("You have collected it \u00D7"
                + casino().stats(viewerId()).rewardCount(reward.id())));
        lore.add(grey("config.yml: casino.rewards." + reward.id()));
        lore.add(grey(""));
        lore.add(CasinoText.legacy("&e\u2794 Left-click: next tier up", NamedTextColor.YELLOW));
        lore.add(grey("Right-click: 5 likelier \u00B7 Shift-right: 5 rarer"));
        return item(reward.icon(), reward.displayName(), lore);
    }

    private CasinoReward prize(String id) {
        if (id == null) {
            return null;
        }
        for (CasinoReward reward : casino().prizes()) {
            if (reward.id().equals(id)) {
                return reward;
            }
        }
        return null;
    }

    // ── Clicks ──────────────────────────────────────────────────────────

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = clickedSlot(event);
        Player player = viewer();
        if (slot < 0 || player == null || !plugin().canUseAdmin(player)) {
            return;
        }
        switch (slot) {
            case CLOSE_SLOT -> {
                player.closeInventory();
                return;
            }
            case OPEN_HUB_SLOT -> {
                CasinoFeedback.back(player);
                player.closeInventory();
                casino().openHub(player);
                return;
            }
            case GAMES_TAB -> show(player, GAMES);
            case LAYOUT_TAB -> show(player, LAYOUT);
            case PRIZES_TAB -> show(player, PRIZES);
            default -> {
                switch (page) {
                    case LAYOUT -> handleLayoutClick(slot, player, event);
                    case PRIZES -> handlePrizeClick(slot, player, event);
                    default -> handleGameClick(slot, player, event);
                }
            }
        }
    }

    private void show(Player player, int wanted) {
        if (page == wanted) {
            return;
        }
        moving = null;
        page = wanted;
        CasinoFeedback.click(player);
        render();
    }

    private void handleGameClick(int slot, Player player, InventoryClickEvent event) {
        String id = gameAtSlot.get(slot);
        if (moving != null) {
            if (id != null && id.equals(moving)) {
                moving = null; // picked up and put straight back down
                CasinoFeedback.back(player);
                render();
                return;
            }
            if (!casino().setGameSlot(moving, slot)) {
                casino().deny(player, "admin-no-move",
                        "&7That slot can't take it \u2014 the hub is &f%size%&7 slots (0-%last%&7).",
                        "%size%", String.valueOf(casino().size()),
                        "%last%", String.valueOf(casino().size() - 1));
                return;
            }
            String guest = id == null ? name(moving) : name(moving) + " and " + name(id) + " swapped";
            moving = null;
            CasinoFeedback.click(player);
            player.sendMessage(Component.text("\uD83C\uDFB0 " + guest + " \u00B7 slot " + slot + ".")
                    .color(NamedTextColor.GREEN));
            render();
            return;
        }
        CasinoManager.Entry entry = casino().entry(id);
        if (entry == null) {
            return;
        }
        if (entry.comingSoon()) {
            casino().deny(player, "admin-no-code",
                    "&7&f%game%&7 is only a placeholder \u2014 no game code claims that id.",
                    "%game%", CasinoText.plain(entry.name()));
            return;
        }
        if (event.isShiftClick() && event.isRightClick()) {
            if (casino().resetGameAppearance(entry.id())) {
                CasinoFeedback.click(player);
                player.sendMessage(Component.text("\u2699 " + CasinoText.plain(entry.name())
                                + " is back to the icon and name this jar ships.")
                        .color(NamedTextColor.GREEN));
            }
            render();
            return;
        }
        if (event.isShiftClick()) {
            if (casino().cycleGameIcon(entry.id())) {
                CasinoFeedback.click(player);
            }
            render();
            return;
        }
        if (event.isRightClick()) {
            moving = entry.id();
            CasinoFeedback.back(player);
            render();
            return;
        }
        boolean wanted = !entry.enabled();
        if (casino().setGameEnabled(entry.id(), wanted)) {
            CasinoFeedback.click(player);
            player.sendMessage(Component.text("\uD83C\uDFB0 " + CasinoText.plain(entry.name())
                            + (wanted ? " is switched on." : " is switched off."))
                    .color(wanted ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        render();
    }

    private void handleLayoutClick(int slot, Player player, InventoryClickEvent event) {
        boolean right = event.isRightClick();
        boolean changed;
        switch (slot) {
            case OPEN_SLOT -> changed = casino().setOpen(!casino().isOpen());
            case COOLDOWN_SLOT -> changed = casino().nudgeCooldown(right ? 1 : -1);
            case HIDE_SLOT -> changed = casino().setHideDisabled(!casino().hideDisabled());
            case COSMETICS_SLOT -> changed = casino().setCosmeticsEnabled(!casino().cosmeticsEnabled());
            case SHOW_OFF_SLOT -> changed = casino().nudgeShowOffRadius(right ? 8 : -8);
            case ENABLE_ALL_SLOT -> changed = casino().setEveryGameEnabled(true);
            case DISABLE_ALL_SLOT -> changed = casino().setEveryGameEnabled(false);
            case RESET_LAYOUT_SLOT -> changed = casino().resetLayout();
            case RELOAD_SLOT -> {
                casino().reload();
                CasinoFeedback.click(player);
                player.sendMessage(Component.text("\uD83C\uDFB0 Casino config re-read: "
                                + casino().gamesAvailable() + " playable, "
                                + casino().prizes().size() + " prize(s).")
                        .color(NamedTextColor.GREEN));
                render();
                return;
            }
            default -> {
                return;
            }
        }
        if (changed) {
            CasinoFeedback.click(player);
        } else {
            CasinoFeedback.deny(player);
        }
        render();
    }

    private void handlePrizeClick(int slot, Player player, InventoryClickEvent event) {
        CasinoReward reward = prize(prizeAtSlot.get(slot));
        if (reward == null) {
            return;
        }
        boolean changed;
        if (event.isShiftClick() && event.isRightClick()) {
            changed = casino().setPrizeWeight(reward.id(), reward.weight() - 5);
        } else if (event.isRightClick()) {
            changed = casino().setPrizeWeight(reward.id(), reward.weight() + 5);
        } else if (event.isShiftClick()) {
            CasinoRarity[] tiers = CasinoRarity.values();
            changed = casino().setPrizeRarity(reward.id(),
                    tiers[(reward.rarity().rank() + 1) % tiers.length]);
        } else {
            changed = false;
        }
        if (changed) {
            CasinoFeedback.click(player);
        } else {
            CasinoFeedback.deny(player);
        }
        render();
    }

    // ── Small helpers ───────────────────────────────────────────────────

    private void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory().getSize()) {
            inventory().setItem(slot, item);
        }
    }

    /** A game's name as an admin reads it: plain text, no colour codes. */
    private String name(String gameId) {
        CasinoManager.Entry entry = casino().entry(gameId);
        return entry == null ? gameId : CasinoText.plain(entry.name());
    }
}
