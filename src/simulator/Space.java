package simulator;

// ─── 空間容器 ─────────────────────────────────────────────────
class Space {
    Obj[][][] building;
    int height, rows, cols;

    public Space(Obj[][][] building) {
        this.building = building;
        this.height   = building.length;
        this.rows     = building[0].length;
        this.cols     = building[0][0].length;
    }

    public boolean isValid(int z, int y, int x) {
        return z >= 0 && z < height && y >= 0 && y < rows && x >= 0 && x < cols;
    }

    // ─── §12 真實面積/體積計算基礎架構 ─────────────────────────────
    // 【重要】height*rows*cols只是「格數(cell count)」，不是真實體積(m³)！
    // 之前有些地方(例如Simulator.java量測時間複雜度)會用height*rows*cols當
    // 「體積」，那是格數體積，用來估算計算量沒問題，但不能拿去算CO稀釋、
    // 通風換氣量等物理量，否則單位完全對不上。這裡明確拆成兩組函式，避免
    // 之後有人搞混：
    //   - getBuildingVolumeCells()：格數體積，純粹是 height*rows*cols，
    //     沿用既有的用法(效能量測、格點計數等)，不代表真實立方公尺。
    //   - getFloorAreaM2() / getFloorVolumeM3() / getBuildingVolumeM3()：
    //     用BLOCK_METERS(平面)與FLOOR_HEIGHT_METERS/GROUND_FLOOR_HEIGHT_METERS
    //     (垂直)換算出的真實面積(m²)/體積(m³)，供之後接入VentilationProfile/
    //     FuelType等真實物理模型使用。目前只提供計算能力，尚未接入火災/CO
    //     模擬流程(EnvironmentSimulator.spread()不使用這裡的結果)。

    /** 格數體積(height*rows*cols)，非真實立方公尺，僅供效能量測/格點計數等既有用途使用。 */
    public long getBuildingVolumeCells() {
        return (long) height * rows * cols;
    }

    /** 單一樓層的真實樓地板面積(m²) = rows × cols × BLOCK_METERS²。 */
    public double getFloorAreaM2() {
        return (double) rows * cols
            * PhysicalConstants.BLOCK_METERS * PhysicalConstants.BLOCK_METERS;
    }

    /**
     * 單一樓層的真實體積(m³) = 樓地板面積 × 樓層淨高。
     * groundFloor=true時使用GROUND_FLOOR_HEIGHT_METERS(一樓挑高)，
     * 否則使用一般樓層淨高FLOOR_HEIGHT_METERS。
     */
    public double getFloorVolumeM3(boolean groundFloor) {
        double floorHeight = groundFloor
            ? PhysicalConstants.GROUND_FLOOR_HEIGHT_METERS
            : PhysicalConstants.FLOOR_HEIGHT_METERS;
        return getFloorAreaM2() * floorHeight;
    }

    /** 單一樓層的真實體積(m³)，預設當作一般樓層(非一樓挑高)。 */
    public double getFloorVolumeM3() {
        return getFloorVolumeM3(false);
    }

    /**
     * 【新增，校正清單§2/§3】單一「格」的真實體積(m³) = 一個block的平面面積
     * (BLOCK_METERS²) × 該樓層淨高。供EnvironmentSimulator把某一格生成的CO
     * 質量換算成濃度(ppm)時使用的「控制體積」——用格自己的體積而非整層樓
     * 體積，是因為模擬本來就用鄰格距離衰減的方式手動處理CO的空間擴散，
     * 若換算濃度時又用整層樓體積會等於把擴散计算了兩次。
     */
    public double getCellVolumeM3(int z) {
        boolean groundFloor = (z == 0);
        double floorHeight = groundFloor
            ? PhysicalConstants.GROUND_FLOOR_HEIGHT_METERS
            : PhysicalConstants.FLOOR_HEIGHT_METERS;
        return PhysicalConstants.BLOCK_METERS * PhysicalConstants.BLOCK_METERS * floorHeight;
    }

    /**
     * 整棟建築的真實總體積(m³) = 一樓體積(挑高) + 其餘樓層體積 × (height-1)。
     * height<=1時直接視為只有一樓(挑高)。
     */
    public double getBuildingVolumeM3() {
        if (height <= 1) {
            return getFloorVolumeM3(true);
        }
        return getFloorVolumeM3(true) + getFloorVolumeM3(false) * (height - 1);
    }
}
