package simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// EnvironmentSimulator — 火 / 煙 / 煙味擴散演算法，以及建築環境危險程度查詢
//
// 【拆解God Class + 降低耦合】原本 spreadEnvironment() 直接讀寫 Simulator 的
// static 欄位(space/currentCause/tick/activeControlEnabled/activeControlActionCount/
// random)。現在改成 spread(...) 明確接收這些狀態當參數，並把「這次呼叫觸發了幾次
// 主動控制動作」當作回傳值交給呼叫端自行累加，不再直接改寫呼叫端的計數器。
// 邏輯本體、每一個 random.nextDouble()/nextGaussian() 的呼叫時機與次序都跟原本
// 完全相同，因此不影響模擬的亂數序列。
// ═══════════════════════════════════════════════════════════════════════════
class EnvironmentSimulator {

    // ─── 三種擴散物(火源/煙霧/煙味)穿越「門」的機率 ───────────────────
    // 防火門(關好時)跟普通門都不再是「完全阻絕」或「完全不管」的二選一，而是各自有一個
    // 穿越機率；同一種擴散物永遠是「防火門機率 < 普通門機率」，跨擴散物則是
    // 「火源 < 煙霧 < 煙味」。數值都是可調參數，改這六個常數就好，不用動下面的邏輯。
    static final double FIRE_SPREAD_PROB_FIREDOOR    = 0.05; // 火源穿越「關好的」防火門
    static final double FIRE_SPREAD_PROB_NORMALDOOR  = 0.35; // 火源穿越普通門
    static final double SMOKE_SPREAD_PROB_FIREDOOR   = 0.45; // 煙霧穿越「關好的」防火門
    static final double SMOKE_SPREAD_PROB_NORMALDOOR = 0.65; // 煙霧穿越普通門
    static final double SMELL_SPREAD_PROB_FIREDOOR   = 0.75; // 煙味穿越「關好的」防火門
    static final double SMELL_SPREAD_PROB_NORMALDOOR = 0.90; // 煙味穿越普通門

    static double smokeGrowth(double x) {
        return Math.pow(Math.abs(Math.sin(x - Math.PI / 2)), 0.68) + 1.0;
    }

    // ─── 火/煙擴散速度的「時間相位」倍率：跟tick掛勾，且依起火原因而不同形狀 ───
    // 回傳值疊乘在原本跟起火原因掛勾的 smokeMultiplier / fireSpreadTick 之上：
    //   >1.0 代表這個時間點擴散比基準快，<1.0 代表比基準慢，1.0 代表跟基準一致。
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

    // 每個可停留格子(Floor/Stage)是否都著火：迴圈的終止條件
    static boolean allFloorsOnFire(Space space) {
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

    // ASET判定：建築內所有可停留格子(Floor/Stage)是否都已陷入危險(著火或濃煙>0.7)
    static boolean allOccupiableCellsUntenable(Space space) {
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

    // 執行一次tick的火/煙/煙味擴散，並回傳「這次呼叫觸發了幾次主動控制動作
    // (自動關閉未關緊的防火門)」，呼叫端(Simulator)自行把它累加進總計數器。
    static int spread(Space space, FireCause currentCause, int tick, boolean activeControlEnabled, Random random) {
        int height = space.height, rows = space.rows, cols = space.cols;
        int[] dyFire = {-1, 1, 0, 0}, dxFire = {0, 0, -1, 1};
        int activeControlActionsThisTick = 0;

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

        // ─── 擴散速度跟tick掛勾：疊加一個「時間相位」倍率 ─────────────────
        double temporalFactor = temporalSpreadFactor(currentCause, tick);
        smokeMultiplier *= temporalFactor;

        // ─── B2情境：決策支援 + 備援控制 ─────────────────────
        // 系統除了給人建議之外，偵測到附近有危險時也會主動操作環境：
        //   1) 自動關閉「本來沒關好」的防火門，讓它恢復阻絕火勢延燒的能力
        //   2) 局部啟動排煙/抑制手段，讓整體煙霧增長率打折扣(模擬機械排煙/灑水)
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
                            activeControlActionsThisTick++;
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

                                        // 煙霧要進入「門」這一格，先看這一步是否成功穿越門縫；
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

        // ─── 煙味(smell)擴散：無害，只用來觸發People的「察覺」判定 ───────────
        // 只要有煙霧或火源的格子都會持續放出煙味；擴散範圍/衰減跟煙霧用同一套邏輯，
        // 差別在穿越「門」這一步的機率遠高於煙霧，模擬「看不到煙，但聞得到味道」
        // 這種比視覺線索更早出現、也更難完全被門封死的訊號。
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

                    // 煙味也跟煙霧一樣，會透過梯間往上/下樓層傳一點，
                    // 邏輯與煙霧完全比照，只差在傳遞量比照煙霧的樓梯間擴散幅度(0.2/0.1)。
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

        // 【機率制火勢延燒】期望頻率仍然是 1/fireSpreadTick(維持原本各起火原因的基準節奏)，
        // 但額外乘上跟tick掛勾的temporalFactor，讓觸發機率隨時間非線性起伏。
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

                        // 防火門不是「關好=100%擋住」，而是低機率被竄燒過去(密封失敗/門縫走火)；
                        // 普通門也是機率制，機率比防火門高。
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

        return activeControlActionsThisTick;
    }
}
