import java.io.*;
import java.util.*;

/**
 * Entry point for `mvn exec:java`.
 *
 * Reads MAX_NODES cities from city_rewards.csv (classpath resource), builds a
 * plain Euclidean distance matrix D[][], then runs four algorithms for RUNS
 * independent cold-start experiments: Greedy1, Greedy2, P-MARL, and AntQ.
 *
 * Why no Floyd-Warshall / no Graph copies:
 *   The city_rewards.csv graph is complete (every pair directly connected).
 *   Shortest path == direct Euclidean edge, so Floyd-Warshall adds nothing.
 *   Agents track visited nodes with a boolean[] instead of cloning the full
 *   graph, cutting per-episode allocation from ~MB to ~KB and making the code
 *   viable for hundreds of nodes.
 *
 * Memory guide (D + Q + R matrices):
 *   MAX_NODES=500  ->  ~6 MB     fast
 *   MAX_NODES=1000 ->  ~24 MB    fast
 *   MAX_NODES=5000 ->  ~600 MB   may need -Xmx2g
 */
public class Main {

    // ── Constants used by AntQ.java ───────────────────────────────────────────
    static final int UNVISITED  = 0;
    static final int VISITED    = 1;
    static final int LAST_VISIT = 2;

    // ── Configuration ─────────────────────────────────────────────────────────
    static final int    MAX_NODES = 13_509;
    static final double BUDGET    = 10_000_000.0;
    static final int    RUNS      = 10;

    // ── P-MARL hyperparameters ────────────────────────────────────────────────
    static final int    TRIALS           = 15_000;
    static final int    M                = 5;         // number of agents
    static double W_CONST; // set after loadCSV() — scaled to prize magnitude
    static final double ALPHA            = 0.125;
    static final double GAMMA            = 0.35;
    static final double DELTA            = 1.0;
    static final double BETA             = 2.0;
    static final double Q0               = 0.8;
    /** Stop early if best prize has not improved for this many episodes. */
    static final int    STAGNATION_LIMIT = 500;
    /** Per-node candidate list size: top-K cities by prize/distance ratio. */
    static final int    CAND_SIZE        = 50;

    // ── AntQ hyperparameters ──────────────────────────────────────────────────
    static final int    AQ_TRIALS     = 15_000;
    /** Stop early if best prize has not improved for this many consecutive episodes. */
    static final int    AQ_STAGNATION = 100;
    static final int    AQ_ANTS       = 5;
    /** Pheromone evaporation rate. */
    static final double AQ_RHO        = 0.1;
    /** Weight of pheromone in action rule. */
    static final double AQ_PHI        = 1.0;
    /** Weight of heuristic (1/dist) in action rule. */
    static final double AQ_ETA_POWER  = 2.0;
    /** Exploitation probability threshold. */
    static final double AQ_Q0         = 0.7;
    static final double AQ_TAU_INIT   = 1.0;

    static final Random RNG    = new Random(42);
    static Random AQ_RNG = new Random(42); // re-seeded each run so AntQ results are independent

    // ── Per-run starting depot (randomised each run) ──────────────────────────
    static int runStartNode = 0; // city index used as depot for the current run

    // ── Graph ─────────────────────────────────────────────────────────────────
    // Layout: node 0 = depot-start  |  nodes 1..N_S = cities  |  node NN-1 = depot-end
    // D[0][NN-1] = 0 (start and end share the same location).
    static int      NN;       // MAX_NODES + 1
    static int      N_S;      // number of sensor/city nodes  = MAX_NODES - 1
    static double[] X, Y;
    static int[]    prize;
    static double[][] D;      // Euclidean distance matrix [NN][NN]

    // ── Shared Q / R tables (reset each run by P-MARL) ───────────────────────
    static double[][] Q_tab, R_tab;

