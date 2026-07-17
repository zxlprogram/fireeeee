package simulator;

// ─── 基礎物件 ────────────────────────────────────────────────
class Obj {
    boolean fire;
    double smoke = 0.0; // 煙霧數值 (0.0 ~ 1.0)：有害、觸發逃生判斷用的濃煙
    double smell = 0.0; // 煙味數值 (0.0 ~ 1.0)：無害，純粹用來觸發People的「察覺」，
                         // 比smoke更容易穿透門縫(見EnvironmentSimulator頂端的擴散機率常數)
}

class Door extends Obj {
    boolean blocked = false; // true 代表已被火封住
}

class FireDoor extends Door {
    // 防火門若沒有確實關好(被卡住/未關緊)，就會失去阻絕火勢延燒的效果，
    // 行為上會退化成跟普通門一樣：擋不住火，也可能被燒到卡死(blocked)。
    boolean isOpen = false;
}

class Exit extends Obj {}
class Wall extends Obj {}
class Floor extends Obj {}

class Stage extends Obj {
    Stage upfloor;
    Stage downfloor;
    int x, y, z;

    public void setLocation(int z, int y, int x) {
        this.z = z; this.y = y; this.x = x;
    }
}

//─── 人員屬性列舉 ──────────────────────────────────────────────
enum PersonProfile {
    NORMAL_SOLO,     // 一般成年人，單獨行動
    WITH_CHILD,      // 成年人，攜帶幼童
    IMPAIRED,        // 行動不便者
    ELDERLY,         // 年長者
    STAFF,           // 對場域熟悉的員工
    CUSTOMER         // 對場域完全陌生的顧客
}
