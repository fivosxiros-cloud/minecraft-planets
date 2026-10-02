package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Personal music: any vanilla song a player wants, on demand, plus a handful of
 * small playlists they build themselves ({@code /music}).
 *
 * <p>This is separate from {@link PlanetMusic}, the automatic soundtrack that
 * plays per planet. That one is a background loop the player can switch off in
 * {@code /settings}; this one only ever plays what somebody asked for.
 *
 * <ul>
 *   <li><b>Play now</b> — any {@code MUSIC_*} sound this server knows (the
 *       vanilla music tracks plus every music disc) starts immediately.</li>
 *   <li><b>Playlists</b> — up to {@code max-playlists} named lists of up to
 *       {@code max-songs-per-playlist} songs. A playlist plays through, one
 *       song at a time, and loops. Songs move on after
 *       {@code track-seconds} (vanilla lengths aren't readable, so this is the
 *       configured approximation), or immediately with the Next button. A list
 *       can be renamed, its songs reordered, and it starts from chat with
 *       {@code /music <playlist>} as well as from the menu.</li>
 *   <li><b>Stop</b> — silences the personal music and asks {@link PlanetMusic}
 *       to stay quiet as well, so pressing Stop doesn't hand the channel
 *       straight back to a planet track. The quiet lifts when the player moves
 *       to another world or asks for music again.</li>
 * </ul>
 *
 * <p>Playlists are saved in {@code player-music.yml} beside the other data
 * files, keyed by player UUID, so they survive restarts.
 */
final class PlayerMusic {

    private static final int DEFAULT_MAX_PLAYLISTS = 5;
    private static final int DEFAULT_MAX_SONGS = 10;
    private static final long DEFAULT_TRACK_SECONDS = 240L;
    private static final long MIN_TRACK_SECONDS = 20L;
    /** A small pause between two playlist songs, in millis. */
    private static final long GAP_MILLIS = 2_000L;
    /** How long a playlist name may be, so it still fits on an item. */
    private static final int MAX_NAME_LENGTH = 24;
    /** How often playlist playback is checked, in ticks (once a second). */
    private static final long CHECK_INTERVAL_TICKS = 20L;

    private final Planets plugin;
    private final File dataFile;
    private YamlConfiguration config;

    /** Player -> their playlists, in the order they were created. */
    private final Map<UUID, List<Playlist>> playlists = new LinkedHashMap<>();
    /** Players whose playlist is currently running. */
    private final Map<UUID, Playback> playing = new ConcurrentHashMap<>();
    /**
     * Players who pressed Stop, with the world they were in at the time. This
     * is how the planet soundtrack is told to stay quiet instead of starting
     * the moment the personal music ends; leaving that world lifts it.
     */
    private final Map<UUID, String> silenced = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private float volume = 0.7f;
    private int maxPlaylists = DEFAULT_MAX_PLAYLISTS;
    private int maxSongs = DEFAULT_MAX_SONGS;
    private long trackSeconds = DEFAULT_TRACK_SECONDS;

