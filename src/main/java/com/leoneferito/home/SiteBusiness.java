package com.leoneferito.home;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "site_business")
public class SiteBusiness {
    public static final short SINGLETON_ID = 1;
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
    public Instant getUodatedAt(){return updatedAt;}
}
