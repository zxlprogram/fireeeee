package simulator;

// ─── 起火原因 ─────────────────────────────────────────────────
// 【校正清單§5/§6/§2/§3】重構前，起火原因只帶一個smokeToleranceFactor，直接乘進
// 「個人」的CO致命閾值(coThreshold)——但現實中不同起火原因會造成CO致命性差異，
// 主因是「這場火通風好不好、燃燒是否完全」與「燒的是什麼東西」，也就是CO產率
// (g CO/g燃料)不同(完全燃燒約0.01–0.05 g/g；不完全燃燒/通風不足甚至可到
// 0.1–0.4 g/g，差距可達10倍以上)，這是「起火原因/燃料種類」的屬性，不該直接
// 改個人的耐受閾值。
//
// 【校正清單§2/§3更新】原本用一個拍腦袋的coYieldMultiplier(1.0/2.0/3.0)代表
// 「這種起火原因CO生成得比較快/慢」，現在改成讓每種起火原因對應一種代表性的
// 真實燃料(representativeFuel，見FuelType.java的燃燒熱/CO產率資料庫)，交給
// FireChemistry.getCoGenerationRateFromHrr()算出實際的CO生成速率(kg/s)，
// 不再是憑空給一個相對倍率——通風好壞(underVented)則由EnvironmentSimulator
// 依當下格子鄰接是否有開放通道(門/出口)動態判斷，兩者相乘後才是CO產率的
// 決定因素，比原本單一倍率更貼近「起火原因→燃料特性→(隨情境變化的)通風
// 狀態→CO產率」這個真實的因果鏈。
//
// 校正後：
//   - representativeFuel：這種起火原因最具代表性的燃料種類，決定燃燒熱
//     (換算質量損失率)與通風好壞兩種情境下各自的CO產率(見FuelType)。
//   - growthCurve：對應校正清單§6建議的NFPA t²火災成長曲線分類，現在真的用
//     於算Q(t)=α·t²(見EnvironmentSimulator.spread()與PhysicalConstants
//     HRR_PEAK_CAP_KW的封頂說明)。
//   - 人員「個人」對CO的易感度掛在People.coSusceptibilityD上，
//     只反映體質(成年人/幼童/行動不便者/年長者)，不再跟起火原因混在一起。
enum FireCause {
    // ELECTRICAL：電線走火/電器短路，起火點常是電線包覆材(PVC)悶燒，
    // 初期蔓延快但不像縱火/化學起火那樣持續通風不良 → §6對應fast(快速)成長曲線
    ELECTRICAL(FuelType.PVC, GrowthCurve.FAST),

    // CHEMICAL：化學反應/可燃液體(汽油等)，達到臨界濃度後燃燒速率遠高於
    // 一般固體燃料 → §6對應ultra-fast(極快速)成長曲線
    CHEMICAL(FuelType.GASOLINE, GrowthCurve.ULTRA_FAST),

    // ARSON：使用汽油等助燃劑，符合ultra-fast曲線的實驗條件設定
    ARSON(FuelType.GASOLINE, GrowthCurve.ULTRA_FAST),

    // ACCIDENTAL：一般家具/建材延燒的典型分類，以木質燃料為代表
    // → §6對應medium(中速)成長曲線
    ACCIDENTAL(FuelType.WOOD, GrowthCurve.MEDIUM);

    // 校正清單§6：NFPA/SFPE t²火災成長曲線分類，Q(t)=α·t² [kW]
    enum GrowthCurve {
        SLOW(PhysicalConstants.ALPHA_SLOW),
        MEDIUM(PhysicalConstants.ALPHA_MEDIUM),
        FAST(PhysicalConstants.ALPHA_FAST),
        ULTRA_FAST(PhysicalConstants.ALPHA_ULTRAFAST);

        final double alphaKwPerSecondSquared;
        GrowthCurve(double alpha) { this.alphaKwPerSecondSquared = alpha; }
    }

    final FuelType representativeFuel;
    final GrowthCurve growthCurve;

    FireCause(FuelType representativeFuel, GrowthCurve growthCurve) {
        this.representativeFuel = representativeFuel;
        this.growthCurve = growthCurve;
    }
}
