package simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//─── 人物類別 ─────────────────────────────────────────────────
// 【重構】People 現在只保留「一個tick該做哪些事、依什麼順序做」的低階步驟方法
// (stillUnaware/instinctiveEscapeStep/checkStatus/...)，以及自身的狀態與資料。
// 「整段流程要怎麼串起來」則交給注入的 EscapeStrategy 決定(見 DefaultEscapeStrategy /
// SmartEscapeStrategy)，People 對外只暴露一個 tick() 入口，不再有 escape()/SmartEscape()
// 兩個平行的公開方法，也不再需要呼叫端(Simulator)自己判斷該叫哪一個。
//
// 【降低耦合】People 不再直接讀寫 Simulator 的任何 static 欄位，改成透過建構時
// 注入的 SimulationContext 存取「系統示警時間」「同行者查詢」「跨樓層指派計數」
// 這幾項原本會跨類別反向耦合的資訊。(原本還有「起火原因對煙霧毒性的倍率」，
// 校正清單§5之後改成掛在EnvironmentSimulator生成CO的速率上，不再需要跨界查詢)
class People {
    int id, z, y, x;
    Space space;
    boolean isDead = false, isEscaped = false;
    DeathCause deathCause = DeathCause.NONE; // 只有isDead=true時才有意義：燒死 or CO中毒致死
    Stage targetStage = null;

    // ─── 人物屬性、移動速度、CO暴露追蹤 ─────────────────────────────
    // 【校正清單§1】速度拆成「平面」與「樓梯」兩個獨立參數(speedFloor/speedStage)，
    // 不再用同一個speed代表所有地形；見currentSpeed()。
    // 【校正清單§5】CO暴露改用Purser FED_CO劑量模型：fedCO是本人累積的
    // Fractional Effective Dose(無因次，≥0.3視為失能、≥1.0視為致死劑量)，
    // coSusceptibilityD是「這個人達到失能/致死所需的%COHb臨界值」，只反映個人
    // 體質，取代原本身兼「個人體質」與「起火原因毒性」兩種用途的coThreshold。
    PersonProfile profile;
    int speedFloor;
    int speedStage;
    double fedCO = 0.0;
    double fedThermal = 0.0; // 熱暴露累積劑量(Purser耐受時間模型，見PhysicalConstants§5b)，達到FED_THERMAL_LETHAL視為燒死
    double coSusceptibilityD;

    // 【降低耦合】共用的 Random 與少數幾個逃生策略需要的常數，原本是 private，
    // 現在放寬為 package-private，讓同套件下的 DefaultEscapeStrategy / SmartEscapeStrategy
    // 可以直接使用，呼叫 rng 的時機與次序跟原本完全相同，不影響模擬亂數序列。
    // 【校正清單§9，更新】上面這句話僅適用於當初的搬移重構；這次為了補上準備時間
    // (stillUnaware()裡的PanicModel.samplePremovementTicks())與人流密度限速
    // (currentSpeed()裡的離散化Bernoulli抽樣)，新增了rng消耗點，是刻意的行為變更，
    // 校正後的Random呼叫序列跟校正前不再相同。
    static final Random rng = new Random();
    static final double COMPLIANCE_RATE = 0.85; // 收到系統建議時，實際照做的機率；其餘時間依自身直覺行動
    static final int ADVICE_SAFETY_CAP_TICKS = 200; // 極寬鬆的安全上限，只防止pathfinding異常時卡死

    // ─── 拆分出去的子模組(見 GridKeys / VisionSystem / RouteFinder /
    //     DeviceStatus / PanicModel / PersonKpi)：各自負責一塊獨立職責，
    //     People 只保留「一個tick該做哪些事」的流程控制(orchestration)。
    DeviceStatus device;   // 手機電力 / 系統連線 / 定位誤差
    PanicModel panic;      // 恐慌值與延遲/誤操作機率
    PersonKpi kpi;         // 逃生過程KPI/事件追蹤
    RouteFinder routeFinder; // 路徑規劃(BFS/Dijkstra/連通性檢查)，綁定同一張Space

