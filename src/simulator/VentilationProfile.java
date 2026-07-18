package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// VentilationProfile — 通風換氣率(ACH)資料結構(校正清單§12基礎架構)。
//
//   【校正清單§2/§3，已接入】EnvironmentSimulator.spread()現在用
//   decayFactorPerTick()算真正的指數稀釋衰減，取代原本固定的
//   CO_AMBIENT_DECAY=0.985；achCurrent依「火源格鄰接是否有開放通道(門/出口)」
//   在ACH_CLOSED_ROOM / ACH_PARTIALLY_OPEN之間切換(仍是格點鄰接層級的簡化
//   判斷，不是依真實房間邊界劃分的通風分區)。
//
//   Q = ACH × Volume / 3600 的稀釋氣流量公式，供之後接入真實的CO稀釋/
//   質量平衡計算。
// ═══════════════════════════════════════════════════════════════════════════
class VentilationProfile {

    // ─── 幾組常見情境的ACH量級，供之後動態切換/初始化使用 ────────────
    // (常數本體集中放在PhysicalConstants，跟其他校正常數放在一起管理)
    static final double CLOSED_ROOM = PhysicalConstants.ACH_CLOSED_ROOM;                     // 密閉室內，門窗緊閉
    static final double PARTIALLY_OPEN = PhysicalConstants.ACH_PARTIALLY_OPEN;               // 門窗部分開啟
    static final double OPEN_ROOM = PhysicalConstants.ACH_OPEN_ROOM;                         // 門窗大開/自然通風良好
    static final double STAIR_OR_SMOKE_CONTROL = PhysicalConstants.ACH_STAIR_OR_SMOKE_CONTROL; // 樓梯間/加壓排煙系統

    /** 目前的每小時換氣次數(1/hr)。 */
    double achCurrent;

    VentilationProfile(double achCurrent) {
        this.achCurrent = achCurrent;
    }

    /** 預設建構子：以CLOSED_ROOM(密閉室內，通風不良)當作保守初始值。 */
    VentilationProfile() {
        this(CLOSED_ROOM);
    }

    /**
     * 計算稀釋氣流量 Q(m³/s)。
     * 公式：Q = ACH × Volume / 3600
     *   ACH：每小時換氣次數(1/hr)
     *   Volume：房間體積(m³)，建議用Space.getFloorVolumeM3()等真實體積，
     *           不要用格數體積(Space.getBuildingVolumeCells())。
     *   3600：把「每小時」換算成「每秒」的單位轉換。
     *
     * @param roomVolumeM3 房間真實體積(m³)
     * @return 氣流量(m³/s)
     */
    double getAirFlowRate(double roomVolumeM3) {
        return achCurrent * roomVolumeM3 / 3600.0;
    }

    /**
     * 【新增，校正清單§2/§3】單一tick的濃度自然稀釋衰減係數(0~1)。
     *
     * 推導：完全混合單一控制體積(V)的質量平衡ODE：V·dC/dt = -Q·C
     * (無外部生成時)，其中Q=ACH×V/3600(見getAirFlowRate())。解出：
     *   C(t+dt) = C(t) × exp(-Q·dt/V) = C(t) × exp(-(ACH/3600)·dt)
     * 注意Q正比於V，V在化簡後會消掉，所以衰減係數其實跟房間體積無關，
     * 只跟ACH與經過的時間(秒)有關——這也是為什麼這個方法不需要體積參數。
     *
     * @param dtSeconds 這次要衰減的時間(秒)，通常代入PhysicalConstants.TICK_SECONDS
     * @return 衰減係數，乘上原本的濃度即為衰減後的濃度
     */
    double decayFactorPerTick(double dtSeconds) {
        return Math.exp(-(achCurrent / 3600.0) * dtSeconds);
    }
}
