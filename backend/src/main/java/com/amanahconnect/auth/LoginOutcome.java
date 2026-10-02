package com.amanahconnect.auth;

/** Result of a sign-in step: either a finished session or a request for the second factor. */
public sealed interface LoginOutcome {

    /** @param refreshToken raw refresh token; the controller puts it in an HttpOnly cookie, never in the body */
    record Authenticated(String accessToken, long expiresInSeconds, boolean mfaSetupRequired, String refreshToken)
            implements LoginOutcome {}

    record MfaChallenge(String mfaToken, long expiresInSeconds) implements LoginOutcome {}
}
