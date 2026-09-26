package io.github.gshahrza.streaming.webflux.dashboard;

import java.util.List;

public record Dashboard(String user, List<String> orders, List<String> recommendations) {
}
