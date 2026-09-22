package me.foivos.planets;

import org.bukkit.Material;

import java.util.*;

/**
 * Persistent data for a player-owned planet. Stored in my-planets.yml and
 * keyed by the lowercase world name. Each claimed planet carries its owner,
 * members with roles, configurable properties, upgrade levels, economy
 * (for-sale state) and aggregate statistics.
 */
public final class MyPlanetData {

    // ── Roles ───────────────────────────────────────────────────────────

    public enum Role {
        OWNER, CO_OWNER, MODERATOR, MEMBER, VISITOR;

        /** Higher ordinal = more permissions. */
        public boolean atLeast(Role other) {
            return this.ordinal() <= other.ordinal();
        }
    }

    /** The roles that grant meaningful build-level access. */
    private static final Set<Role> BUILD_ROLES = Set.of(Role.OWNER, Role.CO_OWNER, Role.MODERATOR, Role.MEMBER);

    // ── Planet status ───────────────────────────────────────────────────

    public enum Status {
        OPEN, LOCKED, FOR_SALE, CONTESTED
    }

    // ── Upgrade IDs ─────────────────────────────────────────────────────

    public enum Upgrade {
        PLANET_SIZE("Planet Size", Material.BEACON),
        MEMBER_CAPACITY("Member Capacity", Material.PLAYER_HEAD),
        VISITOR_CAPACITY("Visitor Capacity", Material.OAK_DOOR),
        TP_COOLDOWN_DURATION("TP Cooldown/Duration", Material.COMPASS),
        BLOCK_LIMITATIONS("Block Limitations", Material.STONE);

        private final String displayName;
        private final Material icon;

        Upgrade(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String displayName() { return displayName; }
        public Material icon() { return icon; }
    }

    // ── Fields ──────────────────────────────────────────────────────────

    /** The world name (the key in the data file). */
    private final String worldName;

    /** Display name chosen by the owner. */
    private String displayName;

    /** The planet archetype id ("terran", "mars", ...), or null for classic planets. */
    private String archetypeId;

    /** UUID of the planet owner. */
    private UUID ownerUuid;

    /** role -> set of player UUIDs (includes the owner). */
    private final Map<Role, Set<UUID>> members = new EnumMap<>(Role.class);

    /** Pending invitations: inviter UUID -> set of invited UUIDs. */
    private final Map<UUID, Set<UUID>> invitations = new HashMap<>();

    // Planet properties
    private String description = "";
    private boolean publicAccess = true;
    private boolean pvpEnabled = false;
    private boolean buildEnabled = true;
    private boolean mobSpawning = true;
    private boolean explosions = false;
    private boolean fireSpread = false;
    private boolean visitorAccess = true;
    private boolean chestDoorAccess = true;
    private boolean itemDrops = true;
    private boolean structures = true;

    /** Locked-in planet weather: "off" (normal), "clear", "rain" or "thunder". */
    private String weather = "off";

    // Status
    private Status status = Status.OPEN;

    // Upgrades (level 0 = not purchased)
    private final Map<Upgrade, Integer> upgrades = new EnumMap<>(Upgrade.class);

    // Block placement tracking per player (UUID -> blocks placed count)
    private final Map<UUID, Integer> blockCounts = new HashMap<>();

    /** Transient: whether the one-time "block limit reached" chat warning was already sent. */
    private boolean blockLimitWarned = false;

    // Economy
    private boolean forSale = false;
    private double salePrice = 0;

    /** The player who last sold this planet — they can no longer visit or buy it back. */
    private UUID lastSeller = null;

    /** How many times the planet was renamed (rename prices scale with this). */
    private int renameCount = 0;

    // Statistics
    private int visitorCount = 0;
    private long createdTimestamp;
    private long lastModifiedTimestamp;

    // ── Constructor ─────────────────────────────────────────────────────

    public MyPlanetData(String worldName, UUID ownerUuid) {
        this.worldName = worldName;
        this.ownerUuid = ownerUuid;
        this.createdTimestamp = System.currentTimeMillis();
        this.lastModifiedTimestamp = this.createdTimestamp;
        // Owner is always in the OWNER role.
        members.computeIfAbsent(Role.OWNER, k -> new HashSet<>()).add(ownerUuid);
        // Initialise upgrades at level 0.
        for (Upgrade u : Upgrade.values()) {
            upgrades.put(u, 0);
        }
    }