    // ─── 【新增】依模擬控制器與逃生策略而異的兩個協作物件 ───────────────────
    private final SimulationContext context; // 對「模擬控制器」唯一允許的依賴窗口
    private final EscapeStrategy strategy;   // 這個人這場模擬要用哪套邏輯決定怎麼逃

    // ─── 準備時間(pre-movement time)：對數常態分布，見PanicModel.samplePremovementTicks() ───
    // 【校正清單§9，取代原本只有WITH_CHILD才有的childGatherDelay】-1代表尚未
    // 抽樣(還沒察覺異常)；一旦在stillUnaware()裡從「未察覺」轉為「已察覺」，
    // 會立刻依角色卡抽一個對數常態分布的準備時間，這段期間人物維持原地不動，
    // 之後才會開始真正的逃生移動，適用於所有角色(不再只有WITH_CHILD才有延遲)。
    int premovementTicksRemaining = -1;

    // ─── 同行者 ──────────────────────────────────────────────────
    Integer companionId = null;
    private static final double COMPANION_WAIT_DISTANCE = 4;
    private static final double COMPANION_WAIT_CHANCE = 0.5;

    // ─── 行動不便者：原地等待救援 ───────────────────────────────
    boolean waitingForRescue = false;
    private static final double IMPAIRED_WAIT_CHANCE = 0.02; // 每tick(在還不算立即危險時)選擇改採等待策略的機率

    // ─── 察覺(Awareness)追蹤 ──────────────────────
    // 人物不是「開局即逃生」，而是要先「察覺」異常(聞到煙味/看到煙火，或[有系統時]收到
    // 系統示警)才會進入逃生狀態；尚未察覺前，這個tick停留原地不動。
    boolean aware = false;
    Integer firstCueAt = null;
    private static final double AWARENESS_SMOKE_THRESHOLD = 0.05; // 「聞到煙味」門檻，比對Obj.smell欄位(不是smoke)

    // ─── 同樓層路網分岔點任務 ──────────────────────────────
    // targetStage(見上方宣告)沿用於「跨樓層」任務：指派後延到下個tick開頭才真正跨過去。
    // 這裡的是「同樓層」任務：目標格是路網上的下一個決策點，中途沿著快取好的路徑走，
    // 不必每tick重新呼叫Dijkstra；到達後才重新規劃下一段。
    int[] junctionTargetPos = null;       // 這次同樓層任務的目標格座標 {z,y,x}
    List<int[]> junctionPath = null;      // 從發布當下到目標格的路徑快取(不含起點)，之後每tick依序走
    int junctionPathIdx = 0;              // 目前走到快取路徑的第幾格

    // 【曾經進入高煙區】判定門檻常數(能見度代理量smoke，不再用於CO判斷——
    // CO臨界暴露改用PhysicalConstants.FED_INCAPACITATION，見checkStatus())
    private static final double HIGH_SMOKE_THRESHOLD = 0.7;  // 沿用程式既有的「濃煙」判定門檻

