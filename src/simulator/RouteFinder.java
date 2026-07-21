package simulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

// ═══════════════════════════════════════════════════════════════════════════
// RouteFinder — 逃生路徑規劃(BFS / Dijkstra / 連通性檢查)
//   每個 People 各自持有一個綁定同一張 Space 的 RouteFinder 實例。
//   全部是確定性演算法，不消耗任何 Random，所以搬動它們不會改變模擬的亂數序列/結果。
//
//   【降低耦合】原本 gatherKnownHazards() 會直接讀取 Simulator.detectors 這個
//   全域靜態欄位，形成「底層路徑規劃邏輯反過來依賴最上層模擬控制器」的反向耦合。
//   現在改成建構時注入 SimulationContext，透過 context.getDetectors() 取得偵測器
//   清單，RouteFinder 不再認得 Simulator 這個類別。
// ═══════════════════════════════════════════════════════════════════════════
class RouteFinder {
    private final Space space;
    private final SimulationContext context;

    // 新增：快取所有出口位置，賦予上帝視角避免全域 BFS
    private List<int[]> knownExits = null;

    RouteFinder(Space space, SimulationContext context) {
        this.space = space;
        this.context = context;
    }

    // 【P1-3 修正】根因6：Hybrid主動關閉防火門時，VisionSystem.isOpaque()會把關閉的門
    // 視為擋視線的障礙物，導致該區域瞬間從「可見」變成「不可見」，而原本gatherKnownHazards()
    // 對「這次查不到最新狀態」的格子沒有任何記憶，等於危險資訊直接消失、被重新當成
    // 未知(進而依現有邏輯被當成安全)。這裡在RouteFinder(一人一個實例，見上方類別註解)
    // 身上記錄每個格子「上一次被確認為危險/起火的tick」，當某格這個tick沒有偵測器覆蓋、
    // 也不在視野內時，優先沿用上一次已知的狀態，而不是預設安全；記憶超過
    // HAZARD_MEMORY_DECAY_TICKS個tick沒有更新就視為失效並清除，避免過度保守、
    // 永遠不敢靠近曾經危險過但實際上可能已經沒事的區域。
    private final Map<Integer, Integer> hazardLastSeenTick = new HashMap<>();
    private final Map<Integer, Integer> fireLastSeenTick = new HashMap<>();
    private static final int HAZARD_MEMORY_DECAY_TICKS = 60;

    // 確認障礙物邏輯，防火門可通行
    // 【校正清單§1】新增門/出口流量瓶頸判斷(DoorFlowModel)：本tick容量耗盡的
    // Door/Exit視為暫時不可通行，逼真人必須繞道或原地等待，近似現實中的排隊現象。
    boolean isPassable(Obj o) {
        if (o instanceof Wall) return false;
        if (o instanceof Door && ((Door) o).blocked) return false;
        if (o.fire) return false;
        if (!DoorFlowModel.hasCapacity(o)) return false;
        return true;
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

    // 初始化與獲取出口位置 (懶加載)
    private List<int[]> getKnownExits() {
        if (knownExits == null) {
            knownExits = new ArrayList<>();
            // 掃描整棟建築找出所有出口 (僅執行一次)
            for (int z = 0; z < space.building.length; z++) {
                for (int y = 0; y < space.building[z].length; y++) {
                    for (int x = 0; x < space.building[z][y].length; x++) {
                        if (space.building[z][y][x] instanceof Exit) {
                            knownExits.add(new int[]{z, y, x});
                        }
                    }
                }
            }
        }
        return knownExits;
    }

    // ────────────────────────────────────────────────────────
    // 【傳統邏輯】只在目前視野(LOS)範圍內找往出口的下一步(BFS)
    // ────────────────────────────────────────────────────────
    int[] findNextStepLosBFS(int z, int y, int x, PersonProfile profile) {
        Set<String> visibleKeys = VisionSystem.computeVisibleCells(space, z, y, x, profile);

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        Queue<int[]> queue = new LinkedList<>();
        Map<String, String> parentMap = new HashMap<>();
        Set<String> visited = new HashSet<>();

        String startKey = GridKeys.key(z, y, x);
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

                String nextKey = GridKeys.key(nz, ny, nx);
                if (visited.contains(nextKey)) continue;

                Obj nextObj = space.building[nz][ny][nx];
                if (!isPassable(nextObj)) continue;
                if (!visibleKeys.contains(nextKey)) continue;

                visited.add(nextKey);
                parentMap.put(nextKey, GridKeys.key(curr[0], curr[1], curr[2]));
                queue.add(new int[]{nz, ny, nx});
            }

            if (currObj instanceof Stage) {
                Stage s = (Stage) currObj;
                for (Stage next : new Stage[]{s.upfloor, s.downfloor}) {
                    if (next == null) continue;
                    if (next.fire) continue;
                    String nextKey = GridKeys.key(next.z, next.y, next.x);
                    if (visited.contains(nextKey)) continue;
                    visited.add(nextKey);
                    parentMap.put(nextKey, GridKeys.key(curr[0], curr[1], curr[2]));
                    queue.add(new int[]{next.z, next.y, next.x});
                }
            }
        }

