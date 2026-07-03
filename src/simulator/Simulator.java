package simulator;
//請至main函數調整模擬資訊，如果想要改參數請直接跟我說，除非你是屎山代碼終結者
import java.io.*;
import java.util.*;

class sim {
    public static void main(String[]args) {
        Simulator s = new Simulator();
        //輸入:模擬次數、樓高範圍、樓寬範圍、樓長範圍
        //輸出:平均存活時間、最終數據報告、過程文檔(會隨著模擬次數被覆蓋，只會看到最後一次模擬的文檔)
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
    boolean broken = false; // 是否被火燒毀
    boolean danger = false; // 是否偵測到濃煙

    public Detector(int z, int y, int x) {
        this.z = z; this.y = y; this.x = x;
    }

    void update(Obj[][][] map) {
        if (map[z][y][x].smoke > 0.7) danger = true;
        if (map[z][y][x].fire) broken = true;
    }
}

// ─── 人物 ─────────────────────────────────────────────────────
class People {
    int id, z, y, x, speed;
    Space space;
    boolean isDead = false, isEscaped = false;
    Stage targetStage = null;

    private static final Random rng = new Random();

    public People(int id, int z, int y, int x, int speed, Space space) {
        this.id = id; this.z = z; this.y = y; this.x = x;
        this.speed = speed; this.space = space;
    }

    // 原本的字串 Key 產生器 (留給傳統 escape 使用)
    private String key(int z, int y, int x) { 
        return z + "," + y + "," + x; 
    }

    // 高效位元優化鍵值轉換 (留給 SmartEscape 使用)
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
        checkStatus();
        if (isDead || isEscaped) return;

        if (targetStage != null) {
            this.z = targetStage.z; this.y = targetStage.y; this.x = targetStage.x;
            targetStage = null;
            checkStatus();
            if (isDead || isEscaped) return;
        }

        boolean inSmoke = space.building[z][y][x].smoke > 0.7;

