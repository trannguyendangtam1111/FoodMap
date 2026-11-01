package com.foodmap.ingestion.snapshot;

import java.util.Optional;

/** Only one snapshot run at a time. */
public interface SnapshotRunLock {

    /** The lock, or empty when another run holds it (then nothing may be done). */
    Optional<Handle> tryAcquire();

    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
