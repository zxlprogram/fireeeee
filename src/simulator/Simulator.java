package simulator;
//請至main函數調整模擬資訊，如果想要改參數請直接跟我說，除非你是屎山代碼終結者
import java.io.*;
import java.util.*;

class sim {
    public static void main(String[]args) {
        Simulator s = new Simulator();
        //輸入:建築類型數量、每個建築類型要跑幾組火源/人物場景、樓高範圍、樓寬範圍、樓長範圍
        //輸出:每個建築類型的小結報表、跨建築類型總表、過程文檔(會隨著模擬次數被覆蓋，只會看到最後一次模擬的文檔)
        //注意事項:時間複雜度超大，一個網格會計算約0.7毫秒
        //         總場景數 = 建築類型數量 × 每個建築類型的場景數，請自行斟酌調整
        Data[] data = new Data[10];
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(new Range(1,11), new Range(5,51), new Range(5,51));
        }
        s.work(data, 10);
    }
}
// ─── 基礎物件 ────────────────────────────────────────────────
class Obj {
    boolean fire;
    double smoke = 0.0; // 煙霧數值 (0.0 ~ 1.0)：有害、觸發逃生判斷用的濃煙
    double smell = 0.0; // 【新增】煙味數值 (0.0 ~ 1.0)：無害，純粹用來觸發People的「察覺」，
                         // 比smoke更容易穿透門縫(見Simulator類別頂端的擴散機率常數)
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

 // ─── 【新增】察覺(Awareness)追蹤 ──────────────────────
 // 呼應羅老師 decision-window.js 的 DecisionWindowTracker 的「察覺(cue)」概念：人物不再
 // 「開局即逃生」，而是要先「察覺」異常(聞到煙味/看到煙火，或[有系統時]收到系統示警)才會
 // 進入逃生狀態；尚未察覺前，這個tick停留原地不動，之後就照原本的邏輯(可能移動、也可能卡在
 // 找小孩/等同伴/等救援/恐慌延遲等分支)繼續跑。
 // 【移除】原本這裡還有 firstMovementAt / 準備延遲 / 移動時間 這一組「察覺→第一次移動」的
 // 拆解診斷指標，已依使用者判斷移除：這個模擬不假設任何「互動延遲」是可以被客觀量化的，
 // 恐慌、找小孩、等同伴這些行為確實會造成延遲，但延遲量沒有客觀依據可以精確估計，
 // 硬是把它拆成一個平均數字反而是一種沒有根據的假設。只留 firstCueAt(察覺時間)本身——
 // 這是「有沒有聞到/看到/被通知」的客觀判定，不涉及對行為延遲量的假設，
 // 且不會拿去重新定義 RSET/ASET/安全餘裕的基準點——那三者仍統一以「點火(tick=0)」為基準。
 boolean aware = false;
 Integer firstCueAt = null;
 private static final double AWARENESS_SMOKE_THRESHOLD = 0.05; // 「聞到煙味」門檻，比對Obj.smell欄位(不是smoke)，遠低於逃生判斷用的濃煙門檻(0.7)，本身無害但足以觸發察覺

 // ─── 【修改】建議生命週期(發布 / 連通性檢查撤回) ──────────────────
 // 原本：SmartEscape 只有「要換樓層」才會建立一筆正式建議，且撤回判定是固定計時器
 // (ADVICE_VALIDITY_TICKS)，跟環境是否真的變危險無關。
 // 現在：
 //   1) 撤回判定改成「連通性檢查」──用目前已知的火/危險格(knownFires/knownHazards)跑一次
 //      可達性檢查，只要「目前位置→任務目標格」還存在一條不經過火/高危格的路徑，建議就繼續
 //      有效；不存在了才撤回。目標本身著火(targetStage.fire / 同樓層目標格.fire)仍是既有的
 //      立即撤回條件，不用等連通性檢查才發現。
 //   2) 任務單位擴充到同樓層──同樓層內移動不再是每tick重算一次新路徑，而是把「下一個樓梯間」
 //      或「下一個分岔格(可通行相鄰格數>2)」包裝成一筆正式的同樓層任務(junctionTargetPos)，
 //      沿快取路徑(junctionPath)走完才重新規劃下一段。
 Integer adviceIssuedTick = null;      // 目前這筆建議(不論跨樓層或同樓層)的發布時間
 Integer adviceRevokedTick = null;     // 建議被撤回(環境改變或連通性喪失)的那個tick，null代表目前沒有「待補建議」的空窗
 Integer revokeToRerouteTicks = null;  // 【KPI】撤回後到完成改道(拿到下一筆任務目標格)所花的tick數；整場模擬只記錄第一次撤回事件

 // ─── 【新增】同樓層路網分岔點任務 ──────────────────────────────
 // targetStage(既有欄位，見上方宣告)沿用於「跨樓層」任務：指派後延到下個tick開頭才真正跨過去。
 // 這裡新增的是「同樓層」任務：目標格是路網上的下一個決策點(下一個樓梯間/分岔格/出口，取先到者)，
 // 中途沿著快取好的路徑走，不必每tick重新呼叫Dijkstra；到達後才重新規劃下一段。
 int[] junctionTargetPos = null;       // 這次同樓層任務的目標格座標 {z,y,x}
 List<int[]> junctionPath = null;      // 從發布當下到目標格的路徑快取(不含起點)，之後每tick依序走
 int junctionPathIdx = 0;              // 目前走到快取路徑的第幾格
 private static final int ADVICE_SAFETY_CAP_TICKS = 200; // 【保留】極寬鬆的安全上限，只防止pathfinding異常時卡死；
                                                           // 不再是主要撤回理由，正常情況下不該被觸發到

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

     // 【新增】尚未察覺異常(無系統：靠聞煙味/看到煙火)，這個tick原地不動
     if (stillUnaware(currentTick, false)) return;

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
     if (rng.nextDouble() < panicLevel * PANIC_DELAY_CHANCE_FACTOR) {
         return;
     }

