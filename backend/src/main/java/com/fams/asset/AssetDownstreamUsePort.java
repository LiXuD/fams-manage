package com.fams.asset;

/** Consumers expose durable downstream use without leaking their private repositories. */
public interface AssetDownstreamUsePort {
    boolean hasPublishedUse(long assetId);
}
