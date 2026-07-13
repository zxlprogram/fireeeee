package simulator;
//請至main函數調整模擬資訊，如果想要改參數請直接跟我說，除非你是屎山代碼終結者
import java.io.*;
import java.util.*;

class sim {
    public static void main(String[]args) {
        Simulator s = new Simulator();
        //輸入:模擬次數、樓高範圍、樓寬範圍、樓長範圍
        //輸出:平均逃脫所需時間、首次正確決策時間、最終數據報告、過程文檔(會隨著模擬次數被覆蓋，只會看到最後一次模擬的文檔)
        //注意事項:時間複雜度超大，不建議輸入大於1000000立方單位的房子(這個數字是樂觀估計，實際上越小越好)
        s.work(100,new Range(1,11),new Range(5,51),new Range(5,51));
    }
}
// ─── 基礎物件 ────────────────────────────────────────────────
class Obj {
    boolean fire;
    double smoke = 0.0; // 煙霧數值 (0.0 ~ 1.0)
}
class Door extends Obj {
    boolean blocked = false; // true 代表已被火封住
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
    public void setLocation(int z, int y, int x) {
        this.z = z; this.y = y; this.x = x;
    }
}

// ─── 空間容器 ─────────────────────────────────────────────────
class Space {
    Obj[][][] building;
    int height, rows, cols;

    public Space(Obj[][][] building) {
        this.building = building;
        this.height   = building.length;
        this.rows     = building[0].length;
        this.cols     = building[0][0].length;
    }

    public boolean isValid(int z, int y, int x) {
        return z >= 0 && z < height && y >= 0 && y < rows && x >= 0 && x < cols;
    }
}

// ─── 感測器網路 ────────────────────────────────────────────────
class Detector {
    int z, y, x;
    boolean broken = false; // 是否已損壞（被火燒毀，或隨機故障），損壞後系統保守視為此處已經著火
    boolean danger = false; // 系統「目前讀到」的濃煙警報，會受感測誤差影響，不代表現場真實狀況

    private static final double MALFUNCTION_CHANCE = 0.002; // 每個tick隨機故障(跟火無關，例如電力/線路/機件問題)的機率
    private static final double NOISE_STDDEV = 0.15;        // 讀數誤差的標準差，誤差可能讓讀數偏高，也可能偏低
    private static final double DANGER_THRESHOLD = 0.7;     // 判定「濃煙警戒」的煙霧濃度門檻

    public Detector(int z, int y, int x) {
        this.z = z; this.y = y; this.x = x;
    }

