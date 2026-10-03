package com.amanahconnect.file;

/** A file reference the server will not accept (not its community's, not uploaded, wrong type or size). The message says why, for the field it was sent in. */
public class FileRejectedException extends RuntimeException {

    public FileRejectedException(String message) {
        super(message);
    }
}
