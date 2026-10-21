package dk.ku.di.dms.vms.marketplace.common.policy;

import java.util.Optional;

/**
 * Decides whether an incoming price update should be applied, given the current version of the
 * record it targets. No VMODB (or any other infrastructure) types appear in this contract: any
 * adapter -- VMODB, Kafka, a plain HTTP handler, a test -- can call it the same way, which is the
 * point: the actual business rule behind a price update lives here, not inside any one
 * framework's annotated methods.
 */
public interface PriceUpdatePolicy {

    /**
     * @param currentVersion the version currently stored for the targeted record
     * @param incomingVersion the version carried by the incoming price update
     * @param incomingPrice the price carried by the incoming price update
     * @return the price to persist, if the update should be applied; empty if it is stale or
     *         conflicting and should be rejected
     */
    Optional<Float> evaluate(String currentVersion, String incomingVersion, float incomingPrice);

}
