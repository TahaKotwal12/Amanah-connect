package com.amanahconnect.mail;

/** Sending failed. {@code permanent} means retrying cannot help (the server rejected the message or address for good). */
public class SmtpFailure extends RuntimeException {

    private final boolean permanent;

    public SmtpFailure(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean permanent() {
        return permanent;
    }
}
