package simulator;

import java.util.Map;

// ═══════════════════════════════════════════════════════════════════════════
// ReportGenerator — 統計報表列印。
//   【拆解God Class】原本 printReport()/printAvgOrNA() 直接寫在 Simulator 裡；
//   這兩個方法本來就是透過參數接收所有需要的資料(statsMap/scenarioCount/...)，
//   沒有直接讀寫 Simulator 的 static 欄位，因此可以原封不動搬到獨立類別。
// ═══════════════════════════════════════════════════════════════════════════
class ReportGenerator {

    static void printReport(String title, Map<SimMode, ModeStats> statsMap, int scenarioCount,
                             double smartWrongDecisionCount, double hybridWrongDecisionCount, long peopleSum) {
        double smartFinalAccuracy  = (peopleSum > 0) ? (smartWrongDecisionCount  / peopleSum) : 0.0;
        double hybridFinalAccuracy = (peopleSum > 0) ? (hybridWrongDecisionCount / peopleSum) : 0.0;

        String[] labels = {"初始模擬(Default)", "決策支援(Smart)  ", "決策支援+備援控制(Hybrid)"};
        SimMode[] modes = {SimMode.DEFAULT, SimMode.SMART, SimMode.HYBRID};

        System.err.println("\n==============================================");
        System.err.println(title + "（場景數: " + scenarioCount + "）");
        System.err.println("==============================================");
        System.err.println("※ 逃生率/死亡率/受困率/進入高煙區比例/臨界CO暴露比例/誤入危險路線比例：");
        System.err.println("   採「每場景等權重平均」計算（不論該場景人數多寡，每場貢獻相同權重）。");
        System.err.println("※ 系統嚴重失誤機率：採「總人數加權平均」計算（人數越多的場景影響力越大）。");
        System.err.println("※ 兩者分母定義不同，請勿直接跨欄位比較數值大小。");
        System.err.println("==============================================");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 逃生率 / 死亡率 / 受困率 [場次平權] : %.2f%% / %.2f%% / %.2f%%\n", labels[i],
                (scenarioCount > 0 ? st.totalSurviveP / scenarioCount : 0) * 100,
                (scenarioCount > 0 ? st.totalDeathP / scenarioCount : 0) * 100,
                (scenarioCount > 0 ? st.totalTrappedP / scenarioCount : 0) * 100);
        }
        System.err.printf("系統嚴重失誤機率 (Smart mistake Rate) [人數加權]  : %.2f%%\n", smartFinalAccuracy * 100);
        System.err.printf("系統嚴重失誤機率 (Hybrid mistake Rate) [人數加權] : %.2f%%\n", hybridFinalAccuracy * 100);
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 進入高煙區比例 [場次平權] : %.2f%%\n", labels[i], (scenarioCount > 0 ? st.highSmokeP / scenarioCount : 0) * 100);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 臨界CO暴露比例 [場次平權] : %.2f%%\n", labels[i], (scenarioCount > 0 ? st.criticalCOP / scenarioCount : 0) * 100);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 誤入危險路線比例 [場次平權] : %.2f%%\n", labels[i], (scenarioCount > 0 ? st.wrongRouteP / scenarioCount : 0) * 100);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 改道成功率", st.rerouteSuccessSum, st.rerouteIterCount, true);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 弱勢對象辨識率", st.vulnerableIdSum, st.vulnerableIterCount, true);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 平均逃脫所需時間(RSET，僅計入成功逃生者，無法代表整體逃生表現)",
                st.avgEscapeTickSum, st.escapeTickIterCount, false);
        }
        System.err.println("----------------------------------------------");
        for (int i = 1; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 建議撤回→改道完成延遲", st.revokeToRerouteSum, st.revokeToRerouteIterCount, false);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 可用安全逃生時間(ASET，所有可停留格子煙霧>0.7或起火)", st.asetSum, st.asetIterCount, false);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 安全餘裕(ASET−RSET，皆以點火為基準)", st.safetyMarginSum, st.safetyMarginIterCount, false);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 平均察覺時間(從點火起算)", st.avgAwarenessSum, st.awarenessIterCount, false);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " RSET(以察覺為基準，僅供對照，不用於安全餘裕計算)", st.avgCueRsetSum, st.cueRsetIterCount, false);
        }
        System.err.println("----------------------------------------------");
        System.err.printf("決策支援+備援控制(Hybrid) 平均每場主動控制動作次數(關門/排煙) : %.2f 次\n",
            scenarioCount > 0 ? statsMap.get(SimMode.HYBRID).activeControlActionSum / scenarioCount : 0.0);
        System.err.println("決策延遲常數 C = 0 秒（因為多次隨機採樣模擬中，每個人的決策延遲無法被客觀因素精確的計算。故定義先設為常數，未納入上列任何平均時間計算）");
        System.err.println("==============================================");
    }

    // 輔助函式：印出「只在有意義的場次才平均」的KPI，若整個模擬過程都沒有發生過相關事件，就標示 N/A 而不是硬算出0
    private static void printAvgOrNA(String label, double sum, int count, boolean asPercent) {
        if (count == 0) {
            System.err.println(label + " : N/A (無相關樣本)");
        } else if (asPercent) {
            System.err.printf("%s : %.2f%% (樣本場次: %d)\n", label, (sum / count) * 100, count);
        } else {
            System.err.printf("%s : %.2f tick (樣本場次: %d)\n", label, sum / count, count);
        }
    }
}