    // 【修改】建構子：多了 context(模擬控制器依賴窗口) 與 strategy(逃生決策策略)兩個參數，
    // 兩者都由呼叫端(Simulator.runOneSimulation)在建立每一位 People 時依當次模擬的 mode 決定。
    public People(int id, int z, int y, int x, Space space, PersonProfile profile,
                  SimulationContext context, EscapeStrategy strategy) {
        this.id = id; this.z = z; this.y = y; this.x = x;
        this.space = space;
        this.profile = profile;
        this.fedCO = 0.0;
        this.fedThermal = 0.0;
        this.context = context;
        this.strategy = strategy;

        double panicSusceptibility;

        // 根據不同屬性，初始化不同的速度與CO易感度。
        // 【校正清單§1】speedFloor是平面移動速度；speedStage另外依
        // PhysicalConstants.STAIR_SPEED_FACTOR算出，站在樓梯間(Stage)格時使用
        // (見currentSpeed())，取代原本「單一speed被誤用成全域速度、實際上比較
        // 像樓梯速度」的問題。
        // 【校正清單§5】coSusceptibilityD是個人達到失能/致死所需的%COHb臨界值，
        // 沿用原本coThreshold(25/20/15/12)的相對比例，改用%COHb常見的臨界範圍
        // (健康成年人約30~50%)重新標定量級，只反映個人體質。
        switch (profile) {
            case NORMAL_SOLO:
            case STAFF:
            case CUSTOMER:
                this.speedFloor = 3;      // 1.2 m/s，落在SFPE/Fruin(1971)保守設計值下緣 ✅
                this.coSusceptibilityD = 40.0; // 健康成年人耐受度高
                panicSusceptibility = 0.3;
                break;
            case WITH_CHILD:
                this.speedFloor = 2;      // 0.8 m/s，落在Proulx家庭群體疏散速度0.6–1.1 m/s區間 ✅
                this.coSusceptibilityD = 32.0; // 幼童較脆弱，整體耐受度下降
                panicSusceptibility = 0.6;
                break;
            case IMPAIRED:
                // 【校正】原本1 block/tick(0.4 m/s)偏低於Boyce/Shields/Silcock(1999)文獻中位數，
                // 上調到2 block/tick(0.8 m/s)較貼近文獻(0.5–0.9 m/s)；樓梯速度另外用
                // STAIR_SPEED_FACTOR折算，不再讓平面速度被誤當樓梯速度使用。
                this.speedFloor = 2;
                this.coSusceptibilityD = 24.0; // 耐受度較低
                panicSusceptibility = 0.8;
                break;
            case ELDERLY:
                // 【校正】原本1 block/tick(0.4 m/s)比較像是樓梯速度被誤用成全域速度；
                // 平面速度上調到2 block/tick(0.8 m/s)，對上Fujiyama & Tyler(2004)
                // 文獻下緣(0.8–1.0 m/s)，樓梯速度另外用STAIR_SPEED_FACTOR折算。
                this.speedFloor = 2;
                this.coSusceptibilityD = 19.0; // 身體最脆弱，致死閾值最低
                panicSusceptibility = 0.7;
                break;
            default:
                this.speedFloor = 3;
                this.coSusceptibilityD = 40.0;
                panicSusceptibility = 0.3;
                break;
        }
        this.speedStage = Math.max(1, (int) Math.round(this.speedFloor * PhysicalConstants.STAIR_SPEED_FACTOR));

        this.panic = new PanicModel(panicSusceptibility);
        // 注意：DeviceStatus建構子內部會依序呼叫 rng.nextDouble() 一次、rng.nextGaussian() 兩次，
        // 跟原本這段邏輯直接寫在這裡的呼叫順序完全相同，不影響模擬亂數序列。
        this.device = new DeviceStatus(rng);
        this.routeFinder = new RouteFinder(space, context);
        this.kpi = new PersonKpi();
    }

    // ────────────────────────────────────────────────────────
    // 【新增】唯一對外入口：這個tick該怎麼決策，完全交給建構時注入的 strategy 決定。
    // Simulator 的主迴圈只需要對每個人統一呼叫 p.tick(tick)，
    // 不再需要「if (useSmart) ... else ...」這種每個tick、每個人都重複一次的分支。
    // ────────────────────────────────────────────────────────
    public void tick(int currentTick) {
        strategy.step(this, currentTick);
    }

