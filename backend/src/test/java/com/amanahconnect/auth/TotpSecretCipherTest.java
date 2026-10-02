package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.auth.crypto.AuthKeys;
import com.amanahconnect.auth.crypto.TotpSecretCipher;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TotpSecretCipherTest {

    private static TotpSecretCipher cipherWithRandomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new TotpSecretCipher(new AuthKeys(new SecretKeySpec(new byte[48], "HmacSHA256"), new SecretKeySpec(key, "AES")));
    }

    private final TotpSecretCipher cipher = cipherWithRandomKey();

    @Test
    void roundTrips() {
        String encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP", "user-1");

        assertThat(encrypted).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(cipher.decrypt(encrypted, "user-1")).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void usesAFreshIvEveryTime() {
        Set<String> outputs = new HashSet<>();
        Set<String> ivs = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String encrypted = cipher.encrypt("same secret", "user-1");
            outputs.add(encrypted);
            ivs.add(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(Base64.getDecoder().decode(encrypted), 12)));
        }

        assertThat(outputs).as("the same plaintext never produces the same ciphertext").hasSize(200);
        assertThat(ivs).hasSize(200);
    }

    @Test
    void detectsTampering() {
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("secret", "user-1"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "user-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToDecryptForAnotherContext() {
        String encrypted = cipher.encrypt("secret", "user-1");

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "user-2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToDecryptWithAnotherKey() {
        String encrypted = cipher.encrypt("secret", "user-1");

        assertThatThrownBy(() -> cipherWithRandomKey().decrypt(encrypted, "user-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> cipher.decrypt("not base64 !!", "u")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[5]), "u")).isInstanceOf(IllegalStateException.class);
    }
}
