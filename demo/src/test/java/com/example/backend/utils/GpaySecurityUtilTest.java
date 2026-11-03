package com.example.backend.utils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.RSAPrivateKey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class GpaySecurityUtilTest {

    @Test
    void generatesASignatureFromAClassicPemPrivateKey() throws Exception {
        KeyPair pair = rsaKeyPair();
        java.security.interfaces.RSAPrivateCrtKey privateKey =
                (java.security.interfaces.RSAPrivateCrtKey) pair.getPrivate();
        RSAPrivateKey pkcs1 = new RSAPrivateKey(
                privateKey.getModulus(), privateKey.getPublicExponent(), privateKey.getPrivateExponent(),
                privateKey.getPrimeP(), privateKey.getPrimeQ(), privateKey.getPrimeExponentP(),
                privateKey.getPrimeExponentQ(), privateKey.getCrtCoefficient());
        String privatePem = "-----BEGIN RSA PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pkcs1.getEncoded())
                + "\n-----END RSA PRIVATE KEY-----";
        String publicPem = publicPem(pair);

        GpaySecurityUtil util = new GpaySecurityUtil();
        String signature = util.generateSignature("1700000000000", "request-1", "{\"amount\":1000}", privatePem);

        assertTrue(util.verifySignature("1700000000000request-1{\"amount\":1000}", signature, publicPem));
    }

    @Test
    void verifiesAValidSignatureAndRejectsTampering() throws Exception {
        KeyPair pair = rsaKeyPair();
        String payload = "merchant_order_id=ORDER-1&status=SUCCESS";

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String publicKey = publicPem(pair);

        GpaySecurityUtil util = new GpaySecurityUtil();
        assertTrue(util.verifySignature(payload, signature, publicKey));
        assertFalse(util.verifySignature(payload + "-changed", signature, publicKey));
        assertFalse(util.verifySignature(payload, "not-a-signature", publicKey));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String publicPem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
