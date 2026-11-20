package com.cretas.aims.service.factorydata;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

/** Shared by the export and heartbeat threads for the lifetime of one export. */
final class FactoryDataExportFailures {
    private final AtomicReference<Throwable> first = new AtomicReference<>();

    void record(Throwable failure) {
        record(failure, failure);
    }

    private boolean record(Throwable primary, Throwable secondary) {
        if (first.compareAndSet(null, primary)) return true;
        Throwable recorded = first.get();
        if (recorded != primary && recorded != secondary && recorded.getCause() != secondary) {
            synchronized (recorded) {
                for (Throwable suppressed : recorded.getSuppressed()) {
                    if (suppressed == secondary) return false;
                }
                recorded.addSuppressed(secondary);
            }
        }
        return false;
    }

    Throwable first() {
        return first.get();
    }

    void throwIfFailed() throws IOException {
        if (first() instanceof IOException e) throw e;
        throwIfSnapshotFailed();
    }

    void throwIfSnapshotFailed() {
        switch (first()) {
            case null -> { }
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            default -> throw new AssertionError("Unchecked snapshot failure expected", first());
        }
    }

    RuntimeException failure(String operation, Exception cause) {
        var failure = new IllegalStateException(operation + ": " + cause.getMessage(), cause);
        record(failure);
        return failure;
    }

    <T> T read(String operation, Work<T, SQLException> work) {
        try {
            return work.run();
        } catch (SQLException e) {
            throw failure(operation, e);
        } catch (RuntimeException | Error e) {
            record(e);
            throw e;
        }
    }

    @FunctionalInterface
    interface Work<T, E extends Exception> {
        T run() throws E;
    }

    <T, E extends Exception> T beforeCleanup(Work<T, E> work) throws E {
        try {
            return work.run();
        } catch (Exception | Error e) {
            record(e);
            throw e;
        }
    }

    @FunctionalInterface
    interface Cleanup extends AutoCloseable {
        @Override
        void close();
    }

    Cleanup cleanup(AutoCloseable resource, String operation) {
        return () -> {
            if (resource == null) return;
            try {
                resource.close();
            } catch (Exception | Error e) {
                Throwable primary = e instanceof RuntimeException || e instanceof Error
                        ? e : new IllegalStateException(operation + ": " + e.getMessage(), e);
                // A secondary cleanup failure is already attached to the export's first
                // failure. Letting try-with-resources attach it again would split the chain.
                if (record(primary, e)) {
                    if (primary instanceof Error error) throw error;
                    throw (RuntimeException) primary;
                }
            }
        };
    }
}
