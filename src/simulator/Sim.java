package simulator;
//請至main函數調整模擬資訊，如果想要改參數請直接跟我說，除非你是屎山代碼終結者

class sim {
    public static void main(String[] args) {
        Simulator s = new Simulator();
        //輸入:建築類型數量、每個建築類型要跑幾組火源/人物場景、樓高範圍、樓寬範圍、樓長範圍
        //輸出:每個建築類型的小結報表、跨建築類型總表、過程文檔(會隨著模擬次數被覆蓋，只會看到最後一次模擬的文檔)
        //注意事項:時間複雜度超大，平均每個格子需要計算k*大樓體積^(4/3) 毫秒
        //         總場景數 = 建築類型數量 × 每個建築類型的場景數，請自行斟酌調整
        String[][][] layout = CustomRoomTextLoader.loadFromFile("C:\\Users\\user\\Desktop\\personal work\\火災救災智慧系統量化模擬器\\src\\simulator\\custom_room_example.txt");
        s.workCustomRoom(layout, FireCause.ACCIDENTAL, 5); // 用這個5層房型重抽5組起火點/人物場景
    }
}

/*
總結物理化成果:
1.ASET爆表，大程度是因為防火門
2.hybrid不見得比smart好，因為防火門遮蔽視線(至少目前觀察到hybrid在意外火源的表現較好、smart在縱火案表現較好、化學失火中，smart表現高於hybrid)
*/