     instinctiveEscapeStep(currentTick);
 }

 // ────────────────────────────────────────────────────────
 // 【新增】察覺判定：回傳true代表「這個tick仍在察覺前，維持原地」，呼叫端應直接return。
 // 無系統：聞到煙味(局部smoke超過門檻)或看到煙火才會察覺。
 // 有系統：上述條件之外，只要仍連線，系統示警(systemAwarenessTick已設定)也能觸發察覺，
 // 模擬「手機收到緊急通知」比自己聞到煙味更早的情境。
 // ────────────────────────────────────────────────────────
 private boolean stillUnaware(int currentTick, boolean hasSystemSupport) {
     if (aware) return false;

     // 【修改】改用專門的smell欄位判斷「聞到煙味」，不再直接看smoke——
     // smell是無害、擴散範圍更廣(含較容易穿透門縫)的獨立訊號，見Simulator.spreadEnvironment()
     boolean smellsSmoke = space.building[z][y][x].smell > AWARENESS_SMOKE_THRESHOLD;
     boolean seesFire = seesFireNearby();
     boolean systemAlert = hasSystemSupport && networkConnected
             && Simulator.systemAwarenessTick != null && currentTick >= Simulator.systemAwarenessTick;

     if (smellsSmoke || seesFire || systemAlert) {
         aware = true;
         firstCueAt = currentTick;
         return false;
     }
     return true;
 }

 // 【重寫】察覺用的「看到」判定：不再只看相鄰四格，改用完整視線(LOS)範圍。
 // 概念(陰影投射 shadow casting)：牆(或已卡死/燒毀的門)這類不透光的障礙物格子，
 // 會在觀察者背後投射出一個「視覺盲區」——盲區的角度範圍，由障礙物格子的
 // 四個角落(格心 ±0.5)決定：從觀察者位置分別畫向四個角落共四條線(四個方向向量)，
 // 取「夾角最大、且夾角<=180度」的那兩條線，作為盲區的兩條邊界。
 // 任何一個目標點，只要 1) 方向落在這兩條邊界夾出的角度內，
 // 且 2) 與觀察者的直線距離(畢氏定理)比「障礙物本身與觀察者的直線距離」更遠，
 // 就會被這個障礙物擋住、判定為看不到。只要被任一障礙物擋住就算看不到。
 // 注意：這是完整LOS掃描，每個尚未察覺的人每個tick都要重算，成本比原本相鄰四格版本高很多，
 // 地圖越大/障礙物越多越明顯，這是刻意的取捨(正確性優先)。
 private boolean seesFireNearby() {
     if (space.building[z][y][x].fire) return true;
     for (int ty = 0; ty < space.rows; ty++) {
         for (int tx = 0; tx < space.cols; tx++) {
             if (ty == y && tx == x) continue;
             if (!space.building[z][ty][tx].fire) continue;
             if (canSee(ty, tx)) return true;
         }
     }

     // 【新增】梯間跨樓層視線：預設「看到」不包含其他樓層，唯一例外是梯間(Stage)——
     // 只要人站在梯間本身、或緊鄰梯間的上下左右四格之一，就能透過梯井直接看到
     // 這個梯間在上一層/下一層的對應網格是否著火。若梯間這一格本身煙霧濃到看不穿
     // (smoke>0.7)或已經著火，代表梯井入口本身就被擋住，看不進去，就不算看到。
     int[] dy4 = {-1, 1, 0, 0}, dx4 = {0, 0, -1, 1};
     for (int i = -1; i < 4; i++) {
         int sy = (i == -1) ? y : y + dy4[i];
         int sx = (i == -1) ? x : x + dx4[i];
         if (!space.isValid(z, sy, sx)) continue;

         Obj cell = space.building[z][sy][sx];
         if (!(cell instanceof Stage)) continue;
         if (isOpaque(cell)) continue;

         Stage stage = (Stage) cell;
         for (Stage linked : new Stage[]{ stage.upfloor, stage.downfloor }) {
             if (linked != null && linked.fire) return true;
         }
     }
     return false;
 }

 // 判斷本層(z不變)是否有暢通視線能看到(targetY,targetX)：
 // 只要被任何一個不透光障礙物格子擋住(見inShadowOf)，就視為看不到。
 private boolean canSee(int targetY, int targetX) {
     for (int oy = 0; oy < space.rows; oy++) {
         for (int ox = 0; ox < space.cols; ox++) {
             if (oy == y && ox == x) continue;             // 自己所在格不算障礙
             if (oy == targetY && ox == targetX) continue; // 目標本身不能擋住自己
             if (!isOpaque(space.building[z][oy][ox])) continue;
             if (inShadowOf(y, x, oy, ox, targetY, targetX)) return false;
         }
     }
     return true;
 }

 // 判定哪些格子屬於「不透光」障礙物，會擋住視線讓人看不到後面的東西：
 //   - 牆：一定擋
 //   - 已被火封死的門(blocked)：視為障礙物，一定擋
 //   - 防火門：關著(isOpen=false)才擋，開著(isOpen=true)就看得穿
 //   - 一般門(非FireDoor)：本身沒有isOpen欄位，視為恆常關閉，一定擋
 //   - 濃煙：該格smoke > 0.7時，視線被煙霧遮蔽，擋住後方視線
 //   - 火源：該格本身正在燒(fire=true)，火焰/熱浪擋住後方視線
 private boolean isOpaque(Obj o) {
     if (o instanceof Wall) return true;
     if (o.fire) return true;
     if (o.smoke > 0.7) return true;
     if (o instanceof Door && ((Door) o).blocked) return true;
     if (o instanceof FireDoor) return !((FireDoor) o).isOpen;
     if (o instanceof Door) return true;
     return false;
 }

 // 2D方向向量外積(叉積)，用來判斷target落在v1→v2夾角的哪一側
 private static double cross(double ax, double ay, double bx, double by) {
     return ax * by - ay * bx;
 }

 // 判斷(targetY,targetX)是否落在障礙物(obsY,obsX)相對於觀察者(viewY,viewX)投射出的盲區內
 private boolean inShadowOf(double viewY, double viewX, double obsY, double obsX,
                             double targetY, double targetX) {
     // 障礙物格子四個角落(格心 ±0.5)
     double[][] corners = {
         { obsY - 0.5, obsX - 0.5 },
         { obsY - 0.5, obsX + 0.5 },
         { obsY + 0.5, obsX - 0.5 },
         { obsY + 0.5, obsX + 0.5 },
     };

     // 四個角落相對觀察者的方向向量 {dx, dy}
     double[][] dirs = new double[4][2];
     for (int i = 0; i < 4; i++) {
         dirs[i][0] = corners[i][1] - viewX; // dx
         dirs[i][1] = corners[i][0] - viewY; // dy
     }

     // 找出「夾角最大」的一對角落方向，作為盲區的兩條邊界。
     // acos()的值域天生就是[0,180]，所以「夾角<=180」這個限制已經自動滿足，
     // 不需要額外判斷。
     int bestI = 0, bestJ = 1;
     double bestAngle = -1;
     for (int i = 0; i < 4; i++) {
         for (int j = i + 1; j < 4; j++) {
             double dot = dirs[i][0] * dirs[j][0] + dirs[i][1] * dirs[j][1];
             double lenI = Math.hypot(dirs[i][0], dirs[i][1]);
             double lenJ = Math.hypot(dirs[j][0], dirs[j][1]);
             double cosA = Math.max(-1.0, Math.min(1.0, dot / (lenI * lenJ)));
             double angle = Math.acos(cosA);
             if (angle > bestAngle) {
                 bestAngle = angle;
                 bestI = i; bestJ = j;
             }
         }
     }

     double v1x = dirs[bestI][0], v1y = dirs[bestI][1];
     double v2x = dirs[bestJ][0], v2y = dirs[bestJ][1];
     double tx = targetX - viewX, ty = targetY - viewY;

     // 角度落點測試：target的方向向量是否落在v1、v2夾出的(較小)那個扇形內
     double cross12 = cross(v1x, v1y, v2x, v2y);
     double crossV1T = cross(v1x, v1y, tx, ty);
     double crossTV2 = cross(tx, ty, v2x, v2y);
     boolean withinAngle = (cross12 >= 0)
         ? (crossV1T >= 0 && crossTV2 >= 0)
         : (crossV1T <= 0 && crossTV2 <= 0);
     if (!withinAngle) return false;

     // 距離測試：只有比障礙物本身更遠的東西，才會被這個障礙物擋住
     double distObs    = Math.hypot(obsY - viewY, obsX - viewX);
     double distTarget = Math.hypot(targetY - viewY, targetX - viewX);
     return distTarget > distObs;
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

     // 【新增】尚未察覺異常(有系統：聞煙味/看到煙火 或 收到系統示警)，這個tick原地不動
     if (stillUnaware(currentTick, true)) return;

     updateConnectivity();
     updatePanic();

     // ─── 【修改】撤回判定改用連通性檢查 ──────────────────────────
     // 「身上有任務」分兩種：targetStage(跨樓層，下個tick開頭直接執行跨越)，
     // 或 junctionTargetPos(同樓層任務，目標是下一個決策點，沿快取路徑走)。
     // 兩種都用同一套邏輯判斷是否撤回：目標本身著火→立即撤回；否則用目前已知的
     // 火/危險格跑一次連通性檢查，「目前位置→任務目標格」不再存在安全路徑才撤回。
     if (targetStage != null) {
         boolean fireHit = targetStage.fire;
         boolean lostConnectivity = false;
         if (!fireHit) {
             HazardKnowledge hk = gatherKnownHazards();
             lostConnectivity = !isReachable(z, y, x, targetStage.z, targetStage.y, targetStage.x,
                 hk.knownFires, hk.knownHazards);
         }
         if (fireHit || lostConnectivity) {
             // 【建議撤回】目標樓梯間著火，或已知資訊顯示這條路已經不通了，視為系統主動撤回這筆建議，
             // 下個tick重新規劃改道；記錄撤回時間，用於統計「撤回後多久才拿到新建議」
             if (adviceRevokedTick == null) adviceRevokedTick = currentTick;
             System.out.println("[ADVICE-REVOKE][SMART] tick=" + currentTick
                 + " id=" + id
                 + " reason=" + (fireHit ? "ENV_CHANGE" : "CONNECTIVITY_LOST")
                 + " issuedTick=" + adviceIssuedTick
                 + " pos=(" + z + "," + y + "," + x + ")"
                 + " selfSmoke=" + String.format("%.2f", space.building[z][y][x].smoke)
                 + " selfFire=" + space.building[z][y][x].fire
                 + " targetStage=(" + targetStage.z + "," + targetStage.y + "," + targetStage.x + ")"
                 + " targetSmoke=" + String.format("%.2f", targetStage.smoke)
                 + " rerouteAttempts(before)=" + rerouteAttempts);
             targetStage = null;
             adviceIssuedTick = null;
             rerouteAttempts++;
             everRerouted = true;
         } else {
             this.z = targetStage.z; this.y = targetStage.y; this.x = targetStage.x;
             targetStage = null;
             adviceIssuedTick = null; // 建議已成功執行完畢，生命週期結束
             checkStatus();
             if (isDead || isEscaped) return;
         }
     } else if (junctionTargetPos != null) {
         Obj junctionObj = space.building[junctionTargetPos[0]][junctionTargetPos[1]][junctionTargetPos[2]];
         boolean fireHit = junctionObj.fire;
         boolean safetyCapHit = adviceIssuedTick != null
             && (currentTick - adviceIssuedTick) > ADVICE_SAFETY_CAP_TICKS;
         boolean lostConnectivity = false;
         if (!fireHit && !safetyCapHit) {
             HazardKnowledge hk = gatherKnownHazards();
             lostConnectivity = !isReachable(z, y, x,
                 junctionTargetPos[0], junctionTargetPos[1], junctionTargetPos[2],
                 hk.knownFires, hk.knownHazards);
         }
         if (fireHit || lostConnectivity || safetyCapHit) {
             if (adviceRevokedTick == null) adviceRevokedTick = currentTick;
             System.out.println("[ADVICE-REVOKE][SMART] tick=" + currentTick
                 + " id=" + id
                 + " reason=" + (fireHit ? "ENV_CHANGE" : (safetyCapHit ? "SAFETY_CAP" : "CONNECTIVITY_LOST"))
                 + " issuedTick=" + adviceIssuedTick
                 + " pos=(" + z + "," + y + "," + x + ")"
                 + " selfSmoke=" + String.format("%.2f", space.building[z][y][x].smoke)
                 + " selfFire=" + space.building[z][y][x].fire
                 + " junctionTarget=(" + junctionTargetPos[0] + "," + junctionTargetPos[1] + "," + junctionTargetPos[2] + ")"
                 + " targetSmoke=" + String.format("%.2f", junctionObj.smoke)
                 + " rerouteAttempts(before)=" + rerouteAttempts);
             junctionTargetPos = null; junctionPath = null; junctionPathIdx = 0;
             adviceIssuedTick = null;
             rerouteAttempts++;
             everRerouted = true;
         }
         // 仍有效的話這個tick不特別處理，交給下面主迴圈沿快取路徑走
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
     if (rng.nextDouble() < panicLevel * PANIC_DELAY_CHANCE_FACTOR) {
         return;
     }

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

     // ─── 【修改】同樓層移動也是正式任務單位：沿快取好的junctionPath走，
     // 只有在「沒有任何進行中任務」或「剛走到任務目標格」時才重新呼叫 computeSmartPath() 規劃下一段。
     boolean moved = false;
     for (int step = 0; step < speed; step++) {
         if (targetStage != null) {
             // 上一輪迴圈(或這個tick最上面)剛指派了「下個tick跨樓層」的任務，
             // 這個tick先停在這裡，實際跨越動作留到下個tick開頭執行。
             moved = true;
             break;
         }

         if (junctionTargetPos == null) {
             boolean issued = planNextAdvice(currentTick);
             if (!issued) { if (!moved) markReportedTrapped(currentTick); break; }
             if (targetStage != null) { moved = true; break; } // 剛規劃出的任務是跨樓層，這個tick先停在這裡
         }

         int[] nextCell = junctionPath.get(junctionPathIdx);
         moved = true;
         this.z = nextCell[0]; this.y = nextCell[1]; this.x = nextCell[2];
         junctionPathIdx++;
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

         if (z == junctionTargetPos[0] && y == junctionTargetPos[1] && x == junctionTargetPos[2]) {
             // 抵達這次任務的目標格(分岔點/本層樓梯間/出口)，任務結束；
             // 下一輪迴圈(若還有剩餘speed)會重新呼叫 planNextAdvice() 規劃下一段。
             junctionTargetPos = null; junctionPath = null; junctionPathIdx = 0;
             adviceIssuedTick = null;
         }
     }
     if (!moved) randomMove(speed);
 }

 // ─── 【新增】建議發布：規劃出「下一個決策點」並包裝成一筆正式任務 ──────────────
 // 回傳true代表成功規劃出新任務(可能是跨樓層的下一個樓梯間，也可能是同樓層的下一個
 // 分岔格/本層樓梯間/出口)；回傳false代表目前完全找不到任何可走的方向(受困)。
 private boolean planNextAdvice(int currentTick) {
     List<int[]> path = computeSmartPath();
     if (path == null || path.size() < 2) return false;

     int idx = findDecisionPointIndex(path);
     int[] decision = path.get(idx);

     adviceIssuedTick = currentTick;
     if (adviceRevokedTick != null) {
         // 【KPI】只要重新規劃出新的任務目標格(不論是否跨樓層)，就視為改道完成，結算撤回→改道延遲；
         // 整場模擬只記錄本人第一次撤回事件
         if (revokeToRerouteTicks == null) revokeToRerouteTicks = currentTick - adviceRevokedTick;
         adviceRevokedTick = null;
     }

     if (decision[0] != this.z) {
         // 下一步就要跨樓層(代表此刻本來就站在樓梯間格上，下一格是另一層的樓梯間)：
         // 沿用既有機制，先指派 targetStage，實際跨樓層動作留到下個tick開頭執行。
         this.targetStage = (Stage) space.building[decision[0]][decision[1]][decision[2]];
         Simulator.stageAssignCount++; // 追蹤「跨樓層目標指派」次數
     } else {
         // 同樓層的下一個決策點(分岔格/本層樓梯間/出口)：快取路徑，之後每tick沿著走。
         junctionTargetPos = decision;
         junctionPath = new ArrayList<>(path.subList(1, idx + 1));
         junctionPathIdx = 0;
     }
     return true;
 }

 // ─── 【新增】從一條完整路徑中找出「下一個決策點」在路徑中的index ─────────────
 // 沿路徑往前掃，遇到樓梯間(Stage)、出口(Exit)、或分岔格(可通行相鄰格數>2，代表這裡
 // 不是單純走廊直線)三者中最先出現的那一個，當作這次任務的目標格；
 // 如果整條路徑都沒遇到(距離很短、直接走到終點前都是直線走廊)，目標格就是路徑最後一格。
 private int findDecisionPointIndex(List<int[]> path) {
     for (int i = 1; i < path.size(); i++) {
         int[] c = path.get(i);
         Obj o = space.building[c[0]][c[1]][c[2]];
         if (o instanceof Stage || o instanceof Exit || isJunctionCell(c[0], c[1], c[2])) {
             return i;
         }
     }
     return path.size() - 1;
 }

 // 判斷某格是否為「分岔點」：可通行的4方向相鄰格數 > 2，代表這裡不是單純走廊直線，
 // 是路網上真正需要抉擇方向的節點。
 private boolean isJunctionCell(int cz, int cy, int cx) {
     int passableCount = 0;
     int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
     for (int i = 0; i < 4; i++) {
         int ny = cy + dy[i], nx = cx + dx[i];
         if (!space.isValid(cz, ny, nx)) continue;
         if (isPassable(space.building[cz][ny][nx])) passableCount++;
     }
     return passableCount > 2;
 }

 // ─── 【新增】連通性檢查：用目前已知的火/危險格，判斷「起點→目標格」是否仍存在一條
 // 不經過火/高危格的路徑。只需要知道「通不通」，用BFS即可，不需要算最短路，比重跑一次
 // Dijkstra便宜很多，適合每tick都要做的撤回判定。
 private boolean isReachable(int fz, int fy, int fx, int tz, int ty, int tx,
                              Set<Integer> knownFires, Set<Integer> knownHazards) {
     int startKey = bitKey(fz, fy, fx);
     int goalKey = bitKey(tz, ty, tx);
     if (startKey == goalKey) return true;

     Set<Integer> visited = new HashSet<>();
     Deque<Integer> queue = new ArrayDeque<>();
     visited.add(startKey);
     queue.add(startKey);

     while (!queue.isEmpty()) {
         int currKey = queue.poll();
         if (currKey == goalKey) return true;

         int[] c = decodeKey(currKey);
         Obj currObj = space.building[c[0]][c[1]][c[2]];

         int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
         for (int i = 0; i < 4; i++) {
             int nz = c[0], ny = c[1] + dy[i], nx = c[2] + dx[i];
             if (!space.isValid(nz, ny, nx)) continue;
             Obj nextObj = space.building[nz][ny][nx];
             if (!isPassable(nextObj)) continue;
             int nextKey = bitKey(nz, ny, nx);
             if (knownHazards.contains(nextKey)) continue; // 不經過火/高危格
             if (visited.add(nextKey)) queue.add(nextKey);
         }

         if (currObj instanceof Stage) {
             Stage s = (Stage) currObj;
             for (Stage next : new Stage[]{s.upfloor, s.downfloor}) {
                 if (next == null || !isPassable(next)) continue;
                 int nextKey = bitKey(next.z, next.y, next.x);
                 if (knownHazards.contains(nextKey)) continue;
                 if (visited.add(nextKey)) queue.add(nextKey);
             }
         }
     }
     return false;
 }

 // ─── 【新增】把「蒐集目前已知火/危險格」抽成獨立函式 ────────────────────────
 // 原本這段邏輯寫死在 computeSmartPath() 裡；現在撤回判定(isReachable的連通性檢查)
 // 也需要用到同一份「目前已知」的火/危險格資訊，所以抽出來共用，避免邏輯重複兩份。
 private static class HazardKnowledge {
     Set<Integer> knownFires;
     Set<Integer> knownHazards;
 }

 private HazardKnowledge gatherKnownHazards() {
     HazardKnowledge hk = new HazardKnowledge();
     hk.knownHazards = new HashSet<>();
     hk.knownFires = new HashSet<>();

     for (Detector d : Simulator.detectors) {
         int k = bitKey(d.z, d.y, d.x);
         if (d.broken) {
             hk.knownHazards.add(k); hk.knownFires.add(k);
         } else if (d.danger) {
             hk.knownHazards.add(k);
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
             hk.knownFires.add(lKey); hk.knownHazards.add(lKey);
         }
     }

     if (space.building[z][y][x] instanceof Stage) {
         Stage st = (Stage) space.building[z][y][x];
         for (Stage nextSt : new Stage[]{st.upfloor, st.downfloor}) {
             if (nextSt != null) {
                 int stKey = bitKey(nextSt.z, nextSt.y, nextSt.x);
                 if (nextSt.fire) {
                     hk.knownFires.add(stKey); hk.knownHazards.add(stKey);
                 } else if (nextSt.smoke > 0.7) {
                     hk.knownHazards.add(stKey);
                 }
             }
         }
     }
     return hk;
 }

 // 【修改】不再只回傳「下一步」，改成回傳從起點(含)到目標(通常是Exit，找不到則是
 // maximizeSurvivalTime()給的逃生方向)的完整路徑，讓呼叫端(planNextAdvice)可以沿著
 // 這條路徑找出「下一個決策點(樓梯間/分岔格/出口)」，把同樓層移動也包裝成正式任務。
 private List<int[]> computeSmartPath() {
     HazardKnowledge hk = gatherKnownHazards();
     Set<Integer> knownHazards = hk.knownHazards;
     Set<Integer> knownFires = hk.knownFires;

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
         List<int[]> path = new ArrayList<>();
         int curr = targetPosKey;
         path.add(decodeKey(curr));
         while (parentMap.containsKey(curr)) {
             curr = parentMap.get(curr);
             path.add(decodeKey(curr));
         }
         Collections.reverse(path); // path.get(0) == 起點(z,y,x)
         return path;
     }

     int[] fallbackStep = maximizeSurvivalTime(knownFires, knownHazards);
     if (fallbackStep == null) return null;
     List<int[]> path = new ArrayList<>();
     path.add(new int[]{z, y, x});
     path.add(fallbackStep);
     return path;
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
     if (this.accumulatedCO >= CRITICAL_CO_RATIO * this.effectiveCoThreshold()) everReachedCriticalCO = true;
     
     // 死亡條件：踩到火，或者 CO 累積超過該角色的「有效」閾值(角色卡基準閾值 × 本場起火原因的煙霧毒性倍率)
     
     
     if (cell.fire || this.accumulatedCO >= this.effectiveCoThreshold()) {
         isDead = true;
     } 
     else if (cell instanceof Exit) {
         isEscaped = true;
     }
 }

 // ─── 【新增】煙霧致命閾值跟起火原因掛勾 ────────────────────────────────
 // 角色卡原本的 coThreshold 只反映「個人體質」(成年人/幼童/行動不便者/年長者)；
 // 現在再疊加「這場火的起火原因」對煙霧毒性的影響──化學/縱火起火的煙霧毒性較高，
 // 同樣的個人體質下，有效閾值會被下修，代表同樣的CO累積量下更快達到致命條件。
 private double effectiveCoThreshold() {
     Simulator.FireCause cause = Simulator.currentCause;
     double factor = (cause != null) ? cause.smokeToleranceFactor : 1.0;
     return this.coThreshold * factor;
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
class Data {
	Range H, R, C;
	public Data(Range H, Range R, Range C) {
		this.H = H;
		this.R = R;
		this.C = C;
	}
}
// ─── 主模擬 ───────────────────────────────────────────────────
public class Simulator {
	enum FireCause {
	    // 【修改】除了原本的火/煙擴散速度差異(見spreadEnvironment)，
	    // 現在每種起火原因還帶一個 smokeToleranceFactor：人員對煙霧的「有效耐受倍率」，
	    // <1.0代表這種起火原因的煙霧毒性較高，等同下修角色卡原本的CO致命閾值(見People.effectiveCoThreshold())；
	    // 1.0代表維持角色卡原本設定的閾值，不額外加成也不額外懲罰。
	    ELECTRICAL(1.00), // 電線走火：初期火勢蔓延極快，但起初煙霧較少；煙霧毒性維持一般水準
	    CHEMICAL(0.55),   // 化學起火：化學煙霧蔓延極快，且毒性遠高於一般燃燒煙霧，致死率最高
	    ARSON(0.80),      // 縱火：使用了助燃劑，火跟煙都很快，煙霧毒性也偏高
	    ACCIDENTAL(1.00); // 一般意外(預設)：正常的擴散速度，煙霧毒性維持一般水準

	    final double smokeToleranceFactor;
	    FireCause(double smokeToleranceFactor) {
	        this.smokeToleranceFactor = smokeToleranceFactor;
	    }
	}

    // 【新增】三種比較情境：
    //   DEFAULT - 沒有智慧系統，純靠個人直覺逃生
    //   SMART   - 只有「決策支援」：系統給建議，人是否服從仍取決於連線/服從率/恐慌
    //   HYBRID  - 「決策支援 + 備援控制」：除了給建議，系統在偵測到危險時也會主動操作環境
    //             (自動關閉未關緊的防火門、局部啟動排煙/抑制煙霧擴散)，即使人沒有服從建議，
    //             環境本身也因為系統的備援控制而變得比較安全
    enum SimMode { DEFAULT, SMART, HYBRID }

    static Space space;
    static int surviveTime=50000;//活過5000時刻就算被救活，之後要改成滅火行動
    static List<People> peopleList = new ArrayList<>();
    static List<Detector> detectors = new ArrayList<>(); 
    // peopleList 會隨著死亡/逃脫被移除，所以另外保留一份不會被清空的登記表，
    // 讓「同行者」邏輯在同伴已經離開 peopleList 之後，仍能查到對方最後的狀態(存活/死亡/逃脫)
    static Map<Integer, People> allPeopleById = new HashMap<>();
    static int  tick   = 0;
    static FireCause currentCause = FireCause.ACCIDENTAL;
    static Random random = new Random();

    static double TotalAccuracySum = 0; // 總體準確率累加值
    static long TotalPeopleSum = 0; // 跨迭代的總人數累計

    // ─── 【新增】Session JSON 匯出 ─────────────────────────────
    // 每次 runOneSimulation() 都會重新建立一個 SessionExporter，記錄該場模擬(單一
    // 建築+單一起火點+單一模式)的靜態地圖、逐tick快照、關鍵事件，並在 run() 結束時
    // 匯出成 JSON 檔。因為檔名固定(依mode區分)，跟既有的 defaultResult.txt 等文字紀錄
    // 一樣，只會保留「最後一次」跑該 mode 的那份 session 記錄。
    static SessionExporter sessionExporter;
    static String sessionJsonFilename = "session_export.json";

    // ─── 【新增】改道成功率除錯用計數器 ───────────────────────────
    // 每次 run() 開始時歸零。記錄本場模擬中「有多少次被指派跨樓層目標(Stage)」，
    // 這是「有沒有機會觸發改道」的前提；如果這個數字是0，代表根本沒人跨樓層移動，
    // rerouteAttemptCount=0 是理所當然的，不是bug。
    // 如果這個數字>0但rerouteAttemptCount還是0，代表跨樓層時「目標樓梯間剛好在那個瞬間著火」
    // 的情況從未發生過，要去看火勢蔓延到Stage的時機/機率是否合理。
    static int stageAssignCount = 0;

    // ─── 【新增】B2情境專用：備援控制是否啟用、以及本場模擬觸發了幾次主動控制動作 ───
    static boolean activeControlEnabled = false; // 只有 HYBRID 模式會設為 true
    static int activeControlActionCount = 0;     // 本場模擬中，系統主動關閉防火門/啟動排煙的次數

    // ─── 【修改】ASET(可用安全逃生時間)追蹤 ───────────────────────
    // 原本用「整棟建築陷入危險的樓地板面積比例」來近似ASET，但建築動輒上千格、
    // 煙又是指數成長，20%面積門檻通常十幾個tick就達到，而人要走完整棟樓的
    // avgEscapeTick(RSET)往往是幾十~幾百tick，兩者尺度完全不對等，算出來的
    // 「安全餘裕」幾乎必定是很大的負值，沒有反映系統是否真的有幫助。
    // 改用消防工程更常見的定義：ASET = 逃生「出口本身」開始變得不可用(著火/濃煙)的那個tick，
    // 因為出口一旦失去可用性，不管走廊多安全，逃生行動實質上都已經失敗。
    // 若整場模擬出口自始至終都沒被威脅到，代表逃生容量始終充足，asetTick維持null，
    // 不計入安全餘裕平均(而不是硬湊一個數字)。
    static Integer asetTick = null;

    // 【新增】察覺模型用：系統(感測器網路)最早偵測到危險/損毀的tick，null代表本場尚未偵測到。
    // SmartEscape() 的 stillUnaware() 用它模擬「手機收到緊急通知」這個察覺管道。
    static Integer systemAwarenessTick = null;

    // 新增狀態暫存變數，用於判定環境是否已無變化
    static double lastSmokeSum = -1;
    static int lastFireCount = -1;

    // ─── 【新增】各情境(DEFAULT/SMART/HYBRID)的統計累加容器 ───────
    // 「不是每場模擬都會發生」的KPI(改道、弱勢辨識、ASET等)額外用 *IterCount 只對「有意義的場次」取平均，
    // 避免被沒發生該事件的場次拉低。
    static class ModeStats {
        double totalSurviveP = 0, totalDeathP = 0, totalTrappedP = 0; // 【手稿修改建議1】三分類比例：逃生/死亡/受困
        double highSmokeP = 0, criticalCOP = 0, wrongRouteP = 0;
        double rerouteSuccessSum = 0; int rerouteIterCount = 0;
        double vulnerableIdSum = 0; int vulnerableIterCount = 0;
        double avgEscapeTickSum = 0; int escapeTickIterCount = 0;      // RSET(以點火為基準，僅計入成功逃生者)
        double revokeToRerouteSum = 0; int revokeToRerouteIterCount = 0;   // 建議撤回→改道完成 延遲
        double asetSum = 0; int asetIterCount = 0;                        // ASET(所有可停留格子煙霧>0.7或起火)
        double safetyMarginSum = 0; int safetyMarginIterCount = 0;        // ASET − RSET(avgEscapeTick)，皆以點火為基準
        double activeControlActionSum = 0;                                // 平均每場主動控制動作次數(僅HYBRID有意義)
        // 【新增，手稿修改建議5】決策窗口分解診斷指標，對照 decision-window.js 的 DecisionWindowTracker，
        // 純粹用來拆解「RSET裡面時間都花在哪」，不會回饋進安全餘裕的計算
        double avgAwarenessSum = 0; int awarenessIterCount = 0;     // 平均察覺時間(從點火起算)
        double avgCueRsetSum = 0; int cueRsetIterCount = 0;         // 診斷用：以察覺為基準的RSET(逃脫tick−察覺tick)，僅供對照
    }
    static Map<SimMode, ModeStats> statsByMode = new LinkedHashMap<>();
    static {
        for (SimMode m : SimMode.values()) statsByMode.put(m, new ModeStats());
    }

    // 【新增，手稿第8點】把某一棟建築(或某個場景)的ModeStats併入更大範圍(全建築類型)的累加容器
    static void mergeModeStats(ModeStats target, ModeStats source) {
        target.totalSurviveP += source.totalSurviveP;
        target.totalDeathP += source.totalDeathP;
        target.totalTrappedP += source.totalTrappedP;
        target.highSmokeP += source.highSmokeP;
        target.criticalCOP += source.criticalCOP;
        target.wrongRouteP += source.wrongRouteP;
        target.rerouteSuccessSum += source.rerouteSuccessSum; target.rerouteIterCount += source.rerouteIterCount;
        target.vulnerableIdSum += source.vulnerableIdSum; target.vulnerableIterCount += source.vulnerableIterCount;
        target.avgEscapeTickSum += source.avgEscapeTickSum; target.escapeTickIterCount += source.escapeTickIterCount;
        target.revokeToRerouteSum += source.revokeToRerouteSum; target.revokeToRerouteIterCount += source.revokeToRerouteIterCount;
        target.asetSum += source.asetSum; target.asetIterCount += source.asetIterCount;
        target.safetyMarginSum += source.safetyMarginSum; target.safetyMarginIterCount += source.safetyMarginIterCount;
        target.activeControlActionSum += source.activeControlActionSum;
        target.avgAwarenessSum += source.avgAwarenessSum; target.awarenessIterCount += source.awarenessIterCount;
        target.avgCueRsetSum += source.avgCueRsetSum; target.cueRsetIterCount += source.cueRsetIterCount;
    }

    // ─── 【修改】ASET改用「建築內所有格子(可停留區域)煙霧濃度都>0.7，或本身就是火源」定義 ──
    // 判定範圍只看Floor/Stage(人真的可能停留的格子)：Wall本來就不能站人，Exit/Door在
    // 煙火擴散邏輯裡也不太適合拿來當「陷入危險」的判斷對象(尤其Exit被設計成永遠不積煙)，
    // 全部一起算反而永遠觸發不了。只要還有任何一格Floor/Stage的煙霧沒超過0.7且沒有起火，
    // 代表建築裡還有「可以撐著」的地方，ASET尚未結束；等所有可停留格子都陷入危險，才算數。
    // 【修正】改成「建築內所有可停留格子(Floor/Stage)都著火」才算數，
    // 不再是「每層樓只要1格著火」那種寬鬆定義。這樣迴圈會一直跑到火真的燒滿整棟樓，
    // 而不是火剛竄到每一層的最開頭幾格就停。Wall/Exit/Door不是可停留格，不列入判斷。
    static boolean allFloorsOnFire() {
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

    static boolean allOccupiableCellsUntenable() {
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj o = space.building[z][y][x];
                    if (!(o instanceof Floor) && !(o instanceof Stage)) continue;
                    if (!(o.fire || o.smoke > 0.7)) {
                        return false; // 還有至少一格可停留區域尚未陷入危險
                    }
                }
            }
        }
        return true;
    }

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

    // ─── 【新增】火/煙擴散速度的「時間相位」倍率：跟tick掛勾，且依起火原因而不同形狀 ───
    // 回傳值疊乘在原本跟起火原因掛勾的 smokeMultiplier / fireSpreadTick 之上：
    //   >1.0 代表這個時間點擴散比基準快，<1.0 代表比基準慢，1.0 代表跟基準一致。
    // 目的是讓同一種起火原因，在模擬的不同階段呈現不同的擴散節奏(非線性)，
    // 而不是整場模擬套用同一個固定係數。
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

    // ─── 【新增】三種擴散物(火源/煙霧/煙味)穿越「門」的機率 ───────────────────
    // 防火門(關好時)跟普通門都不再是「完全阻絕」或「完全不管」的二選一，而是各自有一個
    // 穿越機率；同一種擴散物永遠是「防火門機率 < 普通門機率」(防火門本來就該擋比較久)，
    // 跨擴散物則是「火源 < 煙霧 < 煙味」(火勢竄門需要真的燒穿門板/門縫比較難，
    // 煙味只是氣味分子，門縫關不住的程度最高)：
    //   火源穿防火門 < 火源穿普通門 < 煙霧穿防火門 < 煙霧穿普通門 < 煙味穿防火門 < 煙味穿普通門
    // 數值都是可調參數，如果之後有實測資料想校正，改這六個常數就好，不用動下面的邏輯。
    static final double FIRE_SPREAD_PROB_FIREDOOR   = 0.05; // 火源穿越「關好的」防火門
    static final double FIRE_SPREAD_PROB_NORMALDOOR = 0.35; // 火源穿越普通門
    static final double SMOKE_SPREAD_PROB_FIREDOOR   = 0.45; // 煙霧穿越「關好的」防火門
    static final double SMOKE_SPREAD_PROB_NORMALDOOR = 0.65; // 煙霧穿越普通門
    static final double SMELL_SPREAD_PROB_FIREDOOR   = 0.75; // 煙味穿越「關好的」防火門
    static final double SMELL_SPREAD_PROB_NORMALDOOR = 0.90; // 煙味穿越普通門

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

        // ─── 【新增】擴散速度跟tick掛勾：疊加一個「時間相位」倍率 ─────────────────
        // 原本火/煙的擴散速度只跟起火原因(currentCause)掛勾，整場模擬對同一種原因用同一組
        // 固定係數，等於是線性擴散。這裡再疊加一個會隨tick變化的倍率(temporalSpreadFactor)，
        // 讓不同起火原因呈現不同的「非線性」擴散節奏：電線走火先快後慢(初期電弧/短路瞬間延燒，
        // 隨後沒有持續助燃就趨緩)、化學起火先慢後快(醞釀到臨界濃度才閃燃、之後急遽加速)、
        // 縱火因為助燃劑一開始就維持高峰、燒完後緩慢回落，一般意外則不特別調整(維持原本倍率)。
        double temporalFactor = temporalSpreadFactor(currentCause, tick);
        smokeMultiplier *= temporalFactor;

        // ─── 【新增】B2情境：決策支援 + 備援控制 ─────────────────────
        // 系統除了給人建議之外，偵測到附近有危險時也會主動操作環境：
        //   1) 自動關閉「本來沒關好」的防火門，讓它恢復阻絕火勢延燒的能力
        //   2) 局部啟動排煙/抑制手段，讓整體煙霧增長率打折扣(模擬機械排煙/灑水)
        // 這一段跟人員是否服從建議完全無關，即使沒人聽從系統建議，環境本身也會因為
        // 備援控制而比純「決策支援」情境更安全，藉此跟B1(只有建議)做出差異化。
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
                            activeControlActionCount++;
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

                                        // 【新增】煙霧要進入「門」這一格，先看這一步是否成功穿越門縫；
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

        // ─── 【新增】煙味(smell)擴散：無害，只用來觸發People的「察覺」判定 ───────────
        // 只要有煙霧或火源的格子都會持續放出煙味；擴散範圍/衰減跟煙霧用同一套邏輯，
        // 差別在穿越「門」這一步的機率遠高於煙霧(見類別頂端SMELL_SPREAD_PROB_*常數)，
        // 模擬「看不到煙，但聞得到味道」這種比視覺線索更早出現、也更難完全被門封死的訊號。
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

                    // 【新增】煙味也跟煙霧一樣，會透過梯間往上/下樓層傳一點，
                    // 邏輯與煙霧完全比照(同樣用findSmokeUpTarget/findSmokeDownTarget
                    // 沿梯間往上/下找第一個沒有濃煙(<0.7)擋住的樓層當目標)，
                    // 只差在傳遞量比照煙霧的樓梯間擴散幅度(0.2/0.1)，不是同層擴散的幅度。
                    if (source instanceof Stage) {
                        Stage s = (Stage) source;
                        Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                        if (upT != null) nextSmell[upT.z][upT.y][upT.x] += (0.2 * smokeMultiplier);
                        Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                        if (downT != null) nextSmell[downT.z][downT.y][downT.x] += (0.1 * smokeMultiplier);
                    }
                }
            }
        }

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].smell = Math.min(1.0, nextSmell[z][y][x]);

        // 【修改】原本用「tick % fireSpreadTick == 0」這種固定週期觸發火勢延燒，
        // 等同於火源擴散速度只跟起火原因掛勾、且是線性(每隔固定tick數觸發一次)。
        // 改成機率制：期望頻率仍然是 1/fireSpreadTick(維持原本各起火原因的基準節奏)，
        // 但額外乘上跟tick掛勾的temporalFactor，讓觸發機率隨時間非線性起伏
        // (先快後慢 / 先慢後快 / 先高後緩降，依起火原因而不同)。
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

                        // 【修改】防火門不再是「關好=100%擋住」，而是低機率被竄燒過去(密封失敗/門縫走火)；
                        // 普通門也改成機率制，機率比防火門高，兩者數值都定義在類別頂端
                        if (next instanceof FireDoor && !((FireDoor) next).isOpen) {
                            if (random.nextDouble() >= FIRE_SPREAD_PROB_FIREDOOR) continue; // 這次沒竄過去，門仍維持阻絕效果
                        } else if (next instanceof Door) {
                            Door d = (Door) next;
                            if (random.nextDouble() < 0.3) d.blocked = true;
                            if (random.nextDouble() >= FIRE_SPREAD_PROB_NORMALDOOR) continue; // 這次火勢沒能穿過普通門
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
        // 【手稿修改建議7】firstDecisionTick(首次正確決策時間)、reportedTrappedTick(系統獲知受困位置平均時間)
        // 已依使用者判斷「有疑慮、應該先刪除」移除，不再進入統計報表。People內部仍會記錄/印出除錯log，
        // 只是不再彙整成KPI。
        int[] revokeToRerouteTicks;   // 建議被撤回後到拿到下一筆建議所花的tick，-1代表本人從未發生撤回
        // 【新增】察覺時間：對照 decision-window.js 的 firstCueAt。原本這裡還有 firstMovementTick，
        // 依使用者判斷「模擬不該假設任何無法客觀量化的互動延遲」已移除。
        int[] firstCueTick;      // 第一次察覺(聞到煙味/看到煙火/系統示警)的tick，-1代表整場都沒察覺

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
            revokeToRerouteTicks = new int[n];
            firstCueTick = new int[n];
            Arrays.fill(outcomes, Outcome.TRAPPED); // 預設：模擬結束時還沒死也還沒逃出去
            Arrays.fill(revokeToRerouteTicks, -1);
            Arrays.fill(firstCueTick, -1);
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
        result.revokeToRerouteTicks[idx] = (p.revokeToRerouteTicks != null) ? p.revokeToRerouteTicks : -1;
        result.firstCueTick[idx] = (p.firstCueAt != null) ? p.firstCueAt : -1;
    }

    // ─── 【新增】把建築的靜態結構(牆/門/防火門/出口/樓梯間)寫入SessionExporter ───
    // Floor 不記錄(數量最多，且對前端呈現意義不大)，藉此節省JSON檔案大小。
    private static void recordStaticMap(Space sp) {
        for (int z = 0; z < sp.height; z++) {
            for (int y = 0; y < sp.rows; y++) {
                for (int x = 0; x < sp.cols; x++) {
                    Obj o = sp.building[z][y][x];
                    String type;
                    boolean extraFlag = false;
                    if (o instanceof FireDoor) {
                        type = "FIRE_DOOR";
                        extraFlag = ((FireDoor) o).isOpen; // true代表沒關好，防護效果退化成普通門
                    } else if (o instanceof Door) {
                        type = "DOOR";
                        extraFlag = ((Door) o).blocked;
                    } else if (o instanceof Exit) {
                        type = "EXIT";
                    } else if (o instanceof Wall) {
                        type = "WALL";
                    } else if (o instanceof Stage) {
                        type = "STAGE";
                    } else {
                        continue; // Floor 或其他不記錄
                    }
                    sessionExporter.addStaticElement(z, y, x, type, extraFlag);
                }
            }
        }
    }

    // 將 run 修改為回傳每個人的生存時間陣列，並且準確記錄他們的真實結局與暴露統計
    static SimResult run(SimMode mode, int totalPeople) {
        boolean useSmart = (mode != SimMode.DEFAULT); // SMART與HYBRID都使用SmartEscape(決策支援)
        activeControlEnabled = (mode == SimMode.HYBRID); // 只有HYBRID額外啟用備援控制
        activeControlActionCount = 0;
        asetTick = null; // 每場模擬重新歸零，ASET是「本場」所有可停留格子第一次全部陷入危險的tick
        systemAwarenessTick = null; // 【新增】每場模擬重新歸零，系統示警時間點只在本場有效

        int survive = 0;
        SimResult result = new SimResult(totalPeople);
        stageAssignCount = 0; // 【新增】每場模擬重新歸零，避免跟前一場(甚至前一次Default/Smart)累加混在一起
        printMap();
        
        // 【修改】原本用 while(!allFloorsOnFire()) 檢查放在迴圈最前面，但起火點在呼叫run()之前
        // 就已經被initFireSource()點燃，對「只有1層樓」的建築來說，第一次檢查時allFloorsOnFire()
        // 就已經是true(唯一那層本來就有火)，導致迴圈本體一次都不會執行：tick不會前進、
        // spreadEnvironment()不會被呼叫、所有人連移動的機會都沒有，直接被判定100%受困、
        // 但煙霧/CO暴露卻是0%，資料完全失真。
        // 改成do-while，保證迴圈本體至少執行一次tick，讓煙霧/火勢真的擴散、人員真的有機會行動。
        // 終止條件單純只看allFloorsOnFire()：因為沒有一扇門是100%防火(FIRE_SPREAD_PROB_FIREDOOR/
        // FIRE_SPREAD_PROB_NORMALDOOR都是機率制、永遠>0)，火勢遲早會燒滿建築內所有可停留格子，
        // 迴圈必定終止。allFloorsOnFire()現在要求「每一格Floor/Stage都著火」，不是「每層樓1格」。
        do {
            tick++;
            spreadEnvironment();

            // 【修改】ASET改用「所有可停留格子的煙霧濃度都>0.7，或本身就是火源」定義：
            // 每tick檢查一次，只要建築裡還有任何一格Floor/Stage沒陷入危險，asetTick維持null；
            // 第一次全部格子都陷入危險時，記下這個tick作為本場的ASET上限。
            if (asetTick == null && allOccupiableCellsUntenable()) {
                asetTick = tick;
            }

            for (Detector d : detectors) {
                d.update(space.building, random);
            }

            // 【新增】察覺模型：系統(感測器網路)第一次偵測到危險或損毀的tick，
            // 之後SmartEscape()的stillUnaware()會用它模擬「手機收到緊急通知」這個察覺管道。
            if (systemAwarenessTick == null) {
                for (Detector d : detectors) {
                    if (d.danger || d.broken) { systemAwarenessTick = tick; break; }
                }
            }

            List<People> removed = new ArrayList<>();
            for (People p : peopleList) {
                if (useSmart) p.SmartEscape(tick);
                else          p.escape(tick);

                if (p.isDead) {
                    recordPersonSnapshot(result, p, tick, Outcome.DEAD);
                    sessionExporter.logEvent(tick, "DEATH", p.id,
                        "人員死亡 (累積CO: " + String.format("%.3f", p.accumulatedCO) + ")");
                    removed.add(p); 
                }
                else if (p.isEscaped) { 
                    recordPersonSnapshot(result, p, tick, Outcome.ESCAPED); // 記錄「真正」逃脫的那一刻，而不是模擬結束時的tick
                    sessionExporter.logEvent(tick, "ESCAPE", p.id, "人員成功逃生");
                    survive++; 
                    removed.add(p); 
                }
            }

            // 【新增】記錄本tick快照(在removeAll之前呼叫，讓這個tick剛死亡/逃脫的人員
            // 也能以最終狀態被記錄進這一格timeline，而不是直接消失不留下最後一筆資料)
            sessionExporter.recordTick(tick, space, peopleList, detectors);

            peopleList.removeAll(removed);
            printMap();
        } while (!allFloorsOnFire());
        
        // 迴圈跑完後，還留在 peopleList 裡的人代表模擬結束時他們既沒逃出去也沒死(TRAPPED)
        // 用最終tick填時間只是為了讓 times[] 有個數字，但 outcomes[] 才是判斷用的依據
        for (People p : peopleList) {
            recordPersonSnapshot(result, p, tick, Outcome.TRAPPED);
            sessionExporter.logEvent(tick, "TRAPPED", p.id, "模擬結束時仍受困，未逃出也未死亡");
        }

        // 統計本次模擬「進入高煙區」與「CO暴露達臨界值」的人數比例，以及新增的各項KPI
        int highSmokeCount = 0, criticalCOCount = 0, wrongRouteCount = 0;
        int rerouteAttemptCount = 0, rerouteSuccessCount = 0;
        int vulnerableTotal = 0, vulnerableIdentifiedCount = 0;
        int deathCount = 0, trappedCount = 0; // 【手稿修改建議1】三分類：逃生/死亡/受困
        long escapeTickSum = 0, escapeTickN = 0;
        long revokeToRerouteSum = 0, revokeToRerouteN = 0;
        // 察覺時間 / 以察覺為基準的RSET(僅供對照)。原本這裡還有「準備延遲/移動時間」，
        // 依使用者判斷「模擬不該假設任何無法客觀量化的互動延遲」已移除。
        long awarenessSum = 0, awarenessN = 0;
        long cueRsetSum = 0, cueRsetN = 0;

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
            } else if (result.outcomes[i] == Outcome.DEAD) {
                deathCount++;
            } else {
                trappedCount++;
            }
            if (result.revokeToRerouteTicks[i] >= 0) {
                revokeToRerouteSum += result.revokeToRerouteTicks[i];
                revokeToRerouteN++;
            }
            if (result.firstCueTick[i] >= 0) {
                awarenessSum += result.firstCueTick[i];
                awarenessN++;
                if (result.outcomes[i] == Outcome.ESCAPED) {
                    cueRsetSum += (result.times[i] - result.firstCueTick[i]);
                    cueRsetN++;
                }
            }
        }

        double wrongRouteRate    = (double) wrongRouteCount / totalPeople;
        double deathRate         = (double) deathCount / totalPeople;
        double trappedRate       = (double) trappedCount / totalPeople;
        double rerouteSuccessRate = (rerouteAttemptCount > 0) ? (double) rerouteSuccessCount / rerouteAttemptCount : -1; // -1代表本場沒有發生改道
        double vulnerableIdRate  = (vulnerableTotal > 0) ? (double) vulnerableIdentifiedCount / vulnerableTotal : -1;    // -1代表本場沒有需優先協助對象
        double avgEscapeTick     = (escapeTickN > 0) ? (double) escapeTickSum / escapeTickN : -1;
        double avgRevokeToReroute = (revokeToRerouteN > 0) ? (double) revokeToRerouteSum / revokeToRerouteN : -1;
        double avgAwareness      = (awarenessN > 0) ? (double) awarenessSum / awarenessN : -1;
        double avgCueRset        = (cueRsetN > 0) ? (double) cueRsetSum / cueRsetN : -1;

        // ASET−RSET安全餘裕：兩者統一以點火(tick=0)為基準，只有本場「疏散路徑真的全滅過」
        // 且「有人成功逃脫」時才算安全餘裕，避免湊出沒有意義的數字，也避免不同情境平均察覺時間
        // 不同所造成的失真(見People類別察覺時間追蹤處的說明)
        Double safetyMargin = (asetTick != null && avgEscapeTick >= 0) ? (asetTick - avgEscapeTick) : null;

        System.out.println("\n====== 模擬結束內部數據 ======");
        ModeStats stats = statsByMode.get(mode);
        stats.totalSurviveP  += (double) survive / totalPeople;
        stats.totalDeathP    += deathRate;
        stats.totalTrappedP  += trappedRate;
        stats.highSmokeP     += (double) highSmokeCount / totalPeople;
        stats.criticalCOP    += (double) criticalCOCount / totalPeople;
        stats.wrongRouteP    += wrongRouteRate;
        if (rerouteSuccessRate >= 0)  { stats.rerouteSuccessSum += rerouteSuccessRate; stats.rerouteIterCount++; }
        if (vulnerableIdRate  >= 0)   { stats.vulnerableIdSum   += vulnerableIdRate;   stats.vulnerableIterCount++; }
        if (avgEscapeTick     >= 0)   { stats.avgEscapeTickSum  += avgEscapeTick;      stats.escapeTickIterCount++; }
        if (avgRevokeToReroute >= 0)  { stats.revokeToRerouteSum += avgRevokeToReroute; stats.revokeToRerouteIterCount++; }
        if (asetTick != null)         { stats.asetSum += asetTick; stats.asetIterCount++; }
        if (safetyMargin != null)     { stats.safetyMarginSum += safetyMargin; stats.safetyMarginIterCount++; }
        if (avgAwareness      >= 0)   { stats.avgAwarenessSum += avgAwareness;   stats.awarenessIterCount++; }
        if (avgCueRset        >= 0)   { stats.avgCueRsetSum += avgCueRset;       stats.cueRsetIterCount++; }
        stats.activeControlActionSum += activeControlActionCount;

        System.err.println("Mode: "+mode+", total: "+totalPeople+", survive: "+survive);

        // 【新增】匯出本場模擬的 Session JSON(靜態地圖/逐tick快照/事件日誌)
        sessionExporter.exportToFile(sessionJsonFilename);

        return result;
    }

    // 【重構，手稿第8點】work() 現在跑「多個建築類型 × 每個建築多個火源/人物場景」：
    //   - data：每個元素代表一種建築類型的樓高/樓寬/樓長範圍(Data.H / Data.R / Data.C)，
    //           data.length 即為要抽樣幾種不同的建築幾何(樓層/長/寬)
    //   - count：同一棟建築要再抽幾組不同的起火點+人物配置來跑(即原本的scenariosPerBuilding)
    // 每跑完一棟建築的所有場景，先印出「這個建築類型」的小結報表，最後再印出跨所有建築類型的總表。
    public void work(Data[] data, int count) {
        TotalPeopleSum = 0;

        double smartWrongDecisionCount = 0;  // 【全域】SMART比DEFAULT更差的人數（決策支援情境）
        double hybridWrongDecisionCount = 0; // 【全域】HYBRID比DEFAULT更差的人數（決策支援+備援控制情境）

        Map<SimMode, ModeStats> globalStats = new LinkedHashMap<>();
        for (SimMode m : SimMode.values()) globalStats.put(m, new ModeStats());
        int globalScenarioCount = 0;

        for (int bt = 0; bt < data.length; bt++) {
            long buildingStartNano = System.nanoTime();
            Random buildingRand = new Random();
            Range H = data[bt].H, R = data[bt].R, C = data[bt].C;
            int height = buildingRand.nextInt(H.max - H.min) + H.min;
            int rows   = buildingRand.nextInt(R.max - R.min) + R.min;
            int cols   = buildingRand.nextInt(C.max - C.min) + C.min;

            Obj[][][] baseBuilding = generateMap(height, rows, cols);
            Space tempSpace = new Space(baseBuilding);
            int maxArea = (rows - 2) * (cols - 2);
            if (maxArea < 1) maxArea = 1;

            // 每個建築類型自己的統計容器：先歸零，這棟樓的所有場景都算完再印小結、再併入全域總表
            for (SimMode m : SimMode.values()) statsByMode.put(m, new ModeStats());
            double buildingSmartWrong = 0, buildingHybridWrong = 0;
            long buildingPeopleSum = 0;

            System.err.println("\n################################################");
            System.err.printf("建築類型 #%d ：%d 層 × %d × %d\n", bt + 1, height, rows, cols);
            System.err.println("################################################");

            for (int sc = 0; sc < count; sc++) {
                System.err.println("-- 場景 " + (sc + 1) + "/" + count + " --");
                Random rand = new Random();
                FireCause[] causes = FireCause.values();
                currentCause = causes[rand.nextInt(causes.length)];
                System.err.println("起火原因: " + currentCause);

                // 【手稿第8點】同一棟建築(baseBuilding幾何不變)，每個場景各自重抽一個新的火源位置
                int fireZ = rand.nextInt(tempSpace.height);
                int fireY = rand.nextInt(tempSpace.rows);
                int fireX = rand.nextInt(tempSpace.cols);
                while (!(baseBuilding[fireZ][fireY][fireX] instanceof Floor)) {
                    fireY = rand.nextInt(tempSpace.rows);
                    fireX = rand.nextInt(tempSpace.cols);
                }

                // 【手稿第8點】以及各自重抽一組新的人物資訊(人數與位置)
                int peopleCount = Math.max(((rand.nextInt(maxArea) + 1) / 4), 1);
                TotalPeopleSum += peopleCount;
                buildingPeopleSum += peopleCount;

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

                // 取得每個個體在三種情境下的模擬結果（時間 + 真實結局）：
                //   DEFAULT - 無智慧系統(基準線)
                //   SMART   - 只有決策支援(B1)
                //   HYBRID  - 決策支援 + 備援控制(B2)
                SimResult defaultResult = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, SimMode.DEFAULT, "defaultResult.txt");
                SimResult smartResult   = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, SimMode.SMART,   "smartResult.txt");
                SimResult hybridResult  = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, SimMode.HYBRID,  "hybridResult.txt");

                // 統計「系統做錯決定」的人數：定義為「結果比原本(DEFAULT)更差」
                //   - 原本(Default)活著逃出去，但系統情境卻讓他死掉/一直受困 → 明確的錯誤
                // 注意：不能直接比較 times[i] 大小，因為 times[i] 只在「死亡/逃脫當下」才有意義，
                // 而且各情境是各自獨立結束的模擬，兩邊的 tick 基準點不一樣，
                // 直接比大小會把「系統救活的人」誤判成「系統做錯決定」。
                for (int i = 0; i < peopleCount; i++) {
                    boolean defaultSurvived = defaultResult.outcomes[i] == Outcome.ESCAPED;
                    boolean smartSurvived   = smartResult.outcomes[i] == Outcome.ESCAPED;
                    boolean hybridSurvived  = hybridResult.outcomes[i] == Outcome.ESCAPED;

                    if (defaultSurvived && !smartSurvived)  { smartWrongDecisionCount++;  buildingSmartWrong++; }
                    if (defaultSurvived && !hybridSurvived) { hybridWrongDecisionCount++; buildingHybridWrong++; }
                }

                globalScenarioCount++;
            }

            // 印出「這個建築類型」的小結報表(只彙整這一棟樓、多個火源/人員場景的結果)
            printReport("建築類型 #" + (bt + 1) + " (" + height + "層 × " + rows + "×" + cols + ") 小結",
                statsByMode, count, buildingSmartWrong, buildingHybridWrong, buildingPeopleSum);

            // 【新增】時間複雜度量測：這個建築類型從開始到結束總共花了多少毫秒，
            // 除以(count場景數 × 建築體積(height*rows*cols))，得到「每單位(場景數×體積)平均花費的毫秒數」
            long buildingEndNano = System.nanoTime();
            double buildingElapsedMs = (buildingEndNano - buildingStartNano) / 1_000_000.0;
            long buildingVolume = (long) height * rows * cols;
            double msPerUnit = buildingElapsedMs / (count * buildingVolume);
            System.err.printf("執行時間: %.3f ms，體積: %d，時間複雜度量測(ms ÷ (count×體積)) : %.10f ms/unit\n",
                buildingElapsedMs, buildingVolume, msPerUnit);

            // 把這棟樓的統計併入跨建築類型的總表
            for (SimMode m : SimMode.values()) mergeModeStats(globalStats.get(m), statsByMode.get(m));
        }

        // 印出跨所有建築類型的總表
        printReport("跨建築類型總表", globalStats, globalScenarioCount, smartWrongDecisionCount, hybridWrongDecisionCount, TotalPeopleSum);
    }

    // 【重構】把原本寫死在work()裡的最終報表列印邏輯抽成獨立方法，這樣「單一建築小結」跟
    // 「跨建築總表」可以共用同一套輸出格式，只差在傳進來的統計容器跟分母(場景數)不同。
    static void printReport(String title, Map<SimMode, ModeStats> statsMap, int scenarioCount,
                             double smartWrongDecisionCount, double hybridWrongDecisionCount, long peopleSum) {
        double smartFinalAccuracy  = (peopleSum > 0) ? (smartWrongDecisionCount  / peopleSum) : 0.0;
        double hybridFinalAccuracy = (peopleSum > 0) ? (hybridWrongDecisionCount / peopleSum) : 0.0;

        String[] labels = {"初始模擬(Default)", "決策支援(Smart)  ", "決策支援+備援控制(Hybrid)"};
        SimMode[] modes = {SimMode.DEFAULT, SimMode.SMART, SimMode.HYBRID};

        System.err.println("\n==============================================");
        System.err.println(title + "（場景數: " + scenarioCount + "）");
        System.err.println("==============================================");
        System.err.println("※ 逃生率/死亡率/受困率/進入高煙區比例/臨界CO暴露比例/誤入危險路線比例：");
        System.err.println("   採「每場景等權重平均」計算（不論該場景人數多寡，每場貢獻相同權重）。");
        System.err.println("※ 系統嚴重失誤機率：採「總人數加權平均」計算（人數越多的場景影響力越大）。");
        System.err.println("※ 兩者分母定義不同，請勿直接跨欄位比較數值大小。");
        System.err.println("==============================================");
        // 【手稿修改建議1】三分類：逃生/死亡/受困，每個場次各自算比例後再平均，避免倖存者偏差
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 逃生率 / 死亡率 / 受困率 [場次平權] : %.2f%% / %.2f%% / %.2f%%\n", labels[i],
                (scenarioCount > 0 ? st.totalSurviveP / scenarioCount : 0) * 100,
                (scenarioCount > 0 ? st.totalDeathP / scenarioCount : 0) * 100,
                (scenarioCount > 0 ? st.totalTrappedP / scenarioCount : 0) * 100);
        }
        System.err.printf("系統嚴重失誤機率 (Smart mistake Rate) [人數加權]  : %.2f%%\n", smartFinalAccuracy * 100);
        System.err.printf("系統嚴重失誤機率 (Hybrid mistake Rate) [人數加權] : %.2f%%\n", hybridFinalAccuracy * 100);
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 進入高煙區比例 [場次平權] : %.2f%%\n", labels[i], (scenarioCount > 0 ? st.highSmokeP / scenarioCount : 0) * 100);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 臨界CO暴露比例 [場次平權] : %.2f%%\n", labels[i], (scenarioCount > 0 ? st.criticalCOP / scenarioCount : 0) * 100);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            System.err.printf("%s 誤入危險路線比例 [場次平權] : %.2f%%\n", labels[i], (scenarioCount > 0 ? st.wrongRouteP / scenarioCount : 0) * 100);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 改道成功率", st.rerouteSuccessSum, st.rerouteIterCount, true);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 弱勢對象辨識率", st.vulnerableIdSum, st.vulnerableIterCount, true);
        }
        System.err.println("----------------------------------------------");
        // 【手稿修改建議2】RSET僅計入成功逃生者，無法代表整體逃生表現，只能看「有逃出去的人平均花多久」
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 平均逃脫所需時間(RSET，僅計入成功逃生者，無法代表整體逃生表現)",
                st.avgEscapeTickSum, st.escapeTickIterCount, false);
        }
        System.err.println("----------------------------------------------");
        // 只有SMART/HYBRID才有「建議」可撤回，DEFAULT沒有智慧系統可比較
        for (int i = 1; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 建議撤回→改道完成延遲", st.revokeToRerouteSum, st.revokeToRerouteIterCount, false);
        }
        System.err.println("----------------------------------------------");
        // 【修改】ASET改為「所有可停留格子(Floor/Stage)煙霧濃度都>0.7，或本身就是火源」，
        // 是建築/環境本身的性質，與人物位置無關
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 可用安全逃生時間(ASET，所有可停留格子煙霧>0.7或起火)", st.asetSum, st.asetIterCount, false);
        }
        System.err.println("----------------------------------------------");
        // 【手稿修改建議4】安全餘裕 = ASET − RSET，兩者皆以點火(tick=0)為基準，避免不同情境平均察覺
        // 時間不同而讓安全餘裕失真(對照羅老師信中第3點)
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 安全餘裕(ASET−RSET，皆以點火為基準)", st.safetyMarginSum, st.safetyMarginIterCount, false);
        }
        System.err.println("----------------------------------------------");
        // 察覺時間、以及以察覺為基準的RSET(對照 decision-window.js 的 DecisionWindowTracker)，
        // 純粹是診斷用途，不回饋進上面的安全餘裕計算。原本這裡還有「準備延遲/移動時間」兩項，
        // 依使用者判斷「模擬不該假設任何無法被客觀量化的互動延遲」已移除——
        // 恐慌、找小孩、等同伴等行為確實會造成延遲，但延遲量沒有客觀依據可以精確拆解估計。
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " 平均察覺時間(從點火起算)", st.avgAwarenessSum, st.awarenessIterCount, false);
        }
        System.err.println("----------------------------------------------");
        for (int i = 0; i < modes.length; i++) {
            ModeStats st = statsMap.get(modes[i]);
            printAvgOrNA(labels[i] + " RSET(以察覺為基準，僅供對照，不用於安全餘裕計算)", st.avgCueRsetSum, st.cueRsetIterCount, false);
        }
        System.err.println("----------------------------------------------");
        System.err.printf("決策支援+備援控制(Hybrid) 平均每場主動控制動作次數(關門/排煙) : %.2f 次\n",
            scenarioCount > 0 ? statsMap.get(SimMode.HYBRID).activeControlActionSum / scenarioCount : 0.0);
        // 【手稿修改建議6】決策延遲常數C：目前沒有明確且主要的客觀因素可以量化，先設為常數並註記，
        // 不納入上面任何一項平均時間的計算，需要時再手動把C加回安全餘裕去解讀
        System.err.println("決策延遲常數 C = 0 秒（因為多次隨機採樣模擬中，每個人的決策延遲無法被客觀因素精確的計算。故定義先設為常數，未納入上列任何平均時間計算）");
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
                                  SimMode mode, String outputFile) {
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

        // 【新增】建立本場模擬的 SessionExporter，並記錄靜態地圖(牆/門/防火門/出口/樓梯間)，
        // JSON檔名依outputFile(defaultResult.txt/smartResult.txt/hybridResult.txt)對應產生，
        // 例如 defaultResult.txt -> defaultResult_session.json
        String scenarioName = "Building_" + space.height + "x" + space.rows + "x" + space.cols
            + "_Fire(" + fireZ + "," + fireY + "," + fireX + ")_" + mode;
        sessionExporter = new SessionExporter(scenarioName, space.height, space.rows, space.cols);
        recordStaticMap(space);
        sessionJsonFilename = outputFile.endsWith(".txt")
            ? outputFile.substring(0, outputFile.length() - 4) + "_session.json"
            : outputFile + "_session.json";

        initFireSource(fireZ, fireY, fireX);

        initDetectors();

        PersonProfile[] profiles = PersonProfile.values();
        for (int i = 0; i < peopleInit.length; i++) {
            int z = peopleInit[i][0], y = peopleInit[i][1], x = peopleInit[i][2];
            PersonProfile randomProfile = profiles[random.nextInt(profiles.length)];
            peopleList.add(new People(i + 1, z, y, x, space, randomProfile));
        }
        assignCompanions(peopleList);
        
        return run(mode, peopleInit.length);
    }
}

