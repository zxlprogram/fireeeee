package simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// EnvironmentSimulator — 火 / 煙 / 煙味擴散演算法，以及建築環境危險程度查詢
//
// 【拆解God Class + 降低耦合】原本 spreadEnvironment() 直接讀寫 Simulator 的
// static 欄位(space/currentCause/tick/activeControlEnabled/activeControlActionCount/
// random)。現在改成 spread(...) 明確接收這些狀態當參數，並把「這次呼叫觸發了幾次
// 主動控制動作」當作回傳值交給呼叫端自行累加，不再直接改寫呼叫端的計數器。
//
// 【校正清單§5】新增CO(coPpm)濃度場的生成與擴散，跟原本的smoke(能見度代理量)
// 分開追蹤，供People.absorbCO()的Purser FED_CO劑量模型使用；細節與簡化假設見
// PhysicalConstants與下方spread()內對應區塊的註解。這段新增邏輯會多消耗
// random.nextDouble()呼叫，因此本次校正之後的亂數序列跟校正前不再完全相同
// (原本各處註解宣稱的「不影響模擬的亂數序列」僅適用於前次的搬移重構，
// 這次為了補上CO物理量本身就是刻意的行為變更)。
// ═══════════════════════════════════════════════════════════════════════════
class EnvironmentSimulator {

    // ─── 三種擴散物(火源/煙霧/煙味)穿越「門」的機率 ───────────────────
    // 【校正清單§7】原本這裡還有FIRE_SPREAD_PROB_FIREDOOR/NORMALDOOR兩個常數，
    // 代表火源穿越門的「固定機率」，不管門已經被燒了多久都一樣。現在改成
    // 依門的耐火時效(fire-resistance rating)與已曝火時間，用Weibull存活分布
    // 動態算出這個tick的突破機率(見下方weibullBreachProbability()，常數搬到
    // PhysicalConstants的§7小節)，因此這兩個常數已移除。
    //
    // 煙霧/煙味的門縫滲漏依然是固定機率：完整的孔口流公式(Q=Cd·A·√(2ΔP/ρ))
    // 需要溫度/壓力場(§10尚未建模)才能算ΔP，這裡先維持原本的簡化假設。
    static final double SMOKE_SPREAD_PROB_FIREDOOR   = 0.45; // 煙霧穿越「關好的」防火門
    static final double SMOKE_SPREAD_PROB_NORMALDOOR = 0.65; // 煙霧穿越普通門
    static final double SMELL_SPREAD_PROB_FIREDOOR   = 0.75; // 煙味穿越「關好的」防火門
    static final double SMELL_SPREAD_PROB_NORMALDOOR = 0.90; // 煙味穿越普通門

    // ─── 熱 vs CO 的空間擴散：分開校準，不再共用同一套距離衰減 ─────────
    // 【修正】原本熱(對流HRR)跟CO用同一套「格距1/2/3 → 0.3/0.2/0.1」的線性
    // 衰減，導致遠離火源2~3格的地方也會累積出接近火源本身的高溫，讓「燒死」
    // 在死因統計裡嚴重蓋過「CO中毒」——但現實中兩者的物理機制不同：
    //   - 熱(輻射+對流)：輻射強度隨距離大致按平方反比律(q″∝1/r²)衰減，是
    //     短程效應，離開火焰本身幾步之外強度就掉得很快(SFPE Handbook輻射熱
    //     通量估算常見假設)。
    //   - CO/煙霧：是會持續累積、透過走廊/樓梯間(煙囪效應)在建築物裡到處
    //     流竄的氣體，衰減慢很多，這也是為什麼現實火災統計中，多數CO中毒
    //     死亡者其實是在「遠離火源」的房間/走道被發現，而燒傷致死者集中在
    //     火源附近。
    // 因此：熱的擴散半徑縮短、衰減改成平方反比(THERMAL_DIST_DECAY)；CO/煙霧
    // 的擴散半徑加大、沿用原本較平緩的線性衰減，讓兩者的死因比例能各自反映
    // 對應的物理範圍，而不是被同一套係数綁死。
    static final int THERMAL_DIFFUSION_MAX_DIST = 3;   // 熱只擴散到格距3(輻射短程衰減，見上)
    static final int CO_DIFFUSION_MAX_DIST = 5;        // CO/煙霧擴散到格距5(氣體持續累積/傳播範圍較廣)

    // 熱跨樓層(透過樓板/天花板)的額外折減：現實中樓板通常有一定的耐火時效，
    // 熱要真正燒穿樓板傳到上/下層之前，主要是靠煙囪效應把CO/煙霧帶過去，而不是
    // 熱本身。取0.15代表跨樓層的熱傳播只剩同層擴散強度的約15%。
    static final double THERMAL_CROSS_FLOOR_ATTENUATION = 0.15;

