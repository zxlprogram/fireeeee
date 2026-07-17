package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// EscapeStrategy — 逃生決策策略(Strategy Pattern)
//
// 【策略模式取代mode的if/else】重構前，「這個人這個tick要用哪套邏輯決定怎麼走」
// 是由 Simulator.run() 每個tick、對每一個人重新判斷一次：
//     if (useSmart) p.SmartEscape(tick); else p.escape(tick);
// useSmart 本身只在整場模擬開始時依 SimMode 決定一次，卻被放在「每個tick、每個人」
// 都要重新求值一次的迴圈最內層，形成不必要的重複條件判斷，且People的行為選擇邏輯
// 外洩到Simulator的主迴圈裡，而不是People自己的職責。
//
// 重構後，「用哪套邏輯」在 People 建構當下(也就是模擬開始、mode已經確定的時候)
// 就決定好一個 EscapeStrategy 實例並持有它；Simulator 的主迴圈只需要對每個人
// 呼叫統一的 p.tick(currentTick)，不再需要任何 if/else 分支，真正做到用多型
// (由 strategy 物件決定要執行哪個 step() 實作)取代分支判斷。
// ═══════════════════════════════════════════════════════════════════════════
interface EscapeStrategy {
    void step(People p, int currentTick);
}