    /** Deserialization helper (fields set after construction). */
    private MyPlanetData(String worldName) {
        this.worldName = worldName;
        this.createdTimestamp = System.currentTimeMillis();
        this.lastModifiedTimestamp = this.createdTimestamp;
    }

    // ── Getters ─────────────────────────────────────────────────────────

    public String worldName() { return worldName; }

    public String displayName() { return displayName != null ? displayName : worldName; }
    public void displayName(String name) { this.displayName = name; touch(); }

    /** The archetype id of this planet ("terran", "mars", ...), or null. */
    public String archetypeId() { return archetypeId; }
    public void archetypeId(String id) { this.archetypeId = id; touch(); }

    /** The resolved archetype, or null when this is a classic/no-archetype planet. */
    public PlanetArchetypes.Archetype archetype() {
        return PlanetArchetypes.byId(archetypeId);
    }

    /** Display name of the planet type, or "Classic" when it has none. */
    public String archetypeDisplayName() {
        PlanetArchetypes.Archetype archetype = archetype();
        return archetype != null ? archetype.displayName() : "Classic";
    }

    public UUID ownerUuid() { return ownerUuid; }

    public String description() { return description; }
    public void description(String desc) { this.description = desc; touch(); }

    public boolean isPublic() { return publicAccess; }
    public void isPublic(boolean val) { this.publicAccess = val; touch(); }

    public boolean pvpEnabled() { return pvpEnabled; }
    public void pvpEnabled(boolean val) { this.pvpEnabled = val; touch(); }

    public boolean buildEnabled() { return buildEnabled; }
    public void buildEnabled(boolean val) { this.buildEnabled = val; touch(); }

    public boolean mobSpawning() { return mobSpawning; }
    public void mobSpawning(boolean val) { this.mobSpawning = val; touch(); }

    public boolean explosions() { return explosions; }
    public void explosions(boolean val) { this.explosions = val; touch(); }

    public boolean fireSpread() { return fireSpread; }
    public void fireSpread(boolean val) { this.fireSpread = val; touch(); }

    public boolean visitorAccess() { return visitorAccess; }
    public void visitorAccess(boolean val) { this.visitorAccess = val; touch(); }

    public boolean chestDoorAccess() { return chestDoorAccess; }
    public void chestDoorAccess(boolean val) { this.chestDoorAccess = val; touch(); }

    public boolean itemDrops() { return itemDrops; }
    public void itemDrops(boolean val) { this.itemDrops = val; touch(); }

    /** Whether structures may generate on this planet (villages, ruins, tree growth...). */
    public boolean structures() { return structures; }
    public void structures(boolean val) { this.structures = val; touch(); }

    /**
     * This planet's locked-in weather: {@code off} (normal, changing weather),
     * {@code clear}, {@code rain} or {@code thunder}. Never null.
     */
    public String weather() { return weather == null || weather.isBlank() ? "off" : weather; }
    public void weather(String mode) {
        this.weather = mode == null || mode.isBlank() ? "off" : mode.toLowerCase(java.util.Locale.ROOT);
        touch();
    }

    public Status status() { return status; }
    public void status(Status s) { this.status = s; touch(); }

    public boolean forSale() { return forSale; }
    public double salePrice() { return salePrice; }

    /** The player who last sold this planet, or null. They can no longer visit or buy it. */
    public UUID lastSeller() { return lastSeller; }
    public void lastSeller(UUID uuid) { this.lastSeller = uuid; touch(); }

    /** How many times this planet was renamed so far. */
    public int renameCount() { return renameCount; }
    public void renameCount(int count) { this.renameCount = count; touch(); }

    public int visitorCount() { return visitorCount; }
    public void visitorCount(int c) { this.visitorCount = c; touch(); }
    public void incrementVisitors() { this.visitorCount++; touch(); }

    public long createdTimestamp() { return createdTimestamp; }
    public long lastModifiedTimestamp() { return lastModifiedTimestamp; }

    // ── Members ─────────────────────────────────────────────────────────

