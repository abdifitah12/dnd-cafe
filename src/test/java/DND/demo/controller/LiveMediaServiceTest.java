package DND.demo.controller;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class LiveMediaServiceTest {
    private final LiveMediaService media = new LiveMediaService("wss://example.livekit.cloud", "test-key", "test-secret-only");

    @Test
    void viewersCannotPublishOrModerateAndTokensAreShortLived() {
        var session = media.session("dnd-test", "viewer-test", "Viewer", false);
        var claims = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(session.token().split("\\.")[1]));
        var video = claims.path("video");
        assertEquals("dnd-test", video.path("room").asText());
        assertTrue(video.path("roomJoin").asBoolean());
        assertTrue(video.path("canSubscribe").asBoolean());
        assertFalse(video.path("canPublish").asBoolean());
        assertFalse(video.path("canPublishData").asBoolean());
        assertFalse(video.path("roomAdmin").asBoolean());
        assertFalse(video.has("canPublishSources"));
        assertEquals("viewer-test", claims.path("sub").asText());
        long remaining = claims.path("exp").asLong() - System.currentTimeMillis() / 1000;
        assertTrue(remaining > 0 && remaining <= 120);
    }

    @Test
    void approvedSpeakersOnlyGetCameraAndMicrophonePermissions() {
        var session = media.session("dnd-test", "speaker", "Speaker", true);
        var claims = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(session.token().split("\\.")[1]));
        assertTrue(claims.path("video").path("canPublish").asBoolean());
        assertEquals(2, claims.path("video").path("canPublishSources").size());
        assertFalse(claims.path("video").path("roomAdmin").asBoolean());
    }

    @Test
    void missingHostingCredentialsDoesNotPretendViewingIsAvailable() {
        var disabled = new LiveMediaService("", "", "");
        assertFalse(disabled.configured());
        assertThrows(IllegalStateException.class, () -> disabled.session("room", "viewer", "Viewer", false));
    }
}
