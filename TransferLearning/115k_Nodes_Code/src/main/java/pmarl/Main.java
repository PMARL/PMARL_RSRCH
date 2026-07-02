import java.io.*;
import java.util.*;

/**
 * Entry point for `mvn exec:java`.
 *
 * Reads MAX_NODES cities from city_rewards.csv (classpath resource), builds
 * Euclidean coordinates, then runs four algorithms for RUNS independent
 * cold-start experiments: Greedy1, Greedy2, P-MARL, and AntQ.
 *
 * Distance is computed on the fly (no D[][] matrix) so the code scales to
 * 115 K+ cities without exhausting memory.  Q/R/pheromone tables are indexed
 * by candidate-list slot [NN][candSz+1] instead of [NN][NN]; slot candSz is
 * the depot (NN-1).  Cities reached via the full-scan fallback in pamSelect
 * are excluded from Q/R updates (they are rare and have Q≈0 anyway).
 */
public class Main {

    // ── Constants used by AntQ.java ───────────────────────────────────────────
    static final int UNVISITED  = 0;
    static final int VISITED    = 1;
    static final int LAST_VISIT = 2;

    // ── Configuration ─────────────────────────────────────────────────────────
    static final int    MAX_NODES = 115_475;
    static final double BUDGET    = 3_100_000.0;
    static final int    RUNS      = 10;

    // ── P-MARL hyperparameters ────────────────────────────────────────────────
    static final int    TRIALS           = 20_000;
    static final int    M                = 5;
    static double W_CONST;
    static final double ALPHA            = 0.2;
    static final double GAMMA            = 0.35;
    static final double DELTA            = 1.0;
    static final double BETA             = 2.0;
    static final double Q0               = 0.75;
    static final int    STAGNATION_LIMIT = 1_500;
    static final int    CAND_SIZE        = 100;

    // ── AntQ hyperparameters ──────────────────────────────────────────────────
    static final int    AQ_TRIALS     = 15_000;
    static final int    AQ_STAGNATION = 100;
    static final int    AQ_ANTS       = 5;
    static final double AQ_RHO        = 0.1;
    static final double AQ_PHI        = 1.0;
    static final double AQ_ETA_POWER  = 2.0;
    static final double AQ_Q0         = 0.7;
    static final double AQ_TAU_INIT   = 1.0;

    static final Random RNG    = new Random(42);
    static Random AQ_RNG = new Random(42);

    static int runStartNode = 0;

    // ── Graph ─────────────────────────────────────────────────────────────────
    // Node layout: 0 = depot-start | 1..N_S = cities | NN-1 = depot-end
    // X[NN-1], Y[NN-1] are updated each run to match runStartNode.
    static int      NN;
    static int      N_S;
    static double[] X, Y;
    static int[]    prize;

    // ── Shared Q / R tables (reset each run by P-MARL) ───────────────────────
    // Indexed by candidate slot: Q_tab[i][k] = Q for edge i→candList[i][k].
    // Slot candSz (= candList[0].length) is reserved for the depot (NN-1).
    static double[][] Q_tab, R_tab;
    static int        candSz;   // set after buildCandidateList()

    // ── Candidate list + scratch buffers ─────────────────────────────────────
    static int[][]   candList;
    static double[]  distToDepot;
    static int[]     feasBuf;    // scratch: city indices for pamSelect
    static int[]     feasSlots;  // scratch: candidate slots parallel to feasBuf
    static double[]  scoreBuf;

    // ── Written by runMARL ────────────────────────────────────────────────────
    static int           gBestPrize;
    static double        gBestDist;
    static List<Integer> gBestPath;

    // ── AntQ pheromone state (reset each run) ─────────────────────────────────
    // Same candidate-slot layout as Q_tab.
    static double[][] aqTau;
    static int[][]    aqTauLastEp;
    static int        aqCurEp;

