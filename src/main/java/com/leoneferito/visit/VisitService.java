package com.leoneferito.visit;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접속 통계 (V51, 2026-11-09 고객 "날짜 · 요일 · 시간대별 접속, 접속 경로").
 *
 * <p>손님 화면이 페이지를 열 때 {@link #record} 로 한 줄을 남기고, 관리자 화면은 {@link #stats} 로 묶어서 본다.
 * IP · 브라우저 정보 원본은 남기지 않는다 — 그날의 소금과 섞은 해시(앞 16자리)만 남기고, 소금은 다음 날 지운다(RetentionCleanup).
 * 시간은 모두 한국 시간으로 묶는다.
 */
@Service
public class VisitService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 한 번에 볼 수 있는 기간 — 하루 단위 줄이 이보다 길면 화면이 읽기 어렵다 */
    static final int MAX_DAYS = 366;

    /** 검색 로봇 · 미리보기 · 자동 점검 — 사람이 아니므로 세지 않는다 */
    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|facebookexternalhit|headless|lighthouse|pingdom|monitor|curl|wget|python|java/",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLET = Pattern.compile("ipad|tablet|(android(?!.*mobile))", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE = Pattern.compile("mobi|iphone|ipod|android", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public VisitService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 유입 경로. 광고 · 링크에 utm_source 를 붙였으면 그 값으로, 아니면 들어온 주소(referrer)로 가른다.
     * 화면에는 한글 이름으로 나간다(FE).
     */
    public enum Source {
        DIRECT, NAVER, GOOGLE, DAUM, KAKAO, INSTAGRAM, FACEBOOK, YOUTUBE, OTHER;

        static Source of(String utmSource, String referrerHost) {
            String key = utmSource != null ? utmSource : referrerHost;
            if (key == null) {
                return DIRECT;
            }
            String k = key.toLowerCase(Locale.ROOT);
            if (k.contains("naver")) return NAVER;
            if (k.contains("google")) return GOOGLE;
            if (k.contains("daum")) return DAUM;
            if (k.contains("kakao")) return KAKAO;
            if (k.contains("instagram")) return INSTAGRAM;
            if (k.contains("facebook") || k.equals("fb") || k.endsWith("fb.com") || k.endsWith("fb.me")) return FACEBOOK;
            if (k.contains("youtube") || k.contains("youtu.be")) return YOUTUBE;
            return OTHER;
        }
    }

    /** 화면이 보내는 한 줄. referrer 는 바깥에서 들어온 첫 페이지에만 실린다 */
    public record Hit(String path, String referrer, boolean entry, String utmSource, String utmMedium, String utmCampaign) {
    }

    /**
     * 한 줄 남긴다. 로봇 · 관리자 화면 · 모양이 이상한 주소는 조용히 버린다(손님 화면은 결과를 기다리지 않는다).
     * selfHost — 우리 사이트 주소. 안에서 옮겨 다닌 referrer 는 유입 경로가 아니다.
     */
    @Transactional
    public void record(Hit hit, String ip, String userAgent, String selfHost) {
        String ua = userAgent == null ? "" : userAgent;
        String path = cleanPath(hit.path());
        if (ua.isBlank() || BOT.matcher(ua).find() || path == null) {
            return;
        }
        String host = referrerHost(hit.referrer());
        if (host != null && selfHost != null && (host.equals(selfHost) || host.endsWith("." + selfHost))) {
            host = null;
        }
        String utmSource = clip(hit.utmSource(), 60);
        LocalDate today = LocalDate.now(SEOUL);
        jdbc.update("""
                INSERT INTO site_visit (visitor, entry, path, source, referrer_host, utm_source, utm_medium, utm_campaign, device)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                visitor(today, ip, ua), hit.entry(), path,
                hit.entry() ? Source.of(utmSource, host).name() : Source.DIRECT.name(),
                hit.entry() ? host : null,
                hit.entry() ? utmSource : null,
                hit.entry() ? clip(hit.utmMedium(), 60) : null,
                hit.entry() ? clip(hit.utmCampaign(), 60) : null,
                device(ua));
    }

    /** 그날의 소금과 섞은 해시 앞 16자리. 소금은 날마다 새로 만들고 DB 에 둔다 — 서버를 다시 켜도 같은 날은 같은 값 */
    private String visitor(LocalDate day, String ip, String ua) {
        jdbc.update("INSERT INTO site_visit_salt (day, salt) VALUES (?, ?) ON CONFLICT (day) DO NOTHING",
                day, HexFormat.of().formatHex(random.generateSeed(16)));
        String salt = jdbc.queryForObject("SELECT salt FROM site_visit_salt WHERE day = ?", String.class, day);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + "|" + ip + "|" + ua).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String device(String ua) {
        if (TABLET.matcher(ua).find()) return "TABLET";
        if (MOBILE.matcher(ua).find()) return "MOBILE";
        return "DESKTOP";
    }

    /** "/" 로 시작하는 경로만. 쿼리 · 조각(#)은 떼고, 관리자 화면은 세지 않는다 */
    static String cleanPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            return null;
        }
        String p = path.split("[?#]", 2)[0];
        if (p.startsWith("/admin")) {
            return null;
        }
        return p.length() > 200 ? p.substring(0, 200) : p;
    }

    static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referrer.trim()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host.length() > 100 ? host.substring(0, 100) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ── 관리자 통계 ─────────────────────────────────────────

    public record Totals(long visitors, long sessions, long pageViews) {
    }

    /** visitors — 그날 다른 사람 수(날마다 따로 센다), sessions — 방문 수, pageViews — 본 페이지 수 */
    public record Day(LocalDate date, long visitors, long sessions, long pageViews) {
    }

    /** dow — 1 월요일 … 7 일요일 */
    public record Weekday(int dow, long sessions) {
    }

    public record Hour(int hour, long sessions) {
    }

    public record Count(String key, long sessions) {
    }

    public record Stats(LocalDate from, LocalDate to, Totals totals, List<Day> daily, List<Weekday> weekdays,
                        List<Hour> hours, List<Count> sources, List<Count> referrers, List<Count> landings,
                        List<Count> devices) {
    }

    /** from ~ to (한국 날짜, 둘 다 포함). 비어 있는 날 · 요일 · 시간도 0 으로 채워 준다 — 그래프가 빈칸 없이 그려진다 */
    @Transactional(readOnly = true)
    public Stats stats(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_DAYS - 1).isBefore(to)) {
            throw new IllegalArgumentException("기간은 1 ~ " + MAX_DAYS + "일로 골라 주세요.");
        }
        Timestamp start = Timestamp.from(from.atStartOfDay(SEOUL).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(SEOUL).toInstant());
        String local = "(visited_at AT TIME ZONE 'Asia/Seoul')";
        String range = " FROM site_visit WHERE visited_at >= ? AND visited_at < ?";

        List<Day> rows = jdbc.query("SELECT " + local + "::date AS d, COUNT(DISTINCT visitor) AS v,"
                        + " COUNT(*) FILTER (WHERE entry) AS s, COUNT(*) AS p" + range + " GROUP BY d",
                (rs, i) -> new Day(rs.getObject("d", LocalDate.class), rs.getLong("v"), rs.getLong("s"), rs.getLong("p")),
                start, end);
        List<Day> daily = from.datesUntil(to.plusDays(1))
                .map(d -> rows.stream().filter(r -> r.date().equals(d)).findFirst().orElse(new Day(d, 0, 0, 0)))
                .toList();
        Totals totals = new Totals(daily.stream().mapToLong(Day::visitors).sum(),
                daily.stream().mapToLong(Day::sessions).sum(), daily.stream().mapToLong(Day::pageViews).sum());

        List<Weekday> dowRows = jdbc.query("SELECT EXTRACT(ISODOW FROM " + local + ")::int AS w, COUNT(*) AS s"
                        + range + " AND entry GROUP BY w",
                (rs, i) -> new Weekday(rs.getInt("w"), rs.getLong("s")), start, end);
        List<Weekday> weekdays = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(w -> dowRows.stream().filter(r -> r.dow() == w).findFirst().orElse(new Weekday(w, 0)))
                .toList();

        List<Hour> hourRows = jdbc.query("SELECT EXTRACT(HOUR FROM " + local + ")::int AS h, COUNT(*) AS s"
                        + range + " AND entry GROUP BY h",
                (rs, i) -> new Hour(rs.getInt("h"), rs.getLong("s")), start, end);
        List<Hour> hours = java.util.stream.IntStream.range(0, 24)
                .mapToObj(h -> hourRows.stream().filter(r -> r.hour() == h).findFirst().orElse(new Hour(h, 0)))
                .toList();

        return new Stats(from, to, totals, daily, weekdays, hours,
                counts("source", range, start, end, 20),
                counts("referrer_host", range + " AND referrer_host IS NOT NULL", start, end, 10),
                counts("path", range, start, end, 10),
                counts("device", range, start, end, 3));
    }

    /** 방문 시작 줄을 한 칸으로 묶어 많은 순 — column 은 이 클래스 안에서 고정된 이름만 넘긴다 */
    private List<Count> counts(String column, String range, Timestamp start, Timestamp end, int limit) {
        return jdbc.query("SELECT " + column + " AS k, COUNT(*) AS s" + range + " AND entry GROUP BY k ORDER BY s DESC, k LIMIT " + limit,
                (rs, i) -> new Count(rs.getString("k"), rs.getLong("s")), start, end);
    }
}
