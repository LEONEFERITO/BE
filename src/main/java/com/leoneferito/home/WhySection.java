package com.leoneferito.home;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "why_section")
public class WhySection {
    public static final short SINGLETON_ID = 1;
    @Id
    private Short id;
    @Column(nullable = false)
    private String eyebrow;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String intro;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, createdAt ASC")
    private List<WhyItem> items = new ArrayList<>();
    protected  WhySection(){}
    public void update(String eyebrow, String title, String intro){
        this.eyebrow = Objects.requireNonNull(eyebrow).trim();
        this.title = Objects.requireNonNull(title).trim();
        this.intro = Objects.requireNonNull(intro).trim();
    }
    public void replaceItems(List<WhyItem> next){
        items.clear();
        for(int i = 0; i < next.size(); i++){
            WhyItem item = next.get(i);
            item.attachTo(this, (i + 1) * 10);
            items.add(item);
        }
    }
    public Short getId() {return id;}
    public String getEyebrow() {return eyebrow;}
    public String getTitle() {return title;}
    public String getIntro() {return intro;}
    public Instant getUpdatedAt() {return updatedAt;}
    public List<WhyItem> getItems() {return items;}
}