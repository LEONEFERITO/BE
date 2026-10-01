package com.leoneferito.content.api;

import com.leoneferito.content.ContentService;
import com.leoneferito.content.Faq;
import com.leoneferito.content.Notice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지사항 · FAQ.
 * 손님: {@code GET /api/notices} · {@code /api/notices/{id}} · {@code /api/faqs} (공개만, 로그인 없이).
 * 관리자: {@code /api/admin/notices/**} · {@code /api/admin/faqs/**} (ADMIN).
 */
@RestController
public class ContentController {

    private final ContentService content;

    public ContentController(ContentService content) {
        this.content = content;
    }

    // ── 손님 ────────────────────────────────────────────────────

    @GetMapping("/api/notices")
    public NoticePage notices(@RequestParam(defaultValue = "0") int page) {
        var p = content.publicNotices(page);
        return new NoticePage(p.map(NoticeRow::of).getContent(), p.getNumber(), p.getTotalPages(), p.getTotalElements());
    }

    @GetMapping("/api/notices/{id}")
    public NoticeView notice(@PathVariable UUID id) {
        return NoticeView.of(content.publicNotice(id));
    }

    @GetMapping("/api/faqs")
    public List<FaqView> faqs() {
        return content.publicFaqs().stream().map(FaqView::of).toList();
    }

    // ── 관리자 공지 ─────────────────────────────────────────────

    @GetMapping("/api/admin/notices")
    public List<NoticeView> adminNotices() {
        return content.adminNotices().stream().map(NoticeView::of).toList();
    }

    @GetMapping("/api/admin/notices/{id}")
    public NoticeView adminNotice(@PathVariable UUID id) {
        return NoticeView.of(content.adminNotice(id));
    }

    @PostMapping("/api/admin/notices")
    public ResponseEntity<NoticeView> createNotice(@Valid @RequestBody NoticeInput in) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(NoticeView.of(content.createNotice(in.title(), in.body(), in.pinned(), in.published())));
    }

    @PutMapping("/api/admin/notices/{id}")
    public NoticeView updateNotice(@PathVariable UUID id, @Valid @RequestBody NoticeInput in) {
        return NoticeView.of(content.updateNotice(id, in.title(), in.body(), in.pinned(), in.published()));
    }

    @DeleteMapping("/api/admin/notices/{id}")
    public ResponseEntity<Void> deleteNotice(@PathVariable UUID id) {
        content.deleteNotice(id);
        return ResponseEntity.noContent().build();
    }

    // ── 관리자 FAQ ──────────────────────────────────────────────

    @GetMapping("/api/admin/faqs")
    public List<FaqView> adminFaqs() {
        return content.adminFaqs().stream().map(FaqView::of).toList();
    }

    @PostMapping("/api/admin/faqs")
    public ResponseEntity<FaqView> createFaq(@Valid @RequestBody FaqInput in) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FaqView.of(content.createFaq(in.category(), in.question(), in.answer(), in.published())));
    }

    @PutMapping("/api/admin/faqs/{id}")
    public FaqView updateFaq(@PathVariable UUID id, @Valid @RequestBody FaqInput in) {
        return FaqView.of(content.updateFaq(id, in.category(), in.question(), in.answer(), in.published()));
    }

    @DeleteMapping("/api/admin/faqs/{id}")
    public ResponseEntity<Void> deleteFaq(@PathVariable UUID id) {
        content.deleteFaq(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/admin/faqs/order")
    public List<FaqView> reorderFaqs(@Valid @RequestBody Reorder in) {
        return content.reorderFaqs(in.ids()).stream().map(FaqView::of).toList();
    }

    // ── 모양 ────────────────────────────────────────────────────

    public record NoticeInput(@NotBlank @Size(max = 100) String title, @NotBlank @Size(max = 5000) String body,
                              boolean pinned, boolean published) {
    }

    public record FaqInput(@NotNull Faq.Category category, @NotBlank @Size(max = 200) String question,
                           @NotBlank @Size(max = 3000) String answer, boolean published) {
    }

    public record Reorder(@NotNull @Size(max = 500) List<UUID> ids) {
    }

    public record NoticeRow(UUID id, String title, boolean pinned, Instant publishedAt) {
        static NoticeRow of(Notice n) {
            return new NoticeRow(n.getId(), n.getTitle(), n.isPinned(), n.getPublishedAt());
        }
    }

    public record NoticePage(List<NoticeRow> items, int page, int totalPages, long totalElements) {
    }

    public record NoticeView(UUID id, String title, String body, boolean pinned, boolean published,
                             Instant publishedAt, Instant createdAt, Instant updatedAt) {
        static NoticeView of(Notice n) {
            return new NoticeView(n.getId(), n.getTitle(), n.getBody(), n.isPinned(), n.isPublished(),
                    n.getPublishedAt(), n.getCreatedAt(), n.getUpdatedAt());
        }
    }

    public record FaqView(UUID id, Faq.Category category, String question, String answer, boolean published,
                          int sortOrder) {
        static FaqView of(Faq f) {
            return new FaqView(f.getId(), f.getCategory(), f.getQuestion(), f.getAnswer(), f.isPublished(),
                    f.getSortOrder());
        }
    }
}
