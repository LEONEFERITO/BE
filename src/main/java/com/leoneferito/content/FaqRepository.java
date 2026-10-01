package com.leoneferito.content;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FaqRepository extends JpaRepository<Faq, UUID> {

    @Query("SELECT f FROM Faq f ORDER BY f.sortOrder ASC, f.createdAt ASC")
    List<Faq> findAllOrdered();

    @Query("SELECT f FROM Faq f WHERE f.published = true ORDER BY f.sortOrder ASC, f.createdAt ASC")
    List<Faq> findPublic();

    @Query("SELECT coalesce(max(f.sortOrder), 0) FROM Faq f")
    int maxSortOrder();
}
