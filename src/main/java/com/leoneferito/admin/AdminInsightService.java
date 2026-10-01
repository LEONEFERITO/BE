package com.leoneferito.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 대시보드 · 통계. 읽기 전용 집계라 SQL 로 바로 센다 — 엔티티를 수백 개 올려서 자바로 더하지 않는다.
 *
 * <p>날짜는 서울 기준이다. "오늘 주문" 이 UTC 로 잘리면 아침 9시 전 주문이 어제로 간다.
 * 매출 = 결제 금액 − 환불 금액 (취소는 전액 환불이라 0 이 된다). 결제 전에 떠난 주문서는 세지 않는다.
 */
@Service
public class AdminInsightService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final int DAYS = 14;

    private final JdbcTemplate jdbc;

    public AdminInsightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 대시보드 ───────────────────────────────────────────────

    public record Day(LocalDate date, int orders, long salesKrw) {
    }

    public record RecentOrder(String orderNumber, String orderName, String status, long totalAmountKrw,
                              String recipientName, Instant paidAt) {
    }

    /**
     * todo: 지금 손이 가야 하는 것 — 제작 대기(결제 완료) · 제작 중 · 배송 중 · 교환·반품 신청 · 회수 대기 · 회수 완료.
     * days: 최근 14일, 오래된 날부터 (주문이 없는 날도 0 으로 채운다 — 빈 날이 빠지면 그래프가 거짓말을 한다).
     */
    public record Dashboard(Map<String, Integer> todo, Day today, List<Day> days, List<RecentOrder> recent,
                            int publishedProducts, int draftProducts) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Map<String, Integer> todo = new LinkedHashMap<>();
        for (String k : List.of("PAID", "IN_PRODUCTION", "SHIPPED", "RETURN_REQUESTED", "RETURN_APPROVED",
                "RETURN_COLLECTED")) {
            todo.put(k, 0);
        }
        jdbc.query("""
                SELECT status, count(*) FROM orders
                WHERE status IN ('PAID', 'IN_PRODUCTION', 'SHIPPED') GROUP BY status
                """, rs -> {
            todo.put(rs.getString(1), rs.getInt(2));
        });
        jdbc.query("""
                SELECT status, count(*) FROM return_request
                WHERE status IN ('REQUESTED', 'APPROVED', 'COLLECTED') GROUP BY status
                """, rs -> {
            todo.put("RETURN_" + rs.getString(1), rs.getInt(2));
        });

        LocalDate today = LocalDate.now(SEOUL);
        LocalDate from = today.minusDays(DAYS - 1);
        Map<LocalDate, Day> byDate = new HashMap<>();
        jdbc.query("""
                SELECT (paid_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*),
                       coalesce(sum(total_amount_krw - refunded_amount_krw), 0)
                FROM orders
                WHERE paid_at IS NOT NULL AND paid_at >= ?
                GROUP BY d
                """, rs -> {
            LocalDate d = rs.getDate(1).toLocalDate();
            byDate.put(d, new Day(d, rs.getInt(2), rs.getLong(3)));
        }, Timestamp.from(from.atStartOfDay(SEOUL).toInstant()));
        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            days.add(byDate.getOrDefault(d, new Day(d, 0, 0)));
        }

        List<RecentOrder> recent = jdbc.query("""
                SELECT order_number, order_name, status, total_amount_krw, recipient_name, paid_at
                FROM orders WHERE paid_at IS NOT NULL ORDER BY paid_at DESC LIMIT 5
                """, (rs, i) -> new RecentOrder(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getTimestamp(6).toInstant()));

        int[] products = new int[2];
        jdbc.query("SELECT status, count(*) FROM product GROUP BY status", rs -> {
            if ("PUBLISHED".equals(rs.getString(1))) {
                products[0] = rs.getInt(2);
            } else if ("DRAFT".equals(rs.getString(1))) {
                products[1] = rs.getInt(2);
            }
        });

        return new Dashboard(todo, days.getLast(), days, recent, products[0], products[1]);
    }

    // ── 사이즈별 판매 ──────────────────────────────────────────

    /**
     * 사이즈 한 칸. kept = 팔린 수 − 반품 − 다른 사이즈로 교환해 나간 수 + 이 사이즈로 교환해 들어온 수.
     * "어느 사이즈를 더 만들지" 는 sold 가 아니라 kept 로 본다 — 교환이 많은 사이즈는 실측이 어긋났다는 신호다.
     */
    public record SizeRow(String size, int sold, int returned, int exchangedOut, int exchangedIn, int kept) {
    }

    public record ProductSizes(UUID productId, String productName, int sold, int kept, List<SizeRow> sizes) {
    }

    /** days = 0 이면 전체 기간. 결제 시각 기준. 취소된 주문은 빼고, 교환·반품은 완료된 것만 센다. */
    public record SizeStats(int days, List<ProductSizes> products) {
    }

    private static final class Acc {
        final UUID productId;
        String name;
        final String size;
        int sortOrder = Integer.MAX_VALUE;
        int sold;
        int returned;
        int out;
        int in;

        Acc(UUID productId, String size) {
            this.productId = productId;
            this.size = size;
        }
    }

    @Transactional(readOnly = true)
    public SizeStats sizeStats(int days) {
        Instant since = days > 0 ? LocalDate.now(SEOUL).minusDays(days - 1L).atStartOfDay(SEOUL).toInstant()
                : Instant.EPOCH;
        Timestamp sinceTs = Timestamp.from(since);
        Map<String, Acc> acc = new HashMap<>();

        jdbc.query("""
                SELECT oi.product_id, coalesce(max(p.name), max(oi.product_name)), oi.size, sum(oi.quantity)
                FROM order_item oi
                JOIN orders o ON o.id = oi.order_id
                LEFT JOIN product p ON p.id = oi.product_id
                WHERE o.status IN ('PAID', 'IN_PRODUCTION', 'SHIPPED', 'DELIVERED') AND o.paid_at >= ?
                GROUP BY oi.product_id, oi.size
                """, rs -> {
            UUID productId = rs.getObject(1, UUID.class);
            String size = rs.getString(3);
            Acc a = acc.computeIfAbsent(key(productId, size), k -> new Acc(productId, size));
            a.name = rs.getString(2);
            a.sold += rs.getInt(4);
        }, sinceTs);

        jdbc.query("""
                SELECT oi.product_id, oi.size, r.type, ri.exchange_size, sum(ri.quantity)
                FROM return_request_item ri
                JOIN return_request r ON r.id = ri.return_id
                JOIN order_item oi ON oi.id = ri.order_item_id
                JOIN orders o ON o.id = oi.order_id
                WHERE r.status = 'COMPLETED' AND o.paid_at >= ?
                GROUP BY oi.product_id, oi.size, r.type, ri.exchange_size
                """, rs -> {
            UUID productId = rs.getObject(1, UUID.class);
            String size = rs.getString(2);
            int qty = rs.getInt(5);
            Acc from = acc.computeIfAbsent(key(productId, size), k -> new Acc(productId, size));
            if ("RETURN".equals(rs.getString(3))) {
                from.returned += qty;
            } else {
                from.out += qty;
                String to = rs.getString(4);
                if (to != null) {
                    acc.computeIfAbsent(key(productId, to), k -> new Acc(productId, to)).in += qty;
                }
            }
        }, sinceTs);

        // 사이즈 순서는 상품에 등록된 순서를 따른다 — 교환으로만 생긴 칸(그 사이즈로 판 적은 없다)도
        if (!acc.isEmpty()) {
            jdbc.query("SELECT product_id, size, sort_order FROM product_sku WHERE product_id = ANY (?)", rs -> {
                Acc a = acc.get(key(rs.getObject(1, UUID.class), rs.getString(2)));
                if (a != null) {
                    a.sortOrder = Math.min(a.sortOrder, rs.getInt(3));
                }
            }, (Object) acc.values().stream().map(a -> a.productId).distinct().toArray(UUID[]::new));
        }

        Map<UUID, String> names = new HashMap<>();
        acc.values().forEach(a -> {
            if (a.name != null) {
                names.putIfAbsent(a.productId, a.name);
            }
        });

        Map<UUID, List<Acc>> byProduct = new LinkedHashMap<>();
        acc.values().stream()
                .sorted(Comparator.comparing((Acc a) -> names.getOrDefault(a.productId, ""))
                        .thenComparingInt(a -> a.sortOrder)
                        .thenComparing(a -> a.size, AdminInsightService::compareSize))
                .forEach(a -> byProduct.computeIfAbsent(a.productId, k -> new ArrayList<>()).add(a));

        List<ProductSizes> products = new ArrayList<>();
        byProduct.forEach((productId, list) -> {
            List<SizeRow> rows = list.stream().map(a -> new SizeRow(a.size, a.sold, a.returned, a.out, a.in,
                    a.sold - a.returned - a.out + a.in)).toList();
            products.add(new ProductSizes(productId, names.getOrDefault(productId, "(이름 없음)"),
                    rows.stream().mapToInt(SizeRow::sold).sum(), rows.stream().mapToInt(SizeRow::kept).sum(), rows));
        });
        products.sort(Comparator.comparingInt(ProductSizes::sold).reversed());
        return new SizeStats(Math.max(days, 0), products);
    }

    private static String key(UUID productId, String size) {
        return productId + "|" + size;
    }

    /** "95" < "100" < "105" 처럼 숫자 사이즈는 숫자로, 나머지(S · M · L)는 글자로. */
    static int compareSize(String a, String b) {
        boolean na = a.matches("\\d+(\\.\\d+)?");
        boolean nb = b.matches("\\d+(\\.\\d+)?");
        if (na && nb) {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        }
        return na ? -1 : nb ? 1 : a.compareTo(b);
    }
}
