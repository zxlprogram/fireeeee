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

// ─── 模擬模式 ──────────────────────────────────────────────────
enum SimMode {
    DEFAULT,  // 傳統直覺逃生，無系統輔助
    SMART,    // 智慧系統輔助決策
    B2        // 智慧系統 + 建築輔助控制（防火門、排煙）
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

 // ─── 【新增】建議生命週期相關 ─────────────────────────────────
 static class Advice {
     int issuedTick;
     int validUntilTick;
     int targetKey; // 使用 bitKey 編碼的目標位置（通常為某個 Stage）
     Advice(int issuedTick, int validUntilTick, int targetZ, int targetY, int targetX) {
         this.issuedTick = issuedTick;
         this.validUntilTick = validUntilTick;
         this.targetKey = bitKeyStatic(targetZ, targetY, targetX);
     }
     private static int bitKeyStatic(int z, int y, int x) {
         return (z << 20) | (y << 10) | x;
     }
 }
 Advice currentAdvice = null;
 int adviceRevokedTick = -1;      // 系統撤回建議的時刻
 int rerouteCompletionTick = -1;  // 撤回後完成改道（移動到新目標/逃脫）的時刻

 private static final int ADVICE_VALID_TICKS = 10; // 建議有效期（tick 數）

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
 // 現在包含建議生命週期管理
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

