package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// FuelType — 燃料資料庫(校正清單§12基礎架構)。
//
//   【校正清單§2/§3，已接入】每種FireCause透過representativeFuel對應到這裡
//   的一種燃料，EnvironmentSimulator.spread()用它的燃燒熱/CO產率算出每個
//   火源格實際的CO生成速率(見FireChemistry.java)，取代原本完全沒有「燃料
//   種類」概念的CO_BASE_GENERATION_PPM_PER_TICK代理模型。
//
//   數值取文獻常見量級(SFPE Handbook / Tewarson等常引用範圍)，目的是先把
//   資料結構撐起來，不追求逐一文獻覆核的精確值，之後有需要可再校正。
// ═══════════════════════════════════════════════════════════════════════════
enum FuelType {
    // WOOD：一般木質家具/建材，完全燃燒為主，CO產率偏低，通風不良時明顯上升
    WOOD(17.5, 0.004, 0.06, 0.015),

    // PAPER：紙類/書籍/紙箱等，燃燒特性與木材接近，略低的燃燒熱
    PAPER(16.0, 0.004, 0.06, 0.015),

    // PU_FOAM：聚氨酯泡棉(沙發/床墊填充物)，燃燒熱高，通風不良時CO產率
    // 遠高於一般固體燃料，煙塵產率也高，是室內火災濃煙毒性的主要來源之一
    PU_FOAM(26.0, 0.03, 0.20, 0.13),

    // PVC：聚氯乙烯(電線包覆/管材)，燃燒不完全時CO與煙塵產率都偏高
    PVC(17.0, 0.02, 0.15, 0.15),

    // GASOLINE：汽油等可燃液體，燃燒熱最高，通風良好時CO產率相對低，
    // 但大量堆積/悶燒時可快速轉為不完全燃燒
    GASOLINE(43.7, 0.01, 0.20, 0.06);

    /** 燃燒熱(MJ/kg)：單位質量燃料完全燃燒釋放的熱量。 */
    final double heatOfCombustionMJPerKg;

    /** 通風良好(well-ventilated)時的CO產率(g CO / g 燃料)。 */
    final double coYieldWellVented;

    /** 通風不足(under-ventilated，悶燒/不完全燃燒)時的CO產率(g CO / g 燃料)。 */
    final double coYieldUnderVented;

    /** 煙塵(soot)產率(g soot / g 燃料)，供之後能見度/煙層濃度模型使用。 */
    final double sootYield;

    FuelType(double heatOfCombustionMJPerKg, double coYieldWellVented,
             double coYieldUnderVented, double sootYield) {
        this.heatOfCombustionMJPerKg = heatOfCombustionMJPerKg;
        this.coYieldWellVented = coYieldWellVented;
        this.coYieldUnderVented = coYieldUnderVented;
        this.sootYield = sootYield;
    }

    /**
     * 依通風狀態取得對應的CO產率(g CO / g 燃料)。
     * @param underVented true代表通風不足(悶燒/不完全燃燒)
     */
    double getCoYield(boolean underVented) {
        return underVented ? coYieldUnderVented : coYieldWellVented;
    }
}
