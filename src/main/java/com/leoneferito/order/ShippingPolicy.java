package com.leoneferito.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 배송비 정책 (2026-10-14 — 국내 의류 쇼핑몰에서 흔한 방식으로 정했다).
 *
 * <ul>
 *   <li>기본 배송비 {@code app.order.shipping-fee-krw}(3,000원), 상품 금액이
 *       {@code free-shipping-threshold-krw}(50,000원) 이상이면 0원.</li>
 *   <li>제주 · 도서산간은 우편번호로 추가 배송비 — {@code remote-areas}. 무료 배송 주문에도 붙는다
 *       (택배사가 받는 추가 운임이라서). 장바구니는 주소를 모르니 기본 배송비만, 주문서가 우편번호로 더한다.</li>
 * </ul>
 *
 * <p><b>기본 배송비가 비어 있으면 주문을 받지 않는다(fail closed)</b> — {@code ORDER_SHIPPING_FEE_KRW=} 처럼 일부러 비운 경우.
 * 배송비를 지어내면 그 금액이 실제로 결제된다.
 */
@Component
public class ShippingPolicy {

    /** 추가 배송비를 받는 우편번호 범위(양 끝 포함). label 은 화면 · 메일에 그대로 나간다(예: 제주). */
    public record RemoteArea(int fromZip, int toZip, long extraKrw, String label) {
        boolean covers(int zip) {
            return zip >= fromZip && zip <= toZip;
        }
    }

    /** 배송비 한 건. base — 기본(무료 기준 이상이면 0), extra — 제주 · 도서산간 추가. */
    public record Fee(long baseKrw, long extraKrw, String extraLabel) {
        public long totalKrw() {
            return baseKrw + extraKrw;
        }
    }

    private final Long feeKrw;
    private final Long freeThresholdKrw;
    private final List<RemoteArea> remoteAreas;

    public ShippingPolicy(@Value("${app.order.shipping-fee-krw:#{null}}") Long feeKrw,
                          @Value("${app.order.free-shipping-threshold-krw:#{null}}") Long freeThresholdKrw,
                          @Value("${app.order.remote-areas:}") String remoteAreas) {
        if (feeKrw != null && feeKrw < 0) {
            throw new IllegalArgumentException("배송비는 0원 이상이어야 한다");
        }
        this.feeKrw = feeKrw;
        this.freeThresholdKrw = freeThresholdKrw;
        this.remoteAreas = parse(remoteAreas);
    }

    public boolean isReady() {
        return feeKrw != null;
    }

    /** 주소를 모를 때(장바구니) — 기본 배송비만. 정책이 없으면 비어 있다 — 그러면 주문을 열지 않는다. */
    public OptionalLong feeFor(long itemsAmountKrw) {
        if (feeKrw == null) {
            return OptionalLong.empty();
        }
        if (freeThresholdKrw != null && itemsAmountKrw >= freeThresholdKrw) {
            return OptionalLong.of(0);
        }
        return OptionalLong.of(feeKrw);
    }

    /** 받는 곳까지 알 때(주문서 · 주문) — 기본 + 제주 · 도서산간 추가. 우편번호가 없거나 형식이 틀리면 추가 없이. */
    public Optional<Fee> feeFor(long itemsAmountKrw, String zipCode) {
        OptionalLong base = feeFor(itemsAmountKrw);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        RemoteArea area = areaOf(zipCode);
        return Optional.of(area == null ? new Fee(base.getAsLong(), 0, null)
                : new Fee(base.getAsLong(), area.extraKrw(), area.label()));
    }

    RemoteArea areaOf(String zipCode) {
        if (zipCode == null || !zipCode.trim().matches("^[0-9]{5}$")) {
            return null;
        }
        int zip = Integer.parseInt(zipCode.trim());
        return remoteAreas.stream().filter(a -> a.covers(zip)).findFirst().orElse(null);
    }

    public Long getFreeThresholdKrw() {
        return freeThresholdKrw;
    }

    public List<RemoteArea> getRemoteAreas() {
        return remoteAreas;
    }

    /**
     * "63000-63644:3000:제주,40200-40240:5000:도서산간" → 목록. 형식이 틀리면 기동을 멈춘다 —
     * 조용히 건너뛰면 추가 배송비가 소리 없이 빠진다.
     */
    static List<RemoteArea> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        List<RemoteArea> list = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] f = p.split(":", 3);
            String[] range = f[0].split("-");
            if (f.length != 3 || range.length != 2 || f[2].isBlank()) {
                throw new IllegalArgumentException("추가 배송비 지역 형식이 틀렸다(시작-끝:금액:이름): " + p);
            }
            int from = Integer.parseInt(range[0].trim());
            int to = Integer.parseInt(range[1].trim());
            long extra = Long.parseLong(f[1].trim());
            if (from > to || extra < 0) {
                throw new IllegalArgumentException("추가 배송비 지역 값이 틀렸다: " + p);
            }
            list.add(new RemoteArea(from, to, extra, f[2].trim()));
        }
        return List.copyOf(list);
    }
}