    /** 熱的空間衰減係数：平方反比律，基準值再調降(0.3→0.15)，讓熱的影響更集中在起火點本身。 */
    static double thermalDistFactor(int dist) {
        if (dist < 1 || dist > THERMAL_DIFFUSION_MAX_DIST) return 0.0;
        return 0.15 / (dist * dist); // dist=1→0.15, dist=2→0.0375, dist=3→0.0167
    }

    /** CO/煙霧的空間衰減係数：延續原本較平緩的線性衰減，但擴大到格距5，讓氣體傳播得更遠。 */
    static double coDistFactor(int dist) {
        if (dist < 1 || dist > CO_DIFFUSION_MAX_DIST) return 0.0;
        if (dist == 1) return 0.30;
        if (dist == 2) return 0.20;
        if (dist == 3) return 0.10;
        if (dist == 4) return 0.06;
        return 0.03; // dist == 5
    }

    // ─── §7 防火門/一般門耐火時效存活分布 ─────────────────────────────
    // 用Weibull存活函式 S(t)=exp(-(t/λ)^k) 模擬「門扛火勢扛多久才失效」：
    // λ(ratingMinutes×60秒)是特徵壽命，大致對應額定耐火時效；k(shape)是形狀
    // 參數，越大代表失效時間越集中在額定時效附近(越像標準試驗的「到時間才
    // 失效」)，越小代表失效時間分散度越大。這裡算的是「條件失效機率」：
    // 已經扛過t0秒(這個tick開始時)的前提下，扛不過t0~t0+dt這段時間的機率
    // = 1 − S(t0+dt)/S(t0) = 1 − exp((t0/λ)^k − ((t0+dt)/λ)^k)。
    //
    // @param fireExposureStartTick 這道門第一次被攻擊的tick(-1代表尚未被攻擊，回傳0)
    // @param currentTick 目前的tick
    // @param dtSeconds 一個tick對應的秒數(PhysicalConstants.TICK_SECONDS)
    // @param ratingMinutes 這道門的耐火時效(分鐘)
    // @param shape Weibull形狀參數k
    static double weibullBreachProbability(int fireExposureStartTick, int currentTick,
                                            double dtSeconds, double ratingMinutes, double shape) {
        if (fireExposureStartTick < 0) return 0.0;
        double lambdaSeconds = ratingMinutes * 60.0;
        double t0 = Math.max(0, currentTick - fireExposureStartTick) * dtSeconds;
        double t1 = t0 + dtSeconds;
        double exponent = Math.pow(t0 / lambdaSeconds, shape) - Math.pow(t1 / lambdaSeconds, shape);
        return 1.0 - Math.exp(exponent);
    }

    // ─── §2/§3 判斷某格是否「鄰接開放通道」，決定通風好壞(underVented) ──────
    // 完整模型需要依真實房間邊界劃分通風分區(§12尚未有這種幾何資料)，這裡先用
    // 「這一格的上下左右是否有still-open的門/出口/未被封鎖的通道」當簡化判斷：
    // 鄰接開放通道 → 視為通風尚可(ACH_PARTIALLY_OPEN、well-vented燃燒為主)；
    // 否則 → 視為悶燒/通風不良(ACH_CLOSED_ROOM、under-vented燃燒為主，CO產率
    // 明顯上升，對應現實中「濃煙悶燒房間CO特別毒」的現象)。
    private static boolean isNearOpenPassage(Space space, int z, int y, int x) {
        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        for (int i = 0; i < 4; i++) {
            int ny = y + dy[i], nx = x + dx[i];
            if (!space.isValid(z, ny, nx)) continue;
            Obj n = space.building[z][ny][nx];
            if (n instanceof Wall) continue;
            if (n instanceof Exit) return true;
            if (n instanceof FireDoor) { if (((FireDoor) n).isOpen) return true; continue; }
            if (n instanceof Door) { if (!((Door) n).blocked) return true; continue; }
            return true; // Floor/Stage等一般可通行格，視為連通的開放空間
        }
        return false;
    }

    static double smokeGrowth(double x) {
        return Math.pow(Math.abs(Math.sin(x - Math.PI / 2)), 0.68) + 1.0;
    }

