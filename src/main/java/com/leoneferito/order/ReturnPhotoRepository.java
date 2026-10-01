package com.leoneferito.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReturnPhotoRepository extends JpaRepository<ReturnPhoto, UUID> {
    long countByMemberIdAndRequestIsNull(UUID memberId);
}
