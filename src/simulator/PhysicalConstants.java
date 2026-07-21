package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// PhysicalConstants — 模擬單位校正清單 §0：tick / block 與現實物理量的換算基準，
//   以及後續各節(§1 速度、§5 CO/FED、§1&§12 門流量瓶頸、§6 火災成長曲線、§2 能見度)
//   校正時共用的常數，集中在這裡管理，避免散落各處各自定義、互相對不起來。
//
//   ⚠️ §0 的重點：block=0.4m、tick=1s 是「反推法」選出的一組合理預設值，
//   不是唯一解，但一旦選定，後面所有速度/擴散/生成速率的校正都以此為準。
//   若之後要改用別組(例如 block=0.5m)，只需要改這裡，其餘公式都是相對這組基準
//   校正出來的，不用整個重算。
// ═══════════════════════════════════════════════════════════════════════════
final class PhysicalConstants {
    private PhysicalConstants() {}

    // ─── §0 基礎單位換算 ─────────────────────────────────────────
    // block=0.4m：元胞自動機(CA)疏散模型慣例網格尺寸，約等於一人站立所需的
    // 平面投影面積(Kirchner & Schadschneider 2002、Blue & Adler)。
    // tick=1s：配合上面的block，讓 NORMAL_SOLO 的 3 block/tick 換算成 1.2 m/s，
    // 剛好對上 SFPE Handbook / Fruin(1971) 最常見的保守設計疏散速度。
    static final double BLOCK_METERS = 0.4;
    static final double TICK_SECONDS = 1.0;

    static double blocksPerTickToMetersPerSecond(double blocksPerTick) {
        return blocksPerTick * BLOCK_METERS / TICK_SECONDS;
    }

    // ─── §12 樓層垂直高度(真實體積計算基礎) ─────────────────────────
    // block=0.4m只描述平面尺寸，缺少垂直方向的真實高度，導致目前
    // 「height*rows*cols」只是格數(cell count)，不是真實體積(m³)。
    // 這裡先補上樓層淨高的常見量級，作為之後計算樓地板面積/樓層體積/
    // 建築總體積(m²/m³)的基礎，供Space的getFloorAreaM2()/getFloorVolumeM3()/
    // getBuildingVolumeM3()使用。目前僅提供計算能力，尚未接入火災/CO模擬流程。
    static final double FLOOR_HEIGHT_METERS = 3.0;        // 一般樓層淨高(常見辦公/住宅樓層量級)
    static final double GROUND_FLOOR_HEIGHT_METERS = 3.6; // 一樓挑高(常見量級，未來可依需要使用)

    // ─── §1 樓梯 vs. 平面移動速度 ───────────────────────────────
    // 現實中樓梯下樓速度普遍比平面走廊慢 30%~60%(上樓更慢)；取中間值，
    // 也就是樓梯速度只剩平面速度的 55%。People 依角色卡設定 speedFloor 後，
    // speedStage = max(1, round(speedFloor * STAIR_SPEED_FACTOR))。
    static final double STAIR_SPEED_FACTOR = 0.55;

    // ─── §1 / §12 門與出口的通過流量上限(瓶頸) ─────────────────────
    // 現實中門的通過流量存在上限，常見引用值約 1.2–1.5 人/(公尺·秒)；
    // 門寬取一個block寬(=BLOCK_METERS)、tick=1秒，換算出的理論容量只有
    // 0.48–0.6 人/tick，遠小於1。但模擬是離散的「每tick最多放行整數人」模型，
    // 無法放行「0.5個人」，因此離散化時取 max(1, round(...))：這代表在目前的
    // 時間/空間粒度下，瓶頸模型至少允許每tick通過1人，若聚集人數多於這個
    // 容量，多出來的人這tick會被視為「門暫時不可通行」，需要排隊等下個tick，
    // 藉此近似現實中「門寬限制流量、造成排隊壅塞」的現象，而不是精確還原
    // 連續流量公式。
    static final double DOOR_FLOW_RATE_PER_METER_SECOND = 1.2;
    static final int DOOR_CAPACITY_PER_TICK = Math.max(1,
        (int) Math.round(DOOR_FLOW_RATE_PER_METER_SECOND * BLOCK_METERS * TICK_SECONDS));

    // ─── §5 CO 吸入劑量：Purser FED_CO 模型 ────────────────────────
    // %COHb(t) = Σ [ 3.317×10⁻⁵ × [CO]^1.036 × RMV × Δt ]，[CO]單位ppm，
    // RMV(呼吸每分鐘通氣量)單位L/min，Δt單位分鐘，結果是「血中碳氧血紅蛋白
    // 濃度(%COHb)」的增量。FED_CO = %COHb / D，D是「個人達到失能/致死所需的
    // %COHb臨界值」，代表個人易感度，見People.coSusceptibilityD——
    // 這裡把「起火原因造成的煙毒性差異」移到CO的生成速率上(見FireCause.
    // representativeFuel + FireChemistry/FuelType)，D只反映個人體質，不再像
    // 原本的smokeToleranceFactor那樣直接乘進個人閾值，符合校正清單§5的建議。
    static final double FED_CO_CONST = 3.317e-5;
    static final double FED_CO_EXPONENT = 1.036;
    static final double FED_INCAPACITATION = 0.3; // FED_CO達到0.3視為失能門檻(校正清單§5/§10)
    static final double FED_LETHAL = 1.0;          // FED_CO達到1.0視為致死劑量(對應個人%COHb臨界值)

