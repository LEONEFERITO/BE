package com.leoneferito.content;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.common.error.StaleListException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지사항 · FAQ — 관리자 쓰기, 손님 읽기.
 *
 * <p>손님 화면은 브라우저에서 바로 읽는다(빌드 때 굽지 않는다). 공지는 올리는 즉시 보여야 하고,
 * 정적 재빌드(1~2분)를 기다릴 이유가 없다.
 */
@Service
public class ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);
    static final int NOTICE_PAGE_SIZE = 20;

    private final NoticeRepository notices;
    private final FaqRepository faqs;

    public ContentService(NoticeRepository notices, FaqRepository faqs) {
        this.notices = notices;
        this.faqs = faqs;
    }

    // ── 공지 ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Notice> publicNotices(int page) {
        return notices.findPublic(PageRequest.of(Math.max(page, 0), NOTICE_PAGE_SIZE));
    }

    /** 공개된 공지만. 내린 공지는 "없다" 와 같다. */
    @Transactional(readOnly = true)
    public Notice publicNotice(UUID id) {
        return notices.findById(id).filter(Notice::isPublished)
                .orElseThrow(() -> new ResourceNotFoundException("공지 없음 id=" + id));
    }

    @Transactional(readOnly = true)
    public List<Notice> adminNotices() {
        return notices.findAllForAdmin();
    }

    @Transactional(readOnly = true)
    public Notice adminNotice(UUID id) {
        return notices.findById(id).orElseThrow(() -> new ResourceNotFoundException("공지 없음 id=" + id));
    }

    @Transactional
    public Notice createNotice(String title, String body, boolean pinned, boolean published) {
        Notice n = notices.save(new Notice(title, body, pinned, published));
        log.info("공지 작성 id={} published={}", n.getId(), published);
        return n;
    }

    @Transactional
    public Notice updateNotice(UUID id, String title, String body, boolean pinned, boolean published) {
        Notice n = adminNotice(id);
        n.update(title, body, pinned, published);
        return n;
    }

    @Transactional
    public void deleteNotice(UUID id) {
        notices.delete(adminNotice(id));
        log.info("공지 삭제 id={}", id);
    }

    // ── FAQ ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Faq> publicFaqs() {
        return faqs.findPublic();
    }

    @Transactional(readOnly = true)
    public List<Faq> adminFaqs() {
        return faqs.findAllOrdered();
    }

    /** 새 질문은 맨 아래에 붙는다. */
    @Transactional
    public Faq createFaq(Faq.Category category, String question, String answer, boolean published) {
        return faqs.save(new Faq(category, question, answer, published, faqs.maxSortOrder() + 10));
    }

    @Transactional
    public Faq updateFaq(UUID id, Faq.Category category, String question, String answer, boolean published) {
        Faq f = faqs.findById(id).orElseThrow(() -> new ResourceNotFoundException("FAQ 없음 id=" + id));
        f.update(category, question, answer, published);
        return f;
    }

    @Transactional
    public void deleteFaq(UUID id) {
        faqs.delete(faqs.findById(id).orElseThrow(() -> new ResourceNotFoundException("FAQ 없음 id=" + id)));
    }

    /**
     * 순서 바꾸기. 화면이 보낸 id 목록이 <b>지금 있는 FAQ 전부와 정확히 같아야</b> 한다 — 다른 관리자가
     * 그 사이 추가·삭제했는데 옛 목록으로 덮으면 순서가 뒤섞인다. 다르면 다시 불러오게 한다.
     */
    @Transactional
    public List<Faq> reorderFaqs(List<UUID> ids) {
        List<Faq> all = faqs.findAllOrdered();
        if (ids.size() != all.size() || !new HashSet<>(ids).equals(
                all.stream().map(Faq::getId).collect(Collectors.toSet()))) {
            throw new StaleListException();
        }
        Map<UUID, Faq> byId = all.stream().collect(Collectors.toMap(Faq::getId, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).moveTo((i + 1) * 10);
        }
        return faqs.findAllOrdered();
    }
}
