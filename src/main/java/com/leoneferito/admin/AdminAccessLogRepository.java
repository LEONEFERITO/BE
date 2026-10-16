package com.leoneferito.admin;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 접속 기록 조회. 넣고 읽기만 한다 — 지우는 메서드는 일부러 없다. */
public interface AdminAccessLogRepository extends JpaRepository<AdminAccessLog, Long> {

    /**
     * 최근 것부터. actor · 기간은 비우면 전체. 경로 검색(contains)은 "누구 주문을 봤나" 를 찾을 때 — 주문번호를 넣는다.
     * 네이티브 + CAST 인 이유: 비어 있을 수 있는 조건(:actorId IS NULL)은 PostgreSQL 이 null 파라미터의 타입을 못 정해 실패한다.
     */
    @Query(value = """
            SELECT * FROM admin_access_log
            WHERE (CAST(:actorId AS uuid) IS NULL OR actor_id = CAST(:actorId AS uuid))
              AND (CAST(:from AS timestamptz) IS NULL OR created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR created_at < CAST(:to AS timestamptz))
              AND (CAST(:path AS text) IS NULL OR path LIKE CAST(:path AS text))
            ORDER BY id DESC
            """, countQuery = """
            SELECT count(*) FROM admin_access_log
            WHERE (CAST(:actorId AS uuid) IS NULL OR actor_id = CAST(:actorId AS uuid))
              AND (CAST(:from AS timestamptz) IS NULL OR created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR created_at < CAST(:to AS timestamptz))
              AND (CAST(:path AS text) IS NULL OR path LIKE CAST(:path AS text))
            """, nativeQuery = true)
    Page<AdminAccessLog> search(@Param("actorId") UUID actorId, @Param("from") Instant from, @Param("to") Instant to,
                                @Param("path") String path, Pageable pageable);
}