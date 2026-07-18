package simulator;

import java.util.List;
import java.util.Map;
import java.util.Random;

// ═══════════════════════════════════════════════════════════════════════════
// ScenarioInitializer — 一場模擬開始前的初始化：設定火源、建立偵測器網路、
//   配對同行者。三者都跟「怎麼跑一場模擬(run/spread/report)」無關，只是模擬
//   開始前的一次性場景佈置，因此從 Simulator 拆成獨立職責。
// ═══════════════════════════════════════════════════════════════════════════
class ScenarioInitializer {

    static void initFireSource(Space space, int z, int y, int x) {
        if (space.isValid(z, y, x)) {
            space.building[z][y][x].fire  = true;
            space.building[z][y][x].smoke = 0.5;
            // 【校正清單§2/§3】起火點在tick迴圈開始「之前」就已經點燃，
            // 這裡設ignitedAtTick=0，讓它的HRR成長曲線從模擬一開始
            // (tick從1起算)就已經有非零的「已經過經過時間」，跟其他
            // 後來才被延燒到的格子(ignitedAtTick=實際延燒發生的tick)一致對待。
            space.building[z][y][x].ignitedAtTick = 0;
            System.out.println("【系統】已於座標 (" + z + ", " + y + ", " + x + ") 設定初始火源！\n");
        }
    }

    static void initDetectors(Space space, List<Detector> detectors) {
        for (int z = 0; z < space.height; z++) {
            for (int y = 1; y < space.rows - 1; y += 3) {
                for (int x = 1; x < space.cols - 1; x += 3) {
                    if (!(space.building[z][y][x] instanceof Wall)) {
                        detectors.add(new Detector(z, y, x));
                    }
                }
            }
        }
    }

    // 把部分人隨機兩兩配對成同行者(家人/朋友一起行動)，並登記進不會被清空的名冊
    static void assignCompanions(List<People> list, Random random, Map<Integer, People> allPeopleById) {
        for (People p : list) allPeopleById.put(p.id, p);
        for (int i = 0; i + 1 < list.size(); i++) {
            People a = list.get(i), b = list.get(i + 1);
            if (a.companionId == null && b.companionId == null && random.nextDouble() < 0.35) {
                a.companionId = b.id;
                b.companionId = a.id;
            }
        }
    }
}
