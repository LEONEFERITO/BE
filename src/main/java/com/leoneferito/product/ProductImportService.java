package com.leoneferito.product;

import com.leoneferito.common.csv.CsvReader;
import com.leoneferito.product.api.AdminProductRequests;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 일괄 등록 — 엑셀 한 줄이 상품 하나 (2026-10-16 "엑셀로 한번에 등록").
 *
 * <p>2026-10-27: 엑셀 파일(.xlsx)을 그대로 받는다(CSV 도 그대로 받는다 — 파일 앞머리 "PK" 로 가른다). 고객 양식
 * "브랜드 제품 일괄 등록" 의 칸 이름(판매용상품명 · 대분류 · 소분류)도 알아보고, 관리자 전용 정보(관리용상품명 · 원단공급사 ·
 * 원단컬렉션 · 원단품번 · 공임처 · 원가, V39)도 받는다. 오류 메시지의 칸 이름은 올린 파일의 머리줄 그대로다.
 *
 * <p><b>전부 아니면 전무.</b> 모든 줄을 먼저 검사하고, 한 줄이라도 틀리면 아무것도 저장하지 않은 채 "몇 번째 줄 · 어느 칸 · 왜" 를
 * 전부 돌려준다. 반쯤 들어가면 관리자는 무엇이 들어갔는지 하나하나 찾아야 한다. 맞으면 한 트랜잭션에서 전부 초안으로 만든다 —
 * 사진 · 공개는 상품마다 수정 화면에서. 실측 · 재고는 받지 않는다(사이즈 이름만).
 *
 * <p>머리줄(첫 줄)의 칸 이름으로 열을 찾는다 — 열 순서를 바꾸거나 쓰지 않는 열을 지워도 된다. 칸 이름의 공백 · 괄호 설명 · * 는
 * 무시한다("제작기간(일)" = "제작기간"). 분류 · 라인 · 세부 분류는 한글(셔츠 · 페리토)과 영문(SHIRT · FERITO) 둘 다 받는다.
 */
