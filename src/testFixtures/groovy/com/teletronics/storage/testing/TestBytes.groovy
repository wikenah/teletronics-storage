package com.teletronics.storage.testing

import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

/**
 * File contents for specs, and the SHA-256 a client would compute for them.
 */
class TestBytes {

    /** The same bytes every run for the same size and seed. */
    static byte[] random(long size, long seed = size) {
        def bytes = new byte[(int) size]
        new Random(seed).nextBytes(bytes)
        bytes
    }

    /** Different bytes every call, for specs that share one running application. */
    static byte[] unique(int size) {
        def bytes = new byte[size]
        ThreadLocalRandom.current().nextBytes(bytes)
        bytes
    }

    static String sha256(byte[] bytes) {
        MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString()
    }
}
