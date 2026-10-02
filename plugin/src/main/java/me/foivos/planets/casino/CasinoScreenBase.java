package me.foivos.planets.casino;

import me.foivos.planets.Planets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What every casino screen has in common: an inventory of its own, a viewer it
 * only remembers by uuid, the animations it is running, and the small items
 * every screen wants (a name, a lore line, a Close button).
 *
 * <p>Keeping the animations here is what stops them leaking. A screen hands
 * its delays to {@link #after} and {@link #every}; when the screen is closed,
 * when the viewer logs out mid-animation, or when the round ends, the tasks it
 * started are cancelled. Nothing keeps a {@link Player} instance either — the
 * viewer is held as a uuid and resolved on use, so a screen that somehow
 * outlives a logout cannot pin a player in memory. (Bukkit cancels whatever is
 * left on plugin shutdown.)
 */
public abstract class CasinoScreenBase implements CasinoScreen {

    private final Planets plugin;
    private final CasinoManager casino;
    private final UUID viewerId;
    private final Inventory inventory;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean animating;
    private boolean closed;

    protected CasinoScreenBase(Planets plugin, CasinoManager casino, Player viewer, int size,
                               Component title) {
        this.plugin = plugin;
        this.casino = casino;
        this.viewerId = viewer.getUniqueId();
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    /** Draws the screen and shows it to the player. */
    public final void open(Player player) {
        closed = false;
        render();
        player.openInventory(inventory);
        CasinoFeedback.open(player);
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    /** Paints the whole screen. Called on open and after every click that changes it. */
    protected abstract void render();

    /** Called after the animations have been stopped, when the screen closes. */
    protected void onClosed() {
    }

    // ── The viewer ──────────────────────────────────────────────────────

    /** The player this screen belongs to, or null once they have logged out. */
    protected final Player viewer() {
        return Bukkit.getPlayer(viewerId);
    }

    protected final UUID viewerId() {
        return viewerId;
    }

    protected final Planets plugin() {
        return plugin;
    }

    protected final CasinoManager casino() {
        return casino;
    }

    protected final Inventory inventory() {
        return inventory;
    }

    // ── Clicks ──────────────────────────────────────────────────────────

    /**
     * The first line of every screen's click handling: the click is cancelled
     * so nothing can be pulled out of the GUI, and the raw slot is only
     * answered for clicks on the screen itself — anything in the player's own
     * inventory answers -1.
     */
    protected final int clickedSlot(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inventory.getSize()) {
            return -1;
        }
        return raw;
    }

    // ── Animations ──────────────────────────────────────────────────────

    /** Whether a round is being animated, so clicks should be ignored. */
    protected final boolean animating() {
        return animating;
    }

    protected final void animating(boolean value) {
        this.animating = value;
    }

    /**
     * Runs {@code body} once, {@code delay} ticks from now, and remembers the
     * task so it dies with the screen.
     */
    protected final BukkitTask after(long delay, Runnable body) {
        return track(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (alive()) {
                body.run();
            }
        }, Math.max(1L, delay)));
    }

    /**
     * Runs {@code body} over and over, and stops on its own when the screen
     * closes or the viewer leaves.
     */
    protected final BukkitTask every(long delay, long period, Runnable body) {
        return track(plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!alive()) {
                stopTasks();
                return;
            }
            body.run();
        }, Math.max(1L, delay), Math.max(1L, period)));
    }

    private boolean alive() {
        return !closed && viewer() != null;
    }

    private BukkitTask track(BukkitTask task) {
        tasks.add(task);
        return task;
    }

    /** Cancels every animation this screen started. */
    protected final void stopTasks() {
        for (BukkitTask task : tasks) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        tasks.clear();
        animating = false;
    }

    @Override
    public final void onClose(Player player) {
        closed = true;
        stopTasks();
        onClosed();
    }

    protected final boolean closed() {
        return closed;
    }

    // ── Standard items ──────────────────────────────────────────────────

    /** An item with a name and lore, in the casino's own voice. */
    protected final ItemStack item(Material icon, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** A button: an aqua name over grey lore, the shape every menu uses. */
    protected final ItemStack button(Material icon, String name, String... loreLines) {
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(plain(line));
        }
        return item(icon, CasinoText.legacy(name, NamedTextColor.AQUA), lore);
    }

    /** The standard Close button. */
    protected final ItemStack closeButton() {
        return item(Material.BARRIER, plain("\u2716 Close").color(NamedTextColor.GRAY),
                List.of());
    }

    /** The standard Back button, with the screen it goes back to. */
    protected final ItemStack backButton(String label) {
        return item(Material.ARROW, plain("\u2B05 " + label).color(NamedTextColor.GRAY),
                List.of());
    }

    /** A white, non-italic line, for simple text that needs no colour of its own. */
    protected static Component plain(String text) {
        return Component.text(text).color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** A grey, non-italic line, the ordinary colour of menu lore. */
    protected static Component grey(String text) {
        return Component.text(text).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