    // Slot chosen by the most recent pamSelect call (avoids re-lookup in runMARL)
    static int pamNextSlot = -1;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws IOException {
        loadCSV();
        distToDepot = new double[NN];
        buildCandidateList();
        candSz = candList[0].length;

        feasBuf   = new int[N_S + 1];
        feasSlots = new int[N_S + 1];
        scoreBuf  = new double[N_S + 1];

        long prizeSum = 0;
        for (int i = 1; i < NN - 1; i++) prizeSum += prize[i];
        W_CONST = prizeSum * 0.005;

        System.out.printf(
            "=== BC-PC-TSP  source=city_rewards.csv  nodes=%d  budget=%.0f  runs=%d  W=%.0f ===%n%n",
            N_S, BUDGET, RUNS, W_CONST);
        System.out.printf("  %-9s  %10s  %12s  %12s  %9s%n",
            "Algorithm", "Rewards", "Dist", "Remaining", "Time(ms)");
        System.out.println("  " + "─".repeat(65));

        double[] g1Prize = new double[RUNS], g1Dist = new double[RUNS], g1Time = new double[RUNS];
        double[] g2Prize = new double[RUNS], g2Dist = new double[RUNS], g2Time = new double[RUNS];
        double[] pPrize  = new double[RUNS], pDist  = new double[RUNS];
        double[] aPrize  = new double[RUNS], aDist  = new double[RUNS];
        double[] pTime   = new double[RUNS], aTime  = new double[RUNS];

        for (int run = 1; run <= RUNS; run++) {
            runStartNode = 1 + RNG.nextInt(N_S);
            // Move depot-end to the new start location; dist(runStartNode, NN-1) becomes 0
            X[NN - 1] = X[runStartNode];
            Y[NN - 1] = Y[runStartNode];
            for (int j = 0; j < NN; j++) distToDepot[j] = dist(j, NN - 1);
            distToDepot[runStartNode] = 0.0;

            System.out.printf("%n  ── Run %2d/%d  (start city=%d) ──%n", run, RUNS, runStartNode);

            long tg1s = System.nanoTime();
            double[] g1 = runGreedy1();
            long tg1e = System.nanoTime();
            g1Prize[run-1] = g1[0]; g1Dist[run-1] = g1[1];
            g1Time[run-1]  = (tg1e - tg1s) / 1_000_000.0;
            System.out.printf("  %-9s  %10.0f  %12.2f  %12.2f  %9.0f%n",
                "Greedy1", g1[0], g1[1], BUDGET - g1[1], g1Time[run-1]);

            long tg2s = System.nanoTime();
            double[] g2 = runGreedy2();
            long tg2e = System.nanoTime();
            g2Prize[run-1] = g2[0]; g2Dist[run-1] = g2[1];
            g2Time[run-1]  = (tg2e - tg2s) / 1_000_000.0;
            System.out.printf("  %-9s  %10.0f  %12.2f  %12.2f  %9.0f%n",
                "Greedy2", g2[0], g2[1], BUDGET - g2[1], g2Time[run-1]);

            initQR();
            long t0 = System.nanoTime();
            runMARL();
            long t1 = System.nanoTime();
            pPrize[run-1] = gBestPrize; pDist[run-1] = gBestDist;
            pTime[run-1]  = (t1 - t0) / 1_000_000.0;
            System.out.printf("  %-9s  %10d  %12.2f  %12.2f  %9.0f%n",
                "PMARL", gBestPrize, gBestDist, BUDGET - gBestDist, pTime[run-1]);

            long t2 = System.nanoTime();
            double[] aq = runAntQ(42L + run);
            long t3 = System.nanoTime();
            aPrize[run-1] = aq[0]; aDist[run-1] = aq[1];
            aTime[run-1]  = (t3 - t2) / 1_000_000.0;
            System.out.printf("  %-9s  %10.0f  %12.2f  %12.2f  %9.0f%n",
                "AntQ", aq[0], aq[1], BUDGET - aq[1], aTime[run-1]);
        }

        printSummary(g1Prize, g1Dist, g1Time, g2Prize, g2Dist, g2Time,
                     pPrize, pDist, pTime, aPrize, aDist, aTime);
    }