    // 【新增，回應審查意見：ASET四判据的CO判定應改用累積FED而非瞬時IDLH門檻】
    // 健康成年人的預設易感度D，跟People.java建構子裡對一般人群設的
    // coSusceptibilityD=40.0是同一個量級/同一份文獻假設，供
    // EnvironmentSimulator.spread()計算「這個位置」的代表性fedCoCell使用
    // (見WorldObjects.Obj.fedCoCell)。刻意不取兒童/老人等易感族群較低的D值，
    // 因為這是環境本身的危害嚴重度判定，不是特定某個人的暴露史。
    static final double CO_SUSCEPTIBILITY_D_DEFAULT = 40.0;

    // ─── §5b 熱暴露致死劑量：Purser對流熱耐受時間模型 ────────────────────
    // 【修正】原本「踩到火格=這個tick立刻死亡」是跟現實脫節的硬編碼判定：文獻
    // 上火災死亡平均只有約20~40%是燒傷致死，多數其實死於CO/煙霧吸入，但「碰到
    // 火=100%瞬間死」會讓燒死比例被嚴重高估。改成跟fedCO同一套「累積劑量」
    // 邏輯：用當下所在位置的氣體溫度(tempC，已經由EnvironmentSimulator依HRR
    // 成長曲線逐tick算出，见WorldObjects.tempC)算出這個溫度下人體的耐受時間，
    // 每經過一個tick就把「這個tick佔耐受時間的比例」累加進fedThermal，達到
    // FED_THERMAL_LETHAL(=1.0)才視為燒死，而不是「一沾到火」就無條件判死。
    //
    // 耐受時間公式(Purser，收錄於SFPE Handbook / ISO 13571)：
    //   t_I(分鐘) = exp(5.1849 − 0.0273×T)，T為對流氣體溫度(°C)，
    //   常見引用適用範圍約80~150°C(輕便衣物下皮膚疼痛/失能的耐受時間)；
    //   溫度越高t_I越短，外推到火焰直接包覆的高溫(數百°C以上)時t_I趨近於
    //   秒等級甚至更短，對應「真的被火焰包住幾乎必死無疑」的物理直覺——
    //   但死亡與否取決於「溫度隨時間如何變化」這個連續過程，不是「碰到火格」
    //   這個離散事件本身。
    static final double FED_THERMAL_TOLERANCE_A = 5.1849;
    static final double FED_THERMAL_TOLERANCE_B = 0.0273;
    static final double FED_THERMAL_LETHAL = 1.0; // 跟FED_CO一樣，達到1.0視為致死劑量

    // 【修正，校正清單問題4】Purser公式實證範圍約80~150°C，超出這個範圍屬於
    // 數學外推，沒有實證支持。溫度場全盛期可到COMPARTMENT_PEAK_GAS_TEMP_C(850°C)，
    // 若讓指數公式無界外推，t_I在數學上仍「合理」(趨近於0)，但這個極短耐受時間
    // 只是外推假象，不是真的有實驗量到這個數字。
    // 做法：超過THERMAL_TOLERANCE_EXTRAPOLATION_CAP_C(200°C，比公式驗證上限
    // 150°C留一點緩衝)之後，不再用公式算t_I，直接視為「近乎瞬間失能/致死」——
    // 用THERMAL_TOLERANCE_FLOOR_MINUTES(公式在200°C時算出的t_I，約0.76分鐘/
    // 46秒)當作耐受時間下限，數值上跟外推結果相近，但邏輯上是「明確判定」
    // 而非「指數公式無限外推」，見People.absorbThermal()。
    static final double THERMAL_TOLERANCE_EXTRAPOLATION_CAP_C = 200.0;
    static final double THERMAL_TOLERANCE_FLOOR_MINUTES =
        Math.exp(FED_THERMAL_TOLERANCE_A - FED_THERMAL_TOLERANCE_B * THERMAL_TOLERANCE_EXTRAPOLATION_CAP_C);

    // 呼吸每分鐘通氣量(RMV, L/min)：靜止~輕度活動落在文獻常見的6~25範圍，
    // 劇烈活動/恐慌可達50以上；這裡依panicLevel在輕度活動與劇烈活動間內插。
    static final double RMV_RESTING = 6.0;
    static final double RMV_LIGHT_ACTIVITY = 25.0;
    static final double RMV_HEAVY_ACTIVITY = 50.0;

