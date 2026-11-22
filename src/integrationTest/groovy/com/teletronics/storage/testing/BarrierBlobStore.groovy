package com.teletronics.storage.testing

import com.teletronics.storage.domain.file.StorageKey

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Stores bytes, then waits until every party has stored theirs: uploads started together all pass the
 * pre-check and reach the insert together, so the unique indexes decide the race on every run.
 */
class BarrierBlobStore extends InMemoryBlobStore {

    private final CyclicBarrier barrier

    BarrierBlobStore(int parties) {
        barrier = new CyclicBarrier(parties)
    }

    @Override
    void store(StorageKey key, InputStream body, OptionalLong declaredLength) {
        super.store(key, body, declaredLength)
        barrier.await(10, TimeUnit.SECONDS)
    }
}
