package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// GridKeys — 座標 <-> 鍵值 轉換共用工具
//   字串鍵 key() 給視野掃描/一般 BFS 用；位元鍵 bitKey()/decodeKey() 給
//   Smart 路徑規劃(Dijkstra/連通性檢查)用，效能較好。
// ═══════════════════════════════════════════════════════════════════════════
class GridKeys {
    static String key(int z, int y, int x) {
        return z + "," + y + "," + x;
    }

    static int bitKey(int z, int y, int x) {
        return (z << 20) | (y << 10) | x;
    }

    static int[] decodeKey(int key) {
        return new int[]{ (key >> 20) & 0x3FF, (key >> 10) & 0x3FF, key & 0x3FF };
    }
}
