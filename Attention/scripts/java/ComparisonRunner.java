import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class ComparisonRunner {
    private static final int[] DEFAULT_BUDGETS = new int[] {4000, 6000, 8000, 10000};
    private static final int DEFAULT_TOTAL_INSTANCES = 20;
    private static final String DATASET = "Capital_Cities.txt";

    private static Method traverseP;
    private static Method traverseR;
    private static Method traverseQ;

    public static void main(String[] args) throws Exception {
        String outPath = args.length >= 1 ? args[0] : "../../raw/java_baselines.csv";
        int totalInstances = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_TOTAL_INSTANCES;
        int[] budgets = args.length >= 3 ? parseBudgets(args[2]) : DEFAULT_BUDGETS;

        TableData.fileName = DATASET;
        TableData.NUM_AGENTS = 6;

        traverseP = TableData.class.getDeclaredMethod("traverseP");
        traverseR = TableData.class.getDeclaredMethod("traverseR");
        traverseQ = TableData.class.getDeclaredMethod("traverseQ");
        traverseP.setAccessible(true);
        traverseR.setAccessible(true);
        traverseQ.setAccessible(true);

        File outFile = new File(outPath);
        File parent = outFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        try (PrintWriter out = new PrintWriter(outFile)) {
            out.println(String.join(",",
                "dataset",
                "instance_index",
                "depot",
                "budget_miles",
                "algorithm",
                "collected_prize_excluding_depot",
                "java_raw_prize_including_depot",
                "route_distance_miles",
                "runtime_ms",
                "route"
            ));

            for (int budget : budgets) {
                for (int idx = 0; idx < totalInstances; idx++) {
                    TableData.generateRandomCities(budget, idx);
                    String depot = TableData.begin;
                    System.err.printf("Running Java baselines: budget=%d instance=%d depot=%s%n",
                        budget, idx, depot);

                    runGreedy(out, idx, budget, depot, "Greedy 1", traverseP);
                    runGreedy(out, idx, budget, depot, "Greedy 2", traverseR);
                    runPMarl(out, idx, budget, depot);
                    out.flush();
                }
            }
        }
    }

    private static int[] parseBudgets(String raw) {
        String[] parts = raw.split(",");
        int[] budgets = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            budgets[i] = Integer.parseInt(parts[i].trim());
        }
        return budgets;
    }

    private static void initCommon() {
        TableData.initList();
        TableData.initGraph();
        TableData.initStatics();
    }

    private static void runGreedy(PrintWriter out, int idx, int budget, String depot,
                                  String algorithm, Method traversal) throws Exception {
        initCommon();
        long start = System.nanoTime();
        traversal.invoke(null);
        long end = System.nanoTime();
        writeResult(out, idx, budget, depot, algorithm, nanosToMillis(start, end));
    }

    private static void runPMarl(PrintWriter out, int idx, int budget, String depot) throws Exception {
        initCommon();
        long start = System.nanoTime();
        TableData.learnQ();
        traverseQ.invoke(null);
        long end = System.nanoTime();
        writeResult(out, idx, budget, depot, "P-MARL", nanosToMillis(start, end));
    }

    private static double nanosToMillis(long start, long end) {
        return (end - start) / 1_000_000.0;
    }

    private static void writeResult(PrintWriter out, int idx, int budget, String depot,
                                    String algorithm, double runtimeMs) {
        int collectedExcludingDepot = collectedPrizeExcludingDepot(depot);
        String route = routeNames();
        out.printf("%s,%d,%s,%d,%s,%d,%d,%.6f,%.6f,%s%n",
            csv(DATASET),
            idx,
            csv(depot),
            budget,
            csv(algorithm),
            collectedExcludingDepot,
            TableData.total_prize,
            TableData.total_wt,
            runtimeMs,
            csv(route)
        );
    }

    private static int collectedPrizeExcludingDepot(String depot) {
        Set<String> visitedNames = new LinkedHashSet<>();
        int total = 0;
        for (int cityIndex : TableData.route) {
            CityNode city = TableData.arrCities.get(cityIndex);
            if (!city.name.equals(depot) && visitedNames.add(city.name)) {
                total += city.pop;
            }
        }
        return total;
    }

    private static String routeNames() {
        ArrayList<String> names = new ArrayList<>();
        for (int cityIndex : TableData.route) {
            names.add(TableData.arrCities.get(cityIndex).name);
        }
        return String.join(" -> ", names);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
