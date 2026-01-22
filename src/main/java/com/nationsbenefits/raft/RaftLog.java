package com.nationsbenefits.raft;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory Raft log implementation.
 * Thread-safe with read-write locking for concurrent access.
 */
@Slf4j
@Component
public class RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Appends an entry to the log.
     *
     * @param entry The entry to append
     * @return The index of the appended entry
     */
    public long append(LogEntry entry) {
        lock.writeLock().lock();
        try {
            entries.add(entry);
            log.debug("Appended entry at index {}: {}", entry.index(), entry.type());
            return entry.index();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Appends multiple entries to the log.
     *
     * @param newEntries The entries to append
     */
    public void appendAll(List<LogEntry> newEntries) {
        if (newEntries.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            entries.addAll(newEntries);
            log.debug("Appended {} entries, last index: {}",
                    newEntries.size(), newEntries.getLast().index());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets an entry at the specified index.
     *
     * @param index The log index (1-based)
     * @return The entry if found
     */
    public Optional<LogEntry> get(long index) {
        lock.readLock().lock();
        try {
            if (index < 1 || index > entries.size()) {
                return Optional.empty();
            }
            return Optional.of(entries.get((int) index - 1));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets entries from startIndex to the end of the log.
     *
     * @param startIndex The starting index (inclusive)
     * @return List of entries
     */
    public List<LogEntry> getFrom(long startIndex) {
        lock.readLock().lock();
        try {
            if (startIndex < 1 || startIndex > entries.size()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(entries.subList((int) startIndex - 1, entries.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets entries in a range.
     *
     * @param startIndex The starting index (inclusive)
     * @param endIndex   The ending index (exclusive)
     * @return List of entries
     */
    public List<LogEntry> getRange(long startIndex, long endIndex) {
        lock.readLock().lock();
        try {
            if (startIndex < 1 || startIndex > entries.size()) {
                return Collections.emptyList();
            }
            int end = (int) Math.min(endIndex - 1, entries.size());
            return new ArrayList<>(entries.subList((int) startIndex - 1, end));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the last entry in the log.
     */
    public Optional<LogEntry> getLast() {
        lock.readLock().lock();
        try {
            if (entries.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(entries.getLast());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the index of the last entry.
     */
    public long getLastIndex() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the term of the last entry.
     */
    public long getLastTerm() {
        lock.readLock().lock();
        try {
            if (entries.isEmpty()) {
                return 0;
            }
            return entries.getLast().term();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the term at a specific index.
     */
    public long getTermAt(long index) {
        return get(index).map(LogEntry::term).orElse(0L);
    }

    /**
     * Truncates the log from the specified index (inclusive).
     * Used when conflicting entries are detected.
     *
     * @param fromIndex The index to truncate from
     */
    public void truncateFrom(long fromIndex) {
        lock.writeLock().lock();
        try {
            if (fromIndex < 1 || fromIndex > entries.size()) {
                return;
            }
            var removed = entries.subList((int) fromIndex - 1, entries.size());
            log.debug("Truncating {} entries from index {}", removed.size(), fromIndex);
            removed.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Checks if the log contains an entry at the given index with the given term.
     */
    public boolean containsEntry(long index, long term) {
        return get(index).map(e -> e.term() == term).orElse(index == 0);
    }

    /**
     * Gets the size of the log.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if the log is empty.
     */
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return entries.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears all entries (for testing).
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
            log.warn("Log cleared");
        } finally {
            lock.writeLock().unlock();
        }
    }
}
