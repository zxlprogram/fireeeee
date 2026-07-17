package simulator;

import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// PanicModel — 恐慌值累積與延遲/誤操作機率
//   把「恐慌」這個橫跨多處(escape/instinctiveEscapeStep/SmartEscape)重複用到
//   的概念收斂成一個物件；rollFreeze()/rollMisstep() 內部各自只呼叫一次
//   rng.nextDouble()，呼叫時機跟原本內嵌在 People 方法中的位置完全相同，
//   所以搬動後 Random 的呼叫序列不變。
// ═══════════════════════════════════════════════════════════════════════════
class PanicModel {
    double panicLevel = 0.0;              // 0.0(冷靜) ~ 1.0(極度恐慌)，隨所在位置煙霧濃度動態調整
    private final double panicSusceptibility; // 各角色的恐慌易感度，帶小孩/年長者/行動不便者較高

    private static final double PANIC_DELAY_CHANCE_FACTOR = 0.15; // panicLevel * 此係數 = 該tick「愣住不動」的機率
    private static final double PANIC_MISOP_CHANCE_FACTOR = 0.20; // panicLevel * 此係數 = 該tick「誤操作/不服從建議」的機率

    PanicModel(double panicSusceptibility) {
        this.panicSusceptibility = panicSusceptibility;
    }

    // 所在位置煙霧濃度越高、角色恐慌易感度越高，恐慌值越高
    void update(double localSmoke) {
        double target = Math.min(1.0, localSmoke + panicSusceptibility * 0.3);
        panicLevel += (target - panicLevel) * 0.3;
        if (panicLevel < 0) panicLevel = 0;
    }

    boolean rollFreeze(Random rng) {
        return rng.nextDouble() < panicLevel * PANIC_DELAY_CHANCE_FACTOR;
    }

    boolean rollMisstep(Random rng) {
        return rng.nextDouble() < panicLevel * PANIC_MISOP_CHANCE_FACTOR;
    }
}
