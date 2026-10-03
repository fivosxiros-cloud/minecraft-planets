package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoGame;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoReward;
import me.foivos.planets.casino.CasinoScreenBase;
import me.foivos.planets.casino.CasinoStakeDialog;
import me.foivos.planets.casino.CasinoStats;
import me.foivos.planets.casino.CasinoText;
import me.foivos.planets.casino.CasinoWager;
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
 * A wheel that spins and stops.
 *
 * <p>The wheel is drawn as a ring of sections with a pointer that walks around
 * it; a spin turns the pointer a few whole laps and then one section further, so
 * where it stops was decided before it started moving. A section either pays out
 * or does not — and a section that pays out can name the prize printed on it, so
 * the thing the wheel stops on is the thing the player is given.
 *
 * <p>Everything about it is configurable under {@code casino.games.wheel}: the
 * {@code sections} (each an {@code icon}, a {@code label}, whether it
 * {@code prize}s, the {@code reward} it names and its {@code weight} — a weight
 * of 0 keeps a section on the wheel without it ever coming up), the ticks
 * between steps and how many laps a spin turns for. The shipped set is written
 * into config.yml the first time the game loads, so the wheel can be redrawn
 * from the file alone.
 *
 * <p>A spin costs nothing: a section that pays out rolls a cosmetic keepsake
 * out of the pool, and one that does not costs the round and nothing else.
 */
public final class WheelGame implements CasinoGame {

    static final String ID = "wheel";

    /** What a staked spin pays on a prize section, per coin staked. */
    static final double PRIZE_MULTIPLIER = 1.5;

    /**
     * The ring the sections are drawn in, clockwise from the top-left of the
     * middle of the menu. The wheel can hold as many sections as there are
     * places in it, which is also the most that fits over a 54-slot hub.
     */
    static final int[] RING = {12, 13, 14, 23, 32, 31, 30, 21};
    /** How many laps a spin turns across, before it walks on to its section. */
    private static final int SHIPPED_TURNS = 3;
    private static final long SHIPPED_STEP_TICKS = 2L;

    private final CasinoManager casino;
    private List<Section> sections = shipped();
    private int turns = SHIPPED_TURNS;
    private long stepTicks = SHIPPED_STEP_TICKS;

    public WheelGame(CasinoManager casino) {
        this.casino = casino;
    }

    /**
     * One place on the wheel. {@code reward} names a prize in the pool for a
     * section that pays out, or is blank to let the pool decide.
     */
    record Section(String key, Material icon, String label, boolean prize, String reward, int weight) {
    }

    /** The wheel the game ships with: five prizes and a blank, adding up to 100. */
    static List<Section> shipped() {
        return List.of(
                new Section("nothing", Material.GRAY_DYE, "&7Nothing", false, "", 40),
                new Section("penny", Material.GOLD_NUGGET, "&fLucky Penny", true, "lucky-penny", 25),
                new Section("clover", Material.OAK_SAPLING, "&fClover Sprig", true, "clover-sprig", 15),
                new Section("token", Material.IRON_NUGGET, "&fSilver Token", true, "silver-token", 10),
                new Section("gold", Material.GOLD_INGOT, "&6Golden Token", true, "golden-token", 6),
                new Section("star", Material.NETHER_STAR, "&dLucky Star", true, "lucky-star", 4));
    }

    /** Writes a wheel's sections into config.yml, one section each. */
    static void write(ConfigurationSection section, List<Section> sections) {
        for (Section wheel : sections) {
            ConfigurationSection entry = section.createSection(wheel.key());
            entry.set("icon", wheel.icon().name());
            entry.set("label", wheel.label());
            entry.set("prize", wheel.prize());
            if (wheel.reward() != null && !wheel.reward().isBlank()) {
                entry.set("reward", wheel.reward());
            }
            entry.set("weight", wheel.weight());
        }
    }

