package com.leoneferito.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * 교환·반품 진행 상태.
 *
 * <pre>
 *   REQUESTED → APPROVED → COLLECTED → COMPLETED
 *       ├→ WITHDRAWN (손님이 승인 전에 철회)
 *       └──────┴──────────┴→ REJECTED (관리자 거절 · 검수 불합격)
 * </pre>
 */
public enum ReturnStatus {
    REQUESTED,
    APPROVED,
    COLLECTED,
    COMPLETED,
    REJECTED,
    WITHDRAWN;

    /** 아직 끝나지 않은 신청. 한 주문에 하나만 있을 수 있다. */
    public static final Set<ReturnStatus> ACTIVE = EnumSet.of(REQUESTED, APPROVED, COLLECTED);

    /** 이 신청이 주문 수량을 차지하는가. 거절·철회된 신청은 다시 신청할 수 있게 빼 준다. */
    public boolean holdsQuantity() {
        return this != REJECTED && this != WITHDRAWN;
    }
}