    // ── On-the-fly Euclidean distance ─────────────────────────────────────────
    static double dist(int i, int j) {
        double dx = X[i] - X[j], dy = Y[i] - Y[j];
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ── Candidate-slot lookup: O(candSz); only called during cooperative update ─
    // Returns candSz for the depot (NN-1), -1 if to is not a candidate of from.
    static int findSlot(int from, int to) {
        if (to == NN - 1) return candSz;
        int[] cands = candList[from];
        for (int k = 0; k < cands.length; k++) if (cands[k] == to) return k;
        return -1;
    }

    // ── Summary table ─────────────────────────────────────────────────────────

    static void printSummary(
            double[] g1Prize, double[] g1Dist, double[] g1Time,
            double[] g2Prize, double[] g2Dist, double[] g2Time,
            double[] pPrize,  double[] pDist,  double[] pTime,
            double[] aPrize,  double[] aDist,  double[] aTime) {

        System.out.printf("%n%s%n", "═".repeat(84));
        System.out.printf("  Summary over %d runs   (95%% CI = mean ± 1.96·σ/√n)%n", RUNS);
        System.out.println("─".repeat(84));
        System.out.printf("  %-9s  %13s  %10s    %14s  %14s    %10s%n",
            "Algorithm", "MeanRewards", "±CI(95%)", "MeanDist", "±CI(95%)", "MeanTime(ms)");
        System.out.println("─".repeat(84));
        printAlgoStats("Greedy1", g1Prize, g1Dist, g1Time);
        printAlgoStats("Greedy2", g2Prize, g2Dist, g2Time);
        printAlgoStats("PMARL",   pPrize,  pDist,  pTime);
        printAlgoStats("AntQ",    aPrize,  aDist,  aTime);
        System.out.println("═".repeat(84));
    }

    static void printAlgoStats(String name, double[] prizes, double[] dists, double[] times) {
        double mp  = mean(prizes), sp = stddev(prizes, mp);
        double md  = mean(dists),  sd = stddev(dists,  md);
        double ciP = 1.96 * sp / Math.sqrt(RUNS);
        double ciD = 1.96 * sd / Math.sqrt(RUNS);
        if (times != null) {
            double mt  = mean(times), st = stddev(times, mt);
            double ciT = 1.96 * st / Math.sqrt(RUNS);
            System.out.printf("  %-9s  %13.1f  %+10.2f    %14.2f  %+14.2f    %10.1f ±%.1f%n",
                name, mp, ciP, md, ciD, mt, ciT);
        } else {
            System.out.printf("  %-9s  %13.1f  %+10.2f    %14.2f  %+14.2f    %10s%n",
                name, mp, ciP, md, ciD, "n/a");
        }
    }

    static double mean(double[] a) {
        double s = 0; for (double v : a) s += v; return s / a.length;
    }

    static double stddev(double[] a, double m) {
        if (a.length < 2) return 0;
        double s = 0; for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / (a.length - 1));
    }

    // ── Greedy 1: visit cities in prize-descending order ──────────────────────

    static double[] runGreedy1() {
        boolean[] vis = new boolean[NN];
        vis[runStartNode] = true;
        double spent = 0;
        int totalPrize = 0, cur = runStartNode;

        Integer[] order = new Integer[N_S];
        for (int i = 0; i < N_S; i++) order[i] = i + 1;
        Arrays.sort(order, (a, b) -> prize[b] - prize[a]);

        for (int idx : order) {
            double d = dist(cur, idx);
            if (!vis[idx] && d + distToDepot[idx] <= BUDGET - spent) {
                spent      += d;
                totalPrize += prize[idx];
                vis[idx]    = true;
                cur         = idx;
            }
        }
        spent += distToDepot[cur];
        return new double[]{totalPrize, spent};
    }

    // ── Greedy 2: at each step pick highest prize/distance-ratio feasible city ─

    static double[] runGreedy2() {
        boolean[] vis = new boolean[NN];
        vis[runStartNode] = true;
        double spent = 0;
        int totalPrize = 0, cur = runStartNode;

        List<Integer> cands = new ArrayList<>(N_S);
        for (int i = 1; i < NN - 1; i++) cands.add(i);

        while (true) {
            final int fc = cur;
            cands.sort((a, b) -> Double.compare(
                prize[b] / Math.max(dist(fc, b), 1e-9),
                prize[a] / Math.max(dist(fc, a), 1e-9)));

            boolean found = false;
            for (int city : cands) {
                double d = dist(cur, city);
                if (!vis[city] && d + distToDepot[city] <= BUDGET - spent) {
                    spent      += d;
                    totalPrize += prize[city];
                    vis[city]   = true;
                    cur         = city;
                    found       = true;
                    break;
                }
            }
            if (!found) break;
        }
        spent += distToDepot[cur];
        return new double[]{totalPrize, spent};
    }

