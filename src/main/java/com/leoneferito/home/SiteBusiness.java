package com.leoneferito.home;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Entity
@Table(name = "site_business")
public class SiteBusiness {
    public static final short SINGLETON_ID = 1;
    /** 가이드 페이지 제작 소요 기간 한 줄(V42) — 품목 · 기간 */
    public record LeadTime(String items, String time){ }
    public record Details(String companyName, String representative, String registrationNumber, String mailOrderNumber, String address, String phone, String email, String privacyOfficer, String hosting){ }
    @Id
    private Short id;
    @Column(name = "company_name", nullable = false)
    private String companyName;
    @Column(nullable = false)
    private String representative;
    @Column(name = "registration_number", nullable = false)
    private String registrationNumber;
    @Column(name = "mail_order_number", nullable = false)
    private String mailOrderNumber;
    @Column(nullable = false)
    private String address;
    @Column(nullable = false)
    private String phone;
    @Column(nullable = false)
    private String email;
    @Column(name = "privacy_officer", nullable = false)
    private String privacyOfficer;
    @Column(nullable = false)
    private String hosting;
    /** 한 줄에 "품목<탭>기간", 줄바꿈으로 나눈다(V42) */
    @Column(name = "guide_lead_times", nullable = false)
    private String guideLeadTimes;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
    protected SiteBusiness(){}
    public void change(Details d){
        this.companyName = Objects.requireNonNull(d.companyName()).trim();
        this.representative = Objects.requireNonNull(d.representative()).trim();
        this.registrationNumber = Objects.requireNonNull(d.registrationNumber()).trim();
        this.mailOrderNumber = Objects.requireNonNull(d.mailOrderNumber()).trim();
        this.address = Objects.requireNonNull(d.address()).trim();
        this.phone = Objects.requireNonNull(d.phone()).trim();
        this.email = Objects.requireNonNull(d.email()).trim();
        this.privacyOfficer = Objects.requireNonNull(d.privacyOfficer()).trim();
        this.hosting = Objects.requireNonNull(d.hosting()).trim();
    }
    public Details details(){return new Details(companyName, representative, registrationNumber, mailOrderNumber, address, phone, email, privacyOfficer, hosting);}
    public List<LeadTime> leadTimes(){
        return Arrays.stream(guideLeadTimes.split("\n"))
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\t", 2))
                .map(cols -> new LeadTime(cols[0].trim(), cols.length > 1 ? cols[1].trim() : ""))
                .toList();
    }
    public void changeLeadTimes(List<LeadTime> rows){
        this.guideLeadTimes = rows.stream().map(r -> r.items().trim() + "\t" + r.time().trim()).collect(Collectors.joining("\n"));
    }
    public Instant getUodatedAt(){return updatedAt;}
}
