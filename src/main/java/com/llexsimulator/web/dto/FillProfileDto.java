package com.llexsimulator.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** REST payload for creating / updating a fill-behavior profile. */
public final class FillProfileDto {

    public String name;
    public String description;
    /** Must match a {@link com.llexsimulator.sbe.FillBehaviorType} name. */
    public String behaviorType;
    /** Fill percentage in basis points (0–10000). */
    public int    fillPctBps;
    public int    numPartialFills;
    /** Delay in milliseconds (converted to nanoseconds internally). */
    public long   delayMs;
    /** Must match a {@link com.llexsimulator.sbe.RejectReason} name, or null. */
    public String rejectReason;
    public int    randomMinQtyPct;
    public int    randomMaxQtyPct;
    public long   randomMinDelayMs;
    public long   randomMaxDelayMs;
    public int    priceImprovementBps;

    public FillProfileDto() {}

    @JsonCreator
    public FillProfileDto(
            @JsonProperty("name")               String name,
            @JsonProperty("description")        String description,
            @JsonProperty("behaviorType")       String behaviorType,
            @JsonProperty("fillPctBps")         int    fillPctBps,
            @JsonProperty("numPartialFills")    int    numPartialFills,
            @JsonProperty("delayMs")            long   delayMs,
            @JsonProperty("rejectReason")       String rejectReason,
            @JsonProperty("randomMinQtyPct")    int    randomMinQtyPct,
            @JsonProperty("randomMaxQtyPct")    int    randomMaxQtyPct,
            @JsonProperty("randomMinDelayMs")   long   randomMinDelayMs,
            @JsonProperty("randomMaxDelayMs")   long   randomMaxDelayMs,
            @JsonProperty("priceImprovementBps") int   priceImprovementBps) {
        this.name               = name;
        this.description        = description;
        this.behaviorType       = behaviorType;
        this.fillPctBps         = fillPctBps;
        this.numPartialFills    = numPartialFills;
        this.delayMs            = delayMs;
        this.rejectReason       = rejectReason;
        this.randomMinQtyPct    = randomMinQtyPct;
        this.randomMaxQtyPct    = randomMaxQtyPct;
        this.randomMinDelayMs   = randomMinDelayMs;
        this.randomMaxDelayMs   = randomMaxDelayMs;
        this.priceImprovementBps = priceImprovementBps;
    }
}