    // ── AntQ: pheromone-based ACO (prize-oblivious learning) ──────────────────

    static double[] runAntQ(long seed) {
        AQ_RNG = new Random(seed);

        // aqTau[i][k] = pheromone for edge i→candList[i][k]; slot candSz = depot
        aqTau       = new double[NN][candSz + 1];
        aqTauLastEp = new int[NN][candSz + 1];
        aqCurEp     = 0;
        for (int i = 0; i < NN; i++)
            Arrays.fill(aqTau[i], AQ_TAU_INIT);

        // Scratch arrays for per-ant feasible set
        int[]    feas      = new int[N_S + 1];
        int[]    feasSl    = new int[N_S + 1];
        double[] sc        = new double[N_S + 1];

        int    globalBestPrize = Integer.MIN_VALUE;
        int    stagnation      = 0;
        int[]  bestPath        = null;
        double bestDist        = Double.MAX_VALUE;

        for (int ep = 0; ep < AQ_TRIALS; ep++) {
            aqCurEp = ep;
            int    epBestPrize = Integer.MIN_VALUE;
            double epBestDist  = Double.MAX_VALUE;
            int[]  epBestPath  = null;

            for (int a = 0; a < AQ_ANTS; a++) {
                boolean[] vis  = new boolean[NN];
                int[]     path = new int[N_S + 2];
                int       plen = 0;
                vis[runStartNode] = true;
                path[plen++] = runStartNode;
                double dist  = 0;
                int    prz   = 0, c = runStartNode;

                while (true) {
                    // Build feasible set from candidate list
                    int fcnt = 0;
                    int[] cands = candList[c];
                    for (int k = 0; k < candSz; k++) {
                        int i = cands[k];
                        if (!vis[i] && Main.dist(c, i) + distToDepot[i] <= BUDGET - dist) {
                            feas[fcnt]   = i;
                            feasSl[fcnt] = k;
                            fcnt++;
                        }
                    }
                    // Full-scan fallback
                    if (fcnt == 0) {
                        for (int i = 1; i < NN - 1; i++) {
                            if (!vis[i] && Main.dist(c, i) + distToDepot[i] <= BUDGET - dist) {
                                feas[fcnt]   = i;
                                feasSl[fcnt] = -1;
                                fcnt++;
                            }
                        }
                    }

                    int next, nextSlot;
                    if (fcnt == 0) {
                        next = NN - 1; nextSlot = candSz;
                    } else if (AQ_RNG.nextDouble() <= AQ_Q0) {
                        // Exploitation: argmax tau^PHI * (1/dist)^ETA_POWER
                        int best = 0; double bv = Double.NEGATIVE_INFINITY;
                        for (int i = 0; i < fcnt; i++) {
                            int sl = feasSl[i];
                            double tau = (sl >= 0) ? aqGetTau(c, sl) : AQ_TAU_INIT;
                            double d   = Main.dist(c, feas[i]);
                            if (d <= 0) continue;
                            double v = Math.pow(tau, AQ_PHI) * Math.pow(1.0 / d, AQ_ETA_POWER);
                            if (v > bv) { bv = v; best = i; }
                        }
                        next = feas[best]; nextSlot = feasSl[best];
                    } else {
                        // Exploration: roulette-wheel
                        double total = 0;
                        for (int i = 0; i < fcnt; i++) {
                            int sl = feasSl[i];
                            double tau = (sl >= 0) ? aqGetTau(c, sl) : AQ_TAU_INIT;
                            double d   = Main.dist(c, feas[i]);
                            sc[i] = (d > 0) ? Math.pow(tau, AQ_PHI) * Math.pow(1.0 / d, AQ_ETA_POWER) : 0;
                            total += sc[i];
                        }
                        int chosen = fcnt - 1;
                        if (total > 0) {
                            double r = AQ_RNG.nextDouble() * total;
                            for (int i = 0; i < fcnt; i++) { r -= sc[i]; if (r <= 0) { chosen = i; break; } }
                        } else {
                            chosen = AQ_RNG.nextInt(fcnt);
                        }
                        next = feas[chosen]; nextSlot = feasSl[chosen];
                    }

                    dist += Main.dist(c, next);
                    path[plen++] = next;
                    if (next != NN - 1) { prz += prize[next]; vis[next] = true; }
                    c = next;
                    if (next == NN - 1) break;
                }

                if (prz > epBestPrize || (prz == epBestPrize && dist < epBestDist)) {
                    epBestPrize = prz; epBestDist = dist;
                    epBestPath  = Arrays.copyOf(path, plen);
                }
            }

            // Global pheromone update on episode-best ant's path
            if (epBestDist > 0 && epBestPath != null) {
                double deposit = 1.0 / epBestDist;
                for (int k = 0; k < epBestPath.length - 1; k++) {
                    int from = epBestPath[k], to = epBestPath[k + 1];
                    int sl = findSlot(from, to);
                    if (sl >= 0) {
                        aqGetTau(from, sl); // apply deferred evaporation
                        aqTau[from][sl] += AQ_RHO * deposit;
                    }
                }
            }

            if (epBestPrize > globalBestPrize) {
                globalBestPrize = epBestPrize;
                bestPath        = epBestPath;
                bestDist        = epBestDist;
                stagnation      = 0;
            } else if (++stagnation >= AQ_STAGNATION) {
                break;
            }
        }

        // Execution stage: exploitation-only greedy traversal following pheromone
        boolean[] vis      = new boolean[NN];
        vis[runStartNode]  = true;
        double finalDist   = 0;
        int    finalPrize  = 0, cur = runStartNode;

        while (cur != NN - 1) {
            int    bestNext = NN - 1;
            double bestVal  = Double.NEGATIVE_INFINITY;

            int[] cands = candList[cur];
            for (int k = 0; k < candSz; k++) {
                int i = cands[k];
                double d = dist(cur, i);
                if (!vis[i] && d + distToDepot[i] <= BUDGET - finalDist) {
                    double tau = aqGetTau(cur, k);
                    double v   = Math.pow(tau, AQ_PHI) * Math.pow(1.0 / Math.max(d, 1e-9), AQ_ETA_POWER);
                    if (v > bestVal) { bestVal = v; bestNext = i; }
                }
            }
            // Full-scan fallback if no candidate is feasible
            if (bestNext == NN - 1) {
                for (int i = 1; i < NN - 1; i++) {
                    double d = dist(cur, i);
                    if (!vis[i] && d + distToDepot[i] <= BUDGET - finalDist) {
                        bestNext = i; break; // any feasible city is fine here
                    }
                }
            }

            finalDist += dist(cur, bestNext);
            if (bestNext != NN - 1) { finalPrize += prize[bestNext]; vis[bestNext] = true; }
            cur = bestNext;
        }
        return new double[]{finalPrize, finalDist};
    }