    // ─── §2/§3 CO生成：完整HRR→燃料→通風稀釋鏈 ────────────────────────
    // 【校正清單§2/§3，取代原本的CO_BASE_GENERATION_PPM_PER_TICK簡化代理】
    // 現在每個火源格依「這一格實際著火多久」(Obj.ignitedAtTick)算Q(t)=α·t²的
    // HRR(kW，見FireCause.growthCurve)，換算質量損失率/CO生成速率(見
    // FireChemistry+FuelType)，再用該格的真實體積(見Space.getCellVolumeM3())
    // 與標準狀態下的ppm↔質量濃度換算式，把「這一tick生成的CO質量」換算成
    // ppm增量，取代原本跟時間/幾何完全脫鉤的固定基礎值。
    //
    // CO_MOLAR_MASS_G_PER_MOL / MOLAR_VOLUME_L_PER_MOL_25C：標準空氣品質換算式
    // C(mg/m³) = ppm × M / 24.45 (25°C、1 atm下，理想氣體莫耳體積≈24.45 L/mol)，
    // 這裡反推 ppm = (mg/m³) × 24.45 / M。假設室溫/常壓，火場實際溫度更高、
    // 莫耳體積會更大，此為簡化假設。
    static final double CO_MOLAR_MASS_G_PER_MOL = 28.01;
    static final double MOLAR_VOLUME_L_PER_MOL_25C = 24.45;

    // HRR成長曲線Q(t)=α·t²理論上會無限成長，現實中會因通風/燃料耗盡而在
    // 某個高原期封頂。沒有完整燃料負載/開口面積模型可算Kawagoe通風控制封頂值
    // 之前，先用SFPE Handbook常見引用的「數件家具/中型可燃物房間全盛期HRR」
    // 量級(約1~10MW)取中間值當保守封頂，避免t²無界發散。
    static final double HRR_PEAK_CAP_KW = 5000.0;

    static final double CO_MAX_PPM = 60000.0;      // 上限(約6%)，避免無界累積，非嚴謹的物理飽和值

    // ─── §7 防火門/一般門的耐火時效存活分布 ────────────────────────
    // 【校正清單§7，取代原本的FIRE_SPREAD_PROB_FIREDOOR/NORMALDOOR固定機率】
    // 現實中防火門的耐火時效(fire-resistance rating)是通過標準加熱試驗
    // (如ISO 834/NFPA 252/UL 10C)得到的一個「額定分鐘數」，在額定時間內
    // 應能持續阻絕火勢，接近/超過額定時間後失效機率才快速上升，不是每個
    // tick都固定機率突破。這裡用Weibull存活分布 S(t)=exp(-(t/λ)^k) 模擬，
    // λ取額定時間(特徵壽命)，k(形狀參數)>1代表「隨曝火時間增加、失效風險
    // 遞增」；k越大代表在額定時間前後的失效機率曲線越陡(越接近標準試驗
    // 「到時間才失效」的行為)。
    //   - FIREDOOR_RATING_MINUTES：常見商用/防火區劃防火門額定時效下緣(60分鐘)
    //   - DOOR_UNRATED_EQUIVALENT_MINUTES：一般非防火門(中空夾板門等)在真實
    //     火場中的概略耐火時間，抓文獻/消防常引用的量級(10~20分鐘)之中間值
    //   - WEIBULL_SHAPE_FIREDOOR較高：防火門是通過標準試驗認證的構造，失效
    //     行為更接近「額定時間到才失效」；WEIBULL_SHAPE_NORMALDOOR較低：
    //     一般門構造/密封更不一致，失效時間分散度較大
    static final double FIREDOOR_RATING_MINUTES = 60.0;
    static final double DOOR_UNRATED_EQUIVALENT_MINUTES = 15.0;
    // ⚠️無文獻對應：以下兩個形狀參數(k)在目前搜尋範圍內找不到可直接引用的
    // 出處，純屬工程判斷(方向合理——防火門經標準試驗認證，失效行為更接近
    // 「額定時間到才失效」，形狀曲線應較陡；一般門構造/密封不一致，分散度
    // 較大，形狀曲線應較平)，但4.0/2.0這兩個具體數值沒有實測失效機率分布
    // 校準過。若要更嚴謹，應查BS EN 1634 / ISO 834相關失效機率研究反推，
    // 目前先維持此工程假設值。
    static final double WEIBULL_SHAPE_FIREDOOR = 4.0;
    static final double WEIBULL_SHAPE_NORMALDOOR = 2.0;

