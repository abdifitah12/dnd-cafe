package DND.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "live_broadcast_requests")
@Getter @Setter @NoArgsConstructor
public class LiveBroadcastRequest {
    @Id private String id;
    @Column(nullable = false, length = 60) private String name;
    @Column(nullable = false, length = 160) private String topic;
    @Column(nullable = false, length = 64) private String tokenHash;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;
}