    // ────────────────────────────────────────────────────────
    // 察覺判定：回傳true代表「這個tick仍在察覺前，維持原地」，呼叫端應直接return。
    // 無系統：聞到煙味(局部smoke超過門檻)或看到煙火才會察覺。
    // 有系統：上述條件之外，只要仍連線，系統示警也能觸發察覺，
    // 模擬「手機收到緊急通知」比自己聞到煙味更早的情境。
    // ────────────────────────────────────────────────────────
    boolean stillUnaware(int currentTick, boolean hasSystemSupport) {
        if (aware) return false;

        // 改用專門的smell欄位判斷「聞到煙味」，不再直接看smoke——
        // smell是無害、擴散範圍更廣(含較容易穿透門縫)的獨立訊號，見EnvironmentSimulator。
        boolean smellsSmoke = space.building[z][y][x].smell > AWARENESS_SMOKE_THRESHOLD;
        boolean seesFire = VisionSystem.seesFireNearby(space, z, y, x);
        // 【降低耦合】原本直接讀 Simulator.systemAwarenessTick，現在透過 context 查詢。
        boolean systemAlert = hasSystemSupport && device.networkConnected
                && context.isSystemAlertTriggered(currentTick);

        if (smellsSmoke || seesFire || systemAlert) {
            aware = true;
            firstCueAt = currentTick;
            // 【校正清單§9】察覺異常的當下，依角色卡抽一個對數常態分布的準備時間
            // (見PanicModel.samplePremovementTicks())，取代原本只有WITH_CHILD才有
            // 的ad hoc延遲；這個延遲期間在DefaultEscapeStrategy/SmartEscapeStrategy
            // 裡會被消耗掉(見premovementTicksRemaining的遞減處)。
            premovementTicksRemaining = PanicModel.samplePremovementTicks(profile, rng);
            return false;
        }
        return true;
    }

    // ────────────────────────────────────────────────────────
    // 【共用】依現場視野(BFS)做出的直覺逃生移動，
    // Default模式固定使用；Smart模式在斷線/不服從建議時也會退回使用這套邏輯
    // ────────────────────────────────────────────────────────
    void instinctiveEscapeStep(int currentTick) {
        // 【校正清單§1】這個tick的移動力取決於「這個tick開頭站在哪種地形」
        // (平面Floor或樓梯間Stage)，見currentSpeed()。簡化假設：一個tick內
        // 不會重新評估地形(block=0.4m的粒度下，一tick內跨越地形類型的機率很低)。
        int speed = currentSpeed();

        // 恐慌：誤操作，這個tick不會照理性判斷走，而是隨便亂走
        boolean panicMisstep = panic.rollMisstep(rng);
        boolean inSmoke = space.building[z][y][x].smoke > 0.7;

        if (inSmoke || panicMisstep) {
            randomMove(speed);
            return;
        }

        boolean moved = false;
        for (int step = 0; step < speed; step++) {
            int[] nextPos = routeFinder.findNextStepLosBFS(z, y, x, profile);
            if (nextPos == null) {
                // 視野內看不到出口，但只要沒有濃煙擋住判斷，仍先嘗試憑方向感朝出口移動，
                // 而不是直接放棄、整段路程都用亂走(randomMove)處理
                nextPos = routeFinder.findNextStepTowardExit(z, y, x);
            }
            if (nextPos == null) {
                if (kpi.everRerouted) {
                    System.out.println("[REROUTE-STUCK] tick=" + currentTick
                        + " id=" + id
                        + " pos=(" + z + "," + y + "," + x + ")"
                        + " selfSmoke=" + String.format("%.2f", space.building[z][y][x].smoke)
                        + " rerouteAttempts=" + kpi.rerouteAttempts
                        + " -> no LOS path and no full-map path found, falling to randomMove");
                }
                markReportedTrapped(currentTick); break;
            }
            moved = true;

            if (nextPos[0] != this.z) {
                this.targetStage = (Stage) space.building[nextPos[0]][nextPos[1]][nextPos[2]];
                context.onStageAssigned(); // 追蹤「跨樓層目標指派」次數，是改道事件的前提
                break;
             } else {
                 Obj destCell = space.building[nextPos[0]][nextPos[1]][nextPos[2]];
                 this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
                 DoorFlowModel.consume(destCell); // 【校正清單§1】實際走進門/出口才消耗通行名額
                 if (kpi.firstCorrectDecisionTick == null) kpi.firstCorrectDecisionTick = currentTick;
                 checkStatus();
                 if (isDead || isEscaped) break;

                 if (space.building[z][y][x].smoke > 0.7) {
                     // 依當下已知資訊判斷的路線，卻走進高煙區，代表資訊落後於現場實際狀況
                     kpi.everWrongRoute = true;
                     randomMove(speed - step - 1);
                     break;
                 }
             }
         }
         if (!moved) randomMove(speed);
    }