    // ─── §9 人流密度對移動速度的抑制(Fruin/SFPE基本圖) ──────────────────
    // 【校正清單§9】現實中步行速度隨人流密度增加而下降，SFPE Handbook
    // (Nelson & Mowrer, "Emergency Movement"一章，數據源自Fruin(1971)/
    // Predtechenskii-Milinskii)給出走廊/樓梯的線性近似 S = k1 − k2·D，
    // D為人流密度(人/m²)，S為步行速度(m/s)，超過某密度後S趨近於0(壅塞)。
    // 這裡取樓梯係數(k1較低、下樓)與走廊係數兩組常見引用值；CROWD_JAM_MIN_MPS
    // 是純粼因人流擁擠導致的最低速度下限(避免單純人多就完全卡死，跟恐慌
    // rollFreeze()代表的「心理愣住」是不同機制，兩者分開建模)。
    static final double FRUIN_CORRIDOR_K1 = 1.40;
    static final double FRUIN_CORRIDOR_K2 = 0.37;
    static final double FRUIN_STAIR_K1 = 1.00;
    static final double FRUIN_STAIR_K2 = 0.263;
    static final double CROWD_JAM_MIN_MPS = 0.3;
    static final int CROWD_DENSITY_RADIUS_BLOCKS = 1; // 3x3鄰域(含自己)算局部密度

    // ─── §9 準備時間(pre-movement time)：對數常態分布 ──────────────────
    // 【校正清單§9，取代原本只有WITH_CHILD才有的「3+隨機0~5tick」ad hoc延遲】
    // 文獻(BS PD 7974-6、SFPE Handbook、Purser & Bensilum等)普遍以對數常態
    // 分布描述「察覺異常」到「真正開始朝出口行動」之間的準備時間，具體中位數
    // 依建築複雜度/警報型態/人員熟悉度而異；這裡依角色卡給出中位數(秒)與
    // 對數標準差，量級取常見引用範圍的中段，細節見PanicModel.samplePremovementTicks()。

    // ─── §10/§11 溫度擴散場 + 四判据ASET ────────────────────────────
    // 【校正清單§10/§11】原本ASET只看smoke(能見度代理量)單一判据，且只要
    // smoke>0.7就視為這格陷入危險，比SFPE Handbook/ISO 13571建議的「熱暴露/
    // 輻射熱通量/毒性/能見度」四判据分析明顯保守(容易偏短)。這裡新增：
    //   1. 溫度擴散場(Obj.tempC)：用「控制體積絕熱升溫」公式生成，仿照smoke
    //      的擴散/衰減機制傳播(見EnvironmentSimulator)。
    //   2. 輻射熱通量：不額外開新場，直接從溫度場用Stefan-Boltzmann定律
    //      反推(見Obj.radiantHeatFluxKwM2())。
    //   3. ASET改成四判据(依【使用者決定】採AND邏輯：四項全部達標才視為
    //      陷入危險)——這跟ISO 13571標準做法(任一判据達標即視為陷入危險，
    //      OR邏輯，較保守)不同，是刻意選擇讓ASET更長、更貼近「真實火災
    //      通常是好幾分鐘」的量級，見allOccupiableCellsUntenable()註解。
    //
    // 溫度生成公式：ΔT = (對流熱釋放率×Δt) / (ρ_air × cp_air × V_cell)
    //   對流熱釋放率 = CONVECTIVE_FRACTION_OF_HRR × HRR(kW)，SFPE Handbook
    //   常見拆分：總HRR約7成走對流(加熱氣體)、3成走輻射，這裡只取對流部分
    //   驅動氣體溫度上升(輻射部分則另外反推熱通量，見上)。
    //   ρ_air(空氣密度)、cp_air(空氣比熱)為常溫常壓下的標準空氣物性；
    //   V_cell沿用Space.getCellVolumeM3()，跟CO生成鏈共用同一個「控制體積」
    //   假設(§2/§3)。
    static final double AMBIENT_TEMP_C = 20.0;
    static final double CONVECTIVE_FRACTION_OF_HRR = 0.7;
    static final double AIR_DENSITY_KG_M3 = 1.2;
    static final double AIR_SPECIFIC_HEAT_KJ_PER_KG_K = 1.005;

