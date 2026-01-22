package com.nationsbenefits.raft;

import java.util.Objects;

/**
 * Represents an entry in the Raft log.
 */
public record LogEntry(
        long index,
        long term,
        LogEntryType type,
        byte[] data
) {
    public LogEntry {
        Objects.requireNonNull(type, "type must not be null");
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (term < 0) {
            throw new IllegalArgumentException("term must be non-negative");
        }
    }

    /**
     * Creates a no-op entry (used for leader commitment).
     */
    public static LogEntry noop(long index, long term) {
        return new LogEntry(index, term, LogEntryType.NOOP, new byte[0]);
    }

    /**
     * Creates an acquire lock entry.
     */
    public static LogEntry acquireLock(long index, long term, LockCommand command) {
        return new LogEntry(index, term, LogEntryType.ACQUIRE_LOCK, command.serialize());
    }

    /**
     * Creates a release lock entry.
     */
    public static LogEntry releaseLock(long index, long term, LockCommand command) {
        return new LogEntry(index, term, LogEntryType.RELEASE_LOCK, command.serialize());
    }

    /**
     * Deserializes the command data.
     */
    public LockCommand getCommand() {
        return LockCommand.deserialize(data);
    }
}