        if (inSmoke) {
            // 煙中：隨機移動，但若相鄰有 Exit 則優先走向它
            randomMove(speed);
        } else {
            boolean moved = false;
            for (int step = 0; step < speed; step++) {
                int[] nextPos = findNextStepLosBFS();
                if (nextPos == null) break;
                moved = true;

                if (nextPos[0] != this.z) {
                    this.targetStage = (Stage) space.building[nextPos[0]][nextPos[1]][nextPos[2]];
                    break;
                } else {
                    this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
                    checkStatus();
                    if (isDead || isEscaped) break;

                    // 進入煙霧後立即切換隨機模式（本 tick 剩餘步數）
                    if (space.building[z][y][x].smoke > 0.7) {
                        randomMove(speed - step - 1);
                        break;
                    }
                }
            }
            if (!moved) randomMove(speed);
        }
    }
    private int[] findNextStepLosBFS() {
        Set<String> visibleKeys = computeVisibleCells();

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        Queue<int[]>        queue     = new LinkedList<>();
        Map<String, String> parentMap = new HashMap<>();
        Set<String>          visited   = new HashSet<>();

        String startKey = key(z, y, x);
        queue.add(new int[]{z, y, x});
        visited.add(startKey);
        int[] targetPos = null;

        while (!queue.isEmpty()) {
            int[] curr  = queue.poll();
            Obj   currObj = space.building[curr[0]][curr[1]][curr[2]];

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

        return visible;
    }
    // ────────────────────────────────────────────────────────
    // 【全新智慧逃生系統】聯網全域感測器 + 位元優化 + 雙階段避難
    // ────────────────────────────────────────────────────────
    public void SmartEscape(int currentTick) {
        checkStatus();
        if (isDead || isEscaped) return;

        if (targetStage != null) {
            this.z = targetStage.z; this.y = targetStage.y; this.x = targetStage.x;
            targetStage = null;
            checkStatus();
            if (isDead || isEscaped) return;
        }

        for (int step = 0; step < speed; step++) {
            int[] nextPos = computeSmartPath();
            if(nextPos == null)break;//must means no fire detected

            if (nextPos[0] != this.z) {
                this.targetStage = (Stage) space.building[nextPos[0]][nextPos[1]][nextPos[2]];
                break;
            } else {
                this.z = nextPos[0]; this.y = nextPos[1]; this.x = nextPos[2];
                checkStatus();
                if (isDead || isEscaped) break;
            }
        }
    }

    private int[] computeSmartPath() {
        Set<Integer> knownHazards = new HashSet<>();
        Set<Integer> knownFires = new HashSet<>();

        // 【感測器視野】讀取全域感測器網路更新的最新災情
        for (Detector d : Simulator.detectors) {
            int k = bitKey(d.z, d.y, d.x);
            if (d.broken) {
                knownHazards.add(k);
                knownFires.add(k);
            } else if (d.danger) {
                knownHazards.add(k);
            }
        }

        // 【新增：人物真實視距與樓梯的局部視野】彌補感測器盲區，改用 computeVisibleCells()
        Set<String> visibleCells = computeVisibleCells();
        for (String vKey : visibleCells) {
            String[] tokens = vKey.split(",");
            int vz = Integer.parseInt(tokens[0]);
            int vy = Integer.parseInt(tokens[1]);
            int vx = Integer.parseInt(tokens[2]);
            
            Obj localObj = space.building[vz][vy][vx];
            int lKey = bitKey(vz, vy, vx);
            
            if (localObj.fire) {
                knownFires.add(lKey);
                knownHazards.add(lKey);
            }
        }
        
        // 保留樓梯視野：因為 computeVisibleCells() 只掃描同樓層平面
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

        // 階段一：帶危險權重的最短路徑 (Dijkstra)
        // 「出口權重」＝每走一步的基礎成本；「危險權重」＝離威脅越近，該格的額外成本越高
        // 不再對 knownHazards 直接 continue（硬性封路），改成加成本，讓演算法自己權衡「繞路」vs「冒險走近路」
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

            // 過期節點（已經有更便宜的路徑更新過這格），跳過
            if (currCost > bestCost.getOrDefault(currKey, Double.MAX_VALUE)) continue;

            int[] c = decodeKey(currKey);
            Obj currObj = space.building[c[0]][c[1]][c[2]];

            if (currObj instanceof Exit) {
                targetPosKey = currKey;
                break;
            }

            int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
            for (int i = 0; i < 4; i++) {
                int nz = c[0], ny = c[1] + dy[i], nx = c[2] + dx[i];
                if (!space.isValid(nz, ny, nx)) continue;

                Obj nextObj = space.building[nz][ny][nx];
                if (!isPassable(nextObj)) continue; // 真火/真牆/被封的門仍然是絕對禁止，不能用權重換

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
                    if (!isPassable(next)) continue; // 樓梯本身已經真的著火才禁止

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

        // 階段二：死路求生
        return maximizeSurvivalTime(knownFires, knownHazards);
    }

    // ────────────────────────────────────────────────────────
    // 危險成本計算：距離越近，懲罰越高（反距離加權）
    // 真火 (knownFires) 給比普通危險 (knownHazards，例如濃煙警報) 更高的權重
    // ────────────────────────────────────────────────────────
    private static final double EXIT_STEP_WEIGHT = 0.0;   // 每走一步、朝出口前進的基礎成本
    private static final double DANGER_WEIGHT = 0.5;     // 一般危險（感測器濃煙警報）的權重
    private static final double FIRE_EXTRA_WEIGHT = 5.0; // 真實火源的額外權重（比普通危險更嚴重）

    private double dangerCost(int cz, int cy, int cx, Set<Integer> knownFires, Set<Integer> knownHazards) {
        double cost = 0.0;
        for (int fKey : knownFires) {
            int[] f = decodeKey(fKey);
            int dist = Math.abs(cz - f[0]) * 100 + Math.abs(cy - f[1]) + Math.abs(cx - f[2]);
            cost += FIRE_EXTRA_WEIGHT / (1.0 + dist);
        }
        for (int hKey : knownHazards) {
            if (knownFires.contains(hKey)) continue; // 避免真火的格子被算兩次
            int[] h = decodeKey(hKey);
            int dist = Math.abs(cz - h[0]) * 100 + Math.abs(cy - h[1]) + Math.abs(cx - h[2]);
            cost += DANGER_WEIGHT / (1.0 + dist);
        }
        return cost;
    }

    private int[] maximizeSurvivalTime(Set<Integer> knownFires, Set<Integer> knownHazards) {
        // 【修正漏洞 1】優先躲避火源；如果目前沒發現火，則全力躲避有濃煙的感測器，絕不輕易放棄
        Set<Integer> threats = knownFires.isEmpty() ? knownHazards : knownFires;
        
        // 如果全地圖的感測器都完美安全（防呆安全機制），才回傳 null
        if (threats.isEmpty()) return null;

        int maxManhattanDist = -1;
        int[] bestMove = null;

        // 【修正漏洞 2】首先評估「留在原地」的安全距離
        int myMinDist = Integer.MAX_VALUE;
        for (int tKey : threats) {
            int[] t = decodeKey(tKey);
            // 曼哈頓距離計算 (Z軸跨樓層給予 100 倍高權重)
            int mDist = Math.abs(this.z - t[0]) * 100 + Math.abs(this.y - t[1]) + Math.abs(this.x - t[2]);
            if (mDist < myMinDist) myMinDist = mDist;
        }
        // 預設將「留在原地」設為目前最佳解
        maxManhattanDist = myMinDist;
        bestMove = new int[]{this.z, this.y, this.x};

        // 評估四周相鄰 4 個格子
        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        for (int i = 0; i < 4; i++) {
            int ny = y + dy[i], nx = x + dx[i];
            if (!space.isValid(z, ny, nx)) continue;

            Obj nextObj = space.building[z][ny][nx];
            if (!isPassable(nextObj)) continue; // 撞牆或踩火則跳過

            int minDistToThreat = Integer.MAX_VALUE;
            for (int tKey : threats) {
                int[] t = decodeKey(tKey);
                int mDist = Math.abs(this.z - t[0]) * 100 + Math.abs(ny - t[1]) + Math.abs(nx - t[2]);
                if (mDist < minDistToThreat) {
                    minDistToThreat = mDist;
                }
            }

            // 只有當移動過去「能離危險源更遠」時，才更新移動決策
            if (minDistToThreat > maxManhattanDist) {
                maxManhattanDist = minDistToThreat;
                bestMove = new int[]{z, ny, nx};
            }
        }

        // 評估跨樓層（如果人剛好踩在樓梯 Stage 上，檢查上下樓是否能帶來巨大安全感）
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
            // 檢查周圍是否有出口
            for (int i = 0; i < 4; i++) {
                int ny = y + dy[i], nx = x + dx[i];
                if (!space.isValid(z, ny, nx)) continue;
                if (space.building[z][ny][nx] instanceof Exit) {
                    this.y = ny; this.x = nx;
                    checkStatus();
                    return;
                }
            }
            
            // 收集所有可走的相鄰網格
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
            
            // 【新增：如果站在樓梯上，隨機也可能往上下樓跑】
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

    private void checkStatus() {
        Obj cell = space.building[z][y][x];
        if (cell.fire) {
            isDead = true;
        } else if (cell instanceof Exit) {
            isEscaped = true;
        }
    }

    private boolean isPassable(Obj o) {
        if (o instanceof Wall) return false;
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
    static Space space;
    static List<People> peopleList = new ArrayList<>();
    static List<Detector> detectors = new ArrayList<>(); 
    static int  tick   = 0;
    static Random random = new Random();

    // 更新結算變數，刪除舊有的 TotalserviveT，改用 OptimizationDegree
    static double DTotalserviveP = 0; // Default存活率
    static double STotalserviveP = 0; // Smart存活率
    static double TotalOptimizationDegree = 0; // 總體平均優化程度 (T)

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
                    } else if (o instanceof Door) {
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
        for (int i = 1; i <= count; i++) {
            while (true) {
                int z = rand.nextInt(space.height);
                int y = rand.nextInt(space.rows);
                int x = rand.nextInt(space.cols);
                Obj obj = space.building[z][y][x];
                if (obj instanceof Floor) {
                    peopleList.add(new People(i, z, y, x, rand.nextInt(3) + 1, space));
                    break;
                }
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
                obj[z][y][x] = new Door();
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

    // 自身煙霧的每 tick 遞迴成長函式：a_n = f(a_n-1)
    // f(x) = |sin(x - pi/2)|^0.68 + 1
    // 註：sin(x - pi/2) = -cos(x)，在 x∈[0,1] 恆為負值；負底數配非整數指數在實數域無意義，故取絕對值再開次方
    static double smokeGrowth(double x) {
        return Math.pow(Math.abs(Math.sin(x - Math.PI / 2)), 0.68) + 1.0;
    }

    static void spreadEnvironment() {
        int height = space.height, rows = space.rows, cols = space.cols;
        int[] dyFire = {-1, 1, 0, 0}, dxFire = {0, 0, -1, 1};

        double[][][] nextSmoke = new double[height][rows][cols];
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    nextSmoke[z][y][x] = space.building[z][y][x].smoke;

        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    if (space.building[z][y][x].smoke > 0.0) {
                        // 自身煙霧成長：a_n = f(a_n-1)，f(x) = |sin(x - pi/2)|^0.68 + 1
                        // sin(x - pi/2) 在 x∈[0,1] 恆為負值，負數開 0.68 次方在實數域無定義，故取絕對值避免 NaN
                        nextSmoke[z][y][x] = smokeGrowth(space.building[z][y][x].smoke);

                        for (int dy = -3; dy <= 3; dy++) {
                            for (int dx = -3; dx <= 3; dx++) {
                                int dist = Math.abs(dy) + Math.abs(dx);
                                if (dist == 0 || dist > 3) continue; 

                                int ny = y + dy, nx = x + dx;
                                if (space.isValid(z, ny, nx)) {
                                    Obj next = space.building[z][ny][nx];
                                    // 只往「目前完全沒有煙霧」的格子擴散（用擴散前的原始煙霧值判斷）
                                    if (!(next instanceof Wall) && !(next instanceof Exit) && next.smoke == 0.0) {
                                        if (dist == 1) {
                                            nextSmoke[z][ny][nx] += 0.3;
                                        } else if (dist == 2) {
                                            nextSmoke[z][ny][nx] += 0.2;
                                        } else if (dist == 3) {
                                            nextSmoke[z][ny][nx] += 0.1;
                                        }
                                    }
                                }
                            }
                        }

                        if (space.building[z][y][x] instanceof Stage) {
                            Stage s = (Stage) space.building[z][y][x];
                            Stage upT = s.upfloor != null ? findSmokeUpTarget(s) : null;
                            if (upT != null) nextSmoke[upT.z][upT.y][upT.x] += 0.2;
                            Stage downT = s.downfloor != null ? findSmokeDownTarget(s) : null;
                            if (downT != null) nextSmoke[downT.z][downT.y][downT.x] += 0.1;
                        }
                    }
                }
            }
        }

        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    space.building[z][y][x].smoke = Math.min(1.0, nextSmoke[z][y][x]);

        if (tick % 2 == 0) { 
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

    // 檢查是否火焰充斥整棟樓
    static boolean isBuildingFullyOnFire() {
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj cell = space.building[z][y][x];
                    if (!(cell instanceof Wall) && !(cell instanceof Exit)) {
                        if (!cell.fire) {
                            return false; 
                        }
                    }
                }
            }
        }
        return true;
    }

    // 將 run 修改為回傳每個人的生存時間陣列
    static int[] run(boolean useSmart, int totalPeople) {
        int dead = 0, survive = 0;
        int[] survivalTimes = new int[totalPeople]; // 紀錄每個人的存活時間 (索引 0 對應 id 1)
        printMap();
        while (!isBuildingFullyOnFire()) {
            tick++;
            spreadEnvironment();

            for (Detector d : detectors) {
                d.update(space.building);
            }

            List<People> removed = new ArrayList<>();
            for (People p : peopleList) {
                if (useSmart) p.SmartEscape(tick);
                else          p.escape(tick);

                if (p.isDead) { 
                    dead++;    
                    survivalTimes[p.id - 1] = tick; // 如果沒逃出，按照遇到死劫的 tick 數算
                    removed.add(p); 
                }
                else if (p.isEscaped) { 
                    survive++; 
                    removed.add(p); 
                }
            }
            peopleList.removeAll(removed);
            printMap();
            
        }
        
        // 針對逃出或建築物燒毀時仍活著的人，以最終的 tick 為其存活時間
        for (int i = 0; i < totalPeople; i++) {
            if (survivalTimes[i] == 0) { // 0 代表活到了最後，沒被死亡時賦值的才會是0
                survivalTimes[i] = tick;
            }
        }

        System.out.println("\n====== 模擬結束最終報表 ======");
        double survivalRate = ((double) survive / totalPeople) * 100;

        System.out.printf("總人數: %d 人\n", totalPeople);
        if(useSmart) {
            STotalserviveP += (double)survive / totalPeople;
        }
        else {
            DTotalserviveP += (double)survive / totalPeople;
        }
        System.err.println("total: "+totalPeople+", survive: "+survive);
        System.out.printf("生還人數: %d 人 | 死亡人數: %d 人\n", survive, dead);
        System.out.printf("最終生還率: %.2f%%\n", survivalRate);
        System.out.printf("平均生存時長: %.2ftick\n", avg(survivalTimes));
        return survivalTimes;
    }
    private static double avg(int []arr) {
    	double total=0;
    	for(int i:arr)
    		total+=i;
    	return total/arr.length;
    }

    public void work(int iterations,Range H,Range R,Range C) {
        int bug=0;
    	for(int it = 0; it < iterations; it++) {
            System.err.println("it:" + it);
            Random rand = new Random();

            int height = rand.nextInt(H.max-H.min) + H.min;       
            int rows   = rand.nextInt(R.max-R.min) + R.min;       
            int cols   = rand.nextInt(C.max-C.min) + C.min;       

            int maxArea = (rows - 2) * (cols - 2); 
            if (maxArea < 1) maxArea = 1; 

            int peopleCount = Math.max(((rand.nextInt(maxArea) + 1)/4), 1);

            Obj[][][] baseBuilding = generateMap(height, rows, cols);
            Space tempSpace = new Space(baseBuilding);

            int fireZ = 0;
            int fireY = rand.nextInt(tempSpace.rows);
            int fireX = rand.nextInt(tempSpace.cols);
            while (!(baseBuilding[fireZ][fireY][fireX] instanceof Floor)) {
                fireY = rand.nextInt(tempSpace.rows);
                fireX = rand.nextInt(tempSpace.cols);
            }
            baseBuilding[fireZ][fireY][fireX].fire  = true;
            baseBuilding[fireZ][fireY][fireX].smoke = 0.5;

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

            // 取得每個個體在兩種演算法下的存活時間
            int[] defaultTimes = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, false, "defaultResult.txt");
            int[] smartTimes = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, true, "smartResult.txt");

            // 計算當次迭代所有個體的總優化時間（Smart存活時間 - Default存活時間）
            double iterationDiffSum = 0;
            for (int i = 0; i < peopleCount; i++) {
                iterationDiffSum += (smartTimes[i] - defaultTimes[i]);
            }
            
            // 計算當次迭代平均每位個體的優化程度，並累加進總數
            TotalOptimizationDegree += (iterationDiffSum / peopleCount);
            if((iterationDiffSum / peopleCount)<0) bug++;
        }
        System.err.println("bug: "+bug);
        System.err.println("Default Average Survival Rate: " + DTotalserviveP / iterations);
        System.err.println("Smart Average Survival Rate: " + STotalserviveP / iterations);
        System.err.println("Average Optimization Degree (Ticks per person): " + TotalOptimizationDegree / iterations);
    }

    static int[] runOneSimulation(Obj[][][] baseBuilding, int[][] peopleInit,
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
        tick = 0;

        initDetectors();

        for (int i = 0; i < peopleInit.length; i++) {
            int z = peopleInit[i][0], y = peopleInit[i][1], x = peopleInit[i][2], speed = peopleInit[i][3];
            peopleList.add(new People(i + 1, z, y, x, speed, space));
        }

        return run(useSmart, peopleInit.length);
    }
}