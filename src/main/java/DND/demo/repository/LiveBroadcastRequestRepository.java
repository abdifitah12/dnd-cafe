package DND.demo.repository;

import DND.demo.entity.LiveBroadcastRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LiveBroadcastRequestRepository extends JpaRepository<LiveBroadcastRequest, String> {
    List<LiveBroadcastRequest> findByStatusInAndExpiresAtAfterOrderByCreatedAtAsc(List<String> statuses, Instant now);
    long countByStatusAndExpiresAtAfter(String status, Instant now);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LiveBroadcastRequest> findWithLockById(String id);
}
