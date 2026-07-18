package simulator;

class Data {
    Range H, R, C;
    FireCause cause;
    public Data(Range H, Range R, Range C,FireCause cause) {
        this.H = H;
        this.R = R;
        this.C = C;
        this.cause=cause;
    }
}
