package simulator;

import java.util.List;

// ═══════════════════════════════════════════════════════════════════════════
// SimulationContext — People / RouteFinder 對「模擬控制器」唯一允許的依賴窗口
//
// 【降低耦合】重構前，People 與 RouteFinder 會直接讀取 Simulator 的 static 欄位：
//   - Simulator.systemAwarenessTick   (People.stillUnaware)
//   - Simulator.allPeopleById         (People.waitForCompanion)
//   - Simulator.stageAssignCount++    (People.instinctiveEscapeStep / planNextAdvice)
//   - Simulator.detectors             (RouteFinder.gatherKnownHazards)
// 這是典型的「反向耦合」：本來應該是 Simulator 依賴/驅動 People，
// 結果變成 People 內部反過來認得 Simulator 這個具體類別，兩者互相牽制，
// 導致 People/RouteFinder 無法脫離 Simulator 被單獨測試或重用。
//
// 重構後，People 與 RouteFinder 只認得這個介面，Simulator 實作它並在建構
// People/RouteFinder 時把自己(this)注入進去。依賴方向變成：
//   People/RouteFinder --> SimulationContext <-- Simulator(實作者)
// 符合依賴反轉原則(DIP)，且未來若要抽換成別的模擬控制器(例如測試用的假物件)，
// 只需要提供另一個 SimulationContext 實作即可，People/RouteFinder 完全不用修改。
// ═══════════════════════════════════════════════════════════════════════════
interface SimulationContext {

    // 取代 Simulator.detectors：RouteFinder 蒐集「目前已知危險格」時需要的偵測器清單
    List<Detector> getDetectors();

    // 取代 Simulator.systemAwarenessTick != null && currentTick >= systemAwarenessTick：
    // 系統(感測器網路)是否已經在 currentTick(含)之前偵測到危險/損毀
    boolean isSystemAlertTriggered(int currentTick);

    // 取代 Simulator.allPeopleById.get(companionId)：依id查詢同行者目前狀態
    People findPersonById(int id);

    // 取代 Simulator.stageAssignCount++：通知控制器「這裡發生了一次跨樓層目標指派」，
    // 用於統計「改道成功率」的分母
    void onStageAssigned();

    // 【校正清單§9，新增】People.currentSpeed()查詢「同樓層、以(z,y,x)為中心、
    // 邊長(2×radiusBlocks+1)個block的正方形鄰域內，目前有幾個人(含自己)」，
    // 用來算局部人流密度，代入Fruin/SFPE的S=k1−k2·D公式抑制移動速度。
    int countNearbyPeopleOnFloor(int z, int y, int x, int radiusBlocks);

    // 【校正清單§5】原本這裡還有 smokeToleranceFactor()，把起火原因對煙霧毒性的
    // 影響直接乘進「個人」的CO致命閾值。校正後，起火原因改成只影響
    // EnvironmentSimulator生成CO(coPpm)的速率(見FireCause.representativeFuel)，
    // 不再需要跨People/Simulator邊界查詢，因此這個方法已移除。
}
