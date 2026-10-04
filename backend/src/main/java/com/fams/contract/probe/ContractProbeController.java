package com.fams.contract.probe;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.fams.contract.api.ContractProbeApi;
import com.fams.contract.model.ContractProbe;

@RestController
public class ContractProbeController implements ContractProbeApi {

    @Override
    public ResponseEntity<ContractProbe> echoContractProbe(ContractProbe contractProbe) {
        OffsetDateTime utcOccurredAt = contractProbe.getOccurredAt().withOffsetSameInstant(ZoneOffset.UTC);
        contractProbe.setOccurredAt(utcOccurredAt);
        return ResponseEntity.ok(contractProbe);
    }
}
