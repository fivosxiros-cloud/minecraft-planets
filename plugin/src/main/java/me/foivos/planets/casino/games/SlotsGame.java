package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoGame;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoScreenBase;
import me.foivos.planets.casino.CasinoStats;
import me.foivos.planets.casino.CasinoText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Three reels, one spin.
 *
 * <p>The reels turn together and stop one after another — left, middle, right —
 * and the line they leave decides the round: as many of one symbol as
 * {@code matching-reels-to-win} says is a win, one short of it is a draw, and
 * anything else is a round lost. The reels are decided before they start
 * turning, so what the screen stops on is exactly what the record is told.
 *
 * <p>Everything about the machine is configurable under {@code casino.games.slots}:
 * the {@code symbols} themselves (each one a material, optionally with its own
 * label, {@code APPLE|Apple}), how many {@code steps} a reel turns for, the
 * ticks between those steps and how many reels have to line up. The shipped set
 * is written into config.yml the first time the game loads, so it can be
 * rewritten without touching this class.
 *
 * <p>A spin costs nothing, like every game here: a bad line costs the round and
 * nothing else, and a good one rolls a cosmetic prize out of the pool.
 */
public final class SlotsGame implements CasinoGame {

    static final String ID = "slots";

    /** The reels the machine ships with: a material, then its optional label. */
    private static final List<String> SHIPPED_SYMBOLS = List.of(
            "APPLE|Apple", "DIAMOND|Diamond", "SWEET_BERRIES|Cherries",
            "EMERALD|Emerald", "GOLD_INGOT|Gold Ingot", "AMETHYST_SHARD|Amethyst");
    private static final int SHIPPED_STEPS = 10;
    private static final long SHIPPED_STEP_TICKS = 3L;
    private static final int SHIPPED_MATCHES = 3;

    private final CasinoManager casino;
    private List<Symbol> symbols = parse(SHIPPED_SYMBOLS);
    private int steps = SHIPPED_STEPS;
    private long stepTicks = SHIPPED_STEP_TICKS;
    private int matchesToWin = SHIPPED_MATCHES;

    public SlotsGame(CasinoManager casino) {
        this.casino = casino;
    }

    /** One symbol on the reels: the item it is drawn as, and what it is called. */
    record Symbol(Material icon, String name) {
    }

    /**
     * Reads the machine from {@code casino.games.slots}, writing the shipped
     * symbols and timings in when the file has none.
     */
    @Override
    public boolean loadConfig(ConfigurationSection section) {
        if (section == null) {
            return false;
        }
        boolean written = false;
        if (!section.isList("symbols")) {
            section.set("symbols", SHIPPED_SYMBOLS);
            written = true;
        }
        if (!section.contains("steps")) {
            section.set("steps", SHIPPED_STEPS);
            written = true;
        }
        if (!section.contains("step-ticks")) {
            section.set("step-ticks", SHIPPED_STEP_TICKS);
            written = true;
        }
        if (!section.contains("matching-reels-to-win")) {
            section.set("matching-reels-to-win", SHIPPED_MATCHES);
            written = true;
        }

        List<Symbol> configured = parse(section.getStringList("symbols"));
        symbols = configured.size() >= 2 ? configured : parse(SHIPPED_SYMBOLS);
        steps = Math.max(1, Math.min(40, section.getInt("steps", SHIPPED_STEPS)));
        stepTicks = Math.max(1L, Math.min(20L, section.getLong("step-ticks", SHIPPED_STEP_TICKS)));
        // With three reels only two or three of them can ever agree.
        matchesToWin = Math.max(2, Math.min(3,
                section.getInt("matching-reels-to-win", SHIPPED_MATCHES)));
        return written;
    }