    // ─── §10/§11修正：溫度改成準穩態代數模型，取代會無界疊加的絕熱升溫公式 ──
    // 【修正】原本溫度場每個tick把當下HRR換算出的ΔT直接累加到上一tick殘留
    // 溫度上，降溫只靠跟CO共用的ACH_CLOSED_ROOM(0.5次/小時，是為了CO在數十
    // 分鐘~數小時尺度下緩慢稀釋而設計的常數)——但套用在單一tick(=1秒)、體積
    // 只有單人站立空間量級(見Space.getCellVolumeM3())的「氣體溫度」上完全
    // 不成比例：實測起火後不到1分鐘溫度就衝上數百度，3分半鐘衝上三萬多度，
    // 遠遠脫離現實(現實室內火場全盛期氣體溫度落在800~1200°C量級，不會無限
    // 往上疊加)，也讓「燒死」在死因統計裡嚴重失真地壓過CO中毒死亡。
    //
    // 根本原因：這麼小的控制體積，熱容量小到可以忽略，氣體溫度幾乎是「瞬時」
    // 跟著當下HRR(t)與熱損失(對外輻射+氣流帶走的熱)打平，達到準穩態，而不是
    // 像大空間那樣需要數十分鐘慢慢累積/消散。因此改成每個tick直接用當下HRR
    // 重新算一次準穩態溫度(不再從上一tick的殘留溫度往上疊加)，從根本消除
    // 無界累積：
    //   ΔT_ss(°C) = 對流HRR(kW) ÷ 熱損失係数(kW/°C)
    //   T(°C) = AMBIENT_TEMP_C + ΔT_ss
    // 熱損失係数校正：取「HRR達到HRR_PEAK_CAP_KW(全盛期封頂)時，溫度應落在
    // COMPARTMENT_PEAK_GAS_TEMP_C(現實文獻常見的火場全盛期氣體溫度量級，
    // 對照ISO 834標準升溫曲線長時間漸近值約1000~1200°C)這一點」反推。取
    // 800~1200°C這個常見範圍中偏保守(較低)的一端，一方面Purser對流熱耐受
    // 時間公式(見下方FED_THERMAL_TOLERANCE_A/B)本身的實證適用範圍只到約
    // 150~200°C，外推到極端高溫時本來就只是概略量級；另一方面偏保守的取值
    // 也讓「燒死」不會單靠溫度封頂值的選擇就系統性蓋過CO中毒死亡。
    // 【AIR_DENSITY_KG_M3/AIR_SPECIFIC_HEAT_KJ_PER_KG_K不再用於這個公式，
    // 保留常數是因為仍是有意義的標準空氣物性數值，未來若要重新引入真正的
    // 熱慣性(暫態)模型可以再用到。】
    static final double COMPARTMENT_PEAK_GAS_TEMP_C = 850.0;
    static final double HEAT_LOSS_COEFFICIENT_KW_PER_C =
        (HRR_PEAK_CAP_KW * CONVECTIVE_FRACTION_OF_HRR) / (COMPARTMENT_PEAK_GAS_TEMP_C - AMBIENT_TEMP_C);

    // Stefan-Boltzmann輻射公式：q"(kW/m²) = σ·ε·(T_cell⁴ − T_ambient⁴)，
    // T以絕對溫度(K)代入。ε(SMOKE_LAYER_EMISSIVITY)=0.8是含煙氣層常見的
    // 輻射係数假設(SFPE Handbook常見範圍0.7~1.0，含碳黑濃煙偏高)。
    static final double STEFAN_BOLTZMANN_W_M2_K4 = 5.67e-8;
    static final double SMOKE_LAYER_EMISSIVITY = 0.8;

    // 四判据untenable門檻(SFPE Handbook / ISO 13571常見引用值)：
    //   - 溫度>120°C：對流熱暴露達到數分鐘內失能的常見門檻
    //   - 輻射熱通量>2.5 kW/m²：數秒內產生疼痛感的常見門檻
    //   - CO>1200ppm：NIOSH IDLH(Immediately Dangerous to Life or Health)
    //     濃度，這裡用「瞬時濃度門檻」簡化，不是完整FED累積劑量(累積劑量
    //     版本已經在People.absorbCO()/fedCO對「個人」實作，這裡是給「建築
    //     整體ASET」用的簡化環境判据，兩者用途不同，見allOccupiableCellsUntenable())
    //   - smoke>0.7：沿用既有能見度代理量門檻，不再變動
    static final double TEMP_UNTENABLE_C = 120.0;
    static final double RADIANT_HEAT_FLUX_UNTENABLE_KW_M2 = 2.5;
    static final double CO_UNTENABLE_PPM = 1200.0;

    // ─── §2 能見度換算(近似，僅供顯示/報表參考) ──────────────────────
    // 能見度 = C / K(ISO 13571 / SFPE Handbook)，C為反光標示物常數(≈3)。
    // smoke(0~1)不是真正的消光係数K，這裡假設 smoke=1.0 時 K≈2.0 (1/m)
    // (中等濃煙)做一個粗略的線性對應，只用於把現有的0~1煙霧指標換算成一個
    // 「大約幾公尺」的直覺數字，不是嚴謹的光學/煙層模型。
    static final double VISIBILITY_C_REFLECTIVE = 3.0;
    static final double VISIBILITY_K_AT_SMOKE_1 = 2.0;

    // ─── §6 NFPA t²火災成長曲線分類，alpha值(kW/s²) ────────────────
    // 【校正清單§6，已接入§2/§3完整HRR鏈】EnvironmentSimulator.spread()現在
    // 用FireCause.growthCurve.alphaKwPerSecondSquared × (該格著火經過秒數)²
    // 算出Q(t)(kW，並用HRR_PEAK_CAP_KW封頂)，交給FireChemistry換算CO生成速率，
    // 不再只是文件對照用的metadata。
    static final double ALPHA_SLOW = 0.00293;
    static final double ALPHA_MEDIUM = 0.01172;
    static final double ALPHA_FAST = 0.0469;
    static final double ALPHA_ULTRAFAST = 0.1878;

