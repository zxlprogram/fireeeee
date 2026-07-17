package simulator;

import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// DeviceStatus — 手機電力 / 系統連線 / 定位誤差模擬
//   每個 tick 唯一的 rng 呼叫順序(先手機沒電、後訊號斷線)跟原本完全相同，
//   只是把「消耗 Random 的動作」搬進這個類別的 tick() 方法裡執行。
// ═══════════════════════════════════════════════════════════════════════════
class DeviceStatus {
    boolean networkConnected;      // 是否仍與智慧系統保持連線(手機沒電或訊號斷線都會導致false)
    private boolean phoneHasBattery; // 手機是否還有電
    double posErrorY, posErrorX;   // 定位誤差(格)，只影響系統端「回報位置」，不影響實際移動與判定

    private static final double PHONE_DEAD_INIT_CHANCE   = 0.05;  // 一開始手機就沒電/沒帶在身上的機率
    private static final double PHONE_DEAD_TICK_CHANCE   = 0.0015;// 每tick手機耗盡電力(緊張時頻繁操作)的機率
    private static final double NETWORK_DROP_TICK_CHANCE = 0.001; // 每tick訊號/節點斷線的機率
    private static final double POS_ERROR_STDDEV         = 1.5;   // 定位誤差標準差(格)

    DeviceStatus(Random rng) {
        // 系統連線初始狀態：手機本身有沒有電，決定了一開始是否連得上智慧系統
        this.phoneHasBattery = rng.nextDouble() >= PHONE_DEAD_INIT_CHANCE;
        this.networkConnected = this.phoneHasBattery;

        // 定位誤差：系統看到的位置跟實際位置會有落差，只用於「系統端回報」，不影響本人實際移動
        this.posErrorY = rng.nextGaussian() * POS_ERROR_STDDEV;
        this.posErrorX = rng.nextGaussian() * POS_ERROR_STDDEV;
    }

    // ────────────────────────────────────────────────────────
    // 手機沒電 / 網路節點斷線，會讓系統建議失效
    // ────────────────────────────────────────────────────────
    void tick(Random rng) {
        if (phoneHasBattery && rng.nextDouble() < PHONE_DEAD_TICK_CHANCE) {
            phoneHasBattery = false; // 緊張時頻繁操作手機求救/查看地圖，電力耗盡
        }
        if (networkConnected && rng.nextDouble() < NETWORK_DROP_TICK_CHANCE) {
            networkConnected = false; // 現場訊號不穩或節點斷線
        }
        if (!phoneHasBattery) {
            networkConnected = false; // 沒電就完全無法再跟系統互動
        }
    }
}