    // ─── 火/煙擴散速度的「時間相位」倍率：跟tick掛勾，且依起火原因而不同形狀 ───
    // 回傳值疊乘在原本跟起火原因掛勾的 smokeMultiplier / fireSpreadTick 之上：
    //   >1.0 代表這個時間點擴散比基準快，<1.0 代表比基準慢，1.0 代表跟基準一致。
    static double temporalSpreadFactor(FireCause cause, int currentTick) {
        if (cause == null) return 1.0;
        double t = currentTick;
        switch (cause) {
            case ELECTRICAL:
                // 電線走火：電弧/初期短路瞬間延燒很快，但沒有持續助燃，隨時間趨緩──
                // 用指數衰減模擬「先快後慢」，長期收斂回接近基準值。
                return 1.0 + 1.2 * Math.exp(-t / 15.0);
            case CHEMICAL:
                // 化學起火：初期反應醞釀較慢，累積到臨界濃度後(閃燃)才急遽加速──
                // 用平滑的S型曲線(logistic)模擬「先慢後快」。
                return 0.6 + 1.4 / (1.0 + Math.exp(-(t - 25.0) / 6.0));
            case ARSON:
                // 縱火：助燃劑讓火勢一開始就全力延燒、維持高峰一段時間，
                // 助燃劑燒完後成長率緩慢回落到接近一般水準。
                return 1.5 - 0.5 * (1.0 - Math.exp(-t / 30.0));
            case ACCIDENTAL:
            default:
                return 1.0; // 一般意外：沒有特別的時間相位效果，維持原本固定倍率(線性)
        }
    }

    // 【修正2，bug修正】原本這裡在while迴圈裡不斷用candidate=cur覆蓋，迴圈跑完後
    // candidate會停在「最後一個」符合smoke<0.7的樓層，也就是整條樓梯間裡「最遠」
    // (通常是最頂層)的未起煙樓層，而不是直上「最近」的一層——等於煙霧(以及沿用
    // 同一個方法的火勢延燒判定)每個tick都可能直接跳到大樓最頂層，跳過中間所有
    // 樓層。改成找到第一個符合條件就直接return(跟findSmokeDownTarget的寫法一致)，
    // 讓跨樓層傳播回到「一次只吃掉最近一層」的合理行為。
    static Stage findSmokeUpTarget(Stage origin) {
        Stage cur = origin.upfloor;
        while (cur != null) { if (cur.smoke < 0.7) return cur; cur = cur.upfloor; }
        return null;
    }

    static Stage findSmokeDownTarget(Stage origin) {
        Stage cur = origin.downfloor;
        while (cur != null) { if (cur.smoke < 0.7) return cur; cur = cur.downfloor; }
        return null;
    }

    // ─── 【修正2：樓梯間防火隔間】跨樓層傳播的防火門判定 ─────────────────
    // 原本煙/煙味/CO/熱/火要從樓梯間的一層傳到另一層時，完全不檢查該樓層樓梯
    // 入口有沒有防護門，等於門形同虛設。現在改成：
    //   - stairDoorLeaks()：非致命的持續滲漏(煙/煙味/CO/熱)，沿用門縫穿越的
    //     固定機率，跟同層的門縫滲漏共用同一套常數。
    //   - stairDoorFireBreaches()：火勢本身要點燃目標樓層的樓梯格之前，門必須
    //     先被燒穿——沿用跟一般防火門對抗火勢延燒相同的Weibull耐火時效存活
    //     分布(累積曝火時間越久，這個tick被突破的機率越高)，不再無條件放行。
    // 若這座樓梯在該層沒有記錄到enclosureDoor(極端擁擠地圖放不下門)，兩者都
    // 回傳true，維持修正前「無防護」的保底行為，不會讓地圖生成失敗。
    private static boolean stairDoorLeaks(Stage target, Random random) {
        Door d = (target != null) ? target.enclosureDoor : null;
        if (d == null || d.blocked) return true;
        boolean closedFiredoor = (d instanceof FireDoor) && !((FireDoor) d).isOpen;
        double prob = closedFiredoor ? SMOKE_SPREAD_PROB_FIREDOOR : SMOKE_SPREAD_PROB_NORMALDOOR;
        return random.nextDouble() < prob;
    }

    private static boolean stairDoorFireBreaches(Stage target, int tick, Random random) {
        Door d = (target != null) ? target.enclosureDoor : null;
        if (d == null || d.blocked) return true;
        if (d.fireExposureStartTick < 0) d.fireExposureStartTick = tick;
        boolean isFiredoor = d instanceof FireDoor;
        double ratingMinutes = isFiredoor ? PhysicalConstants.FIREDOOR_RATING_MINUTES
                                           : PhysicalConstants.DOOR_UNRATED_EQUIVALENT_MINUTES;
        double shape = isFiredoor ? PhysicalConstants.WEIBULL_SHAPE_FIREDOOR
                                   : PhysicalConstants.WEIBULL_SHAPE_NORMALDOOR;
        double breachProb = weibullBreachProbability(d.fireExposureStartTick, tick,
            PhysicalConstants.TICK_SECONDS, ratingMinutes, shape);
        if (random.nextDouble() < breachProb) d.blocked = true;
        return d.blocked;
    }

