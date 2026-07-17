package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// PersonKpi — 逃生過程中的統計/事件追蹤資料(純資料封裝)
//   People 只需持有一個 kpi 參照，沒有任何邏輯／消耗Random的行為被改動。
// ═══════════════════════════════════════════════════════════════════════════
class PersonKpi {
    boolean systemIdentifiedVulnerable = false; // 系統(連線狀態下)是否已辨識並標記此人為需優先協助對象
    Integer firstCorrectDecisionTick = null;    // 第一次做出「有依據(非隨機亂走)」移動決策的tick
    boolean everWrongRoute = false;             // 是否曾依現有資訊做出決策，卻仍走進高煙區(資訊落後於現場)
    boolean everRerouted = false;               // 原定路線(樓梯/通道)在移動前失效，是否曾被迫重新規劃
    int rerouteAttempts = 0;                    // 總共被迫重新規劃路線的次數
    Integer reportedTrappedTick = null;         // 系統端最早得知此人「受困/卡住」位置的tick

    Integer adviceIssuedTick = null;      // 目前這筆建議(不論跨樓層或同樓層)的發布時間
    Integer adviceRevokedTick = null;     // 建議被撤回(環境改變或連通性喪失)的那個tick，null代表目前沒有「待補建議」的空窗
    Integer revokeToRerouteTicks = null;  // 撤回後到完成改道(拿到下一筆任務目標格)所花的tick數；整場模擬只記錄第一次撤回事件

    boolean everEnteredHighSmoke = false;   // 曾經進入高煙區，用於事後統計（跟是否死亡無關）
    boolean everReachedCriticalCO = false;  // 曾經CO暴露達臨界值，用於事後統計（跟是否死亡無關）
}
