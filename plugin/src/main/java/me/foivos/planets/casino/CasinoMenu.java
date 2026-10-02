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
 * The casino hub: one screen with every registered game on it.
 *
 * <pre>
 *   🟦🟦🟦🟦🎰🟦🟦🟦🟦         🎰 Casino
 *   🟦▣▣🎰▣🪙▣🎡▣▣🟦            each game, at the slot config.yml gives it
 *   🟦▣▣🎲▣🎡▣🃏▣▣🟦
 *   🟦▣▣🃏▣❓▣☀▣▣🟦            ❓ a game that is not implemented yet
 *   🟦▣▣📊▣📖▣✖▣▣🟦            📊 your record · 📖 how it works · ✖ close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 *
 * <p>Nothing here is written per game. The menu asks the {@link CasinoManager}
 * what is registered, draws each entry where config.yml puts it, and remembers
 * which slot holds which game — so a game added later shows up by itself, and
 * this class never learns its name.
 */
public final class CasinoMenu extends CasinoScreenBase {

    /** Which game each slot holds, rebuilt every time the screen is drawn. */
    private final Map<Integer, String> gameAtSlot = new HashMap<>();

    CasinoMenu(Planets plugin, CasinoManager casino, Player viewer) {
        super(plugin, casino, viewer, casino.size(),
                CasinoText.legacy(casino.title(), NamedTextColor.DARK_AQUA));
    }

    @Override
    protected void render() {
        gameAtSlot.clear();
        inventory().clear();
        MenuStyle.decorate(inventory(), casino().leftLabel(), casino().rightLabel());
        if (inventory().getSize() > 4) {
            inventory().setItem(4, header());
        }
        for (CasinoManager.Entry entry : casino().entries()) {
            if (entry.slot() < 0 || entry.slot() >= inventory().getSize()) {
                continue;
            }
            if (casino().hideDisabled() && !entry.playable()) {
                continue;
            }
            inventory().setItem(entry.slot(), gameItem(entry));
            gameAtSlot.put(entry.slot(), entry.id());
        }
        setButton(casino().statsSlot(), statsButton());
        setButton(casino().infoSlot(), infoButton());
        setButton(casino().closeSlot(), closeButton());
    }

    /** Draws a configured button, or nothing at all when its slot is negative. */
    private void setButton(int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory().getSize()) {
            inventory().setItem(slot, item);
        }
    }

    private ItemStack header() {
        CasinoStats stats = casino().stats(viewerId());
        List<String> lore = new ArrayList<>();
        lore.add(stats.played() + " rounds played \u00B7 " + stats.wins() + " won");
        lore.add(casino().gamesAvailable() + " games open \u00B7 every round is free");
        return MenuStyle.header("Casino", NamedTextColor.GOLD, lore);
    }

    private ItemStack gameItem(CasinoManager.Entry entry) {
        List<Component> lore = new ArrayList<>();
        for (String line : entry.description()) {
            lore.add(CasinoText.legacy(line, NamedTextColor.GRAY));
        }
        int played = casino().stats(viewerId()).plays(entry.id());
        if (played > 0) {
            lore.add(grey(""));
            lore.add(grey("Your rounds: " + played));
        }
        lore.add(grey(""));
        if (entry.comingSoon()) {
            lore.add(grey("\u23F3 Coming soon \u2014 not playable yet"));
        } else if (!entry.enabled()) {
            lore.add(grey("\u26D4 Switched off right now"));
        } else {
            lore.add(CasinoText.legacy("&e\u2794 Click to play", NamedTextColor.YELLOW));
        }
        NamedTextColor colour = entry.playable() ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY;
        return item(entry.icon(), CasinoText.legacy(entry.name(), colour), lore);
    }

    private ItemStack statsButton() {
        return button(Material.KNOWLEDGE_BOOK, "\uD83D\uDCCA Your Record",
                "Rounds, wins, streaks and prizes",
                "Best reaction and achievements",
                "",
                "Click to open your record");
    }

    private ItemStack infoButton() {
        return button(Material.BOOK, "\uD83D\uDCD6 How it works",
                "Every game is free to play",
                "Prizes are cosmetic keepsakes",
                "",
                "Click to read the rules");
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = clickedSlot(event);
        Player player = viewer();
        if (slot < 0 || player == null) {
            return;
        }
        if (slot == casino().closeSlot()) {
            player.closeInventory();
            return;
        }
        if (slot == casino().statsSlot()) {
            CasinoFeedback.click(player);
            player.closeInventory();
            casino().openStats(player);
            return;
        }
        if (slot == casino().infoSlot()) {
            CasinoFeedback.click(player);
            printInfo(player);
            return;
        }
        String gameId = gameAtSlot.get(slot);
        CasinoManager.Entry entry = casino().entry(gameId);
        if (entry == null) {
            return;
        }
        if (entry.comingSoon() || !entry.enabled()) {
            casino().deny(player, entry.comingSoon() ? "coming-soon" : "disabled",
                    entry.comingSoon()
                            ? "&7\u23F3 &f%game%&7 is still being built \u2014 check back soon."
                            : "&7\u26D4 &f%game%&7 is switched off right now.",
                    "%game%", CasinoText.plain(entry.name()));
            return;
        }
        CasinoFeedback.click(player);
        player.closeInventory();
        entry.game().open(player);
    }

    /** The rules, as a chat panel — the casino's one page of explanation. */
    private void printInfo(Player player) {
        casino().notice(player, "info-intro", "&8\u2500\u2500 &6\uD83C\uDFB0 Casino &8\u2500\u2500");
        casino().notice(player, "info-body", "&7Every game is free to play.");
        List<String> prizes = casino().prizeNames();
        if (!prizes.isEmpty()) {
            casino().notice(player, "info-prizes", "&7Prizes in the pool: &f" + String.join("&7, &f", prizes));
        }
        casino().notice(player, "info-commands",
                "&7\u2794 &f/casino &7opens the hub \u00B7 &f/casino stats &7your record");
    }
}
