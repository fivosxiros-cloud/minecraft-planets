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
 * One throw of a die.
 *
 * <p>The die tumbles through its faces, lands on one, and the roll decides the
 * round: {@code win-at} or higher wins a cosmetic prize, anything lower is a
 * round lost. Both the number of faces and the winning line come from
 * {@code casino.games.dice} in config.yml, so a server can make it a d6 that
 * needs a 5, or a d20 that needs a 19.
 *
 * <p>Rolling is free, like every other game here — the only thing a bad throw
 * costs is the round.
 */
public final class DiceGame implements CasinoGame {

    static final String ID = "dice";
    /** The six die faces, for a die with no more than six sides. */
    private static final String FACES = "\u2680\u2681\u2682\u2683\u2684\u2685";
    private static final int ROLLS = 8;
    private static final long ROLL_TICKS = 3L;

    private final CasinoManager casino;
    /** The die the game ships with, and its winning line. Both are configurable. */
    private int sides = 6;
    private int winAt = 5;

    public DiceGame(CasinoManager casino) {
        this.casino = casino;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bDice";
    }

    @Override
    public Material icon() {
        return Material.BONE;
    }

    @Override
    public List<String> description() {
        return List.of("Roll and take what comes.", "Six faces, one throw.");
    }

    @Override
    public boolean loadConfig(ConfigurationSection section) {
        if (section == null) {
            return false;
        }
        boolean written = false;
        if (!section.contains("sides")) {
            section.set("sides", sides);
            written = true;
        }
        if (!section.contains("win-at")) {
            section.set("win-at", winAt);
            written = true;
        }
        sides = Math.max(2, Math.min(100, section.getInt("sides", 6)));
        winAt = Math.max(2, Math.min(sides, section.getInt("win-at", Math.max(2, sides - 1))));
        return written;
    }

    @Override
    public void open(Player player) {
        new Screen(casino, player, sides, winAt).open(player);
    }

    /** The game's own GUI, clicks and animation. */
    private static final class Screen extends CasinoScreenBase {

        private static final int ROLL_SLOT = 22;
        private static final int FACE_SLOT = 31;
        private static final int RESULT_SLOT = 40;
        private static final int BACK_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;

        private final int sides;
        private final int winAt;

        /** The face the die is showing right now. */
        private int face = 1;
        private String result = "";
        private NamedTextColor resultColour = NamedTextColor.GRAY;

        Screen(CasinoManager casino, Player viewer, int sides, int winAt) {
            super(casino.plugin(), casino, viewer, 54,
                    CasinoText.legacy("&8\uD83C\uDFB2 Dice", NamedTextColor.DARK_AQUA));
            this.sides = sides;
            this.winAt = winAt;
        }

        @Override
        protected void render() {
            inventory().clear();
            MenuStyle.decorate(inventory(), "\uD83C\uDFB2 Dice", "\uD83C\uDFAF Roll " + winAt + "+");
            inventory().setItem(4, MenuStyle.header("Dice", NamedTextColor.GOLD,
                    List.of("A d" + sides + " \u00B7 " + winAt + " or better wins",
                            "Free to play \u00B7 cosmetic prizes only")));
            inventory().setItem(ROLL_SLOT, rollButton());
            inventory().setItem(FACE_SLOT, faceItem());
            if (!result.isEmpty()) {
                inventory().setItem(RESULT_SLOT, item(Material.PAPER,
                        plain("The throw").color(NamedTextColor.WHITE),
                        List.of(CasinoText.legacy(result, resultColour))));
            }
            inventory().setItem(BACK_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack rollButton() {
            List<String> lore = new ArrayList<>();
            lore.add("A d" + sides + " \u2014 " + winAt + " or higher wins");
            lore.add("");
            lore.add(animating() ? "The die is rolling\u2026" : "Click to throw");
            return button(Material.BONE, "\uD83C\uDFB2 Roll the dice", lore.toArray(new String[0]));
        }

        private ItemStack faceItem() {
            return item(Material.BONE,
                    plain("\uD83C\uDFB2 " + faceText(face))
                            .color(animating() ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
                    List.of(grey(animating() ? "Tumbling" : "Face up: " + faceText(face))));
        }

        /** A die face: a pip glyph on a six-sided die, the number otherwise. */
        private String faceText(int value) {
            return sides <= FACES.length() && value >= 1
                    ? String.valueOf(FACES.charAt(value - 1))
                    : String.valueOf(value);
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Rounds: " + stats.plays(ID)));
            lore.add(grey("Won: " + stats.wins()));
            lore.add(grey("Best face: " + (stats.plays(ID) == 0 ? "not thrown yet" : "d" + sides)));
            return item(Material.PAPER, plain("\uD83D\uDCCA Your throws").color(NamedTextColor.AQUA), lore);
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
                case ROLL_SLOT -> roll(player);
                default -> {
                    // The frame and the read-outs do nothing.
                }
            }
        }

        private void roll(Player player) {
            if (animating()) {
                casino().notice(player, "busy", "&7That round is still going \u2014 one moment.");
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            CasinoFeedback.click(player);
            result = "";
            animating(true);
            render();
            int[] rolls = {0};
            every(ROLL_TICKS, ROLL_TICKS, () -> {
                rolls[0]++;
                face = ThreadLocalRandom.current().nextInt(1, sides + 1);
                CasinoFeedback.tick(player, rolls[0]);
                render();
                if (rolls[0] >= ROLLS) {
                    stopTasks();
                    settle(player);
                }
            });
        }

        private void settle(Player player) {
            face = ThreadLocalRandom.current().nextInt(1, sides + 1);
            boolean won = face >= winAt;
            if (won) {
                casino().win(player, ID);
                CasinoFeedback.win(player);
                casino().grant(player, ID);
            } else {
                casino().lose(player, ID);
                CasinoFeedback.lose(player);
            }
            result = "&7You threw a " + (won ? "&a" : "&c") + face + "&7 of " + sides
                    + " \u2014 " + (won ? "a win." : "not enough this time.");
            resultColour = won ? NamedTextColor.GREEN : NamedTextColor.RED;
            animating(false);
            render();
        }
    }
}