    // 每個可停留格子(Floor/Stage)是否都著火：迴圈的終止條件
    static boolean allFloorsOnFire(Space space) {
        int height = space.height, rows = space.rows, cols = space.cols;
        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Obj o = space.building[z][y][x];
                    if (!(o instanceof Floor) && !(o instanceof Stage)) continue;
                    if (!o.fire) return false; // 還有至少一格可停留區域尚未著火
                }
            }
        }
        return true;
    }

    // 【校正清單§10/§11】四判据是否「同時」達到untenable門檻(依使用者決定
    // 採AND邏輯，而非ISO 13571標準做法的OR/任一判据達標即算)：溫度、輻射熱
    // 通量、CO濃度(瞬時IDLH門檻，非累積FED劑量)、能見度代理量smoke。
    // ⚠️ 這跟真實消防工程慣例(任一判据先達標就代表這個位置已經陷入危險，
    // 因為人只要撐不過其中一項就會失能/死亡)方向相反，AND邏輯會讓ASET明顯
    // 拉長；這是本次討論後刻意的設計選擇(用來對齊「真實火災ASET通常是好幾
    // 分鐘」的量級)，不是ISO 13571建議的判定方式，未來若要切回標準OR邏輯，
    // 只需要把下面的 && 全部換成 || 即可。
    private static boolean untenableByFourCriteria(Obj o) {
        boolean tempFail = o.tempC >= PhysicalConstants.TEMP_UNTENABLE_C;
        boolean radiantFail = o.radiantHeatFluxKwM2() >= PhysicalConstants.RADIANT_HEAT_FLUX_UNTENABLE_KW_M2;
        boolean toxicityFail = o.coPpm >= PhysicalConstants.CO_UNTENABLE_PPM;
        boolean visibilityFail = o.smoke > 0.7;
        return tempFail && radiantFail && toxicityFail && visibilityFail;
    }

    // ASET判定：建築內所有可停留格子(Floor/Stage)是否都已陷入危險
    // (著火，或四判据同時達標，見untenableByFourCriteria())
    static boolean allOccupiableCellsUntenable(Space space) {
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj o = space.building[z][y][x];
                    if (!(o instanceof Floor) && !(o instanceof Stage)) continue;
                    if (!(o.fire || untenableByFourCriteria(o))) {
                        return false; // 還有至少一格可停留區域尚未陷入危險
                    }
                }
            }
        }
        return true;
    }

    // 執行一次tick的火/煙/煙味擴散，並回傳「這次呼叫觸發了幾次主動控制動作
    // (自動關閉未關緊的防火門)」，呼叫端(Simulator)自行把它累加進總計數器。
    static int spread(Space space, FireCause currentCause, int tick, boolean activeControlEnabled, Random random) {
        int height = space.height, rows = space.rows, cols = space.cols;
        int[] dyFire = {-1, 1, 0, 0}, dxFire = {0, 0, -1, 1};
        int activeControlActionsThisTick = 0;

        double smokeMultiplier = 1.0;
        int fireSpreadTick = 2;

        if (currentCause != null) {
            switch (currentCause) {
                case CHEMICAL:   smokeMultiplier = 1.5; fireSpreadTick = 2; break;
                case ELECTRICAL: smokeMultiplier = 0.6; fireSpreadTick = 1; break;
                case ARSON:      smokeMultiplier = 1.2; fireSpreadTick = 1; break;
                case ACCIDENTAL: smokeMultiplier = 1.0; fireSpreadTick = 2; break;
            }
        }

        // ─── 擴散速度跟tick掛勾：疊加一個「時間相位」倍率 ─────────────────
        double temporalFactor = temporalSpreadFactor(currentCause, tick);
        smokeMultiplier *= temporalFactor;

        // ─── B2情境：決策支援 + 備援控制 ─────────────────────
        // 系統除了給人建議之外，偵測到附近有危險時也會主動操作環境：
        //   1) 自動關閉「本來沒關好」的防火門，讓它恢復阻絕火勢延燒的能力
        //   2) 局部啟動排煙/抑制手段，讓整體煙霧增長率打折扣(模擬機械排煙/灑水)
        if (activeControlEnabled) {
            for (int z = 0; z < height; z++) {
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        Obj cell = space.building[z][y][x];
                        if (!(cell instanceof FireDoor)) continue;
                        FireDoor fd = (FireDoor) cell;
                        if (!fd.isOpen) continue;

                        boolean nearbyDanger = false;
                        for (int dy = -2; dy <= 2 && !nearbyDanger; dy++) {
                            for (int dx = -2; dx <= 2 && !nearbyDanger; dx++) {
                                int ny = y + dy, nx = x + dx;
                                if (!space.isValid(z, ny, nx)) continue;
                                Obj near = space.building[z][ny][nx];
                                if (near.fire || near.smoke > 0.5) nearbyDanger = true;
                            }
                        }
                        if (nearbyDanger) {
                            fd.isOpen = false; // 系統主動關門，恢復防火門應有的阻絕效果
                            activeControlActionsThisTick++;
                        }
                    }
                }
            }
            smokeMultiplier *= 0.85; // 局部排煙/抑制手段，讓整體煙霧增長打折扣
        }

        double[][][] nextSmoke = new double[height][rows][cols];
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    nextSmoke[z][y][x] = space.building[z][y][x].smoke;

        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    if (space.building[z][y][x].smoke > 0.0) {
                        nextSmoke[z][y][x] = smokeGrowth(space.building[z][y][x].smoke) * smokeMultiplier;

                        for (int dy = -3; dy <= 3; dy++) {
                            for (int dx = -3; dx <= 3; dx++) {
                                int dist = Math.abs(dy) + Math.abs(dx);
                                if (dist == 0 || dist > 3) continue;

                                int ny = y + dy, nx = x + dx;
                                if (space.isValid(z, ny, nx)) {
                                    Obj next = space.building[z][ny][nx];
                                    if (!(next instanceof Wall) && !(next instanceof Exit) && next.smoke == 0.0) {
                                        double addAmt;
                                        if (dist == 1) addAmt = 0.3 * smokeMultiplier;
                                        else if (dist == 2) addAmt = 0.2 * smokeMultiplier;
                                        else addAmt = 0.1 * smokeMultiplier;

                                        // 煙霧要進入「門」這一格，先看這一步是否成功穿越門縫；
                                        // 沒過就直接跳過這格，這tick煙霧還進不去
                                        if (next instanceof FireDoor && !((FireDoor) next).isOpen) {
                                            if (random.nextDouble() >= SMOKE_SPREAD_PROB_FIREDOOR) continue;
                                        } else if (next instanceof Door) {
                                            if (random.nextDouble() >= SMOKE_SPREAD_PROB_NORMALDOOR) continue;
                                        }

                                        nextSmoke[z][ny][nx] += addAmt;
                                    }
                                }
                            }
                        }

                        if (space.building[z][y][x] instanceof Stage) {
                            Stage s = (Stage) space.building[z][y][x];
                            Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                            if (upT != null && stairDoorLeaks(upT, random)) nextSmoke[upT.z][upT.y][upT.x] += (0.2 * smokeMultiplier);
                            Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                            if (downT != null && stairDoorLeaks(downT, random)) nextSmoke[downT.z][downT.y][downT.x] += (0.1 * smokeMultiplier);
                        }
                    }
                }
            }
        }

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].smoke = Math.min(1.0, nextSmoke[z][y][x]);

        // ═══════════════════════════════════════════════════════════════════
        // 【校正清單§2/§3，取代原本的簡化代理】CO濃度(coPpm)生成與擴散：
        // 跟smoke分開追蹤的獨立量，不再讓People直接把smoke(能見度代理量)當CO
        // 劑量使用(見People.absorbCO())。
        //
        // 現在真的走「HRR→燃料→通風」這條鏈：每個火源格依自己的著火時刻
        // (ignitedAtTick)算Q(t)=α·t²(HRR，用FireCause.growthCurve，並用
        // PhysicalConstants.HRR_PEAK_CAP_KW封頂)，交給FireChemistry換算質量
        // 損失率/CO生成速率(kg/s)，用該格的真實體積(Space.getCellVolumeM3())
        // 換算成這一tick的ppm增量(標準空氣品質換算式，見PhysicalConstants§2/§3
        // 註解)。通風好壞(underVented)依這一格是否鄰接開放通道(門/出口)簡化
        // 判斷(isNearOpenPassage())。擴散方式維持原本跟煙霧一樣的距離衰減+
        // 門的穿越機率；自然稀釋衰減則從固定常數改成VentilationProfile依ACH
        // 算出的真實指數衰減(decayFactorPerTick())。
        // ═══════════════════════════════════════════════════════════════════
        double coAmbientDecayFactor = new VentilationProfile(PhysicalConstants.ACH_CLOSED_ROOM)
            .decayFactorPerTick(PhysicalConstants.TICK_SECONDS);

        double[][][] nextCoPpm = new double[height][rows][cols];
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    nextCoPpm[z][y][x] = space.building[z][y][x].coPpm * coAmbientDecayFactor;

        // ═══════════════════════════════════════════════════════════════════
        // 【修正，取代原本會無界疊加的絕熱升溫公式】溫度改成準穩態代數模型：
        // 不再讓上一tick的殘留溫度乘衰減係数繼續往上疊加，而是每個tick重新
        // 累加「這個tick所有火源(含擴散/跨樓層)貢獻到這一格的對流熱功率
        // (kW)」，最後統一用 T = AMBIENT + 熱功率 ÷ 熱損失係数 換算成準穩態
        // 溫度(見PhysicalConstants§10/§11修正的完整推導)。單位從「溫度增量
        // (°C)」改成「熱功率(kW)」，才能讓多個火源疊加對同一格的貢獻時，
        // 物理意義維持一致(功率可以直接相加，溫度增量不能簡單相加)。
        // ═══════════════════════════════════════════════════════════════════
        double[][][] netConvectiveHeatKw = new double[height][rows][cols];

        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Obj source = space.building[z][y][x];
                    if (!source.fire) continue; // 只有真正著火的格子才生成CO/熱；有煙但沒火的格子只靠擴散/滲入

                    // ── §2/§3：這一格自己的HRR(t)→質量損失率→CO生成速率→ppm增量 ──
                    int exposureTicks = (source.ignitedAtTick >= 0) ? Math.max(0, tick - source.ignitedAtTick) : 0;
                    double burnSeconds = exposureTicks * PhysicalConstants.TICK_SECONDS;
                    double alphaForCause = (currentCause != null)
                        ? currentCause.growthCurve.alphaKwPerSecondSquared : PhysicalConstants.ALPHA_MEDIUM;
                    double hrrKw = Math.min(PhysicalConstants.HRR_PEAK_CAP_KW, alphaForCause * burnSeconds * burnSeconds);

                    FuelType fuel = (currentCause != null) ? currentCause.representativeFuel : FuelType.WOOD;
                    boolean underVented = !isNearOpenPassage(space, z, y, x);
                    double coGenKgPerS = FireChemistry.getCoGenerationRateFromHrr(hrrKw, fuel, underVented);
                    double coGenKgThisTick = coGenKgPerS * PhysicalConstants.TICK_SECONDS;
                    double coGenMgThisTick = coGenKgThisTick * 1.0e6; // kg -> mg

                    double cellVolumeM3 = space.getCellVolumeM3(z);
                    double coGenerationThisTick = (cellVolumeM3 > 0)
                        ? (coGenMgThisTick / cellVolumeM3) * PhysicalConstants.MOLAR_VOLUME_L_PER_MOL_25C
                            / PhysicalConstants.CO_MOLAR_MASS_G_PER_MOL
                        : 0.0;

                    // ── §10/§11修正：這一格自己的對流HRR(kW)，直接以「熱功率」的形式
                    // 累加進netConvectiveHeatKw，換算成溫度的步驟統一挪到最後一次做。──
                    double convectiveHrrKw = hrrKw * PhysicalConstants.CONVECTIVE_FRACTION_OF_HRR;

                    nextCoPpm[z][y][x] += coGenerationThisTick;
                    netConvectiveHeatKw[z][y][x] += convectiveHrrKw;

                    for (int dy = -CO_DIFFUSION_MAX_DIST; dy <= CO_DIFFUSION_MAX_DIST; dy++) {
                        for (int dx = -CO_DIFFUSION_MAX_DIST; dx <= CO_DIFFUSION_MAX_DIST; dx++) {
                            int dist = Math.abs(dy) + Math.abs(dx);
                            if (dist == 0 || dist > CO_DIFFUSION_MAX_DIST) continue;

                            int ny = y + dy, nx = x + dx;
                            if (!space.isValid(z, ny, nx)) continue;
                            Obj next = space.building[z][ny][nx];
                            if (next instanceof Wall || next instanceof Exit) continue;

                            // 熱只在THERMAL_DIFFUSION_MAX_DIST内有非零貢獻(平方反比、短程)，
                            // CO/煙霧擴散到更遠的CO_DIFFUSION_MAX_DIST(線性、較平緩)，
                            // 兩者各自的距離衰減見上方thermalDistFactor()/coDistFactor()。
                            double coFactor = coDistFactor(dist);
                            double thermalFactor = thermalDistFactor(dist);
                            if (coFactor <= 0.0 && thermalFactor <= 0.0) continue;

                            // 沿用煙霧穿越門縫的機率(見上方常數)：CO/熱氣體跟煙霧一樣會透過
                            // 門縫滲漏，方向合理，但不是§7建議的孔口流公式(Q=Cd·A·√(2ΔP/ρ))；
                            // 熱傳導(關著的門本身也會傳熱)這裡不另外單獨建模，簡化成跟CO/煙霧
                            // 共用同一個「有沒有穿越門」的判斷，兩者要嘛一起過、要嘛一起被擋。
                            if (next instanceof FireDoor && !((FireDoor) next).isOpen) {
                                if (random.nextDouble() >= SMOKE_SPREAD_PROB_FIREDOOR) continue;
                            } else if (next instanceof Door) {
                                if (random.nextDouble() >= SMOKE_SPREAD_PROB_NORMALDOOR) continue;
                            }

                            nextCoPpm[z][ny][nx] += coFactor * coGenerationThisTick;
                            netConvectiveHeatKw[z][ny][nx] += thermalFactor * convectiveHrrKw;
                        }
                    }

                    // 跨樓層(煙囪效應)：CO/煙霧维持原本的傳播強度(現實中樓梯間/管道間的
                    // 「煙囪效應」是造成其他樓層人員遠端CO中毒死亡的主要途徑)，但熱透過
                    // 樓板/天花板的跨樓層傳播大幅降低(真的燒穿樓板前，熱傳導遠不如同層
                    // 的輻射/對流直接，用THERMAL_CROSS_FLOOR_ATTENUATION額外打折)。
                    if (source instanceof Stage) {
                        Stage s = (Stage) source;
                        Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                        if (upT != null && stairDoorLeaks(upT, random)) {
                            nextCoPpm[upT.z][upT.y][upT.x] += (0.2 * coGenerationThisTick);
                            netConvectiveHeatKw[upT.z][upT.y][upT.x] += (0.2 * THERMAL_CROSS_FLOOR_ATTENUATION * convectiveHrrKw);
                        }
                        Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                        if (downT != null && stairDoorLeaks(downT, random)) {
                            nextCoPpm[downT.z][downT.y][downT.x] += (0.1 * coGenerationThisTick);
                            netConvectiveHeatKw[downT.z][downT.y][downT.x] += (0.1 * THERMAL_CROSS_FLOOR_ATTENUATION * convectiveHrrKw);
                        }
                    }
                }
            }
        }

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].coPpm = Math.min(PhysicalConstants.CO_MAX_PPM, Math.max(0.0, nextCoPpm[z][y][x]));

        // 【修正】溫度場改成用準穩態代數公式一次算出，不再有「殘留值」需要
        // 下限保護以外的處理；HEAT_LOSS_COEFFICIENT_KW_PER_C已經依
        // HRR_PEAK_CAP_KW反推校正，所以就算所有鄰近火源的熱功率同時疊加，
        // 溫度也會自然收斂在COMPARTMENT_PEAK_GAS_TEMP_C附近的合理量級，
        // 不需要再另外加一道武斷的硬上限。
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++) {
                    double steadyStateTemp = PhysicalConstants.AMBIENT_TEMP_C
                        + netConvectiveHeatKw[z][y][x] / PhysicalConstants.HEAT_LOSS_COEFFICIENT_KW_PER_C;
                    space.building[z][y][x].tempC = Math.max(PhysicalConstants.AMBIENT_TEMP_C, steadyStateTemp);
                }

        // ─── 煙味(smell)擴散：無害，只用來觸發People的「察覺」判定 ───────────
        // 只要有煙霧或火源的格子都會持續放出煙味；擴散範圍/衰減跟煙霧用同一套邏輯，
        // 差別在穿越「門」這一步的機率遠高於煙霧，模擬「看不到煙，但聞得到味道」
        // 這種比視覺線索更早出現、也更難完全被門封死的訊號。
        double[][][] nextSmell = new double[height][rows][cols];
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    nextSmell[z][y][x] = space.building[z][y][x].smell;

        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Obj source = space.building[z][y][x];
                    if (!source.fire && source.smoke <= 0.0) continue; // 沒有煙也沒有火，這格不放出煙味

                    nextSmell[z][y][x] = Math.max(nextSmell[z][y][x],
                        smokeGrowth(Math.max(source.smoke, 0.05)) * smokeMultiplier);

                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            int dist = Math.abs(dy) + Math.abs(dx);
                            if (dist == 0 || dist > 3) continue;

                            int ny = y + dy, nx = x + dx;
                            if (!space.isValid(z, ny, nx)) continue;
                            Obj next = space.building[z][ny][nx];
                            if (next instanceof Wall || next instanceof Exit) continue;

                            double addAmt;
                            if (dist == 1) addAmt = 0.3 * smokeMultiplier;
                            else if (dist == 2) addAmt = 0.2 * smokeMultiplier;
                            else addAmt = 0.1 * smokeMultiplier;

                            if (next instanceof FireDoor && !((FireDoor) next).isOpen) {
                                if (random.nextDouble() >= SMELL_SPREAD_PROB_FIREDOOR) continue;
                            } else if (next instanceof Door) {
                                if (random.nextDouble() >= SMELL_SPREAD_PROB_NORMALDOOR) continue;
                            }

                            nextSmell[z][ny][nx] += addAmt;
                        }
                    }

                    // 煙味也跟煙霧一樣，會透過梯間往上/下樓層傳一點，
                    // 邏輯與煙霧完全比照，只差在傳遞量比照煙霧的樓梯間擴散幅度(0.2/0.1)。
                    if (source instanceof Stage) {
                        Stage s = (Stage) source;
                        Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                        if (upT != null && stairDoorLeaks(upT, random)) nextSmell[upT.z][upT.y][upT.x] += (0.2 * smokeMultiplier);
                        Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                        if (downT != null && stairDoorLeaks(downT, random)) nextSmell[downT.z][downT.y][downT.x] += (0.1 * smokeMultiplier);
                    }
                }
            }
        }

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].smell = Math.min(1.0, nextSmell[z][y][x]);

        // 【機率制火勢延燒】期望頻率仍然是 1/fireSpreadTick(維持原本各起火原因的基準節奏)，
        // 但額外乘上跟tick掛勾的temporalFactor，讓觸發機率隨時間非線性起伏。
        double fireSpreadProb = Math.min(1.0, (1.0 / fireSpreadTick) * temporalFactor);
        if (random.nextDouble() < fireSpreadProb) {
            List<int[]> fireCells = new ArrayList<>();
            for (int z = 0; z < height; z++)
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < cols; x++)
                        if (space.building[z][y][x].fire) fireCells.add(new int[]{z, y, x});

            for (int[] cell : fireCells) {
                Obj cellObj = space.building[cell[0]][cell[1]][cell[2]];

                for (int i = 0; i < 4; i++) {
                    int nz = cell[0], ny = cell[1] + dyFire[i], nx = cell[2] + dxFire[i];
                    if (space.isValid(nz, ny, nx)) {
                        Obj next = space.building[nz][ny][nx];

                        if (next instanceof Wall || next instanceof Exit) continue;

                        // 【校正清單§7】門的突破機率不再是固定值，改成依「這道門已經被攻擊
                        // 多久」與其耐火時效，用Weibull存活分布動態算出這個tick的突破機率
                        // (見上方weibullBreachProbability()常數定義於PhysicalConstants §7)。
                        if (next instanceof FireDoor && !((FireDoor) next).isOpen) {
                            FireDoor fdNext = (FireDoor) next;
                            if (fdNext.fireExposureStartTick < 0) fdNext.fireExposureStartTick = tick;
                            double breachProb = weibullBreachProbability(fdNext.fireExposureStartTick, tick,
                                PhysicalConstants.TICK_SECONDS, PhysicalConstants.FIREDOOR_RATING_MINUTES,
                                PhysicalConstants.WEIBULL_SHAPE_FIREDOOR);
                            if (random.nextDouble() >= breachProb) continue; // 還沒扛到失效，門仍維持阻絕效果
                        } else if (next instanceof Door) {
                            Door d = (Door) next;
                            if (d.fireExposureStartTick < 0) d.fireExposureStartTick = tick;
                            double breachProb = weibullBreachProbability(d.fireExposureStartTick, tick,
                                PhysicalConstants.TICK_SECONDS, PhysicalConstants.DOOR_UNRATED_EQUIVALENT_MINUTES,
                                PhysicalConstants.WEIBULL_SHAPE_NORMALDOOR);
                            if (random.nextDouble() < breachProb) d.blocked = true; // 門本身耐火時效已到，物理上被燒穿卡死
                            if (random.nextDouble() >= breachProb) continue; // 這次火勢還沒能穿過這道普通門
                        }

                        next.fire = true;
                        next.smoke = Math.max(next.smoke, 0.6);
                        if (next.ignitedAtTick < 0) next.ignitedAtTick = tick; // 【校正清單§2/§3】記錄這一格自己的起燒時刻
                    }
                }

                if (cellObj instanceof Stage) {
                    // 【修正2，bug修正】原本的while迴圈用upFireTarget=cur不斷覆蓋，等於一路
                    // 找到「最遠」(通常是頂層)那個還沒著火的樓層直接點燃，一tick就能跳過中間
                    // 所有樓層；而且只要上方還有任何一層沒著火，就完全不會往下方蔓延，導致
                    // 火勢在樓梯間裡幾乎是瞬間衝到頂樓。改成上下兩個方向都各自找「最近」一層
                    // (跟findSmokeUpTarget/findSmokeDownTarget一致)，且要先通過該層樓梯入口的
                    // 防火門(stairDoorFireBreaches()：Weibull耐火時效存活分布)才會真的被點燃，
                    // 不再無條件直接燒過去。
                    Stage s = (Stage) cellObj;
                    Stage upTarget = s.upfloor != null ? findSmokeUpTarget(s) : null;
                    if (upTarget == null && s.upfloor != null && !s.upfloor.fire) upTarget = s.upfloor;
                    if (upTarget != null && stairDoorFireBreaches(upTarget, tick, random)) {
                        upTarget.fire = true;
                        upTarget.smoke = Math.max(upTarget.smoke, 0.5);
                        if (upTarget.ignitedAtTick < 0) upTarget.ignitedAtTick = tick;
                    }

                    Stage downTarget = s.downfloor != null ? findSmokeDownTarget(s) : null;
                    if (downTarget == null && s.downfloor != null && !s.downfloor.fire) downTarget = s.downfloor;
                    if (downTarget != null && stairDoorFireBreaches(downTarget, tick, random)) {
                        downTarget.fire = true;
                        downTarget.smoke = Math.max(downTarget.smoke, 0.5);
                        if (downTarget.ignitedAtTick < 0) downTarget.ignitedAtTick = tick;
                    }
                }
            }
        }

        return activeControlActionsThisTick;
    }
}
