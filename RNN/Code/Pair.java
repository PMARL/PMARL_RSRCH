import java.util.List;

public class Pair
{
    private Table table;
    private List<Integer> route;
    private double remainingBudget;

    public Pair(Table t, List<Integer> r, double b) {
        this.table = t;
        this.route = r;
        this.remainingBudget = b;
    }

    public Table getTable() {
        return this.table;
    }

    public List<Integer> getRoute() {
        return this.route;
    }

    public double getBudgte()
    {
        return this.remainingBudget;
    }
}


    

