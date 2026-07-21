package simulator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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

    // ─── 三種擴散物(火源/煙霧/煙味)穿越「門」的機制 ───────────────────
    // 【校正清單§7】原本這裡還有FIRE_SPREAD_PROB_FIREDOOR/NORMALDOOR兩個常數，
    // 代表火源穿越門的「固定機率」，不管門已經被燒了多久都一樣。現在改成
    // 依門的耐火時效(fire-resistance rating)與已曝火時間，用Weibull存活分布
    // 動態算出這個tick的突破機率(見下方weibullBreachProbability()，常數搬到
    // PhysicalConstants的§7小節)，因此這兩個常數已移除。
    //
    // 【校正清單§14，取代原本的SMOKE_SPREAD_PROB_FIREDOOR/NORMALDOOR、
    // SMELL_SPREAD_PROB_FIREDOOR/NORMALDOOR】煙霧/煙味(以及共用同一套擴散
    // 迴圈的CO/熱)的門縫滲漏，原本也是四個沒有文獻依據的固定「每tick穿越
    // 機率」常數(0.45/0.65/0.75/0.90)，用擲骰子決定通不通。現在改成孔口
    // 流量公式(Q_leak=Cd·A_leak·√(2ΔP/ρ_air))算出的連續穿越量，取代機率
    // 事件——常數與推導過程搬到PhysicalConstants的§14小節，實際換算邏輯見
    // DoorLeakageModel.java的leakageFactor()/linkFactor()，下方擴散迴圈直接
    // 呼叫這兩個方法取得連續的[0,1]暢通比例，不再呼叫random.nextDouble()
    // 決定門通不通。

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
    //
    // 【校正清單§14審查】這個常數跟門/開口的孔口流量洩漏模型無關，不歸入本次
    // Q_leak架構：它描述的是熱「透過樓板/天花板固體構造」的傳導折減，物理機制
    // 是固體熱傳導/建築構造的耐火時效，不是氣體透過門縫孔口的流量洩漏(孔口
    // 流公式Q=Cd·A·√(2ΔP/ρ)是氣體流經開口的模型，不適用於描述熱穿透實心樓板
    // 這種固體傳導路徑)，因此維持原樣，不套用DoorLeakageModel。
    // ⚠️無文獻對應：0.15這個具體數值目前搜尋範圍內找不到可直接引用的樓板
    // 耐火構造熱穿透衰減比例文獻，純屬工程判斷(方向合理——樓板通常有數十分
    // 鐘等級的耐火時效，熱穿透遠比同層直接輻射/對流慢很多，但15%這個具體
    // 數字沒有實測或規範數據校準過)，比照PhysicalConstants裡
    // WEIBULL_SHAPE_FIREDOOR的處理方式誠實標註，之後若要更嚴謹，應查樓板
    // 構造(如混凝土樓板)的熱傳導/耐火試驗數據反推。
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
    //   - stairDoorLeakageFactor()：非致命的持續滲漏(煙/煙味/CO/熱)，改用
    //     DoorLeakageModel的孔口流量比例，跟同層的門縫滲漏共用同一套物理
    //     模型(見校正清單§14)，回傳連續的[0,1]暢通比例，不再擲骰子。
    //   - stairDoorFireBreaches()：火勢本身要點燃目標樓層的樓梯格之前，門必須
    //     先被燒穿——沿用跟一般防火門對抗火勢延燒相同的Weibull耐火時效存活
    //     分布(累積曝火時間越久，這個tick被突破的機率越高)，不再無條件放行。
    // 若這座樓梯在該層沒有記錄到enclosureDoor(極端擁擠地圖放不下門)，兩者都
    // 視為「無防護」的保底行為(暢通比例1.0/直接燒穿)，不會讓地圖生成失敗。
    private static double stairDoorLeakageFactor(Stage target) {
        Door d = (target != null) ? target.enclosureDoor : null;
        if (d == null) return 1.0;
        return DoorLeakageModel.leakageFactor(d);
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

    // 【校正清單§10/§11；CO判据已依審查意見改為暴露時間累積(FED)】四判据是否
    // 達到untenable門檻：溫度、輻射熱通量、CO毒性、能見度代理量smoke。
    // ⚠️ 目前四者用OR：任一判据達標即算這一格陷入危險，符合ISO 13571/真實消防
    // 工程慣例(人只要撐不過其中一項就會失能)。若之後想切回「同時達標才算」的
    // AND邏輯，把下面的 || 全部換成 && 即可，但要注意那會讓ASET明顯拉長，
    // 不是ISO 13571建議的判定方式。
    //
    // 【CO判据修正】原本用coPpm>=1200ppm(NIOSH瞬時IDLH)判定，等於假設「濃度
    // 一超過門檻，人就立刻失能」，但CO中毒的實際機制是「%COHb隨暴露時間累積」，
    // 100ppm待1小時和1200ppm待數秒對血中濃度的影響完全不同量級，瞬時門檻會
    // 錯把「濃度」本身當成充分條件，忽略了「待了多久」這個關鍵變數。
    // 現在改用跟People.checkStatus()同一套Purser FED_CO劑量模型：
    // WorldObjects.Obj.fedCoCell是「假設一名代表性occupant從這裡開始有CO起
    // 就持續待著」所累積的FED劑量(由本檔案spread()逐tick更新，公式與
    // People.absorbCO()相同，只是RMV/易感度取代表性常數而非個別人員的
    // 動態值，見PhysicalConstants.CO_SUSCEPTIBILITY_D_DEFAULT)，達到
    // FED_INCAPACITATION(=0.3，ISO 13571定義的失能門檻)才視為這一格因CO而
    // untenable，取代原本瞬時ppm門檻。CO_UNTENABLE_PPM常數保留供其他報表/
    // 歷史比對參考，但ASET判定不再使用它。
    private static boolean untenableByFourCriteria(Obj o) {
        boolean tempFail = o.tempC >= PhysicalConstants.TEMP_UNTENABLE_C;
        boolean radiantFail = o.radiantHeatFluxKwM2() >= PhysicalConstants.RADIANT_HEAT_FLUX_UNTENABLE_KW_M2;
        boolean toxicityFail = o.fedCoCell >= PhysicalConstants.FED_INCAPACITATION;
        boolean visibilityFail = o.smoke > 0.7;
        return tempFail || radiantFail || toxicityFail || visibilityFail;
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
    static int spread(Space space, FireCause currentCause, int tick, boolean activeControlEnabled, Random random, List<People> peopleList) {
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
            // 【校正清單 追加項】關門前先掃一次所有存活/未逃生的People，把「目前所在位置」
            // 「下個junction任務的目標格」「junctionPath剩餘路徑(從junctionPathIdx開始)」
            // 全部收集成一個座標集合，代表「這個tick正被某人使用/依賴的格子」。
            // 之所以先掃一次收集成Set，而不是對每扇門各自線性掃peopleList，是因為
            // 門的數量通常遠小於people×path長度的組合，先建好查詢集合再逐門查詢
            // 是O(people總路徑長度 + 門數)，比O(門數×people數)更省。
            Set<Long> doorsInUse = collectDoorsInUse(peopleList);

            for (int z = 0; z < height; z++) {
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        Obj cell = space.building[z][y][x];
                        if (!(cell instanceof FireDoor)) continue;
                        FireDoor fd = (FireDoor) cell;
                        if (!fd.isOpen) continue;

                        // 這扇門正被某人的路線依賴(人就站在門上、或門是junction目標、
                        // 或還在某人junctionPath剩餘路徑裡)：本tick不關，順延到下個
                        // 沒人使用的tick再檢查，避免系統把自己剛給的建議切斷。
                        if (doorsInUse.contains(encodeCoord(z, y, x))) continue;

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

        // ═══════════════════════════════════════════════════════════════════
        // 【煙霧模型重構：依火災工程文獻】原本的smokeGrowth()人工曲線、格距
        // 1/2/3→0.3/0.2/0.1固定衰減權重、樓梯間+0.2/+0.1固定增量，已全數
        // 移除。新的管線嚴格依序為：
        //   Smoke Generation (下方與CO共用同一份Fuel Burning Rate算出)
        //     ↓
        //   Diffusion (Fick's Second Law，有限差分，見smokeGenerated填好之後
        //     的diffuseSmoke()呼叫)
        //     ↓
        //   Ventilation Removal (VentilationProfile指數稀釋，跟CO共用同一套
        //     ACH模型)
        // 三個階段彼此不覆蓋，也不在其他地方額外疊加。
        //
        // smokeGenerated[z][y][x]：這個tick每一格「新生成」的煙霧量(沿用原本
        // smoke的0~1能見度危害代理單位)，在下方的火源格迴圈(跟CO/熱共用同一個
        // 迴圈、同一份massLossRateKgPerS)裡累加，迴圈跑完後才一次套用擴散與
        // 通風排除，見本方法最後的diffuseSmoke()呼叫。
        // ═══════════════════════════════════════════════════════════════════
        double[][][] smokeGenerated = new double[height][rows][cols];

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

                    // 【新增，回應「可燃物影響燃燒/煙霧/CO排放」需求】優先使用這一格自己
                    // 鋪設的可燃物(source.fuel，由BuildingGenerator.distributeFuelLoads()
                    // 隨機分布)算燃燒特性；這一格沒有可燃物(空地/已清空區域)時，退回原本
                    // 「起火原因→代表性燃料」的全域假設，維持既有行為不變。
                    FuelType fuel = (source.fuel != null) ? source.fuel
                        : ((currentCause != null) ? currentCause.representativeFuel : FuelType.WOOD);
                    boolean underVented = !isNearOpenPassage(space, z, y, x);

                    // ── 燃料燃燒率 ṁ_fuel(kg/s) = HRR(kW) / ΔHc(kJ/kg)，Prompt §一 ──
                    // 只算一次，CO與煙霧生成都共用這同一份質量損失率，不重複計算
                    // (對應Prompt §一/§十一：不可Smoke算一次、CO再算一次)。
                    double massLossRateKgPerS = FireChemistry.getMassLossRate(hrrKw, fuel);

                    double coGenKgPerS = FireChemistry.getCoGenerationRate(massLossRateKgPerS, fuel, underVented);
                    double coGenKgThisTick = coGenKgPerS * PhysicalConstants.TICK_SECONDS;
                    double coGenMgThisTick = coGenKgThisTick * 1.0e6; // kg -> mg

                    double cellVolumeM3 = space.getCellVolumeM3(z);
                    double coGenerationThisTick = (cellVolumeM3 > 0)
                        ? (coGenMgThisTick / cellVolumeM3) * PhysicalConstants.MOLAR_VOLUME_L_PER_MOL_25C
                            / PhysicalConstants.CO_MOLAR_MASS_G_PER_MOL
                        : 0.0;

                    // ═══ 煙霧生成 ṁ_smoke = Y_s × ṁ_fuel，Prompt §一/§二 ═══
                    // Y_s(Smoke Yield, kg smoke/kg fuel)沿用FuelType.sootYield——
                    // 這個欄位在FuelType.java裡本來就已經依燃料種類提供文獻量級的
                    // 煙塵產率，且其註解早已寫明「供之後能見度/煙層濃度模型使用」，
                    // 所以直接沿用，不在PhysicalConstants另開一個會跟它打架的重複
                    // 全域常數(見PhysicalConstants §十三的說明)。
                    double smokeGenKgPerS = fuel.sootYield * massLossRateKgPerS;
                    double smokeGenKgThisTick = smokeGenKgPerS * PhysicalConstants.TICK_SECONDS;

                    // 這一tick生成的煙塵質量濃度增量(kg/m³) = 生成質量 / 這一格真實體積
                    // (跟CO共用同一個控制體積假設，見Space.getCellVolumeM3())。
                    double sootConcentrationIncrementKgM3 = (cellVolumeM3 > 0)
                        ? (smokeGenKgThisTick / cellVolumeM3) : 0.0;

                    // 質量濃度(kg/m³) → 消光係數K(1/m)：K = Dm × 質量濃度，Dm為煙塵的
                    // 質量消光係數(SMOKE_MASS_EXTINCTION_COEFFICIENT_M2_PER_KG，見
                    // PhysicalConstants)。再把K換算回既有的0~1煙霧(能見度危害代理量)
                    // 指標：沿用現有VISIBILITY_K_AT_SMOKE_1的假設(smoke=1.0時K≈2.0/m)，
                    // 兩處共用同一個物理假設，維持一致。
                    double extinctionCoefficientIncrement =
                        sootConcentrationIncrementKgM3 * PhysicalConstants.SMOKE_MASS_EXTINCTION_COEFFICIENT_M2_PER_KG;
                    double smokeIndexGenerated = extinctionCoefficientIncrement / PhysicalConstants.VISIBILITY_K_AT_SMOKE_1;

                    smokeGenerated[z][y][x] += smokeIndexGenerated;

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

                            // 【校正清單§14】CO/熱氣體跟煙霧一樣會透過門縫滲漏，現在改用
                            // DoorLeakageModel算出的孔口流量連續比例(見PhysicalConstants
                            // §14)，取代原本的門縫穿越機率擲骰子；熱傳導(關著的門本身也會
                            // 傳熱)這裡仍不另外單獨建模，簡化成跟CO/煙霧共用同一個「暢通
                            // 比例」，兩者一起依同一個倍率打折，而不是各自獨立判定。
                            double doorFactor = DoorLeakageModel.leakageFactor(next);
                            if (doorFactor <= 0.0) continue;

                            nextCoPpm[z][ny][nx] += doorFactor * coFactor * coGenerationThisTick;
                            netConvectiveHeatKw[z][ny][nx] += doorFactor * thermalFactor * convectiveHrrKw;
                        }
                    }

                    // 跨樓層(煙囪效應)：CO/煙霧维持原本的傳播強度(現實中樓梯間/管道間的
                    // 「煙囪效應」是造成其他樓層人員遠端CO中毒死亡的主要途徑)，但熱透過
                    // 樓板/天花板的跨樓層傳播大幅降低(真的燒穿樓板前，熱傳導遠不如同層
                    // 的輻射/對流直接，用THERMAL_CROSS_FLOOR_ATTENUATION額外打折)。
                    // 【校正清單§14】樓梯間防護門的滲漏也改用DoorLeakageModel的連續比例
                    // (stairDoorLeakageFactor())取代原本的機率擲骰子。
                    if (source instanceof Stage) {
                        Stage s = (Stage) source;
                        Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                        if (upT != null) {
                            double stairFactor = stairDoorLeakageFactor(upT);
                            nextCoPpm[upT.z][upT.y][upT.x] += stairFactor * (0.2 * coGenerationThisTick);
                            netConvectiveHeatKw[upT.z][upT.y][upT.x] += stairFactor * (0.2 * THERMAL_CROSS_FLOOR_ATTENUATION * convectiveHrrKw);
                        }
                        Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                        if (downT != null) {
                            double stairFactor = stairDoorLeakageFactor(downT);
                            nextCoPpm[downT.z][downT.y][downT.x] += stairFactor * (0.1 * coGenerationThisTick);
                            netConvectiveHeatKw[downT.z][downT.y][downT.x] += stairFactor * (0.1 * THERMAL_CROSS_FLOOR_ATTENUATION * convectiveHrrKw);
                        }
                    }
                }
            }
        }

        // ═══ Smoke Generation → Diffusion → Ventilation Removal ═══
        // smokeGenerated[][][]已經在上面的火源迴圈填好(跟CO共用同一份燃燒率)，
        // 這裡接著做Fick's Second Law擴散，最後套用通風稀釋，寫回space。
        diffuseAndVentilateSmoke(space, smokeGenerated, activeControlEnabled, random);

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].coPpm = Math.min(PhysicalConstants.CO_MAX_PPM, Math.max(0.0, nextCoPpm[z][y][x]));

        // 【新增，回應審查意見：CO濃度判定應採暴露時間累積(FED)而非瞬時IDLH門檻】
        // 在coPpm確定之後，順便幫每一格累積fedCoCell：用跟People.absorbCO()
        // 完全相同的Purser公式(FED_CO_CONST × ppm^FED_CO_EXPONENT × RMV × dt)，
        // 只是RMV固定取RMV_LIGHT_ACTIVITY當代表性occupant的假設(不像People那樣
        // 隨panicLevel內插)，因為這是「這個位置」的環境危害嚴重度，不是特定
        // 某個人的暴露史。之所以在這裡而不是在People的迴圈裡做，是因為就算
        // 這格暫時沒人，untenable判定仍然需要知道「如果有人在這裡待到現在，
        // 會不會已經失能」。
        {
            double dtMinutes = PhysicalConstants.TICK_SECONDS / 60.0;
            for (int z = 0; z < height; z++)
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < cols; x++) {
                        Obj cell = space.building[z][y][x];
                        if (cell.coPpm <= 0.0) continue;
                        double cohbIncrement = PhysicalConstants.FED_CO_CONST
                            * Math.pow(cell.coPpm, PhysicalConstants.FED_CO_EXPONENT)
                            * PhysicalConstants.RMV_LIGHT_ACTIVITY * dtMinutes;
                        cell.fedCoCell += cohbIncrement / PhysicalConstants.CO_SUSCEPTIBILITY_D_DEFAULT;
                    }
        }

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

        // ─── 【新增，回應「可燃物需要燃點，溫度到達燃點時點燃該格」需求】───────
        // 溫度場(tempC)已經在上面算完這個tick的準穩態值，這裡額外掃一次：任何
        // 「還沒著火、但鋪有可燃物」的格子，只要這一格的溫度已經達到或超過該
        // 可燃物的引燃溫度(FuelType.ignitionTempC)，就直接點燃，不必等下面
        // 【機率制火勢延燒】的鄰接擴散輪到它——對應現實中「還沒被火苗直接舔到，
        // 但被輻射/對流熱烤到自燃點」的閃燃(flashover)前兆現象。這是獨立於原本
        // 機率式鄰接延燒之外「新增的第二條點燃途徑」，兩者並存、互不取代：
        //   1) 鄰接延燒(下方，機率式，不論該格有無可燃物都可能被燒到，維持
        //      allFloorsOnFire()終止條件不受影響)
        //   2) 這裡新增的溫度自燃(只發生在真的鋪有可燃物、且溫度夠高的格子)
        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Obj cell = space.building[z][y][x];
                    if (cell.fire || cell.fuel == null) continue;
                    if (cell.tempC < cell.fuel.ignitionTempC) continue;

                    cell.fire = true;
                    cell.smoke = Math.max(cell.smoke, 0.4);
                    if (cell.ignitedAtTick < 0) cell.ignitedAtTick = tick;
                }
            }
        }

        // ─── 煙味(smell)擴散：無害，只用來觸發People的「察覺」判定 ───────────
        // 只要有煙霧或火源的格子都會持續放出煙味；擴散範圍/衰減跟煙霧用同一套邏輯。
        // 【校正清單§14】原本這裡穿越「門」用獨立的SMELL_SPREAD_PROB_FIREDOOR/
        // NORMALDOOR機率(0.75/0.90，比煙霧的0.45/0.65高，用來模擬「看不到煙但
        // 聞得到味道」)，屬於「穿越機率」量，沒有文獻依據。現在改成跟煙霧/CO/
        // 熱共用同一套DoorLeakageModel孔口流量比例(同一道門縫，物理上就是同一
        // 個開口面積，沒有理由讓氣味分子跟煙塵走不同的門縫穿越比例)，是否真的
        // 「聞得到」則交給下游People.java裡對smell欄位的偵測閾值判定(比對一個
        // 濃度門檻，而不是靠這裡的擴散機率決定)。
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

                    // 【煙味不在本次煙霧物理重構範圍內】原本這裡呼叫已移除的smokeGrowth()
                    // 人工曲線；煙味(smell)只是無害的「察覺」訊號，不是本次要求重構的
                    // 煙霧(smoke)能見度危害量，因此僅做最小改動：直接沿用source.smoke
                    // (夾在0.05下限)乘上原本的smokeMultiplier，不引入新的人工曲線。
                    nextSmell[z][y][x] = Math.max(nextSmell[z][y][x],
                        Math.max(source.smoke, 0.05) * smokeMultiplier);

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

                            double doorFactor = DoorLeakageModel.leakageFactor(next);
                            if (doorFactor <= 0.0) continue;

                            nextSmell[z][ny][nx] += doorFactor * addAmt;
                        }
                    }

                    // 煙味也跟煙霧一樣，會透過梯間往上/下樓層傳一點，
                    // 邏輯與煙霧完全比照，只差在傳遞量比照煙霧的樓梯間擴散幅度(0.2/0.1)，
                    // 樓梯間防護門的滲漏比例同樣改用stairDoorLeakageFactor()。
                    if (source instanceof Stage) {
                        Stage s = (Stage) source;
                        Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                        if (upT != null) nextSmell[upT.z][upT.y][upT.x] += stairDoorLeakageFactor(upT) * (0.2 * smokeMultiplier);
                        Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                        if (downT != null) nextSmell[downT.z][downT.y][downT.x] += stairDoorLeakageFactor(downT) * (0.1 * smokeMultiplier);
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

    // 【校正清單 追加項】把(z,y,x)編碼成單一long當Set的key，避免額外配置int[3]
    // 物件或自訂equals/hashCode。z/y/x都遠小於2^16(建築規模)，用20 bit/軸綽綽有餘。
    private static long encodeCoord(int z, int y, int x) {
        return ((long) z << 40) | ((long) y << 20) | (long) x;
    }

    // 【校正清單 追加項】掃一次peopleList，收集「這個tick正被使用/依賴的格子座標」：
    //   1) 每個還活著、還沒逃生的人「目前站的格子」
    //   2) 已指派但還沒真正跨過去的junctionTargetPos(這次同樓層任務的目標格)
    //   3) junctionPath從junctionPathIdx開始、還沒走到的剩餘路徑格
    //   4) targetStage(跨樓層任務目標，例如樓梯間門)的座標
    // 只掃活人一次，比對每扇門各自掃一次peopleList快得多。
    private static Set<Long> collectDoorsInUse(List<People> peopleList) {
        Set<Long> inUse = new HashSet<>();
        if (peopleList == null) return inUse;
        for (People p : peopleList) {
            if (p == null || p.isDead || p.isEscaped) continue;

            inUse.add(encodeCoord(p.z, p.y, p.x));

            if (p.junctionTargetPos != null && p.junctionTargetPos.length == 3) {
                inUse.add(encodeCoord(p.junctionTargetPos[0], p.junctionTargetPos[1], p.junctionTargetPos[2]));
            }

            if (p.junctionPath != null) {
                for (int i = p.junctionPathIdx; i < p.junctionPath.size(); i++) {
                    int[] pos = p.junctionPath.get(i);
                    if (pos != null && pos.length == 3) {
                        inUse.add(encodeCoord(pos[0], pos[1], pos[2]));
                    }
                }
            }

            if (p.targetStage != null) {
                inUse.add(encodeCoord(p.targetStage.z, p.targetStage.y, p.targetStage.x));
            }
        }
        return inUse;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 【煙霧模型重構】Fick's Second Law的有限差分擴散 + 通風排除。
    //
    //   ∂C/∂t = D·∇²C
    //
    // 對每一組相鄰的「格」(同層四鄰居，或樓梯間上下層各一條連通路徑)，
    // 用顯式歐拉(Explicit Euler)、鏈路對稱的通量形式:
    //   flux(A→B) = r × (C_A − C_B)，C_A -= flux，C_B += flux
    // 這種「每條連結各自成對更新」的寫法，不管格子邊界形狀多不規則(牆/門/
    // 樓層邊緣)，總和永遠等於原本的Laplacian四鄰居公式在內部格子上的結果，
    // 而且天生質量守恆：對任一條連結，flux從A扣掉的量，一定等於B加上的量，
    // 全域加總後兩兩互相抵銷，Diffusion階段不會無中生有增加或減少總煙量
    // (Prompt §六的質量守恆要求)。
    //
    // 數值穩定性(Prompt §五)：顯式差分要求 r=D·Δt/Δx² ≤ 0.25(四鄰居版本)。
    // 這裡不是把D或Δt/Δx寫死調到剛好符合，而是「依目前專案參數自動調整
    // 更新方式」：把一個tick切成diffusionSubsteps個子步，讓每個子步的r
    // (含樓梯間用STACK_COEFFICIENT放大的垂直r)都留在安全範圍內，這樣未來
    // 就算改了SMOKE_DIFFUSIVITY/STACK_COEFFICIENT/BLOCK_METERS/TICK_SECONDS，
    // 也不會讓數值發散。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 【校正清單§14，取代原本的isClosedFireDoor()/isPlainDoor()/
     * smokeDiffusionLinkOpen()】判斷這個tick，A、B兩格之間的煙霧擴散連結的
     * 「暢通比例」(0~1)，取代原本的布林「暢通/不暢通」+機率擲骰子。
     * 牆(Wall)/出口(Exit)視為no-flux邊界，呼叫端須先排除，這裡不處理。
     * 若A或B任一邊是門，改用DoorLeakageModel.linkFactor()算出的孔口流量
     * 連續比例(跟CO/熱共用同一套物理模型，見PhysicalConstants §14)，一次
     * 算出A↔B這條連結的倍率，而不是每個方向各驗一次或各擲一次骰子
     * (避免A→B跟B→A用不同結果，破壞質量守恆的對稱性；現在改成確定性計算，
     * 這個對稱性問題自然不存在)。
     */
    private static double smokeDiffusionLinkFactor(Obj a, Obj b) {
        return DoorLeakageModel.linkFactor(a, b);
    }

    // 【校正清單§14】random參數保留是為了不更動呼叫端(spread())的既有簽章，
    // 但門縫穿越的判定已經改成DoorLeakageModel的確定性連續比例，這個方法內部
    // 不再消耗random.nextDouble()呼叫；若之後這個方法完全不需要隨機性，可以
    // 考慮把這個參數整個移除。
    static void diffuseAndVentilateSmoke(Space space, double[][][] smokeGenerated,
                                          boolean activeControlEnabled, Random random) {
        int height = space.height, rows = space.rows, cols = space.cols;

        // ─── Step 1: Smoke Generation ─── 把這個tick新生成的煙霧疊加到現有濃度上。
        double[][][] cur = new double[height][rows][cols];
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    cur[z][y][x] = space.building[z][y][x].smoke + smokeGenerated[z][y][x];

        // ─── Step 2: Diffusion (Fick's Second Law，有限差分) ───
        double dx = PhysicalConstants.BLOCK_METERS;
        double dt = PhysicalConstants.TICK_SECONDS;
        double rHoriz = PhysicalConstants.SMOKE_DIFFUSIVITY * dt / (dx * dx);
        double rVert = rHoriz * PhysicalConstants.STACK_COEFFICIENT; // D_z = StackCoefficient × D

        // 【數值穩定性自動調整，Prompt §五】一格最多同層4個鄰居+樓梯上下各1個，
        // 抓最壞情況(4個同層鄰居+上下2條樓梯連結)算總流出比例，並留安全margin，
        // 不夠穩定就把這個tick切成更多子步，而不是任意調整常數本身。
        double worstCaseOutflowFraction = 4.0 * rHoriz + 2.0 * rVert;
        double stabilitySafetyMargin = 0.9; // 顯式方法理論上限是1.0，留10%安全餘裕避免邊界效應震盪
        int diffusionSubsteps = Math.max(1, (int) Math.ceil(worstCaseOutflowFraction / stabilitySafetyMargin));
        double rHorizStep = rHoriz / diffusionSubsteps;
        double rVertStep = rVert / diffusionSubsteps;

        int[] dy4 = {0, 1}, dx4 = {1, 0}; // 只需列舉「右」「下」兩個方向，配合下方(ny>y)||(ny==y&&nx>x)的判斷，同層每對鄰居只走一次

        for (int step = 0; step < diffusionSubsteps; step++) {
            double[][][] next = new double[height][rows][cols];
            for (int z = 0; z < height; z++)
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < cols; x++)
                        next[z][y][x] = cur[z][y][x];

            // 同層四鄰居：只掃「右」「下」兩個方向，每一對相鄰格恰好處理一次
            for (int z = 0; z < height; z++) {
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        Obj a = space.building[z][y][x];
                        if (a instanceof Wall || a instanceof Exit) continue; // 牆/出口：no-flux邊界，兩側都不參與擴散

                        for (int dir = 0; dir < 2; dir++) {
                            int ny = y + dy4[dir], nx = x + dx4[dir];
                            if (!space.isValid(z, ny, nx)) continue;
                            Obj b = space.building[z][ny][nx];
                            if (b instanceof Wall || b instanceof Exit) continue;

                            double doorFactor = smokeDiffusionLinkFactor(a, b);
                            if (doorFactor <= 0.0) continue;

                            double flux = rHorizStep * doorFactor * (cur[z][y][x] - cur[z][ny][nx]);
                            next[z][y][x] -= flux;
                            next[z][ny][nx] += flux;
                        }
                    }
                }
            }

            // 樓梯間垂直連結：只從「本層」往upfloor走一次，downfloor那一側會在
            // 它自己是本層時被處理到，不會重複計算同一條連結。
            for (int z = 0; z < height; z++) {
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        Obj o = space.building[z][y][x];
                        if (!(o instanceof Stage)) continue;
                        Stage s = (Stage) o;
                        if (s.upfloor == null) continue;
                        Stage up = s.upfloor;

                        // 【校正清單§14】樓梯間防火門滲漏改用連續比例(取代原本的機率擲骰子)。
                        double stairFactor = stairDoorLeakageFactor(up);
                        if (stairFactor <= 0.0) continue;

                        double flux = rVertStep * stairFactor * (cur[z][y][x] - cur[up.z][up.y][up.x]);
                        next[z][y][x] -= flux;
                        next[up.z][up.y][up.x] += flux;
                    }
                }
            }

            cur = next;
        }

        // ─── Step 3: Ventilation Removal ─── 跟CO共用同一套ACH指數稀釋衰減；
        // activeControlEnabled(機械排煙/加壓系統啟動)時，換氣率提升到
        // ACH_STAIR_OR_SMOKE_CONTROL量級，模擬主動排煙的效果(取代原本武斷的
        // ×0.85生成折扣，把「排煙系統降低煙霧」這件事放回它物理上真正對應的
        // 通風排除階段，而不是竄改生成速率)。
        double achForSmoke = activeControlEnabled
            ? PhysicalConstants.ACH_STAIR_OR_SMOKE_CONTROL
            : PhysicalConstants.ACH_CLOSED_ROOM;
        double ventilationDecayFactor = new VentilationProfile(achForSmoke).decayFactorPerTick(dt);

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++) {
                    double smokeAfterVentilation = cur[z][y][x] * ventilationDecayFactor;
                    space.building[z][y][x].smoke = Math.min(1.0, Math.max(0.0, smokeAfterVentilation));
                }
    }
}
