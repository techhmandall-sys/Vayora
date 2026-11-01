package com.dypcet.vayora.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Web Push protocol's cryptography, with nothing but the JDK: the message encryption
 * of RFC 8291 (aes128gcm, RFC 8188) and the server identification of RFC 8292 (VAPID).
 * Checked against RFC 8291's worked example in WebPushTest.
 */
public final class WebPush {

    /** One record holds any notification this app sends. */
    static final int RECORD_SIZE = 4096;

    private static final ECParameterSpec P256 = p256();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder UNB64 = Base64.getUrlDecoder();

    private WebPush() {
    }

    /** The server's key pair, from the two base64url values configured for it. */
    public static KeyPair keys(String publicKey, String privateKey) {
        return new KeyPair(publicKey(decode(publicKey)), privateKey(decode(privateKey)));
    }

    /** Encrypts for one subscription, with a fresh key pair and salt as the protocol requires. */
    public static byte[] encrypt(byte[] plaintext, byte[] receiverPublic, byte[] authSecret) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256);
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return encrypt(plaintext, receiverPublic, authSecret, generator.generateKeyPair(), salt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 8291 section 3.4 and RFC 8188, with the sender's key pair and salt given. */
    static byte[] encrypt(byte[] plaintext, byte[] receiverPublic, byte[] authSecret, KeyPair sender, byte[] salt) {
        if (plaintext.length + 1 + 16 > RECORD_SIZE - 86) {
            throw new IllegalArgumentException("Too long for one record");
        }
        try {
            byte[] senderPublic = raw((ECPublicKey) sender.getPublic());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(sender.getPrivate());
            agreement.doPhase(publicKey(receiverPublic), true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), receiverPublic, senderPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(
                    "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(
                    "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            // A single, last record: the content, then the delimiter 0x02.
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + senderPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) senderPublic.length).put(senderPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The Authorization header of RFC 8292: a signed token naming the push service it is for,
     * and the server's public key.
     *
     * @param audience the push service's origin, such as {@code https://fcm.googleapis.com}
     * @param subject  a contact for the push service: a mailto: or https: address
     */
    public static String authorization(KeyPair server, String audience, String subject, Instant expires) {
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + json(audience) + "\",\"exp\":" + expires.getEpochSecond()
                + ",\"sub\":\"" + json(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(server.getPrivate());
            signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
            String token = header + "." + claims + "." + B64.encodeToString(signer.sign());
            return "vapid t=" + token + ", k=" + encode(raw((ECPublicKey) server.getPublic()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] decode(String base64url) {
        return UNB64.decode(base64url.strip());
    }

    public static String encode(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    /** The uncompressed point, 0x04 then x then y, as browsers give and expect public keys. */
    static byte[] raw(ECPublicKey key) {
        return concat(new byte[] {4}, fixed(key.getW().getAffineX()), fixed(key.getW().getAffineY()));
    }

    static ECPublicKey publicKey(byte[] raw) {
        if (raw.length != 65 || raw[0] != 4) {
            throw new IllegalArgumentException("Not an uncompressed P-256 public key");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 public key", e);
        }
    }

    static ECPrivateKey privateKey(byte[] raw) {
        if (raw.length != 32) {
            throw new IllegalArgumentException("Not a P-256 private key");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 private key", e);
        }
    }

    private static byte[] fixed(BigInteger coordinate) {
        byte[] bytes = coordinate.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, out, 32 - length, length);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
