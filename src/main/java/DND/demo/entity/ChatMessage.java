package DND.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(indexes = @Index(name = "chat_message_conversation_id", columnList = "conversation_id,id"))
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;
    @Column(nullable = false)
    private String sender;
    @Column(nullable = false, length = 2000)
    private String content;
    @Column(nullable = false)
    private Instant createdAt;
}
