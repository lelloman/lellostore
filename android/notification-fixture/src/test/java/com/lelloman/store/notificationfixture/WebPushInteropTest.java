package com.lelloman.store.notificationfixture;

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Ciphertext produced by Node web-push and forwarded byte-for-byte by the Rust tests. */
public class WebPushInteropTest {
    @Test public void officialConnectorDecryptsIndependentSenderCiphertext() throws Exception {
        Properties vector = new Properties();
        try (java.io.InputStream stream = getClass().getResourceAsStream("/webpush.properties")) { vector.load(stream); }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECPrivateKey privateKey = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(
            new BigInteger(1, Base64.getUrlDecoder().decode(vector.getProperty("private"))), parameters.getParameterSpec(ECParameterSpec.class)));
        WebPushHybridDecrypt decryptor = new WebPushHybridDecrypt.Builder()
            .withAuthSecret(Base64.getUrlDecoder().decode(vector.getProperty("auth")))
            .withRecipientPublicKey(Base64.getUrlDecoder().decode(vector.getProperty("public")))
            .withRecipientPrivateKey(privateKey).build();
        byte[] ciphertext = Base64.getDecoder().decode(vector.getProperty("body"));
        assertArrayEquals(Base64.getDecoder().decode(vector.getProperty("plaintext")), decryptor.decrypt(ciphertext, null));
        ciphertext[ciphertext.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> decryptor.decrypt(ciphertext, null));
    }
}
