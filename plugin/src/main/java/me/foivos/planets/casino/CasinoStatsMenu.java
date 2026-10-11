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
import java.util.List;
import java.util.Map;

/**
 * A player's record at the casino, on two pages: how they have done, and the
 * cabinet of prizes they have collected.
 *
 * <pre>
 *   🟦🟦🟦🟦📊🟦🟦🟦🟦         📊 Your record
 *   🟦▣▣▣▣▣▣▣🟦                 rounds · wins · losses · streak
 *   🟦▣▣🏆▣🏆▣🏆▣▣🟦             ★ unlocked · ✖ locked with its requirement
 *   🟦▣▣🎁▣▣▣▣▣🟦
 *   🟦▣▣▣▣▣▣▣🟦
 *   🟦🟦📊🟦🟦🎁🟦↩🟦✖🟦          📊 record · 🎁 cabinet · ↩ hub · ✖ close
 * </pre>
 *
 * <p>The numbers come straight from the statistics the manager keeps, so a
 * game added later appears in them without this screen being changed.
 */
public final class CasinoStatsMenu extends CasinoScreenBase {

    private static final int HEADER_SLOT = 4;
    private static final int[] STAT_SLOTS = {19, 20, 21, 23, 24, 25};
    private static final int PRIZES_SLOT = 22;
    private static final int[] ACHIEVEMENT_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int[] CABINET_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};
    private static final int RECORD_TAB = 45;
    private static final int CABINET_TAB = 47;
    private static final int BACK_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    /** False draws the record, true the prize cabinet. */
    private boolean cabinetPage;
    /** Which prize each cabinet slot holds, so a click knows what it picked. */
    private final Map<Integer, String> rewardAtSlot = new HashMap<>();

    CasinoStatsMenu(Planets plugin, CasinoManager casino, Player viewer) {
        super(plugin, casino, viewer, casino.size(),
                CasinoText.legacy("\uD83D\uDCCA " + CasinoText.plain(casino.title()) + " \u00B7 Record",
                        NamedTextColor.DARK_AQUA));
    }

    @Override
    protected void render() {
        inventory().clear();
        MenuStyle.decorate(inventory(), "\uD83C\uDFB0 Record", "\uD83D\uDC64 Yours");
        if (inventory().getSize() > HEADER_SLOT) {
            inventory().setItem(HEADER_SLOT, header());
        }
        if (cabinetPage) {
            drawCabinet();
        } else {
            drawRecord();
        }
        inventory().setItem(RECORD_TAB, MenuStyle.tab(Material.KNOWLEDGE_BOOK, "Record",
                !cabinetPage, "Rounds, wins and streaks"));
        inventory().setItem(CABINET_TAB, MenuStyle.tab(Material.CHEST, "Cabinet",
                cabinetPage, "Every prize you have collected"));
        inventory().setItem(BACK_SLOT, backButton("Casino"));
        inventory().setItem(CLOSE_SLOT, closeButton());
    }

    private CasinoStats stats() {
        return casino().stats(viewerId());
    }

    private ItemStack header() {
        CasinoStats stats = stats();
        List<String> lines = new ArrayList<>();
        lines.add(stats.name().isBlank() ? "Casino regular" : stats.name());
        lines.add(stats.played() + " rounds \u00B7 " + stats.totalRewards() + " prizes");
        CasinoReward trophy = casino().displayReward(viewerId());
        lines.add(trophy == null
                ? "Nothing on display \u2014 click a prize in the cabinet"
                : "Displaying " + trophy.name() + " (" + trophy.rarity().label() + ")");
        return MenuStyle.header(cabinetPage ? "Prize Cabinet" : "Your Record",
                cabinetPage ? NamedTextColor.GOLD : NamedTextColor.AQUA, lines);
    }

    private void drawRecord() {
        CasinoStats stats = stats();
        int slot = 0;
        set(STAT_SLOTS[slot++], statItem(Material.WRITABLE_BOOK, "\uD83C\uDFAF Rounds Played",
                List.of(grey(stats.played() + " started"),
                        grey(stats.completed() + " played to the end"))));
        set(STAT_SLOTS[slot++], statItem(Material.GOLD_INGOT, "\uD83C\uDFC6 Wins",
                List.of(grey(stats.wins() + " rounds won"),
                        grey(stats.wins() + " won, " + stats.losses() + " lost"))));
        set(STAT_SLOTS[slot++], statItem(Material.COAL, "\uD83D\uDCA5 Losses",
                List.of(grey(stats.losses() + " rounds lost"),
                        grey(stats.draws() + " draws"))));
        set(STAT_SLOTS[slot++], statItem(Material.COMPASS, "\uD83C\uDFB0 Favourite Game",
                List.of(grey(favourite()))));
        set(STAT_SLOTS[slot++], statItem(Material.ENDER_EYE, "\u26A1 Best Reaction",
                List.of(grey(stats.bestReactionMs() > 0
                                ? stats.bestReactionMs() + " ms"
                                : "not measured yet"),
                        grey("Reaction game"))));
        set(STAT_SLOTS[slot++], statItem(Material.SUNFLOWER, "\u2600 Daily Spin",
                List.of(grey(stats.dailySpins() + " spins used"),
                        grey("Streak: " + stats.streak() + " (best " + stats.bestStreak() + ")"),
                        grey(casino().canSpinToday(viewer()) ? "Ready now"
                                : "Next in " + casino().nextResetIn()))));

        List<Component> prizeLore = new ArrayList<>();
        prizeLore.add(grey(stats.totalRewards() + " in total"));
        prizeLore.add(grey(stats.rewards().size() + " different ones"));
        prizeLore.add(grey("Nothing here is tradeable"));
        CasinoReward trophy = casino().displayReward(viewerId());
        prizeLore.add(grey(""));
        prizeLore.add(trophy == null
                ? grey("Nothing on display \u2014 open the cabinet")
                : CasinoText.legacy("&6\uD83C\uDF96 " + trophy.name(),
                        trophy.rarity().colour()));
        set(PRIZES_SLOT, statItem(Material.CHEST, "\uD83C\uDF81 Prizes Collected", prizeLore));

        List<CasinoAchievement> unlocked = casino().achievements();
        int index = 0;
        for (CasinoAchievement achievement : unlocked) {
            if (index >= ACHIEVEMENT_SLOTS.length) {
                break;
            }
            set(ACHIEVEMENT_SLOTS[index++], achievementItem(achievement, stats));
        }
    }

    private String favourite() {
        CasinoStats stats = stats();
        if (stats.favourite().isBlank()) {
            return "nothing played yet";
        }
        CasinoManager.Entry entry = casino().entry(stats.favourite());
        return entry == null ? stats.favourite() : CasinoText.plain(entry.name());
    }

    private ItemStack achievementItem(CasinoAchievement achievement, CasinoStats stats) {
        boolean unlocked = stats.hasAchievement(achievement.id());
        List<Component> lore = new ArrayList<>();
        lore.add(grey(achievement.description()));
        lore.add(grey(""));
        lore.add(grey(achievement.requirement()));
        lore.add(grey(""));
        lore.add(unlocked
                ? CasinoText.legacy("&a\u2605 Unlocked", NamedTextColor.GREEN)
                : CasinoText.legacy("&8\u2716 Locked", NamedTextColor.DARK_GRAY));
        return item(achievement.icon(),
                CasinoText.legacy(achievement.name(),
                        unlocked ? achievement.rarity().colour() : NamedTextColor.DARK_GRAY),
                lore);
    }

    private void drawCabinet() {
        rewardAtSlot.clear();
        Map<CasinoReward, Integer> cabinet = casino().cabinet(viewerId());
        int slot = 0;
        for (Map.Entry<CasinoReward, Integer> entry : cabinet.entrySet()) {
            if (slot >= CABINET_SLOTS.length) {
                break;
            }
            CasinoReward reward = entry.getKey();
            int owned = entry.getValue();
            List<Component> lore = new ArrayList<>();
            lore.add(CasinoText.legacy("&7" + reward.rarity().label(), reward.rarity().colour()));
            for (String line : reward.lore()) {
                lore.add(grey(line));
            }
            lore.add(grey(""));
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
    }

    private ItemStack statItem(Material icon, String name, List<Component> lore) {
        return item(icon, CasinoText.legacy(name, NamedTextColor.AQUA), lore);
    }

    private void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory().getSize()) {
            inventory().setItem(slot, item);
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = clickedSlot(event);
        Player player = viewer();
        if (slot < 0 || player == null) {
            return;
        }
        if (cabinetPage && rewardAtSlot.containsKey(slot)) {
            if (casino().toggleDisplay(player, rewardAtSlot.get(slot))) {
                render();
            } else {
                CasinoFeedback.deny(player);
            }
            return;
        }
        switch (slot) {
            case CLOSE_SLOT -> player.closeInventory();
            case BACK_SLOT -> {
                CasinoFeedback.back(player);
                player.closeInventory();
                casino().openHub(player);
            }
            case RECORD_TAB -> {
                if (cabinetPage) {
                    cabinetPage = false;
                    CasinoFeedback.click(player);
                    render();
                }
            }
            case CABINET_TAB -> {
                if (!cabinetPage) {
                    cabinetPage = true;
                    CasinoFeedback.click(player);
                    render();
                }
            }
            default -> {
                // Frame, figures and prizes are read-only.
            }
        }
    }
}
