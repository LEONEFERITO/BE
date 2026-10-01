package com.leoneferito.member;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberAdminLogRepository extends JpaRepository<MemberAdminLog, Long> {

    /** 회원 상세에 보여 줄 최근 기록. 시각이 아니라 id 순 — 같은 순간에 남은 기록도 순서가 맞다. */
    List<MemberAdminLog> findTop20ByMemberIdOrderByIdDesc(UUID memberId);
}