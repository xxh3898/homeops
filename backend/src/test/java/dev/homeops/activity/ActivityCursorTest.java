package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActivityCursorTest {
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-08-07T00:00:00Z");
    private static final String VISIBILITY_SNAPSHOT = "100:200:150";
    private static final String UUID = "10000000-0000-0000-0000-000000000001";
    private static final byte[] KEY = "activity-cursor-test-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "other-activity-cursor-key-32byte".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @MethodSource("validSortKeys")
    void should_roundTripAuthenticatedCursor_when_sortKeyUsesServerGeneratedFormat(String sortKey) {
        ActivityCursor cursor = new ActivityCursor(
                SNAPSHOT_AT, VISIBILITY_SNAPSHOT, SNAPSHOT_AT, sortKey, ActivityTypeFilter.CONTAINER_ACTION);
        ActivityCursorCodec codec = codec();

        assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
    }

    @ParameterizedTest
    @MethodSource("validScopes")
    void should_bindAuthenticatedCursorToExactActivityScope_when_scopeIsSupported(ActivityTypeFilter scope) {
        ActivityCursor cursor = new ActivityCursor(
                SNAPSHOT_AT, VISIBILITY_SNAPSHOT, SNAPSHOT_AT, "DEPLOYMENT:" + UUID, scope);
        ActivityCursorCodec codec = codec();

        assertThat(codec.decode(codec.encode(cursor)).scope()).isEqualTo(scope);
    }

    @Test
    void should_emitVersionedUrlSafeEnvelope_when_cursorIsEncoded() {
        String encoded = codec().encode(validCursor());

        assertThat(encoded).matches("^v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    }

    @Test
    void should_rejectCursor_when_onePayloadByteIsTampered() {
        ActivityCursorCodec codec = codec();
        String encoded = codec.encode(validCursor());
        String[] parts = encoded.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        payload[0] ^= 1;
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + parts[2];

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectCursor_when_signatureIsTampered() {
        ActivityCursorCodec codec = codec();
        String encoded = codec.encode(validCursor());
        String[] parts = encoded.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectCursor_when_versionIsUnknown() {
        String encoded = codec().encode(validCursor()).replaceFirst("^v1\\.", "v2.");

        assertThatThrownBy(() -> codec().decode(encoded))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectCursor_when_encodingIsMalformed() {
        for (String encoded : new String[] {"", "v1", "v1.payload", "v1.*.signature", "v1.payload.*"}) {
            assertThatThrownBy(() -> codec().decode(encoded))
                    .isInstanceOf(InvalidActivityCursorException.class);
        }
    }

    @Test
    void should_rejectCursor_when_encodedValueExceedsMaximumLength() {
        assertThatThrownBy(() -> codec().decode("a".repeat(4097)))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectCursor_when_processSigningKeyDiffers() {
        String encoded = codec().encode(validCursor());

        assertThatThrownBy(() -> new ActivityCursorCodec(OTHER_KEY).decode(encoded))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectSigningKey_when_keyIsShorterThan256Bits() {
        assertThatThrownBy(() -> new ActivityCursorCodec(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void should_rejectUnsignedLegacyCursor_when_payloadHasFourParts() {
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(
                legacyPayload(false).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec().decode(legacy))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectUnsignedLegacyCursor_when_payloadHasFiveParts() {
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(
                legacyPayload(true).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec().decode(legacy))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_decodeCursor_when_visibilitySnapshotUsesUnsigned64Boundary() {
        String largestXid = "18446744073709551615";
        String snapshot = "18446744073709551614:" + largestXid
                + ":18446744073709551614,18446744073709551614";
        ActivityCursor cursor = new ActivityCursor(
                SNAPSHOT_AT, snapshot, SNAPSHOT_AT, "DEPLOYMENT:" + UUID, ActivityTypeFilter.ALL);
        ActivityCursorCodec codec = codec();

        assertThat(codec.decode(codec.encode(cursor)).visibilitySnapshot()).isEqualTo(snapshot);
    }

    @Test
    void should_rejectAuthenticatedCursor_when_scopeIsUnknown() {
        assertThatThrownBy(() -> codec().decode(signedPayload(legacyPayload(false) + "\nUNKNOWN")))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidSortKeys")
    void should_rejectAuthenticatedCursor_when_sortKeyIsNotServerGeneratedFormat(String sortKey) {
        ActivityCursor invalid = new ActivityCursor(
                SNAPSHOT_AT, VISIBILITY_SNAPSHOT, SNAPSHOT_AT, sortKey, ActivityTypeFilter.ALL);
        ActivityCursorCodec codec = codec();

        assertThatThrownBy(() -> codec.decode(codec.encode(invalid)))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    private static ActivityCursor validCursor() {
        return new ActivityCursor(SNAPSHOT_AT, VISIBILITY_SNAPSHOT, SNAPSHOT_AT,
                "DEPLOYMENT:" + UUID, ActivityTypeFilter.ALL);
    }

    private static ActivityCursorCodec codec() {
        return new ActivityCursorCodec(KEY);
    }

    private static String legacyPayload(boolean includeScope) {
        return SNAPSHOT_AT + "\n" + VISIBILITY_SNAPSHOT + "\n" + SNAPSHOT_AT + "\nDEPLOYMENT:"
                + UUID + (includeScope ? "\nALL" : "");
    }

    private static String signedPayload(String payload) {
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String authenticated = "v1." + encodedPayload;
        return authenticated + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(authenticated));
    }

    private static byte[] mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Stream<String> validSortKeys() {
        return Stream.of(
                "DEPLOYMENT:" + UUID,
                "BACKUP:" + UUID,
                "INCIDENT_OPEN:" + UUID,
                "INCIDENT_RECOVERY:" + UUID,
                "AGENT:" + UUID,
                "CONTAINER_ACTION:" + UUID);
    }

    private static Stream<String> invalidSortKeys() {
        return Stream.of(
                "DEPLOYMENT:" + UUID + "\u0000",
                "DEPLOYMENT:" + UUID + "\nBACKUP:" + UUID,
                "UNKNOWN:" + UUID,
                "DEPLOYMENT:not-a-uuid",
                "DEPLOYMENT:10000000-0000-0000-0000-00000000000z",
                "DEPLOYMENT:" + "0".repeat(161));
    }

    private static Stream<ActivityTypeFilter> validScopes() {
        return Stream.of(ActivityTypeFilter.values());
    }
}