    public Role roleOf(UUID uuid) {
        for (Map.Entry<Role, Set<UUID>> entry : members.entrySet()) {
            if (entry.getValue().contains(uuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean isMember(UUID uuid) { return roleOf(uuid) != null; }

    public Set<UUID> membersWithRole(Role role) {
        return Set.copyOf(members.getOrDefault(role, Set.of()));
    }

    /** All member UUIDs across all roles. */
    public Set<UUID> allMembers() {
        Set<UUID> result = new HashSet<>();
        members.values().forEach(result::addAll);
        return result;
    }

    public int totalMembers() {
        return members.values().stream().mapToInt(Set::size).sum();
    }

    public int memberCapacity() {
        int base = 5;
        int upgradeLevel = upgrades.getOrDefault(Upgrade.MEMBER_CAPACITY, 0);
        return base + upgradeLevel * 5;
    }

    public int visitorCapacity() {
        int base = 10;
        int upgradeLevel = upgrades.getOrDefault(Upgrade.VISITOR_CAPACITY, 0);
        return base + upgradeLevel * 10;
    }

    // ── Block Limitations ─────────────────────────────────────────────

    /**
     * Block limit pricing ladder: index 0 = level 1 cost (free), etc.
     * Level 0 (no upgrade) has a starter limit of {@link #BASE_BLOCK_LIMIT}.
     */
    private static final int[] BLOCK_LIMIT_VALUES = {50, 150, 350, 1000, 1600};
    private static final double[] BLOCK_LIMIT_COSTS  = {0, 100, 500, 2000, 4000};

    /** Planet-wide block limit without any Block Limitations upgrade. */
    public static final int BASE_BLOCK_LIMIT = 20;

    /** Maximum level for the Block Limitations upgrade. */
    public static final int BLOCK_LIMIT_MAX_LEVEL = BLOCK_LIMIT_VALUES.length;

    /**
     * Returns the planet-wide block placement limit for this planet.
     * Level 0 (no upgrade) = starter limit of 20 blocks.
     */
    public int blockLimit() {
        int level = upgradeLevel(Upgrade.BLOCK_LIMITATIONS);
        if (level <= 0) return BASE_BLOCK_LIMIT;
        return blockLimitAtLevel(level);
    }

    /** Returns the VPL cost to reach the given block-limitation level, or -1 if max. */
    public static double blockLimitCost(int currentLevel) {
        if (currentLevel < 0 || currentLevel >= BLOCK_LIMIT_COSTS.length) return -1;
        return BLOCK_LIMIT_COSTS[currentLevel];
    }

    /** Returns the block limit granted at the given upgrade level (level 0 = starter limit). */
    public static int blockLimitAtLevel(int level) {
        if (level <= 0) return BASE_BLOCK_LIMIT;
        if (level > BLOCK_LIMIT_VALUES.length) return BLOCK_LIMIT_VALUES[BLOCK_LIMIT_VALUES.length - 1];
        return BLOCK_LIMIT_VALUES[level - 1];
    }

    /** How many blocks this player has placed on the planet so far. */
    public int blockCount(UUID uuid) {
        return blockCounts.getOrDefault(uuid, 0);
    }

    /** Increment the block count for a player. Returns the new count. */
    public int incrementBlockCount(UUID uuid) {
        int next = blockCount(uuid) + 1;
        blockCounts.put(uuid, next);
        touch();
        return next;
    }

    /** Decrement the block count for a player (block broken). */
    public int decrementBlockCount(UUID uuid) {
        int next = Math.max(0, blockCount(uuid) - 1);
        blockCounts.put(uuid, next);
        // Back under the limit → re-arm the one-time warning.
        if (!blockLimitReached()) {
            blockLimitWarned = false;
        }
        touch();
        return next;
    }

    /** Total blocks placed by ALL players combined on this planet. */
    public int totalBlocksPlaced() {
        return blockCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Whether the planet-wide block placement limit has been reached.
     * The limit is shared by every player on the planet.
     */
    public boolean blockLimitReached() {
        int limit = blockLimit();
        if (limit < 0) return false; // unlimited
        return totalBlocksPlaced() >= limit;
    }

    /**
     * Whether the one-time "planet block limit reached" chat warning should be
     * sent right now. Returns true only on the first check while the limit is
     * reached; afterwards false until the planet drops below the limit again
     * (blocks broken, counts reset or the limit upgraded).
     */
    public boolean shouldWarnBlockLimit() {
        if (blockLimitReached() && !blockLimitWarned) {
            blockLimitWarned = true;
            return true;
        }
        return false;
    }

    /** Per-player block counts, sorted highest first (for the leaderboard). */
    public List<Map.Entry<UUID, Integer>> blockLeaderboard() {
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(blockCounts.entrySet());
        entries.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
        return entries;
    }

    /** Reset all block counts (e.g. when the upgrade is changed). */
    public void resetBlockCounts() {
        blockCounts.clear();
        blockLimitWarned = false; // re-arm the one-time warning
        touch();
    }

    public void addMember(UUID uuid, Role role) {
        members.computeIfAbsent(role, k -> new HashSet<>()).add(uuid);
        touch();
    }

    public void removeMember(UUID uuid) {
        for (Set<UUID> set : members.values()) {
            set.remove(uuid);
        }
        touch();
    }

    public void changeRole(UUID uuid, Role newRole) {
        removeMember(uuid);
        addMember(uuid, newRole);
    }

    // ── Invitations ─────────────────────────────────────────────────────

    public void invite(UUID inviter, UUID target) {
        invitations.computeIfAbsent(inviter, k -> new HashSet<>()).add(target);
        touch();
    }

    public boolean isInvited(UUID target) {
        return invitations.values().stream().anyMatch(set -> set.contains(target));
    }

    public void revokeInvitation(UUID target) {
        invitations.values().forEach(set -> set.remove(target));
        touch();
    }

    public Map<UUID, Set<UUID>> invitations() {
        return Collections.unmodifiableMap(invitations);
    }

    // ── Upgrades ────────────────────────────────────────────────────────

    public int upgradeLevel(Upgrade upgrade) {
        return upgrades.getOrDefault(upgrade, 0);
    }

    public void setUpgradeLevel(Upgrade upgrade, int level) {
        upgrades.put(upgrade, Math.max(0, Math.min(level, maxUpgradeLevel(upgrade))));
        // A new limit → allow one fresh "limit reached" warning at the new cap.
        if (upgrade == Upgrade.BLOCK_LIMITATIONS) {
            blockLimitWarned = false;
        }
        touch();
    }

    /**
     * Planet Size levels. Level 0 is the size a planet is bought at — a tiny
     * 5x5 block plot — then the border grows a chunk at a time up to the
     * special level 6 (6x6 chunks).
     */
    public static final int PLANET_MAX_SIZE_LEVEL = 6;

    public int maxUpgradeLevel() {
        return 10;
    }

    /** Maximum level for a specific upgrade. */
    public int maxUpgradeLevel(Upgrade upgrade) {
        if (upgrade == Upgrade.PLANET_SIZE) return PLANET_MAX_SIZE_LEVEL;
        if (upgrade == Upgrade.BLOCK_LIMITATIONS) return BLOCK_LIMIT_MAX_LEVEL;
        return maxUpgradeLevel();
    }

    /**
     * World border size (side length in blocks) for a planet-size level:
     * <pre>
     * level 0 (starter)  5 blocks  =  5x5 blocks
     * level 1           16 blocks  =  1x1 chunks
     * level 2           32 blocks  =  2x2 chunks
     * level 3           48 blocks  =  3x3 chunks
     * level 4           64 blocks  =  4x4 chunks
     * level 5           80 blocks  =  5x5 chunks
     * level 6 (special) 96 blocks  =  6x6 chunks
     * </pre>
     * Levels above the cap are clamped to the largest size.
     */
    public static int borderSizeAtLevel(int level) {
        return switch (Math.max(0, Math.min(level, PLANET_MAX_SIZE_LEVEL))) {
            case 0 -> 5;    // starter: a 5x5 block plot around the spawn
            case 1 -> 16;   // 1x1 chunks
            case 2 -> 32;   // 2x2 chunks
            case 3 -> 48;   // 3x3 chunks
            case 4 -> 64;   // 4x4 chunks
            case 5 -> 80;   // 5x5 chunks
            default -> 96;  // level 6 (special): 6x6 chunks
        };
    }

    /** Short label for a planet-size level, e.g. {@code 3x3 chunks} or {@code 5x5 blocks}. */
    public static String sizeName(int level) {
        if (level <= 0) {
            return "5x5 blocks";
        }
        return level + "x" + level + " chunks" + (level >= PLANET_MAX_SIZE_LEVEL ? " (special)" : "");
    }

    /**
     * Cost in VPL of buying the given Planet Size level. Level 0 is the size a
     * planet is bought with, so the ladder starts at level 1.
     * Returns -1 when the level doesn't exist.
     */
    public static double planetSizeCost(int level) {
        return switch (level) {
            case 1 -> 2000;
            case 2 -> 6000;
            case 3 -> 23000;
            case 4 -> 40000;
            case 5 -> 65000;
            case 6 -> 130000;
            default -> -1;
        };
    }

    /**
     * The VPL cost of the next level of an upgrade. Planet Size uses its own
     * fixed ladder, Block Limitations its own, and every other upgrade scales
     * by 100 VPL per level.
     */
    public static double upgradeCost(Upgrade upgrade, int currentLevel) {
        if (upgrade == Upgrade.PLANET_SIZE) {
            return planetSizeCost(currentLevel + 1);
        }
        if (upgrade == Upgrade.BLOCK_LIMITATIONS) {
            return blockLimitCost(currentLevel);
        }
        return (currentLevel + 1) * 100.0;
    }

    public int planetLevel() {
        return upgrades.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<Upgrade, Integer> allUpgrades() {
        return Collections.unmodifiableMap(upgrades);
    }

    // ── Economy ─────────────────────────────────────────────────────────

    public void putForSale(double price) {
        this.forSale = true;
        this.salePrice = price;
        this.status = Status.FOR_SALE;
        touch();
    }

    public void removeFromSale() {
        this.forSale = false;
        this.salePrice = 0;
        this.status = Status.OPEN;
        touch();
    }

    public void transferOwnership(UUID newOwner) {
        Role oldRole = roleOf(newOwner);
        if (oldRole != null) {
            removeMember(newOwner);
        }
        // Demote old owner to CO_OWNER (if they're still a member).
        removeMember(ownerUuid);
        addMember(ownerUuid, Role.CO_OWNER);
        // Promote new owner.
        addMember(newOwner, Role.OWNER);
        this.ownerUuid = newOwner;
        // Remember who sold it so they can't visit or buy it back.
        this.lastSeller = ownerUuid;
        removeFromSale();
        touch();
    }

    // ── Permissions helpers ─────────────────────────────────────────────

    /** Whether this player can build on the planet (respecting the global toggle). */
    public boolean canBuild(UUID uuid) {
        if (!buildEnabled) return false;
        Role role = roleOf(uuid);
        return role != null && BUILD_ROLES.contains(role);
    }

    /** Whether this player can manage settings (owner or co-owner). */
    public boolean canManage(UUID uuid) {
        Role role = roleOf(uuid);
        return role != null && role.atLeast(Role.CO_OWNER);
    }

    /** Whether this player can manage members (owner, co-owner, or moderator). */
    public boolean canManageMembers(UUID uuid) {
        Role role = roleOf(uuid);
        return role != null && role.atLeast(Role.MODERATOR);
    }

    // ── Serialization ───────────────────────────────────────────────────

    /**
     * Serialize this data into a config section. The section is expected to
     * live under {@code my-planets.<worldName>} in my-planets.yml.
     */
    public void save(org.bukkit.configuration.ConfigurationSection section) {
        section.set("display-name", displayName);
        section.set("owner", ownerUuid.toString());
        if (archetypeId != null) {
            section.set("archetype", archetypeId);
        }
        section.set("description", description);
        section.set("public", publicAccess);
        section.set("pvp", pvpEnabled);
        section.set("build", buildEnabled);
        section.set("mob-spawning", mobSpawning);
        section.set("explosions", explosions);
        section.set("fire-spread", fireSpread);
        section.set("visitor-access", visitorAccess);
        section.set("chest-door-access", chestDoorAccess);
        section.set("item-drops", itemDrops);
        section.set("structures", structures);
        section.set("weather", weather());
        section.set("status", status.name());
        section.set("for-sale", forSale);
        section.set("sale-price", salePrice);
        if (lastSeller != null) {
            section.set("last-seller", lastSeller.toString());
        }
        section.set("rename-count", renameCount);
        section.set("visitor-count", visitorCount);
        section.set("created", createdTimestamp);
        section.set("last-modified", lastModifiedTimestamp);

        // Members
        for (Role role : Role.values()) {
            Set<UUID> uuids = members.getOrDefault(role, Set.of());
            section.set("members." + role.name(), uuids.stream().map(UUID::toString).toList());
        }

        // Invitations
        List<String> inviteList = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> entry : invitations.entrySet()) {
            for (UUID target : entry.getValue()) {
                inviteList.add(entry.getKey() + ":" + target);
            }
        }
        section.set("invitations", inviteList);

        // Upgrades
        for (Upgrade u : Upgrade.values()) {
            section.set("upgrades." + u.name(), upgrades.getOrDefault(u, 0));
        }

        // Block counts
        org.bukkit.configuration.ConfigurationSection bcSection = section.createSection("block-counts");
        for (Map.Entry<UUID, Integer> entry : blockCounts.entrySet()) {
            bcSection.set(entry.getKey().toString(), entry.getValue());
        }
    }

    /** Deserialize from a config section. */
    public static MyPlanetData load(String worldName, org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return null;
        MyPlanetData data = new MyPlanetData(worldName);
        data.displayName = section.getString("display-name");
        data.archetypeId = section.getString("archetype", null);
        data.ownerUuid = UUID.fromString(Objects.requireNonNull(section.getString("owner")));
        data.description = section.getString("description", "");
        data.publicAccess = section.getBoolean("public", true);
        data.pvpEnabled = section.getBoolean("pvp", false);
        data.buildEnabled = section.getBoolean("build", true);
        data.mobSpawning = section.getBoolean("mob-spawning", true);
        data.explosions = section.getBoolean("explosions", false);
        data.fireSpread = section.getBoolean("fire-spread", false);
        data.visitorAccess = section.getBoolean("visitor-access", true);
        data.chestDoorAccess = section.getBoolean("chest-door-access", true);
        // Migrate the removed RTP Access setting to Item Drops (both default ON).
        data.itemDrops = section.getBoolean("item-drops", section.getBoolean("rtp-access", true));
        data.structures = section.getBoolean("structures", true);
        data.weather = section.getString("weather", "off");
        try {
            data.status = Status.valueOf(section.getString("status", "OPEN"));
        } catch (IllegalArgumentException e) {
            data.status = Status.OPEN;
        }
        data.forSale = section.getBoolean("for-sale", false);
        data.salePrice = section.getDouble("sale-price", 0);
        String lastSellerStr = section.getString("last-seller", null);
        if (lastSellerStr != null) {
            try { data.lastSeller = UUID.fromString(lastSellerStr); } catch (IllegalArgumentException ignored) {}
        }
        data.renameCount = section.getInt("rename-count", 0);
        data.visitorCount = section.getInt("visitor-count", 0);
        data.createdTimestamp = section.getLong("created", System.currentTimeMillis());
        data.lastModifiedTimestamp = section.getLong("last-modified", System.currentTimeMillis());

        // Members
        for (Role role : Role.values()) {
            List<String> uuidStrings = section.getStringList("members." + role.name());
            Set<UUID> uuids = new HashSet<>();
            for (String s : uuidStrings) {
                try { uuids.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            }
            if (!uuids.isEmpty()) {
                data.members.put(role, uuids);
            }
        }

        // Invitations
        List<String> inviteList = section.getStringList("invitations");
        for (String entry : inviteList) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                try {
                    UUID inviter = UUID.fromString(parts[0]);
                    UUID target = UUID.fromString(parts[1]);
                    data.invitations.computeIfAbsent(inviter, k -> new HashSet<>()).add(target);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // Upgrades
        org.bukkit.configuration.ConfigurationSection upgradeSection = section.getConfigurationSection("upgrades");
        if (upgradeSection != null) {
            for (Upgrade u : Upgrade.values()) {
                data.upgrades.put(u, upgradeSection.getInt(u.name(), 0));
            }
            // Migrate legacy RTP_COOLDOWN -> TP_COOLDOWN_DURATION
            if (data.upgrades.getOrDefault(Upgrade.TP_COOLDOWN_DURATION, 0) == 0 && upgradeSection.contains("RTP_COOLDOWN")) {
                data.upgrades.put(Upgrade.TP_COOLDOWN_DURATION, upgradeSection.getInt("RTP_COOLDOWN", 0));
            }
        }

        // Clamp legacy saves: Planet Size used to go up to 10, now capped at 6.
        int sizeLevel = data.upgrades.getOrDefault(Upgrade.PLANET_SIZE, 0);
        if (sizeLevel > PLANET_MAX_SIZE_LEVEL) {
            data.upgrades.put(Upgrade.PLANET_SIZE, PLANET_MAX_SIZE_LEVEL);
        }

        // Block counts
        org.bukkit.configuration.ConfigurationSection bcSection = section.getConfigurationSection("block-counts");
        if (bcSection != null) {
            for (String key : bcSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    data.blockCounts.put(uuid, bcSection.getInt(key, 0));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        return data;
    }

    /** Bump the last-modified timestamp. */
    private void touch() {
        lastModifiedTimestamp = System.currentTimeMillis();
    }
}
