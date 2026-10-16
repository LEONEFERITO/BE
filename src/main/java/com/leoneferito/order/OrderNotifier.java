package com.leoneferito.order;

import com.leoneferito.mail.Mailer;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 알림 메일 (2026-10-14 "주문 알림").
 *
 * <ul>
 *   <li>결제 완료 — 손님: 주문 내역 · 관리자: 새 주문</li>
 *   <li>발송 — 손님: 택배사 · 송장번호</li>
 *   <li>취소 — 손님: 환불 안내 · 관리자: 손님이 취소했을 때</li>
 *   <li>교환·반품 신청 — 손님: 접수 확인 · 관리자: 새 신청</li>
 * </ul>
 *
 * <p>서비스는 {@link Notice} 를 이벤트로 낸다. 메일은 <b>커밋된 뒤에</b> 보낸다 — 결제가 되돌려졌는데 "주문 완료" 메일이
 * 나가면 안 된다. 메일이 실패해도 주문은 그대로다(로그만 남긴다).
 * 관리자 주소는 {@code app.notify.admin-emails}(ORDER_NOTIFY_EMAILS, 쉼표로 여럿) — 비우면 관리자 메일은 보내지 않는다.
 */
@Component
public class OrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(OrderNotifier.class);
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final Map<ReturnType, String> TYPE = Map.of(ReturnType.EXCHANGE, "교환", ReturnType.RETURN, "반품");
    private static final Map<ReturnReason, String> REASON = Map.of(
            ReturnReason.SIZE, "사이즈", ReturnReason.CHANGE_OF_MIND, "단순 변심", ReturnReason.DEFECT, "불량",
            ReturnReason.WRONG_ITEM, "오배송", ReturnReason.OTHER, "기타");
    private static final String FOOTER = """

            ─────────────
            LEONE FERITO
            문의 · 카카오톡 채널 「더맨리 (THE MANLY)」 https://pf.kakao.com/_ZiAxiX/chat
            이 메일은 발신 전용입니다.
            """;

    public enum Kind {
        PAID, SHIPPED, CANCELLED_BY_CUSTOMER, CANCELLED_BY_ADMIN, RETURN_REQUESTED,
        /** 2026-10-16 보강 — 교환·반품 진행도 손님에게 알린다 */
        RETURN_APPROVED, RETURN_REJECTED, RETURN_COMPLETED, EXCHANGE_SHIPPED
    }

    /** 알릴 일. 교환·반품 신청은 {@code returnId} 를 함께 준다. */
    public record Notice(Kind kind, String orderNumber, UUID returnId) {
        public Notice(Kind kind, String orderNumber) {
            this(kind, orderNumber, null);
        }
    }

    private final ShopOrderRepository orders;
    private final ReturnRequestRepository returns;
    private final MemberRepository members;
    private final Mailer mailer;
    private final String front;
    private final List<String> admins;

    public OrderNotifier(ShopOrderRepository orders, ReturnRequestRepository returns, MemberRepository members,
                         Mailer mailer, @Value("${app.front.base-url}") String front,
                         @Value("${app.notify.admin-emails:}") String admins) {
        this.orders = orders;
        this.returns = returns;
        this.members = members;
        this.mailer = mailer;
        this.front = front.endsWith("/") ? front.substring(0, front.length() - 1) : front;
        this.admins = Arrays.stream(admins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void on(Notice n) {
        try {
            ShopOrder o = orders.findByOrderNumber(n.orderNumber()).orElse(null);
            if (o == null) {
                return;
            }
            String no = o.getOrderNumber();
            switch (n.kind()) {
                case PAID -> {
                    toCustomer(o, "주문이 완료되었습니다", "주문해 주셔서 감사합니다.\n\n" + summary(o)
                            + "\n주문 후 제작하는 상품입니다. 제작에 약 " + leadTime(o) + "일이 걸리며, 보내면 송장번호를 메일로 알려 드립니다.\n\n"
                            + "주문 상세 " + front + "/mypage/order/?no=" + no);
                    toAdmins("[새 주문] " + no + " · " + won(o.getTotalAmountKrw()) + " · " + o.getRecipientName(),
                            summary(o) + "\n관리자 화면 " + front + "/admin/orders/detail/?no=" + no);
                }
                case SHIPPED -> toCustomer(o, "상품을 보냈습니다", "주문하신 상품을 보냈습니다.\n\n"
                        + "주문번호  " + no + "\n택배사  " + o.getCourier() + "\n송장번호  " + o.getTrackingNumber()
                        + "\n\n배송 조회는 택배사 홈페이지에서 송장번호로 할 수 있습니다.\n주문 상세 " + front + "/mypage/order/?no=" + no);
                case CANCELLED_BY_CUSTOMER, CANCELLED_BY_ADMIN -> {
                    toCustomer(o, "주문이 취소되었습니다", "주문이 취소되어 결제 금액을 환불했습니다.\n\n"
                            + "주문번호  " + no + "\n환불 금액  " + won(o.getRefundedAmountKrw())
                            + (n.kind() == Kind.CANCELLED_BY_ADMIN && o.getCancelReason() != null ? "\n취소 사유  " + o.getCancelReason() : "")
                            + "\n\n카드 결제는 카드사에 따라 3~7영업일 안에 취소가 반영됩니다.");
                    if (n.kind() == Kind.CANCELLED_BY_CUSTOMER) {
                        toAdmins("[주문 취소] " + no + " · 손님이 취소 · " + won(o.getRefundedAmountKrw()),
                                summary(o) + "\n관리자 화면 " + front + "/admin/orders/detail/?no=" + no);
                    }
                }
                case RETURN_APPROVED, RETURN_REJECTED, RETURN_COMPLETED, EXCHANGE_SHIPPED -> {
                    ReturnRequest r = n.returnId() == null ? null : returns.findById(n.returnId()).orElse(null);
                    if (r == null) {
                        return;
                    }
                    String type = TYPE.get(r.getType());
                    String tail = "\n\n주문번호  " + no + "\n주문 상세 " + front + "/mypage/order/?no=" + no;
                    switch (n.kind()) {
                        case RETURN_APPROVED -> toCustomer(o, type + " 신청이 승인되었습니다", type + " 신청을 승인했습니다. "
                                + (r.getType() == ReturnType.RETURN ? "상품을 보내 주시면 확인한 뒤 환불해 드립니다."
                                        : "상품을 보내 주시면 확인한 뒤 교환 상품을 보내 드립니다.")
                                + (r.getAdminNote() == null ? "" : "\n\n안내  " + r.getAdminNote()) + tail);
                        case RETURN_REJECTED -> toCustomer(o, type + " 신청이 거절되었습니다", type + " 신청을 받아 드리지 못했습니다."
                                + (r.getRejectReason() == null ? "" : "\n\n사유  " + r.getRejectReason())
                                + "\n\n궁금한 점은 카카오톡 채널로 문의해 주세요." + tail);
                        case RETURN_COMPLETED -> toCustomer(o, "반품이 완료되었습니다", "반품을 확인하고 환불했습니다.\n\n환불 금액  "
                                + won(r.getRefundAmountKrw()) + "\n\n카드 결제는 카드사에 따라 3~7영업일 안에 취소가 반영됩니다." + tail);
                        case EXCHANGE_SHIPPED -> toCustomer(o, "교환 상품을 보냈습니다", "교환 상품을 보냈습니다.\n\n택배사  "
                                + r.getReshipCourier() + "\n송장번호  " + r.getReshipTrackingNumber() + tail);
                        default -> { }
                    }
                }
                case RETURN_REQUESTED -> {
                    ReturnRequest r = n.returnId() == null ? null : returns.findById(n.returnId()).orElse(null);
                    if (r == null) {
                        return;
                    }
                    StringBuilder lines = new StringBuilder();
                    for (ReturnItem i : r.getItems()) {
                        lines.append("- ").append(i.getOrderItem().getProductName()).append(" · ")
                                .append(i.getOrderItem().getSize()).append(" · ").append(i.getQuantity()).append("벌")
                                .append(i.getExchangeSize() == null || i.getExchangeSize().isEmpty() ? "" : " → " + i.getExchangeSize())
                                .append('\n');
                    }
                    String what = TYPE.get(r.getType()) + " · " + REASON.get(r.getReason());
                    toCustomer(o, TYPE.get(r.getType()) + " 신청이 접수되었습니다", TYPE.get(r.getType())
                            + " 신청을 받았습니다. 확인한 뒤 진행 상황을 주문 상세에 남겨 드립니다.\n\n주문번호  " + no
                            + "\n신청  " + what + "\n\n" + lines + "\n주문 상세 " + front + "/mypage/order/?no=" + no);
                    toAdmins("[" + TYPE.get(r.getType()) + " 신청] " + no + " · " + REASON.get(r.getReason()),
                            "주문번호  " + no + "\n신청  " + what + "\n\n" + lines
                                    + (r.getDetail() == null ? "" : "\n손님 메모\n" + r.getDetail() + "\n")
                                    + "\n관리자 화면 " + front + "/admin/returns/detail/?id=" + r.getId());
                }
            }
        } catch (RuntimeException e) {
            log.error("주문 알림 메일을 만들지 못함 kind={} orderNumber={}", n.kind(), n.orderNumber(), e);
        }
    }

    /** 주문 내역 — 손님 · 관리자 메일이 같이 쓴다. */
    private String summary(ShopOrder o) {
        StringBuilder s = new StringBuilder()
                .append("주문번호  ").append(o.getOrderNumber()).append('\n')
                .append("결제일시  ").append(when(o.getPaidAt())).append('\n')
                .append("결제금액  ").append(won(o.getTotalAmountKrw()))
                .append(" (상품 ").append(won(o.getItemsAmountKrw()))
                .append(" + 배송비 ").append(won(o.getShippingFeeKrw())).append(")\n")
                .append("결제수단  ").append(o.getPaymentMethod() == null ? "-" : o.getPaymentMethod()).append("\n\n")
                .append("주문 상품\n");
        for (OrderItem i : o.getItems()) {
            s.append("- ").append(i.getProductName()).append(" · ").append(i.getSize()).append(" · ")
                    .append(i.getQuantity()).append("벌 · ").append(won(i.getLineAmountKrw())).append('\n');
        }
        return s.append("\n받는 분  ").append(o.getRecipientName()).append(" · ").append(o.getRecipientPhone())
                .append("\n배송지  (").append(o.getZipCode()).append(") ").append(o.getAddress1())
                .append(o.getAddress2() == null || o.getAddress2().isBlank() ? "" : " " + o.getAddress2())
                .append(o.getDeliveryMemo() == null || o.getDeliveryMemo().isBlank() ? "" : "\n배송 메모  " + o.getDeliveryMemo())
                .append('\n').toString();
    }

    /** 손님 — 탈퇴했거나 정지된 회원에게는 보내지 않는다(탈퇴하면 이메일이 지워진다). */
    private void toCustomer(ShopOrder o, String subject, String body) {
        Member m = members.findById(o.getMemberId()).orElse(null);
        if (m == null || !m.isActive()) {
            return;
        }
        mailer.send(m.getEmail(), "[LEONE FERITO] " + subject, m.getName() + "님,\n\n" + body + FOOTER);
    }

    private void toAdmins(String subject, String body) {
        admins.forEach(to -> mailer.send(to, subject, body));
    }

    /**
     * 관리자 경보 — 사람이 봐야 하는 일(결제 금액 불일치 · 환불액 불일치 · 재고보다 많이 결제). 2026-10-16 보강.
     * 트랜잭션 이벤트가 아니라 바로 보낸다: 이 일들은 대개 예외로 끝나 롤백되므로 AFTER_COMMIT 이 오지 않는다.
     * 메일 자체는 Mailer 가 다른 스레드에서 보낸다 — 요청을 붙잡지 않는다.
     */
    public void alertAdmins(String what, String detail) {
        if (admins.isEmpty()) {
            log.warn("관리자 경보 받을 곳 없음(app.notify.admin-emails 비어 있음) — {}", what);
            return;
        }
        toAdmins("[확인 필요] " + what, detail + "\n\n관리자 화면 " + front + "/admin/orders/");
    }

    private static int leadTime(ShopOrder o) {
        return o.getItems().stream().mapToInt(OrderItem::getLeadTimeDays).max().orElse(0);
    }

    private static String when(Instant t) {
        return t == null ? "-" : WHEN.format(t);
    }

    private static String won(long krw) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(krw) + "원";
    }
}