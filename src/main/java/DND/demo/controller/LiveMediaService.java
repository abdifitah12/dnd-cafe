package DND.demo.controller;

import io.livekit.server.*;
import livekit.LivekitModels;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import retrofit2.Call;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Secrets stay on the backend. Viewers receive subscribe-only room tokens. */
@Service
public class LiveMediaService {
    private final String url;
    private final String apiKey;
    private final String secret;
    private final RoomServiceClient rooms;

    public LiveMediaService(@Value("${LIVEKIT_URL:}") String url,
                            @Value("${LIVEKIT_API_KEY:}") String apiKey,
                            @Value("${LIVEKIT_API_SECRET:}") String secret) {
        this.url = url.strip();
        this.apiKey = apiKey.strip();
        this.secret = secret.strip();
        rooms = configured() ? RoomServiceClient.createClient(
                this.url.replaceFirst("^wss:", "https:").replaceFirst("^ws:", "http:"), this.apiKey, this.secret,
                () -> new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10)).build()) : null;
    }

    public record MediaSession(String url, String token) {}

    public boolean configured() {
        return !url.isBlank() && !apiKey.isBlank() && !secret.isBlank();
    }

    public MediaSession session(String room, String identity, String name, boolean publish) {
        if (!configured()) throw new IllegalStateException("Watch-only streaming is not configured yet.");
        var token = new AccessToken(apiKey, secret);
        token.setIdentity(identity);
        token.setName(name);
        token.setTtl(120_000L);
        token.addGrants(new RoomJoin(true), new RoomName(room), new CanPublish(publish),
                new CanSubscribe(true), new CanPublishData(false), new CanUpdateOwnMetadata(false));
        if (publish) token.addGrants(new CanPublishSources(List.of("camera", "microphone")));
        return new MediaSession(url, token.toJwt());
    }

    public void restrict(String room, String identity, boolean audioBlocked, boolean cameraBlocked) {
        if (!configured()) return;
        var sources = new ArrayList<LivekitModels.TrackSource>();
        if (!audioBlocked) sources.add(LivekitModels.TrackSource.MICROPHONE);
        if (!cameraBlocked) sources.add(LivekitModels.TrackSource.CAMERA);
        var permission = LivekitModels.ParticipantPermission.newBuilder().setCanSubscribe(true)
                .setCanPublish(!sources.isEmpty()).setCanPublishData(false).addAllCanPublishSources(sources).build();
        execute(rooms.updateParticipant(room, identity, null, null, permission));
        // Permission changes stop future publication; explicitly mute existing tracks too.
        var participant = execute(rooms.getParticipant(room, identity));
        if (participant != null) for (var track : participant.getTracksList()) {
            if ((audioBlocked && track.getSource() == LivekitModels.TrackSource.MICROPHONE)
                    || (cameraBlocked && track.getSource() == LivekitModels.TrackSource.CAMERA)) {
                execute(rooms.mutePublishedTrack(room, identity, track.getSid(), true));
            }
        }
    }

    public void remove(String room, String identity) {
        if (configured()) execute(rooms.removeParticipant(room, identity, System.currentTimeMillis()), true);
    }

    public void end(String room) {
        if (configured()) execute(rooms.deleteRoom(room), true);
    }

    private <T> T execute(Call<T> call) { return execute(call, false); }

    private <T> T execute(Call<T> call, boolean allowMissing) {
        try {
            var response = call.execute();
            if (!response.isSuccessful() && !(allowMissing && response.code() == 404)) {
                throw new IllegalStateException("The media server could not complete this action. Please try again.");
            }
            return response.body();
        } catch (IOException failure) {
            throw new IllegalStateException("The media server is unavailable. Please try again.");
        }
    }
}
