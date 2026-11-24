package com.fams.asset;

import com.fams.auth.AssetActorContext;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTTP surface for factory-replacement provenance. It performs no authorization or
 * query logic of its own: the actor is resolved through {@link AssetActorContext} and every
 * retrieval, including the visibility rule for draft and cancelled replacements, is delegated to
 * the asset-owned port.
 */
@RestController
@Profile("fams-product")
@Validated
public class AssetFactoryReplacementController {
    private final AssetFactoryReplacementPort port;
    private final AssetActorContext actors;
    /**
     * Wires the controller to the provenance port and the actor context. Spring performs the
     * injection; the controller stays stateless and holds no request-scoped data.
     *
     * @param port asset-owned reader that assembles the trace and applies card-stage visibility
     * @param actors resolver producing the authenticated read actor for the current request
     */
    public AssetFactoryReplacementController(AssetFactoryReplacementPort port, AssetActorContext actors) {
        this.port = port;
        this.actors = actors;
    }
    @GetMapping("/api/v1/assets/{id}/factory-replacements")
    AssetFactoryReplacementPort.Trace trace(@PathVariable @Positive long id) {
        return port.trace(actors.requireRead(), id);
    }
}
