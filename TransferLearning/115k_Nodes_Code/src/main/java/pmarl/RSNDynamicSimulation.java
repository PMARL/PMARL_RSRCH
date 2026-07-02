import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * RSN Dynamic Prize Simulation — BC-TSP paper, Section V-C.
 *
 * Compares P-MARL (reset Q-table each round) vs Transfer Learning (TL,
 * Algorithm 4) over 10 consecutive rounds where 10 of 100 sensor nodes
 * randomly change their data-packet counts each round.
 *
 * Design
 * ──────
 *  Field   : 10 000 × 10 000 m, 100 sensor nodes
 *  Layout  : Round-trip — depot node 0 AND node NN-1 both sit at (0,0).
 *            Zero baseline travel cost so the full budget is available for
 *            sensor collection (matches Fig. 13(b) RSN setting).
 *  Battery : 0.5 / 1.0 / 1.5 / 2.0 KWh → budget(m) = KWh × 3 600 000 / 102
 *  Params  : α=0.1, γ=0.6, q0=0.5, δ=1, β=2, W=10 000,
 *            EPI_MAX=1 000, STAG=100, M=30
 *
 * Per-round protocol
 * ──────────────────
 *  Round 0 : Both P-MARL and TL start from a cold Q-table (identical).
 *  Round j>0:
 *    P-MARL : reset Q/R from scratch → runMARL → record gBestPrize + time.
 *    TL     : restore Q/R from round j-1's trained tables
 *             → apply Algo-4 local-search (transfer+reset, no hard zeros)
 *             → runMARL from the warm Q → record gBestPrize + time.
 *
 * Why TL wins both metrics
 * ────────────────────────
 *  Time  : Warm-started Q is already near-optimal; MARL stagnates in far fewer
 *          episodes → early-stop → much less wall time.
 *  Prize : Warm-start avoids local optima that a cold start gets trapped in;
 *          Algo-4 explicitly redirects Q towards nodes whose prizes rose.
 *
 * Algorithm 4 (non-destructive variant for round-trip RSN)
 * ─────────────────────────────────────────────────────────
 *  For each node B on the previous best path whose prize decreased:
 *    Search neighbourhood BN(B, R_NEIGH=1 500 m) for replacement D with
 *    newData[D] > newData[B] that fits within the budget slack.
 *    Transfer : Q[A][D] ← Q[A][B];   Q[D][C] ← Q[B][C]
 *    Reset    : Q[A][B] ← qInit(A,B); Q[B][C] ← qInit(B,C)
 *    (Redirects agents from B to D; no hard zeros that would lose edge info.)
 *    Likewise for R_tab.
 *  Because s=d=depot, no directional reversal is needed — only the
 *  prize-adaptive local-search from Algorithm 4 is applied.
 *
 * Output : console table + results/rsn_dynamic_prizes_<ts>.{txt,csv}
 */
public class RSNDynamicSimulation {

    // ── RSN constants ─────────────────────────────────────────────────────────
    static final int    N_SENSORS = 100;
    static final int    FIELD     = 10_000;
    static final double J_PER_M   = 600.0 * 0.17;   // 102 J/m

    // ── Paper §VI-B hyper-parameters ─────────────────────────────────────────
    static final double ALPHA   = 0.1;
    static final double GAMMA   = 0.6;
    static final double Q0      = 0.5;
    static final double DELTA   = 1.0;
    static final double BETA    = 2.0;
    static final double W_CONST = 10_000.0;
    static final int    EPI_MAX = 1_000;
    static final int    STAG    = 100;
    static final int    M       = 30;

    // ── Experiment parameters ─────────────────────────────────────────────────
    static final int      ROUNDS   = 10;
    static final int      CHANGES  = 10;
    static final int      RUNS     = 10;
    static final double   R_NEIGH  = 1_500.0;
    static final double[] BATT_KWH = {0.5, 1.0, 1.5, 2.0};
    /**
     * Warm-start cap multiplier for TL.
     * After training, path-edge Q values can be 10 000–50 000× larger than
     * fresh off-path Q values, making exploration essentially impossible and
     * locking TL onto the old path even when better alternatives exist.
     * Capping Q_warm ≤ K_WARM × qInit gives path edges a K_WARM-fold
     * head-start in PAM while keeping off-path exploration meaningful.
     */
    static final double   K_WARM   = 5.0;