    /**
     * Turns a configured symbol list into items. An entry is a material name,
     * or {@code MATERIAL|Label} to name it something else; anything the server
     * does not know is left out rather than shown as a sheet of paper.
     */
    static List<Symbol> parse(List<String> entries) {
        List<Symbol> parsed = new ArrayList<>();
        if (entries == null) {
            return parsed;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String material = entry;
            String label = "";
            int bar = entry.indexOf('|');
            if (bar >= 0) {
                material = entry.substring(0, bar).trim();
                label = entry.substring(bar + 1).trim();
            }
            Material icon = Material.matchMaterial(material);
            if (icon == null || icon == Material.AIR) {
                continue;
            }
            parsed.add(new Symbol(icon, label.isBlank() ? CasinoGame.prettify(material) : label));
        }
        return parsed;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bSlots";
    }

    @Override
    public Material icon() {
        return Material.SLIME_BALL;
    }

    @Override
    public List<String> description() {
        return List.of("Three reels, one spin.",
                "Line the symbols up for a prize.");
    }

    @Override
    public void open(Player player) {
        new Screen(casino, player, symbols, steps, stepTicks, matchesToWin).open(player);
    }

    /** The game's own GUI, clicks and animation. */
    private static final class Screen extends CasinoScreenBase {

        /** Where the three reels sit — one line across the middle of the field. */
        private static final int[] REEL_SLOTS = {19, 22, 25};
        private static final int SPIN_SLOT = 31;
        private static final int RESULT_SLOT = 40;
        private static final int BACK_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;
        /** Ticks between one reel stopping and the next. */
        private static final int STOP_GAP = 2;

        private final List<Symbol> symbols;
        private final int steps;
        private final long stepTicks;
        private final int matchesToWin;

        /** What each reel is showing right now. */
        private final Symbol[] reels = new Symbol[REEL_SLOTS.length];
        /** What each reel will stop on; decided before the first turn. */
        private final Symbol[] landing = new Symbol[REEL_SLOTS.length];
        /** How many reels have stopped so far. */
        private int stopped;
        private String result = "";
        private NamedTextColor resultColour = NamedTextColor.GRAY;

        Screen(CasinoManager casino, Player viewer, List<Symbol> symbols, int steps,
               long stepTicks, int matchesToWin) {
            super(casino.plugin(), casino, viewer, 54,
                    CasinoText.legacy("&8\uD83C\uDFB0 Slots", NamedTextColor.DARK_AQUA));
            this.symbols = symbols;
            this.steps = steps;
            this.stepTicks = stepTicks;
            this.matchesToWin = matchesToWin;
            for (int reel = 0; reel < reels.length; reel++) {
                reels[reel] = symbols.get(0);
                landing[reel] = symbols.get(0);
            }
        }

