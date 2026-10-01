package com.leoneferito.content;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    /** 손님 목록 — 공개만, 고정 먼저, 최근 공개 순. */
    @Query("SELECT n FROM Notice n WHERE n.published = true ORDER BY n.pinned DESC, n.publishedAt DESC")
    Page<Notice> findPublic(Pageable pageable);

    /** 관리자 목록 — 전부, 고정 먼저, 최근에 만든 순. */
    @Query("SELECT n FROM Notice n ORDER BY n.pinned DESC, n.createdAt DESC")
    List<Notice> findAllForAdmin();
}
