package simulator;

import java.util.Random;

// ─── 感測器網路 ────────────────────────────────────────────────
class Detector {
    int z, y, x;
    boolean broken = false; // 是否已損壞（被火燒毀，或隨機故障），損壞後系統保守視為此處已經著火
    boolean danger = false; // 系統「目前讀到」的濃煙警報，會受感測誤差影響，不代表現場真實狀況

    private static final double MALFUNCTION_CHANCE = 0.002; // 每個tick隨機故障(跟火無關，例如電力/線路/機件問題)的機率
    private static final double NOISE_STDDEV = 0.15;        // 讀數誤差的標準差，誤差可能讓讀數偏高，也可能偏低
    private static final double DANGER_THRESHOLD = 0.7;     // 判定「濃煙警戒」的煙霧濃度門檻

    public Detector(int z, int y, int x) {
        this.z = z; this.y = y; this.x = x;
    }

    void update(Obj[][][] map, Random rng) {
        // 已經損壞的感測器不會再回報新讀數，維持在「未知、系統保守視為危險」的狀態
        if (broken) return;

        // 真的被火燒到：物理損毀，跟感測誤差無關
        if (map[z][y][x].fire) {
            broken = true;
            return;
        }

        // 隨機故障：例如電力不穩、線路老化、機件本身問題，跟現場是否有火無關
        if (rng.nextDouble() < MALFUNCTION_CHANCE) {
            broken = true;
            return;
        }

        // 正常運作時，讀數仍帶有誤差：真實煙霧濃度 + 常態分布雜訊，可能誤報（偏高）也可能漏報（偏低）
        double perceivedSmoke = map[z][y][x].smoke + rng.nextGaussian() * NOISE_STDDEV;
        danger = perceivedSmoke > DANGER_THRESHOLD;
    }
}
