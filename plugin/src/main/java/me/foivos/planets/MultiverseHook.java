package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multiverse-Core support that has to keep working whatever version is installed.
 * <p>
 * Reading the world list stays reflective on purpose, so an old Multiverse 4.x
 * server still lists its worlds here instead of showing an empty planet menu.
 * Everything that <i>changes</i> worlds lives in {@link MultiverseWorlds}, which
 * calls Multiverse's own API — a command line can only report a failure to the
 * console, which is how a mistyped flag once made every new planet fail quietly.
 */
final class MultiverseHook {

    private MultiverseHook() {
    }

    /** Whether a Multiverse-Core plugin is currently loaded on the server. */
    static boolean isPresent() {
        return Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null;
    }

    /**
     * All worlds Multiverse currently has loaded, as planets. Returns an empty
     * list when Multiverse is absent or its API could not be read.
     */
    static List<Planet> planets() {
        List<Planet> planets = new ArrayList<>();
        if (!isPresent()) {
            return planets;
        }
        try {
            if (!tryMultiverse5(planets)) {
                tryMultiverse4(planets);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Bukkit.getLogger().warning("[planets] Could not read the Multiverse world list: " + ex.getMessage());
        }
        return planets;
    }

    /**
     * Makes Multiverse forget a world for good. Unloading a world leaves its entry
     * in Multiverse's own world list, so on the next restart Multiverse re-creates
     * it (empty) and the "deleted" planet shows up in the menus again. This removes
     * that entry through Multiverse's API and then strips the world out of
     * Multiverse's own config file, so nothing can resurrect it.
     *
     * @return whether Multiverse (or at least its config) no longer knows the world.
     */
    static boolean forget(String worldName) {
        if (worldName == null || worldName.isBlank() || !isPresent()) {
            return false;
        }
        MultiverseWorlds.Outcome removed = MultiverseWorlds.forget(worldName);
        boolean cleaned = stripFromConfig(worldName);
        if (removed.ok()) {
            Bukkit.getLogger().info("[planets] Multiverse no longer knows '" + worldName + "'.");
        } else {
            Bukkit.getLogger().warning("[planets] Could not remove '" + worldName
                    + "' from Multiverse's world list (" + removed.detail() + ").");
        }
        if (cleaned) {
            Bukkit.getLogger().info("[planets] Dropped '" + worldName + "' from Multiverse's worlds.yml too.");
        }
        if (!removed.ok() && !cleaned) {
            Bukkit.getLogger().warning("[planets] Multiverse may still remember '" + worldName
                    + "' — it would come back empty on the next restart.");
        }
        return removed.ok() || cleaned;
    }

    /**
     * Removes the world's entry from Multiverse's own config file, so a restart
     * cannot re-create the world from it. Best effort: Multiverse versions keep
     * this list in {@code worlds.yml} inside their plugin folder.
     */
    private static boolean stripFromConfig(String worldName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (plugin == null) {
            return false;
        }
        boolean cleaned = false;
        for (String fileName : new String[]{"worlds.yml", "worlds.yaml"}) {
            File file = new File(plugin.getDataFolder(), fileName);
            if (!file.isFile()) {
                continue;
            }
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                ConfigurationSection worlds = config.getConfigurationSection("worlds");
                if (worlds == null) {
                    continue;
                }
                boolean changed = false;
                for (String key : new ArrayList<>(worlds.getKeys(false))) {
                    if (key.equalsIgnoreCase(worldName)) {
                        worlds.set(key, null);
                        changed = true;
                    }
                }
                if (changed) {
                    config.save(file);
                    cleaned = true;
                }
            } catch (IOException | RuntimeException ex) {
                Bukkit.getLogger().warning("[planets] Could not clean " + fileName + ": " + ex.getMessage());
            }
        }
        return cleaned;
    }

    /** Multiverse 5.x: {@code org.mvplugins.multiverse.core.MultiverseCoreApi}. */
    private static boolean tryMultiverse5(List<Planet> planets) throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("org.mvplugins.multiverse.core.MultiverseCoreApi");
        if (!(boolean) apiClass.getMethod("isLoaded").invoke(null)) {
            Bukkit.getLogger().warning("[planets] Multiverse-Core 5.x is installed but its API is not initialized yet.");
            return true;
        }
        Object api = apiClass.getMethod("get").invoke(null);
        Object worldManager = api.getClass().getMethod("getWorldManager").invoke(api);
        Object worlds = worldManager.getClass().getMethod("getLoadedWorlds").invoke(worldManager);
        addWorlds(planets, worlds, true);
        return true;
    }

    /** Multiverse 4.x: {@code com.onarandombox.multiversecore.MultiverseCore}. */
    private static void tryMultiverse4(List<Planet> planets) throws ReflectiveOperationException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        Object worldManager = plugin.getClass().getMethod("getMVWorldManager").invoke(plugin);
        Object worlds = worldManager.getClass().getMethod("getMVWorlds").invoke(worldManager);
        addWorlds(planets, worlds, false);
    }

    private static void addWorlds(List<Planet> planets, Object worlds, boolean multiverse5)
            throws ReflectiveOperationException {
        if (!(worlds instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object world : iterable) {
            if (world == null) {
                continue;
            }
            if ((boolean) world.getClass().getMethod("isHidden").invoke(world)) {
                continue; // Multiverse marks this world as hidden from world lists
            }
            String name = (String) world.getClass().getMethod("getName").invoke(world);
            String aliasMethod = multiverse5 ? "getAliasOrName" : "getAlias";
            String alias = (String) world.getClass().getMethod(aliasMethod).invoke(world);
            String display = alias == null || alias.isBlank() ? name : alias;
            Object environment = world.getClass().getMethod("getEnvironment").invoke(world);
            String environmentName = environment == null ? null : environment.toString();
            // Only the vanilla nether/end worlds (or their per-world folders like
            // "world_nether" / "world_the_end") get the canonical "Nether"/"End"
            // labels. Custom planets with a nether or end environment keep their
            // own name, e.g. a "Lava_planet" created as nether.
            if (environmentName != null && isVanillaDimension(name, environmentName)) {
                display = switch (environmentName) {
                    case "NETHER" -> "Nether";
                    case "THE_END" -> "End";
                    default -> display;
                };
            }
            planets.add(new Planet(display, iconFor(environmentName), name));
        }
    }

    /**
     * Whether the world looks like the vanilla nether/end dimension belonging to
     * a primary world: Bukkit names the per-world dimensions "&lt;world&gt;_nether"
     * and "&lt;world&gt;_the_end" (a plain "nether"/"end"/"the_end" also counts).
     */
    private static boolean isVanillaDimension(String name, String environmentName) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (environmentName) {
            case "NETHER" -> lower.equals("nether") || lower.endsWith("_nether");
            case "THE_END" -> lower.equals("end") || lower.equals("the_end") || lower.endsWith("_the_end");
            default -> false;
        };
    }

    private static Material iconFor(String environmentName) {
        if (environmentName == null) {
            return Material.GRASS_BLOCK;
        }
        return switch (environmentName) {
            case "NETHER" -> Material.NETHERRACK;
            case "THE_END" -> Material.END_STONE;
            case "CUSTOM" -> Material.CHORUS_PLANT;
            default -> Material.GRASS_BLOCK;
        };
    }
}
