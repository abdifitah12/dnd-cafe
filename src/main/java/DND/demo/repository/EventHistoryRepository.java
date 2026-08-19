package DND.demo.repository;

import DND.demo.entity.EventHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EventHistoryRepository
        extends JpaRepository<EventHistory, Long> {

    @Query(value = """
        SELECT *
        FROM event_history
        ORDER BY created_at DESC NULLS LAST, id DESC
        """, nativeQuery = true)
    List<EventHistory> findAllNewestFirst();
}