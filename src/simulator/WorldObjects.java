package simulator;

// ─── 基礎物件 ────────────────────────────────────────────────
// 【校正清單§2/§5】原本smoke這一個0~1的數字同時身兼「能見度判斷」「CO中毒判斷」
// 「ASET判斷」「People有沒有察覺」四件事的依據，但現實中這些分別對應消光係数
// (能見度)、氣體濃度(毒性)三種不同的物理量。校正後拆成兩條各自有意義的欄位：
//   - smoke：保留下來專職「能見度危害代理量」，繼續驅動LOS視野遮蔽/偵測器讀數/
//     ASET判定(這些本來就是能見度相關的用途)，並提供 approxVisibilityMeters()
//     做一個粗略的「大約幾公尺」換算(見PhysicalConstants §2)。
//   - coPpm：新增，專職「CO濃度(ppm)」，供People.absorbCO()的Purser FED_CO
//     劑量模型使用，取代原本直接把smoke累加當作CO劑量的做法。
class Obj {
    boolean fire;

    // 【新增，校正清單§2/§3】這一格「真正著火」的那個tick，-1代表尚未著火。
    // 由EnvironmentSimulator在把某格點燃(fire=true)的當下設定，供該格自己的
    // Q(t)=α·t²(HRR)成長曲線計算「從這一格開始燒起，已經過了多久」使用——
    // 用「這一格自己的著火時刻」而不是全域tick，讓火勢後來才延燒到的格子
    // 也有自己合理的、從0開始爬升的HRR成長曲線，而不是繼承全域已經燒了很久
    // 的強度。
    int ignitedAtTick = -1;

    // 煙霧「能見度危害」代理量 (0.0 ~ 1.0)：不是真正的消光係数，只是一個標準化過的
    // 指標，用來驅動視野遮蔽(VisionSystem)、偵測器讀數(Detector)、ASET判定
    // (EnvironmentSimulator.allOccupiableCellsUntenable)。CO中毒判斷已改用下面
    // 的coPpm欄位，不再共用這個數字(見校正清單§2/§5)。
    double smoke = 0.0;

    // 煙味數值 (0.0 ~ 1.0)：無害，純粹用來觸發People的「察覺」，
    // 比smoke更容易穿透門縫(見EnvironmentSimulator頂端的擴散機率常數)
    double smell = 0.0;

    // 【新增，校正清單§5，生成公式已於§2/§3更新】一氧化碳濃度 (ppm)：由
    // EnvironmentSimulator依起火原因對應的代表性燃料(FireCause.representativeFuel)
    // 與當下通風狀態算出的CO生成速率在火源格生成、並向外擴散/稀釋，
    // People.absorbCO()用它搭配呼吸每分鐘通氣量(RMV)算Purser FED_CO劑量。
    double coPpm = 0.0;

    // 【新增，校正清單§10/§11】這一格的氣體溫度(°C)，預設為常溫
    // PhysicalConstants.AMBIENT_TEMP_C。由EnvironmentSimulator依火源格的
    // 對流HRR用「控制體積絕熱升溫」公式生成，並用跟smoke一樣的距離衰減方式
    // 擴散，自然衰減(混合冷空氣)則沿用跟CO一樣的VentilationProfile指數衰減，
    // 只是衰減目標是AMBIENT_TEMP_C而不是0。供radiantHeatFluxKwM2()與四判据
    // ASET判定使用(見EnvironmentSimulator.allOccupiableCellsUntenable())。
    double tempC = PhysicalConstants.AMBIENT_TEMP_C;

    // 【新增，校正清單§10/§11】從溫度場反推輻射熱通量(kW/m²)，不另外開新場：
    // q" = σ·ε·(T_cell⁴ − T_ambient⁴)，Stefan-Boltzmann定律，T以絕對溫度(K)
    // 代入，ε=SMOKE_LAYER_EMISSIVITY是含煙氣層的輻射係数假設。這是簡化的
    // 「氣體/煙層黑體輻射」近似，不是嚴謹的視角係数(view factor)輻射傳遞模型。
    double radiantHeatFluxKwM2() {
        double tKelvin = tempC + 273.15;
        double ambientKelvin = PhysicalConstants.AMBIENT_TEMP_C + 273.15;
        double qWattsM2 = PhysicalConstants.STEFAN_BOLTZMANN_W_M2_K4 * PhysicalConstants.SMOKE_LAYER_EMISSIVITY
            * (Math.pow(tKelvin, 4) - Math.pow(ambientKelvin, 4));
        return Math.max(0.0, qWattsM2) / 1000.0; // W/m² -> kW/m²
    }

    // 【新增，校正清單§1/§12】本tick這一格(僅Door/Exit有意義)剩餘可通行人數，
    // 由DoorFlowModel每tick重置/消耗，近似「門/出口通過流量上限」造成的瓶頸排隊。
    int doorFlowRemaining = Integer.MAX_VALUE;

    // 【新增，校正清單§2】把0~1的smoke換算成一個粗略的「大約幾公尺」能見度，
    // 只用於顯示/報表參考，不是嚴謹的光學/煙層模型(細節見PhysicalConstants)。
    double approxVisibilityMeters() {
        double k = Math.max(smoke, 0.02) * PhysicalConstants.VISIBILITY_K_AT_SMOKE_1;
        return PhysicalConstants.VISIBILITY_C_REFLECTIVE / k;
    }
}

class Door extends Obj {
    boolean blocked = false; // true 代表已被火封住

    // 【新增，校正清單§7】這道門第一次被鄰近火勢「攻擊」(嘗試突破)的tick，
    // -1代表尚未被攻擊過。搭配PhysicalConstants的耐火時效/Weibull形狀參數，
    // 在EnvironmentSimulator.weibullBreachProbability()算出「這道門已經扛了
    // 多久，這個tick失效的機率是多少」，取代原本不管扛了多久都固定機率的模型。
    int fireExposureStartTick = -1;
}

class FireDoor extends Door {
    // 防火門若沒有確實關好(被卡住/未關緊)，就會失去阻絕火勢延燒的效果，
    // 行為上會退化成跟普通門一樣：擋不住火，也可能被燒到卡死(blocked)。
    boolean isOpen = false;
}

class Exit extends Obj {}
class Wall extends Obj {}
class Floor extends Obj {}

class Stage extends Obj {
    Stage upfloor;
    Stage downfloor;
    int x, y, z;

    // 【修正2：樓梯間防火隔間】這座樓梯在「本層」的防護入口門，由BuildingGenerator
    // 在生成樓梯時一併建立(見enclosePassage())。EnvironmentSimulator做跨樓層
    // (煙/煙味/CO/熱/火)傳播時，改成先檢查這道門有沒有被突破，取代原本「無條件
    // 直接傳到下一層樓梯格」的作法。可能為null(極端擁擠、放不下門的地圖)，
    // 這種情況下維持原本「無防護」的行為當保底。
    Door enclosureDoor;

    public void setLocation(int z, int y, int x) {
        this.z = z; this.y = y; this.x = x;
    }
}

//─── 人員屬性列舉 ──────────────────────────────────────────────
enum PersonProfile {
    NORMAL_SOLO,     // 一般成年人，單獨行動
    WITH_CHILD,      // 成年人，攜帶幼童
    IMPAIRED,        // 行動不便者
    ELDERLY,         // 年長者
    STAFF,           // 對場域熟悉的員工
    CUSTOMER         // 對場域完全陌生的顧客
}
