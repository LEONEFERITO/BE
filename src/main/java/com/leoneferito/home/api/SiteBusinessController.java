package com.leoneferito.home.api;

import com.leoneferito.home.SiteBusiness;
import com.leoneferito.home.SiteBusinessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    // ── 가이드 페이지 제작 소요 기간 표 (V42) — 손님 화면은 빌드할 때 받는다 ──

    @GetMapping("/api/site-guide")
    public Guide publicGuide() {
        return new Guide(business.leadTimes());
    }

    @GetMapping("/api/admin/site-guide")
    public Guide adminGuide() {
        return new Guide(business.leadTimes());
    }

    @PutMapping("/api/admin/site-guide")
    public Guide saveGuide(@Valid @RequestBody GuideInput in) {
        return new Guide(business.saveLeadTimes(in.leadTimes().stream().map(LeadTimeInput::row).toList()));
    }

    public record Guide(List<SiteBusiness.LeadTime> leadTimes) {
    }

    /** 표는 1~12줄. 품목 · 기간에 탭 · 줄바꿈은 못 넣는다(저장할 때 칸 구분자로 쓴다) */
    public record GuideInput(@NotNull @Size(min = 1, max = 12) List<@Valid @NotNull LeadTimeInput> leadTimes) {
    }

    public record LeadTimeInput(
            @NotBlank @Size(max = 60) @Pattern(regexp = "^[^\\t\\r\\n]*$", message = "품목에 줄바꿈을 넣을 수 없습니다.") String items,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[^\\t\\r\\n]*$", message = "기간에 줄바꿈을 넣을 수 없습니다.") String time) {

        SiteBusiness.LeadTime row() {
            return new SiteBusiness.LeadTime(items, time);
        }
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