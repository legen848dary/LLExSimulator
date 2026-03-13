package com.llexsimulator.fill;

import com.llexsimulator.sbe.FillBehaviorType;
import com.llexsimulator.sbe.RejectReason;
import com.llexsimulator.web.dto.FillProfileDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FillProfileManagerTest {

    @Test
    void seedsBuiltinProfilesAndTracksDefaultActiveName() {
        FillProfileManager manager = new FillProfileManager();

        assertEquals("immediate-full-fill", manager.getActiveProfileName());
        assertTrue(manager.getAllProfiles().size() >= 6);
        assertEquals(FillBehaviorType.IMMEDIATE_FULL_FILL, manager.getActiveConfig().behaviorType);
    }

    @Test
    void activateSwitchesToRequestedBuiltinProfile() {
        FillProfileManager manager = new FillProfileManager();

        manager.activate("reject-all");

        assertEquals("reject-all", manager.getActiveProfileName());
        assertEquals(FillBehaviorType.REJECT, manager.getActiveConfig().behaviorType);
        assertEquals(RejectReason.SIMULATOR_REJECT, manager.getActiveConfig().rejectReason);
    }

    @Test
    void createUpdateAndDeleteCustomProfiles() {
        FillProfileManager manager = new FillProfileManager();
        FillProfileDto dto = new FillProfileDto(
                "custom-random",
                "Custom random fill profile",
                "RANDOM_FILL",
                6_000,
                2,
                3,
                null,
                25,
                75,
                1,
                5,
                2);

        manager.createOrUpdate(dto);
        manager.activate("custom-random");

        FillBehaviorConfig config = manager.getActiveConfig();
        assertEquals("custom-random", manager.getActiveProfileName());
        assertEquals(FillBehaviorType.RANDOM_FILL, config.behaviorType);
        assertEquals(2_500, config.randomMinQtyPctBps);
        assertEquals(7_500, config.randomMaxQtyPctBps);
        assertEquals(1_000_000L, config.randomMinDelayNs);
        assertEquals(5_000_000L, config.randomMaxDelayNs);

        assertTrue(manager.delete("custom-random"));
        assertFalse(manager.delete("custom-random"));
    }
}

