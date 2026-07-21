package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// CustomRoomParser — 自訂房型入口專用：把使用者手動輸入的房型佈局解析成
// Obj[][][]，取代 BuildingGenerator.generateMap() 裡「隨機生成房間/樓梯/出口」
// 的步驟(§1~§5)。
//
// 輸入是「三維字串陣列」layout[z][y][x]：z=樓層、y=列、x=行，每個元素就是
// ConsoleMapPrinter印地圖時用的那個icon本身(反向輸入)，只有出口刻意另外用
// 'E'表示(理由見下方對照表)。單層房型可以只給 layout.length==1。
//
// 【除了房型生成之外，其他都照原本流程】generateMap()尾段的§6可燃物隨機分布
// (distributeFuelLoads)與§7統計列印(printCompartmentStats)，在這裡對解析完的
// 自訂佈局照樣呼叫一次，讓自訂房型跟隨機生成房型享有完全一致的後續行為；
// 再之後(起火點/人物抽樣、DEFAULT/SMART/HYBRID三種模式各跑一次run()、統計彙整
// /報表)則完全交回 Simulator，不做任何特殊處理。
//
// ─── 字元對照表(依 ConsoleMapPrinter 目前使用的地圖icon「反向」對應而來) ─────
//
//   icon  | 物件                              | 輸入符號
//   ⬜    | Floor    一般樓地板              | ⬜ (或 '.'、空白、空字串)
//   🧱    | Wall     牆(不可通行)            | 🧱 (或 'W')
//   🚪    | Door     一般門                  | 🚪 (或 'D')
//   🚧    | FireDoor，預設關閉(isOpen=false)  | 🚧 (或 'F')
//   ⚠️    | FireDoor，預設沒關好(isOpen=true) | ⚠️ (或 'O')
//   🪜    | Stage    樓梯間格                | 🪜 (或 'S')
//        |   同一個(y,x)座標若在相鄰樓層(z, z+1)都是Stage，視為同一座樓梯，
//        |   會自動建立upfloor/downfloor連結(z越大代表越高的樓層)。
//   🪵    | 尚未著火、鋪有可燃物的樓地板       | 🪵
//        |   直接指定這一格帶有可燃物(預設WOOD)。之後distributeFuelLoads()
//        |   只會對「還沒被指定過可燃物」的一般Floor格再隨機灑一輪，不會
//        |   覆蓋這裡已經明確指定的格子。
//   🚪    | Exit     逃生出口                | 'E'
//        |   ConsoleMapPrinter裡Exit跟Door畫的是同一個🚪，容易混淆，
//        |   因此刻意不沿用Door的符號，另外用'E'表示。
//
// fire/smoke/coPpm/人員位置等屬於「模擬過程中才會變化」的動態狀態，不是房型佈局
// 本身的一部分，因此不開放由這個對照表直接指定，一律維持Obj的預設初始值，
// 之後交給EnvironmentSimulator/ScenarioInitializer在模擬開始後照原本方式產生。
// ═══════════════════════════════════════════════════════════════════════════
class CustomRoomParser {

