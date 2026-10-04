package com.fams.masterdata;

import com.fams.product.contract.model.NumberingTarget;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Keeps inbound number allocation inside the master-data owner. */
@Profile("fams-product")
@Component
public final class InboundNumberPort {
    private final NumberAllocationService numbers;

    InboundNumberPort(NumberAllocationService numbers) { this.numbers = numbers; }

    public Reservation reserve() {
        NumberReservation value = numbers.reserve(NumberingTarget.INBOUND_RECEIPT, null, 1).getFirst();
        return new Reservation(value.id(), value.number());
    }

    public void markUsed(long id) { numbers.markUsed(id); }

    public record Reservation(long id, String number) { }
}