    // ─── §12 通風換氣率(ACH, Air Changes per Hour)基礎架構 ──────────────
    // 【校正清單§12，已接入§2/§3】EnvironmentSimulator.spread()現在用
    // VentilationProfile.decayFactorPerTick()算真正的指數稀釋衰減(取代原本
    // 固定的CO_AMBIENT_DECAY=0.985)，並用「火源格鄰接是否有開放通道(門/出口)」
    // 這個簡化判斷，在ACH_CLOSED_ROOM/ACH_PARTIALLY_OPEN之間切換，間接決定
    // FuelType.getCoYield(underVented)要用哪一組CO產率。尚未做到「依房間幾何
    // 精確劃分獨立的通風區塊」，仍是格點鄰接層級的近似。
    // 量級參考：密閉室內通風不良約0.5 ACH；一般房間門窗部分開啟約2 ACH；
    // 門窗大開/自然通風良好約8 ACH；樓梯間或加壓排煙系統可達12 ACH以上。
    static final double ACH_CLOSED_ROOM = 0.5;
    static final double ACH_PARTIALLY_OPEN = 2.0;
    static final double ACH_OPEN_ROOM = 8.0;
    static final double ACH_STAIR_OR_SMOKE_CONTROL = 12.0;

    // ─── §13 煙霧模型重構：Fick's Second Law擴散 + 生成常數 ──────────────
    // 【煙霧模型重構，取代原本的smokeGrowth()/固定距離權重/樓梯固定增量】
    //
    // 關於Smoke Yield(Y_s)與Heat of Combustion(ΔHc)：這兩個量「已經」存在於
    // FuelType.java裡(分別是sootYield與heatOfCombustionMJPerKg)，且FuelType
    // 的sootYield欄位註解早已寫明是為了「之後能見度/煙層濃度模型使用」而準備
    // 的——這正是本次要做的事。因此這裡刻意不在PhysicalConstants另外重複定義
    // 一份全域SMOKE_YIELD/HEAT_OF_COMBUSTION常數：那樣會造成兩份互相打架的
    // 燃料資料(一份依燃料種類細分，一份是不分燃料的單一數字)，也違背Prompt
    // §十一「不可Smoke算一次、CO再算一次」的精神——CO跟煙霧本來就該共用同一份
    // 「燃料是什麼、燒得多快」的資料，而不是各自維護一份。實際使用見
    // EnvironmentSimulator.spread()：Y_s = fuel.sootYield，ΔHc已內含在
    // FireChemistry.getMassLossRate(hrrKw, fuel)裡。

    /**
     * 煙霧(soot)擴散係數 D，單位 m²/s。
     *
     * 真正的分子擴散係數(molecular diffusivity)對氣體/微粒而言只有約
     * 1e-5 m²/s量級，在室內火場的時間/空間尺度下幾乎可忽略——實際上煙霧
     * 在房間/走廊裡的傳播主要由熱浮力驅動的紊流(turbulent/eddy diffusion)
     * 主導，而非純分子擴散。這裡取消防工程/室內氣流模擬常引用的紊流渦流
     * 擴散係數量級(約0.01~0.1 m²/s)中段，作為這個簡化網格模型「有效擴散
     * 係数」的合理近似，取代原本完全沒有物理依據的固定距離權重。
     */
    static final double SMOKE_DIFFUSIVITY = 0.02;

    /**
     * 樓梯間煙囪效應(stack effect)係数，無因次。
     *
     * 樓梯間/豎井因為室內外溫差造成的浮力壓差(stack effect / chimney
     * effect，NFPA 92常見討論主題)，會讓煙霧沿垂直方向的傳播速度遠高於
     * 同層房間內單純的紊流擴散——這裡把樓梯間的垂直擴散係数表示成
     * D_z = STACK_COEFFICIENT × SMOKE_DIFFUSIVITY，STACK_COEFFICIENT取一個
     * 反映「樓梯間垂直傳播明顯快於同層水平擴散」但仍是同一套Fick擴散架構
     * 的放大倍率，量級參考樓梯間/加壓排煙系統ACH(ACH_STAIR_OR_SMOKE_CONTROL)
     * 相對於密閉房間ACH(ACH_CLOSED_ROOM)的比值(12/0.5=24，取同一個量級的
     * 保守下緣)。
     */
    static final double STACK_COEFFICIENT = 8.0;

    /**
     * 煙塵質量消光係数(specific extinction area) Dm，單位 m²/kg。
     *
     * 用來把生成的煙塵質量濃度(kg/m³)換算成消光係数K(1/m)：
     *   K = Dm × 煙塵質量濃度(kg/m³)
     * 取SFPE Handbook(Mulholland, "Smoke Production and Properties"一章)
     * 常引用的燃燒生成煙塵quantitative值量級(約數千 m²/kg)，換算出的K再
     * 透過既有的VISIBILITY_K_AT_SMOKE_1假設(smoke=1.0時K≈2.0/m)對應回
     * 目前程式沿用的0~1煙霧(能見度危害代理量)指標，讓「生成的煙塵質量」
     * 跟「既有的smoke數值尺度」之間有一個可追溯的物理換算，而不是憑空
     * 決定一個生成速率。
     */
    static final double SMOKE_MASS_EXTINCTION_COEFFICIENT_M2_PER_KG = 7600.0;