    // ── Candidate list + reusable scratch buffers (built once after loadCSV) ─
    static int[][]   candList;    // candList[i] = top CAND_SIZE city indices from node i
    static double[]  distToDepot; // distToDepot[j] = D[j][NN-1], cache-friendly
    static int[]     feasBuf;     // scratch: feasible cities for current pamSelect call
    static double[]  scoreBuf;    // scratch: selection scores for current pamSelect call

    // ── Written by runMARL ────────────────────────────────────────────────────
    static int           gBestPrize;
    static double        gBestDist;
    static List<Integer> gBestPath;

    // ── AntQ pheromone state (reset each run) ────────────────────────────────
    static double[][] aqTau;
    static int[][]    aqTauLastEp;
    static int        aqCurEp;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws IOException {
        loadCSV();
        distToDepot = new double[NN];
        for (int j = 0; j < NN; j++) distToDepot[j] = D[j][NN - 1];
        buildCandidateList();
        // Scale W so cooperative boost per episode is meaningful vs. R values (prize[j]).
        long prizeSum = 0;
        for (int i = 1; i < NN - 1; i++) prizeSum += prize[i];
        W_CONST = (prizeSum / (double) N_S) * 100.0;

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
            // Pick a new random starting city for this run
            runStartNode = 1 + RNG.nextInt(N_S);
            // Update depot-end column/row to reflect new start location
            for (int j = 0; j < NN; j++) {
                double dx = X[j] - X[runStartNode], dy = Y[j] - Y[runStartNode];
                double d  = Math.sqrt(dx * dx + dy * dy);
                D[j][NN - 1] = d;
                D[NN - 1][j] = d;
            }
            D[runStartNode][NN - 1] = 0.0;
            D[NN - 1][runStartNode] = 0.0;
            for (int j = 0; j < NN; j++) distToDepot[j] = D[j][NN - 1];

            System.out.printf("%n  ── Run %2d/%d  (start city=%d) ──%n", run, RUNS, runStartNode);

            // ── Greedy 1 ─────────────────────────────────────────────────────
            long tg1s = System.nanoTime();
            double[] g1 = runGreedy1();
            long tg1e = System.nanoTime();
            g1Prize[run-1] = g1[0]; g1Dist[run-1] = g1[1];
            g1Time[run-1]  = (tg1e - tg1s) / 1_000_000.0;
            System.out.printf("  %-9s  %10.0f  %12.2f  %12.2f  %9.0f%n",
                "Greedy1", g1[0], g1[1], BUDGET - g1[1], g1Time[run-1]);

            // ── Greedy 2 ─────────────────────────────────────────────────────
            long tg2s = System.nanoTime();
            double[] g2 = runGreedy2();
            long tg2e = System.nanoTime();
            g2Prize[run-1] = g2[0]; g2Dist[run-1] = g2[1];
            g2Time[run-1]  = (tg2e - tg2s) / 1_000_000.0;
            System.out.printf("  %-9s  %10.0f  %12.2f  %12.2f  %9.0f%n",
                "Greedy2", g2[0], g2[1], BUDGET - g2[1], g2Time[run-1]);

            // ── P-MARL ───────────────────────────────────────────────────────
            initQR();
            long t0 = System.nanoTime();
            runMARL();
            long t1 = System.nanoTime();
            pPrize[run-1] = gBestPrize; pDist[run-1] = gBestDist;
            pTime[run-1]  = (t1 - t0) / 1_000_000.0;
            System.out.printf("  %-9s  %10d  %12.2f  %12.2f  %9.0f%n",
                "PMARL", gBestPrize, gBestDist, BUDGET - gBestDist, pTime[run-1]);

            // ── AntQ ─────────────────────────────────────────────────────────
            long t2 = System.nanoTime();
            double[] aq = runAntQ(42L + run);
            long t3 = System.nanoTime();
            aPrize[run-1] = aq[0]; aDist[run-1] = aq[1];
            aTime[run-1]  = (t3 - t2) / 1_000_000.0;
            System.out.printf("  %-9s  %10.0f  %12.2f  %12.2f  %9.0f%n",
                "AntQ", aq[0], aq[1], BUDGET - aq[1], aTime[run-1]);
        }

