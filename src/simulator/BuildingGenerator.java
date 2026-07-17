package simulator;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// BuildingGenerator — 建築地圖生成 / 複製
//   【拆解God Class】原本這些方法直接寫在 Simulator 裡，跟人員/環境/報表邏輯
//   混在一起。這些方法本身完全是純函式(只依賴傳入的參數，不讀寫 Simulator 的
//   任何 static 欄位)，因此可以原封不動搬到獨立類別，Simulator 呼叫時只需要
//   把需要的參數傳進來，不再需要在同一個類別裡才能用。
// ═══════════════════════════════════════════════════════════════════════════
class BuildingGenerator {

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
            int wallCount = (rows * cols) / (int) (rand.nextDouble(6) + 4), attempts = 0, placed = 0;
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
                        int dist = Math.abs(sy - stages[z - 1].y) + Math.abs(sx - stages[z - 1].x);
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
                if (obj[z][rows - 2][x] instanceof Floor) edgeCandidates.add(new int[]{rows - 1, x});
            }
            for (int y = 1; y < rows - 1; y++) {
                if (obj[z][y][1] instanceof Floor)      edgeCandidates.add(new int[]{y, 0});
                if (obj[z][y][cols - 2] instanceof Floor) edgeCandidates.add(new int[]{y, cols - 1});
            }
            if (!edgeCandidates.isEmpty()) {
                int[] chosen = edgeCandidates.get(rand.nextInt(edgeCandidates.size()));
                obj[z][chosen[0]][chosen[1]] = new Exit();
            }
        }

        return obj;
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
}
