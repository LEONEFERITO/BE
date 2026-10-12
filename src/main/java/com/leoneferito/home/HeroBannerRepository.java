package com.leoneferito.home;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 메인 배너 목록 (V25). */
public interface HeroBannerRepository extends JpaRepository<HeroBanner, UUID> {

    /** 관리자가 놓은 순서대로. 같은 순서면 먼저 만든 것이 앞. */
    @Query("SELECT b FROM HeroBanner b ORDER BY b.sortOrder ASC, b.createdAt ASC")
    List<HeroBanner> findAllOrdered();

    @Query("SELECT coalesce(max(b.sortOrder), 0) FROM HeroBanner b")
    int maxSortOrder();
}
