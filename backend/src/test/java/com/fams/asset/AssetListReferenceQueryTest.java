package com.fams.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fams.auth.AssetActorContext.Actor;
import com.fams.masterdata.AssetMasterDataPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AssetListReferenceQueryTest {
    @Test
    void fiftyAssetPageLoadsEachReferenceKindOnce() {
        AssetMapper mapper = mock(AssetMapper.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("asset_code", "A-" + id);
            row.put("name", "Asset " + id);
            row.put("category_id", 1L);
            row.put("owning_organization_id", 1L);
            row.put("lifecycle_status", "IN_STOCK");
            row.put("exception_status", "NORMAL");
            row.put("card_stage", "ACTIVE");
            row.put("custody_type", "STORAGE");
            row.put("storage_warehouse_id", 1L);
            row.put("current_location_id", 1L);
            row.put("version", 0L);
            row.put("parameter_version_id", 1L);
            row.put("creator_account_id", 1L);
            row.put("creator_username_snapshot", "worker");
            row.put("created_business_date", Date.valueOf(LocalDate.of(2026, 9, 29)));
            row.put("created_at", Timestamp.from(Instant.parse("2026-09-29T00:00:00Z")));
            rows.add(row);
        }
        when(mapper.list(anyLong(), anyMap(), anyInt(), anyInt())).thenReturn(rows);
        when(mapper.count(anyLong(), anyMap())).thenReturn(50L);
        when(mapper.referenceViews(anyString(), anyList()))
                .thenReturn(List.of(Map.of("id", 1L, "version", 0L)));
        var service = new AssetService(mapper, mock(AssetMasterDataPort.class),
                mock(AssetSecretCodec.class), new ObjectMapper(), Clock.systemUTC(), "FAMS");
        var result = service.list(new Actor(1, "worker", true, false, "WAREHOUSE_OPERATOR", "test"),
                1, 50, Map.of());
        assertEquals(50, result.getItems().size());
        verify(mapper).list(anyLong(), anyMap(), anyInt(), anyInt());
        verify(mapper).count(anyLong(), anyMap());
        verify(mapper, times(4)).referenceViews(anyString(), anyList());
        verifyNoMoreInteractions(mapper);
    }
}
