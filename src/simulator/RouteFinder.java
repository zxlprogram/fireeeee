package simulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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

    RouteFinder(Space space, SimulationContext context) {
        this.space = space;
        this.context = context;
    }

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
    // 沒有濃煙時的備援邏輯：即使出口不在視野(LOS)範圍內，
    // 只要現場沒有濃煙阻擋視線/判斷，人員仍應憑對建築的基本方向感
    // (例如逃生指示燈、樓層配置)嘗試朝出口方向移動，而不是直接亂走。
    // 因此這裡不受 computeVisibleCells() 的視野限制，走全域BFS找出口。
    // ────────────────────────────────────────────────────────
    int[] findNextStepTowardExit(int z, int y, int x) {
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

    // 把「蒐集目前已知火/危險格」抽成獨立函式
    static class HazardKnowledge {
        Set<Integer> knownFires;
        Set<Integer> knownHazards;
    }

    HazardKnowledge gatherKnownHazards(int z, int y, int x, PersonProfile profile) {
        HazardKnowledge hk = new HazardKnowledge();
        hk.knownHazards = new HashSet<>();
        hk.knownFires = new HashSet<>();

        // 【降低耦合】原本這裡是 for (Detector d : Simulator.detectors)，
        // 現在透過建構時注入的 context 取得，RouteFinder 不再直接認識 Simulator。
        for (Detector d : context.getDetectors()) {
            int k = GridKeys.bitKey(d.z, d.y, d.x);
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

            if (localObj.fire) {
                hk.knownFires.add(lKey); hk.knownHazards.add(lKey);
            }
        }

        if (space.building[z][y][x] instanceof Stage) {
            Stage st = (Stage) space.building[z][y][x];
            for (Stage nextSt : new Stage[]{st.upfloor, st.downfloor}) {
                if (nextSt != null) {
                    int stKey = GridKeys.bitKey(nextSt.z, nextSt.y, nextSt.x);
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
    List<int[]> computeSmartPath(int z, int y, int x, PersonProfile profile) {
        HazardKnowledge hk = gatherKnownHazards(z, y, x, profile);
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

    private static final double EXIT_STEP_WEIGHT = 0.0;
    private static final double DANGER_WEIGHT = 0.5;
    private static final double FIRE_EXTRA_WEIGHT = 5.0;

    private double dangerCost(int cz, int cy, int cx, Set<Integer> knownFires, Set<Integer> knownHazards) {
        double cost = 0.0;
        for (int fKey : knownFires) {
            int[] f = GridKeys.decodeKey(fKey);
            int dist = Math.abs(cz - f[0]) * 100 + Math.abs(cy - f[1]) + Math.abs(cx - f[2]);
            cost += FIRE_EXTRA_WEIGHT / (1.0 + dist);
        }
        for (int hKey : knownHazards) {
            if (knownFires.contains(hKey)) continue;
            int[] h = GridKeys.decodeKey(hKey);
            int dist = Math.abs(cz - h[0]) * 100 + Math.abs(cy - h[1]) + Math.abs(cx - h[2]);
            cost += DANGER_WEIGHT / (1.0 + dist);
        }
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
