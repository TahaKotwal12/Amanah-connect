package com.amanahconnect.auth;

import java.util.UUID;

/** Published inside the accept-invite transaction, so other modules can react (e.g. activate the owner's community). */
public record InvitationAccepted(UUID userId) {}
