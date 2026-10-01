package com.leoneferito.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 자주 묻는 질문 한 줄. 답은 글자 그대로(HTML 없음). 순서는 sortOrder. */
@Entity
@Table(name = "faq")
public class Faq {

    /** QnA 화면의 칩과 같다: 주문·제작 / 사이즈 / 배송·교환. */
    public enum Category {
        ORDER,
        SIZE,
        SHIPPING
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String question;

    @Column(nullable = false)
    private String answer;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Faq() {
        // JPA
    }

    public Faq(Category category, String question, String answer, boolean published, int sortOrder) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.sortOrder = sortOrder;
        update(category, question, answer, published);
    }

    public void update(Category category, String question, String answer, boolean published) {
        this.category = Objects.requireNonNull(category);
        this.question = Objects.requireNonNull(question).trim();
        this.answer = Objects.requireNonNull(answer).trim();
        this.published = published;
    }

    void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
