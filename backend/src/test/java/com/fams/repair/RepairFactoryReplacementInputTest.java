package com.fams.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fams.product.contract.model.AssetCreateRequest;
import com.fams.product.contract.model.AttachmentReference;
import com.fams.product.contract.model.CatalogReference;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

class RepairFactoryReplacementInputTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void frozenIntentSurvivesCallerAndAccessorChangesWithoutLosingDatesOrDecimalPrecision() {
        var asset = new AssetCreateRequest("Original new-card name");
        asset.setSerialNumber("FROZEN-NEW-SERIAL");
        asset.setAcquisitionDate(LocalDate.of(2026, 10, 27));
        asset.setWarrantyExpiryDate(LocalDate.of(2027, 10, 27));
        var category = new CatalogReference();
        category.setId("9007199254740993");
        category.setVersion(0L);
        asset.setCategory(category);
        var attachment = new AttachmentReference();
        attachment.setId(UUID.randomUUID().toString());
        attachment.setVersion(0L);
        asset.getAttachments().add(attachment);
        var nested = new ArrayList<>(List.of("Original extension value"));
        var extensions = new LinkedHashMap<String, Object>();
        extensions.put("nested", nested);
        extensions.put("amount", new BigDecimal("12345678901234567890.123400"));
        asset.setExtensionValues(extensions);
        String original = json.writeValueAsString(asset);
        var input = input(asset);
        String frozen = json.writeValueAsString(input);
        assertEquals(original, json.writeValueAsString(input.newAsset()));

        asset.setSerialNumber("Caller replacement");
        category.setId("2");
        nested.add("Caller addition");
        extensions.put("amount", BigDecimal.ZERO);
        assertEquals(original, json.writeValueAsString(input.newAsset()));

        var returned = input.newAsset();
        returned.setName("Accessor replacement");
        returned.getCategory().setId("3");
        returned.getAttachments().getFirst().setId(UUID.randomUUID().toString());
        ((List<?>) returned.getExtensionValues().get("nested")).clear();
        returned.getAttachments().clear();
        assertEquals(original, json.writeValueAsString(input.newAsset()));
        assertEquals(frozen, json.writeValueAsString(input));
    }

    @Test
    void absentNewAssetRemainsAValidationErrorInsteadOfFailingSnapshotConstruction() {
        var input = input(null);
        assertNull(input.newAsset());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(input).stream()
                    .anyMatch(item -> "newAsset".equals(item.getPropertyPath().toString())));
        }
    }

    private RepairFactoryInput.ReplacementFact input(AssetCreateRequest asset) {
        return new RepairFactoryInput.ReplacementFact(0L, 0L, 0L, 0L, "FACTORY_RETAINED",
                Instant.parse("2026-10-27T00:00:00Z"), List.of(UUID.randomUUID()),
                "Original observed replacement basis", asset, LocalDate.of(2026, 10, 27),
                1L, 0L, 1L, 0L, true);
    }
}