    // ────────────────────────────────────────────────────────
    // 【校正清單§1】依「這個tick開頭」所在的地形回傳基礎移動速度：
    // 站在樓梯間(Stage)用speedStage，其餘(Floor)用speedFloor。
    // 【校正清單§9，新增】再疊加人流密度對速度的抑制(Fruin/SFPE基本圖，
    // 見PhysicalConstants §9小節)：算出這個人身邊3x3鄰域(含自己)的人流密度
    // (人/m²)，代入 S=k1−k2·D 算出「密度限制下」的理論步行速度上限，跟
    // 角色卡本身的基礎速度取min後，換算回離散的block/tick——因為換算結果
    // 通常是小數，沿用DOOR_CAPACITY_PER_TICK同樣的離散化手法：取整數部分，
    // 小數部分當機率決定要不要多走一格，讓「平均而言」符合連續速度公式，
    // 而不是簡單無條件捨去(那樣密度稍微一高，慢角色就會變成龜速)。
    // CROWD_JAM_MIN_MPS當最低速度下限，避免純粹人多就完全卡死——那是
    // panic.rollFreeze()代表的「心理愣住」該負責的事，兩種機制分開建模。
    // ────────────────────────────────────────────────────────
    int currentSpeed() {
        boolean onStage = space.building[z][y][x] instanceof Stage;
        int baseBlocks = onStage ? speedStage : speedFloor;
        double baseMps = PhysicalConstants.blocksPerTickToMetersPerSecond(baseBlocks);
        if (baseMps <= 0) return Math.max(1, baseBlocks);

        int radius = PhysicalConstants.CROWD_DENSITY_RADIUS_BLOCKS;
        int nearby = context.countNearbyPeopleOnFloor(z, y, x, radius);
        double sideLengthM = (2 * radius + 1) * PhysicalConstants.BLOCK_METERS;
        double densityPerM2 = nearby / (sideLengthM * sideLengthM);

        double k1 = onStage ? PhysicalConstants.FRUIN_STAIR_K1 : PhysicalConstants.FRUIN_CORRIDOR_K1;
        double k2 = onStage ? PhysicalConstants.FRUIN_STAIR_K2 : PhysicalConstants.FRUIN_CORRIDOR_K2;
        double densityLimitedMps = Math.max(PhysicalConstants.CROWD_JAM_MIN_MPS, k1 - k2 * densityPerM2);

        double speedFactor = Math.min(1.0, densityLimitedMps / baseMps);
        double rawBlocks = baseBlocks * speedFactor;
        int intPart = (int) rawBlocks;
        double frac = rawBlocks - intPart;
        int speed = intPart + (rng.nextDouble() < frac ? 1 : 0);
        return Math.max(1, speed);
    }

    // ────────────────────────────────────────────────────────
    // 【行動不便者】等待救援行為：尚無立即危險時，有機率選擇原地等待，
    // 一旦所在位置煙霧開始升高到危險程度，被迫放棄等待、嘗試自行移動
    // ────────────────────────────────────────────────────────
    void updateWaitingForRescue() {
        if (waitingForRescue) {
            if (space.building[z][y][x].smoke > 0.5) {
                waitingForRescue = false;
            }
            return;
        }
        if (space.building[z][y][x].smoke < 0.3 && rng.nextDouble() < IMPAIRED_WAIT_CHANCE) {
            waitingForRescue = true;
        }
    }

