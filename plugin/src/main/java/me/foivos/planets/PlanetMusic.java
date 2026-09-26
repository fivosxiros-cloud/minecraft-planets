package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The planet soundtrack: calm vanilla music plays while a player stands on a
 * planet or in a lobby, with a pause between tracks, and only while that
 * player's "Planet Music" toggle in {@code /settings} is on. It is on by
 * default, like the other planet settings, so the soundtrack is there for
 * everyone who doesn't switch it off.
 *
 * <p>What plays where comes from the {@code music} section of config.yml:
 * every world may list its own tracks and {@code default} covers the rest of
 * the plugin's worlds (its planets and lobbies — never a vanilla world). A
 * track names any {@link Sound} constant, e.g. {@code MUSIC_OVERWORLD_FOREST}
 * or {@code music.end}, plus roughly how many seconds it lasts, so the plugin
 * knows when it may look for the next one; a track written on its own falls
 * back to {@link #DEFAULT_TRACK_SECONDS}. When config.yml has no music section
 * at all, {@link #BUILT_IN_TRACKS} is used, so the feature also works on a
 * config written before it existed.
 *
 * <p>Music is never layered: starting a track first stops whatever the client
 * was playing, so the vanilla background music doesn't play over a planet's
 * own. Everything goes out on the MUSIC channel, which also means the player's
 * own "Music" volume slider still has the final say.
 */
final class PlanetMusic {

    /** The config key whose track list covers every world without one of its own. */
    private static final String DEFAULT_KEY = "default";
    /** Assumed length of a track that doesn't say how long it is. */
    private static final long DEFAULT_TRACK_SECONDS = 240L;
    /** A track is never booked as shorter than this, so a typo can't loop a sound. */
    private static final long MIN_TRACK_SECONDS = 10L;
    /** How often every player is looked at, in ticks (once a second). */
    private static final long CHECK_INTERVAL_TICKS = 20L;

    /**
     * What plays when config.yml lists no tracks at all. The entries use the
     * same "sound:seconds" form the config accepts, so they are read by the
     * same parser as everything else.
     */
    private static final List<String> BUILT_IN_TRACKS = List.of(
            "MUSIC_END:170",
            "MUSIC_OVERWORLD_DRIPSTONE_CAVES:260",
            "MUSIC_OVERWORLD_LUSH_CAVES:300",
            "MUSIC_OVERWORLD_DEEP_DARK:220",
            "MUSIC_UNDER_WATER:140");

    private final Planets plugin;
    private final Random random = new Random();

    /** lowercase world name -> the tracks that may play there (may be empty). */
    private final Map<String, List<Track>> tracks = new HashMap<>();
    /** Players with a track running, or waiting for their next one. */
    private final Map<UUID, Now> playing = new ConcurrentHashMap<>();

    /** Tracks for every plugin world without a list of its own. */
    private List<Track> fallback = List.of();
    private boolean enabled = true;
    private float volume = 0.6f;
    private long gapMinMillis = 60_000L;
    private long gapMaxMillis = 180_000L;

    PlanetMusic(Planets plugin) {
        this.plugin = plugin;
    }

    /** One music track: the vanilla sound to play and roughly how long it runs. */
    private record Track(Sound sound, long seconds) {
    }

    /** What one player has going on: the world it started in and the next start time. */
    private record Now(String world, long nextAt) {
    }

    // ── Configuration ───────────────────────────────────────────────────

    /** Re-reads the {@code music} section of config.yml. */
    void loadConfig(FileConfiguration config) {
        tracks.clear();
        fallback = parse(BUILT_IN_TRACKS, "the built-in track list");
        enabled = true;
        volume = 0.6f;
        gapMinMillis = 60_000L;
        gapMaxMillis = 180_000L;

        ConfigurationSection section = config.getConfigurationSection("music");
        if (section == null) {
            // A config written before the soundtrack existed: the built-in list
            // above keeps planets musical, and every player can still switch it
            // off for themselves in /settings.
            return;
        }
        enabled = section.getBoolean("enabled", true);
        volume = (float) Math.min(1.0, Math.max(0.0, section.getDouble("volume", 0.6)));
        gapMinMillis = millis(section.getDouble("gap-min-seconds", 60.0));
        gapMaxMillis = Math.max(gapMinMillis, millis(section.getDouble("gap-max-seconds", 180.0)));

        ConfigurationSection lists = section.getConfigurationSection("tracks");
        if (lists == null) {
            return;
        }
        for (String world : lists.getKeys(false)) {
            List<Track> parsed = parse(lists.getList(world), "music.tracks." + world);
            if (DEFAULT_KEY.equalsIgnoreCase(world)) {
                if (!parsed.isEmpty()) {
                    fallback = parsed;
                }
                continue;
            }
            // An empty list is kept on purpose: it silences that world.
            tracks.put(world.toLowerCase(Locale.ROOT), parsed);
        }
    }

    /**
     * Parses one configured track list. Every entry is a sound name on its own
     * ({@code MUSIC_END}), a sound with its length ({@code MUSIC_END:170}) or a
     * small map ({@code sound: ...} plus {@code seconds: ...}).
     */
    private List<Track> parse(List<?> entries, String where) {
        List<Track> parsed = new ArrayList<>();
        if (entries == null) {
            return parsed;
        }
        for (Object entry : entries) {
            Track track = parseEntry(entry, where);
            if (track != null) {
                parsed.add(track);
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * Parses a single entry: a sound name on its own ({@code MUSIC_END}), a
     * sound with its length ({@code MUSIC_END:170}) or a small map. Returns
     * null — with a warning — when the entry names no known sound.
     */
    private Track parseEntry(Object entry, String where) {
        String name = null;
        long seconds = DEFAULT_TRACK_SECONDS;
        if (entry instanceof String text) {
            int colon = text.indexOf(':');
            name = colon < 0 ? text : text.substring(0, colon);
            if (colon >= 0) {
                try {
                    seconds = Long.parseLong(text.substring(colon + 1).trim());
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("music: '" + text + "' in " + where
                            + " doesn't end in a number of seconds — using " + DEFAULT_TRACK_SECONDS + " for it.");
                }
            }
        } else if (entry instanceof Map<?, ?> map) {
            Object sound = map.get("sound");
            name = sound == null ? null : String.valueOf(sound);
            if (map.get("seconds") instanceof Number number) {
                seconds = number.longValue();
            }
        }
        Sound sound = sound(name);
        if (sound == null) {
            if (name != null && !name.isBlank()) {
                plugin.getLogger().warning("music: '" + name + "' in " + where
                        + " is not a known sound — skipping that track.");
            }
            return null;
        }
        return new Track(sound, Math.max(MIN_TRACK_SECONDS, seconds));
    }

    /** Resolves a configured sound name ({@code MUSIC_END}, {@code music.end}, {@code music_end}). */
    private static Sound sound(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toUpperCase(Locale.ROOT)
                .replace('.', '_').replace('-', '_').replace(' ', '_');
        try {
            return Sound.valueOf(key);
        } catch (RuntimeException ex) {
            // Unknown sound, or one this server build doesn't have.
            return null;
        }
    }

    private static long millis(double seconds) {
        return Math.max(MIN_TRACK_SECONDS * 1000L, Math.round(seconds * 1000.0));
    }

    // ── Playing ─────────────────────────────────────────────────────────

    /** Starts the once-a-second scheduler that keeps everyone's music going. */
    void start(JavaPlugin owner) {
        Bukkit.getScheduler().runTaskTimer(owner, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Set<String> pluginWorlds = null; // built once per cycle, and only if a player needs it
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (world == null) {
                continue;
            }
            if (pluginWorlds == null) {
                pluginWorlds = plugin.pluginWorldNames();
            }
            String key = world.getName().toLowerCase(Locale.ROOT);
            List<Track> list = tracksFor(key, pluginWorlds);
            if (!enabled || list.isEmpty()
                    || !PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.MUSIC)) {
                stop(player); // no-op unless this player had music of ours running
                continue;
            }
            Now state = playing.get(player.getUniqueId());
            if (state != null && !state.world().equals(key)) {
                // A different planet: its music starts now instead of waiting
                // out the pause that belonged to the previous world.
                stop(player);
                state = null;
            }
            if (state == null || now >= state.nextAt()) {
                play(player, list, key, now);
            }
        }
        // Forget players who logged out mid-track.
        playing.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Starts a random track of the list for a player and books the next one. */
    private void play(Player player, List<Track> list, String worldKey, long now) {
        Track track = list.get(random.nextInt(list.size()));
        // Silence the vanilla background music first, so the two never overlap.
        player.stopSound(SoundCategory.MUSIC);
        player.playSound(player.getLocation(), track.sound(), SoundCategory.MUSIC, volume, 1.0f);
        playing.put(player.getUniqueId(), new Now(worldKey, now + track.seconds() * 1000L + gap()));
    }

    /** A random pause between two tracks, from the configured range. */
    private long gap() {
        if (gapMaxMillis <= gapMinMillis) {
            return gapMinMillis;
        }
        return gapMinMillis + (long) (random.nextDouble() * (gapMaxMillis - gapMinMillis));
    }

    /** Silences this player's music right away (the /settings toggle going off). */
    void stop(Player player) {
        if (playing.remove(player.getUniqueId()) != null) {
            player.stopSound(SoundCategory.MUSIC);
        }
    }

    /**
     * Plays a track for this player right away, when their world has music and
     * they still have it switched on (the /settings toggle going back on).
     */
    void startNow(Player player) {
        if (!enabled || !PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.MUSIC)) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        String key = world.getName().toLowerCase(Locale.ROOT);
        List<Track> list = tracksFor(key, plugin.pluginWorldNames());
        if (!list.isEmpty()) {
            play(player, list, key, System.currentTimeMillis());
        }
    }

    /** Whether the server has the soundtrack switched on at all ({@code music.enabled}). */
    boolean enabled() {
        return enabled;
    }

    // ── The in-game editor's view of the music ──────────────────────────

    /** The length a track gets when the editor adds one. */
    static long defaultTrackSeconds() {
        return DEFAULT_TRACK_SECONDS;
    }

    /**
     * A world's own track list, in the {@code SOUND:seconds} form config.yml
     * uses, so the editor can show and rewrite it. Empty when the world has no
     * list of its own (it plays the shared default one instead).
     */
    List<String> ownTrackSpecs(String worldName) {
        List<Track> list = tracks.get(worldName.toLowerCase(Locale.ROOT));
        return list == null ? List.of() : list.stream().map(PlanetMusic::spec).toList();
    }

    /** Whether the world has a list of its own (an empty one silences the world). */
    boolean hasOwnTracks(String worldName) {
        return tracks.containsKey(worldName.toLowerCase(Locale.ROOT));
    }

    /** The shared default list, in the same form the editor writes. */
    List<String> defaultTrackSpecs() {
        return fallback.stream().map(PlanetMusic::spec).toList();
    }

    /** Turns a parsed track back into the config form ({@code MUSIC_END:170}). */
    private static String spec(Track track) {
        return track.sound().name() + ":" + track.seconds();
    }

    /**
     * Writes a world's own track list into config.yml and reloads the
     * soundtrack, so the change is heard at once. An empty list silences the
     * world; {@link #forgetOwnTracks} hands it back to the default list instead.
     * The list is written through the section, so a world name containing a dot
     * still becomes one key rather than a nested path.
     */
    void saveOwnTracks(String worldName, List<String> specs) {
        trackSection().set(worldName, new ArrayList<>(specs));
        plugin.saveConfigQuietly();
        loadConfig(plugin.getConfig());
    }

    /** Drops a world's own list, so it plays the shared default list again. */
    void forgetOwnTracks(String worldName) {
        trackSection().set(worldName, null);
        plugin.saveConfigQuietly();
        loadConfig(plugin.getConfig());
    }

    /** The {@code music.tracks} section, created when config.yml has none yet. */
    private ConfigurationSection trackSection() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("music.tracks");
        return section != null ? section : plugin.getConfig().createSection("music.tracks");
    }

    /**
     * Plays one specific track for a player right away, out of the editor: the
     * scheduler treats it as that player's current track, so its own music
     * keeps out of the way and picks up afterwards.
     *
     * @return whether the given track could be played
     */
    boolean preview(Player player, String spec) {
        Track track = parseEntry(spec, "the soundtrack editor");
        World world = player.getWorld();
        if (track == null || world == null) {
            return false;
        }
        play(player, List.of(track), world.getName().toLowerCase(Locale.ROOT), System.currentTimeMillis());
        return true;
    }

    /**
     * Every music sound this server knows, soundtracks first and music discs
     * last, both in alphabetical order. Used by the editor's track picker.
     */
    static List<String> musicSoundNames() {
        List<String> names = new ArrayList<>();
        try {
            for (Sound sound : Sound.values()) {
                String name = sound.name();
                if (name.startsWith("MUSIC_") && !name.endsWith("_MUSIC_BOX")) {
                    names.add(name);
                }
            }
        } catch (Throwable ex) {
            // A server build that can't list its sounds: fall back to the
            // handful the plugin ships with, so the picker still has entries.
            for (String builtIn : BUILT_IN_TRACKS) {
                int colon = builtIn.indexOf(':');
                names.add(colon < 0 ? builtIn : builtIn.substring(0, colon));
            }
        }
        names.sort(java.util.Comparator
                .comparingInt((String name) -> name.startsWith("MUSIC_DISC") ? 1 : 0)
                .thenComparing(java.util.Comparator.naturalOrder()));
        return List.copyOf(names);
    }

    /**
     * The tracks that may play in a world: its own list when it has one, the
     * fallback list when it's a world this plugin owns, and nothing otherwise.
     */
    private List<Track> tracksFor(String worldKey, Set<String> pluginWorlds) {
        List<Track> list = tracks.get(worldKey);
        if (list != null) {
            return list;
        }
        return pluginWorlds.contains(worldKey) ? fallback : List.of();
    }
}
