package com.teletronics.storage.application.upload;

/**
 * The upload memory budget is fully reserved right now. Mapped to 503 /problems/upload-capacity-reached.
 */
public class UploadCapacityReachedException extends RuntimeException {

    public UploadCapacityReachedException() {
        super("upload capacity reached, try again later");
    }
}
