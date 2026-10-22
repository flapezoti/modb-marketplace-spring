package marketplace.ports.policy;

import java.util.Optional;

/**
 * The actual business rule behind price-update handling: a price update is only applied if its
 * version matches the record's current version. Looking the record up, persisting the result,
 * and publishing an event are all adapter-specific and live outside this class. One instance of
 * this class is shared by both the VMODB and the Redis/Kafka adapters -- it has no reason to
 * differ between them.
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
