package simulator;

// 每個人的模擬結果：實際發生的tick，以及最終「結局」(存活/死亡/仍受困)
// 這樣才能正確分辨「逃脫」跟「困在原地但還沒死」，不會把兩者混為一談
enum Outcome { ESCAPED, DEAD, TRAPPED }