    /** Reads the sections back, in file order. */
    static List<Section> read(ConfigurationSection section) {
        List<Section> sections = new ArrayList<>();
        if (section == null) {
            return sections;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String iconName = entry.getString("icon");
            Material icon = iconName == null ? null : Material.matchMaterial(iconName);
            if (icon == null || icon == Material.AIR) {
                icon = Material.GRAY_DYE;
            }
            sections.add(new Section(key, icon,
                    entry.getString("label", "&f" + CasinoGame.prettify(key)),
                    entry.getBoolean("prize", false),
                    entry.getString("reward", ""),
                    Math.max(0, entry.getInt("weight", 1))));
        }
        return sections;
    }

    /**
     * Reads the wheel from {@code casino.games.wheel}, writing the shipped
     * sections, timings and lap count in when the file has none.
     */
    @Override
    public boolean loadConfig(ConfigurationSection section) {
        if (section == null) {
            return false;
        }
        boolean written = false;
        if (!section.isConfigurationSection("sections")) {
            write(section.createSection("sections"), shipped());
            written = true;
        }
        if (!section.contains("turns")) {
            section.set("turns", SHIPPED_TURNS);
            written = true;
        }
        if (!section.contains("step-ticks")) {
            section.set("step-ticks", SHIPPED_STEP_TICKS);
            written = true;
        }

        List<Section> configured = read(section.getConfigurationSection("sections"));
        if (configured.size() > RING.length) {
            casino.plugin().getLogger().warning("casino: the wheel can hold " + RING.length
                    + " sections and config.yml has " + configured.size()
                    + "; the last " + (configured.size() - RING.length) + " will not be drawn or landed on.");
            configured = configured.subList(0, RING.length);
        }
        sections = configured.size() >= 2 ? configured : shipped();
        turns = Math.max(1, Math.min(10, section.getInt("turns", SHIPPED_TURNS)));
        stepTicks = Math.max(1L, Math.min(20L, section.getLong("step-ticks", SHIPPED_STEP_TICKS)));
        for (Section wheel : sections) {
            if (wheel.prize() && !wheel.reward().isBlank() && !casino.hasPrize(wheel.reward())) {
                casino.plugin().getLogger().warning("casino: the wheel's '" + wheel.key()
                        + "' section promises the prize '" + wheel.reward()
                        + "', which the pool does not hold \u2014 it will roll a random prize instead.");
            }
        }
        return written;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bWheel";
    }

    @Override
    public Material icon() {
        return Material.COMPASS;
    }

    @Override
    public List<String> description() {
        return List.of("A wheel that spins and stops.",
                "&eStake coins&7 \u2014 a prize section pays " + PRIZE_MULTIPLIER + "\u00D7.");
    }

    @Override
    public void open(Player player) {
        new Screen(casino, player, sections, turns, stepTicks).open(player);
    }

    /** The game's own GUI, clicks and animation. */
    private static final class Screen extends CasinoScreenBase {

        private static final int HUB_SLOT = 22;
        private static final int STAKE_SLOT = 39;
        private static final int SPIN_SLOT = 40;
        private static final int BACK_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;

        private final List<Section> sections;
        private final int turns;
        private final long stepTicks;
        private final int weightTotal;

        /** The section the pointer is on right now. */
        private int position;
        /** The section the last spin stopped on, or null before the first spin. */
        private Section landed;
        /** What the middle of the wheel says about the last spin. */
        private String outcome = "";
        /** What the player puts on each spin; 0 is the free round. */
        private double stake = 0;

        Screen(CasinoManager casino, Player viewer, List<Section> sections, int turns,
               long stepTicks) {
            super(casino.plugin(), casino, viewer, 54,
                    CasinoText.legacy("&8\uD83C\uDFA1 Wheel", NamedTextColor.DARK_AQUA));
            this.sections = sections;
            this.turns = turns;
            this.stepTicks = stepTicks;
            int total = 0;
            for (Section section : sections) {
                total += section.weight();
            }
            this.weightTotal = total;
        }

