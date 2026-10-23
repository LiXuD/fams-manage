package com.fams.repair;

import com.fams.asset.AssetHandoverPort;
import com.fams.auth.RequisitionActorContext.Actor;
import com.fams.loan.LoanRepairPort;
import com.fams.proxy.ProxyReceiptFailure;
import com.fams.proxy.ProxyReceiptPort;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

@Component
@Profile("fams-product")
public class RepairProxyReceiptPort implements ProxyReceiptPort {
    private final RepairService repairs;

    public RepairProxyReceiptPort(RepairService repairs) {
        this.repairs = repairs;
    }

    @Override
    public String businessType() {
        return "REPAIR";
    }

    @Override
    public void lockHeader(long request) {
        translated(
                () -> {
                    repairs.lockTargetChange(request);
                    return null;
                });
    }

    @Override
    public void requireProcessor(long account, long request) {
        translated(
                () -> {
                    repairs.requireTargetChangeProcessor(account, request);
                    return null;
                });
    }

    @Override
    public boolean currentProcessor(long account, long request, boolean lock) {
        return translated(() -> repairs.proxyProcessor(account, request, lock));
    }

    @Override
    public Map<String, Object> snapshot(Actor actor, long request, long item, boolean executing) {
        return translated(() -> repairs.proxySnapshot(actor, request, item, executing));
    }

    @Override
    public Map<String, Object> execute(
            Actor actor,
            long request,
            long item,
            Map<String, Object> expected,
            String observedCode,
            long confirmation) {
        throw new ProxyReceiptFailure(400, "REPAIR_PROXY_EVIDENCE_REQUIRED");
    }

    @Override
    public Map<String, Object> execute(
            Actor actor,
            long request,
            long item,
            Map<String, Object> expected,
            String observedCode,
            long confirmation,
            ExecutionEvidence evidence) {
        return translated(
                () ->
                        repairs.proxyReceipt(
                                actor,
                                request,
                                item,
                                expected,
                                observedCode,
                                confirmation,
                                evidence));
    }

    private <T> T translated(Supplier<T> action) {
        try {
            return action.get();
        } catch (RepairFailure error) {
            throw new ProxyReceiptFailure(error.status(), error.getMessage());
        } catch (AssetHandoverPort.Failure error) {
            throw new ProxyReceiptFailure(error.status(), error.getMessage());
        } catch (LoanRepairPort.Failure error) {
            throw new ProxyReceiptFailure(409, error.getMessage());
        }
    }
}
