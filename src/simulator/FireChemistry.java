package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// FireChemistry — HRR → 質量損失率 → CO生成率計算工具(校正清單§12基礎架構)。
//
//   【校正清單§2/§3，已接入】EnvironmentSimulator.spread()現在真的依序呼叫
//   這裡的公式：
//     1. 由FireCause.growthCurve算出這一格「已經燒了多久」對應的HRR(kW)
//        (見PhysicalConstants.HRR_PEAK_CAP_KW的封頂說明)
//     2. getMassLossRate(hrrKw, fuelType) 換算成質量損失率(kg/s)
//     3. getCoGenerationRate(massLossRate, fuelType, underVented) 換算成
//        CO生成速率(kg/s)
//     4. 用該格的真實體積(Space.getCellVolumeM3())把這次生成的CO質量換算成
//        濃度(ppm)增量，取代原本的CO_BASE_GENERATION_PPM_PER_TICK代理模型。
// ═══════════════════════════════════════════════════════════════════════════
final class FireChemistry {
    private FireChemistry() {}

    /**
     * 質量損失率(kg/s) = HRR(kW) / 燃燒熱(MJ/kg)。
     * 換算細節：HRR單位kW = kJ/s，燃燒熱單位MJ/kg = 1000 kJ/kg，
     * 所以 kg/s = (kJ/s) / (1000 kJ/kg) = HRR / (heatOfCombustionMJPerKg × 1000)。
     *
     * @param hrrKw 熱釋放率(kW)
     * @param fuelType 燃料種類，提供燃燒熱(MJ/kg)
     * @return 質量損失率(kg/s)
     */
    static double getMassLossRate(double hrrKw, FuelType fuelType) {
        if (fuelType.heatOfCombustionMJPerKg <= 0) return 0.0;
        return hrrKw / (fuelType.heatOfCombustionMJPerKg * 1000.0);
    }

    /**
     * CO生成速率(kg/s) = 質量損失率(kg/s) × CO產率(g CO/g 燃料，無因次比值)。
     *
     * @param massLossRateKgPerS 質量損失率(kg/s)，通常來自getMassLossRate()
     * @param fuelType 燃料種類，提供CO產率
     * @param underVented 是否為通風不足(悶燒/不完全燃燒)情境
     * @return CO生成速率(kg/s)
     */
    static double getCoGenerationRate(double massLossRateKgPerS, FuelType fuelType, boolean underVented) {
        return massLossRateKgPerS * fuelType.getCoYield(underVented);
    }

    /**
     * 便利函式：直接從HRR算到CO生成速率(kg/s)，內部依序呼叫
     * getMassLossRate()與getCoGenerationRate()。
     *
     * @param hrrKw 熱釋放率(kW)
     * @param fuelType 燃料種類
     * @param underVented 是否為通風不足情境
     * @return CO生成速率(kg/s)
     */
    static double getCoGenerationRateFromHrr(double hrrKw, FuelType fuelType, boolean underVented) {
        double massLossRate = getMassLossRate(hrrKw, fuelType);
        return getCoGenerationRate(massLossRate, fuelType, underVented);
    }
}
