package com.fams.asset;

/** Consumers expose durable downstream use without leaking their private repositories. */
public interface AssetDownstreamUsePort {
    /**
     * Reports whether the consumer domain still holds a published use of the asset. Asset import
     * rollback polls every implementation and aborts the whole batch when any returns
     * {@code true}, so the answer must already reflect committed state rather than intent.
     * Implementations query their own storage; the asset domain never reads their tables.
     *
     * @param assetId fams_asset primary key whose downstream use is probed
     * @return {@code true} when a published downstream use still references the asset
     */
    boolean hasPublishedUse(long assetId);
}
