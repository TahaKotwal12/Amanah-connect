package com.amanahconnect.auth.crypto;

import javax.crypto.SecretKey;

/** Resolved key material: JWT signing key and the AES key for TOTP secrets. */
public record AuthKeys(SecretKey jwtKey, SecretKey totpKey) {}