    /** Lazy pheromone read: applies deferred evaporation for elapsed episodes. */
    static double aqGetTau(int i, int slot) {
        int lag = aqCurEp - aqTauLastEp[i][slot];
        if (lag == 0) return aqTau[i][slot];
        aqTau[i][slot]      *= Math.pow(1.0 - AQ_RHO, lag);
        aqTauLastEp[i][slot] = aqCurEp;
        return aqTau[i][slot];
    }

    // ── Load CSV ──────────────────────────────────────────────────────────────

    static void loadCSV() throws IOException {
        InputStream is = Main.class.getResourceAsStream("/city_rewards.csv");
        if (is == null)
            throw new FileNotFoundException("city_rewards.csv not found on classpath");

        List<double[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            br.readLine(); // skip header: city_id,x,y,reward
            String line;
            while ((line = br.readLine()) != null && rows.size() < MAX_NODES) {
                String[] p = line.split(",");
                rows.add(new double[]{
                    Double.parseDouble(p[1].trim()),
                    Double.parseDouble(p[2].trim()),
                    Double.parseDouble(p[3].trim())
                });
            }
        }

        int n = rows.size();
        N_S = n - 1;
        NN  = n + 1;

        X     = new double[NN];
        Y     = new double[NN];
        prize = new int[NN];

        for (int i = 0; i < n; i++) {
            X[i]     = rows.get(i)[0];
            Y[i]     = rows.get(i)[1];
            prize[i] = (int) rows.get(i)[2];
        }
        // Depot-end shares location with depot-start; updated per run in main()
        X[NN-1]     = X[0];
        Y[NN-1]     = Y[0];
        prize[NN-1] = 0;
    }