    PlayerMusic(Planets plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "player-music.yml");
        load();
    }

    /** One saved playlist: a name and the song sound-names in it, in order. */
    record Playlist(String name, List<String> songs) {
    }

    /**
     * What a player is hearing: the playlist it came from (null for a single
     * song), which song, and when the next one is due.
     */
    private record Playback(String playlist, String song, int index, long nextAt) {
        boolean singleSong() {
            return playlist == null;
        }
    }

    // ── Configuration ───────────────────────────────────────────────────

    /** Re-reads the {@code personal-music} section of config.yml. */
    void loadConfig(FileConfiguration config) {
        enabled = true;
        volume = 0.7f;
        maxPlaylists = DEFAULT_MAX_PLAYLISTS;
        maxSongs = DEFAULT_MAX_SONGS;
        trackSeconds = DEFAULT_TRACK_SECONDS;
        ConfigurationSection section = config.getConfigurationSection("personal-music");
        if (section == null) {
            return; // a config written before the feature existed
        }
        enabled = section.getBoolean("enabled", true);
        volume = (float) Math.min(1.0, Math.max(0.0, section.getDouble("volume", 0.7)));
        maxPlaylists = Math.max(1, Math.min(9, section.getInt("max-playlists", DEFAULT_MAX_PLAYLISTS)));
        maxSongs = Math.max(1, Math.min(45, section.getInt("max-songs-per-playlist", DEFAULT_MAX_SONGS)));
        trackSeconds = Math.max(MIN_TRACK_SECONDS,
                section.getLong("track-seconds", DEFAULT_TRACK_SECONDS));
    }

    boolean enabled() {
        return enabled;
    }

    int maxPlaylists() {
        return maxPlaylists;
    }

    int maxSongs() {
        return maxSongs;
    }

    /** How long a playlist name may be, so it still fits on an item. */
    static int maxNameLength() {
        return MAX_NAME_LENGTH;
    }

    long trackSeconds() {
        return trackSeconds;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private void load() {
        playlists.clear();
        if (!dataFile.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = config.getConfigurationSection("playlists");
        if (root == null) {
            return;
        }
        for (String playerKey : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(playerKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection playerSection = root.getConfigurationSection(playerKey);
            if (playerSection == null) {
                continue;
            }
            List<Playlist> list = new ArrayList<>();
            for (String name : playerSection.getKeys(false)) {
                List<String> songs = new ArrayList<>();
                for (String song : playerSection.getStringList(name)) {
                    if (PlanetMusic.sound(song) != null) {
                        songs.add(song);
                    }
                }
                list.add(new Playlist(name, List.copyOf(songs)));
            }
            if (!list.isEmpty()) {
                playlists.put(uuid, list);
            }
        }
    }

    /** Writes every player's playlists back to player-music.yml. */
    void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("playlists", null);
        ConfigurationSection root = config.createSection("playlists");
        for (Map.Entry<UUID, List<Playlist>> entry : playlists.entrySet()) {
            ConfigurationSection playerSection = root.createSection(entry.getKey().toString());
            for (Playlist playlist : entry.getValue()) {
                playerSection.set(playlist.name(), new ArrayList<>(playlist.songs()));
            }
        }
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save player-music.yml", e);
        }
    }

    // ── Playlists ───────────────────────────────────────────────────────

    /** A player's playlists, in creation order. */
    List<Playlist> playlists(UUID player) {
        List<Playlist> list = playlists.get(player);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** One playlist by name (case-insensitive), or null. */
    Playlist playlist(UUID player, String name) {
        if (name == null) {
            return null;
        }
        for (Playlist playlist : playlists(player)) {
            if (playlist.name().equalsIgnoreCase(name)) {
                return playlist;
            }
        }
        return null;
    }

    /**
     * Creates an empty playlist with the next free "Playlist N" name.
     *
     * @return the new playlist, or null when the player already has the maximum
     */
    Playlist createPlaylist(UUID player) {
        List<Playlist> list = playlists.computeIfAbsent(player, k -> new ArrayList<>());
        if (list.size() >= maxPlaylists) {
            return null;
        }
        int number = 1;
        while (playlist(player, "Playlist " + number) != null) {
            number++;
        }
        Playlist created = new Playlist("Playlist " + number, List.of());
        list.add(created);
        save();
        return created;
    }

    /** Deletes a playlist. Stops playback when it was the one running. */
    boolean deletePlaylist(UUID player, String name) {
        List<Playlist> list = playlists.get(player);
        if (list == null || !list.removeIf(playlist -> playlist.name().equalsIgnoreCase(name))) {
            return false;
        }
        if (list.isEmpty()) {
            playlists.remove(player);
        }
        Playback playback = playing.get(player);
        if (playback != null && name.equalsIgnoreCase(playback.playlist())) {
            playing.remove(player);
        }
        save();
        return true;
    }

    /** Adds a song to the end of a playlist. */
    AddResult addSong(UUID player, String name, String soundName) {
        Playlist playlist = playlist(player, name);
        if (playlist == null) {
            return AddResult.NO_PLAYLIST;
        }
        if (playlist.songs().size() >= maxSongs) {
            return AddResult.FULL;
        }
        int index = indexOf(player, name);
        if (index < 0) {
            return AddResult.NO_PLAYLIST;
        }
        List<String> songs = new ArrayList<>(playlist.songs());
        songs.add(soundName);
        playlists.get(player).set(index, new Playlist(playlist.name(), List.copyOf(songs)));
        // Keep a running playlist in sync: the new song is simply appended.
        save();
        return AddResult.OK;
    }

    /** Removes the song at a position, shifting the rest down. */
    boolean removeSong(UUID player, String name, int songIndex) {
        Playlist playlist = playlist(player, name);
        if (playlist == null || songIndex < 0 || songIndex >= playlist.songs().size()) {
            return false;
        }
        int index = indexOf(player, name);
        if (index < 0) {
            return false;
        }
        List<String> songs = new ArrayList<>(playlist.songs());
        songs.remove(songIndex);
        playlists.get(player).set(index, new Playlist(playlist.name(), List.copyOf(songs)));
        Playback playback = playing.get(player);
        if (playback != null && name.equalsIgnoreCase(playback.playlist())) {
            // Keep playing from the same place, now that everything shifted.
            playing.put(player, new Playback(playback.playlist(), playback.song(),
                    Math.max(0, playback.index() - 1), playback.nextAt()));
        }
        save();
        return true;
    }

    /**
     * Renames a playlist. A playlist that is playing keeps playing under its
     * new name, so the Now Playing line and the Stop button stay honest.
     *
     * @return what happened, so the caller can explain a refusal
     */
    RenameResult renamePlaylist(UUID player, String oldName, String newName) {
        Playlist playlist = playlist(player, oldName);
        if (playlist == null) {
            return RenameResult.UNKNOWN;
        }
        String clean = newName == null ? "" : newName.trim();
        if (clean.isEmpty() || clean.indexOf('.') >= 0) {
            // A dot would become a nested path in the config file and the songs
            // would be lost on the next save, so those names are refused.
            return RenameResult.INVALID;
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            return RenameResult.TOO_LONG;
        }
        Playlist clash = playlist(player, clean);
        if (clash != null && !clash.name().equals(playlist.name())) {
            return RenameResult.TAKEN;
        }
        int index = indexOf(player, oldName);
        if (index < 0) {
            return RenameResult.UNKNOWN;
        }
        playlists.get(player).set(index, new Playlist(clean, playlist.songs()));
        Playback playback = playing.get(player);
        if (playback != null && playback.playlist() != null
                && playback.playlist().equalsIgnoreCase(oldName)) {
            playing.put(player, new Playback(clean, playback.song(), playback.index(), playback.nextAt()));
        }
        save();
        return RenameResult.OK;
    }

    /**
     * Moves a song one place earlier or later in a playlist.
     *
     * @param from the song's current position
     * @param to where it should end up
     */
    boolean moveSong(UUID player, String name, int from, int to) {
        Playlist playlist = playlist(player, name);
        if (playlist == null) {
            return false;
        }
        int size = playlist.songs().size();
        if (from == to || from < 0 || from >= size || to < 0 || to >= size) {
            return false;
        }
        int index = indexOf(player, name);
        if (index < 0) {
            return false;
        }
        List<String> songs = new ArrayList<>(playlist.songs());
        String moved = songs.remove(from);
        songs.add(to, moved);
        playlists.get(player).set(index, new Playlist(playlist.name(), List.copyOf(songs)));
        Playback playback = playing.get(player);
        if (playback != null && !playback.singleSong()
                && name.equalsIgnoreCase(playback.playlist())) {
            // Follow the song that is playing to wherever it landed.
            int nowAt = playback.index();
            if (nowAt == from) {
                nowAt = to;
            } else if (from < nowAt && to >= nowAt) {
                nowAt--;
            } else if (from > nowAt && to <= nowAt) {
                nowAt++;
            }
            nowAt = Math.max(0, Math.min(nowAt, size - 1));
            playing.put(player, new Playback(playback.playlist(), songs.get(nowAt), nowAt,
                    playback.nextAt()));
        }
        save();
        return true;
    }

    /** What {@link #renamePlaylist} did, so the menus can explain a refusal. */
    enum RenameResult {
        OK, UNKNOWN, TAKEN, INVALID, TOO_LONG
    }

    private int indexOf(UUID player, String name) {
        List<Playlist> list = playlists.get(player);
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** What {@link #addSong} did, so the menus can explain a refusal. */
    enum AddResult {
        OK, FULL, NO_PLAYLIST
    }

    // ── Playback ────────────────────────────────────────────────────────

    /**
     * Plays one song immediately, replacing any playlist that was running. The
     * song is remembered as the current one until it is stopped (or its length
     * runs out), so the soundtrack keeps out of its way and the Stop button
     * always knows what to silence.
     */
    void playNow(Player player, Sound sound) {
        if (!enabled) {
            return;
        }
        player.stopSound(SoundCategory.MUSIC);
        player.playSound(player.getLocation(), sound, SoundCategory.MUSIC, volume, 1.0f);
        unsilence(player.getUniqueId()); // asking for a song lifts a Stop
        playing.put(player.getUniqueId(), new Playback(null, sound.name(), -1,
                System.currentTimeMillis() + trackSeconds * 1000L + GAP_MILLIS));
    }

    /**
     * Starts a playlist at the given song index, looping around the end.
     *
     * @return false when the playlist doesn't exist or has no playable songs
     */
    boolean startPlaylist(Player player, String name, int fromIndex) {
        if (!enabled) {
            return false;
        }
        Playlist playlist = playlist(player.getUniqueId(), name);
        if (playlist == null || playlist.songs().isEmpty()) {
            return false;
        }
        int index = Math.max(0, Math.min(fromIndex, playlist.songs().size() - 1));
        play(player, playlist, index);
        return true;
    }

    /** Moves a running playlist straight on to its next song. */
    boolean next(Player player) {
        Playback playback = playing.get(player.getUniqueId());
        if (playback == null || playback.singleSong()) {
            return false;
        }
        Playlist playlist = playlist(player.getUniqueId(), playback.playlist());
        if (playlist == null || playlist.songs().isEmpty()) {
            playing.remove(player.getUniqueId());
            return false;
        }
        int index = (playback.index() + 1) % playlist.songs().size();
        play(player, playlist, index);
        return true;
    }

    /**
     * Stops whatever personal music this player has running and silences the
     * MUSIC channel, so a planet track that happens to be on stops with it.
     * The quiet is remembered, which is what makes Stop stick: without it the
     * soundtrack would start its next track on the very next pass.
     *
     * @return whether anything of theirs was actually playing
     */
    boolean stop(Player player) {
        boolean wasPlaying = playing.remove(player.getUniqueId()) != null;
        player.stopSound(SoundCategory.MUSIC);
        silenced.put(player.getUniqueId(), worldName(player));
        return wasPlaying;
    }

    /** Whether this player asked for silence here and hasn't moved on since. */
    boolean isSilenced(Player player) {
        String world = silenced.get(player.getUniqueId());
        if (world == null) {
            return false;
        }
        if (!world.equals(worldName(player))) {
            silenced.remove(player.getUniqueId()); // a new planet: music may return
            return false;
        }
        return true;
    }

    /** Lets the soundtrack play for this player again (they asked for music). */
    void unsilence(UUID player) {
        silenced.remove(player);
    }

    private static String worldName(Player player) {
        World world = player.getWorld();
        return world == null ? "" : world.getName();
    }

    /** Whether a playlist is running for this player (used by the soundtrack to stay out of the way). */
    boolean isPlaying(UUID player) {
        return playing.containsKey(player);
    }

    /** The name of the playlist this player is hearing, or null for a single song. */
    String runningPlaylist(UUID player) {
        Playback playback = playing.get(player);
        return playback == null || playback.singleSong() ? null : playback.playlist();
    }

    /** A short description of what a player is hearing, or null. */
    String nowPlaying(UUID player) {
        Playback playback = playing.get(player);
        if (playback == null) {
            return null;
        }
        if (playback.singleSong()) {
            return pretty(playback.song());
        }
        Playlist playlist = playlist(player, playback.playlist());
        if (playlist == null || playlist.songs().isEmpty()) {
            return null;
        }
        int index = Math.min(playback.index(), playlist.songs().size() - 1);
        return playlist.name() + " — " + pretty(playlist.songs().get(index));
    }

    /** Starts the once-a-second scheduler that advances running playlists. */
    void start(JavaPlugin owner) {
        Bukkit.getScheduler().runTaskTimer(owner, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : List.copyOf(playing.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                playing.remove(id); // logged out mid-song
                continue;
            }
            Playback playback = playing.get(id);
            if (playback == null || now < playback.nextAt()) {
                continue;
            }
            if (playback.singleSong()) {
                playing.remove(id); // a single song is simply over
                continue;
            }
            Playlist playlist = playlist(id, playback.playlist());
            if (playlist == null || playlist.songs().isEmpty()) {
                playing.remove(id);
                continue;
            }
            int index = (playback.index() + 1) % playlist.songs().size();
            play(player, playlist, index);
        }
        // Forget players who logged out while silenced, so the map stays small.
        silenced.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Plays one song of a playlist and books the next one. */
    private void play(Player player, Playlist playlist, int index) {
        Sound sound = PlanetMusic.sound(playlist.songs().get(index));
        if (sound == null) {
            playing.remove(player.getUniqueId());
            return;
        }
        player.stopSound(SoundCategory.MUSIC);
        player.playSound(player.getLocation(), sound, SoundCategory.MUSIC, volume, 1.0f);
        unsilence(player.getUniqueId()); // asking for a playlist lifts a Stop
        playing.put(player.getUniqueId(), new Playback(playlist.name(), playlist.songs().get(index), index,
                System.currentTimeMillis() + trackSeconds * 1000L + GAP_MILLIS));
    }

    // ── Display helpers ─────────────────────────────────────────────────

    /** "MUSIC_DISC_CAT" -> "Cat" (discs) / "Overworld Forest" (tracks). */
    static String pretty(String soundName) {
        String name = soundName;
        if (name.startsWith("MUSIC_DISC_")) {
            name = name.substring("MUSIC_DISC_".length());
        } else if (name.startsWith("MUSIC_")) {
            name = name.substring("MUSIC_".length());
        } else if (name.startsWith("RECORD_")) {
            name = name.substring("RECORD_".length());
        }
        String[] words = name.split("_");
        StringBuilder pretty = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (pretty.length() > 0) {
                pretty.append(' ');
            }
            if (word.length() <= 3 && !word.equals("END")) {
                pretty.append(word.toUpperCase(Locale.ROOT));
            } else {
                pretty.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return pretty.length() == 0 ? soundName : pretty.toString();
    }
}