@Service
public class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    public static final int MAX_ROWS = 200;
    public static final int MAX_BYTES = 1024 * 1024;
    /** 엑셀은 같은 내용이어도 CSV 보다 크다(서식 · 압축 꾸러미). */
    public static final int MAX_XLSX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIZES = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 열. label 이 양식 머리줄의 이름이고, aliases 는 같은 칸으로 알아보는 다른 이름이다.
     * 열 순서가 곧 양식(template)의 순서 — 고객 최종 양식(2026-10-27, 22칸)과 같은 차례, 그 뒤에 고객 양식에 없는 정가.
     */
    enum Col {
        NAME("판매용상품명", true, "상품명"), ADMIN_NAME("관리용상품명", false), LINE("라인", true),
        CATEGORY("대분류", true, "분류"), STYLE("소분류", false, "세부분류"), SIZES("사이즈", false), COLOR("색상", false),
        FABRIC_SUPPLIER("원단공급사", false), FABRIC_COLLECTION("원단컬렉션", false), FABRIC_CODE("원단품번", false),
        WORKSHOP("공임처", false), MANUFACTURER("제조사", false), PRICE("판매가", false), COST("원가", false),
        LEAD_TIME("제작기간", false), ORIGIN("원산지", false, "제조국"), SUMMARY("한줄설명", false),
        DESCRIPTION("상세설명", false), FABRIC("소재", false), CARE("세탁", false), MADE_ON("제조연월", false),
        SLUG("주소", false), LIST_PRICE("정가", false);

        final String label;
        final boolean required;
        final List<String> aliases;

        Col(String label, boolean required, String... aliases) {
            this.label = label;
            this.required = required;
            this.aliases = List.of(aliases);
        }
    }

    private static final Map<String, ProductCategory> CATEGORY = Map.ofEntries(
            Map.entry("수트", ProductCategory.SUIT), Map.entry("정장", ProductCategory.SUIT),
            Map.entry("재킷", ProductCategory.JACKET), Map.entry("자켓", ProductCategory.JACKET),
            Map.entry("트라우저", ProductCategory.TROUSERS), Map.entry("바지", ProductCategory.TROUSERS),
            Map.entry("셔츠", ProductCategory.SHIRT), Map.entry("신발", ProductCategory.SHOES), Map.entry("구두", ProductCategory.SHOES),
            Map.entry("액세서리", ProductCategory.ACCESSORIES), Map.entry("악세서리", ProductCategory.ACCESSORIES));
    private static final Map<String, ProductLine> LINE = Map.of("레오네", ProductLine.LEONE, "페리토", ProductLine.FERITO);
    private static final Map<String, ProductStyle> STYLE = Map.of(
            "레귤러", ProductStyle.REGULAR, "스트레이트", ProductStyle.STRAIGHT, "플레어", ProductStyle.FLARE,
            "옥스퍼드", ProductStyle.OXFORD, "로퍼", ProductStyle.LOAFER);

    /** 틀린 칸 하나. row 는 엑셀에서 보이는 줄 번호(머리줄이 1). column 이 비면 줄 전체 · 파일 문제. */
    public record RowError(int row, String column, String message) {
    }

    public record Created(int row, UUID id, String slug, String name) {
    }

    /** 검사에서 걸린 것들. 하나도 저장하지 않았다. 400. */
    public static class ImportException extends RuntimeException {
        private final List<RowError> errors;

        public ImportException(List<RowError> errors) {
            super(errors.size() + "곳을 고쳐 주세요. 아무것도 저장하지 않았습니다.");
            this.errors = List.copyOf(errors);
        }

        public List<RowError> getErrors() {
            return errors;
        }
    }

    private final AdminProductService service;
    private final ProductRepository products;
    private final Validator validator;

    public ProductImportService(AdminProductService service, ProductRepository products, Validator validator) {
        this.service = service;
        this.products = products;
        this.validator = validator;
    }

    /** 양식 — 머리줄 + 예시 두 줄. BOM 을 붙인 UTF-8 이라 엑셀에서 바로 열어도 한글이 깨지지 않는다. */
    public static byte[] template() {
        StringBuilder csv = new StringBuilder("\uFEFF");
        List<String> head = new ArrayList<>();
        for (Col c : Col.values()) {
            head.add(c.label + (c.required ? "*" : "") + (c == Col.LEAD_TIME ? "(일)" : c == Col.SLUG ? "(비우면 자동)" : ""));
        }
        csv.append(String.join(",", head)).append("\r\n");
        // 칸 차례는 Col 과 같다: 판매용상품명 · 관리용상품명 · 라인 · 대분류 · 소분류 · 사이즈 · 색상 · 원단공급사 · 원단컬렉션 ·
        // 원단품번 · 공임처 · 제조사 · 판매가 · 원가 · 제작기간 · 원산지 · 한줄설명 · 상세설명 · 소재 · 세탁 · 제조연월 · 주소 · 정가
        csv.append(row("클래식 옥스퍼드 셔츠 · 화이트", "OX-WH-01", "페리토", "셔츠", "", "95/100/105/110", "화이트",
                "", "", "", "", "레오네페리토", "100000", "", "14", "대한민국", "운동한 체형을 위한 셔츠",
                "어깨와 가슴에 여유를 두고 허리를 정리했습니다.", "면 100%", "드라이클리닝", "2026년 10월", "", ""))
                .append("\r\n");
        csv.append(row("스트레이트핏 트라우저 · 네이비", "ST-NV-01", "레오네", "트라우저", "스트레이트", "30/32/34/36", "네이비",
                "", "", "", "", "레오네페리토", "150000", "", "21", "대한민국", "허벅지 여유, 곧게 떨어지는 선", "", "울 100%",
                "드라이클리닝", "2026년 10월", "straight-trousers-navy", "180000")).append("\r\n");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String row(String... cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) {
            out.add(CsvReader.quote(c));
        }
        return String.join(",", out);
    }

    /**
     * 검사 → (모두 맞으면) 전부 초안으로 만든다. 하나라도 틀리면 {@link ImportException} — 저장한 것 없음.
     * 엑셀(.xlsx — zip 이라 "PK" 로 시작한다)이면 첫 시트를, 아니면 CSV 로 읽는다.
     */
    @Transactional
    public List<Created> importFile(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ImportException(List.of(new RowError(0, null, "파일이 비어 있습니다.")));
        }
        boolean xlsx = bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K';
        if (bytes.length > (xlsx ? MAX_XLSX_BYTES : MAX_BYTES)) {
            throw new ImportException(List.of(new RowError(0, null,
                    "파일이 너무 큽니다. " + (xlsx ? "5MB" : "1MB") + " 이하로 나눠 올려 주세요.")));
        }
        List<List<String>> lines = xlsx ? XlsxRows.read(bytes) : CsvReader.parse(CsvReader.decode(bytes));
        if (lines.isEmpty()) {
            throw new ImportException(List.of(new RowError(0, null, "파일이 비어 있습니다.")));
        }

        Map<Col, String> names = new HashMap<>();
        Map<Col, Integer> at = header(lines.getFirst(), names);
        List<RowError> errors = new ArrayList<>();
        for (Col c : Col.values()) {
            if (c.required && !at.containsKey(c)) {
                errors.add(new RowError(1, c.label, "머리줄에 '" + c.label + "' 칸이 없습니다. 양식을 내려받아 쓰세요."));
            }
        }
        if (!errors.isEmpty()) {
            throw new ImportException(errors);
        }

        List<AdminProductRequests.Save> saves = new ArrayList<>();
        List<Integer> rowNumbers = new ArrayList<>();
        Set<String> slugsInFile = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = lines.get(i);
            if (cells.stream().allMatch(s -> s == null || s.isBlank())) {
                continue; // 빈 줄 — 엑셀이 끝에 남기는 줄
            }
            int rowNo = i + 1;
            if (saves.size() + errors.size() >= MAX_ROWS) {
                errors.add(new RowError(rowNo, null, "한 번에 " + MAX_ROWS + "개까지 올릴 수 있습니다. 파일을 나눠 주세요."));
                break;
            }
            AdminProductRequests.Save save = readRow(rowNo, cells, at, names, slugsInFile, errors);
            if (save != null) {
                saves.add(save);
                rowNumbers.add(rowNo);
            }
        }
        if (saves.isEmpty() && errors.isEmpty()) {
            errors.add(new RowError(2, null, "상품 줄이 없습니다. 머리줄 아래에 한 줄에 하나씩 적어 주세요."));
        }
        if (!errors.isEmpty()) {
            throw new ImportException(errors);
        }

        List<Created> created = new ArrayList<>();
        for (int k = 0; k < saves.size(); k++) {
            AdminProductRequests.Save s = saves.get(k);
            UUID id = service.create(s);
            created.add(new Created(rowNumbers.get(k), id, s.slug(), s.name()));
        }
        log.info("상품 일괄 등록 count={}", created.size());
        return created;
    }

    /**
     * 머리줄 → 열 위치. 공백 · 괄호 설명 · * 를 지우고 이름(또는 다른 이름)으로 찾는다. 모르는 칸은 무시.
     * names 에는 파일에 적힌 칸 이름을 남긴다 — 오류를 그 이름으로 알려 준다("4행 · 판매용상품명").
     */
    private static Map<Col, Integer> header(List<String> head, Map<Col, String> names) {
        Map<String, Col> byLabel = new HashMap<>();
        for (Col c : Col.values()) {
            byLabel.put(c.label, c);
            for (String a : c.aliases) {
                byLabel.put(a, c);
            }
        }
        Map<Col, Integer> at = new HashMap<>();
        for (int i = 0; i < head.size(); i++) {
            String key = head.get(i).replace("\uFEFF", "").replaceAll("\\(.*?\\)|\\*|\\s", "");
            Col c = byLabel.get(key);
            if (c != null && !at.containsKey(c)) {
                at.put(c, i);
                names.put(c, key);
            }
        }
        return at;
    }

    /** 한 줄 → 등록 요청. 틀린 칸은 errors 에 쌓고 null. */
    private AdminProductRequests.Save readRow(int rowNo, List<String> cells, Map<Col, Integer> at, Map<Col, String> names,
                                              Set<String> slugsInFile, List<RowError> errors) {
        int before = errors.size();
        Cells c = new Cells(rowNo, cells, at, names, errors);

        String name = c.text(Col.NAME, AdminProductRequests.NAME_MAX);
        if (name == null) {
            errors.add(new RowError(rowNo, c.name(Col.NAME), "상품명을 적어 주세요."));
        }
        ProductCategory category = c.pick(Col.CATEGORY, CATEGORY, ProductCategory.class);
        ProductLine line = c.pick(Col.LINE, LINE, ProductLine.class);
        if (c.raw(Col.CATEGORY) == null) {
            errors.add(new RowError(rowNo, c.name(Col.CATEGORY), "분류를 적어 주세요 (수트 · 재킷 · 트라우저 · 셔츠 · 신발 · 액세서리)."));
        }
        if (c.raw(Col.LINE) == null) {
            errors.add(new RowError(rowNo, c.name(Col.LINE), "라인을 적어 주세요 (레오네 · 페리토)."));
        }
        ProductStyle style = c.pick(Col.STYLE, STYLE, ProductStyle.class);
        Long price = c.won(Col.PRICE);
        Long listPrice = c.won(Col.LIST_PRICE);
        Long lead = c.number(Col.LEAD_TIME, 1, 365);

        String slug = c.raw(Col.SLUG);
        if (slug == null) {
            do {
                slug = "p-" + randomSuffix();
            } while (slugsInFile.contains(slug) || products.existsBySlug(slug));
        } else {
            slug = slug.trim().toLowerCase(Locale.ROOT);
            if (!slug.matches("^[a-z0-9]+(-[a-z0-9]+)*$") || slug.length() > 80) {
                errors.add(new RowError(rowNo, c.name(Col.SLUG), "영문 소문자 · 숫자 · 하이픈만, 80자 이하로 적어 주세요 (예: oxford-shirt-white)."));
            } else if (slugsInFile.contains(slug)) {
                errors.add(new RowError(rowNo, c.name(Col.SLUG), "같은 주소가 파일 안에 두 번 있습니다: " + slug));
            } else if (products.existsBySlug(slug)) {
                errors.add(new RowError(rowNo, c.name(Col.SLUG), "이미 쓰는 주소입니다: " + slug));
            }
        }
        slugsInFile.add(slug);

        List<AdminProductRequests.Sku> skus = new ArrayList<>();
        String sizes = c.raw(Col.SIZES);
        if (sizes != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String s : sizes.split("[/|,]")) {
                if (!s.isBlank()) {
                    seen.add(s.trim());
                }
            }
            if (seen.size() > MAX_SIZES || seen.stream().anyMatch(s -> s.length() > 20)) {
                errors.add(new RowError(rowNo, c.name(Col.SIZES), "사이즈는 " + MAX_SIZES + "개까지, 하나에 20자까지입니다 (예: 95/100/105)."));
            } else {
                int order = 0;
                for (String s : seen) {
                    skus.add(new AdminProductRequests.Sku(s, order++, true, null, List.of()));
                }
            }
        }

        AdminProductRequests.Save save = new AdminProductRequests.Save(slug, name, category, line, style, price, listPrice,
                c.text(Col.SUMMARY, AdminProductRequests.SUMMARY_MAX), c.text(Col.DESCRIPTION, AdminProductRequests.BODY_MAX),
                null, null, c.text(Col.FABRIC, AdminProductRequests.SHORT_BODY_MAX), c.text(Col.CARE, AdminProductRequests.SHORT_BODY_MAX),
                c.text(Col.COLOR, AdminProductRequests.NOTICE_MAX), c.text(Col.MANUFACTURER, AdminProductRequests.NOTICE_MAX),
                c.text(Col.ORIGIN, AdminProductRequests.NOTICE_MAX), c.text(Col.MADE_ON, AdminProductRequests.NOTICE_MAX),
                null, null, null, lead == null ? null : lead.shortValue(), null, null, null, null, List.of(), skus,
                new AdminProductRequests.Internal(c.text(Col.ADMIN_NAME, AdminProductRequests.NAME_MAX * 2),
                        c.text(Col.FABRIC_SUPPLIER, AdminProductRequests.NOTICE_MAX),
                        c.text(Col.FABRIC_COLLECTION, AdminProductRequests.NOTICE_MAX),
                        c.text(Col.FABRIC_CODE, AdminProductRequests.NOTICE_MAX),
                        c.text(Col.WORKSHOP, AdminProductRequests.NOTICE_MAX), c.won(Col.COST)));

        if (errors.size() == before) {
            // 마지막 그물 — 화면 등록과 같은 규칙(세부 분류가 분류와 맞는지 등). 위에서 거른 것은 여기 오지 않는다
            for (ConstraintViolation<AdminProductRequests.Save> v : validator.validate(save)) {
                errors.add(new RowError(rowNo, null, v.getMessage()));
            }
        }
        return errors.size() == before ? save : null;
    }

    /**
     * 엑셀(.xlsx) 첫 시트 → 줄 목록. 셀은 엑셀 화면에 보이는 글자 그대로 읽는다(DataFormatter) — 150000 은 "150000",
     * 서식이 "#,##0" 이면 "150,000"(won() 이 쉼표를 받는다). 수식 칸은 계산한 값. 빈 줄도 자리를 지켜 줄 번호가 엑셀과 같다.
     * 압축 폭탄 · 거대한 시트는 POI 의 기본 방어(ZipSecureFile)와 MAX_ROWS 가 막는다.
     */
    static final class XlsxRows {

        private XlsxRows() {
        }

        static List<List<String>> read(byte[] bytes) {
            try (org.apache.poi.ss.usermodel.Workbook wb =
                         new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
                if (wb.getNumberOfSheets() == 0) {
                    return List.of();
                }
                org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
                org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter(Locale.KOREA);
                org.apache.poi.ss.usermodel.FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
                int last = Math.min(sheet.getLastRowNum(), MAX_ROWS + 1);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r <= last; r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>();
                    if (row != null) {
                        for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                            cells.add(cell == null ? "" : fmt.formatCellValue(cell, eval));
                        }
                    }
                    rows.add(cells);
                }
                return rows;
            } catch (java.io.IOException | RuntimeException e) {
                throw new ImportException(List.of(new RowError(0, null,
                        "엑셀 파일을 읽지 못했습니다. 엑셀에서 다시 저장(.xlsx)해 올려 주세요.")));
            }
        }
    }

    private static String randomSuffix() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            s.append(abc.charAt(RANDOM.nextInt(abc.length())));
        }
        return s.toString();
    }

    /** 한 줄의 칸 읽기 — 틀리면 errors 에 쌓고 null. */
    private record Cells(int rowNo, List<String> cells, Map<Col, Integer> at, Map<Col, String> names, List<RowError> errors) {

        /** 오류에 쓸 칸 이름 — 파일 머리줄에 적힌 그대로, 없으면 양식 이름 */
        String name(Col c) {
            return names.getOrDefault(c, c.label);
        }

        String raw(Col c) {
            Integer i = at.get(c);
            if (i == null || i >= cells.size()) {
                return null;
            }
            String v = cells.get(i).trim();
            return v.isEmpty() ? null : v;
        }

        String text(Col c, int max) {
            String v = raw(c);
            if (v != null && v.length() > max) {
                errors.add(new RowError(rowNo, name(c), max + "자 이하로 줄여 주세요 (지금 " + v.length() + "자)."));
                return null;
            }
            return v;
        }

        /** "100,000" · "100000원" 도 받는다. */
        Long won(Col c) {
            String v = raw(c);
            if (v == null) {
                return null;
            }
            String digits = v.replaceAll("[,\\s원]", "");
            if (!digits.matches("^[0-9]{1,10}$") || Long.parseLong(digits) <= 0) {
                errors.add(new RowError(rowNo, name(c), "숫자로 적어 주세요 (예: 100000)."));
                return null;
            }
            return Long.parseLong(digits);
        }

        Long number(Col c, int min, int max) {
            String v = raw(c);
            if (v == null) {
                return null;
            }
            String digits = v.replaceAll("[\\s일]", "");
            if (!digits.matches("^[0-9]{1,4}$") || Long.parseLong(digits) < min || Long.parseLong(digits) > max) {
                errors.add(new RowError(rowNo, name(c), min + "~" + max + " 사이 숫자로 적어 주세요."));
                return null;
            }
            return Long.parseLong(digits);
        }

        /** 한글 이름 또는 영문 이름(대소문자 무시). 모르는 값이면 받을 수 있는 값을 알려 준다. */
        <E extends Enum<E>> E pick(Col c, Map<String, E> korean, Class<E> type) {
            String v = raw(c);
            if (v == null) {
                return null;
            }
            E k = korean.get(v.replaceAll("\\s", ""));
            if (k != null) {
                return k;
            }
            for (E e : type.getEnumConstants()) {
                if (e.name().equalsIgnoreCase(v)) {
                    return e;
                }
            }
            errors.add(new RowError(rowNo, name(c), "'" + v + "' 는 쓸 수 없는 값입니다. 쓸 수 있는 값: " + String.join(" · ", korean.keySet()) + "."));
            return null;
        }
    }
}