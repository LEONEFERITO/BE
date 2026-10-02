package com.leoneferito.home;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.product.FrontRebuildTrigger;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WhyService {
    private static final Logger log = LoggerFactory.getLogger(WhyService.class);
    public static final int MIN_ITEMS = 2;
    public static final int MAX_ITEMS = 5;
    private final WhySectionRepository sections;
    private final MediaAssetRepository mediaAssets;
    private final ApplicationEventPublisher events;

    public WhyService(WhySectionRepository sections, MediaAssetRepository mediaAssets, ApplicationEventPublisher events){
        this.sections = sections;
        this.mediaAssets = mediaAssets;
        this.events = events;
    }
    public record ItemInput(String title, String body, UUID mediaId){}
    @Transactional(readOnly = true)
    public WhySection get(){
        WhySection s = sections.findById(WhySection.SINGLETON_ID).orElseThrow(() -> new ResourceNotFoundException("WHY 구간 없음( V18 이 돌지 않음)"));
        s.getItems().forEach(i -> {if(i.getMedia() != null){i.getMedia().getStorageKey();}});
        return s;
    }
    @Transactional
    public WhySection save(String eyebrow, String title, String intro, List<ItemInput> items){
        WhySection s = get();
        s.update(eyebrow, title, intro);
        s.replaceItems(items.stream().map(in -> new WhyItem(in.title(), in.body(), media(in.mediaId()))).toList());
        sections.flush();
        log.info("WHY 구간 저장 items={}", items.size());
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("WHY구간"));
        return get();
    }
    private MediaAsset media(UUID id){
        if(id == null){
            return null;
        }
        return mediaAssets.findById(id).orElseThrow(() -> new ResourceNotFoundException("이미지 없음: mediaId=" + id));
    }
}
