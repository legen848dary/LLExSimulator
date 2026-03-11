package com.llexsimulator.config;

import com.llexsimulator.fill.FillBehaviorConfig;

/**
 * A named fill-behavior profile that can be saved, listed, and activated via the REST API.
 */
public record FillBehaviorProfile(String name, String description, FillBehaviorConfig config) {}

