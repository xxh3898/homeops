package dev.homeops.activity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class ActivityCursorCodec {
    private static final String VERSION = "v1";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int MAC_BYTES = 32;
    private static final int MAXIMUM_ENCODED_LENGTH = 4096;
    private static final Pattern BASE64_URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final SecretKeySpec signingKey;

    static ActivityCursorCodec processLocal() {
        byte[] key = new byte[MINIMUM_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new ActivityCursorCodec(key);
    }

    ActivityCursorCodec(byte[] key) {
        if (key == null || key.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException("Activity cursor signing key must be at least 256 bits");
        }
        this.signingKey = new SecretKeySpec(key.clone(), MAC_ALGORITHM);
    }

    String encode(ActivityCursor cursor) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.payload().getBytes(StandardCharsets.UTF_8));
        String authenticated = VERSION + "." + payload;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(authenticated));
        String encoded = authenticated + "." + signature;
        if (encoded.length() > MAXIMUM_ENCODED_LENGTH) {
            throw new IllegalStateException("Activity cursor exceeds the bounded wire contract");
        }
        return encoded;
    }

    ActivityCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAXIMUM_ENCODED_LENGTH) {
            throw new InvalidActivityCursorException();
        }
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 3
                    || !VERSION.equals(parts[0])
                    || !BASE64_URL.matcher(parts[1]).matches()
                    || !BASE64_URL.matcher(parts[2]).matches()) {
                throw new InvalidActivityCursorException();
            }
            byte[] providedMac = decodeCanonical(parts[2]);
            if (providedMac.length != MAC_BYTES
                    || !MessageDigest.isEqual(mac(parts[0] + "." + parts[1]), providedMac)) {
                throw new InvalidActivityCursorException();
            }
            String payload = new String(decodeCanonical(parts[1]), StandardCharsets.UTF_8);
            return ActivityCursor.parsePayload(payload);
        } catch (IllegalArgumentException exception) {
            throw new InvalidActivityCursorException();
        }
    }

    private static byte[] decodeCanonical(String encoded) {
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
            throw new InvalidActivityCursorException();
        }
        return decoded;
    }

    private byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Activity cursor signing is unavailable", exception);
        }
    }
}
