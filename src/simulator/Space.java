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
}