        if (targetPos == null) return null;

        String currKey = GridKeys.key(targetPos[0], targetPos[1], targetPos[2]);
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
    // 備援邏輯：導航總是能看到出口，不再做 BFS 全域掃描。
    // 評估周圍相鄰格，採貪婪演算法，選擇與已知出口曼哈頓距離最短的下一步。
    // ────────────────────────────────────────────────────────
    int[] findNextStepTowardExit(int z, int y, int x) {
        List<int[]> exits = getKnownExits();
        if (exits.isEmpty()) return null;

        int[] bestStep = null;
        int minDistance = Integer.MAX_VALUE;

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        for (int i = 0; i < 4; i++) {
            int nz = z, ny = y + dy[i], nx = x + dx[i];

            if (!space.isValid(nz, ny, nx)) continue;
            Obj nextObj = space.building[nz][ny][nx];
            if (!isPassable(nextObj)) continue;

            int distToClosestExit = getMinDistanceToExits(nz, ny, nx, exits);
            if (distToClosestExit < minDistance) {
                minDistance = distToClosestExit;
                bestStep = new int[]{nz, ny, nx};
            }
        }

        Obj currentObj = space.building[z][y][x];
        if (currentObj instanceof Stage) {
            Stage s = (Stage) currentObj;
            for (Stage nextStage : new Stage[]{s.upfloor, s.downfloor}) {
                if (nextStage == null || nextStage.fire || !isPassable(nextStage)) continue;

                int distToClosestExit = getMinDistanceToExits(nextStage.z, nextStage.y, nextStage.x, exits);
                if (distToClosestExit < minDistance) {
                    minDistance = distToClosestExit;
                    bestStep = new int[]{nextStage.z, nextStage.y, nextStage.x};
                }
            }
        }

        return bestStep;
    }

    // 輔助方法：計算指定座標到所有已知出口中的最短曼哈頓距離
    private int getMinDistanceToExits(int z, int y, int x, List<int[]> exits) {
        int minDist = Integer.MAX_VALUE;
        for (int[] exit : exits) {
            int dist = Math.abs(z - exit[0]) * 100 + Math.abs(y - exit[1]) + Math.abs(x - exit[2]);
            if (dist < minDist) {
                minDist = dist;
            }
        }
        return minDist;
    }

    // 把「蒐集目前已知火/危險格」抽成獨立函式
    static class HazardKnowledge {
        Set<Integer> knownFires;
        Set<Integer> knownHazards;
    }

