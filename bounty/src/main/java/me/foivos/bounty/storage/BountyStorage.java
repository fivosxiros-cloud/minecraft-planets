package me.foivos.bounty.storage;

import me.foivos.bounty.bounty.Bounty;
import me.foivos.bounty.bounty.BountyContribution;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Where bounties live between server starts.
 *
 * <p>The rest of the plugin only ever talks to this interface, so bounties can
 * be moved somewhere else later (another database, another host) without any of
 * the gameplay code changing: {@link SQLiteBountyStorage} is the implementation
 * that ships, a single file in the plugin's folder.
 *
 * <p>Writes happen on the server thread, one bounty at a time, only for the
 * bounties that actually changed — see {@code storage.save-interval-seconds}.
 */
public interface BountyStorage extends AutoCloseable {

    /** One stored bounty with the contributions behind it. */
    record Snapshot(Bounty bounty, List<BountyContribution> contributions) {
    }

    /** Opens the store and makes sure its schema is in place. */
    void init();

    /** Everything stored, ready to be loaded into memory. */
    List<Snapshot> load();

    /** Writes one bounty and replaces its contributions with the given ones. */
    void save(Bounty bounty, Collection<BountyContribution> contributions);

    /** Forgets a bounty entirely (it was claimed or cleared). */
    void delete(UUID target);

    /** Releases the connection. Everything already written stays written. */
    @Override
    void close();
}
