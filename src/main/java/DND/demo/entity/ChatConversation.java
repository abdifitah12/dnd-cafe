package DND.demo.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation {
    @Id
    private UUID id;
    @JsonIgnore
    @Column(nullable = false)
    private UUID accessToken;
    @Column(nullable = false, length = 80)
    private String visitorName;
    @Column(nullable = false)
    private Instant createdAt;
}
