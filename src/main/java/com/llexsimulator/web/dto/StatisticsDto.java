package com.llexsimulator.web.dto;

/** Statistics snapshot returned by the REST API. All latencies in microseconds. */
public final class StatisticsDto {
    public long   ordersReceived;
    public long   execReportsSent;
    public long   fillsSent;
    public long   rejectsSent;
    public long   cancelsSent;
    public long   p50LatencyUs;
    public long   p99LatencyUs;
    public long   p999LatencyUs;
    public long   maxLatencyUs;
    public long   throughputPerSec;
    public double fillRatePct;
    public String activeProfile;

    public StatisticsDto() {}
}