    // ─── 多樓層入口 ─────────────────────────────────────────────────────
    static Obj[][][] parse(String[][][] layout) {
        if (layout == null || layout.length == 0) {
            throw new IllegalArgumentException("自訂房型不可為空");
        }
        int height = layout.length;
        if (layout[0] == null || layout[0].length == 0 || layout[0][0] == null || layout[0][0].length == 0) {
            throw new IllegalArgumentException("自訂房型不可為空");
        }
        int rows = layout[0].length;
        int cols = layout[0][0].length;

        Obj[][][] obj = new Obj[height][rows][cols];
        Stage[][][] stageMap = new Stage[height][rows][cols];

        boolean hasExit = false;
        boolean hasFloor = false;

        for (int z = 0; z < height; z++) {
            if (layout[z] == null || layout[z].length != rows) {
                throw new IllegalArgumentException(
                    "第 " + z + " 層的列數(" + (layout[z] == null ? 0 : layout[z].length)
                    + ")與第 0 層(" + rows + ")不一致，每一層都必須是同樣大小的矩形");
            }
            for (int y = 0; y < rows; y++) {
                if (layout[z][y] == null || layout[z][y].length != cols) {
                    throw new IllegalArgumentException(
                        "第 " + z + " 層第 " + y + " 列的欄位數與第 0 列(" + cols + ")不一致，輸入必須是矩形陣列");
                }
                for (int x = 0; x < cols; x++) {
                    Obj cell = toObj(layout[z][y][x]);
                    if (cell instanceof Stage) {
                        Stage st = (Stage) cell;
                        st.setLocation(z, y, x);
                        stageMap[z][y][x] = st;
                    }
                    obj[z][y][x] = cell;
                    if (cell instanceof Exit) hasExit = true;
                    if (cell instanceof Floor) hasFloor = true;
                }
            }
        }

        // 相鄰樓層(z, z+1)同一個(y,x)都是Stage的話，視為同一座樓梯，串起upfloor/downfloor，
        // 讓RouteFinder/People的跨樓層移動邏輯認得出這是可以上下樓的樓梯間。
        for (int z = 0; z < height - 1; z++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    Stage lower = stageMap[z][y][x];
                    Stage upper = stageMap[z + 1][y][x];
                    if (lower != null && upper != null) {
                        lower.upfloor = upper;
                        upper.downfloor = lower;
                    }
                }
            }
        }

        // 以下只是提醒性質的警告，不會阻擋模擬繼續執行(維持跟BuildingGenerator
        // 隨機生成失敗時「盡量跑得下去」的一貫寬鬆風格一致)。
        if (!hasExit) {
            System.err.println("[CustomRoomParser] 警告：自訂房型沒有任何 'E'(出口)，人員將永遠無法逃生成功。");
        }
        if (!hasFloor) {
            System.err.println("[CustomRoomParser] 警告：自訂房型沒有任何樓地板，無法生成起火點/人員初始位置，模擬會卡住。");
        }
        for (int z = 0; z < height; z++) {
            if (!BuildingGenerator.isMapConnected(obj[z], rows, cols)) {
                System.err.println("[CustomRoomParser] 警告：第 " + z + " 層並非完全連通，部分區域可能永遠到不了出口(若該層有靠樓梯連到別層，仍可能逃得出去)。");
            }
        }

        return obj;
    }

    // ─── 單樓層入口(便利方法)：包成 height=1 的三維陣列再呼叫上面那個 ───────
    static Obj[][][] parse(String[][] singleFloorLayout) {
        if (singleFloorLayout == null) {
            throw new IllegalArgumentException("自訂房型不可為空");
        }
        return parse(new String[][][]{ singleFloorLayout });
    }

    private static Obj toObj(String token) {
        String t = (token == null) ? "" : token.trim();
        if (t.isEmpty() || t.equals(".") || t.equals("⬜")) return new Floor();

        switch (t) {
            case "🧱": case "W": return new Wall();
            case "🚪": case "D": return new Door();
            case "🚧": case "F": {
                FireDoor fd = new FireDoor();
                fd.isOpen = false; // 🚧 預設關閉、能有效阻絕火煙
                return fd;
            }
            case "⚠️": case "⚠": case "O": {
                FireDoor fd = new FireDoor();
                fd.isOpen = true; // ⚠️ 沒關好，效果退化成普通門
                return fd;
            }
            case "🪜": case "S": return new Stage();
            case "E": return new Exit();
            case "🪵": {
                Floor f = new Floor();
                f.fuel = FuelType.WOOD; // icon本身沒有區分具體燃料種類，取最常見的木質可燃物代表
                return f;
            }
            default: return new Floor(); // 未知符號一律當作一般樓地板，避免因為打錯字就整個炸掉
        }
    }
}
