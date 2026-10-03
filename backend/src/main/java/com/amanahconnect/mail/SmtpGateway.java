package com.amanahconnect.mail;

/** The one door out of the application for email. Tests replace it; production talks SMTP (Amazon SES). */
public interface SmtpGateway {

    /**
     * Sends the mail.
     *
     * @return the message id the server gave it (SES's id, used to match bounce notifications), or null if the server gave none
     * @throws SmtpFailure when it could not be sent
     */
    String send(OutgoingMail mail);
}
