package com.llexsimulator.fill;

import com.llexsimulator.config.FillBehaviorProfile;
import com.llexsimulator.web.dto.FillProfileDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages named {@link FillBehaviorProfile} instances.
 *
 * <p>The currently active {@link FillBehaviorConfig} is exposed as a volatile
 * reference so {@code FillStrategyHandler} can read it with a single volatile
 * load on the hot path. Profile mutations happen on the Vert.x event-loop
 * thread (REST API calls) and are therefore off the critical path.
 */
public final class FillProfileManager {

    private static final Logger log = LoggerFactory.getLogger(FillProfileManager.class);

    private final ConcurrentHashMap<String, FillBehaviorProfile> profiles = new ConcurrentHashMap<>();
    /** Volatile reference: a single read gives a consistent snapshot. */
    private volatile FillBehaviorConfig activeConfig = new FillBehaviorConfig();
    private volatile String activeProfileName = "default";

    public FillProfileManager() {
        // Seed with built-in profiles
        seedBuiltinProfiles();
    }

    private void seedBuiltinProfiles() {
        createOrUpdate(new FillProfileDto("immediate-full-fill", "Immediate 100% fill",
                "IMMEDIATE_FULL_FILL", 10_000, 1, 0, null, 0, 100, 0, 0, 0));
        createOrUpdate(new FillProfileDto("partial-50pct", "50% partial fill",
                "PARTIAL_FILL", 5_000, 1, 0, null, 0, 100, 0, 0, 0));
        createOrUpdate(new FillProfileDto("delayed-1ms", "Full fill after 1 ms delay",
                "DELAYED_FILL", 10_000, 1, 1, null, 0, 100, 0, 0, 0));
        createOrUpdate(new FillProfileDto("reject-all", "Reject all orders",
                "REJECT", 0, 0, 0, "SIMULATOR_REJECT", 0, 100, 0, 0, 0));
        createOrUpdate(new FillProfileDto("random-fill", "Random qty + random delay",
                "RANDOM_FILL", 0, 1, 0, null, 50, 100, 0, 5, 0));
        createOrUpdate(new FillProfileDto("price-improvement", "Fill with 1bp price improvement",
                "PRICE_IMPROVEMENT", 10_000, 1, 0, null, 0, 100, 0, 0, 1));

        // Activate the immediate-full-fill profile by default
        activate("immediate-full-fill");
    }

    public void createOrUpdate(FillProfileDto dto) {
        FillBehaviorConfig cfg = FillBehaviorConfig.fromDto(dto);
        FillBehaviorProfile profile = new FillBehaviorProfile(dto.name, dto.description, cfg);
        profiles.put(dto.name, profile);
        log.info("Saved fill profile: {}", dto.name);
    }

    public void activate(String name) {
        FillBehaviorProfile profile = profiles.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown fill profile: " + name);
        }
        activeConfig = profile.config();
        activeProfileName = name;
        log.info("Activated fill profile: {}", name);
    }

    /** Called with a single volatile read from the Disruptor hot path. */
    public FillBehaviorConfig getActiveConfig() {
        return activeConfig;
    }

    public String getActiveProfileName() {
        return activeProfileName;
    }

    public List<FillBehaviorProfile> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public boolean delete(String name) {
        return profiles.remove(name) != null;
    }
}

