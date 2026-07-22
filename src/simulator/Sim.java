package simulator;

class sim {
    public static void main(String[] args) {
        Simulator s = new Simulator();
        //決定是否輸出json檔案(若樓型稍大，json檔案恐大於1GB，若樓型過大，恐觸發max heap exception)
        s.sessionOutput=false;
        //輸出:每個建築類型的小結報表、跨建築類型總表、過程文檔(會隨著模擬次數被覆蓋，只會看到最後一次模擬的文檔)
        //注意事項:時間複雜度超大，平均每個格子需要計算k*大樓體積^(4/3) 毫秒
        //         總場景數 = 建築數量 × 每個建築類型的場景數，請自行斟酌調整
        
        //建築數量
        Data[]data=new Data[40];
        for(int i=0;i<10;i++)
        	//生成範圍(高寬長)，失火原因
        	data[i]=new Data(new Range(10,11),new Range(50,51),new Range(50,51),FireCause.ACCIDENTAL);
        for(int i=10;i<20;i++)
        	data[i]=new Data(new Range(10,11),new Range(50,51),new Range(50,51),FireCause.ARSON);
        for(int i=20;i<30;i++)
        	data[i]=new Data(new Range(10,11),new Range(50,51),new Range(50,51),FireCause.CHEMICAL);
        for(int i=30;i<40;i++)
        	data[i]=new Data(new Range(10,11),new Range(50,51),new Range(50,51),FireCause.ELECTRICAL);
        
        //第二個int參數為同一建築的模擬次數
        s.work(data,1);
        
        //workCustomRoom可自定義樓型
    }
}

/*
總結物理化成果:
1.ASET爆表，大程度是因為防火門
2.hybrid不見得比smart好，因為防火門遮蔽視線(至少目前觀察到hybrid在意外火源的表現較好、smart在縱火案表現較好、化學失火中，smart表現高於hybrid)
*/