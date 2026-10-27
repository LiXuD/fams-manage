package com.fams.asset;

import com.fams.auth.AssetActorContext;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@Profile("fams-product")
@Validated
public class AssetFactoryReplacementController {
    private final AssetFactoryReplacementPort port;
    private final AssetActorContext actors;
    public AssetFactoryReplacementController(AssetFactoryReplacementPort port, AssetActorContext actors) {
        this.port = port;
        this.actors = actors;
    }
    @GetMapping("/api/v1/assets/{id}/factory-replacements")
    @Transactional
    Map<String, Object> trace(@PathVariable @Positive long id) {
        return port.trace(actors.requireRead(), id);
    }
}