    void update(Obj[][][] map, Random rng) {
        // 已經損壞的感測器不會再回報新讀數，維持在「未知、系統保守視為危險」的狀態
        if (broken) return;

        // 真的被火燒到：物理損毀，跟感測誤差無關
        if (map[z][y][x].fire) {
            broken = true;
            return;
        }

        // 隨機故障：例如電力不穩、線路老化、機件本身問題，跟現場是否有火無關
        if (rng.nextDouble() < MALFUNCTION_CHANCE) {
            broken = true;
            return;
        }

        // 正常運作時，讀數仍帶有誤差：真實煙霧濃度 + 常態分布雜訊，可能誤報（偏高）也可能漏報（偏低）
        double perceivedSmoke = map[z][y][x].smoke + rng.nextGaussian() * NOISE_STDDEV;
        danger = perceivedSmoke > DANGER_THRESHOLD;
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

//─── 人物類別 ─────────────────────────────────────────────────
class People {
 int id, z, y, x, speed;
 Space space;
 boolean isDead = false, isEscaped = false;
 Stage targetStage = null;

 // 【新增屬性】人物狀態與一氧化碳追蹤
 PersonProfile profile;
 double accumulatedCO = 0.0;
 double coThreshold; 

 private static final Random rng = new Random();

 // ─── 系統連線 / 定位相關(批次模擬失效變數) ─────────────────────
 boolean networkConnected = true;   // 是否仍與智慧系統保持連線(手機沒電或訊號斷線都會導致false)
 private boolean phoneHasBattery;   // 手機是否還有電
 double posErrorY, posErrorX;       // 定位誤差(格)，只影響系統端「回報位置」，不影響實際移動與判定
 private static final double PHONE_DEAD_INIT_CHANCE  = 0.05;  // 一開始手機就沒電/沒帶在身上的機率
 private static final double PHONE_DEAD_TICK_CHANCE  = 0.0015;// 每tick手機耗盡電力(緊張時頻繁操作)的機率
 private static final double NETWORK_DROP_TICK_CHANCE = 0.001;// 每tick訊號/節點斷線的機率
 private static final double POS_ERROR_STDDEV = 1.5;          // 定位誤差標準差(格)

 // ─── 服從率 ──────────────────────────────────────────────────
 private static final double COMPLIANCE_RATE = 0.85; // 收到系統建議時，實際照做的機率；其餘時間依自身直覺行動

 // ─── 恐慌 ────────────────────────────────────────────────────
 double panicLevel = 0.0;           // 0.0(冷靜) ~ 1.0(極度恐慌)，隨所在位置煙霧濃度動態調整
 private double panicSusceptibility;// 各角色的恐慌易感度，帶小孩/年長者/行動不便者較高
 private static final double PANIC_DELAY_CHANCE_FACTOR = 0.15; // panicLevel * 此係數 = 該tick「愣住不動」的機率
 private static final double PANIC_MISOP_CHANCE_FACTOR = 0.20; // panicLevel * 此係數 = 該tick「誤操作/不服從建議」的機率

 // ─── 帶小孩：尋找/確認小孩安全 ───────────────────────────────
 int childGatherDelay = 0;

 // ─── 同行者 ──────────────────────────────────────────────────
 Integer companionId = null;
 private static final double COMPANION_WAIT_DISTANCE = 4;
 private static final double COMPANION_WAIT_CHANCE = 0.5;

 // ─── 行動不便者：原地等待救援 ───────────────────────────────
 boolean waitingForRescue = false;
 private static final double IMPAIRED_WAIT_CHANCE = 0.02; // 每tick(在還不算立即危險時)選擇改採等待策略的機率

 // ─── KPI追蹤用旗標 ───────────────────────────────────────────
 boolean systemIdentifiedVulnerable = false; // 系統(連線狀態下)是否已辨識並標記此人為需優先協助對象
 Integer firstCorrectDecisionTick = null;    // 第一次做出「有依據(非隨機亂走)」移動決策的tick
 boolean everWrongRoute = false;             // 是否曾依現有資訊做出決策，卻仍走進高煙區(資訊落後於現場)
 boolean everRerouted = false;               // 原定路線(樓梯/通道)在移動前失效，是否曾被迫重新規劃
 int rerouteAttempts = 0;                    // 總共被迫重新規劃路線的次數
 Integer reportedTrappedTick = null;         // 系統端最早得知此人「受困/卡住」位置的tick

 // 【修改】建構子：傳入 profile，並根據屬性自動決定速度與 CO 閾值
 public People(int id, int z, int y, int x, Space space, PersonProfile profile) {
     this.id = id; this.z = z; this.y = y; this.x = x;
     this.space = space;
     this.profile = profile;
     this.accumulatedCO = 0.0;

     // 根據不同屬性，初始化不同的速度與 CO 耐受度
     switch (profile) {
         case NORMAL_SOLO:
         case STAFF:
         case CUSTOMER:
             this.speed = 3;
             this.coThreshold = 25.0; // 一般成年人耐受度高
             this.panicSusceptibility = 0.3;
             break;
         case WITH_CHILD:
             this.speed = 2;          // 帶小孩移動變慢
             this.coThreshold = 20.0; // 幼童較脆弱，整體耐受度下降
             this.panicSusceptibility = 0.6;
             this.childGatherDelay = 3 + rng.nextInt(6); // 一開始要花幾個tick尋找/確認小孩安全才會開始逃生
             break;
         case IMPAIRED:
             this.speed = 1;          // 行動不便者移動慢
             this.coThreshold = 15.0; // 耐受度較低
             this.panicSusceptibility = 0.8;
             break;
         case ELDERLY:
             this.speed = 1;          // 年長者移動慢
             this.coThreshold = 12.0; // 身體最脆弱，致死閾值最低
             this.panicSusceptibility = 0.7;
             break;
     }

     // 系統連線初始狀態：手機本身有沒有電，決定了一開始是否連得上智慧系統
     this.phoneHasBattery = rng.nextDouble() >= PHONE_DEAD_INIT_CHANCE;
     this.networkConnected = this.phoneHasBattery;

     // 定位誤差：系統看到的位置跟實際位置會有落差，只用於「系統端回報」，不影響本人實際移動
     this.posErrorY = rng.nextGaussian() * POS_ERROR_STDDEV;
     this.posErrorX = rng.nextGaussian() * POS_ERROR_STDDEV;
 }

 private String key(int z, int y, int x) { 
     return z + "," + y + "," + x; 
 }

 private int bitKey(int z, int y, int x) {
     return (z << 20) | (y << 10) | x;
 }

 private int[] decodeKey(int key) {
     return new int[]{ (key >> 20) & 0x3FF, (key >> 10) & 0x3FF, key & 0x3FF };
 }

 // ────────────────────────────────────────────────────────
 // 【傳統逃生函數】保留原汁原味字串鍵值，不與感測器聯網
 // ────────────────────────────────────────────────────────
 public void escape(int currentTick) {
	 this.accumulatedCO += space.building[z][y][x].smoke;
     checkStatus();
     if (isDead || isEscaped) return;

     updateConnectivity();
     updatePanic();

     if (targetStage != null) {
         if (targetStage.fire) {
             // 原本規劃要走的樓梯間已經失火，原路線失效，這個tick先取消目標，等下個tick重新規劃改道
             System.out.println("[REROUTE] tick=" + currentTick
                 + " id=" + id
                 + " pos=(" + z + "," + y + "," + x + ")"
                 + " selfSmoke=" + String.format("%.2f", space.building[z][y][x].smoke)
                 + " selfFire=" + space.building[z][y][x].fire
                 + " targetStage=(" + targetStage.z + "," + targetStage.y + "," + targetStage.x + ")"
                 + " targetSmoke=" + String.format("%.2f", targetStage.smoke)
                 + " rerouteAttempts(before)=" + rerouteAttempts);
             targetStage = null;
             rerouteAttempts++;
             everRerouted = true;
         } else {
             this.z = targetStage.z; this.y = targetStage.y; this.x = targetStage.x;
             targetStage = null;
             checkStatus();
             if (isDead || isEscaped) return;
         }
     }

     // 帶小孩：一開始要花幾個tick尋找/確認小孩安全，這段期間不會主動逃生
     if (childGatherDelay > 0) {
         childGatherDelay--;
         return;
     }

     // 行動不便者：可能選擇原地等待救援，而不是自行冒險移動
     if (profile == PersonProfile.IMPAIRED) {
         updateWaitingForRescue();
         if (waitingForRescue) {
             markReportedTrapped(currentTick);
             return;
         }
     }

     // 同行者：同伴落後太多時，可能選擇等待或折返確認同伴狀況
     if (waitForCompanion(currentTick)) {
         markReportedTrapped(currentTick);
         return;
     }

     // 恐慌：反應延遲，這個tick可能整個人愣住沒有行動
     if (rng.nextDouble() < panicLevel * PANIC_DELAY_CHANCE_FACTOR) return;

     instinctiveEscapeStep(currentTick);
 }

 // ────────────────────────────────────────────────────────
 // 【共用】依現場視野(BFS)做出的直覺逃生移動，
 // Default模式固定使用；Smart模式在斷線/不服從建議時也會退回使用這套邏輯
 // ────────────────────────────────────────────────────────
 private void instinctiveEscapeStep(int currentTick) {
     // 恐慌：誤操作，這個tick不會照理性判斷走，而是隨便亂走
     boolean panicMisstep = rng.nextDouble() < panicLevel * PANIC_MISOP_CHANCE_FACTOR;
     boolean inSmoke = space.building[z][y][x].smoke > 0.7;

     if (inSmoke || panicMisstep) {
         randomMove(speed);
         return;
     }

    boolean moved = false;
    for (int step = 0; step < speed; step++) {
        int[] nextPos = findNextStepLosBFS();
        if (nextPos == null) {
            // 視野內看不到出口，但只要沒有濃煙擋住判斷，仍先嘗試憑方向感朝出口移動，
            // 而不是直接放棄、整段路程都用亂走(randomMove)處理
            nextPos = findNextStepTowardExit();
        }
        if (nextPos == null) {
            if (everRerouted) {
                System.out.println("[REROUTE-STUCK] tick=" + currentTick
                    + " id=" + id
                    + " pos=(" + z + "," + y + "," + x + ")"
                    + " selfSmoke=" + String.format("%.2f", space.building[z][y][x].smoke)
                    + " rerouteAttempts=" + rerouteAttempts
                    + " -> no LOS path and no full-map path found, falling to randomMove");
            }
            markReportedTrapped(currentTick); break;
        }
        moved = true;

        if (nextPos[0] != this.z) {
            this.targetStage = (Stage) space.building[nextPos[0]][nextPos[1]][nextPos[2]];
            Simulator.stageAssignCount++; // 【新增】追蹤「跨樓層目標指派」次數，是改道事件的前提
            break;
         } else {
             this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
             if (firstCorrectDecisionTick == null) firstCorrectDecisionTick = currentTick;
             checkStatus();
             if (isDead || isEscaped) break;

             if (space.building[z][y][x].smoke > 0.7) {
                 // 依當下已知資訊判斷的路線，卻走進高煙區，代表資訊落後於現場實際狀況
                 everWrongRoute = true;
                 randomMove(speed - step - 1);
                 break;
             }
         }
     }
     if (!moved) randomMove(speed);
 }

 // ────────────────────────────────────────────────────────
 // 【批次模擬失效變數】手機沒電 / 網路節點斷線，會讓系統建議失效
 // ────────────────────────────────────────────────────────
 private void updateConnectivity() {
     if (phoneHasBattery && rng.nextDouble() < PHONE_DEAD_TICK_CHANCE) {
         phoneHasBattery = false; // 緊張時頻繁操作手機求救/查看地圖，電力耗盡
     }
     if (networkConnected && rng.nextDouble() < NETWORK_DROP_TICK_CHANCE) {
         networkConnected = false; // 現場訊號不穩或節點斷線
     }
     if (!phoneHasBattery) {
         networkConnected = false; // 沒電就完全無法再跟系統互動
     }
 }

 // ────────────────────────────────────────────────────────
 // 【恐慌模型】所在位置煙霧濃度越高、角色恐慌易感度越高，恐慌值越高
 // ────────────────────────────────────────────────────────
 private void updatePanic() {
     double localSmoke = space.building[z][y][x].smoke;
     double target = Math.min(1.0, localSmoke + panicSusceptibility * 0.3);
     panicLevel += (target - panicLevel) * 0.3;
     if (panicLevel < 0) panicLevel = 0;
 }

 // ────────────────────────────────────────────────────────
 // 【行動不便者】等待救援行為：尚無立即危險時，有機率選擇原地等待，
 // 一旦所在位置煙霧開始升高到危險程度，被迫放棄等待、嘗試自行移動
 // ────────────────────────────────────────────────────────
 private void updateWaitingForRescue() {
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
 private boolean waitForCompanion(int currentTick) {
     if (companionId == null) return false;
     People companion = Simulator.allPeopleById.get(companionId);
     if (companion == null || companion.isDead || companion.isEscaped) return false;

     int dist = Math.abs(this.z - companion.z) * 100 + Math.abs(this.y - companion.y) + Math.abs(this.x - companion.x);
     if (dist <= COMPANION_WAIT_DISTANCE) return false;
     if (rng.nextDouble() >= COMPANION_WAIT_CHANCE) return false;

     // 回報同行者狀態：讓系統知道這兩人是一組，目前彼此距離多遠、選擇了等待/折返
     if (networkConnected) {
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
         if (!isPassable(space.building[z][ny][nx])) continue;
         int d = Math.abs(ny - ty) + Math.abs(nx - tx);
         if (d < bestDist) { bestDist = d; by = ny; bx = nx; }
     }
     this.y = by; this.x = bx;
     checkStatus();
 }

 // ────────────────────────────────────────────────────────
 // 系統端最早得知此人受困/卡住位置的時刻(需在連線狀態下才會被回報)
 // ────────────────────────────────────────────────────────
 private void markReportedTrapped(int currentTick) {
     if (networkConnected && reportedTrappedTick == null) {
         reportedTrappedTick = currentTick;
         // 【定位不準】系統回報給救援端的位置帶有定位誤差，跟本人實際座標不完全相同，
         // 這裡把「系統看到的座標」印進過程紀錄，代表定位誤差確實影響了救援端收到的資訊(而不是記錄了卻沒人用的死資料)
         int reportedY = (int) Math.round(y + posErrorY);
         int reportedX = (int) Math.round(x + posErrorX);
         System.out.println("【系統】T" + currentTick + "：偵測到 #" + id + " 受困，回報座標(含定位誤差) = ("
                 + z + ", " + reportedY + ", " + reportedX + ")，實際座標 = (" + z + ", " + y + ", " + x + ")");
     }
 }

 private int[] findNextStepLosBFS() {
     Set<String> visibleKeys = computeVisibleCells();

     int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
     Queue<int[]> queue = new LinkedList<>();
     Map<String, String> parentMap = new HashMap<>();
     Set<String> visited = new HashSet<>();

     String startKey = key(z, y, x);
     queue.add(new int[]{z, y, x});
     visited.add(startKey);
     int[] targetPos = null;

     while (!queue.isEmpty()) {
         int[] curr = queue.poll();
         Obj currObj = space.building[curr[0]][curr[1]][curr[2]];

         if (currObj instanceof Exit) { targetPos = curr; break; }

         for (int i = 0; i < 4; i++) {
             int nz = curr[0], ny = curr[1] + dy[i], nx = curr[2] + dx[i];
             if (!space.isValid(nz, ny, nx)) continue;

             String nextKey = key(nz, ny, nx);
             if (visited.contains(nextKey)) continue;

             Obj nextObj = space.building[nz][ny][nx];
             if (!isPassable(nextObj)) continue;
             if (!visibleKeys.contains(nextKey)) continue;

             visited.add(nextKey);
             parentMap.put(nextKey, key(curr[0], curr[1], curr[2]));
             queue.add(new int[]{nz, ny, nx});
         }

         if (currObj instanceof Stage) {
             Stage s = (Stage) currObj;
             for (Stage next : new Stage[]{s.upfloor, s.downfloor}) {
                 if (next == null) continue;
                 if (next.fire) continue;
                 String nextKey = key(next.z, next.y, next.x);
                 if (visited.contains(nextKey)) continue;
                 visited.add(nextKey);
                 parentMap.put(nextKey, key(curr[0], curr[1], curr[2]));
                 queue.add(new int[]{next.z, next.y, next.x});
             }
         }
     }

     if (targetPos == null) return null;

     String currKey = key(targetPos[0], targetPos[1], targetPos[2]);
     while (parentMap.containsKey(currKey) && !parentMap.get(currKey).equals(startKey))
         currKey = parentMap.get(currKey);

     String[] tokens = currKey.split(",");
     return new int[]{
         Integer.parseInt(tokens[0]),
         Integer.parseInt(tokens[1]),
         Integer.parseInt(tokens[2])
     };
 }

 // ────────────────────────────────────────────────────────
 // 【新增】沒有濃煙時的備援邏輯：即使出口不在視野(LOS)範圍內，
 // 只要現場沒有濃煙阻擋視線/判斷，人員仍應憑對建築的基本方向感
 // (例如逃生指示燈、樓層配置)嘗試朝出口方向移動，而不是直接亂走。
 // 因此這裡不受 computeVisibleCells() 的視野限制，走全域BFS找出口。
 // ────────────────────────────────────────────────────────
 private int[] findNextStepTowardExit() {
     int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
     Queue<int[]> queue = new LinkedList<>();
     Map<String, String> parentMap = new HashMap<>();
     Set<String> visited = new HashSet<>();

     String startKey = key(z, y, x);
     queue.add(new int[]{z, y, x});
     visited.add(startKey);
     int[] targetPos = null;

     while (!queue.isEmpty()) {
         int[] curr = queue.poll();
         Obj currObj = space.building[curr[0]][curr[1]][curr[2]];

         if (currObj instanceof Exit) { targetPos = curr; break; }

         for (int i = 0; i < 4; i++) {
             int nz = curr[0], ny = curr[1] + dy[i], nx = curr[2] + dx[i];
             if (!space.isValid(nz, ny, nx)) continue;

             String nextKey = key(nz, ny, nx);
             if (visited.contains(nextKey)) continue;

             Obj nextObj = space.building[nz][ny][nx];
             if (!isPassable(nextObj)) continue;

             visited.add(nextKey);
             parentMap.put(nextKey, key(curr[0], curr[1], curr[2]));
             queue.add(new int[]{nz, ny, nx});
         }

         if (currObj instanceof Stage) {
             Stage s = (Stage) currObj;
             for (Stage next : new Stage[]{s.upfloor, s.downfloor}) {
                 if (next == null) continue;
                 if (next.fire) continue;
                 String nextKey = key(next.z, next.y, next.x);
                 if (visited.contains(nextKey)) continue;
                 visited.add(nextKey);
                 parentMap.put(nextKey, key(curr[0], curr[1], curr[2]));
                 queue.add(new int[]{next.z, next.y, next.x});
             }
         }
     }

     if (targetPos == null) return null;

     String currKey = key(targetPos[0], targetPos[1], targetPos[2]);
     while (parentMap.containsKey(currKey) && !parentMap.get(currKey).equals(startKey))
         currKey = parentMap.get(currKey);

     String[] tokens = currKey.split(",");
     return new int[]{
         Integer.parseInt(tokens[0]),
         Integer.parseInt(tokens[1]),
         Integer.parseInt(tokens[2])
     };
 }

 private Set<String> computeVisibleCells() {
     Set<String> visible = new HashSet<>();
     visible.add(key(z, y, x));

     int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};

     for (int dir = 0; dir < 4; dir++) {
         int cy = y, cx = x;
         while (true) {
             int ny = cy + dy[dir], nx = cx + dx[dir];
             if (!space.isValid(z, ny, nx)) break;

             Obj obj = space.building[z][ny][nx];
             String k = key(z, ny, nx);

             if (obj instanceof Exit) { visible.add(k); break; }
             if (obj instanceof Wall) break;
             if ((obj instanceof Door && ((Door) obj).blocked) || obj.fire) { visible.add(k); break; }
             if (obj instanceof Stage) { visible.add(k); break; }

             visible.add(k);
             cy = ny; cx = nx;
         }
     }

     int[] qdy = {-1, -1, 1, 1}, qdx = {-1, 1, -1, 1};
     for (int dir = 0; dir < 4; dir++) {
         for (int step = 1; ; step++) {
             int ny = y + qdy[dir] * step, nx = x + qdx[dir] * step;
             if (!space.isValid(z, ny, nx)) break;

             boolean sideA = visible.contains(key(z, ny - qdy[dir], nx));
             boolean sideB = visible.contains(key(z, ny, nx - qdx[dir]));
             if (!sideA && !sideB) break;

             Obj obj = space.building[z][ny][nx];
             String k = key(z, ny, nx);

             if (obj instanceof Exit)  { visible.add(k); break; }
             if (obj instanceof Wall)  break;
             if ((obj instanceof Door && ((Door) obj).blocked) || obj.fire) { visible.add(k); break; }
             if (obj instanceof Stage) { visible.add(k); break; }

             visible.add(k);
         }
     }

     // 【員工】對場域熟悉，即使沒有直接視線也知道本層所有樓梯間(備用逃生動線)位置
     if (profile == PersonProfile.STAFF) {
         for (int by = 0; by < space.rows; by++) {
             for (int bx = 0; bx < space.cols; bx++) {
                 if (space.building[z][by][bx] instanceof Stage) {
                     visible.add(key(z, by, bx));
                 }
             }
         }
     }

     return visible;
 }

 // ────────────────────────────────────────────────────────
 // 【全新智慧逃生系統】聯網全域感測器 + 位元優化 + 雙階段避難
 // ────────────────────────────────────────────────────────
 public void SmartEscape(int currentTick) {
	 this.accumulatedCO += space.building[z][y][x].smoke;
     checkStatus();
     if (isDead || isEscaped) return;

     updateConnectivity();
     updatePanic();

     if (targetStage != null) {
         if (targetStage.fire) {
             // 原定樓梯間路線失效，取消目標，下個tick重新規劃改道
             System.out.println("[REROUTE][SMART] tick=" + currentTick
                 + " id=" + id
                 + " pos=(" + z + "," + y + "," + x + ")"
                 + " selfSmoke=" + String.format("%.2f", space.building[z][y][x].smoke)
                 + " selfFire=" + space.building[z][y][x].fire
                 + " targetStage=(" + targetStage.z + "," + targetStage.y + "," + targetStage.x + ")"
                 + " targetSmoke=" + String.format("%.2f", targetStage.smoke)
                 + " rerouteAttempts(before)=" + rerouteAttempts);
             targetStage = null;
             rerouteAttempts++;
             everRerouted = true;
         } else {
             this.z = targetStage.z; this.y = targetStage.y; this.x = targetStage.x;
             targetStage = null;
             checkStatus();
             if (isDead || isEscaped) return;
         }
     }

     // 帶小孩：一開始要花幾個tick尋找/確認小孩安全
     if (childGatherDelay > 0) {
         childGatherDelay--;
         return;
     }

     // 行動不便者：可能選擇原地等待救援
     if (profile == PersonProfile.IMPAIRED) {
         updateWaitingForRescue();
         if (waitingForRescue) {
             markReportedTrapped(currentTick);
             return;
         }
     }

     // 同行者：同伴落後太多時，可能選擇等待或折返
     if (waitForCompanion(currentTick)) {
         markReportedTrapped(currentTick);
         return;
     }

     // 系統辨識與標記：只要仍連線，行動不便者/年長者這類需優先協助對象會被系統標記
     if (networkConnected && (profile == PersonProfile.IMPAIRED || profile == PersonProfile.ELDERLY)) {
         systemIdentifiedVulnerable = true;
     }

     // 恐慌：反應延遲，這個tick整個人愣住
     if (rng.nextDouble() < panicLevel * PANIC_DELAY_CHANCE_FACTOR) return;

     // 服從率 + 連線狀態：斷線、或恐慌下不服從建議時，退回使用直覺逃生邏輯
     boolean panicNonCompliance = rng.nextDouble() < panicLevel * PANIC_MISOP_CHANCE_FACTOR;
     boolean usingSmartGuidance = networkConnected && !panicNonCompliance && rng.nextDouble() < COMPLIANCE_RATE;

     if (!usingSmartGuidance) {
         instinctiveEscapeStep(currentTick);
         return;
     }

     // 【只修測量、不改變行為】不強迫Smart在濃煙中放棄系統路徑規劃——
     // 系統的資訊來源是全域感測器網路，即使人正站在濃煙格裡，系統仍可能透過
     // 其他偵測器/已知火點資訊繼續規劃出相對安全的路線，所以繼續呼叫 computeSmartPath()。
     // 但為了不讓「持續待在同一段濃煙裡」被重複算成一次次新的「誤入危險路線」，
     // 用 inHazardNow 追蹤「目前是否處於濃煙中」，只有從「不在濃煙」轉為「進入濃煙」
     // 的那一瞬間才記一次；只要沒離開過濃煙，之後每一步都不會重複觸發。
     boolean inHazardNow = space.building[z][y][x].smoke > 0.7;

     boolean moved = false;
     for (int step = 0; step < speed; step++) {
         int[] nextPos = computeSmartPath();
         if (nextPos == null) { markReportedTrapped(currentTick); break; }
         moved = true;

        if (nextPos[0] != this.z) {
            this.targetStage = (Stage) space.building[nextPos[0]][nextPos[1]][nextPos[2]];
            Simulator.stageAssignCount++; // 【新增】追蹤「跨樓層目標指派」次數，是改道事件的前提
            break;
         } else {
             this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
             if (firstCorrectDecisionTick == null) firstCorrectDecisionTick = currentTick;
             checkStatus();
             if (isDead || isEscaped) break;

             boolean nowInSmoke = space.building[z][y][x].smoke > 0.7;
             if (nowInSmoke && !inHazardNow) {
                 // 系統建議的路線，實際走到時才發現煙霧已經超標(感測器/資訊延遲)——
                 // 只在「這一步剛好從乾淨格走進濃煙格」的瞬間記一次。
                 everWrongRoute = true;
             }
             inHazardNow = nowInSmoke;
         }
     }
     if (!moved) randomMove(speed);
 }

 private int[] computeSmartPath() {
     Set<Integer> knownHazards = new HashSet<>();
     Set<Integer> knownFires = new HashSet<>();

     for (Detector d : Simulator.detectors) {
         int k = bitKey(d.z, d.y, d.x);
         if (d.broken) {
             knownHazards.add(k); knownFires.add(k);
         } else if (d.danger) {
             knownHazards.add(k);
         }
     }

     Set<String> visibleCells = computeVisibleCells();
     for (String vKey : visibleCells) {
         String[] tokens = vKey.split(",");
         int vz = Integer.parseInt(tokens[0]);
         int vy = Integer.parseInt(tokens[1]);
         int vx = Integer.parseInt(tokens[2]);
         
         Obj localObj = space.building[vz][vy][vx];
         int lKey = bitKey(vz, vy, vx);
         
         if (localObj.fire) {
             knownFires.add(lKey); knownHazards.add(lKey);
         }
     }
     
     if (space.building[z][y][x] instanceof Stage) {
         Stage st = (Stage) space.building[z][y][x];
         for (Stage nextSt : new Stage[]{st.upfloor, st.downfloor}) {
             if (nextSt != null) {
                 int stKey = bitKey(nextSt.z, nextSt.y, nextSt.x);
                 if (nextSt.fire) {
                     knownFires.add(stKey); knownHazards.add(stKey);
                 } else if (nextSt.smoke > 0.7) {
                     knownHazards.add(stKey);
                 }
             }
         }
     }

     int startKey = bitKey(z, y, x);
     Map<Integer, Double> bestCost = new HashMap<>();
     Map<Integer, Integer> parentMap = new HashMap<>();
     PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));

     bestCost.put(startKey, 0.0);
     pq.add(new double[]{0.0, startKey});
     Integer targetPosKey = null;

     while (!pq.isEmpty()) {
         double[] top = pq.poll();
         double currCost = top[0];
         int currKey = (int) top[1];

         if (currCost > bestCost.getOrDefault(currKey, Double.MAX_VALUE)) continue;

         int[] c = decodeKey(currKey);
         Obj currObj = space.building[c[0]][c[1]][c[2]];

         if (currObj instanceof Exit) {
             targetPosKey = currKey; break;
         }

         int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
         for (int i = 0; i < 4; i++) {
             int nz = c[0], ny = c[1] + dy[i], nx = c[2] + dx[i];
             if (!space.isValid(nz, ny, nx)) continue;

             Obj nextObj = space.building[nz][ny][nx];
             if (!isPassable(nextObj)) continue;

             int nextKey = bitKey(nz, ny, nx);
             double moveCost = EXIT_STEP_WEIGHT + dangerCost(nz, ny, nx, knownFires, knownHazards);
             double newCost = currCost + moveCost;

             if (newCost < bestCost.getOrDefault(nextKey, Double.MAX_VALUE)) {
                 bestCost.put(nextKey, newCost);
                 parentMap.put(nextKey, currKey);
                 pq.add(new double[]{newCost, nextKey});
             }
         }

         if (currObj instanceof Stage) {
             Stage s = (Stage) currObj;
             for (Stage next : new Stage[]{s.upfloor, s.downfloor}) {
                 if (next == null) continue;
                 if (!isPassable(next)) continue;

                 int nextKey = bitKey(next.z, next.y, next.x);
                 double moveCost = EXIT_STEP_WEIGHT + dangerCost(next.z, next.y, next.x, knownFires, knownHazards);
                 double newCost = currCost + moveCost;

                 if (newCost < bestCost.getOrDefault(nextKey, Double.MAX_VALUE)) {
                     bestCost.put(nextKey, newCost);
                     parentMap.put(nextKey, currKey);
                     pq.add(new double[]{newCost, nextKey});
                 }
             }
         }
     }

     if (targetPosKey != null) {
         int curr = targetPosKey;
         while (parentMap.containsKey(curr) && parentMap.get(curr) != startKey) {
             curr = parentMap.get(curr);
         }
         return decodeKey(curr);
     }

     return maximizeSurvivalTime(knownFires, knownHazards);
 }