    // ── Graph ─────────────────────────────────────────────────────────────────
    //  NN = N_SENSORS + 2.
    //  Node 0 = node NN-1 = depot at (0,0).  D[0][NN-1] = 0.
    static int       NN;
    static double[]  X, Y;
    static double[][] D;

    // ── Shared Q / R tables (mutated by runMARL / applyAlgo4) ────────────────
    static double[][] Q_tab, R_tab;

    // ── Written by runMARL ────────────────────────────────────────────────────
    static int           gBestPrize;
    static double        gBestDist;
    static List<Integer> gBestPath;

    static final Random RNG = new Random(42);

    // ═════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws IOException {
        File dir = new File("results");
        dir.mkdirs();
        String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String base = "rsn_dynamic_prizes_" + ts;

        PrintStream orig = System.out;
        PrintStream flog = new PrintStream(new FileOutputStream(new File(dir, base + ".txt")));
        System.setOut(new PrintStream(new OutputStream() {
            public void write(int b) throws IOException { orig.write(b); flog.write(b); }
            public void write(byte[] b, int o, int l) throws IOException {
                orig.write(b, o, l); flog.write(b, o, l); }
        }));

        PrintWriter csv = new PrintWriter(new FileWriter(new File(dir, base + ".csv")));
        csv.println("battery_kwh,round,pmarl_packets,tl_packets,pmarl_ms,tl_ms");

        System.out.println("=== RSN Dynamic Prize Simulation (Section V-C) ===");
        System.out.printf("Nodes=%d  Field=%dx%d m  Rounds=%d  Changes/round=%d  Runs=%d%n",
                N_SENSORS, FIELD, FIELD, ROUNDS, CHANGES, RUNS);
        System.out.printf("α=%.1f  γ=%.1f  q0=%.1f  δ=%.0f  β=%.0f  " +
                "W=%.0f  EPI_MAX=%d  STAG=%d  M=%d%n%n",
                ALPHA, GAMMA, Q0, DELTA, BETA, W_CONST, EPI_MAX, STAG, M);

        for (double battKwh : BATT_KWH) {
            double budget = battKwh * 3_600_000.0 / J_PER_M;
            System.out.printf("══════  Battery=%.1f KWh  Budget=%.0f m  ══════%n",
                    battKwh, budget);

            double[][] sumPrize = new double[ROUNDS][2];
            double[][] sumTime  = new double[ROUNDS][2];

            for (int run = 0; run < RUNS; run++) {
                runOnce(budget, sumPrize, sumTime);
                System.out.printf("  Run %2d/%d done%n", run + 1, RUNS);
            }

            System.out.printf("%n  %-6s  %13s  %13s  %15s  %15s%n",
                    "Round", "PMARL pkts", "TL pkts", "PMARL ms", "TL ms");
            System.out.println("  " + "─".repeat(67));
            for (int r = 0; r < ROUNDS; r++) {
                double pp = sumPrize[r][0] / RUNS, tp = sumPrize[r][1] / RUNS;
                double pt = sumTime[r][0]  / RUNS, tt = sumTime[r][1]  / RUNS;
                System.out.printf("  %-6d  %13.1f  %13.1f  %15.1f  %15.1f%n",
                        r+1, pp, tp, pt, tt);
                csv.printf("%.1f,%d,%.2f,%.2f,%.2f,%.2f%n",
                        battKwh, r+1, pp, tp, pt, tt);
            }
            System.out.println();
        }

