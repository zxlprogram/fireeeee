package simulator;

import java.util.List;

// ═══════════════════════════════════════════════════════════════════════════
// ConsoleMapPrinter — 把目前地圖狀態印成emoji地圖，純輸出、不影響任何模擬狀態。
// ═══════════════════════════════════════════════════════════════════════════
class ConsoleMapPrinter {

    static void print(Space space, int tick, List<People> peopleList) {
        int height = space.height, rows = space.rows, cols = space.cols;
        int[][][] personIdMap = new int[height][rows][cols];
        for (People p : peopleList) {
            if (!p.isDead && !p.isEscaped && space.isValid(p.z, p.y, p.x)) {
                personIdMap[p.z][p.y][p.x] = p.id;
            }
        }
        String[] numEmojis = {"", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"};

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
                    else if (cell instanceof FireDoor)   sb.append(((FireDoor) cell).isOpen ? "⚠️" : "🚧");
                    else if (cell.fire)                  sb.append("🔥");
                    else if (cell.smoke >= 0.5)          sb.append("💨");
                    else if (cell instanceof Door) {
                        sb.append(((Door) cell).blocked ? "X" : "🚪");
                    }
                    else if (cell instanceof Stage)      sb.append("🪜");
                    else if (cell instanceof Wall)       sb.append("🧱");
                    // 【新增】還沒著火、但鋪有可燃物的樓地板格，用木材符號標示，
                    // 讓「可燃物隨機分布」的結果能在地圖上直接看出來(見WorldObjects.Obj.fuel)。
                    else if (cell.fuel != null)          sb.append("🪵");
                    else                                 sb.append("⬜");
                }
                System.out.println(sb);
            }
            System.out.println();
        }
    }
}