        printSummary(g1Prize, g1Dist, g1Time, g2Prize, g2Dist, g2Time, pPrize, pDist, pTime, aPrize, aDist, aTime);
    }

    // ── Summary table ────────────────────────────────────────────────────────

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

    // ── Greedy 1: visit cities in prize-descending order (feasibility-checked) ─

    static double[] runGreedy1() {
        boolean[] vis = new boolean[NN];
        vis[runStartNode] = true;
        double spent = 0;
        int totalPrize = 0, cur = runStartNode;

        Integer[] order = new Integer[N_S];
        for (int i = 0; i < N_S; i++) order[i] = i + 1;
        Arrays.sort(order, (a, b) -> prize[b] - prize[a]);

        for (int idx : order) {
            if (!vis[idx] && D[cur][idx] + D[idx][NN - 1] <= BUDGET - spent) {
                spent      += D[cur][idx];
                totalPrize += prize[idx];
                vis[idx]    = true;
                cur         = idx;
            }
        }
        spent += D[cur][NN - 1]; // return to depot
        return new double[]{totalPrize, spent};
    }

    // ── Greedy 2: at each step pick the highest prize/distance-ratio feasible city ─

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
                prize[b] / Math.max(D[fc][b], 1e-9),
                prize[a] / Math.max(D[fc][a], 1e-9)));

            boolean found = false;
            for (int city : cands) {
                if (!vis[city] && D[cur][city] + D[city][NN - 1] <= BUDGET - spent) {
                    spent      += D[cur][city];
                    totalPrize += prize[city];
                    vis[city]   = true;
                    cur         = city;
                    found       = true;
                    break;
                }
            }
            if (!found) break;
        }
        spent += D[cur][NN - 1]; // return to depot
        return new double[]{totalPrize, spent};
    }

    // ── AntQ: pheromone-based ACO (prize-oblivious learning, greedy execution) ─

    static double[] runAntQ(long seed) {
        AQ_RNG = new Random(seed);
        // Initialize pheromone matrix
        aqTau       = new double[NN][NN];
        aqTauLastEp = new int[NN][NN];
        aqCurEp     = 0;
        for (int i = 0; i < NN; i++)
            for (int j = 0; j < NN; j++)
                aqTau[i][j] = (D[i][j] > 0) ? AQ_TAU_INIT : 0.0;

        int globalBestPrize = Integer.MIN_VALUE;
        int stagnation = 0;

        for (int ep = 0; ep < AQ_TRIALS; ep++) {
            aqCurEp = ep;
            int[]  bestPath  = null;
            double bestDist  = Double.MAX_VALUE;
            int    bestPrize = Integer.MIN_VALUE;

            for (int a = 0; a < AQ_ANTS; a++) {
                boolean[]    vis  = new boolean[NN];
                List<Integer> path = new ArrayList<>();
                vis[runStartNode] = true;
                path.add(runStartNode);
                double dist = 0;
                int prz = 0, c = runStartNode;

                while (true) {
                    List<Integer> feas = new ArrayList<>();
                    for (int i = 1; i < NN - 1; i++)
                        if (!vis[i] && D[c][i] + D[i][NN - 1] <= BUDGET - dist)
                            feas.add(i);

                    int next = feas.isEmpty()                      ? NN - 1
                             : AQ_RNG.nextDouble() <= AQ_Q0       ? aqExploit(c, feas)
                                                                   : aqExplore(c, feas);
                    dist += D[c][next];
                    path.add(next);
                    if (next != NN - 1) { prz += prize[next]; vis[next] = true; }
                    c = next;
                    if (next == NN - 1) break;
                }

                if (prz > bestPrize || (prz == bestPrize && dist < bestDist)) {
                    bestPrize = prz; bestDist = dist;
                    bestPath  = path.stream().mapToInt(Integer::intValue).toArray();
                }
            }

            // Global pheromone update on best ant's path
            if (bestDist > 0 && bestPath != null) {
                double deposit = 1.0 / bestDist;
                for (int k = 0; k < bestPath.length - 1; k++) {
                    aqGetTau(bestPath[k], bestPath[k + 1]); // apply deferred evaporation
                    aqTau[bestPath[k]][bestPath[k + 1]] += AQ_RHO * deposit;
                }
            }

            if (bestPrize > globalBestPrize) { globalBestPrize = bestPrize; stagnation = 0; }
            else if (++stagnation >= AQ_STAGNATION) break;
        }

        // Execution stage: exploitation-only greedy traversal following pheromone
        boolean[] vis = new boolean[NN];
        vis[runStartNode] = true;
        double finalDist = 0;
        int finalPrize = 0, cur = runStartNode;

        while (cur != NN - 1) {
            List<Integer> feas = new ArrayList<>();
            for (int i = 1; i < NN - 1; i++)
                if (!vis[i] && D[cur][i] + D[i][NN - 1] <= BUDGET - finalDist)
                    feas.add(i);

            int next = feas.isEmpty() ? NN - 1 : aqExploit(cur, feas);
            finalDist += D[cur][next];
            if (next != NN - 1) { finalPrize += prize[next]; vis[next] = true; }
            cur = next;
        }
        return new double[]{finalPrize, finalDist};
    }

    /** Lazy pheromone read: applies deferred evaporation for episodes elapsed since last update. */
    static double aqGetTau(int i, int j) {
        int lag = aqCurEp - aqTauLastEp[i][j];
        if (lag == 0) return aqTau[i][j];
        aqTau[i][j]      *= Math.pow(1.0 - AQ_RHO, lag);
        aqTauLastEp[i][j] = aqCurEp;
        return aqTau[i][j];
    }

    /** Exploitation: argmax tau(r,u)^PHI * (1/dist)^ETA_POWER over feasible set. */
    static int aqExploit(int cur, List<Integer> feas) {
        int best = feas.get(0); double bv = Double.NEGATIVE_INFINITY;
        for (int u : feas) {
            if (D[cur][u] <= 0) continue;
            double v = Math.pow(aqGetTau(cur, u), AQ_PHI) * Math.pow(1.0 / D[cur][u], AQ_ETA_POWER);
            if (v > bv) { bv = v; best = u; }
        }
        return best;
    }

    /** Exploration: roulette-wheel selection proportional to tau^PHI * (1/dist)^ETA_POWER. */
    static int aqExplore(int cur, List<Integer> feas) {
        double[] sc = new double[feas.size()]; double total = 0;
        for (int i = 0; i < feas.size(); i++) {
            int u = feas.get(i);
            if (D[cur][u] <= 0) continue;
            sc[i]  = Math.pow(aqGetTau(cur, u), AQ_PHI) * Math.pow(1.0 / D[cur][u], AQ_ETA_POWER);
            total += sc[i];
        }
        if (total == 0) return feas.get(AQ_RNG.nextInt(feas.size()));
        double r = AQ_RNG.nextDouble() * total;
        for (int i = 0; i < sc.length; i++) { r -= sc[i]; if (r <= 0) return feas.get(i); }
        return feas.get(feas.size() - 1);
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

        // Node layout:
        //   0        = depot-start  (first CSV row)
        //   1..n-1   = intermediate cities (remaining CSV rows)
        //   NN-1     = depot-end   (copy of node 0; D[0][NN-1] = 0)
        int n = rows.size();   // actual rows loaded
        N_S = n - 1;           // sensor/city count (excludes depot)
        NN  = n + 1;           // total graph nodes (adds depot-end copy)

        X     = new double[NN];
        Y     = new double[NN];
        prize = new int[NN];

        for (int i = 0; i < n; i++) {
            X[i]     = rows.get(i)[0];
            Y[i]     = rows.get(i)[1];
            prize[i] = (int) rows.get(i)[2];
        }
        // Depot-end is at the same location as depot-start
        X[NN-1]     = X[0];
        Y[NN-1]     = Y[0];
        prize[NN-1] = 0;

        // Build Euclidean distance matrix once
        D = new double[NN][NN];
        for (int i = 0; i < NN; i++)
            for (int j = 0; j < NN; j++) {
                double dx = X[i] - X[j], dy = Y[i] - Y[j];
                D[i][j] = Math.sqrt(dx * dx + dy * dy);
            }
    }

    // ── Candidate list: top CAND_SIZE cities per node by prize/distance ratio ──
    // Built once at startup; cuts per-step O(N) scans to O(CAND_SIZE).
    // Falls back to full scan in pamSelect if all candidates are exhausted.

    static void buildCandidateList() {
        int sz = Math.min(CAND_SIZE, N_S);
        candList = new int[NN][sz];
        feasBuf  = new int[N_S + 1];
        scoreBuf = new double[N_S + 1];

        double[]  key = new double[N_S];
        Integer[] ord = new Integer[N_S];
        for (int m = 0; m < N_S; m++) ord[m] = m; // ord[m]=m → city node m+1

        for (int i = 0; i < NN; i++) {
            for (int m = 0; m < N_S; m++) {
                int j = m + 1;
                key[m] = (D[i][j] > 0) ? (double) prize[j] / D[i][j]
                                        : (double) prize[j] * 1e9;
            }
            Arrays.sort(ord, (a, b) -> Double.compare(key[b], key[a]));
            for (int k = 0; k < sz; k++) candList[i][k] = ord[k] + 1;
        }
    }

    // ── Q / R cold-start ──────────────────────────────────────────────────────

    static void initQR() {
        Q_tab = new double[NN][NN];
        R_tab = new double[NN][NN];
        for (int i = 0; i < NN; i++)
            for (int j = 0; j < NN; j++) {
                R_tab[i][j] = prize[j];
                Q_tab[i][j] = (D[i][j] > 0) ? (prize[i] + prize[j]) / D[i][j] : 0.0;
            }
    }

    // ── P-MARL Algorithm 3: IL + CL ──────────────────────────────────────────

    static void runMARL() {
        gBestPrize = Integer.MIN_VALUE;
        gBestPath  = new ArrayList<>();
        gBestDist  = 0;
        int noImprove = 0;

        for (int ep = 0; ep < TRIALS; ep++) {

            // Per-agent state (boolean[] is trivially cheap vs full Graph copy)
            boolean[][] vis   = new boolean[M][NN];
            double[]    spent = new double[M];
            int[]       cur   = new int[M];
            int[]       prz   = new int[M];
            boolean[]   done  = new boolean[M];
            @SuppressWarnings("unchecked")
            List<Integer>[] paths = new List[M];
            for (int a = 0; a < M; a++) {
                vis[a][runStartNode] = true;
                cur[a]    = runStartNode;
                paths[a]  = new ArrayList<>(Collections.singletonList(runStartNode));
            }

            // Independent Learning (Eq. 11)
            double eps = 1.0 - Q0 * (TRIALS - ep) / (double) TRIALS;
            while (notAllDone(done)) {
                for (int a = 0; a < M; a++) {
                    if (done[a]) continue;
                    int next = pamSelect(cur[a], spent[a], vis[a], eps);
                    double mq = maxFeasibleQ(next, spent[a] + D[cur[a]][next], vis[a]);
                    Q_tab[cur[a]][next] =
                            (1 - ALPHA) * Q_tab[cur[a]][next]
                            + ALPHA * (R_tab[cur[a]][next] + GAMMA * mq);
                    vis[a][next] = true;
                    paths[a].add(next);
                    spent[a] += D[cur[a]][next];
                    if (next != NN - 1) prz[a] += prize[next];
                    else                done[a]  = true;
                    cur[a] = next;
                }
            }

            // Cooperative Learning (Eqs. 12-13 — reward best agent's path)
            int jStar = 0;
            for (int a = 1; a < M; a++) if (prz[a] > prz[jStar]) jStar = a;
            List<Integer> p      = paths[jStar];
            int           jPrize = prz[jStar];
            double        jDist  = spent[jStar];

            for (int v = 0; v < p.size() - 1; v++) {
                int u = p.get(v), w = p.get(v + 1);
                R_tab[u][w] += W_CONST / Math.max(jPrize, 1);
                Q_tab[u][w]  = (1 - ALPHA) * Q_tab[u][w]
                        + ALPHA * (R_tab[u][w] + GAMMA * maxQAll(w));
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

    static int pamSelect(int cur, double spent, boolean[] vis, double eps) {
        double remB = BUDGET - spent;
        int cnt = 0;
        // Fast path: scan candidate list (O(CAND_SIZE))
        for (int j : candList[cur])
            if (!vis[j] && D[cur][j] + distToDepot[j] <= remB)
                feasBuf[cnt++] = j;
        // Fallback: full scan only when all candidates are visited/infeasible
        if (cnt == 0) {
            for (int j = 1; j < NN - 1; j++)
                if (!vis[j] && D[cur][j] + distToDepot[j] <= remB)
                    feasBuf[cnt++] = j;
        }
        if (cnt == 0) return NN - 1;

        if (RNG.nextDouble() <= eps) {
            // Exploitation: argmax score
            int    best = feasBuf[0];
            double bv   = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < cnt; i++) {
                int u = feasBuf[i];
                double v = Math.pow(Math.max(Q_tab[cur][u], 1e-12), DELTA)
                         * prize[u]
                         / Math.pow(Math.max(D[cur][u], 1.0), BETA);
                if (v > bv) { bv = v; best = u; }
            }
            return best;
        } else {
            // Proportional exploration (reuses scoreBuf, no allocation)
            double total = 0;
            for (int i = 0; i < cnt; i++) {
                int u = feasBuf[i];
                scoreBuf[i] = Math.pow(Math.max(Q_tab[cur][u], 1e-12), DELTA)
                            * prize[u]
                            / Math.pow(Math.max(D[cur][u], 1.0), BETA);
                total += scoreBuf[i];
            }
            if (total <= 0) return feasBuf[RNG.nextInt(cnt)];
            double r = RNG.nextDouble() * total;
            for (int i = 0; i < cnt; i++) { r -= scoreBuf[i]; if (r <= 0) return feasBuf[i]; }
            return feasBuf[cnt - 1];
        }
    }

    // ── Feasible set: unvisited cities reachable within remaining budget ──────

    static List<Integer> feasible(int cur, double spent, boolean[] vis) {
        List<Integer> f = new ArrayList<>();
        double remB = BUDGET - spent;
        for (int j = 1; j < NN - 1; j++)
            if (!vis[j] && D[cur][j] + D[j][NN - 1] <= remB)
                f.add(j);
        return f;
    }

    // ── Q helpers ─────────────────────────────────────────────────────────────

    static double maxFeasibleQ(int s, double spent, boolean[] vis) {
        double max  = 0;
        double remB = BUDGET - spent;
        for (int j : candList[s])
            if (!vis[j] && D[s][j] + distToDepot[j] <= remB && Q_tab[s][j] > max)
                max = Q_tab[s][j];
        return max;
    }

    static double maxQAll(int s) {
        double max = 0;
        for (int j : candList[s]) if (Q_tab[s][j] > max) max = Q_tab[s][j];
        if (Q_tab[s][NN - 1] > max) max = Q_tab[s][NN - 1];
        return max;
    }

    static boolean notAllDone(boolean[] done) {
        for (boolean b : done) if (!b) return true;
        return false;
    }
}