 private static final double EXIT_STEP_WEIGHT = 0.0;   
 private static final double DANGER_WEIGHT = 0.5;     
 private static final double FIRE_EXTRA_WEIGHT = 5.0; 

 private double dangerCost(int cz, int cy, int cx, Set<Integer> knownFires, Set<Integer> knownHazards) {
     double cost = 0.0;
     for (int fKey : knownFires) {
         int[] f = decodeKey(fKey);
         int dist = Math.abs(cz - f[0]) * 100 + Math.abs(cy - f[1]) + Math.abs(cx - f[2]);
         cost += FIRE_EXTRA_WEIGHT / (1.0 + dist);
     }
     for (int hKey : knownHazards) {
         if (knownFires.contains(hKey)) continue;
         int[] h = decodeKey(hKey);
         int dist = Math.abs(cz - h[0]) * 100 + Math.abs(cy - h[1]) + Math.abs(cx - h[2]);
         cost += DANGER_WEIGHT / (1.0 + dist);
     }
     return cost;
 }

 private int[] maximizeSurvivalTime(Set<Integer> knownFires, Set<Integer> knownHazards) {
     Set<Integer> threats = knownFires.isEmpty() ? knownHazards : knownFires;
     if (threats.isEmpty()) return null;

     int maxManhattanDist = -1;
     int[] bestMove = null;

     int myMinDist = Integer.MAX_VALUE;
     for (int tKey : threats) {
         int[] t = decodeKey(tKey);
         int mDist = Math.abs(this.z - t[0]) * 100 + Math.abs(this.y - t[1]) + Math.abs(this.x - t[2]);
         if (mDist < myMinDist) myMinDist = mDist;
     }
     maxManhattanDist = myMinDist;
     bestMove = new int[]{this.z, this.y, this.x};

     int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
     for (int i = 0; i < 4; i++) {
         int ny = y + dy[i], nx = x + dx[i];
         if (!space.isValid(z, ny, nx)) continue;

         Obj nextObj = space.building[z][ny][nx];
         if (!isPassable(nextObj)) continue; 

         int minDistToThreat = Integer.MAX_VALUE;
         for (int tKey : threats) {
             int[] t = decodeKey(tKey);
             int mDist = Math.abs(this.z - t[0]) * 100 + Math.abs(ny - t[1]) + Math.abs(nx - t[2]);
             if (mDist < minDistToThreat) {
                 minDistToThreat = mDist;
             }
         }

         if (minDistToThreat > maxManhattanDist) {
             maxManhattanDist = minDistToThreat;
             bestMove = new int[]{z, ny, nx};
         }
     }

     Obj currentObj = space.building[z][y][x];
     if (currentObj instanceof Stage) {
         Stage s = (Stage) currentObj;
         for (Stage nextStage : new Stage[]{s.upfloor, s.downfloor}) {
             if (nextStage == null) continue;
             if (!isPassable(nextStage)) continue;

             int minDistToThreat = Integer.MAX_VALUE;
             for (int tKey : threats) {
                 int[] t = decodeKey(tKey);
                 int mDist = Math.abs(nextStage.z - t[0]) * 100 + Math.abs(nextStage.y - t[1]) + Math.abs(nextStage.x - t[2]);
                 if (mDist < minDistToThreat) {
                     minDistToThreat = mDist;
                 }
             }

             if (minDistToThreat > maxManhattanDist) {
                 maxManhattanDist = minDistToThreat;
                 bestMove = new int[]{nextStage.z, nextStage.y, nextStage.x};
             }
         }
     }
     return bestMove;
 }

