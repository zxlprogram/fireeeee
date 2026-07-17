package simulator;
//請至main函數調整模擬資訊，如果想要改參數請直接跟我說，除非你是屎山代碼終結者

class sim {
    public static void main(String[] args) {
        Simulator s = new Simulator();
        //輸入:建築類型數量、每個建築類型要跑幾組火源/人物場景、樓高範圍、樓寬範圍、樓長範圍
        //輸出:每個建築類型的小結報表、跨建築類型總表、過程文檔(會隨著模擬次數被覆蓋，只會看到最後一次模擬的文檔)
        //注意事項:時間複雜度超大，平均每個格子需要計算0.183324742*大樓體積^(4/3) 毫秒
        //         總場景數 = 建築類型數量 × 每個建築類型的場景數，請自行斟酌調整
        Data[] data = new Data[10];
        for (int i = 0; i < data.length; i++) {
            data[i] = new Data(new Range(1, 11), new Range(5, 51), new Range(5, 51));
        }
        s.work(data, 10);
    }
}