    // ─── §14 門的孔口流量洩漏模型：取代「穿越機率」常數 ──────────────────
    // 【校正清單§14，取代原本EnvironmentSimulator.SMOKE_SPREAD_PROB_FIREDOOR/
    // NORMALDOOR、SMELL_SPREAD_PROB_FIREDOOR/NORMALDOOR這四個沒有文獻依據的
    // 「每tick穿越機率」常數(0.45/0.65/0.75/0.90，純工程猜測)】
    //
    // 消防工程文獻裡沒有「穿越機率」這種量，實際規範/量測的是「洩漏率」這種
    // 連續物理量，這裡改用孔口流量公式(orifice flow equation)：
    //   Q_leak(m³/s) = Cd × A_leak × sqrt(2·ΔP / ρ_air)
    // Cd為孔口流量係数，A_leak為等效洩漏面積(m²)，ΔP為門兩側壓差(Pa)，
    // ρ_air為空氣密度(沿用上方AIR_DENSITY_KG_M3)。實際套用方式見
    // DoorLeakageModel.java。

    /**
     * 孔口流量係数 Cd，無因次。
     * 取ASHRAE Fundamentals對「銳緣孔口(sharp-edged orifice)」的慣用值，
     * 消防/HVAC洩漏計算(建築氣密性/ELA換算、NFPA 92煙控系統設計等)常見引用
     * 範圍0.6~0.65，這裡取中段值0.62。門縫並非理想銳緣孔口，實際Cd會依縫隙
     * 幾何形狀略有差異，這裡取常見量級，非針對特定門縫形狀實測校準。
     */
    static final double DISCHARGE_COEFFICIENT_CD = 0.62;

    /**
     * 通過認證的「smoke-and-draft-control door」(對應本專案「防火門」關閉
     * 狀態)洩漏率上限，單位 m³/(s·m²)(以門面積計)。
     * 來源：UL 1784《Air Leakage Tests of Door Assemblies》+ NFPA 105 +
     * IBC 710.5.2.2，規範上限為3.0 cfm/ft² ≈ 0.01524 m³/(s·m²)，量測條件為
     * FIREDOOR_TEST_PRESSURE_PA(0.10 in w.g. ≈ 24.9 Pa)。這是「認證門洩漏率」
     * 的規範上限值，不是機率，也不是每一款防火門實測的平均值——實際產品
     * 通常要優於此上限才能過認證，這裡取規範允許的上限當保守估計。
     */
    static final double FIREDOOR_LEAKAGE_RATE_M3_PER_S_M2 = 0.01524;

    /** UL 1784測試壓差，0.10 in w.g. ≈ 24.9 Pa，用來反推防火門的等效洩漏面積。 */
    static final double FIREDOOR_TEST_PRESSURE_PA = 24.9;

    /**
     * 單一門扇的參考面積，取常見單開門(single-leaf door)量級：寬0.9m×高2.0m
     * = 1.8 m²(建築規範/門窗產業常見的標準單開門尺寸量級，非特定產品規格)。
     * 用來把FIREDOOR_LEAKAGE_RATE_M3_PER_S_M2(以門面積為基準的洩漏率)換算
     * 回實際的等效洩漏面積(A_leak)，也做為NORMALDOOR_LEAKAGE_AREA_M2門縫
     * 周長估算的基礎(見下)。
     */
    static final double DOOR_LEAF_AREA_M2 = 1.8;

    /**
     * 一般(無認證)門縫的縫隙寬度量級，取3~5mm常見引用範圍的中間值。
     * ⚠️量級估計，非直接引用單一門實測值：一般門沒有對應法規門檻，這裡
     * 借用建築氣密性文獻的等效洩漏面積(Effective Leakage Area, ELA)概念
     * (ASHRAE Fundamentals、Sherman-Grimsrud模型)取量級參考，但那些文獻的
     * ELA通常是「整棟建築」的加總值，不能直接套用在單一門縫上，因此這裡
     * 改用「門縫周長 × 常見縫隙寬度」的量級換算(見NORMALDOOR_LEAKAGE_AREA_M2)。
     */
    static final double NORMALDOOR_GAP_WIDTH_M = 0.004; // 3~5mm量級中間值

    /**
     * 一般(無認證)門的等效洩漏面積(m²) = 門扇周長 × 縫隙寬度。
     * 周長取DOOR_LEAF_AREA_M2(0.9m×2.0m)的周長 2×(0.9+2.0)=5.8m，
     * 乘上NORMALDOOR_GAP_WIDTH_M(4mm)，得到5.8×0.004≈0.0232 m²。
     * ⚠️這是量級估計，非直接引用單一門實測值，未來有更精確數據應更新。
     */
    static final double NORMALDOOR_LEAKAGE_AREA_M2 =
        2.0 * (0.9 + 2.0) * NORMALDOOR_GAP_WIDTH_M;

