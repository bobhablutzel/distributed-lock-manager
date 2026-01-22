package com.nationsbenefits.model;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents the result of a lock operation using a functional Either pattern.
 * Can be either a success with lock data or a failure with error information.
 */
public sealed interface LockResult<T> {

    /**
     * Checks if this result represents a success.
     */
    boolean isSuccess();

    /**
     * Gets the value if successful, throws if not.
     */
    T getValue();

    /**
     * Gets the error if failed, throws if successful.
     */
    LockError getError();

    /**
     * Maps the success value to a new type.
     */
    <U> LockResult<U> map(Function<T, U> mapper);

    /**
     * FlatMaps the success value to a new LockResult.
     */
    <U> LockResult<U> flatMap(Function<T, LockResult<U>> mapper);

    /**
     * Executes the given consumer if this is a success.
     */
    LockResult<T> onSuccess(Consumer<T> consumer);

    /**
     * Executes the given consumer if this is a failure.
     */
    LockResult<T> onFailure(Consumer<LockError> consumer);

    /**
     * Returns the value if successful, otherwise returns the default value.
     */
    T orElse(T defaultValue);

    /**
     * Creates a successful result.
     */
    static <T> LockResult<T> success(T value) {
        return new Success<>(value);
    }

    /**
     * Creates a failed result.
     */
    static <T> LockResult<T> failure(LockError error) {
        return new Failure<>(error);
    }

    /**
     * Creates a failed result with status and message.
     */
    static <T> LockResult<T> failure(LockStatus status, String message) {
        return new Failure<>(new LockError(status, message));
    }

    // Success implementation
    record Success<T>(T value) implements LockResult<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public T getValue() {
            return value;
        }

        @Override
        public LockError getError() {
            throw new IllegalStateException("Cannot get error from success result");
        }

        @Override
        public <U> LockResult<U> map(Function<T, U> mapper) {
            return new Success<>(mapper.apply(value));
        }

        @Override
        public <U> LockResult<U> flatMap(Function<T, LockResult<U>> mapper) {
            return mapper.apply(value);
        }

        @Override
        public LockResult<T> onSuccess(Consumer<T> consumer) {
            consumer.accept(value);
            return this;
        }

        @Override
        public LockResult<T> onFailure(Consumer<LockError> consumer) {
            return this;
        }

        @Override
        public T orElse(T defaultValue) {
            return value;
        }
    }

    // Failure implementation
    record Failure<T>(LockError error) implements LockResult<T> {
        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public T getValue() {
            throw new IllegalStateException("Cannot get value from failure result: " + error);
        }

        @Override
        public LockError getError() {
            return error;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> LockResult<U> map(Function<T, U> mapper) {
            return (LockResult<U>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> LockResult<U> flatMap(Function<T, LockResult<U>> mapper) {
            return (LockResult<U>) this;
        }

        @Override
        public LockResult<T> onSuccess(Consumer<T> consumer) {
            return this;
        }

        @Override
        public LockResult<T> onFailure(Consumer<LockError> consumer) {
            consumer.accept(error);
            return this;
        }

        @Override
        public T orElse(T defaultValue) {
            return defaultValue;
        }
    }
}