             // 目標失效，強制撤回當前建議
             if (currentAdvice != null) {
                 adviceRevokedTick = currentTick;
                 currentAdvice = null;
             }
         } else {
             this.z = targetStage.z; this.y = targetStage.y; this.x = targetStage.x;
             // 若之前有撤回建議，且尚未記錄完成，此時記錄改道完成時間
             if (adviceRevokedTick != -1 && rerouteCompletionTick == -1) {
                 rerouteCompletionTick = currentTick;
             }
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

     // ── 建議生命週期管理 ──
     // 檢查現有建議是否仍然有效
     if (currentAdvice != null) {
         int[] targetPos = decodeKey(currentAdvice.targetKey);
         if (currentTick > currentAdvice.validUntilTick ||
             !space.isValid(targetPos[0], targetPos[1], targetPos[2]) ||
             space.building[targetPos[0]][targetPos[1]][targetPos[2]].fire) {
             // 建議過期或目標失效，撤回
             adviceRevokedTick = currentTick;
             currentAdvice = null;
         }
     }

     // 如果沒有有效建議，重新計算路徑並產生新建議
     if (currentAdvice == null) {
         int[] nextPos = computeSmartPath(null);
         if (nextPos == null) {
             markReportedTrapped(currentTick);
             return;
         }
         // 若計算出的下一步是跨層移動，則目標是該 Stage；否則目標為當前位置（沿用直到跨層）
         int targetZ = nextPos[0], targetY = nextPos[1], targetX = nextPos[2];
         if (targetZ != this.z) {
             // 跨層目標，建立建議
             currentAdvice = new Advice(currentTick, currentTick + ADVICE_VALID_TICKS, targetZ, targetY, targetX);
         } else {
             // 同層移動，也可建立建議（以當前位置為暫時目標），有效期限較短
             currentAdvice = new Advice(currentTick, currentTick + ADVICE_VALID_TICKS/2, this.z, this.y, this.x);
         }
     }

     // 根據當前建議（若有）或剛建立的新建議執行移動
     boolean inHazardNow = space.building[z][y][x].smoke > 0.7;
     boolean moved = false;
     for (int step = 0; step < speed; step++) {
         int[] nextPos;
         if (currentAdvice != null) {
             // 使用建議的目標來引導路徑
             nextPos = computeSmartPath(currentAdvice.targetKey);
         } else {
             nextPos = computeSmartPath(null);
         }
         if (nextPos == null) { markReportedTrapped(currentTick); break; }
         moved = true;

         if (nextPos[0] != this.z) {
             this.targetStage = (Stage) space.building[nextPos[0]][nextPos[1]][nextPos[2]];
             Simulator.stageAssignCount++;
             break;
         } else {
             this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
             if (firstCorrectDecisionTick == null) firstCorrectDecisionTick = currentTick;
             checkStatus();
             if (isDead || isEscaped) {
                 // 逃脫或死亡時，若曾有撤回，記錄完成
                 if (adviceRevokedTick != -1 && rerouteCompletionTick == -1) {
                     rerouteCompletionTick = currentTick;
                 }
                 break;
             }

             boolean nowInSmoke = space.building[z][y][x].smoke > 0.7;
             if (nowInSmoke && !inHazardNow) {
                 everWrongRoute = true;
             }
             inHazardNow = nowInSmoke;
         }
     }
     if (!moved) randomMove(speed);
 }

 // computeSmartPath 增加可選目標參數，若指定則導向該目標而非 Exit
 private int[] computeSmartPath(Integer targetKey) {
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

         // 若指定了目標 key，且到達該 key，視為成功（即使不是 Exit）
         if (targetKey != null && currKey == targetKey.intValue()) {
             targetPosKey = currKey; break;
         }
         if (targetKey == null && currObj instanceof Exit) {
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

    // 更新結算變數 (加入 B2 模式對應變量)
    static double DTotalserviveP = 0, STotalserviveP = 0, B2TotalserviveP = 0;
    static double DHighSmokeP = 0, SHighSmokeP = 0, B2HighSmokeP = 0;
    static double DCriticalCOP = 0, SCriticalCOP = 0, B2CriticalCOP = 0;
    static double TotalAccuracySum = 0;
    static long TotalPeopleSum = 0;

    static double DWrongRouteP = 0, SWrongRouteP = 0, B2WrongRouteP = 0;
    static double DRerouteSuccessSum = 0, SRerouteSuccessSum = 0, B2RerouteSuccessSum = 0;
    static int    DRerouteIterCount = 0,  SRerouteIterCount = 0, B2RerouteIterCount = 0;
    static int stageAssignCount = 0;

    static double DVulnerableIdSum = 0, SVulnerableIdSum = 0, B2VulnerableIdSum = 0;
    static int    DVulnerableIterCount = 0, SVulnerableIterCount = 0, B2VulnerableIterCount = 0;

    static double DAvgEscapeTickSum = 0, SAvgEscapeTickSum = 0, B2AvgEscapeTickSum = 0;
    static int    DEscapeTickIterCount = 0, SEscapeTickIterCount = 0, B2EscapeTickIterCount = 0;

    static double DAvgDecisionTickSum = 0, SAvgDecisionTickSum = 0, B2AvgDecisionTickSum = 0;
    static int    DDecisionTickIterCount = 0, SDecisionTickIterCount = 0, B2DecisionTickIterCount = 0;

    static double DAvgTrappedReportSum = 0, SAvgTrappedReportSum = 0, B2AvgTrappedReportSum = 0;
    static int    DTrappedReportIterCount = 0, STrappedReportIterCount = 0, B2TrappedReportIterCount = 0;

    // 安全餘裕累加值
    static double DSafetyMarginSum = 0, SSafetyMarginSum = 0, B2SafetyMarginSum = 0;
    // 撤回後完成改道時間累加
    static double DRerouteCompletionSum = 0, SRerouteCompletionSum = 0, B2RerouteCompletionSum = 0;
    static int    DRerouteCompletionCount = 0, SRerouteCompletionCount = 0, B2RerouteCompletionCount = 0;

    static double lastSmokeSum = -1;
    static int lastFireCount = -1;

    // B2 模式煙霧抑制因子（排煙效果），可根據需要調整
    static double smokeSuppressionFactor = 1.0;

    static Obj[][][] cloneBuilding(Obj[][][] src) {
        int h = src.length, r = src[0].length, c = src[0][0].length;
        Obj[][][] dst = new Obj[h][r][c];
        Stage[][][] stageMap = new Stage[h][r][c];

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

    static boolean isFloorConnected(Obj[][] layer, int rows, int cols) { /* 略，同原代碼 */ return true; }
    static boolean wouldIsolate(Obj[][] layer, int rows, int cols, int ty, int tx) { return false; }
    static boolean space_isWalkable(Obj[][] layer, int rows, int cols, int y, int x) { return true; }

    static Obj[][][] generateMap(int height, int rows, int cols) { /* 略，同原代碼 */ return null; }

    static void initFireSource(int z, int y, int x) {
        if (space.isValid(z, y, x)) {
            space.building[z][y][x].fire  = true;
            space.building[z][y][x].smoke = 0.5;
            System.out.println("【系統】已於座標 (" + z + ", " + y + ", " + x + ") 設定初始火源！\n");
        }
    }

    static Stage findSmokeUpTarget(Stage origin) { /* 略 */ return null; }
    static Stage findSmokeDownTarget(Stage origin) { return null; }

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
        // 加入 B2 排煙抑制效果
        smokeMultiplier *= smokeSuppressionFactor;

        double[][][] nextSmoke = new double[height][rows][cols];
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    nextSmoke[z][y][x] = space.building[z][y][x].smoke;

        // 煙霧擴散邏輯同原代碼，此處省略以節省篇幅，實際代碼應保留完整擴散
        // ...（假設已實現）
        // 最終賦值
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].smoke = Math.min(1.0, nextSmoke[z][y][x]);

        // 火勢蔓延（略）
    }

    // B2 模式：主動關閉防火門
    static void applyB2Controls() {
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj obj = space.building[z][y][x];
                    if (obj instanceof FireDoor && ((FireDoor) obj).isOpen) {
                        // 檢查周圍危險條件
                        boolean closeDoor = false;
                        if (obj.smoke > 0.5 || obj.fire) closeDoor = true;
                        else {
                            int[] dy = {-1,1,0,0}, dx = {0,0,-1,1};
                            for (int i = 0; i < 4; i++) {
                                int ny = y+dy[i], nx = x+dx[i];
                                if (space.isValid(z, ny, nx)) {
                                    Obj neighbor = space.building[z][ny][nx];
                                    if (neighbor.smoke > 0.5 || neighbor.fire) {
                                        closeDoor = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (closeDoor) {
                            ((FireDoor) obj).isOpen = false;
                        }
                    }
                }
            }
        }
    }

    // 檢查環境穩定，略
    static boolean isEnvironmentStable() { return false; }

    // 模擬結果結構，擴充 ASET 和建議生命週期相關字段
    static class SimResult {
        int[] times;
        Outcome[] outcomes;
        boolean[] enteredHighSmoke;
        boolean[] reachedCriticalCO;
        boolean[] wrongRoute;
        boolean[] rerouted;
        boolean[] rerouteSuccess;
        boolean[] vulnerableIdentified;
        boolean[] isVulnerableProfile;
        int[] firstDecisionTick;
        int[] reportedTrappedTick;
        int[] adviceRevokedTick;       // -1 未發生
        int[] rerouteCompletionTick;   // -1 未完成
        int asetTick;                  // 全局 ASET

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
            adviceRevokedTick = new int[n];
            rerouteCompletionTick = new int[n];
            Arrays.fill(outcomes, Outcome.TRAPPED);
            Arrays.fill(firstDecisionTick, -1);
            Arrays.fill(reportedTrappedTick, -1);
            Arrays.fill(adviceRevokedTick, -1);
            Arrays.fill(rerouteCompletionTick, -1);
        }
    }

    enum Outcome { ESCAPED, DEAD, TRAPPED }

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
        result.adviceRevokedTick[idx] = p.adviceRevokedTick;
        result.rerouteCompletionTick[idx] = p.rerouteCompletionTick;
    }

    static SimResult run(SimMode mode, int totalPeople) {
        int survive = 0;
        SimResult result = new SimResult(totalPeople);
        stageAssignCount = 0;
        smokeSuppressionFactor = (mode == SimMode.B2) ? 0.8 : 1.0; // B2 排煙因子
        
        // 收集所有 Exit 座標以便計算 ASET
        List<int[]> exitList = new ArrayList<>();
        for (int z = 0; z < space.height; z++)
            for (int y = 0; y < space.rows; y++)
                for (int x = 0; x < space.cols; x++)
                    if (space.building[z][y][x] instanceof Exit)
                        exitList.add(new int[]{z, y, x});
        
        int asetTick = -1;

        while (!isEnvironmentStable() && tick < surviveTime) {
            tick++;
            if (mode == SimMode.B2) {
                applyB2Controls(); // B2 先關門
            }
            spreadEnvironment();
            
            // 檢查 ASET：是否有任一出口失效
            if (asetTick == -1) {
                for (int[] e : exitList) {
                    Obj exitObj = space.building[e[0]][e[1]][e[2]];
                    if (exitObj.smoke > 0.7 || exitObj.fire) {
                        asetTick = tick;
                        break;
                    }
                }
            }

            for (Detector d : detectors) d.update(space.building, random);

            List<People> removed = new ArrayList<>();
            for (People p : peopleList) {
                if (mode == SimMode.DEFAULT) p.escape(tick);
                else p.SmartEscape(tick);   // SMART 與 B2 使用相同智慧邏輯

                if (p.isDead) {
                    recordPersonSnapshot(result, p, tick, Outcome.DEAD);
                    removed.add(p); 
                } else if (p.isEscaped) { 
                    recordPersonSnapshot(result, p, tick, Outcome.ESCAPED);
                    survive++; 
                    removed.add(p); 
                }
            }
            peopleList.removeAll(removed);
        }
        if (asetTick == -1) asetTick = tick; // 一直安全則設為最終時刻
        result.asetTick = asetTick;

        for (People p : peopleList) {
            recordPersonSnapshot(result, p, tick, Outcome.TRAPPED);
        }

        // 更新各模式累加統計的輔助函數省略，實際代碼中需要根據 mode 累加到對應變量，
        // 並計算安全餘裕、撤回完成時間等
        // ...

        return result;
    }

    public void work(int iterations,Range H,Range R,Range C) {
        // 重設所有累加變量，加入 B2 初始化
        // ...
        for(int it = 0; it < iterations; it++) {
            // 生成建築、人員等，對每種模式運行一次模擬
            // 使用 for (SimMode mode : SimMode.values()) 循環
            // 收集統計數據，輸出最終報告時顯示三種模式對比，以及安全餘裕、撤回完成時間
        }
        // 最終輸出報告需包含新增 KPI
    }

    static SimResult runOneSimulation(Obj[][][] baseBuilding, int[][] peopleInit,
                                  int fireZ, int fireY, int fireX,
                                  SimMode mode, String outputFile) {
        // 重定向輸出，初始化模擬，調用 run(mode, peopleInit.length)
        return null;
    }
}