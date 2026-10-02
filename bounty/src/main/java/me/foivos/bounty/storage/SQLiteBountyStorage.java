package me.foivos.bounty.storage;

import me.foivos.bounty.bounty.Bounty;
import me.foivos.bounty.bounty.BountyContribution;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounties in a single SQLite file next to the config.
 *
 * <p>Two tables: {@code bounties} is one row per player with money on their
 * head (totals and timestamps), and {@code contributions} is one row per
 * player who put money on them. Both are keyed by UUID, never by name, so a
 * rename and an offline player are both just rows.
 *
 * <p>Writes are prepared statements executed as they arrive — the manager only
 * hands over bounties that changed, a few times a minute at most on any real
 * server, so this never blocks the tick for long. SQLite is happy with the one
 * connection kept open for the life of the plugin.
 */
public final class SQLiteBountyStorage implements BountyStorage {

    private static final String CREATE_BOUNTIES = """
            CREATE TABLE IF NOT EXISTS bounties (
                target_uuid  TEXT PRIMARY KEY,
                target_name  TEXT,
                total        REAL NOT NULL DEFAULT 0,
                self_total   REAL NOT NULL DEFAULT 0,
                updated_at   INTEGER NOT NULL DEFAULT 0
            )""";

    private static final String CREATE_CONTRIBUTIONS = """
            CREATE TABLE IF NOT EXISTS contributions (
                target_uuid      TEXT NOT NULL,
                contributor_uuid TEXT NOT NULL,
                contributor_name TEXT,
                amount           REAL NOT NULL DEFAULT 0,
                updated_at       INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (target_uuid, contributor_uuid)
            )""";

    private final File file;
    private final Logger logger;
    private Connection connection;

    public SQLiteBountyStorage(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    private String url() {
        return "jdbc:sqlite:" + file.getAbsolutePath();
    }

    @Override
    public synchronized void init() {
        if (connection != null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new StorageException("Could not create " + parent);
        }
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(url());
        } catch (ClassNotFoundException notThere) {
            throw new StorageException("The bundled SQLite driver is missing from the jar", notThere);
        } catch (SQLException noDriver) {
            // A plugin class loader can hide the driver from DriverManager, so
            // fall back to the driver object itself.
            try {
                connection = new org.sqlite.JDBC().connect(url(), new Properties());
            } catch (SQLException failed) {
                throw new StorageException("Could not open " + file.getName(), failed);
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_BOUNTIES);
            statement.executeUpdate(CREATE_CONTRIBUTIONS);
        } catch (SQLException ex) {
            throw new StorageException("Could not prepare " + file.getName(), ex);
        }
        logger.info("Bounties are stored in " + file.getName() + ".");
    }

    @Override
    public synchronized List<Snapshot> load() {
        require();
        Map<UUID, List<BountyContribution>> contributions = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target_uuid, contributor_uuid, contributor_name, amount, updated_at"
                        + " FROM contributions");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID target = uuid(rows.getString("target_uuid"));
                if (target == null) {
                    continue;
                }
                UUID contributor = uuid(rows.getString("contributor_uuid"));
                if (contributor == null) {
                    continue;
                }
                contributions.computeIfAbsent(target, key -> new ArrayList<>())
                        .add(new BountyContribution(contributor,
                                rows.getString("contributor_name"),
                                rows.getDouble("amount"),
                                rows.getLong("updated_at")));
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not read the stored contributions", ex);
        }

        List<Snapshot> snapshots = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target_uuid, target_name, total, self_total, updated_at FROM bounties");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID target = uuid(rows.getString("target_uuid"));
                if (target == null) {
                    continue;
                }
                Bounty bounty = new Bounty(target,
                        rows.getString("target_name"),
                        rows.getDouble("total"),
                        rows.getDouble("self_total"),
                        rows.getLong("updated_at"));
                snapshots.add(new Snapshot(bounty, contributions.getOrDefault(target, List.of())));
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not read the stored bounties", ex);
        }
        return snapshots;
    }

    @Override
    public synchronized void save(Bounty bounty, Collection<BountyContribution> contributions) {
        require();
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bounties (target_uuid, target_name, total, self_total, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(target_uuid) DO UPDATE SET
                        target_name = excluded.target_name,
                        total = excluded.total,
                        self_total = excluded.self_total,
                        updated_at = excluded.updated_at""")) {
                statement.setString(1, bounty.target().toString());
                statement.setString(2, bounty.targetName());
                statement.setDouble(3, bounty.total());
                statement.setDouble(4, bounty.selfTotal());
                statement.setLong(5, bounty.updatedAt());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM contributions WHERE target_uuid = ?")) {
                statement.setString(1, bounty.target().toString());
                statement.executeUpdate();
            }
            if (contributions.isEmpty()) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO contributions
                        (target_uuid, contributor_uuid, contributor_name, amount, updated_at)
                    VALUES (?, ?, ?, ?, ?)""")) {
                for (BountyContribution contribution : contributions) {
                    statement.setString(1, bounty.target().toString());
                    statement.setString(2, contribution.contributor().toString());
                    statement.setString(3, contribution.name());
                    statement.setDouble(4, contribution.amount());
                    statement.setLong(5, contribution.updatedAt());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not save the bounty of " + bounty.targetName(), ex);
        }
    }

    @Override
    public synchronized void delete(UUID target) {
        require();
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM contributions WHERE target_uuid = ?")) {
                statement.setString(1, target.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM bounties WHERE target_uuid = ?")) {
                statement.setString(1, target.toString());
                statement.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new StorageException("Could not clear a claimed bounty", ex);
        }
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "Could not close " + file.getName(), ex);
        }
        connection = null;
    }

    private void require() {
        if (connection == null) {
            init();
        }
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