    // ── Candidate list: top CAND_SIZE cities per node by prize/distance ratio ──

    static void buildCandidateList() {
        int sz = Math.min(CAND_SIZE, N_S);
        candList = new int[NN][sz];

        double[]  key = new double[N_S];
        Integer[] ord = new Integer[N_S];
        for (int m = 0; m < N_S; m++) ord[m] = m;

        for (int i = 0; i < NN; i++) {
            for (int m = 0; m < N_S; m++) {
                int j = m + 1;
                double d = dist(i, j);
                key[m] = (d > 0) ? (double) prize[j] / d : (double) prize[j] * 1e9;
            }
            Arrays.sort(ord, (a, b) -> Double.compare(key[b], key[a]));
            for (int k = 0; k < sz; k++) candList[i][k] = ord[k] + 1;
        }
    }

    // ── Q / R cold-start (candidate-slot indexed) ─────────────────────────────

    static void initQR() {
        Q_tab = new double[NN][candSz + 1];
        R_tab = new double[NN][candSz + 1];
        for (int i = 0; i < NN; i++) {
            int[] cands = candList[i];
            for (int k = 0; k < candSz; k++) {
                int j = cands[k];
                R_tab[i][k] = prize[j];
                double d = dist(i, j);
                Q_tab[i][k] = (d > 0) ? (prize[i] + prize[j]) / d : 0.0;
            }
            // Depot slot
            double d = dist(i, NN - 1);
            Q_tab[i][candSz] = (d > 0) ? prize[i] / d : 0.0;
            R_tab[i][candSz] = 0; // prize[NN-1] = 0
        }
    }

    // ── P-MARL Algorithm 3: IL + CL ──────────────────────────────────────────

    static void runMARL() {
        gBestPrize = Integer.MIN_VALUE;
        gBestPath  = new ArrayList<>();
        gBestDist  = 0;
        int noImprove = 0;

        for (int ep = 0; ep < TRIALS; ep++) {

            boolean[][] vis   = new boolean[M][NN];
            double[]    spent = new double[M];
            int[]       cur   = new int[M];
            int[]       prz   = new int[M];
            boolean[]   done  = new boolean[M];
            @SuppressWarnings("unchecked")
            List<Integer>[] paths = new List[M];
            for (int a = 0; a < M; a++) {
                vis[a][runStartNode] = true;
                cur[a]   = runStartNode;
                paths[a] = new ArrayList<>(Collections.singletonList(runStartNode));
            }

            double eps = 1.0 - Q0 * (TRIALS - ep) / (double) TRIALS;
            while (notAllDone(done)) {
                for (int a = 0; a < M; a++) {
                    if (done[a]) continue;
                    int next = pamSelect(cur[a], spent[a], vis[a], eps);
                    int slot = pamNextSlot; // set by pamSelect
                    double mq = maxFeasibleQ(next, spent[a] + dist(cur[a], next), vis[a]);
                    if (slot >= 0) {
                        Q_tab[cur[a]][slot] =
                                (1 - ALPHA) * Q_tab[cur[a]][slot]
                                + ALPHA * (R_tab[cur[a]][slot] + GAMMA * mq);
                    }
                    vis[a][next] = true;
                    paths[a].add(next);
                    spent[a] += dist(cur[a], next);
                    if (next != NN - 1) prz[a] += prize[next];
                    else                done[a]  = true;
                    cur[a] = next;
                }
            }

            // Cooperative Learning: reward best agent's path
            int jStar = 0;
            for (int a = 1; a < M; a++) if (prz[a] > prz[jStar]) jStar = a;
            List<Integer> p      = paths[jStar];
            int           jPrize = prz[jStar];
            double        jDist  = spent[jStar];

            for (int v = 0; v < p.size() - 1; v++) {
                int u = p.get(v), w = p.get(v + 1);
                int sl = findSlot(u, w);
                if (sl >= 0) {
                    R_tab[u][sl] += W_CONST / Math.max(jPrize, 1);
                    Q_tab[u][sl]  = (1 - ALPHA) * Q_tab[u][sl]
                            + ALPHA * (R_tab[u][sl] + GAMMA * maxQAll(w));
                }
            }

            if (jPrize > gBestPrize) {
                gBestPrize = jPrize;
                gBestPath  = new ArrayList<>(p);
                gBestDist  = jDist;
                noImprove  = 0;
            } else if (++noImprove >= STAGNATION_LIMIT) {
                break;
            }
        }
    }

