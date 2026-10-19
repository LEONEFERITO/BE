package com.leoneferito.auth;

import com.leoneferito.member.Member;
import com.leoneferito.member.MemberProvider;
import com.leoneferito.member.MemberRepository;
import com.leoneferito.member.MemberSocialLink;
import com.leoneferito.member.MemberSocialLinkRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FindEmailService {

    private static final Logger log = LoggerFactory.getLogger(FindEmailService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_RESULTS = 5;
    private final MemberRepository members;
    private final MemberSocialLinkRepository links;
    public FindEmailService(MemberRepository members, MemberSocialLinkRepository links) {
        this.members = members;
        this.links = links;
    }
    public record Account(String maskedEmail, List<MemberProvider> providers, LocalDate joinedOn) { }
    @Transactional(readOnly = true)
    public List<Account> find(String name, String phone) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (n.isEmpty() || digits.length() < 10 || digits.length() > 11) {return List.of();}
        List<Member> found = members.findForEmailLookup(n, digits, PageRequest.of(0, MAX_RESULTS));
        log.info("아이디 찾기 matched={}", found.size());
        return found.stream().map(m -> new Account(mask(m.getEmail()), providersOf(m), m.getCreatedAt().atZone(SEOUL).toLocalDate())).toList();}

    private List<MemberProvider> providersOf(Member m) {List<MemberProvider> result = new ArrayList<>();
        result.add(m.getProvider());
        for (MemberSocialLink link : links.findByMemberIdOrderByLinkedAtAsc(m.getId())) {if (!result.contains(link.getProvider())) {result.add(link.getProvider());}}return result;}
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {return "***";}
        String local = email.substring(0, at);
        int keep = local.length() <= 2 ? 1 : 2;
        return local.substring(0, keep) + "*".repeat(local.length() - keep) + email.substring(at);
    }
}