 private void randomMove(int steps) {
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

 // 【新增】曾經進入高煙區 / 曾經CO暴露達臨界值，用於事後統計（跟是否死亡無關）
 boolean everEnteredHighSmoke = false;
 boolean everReachedCriticalCO = false;
 private static final double HIGH_SMOKE_THRESHOLD = 0.7;  // 沿用程式既有的「濃煙」判定門檻
 private static final double CRITICAL_CO_RATIO = 0.8;     // 累積CO達到「個人耐受閾值」的80%視為臨界暴露

 // 【修改】加入 CO 致命判定，並記錄高煙/臨界CO暴露統計
 private void checkStatus() {
     Obj cell = space.building[z][y][x];

     // 【新增】不論最後死活，只要曾經符合條件就記錄下來
     if (cell.smoke > HIGH_SMOKE_THRESHOLD) everEnteredHighSmoke = true;
     if (this.accumulatedCO >= CRITICAL_CO_RATIO * this.coThreshold) everReachedCriticalCO = true;

     // 死亡條件：踩到火，或者 CO 累積超過該角色的專屬閾值
     if (cell.fire || this.accumulatedCO >= this.coThreshold) {
         if (everRerouted && !isDead) {
             String outcomeMsg = "[REROUTE-OUTCOME] id=" + id + " DIED pos=(" + z + "," + y + "," + x + ")"
                 + " byFire=" + cell.fire
                 + " accumulatedCO=" + String.format("%.2f", accumulatedCO)
                 + " coThreshold=" + coThreshold
                 + " rerouteAttempts=" + rerouteAttempts;
             System.out.println(outcomeMsg);
             System.err.println(outcomeMsg); // 【新增】鏡射一份到stderr，這樣不用開defaultResult.txt/smartResult.txt也看得到
         }
         isDead = true;
     } else if (cell instanceof Exit) {
         if (everRerouted && !isEscaped) {
             String outcomeMsg = "[REROUTE-OUTCOME] id=" + id + " ESCAPED pos=(" + z + "," + y + "," + x + ")"
                 + " rerouteAttempts=" + rerouteAttempts;
             System.out.println(outcomeMsg);
             System.err.println(outcomeMsg); // 【新增】鏡射一份到stderr
         }
         isEscaped = true;
     }
 }

 // 【修改】確認障礙物邏輯，防火門可通行
 private boolean isPassable(Obj o) {
     if (o instanceof Wall) return false;
     // 注意：若未來實作 FireDoor，因為它繼承自 Door，未被 blocked 時依然能過
     if (o instanceof Door && ((Door) o).blocked) return false;
     if (o.fire) return false;
     return true;
 }
}
class Range {
	int max,min;
	public Range(int min,int max) {
		this.min=min;
		this.max=max;
	}
}
// ─── 主模擬 ───────────────────────────────────────────────────
public class Simulator {
	enum FireCause {
	    ELECTRICAL, // 電線走火：初期火勢蔓延極快，但起初煙霧較少
	    CHEMICAL,   // 化學起火：化學煙霧蔓延極快，且致死率高
	    ARSON,      // 縱火：使用了助燃劑，火跟煙都很快
	    ACCIDENTAL  // 一般意外(預設)：正常的擴散速度
	}
	
