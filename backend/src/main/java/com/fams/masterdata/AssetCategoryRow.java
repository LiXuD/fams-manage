package com.fams.masterdata;

import java.time.Instant;

record AssetCategoryRow(long id, String code, String name, Long parentId,
        Long parentVersion, String parentStatus, String description,
        String suggestedUnit, String numberingPrefix, String extensionTemplateJson,
        String status, long version) {
}

final class NewAssetCategoryRow {
    private Long id;
    private final String code;
    private final String normalizedCode;
    private final String name;
    private final Long parentId;
    private final String description;
    private final String suggestedUnit;
    private final String numberingPrefix;
    private final String extensionTemplateJson;
    private final long actorId;
    private final Instant now;

    NewAssetCategoryRow(String code, String normalizedCode, String name, Long parentId,
            String description, String suggestedUnit, String numberingPrefix,
            String extensionTemplateJson, long actorId, Instant now) {
        this.code = code;
        this.normalizedCode = normalizedCode;
        this.name = name;
        this.parentId = parentId;
        this.description = description;
        this.suggestedUnit = suggestedUnit;
        this.numberingPrefix = numberingPrefix;
        this.extensionTemplateJson = extensionTemplateJson;
        this.actorId = actorId;
        this.now = now;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public String getNormalizedCode() { return normalizedCode; }
    public String getName() { return name; }
    public Long getParentId() { return parentId; }
    public String getDescription() { return description; }
    public String getSuggestedUnit() { return suggestedUnit; }
    public String getNumberingPrefix() { return numberingPrefix; }
    public String getExtensionTemplateJson() { return extensionTemplateJson; }
    public long getActorId() { return actorId; }
    public Instant getNow() { return now; }
}
