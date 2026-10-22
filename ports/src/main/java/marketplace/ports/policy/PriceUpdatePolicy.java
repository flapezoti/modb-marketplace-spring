package marketplace.ports.policy;

import java.util.Optional;

/**
 * Decides whether an incoming price update should be applied, given the current version of the
 * record it targets. No VMODB, Kafka, Redis, or Spring types in this contract -- any adapter can
 * satisfy it the same way. This module has zero dependencies, by design: any module on either
 * backend can depend on it without pulling in the other backend's technology.
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
