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
// 「起火原因對煙霧毒性的倍率」這幾項原本會跨類別反向耦合的資訊。
class People {
    int id, z, y, x, speed;
    Space space;
    boolean isDead = false, isEscaped = false;
    Stage targetStage = null;

    // 人物屬性與一氧化碳追蹤
    PersonProfile profile;
    double accumulatedCO = 0.0;
    double coThreshold;

    // 【降低耦合】共用的 Random 與少數幾個逃生策略需要的常數，原本是 private，
    // 現在放寬為 package-private，讓同套件下的 DefaultEscapeStrategy / SmartEscapeStrategy
    // 可以直接使用，呼叫 rng 的時機與次序跟原本完全相同，不影響模擬亂數序列。
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

    // ─── 帶小孩：尋找/確認小孩安全 ───────────────────────────────
    int childGatherDelay = 0;

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

    // 【曾經進入高煙區/CO臨界暴露】判定門檻常數
    private static final double HIGH_SMOKE_THRESHOLD = 0.7;  // 沿用程式既有的「濃煙」判定門檻
    private static final double CRITICAL_CO_RATIO = 0.8;     // 累積CO達到「個人耐受閾值」的80%視為臨界暴露

    // 【修改】建構子：多了 context(模擬控制器依賴窗口) 與 strategy(逃生決策策略)兩個參數，
    // 兩者都由呼叫端(Simulator.runOneSimulation)在建立每一位 People 時依當次模擬的 mode 決定。
    public People(int id, int z, int y, int x, Space space, PersonProfile profile,
                  SimulationContext context, EscapeStrategy strategy) {
        this.id = id; this.z = z; this.y = y; this.x = x;
        this.space = space;
        this.profile = profile;
        this.accumulatedCO = 0.0;
        this.context = context;
        this.strategy = strategy;

        double panicSusceptibility;

        // 根據不同屬性，初始化不同的速度與 CO 耐受度
        switch (profile) {
            case NORMAL_SOLO:
            case STAFF:
            case CUSTOMER:
                this.speed = 3;
                this.coThreshold = 25.0; // 一般成年人耐受度高
                panicSusceptibility = 0.3;
                break;
            case WITH_CHILD:
                this.speed = 2;          // 帶小孩移動變慢
                this.coThreshold = 20.0; // 幼童較脆弱，整體耐受度下降
                panicSusceptibility = 0.6;
                this.childGatherDelay = 3 + rng.nextInt(6); // 一開始要花幾個tick尋找/確認小孩安全才會開始逃生
                break;
            case IMPAIRED:
                this.speed = 1;          // 行動不便者移動慢
                this.coThreshold = 15.0; // 耐受度較低
                panicSusceptibility = 0.8;
                break;
            case ELDERLY:
                this.speed = 1;          // 年長者移動慢
                this.coThreshold = 12.0; // 身體最脆弱，致死閾值最低
                panicSusceptibility = 0.7;
                break;
            default:
                this.speed = 3;
                this.coThreshold = 25.0;
                panicSusceptibility = 0.3;
                break;
        }

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
            return false;
        }
        return true;
    }

    // ────────────────────────────────────────────────────────
    // 【共用】依現場視野(BFS)做出的直覺逃生移動，
    // Default模式固定使用；Smart模式在斷線/不服從建議時也會退回使用這套邏輯
    // ────────────────────────────────────────────────────────
    void instinctiveEscapeStep(int currentTick) {
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
                 this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
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
        this.y = by; this.x = bx;
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
                if (space.building[z][ny][nx] instanceof Exit) {
                    this.y = ny; this.x = nx;
                    checkStatus();
                    return;
                }
            }

            List<int[]> choices = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int ny = y + dy[i], nx = x + dx[i];
                if (!space.isValid(z, ny, nx)) continue;
                Obj next = space.building[z][ny][nx];
                if (next instanceof Wall) continue;
                if (next instanceof Door && ((Door) next).blocked) continue;
                if (next.fire) continue;
                choices.add(new int[]{z, ny, nx});
            }

            if (space.building[z][y][x] instanceof Stage) {
                Stage st = (Stage) space.building[z][y][x];
                if (st.upfloor != null && !st.upfloor.fire) choices.add(new int[]{st.upfloor.z, st.upfloor.y, st.upfloor.x});
                if (st.downfloor != null && !st.downfloor.fire) choices.add(new int[]{st.downfloor.z, st.downfloor.y, st.downfloor.x});
            }

            if (choices.isEmpty()) break;
            int[] chosen = choices.get(rng.nextInt(choices.size()));
            this.z = chosen[0]; this.y = chosen[1]; this.x = chosen[2];
            checkStatus();
            if (isDead || isEscaped) break;
        }
    }

    // 加入 CO 致命判定，並記錄高煙/臨界CO暴露統計
    void checkStatus() {
        Obj cell = space.building[z][y][x];

        // 不論最後死活，只要曾經符合條件就記錄下來
        if (cell.smoke > HIGH_SMOKE_THRESHOLD) kpi.everEnteredHighSmoke = true;
        if (this.accumulatedCO >= CRITICAL_CO_RATIO * this.effectiveCoThreshold()) kpi.everReachedCriticalCO = true;

        // 死亡條件：踩到火，或者 CO 累積超過該角色的「有效」閾值(角色卡基準閾值 × 本場起火原因的煙霧毒性倍率)
        if (cell.fire || this.accumulatedCO >= this.effectiveCoThreshold()) {
            isDead = true;
        }
        else if (cell instanceof Exit) {
            isEscaped = true;
        }
    }

    // ─── 煙霧致命閾值跟起火原因掛勾 ────────────────────────────────
    // 角色卡原本的 coThreshold 只反映「個人體質」(成年人/幼童/行動不便者/年長者)；
    // 再疊加「這場火的起火原因」對煙霧毒性的影響──化學/縱火起火的煙霧毒性較高，
    // 同樣的個人體質下，有效閾值會被下修，代表同樣的CO累積量下更快達到致命條件。
    double effectiveCoThreshold() {
        // 【降低耦合】原本直接讀 Simulator.currentCause.smokeToleranceFactor，
        // 現在透過 context 取得同樣的倍率，People 不再需要認得 Simulator/FireCause。
        return this.coThreshold * context.smokeToleranceFactor();
    }
}
