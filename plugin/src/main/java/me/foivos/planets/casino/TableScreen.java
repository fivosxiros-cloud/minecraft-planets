package me.foivos.planets.casino;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.Planets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen every table game shares.
 *
 * <p>A table is not a screen: one round runs for everybody at once, so the
 * screen's job is only to keep up with it. It re-paints twice a second, which
 * is what makes the countdown tick down, other players' bets appear, and the
 * result arrive without a single listener being registered. A game supplies its
 * own betting controls and its own result banner, and nothing else.
 *
 * <p>The header says the same four things on every table — which round it is,
 * how long is left, how much is on the table, and what the viewer has staked —
 * so the games look like one casino rather than three.
 */
public abstract class TableScreen extends CasinoScreenBase {

    private static final int HEADER_SLOT = 4;
    private static final int BACK_SLOT = 45;
    private static final int RECORD_SLOT = 49;
    private static final int CLOSE_SLOT = 53;
    /** Twice a second: fast enough for a countdown, cheap enough for a hub. */
    private static final long POLL_TICKS = 10L;

    private final CasinoTable table;
    private final String gameId;
    private final String label;
    private boolean polling;
    private int lastTickedSecond = -1;

    protected TableScreen(Planets plugin, CasinoManager casino, CasinoTable table, String gameId,
                          String label, Component title, Player viewer) {
        super(plugin, casino, viewer, 54, title);
        this.table = table;
        this.gameId = gameId;
        this.label = label;
    }

    /** The round this screen is showing. */
    protected final CasinoTable table() {
        return table;
    }

    @Override
    protected final void render() {
        Player player = viewer();
        if (player == null) {
            return;
        }
        if (!polling) {
            polling = true;
            table.watch(viewerId());
            every(POLL_TICKS, POLL_TICKS, this::render);
        }
        inventory().clear();
        MenuStyle.decorate(inventory(), label, phaseTag());
        inventory().setItem(HEADER_SLOT, headerItem(player));
        if (table.phase() == CasinoTable.Phase.BETTING) {
            drawBets(player);
        }
        if (table.phase() == CasinoTable.Phase.DRAWING) {
            drawDrawing(player);
        }
        if (table.phase() == CasinoTable.Phase.RESULT) {
            drawResult(player);
        }
        inventory().setItem(BACK_SLOT, backButton("Casino"));
        inventory().setItem(RECORD_SLOT, recordItem());
        inventory().setItem(CLOSE_SLOT, closeButton());
        countdown(player);
    }

    // ── What a game fills in ────────────────────────────────────────────

    /** The betting controls, drawn only while bets are open. */
    protected abstract void drawBets(Player player);

    /** A click on one of those controls. */
    protected abstract void clickBet(Player player, int slot, InventoryClickEvent event);

    /** The draw in progress, painted once per poll while the round is deciding. */
    protected void drawDrawing(Player player) {
    }

    /** The result, drawn only for the seconds between the draw and the next round. */
    protected void drawResult(Player player) {
    }

    // ── Clicks ──────────────────────────────────────────────────────────

    @Override
    public final void handleClick(InventoryClickEvent event) {
        int slot = clickedSlot(event);
        Player player = viewer();
        if (slot < 0 || player == null) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            CasinoFeedback.back(player);
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            CasinoFeedback.back(player);
            casino().openHub(player);
            return;
        }
        if (slot == HEADER_SLOT || slot == RECORD_SLOT) {
            CasinoFeedback.click(player);
            return;
        }
        if (table.phase() != CasinoTable.Phase.BETTING) {
            CasinoFeedback.deny(player);
            casino().notice(player, "bets-closed",
                    "&eThis round is already underway. &7Your next bet waits for the next round.");
            return;
        }
        CasinoFeedback.click(player);
        clickBet(player, slot, event);
    }

    // ── The shared header ───────────────────────────────────────────────

    /** The tag on the right of the window's trim, saying where the round is. */
    private String phaseTag() {
        return switch (table.phase()) {
            case IDLE -> "waiting";
            case BETTING -> "bets open";
            case DRAWING -> "drawing";
            case RESULT -> "result";
        };
    }

    private ItemStack headerItem(Player player) {
        List<String> lore = new ArrayList<>();
        lore.add("Round " + Math.max(1, table.round()));
        switch (table.phase()) {
            case IDLE -> lore.add("The next round opens in a moment");
            case BETTING -> lore.add("Bets close in " + table.secondsLeft() + "s");
            case DRAWING -> lore.add("The draw is under way");
            case RESULT -> lore.add("Next round in " + table.secondsLeft() + "s");
        }
        lore.add("");
        lore.add("On the table: " + CasinoWager.money(table.pot()) + " coins");
        double stake = table.stakeOf(viewerId());
        lore.add(stake > 0
                ? "Your stake: " + CasinoWager.money(stake) + " coins"
                : "You have nothing on this round");
        double balance = casino().wager().balance(player);
        lore.add("Your balance: " + CasinoWager.money(balance) + " coins");
        return MenuStyle.header(label, NamedTextColor.GOLD, lore);
    }

    /** Your record at this table: the same numbers the stats screen counts. */
    private ItemStack recordItem() {
        CasinoStats stats = casino().stats(viewerId());
        List<String> lore = new ArrayList<>();
        lore.add("Rounds played: " + stats.plays(gameId));
        lore.add("Won: " + stats.wins());
        lore.add("Lost: " + stats.losses());
        lore.add("Drawn: " + stats.draws());
        return MenuStyle.header("Your record", NamedTextColor.AQUA, lore);
    }

    /** Counts the last three seconds of betting in, so nobody misses the close. */
    private void countdown(Player player) {
        if (table.phase() != CasinoTable.Phase.BETTING) {
            lastTickedSecond = -1;
            return;
        }
        int left = table.secondsLeft();
        if (left > 0 && left <= 3 && left != lastTickedSecond) {
            lastTickedSecond = left;
            CasinoFeedback.tick(player, 4 - left);
        }
    }
}
