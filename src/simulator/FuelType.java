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
    // 燃點取一般木材片狀引燃溫度常見量級(約300°C)
    WOOD(17.5, 0.004, 0.06, 0.015, 300.0),

    // PAPER：紙類/書籍/紙箱等，燃燒特性與木材接近，略低的燃燒熱
    // 燃點取紙張常見引燃溫度量級(約230°C，俗稱「華氏451度」即約233°C)
    PAPER(16.0, 0.004, 0.06, 0.015, 230.0),

    // PU_FOAM：聚氨酯泡棉(沙發/床墊填充物)，燃燒熱高，通風不良時CO產率
    // 遠高於一般固體燃料，煙塵產率也高，是室內火災濃煙毒性的主要來源之一。
    // 【修正，校正清單問題3】原本通風不良CO產率取0.20偏保守；SFPE Handbook
    // (Tewarson章節)對高度通風不良的PU泡棉實測案例可到0.3~0.4 g/g量級，
    // 這裡上修到0.35(區間中段)，更貼近實測上緣。
    // 燃點取PU泡棉常見引燃溫度量級(約320°C)
    PU_FOAM(26.0, 0.03, 0.35, 0.13, 320.0),

    // PVC：聚氯乙烯(電線包覆/管材)，燃燒不完全時CO與煙塵產率都偏高。
    // 【修正，校正清單問題3】原本淨燃燒熱取17.0 MJ/kg偏高；PVC因含氯，
    // 燃燒生成HCl等產物會帶走部分能量，文獻常見淨燃燒熱範圍約11~16 MJ/kg，
    // 這裡下修到13.5(區間中段)。
    // 燃點取PVC常見自燃溫度量級(約390°C，含氯材料通常自燃溫度偏高)
    PVC(13.5, 0.02, 0.15, 0.15, 390.0),

    // GASOLINE：汽油等可燃液體，燃燒熱最高，通風良好時CO產率相對低，
    // 但大量堆積/悶燒時可快速轉為不完全燃燒
    GASOLINE(43.7, 0.01, 0.20, 0.06, 280.0);

    /** 燃燒熱(MJ/kg)：單位質量燃料完全燃燒釋放的熱量。 */
    final double heatOfCombustionMJPerKg;

    /** 通風良好(well-ventilated)時的CO產率(g CO / g 燃料)。 */
    final double coYieldWellVented;

    /** 通風不足(under-ventilated，悶燒/不完全燃燒)時的CO產率(g CO / g 燃料)。 */
    final double coYieldUnderVented;

    /** 煙塵(soot)產率(g soot / g 燃料)，供之後能見度/煙層濃度模型使用。 */
    final double sootYield;

    // 【新增，回應「可燃物需要燃點」需求】自燃/引燃溫度(°C)：這一格周遭氣體溫度
    // (Obj.tempC，見EnvironmentSimulator準穩態溫度公式)達到這個門檻時，即使還沒
    // 被相鄰火源直接「燒到」(原本靠機率式的鄰接延燒spread())，這一格本身鋪設的
    // 可燃物也會因為熱輻射/熱對流累積到自燃/引燃點而被獨立點燃(見
    // EnvironmentSimulator.igniteByTemperature())。
    //
    // 數值取常見文獻/工程手冊引用的量級(木材/紙張/PU泡棉/PVC/汽油自燃溫度常見
    // 範圍)，目的是先把「燃點」這個屬性接進模型讓熱擴散能反過來觸發點燃，不追求
    // 逐一文獻覆核的精確值，之後有需要可再校正——跟本檔案其餘數值的定位一致
    // (見檔案頂端註解)。
    final double ignitionTempC;

    FuelType(double heatOfCombustionMJPerKg, double coYieldWellVented,
             double coYieldUnderVented, double sootYield, double ignitionTempC) {
        this.heatOfCombustionMJPerKg = heatOfCombustionMJPerKg;
        this.coYieldWellVented = coYieldWellVented;
        this.coYieldUnderVented = coYieldUnderVented;
        this.sootYield = sootYield;
        this.ignitionTempC = ignitionTempC;
    }

    /**
     * 依通風狀態取得對應的CO產率(g CO / g 燃料)。
     * @param underVented true代表通風不足(悶燒/不完全燃燒)
     */
    double getCoYield(boolean underVented) {
        return underVented ? coYieldUnderVented : coYieldWellVented;
    }
}
