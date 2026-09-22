package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the world list from Multiverse-Core without any compile-time dependency
 * on it, so this plugin can never throw a {@code NoClassDefFoundError} when
 * Multiverse is missing or a different version is installed.
 * <p>
 * Supports both Multiverse-Core 5.x ({@code org.mvplugins.multiverse.core})
 * and the older 4.x ({@code com.onarandombox.multiversecore}) APIs.
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
     * Makes Multiverse forget a world for good. Unloading a world leaves its
     * entry in Multiverse's own world list, so on the next restart Multiverse
     * re-creates it (empty) and the "deleted" planet shows up in the menus
     * again. This removes the entry through the Multiverse API when it is
     * available, falls back to Multiverse's own commands, and finally strips the
     * world out of Multiverse's config file so nothing can resurrect it.
     *
     * @return whether Multiverse (or at least its config) no longer knows the world.
     */
    static boolean forget(String worldName) {
        if (worldName == null || worldName.isBlank() || !isPresent()) {
            return false;
        }
        boolean forgotten = false;
        try {
            forgotten = forgetViaApi(worldName);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Bukkit.getLogger().warning("[planets] Could not remove '" + worldName
                    + "' from Multiverse's world list: " + ex.getMessage());
        }
        if (!forgotten) {
            forgotten = forgetViaCommands(worldName);
        }
        boolean cleaned = stripFromConfig(worldName);
        if (forgotten || cleaned) {
            Bukkit.getLogger().info("[planets] Multiverse now ignores '" + worldName
                    + "' — the deleted world can't come back on the next restart.");
        } else {
            Bukkit.getLogger().warning("[planets] Could not make Multiverse forget '" + worldName
                    + "' — remove it there manually if it reappears (e.g. /mv delete " + worldName + ").");
        }
        return forgotten || cleaned;
    }

    private static boolean forgetViaApi(String worldName) throws ReflectiveOperationException {
        return tryForget5(worldName) || tryForget4(worldName);
    }

    /** Multiverse 5.x: {@code WorldManager#deleteWorld(MVWorld)} / {@code #removeWorld(MVWorld)}. */
    private static boolean tryForget5(String worldName) throws ReflectiveOperationException {
        Class<?> apiClass;
        try {
            apiClass = Class.forName("org.mvplugins.multiverse.core.MultiverseCoreApi");
        } catch (ClassNotFoundException notFive) {
            return false;
        }
        if (!(boolean) apiClass.getMethod("isLoaded").invoke(null)) {
            return false;
        }
        Object api = apiClass.getMethod("get").invoke(null);
        Object worldManager = api.getClass().getMethod("getWorldManager").invoke(api);
        Object world = findWorld(worldManager, worldName);
        if (world == null) {
            return false;
        }
        return invokeSingleArg(worldManager, world, "deleteWorld")
                || invokeSingleArg(worldManager, world, "removeWorld");
    }

    /** Multiverse 4.x: {@code MVWorldManager#deleteWorld(String)} / {@code #removeWorld(String)}. */
    private static boolean tryForget4(String worldName) throws ReflectiveOperationException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (plugin == null) {
            return false;
        }
        Object worldManager;
        try {
            worldManager = plugin.getClass().getMethod("getMVWorldManager").invoke(plugin);
        } catch (NoSuchMethodException notFour) {
            return false;
        }
        return invokeSingleArg(worldManager, worldName, "deleteWorld")
                || invokeSingleArg(worldManager, worldName, "removeWorld");
    }

    /** Calls {@code methodName(argument)} on the target, whatever the version's signature is. */
    private static boolean invokeSingleArg(Object target, Object argument, String methodName) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isInstance(argument)) {
                continue;
            }
            try {
                method.invoke(target, argument);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next overload / fall back to the command path.
            }
        }
        return false;
    }

    /** Looks a Multiverse world object up by name, unwrapping MV5's {@code Optional}. */
    private static Object findWorld(Object worldManager, String worldName) {
        for (Method method : worldManager.getClass().getMethods()) {
            if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(worldName)) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.equals("getworld") && !name.equals("getworldbyname")) {
                continue;
            }
            try {
                Object result = method.invoke(worldManager, worldName);
                if (result instanceof Optional<?> optional) {
                    return optional.orElse(null);
                }
                if (result != null) {
                    return result;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    /** Last-resort fallback: run Multiverse's own remove/delete commands. */
    private static boolean forgetViaCommands(String worldName) {
        CommandSender console = Bukkit.getConsoleSender();
        boolean dispatched = false;
        try {
            dispatched = Bukkit.dispatchCommand(console, "mv remove " + worldName);
        } catch (RuntimeException ignored) {
        }
        try {
            // Multiverse asks for confirmation before deleting; answer it now.
            dispatched |= Bukkit.dispatchCommand(console, "mv delete " + worldName);
            dispatched |= Bukkit.dispatchCommand(console, "mv confirm");
        } catch (RuntimeException ignored) {
        }
        return dispatched;
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