    HazardKnowledge gatherKnownHazards(int z, int y, int x, PersonProfile profile, int currentTick) {
        HazardKnowledge hk = new HazardKnowledge();
        hk.knownHazards = new HashSet<>();
        hk.knownFires = new HashSet<>();
        // 【P1-3】這個tick真正有「新鮮證據」(偵測器覆蓋，或在目前視野內)的格子，
        // 不論結果是危險還是安全，都算「觀測到了」，用來跟下面的記憶回退做區分：
        // 有新鮮證據的格子一律用新鮮證據，沒有新鮮證據的格子才考慮沿用記憶。
        Set<Integer> observedThisTick = new HashSet<>();

        // 【降低耦合】原本這裡是 for (Detector d : Simulator.detectors)，
        // 現在透過建構時注入的 context 取得，RouteFinder 不再直接認識 Simulator。
        for (Detector d : context.getDetectors()) {
            int k = GridKeys.bitKey(d.z, d.y, d.x);
            observedThisTick.add(k);
            if (d.broken) {
                hk.knownHazards.add(k); hk.knownFires.add(k);
            } else if (d.danger) {
                hk.knownHazards.add(k);
            }
        }

        Set<String> visibleCells = VisionSystem.computeVisibleCells(space, z, y, x, profile);
        for (String vKey : visibleCells) {
            String[] tokens = vKey.split(",");
            int vz = Integer.parseInt(tokens[0]);
            int vy = Integer.parseInt(tokens[1]);
            int vx = Integer.parseInt(tokens[2]);

            Obj localObj = space.building[vz][vy][vx];
            int lKey = GridKeys.bitKey(vz, vy, vx);
            observedThisTick.add(lKey);

            if (localObj.fire) {
                hk.knownFires.add(lKey); hk.knownHazards.add(lKey);
            } else if (localObj.smoke > 0.7) {
                // 【校正清單 追加項§1】視野內任何可見格子，只要煙濃度超標，
                // 無論是否已起火、無論是否有感測器覆蓋在該座標，都視為已知危險，
                // 沿用現有VisionSystem.computeVisibleCells()的視野範圍，不做額外能見度判斷。
                hk.knownHazards.add(lKey);
            }
        }

        if (space.building[z][y][x] instanceof Stage) {
            Stage st = (Stage) space.building[z][y][x];
            for (Stage nextSt : new Stage[]{st.upfloor, st.downfloor}) {
                if (nextSt != null) {
                    int stKey = GridKeys.bitKey(nextSt.z, nextSt.y, nextSt.x);
                    observedThisTick.add(stKey);
                    if (nextSt.fire) {
                        hk.knownFires.add(stKey); hk.knownHazards.add(stKey);
                    } else if (nextSt.smoke > 0.7) {
                        hk.knownHazards.add(stKey);
                    }
                }
            }
        }

        // 【P1-3】用這個tick的新鮮觀測結果更新記憶：危險/起火就記下tick，
        // 親眼確認安全的格子則清掉舊記憶(不要讓已經解除的危險繼續被當成有效記憶)。
        for (int k : observedThisTick) {
            if (hk.knownFires.contains(k)) {
                fireLastSeenTick.put(k, currentTick);
                hazardLastSeenTick.put(k, currentTick);
            } else if (hk.knownHazards.contains(k)) {
                hazardLastSeenTick.put(k, currentTick);
                fireLastSeenTick.remove(k);
            } else {
                hazardLastSeenTick.remove(k);
                fireLastSeenTick.remove(k);
            }
        }

        // 【P1-3】記憶回退：這個tick沒有新鮮證據(沒有偵測器覆蓋、也不在視野內)的格子，
        // 如果記憶還沒過期，沿用上一次已知的危險/起火狀態，而不是預設安全。
        // 典型情境：Hybrid關門後該區域瞬間從可見變不可見，這裡讓「上一次看到的危險」
        // 不會因為門一關就憑空消失。
        Iterator<Map.Entry<Integer, Integer>> it = hazardLastSeenTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> e = it.next();
            int k = e.getKey();
            if (observedThisTick.contains(k)) continue; // 已有新鮮證據，不需要記憶
            int lastSeen = e.getValue();
            if (currentTick - lastSeen > HAZARD_MEMORY_DECAY_TICKS) {
                it.remove();
                fireLastSeenTick.remove(k);
                continue;
            }
            hk.knownHazards.add(k);
            Integer fireLastSeen = fireLastSeenTick.get(k);
            if (fireLastSeen != null && currentTick - fireLastSeen <= HAZARD_MEMORY_DECAY_TICKS) {
                hk.knownFires.add(k);
            }
        }

