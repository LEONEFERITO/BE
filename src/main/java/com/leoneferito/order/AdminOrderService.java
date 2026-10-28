package com.leoneferito.order;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.payment.PaymentGateway;
import com.leoneferito.product.FrontRebuildTrigger;
import com.leoneferito.product.ProductRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주문 처리 — 제작 시작 · 발송(송장) · 배송 완료 · 취소/환불.
 *
 * <p>상태가 바뀔 수 있는지는 {@link ShopOrder} 가 판단한다. 여기서는 순서를 지킨다:
 * 취소는 <b>토스 환불이 먼저 성공해야</b> 상태를 바꾼다. 반대로 하면 "취소됨" 인데 돈은 안 돌아간 주문이 생긴다.
 */
@Service
public class AdminOrderService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderService.class);

    static final int PAGE_SIZE = 20;
    /** 숫자가 없는 검색어일 때 연락처 조건이 아무것도 걸지 않게 (AdminMemberService 와 같은 이유). */
    private static final String MATCH_NOTHING = "#";

    private final ShopOrderRepository orders;
    private final PaymentGateway gateway;
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public AdminOrderService(ShopOrderRepository orders, PaymentGateway gateway, ProductRepository products,
                             ApplicationEventPublisher events) {
        this.orders = orders;
        this.gateway = gateway;
        this.products = products;
        this.events = events;
    }

    /** 검색. status 가 없으면 결제 전 주문서를 뺀 전부. */
    @Transactional(readOnly = true)
    public Page<ShopOrder> search(String query, OrderStatus status, int page) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String digits = q.replaceAll("[^0-9]", "");
        Set<OrderStatus> statuses = status == null ? OrderService.VISIBLE : EnumSet.of(status);
        Page<ShopOrder> found = orders.search(pattern, digits.isEmpty() ? MATCH_NOTHING : "%" + digits + "%",
                statuses, PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
        found.forEach(o -> o.getItems().size());
        return found;
    }

    @Transactional(readOnly = true)
    public ShopOrder detail(String orderNumber) {
        return OrderService.loaded(find(orderNumber));
    }

    @Transactional
    public void startProduction(UUID actorId, String orderNumber) {
        find(orderNumber).startProduction(actorId);
        log.info("제작 시작 orderNumber={} actorId={}", orderNumber, actorId);
    }

    /** 발송. 택배사 · 송장번호를 남긴다 — 손님 주문 상세에 그대로 보인다. */
    @Transactional
    public void ship(UUID actorId, String orderNumber, String courier, String trackingNumber) {
        find(orderNumber).ship(actorId, courier.trim(), trackingNumber.trim(), Instant.now());
        // 손님에게 택배사 · 송장번호 메일 (OrderNotifier)
        events.publishEvent(new OrderNotifier.Notice(OrderNotifier.Kind.SHIPPED, orderNumber));
        log.info("발송 orderNumber={} actorId={}", orderNumber, actorId);
    }

    @Transactional
    public void markDelivered(UUID actorId, String orderNumber) {
        find(orderNumber).markDelivered(actorId, Instant.now());
        log.info("배송 완료 orderNumber={} actorId={}", orderNumber, actorId);
    }

    /** 취소 + 전액 환불. 발송 전(결제 완료 · 제작 중)에만. 발송 뒤에는 반품 절차로 간다. */
    @Transactional
    public void cancel(UUID actorId, String orderNumber, String reason) {
        // 잠그고 읽는다 — 손님 취소와 관리자 취소가 동시에 오면 환불이 두 번 나갈 수 있다.
        ShopOrder order = orders.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));
        if (!order.getStatus().adminCancellable()) {
            throw new ShopOrder.OrderStateException(
                    "발송 전 주문만 취소할 수 있습니다. 발송된 주문은 반품으로 처리해 주세요.");
        }
        gateway.cancel(order.getPaymentKey(), order.getOrderNumber(), reason.trim(), "cancel-" + order.getOrderNumber(), null);
        order.cancel(actorId, reason.trim(), order.getTotalAmountKrw(), Instant.now());
        events.publishEvent(new OrderNotifier.Notice(OrderNotifier.Kind.CANCELLED_BY_ADMIN, orderNumber));
        // 발송 전 취소 — 재고를 세는 사이즈는 돌려놓는다 (V30)
        if (OrderService.restock(products, order)) {
            events.publishEvent(new FrontRebuildTrigger.CatalogChanged("재고"));
        }
        log.info("주문 취소(관리자) orderNumber={} actorId={}", orderNumber, actorId);
    }

    /** 보낼 수 있는 상태 — ShopOrder.ship 과 같다 */
    private static final Set<OrderStatus> SHIPPABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.IN_PRODUCTION);
    static final int MAX_TRACKING_ROWS = 500;

    /**
     * 주문 목록 엑셀 (2026-10-28) — 보낼 주문(결제 완료 · 제작 중)의 받는 분 · 주소 · 연락처를 택배 프로그램에 올릴 수 있게.
     * 택배사가 정해지기 전이라 어느 택배 프로그램이든 칸을 짝지어 올릴 수 있는 일반 양식이다. 첫 칸 주문번호는 송장 일괄 입력에 그대로 쓴다.
     * 연락처 · 우편번호는 글자 칸 — 엑셀이 앞의 0 을 지우지 않게.
     */
    @Transactional(readOnly = true)
    public byte[] exportShippable() {
        List<ShopOrder> list = orders.findByStatusInOrderByCreatedAtAsc(SHIPPABLE);
        String[] head = {"주문번호", "받는분", "연락처", "우편번호", "주소", "상세주소", "배송메모", "상품", "수량", "주문일시", "상태"};
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Seoul"));
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("보낼 주문");
            org.apache.poi.ss.usermodel.CellStyle textStyle = wb.createCellStyle();
            textStyle.setDataFormat(wb.createDataFormat().getFormat("@"));
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            for (int c = 0; c < head.length; c++) {
                h.createCell(c).setCellValue(head[c]);
            }
            int r = 1;
            for (ShopOrder o : list) {
                String items = o.getItems().stream()
                        .map(i -> i.getProductName() + " " + i.getSize() + " x" + i.getQuantity())
                        .collect(java.util.stream.Collectors.joining(" / "));
                int qty = o.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
                String[] cells = {o.getOrderNumber(), o.getRecipientName(), o.getRecipientPhone(), o.getZipCode(),
                        o.getAddress1(), o.getAddress2() == null ? "" : o.getAddress2(),
                        o.getDeliveryMemo() == null ? "" : o.getDeliveryMemo(), items, null,
                        when.format(o.getCreatedAt()), o.getStatus() == OrderStatus.PAID ? "결제 완료" : "제작 중"};
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r++);
                for (int c = 0; c < cells.length; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    if (c == 8) {
                        cell.setCellValue(qty);
                    } else {
                        cell.setCellStyle(textStyle);
                        cell.setCellValue(cells[c]);
                    }
                }
            }
            for (int c = 0; c < head.length; c++) {
                sheet.setColumnWidth(c, c == 4 || c == 7 ? 40 * 256 : 16 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 송장 일괄 입력 결과 — 보낸 주문 */
    public record Shipped(int row, String orderNumber, String courier, String trackingNumber) {
    }

    /**
     * 송장 일괄 입력 (2026-10-28) — 엑셀(.xlsx) · CSV 의 "주문번호 · 송장번호(· 택배사)" 로 여러 주문을 한 번에 발송 처리.
     * 택배사 칸이 없거나 비면 화면에서 고른 택배사(defaultCourier). 머리줄 이름으로 칸을 찾는다(운송장번호 · 택배사명 등 다른 이름도).
     * 전부 아니면 전무 — 한 줄이라도 틀리면(없는 주문 · 이미 보낸 주문 · 빈 송장 · 같은 주문 두 번) 아무것도 보내지 않고 줄 · 칸 · 이유를 돌려준다.
     * 맞으면 한 트랜잭션에서 전부 발송 처리하고 손님마다 발송 메일을 보낸다.
     */
    @Transactional
    public List<Shipped> importTracking(UUID actorId, byte[] bytes, String defaultCourier) {
        List<com.leoneferito.product.ProductImportService.RowError> errors = new java.util.ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            throw importError(0, null, "파일이 비어 있습니다.");
        }
        boolean xlsx = bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K';
        if (bytes.length > 5 * 1024 * 1024) {
            throw importError(0, null, "파일이 너무 큽니다. 5MB 이하로 나눠 올려 주세요.");
        }
        List<List<String>> lines = xlsx ? com.leoneferito.product.ProductImportService.XlsxRows.read(bytes)
                : com.leoneferito.common.csv.CsvReader.parse(com.leoneferito.common.csv.CsvReader.decode(bytes));
        if (lines.isEmpty()) {
            throw importError(0, null, "파일이 비어 있습니다.");
        }
        int orderAt = -1;
        int trackingAt = -1;
        int courierAt = -1;
        List<String> head = lines.getFirst();
        for (int i = 0; i < head.size(); i++) {
            String key = head.get(i).replace("\uFEFF", "").replaceAll("\\(.*?\\)|\\*|\\s", "");
            if (orderAt < 0 && java.util.Set.of("주문번호", "고객주문번호", "주문코드", "상품주문번호").contains(key)) {
                orderAt = i;
            } else if (trackingAt < 0 && java.util.Set.of("송장번호", "운송장번호", "운송장", "송장").contains(key)) {
                trackingAt = i;
            } else if (courierAt < 0 && java.util.Set.of("택배사", "택배사명", "배송사", "택배회사").contains(key)) {
                courierAt = i;
            }
        }
        if (orderAt < 0) {
            errors.add(new com.leoneferito.product.ProductImportService.RowError(1, "주문번호", "머리줄에 '주문번호' 칸이 없습니다."));
        }
        if (trackingAt < 0) {
            errors.add(new com.leoneferito.product.ProductImportService.RowError(1, "송장번호", "머리줄에 '송장번호' 칸이 없습니다."));
        }
        if (!errors.isEmpty()) {
            throw new com.leoneferito.product.ProductImportService.ImportException(errors);
        }

        List<Shipped> plan = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = lines.get(i);
            if (cells.stream().allMatch(s -> s == null || s.isBlank())) {
                continue;
            }
            int rowNo = i + 1;
            if (plan.size() + errors.size() >= MAX_TRACKING_ROWS) {
                errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, null,
                        "한 번에 " + MAX_TRACKING_ROWS + "건까지 올릴 수 있습니다. 파일을 나눠 주세요."));
                break;
            }
            String no = cell(cells, orderAt);
            String tracking = cell(cells, trackingAt).replaceAll("\\s", "");
            String courier = courierAt < 0 || cell(cells, courierAt).isEmpty() ? (defaultCourier == null ? "" : defaultCourier.trim())
                    : cell(cells, courierAt);
            int before = errors.size();
            if (no.isEmpty()) {
                errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, "주문번호", "주문번호가 비었습니다."));
            } else if (!seen.add(no)) {
                errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, "주문번호", "같은 주문이 파일에 두 번 있습니다: " + no));
            } else {
                java.util.Optional<ShopOrder> found = orders.findByOrderNumber(no);
                if (found.isEmpty()) {
                    errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, "주문번호", "없는 주문입니다: " + no));
                } else if (!SHIPPABLE.contains(found.get().getStatus())) {
                    errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, "주문번호",
                            "보낼 수 없는 상태입니다(이미 발송 · 취소 등): " + no));
                }
            }
            if (tracking.isEmpty() || tracking.length() > 50) {
                errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, "송장번호", "송장번호를 50자 이하로 적어 주세요."));
            }
            if (courier.isEmpty() || courier.length() > 50) {
                errors.add(new com.leoneferito.product.ProductImportService.RowError(rowNo, "택배사", "택배사를 고르거나 택배사 칸에 적어 주세요."));
            }
            if (errors.size() == before) {
                plan.add(new Shipped(rowNo, no, courier, tracking));
            }
        }
        if (plan.isEmpty() && errors.isEmpty()) {
            errors.add(new com.leoneferito.product.ProductImportService.RowError(2, null, "송장 줄이 없습니다."));
        }
        if (!errors.isEmpty()) {
            throw new com.leoneferito.product.ProductImportService.ImportException(errors);
        }
        Instant now = Instant.now();
        for (Shipped s : plan) {
            find(s.orderNumber()).ship(actorId, s.courier(), s.trackingNumber(), now);
            events.publishEvent(new OrderNotifier.Notice(OrderNotifier.Kind.SHIPPED, s.orderNumber()));
        }
        log.info("송장 일괄 입력 count={} actorId={}", plan.size(), actorId);
        return plan;
    }

    private static String cell(List<String> cells, int at) {
        return at < 0 || at >= cells.size() || cells.get(at) == null ? "" : cells.get(at).trim();
    }

    private static com.leoneferito.product.ProductImportService.ImportException importError(int row, String col, String msg) {
        return new com.leoneferito.product.ProductImportService.ImportException(
                List.of(new com.leoneferito.product.ProductImportService.RowError(row, col, msg)));
    }

    private ShopOrder find(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));
    }
}