        csv.flush(); csv.close();
        flog.flush(); flog.close();
        System.setOut(orig);
        System.out.println("Done. Results in results/" + base + ".{txt,csv}");
    }

    // ── One independent RSN realisation ──────────────────────────────────────
    static void runOnce(double budget, double[][] sumPrize, double[][] sumTime) {
        generateRSN();
        int[] data = new int[NN];
        initDataPackets(data);

        double[][] tlQ       = null;
        List<Integer> tlPath = null;
        double tlRemBudget   = 0;

        for (int round = 0; round < ROUNDS; round++) {
            int[] prevData = data.clone();          // snapshot BEFORE change
            if (round > 0) changeDataPackets(data, CHANGES);

            // ── P-MARL: cold start every round ───────────────────────────────
            initQR(data);
            long t0 = System.nanoTime();
            runMARL(data, budget);
            long t1 = System.nanoTime();
            sumPrize[round][0] += gBestPrize;
            sumTime[round][0]  += (t1 - t0) / 1_000_000.0;

            // ── TL: warm Q (previous round) + FRESH R + Algorithm 4 ──────────
            // KEY: R_tab is reset fresh each round so accumulated R values from
            // previous rounds cannot lock TL onto the old path structure.
            // Q_tab is carried forward to give agents a warm-start near the
            // previous optimum, which is still near-optimal for the current
            // round (only 10% of prizes changed).
            if (round == 0) {
                // No prior model — TL equals P-MARL in the first round
                sumPrize[round][1] += gBestPrize;
                sumTime[round][1]  += (t1 - t0) / 1_000_000.0;
            } else {
                Q_tab = deepCopy(tlQ);  // warm Q from previous round
                initR(data);            // FRESH R — no cross-round accumulation
                // Selective warm-start:
                //   Path edges → min(Q_trained, K_WARM × qInit_new): retains path
                //     guidance while preventing 50 000× dominance.
                //   Off-path edges → 1× qInit_new: resets to current prizes so nodes
                //     whose prizes INCREASED this round are represented at their
                //     new qInit (same quality as P-MARL cold start for those nodes).
                boolean[][] pathEdge = new boolean[NN][NN];
                for (int k = 0; k < tlPath.size() - 1; k++) {
                    int u = tlPath.get(k), v = tlPath.get(k + 1);
                    pathEdge[u][v] = true;
                }
                for (int i = 0; i < NN; i++)
                    for (int j = 0; j < NN; j++) {
                        double qi = qInit(i, j, data);
                        Q_tab[i][j] = pathEdge[i][j]
                                ? Math.min(Q_tab[i][j], K_WARM * qi)
                                : qi;
                    }
                long s0 = System.nanoTime();
                applyAlgo4(tlPath, prevData, data, tlRemBudget);
                runMARL(data, budget);
                long s1 = System.nanoTime();
                sumPrize[round][1] += gBestPrize;
                sumTime[round][1]  += (s1 - s0) / 1_000_000.0;
            }

            // Save TL carry-over: Q only (R is always re-initialised fresh)
            tlQ = deepCopy(Q_tab);
            tlPath = new ArrayList<>(gBestPath);
            tlRemBudget = budget - gBestDist;
        }
    }

    // ── RSN generation ────────────────────────────────────────────────────────
    static void generateRSN() {
        NN = N_SENSORS + 2;
        X  = new double[NN];
        Y  = new double[NN];
        // Node 0 and NN-1 are both the depot at (0,0) → round-trip, D[0][NN-1]=0
        X[0] = Y[0] = X[NN-1] = Y[NN-1] = 0;
        for (int i = 1; i <= N_SENSORS; i++) {
            X[i] = RNG.nextDouble() * FIELD;
            Y[i] = RNG.nextDouble() * FIELD;
        }
        D = new double[NN][NN];
        for (int i = 0; i < NN; i++)
            for (int j = 0; j < NN; j++) {
                double dx = X[i]-X[j], dy = Y[i]-Y[j];
                D[i][j] = Math.sqrt(dx*dx + dy*dy);
            }
    }

    // ── Prize management ──────────────────────────────────────────────────────
    static void initDataPackets(int[] data) {
        data[0] = data[NN-1] = 0;
        for (int i = 1; i <= N_SENSORS; i++) data[i] = 1 + RNG.nextInt(100);
    }

    static void changeDataPackets(int[] data, int k) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 1; i <= N_SENSORS; i++) idx.add(i);
        Collections.shuffle(idx, RNG);
        for (int m = 0; m < k; m++) data[idx.get(m)] = 1 + RNG.nextInt(100);
    }

    // ── Q / R cold initialisation ────────────────────────────────────────────
    static void initQR(int[] data) {
        Q_tab = new double[NN][NN];
        R_tab = new double[NN][NN];
        for (int i = 0; i < NN; i++)
            for (int j = 0; j < NN; j++) {
                R_tab[i][j] = data[j];
                Q_tab[i][j] = (D[i][j] > 0) ? (data[i] + data[j]) / D[i][j] : 0.0;
            }
    }

    static double qInit(int i, int j, int[] data) {
        return (D[i][j] > 0) ? (data[i] + data[j]) / D[i][j] : 0.0;
    }

    static void initR(int[] data) {
        R_tab = new double[NN][NN];
        for (int i = 0; i < NN; i++)
            for (int j = 0; j < NN; j++)
                R_tab[i][j] = data[j];
    }

    // ── Algorithm 4: non-destructive TL warm-start ───────────────────────────
    static void applyAlgo4(List<Integer> prevPath, int[] prevData,
                           int[] newData, double remBudget) {
        List<Integer> path   = new ArrayList<>(prevPath);
        Set<Integer>  inPath = new HashSet<>(path);
        double remB = remBudget;

        for (int idx = 1; idx < path.size() - 1; idx++) {
            int A = path.get(idx - 1);
            int B = path.get(idx);
            int C = path.get(idx + 1);

            if (prevData[B] <= newData[B]) continue;  // prize did not decrease

            // Sorted candidate replacements in BN(B, R_NEIGH)
            List<int[]> cands = new ArrayList<>();
            for (int dd = 1; dd <= N_SENSORS; dd++) {
                if (!inPath.contains(dd) && D[B][dd] <= R_NEIGH
                        && newData[dd] > newData[B])
                    cands.add(new int[]{dd, newData[dd]});
            }
            cands.sort((x, y) -> Integer.compare(y[1], x[1]));

            for (int[] cand : cands) {
                int    DD    = cand[0];
                double delta = D[A][DD] + D[DD][C] - D[A][B] - D[B][C];
                if (delta < remB) {
                    remB -= delta;

                    // Give DD the path-edge warm-start advantage (K_WARM × qInit)
                    // based on DD's own prize/distance, not B's (which decreased).
                    Q_tab[A][DD] = K_WARM * qInit(A, DD, newData);
                    Q_tab[DD][C] = K_WARM * qInit(DD, C, newData);

                    // Deprioritise B: reset to 1× qInit (no warm-start for B)
                    Q_tab[A][B] = qInit(A, B, newData);
                    Q_tab[B][C] = qInit(B, C, newData);
                    // R_tab is always fresh (initR called before applyAlgo4)

                    inPath.remove(B); inPath.add(DD);
                    path.set(idx, DD);
                    break;
                }
            }
        }
    }

    // ── P-MARL (Algorithm 3): IL + CL with early stopping ────────────────────
    static void runMARL(int[] data, double budget) {
        gBestPrize = Integer.MIN_VALUE;
        gBestPath  = new ArrayList<>();
        gBestDist  = 0;
        int noImprove = 0;

        for (int ep = 0; ep < EPI_MAX; ep++) {

            boolean[][] vis   = new boolean[M][NN];
            double[]    spent = new double[M];
            int[]       cur   = new int[M];
            int[]       prize = new int[M];
            boolean[]   done  = new boolean[M];
            @SuppressWarnings("unchecked")
            List<Integer>[] paths = new List[M];
            for (int a = 0; a < M; a++) {
                vis[a][0] = true;
                cur[a]    = 0;
                paths[a]  = new ArrayList<>(Collections.singletonList(0));
            }

            // Independent Learning (Eq. 11 — no reward term)
            while (notAllDone(done)) {
                for (int a = 0; a < M; a++) {
                    if (done[a]) continue;
                    int next = pamSelect(cur[a], budget, spent[a], vis[a], data);
                    vis[a][next] = true;
                    double mq = maxFeasibleQ(next, budget, spent[a] + D[cur[a]][next], vis[a]);
                    Q_tab[cur[a]][next] =
                            (1 - ALPHA) * Q_tab[cur[a]][next] + ALPHA * GAMMA * mq;
                    paths[a].add(next);
                    spent[a] += D[cur[a]][next];
                    if (next != NN - 1) prize[a] += data[next];
                    else                done[a]   = true;
                    cur[a] = next;
                }
            }

            // Cooperative Learning (Eqs. 12–13)
            int jStar = 0;
            for (int a = 1; a < M; a++) if (prize[a] > prize[jStar]) jStar = a;
            List<Integer> p = paths[jStar];
            int jPrize = prize[jStar]; double jDist = spent[jStar];
            for (int v = 0; v < p.size() - 1; v++) {
                int u = p.get(v), w = p.get(v + 1);
                R_tab[u][w] += W_CONST / Math.max(jPrize, 1);
                Q_tab[u][w]  = (1 - ALPHA) * Q_tab[u][w]
                        + ALPHA * (R_tab[u][w] + GAMMA * maxQAll(w));
            }

            if (jPrize > gBestPrize) {
                gBestPrize = jPrize; gBestPath = new ArrayList<>(p);
                gBestDist  = jDist;  noImprove = 0;
            } else {
                if (++noImprove >= STAG) break;
            }
        }
    }

    // ── PAM — prize-based action mechanism ───────────────────────────────────
    static int pamSelect(int cur, double budget, double spent,
                         boolean[] vis, int[] data) {
        List<Integer> feas = feasible(cur, budget, spent, vis);
        if (feas.isEmpty()) return NN - 1;

        if (RNG.nextDouble() <= Q0) {
            // Exploitation
            int best = feas.get(0); double bv = Double.NEGATIVE_INFINITY;
            for (int u : feas) {
                double v = Math.pow(Math.max(Q_tab[cur][u], 1e-12), DELTA)
                         * data[u]
                         / Math.pow(Math.max(D[cur][u], 1.0), BETA);
                if (v > bv) { bv = v; best = u; }
            }
            return best;
        } else {
            // Proportional exploration (Eq. 10)
            double[] score = new double[feas.size()]; double total = 0;
            for (int i = 0; i < feas.size(); i++) {
                int u = feas.get(i);
                score[i] = Math.pow(Math.max(Q_tab[cur][u], 1e-12), DELTA)
                         * data[u]
                         / Math.pow(Math.max(D[cur][u], 1.0), BETA);
                total += score[i];
            }
            if (total <= 0) return feas.get(RNG.nextInt(feas.size()));
            double r = RNG.nextDouble() * total;
            for (int i = 0; i < score.length; i++) { r -= score[i]; if (r <= 0) return feas.get(i); }
            return feas.get(feas.size() - 1);
        }
    }

    // ── Feasible next nodes (budget-pruned) ───────────────────────────────────
    static List<Integer> feasible(int cur, double budget, double spent, boolean[] vis) {
        List<Integer> f = new ArrayList<>();
        double remB = budget - spent;
        for (int j = 1; j <= N_SENSORS; j++)
            if (!vis[j] && D[cur][j] + D[j][NN-1] <= remB) f.add(j);
        return f;
    }

    // ── Q helpers ─────────────────────────────────────────────────────────────
    static double maxFeasibleQ(int s, double budget, double spent, boolean[] vis) {
        double max = 0, remB = budget - spent;
        for (int j = 1; j <= N_SENSORS; j++)
            if (!vis[j] && D[s][j] + D[j][NN-1] <= remB && Q_tab[s][j] > max)
                max = Q_tab[s][j];
        return max;
    }

    static double maxQAll(int s) {
        double max = 0;
        for (int j = 0; j < NN; j++) if (Q_tab[s][j] > max) max = Q_tab[s][j];
        return max;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    static boolean notAllDone(boolean[] done) {
        for (boolean b : done) if (!b) return true;
        return false;
    }

    static double[][] deepCopy(double[][] m) {
        if (m == null) return null;
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) c[i] = m[i].clone();
        return c;
    }
}
