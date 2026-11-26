package DND.demo.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LiveRoomCorsTest {
    private LiveRoomWebSocketHandler room;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfig.class);
        context.refresh();
        room = context.getBean(LiveRoomWebSocketHandler.class);
        when(room.roomStatus()).thenReturn(new LiveRoomWebSocketHandler.RoomStatus(10, List.of(), null, false));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void cleanup() { context.close(); }

    @Configuration
    @EnableWebMvc
    static class MvcConfig {
        @Bean
        LiveRoomWebSocketHandler room() { return mock(LiveRoomWebSocketHandler.class); }
        @Bean
        LiveBroadcastRequests broadcasts() { return mock(LiveBroadcastRequests.class); }
        @Bean
        LiveBroadcastController broadcastController(LiveBroadcastRequests requests, LiveRoomWebSocketHandler room) { return new LiveBroadcastController(requests, room); }
        @Bean
        LiveRoomController controller(LiveRoomWebSocketHandler room) { return new LiveRoomController(room, new LiveHostTickets()); }
    }
    @Test
    void productionAndLocalSitesCanReadLiveParticipants() throws Exception {
        for (String origin : List.of("https://dndcafe.to", "https://www.dndcafe.to", "http://localhost:5173",
                "https://d-d-frontend-sigma.vercel.app")) {
            mvc.perform(get("/api/live/participants").header("Origin", origin))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    void productionSiteCanPreflightTheWatchEndpoint() throws Exception {
        mvc.perform(options("/api/live/watch").header("Origin", "https://dndcafe.to")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://dndcafe.to"))
                .andExpect(header().string("Access-Control-Allow-Methods", "POST"));
    }

    @Test
    void unavailableWatchResponsesStillCarryCorsHeaders() throws Exception {
        when(room.watch()).thenThrow(new IllegalStateException("No live room is active."));
        mvc.perform(post("/api/live/watch").header("Origin", "https://dndcafe.to"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://dndcafe.to"));
    }

    @Test
    void broadcastRequestAndAdminPreflightsAllowTheRequiredHeaders() throws Exception {
        for (String path : List.of("/api/live/host-ticket", "/api/live/admin/monitor-ticket", "/api/live/requests", "/api/live/admin/control", "/api/live/requests/test/start")) {
            mvc.perform(options(path).header("Origin", "https://dndcafe.to")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "authorization,content-type,x-live-request-token"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://dndcafe.to"));
        }
        mvc.perform(get("/api/live/admin/requests").header("Origin", "https://dndcafe.to"))
                .andExpect(status().isForbidden()).andExpect(header().string("Access-Control-Allow-Origin", "https://dndcafe.to"));
    }

    @Test
    void unrelatedSitesAreNotAllowed() throws Exception {
        mvc.perform(get("/api/live/participants").header("Origin", "https://unrelated.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
