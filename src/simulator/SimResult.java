package simulator;

import java.util.Arrays;

// 每個人的模擬結果：實際發生的tick，以及最終「結局」(存活/死亡/仍受困)
// 這樣才能正確分辨「逃脫」跟「困在原地但還沒死」，不會把兩者混為一談
class SimResult {
    int[] times;
    Outcome[] outcomes;
    boolean[] enteredHighSmoke;   // 是否曾經進入高煙區
    boolean[] reachedCriticalCO;  // 是否曾經CO暴露達臨界值
    boolean[] wrongRoute;         // 是否曾依現有資訊做決策卻仍誤入高煙危險路線
    boolean[] rerouted;           // 是否曾發生「原路線失效，被迫改道」
    boolean[] rerouteSuccess;     // 改道之後最終是否成功逃脫(只在 rerouted=true 時有意義)
    boolean[] vulnerableIdentified; // 系統是否成功辨識並標記此人為需優先協助對象
    boolean[] isVulnerableProfile;  // 此人本身是否屬於「行動不便/年長者」這類需優先協助對象
    int[] revokeToRerouteTicks;   // 建議被撤回後到拿到下一筆建議所花的tick，-1代表本人從未發生撤回
    int[] firstCueTick;      // 第一次察覺(聞到煙味/看到煙火/系統示警)的tick，-1代表整場都沒察覺
    DeathCause[] deathCauses; // 死因(燒死/CO中毒)，只有outcomes[i]==DEAD時才有意義，其餘為NONE

    SimResult(int n) {
        times = new int[n];
        outcomes = new Outcome[n];
        enteredHighSmoke = new boolean[n];
        reachedCriticalCO = new boolean[n];
        wrongRoute = new boolean[n];
        rerouted = new boolean[n];
        rerouteSuccess = new boolean[n];
        vulnerableIdentified = new boolean[n];
        isVulnerableProfile = new boolean[n];
        revokeToRerouteTicks = new int[n];
        firstCueTick = new int[n];
        deathCauses = new DeathCause[n];
        Arrays.fill(outcomes, Outcome.TRAPPED); // 預設：模擬結束時還沒死也還沒逃出去
        Arrays.fill(revokeToRerouteTicks, -1);
        Arrays.fill(firstCueTick, -1);
        Arrays.fill(deathCauses, DeathCause.NONE);
    }

    // 依據 People 目前狀態，把單一個體的所有KPI旗標寫入對應索引
    void recordPersonSnapshot(People p, int tick, Outcome outcome) {
        int idx = p.id - 1;
        times[idx] = tick;
        outcomes[idx] = outcome;
        enteredHighSmoke[idx] = p.kpi.everEnteredHighSmoke;
        reachedCriticalCO[idx] = p.kpi.everReachedCriticalCO;
        wrongRoute[idx] = p.kpi.everWrongRoute;
        rerouted[idx] = p.kpi.everRerouted;
        rerouteSuccess[idx] = p.kpi.everRerouted && outcome == Outcome.ESCAPED;
        vulnerableIdentified[idx] = p.kpi.systemIdentifiedVulnerable;
        isVulnerableProfile[idx] = (p.profile == PersonProfile.IMPAIRED || p.profile == PersonProfile.ELDERLY);
        revokeToRerouteTicks[idx] = (p.kpi.revokeToRerouteTicks != null) ? p.kpi.revokeToRerouteTicks : -1;
        firstCueTick[idx] = (p.firstCueAt != null) ? p.firstCueAt : -1;
        deathCauses[idx] = p.deathCause;
    }
}
