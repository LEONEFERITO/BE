package com.leoneferito.home.api;

import com.leoneferito.home.SiteBusiness;
import com.leoneferito.home.SiteBusinessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SiteBusinessController {

    private final SiteBusinessService business;

    public SiteBusinessController(SiteBusinessService business) {
        this.business = business;
    }

    @GetMapping("/api/site-business")
    public SiteBusiness.Details publicView() {
        return business.get().details();
    }

    @GetMapping("/api/admin/site-business")
    public SiteBusiness.Details adminView() {
        return business.get().details();
    }

    @PutMapping("/api/admin/site-business")
    public SiteBusiness.Details save(@Valid @RequestBody Input in) {
        return business.save(in.details()).details();
    }

    public record Input(
            @NotBlank @Size(max = 100) String companyName,
            @NotBlank @Size(max = 50) String representative,
            @NotBlank @Pattern(regexp = "^[0-9]{3}-[0-9]{2}-[0-9]{5}$", message = "사업자등록번호는 000-00-00000 형식입니다.")
            String registrationNumber,
            @NotBlank @Size(max = 50) String mailOrderNumber,
            @NotBlank @Size(max = 200) String address,
            @NotBlank @Size(max = 100) String phone,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String privacyOfficer,
            @NotBlank @Size(max = 100) String hosting) {

        SiteBusiness.Details details() {
            return new SiteBusiness.Details(companyName, representative, registrationNumber, mailOrderNumber, address, phone,
                    email, privacyOfficer, hosting);
        }
    }
}