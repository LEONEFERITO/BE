package com.leoneferito.home.api;

import com.leoneferito.home.WhyItem;
import com.leoneferito.home.WhySection;
import com.leoneferito.home.WhyService;
import com.leoneferito.media.MediaUrls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhyController {
    private final WhyService why;
    private final MediaUrls urls;

    public WhyController(WhyService why, MediaUrls urls){
        this.why = why;
        this.urls = urls;
    }
    @GetMapping("/api/why")
    public View publicView(){
        return View.of(why.get(), urls);
    }
    @GetMapping("/api/admin/why")
    public View adminView(){
        return View.of(why.get(), urls);
    }
    @PutMapping("/api/admin/why")
    public View save(@Valid @RequestBody Input in) {
        return View.of(why.save(in.eyebrow(), in.title(), in.intro(), in.items().stream().map(i -> new WhyService.ItemInput(i.title(), i.body(), i.mediaId())).toList()), urls);
    }
    public record Input(@NotBlank @Size(max = 40)String eyebrow, @NotBlank @Size(max = 60)String title, @NotBlank @Size(max = 400)String intro, @NotNull @Size(min = WhyService.MIN_ITEMS, max = WhyService.MAX_ITEMS) List<@Valid ItemIn> items){}
    public record ItemIn(@NotBlank @Size(max = 40)String title, @NotBlank @Size(max = 300)String body,UUID mediaId){}
    public record Item(UUID id, String title, String body, UUID mediaId, String imageUrl) {
        static Item of(WhyItem i, MediaUrls urls) {
            return new Item(i.getId(), i.getTitle(), i.getBody(), i.getMedia() == null ? null : i.getMedia().getId(), i.getMedia() == null ? null : urls.urlFor(i.getMedia()));
        }
    }
    public record View(String eyebrow, String title, String intro, List<Item> items) {
        static View of(WhySection s, MediaUrls urls) {
            return new View(s.getEyebrow(), s.getTitle(), s.getIntro(),s.getItems().stream().map(i -> Item.of(i, urls)).toList());
        }
    }
}
