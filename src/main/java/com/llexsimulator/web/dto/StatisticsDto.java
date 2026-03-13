package com.llexsimulator.web.dto;

/** Statistics snapshot returned by the REST API. All latencies in microseconds. */
public final class StatisticsDto {
    public long   ordersReceived;
    public long   execReportsSent;
    public long   fillsSent;
    public long   rejectsSent;
    public long   cancelsSent;
    public long   p80LatencyUs;
    public long   p90LatencyUs;
    public long   p99LatencyUs;
    public long   throughputPerSec;
    public double fillRatePct;
    public String activeProfile;

    public StatisticsDto() {}
}

