package simulator;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// BuildingGenerator — 建築地圖生成 / 複製
// ═══════════════════════════════════════════════════════════════════════════
class BuildingGenerator {

    // ─── 檢查包含「門」在內的完整連通性 ────────────────────────
    static boolean isMapConnected(Obj[][] layer, int rows, int cols) {
        int sy = -1, sx = -1;
        int totalWalkable = 0;
        
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (!(layer[y][x] instanceof Wall)) {
                    if (sy == -1) { sy = y; sx = x; }
                    totalWalkable++;
                }
            }
        }
        if (sy == -1) return true; 

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        boolean[][] vis = new boolean[rows][cols];
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{sy, sx}); 
        vis[sy][sx] = true;
        int reached = 1;
        
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int i = 0; i < 4; i++) {
                int ny = c[0] + dy[i], nx = c[1] + dx[i];
                if (ny >= 0 && ny < rows && nx >= 0 && nx < cols) {
                    if (!vis[ny][nx] && !(layer[ny][nx] instanceof Wall)) {
                        vis[ny][nx] = true; 
                        reached++; 
                        q.add(new int[]{ny, nx});
                    }
                }
            }
        }
        return reached == totalWalkable;
    }

    // ─── 修正版：BSP 房間切割演算法 ─────────────────────────────────────
    private static void generateRooms(Obj[][] layer, int x, int y, int w, int h, Random rand) {
        // 【新增安全防呆】如果傳入的寬或高小於等於 0 (例如極端小的地圖設定)，直接返回避免崩潰
        if (w <= 0 || h <= 0) return;

        final int MIN_ROOM_SIZE = 4; // 房間內部最小寬/高
        boolean canSplitH = h >= (MIN_ROOM_SIZE * 2 + 1);
        boolean canSplitV = w >= (MIN_ROOM_SIZE * 2 + 1);

        if (!canSplitH && !canSplitV) return; // 已經夠小，不再切分

        boolean splitH = false;
        if (canSplitH && canSplitV) {
            if (h > w * 1.5) splitH = true;
            else if (w > h * 1.5) splitH = false;
            else splitH = rand.nextBoolean();
        } else if (canSplitH) {
            splitH = true;
        }

        if (splitH) {
            // 水平切一刀 (y 座標固定)
            int splitY = y + MIN_ROOM_SIZE + rand.nextInt(h - MIN_ROOM_SIZE * 2);

            // 1. 蓋滿完整的實體牆
            for (int i = x; i < x + w; i++) layer[splitY][i] = new Wall();

            // 2. 挖洞放門
            int doorCount = (w > 12 && rand.nextDouble() < 0.3) ? 2 : 1;
            int[] doorX = new int[doorCount];
            doorX[0] = x + rand.nextInt(w);
            if (doorCount == 2) {
                doorX[1] = x + rand.nextInt(w);
                while (doorX[1] == doorX[0]) doorX[1] = x + rand.nextInt(w); 
            }

            for (int dx : doorX) {
                if (rand.nextBoolean()) {
                    FireDoor fd = new FireDoor();
                    fd.isOpen = rand.nextDouble() < 0.15; 
                    layer[splitY][dx] = fd;
                } else {
                    layer[splitY][dx] = new Door();
                }
            }

            // 3. 遞迴處理上下兩塊新區域
            generateRooms(layer, x, y, w, splitY - y, rand);
            // 💡【已修正】移除前方多餘的 "y +"，確保剩餘高度計算準確
            generateRooms(layer, x, splitY + 1, w, h - (splitY - y) - 1, rand);

        } else {
            // 垂直切一刀 (x 座標固定)
            int splitX = x + MIN_ROOM_SIZE + rand.nextInt(w - MIN_ROOM_SIZE * 2);

            // 1. 蓋滿完整的實體牆
            for (int i = y; i < y + h; i++) layer[i][splitX] = new Wall();

            // 2. 挖洞放門
            int doorCount = (h > 12 && rand.nextDouble() < 0.3) ? 2 : 1;
            int[] doorY = new int[doorCount];
            doorY[0] = y + rand.nextInt(h);
            if (doorCount == 2) {
                doorY[1] = y + rand.nextInt(h);
                while (doorY[1] == doorY[0]) doorY[1] = y + rand.nextInt(h);
            }

            for (int dy : doorY) {
                if (rand.nextBoolean()) {
                    FireDoor fd = new FireDoor();
                    fd.isOpen = rand.nextDouble() < 0.15;
                    layer[dy][splitX] = fd;
                } else {
                    layer[dy][splitX] = new Door();
                }
            }

            // 3. 遞迴處理左右兩塊新區域
            generateRooms(layer, x, y, splitX - x, h, rand);
            generateRooms(layer, splitX + 1, y, w - (splitX - x) - 1, h, rand);
        }
    }

    static Obj[][][] generateMap(int height, int rows, int cols) {
        Obj[][][] obj = new Obj[height][rows][cols];
        Random rand = new Random();

        // 1. 初始化整棟建築為 Floor
        for (int z = 0; z < height; z++)
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++)
                    obj[z][y][x] = new Floor();

        // 2. 鋪設建築最外圍的承重牆
        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                obj[z][y][0] = new Wall(); obj[z][y][cols - 1] = new Wall();
            }
            for (int x = 0; x < cols; x++) {
                obj[z][0][x] = new Wall(); obj[z][rows - 1][x] = new Wall();
            }
        }

        // 3. 使用 BSP 演算法劃分房間
        for (int z = 0; z < height; z++) {
            boolean valid = false;
            int attempts = 0;
            
            while (!valid && attempts < 10) {
                attempts++;
                for (int y = 1; y < rows - 1; y++) {
                    for (int x = 1; x < cols - 1; x++) {
                        obj[z][y][x] = new Floor();
                    }
                }

                // 呼叫 BSP 演算法
                generateRooms(obj[z], 1, 1, cols - 2, rows - 2, rand);

                if (isMapConnected(obj[z], rows, cols)) {
                    valid = true;
                }
            }
        }

        // 4. 多樓梯生成與配置
        if (height > 1) {
            int numStairs = Math.max(MIN_STAIRS, (rows * cols) / STAIR_AREA_PER_UNIT);
            List<int[]> stairAnchors = new ArrayList<>();

            for (int stairIdx = 0; stairIdx < numStairs; stairIdx++) {
                Stage[] stages = new Stage[height];
                for (int z = 0; z < height; z++) {
                    int sy = -1, sx = -1, attempts = 0;
                    while (attempts < 300) {
                        attempts++;
                        int cy = rand.nextInt(rows - 2) + 1, cx = rand.nextInt(cols - 2) + 1;
                        if (!(obj[z][cy][cx] instanceof Floor)) continue;
                        if (z > 0) {
                            int dist = Math.abs(cy - stages[z - 1].y) + Math.abs(cx - stages[z - 1].x);
                            if (dist > 3) continue;
                        }
                        if (z == 0 && !farEnough(cy, cx, stairAnchors, MIN_STAIR_SEPARATION)) continue;
                        sy = cy; sx = cx;
                        break;
                    }
                    if (sy == -1) {
                        sy = rand.nextInt(rows - 2) + 1; sx = rand.nextInt(cols - 2) + 1;
                    }
                    Stage s = new Stage(); s.setLocation(z, sy, sx);
                    stages[z] = s; obj[z][sy][sx] = s;
                }
                for (int z = 0; z < height - 1; z++) {
                    stages[z].upfloor = stages[z + 1];
                    stages[z + 1].downfloor = stages[z];
                }
                stairAnchors.add(new int[]{stages[0].y, stages[0].x});
                enclosePassage(obj, stages, rows, cols);
            }
        }

        // 5. 地面層多出口配置
        if (height > 0 && rows > 2 && cols > 2) { // 新增邊界保護
            int z = 0;
            List<int[]> edgeCandidates = new ArrayList<>();
            for (int x = 1; x < cols - 1; x++) {
                if (obj[z][1][x] instanceof Floor)      edgeCandidates.add(new int[]{0, x});
                if (obj[z][rows - 2][x] instanceof Floor) edgeCandidates.add(new int[]{rows - 1, x});
            }
            for (int y = 1; y < rows - 1; y++) {
                if (obj[z][y][1] instanceof Floor)      edgeCandidates.add(new int[]{y, 0});
                if (obj[z][y][cols - 2] instanceof Floor) edgeCandidates.add(new int[]{y, cols - 1});
            }

            int numExits = Math.max(MIN_EXITS, (rows * cols) / EXIT_AREA_PER_UNIT);
            java.util.Collections.shuffle(edgeCandidates, rand);
            List<int[]> chosenExits = new ArrayList<>();
            for (int[] cand : edgeCandidates) {
                if (chosenExits.size() >= numExits) break;
                if (!farEnough(cand[0], cand[1], chosenExits, MIN_EXIT_SEPARATION)) continue;
                chosenExits.add(cand);
            }
            if (chosenExits.isEmpty() && !edgeCandidates.isEmpty()) chosenExits.add(edgeCandidates.get(0));
            for (int[] c : chosenExits) obj[z][c[0]][c[1]] = new Exit();
        }

        // 6. 隨機分布可燃物：只在「一般樓地板(Floor)」上鋪設，門/牆/樓梯/出口
        // 不放，且不是每一格Floor都有——見distributeFuelLoads()註解。放在樓梯/
        // 出口都已配置完成之後，確保不會對已經變成Stage/Exit的格子誤植可燃物。
        distributeFuelLoads(obj, height, rows, cols, rand);

        // 7. 印出統計
        printCompartmentStats(obj, height, rows, cols);

        return obj;
    }

    // ─── 可燃物隨機分布 ─────────────────────────────────────────────
    // 【新增，回應「可燃物隨機分布在每個空白區域」需求】把FuelType隨機灑在部分
    // Floor格上，模擬室內家具/建材分布不均勻的真實情況：有些地方(例如堆滿家具、
    // 沙發、紙箱的房間)可燃物濃度高，有些地方(例如淨空的走道、大廳)幾乎沒有
    // 可燃物。這個分布只在地圖生成時決定一次，之後隨模擬過程被逐格燒毀
    // (fire=true)，不會中途重新洗牌。
    //
    // 覆蓋率與燃料組成都是工程判斷取的合理量級，不是逐一文獻校正的精確值
    // (定位比照FuelType.java檔頭註解「先把資料結構撐起來」的態度)：
    //   - FUEL_COVERAGE_PROBABILITY：約半數樓地板格鋪有可燃物，另一半視為
    //     相對淨空的走道/通道/已清空區域，讓「有些地方有、有些地方沒有」
    //     這件事真的在地圖上呈現出斑駁分布，而不是均勻鋪滿。
    //   - AMBIENT_FUEL_POOL：一般室內環境常見的固態可燃物組成(木質家具/紙類
    //     /泡棉家具填充物/PVC電線管材)，木材出現頻率加倍反映其在室內佔比通常
    //     最高；刻意不含GASOLINE，因為汽油是可燃「液體」，不會隨機平鋪在一般
    //     房間格子裡當環境背景可燃物，仍只透過FireCause.representativeFuel
    //     (化學/縱火起火)在起火點本身出現。
    private static final double FUEL_COVERAGE_PROBABILITY = 0.5;
    private static final FuelType[] AMBIENT_FUEL_POOL = {
        FuelType.WOOD, FuelType.WOOD, FuelType.PAPER, FuelType.PU_FOAM, FuelType.PVC
    };

    // 【原為private】改成package-private，讓CustomRoomParser在自訂房型(手動輸入
    // 佈局、跳過generateMap()前段的隨機房間/樓梯/出口生成)之後，仍能重用同一套
    // 可燃物分布邏輯，維持跟隨機生成房型完全一致的後續行為。
    static void distributeFuelLoads(Obj[][][] obj, int height, int rows, int cols, Random rand) {
        for (int z = 0; z < height; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Obj cell = obj[z][y][x];
                    // 只在一般樓地板上鋪可燃物；門/牆/樓梯間/出口本身不是「家具/建材
                    // 堆積區」的概念，維持fuel=null(不可燃/無額外燃料負載)保底。
                    if (!(cell instanceof Floor)) continue;
                    if (cell.fuel != null) continue; // 已經被明確指定過可燃物(例如自訂房型的🪵)，不覆蓋
                    if (rand.nextDouble() >= FUEL_COVERAGE_PROBABILITY) continue; // 這一格保持空白(沒有可燃物)
                    cell.fuel = AMBIENT_FUEL_POOL[rand.nextInt(AMBIENT_FUEL_POOL.length)];
                }
            }
        }
    }

    // 【原為private】理由同distributeFuelLoads()。
    static void printCompartmentStats(Obj[][][] obj, int height, int rows, int cols) {
        int[] dy = {-1, 1, 0, 0};
        int[] dx = {0, 0, -1, 1};

        for (int z = 0; z < height; z++) {
            boolean[][] vis = new boolean[rows][cols];
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Obj currObj = obj[z][y][x];
                    if (!vis[y][x] && (currObj instanceof Floor || currObj instanceof Stage || currObj instanceof Exit)) {
                        Queue<int[]> q = new LinkedList<>();
                        q.add(new int[]{y, x});
                        vis[y][x] = true;

                        while (!q.isEmpty()) {
                            int[] c = q.poll();
                            for (int i = 0; i < 4; i++) {
                                int ny = c[0] + dy[i], nx = c[1] + dx[i];
                                if (ny >= 0 && ny < rows && nx >= 0 && nx < cols && !vis[ny][nx]) {
                                    Obj neighbor = obj[z][ny][nx];
                                    if (neighbor instanceof Floor || neighbor instanceof Stage || neighbor instanceof Exit) {
                                        vis[ny][nx] = true;
                                        q.add(new int[]{ny, nx});
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static final int STAIR_AREA_PER_UNIT = 250;
    private static final int EXIT_AREA_PER_UNIT  = 250;
    private static final int MIN_STAIRS = 2;            
    private static final int MIN_EXITS  = 2;            
    private static final int MIN_STAIR_SEPARATION = 6;  
    private static final int MIN_EXIT_SEPARATION  = 6;  

    private static boolean farEnough(int y, int x, List<int[]> existing, int minDist) {
        for (int[] p : existing) {
            if (Math.abs(p[0] - y) + Math.abs(p[1] - x) < minDist) return false;
        }
        return true;
    }

    private static void enclosePassage(Obj[][][] obj, Stage[] stages, int rows, int cols) {
        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};
        for (Stage s : stages) {
            if (s == null) continue;
            for (int i = 0; i < 4; i++) {
                int ny = s.y + dy[i], nx = s.x + dx[i];
                if (ny <= 0 || ny >= rows - 1 || nx <= 0 || nx >= cols - 1) continue;
                if (!(obj[s.z][ny][nx] instanceof Floor)) continue;
                FireDoor fd = new FireDoor();
                fd.isOpen = false;
                obj[s.z][ny][nx] = fd;
                s.enclosureDoor = fd;
                break;
            }
        }
    }

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
                    n.fuel  = o.fuel; // 【新增】可燃物種類也要跟著複製，否則clone後的地圖會全部變回無燃料
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
}