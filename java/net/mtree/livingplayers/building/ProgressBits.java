package net.mtree.livingplayers.building;

/** Compact placed/not-placed flags for a project, stored as a hex string so a half-built house survives restarts. */
public final class ProgressBits {
    private final boolean[] bits;

    public ProgressBits(int size) { bits = new boolean[size]; }

    public int size() { return bits.length; }
    public boolean get(int i) { return bits[i]; }
    public void set(int i) { bits[i] = true; }

    public int count() {
        int n = 0;
        for (boolean b : bits) if (b) n++;
        return n;
    }

    public int firstUnset() {
        for (int i = 0; i < bits.length; i++) if (!bits[i]) return i;
        return -1;
    }

    public String toHex() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i += 4) {
            int v = 0;
            for (int k = 0; k < 4 && i + k < bits.length; k++) if (bits[i + k]) v |= 1 << k;
            sb.append(Character.forDigit(v, 16));
        }
        return sb.toString();
    }

    /** Tolerant: a short/garbled string yields a partially (or un)filled instance; it never throws. */
    public static ProgressBits fromHex(int size, String hex) {
        ProgressBits p = new ProgressBits(size);
        if (hex == null) return p;
        for (int i = 0; i < hex.length() && i * 4 < size; i++) {
            int v = Character.digit(hex.charAt(i), 16);
            if (v < 0) continue;
            for (int k = 0; k < 4 && i * 4 + k < size; k++) if ((v & (1 << k)) != 0) p.bits[i * 4 + k] = true;
        }
        return p;
    }
}
