package com.teletronics.storage.application;

import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * A memory budget for upload part buffers, counted in MiB (HTTP decision 9, Storage decision 2).
 * Never waits: a reservation either succeeds at once or the upload is refused.
 */
public class UploadBudget {

    static final long MIB = 1024L * 1024L;

    private final int totalMib;
    private final Semaphore permits;

    public UploadBudget(int totalMib) {
        if (totalMib < 1) {
            throw new IllegalArgumentException("budget must be at least 1 MiB");
        }
        this.totalMib = totalMib;
        this.permits = new Semaphore(totalMib);
    }

    /** Whether a single part of this size could ever fit, even with nothing else running. */
    public boolean canEverFit(long partBytes) {
        return toMib(partBytes) <= totalMib;
    }

    /** Reserves the part size, or returns empty if the budget doesn't have room right now. */
    public Optional<Reservation> tryReserve(long partBytes) {
        int mib = toMib(partBytes);
        return permits.tryAcquire(mib) ? Optional.of(new Reservation(mib)) : Optional.empty();
    }

    public int availableMib() {
        return permits.availablePermits();
    }

    public int totalMib() {
        return totalMib;
    }

    private static int toMib(long bytes) {
        return (int) Math.max(1, (bytes + MIB - 1) / MIB);
    }

    /** Returned exactly once, whatever happens to the upload. */
    public final class Reservation implements AutoCloseable {

        private final int mib;
        private boolean released;

        private Reservation(int mib) {
            this.mib = mib;
        }

        public int mib() {
            return mib;
        }

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                permits.release(mib);
            }
        }
    }
}