    // ── Prize-based Action Mechanism (PAM) ───────────────────────────────────
    // Sets pamNextSlot: candidate slot of the chosen city (-1 if fallback city).

    static int pamSelect(int cur, double spent, boolean[] vis, double eps) {
        double remB = BUDGET - spent;
        int cnt = 0;
        int[] cands = candList[cur];
        for (int k = 0; k < candSz; k++) {
            int j = cands[k];
            if (!vis[j] && dist(cur, j) + distToDepot[j] <= remB) {
                feasBuf[cnt]   = j;
                feasSlots[cnt] = k;
                cnt++;
            }
        }
        if (cnt == 0) {
            for (int j = 1; j < NN - 1; j++) {
                if (!vis[j] && dist(cur, j) + distToDepot[j] <= remB) {
                    feasBuf[cnt]   = j;
                    feasSlots[cnt] = -1;
                    cnt++;
                }
            }
        }
        if (cnt == 0) { pamNextSlot = candSz; return NN - 1; }

        if (RNG.nextDouble() <= eps) {
            int    best = 0;
            double bv   = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < cnt; i++) {
                int u    = feasBuf[i];
                int sl   = feasSlots[i];
                double q = (sl >= 0) ? Q_tab[cur][sl] : 0.0;
                double v = Math.pow(Math.max(q, 1e-12), DELTA)
                         * prize[u]
                         / Math.pow(Math.max(dist(cur, u), 1.0), BETA);
                if (v > bv) { bv = v; best = i; }
            }
            pamNextSlot = feasSlots[best];
            return feasBuf[best];
        } else {
            double total = 0;
            for (int i = 0; i < cnt; i++) {
                int u    = feasBuf[i];
                int sl   = feasSlots[i];
                double q = (sl >= 0) ? Q_tab[cur][sl] : 0.0;
                scoreBuf[i] = Math.pow(Math.max(q, 1e-12), DELTA)
                            * prize[u]
                            / Math.pow(Math.max(dist(cur, u), 1.0), BETA);
                total += scoreBuf[i];
            }
            if (total <= 0) {
                int ri = RNG.nextInt(cnt);
                pamNextSlot = feasSlots[ri];
                return feasBuf[ri];
            }
            double r = RNG.nextDouble() * total;
            for (int i = 0; i < cnt; i++) {
                r -= scoreBuf[i];
                if (r <= 0) { pamNextSlot = feasSlots[i]; return feasBuf[i]; }
            }
            pamNextSlot = feasSlots[cnt - 1];
            return feasBuf[cnt - 1];
        }
    }

    // ── Q helpers ─────────────────────────────────────────────────────────────

    static double maxFeasibleQ(int s, double spent, boolean[] vis) {
        double max  = 0;
        double remB = BUDGET - spent;
        int[] cands = candList[s];
        for (int k = 0; k < candSz; k++) {
            int j = cands[k];
            if (!vis[j] && dist(s, j) + distToDepot[j] <= remB && Q_tab[s][k] > max)
                max = Q_tab[s][k];
        }
        return max;
    }

    static double maxQAll(int s) {
        double max = 0;
        for (int k = 0; k <= candSz; k++) if (Q_tab[s][k] > max) max = Q_tab[s][k];
        return max;
    }

    static boolean notAllDone(boolean[] done) {
        for (boolean b : done) if (!b) return true;
        return false;
    }
}
