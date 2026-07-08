package com.sunshine.orchestrator.expert;

import com.fasterxml.jackson.databind.JsonNode;

/** 多专家 Hub 轮次上下限钳制 */
public final class ExpertSessionRounds {

    private ExpertSessionRounds() {
    }

    public static int clampSessionMax(Integer requested, int minRounds, int globalMaxRounds) {
        int min = Math.max(1, minRounds);
        int globalMax = Math.max(min, globalMaxRounds);
        int session = requested != null && requested > 0 ? requested : globalMax;
        session = Math.min(session, globalMax);
        session = Math.max(session, min);
        return session;
    }

    public static int parseMaxRoundsNode(JsonNode node) {
        if (node == null || !node.has("maxRounds")) {
            return -1;
        }
        JsonNode rounds = node.get("maxRounds");
        if (rounds.isInt()) {
            return rounds.asInt();
        }
        if (rounds.isTextual()) {
            try {
                return Integer.parseInt(rounds.asText().strip());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
