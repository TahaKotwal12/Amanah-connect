package com.amanahconnect.mail;

/** An email that cannot be rendered (unknown template, missing data). Retrying cannot help, so the sender fails it at once. */
public class MailRenderException extends RuntimeException {

    public MailRenderException(String message) {
        super(message);
    }

    public MailRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
