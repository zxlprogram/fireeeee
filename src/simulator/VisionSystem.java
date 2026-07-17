package simulator;

import java.util.HashSet;
import java.util.Set;

// ═══════════════════════════════════════════════════════════════════════════
// VisionSystem — 視野 / 察覺相關的純幾何判斷
//   全部是純函式(不讀寫任何人物或模擬狀態，只依賴傳入的座標與地圖)。
// ═══════════════════════════════════════════════════════════════════════════
class VisionSystem {

    // 判定哪些格子屬於「不透光」障礙物，會擋住視線讓人看不到後面的東西：
    //   - 牆：一定擋
    //   - 已被火封死的門(blocked)：視為障礙物，一定擋
    //   - 防火門：關著(isOpen=false)才擋，開著(isOpen=true)就看得穿
    //   - 一般門(非FireDoor)：本身沒有isOpen欄位，視為恆常關閉，一定擋
    //   - 濃煙：該格smoke > 0.7時，視線被煙霧遮蔽，擋住後方視線
    //   - 火源：該格本身正在燒(fire=true)，火焰/熱浪擋住後方視線
    static boolean isOpaque(Obj o) {
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
    private static boolean inShadowOf(double viewY, double viewX, double obsY, double obsX,
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

    // 判斷本層(z不變)是否有暢通視線能看到(targetY,targetX)：
    // 只要被任何一個不透光障礙物格子擋住(見inShadowOf)，就視為看不到。
    static boolean canSee(Space space, int z, int viewY, int viewX, int targetY, int targetX) {
        for (int oy = 0; oy < space.rows; oy++) {
            for (int ox = 0; ox < space.cols; ox++) {
                if (oy == viewY && ox == viewX) continue;             // 自己所在格不算障礙
                if (oy == targetY && ox == targetX) continue;         // 目標本身不能擋住自己
                if (!isOpaque(space.building[z][oy][ox])) continue;
                if (inShadowOf(viewY, viewX, oy, ox, targetY, targetX)) return false;
            }
        }
        return true;
    }

    // 察覺用的「看到」判定：不再只看相鄰四格，改用完整視線(LOS)範圍。
    // 概念(陰影投射 shadow casting)：牆(或已卡死/燒毀的門)這類不透光的障礙物格子，
    // 會在觀察者背後投射出一個「視覺盲區」——盲區的角度範圍，由障礙物格子的
    // 四個角落(格心 ±0.5)決定：從觀察者位置分別畫向四個角落共四條線(四個方向向量)，
    // 取「夾角最大、且夾角<=180度」的那兩條線，作為盲區的兩條邊界。
    // 任何一個目標點，只要 1) 方向落在這兩條邊界夾出的角度內，
    // 且 2) 與觀察者的直線距離(畢氏定理)比「障礙物本身與觀察者的直線距離」更遠，
    // 就會被這個障礙物擋住、判定為看不到。只要被任一障礙物擋住就算看不到。
    // 注意：這是完整LOS掃描，每個尚未察覺的人每個tick都要重算，成本比原本相鄰四格版本高很多，
    // 地圖越大/障礙物越多越明顯，這是刻意的取捨(正確性優先)。
    static boolean seesFireNearby(Space space, int z, int y, int x) {
        if (space.building[z][y][x].fire) return true;
        for (int ty = 0; ty < space.rows; ty++) {
            for (int tx = 0; tx < space.cols; tx++) {
                if (ty == y && tx == x) continue;
                if (!space.building[z][ty][tx].fire) continue;
                if (canSee(space, z, y, x, ty, tx)) return true;
            }
        }

        // 梯間跨樓層視線：預設「看到」不包含其他樓層，唯一例外是梯間(Stage)——
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

    // 計算「目前視野內能看到的所有格子」鍵值集合：本層四個正交方向直線掃描 +
    // 四個象限的斜向擴散掃描(只要任一相鄰側已可見就延伸)，員工(STAFF)額外
    // 對本層所有樓梯間有先天認知(不受視野限制)。
    static Set<String> computeVisibleCells(Space space, int z, int y, int x, PersonProfile profile) {
        Set<String> visible = new HashSet<>();
        visible.add(GridKeys.key(z, y, x));

        int[] dy = {-1, 1, 0, 0}, dx = {0, 0, -1, 1};

        for (int dir = 0; dir < 4; dir++) {
            int cy = y, cx = x;
            while (true) {
                int ny = cy + dy[dir], nx = cx + dx[dir];
                if (!space.isValid(z, ny, nx)) break;

                Obj obj = space.building[z][ny][nx];
                String k = GridKeys.key(z, ny, nx);

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

                boolean sideA = visible.contains(GridKeys.key(z, ny - qdy[dir], nx));
                boolean sideB = visible.contains(GridKeys.key(z, ny, nx - qdx[dir]));
                if (!sideA && !sideB) break;

                Obj obj = space.building[z][ny][nx];
                String k = GridKeys.key(z, ny, nx);

                if (obj instanceof Exit)  { visible.add(k); break; }
                if (obj instanceof Wall)  break;
                if ((obj instanceof Door && ((Door) obj).blocked) || obj.fire) { visible.add(k); break; }
                if (obj instanceof Stage) { visible.add(k); break; }

                visible.add(k);
            }
        }

        // 員工：對場域熟悉，即使沒有直接視線也知道本層所有樓梯間(備用逃生動線)位置
        if (profile == PersonProfile.STAFF) {
            for (int by = 0; by < space.rows; by++) {
                for (int bx = 0; bx < space.cols; bx++) {
                    if (space.building[z][by][bx] instanceof Stage) {
                        visible.add(GridKeys.key(z, by, bx));
                    }
                }
            }
        }

        return visible;
    }
}