    // ────────────────────────────────────────────────────────
    // 【同行者】若同伴仍存活且落後太多，有機率選擇等待、或朝同伴方向折返一步，
    // 並(在連線狀態下)把彼此的相對狀態回報給系統
    // ────────────────────────────────────────────────────────
    boolean waitForCompanion(int currentTick) {
        if (companionId == null) return false;
        // 【降低耦合】原本直接讀 Simulator.allPeopleById，現在透過 context 查詢。
        People companion = context.findPersonById(companionId);
        if (companion == null || companion.isDead || companion.isEscaped) return false;

        int dist = Math.abs(this.z - companion.z) * 100 + Math.abs(this.y - companion.y) + Math.abs(this.x - companion.x);
        if (dist <= COMPANION_WAIT_DISTANCE) return false;
        if (rng.nextDouble() >= COMPANION_WAIT_CHANCE) return false;

        // 回報同行者狀態：讓系統知道這兩人是一組，目前彼此距離多遠、選擇了等待/折返
        if (device.networkConnected) {
            System.out.println("【回報】T" + currentTick + "：#" + id + " 回報同行者 #" + companion.id
                    + " 落後約 " + dist + " 格，選擇原地等待/折返會合，暫緩自行逃生");
        }

        // 若同伴還在同一樓層且距離還不算太誇張，嘗試朝同伴方向折返一步；
        // 距離太遠或跨樓層(無法簡單折返)則單純原地等待
        if (this.z == companion.z && dist <= COMPANION_WAIT_DISTANCE * 3) {
            stepToward(companion.y, companion.x);
        }
        return true;
    }

    // 朝目標座標移動最多一步(只挑同樓層可通行的相鄰格，找不到更近的格子就原地不動)，用於同行者折返會合
    private void stepToward(int ty, int tx) {
        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        int bestDist = Math.abs(y - ty) + Math.abs(x - tx);
        int by = y, bx = x;
        for (int i = 0; i < 4; i++) {
            int ny = y + dy[i], nx = x + dx[i];
            if (!space.isValid(z, ny, nx)) continue;
            if (!routeFinder.isPassable(space.building[z][ny][nx])) continue;
            int d = Math.abs(ny - ty) + Math.abs(nx - tx);
            if (d < bestDist) { bestDist = d; by = ny; bx = nx; }
        }
        Obj destCell = space.building[z][by][bx];
        this.y = by; this.x = bx;
        DoorFlowModel.consume(destCell); // 【校正清單§1】折返會合也可能走進門，同樣受瓶頸限制
        checkStatus();
    }

    // ────────────────────────────────────────────────────────
    // 系統端最早得知此人受困/卡住位置的時刻(需在連線狀態下才會被回報)
    // ────────────────────────────────────────────────────────
    void markReportedTrapped(int currentTick) {
        if (device.networkConnected && kpi.reportedTrappedTick == null) {
            kpi.reportedTrappedTick = currentTick;
            // 【定位不準】系統回報給救援端的位置帶有定位誤差，跟本人實際座標不完全相同，
            // 這裡把「系統看到的座標」印進過程紀錄，代表定位誤差確實影響了救援端收到的資訊
            int reportedY = (int) Math.round(y + device.posErrorY);
            int reportedX = (int) Math.round(x + device.posErrorX);
            System.out.println("【系統】T" + currentTick + "：偵測到 #" + id + " 受困，回報座標(含定位誤差) = ("
                    + z + ", " + reportedY + ", " + reportedX + ")，實際座標 = (" + z + ", " + y + ", " + x + ")");
        }
    }