    /**
     * 防火門(關閉狀態)的等效洩漏面積(m²)，由FIREDOOR_LEAKAGE_RATE_M3_PER_S_M2
     * + FIREDOOR_TEST_PRESSURE_PA，透過孔口公式反推：
     *   Q_test(m³/s) = FIREDOOR_LEAKAGE_RATE_M3_PER_S_M2 × DOOR_LEAF_AREA_M2
     *   Q_test = A_leak × sqrt(2×FIREDOOR_TEST_PRESSURE_PA / ρ_air)
     *   ⇒ A_leak = Q_test / sqrt(2×FIREDOOR_TEST_PRESSURE_PA / ρ_air)
     *
     * 【推導/使用上的重要澄清，誠實記錄】上式刻意不除以Cd：Q_test是UL1784
     * 測試量到的「實際通過流量」，已經內含測試當下門縫的真實流出行為，這裡
     * 反推出來的A_leak本質上已經吸收了測試當下的Cd效應。但DoorLeakageModel
     * 實際計算Q_leak時，為了讓防火門/一般門共用同一個孔口公式(而不是防火門
     * 一套公式、一般門另一套)，仍然會對這個A_leak再乘一次
     * DISCHARGE_COEFFICIENT_CD，這在嚴謹意義上是重複套用了一次Cd，會讓防火
     * 門在同一個測試壓差下算出的Q_leak比原始規範值再打約Cd倍折扣(≈6折)。
     * 這裡刻意保留這個重複折扣，原因：(1)方向是保守的——讓防火門洩漏量
     * 更低，更符合「防火門應該更密閉」的物理直覺，不會讓防火門洩漏比規範
     * 上限值還誇張；(2)避免另外維護「防火門不乘Cd、一般門要乘Cd」兩套不同
     * 公式，用同一個孔口流量函式處理兩種門，程式碼更不容易出錯。未來若要
     * 精確對齊規範值，只需要把DoorLeakageModel對防火門那條路徑的Cd改成
     * 1.0即可，這裡誠實記錄這個已知的簡化，不包裝成精確反推值。
     */
    static final double FIREDOOR_LEAKAGE_AREA_M2 =
        (FIREDOOR_LEAKAGE_RATE_M3_PER_S_M2 * DOOR_LEAF_AREA_M2)
        / Math.sqrt(2.0 * FIREDOOR_TEST_PRESSURE_PA / AIR_DENSITY_KG_M3);

    /**
     * 「完全開放通道」的參考截面積(m²)，用來把Q_leak(門縫的物理洩漏量)換算
     * 成一個「相對於完全暢通時能有多開」的連續比例(0~1)，供既有的Fick's Law
     * 擴散架構直接用倍率取代原本「開/關」二元判定，不需要额外處理壓力場
     * 的絕對單位換算。取一個block的平面寬度(BLOCK_METERS) × 樓層淨高
     * (FLOOR_HEIGHT_METERS)，對應本模擬既有「格與格之間，若中間沒有門/牆
     * 完全阻隔，就視為完全暢通」假設下隱含的截面積量級。
     * ⚠️這是簡化：真實開口大小應依實際門/開口幾何決定，這裡用單一block的
     * 截面積作為模擬既有grid結構下的合理近似，非精確量測值。
     */
    static final double FULL_OPENING_REFERENCE_AREA_M2 = BLOCK_METERS * FLOOR_HEIGHT_METERS;

    /**
     * 門縫滲漏相對於「完全暢通通道」的連續流量比例(0~1)：
     *   factor = (Cd × A_leak) / A_full_opening
     * 在「兩側都用同一個固定測試壓差ΔP」的簡化假設下(見DoorLeakageModel類
     * 註解)，ΔP項在比例中會相除抵消，因此不需要動態壓力場也能得到一個有
     * 物理意義、且跟ΔP大小無關的固定比例常數，只反映「洩漏面積相對於完全
     * 開口面積」的縮小倍率。
     * ⚠️簡化假設：真實情況下ΔP會隨火場發展動態變化(§10尚未有完整動態壓力
     * 場)，這裡先用固定測試壓差(FIREDOOR_TEST_PRESSURE_PA)作為簡化保守假設，
     * 非逐tick真正重算的動態壓力場。
     */
    static final double FIREDOOR_LEAKAGE_FACTOR =
        (DISCHARGE_COEFFICIENT_CD * FIREDOOR_LEAKAGE_AREA_M2) / FULL_OPENING_REFERENCE_AREA_M2;
    static final double NORMALDOOR_LEAKAGE_FACTOR =
        (DISCHARGE_COEFFICIENT_CD * NORMALDOOR_LEAKAGE_AREA_M2) / FULL_OPENING_REFERENCE_AREA_M2;
}