class SessionExporter {

    // ─── 內部資料結構：事件記錄 ─────────────────────────────────
    public static class EventLog {
        public int tick;
        public String eventType; // "AWARE", "ADVICE_ISSUED", "ADVICE_REVOKED", "REROUTE", "TRAPPED", "ESCAPE", "DEATH"
        public int personId;
        public String description;

        public EventLog(int tick, String eventType, int personId, String description) {
            this.tick = tick;
            this.eventType = eventType;
            this.personId = personId;
            this.description = description;
        }

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"tick\": " + tick + ",\n" +
                   indent + "  \"eventType\": \"" + eventType + "\",\n" +
                   indent + "  \"personId\": " + personId + ",\n" +
                   indent + "  \"description\": \"" + escapeJson(description) + "\"\n" +
                   indent + "}";
        }
    }

    // ─── 內部資料結構：每 Tick 的快照 ───────────────────────────
    public static class TickSnapshot {
        public int tick;
        public List<PersonState> peopleStates = new ArrayList<>();
        public List<DetectorState> detectorStates = new ArrayList<>();
        public List<GridState> dynamicGrids = new ArrayList<>(); // 僅記錄有火或有煙的格點，節省空間

        public TickSnapshot(int tick) {
            this.tick = tick;
        }

        public String toJson(String indent) {
            StringBuilder sb = new StringBuilder();
            sb.append(indent).append("{\n");
            sb.append(indent).append("  \"tick\": ").append(tick).append(",\n");

            // People States
            sb.append(indent).append("  \"people\": [\n");
            for (int i = 0; i < peopleStates.size(); i++) {
                sb.append(peopleStates.get(i).toJson(indent + "    "));
                if (i < peopleStates.size() - 1) sb.append(",\n");
            }
            sb.append("\n").append(indent).append("  ],\n");

            // Detector States
            sb.append(indent).append("  \"detectors\": [\n");
            for (int i = 0; i < detectorStates.size(); i++) {
                sb.append(detectorStates.get(i).toJson(indent + "    "));
                if (i < detectorStates.size() - 1) sb.append(",\n");
            }
            sb.append("\n").append(indent).append("  ],\n");

            // Dynamic Grids (Fire/Smoke)
            sb.append(indent).append("  \"environment\": [\n");
            for (int i = 0; i < dynamicGrids.size(); i++) {
                sb.append(dynamicGrids.get(i).toJson(indent + "    "));
                if (i < dynamicGrids.size() - 1) sb.append(",\n");
            }
            sb.append("\n").append(indent).append("  ]\n");

            sb.append(indent).append("}");
            return sb.toString();
        }
    }

    public static class PersonState {
        public int id;
        public int z, y, x;
        public String profile;
        public boolean isDead;
        public boolean isEscaped;
        public boolean aware;
        public double accumulatedCO;
        public double panicLevel;
        public boolean networkConnected;
        public String currentTask; // "ESCAPE", "WAIT_RESCUE", "WAIT_COMPANION", "GATHERING_CHILD", "IDLE"

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"id\": " + id + ",\n" +
                   indent + "  \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                   indent + "  \"profile\": \"" + profile + "\",\n" +
                   indent + "  \"isDead\": " + isDead + ",\n" +
                   indent + "  \"isEscaped\": " + isEscaped + ",\n" +
                   indent + "  \"aware\": " + aware + ",\n" +
                   indent + "  \"accumulatedCO\": " + String.format("%.3f", accumulatedCO) + ",\n" +
                   indent + "  \"panicLevel\": " + String.format("%.3f", panicLevel) + ",\n" +
                   indent + "  \"networkConnected\": " + networkConnected + ",\n" +
                   indent + "  \"currentTask\": \"" + currentTask + "\"\n" +
                   indent + "}";
        }
    }

    public static class DetectorState {
        public int z, y, x;
        public boolean broken;
        public boolean danger;

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                   indent + "  \"broken\": " + broken + ",\n" +
                   indent + "  \"danger\": " + danger + "\n" +
                   indent + "}";
        }
    }

    public static class GridState {
        public int z, y, x;
        public boolean fire;
        public double smoke;

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                   indent + "  \"fire\": " + fire + ",\n" +
                   indent + "  \"smoke\": " + String.format("%.3f", smoke) + "\n" +
                   indent + "}";
        }
    }

    // ─── Exporter 主體欄位 ─────────────────────────────────────
    private String scenarioName;
    private int height, rows, cols;
    private List<EventLog> events = new ArrayList<>();
    private List<TickSnapshot> ticks = new ArrayList<>();
    private List<String> staticElementsJson = new ArrayList<>();

    public SessionExporter(String scenarioName, int height, int rows, int cols) {
        this.scenarioName = scenarioName;
        this.height = height;
        this.rows = rows;
        this.cols = cols;
    }

    // ─── 記錄靜態地圖元素（初始化時呼叫一次） ───────────────────────
    public void addStaticElement(int z, int y, int x, String type, boolean extraFlag) {
        String json = "      {\n" +
                      "        \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                      "        \"type\": \"" + type + "\",\n" +
                      "        \"extraFlag\": " + extraFlag + "\n" +
                      "      }";
        staticElementsJson.add(json);
    }

    // ─── 記錄關鍵事件日誌（模擬中發生時即時呼叫） ───────────────────
    public void logEvent(int tick, String eventType, int personId, String description) {
        events.add(new EventLog(tick, eventType, personId, description));
    }

    // ─── 記錄每 Tick 狀態快照（每個 tick 結束時呼叫） ─────────────────
    public void recordTick(int currentTick, Space space, List<People> peopleList, List<Detector> detectorList) {
        TickSnapshot snapshot = new TickSnapshot(currentTick);

        // 1. 紀錄人員狀態
        for (People p : peopleList) {
            PersonState ps = new PersonState();
            ps.id = p.id;
            ps.z = p.z;
            ps.y = p.y;
            ps.x = p.x;
            ps.profile = p.profile.name();
            ps.isDead = p.isDead;
            ps.isEscaped = p.isEscaped;
            ps.aware = p.aware;
            ps.accumulatedCO = p.accumulatedCO;
            ps.panicLevel = p.panicLevel;
            ps.networkConnected = p.networkConnected;

            // 判斷當前主要行動任務
            if (p.isEscaped) {
                ps.currentTask = "ESCAPED";
            } else if (p.isDead) {
                ps.currentTask = "DEAD";
            } else if (p.profile == PersonProfile.IMPAIRED && p.waitingForRescue) {
                ps.currentTask = "WAIT_RESCUE";
            } else if (p.childGatherDelay > 0) {
                ps.currentTask = "GATHERING_CHILD";
            } else if (p.targetStage != null) {
                ps.currentTask = "MOVE_FLOOR";
            } else if (p.junctionTargetPos != null) {
                ps.currentTask = "ESCAPE_ROUTE";
            } else {
                ps.currentTask = "IDLE";
            }
            snapshot.peopleStates.add(ps);
        }

        // 2. 紀錄感測器網路
        for (Detector d : detectorList) {
            DetectorState ds = new DetectorState();
            ds.z = d.z;
            ds.y = d.y;
            ds.x = d.x;
            ds.broken = d.broken;
            ds.danger = d.danger;
            snapshot.detectorStates.add(ds);
        }

        // 3. 紀錄動態火煙環境（只記錄有煙霧 smoke > 0.01 或有起火的網格，優化檔案大小）
        Obj[][][] map = space.building;
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj obj = map[z][y][x];
                    if (obj.fire || obj.smoke > 0.01) {
                        GridState gs = new GridState();
                        gs.z = z;
                        gs.y = y;
                        gs.x = x;
                        gs.fire = obj.fire;
                        gs.smoke = obj.smoke;
                        snapshot.dynamicGrids.add(gs);
                    }
                }
            }
        }

        ticks.add(snapshot);
    }

    // ─── 匯出 JSON 檔案 ─────────────────────────────────────────
    public void exportToFile(String filename) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write("{\n");
            writer.write("  \"metadata\": {\n");
            writer.write("    \"scenarioName\": \"" + escapeJson(scenarioName) + "\",\n");
            writer.write("    \"dimensions\": {\n");
            writer.write("      \"height\": " + height + ",\n");
            writer.write("      \"rows\": " + rows + ",\n");
            writer.write("      \"cols\": " + cols + "\n");
            writer.write("    },\n");
            writer.write("    \"exportedAt\": \"" + java.time.Instant.now().toString() + "\"\n");
            writer.write("  },\n");

            // 靜態地圖元素
            writer.write("  \"staticMap\": [\n");
            for (int i = 0; i < staticElementsJson.size(); i++) {
                writer.write(staticElementsJson.get(i));
                if (i < staticElementsJson.size() - 1) writer.write(",\n");
            }
            writer.write("\n  ],\n");

            // 關鍵事件日誌
            writer.write("  \"eventLogs\": [\n");
            for (int i = 0; i < events.size(); i++) {
                writer.write(events.get(i).toJson("    "));
                if (i < events.size() - 1) writer.write(",\n");
            }
            writer.write("\n  ],\n");

            // 歷史模擬軌跡 (Time-series)
            writer.write("  \"timeline\": [\n");
            for (int i = 0; i < ticks.size(); i++) {
                writer.write(ticks.get(i).toJson("    "));
                if (i < ticks.size() - 1) writer.write(",\n");
            }
            writer.write("\n  ]\n");

            writer.write("}\n");
            System.err.println("[SUCCESS] Session JSON successfully exported to: " + filename);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to export session JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // JSON 字串逸出處理
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}