    // 建議發布：規劃出「下一個決策點」並包裝成一筆正式任務
    // 回傳true代表成功規劃出新任務(可能是跨樓層的下一個樓梯間，也可能是同樓層的下一個
    // 分岔格/本層樓梯間/出口)；回傳false代表目前完全找不到任何可走的方向(受困)。
    boolean planNextAdvice(int currentTick) {
        List<int[]> path = routeFinder.computeSmartPath(z, y, x, profile);
        if (path == null || path.size() < 2) return false;

        int idx = routeFinder.findDecisionPointIndex(path);
        int[] decision = path.get(idx);

        kpi.adviceIssuedTick = currentTick;
        if (kpi.adviceRevokedTick != null) {
            // 只要重新規劃出新的任務目標格(不論是否跨樓層)，就視為改道完成，結算撤回→改道延遲；
            // 整場模擬只記錄本人第一次撤回事件
            if (kpi.revokeToRerouteTicks == null) kpi.revokeToRerouteTicks = currentTick - kpi.adviceRevokedTick;
            kpi.adviceRevokedTick = null;
        }

        if (decision[0] != this.z) {
            // 下一步就要跨樓層(代表此刻本來就站在樓梯間格上，下一格是另一層的樓梯間)：
            // 沿用既有機制，先指派 targetStage，實際跨樓層動作留到下個tick開頭執行。
            this.targetStage = (Stage) space.building[decision[0]][decision[1]][decision[2]];
            context.onStageAssigned(); // 追蹤「跨樓層目標指派」次數
        } else {
            // 同樓層的下一個決策點(分岔格/本層樓梯間/出口)：快取路徑，之後每tick沿著走。
            junctionTargetPos = decision;
            junctionPath = new ArrayList<>(path.subList(1, idx + 1));
            junctionPathIdx = 0;
        }
        return true;
    }

