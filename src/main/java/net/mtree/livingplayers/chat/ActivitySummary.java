package net.mtree.livingplayers.chat;

/** Turns internal task state into a short phrase a bot can say it is doing. Pure. */
public final class ActivitySummary {
    private ActivitySummary() {}

    /** @param buildingPhase null, "GATHER" or "BUILD"; @param survivalStep e.g. "GATHER:LOG", "CRAFT:STONE_PICKAXE" or null */
    public static String describe(boolean fighting, boolean retreating, boolean eating, String buildingPhase, String survivalStep) {
        if (fighting && retreating) return "running away to recover";
        if (fighting) return "fighting";
        if (eating) return "eating";
        if ("BUILD".equals(buildingPhase)) return "building my hut";
        if ("GATHER".equals(buildingPhase)) return "gathering wood for my hut";
        if (survivalStep != null) {
            if (survivalStep.equals("GATHER:LOG")) return "chopping trees";
            if (survivalStep.equals("GATHER:COBBLESTONE")) return "mining stone";
            if (survivalStep.startsWith("CRAFT:")) return "crafting";
            if (survivalStep.startsWith("PLACE_TABLE")) return "setting up a crafting table";
        }
        return "just wandering around";
    }
}
