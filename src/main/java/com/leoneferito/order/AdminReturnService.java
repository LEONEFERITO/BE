package com.leoneferito.order;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.order.ReturnService.ReturnException;
import com.leoneferito.payment.TossPaymentsClient;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 교환·반품 처리 — 승인 · 회수 완료 · 완료(반품 환불 / 교환 재발송) · 거절.
 *
 * <p>상태가 바뀔 수 있는지는 {@link ReturnRequest} 가 판단한다. 반품 환불은 <b>토스 부분 취소가 먼저</b>
 * 성공해야 완료로 바꾼다 ({@link AdminOrderService#cancel} 과 같은 순서).
 */
@Service
public class AdminReturnService {

    private static final Logger log = LoggerFactory.getLogger(AdminReturnService.class);

    static final int PAGE_SIZE = 20;

    private final ReturnRequestRepository returns;
    private final ShopOrderRepository orders;
    private final TossPaymentsClient toss;

    public AdminReturnService(ReturnRequestRepository returns, ShopOrderRepository orders, TossPaymentsClient toss) {
        this.returns = returns;
        this.orders = orders;
        this.toss = toss;
    }

    /** 목록. open = 처리할 것만(신청 · 승인 · 회수). status 를 주면 그 상태만. 둘 다 없으면 전부. */
    @Transactional(readOnly = true)
    public Page<ReturnRequest> search(ReturnStatus status, boolean open, int page) {
        Set<ReturnStatus> statuses = status != null ? EnumSet.of(status)
                : open ? ReturnStatus.ACTIVE : EnumSet.allOf(ReturnStatus.class);
        Page<ReturnRequest> found = returns.findByStatusIn(statuses,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
        found.forEach(ReturnService::loaded);
        return found;
    }

    @Transactional(readOnly = true)
    public ReturnRequest detail(UUID id) {
        return ReturnService.loaded(find(id));
    }

    /** 주문 상세(관리자)에 붙는 신청 내역. */
    @Transactional(readOnly = true)
    public List<ReturnRequest> forOrder(UUID orderId) {
        List<ReturnRequest> list = returns.findForOrder(orderId);
        list.forEach(ReturnService::loaded);
        return list;
    }

    @Transactional
    public void approve(UUID actorId, UUID id, String note) {
        findLocked(id).approve(actorId, blankToNull(note));
        log.info("교환·반품 승인 returnId={} actorId={}", id, actorId);
    }

    @Transactional
    public void markCollected(UUID actorId, UUID id, String note) {
        findLocked(id).markCollected(actorId, blankToNull(note));
        log.info("교환·반품 회수 완료 returnId={} actorId={}", id, actorId);
    }

    @Transactional
    public void reject(UUID actorId, UUID id, String reason) {
        findLocked(id).reject(actorId, reason.trim());
        log.info("교환·반품 거절 returnId={} actorId={}", id, actorId);
    }

    /**
     * 반품 완료 + 환불. 환불액은 관리자가 넣는다 — 배송비 차감 기준이 아직 없다(TODO(고객확인)).
     * 0원이면 토스를 부르지 않는다 (상품 금액 전부가 차감된 경우 등).
     */
    @Transactional
    public void completeReturn(UUID actorId, UUID id, long refundAmount) {
        ReturnRequest r = findLocked(id);
        // 주문도 잠근다 — 같은 주문의 취소·환불이 동시에 오면 환불 합계가 결제 금액을 넘을 수 있다.
        ShopOrder order = orders.findByOrderNumberForUpdate(r.getOrder().getOrderNumber()).orElseThrow();
        if (refundAmount < 0 || refundAmount > order.refundableKrw()) {
            throw new ReturnException("환불액은 0원 이상, " + order.refundableKrw() + "원 이하로 넣어 주세요.");
        }
        r.requireReturnCompletable(); // 토스를 부르기 전에 상태부터 본다
        if (refundAmount > 0) {
            toss.cancel(order.getPaymentKey(), "반품 환불", "return-" + r.getId(), refundAmount);
        }
        r.completeReturn(actorId, refundAmount);
        log.info("반품 완료 returnId={} refund={} actorId={}", id, refundAmount, actorId);
    }

    @Transactional
    public void completeExchange(UUID actorId, UUID id, String courier, String trackingNumber) {
        findLocked(id).completeExchange(actorId, courier.trim(), trackingNumber.trim());
        log.info("교환 재발송 returnId={} actorId={}", id, actorId);
    }

    private ReturnRequest find(UUID id) {
        return returns.findById(id).orElseThrow(() -> new ResourceNotFoundException("신청 없음 id=" + id));
    }

    private ReturnRequest findLocked(UUID id) {
        return returns.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("신청 없음 id=" + id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