        return hk;
    }

    // 連通性檢查：用目前已知的火/危險格，判斷「起點→目標格」是否仍存在一條
    // 不經過火/高危格的路徑。只需要知道「通不通」，用BFS即可，不需要算最短路，比重跑一次
    // Dijkstra便宜很多，適合每tick都要做的撤回判定。
    // 【校正清單§1】isPassable()現在也會檢查門/出口的流量瓶頸(DoorFlowModel)，
    // 代表本tick「容量剛好被別人用完」的門，這裡會被視為暫時不可達，可能讓
    // SmartEscapeStrategy誤判成連通性喪失而觸發撤回改道——這其實是瓶頸擁堵的
    // 合理副作用(系統發現這條路暫時塞住，先繞道)，但如果之後要更精確地區分
    // 「永久不可達(著火)」與「暫時擁堵(下tick可能就通了)」，可以在這裡另外
    // 加一個「只忽略門流量限制、只看結構性/火源阻絕」的版本。
    boolean isReachable(int fz, int fy, int fx, int tz, int ty, int tx,
                         Set<Integer> knownFires, Set<Integer> knownHazards) {
        int startKey = GridKeys.bitKey(fz, fy, fx);
        int goalKey = GridKeys.bitKey(tz, ty, tx);
        if (startKey == goalKey) return true;

        Set<Integer> visited = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        visited.add(startKey);
        queue.add(startKey);

        while (!queue.isEmpty()) {
            int currKey = queue.poll();
            if (currKey == goalKey) return true;

            int[] c = GridKeys.decodeKey(currKey);
            Obj currObj = space.building[c[0]][c[1]][c[2]];

            int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
            for (int i = 0; i < 4; i++) {
                int nz = c[0], ny = c[1] + dy[i], nx = c[2] + dx[i];
                if (!space.isValid(nz, ny, nx)) continue;
                Obj nextObj = space.building[nz][ny][nx];
                if (!isPassable(nextObj)) continue;
                int nextKey = GridKeys.bitKey(nz, ny, nx);
                if (knownHazards.contains(nextKey)) continue; // 不經過火/高危格
                if (visited.add(nextKey)) queue.add(nextKey);
            }

            if (currObj instanceof Stage) {
                Stage s = (Stage) currObj;
                for (Stage next : new Stage[]{s.upfloor, s.downfloor}) {
                    if (next == null || !isPassable(next)) continue;
                    int nextKey = GridKeys.bitKey(next.z, next.y, next.x);
                    if (knownHazards.contains(nextKey)) continue;
                    if (visited.add(nextKey)) queue.add(nextKey);
                }
            }
        }
        return false;
    }

    // 不再只回傳「下一步」，改成回傳從起點(含)到目標(通常是Exit，找不到則是
    // maximizeSurvivalTime()給的逃生方向)的完整路徑，讓呼叫端(planNextAdvice)可以沿著
    // 這條路徑找出「下一個決策點(樓梯間/分岔格/出口)」，把同樓層移動也包裝成正式任務。
    List<int[]> computeSmartPath(int z, int y, int x, PersonProfile profile, int currentTick) {
        HazardKnowledge hk = gatherKnownHazards(z, y, x, profile, currentTick);
        Set<Integer> knownHazards = hk.knownHazards;
        Set<Integer> knownFires = hk.knownFires;

        int startKey = GridKeys.bitKey(z, y, x);
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

            int[] c = GridKeys.decodeKey(currKey);
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

                int nextKey = GridKeys.bitKey(nz, ny, nx);
                // 【校正清單 追加項§2】已知危險格(煙濃度超標，含已起火格)硬性排除，
                // 視同不可通行，不再只是隨距離衰減的軟成本；若因此找不到路，
                // 外層會fallback到maximizeSurvivalTime()。
                if (knownHazards.contains(nextKey)) continue;
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

                    int nextKey = GridKeys.bitKey(next.z, next.y, next.x);
                    // 【校正清單 追加項§2】同上，樓梯間跨樓層移動一樣硬性排除已知危險格。
                    if (knownHazards.contains(nextKey)) continue;
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
            path.add(GridKeys.decodeKey(curr));
            while (parentMap.containsKey(curr)) {
                curr = parentMap.get(curr);
                path.add(GridKeys.decodeKey(curr));
            }
            java.util.Collections.reverse(path); // path.get(0) == 起點(z,y,x)
            return path;
        }

        int[] fallbackStep = maximizeSurvivalTime(z, y, x, knownFires, knownHazards);
        if (fallbackStep == null) return null;
        List<int[]> path = new ArrayList<>();
        path.add(new int[]{z, y, x});
        path.add(fallbackStep);
        return path;
    }

    // 【P0 修正】原本是 0.0，導致 Dijkstra 完全不考慮距離，只避開已知危險格，
    // 可能規劃出繞遠路甚至在安全區域內打轉的路徑（因為任何不踩雷的路徑成本都相同）。
    // 改成 1.0 讓每一步都有基礎移動成本，使演算法重新具備「最短安全路徑」的目標，
    // dangerCost() 仍作為疊加在距離成本之上的危險懲罰項，邏輯不變。
    private static final double EXIT_STEP_WEIGHT = 1.0;
    private static final double DANGER_WEIGHT = 0.5;
    private static final double FIRE_EXTRA_WEIGHT = 5.0;
    // 【P1-1 修正】根因3：偵測器覆蓋稀疏(每3格一個)+視野有限，導致大部分格子屬於
    // 「沒有偵測器覆蓋、也不在目前視野內」的未知格，而原本的邏輯把「未知」跟「已驗證
    // 安全」完全劃上等號，dangerCost()對這種格子一律回傳0。這裡加一個小的、非零的
    // 不確定性懲罰係數，只套用在「本身不是已知危險格，但緊鄰(上下左右相鄰1格)某個
    // 已知危險/火點格」的未知格上——這種格子最可能只是「還沒被偵測到」而非「真的安全」。
    // 數值刻意設得比DANGER_WEIGHT(0.5，確認危險)小很多，只是讓路徑規劃「同等距離下
    // 優先選已驗證安全的格子」的輕微偏好，不會讓完全沒有偵測器覆蓋的正常區域變得
    // 難以通行(那些區域通常離任何已知危險都有一段距離，不會觸發這個懲罰)。
    private static final double UNCERTAINTY_WEIGHT = 0.15;

    private double dangerCost(int cz, int cy, int cx, Set<Integer> knownFires, Set<Integer> knownHazards) {
        double cost = 0.0;
        boolean adjacentToKnownHazard = false;
        for (int fKey : knownFires) {
            int[] f = GridKeys.decodeKey(fKey);
            int dist = Math.abs(cz - f[0]) * 100 + Math.abs(cy - f[1]) + Math.abs(cx - f[2]);
            cost += FIRE_EXTRA_WEIGHT / (1.0 + dist);
        }
        for (int hKey : knownHazards) {
            int[] h = GridKeys.decodeKey(hKey);
            int dist = Math.abs(cz - h[0]) * 100 + Math.abs(cy - h[1]) + Math.abs(cx - h[2]);
            if (dist == 1) adjacentToKnownHazard = true;
            if (knownFires.contains(hKey)) continue;
            cost += DANGER_WEIGHT / (1.0 + dist);
        }
        // 呼叫端(computeSmartPath)已經把 knownHazards 裡的格子硬性排除，走到這裡的
        // (cz,cy,cx) 保證不是已知危險格本身，所以不需要另外檢查「自己是不是危險格」。
        if (adjacentToKnownHazard) cost += UNCERTAINTY_WEIGHT;
        return cost;
    }

    int[] maximizeSurvivalTime(int z, int y, int x, Set<Integer> knownFires, Set<Integer> knownHazards) {
        Set<Integer> threats = knownFires.isEmpty() ? knownHazards : knownFires;
        if (threats.isEmpty()) return null;

        int maxManhattanDist;
        int[] bestMove;

        int myMinDist = Integer.MAX_VALUE;
        for (int tKey : threats) {
            int[] t = GridKeys.decodeKey(tKey);
            int mDist = Math.abs(z - t[0]) * 100 + Math.abs(y - t[1]) + Math.abs(x - t[2]);
            if (mDist < myMinDist) myMinDist = mDist;
        }
        maxManhattanDist = myMinDist;
        bestMove = new int[]{z, y, x};

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        for (int i = 0; i < 4; i++) {
            int ny = y + dy[i], nx = x + dx[i];
            if (!space.isValid(z, ny, nx)) continue;

            Obj nextObj = space.building[z][ny][nx];
            if (!isPassable(nextObj)) continue;

            int minDistToThreat = Integer.MAX_VALUE;
            for (int tKey : threats) {
                int[] t = GridKeys.decodeKey(tKey);
                int mDist = Math.abs(z - t[0]) * 100 + Math.abs(ny - t[1]) + Math.abs(nx - t[2]);
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
                    int[] t = GridKeys.decodeKey(tKey);
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

    // 從一條完整路徑中找出「下一個決策點」在路徑中的index：
    // 沿路徑往前掃，遇到樓梯間(Stage)、出口(Exit)、或分岔格(可通行相鄰格數>2，代表這裡
    // 不是單純走廊直線)三者中最先出現的那一個，當作這次任務的目標格；
    // 如果整條路徑都沒遇到(距離很短、直接走到終點前都是直線走廊)，目標格就是路徑最後一格。
    int findDecisionPointIndex(List<int[]> path) {
        for (int i = 1; i < path.size(); i++) {
            int[] c = path.get(i);
            Obj o = space.building[c[0]][c[1]][c[2]];
            if (o instanceof Stage || o instanceof Exit || isJunctionCell(c[0], c[1], c[2])) {
                return i;
            }
        }
        return path.size() - 1;
    }
}