// Agent class which is used in MARL
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Agent 
{
    private int agentID; 
    private Node curr; // current node where the agent is
    private boolean end; // if base == base true, if base!= base false
    private double budget; // current budget of the agent
    private int dataPackets; // data packets collected 
    private HashSet<Node> unvisited; // set of unvisited nodes
    private List<Integer> route; // route taken by the agent
    private boolean isDone; // status if is done or not
    private double cost; // cost of the route
    private HashMap<Node, Double> shortestPaths; // shortest paths from the starting point. This STARTING POINT is given in main()
   
    Network network = new Network();

    
    public Agent(int id, Node curr, Node target, boolean end, double budget, boolean isDone, List<Node> nodes, HashMap<Node, Double> shortestPaths)
    {   
        this.curr = curr;
        this.end = end;
        this.budget = budget*3600; // conversion for Watt Hours to Joules
        this.unvisited = new HashSet<>(nodes);
        unvisited.remove(curr); // remove starting node
        if(!this.end)
        {
            unvisited.remove(target);
        }
        this.isDone = isDone;
        this.shortestPaths = shortestPaths;
        this.dataPackets = 0;
        this.cost= 0;
        this.route = new ArrayList<>();
        this.route.add(curr.getID()); // add starting node to the route
        this.agentID = id;
    }

    public int getAgentID() {
        return agentID;
    }

    public void setAgentID(int agentID) {
        this.agentID = agentID;
    }
    
    // adds node to the route
    public void addNode(Node node)
    {
        route.add(node.getID());
    }

    // adds cost to reach the next node to total cost
    public void addCost(Node current, Node next)
    {
        if(current == next)
        {
            cost = cost + 0;
        }
        else
        {
            cost = cost + current.getNeighbor(next);
        }   
    }

    // agent is done, adds cost to reach to starting point, updates budget
    public void terminate()
    {
        this.cost = this.cost + shortestPaths.get(this.curr);
        this.budget = this.budget - shortestPaths.get(this.curr)*100;     // 100 is used because 1m requires 100 Joules 
    }

    //returns the current cost of agent's route
    public double getCost()
    {
        return this.cost;
    }
    
    //  update budget according to the next node's details
    public void updateBudget(Node current, Node next)
    {
        this.budget = this.budget - (current.getNeighbor(next)*100);
    }

    // update data packets according to current node's details
    public void updateData(Node current)
    {
        this.dataPackets = this.dataPackets + current.getDataPackets();
    }

    // updates next to curr
    public void updateCurrent(Node current)
    {
        this.curr = current;
    }

    // status of the agent
    public boolean isDone()
    {
        return this.isDone;
    }

    // update status of agent
    public void updateDone(boolean done)
    {
        this.isDone = done;
    }

    // get the feasible set of nodes for the agent from current node based on it's current budget, and set of unvisited nodes
    public Set<Node> agentFeasibleSet()
    {
        return network.feasibleSet(this.curr, this.budget, this.shortestPaths, this.unvisited);
    }

    // returns current nodes
    public Node getCurrent()
    {
        return this.curr;
    }

    // returns the current budget of the agent
    public double getBudget()
    {
        return this.budget;
    }

    // returns the unvisited set of nodes of the agent
    public HashSet<Node> getUnvisited()
    {
        return this.unvisited;
    }

    // remove a node from unvisited set
    public void updateUnvisited(Node node)
    {
        this.unvisited.remove(node);
    }

    // returns data packets collected up until now
    public int getDataPackets()
    {
        return this.dataPackets;
    }

    // returns the route taken by agent 
    public List<Integer> getRoute()
    {
        return this.route;
    }


    
}