    static Space space;
    static int surviveTime=5000;//活過5000時刻就算被救活，之後要改成滅火行動
    static List<People> peopleList = new ArrayList<>();
    static List<Detector> detectors = new ArrayList<>(); 
    // peopleList 會隨著死亡/逃脫被移除，所以另外保留一份不會被清空的登記表，
    // 讓「同行者」邏輯在同伴已經離開 peopleList 之後，仍能查到對方最後的狀態(存活/死亡/逃脫)
    static Map<Integer, People> allPeopleById = new HashMap<>();
    static int  tick   = 0;
    static FireCause currentCause = FireCause.ACCIDENTAL;
    static Random random = new Random();

    // 更新結算變數
    static double DTotalserviveP = 0; // Default生還率累加值
    static double STotalserviveP = 0; // Smart生還率累加值

    static double DHighSmokeP = 0;  // Default 進入高煙區比例累加值
    static double SHighSmokeP = 0;  // Smart   進入高煙區比例累加值
    static double DCriticalCOP = 0; // Default 臨界CO暴露比例累加值
    static double SCriticalCOP = 0; // Smart   臨界CO暴露比例累加值
    static double TotalAccuracySum = 0; // 總體準確率累加值
    static long TotalPeopleSum = 0; // 跨迭代的總人數累計

    // ─── 新增KPI累加值 ───────────────────────────────────────────
    static double DWrongRouteP = 0, SWrongRouteP = 0; // 錯誤決策後誤入危險路線比例累加值(分母固定是totalPeople，每場都有意義)

    // 以下幾項「不是每場模擬都會發生」(例如沒人需要改道、沒有需優先協助對象)，
    // 所以額外用一個計數器記錄「有意義的場次數」，最後除以這個計數器而不是總場次，避免被沒發生的場次拉低平均
    static double DRerouteSuccessSum = 0, SRerouteSuccessSum = 0;
    static int    DRerouteIterCount = 0,  SRerouteIterCount = 0;

    // ─── 【新增】改道成功率除錯用計數器 ───────────────────────────
    // 每次 run() 開始時歸零。記錄本場模擬中「有多少次被指派跨樓層目標(Stage)」，
    // 這是「有沒有機會觸發改道」的前提；如果這個數字是0，代表根本沒人跨樓層移動，
    // rerouteAttemptCount=0 是理所當然的，不是bug。
    // 如果這個數字>0但rerouteAttemptCount還是0，代表跨樓層時「目標樓梯間剛好在那個瞬間著火」
    // 的情況從未發生過，要去看火勢蔓延到Stage的時機/機率是否合理。
    static int stageAssignCount = 0;

    static double DVulnerableIdSum = 0, SVulnerableIdSum = 0;
    static int    DVulnerableIterCount = 0, SVulnerableIterCount = 0;

    static double DAvgEscapeTickSum = 0, SAvgEscapeTickSum = 0;
    static int    DEscapeTickIterCount = 0, SEscapeTickIterCount = 0;

    static double DAvgDecisionTickSum = 0, SAvgDecisionTickSum = 0;
    static int    DDecisionTickIterCount = 0, SDecisionTickIterCount = 0;

    static double DAvgTrappedReportSum = 0, SAvgTrappedReportSum = 0;
    static int    DTrappedReportIterCount = 0, STrappedReportIterCount = 0;

    // 新增狀態暫存變數，用於判定環境是否已無變化
    static double lastSmokeSum = -1;
    static int lastFireCount = -1;

    static Obj[][][] cloneBuilding(Obj[][][] src) {
        int h = src.length, r = src[0].length, c = src[0][0].length;
        Obj[][][] dst = new Obj[h][r][c];
        Stage[][][] stageMap = new Stage[h][r][c]; // 暫存對應的新 Stage，方便之後重建連結

        for (int z = 0; z < h; z++) {
            for (int y = 0; y < r; y++) {
                for (int x = 0; x < c; x++) {
                    Obj o = src[z][y][x];
                    Obj n;
                    if (o instanceof Stage) {
                        Stage s = new Stage();
                        s.setLocation(z, y, x);
                        stageMap[z][y][x] = s;
                        n = s;
                    } 
                    else if (o instanceof FireDoor) { 
                        FireDoor fd = new FireDoor();
                        fd.blocked = ((FireDoor) o).blocked;
                        fd.isOpen  = ((FireDoor) o).isOpen;
                        n = fd;
                    }
                    else if (o instanceof Door) {
                        Door d = new Door();
                        d.blocked = ((Door) o).blocked;
                        n = d;
                    } else if (o instanceof Exit) {
                        n = new Exit();
                    } else if (o instanceof Wall) {
                        n = new Wall();
                    } else {
                        n = new Floor();
                    }
                    n.fire  = o.fire;
                    n.smoke = o.smoke;
                    dst[z][y][x] = n;
                }
            }
        }

        // 重新串接樓梯上下層關係
        for (int z = 0; z < h; z++) {
            for (int y = 0; y < r; y++) {
                for (int x = 0; x < c; x++) {
                    if (src[z][y][x] instanceof Stage) {
                        Stage origin = (Stage) src[z][y][x];
                        Stage clone  = stageMap[z][y][x];
                        if (origin.upfloor != null) {
                            clone.upfloor = stageMap[origin.upfloor.z][origin.upfloor.y][origin.upfloor.x];
                        }
                        if (origin.downfloor != null) {
                            clone.downfloor = stageMap[origin.downfloor.z][origin.downfloor.y][origin.downfloor.x];
                        }
                    }
                }
            }
        }

        return dst;
    }
    
    static void initPeople(int count) {
        Random rand = new Random();
        PersonProfile[] profiles = PersonProfile.values(); 
        
        for (int i = 1; i <= count; i++) {
            while (true) {
                int z = rand.nextInt(space.height);
                int y = rand.nextInt(space.rows);
                int x = rand.nextInt(space.cols);
                Obj obj = space.building[z][y][x];
                if (obj instanceof Floor) {
                    PersonProfile randomProfile = profiles[rand.nextInt(profiles.length)];
                    peopleList.add(new People(i, z, y, x, space, randomProfile));
                    break;
                }
            }
        }
        assignCompanions(peopleList);
    }

    // 【同行者】把部分人隨機兩兩配對成同行者(家人/朋友一起行動)，並登記進不會被清空的名冊
    static void assignCompanions(List<People> list) {
        for (People p : list) allPeopleById.put(p.id, p);
        for (int i = 0; i + 1 < list.size(); i++) {
            People a = list.get(i), b = list.get(i + 1);
            if (a.companionId == null && b.companionId == null && random.nextDouble() < 0.35) {
                a.companionId = b.id;
                b.companionId = a.id;
            }
        }
    }

    static void initDetectors() {
        for (int z = 0; z < space.height; z++) {
            for (int y = 1; y < space.rows - 1; y += 3) {
                for (int x = 1; x < space.cols - 1; x += 3) {
                    if (!(space.building[z][y][x] instanceof Wall)) {
                        detectors.add(new Detector(z, y, x));
                    }
                }
            }
        }
    }

