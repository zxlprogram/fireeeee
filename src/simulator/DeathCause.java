package simulator;

// ─── 死因分類 ─────────────────────────────────────────────────
// People.checkStatus() 判定死亡時，同時記錄「怎麼死的」，讓報表能進一步
// 拆解死因比例(燒死 vs CO中毒致死)，而不是只有一個籠統的死亡率。
// NONE：尚未死亡(存活/逃脫/受困)時的預設值，不會出現在「死亡者」的統計分母裡。
enum DeathCause { FIRE, CO_POISONING, NONE }
