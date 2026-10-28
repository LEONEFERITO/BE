package com.leoneferito.home;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.product.FrontRebuildTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SiteBusinessService {

    private static final Logger log = LoggerFactory.getLogger(SiteBusinessService.class);
    private final SiteBusinessRepository business;
    private final ApplicationEventPublisher events;
    public SiteBusinessService(SiteBusinessRepository business, ApplicationEventPublisher events) {
        this.business = business;
        this.events = events;
    }
    @Transactional(readOnly = true)
    public SiteBusiness get() {return business.findById(SiteBusiness.SINGLETON_ID).orElseThrow(() -> new ResourceNotFoundException("사업자 정보 없음(V31 이 돌지 않음)"));}
    @Transactional
    public SiteBusiness save(SiteBusiness.Details details) {
        SiteBusiness b = get();
        b.change(details);
        business.flush();
        log.info("푸터 사업자 정보 저장");
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("사업자정보"));
        return b;
    }
    /** 가이드 페이지 제작 소요 기간 표(V42) */
    @Transactional(readOnly = true)
    public List<SiteBusiness.LeadTime> leadTimes() {return get().leadTimes();}
    @Transactional
    public List<SiteBusiness.LeadTime> saveLeadTimes(List<SiteBusiness.LeadTime> rows) {
        SiteBusiness b = get();
        b.changeLeadTimes(rows);
        business.flush();
        log.info("가이드 제작 기간 저장 ({}줄)", rows.size());
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("가이드"));
        return b.leadTimes();
    }
}