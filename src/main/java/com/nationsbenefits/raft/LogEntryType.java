package com.nationsbenefits.raft;

/**
 * Types of Raft log entries.
 */
public enum LogEntryType {
    /**
     * No operation - used for leader commitment.
     */
    NOOP,

    /**
     * Acquire a lock.
     */
    ACQUIRE_LOCK,

    /**
     * Release a lock.
     */
    RELEASE_LOCK,

    /**
     * Extend a lock timeout.
     */
    EXTEND_LOCK
}
