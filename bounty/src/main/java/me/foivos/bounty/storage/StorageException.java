package me.foivos.bounty.storage;

/**
 * Anything that went wrong while reading or writing bounties.
 *
 * <p>It is unchecked on purpose: a storage failing is never something the
 * player who clicked has to deal with, so the plugin logs it, keeps serving the
 * bounties it already has in memory, and retries on the next write.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
