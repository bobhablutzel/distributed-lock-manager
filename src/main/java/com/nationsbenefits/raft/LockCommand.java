package com.nationsbenefits.raft;

import java.io.*;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a lock command to be applied to the state machine.
 */
public record LockCommand(
        String lockId,
        String clientId,
        String regionId,
        long fencingToken,
        long timeoutMs,
        Instant expiresAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public LockCommand {
        Objects.requireNonNull(lockId, "lockId must not be null");
    }

    /**
     * Creates an acquire lock command.
     */
    public static LockCommand acquire(String lockId, String clientId, String regionId,
                                       long fencingToken, long timeoutMs) {
        return new LockCommand(lockId, clientId, regionId, fencingToken, timeoutMs,
                Instant.now().plusMillis(timeoutMs));
    }

    /**
     * Creates a release lock command.
     */
    public static LockCommand release(String lockId, String clientId, long fencingToken) {
        return new LockCommand(lockId, clientId, null, fencingToken, 0, null);
    }

    /**
     * Serializes this command to bytes.
     */
    public byte[] serialize() {
        try (var bos = new ByteArrayOutputStream();
             var oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize command", e);
        }
    }

    /**
     * Deserializes a command from bytes.
     */
    public static LockCommand deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (var bis = new ByteArrayInputStream(data);
             var ois = new ObjectInputStream(bis)) {
            return (LockCommand) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }
    }
}
