package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.utils.result.FailureReason;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.DeleteWorldOptions;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.RegenWorldOptions;
import org.mvplugins.multiverse.core.world.options.UnloadWorldOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every world operation that used to be a {@code /mv create|unload|delete|regen|
 * load|import} console command, now a call into Multiverse-Core's own API.
 * <p>
 * The console commands had two problems: Multiverse parses them as text, so an
 * unsupported flag made the whole command fail ("--diameter is not a valid flag"
 * once meant <i>every</i> new planet failed), and the explanation was only ever
 * printed to the console, where nobody looks. The API reports the result to the
 * caller instead, so every operation here hands back an {@link Outcome} whose
 * {@link Outcome#detail()} can be shown to the player who asked for it.
 * <p>
 * Nothing touches Multiverse's classes until {@link MultiverseHook#isPresent()}
 * has passed, so a server without Multiverse never links any of them.
 */
final class MultiverseWorlds {

    private MultiverseWorlds() {
    }

    /**
     * The result of a world operation: whether it worked, a reason to show the
     * player when it didn't, and the world it produced when it produced one.
     */
    record Outcome(boolean ok, String detail, World world) {

        static Outcome success(World world) {
            return new Outcome(true, "", world);
        }

        static Outcome failure(String detail) {
            return new Outcome(false, detail, null);
        }
    }

    /**
     * A world found through Multiverse: whether it exists at all, whether it is
     * loaded right now, and its Bukkit world when it is.
     */
    record Target(boolean known, boolean loaded, String worldName, String displayName, World world) {

        static Target unknown() {
            return new Target(false, false, null, null, null);
        }
    }

    /**
     * Resolves a world by name or alias through Multiverse, loaded or not. This
     * is what makes "/planets world &lt;planet&gt; ..." work for worlds the
     * plugin's own menus hide or hold no loaded copy of: a player planet, a
     * lobby, a hidden world, or a world only known by its Multiverse alias (the
     * plugin's own name matching can never guess that "test" is a world called
     * "normal").
     */
    static Target resolve(String query) {
        return callSafe(() -> Api.resolve(query), Target.unknown());
    }

    /** Every world name and alias Multiverse knows, loaded or not, for suggestions. */
    static List<String> knownNames() {
        return callSafe(Api::knownNames, List.of());
    }

    /**
     * Creates a new world and generates it.
     *
     * @param flat whether to create a superflat world (used for station planets).
     */
    static Outcome create(String name, World.Environment environment, boolean flat, boolean generateStructures) {
        return call(() -> Api.create(name, environment, flat, generateStructures));
    }

    /** Loads a world Multiverse knows about but has unloaded. */
    static Outcome load(String name) {
        return call(() -> Api.load(name));
    }

    /** Unloads a world, keeping its entry in Multiverse's own world list. */
    static Outcome unload(String name) {
        return call(() -> Api.unload(name));
    }

    /** Deletes a world outright: unloads it, forgets it and deletes its folder. */
    static Outcome delete(String name) {
        return call(() -> Api.delete(name));
    }

    /** Makes Multiverse forget a world while leaving its files alone. */
    static Outcome forget(String name) {
        return call(() -> Api.forget(name));
    }

    /**
     * Regenerates a world from scratch, destroying its contents.
     *
     * @param seed {@code null} to keep the world's current seed, {@code "random"}
     *             for a fresh one, otherwise the seed to use.
     */
    static Outcome regenerate(String name, String seed) {
        return call(() -> Api.regenerate(name, seed));
    }

    /** Registers a world folder that already exists on disk with Multiverse. */
    static Outcome importWorld(String name, World.Environment environment) {
        return call(() -> Api.importWorld(name, environment));
    }

    /**
     * Runs one Multiverse operation, turning everything that used to be an
     * exception or a silent console line into an ordinary failure: a missing
     * Multiverse, an API that isn't ready, or a Multiverse version whose API has
     * methods and classes this build doesn't know (a {@code LinkageError}).
     * <p>
     * The API itself lives in {@link Api} on purpose: this class — and therefore
     * every caller — loads without a single Multiverse class on the classpath,
     * and {@link Api} is only loaded once Multiverse is actually installed.
     */
    private static Outcome call(Supplier<Outcome> operation) {
        if (!MultiverseHook.isPresent()) {
            return Outcome.failure("Multiverse-Core is not installed");
        }
        try {
            if (!Api.ready()) {
                return Outcome.failure("Multiverse-Core's API is not ready yet — try again in a moment");
            }
            return operation.get();
        } catch (RuntimeException | LinkageError ex) {
            return Outcome.failure("the installed Multiverse-Core version does not support this (" + ex + ")");
        }
    }

    /**
     * Runs one Multiverse read, falling back to a default value when Multiverse
     * is missing, not ready, or a version whose API this build doesn't know.
     */
    private static <T> T callSafe(Supplier<T> operation, T fallback) {
        if (!MultiverseHook.isPresent()) {
            return fallback;
        }
        try {
            if (!Api.ready()) {
                return fallback;
            }
            return operation.get();
        } catch (RuntimeException | LinkageError ex) {
            return fallback;
        }
    }

    /** The Multiverse API calls; loaded only when Multiverse is present. */
    private static final class Api {

        static boolean ready() {
            return MultiverseCoreApi.isLoaded();
        }

        private static WorldManager worlds() {
            return MultiverseCoreApi.get().getWorldManager();
        }

        static Target resolve(String query) {
            MultiverseWorld world = worlds().getWorldByNameOrAlias(query).getOrNull();
            if (world == null && !query.contains(":")) {
                // Paper 26.1+ keeps a world's folder under its namespaced key
                // ("minecraft:nebula"); Multiverse answers to the short name, but
                // accept the key as typed too, so both forms work.
                world = worlds().getWorld("minecraft:" + query).getOrNull();
            }
            if (world == null) {
                return Target.unknown();
            }
            LoadedMultiverseWorld loaded = world.asLoadedWorld().getOrNull();
            if (loaded == null) {
                return new Target(true, false, world.getName(), world.getAliasOrName(), null);
            }
            World bukkit = loaded.getBukkitWorld().getOrNull();
            if (bukkit == null) {
                bukkit = Bukkit.getWorld(world.getName());
            }
            return new Target(true, bukkit != null, world.getName(), world.getAliasOrName(), bukkit);
        }

        static List<String> knownNames() {
            List<String> names = new ArrayList<>();
            for (MultiverseWorld world : worlds().getWorlds()) {
                names.add(world.getName());
                String alias = world.getAlias();
                if (alias != null && !alias.isBlank()) {
                    names.add(alias);
                }
            }
            return names;
        }

        static Outcome create(String name, World.Environment environment, boolean flat, boolean generateStructures) {
            // doFolderCheck is on by default: a leftover folder is reported as
            // WORLD_EXIST_FOLDER instead of being loaded over.
            var attempt = worlds().createWorld(CreateWorldOptions.worldName(name)
                    .environment(environment)
                    .worldType(flat ? WorldType.FLAT : WorldType.NORMAL)
                    .generateStructures(generateStructures));
            return created(attempt);
        }

        static Outcome load(String name) {
            return created(worlds().loadWorld(name));
        }

        static Outcome importWorld(String name, World.Environment environment) {
            return created(worlds().importWorld(
                    ImportWorldOptions.worldName(name).environment(environment)));
        }

        static Outcome regenerate(String name, String seed) {
            LoadedMultiverseWorld world = worlds().getLoadedWorld(name).getOrNull();
            if (world == null) {
                return Outcome.failure("the world is not loaded, so it can't be regenerated");
            }
            RegenWorldOptions options = RegenWorldOptions.world(world);
            if (seed == null || seed.isBlank()) {
                // No seed at all: Multiverse regenerates with the world's own seed.
            } else if (seed.equalsIgnoreCase("random")) {
                options.randomSeed(true);
            } else {
                options.seed(seed);
            }
            return created(worlds().regenWorld(options));
        }

        static Outcome unload(String name) {
            LoadedMultiverseWorld world = worlds().getLoadedWorld(name).getOrNull();
            if (world == null) {
                return Outcome.success(null); // Already unloaded — nothing to do.
            }
            return done(worlds().unloadWorld(UnloadWorldOptions.world(world)));
        }

        static Outcome delete(String name) {
            MultiverseWorld world = worlds().getWorld(name).getOrNull();
            if (world == null) {
                // Unknown to Multiverse (already removed, or only a stray folder on
                // disk): the caller's own folder check can still clean it up.
                return Outcome.failure("Multiverse doesn't know a world called '" + name + "'");
            }
            return done(worlds().deleteWorld(DeleteWorldOptions.world(world)));
        }

        static Outcome forget(String name) {
            return done(worlds().removeWorld(name));
        }

        /** Unwraps an attempt that produced a world. */
        private static <F extends FailureReason> Outcome created(Attempt<LoadedMultiverseWorld, F> attempt) {
            if (attempt.isFailure()) {
                return Outcome.failure(explain(attempt));
            }
            return Outcome.success(attempt.get().getBukkitWorld().getOrNull());
        }

        /** Unwraps an attempt that only reports whether it worked. */
        private static <T, F extends FailureReason> Outcome done(Attempt<T, F> attempt) {
            return attempt.isFailure() ? Outcome.failure(explain(attempt)) : Outcome.success(null);
        }

        /**
         * Multiverse's reason for a failure, in words that can be shown to a
         * player. Reasons this build doesn't know fall back to Multiverse's own
         * (localised) message, so a Multiverse update can never produce a blank one.
         */
        private static String explain(Attempt<?, ?> attempt) {
            FailureReason reason = attempt.getFailureReason();
            String known = switch (nameOf(reason)) {
                case "INVALID_WORLDNAME" -> "the world name is not valid";
                case "WORLD_EXIST_FOLDER" -> "a folder with that name is already on disk";
                case "WORLD_EXIST_UNLOADED" -> "Multiverse already has a world with that name (it is unloaded)";
                case "WORLD_EXIST_LOADED" -> "a world with that name is already running";
                case "WORLD_CREATOR_FAILED" -> "the server could not generate the world";
                case "LOAD_FAILED" -> "the world could not be loaded from its folder";
                case "REMOVE_FAILED", "FAILED_TO_DELETE_FOLDER" -> "the world could not be removed from disk";
                case "DELETE_FAILED" -> "the old world could not be deleted";
                case "CREATE_FAILED" -> "the new world could not be generated";
                case "EVENT_CANCELLED" -> "another plugin cancelled it";
                default -> "";
            };
            if (!known.isEmpty()) {
                return known;
            }
            try {
                return attempt.getFailureMessage().formatted();
            } catch (RuntimeException ex) {
                return nameOf(reason);
            }
        }

        private static String nameOf(FailureReason reason) {
            return reason instanceof Enum<?> constant ? constant.name() : String.valueOf(reason);
        }
    }
}
