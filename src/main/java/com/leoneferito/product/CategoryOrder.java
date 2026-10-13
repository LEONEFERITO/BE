package com.leoneferito.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "category_order")
public class CategoryOrder {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ProductCategory category;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected CategoryOrder() {}
    void moveTo(int sortOrder) {this.sortOrder = sortOrder;}
    public ProductCategory getCategory() {return category;}
    public int getSortOrder() {return sortOrder;}
    public Instant getUpdatedAt() {return updatedAt;}
}