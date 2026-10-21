package dk.ku.di.dms.vms.marketplace.common.policy;

import java.util.Optional;

/**
 * The actual business rule behind both Product's and Cart's price-update handling: a price
 * update is only applied if its version matches the record's current version. Looking the
 * record up, persisting the result, and emitting an event are all adapter-specific and live
 * outside this class.
 */
public final class VersionMatchPriceUpdatePolicy implements PriceUpdatePolicy {

    @Override
    public Optional<Float> evaluate(String currentVersion, String incomingVersion, float incomingPrice) {
        if (!currentVersion.equals(incomingVersion)) {
            return Optional.empty();
        }
        return Optional.of(incomingPrice);
    }

}
