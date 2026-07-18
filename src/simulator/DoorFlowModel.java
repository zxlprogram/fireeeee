package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// DoorFlowModel — 校正清單 §1 / §12：門與出口的通過流量上限(瓶頸)。
//
//   重構前的模型完全沒有「人與人互相排隊」的機制：人可以無限重疊站在同一格，
//   門/出口沒有通過流量上限，因此「出口/門的瓶頸排隊現象完全沒有被模擬」
//   (校正清單§1)。這裡用最小改動的方式補上一個近似：
//     - 每個Door/Exit格，每tick最多放行 PhysicalConstants.DOOR_CAPACITY_PER_TICK 人
//     - 容量在每個tick開頭重置(resetTick)
//     - 有人實際移動進入該格時才消耗一個名額(consume)
//     - 名額耗盡的門/出口這個tick會被RouteFinder.isPassable()視為不可通行，
//       逼真人必須繞道或原地等待，下一tick容量重置後才能再嘗試
//
//   這不是完整的行人流體力學/水力模型(Predtechenskii & Milinskii)，沒有模擬
//   連續流量、也沒有模擬人流密度對速度的抑制(§1另一項待補參數)，但至少讓
//   「門越窄/人越多，越容易堵住」這個方向性的現象在模擬裡真的會發生。
// ═══════════════════════════════════════════════════════════════════════════
class DoorFlowModel {

    static void resetTick(Space space) {
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj o = space.building[z][y][x];
                    if (o instanceof Door || o instanceof Exit) {
                        o.doorFlowRemaining = PhysicalConstants.DOOR_CAPACITY_PER_TICK;
                    }
                }
            }
        }
    }

    // 這一格是否還有剩餘通行容量；非Door/Exit的格型不受此限制，永遠回傳true
    static boolean hasCapacity(Obj o) {
        if (o instanceof Door || o instanceof Exit) {
            return o.doorFlowRemaining > 0;
        }
        return true;
    }

    // 有人實際移動進入這一格時呼叫，消耗一個名額(僅Door/Exit會被扣)
    static void consume(Obj o) {
        if ((o instanceof Door || o instanceof Exit) && o.doorFlowRemaining > 0) {
            o.doorFlowRemaining--;
        }
    }
}
