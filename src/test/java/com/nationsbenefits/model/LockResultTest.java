package com.nationsbenefits.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LockResult functional pattern.
 */
class LockResultTest {

    @Test
    @DisplayName("Should create success result")
    void shouldCreateSuccessResult() {
        var result = LockResult.success("value");

        assertTrue(result.isSuccess());
        assertEquals("value", result.getValue());
        assertThrows(IllegalStateException.class, result::getError);
    }

    @Test
    @DisplayName("Should create failure result")
    void shouldCreateFailureResult() {
        var error = new LockError(LockStatus.ALREADY_LOCKED, "Lock is held");
        var result = LockResult.<String>failure(error);

        assertFalse(result.isSuccess());
        assertEquals(error, result.getError());
        assertThrows(IllegalStateException.class, result::getValue);
    }

    @Test
    @DisplayName("Should create failure result with status and message")
    void shouldCreateFailureWithStatusAndMessage() {
        var result = LockResult.<String>failure(LockStatus.NOT_FOUND, "Lock not found");

        assertFalse(result.isSuccess());
        assertEquals(LockStatus.NOT_FOUND, result.getError().status());
        assertEquals("Lock not found", result.getError().message());
    }

    @Test
    @DisplayName("Should map success value")
    void shouldMapSuccessValue() {
        var result = LockResult.success(5);

        var mapped = result.map(v -> v * 2);

        assertTrue(mapped.isSuccess());
        assertEquals(10, mapped.getValue());
    }

    @Test
    @DisplayName("Should not map failure")
    void shouldNotMapFailure() {
        var error = new LockError(LockStatus.ERROR, "Error");
        var result = LockResult.<Integer>failure(error);

        var mapped = result.map(v -> v * 2);

        assertFalse(mapped.isSuccess());
        assertEquals(error, mapped.getError());
    }

    @Test
    @DisplayName("Should flatMap success value")
    void shouldFlatMapSuccessValue() {
        var result = LockResult.success(5);

        var flatMapped = result.flatMap(v ->
                v > 0 ? LockResult.success(v * 2) : LockResult.failure(LockStatus.ERROR, "Negative")
        );

        assertTrue(flatMapped.isSuccess());
        assertEquals(10, flatMapped.getValue());
    }

    @Test
    @DisplayName("Should not flatMap failure")
    void shouldNotFlatMapFailure() {
        var error = new LockError(LockStatus.ERROR, "Error");
        var result = LockResult.<Integer>failure(error);

        var flatMapped = result.flatMap(v -> LockResult.success(v * 2));

        assertFalse(flatMapped.isSuccess());
        assertEquals(error, flatMapped.getError());
    }

    @Test
    @DisplayName("Should execute onSuccess for success result")
    void shouldExecuteOnSuccessForSuccessResult() {
        var executed = new AtomicBoolean(false);
        var capturedValue = new AtomicReference<String>();

        var result = LockResult.success("value");
        result.onSuccess(v -> {
            executed.set(true);
            capturedValue.set(v);
        });

        assertTrue(executed.get());
        assertEquals("value", capturedValue.get());
    }

    @Test
    @DisplayName("Should not execute onSuccess for failure result")
    void shouldNotExecuteOnSuccessForFailureResult() {
        var executed = new AtomicBoolean(false);

        var result = LockResult.<String>failure(LockStatus.ERROR, "Error");
        result.onSuccess(v -> executed.set(true));

        assertFalse(executed.get());
    }

    @Test
    @DisplayName("Should execute onFailure for failure result")
    void shouldExecuteOnFailureForFailureResult() {
        var executed = new AtomicBoolean(false);
        var capturedError = new AtomicReference<LockError>();

        var error = new LockError(LockStatus.ERROR, "Error");
        var result = LockResult.<String>failure(error);
        result.onFailure(e -> {
            executed.set(true);
            capturedError.set(e);
        });

        assertTrue(executed.get());
        assertEquals(error, capturedError.get());
    }

    @Test
    @DisplayName("Should not execute onFailure for success result")
    void shouldNotExecuteOnFailureForSuccessResult() {
        var executed = new AtomicBoolean(false);

        var result = LockResult.success("value");
        result.onFailure(e -> executed.set(true));

        assertFalse(executed.get());
    }

    @Test
    @DisplayName("Should return value for orElse on success")
    void shouldReturnValueForOrElseOnSuccess() {
        var result = LockResult.success("value");

        assertEquals("value", result.orElse("default"));
    }

    @Test
    @DisplayName("Should return default for orElse on failure")
    void shouldReturnDefaultForOrElseOnFailure() {
        var result = LockResult.<String>failure(LockStatus.ERROR, "Error");

        assertEquals("default", result.orElse("default"));
    }

    @Test
    @DisplayName("Should chain operations functionally")
    void shouldChainOperationsFunctionally() {
        var result = LockResult.success(5)
                .map(v -> v * 2)
                .flatMap(v -> v > 5 ? LockResult.success("big: " + v) : LockResult.failure(LockStatus.ERROR, "small"))
                .map(String::toUpperCase);

        assertTrue(result.isSuccess());
        assertEquals("BIG: 10", result.getValue());
    }
}
