package simulator;

import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// PanicModel — 恐慌值累積與延遲/誤操作機率
//   把「恐慌」這個橫跨多處(escape/instinctiveEscapeStep/SmartEscape)重複用到
//   的概念收斂成一個物件；rollFreeze()/rollMisstep() 內部各自只呼叫一次
//   rng.nextDouble()，呼叫時機跟原本內嵌在 People 方法中的位置完全相同，
//   所以搬動後 Random 的呼叫序列不變。
//
//   【校正清單§9，新增】samplePremovementTicks()：依角色卡抽樣「準備時間
//   (pre-movement time)」，取代原本只有WITH_CHILD才有、且抽樣範圍明顯偏短
//   的ad hoc延遲(People.childGatherDelay)，現在所有角色在察覺異常的當下
//   都會抽一個對數常態分布的準備時間(見People.premovementTicksRemaining的
//   設定點)，這會讓Random的呼叫序列跟校正前不同，是刻意的行為變更。
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

    // ─── §9 準備時間(pre-movement time)：對數常態分布 ──────────────────
    // 【校正清單§9，取代原本只有WITH_CHILD才有的「3+隨機0~5tick」ad hoc延遲】
    // 文獻(BS PD 7974-6、SFPE Handbook第4版"Human Behavior in Fire"一章、
    // Purser & Bensilum(2001))普遍以對數常態分布描述「察覺異常」到「真正開始
    // 朝出口行動」之間的準備時間：中位數依警報型態/建築熟悉度/人員角色而異，
    // 對數標準差反映個體差異(有人幾乎立刻行動、有人拖得很久)。這裡依角色卡
    // 給出中位數(秒)與對數標準差，量級取常見引用範圍的中段：
    //   - STAFF：熟悉場地、反應快，中位數最短
    //   - NORMAL_SOLO：一般成人，中位數約1分鐘
    //   - CUSTOMER：對場域陌生，需要多一點時間確認狀況
    //   - IMPAIRED：認知判斷速度跟一般成人接近，但確認/求助過程稍久
    //   - ELDERLY：確認/決策過程普遍較慢(文獻常見現象)
    //   - WITH_CHILD：需要先尋找/確認小孩安全，中位數最長，取代原本的
    //     ad hoc延遲(原本只有3~8個tick，明顯低估真實的家庭疏散準備時間)
    //
    // X = exp(μ + σ·Z)，Z~N(0,1)，μ=ln(中位數)，這樣X的中位數剛好等於
    // medianSeconds(對數常態分布的中位數就是exp(μ))。
    static int samplePremovementTicks(PersonProfile profile, Random rng) {
        double medianSeconds;
        double sigma;
        switch (profile) {
            case STAFF:
                medianSeconds = 40.0;  sigma = 0.4; break;
            case WITH_CHILD:
                medianSeconds = 150.0; sigma = 0.5; break;
            case ELDERLY:
                medianSeconds = 120.0; sigma = 0.6; break;
            case IMPAIRED:
                medianSeconds = 90.0;  sigma = 0.5; break;
            case CUSTOMER:
                medianSeconds = 90.0;  sigma = 0.6; break;
            case NORMAL_SOLO:
            default:
                medianSeconds = 60.0;  sigma = 0.5; break;
        }
        double mu = Math.log(medianSeconds);
        double sampleSeconds = Math.exp(mu + sigma * rng.nextGaussian());
        int ticks = (int) Math.round(sampleSeconds / PhysicalConstants.TICK_SECONDS);
        return Math.max(0, ticks);
    }
}