    static boolean isFloorConnected(Obj[][] layer, int rows, int cols) {
        int sy = -1, sx = -1;
        for (int y = 0; y < rows && sy == -1; y++)
            for (int x = 0; x < cols && sy == -1; x++)
                if (layer[y][x] instanceof Floor) { sy = y; sx = x; }
        if (sy == -1) return true;

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        boolean[][] vis = new boolean[rows][cols];
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{sy, sx}); vis[sy][sx] = true;
        int reached = 1;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int i = 0; i < 4; i++) {
                int ny = c[0] + dy[i], nx = c[1] + dx[i];
                if (ny >= 0 && ny < rows && nx >= 0 && nx < cols
                        && !vis[ny][nx] && layer[ny][nx] instanceof Floor) {
                    vis[ny][nx] = true; reached++; q.add(new int[]{ny, nx});
                }
            }
        }
        int total = 0;
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++)
                if (layer[y][x] instanceof Floor) total++;
        return reached == total;
    }

    static boolean wouldIsolate(Obj[][] layer, int rows, int cols, int ty, int tx) {
        Obj original = layer[ty][tx];
        layer[ty][tx] = new Wall();
        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        boolean isolate = false;
        for (int i = 0; i < 4; i++) {
            int ny = ty + dy[i], nx = tx + dx[i];
            if (ny < 0 || ny >= rows || nx < 0 || nx >= cols) continue;
            if (!(layer[ny][nx] instanceof Floor)) continue;
            int freeNeighbors = 0;
            for (int j = 0; j < 4; j++) {
                int fy = ny + dy[j], fx = nx + dx[j];
                if (fy >= 0 && fy < rows && fx >= 0 && fx < cols
                        && !(layer[fy][fx] instanceof Wall)) freeNeighbors++;
            }
            if (freeNeighbors == 0) { isolate = true; break; }
        }
        layer[ty][tx] = original;
        return isolate;
    }

    static boolean space_isWalkable(Obj[][] layer, int rows, int cols, int y, int x) {
        if (y < 0 || y >= rows || x < 0 || x >= cols) return false;
        return !(layer[y][x] instanceof Wall);
    }

    static Obj[][][] generateMap(int height, int rows, int cols) {
        Obj[][][] obj = new Obj[height][rows][cols];
        Random rand = new Random();

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    obj[z][y][x] = new Floor();

        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                obj[z][y][0] = new Wall(); obj[z][y][cols - 1] = new Wall();
            }
            for (int x = 0; x < cols; x++) {
                obj[z][0][x] = new Wall(); obj[z][rows - 1][x] = new Wall();
            }
        }

        for (int z = 0; z < height; z++) {
            int wallCount = (rows * cols) / (int)(rand.nextDouble(6)+4), attempts = 0, placed = 0;
            while (placed < wallCount && attempts < wallCount * 10) {
                attempts++;
                int y = rand.nextInt(rows - 2) + 1, x = rand.nextInt(cols - 2) + 1;
                if (!(obj[z][y][x] instanceof Floor)) continue;
                if (wouldIsolate(obj[z], rows, cols, y, x)) continue;
                obj[z][y][x] = new Wall();
                if (!isFloorConnected(obj[z], rows, cols)) { obj[z][y][x] = new Floor(); continue; }
                placed++;
            }
        }

        for (int z = 0; z < height; z++) {
            int doorCount = (rows * cols) / 20;
            int placed = 0, attempts = 0;
            while (placed < doorCount && attempts < doorCount * 10) {
                attempts++;
                int y = rand.nextInt(rows - 2) + 1, x = rand.nextInt(cols - 2) + 1;
                if (!(obj[z][y][x] instanceof Floor)) continue;
                boolean wallN = !space_isWalkable(obj[z], rows, cols, y - 1, x);
                boolean wallS = !space_isWalkable(obj[z], rows, cols, y + 1, x);
                boolean wallW = !space_isWalkable(obj[z], rows, cols, y, x - 1);
                boolean wallE = !space_isWalkable(obj[z], rows, cols, y, x + 1);
                boolean corridor_NS = (wallN && wallS && !wallW && !wallE); 
                boolean corridor_EW = (wallW && wallE && !wallN && !wallS); 
                if (!corridor_NS && !corridor_EW) continue;
                if (rand.nextBoolean()) {
                    FireDoor fd = new FireDoor();
                    // 現實中常見管理疏失：防火門被雜物卡住/圖方便沒關緊，約15%機率一開始就是開著的
                    fd.isOpen = rand.nextDouble() < 0.15;
                    obj[z][y][x] = fd;
                } else {
                    obj[z][y][x] = new Door();
                }
                placed++;
            }
        }

        if (height > 1) {
            Stage[] stages = new Stage[height];
            for (int z = 0; z < height; z++) {
                int sy, sx;
                while (true) {
                    sy = rand.nextInt(rows - 2) + 1; sx = rand.nextInt(cols - 2) + 1;
                    if (!(obj[z][sy][sx] instanceof Floor)) continue;
                    if (z > 0) {
                        int dist = Math.abs(sy - stages[z-1].y) + Math.abs(sx - stages[z-1].x);
                        if (dist > 3) continue;
                    }
                    break;
                }
                Stage s = new Stage(); s.setLocation(z, sy, sx);
                stages[z] = s; obj[z][sy][sx] = s;
            }
            for (int z = 0; z < height - 1; z++) {
                stages[z].upfloor = stages[z + 1];
                stages[z + 1].downfloor = stages[z];
            }
        }

        if (height > 0) {
            int z = 0; 
            List<int[]> edgeCandidates = new ArrayList<>();
            for (int x = 1; x < cols - 1; x++) {
                if (obj[z][1][x] instanceof Floor)      edgeCandidates.add(new int[]{0, x});
                if (obj[z][rows-2][x] instanceof Floor) edgeCandidates.add(new int[]{rows-1, x});
            }
            for (int y = 1; y < rows - 1; y++) {
                if (obj[z][y][1] instanceof Floor)      edgeCandidates.add(new int[]{y, 0});
                if (obj[z][y][cols-2] instanceof Floor) edgeCandidates.add(new int[]{y, cols-1});
            }
            if (!edgeCandidates.isEmpty()) {
                int[] chosen = edgeCandidates.get(rand.nextInt(edgeCandidates.size()));
                obj[z][chosen[0]][chosen[1]] = new Exit();
            }
        }

        return obj;
    }

    static void initFireSource(int z, int y, int x) {
        if (space.isValid(z, y, x)) {
            space.building[z][y][x].fire  = true;
            space.building[z][y][x].smoke = 0.5;
            System.out.println("【系統】已於座標 (" + z + ", " + y + ", " + x + ") 設定初始火源！\n");
        }
    }

    static Stage findSmokeUpTarget(Stage origin) {
        Stage cur = origin.upfloor, candidate = null;
        while (cur != null) { if (cur.smoke < 0.7) candidate = cur; cur = cur.upfloor; }
        return candidate;
    }
    static Stage findSmokeDownTarget(Stage origin) {
        Stage cur = origin.downfloor;
        while (cur != null) { if (cur.smoke < 0.7) return cur; cur = cur.downfloor; }
        return null;
    }

    static double smokeGrowth(double x) {
        return Math.pow(Math.abs(Math.sin(x - Math.PI / 2)), 0.68) + 1.0;
    }

    static void spreadEnvironment() {
        int height = space.height, rows = space.rows, cols = space.cols;
        int[] dyFire = {-1, 1, 0, 0}, dxFire = {0, 0, -1, 1};

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
                                        if (dist == 1) {
                                            nextSmoke[z][ny][nx] += 0.3 * smokeMultiplier;
                                        } else if (dist == 2) {
                                            nextSmoke[z][ny][nx] += 0.2 * smokeMultiplier;
                                        } else if (dist == 3) {
                                            nextSmoke[z][ny][nx] += 0.1 * smokeMultiplier;
                                        }
                                    }
                                }
                            }
                        }

                        if (space.building[z][y][x] instanceof Stage) {
                            Stage s = (Stage) space.building[z][y][x];
                            Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                            if (upT != null) nextSmoke[upT.z][upT.y][upT.x] += (0.2 * smokeMultiplier);
                            Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                            if (downT != null) nextSmoke[downT.z][downT.y][downT.x] += (0.1 * smokeMultiplier);
                        }
                    }
                }
            }
        }

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].smoke = Math.min(1.0, nextSmoke[z][y][x]);

        if (tick % fireSpreadTick == 0) { 
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
                        // 確實關好的防火門能有效阻絕火勢延燒；沒關好的防火門(isOpen)則退化成普通門
                        if (next instanceof FireDoor && !((FireDoor) next).isOpen) continue;
                        
                        if (next instanceof Door) {
                            Door d = (Door) next;
                            if (random.nextDouble() < 0.3) d.blocked = true;
                        }
                        next.fire = true;
                        next.smoke = Math.max(next.smoke, 0.6); 
                    }
                }
                
                if (cellObj instanceof Stage) {
                    Stage s = (Stage) cellObj; 
                    Stage upFireTarget = null;
                    if (s.upfloor != null) {
                        Stage cur = s.upfloor;
                        while (cur != null) { 
                            if (!cur.fire) upFireTarget = cur; 
                            cur = cur.upfloor; 
                        }
                    }
                    
                    if (upFireTarget != null) { 
                        upFireTarget.fire = true; 
                        upFireTarget.smoke = Math.max(upFireTarget.smoke, 0.5); 
                    } 
                    else if (s.downfloor != null) {
                        Stage cur = s.downfloor;
                        while (cur != null) {
                            if (!cur.fire) { 
                                cur.fire = true; 
                                cur.smoke = Math.max(cur.smoke, 0.5); 
                                break; 
                            }
                            cur = cur.downfloor;
                        }
                    }
                }
            }
        }
    }

    static void printMap() {
        int height = space.height, rows = space.rows, cols = space.cols;
        int[][][] personIdMap = new int[height][rows][cols];
        for (People p : peopleList) {
            if (!p.isDead && !p.isEscaped && space.isValid(p.z, p.y, p.x)) {
                personIdMap[p.z][p.y][p.x] = p.id; 
            }
        }
        String[] numEmojis = {"", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣","9️⃣","🔟"};

        System.out.println("====== 地圖當前狀態 (Tick: " + tick + ") ======");
        for (int z = 0; z < height; z++) {
            System.out.println("【 樓層 " + z + " 】");
            for (int y = 0; y < rows; y++) {
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x < cols; x++) {
                    Obj cell = space.building[z][y][x];
                    int pId = personIdMap[z][y][x];
                    
                    if (pId > 0) {
                        if (pId >= 1 && pId <= 10) {
                            sb.append(numEmojis[pId]); 
                        } else {
                            sb.append("😭"); 
                        }
                    }
                    else if (cell instanceof Exit)       sb.append("🚪");
                    else if (cell instanceof FireDoor)	 sb.append(((FireDoor) cell).isOpen ? "⚠️" : "🚧");
                    else if (cell.fire)                  sb.append("🔥");
                    else if (cell.smoke >= 0.5)          sb.append("💨"); 
                    else if (cell instanceof Door) {
                        sb.append(((Door) cell).blocked ? "X" : "🚪");
                    }
                    else if (cell instanceof Stage)      sb.append("🪜");
                    else if (cell instanceof Wall)       sb.append("🧱");
                    else                                 sb.append("⬜");
                }
                System.out.println(sb);
            }
            System.out.println();
        }
    }

    // 檢查火源跟煙霧如果沒有變化，代表環境趨於穩定，就停止模擬
    static boolean isEnvironmentStable() {
        int currentFireCount = 0;
        double currentSmokeSum = 0.0;
        
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    if (space.building[z][y][x].fire) currentFireCount++;
                    currentSmokeSum += space.building[z][y][x].smoke;
                }
            }
        }
        
        boolean stable = (lastSmokeSum != -1 && currentFireCount == lastFireCount && Math.abs(currentSmokeSum - lastSmokeSum) < 1e-6);
        
        lastSmokeSum = currentSmokeSum;
        lastFireCount = currentFireCount;
        
        return stable;
    }

    // 每個人的模擬結果：實際發生的tick，以及最終「結局」(存活/死亡/仍受困)
    // 這樣才能正確分辨「逃脫」跟「困在原地但還沒死」，不會把兩者混為一談
    enum Outcome { ESCAPED, DEAD, TRAPPED }
    static class SimResult {
        int[] times;
        Outcome[] outcomes;
        boolean[] enteredHighSmoke;   // 是否曾經進入高煙區
        boolean[] reachedCriticalCO;  // 是否曾經CO暴露達臨界值
        boolean[] wrongRoute;         // 是否曾依現有資訊做決策卻仍誤入高煙危險路線
        boolean[] rerouted;           // 是否曾發生「原路線失效，被迫改道」
        boolean[] rerouteSuccess;     // 改道之後最終是否成功逃脫(只在 rerouted=true 時有意義)
        boolean[] vulnerableIdentified; // 系統是否成功辨識並標記此人為需優先協助對象
        boolean[] isVulnerableProfile;  // 此人本身是否屬於「行動不便/年長者」這類需優先協助對象
        int[] firstDecisionTick;      // 第一次做出有依據決策的tick，-1代表整場模擬都沒有
        int[] reportedTrappedTick;    // 系統端最早得知此人受困位置的tick，-1代表系統始終不知道

        SimResult(int n) {
            times = new int[n];
            outcomes = new Outcome[n];
            enteredHighSmoke = new boolean[n];
            reachedCriticalCO = new boolean[n];
            wrongRoute = new boolean[n];
            rerouted = new boolean[n];
            rerouteSuccess = new boolean[n];
            vulnerableIdentified = new boolean[n];
            isVulnerableProfile = new boolean[n];
            firstDecisionTick = new int[n];
            reportedTrappedTick = new int[n];
            Arrays.fill(outcomes, Outcome.TRAPPED); // 預設：模擬結束時還沒死也還沒逃出去
            Arrays.fill(firstDecisionTick, -1);
            Arrays.fill(reportedTrappedTick, -1);
        }
    }

    // 依據 People 目前狀態，把單一個體的所有KPI旗標寫入 SimResult 對應索引
    private static void recordPersonSnapshot(SimResult result, People p, int tick, Outcome outcome) {
        int idx = p.id - 1;
        result.times[idx] = tick;
        result.outcomes[idx] = outcome;
        result.enteredHighSmoke[idx] = p.everEnteredHighSmoke;
        result.reachedCriticalCO[idx] = p.everReachedCriticalCO;
        result.wrongRoute[idx] = p.everWrongRoute;
        result.rerouted[idx] = p.everRerouted;
        result.rerouteSuccess[idx] = p.everRerouted && outcome == Outcome.ESCAPED;
        result.vulnerableIdentified[idx] = p.systemIdentifiedVulnerable;
        result.isVulnerableProfile[idx] = (p.profile == PersonProfile.IMPAIRED || p.profile == PersonProfile.ELDERLY);
        result.firstDecisionTick[idx] = (p.firstCorrectDecisionTick != null) ? p.firstCorrectDecisionTick : -1;
        result.reportedTrappedTick[idx] = (p.reportedTrappedTick != null) ? p.reportedTrappedTick : -1;
    }

    // 將 run 修改為回傳每個人的生存時間陣列，並且準確記錄他們的真實結局與暴露統計
    static SimResult run(boolean useSmart, int totalPeople) {
        int survive = 0;
        SimResult result = new SimResult(totalPeople);
        stageAssignCount = 0; // 【新增】每場模擬重新歸零，避免跟前一場(甚至前一次Default/Smart)累加混在一起
        printMap();
        
        while (!isEnvironmentStable() && tick < surviveTime) {
            tick++;
            spreadEnvironment();

            for (Detector d : detectors) {
                d.update(space.building, random);
            }

            List<People> removed = new ArrayList<>();
            for (People p : peopleList) {
                if (useSmart) p.SmartEscape(tick);
                else          p.escape(tick);

                if (p.isDead) {
                    recordPersonSnapshot(result, p, tick, Outcome.DEAD);
                    removed.add(p); 
                }
                else if (p.isEscaped) { 
                    recordPersonSnapshot(result, p, tick, Outcome.ESCAPED); // 記錄「真正」逃脫的那一刻，而不是模擬結束時的tick
                    survive++; 
                    removed.add(p); 
                }
            }
            peopleList.removeAll(removed);
            printMap();
        }
        
        // 迴圈跑完後，還留在 peopleList 裡的人代表模擬結束時他們既沒逃出去也沒死(TRAPPED)
        // 用最終tick填時間只是為了讓 times[] 有個數字，但 outcomes[] 才是判斷用的依據
        for (People p : peopleList) {
            recordPersonSnapshot(result, p, tick, Outcome.TRAPPED);
        }

        // 統計本次模擬「進入高煙區」與「CO暴露達臨界值」的人數比例，以及新增的各項KPI
        int highSmokeCount = 0, criticalCOCount = 0, wrongRouteCount = 0;
        int rerouteAttemptCount = 0, rerouteSuccessCount = 0;
        int vulnerableTotal = 0, vulnerableIdentifiedCount = 0;
        long escapeTickSum = 0, escapeTickN = 0;
        long decisionTickSum = 0, decisionTickN = 0;
        long trappedReportSum = 0, trappedReportN = 0;

        for (int i = 0; i < totalPeople; i++) {
            if (result.enteredHighSmoke[i]) highSmokeCount++;
            if (result.reachedCriticalCO[i]) criticalCOCount++;
            if (result.wrongRoute[i]) wrongRouteCount++;
            if (result.rerouted[i]) {
                rerouteAttemptCount++;
                if (result.rerouteSuccess[i]) rerouteSuccessCount++;
            }
            if (result.isVulnerableProfile[i]) {
                vulnerableTotal++;
                if (result.vulnerableIdentified[i]) vulnerableIdentifiedCount++;
            }
            if (result.outcomes[i] == Outcome.ESCAPED) {
                escapeTickSum += result.times[i];
                escapeTickN++;
            }
            if (result.firstDecisionTick[i] >= 0) {
                decisionTickSum += result.firstDecisionTick[i];
                decisionTickN++;
            }
            if (result.outcomes[i] != Outcome.ESCAPED && result.reportedTrappedTick[i] >= 0) {
                trappedReportSum += result.reportedTrappedTick[i];
                trappedReportN++;
            }
        }

        double wrongRouteRate    = (double) wrongRouteCount / totalPeople;
        double rerouteSuccessRate = (rerouteAttemptCount > 0) ? (double) rerouteSuccessCount / rerouteAttemptCount : -1; // -1代表本場沒有發生改道
        double vulnerableIdRate  = (vulnerableTotal > 0) ? (double) vulnerableIdentifiedCount / vulnerableTotal : -1;    // -1代表本場沒有需優先協助對象
        double avgEscapeTick     = (escapeTickN > 0) ? (double) escapeTickSum / escapeTickN : -1;
        double avgDecisionTick   = (decisionTickN > 0) ? (double) decisionTickSum / decisionTickN : -1;
        double avgTrappedReport  = (trappedReportN > 0) ? (double) trappedReportSum / trappedReportN : -1;

        // 【新增】改道成功率專用除錯log：直接印到stderr，每一場(Default/Smart各一次)都會顯示，
        // 不必再去開 defaultResult.txt / smartResult.txt 才看得到。
        // 重點看 stageCrossings：
        //   stageCrossings=0            → 這場模擬根本沒人跨樓層移動，rerouteAttempts=0是正常的，不是bug
        //   stageCrossings>0 但 rerouteAttempts=0 → 有跨樓層，但目標樓梯間從未在「指派後、移動前」那個瞬間著火，
        //                                            要去檢查火勢蔓延到Stage的時機/機率是否合理
        //   rerouteAttempts>0           → 改道事件有觸發，success/fail細節可以對照 [REROUTE-OUTCOME] log
        System.err.println("[REROUTE-SUMMARY] mode=" + (useSmart ? "SMART" : "DEFAULT")
            + " totalPeople=" + totalPeople
            + " stageCrossings=" + stageAssignCount
            + " rerouteAttempts=" + rerouteAttemptCount
            + " rerouteSuccess=" + rerouteSuccessCount
            + " rerouteFail=" + (rerouteAttemptCount - rerouteSuccessCount)
            + " rate=" + (rerouteAttemptCount > 0 ? String.format("%.2f%%", rerouteSuccessRate * 100) : "N/A(no reroute occurred this run)"));

        System.out.println("\n====== 模擬結束內部數據 ======");
        if(useSmart) {
            STotalserviveP  += (double)survive / totalPeople;
            SHighSmokeP     += (double)highSmokeCount / totalPeople;
            SCriticalCOP    += (double)criticalCOCount / totalPeople;
            SWrongRouteP    += wrongRouteRate;
            if (rerouteSuccessRate >= 0) { SRerouteSuccessSum += rerouteSuccessRate; SRerouteIterCount++; }
            if (vulnerableIdRate  >= 0)  { SVulnerableIdSum   += vulnerableIdRate;  SVulnerableIterCount++; }
            if (avgEscapeTick     >= 0)  { SAvgEscapeTickSum  += avgEscapeTick;     SEscapeTickIterCount++; }
            if (avgDecisionTick   >= 0)  { SAvgDecisionTickSum += avgDecisionTick;  SDecisionTickIterCount++; }
            if (avgTrappedReport  >= 0)  { SAvgTrappedReportSum += avgTrappedReport; STrappedReportIterCount++; }
        }
        else {
            DTotalserviveP  += (double)survive / totalPeople;
            DHighSmokeP     += (double)highSmokeCount / totalPeople;
            DCriticalCOP    += (double)criticalCOCount / totalPeople;
            DWrongRouteP    += wrongRouteRate;
            if (rerouteSuccessRate >= 0) { DRerouteSuccessSum += rerouteSuccessRate; DRerouteIterCount++; }
            if (vulnerableIdRate  >= 0)  { DVulnerableIdSum   += vulnerableIdRate;  DVulnerableIterCount++; }
            if (avgEscapeTick     >= 0)  { DAvgEscapeTickSum  += avgEscapeTick;     DEscapeTickIterCount++; }
            if (avgDecisionTick   >= 0)  { DAvgDecisionTickSum += avgDecisionTick;  DDecisionTickIterCount++; }
            if (avgTrappedReport  >= 0)  { DAvgTrappedReportSum += avgTrappedReport; DTrappedReportIterCount++; }
        }
        System.err.println("total: "+totalPeople+", survive: "+survive);
        return result;
    }

    public void work(int iterations,Range H,Range R,Range C) {
        TotalPeopleSum = 0;
        double totalWrongDecisionCount = 0; // 統計原本活但系統模擬中死掉的總人數

    	for(int it = 0; it < iterations; it++) {
            System.err.println("it:" + it);
            Random rand = new Random();
            FireCause[] causes = FireCause.values();
            currentCause = causes[rand.nextInt(causes.length)];
            System.err.println("起火原因: " + currentCause);
            int height = rand.nextInt(H.max-H.min) + H.min;       
            int rows   = rand.nextInt(R.max-R.min) + R.min;       
            int cols   = rand.nextInt(C.max-C.min) + C.min;       

            int maxArea = (rows - 2) * (cols - 2); 
            if (maxArea < 1) maxArea = 1; 

            int peopleCount = Math.max(((rand.nextInt(maxArea) + 1)/4), 1);
            TotalPeopleSum += peopleCount;

            Obj[][][] baseBuilding = generateMap(height, rows, cols);
            Space tempSpace = new Space(baseBuilding);

            int fireZ = rand.nextInt(tempSpace.height);
            int fireY = rand.nextInt(tempSpace.rows);
            int fireX = rand.nextInt(tempSpace.cols);
            while (!(baseBuilding[fireZ][fireY][fireX] instanceof Floor)) {
                fireY = rand.nextInt(tempSpace.rows);
                fireX = rand.nextInt(tempSpace.cols);
            }

            int[][] peopleInit = new int[peopleCount][4]; 
            for (int i = 0; i < peopleCount; i++) {
                while (true) {
                    int z = rand.nextInt(tempSpace.height);
                    int y = rand.nextInt(tempSpace.rows);
                    int x = rand.nextInt(tempSpace.cols);
                    if (baseBuilding[z][y][x] instanceof Floor) {
                        peopleInit[i] = new int[]{ z, y, x, rand.nextInt(3) + 1 };
                        break;
                    }
                }
            }

            // 取得每個個體在兩種演算法下的模擬結果（時間 + 真實結局）
            SimResult defaultResult = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, false, "defaultResult.txt");
            SimResult smartResult   = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, true, "smartResult.txt");

            // 統計「系統做錯決定」的人數：定義為「結果比原本更差」
            //   - 原本(Default)活著逃出去，但系統(Smart)卻讓他死掉 → 明確的錯誤
            //   - 原本活著逃出去，系統卻讓他一直受困沒逃出 → 也是變差，一併算錯誤
            // 注意：不能直接比較 times[i] 大小，因為 times[i] 只在「死亡/逃脫當下」才有意義，
            // 而且 Default 和 Smart 是兩場「各自獨立結束」的模擬，兩邊的 tick 基準點不一樣，
            // 直接比大小會把「系統救活的人」誤判成「系統做錯決定」。
            for (int i = 0; i < peopleCount; i++) {
                boolean defaultSurvived = defaultResult.outcomes[i] == Outcome.ESCAPED;
                boolean smartSurvived   = smartResult.outcomes[i] == Outcome.ESCAPED;

                if (defaultSurvived && !smartSurvived) {
                    totalWrongDecisionCount++;
                }
            }
        }
        
        // 根據定義計算準確率
        double finalAccuracy = (TotalPeopleSum > 0) ? (totalWrongDecisionCount / TotalPeopleSum) : 0.0;
        

        // 【調整輸出報表】依需求移除無關參數，只保留生還率與準確率
        System.err.println("\n==============================================");
        System.err.println("                 最終數據報告                 ");
        System.err.println("==============================================");
        System.err.printf("初始模擬生還率 (Default Survival Rate): %.2f%%\n", (DTotalserviveP / iterations) * 100);
        System.err.printf("系統模擬生還率 (Smart Survival Rate)  : %.2f%%\n", (STotalserviveP / iterations) * 100);
        System.err.printf("系統嚴重失誤機率 (System mistake Rate) : %.2f%%\n", finalAccuracy * 100);
        System.err.println("----------------------------------------------");
        System.err.printf("初始模擬進入高煙區比例 (Default High-Smoke Rate) : %.2f%%\n", (DHighSmokeP / iterations) * 100);
        System.err.printf("系統模擬進入高煙區比例 (Smart High-Smoke Rate)   : %.2f%%\n", (SHighSmokeP / iterations) * 100);
        System.err.printf("初始模擬臨界CO暴露比例 (Default Critical-CO Rate): %.2f%%\n", (DCriticalCOP / iterations) * 100);
        System.err.printf("系統模擬臨界CO暴露比例 (Smart Critical-CO Rate)  : %.2f%%\n", (SCriticalCOP / iterations) * 100);
        System.err.println("----------------------------------------------");
        System.err.printf("初始模擬誤入危險路線比例 (Default Wrong-Route Rate): %.2f%%\n", (DWrongRouteP / iterations) * 100);
        System.err.printf("系統模擬誤入危險路線比例 (Smart Wrong-Route Rate)  : %.2f%%\n", (SWrongRouteP / iterations) * 100);
        System.err.println("----------------------------------------------");
        printAvgOrNA("初始模擬改道成功率 (Default Reroute Success Rate)", DRerouteSuccessSum, DRerouteIterCount, true);
        printAvgOrNA("系統模擬改道成功率 (Smart Reroute Success Rate)  ", SRerouteSuccessSum, SRerouteIterCount, true);
        System.err.println("----------------------------------------------");
        printAvgOrNA("初始模擬弱勢對象辨識率 (Default Vulnerable-ID Rate)", DVulnerableIdSum, DVulnerableIterCount, true);
        printAvgOrNA("系統模擬弱勢對象辨識率 (Smart Vulnerable-ID Rate)  ", SVulnerableIdSum, SVulnerableIterCount, true);
        System.err.println("----------------------------------------------");
        printAvgOrNA("初始模擬平均逃脫所需時間 (Default Avg Escape Tick)", DAvgEscapeTickSum, DEscapeTickIterCount, false);
        printAvgOrNA("系統模擬平均逃脫所需時間 (Smart Avg Escape Tick)  ", SAvgEscapeTickSum, SEscapeTickIterCount, false);
        System.err.println("----------------------------------------------");
        printAvgOrNA("初始模擬平均首次正確決策時間 (Default Avg First-Decision Tick)", DAvgDecisionTickSum, DDecisionTickIterCount, false);
        printAvgOrNA("系統模擬平均首次正確決策時間 (Smart Avg First-Decision Tick)  ", SAvgDecisionTickSum, SDecisionTickIterCount, false);
        System.err.println("----------------------------------------------");
        printAvgOrNA("初始模擬系統獲知受困位置平均時間 (Default Avg Trapped-Report Tick)", DAvgTrappedReportSum, DTrappedReportIterCount, false);
        printAvgOrNA("系統模擬系統獲知受困位置平均時間 (Smart Avg Trapped-Report Tick)  ", SAvgTrappedReportSum, STrappedReportIterCount, false);
        System.err.println("==============================================");
    }

    // 輔助函式：印出「只在有意義的場次才平均」的KPI，若整個模擬過程都沒有發生過相關事件，就標示 N/A 而不是硬算出0
    private static void printAvgOrNA(String label, double sum, int count, boolean asPercent) {
        if (count == 0) {
            System.err.println(label + " : N/A (無相關樣本)");
        } else if (asPercent) {
            System.err.printf("%s : %.2f%% (樣本場次: %d)\n", label, (sum / count) * 100, count);
        } else {
            System.err.printf("%s : %.2f tick (樣本場次: %d)\n", label, sum / count, count);
        }
    }

    static SimResult runOneSimulation(Obj[][][] baseBuilding, int[][] peopleInit,
                                  int fireZ, int fireY, int fireX,
                                  boolean useSmart, String outputFile) {
        try {
            PrintStream output = new PrintStream(outputFile);
            System.setOut(output);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        space = new Space(cloneBuilding(baseBuilding));
        detectors = new ArrayList<>();
        peopleList = new ArrayList<>();
        allPeopleById = new HashMap<>();
        tick = 0;

        lastSmokeSum = -1;
        lastFireCount = -1;

        initFireSource(fireZ, fireY, fireX);

        initDetectors();

        PersonProfile[] profiles = PersonProfile.values();
        for (int i = 0; i < peopleInit.length; i++) {
            int z = peopleInit[i][0], y = peopleInit[i][1], x = peopleInit[i][2];
            PersonProfile randomProfile = profiles[random.nextInt(profiles.length)];
            peopleList.add(new People(i + 1, z, y, x, space, randomProfile));
        }
        assignCompanions(peopleList);
        
        return run(useSmart, peopleInit.length);
    }
}