        @Override
        protected void render() {
            inventory().clear();
            MenuStyle.decorate(inventory(), "\uD83C\uDFA1 Wheel",
                    "\uD83C\uDFAF " + payingSections() + " of " + sections.size() + " pay out");
            Player player = viewer();
            inventory().setItem(4, MenuStyle.header("Wheel", NamedTextColor.GOLD,
                    List.of(sections.size() + " sections \u00B7 " + turns + " laps a spin",
                            stake > 0
                                    ? "Stake: " + CasinoWager.money(stake) + " coins \u00B7 a prize pays "
                                            + CasinoWager.money(stake * PRIZE_MULTIPLIER)
                                    : "Free to play \u00B7 set a stake to bet")));

            for (int place = 0; place < Math.min(sections.size(), RING.length); place++) {
                inventory().setItem(RING[place], sectionItem(place, place == position));
            }
            inventory().setItem(HUB_SLOT, hubItem());
            if (player != null) {
                inventory().setItem(STAKE_SLOT, stakeItem(player));
            }
            inventory().setItem(SPIN_SLOT, spinButton());
            inventory().setItem(BACK_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private int payingSections() {
            int paying = 0;
            for (Section section : sections) {
                if (section.prize()) {
                    paying++;
                }
            }
            return paying;
        }

        /** One place on the wheel, lit up when the pointer is on it. */
        private ItemStack sectionItem(int place, boolean pointed) {
            Section section = sections.get(place);
            List<Component> lore = new ArrayList<>();
            lore.add(grey(section.prize() ? "Pays out a cosmetic prize" : "Pays out nothing"));
            lore.add(grey("Chance: " + chance(section)));
            if (pointed) {
                lore.add(grey(""));
                lore.add(CasinoText.legacy("&e\u25C0 the pointer is here", NamedTextColor.YELLOW));
            }
            return item(section.icon(),
                    CasinoText.legacy((pointed ? "&e\u25B6 " : "&7") + section.label()),
                    lore);
        }

        /** How often a section comes up, in words, for the item's lore. */
        private String chance(Section section) {
            if (weightTotal <= 0) {
                // Nothing is weighted, so the wheel falls back to even odds.
                return "even odds";
            }
            if (section.weight() <= 0) {
                return "never";
            }
            double share = section.weight() / (double) weightTotal;
            return "1 in " + Math.max(1, Math.round(1.0 / share));
        }

        /** The middle of the wheel: what it is doing, or what it just landed on. */
        private ItemStack hubItem() {
            List<Component> lore = new ArrayList<>();
            Component name;
            if (animating()) {
                name = plain("\uD83C\uDFA1 Spinning\u2026").color(NamedTextColor.YELLOW);
                lore.add(grey("The pointer is running"));
            } else if (landed != null) {
                name = CasinoText.legacy(landed.label(), sectionColour(landed));
                lore.add(CasinoText.legacy(outcome, NamedTextColor.GRAY));
            } else {
                name = plain("\uD83C\uDFA1 Ready").color(NamedTextColor.AQUA);
                lore.add(grey("Click Spin to set it going"));
            }
            return item(Material.COMPASS, name, lore);
        }

        private static NamedTextColor sectionColour(Section section) {
            return section.prize() ? NamedTextColor.GREEN : NamedTextColor.RED;
        }

        private ItemStack spinButton() {
            List<String> lore = new ArrayList<>();
            lore.add(sections.size() + " sections on the wheel");
            lore.add(stake > 0
                    ? "This spin risks " + CasinoWager.money(stake) + " coins"
                    : "A spin costs nothing");
            lore.add("");
            lore.add(animating() ? "The wheel is turning\u2026" : "Click to spin");
            return button(Material.COMPASS, "\uD83C\uDFA1 Spin the wheel", lore.toArray(new String[0]));
        }

        /** The stake button: what is on the next spin, and the dialog opener. */
        private ItemStack stakeItem(Player player) {
            List<Component> lore = new ArrayList<>();
            lore.add(grey(stake > 0
                    ? "Each spin risks " + CasinoWager.money(stake) + " coins"
                    : "The spin is free \u2014 nothing staked"));
            lore.add(grey("A prize section pays "
                    + CasinoWager.money(stake * PRIZE_MULTIPLIER) + " coins"));
            lore.add(plain(""));
            lore.add(plain("Click to change the stake").color(NamedTextColor.YELLOW));
            return item(stake > 0 ? Material.GOLD_BLOCK : Material.SUNFLOWER,
                    plain("\uD83D\uDCB0 " + (stake > 0
                            ? CasinoWager.money(stake) + " coins" : "No stake"))
                            .color(stake > 0 ? NamedTextColor.GOLD : NamedTextColor.AQUA),
                    lore);
        }

        /** Opens the stake dialog; the pick lands back on this screen. */
        private void chooseStake(Player player) {
            new CasinoStakeDialog(casino(), player,
                    CasinoText.legacy("&8\uD83C\uDFA1 Wheel \u2014 stake", NamedTextColor.DARK_AQUA),
                    chosen -> {
                        stake = chosen;
                        Player back = viewer();
                        if (back != null) {
                            open(back);
                        }
                    }).open(player);
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Spins: " + stats.plays(ID)));
            lore.add(grey("Won: " + stats.wins()));
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
                case STAKE_SLOT -> chooseStake(player);
                default -> {
                    // The frame and the read-outs do nothing.
                }
            }
        }

        /** Starts a spin: the manager records the round, then the pointer runs. */
        private void spin(Player player) {
            if (animating()) {
                casino().notice(player, "busy", "&7That round is still going \u2014 one moment.");
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            // The stake leaves the balance only when the wheel actually turns.
            if (stake > 0) {
                if (!casino().wager().canAfford(player, stake)
                        || !casino().wager().take(player, stake)) {
                    casino().notice(player, "not-enough",
                            "&cYou need &e%amount%&c coins for that spin.",
                            "%amount%", CasinoWager.money(stake));
                    return;
                }
            }
            CasinoFeedback.click(player);
            landed = null;
            outcome = "";
            int target = pick();
            // Whole laps, then on to the section: the wheel always stops where
            // the spin said it would before the pointer started moving.
            int totalSteps = turns * sections.size()
                    + Math.floorMod(target - position, sections.size());
            animating(true);
            render();

            int[] step = {0};
            every(stepTicks, stepTicks, () -> {
                step[0]++;
                position = (position + 1) % sections.size();
                CasinoFeedback.tick(player, step[0]);
                render();
                if (step[0] >= totalSteps) {
                    stopTasks();
                    settle(player, sections.get(target));
                }
            });
        }

        /** Picks the section the spin will stop on, weighted by the config. */
        private int pick() {
            if (weightTotal <= 0) {
                return ThreadLocalRandom.current().nextInt(sections.size());
            }
            int roll = ThreadLocalRandom.current().nextInt(weightTotal);
            for (int place = 0; place < sections.size(); place++) {
                Section section = sections.get(place);
                if (section.weight() <= 0) {
                    continue;
                }
                roll -= section.weight();
                if (roll < 0) {
                    return place;
                }
            }
            return sections.size() - 1;
        }

        /** Stops on the section the spin was for, and files the round. */
        private void settle(Player player, Section section) {
            landed = section;
            position = sections.indexOf(section);
            if (section.prize()) {
                casino().win(player, ID);
                CasinoFeedback.win(player);
                CasinoReward prize = casino().grant(player, ID, section.reward());
                outcome = prize == null
                        ? "&7The prize pool is empty \u2014 nothing to give."
                        : "&aPays out: &f" + prize.name() + "&a.";
                if (stake > 0) {
                    double payout = stake * PRIZE_MULTIPLIER;
                    if (casino().wager().give(player, payout)) {
                        casino().notice(player, "stake-won",
                                "&aThe wheel paid out &e%amount%&a coins.",
                                "%amount%", CasinoWager.money(payout));
                    } else {
                        casino().notice(player, "payout-failed",
                                "&cYour win of &e%amount%&c could not be paid \u2014 tell staff.",
                                "%amount%", CasinoWager.money(payout));
                    }
                }
            } else {
                casino().lose(player, ID);
                CasinoFeedback.lose(player);
                if (stake > 0) {
                    casino().notice(player, "stake-lost",
                            "&cThe wheel came up empty \u2014 your stake of &e%amount%&c is gone.",
                            "%amount%", CasinoWager.money(stake));
                }
                outcome = "&cNothing this time.";
            }
            animating(false);
            render();
        }
    }
}
