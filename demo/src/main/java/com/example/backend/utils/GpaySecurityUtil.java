package com.example.backend.utils;

import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class GpaySecurityUtil {

    public String generateSignature(
            String timestamp,
            String requestId,
            String jsonBody,
            String privateKeyString) throws Exception {

        String rawData = timestamp + requestId + jsonBody;

        String base64Key = privateKeyString
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("[\\r\\n\\t ]+", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);

        RSAPrivateKey rsaPrivateKey = RSAPrivateKey.getInstance(keyBytes);
        BigInteger modulus = rsaPrivateKey.getModulus();
        BigInteger privateExponent = rsaPrivateKey.getPrivateExponent();

        RSAPrivateKeySpec keySpec = new RSAPrivateKeySpec(modulus, privateExponent);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(rawData.getBytes(StandardCharsets.UTF_8));

        byte[] signedData = signer.sign();
        return Base64.getEncoder().encodeToString(signedData);
    }

    public boolean verifySignature(String rawData, String signatureBase64, String gpayPublicKeyString) {
        try {
            String publicKeyPEM = gpayPublicKeyString
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                    .replace("-----END RSA PUBLIC KEY-----", "")
                    .replaceAll("[\\r\\n\\t ]+", "");

            byte[] decodedKey = Base64.getDecoder().decode(publicKeyPEM);

            PublicKey publicKey = null;
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            try {
                // 1. THỬ ĐỌC THEO CHUẨN X.509 (RSA 8 / PKCS#8)
                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decodedKey);
                publicKey = keyFactory.generatePublic(keySpec);
            } catch (Exception e) {
                // 2. NẾU LỖI, CHUYỂN SANG ĐỌC THEO CHUẨN RSA 1 (PKCS#1) BẰNG BOUNCYCASTLE
                org.bouncycastle.asn1.pkcs.RSAPublicKey rsaPub = org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(decodedKey);
                java.security.spec.RSAPublicKeySpec rsaPubSpec = new java.security.spec.RSAPublicKeySpec(
                        rsaPub.getModulus(), rsaPub.getPublicExponent()
                );
                publicKey = keyFactory.generatePublic(rsaPubSpec);
            }

            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);

            // ===============================================
            // 1. Thử xác thực với chuẩn SHA-256 (Chuẩn mới)
            // ===============================================
            Signature verifier256 = Signature.getInstance("SHA256withRSA");
            verifier256.initVerify(publicKey); // TRUYỀN BIẾN publicKey, KHÔNG PHẢI SỐ
            verifier256.update(rawData.getBytes(StandardCharsets.UTF_8));
            if (verifier256.verify(signatureBytes)) {
                return true;
            }

            // ===============================================
            // 2. Thử xác thực với chuẩn SHA-1 (Dự phòng cho Sandbox)
            // ===============================================
            Signature verifier1 = Signature.getInstance("SHA1withRSA");
            verifier1.initVerify(publicKey); // TRUYỀN BIẾN publicKey, KHÔNG PHẢI SỐ
            verifier1.update(rawData.getBytes(StandardCharsets.UTF_8));
            return verifier1.verify(signatureBytes);

        } catch (Exception e) {
            System.err.println("Lỗi giải mã chữ ký G-Pay: " + e.getMessage());
            return false;
        }
    }
}