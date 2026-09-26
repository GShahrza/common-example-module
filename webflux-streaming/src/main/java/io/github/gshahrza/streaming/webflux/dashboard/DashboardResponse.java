package io.github.gshahrza.streaming.webflux.dashboard;

public record DashboardResponse(String mode, long elapsedMs, Dashboard dashboard) {
}