        @Override
        protected void render() {
            inventory().clear();
            MenuStyle.decorate(inventory(), "\uD83C\uDFB0 Slots",
                    "\uD83C\uDFAF " + (matchesToWin == 3 ? "Three" : "Two") + " in a line");
            inventory().setItem(4, MenuStyle.header("Slots", NamedTextColor.GOLD,
                    List.of(symbols.size() + " symbols \u00B7 "
                                    + (matchesToWin == 3 ? "three" : "two") + " in a line wins",
                            "Free to play \u00B7 cosmetic prizes only")));

            for (int reel = 0; reel < REEL_SLOTS.length; reel++) {
                inventory().setItem(REEL_SLOTS[reel], reelItem(reel));
            }
            inventory().setItem(SPIN_SLOT, spinButton());
            if (!result.isEmpty()) {
                inventory().setItem(RESULT_SLOT, item(Material.PAPER,
                        plain("The line").color(NamedTextColor.WHITE),
                        List.of(CasinoText.legacy(result, resultColour))));
            }
            inventory().setItem(BACK_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack reelItem(int reel) {
            Symbol symbol = reels[reel];
            boolean turning = animating() && reel >= stopped;
            List<Component> lore = new ArrayList<>();
            lore.add(grey(turning ? "Turning\u2026" : "Reel " + (reel + 1)));
            return item(symbol.icon(),
                    CasinoText.legacy((turning ? "&e" : "&b") + symbol.name(),
                            turning ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
                    lore);
        }

        private ItemStack spinButton() {
            List<String> lore = new ArrayList<>();
            lore.add(symbols.size() + " symbols \u00B7 "
                    + (matchesToWin == 3 ? "three of a kind wins" : "a pair wins"));
            lore.add("A spin costs nothing");
            lore.add("");
            lore.add(animating() ? "The reels are turning\u2026" : "Click to spin");
            return button(Material.SLIME_BALL, "\uD83C\uDFB0 Spin the reels",
                    lore.toArray(new String[0]));
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Spins: " + stats.plays(ID)));
            lore.add(grey("Lines: " + stats.wins()));
            lore.add(grey("Prizes: " + stats.totalRewards()));
            return item(Material.PAPER, plain("\uD83D\uDCCA Your spins").color(NamedTextColor.AQUA), lore);
        }

        @Override
        public void handleClick(InventoryClickEvent event) {
            int slot = clickedSlot(event);
            Player player = viewer();
            if (slot < 0 || player == null) {
                return;
            }
            switch (slot) {
                case CLOSE_SLOT -> player.closeInventory();
                case BACK_SLOT -> {
                    CasinoFeedback.back(player);
                    player.closeInventory();
                    casino().openHub(player);
                }
                case SPIN_SLOT -> spin(player);
                default -> {
                    // The frame and the read-outs do nothing.
                }
            }
        }

        /** Starts a spin: the manager records the round, then the reels turn. */
        private void spin(Player player) {
            if (animating()) {
                casino().notice(player, "busy", "&7That round is still going \u2014 one moment.");
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            CasinoFeedback.click(player);
            result = "";
            stopped = 0;
            for (int reel = 0; reel < landing.length; reel++) {
                landing[reel] = symbols.get(ThreadLocalRandom.current().nextInt(symbols.size()));
                reels[reel] = landing[reel];
            }
            animating(true);
            render();

            int[] step = {0};
            every(stepTicks, stepTicks, () -> {
                step[0]++;
                int settled = 0;
                for (int reel = 0; reel < reels.length; reel++) {
                    if (step[0] >= steps + reel * STOP_GAP) {
                        // This reel has run out of turns: it holds what it will
                        // be paid on for the rest of the spin.
                        reels[reel] = landing[reel];
                        settled++;
                    } else {
                        reels[reel] = symbols.get(ThreadLocalRandom.current().nextInt(symbols.size()));
                    }
                }
                stopped = settled;
                CasinoFeedback.tick(player, step[0]);
                render();
                if (settled >= reels.length) {
                    stopTasks();
                    settle(player);
                }
            });
        }

        /** Reads the line the reels stopped on and files the round. */
        private void settle(Player player) {
            int best = 0;
            for (Symbol reel : landing) {
                int alike = 0;
                for (Symbol other : landing) {
                    if (other.equals(reel)) {
                        alike++;
                    }
                }
                best = Math.max(best, alike);
            }
            boolean won = best >= matchesToWin;
            boolean drawn = !won && best > 1;
            if (won) {
                casino().win(player, ID);
                CasinoFeedback.win(player);
                casino().grant(player, ID);
            } else if (drawn) {
                // Two of three: the round was played to the end, but it did not
                // reach the line the machine pays on.
                casino().draw(player, ID);
                CasinoFeedback.click(player);
            } else {
                casino().lose(player, ID);
                CasinoFeedback.lose(player);
            }

            StringBuilder named = new StringBuilder();
            for (Symbol symbol : landing) {
                named.append(named.isEmpty() ? "" : "&7 \u00B7 ").append("&f").append(symbol.name());
            }
            String line = named.toString();
            if (won) {
                result = line + "&7 \u2014 "
                        + (matchesToWin == 3 ? "&athree of a kind, a win." : "&aa pair, a win.");
                resultColour = NamedTextColor.GREEN;
            } else if (drawn) {
                result = line + "&7 \u2014 &etwo of a kind, but not enough. Nothing this time.";
                resultColour = NamedTextColor.YELLOW;
            } else {
                result = line + "&7 \u2014 &cnothing lined up. Nothing this time.";
                resultColour = NamedTextColor.RED;
            }
            animating(false);
            render();
        }
    }
}
