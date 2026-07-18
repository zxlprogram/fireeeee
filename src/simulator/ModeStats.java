package simulator;

// 各情境(DEFAULT/SMART/HYBRID)的統計累加容器。
// 「不是每場模擬都會發生」的KPI(改道、弱勢辨識、ASET等)額外用 *IterCount 只對
// 「有意義的場次」取平均，避免被沒發生該事件的場次拉低。
class ModeStats {
    double totalSurviveP = 0, totalDeathP = 0, totalTrappedP = 0; // 三分類比例：逃生/死亡/受困
    double highSmokeP = 0, criticalCOP = 0, wrongRouteP = 0;
    double rerouteSuccessSum = 0; int rerouteIterCount = 0;
    // 死因比例(僅在「死亡者中」計算，只對有人死亡的場次取平均，避免被沒人死亡的場次拉低)
    double fireDeathCauseSum = 0, coDeathCauseSum = 0; int deathCauseIterCount = 0;
    double vulnerableIdSum = 0; int vulnerableIterCount = 0;
    double avgEscapeTickSum = 0; int escapeTickIterCount = 0;      // RSET(以點火為基準，僅計入成功逃生者)
    double revokeToRerouteSum = 0; int revokeToRerouteIterCount = 0;   // 建議撤回→改道完成 延遲
    double asetSum = 0; int asetIterCount = 0;                        // ASET(所有可停留格子煙霧>0.7或起火)
    double safetyMarginSum = 0; int safetyMarginIterCount = 0;        // ASET − RSET(avgEscapeTick)，皆以點火為基準
    double activeControlActionSum = 0;                                // 平均每場主動控制動作次數(僅HYBRID有意義)
    // 決策窗口分解診斷指標，純粹用來拆解「RSET裡面時間都花在哪」，不會回饋進安全餘裕的計算
    double avgAwarenessSum = 0; int awarenessIterCount = 0;     // 平均察覺時間(從點火起算)
    double avgCueRsetSum = 0; int cueRsetIterCount = 0;         // 診斷用：以察覺為基準的RSET(逃脫tick−察覺tick)，僅供對照

    // 把某一棟建築(或某個場景)的ModeStats併入更大範圍(全建築類型)的累加容器
    void mergeFrom(ModeStats source) {
        this.totalSurviveP += source.totalSurviveP;
        this.totalDeathP += source.totalDeathP;
        this.totalTrappedP += source.totalTrappedP;
        this.highSmokeP += source.highSmokeP;
        this.criticalCOP += source.criticalCOP;
        this.wrongRouteP += source.wrongRouteP;
        this.rerouteSuccessSum += source.rerouteSuccessSum; this.rerouteIterCount += source.rerouteIterCount;
        this.fireDeathCauseSum += source.fireDeathCauseSum; this.coDeathCauseSum += source.coDeathCauseSum;
        this.deathCauseIterCount += source.deathCauseIterCount;
        this.vulnerableIdSum += source.vulnerableIdSum; this.vulnerableIterCount += source.vulnerableIterCount;
        this.avgEscapeTickSum += source.avgEscapeTickSum; this.escapeTickIterCount += source.escapeTickIterCount;
        this.revokeToRerouteSum += source.revokeToRerouteSum; this.revokeToRerouteIterCount += source.revokeToRerouteIterCount;
        this.asetSum += source.asetSum; this.asetIterCount += source.asetIterCount;
        this.safetyMarginSum += source.safetyMarginSum; this.safetyMarginIterCount += source.safetyMarginIterCount;
        this.activeControlActionSum += source.activeControlActionSum;
        this.avgAwarenessSum += source.avgAwarenessSum; this.awarenessIterCount += source.awarenessIterCount;
        this.avgCueRsetSum += source.avgCueRsetSum; this.cueRsetIterCount += source.cueRsetIterCount;
    }
}
