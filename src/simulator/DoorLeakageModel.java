package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// DoorLeakageModel — 校正清單§14：門對煙霧/煙味/CO/熱擴散通量的「穿越量」，
// 改用孔口流量公式的物理洩漏模型計算，取代原本EnvironmentSimulator裡
// SMOKE_SPREAD_PROB_FIREDOOR/NORMALDOOR、SMELL_SPREAD_PROB_FIREDOOR/
// NORMALDOOR這四個「每tick穿越機率」常數(擲骰子決定通不通，沒有文獻依據)。
//
//   Q_leak(m³/s) = Cd × A_leak × sqrt(2·ΔP / ρ_air)
//
// 這裡把Q_leak換算成一個「相對於完全暢通通道」的連續流量比例(0~1)——
// 見PhysicalConstants.FIREDOOR_LEAKAGE_FACTOR/NORMALDOOR_LEAKAGE_FACTOR的
// 推導——直接乘進既有的Fick's Law擴散/衰減架構(EnvironmentSimulator的
// diffuseAndVentilateSmoke()、以及CO/熱/煙味的鄰格擴散迴圈)，取代原本
// 「先擲骰子決定這個tick通不通、通了就整條全開」的離散事件判定。這不是
// 額外加一層獨立的「是否穿越」判定，而是讓門縫變成擴散通量上的一個連續
// 衰減倍率，跟牆/出口的no-flux邊界、開放格的100%暢通，共用同一套通量
// 計算路徑。
//
// ΔP(壓差)簡化假設：本模擬尚未有完整的動態壓力場(§10)，這裡沿用
// PhysicalConstants.FIREDOOR_TEST_PRESSURE_PA(UL1784測試壓差24.9Pa)當作
// 「門兩側都用同一個固定測試壓差」的簡化保守假設，而不是逐tick依煙霧/CO/
// 溫度動態反推壓力場——這個簡化的直接效果，是讓leakageFactor()變成一個
// 跟ΔP大小無關、只反映「洩漏面積相對於完全開口面積」的固定比例常數
// (見PhysicalConstants §14 FIREDOOR_LEAKAGE_FACTOR/NORMALDOOR_LEAKAGE_FACTOR
// 的推導)。未來若要接上真正的動態壓力場，可以改用leakageFlowM3PerS()這個
// 保留下來的孔口公式本體，用逐tick算出的ΔP取代這裡的簡化假設。
// ═══════════════════════════════════════════════════════════════════════════
final class DoorLeakageModel {
    private DoorLeakageModel() {}

    /**
     * 這一格(若是門)對煙霧/煙味/CO/熱擴散通量的「暢通比例」，範圍[0,1]：
     *   - 非Door/FireDoor格型：1.0(既有假設，完全暢通；牆/出口屬於no-flux
     *     邊界，由呼叫端另外判斷排除，這裡不重複處理)
     *   - 已被燒穿卡死(blocked=true)：1.0(門本身已經被燒穿失去阻隔能力，
     *     形同無阻隔的開口，不再是門縫滲漏的量級)
     *   - 防火門且isOpen=true(門實際上開著)：1.0(不是門縫滲漏，是真的開著)
     *   - 防火門(關閉狀態，未blocked)：PhysicalConstants.FIREDOOR_LEAKAGE_FACTOR
     *   - 一般門(非防火門，未blocked)：PhysicalConstants.NORMALDOOR_LEAKAGE_FACTOR
     */
    static double leakageFactor(Obj o) {
        if (o instanceof FireDoor) {
            FireDoor fd = (FireDoor) o;
            if (fd.blocked || fd.isOpen) return 1.0;
            return PhysicalConstants.FIREDOOR_LEAKAGE_FACTOR;
        }
        if (o instanceof Door) {
            Door d = (Door) o;
            if (d.blocked) return 1.0;
            return PhysicalConstants.NORMALDOOR_LEAKAGE_FACTOR;
        }
        return 1.0;
    }

    /**
     * A、B兩格之間，這條擴散連結要用的暢通比例。只要有一側是「未被突破的
     * 關閉狀態門」，通量就要依那一側的leakageFactor()打折；兩側都不是門
     * (或門已breach/開啟)則回傳1.0，不影響既有的擴散行為。理論上不會出現
     * 兩側同時都是門的情況，但保險起見取兩側較嚴格(較小)的一個，避免任一
     * 側的門被意外忽略。
     */
    static double linkFactor(Obj a, Obj b) {
        return Math.min(leakageFactor(a), leakageFactor(b));
    }

    /**
     * 孔口流量公式本體：Q_leak(m³/s) = Cd × A_leak × sqrt(2ΔP/ρ_air)。
     *
     * 目前EnvironmentSimulator不直接呼叫這個方法算絕對流量——現有架構改用
     * linkFactor()算出的「相對於完全暢通通道」比例形式，直接整合進Fick's
     * Law擴散通量。保留這個方法有兩個用途：(1)讓PhysicalConstants §14
     * FIREDOOR_LEAKAGE_FACTOR/NORMALDOOR_LEAKAGE_FACTOR的推導過程能在程式碼
     * 裡被直接驗證追溯，而不是只存在註解文字裡；(2)未來若要接入真正的動態
     * 壓力場(§10)，可以直接呼叫這裡，用逐tick算出的ΔP取代目前的固定測試
     * 壓差簡化假設。
     */
    static double leakageFlowM3PerS(double dischargeCoefficient, double leakageAreaM2, double deltaPPa) {
        if (leakageAreaM2 <= 0 || deltaPPa <= 0) return 0.0;
        return dischargeCoefficient * leakageAreaM2
            * Math.sqrt(2.0 * deltaPPa / PhysicalConstants.AIR_DENSITY_KG_M3);
    }
}