    void randomMove(int steps) {
        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        for (int s = 0; s < steps; s++) {
            for (int i = 0; i < 4; i++) {
                int ny = y + dy[i], nx = x + dx[i];
                if (!space.isValid(z, ny, nx)) continue;
                Obj maybeExit = space.building[z][ny][nx];
                if (maybeExit instanceof Exit) {
                    // 【校正清單§1】出口也受門流量瓶頸限制：滿了就不走這條捷徑，
                    // 讓下面的一般選擇邏輯(同樣會檢查瓶頸)決定這tick怎麼辦。
                    if (!DoorFlowModel.hasCapacity(maybeExit)) continue;
                    this.y = ny; this.x = nx;
                    DoorFlowModel.consume(maybeExit);
                    checkStatus();
                    return;
                }
            }

            List<int[]> choices = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int ny = y + dy[i], nx = x + dx[i];
                if (!space.isValid(z, ny, nx)) continue;
                Obj next = space.building[z][ny][nx];
                // 【校正清單§1】改呼叫routeFinder.isPassable()，跟其餘路徑規劃邏輯共用同一套
                // 通行判斷(含牆/被封鎖的門/火/門與出口的流量瓶頸)，不再各自維護一份判斷。
                if (!routeFinder.isPassable(next)) continue;
                choices.add(new int[]{z, ny, nx});
            }

            if (space.building[z][y][x] instanceof Stage) {
                Stage st = (Stage) space.building[z][y][x];
                if (st.upfloor != null && !st.upfloor.fire) choices.add(new int[]{st.upfloor.z, st.upfloor.y, st.upfloor.x});
                if (st.downfloor != null && !st.downfloor.fire) choices.add(new int[]{st.downfloor.z, st.downfloor.y, st.downfloor.x});
            }

            if (choices.isEmpty()) break;
            int[] chosen = choices.get(rng.nextInt(choices.size()));
            Obj chosenCell = space.building[chosen[0]][chosen[1]][chosen[2]];
            this.z = chosen[0]; this.y = chosen[1]; this.x = chosen[2];
            DoorFlowModel.consume(chosenCell);
            checkStatus();
            if (isDead || isEscaped) break;
        }
    }

    // 加入 CO 致命判定(改用Purser FED_CO劑量模型)，並記錄高煙/臨界CO暴露統計
    void checkStatus() {
        Obj cell = space.building[z][y][x];

        // 不論最後死活，只要曾經符合條件就記錄下來
        if (cell.smoke > HIGH_SMOKE_THRESHOLD) kpi.everEnteredHighSmoke = true;
        if (this.fedCO >= PhysicalConstants.FED_INCAPACITATION) kpi.everReachedCriticalCO = true;

        // 死亡條件：熱暴露劑量或CO吸入劑量，任一者累積達到致死劑量門檻(FED模型，
        // 兩者採同一套「隨時間累積」邏輯，見PhysicalConstants§5b)。不再讓「踩到
        // 火格」單獨、無條件地判死——現實中平均只有20~40%的火災死亡是燒傷致死，
        // 多數其實死於CO/煙霧吸入；讓熱暴露也走劑量累積模型，兩種死因的比例才會
        // 隨溫度/濃度隨時間變化自然浮現，而不是被「碰到火=100%瞬間死」這條規則
        // 直接支配、蓋掉CO致死的可能性。
        if (this.fedThermal >= PhysicalConstants.FED_THERMAL_LETHAL || this.fedCO >= PhysicalConstants.FED_LETHAL) {
            isDead = true;
            deathCause = (this.fedThermal >= PhysicalConstants.FED_THERMAL_LETHAL) ? DeathCause.FIRE : DeathCause.CO_POISONING;
        }
        else if (cell instanceof Exit) {
            isEscaped = true;
        }
    }

    // ─── 【校正清單§5】CO吸入劑量：Purser FED_CO模型 ─────────────────────
    // 用當下所在位置的CO濃度(ppm，見EnvironmentSimulator生成/擴散的coPpm欄位)
    // 搭配呼吸每分鐘通氣量(RMV，隨恐慌程度在輕度活動~劇烈活動間內插)，依Purser
    // 公式換算成本tick的%COHb增量，除以個人易感度(coSusceptibilityD，即這個人
    // 達到失能/致死所需的%COHb臨界值)得到FED增量，累加進fedCO。
    // 起火原因對CO毒性的影響已經改成掛在EnvironmentSimulator生成coPpm的速率上
    // (FireCause.representativeFuel + FireChemistry)，這裡不再需要另外查詢
    // context——原本的effectiveCoThreshold()/smokeToleranceFactor()依賴因此
    // 一併移除。
    void absorbCO() {
        double coPpm = space.building[z][y][x].coPpm;
        if (coPpm <= 0.0) return;

        double rmv = PhysicalConstants.RMV_LIGHT_ACTIVITY
            + panic.panicLevel * (PhysicalConstants.RMV_HEAVY_ACTIVITY - PhysicalConstants.RMV_LIGHT_ACTIVITY);
        double dtMinutes = PhysicalConstants.TICK_SECONDS / 60.0;

        double cohbIncrement = PhysicalConstants.FED_CO_CONST
            * Math.pow(coPpm, PhysicalConstants.FED_CO_EXPONENT)
            * rmv * dtMinutes;

        fedCO += cohbIncrement / coSusceptibilityD;
    }

    // ─── §5b 熱暴露劑量：Purser對流熱耐受時間模型 ─────────────────────────
    // 用當下所在位置的氣體溫度(tempC，見EnvironmentSimulator依HRR成長曲線生成/
    // 擴散的溫度場)算出這個溫度下人體的耐受時間t_I，本tick經過的時間(dtMinutes)
    // 除以t_I得到這個tick的FED增量，累加進fedThermal，跟absorbCO()是同一套
    // 「累積劑量」寫法。溫度未高於常溫時沒有額外熱暴露，直接跳過，避免呼叫
    // Math.exp()產生無意義的極小增量。
    void absorbThermal() {
        double tempC = space.building[z][y][x].tempC;
        if (tempC <= PhysicalConstants.AMBIENT_TEMP_C) return;

        double toleranceMinutes = Math.exp(PhysicalConstants.FED_THERMAL_TOLERANCE_A
            - PhysicalConstants.FED_THERMAL_TOLERANCE_B * tempC);
        double dtMinutes = PhysicalConstants.TICK_SECONDS / 60.0;

        fedThermal += dtMinutes / toleranceMinutes;
    }
}
