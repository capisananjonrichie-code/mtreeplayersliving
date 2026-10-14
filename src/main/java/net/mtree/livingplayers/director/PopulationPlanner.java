package net.mtree.livingplayers.director;

/** Pure decision: should the director add a bot, remove one, or hold? */
public final class PopulationPlanner {
    private PopulationPlanner() {}

    public enum Action { SPAWN, DESPAWN_ONE, NONE }

    public record Input(boolean enabled, int eligiblePlayers, int activeBots, int maxActive,
                        double targetPerPlayer, double tps, double tpsThrottleBelow) {}

    public record Plan(Action action, String reason, int target) {}

    public static int targetActive(int players, double perPlayer, int maxActive) {
        if (players <= 0) return 0;
        return Math.max(0, Math.min(maxActive, (int) Math.ceil(players * Math.max(0, perPlayer))));
    }

    public static Plan plan(Input in) {
        int target = targetActive(in.eligiblePlayers(), in.targetPerPlayer(), in.maxActive());
        if (!in.enabled()) return new Plan(Action.NONE, "population disabled", target);
        if (in.eligiblePlayers() <= 0) return new Plan(Action.NONE, "no eligible players online", 0);
        if (in.tps() < in.tpsThrottleBelow()) return new Plan(Action.NONE, "TPS below throttle (" + String.format("%.1f", in.tps()) + ")", target);
        if (in.activeBots() < target) return new Plan(Action.SPAWN, "below target", target);
        if (in.activeBots() > target) return new Plan(Action.DESPAWN_ONE, "above target", target);
        return new Plan(Action.NONE, "at target", target);
    }
}
