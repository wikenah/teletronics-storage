package com.teletronics.storage.application.upload;

import com.teletronics.storage.domain.file.Sha256;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Passes the body through unchanged while counting bytes, computing SHA-256 and keeping a copy of the
 * first bytes for type detection. Memory stays fixed whatever the body size.
 */
public class DigestingInputStream extends FilterInputStream {

    private final MessageDigest sha256;
    private final byte[] prefix;
    private int prefixLength;
    private long count;

    public DigestingInputStream(InputStream in, int prefixCapacity) {
        super(in);
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        this.prefix = new byte[prefixCapacity];
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            record(new byte[] {(byte) b}, 0, 1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            record(buffer, offset, n);
        }
        return n;
    }

    @Override
    public long skip(long n) {
        throw new UnsupportedOperationException("skipping would bypass the digest");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void record(byte[] buffer, int offset, int length) {
        sha256.update(buffer, offset, length);
        count += length;
        int room = prefix.length - prefixLength;
        if (room > 0) {
            int copy = Math.min(room, length);
            System.arraycopy(buffer, offset, prefix, prefixLength, copy);
            prefixLength += copy;
        }
    }

    public long count() {
        return count;
    }

    public Sha256 sha256() {
        return Sha256.of(sha256.digest());
    }

    public byte[] prefix() {
        return Arrays.copyOf(prefix, prefixLength);
    }
}
