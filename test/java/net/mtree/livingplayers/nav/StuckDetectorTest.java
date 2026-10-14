package net.mtree.livingplayers.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StuckDetectorTest {
    @Test void notStuckWhileMoving() {
        StuckDetector d = new StuckDetector(4, 0.5);
        for (int i = 0; i < 20; i++) assertFalse(d.sample(i * 1.0, 64, 0));
    }
    @Test void stuckWhenStationaryForAWindow() {
        StuckDetector d = new StuckDetector(4, 0.5);
        boolean stuck = false;
        for (int i = 0; i < 6; i++) stuck = d.sample(10, 64, 10);
        assertTrue(stuck);
    }
    @Test void needsAFullWindowBeforeJudging() {
        StuckDetector d = new StuckDetector(4, 0.5);
        assertFalse(d.sample(0, 0, 0)); assertFalse(d.sample(0, 0, 0)); assertFalse(d.sample(0, 0, 0));
        assertTrue(d.sample(0, 0, 0));
    }
    @Test void resetClearsHistory() {
        StuckDetector d = new StuckDetector(3, 0.5);
        for (int i = 0; i < 5; i++) d.sample(0, 0, 0);
        d.reset();
        assertFalse(d.sample(0, 0, 0));
    }
    @Test void slowCrawlBelowThresholdIsStuck() {
        StuckDetector d = new StuckDetector(4, 0.5);
        boolean stuck = false;
        for (int i = 0; i < 8; i++) stuck = d.sample(i * 0.05, 64, 0);
        assertTrue(stuck);
    }
}
