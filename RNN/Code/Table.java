import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class Table 
{
    private double[][] QTable;
    private double[][] reversedQTable;
    private double[][] RTable;
    private double[][] CTable;
    private List<Node> nodes;
    private double learningRate;
    private double discountFactor;
    private int W = 10000;
    private int Wi = 10000;
    Network network = new Network();


    public Table(List<Node> nodes, int totalNodes, double learningRate, double discountFactor)
    {
        this.nodes = nodes;
        this.QTable = new double[totalNodes][totalNodes];
        this.RTable = new double[totalNodes][totalNodes];
        this.discountFactor = discountFactor;
        this.learningRate = learningRate;

        // intitalize QTable to prize/cost 
        for(int state = 0; state<totalNodes; state++)
        {
            Node s = nodes.get(state);
            for(int action = 0; action<totalNodes; action++)
            {
                if(state != action)
                {
                    Node a = nodes.get(action);
                    double val = (s.getDataPackets() + a.getDataPackets())/s.getNeighbor(a);
                    QTable[state][action] = val;
                }
                else
                {
                    QTable[state][action] = 0;
                }
                
            }
        }

        // initialize R table;
        for(int state = 0; state<totalNodes; state++)
        {
            Node s = nodes.get(state);
            for(int action = 0; action<totalNodes; action++)
            {
                if(state == action)
                {
                    RTable[state][action] = 0;
                }
                else
                {
                    Node a = nodes.get(action);
                    if(a.getDataPackets()==0)
                    {
                        RTable[state][action] = 0;
                    }
                    else
                    {
                        RTable[state][action] = -(s.getNeighbor(a)/a.getDataPackets());
                    }
                }
                
            }
        }
    }
    // This constructor is used for PMARL, where the Q table needs to be 0. I have not integrated it with the original constructor, please do so if needed.
    public Table(int n, List<Node> nodes, int totalNodes, double learningRate, double discountFactor)
    {
        this.nodes = nodes;
        this.QTable = new double[totalNodes][totalNodes];
        this.RTable = new double[totalNodes][totalNodes];
        this.discountFactor = discountFactor;
        this.learningRate = learningRate;
        this.CTable = new double[totalNodes][totalNodes];
        
        for(int state = 0; state<totalNodes; state++)
        {
            Node s = nodes.get(state);
            for(int action = 0; action<totalNodes; action++)
            {
                if(state != action)
                {
                    Node a = nodes.get(action);
                    double val = (s.getDataPackets() + a.getDataPackets())/s.getNeighbor(a);
                    CTable[state][action] = val;
                }
                else
                {
                    CTable[state][action] = 0;
                }
                
            }
        }
    }
    public Table(double[][] q, List<Node> nodes)
    {
        this.nodes = nodes;
        int totalNodes = nodes.size();
        this.reversedQTable =q;
        this.RTable = new double[totalNodes][totalNodes];
        
        this.CTable = new double[totalNodes][totalNodes];
        
        for(int state = 0; state<totalNodes; state++)
        {
            Node s = nodes.get(state);
            for(int action = 0; action<totalNodes; action++)
            {
                if(state != action)
                {
                    Node a = nodes.get(action);
                    double val = (s.getDataPackets() + a.getDataPackets())/s.getNeighbor(a);
                    CTable[state][action] = val;
                }
                else
                {
                    CTable[state][action] = 0;
                }
                
            }
        }
        
    }
    public double[][] getQTable()
    {
        return this.QTable;
    }
    public double getCvalue(int currentNode, int nextNode)
    {
        return this.CTable[currentNode][nextNode];
    }

    // returns the q value of a give state action pair
    public double getQvalue(int currentNode, int nextNode)
    {
        return this.QTable[currentNode][nextNode];
    }

    public double getReverseQvalue(int currentNode, int nextNode)
    {
        return this.reversedQTable[currentNode][nextNode];
    }

    // sets q value
    public void setQvalue(int currentNode, int nextNode, double value)
    {
        this.QTable[currentNode][nextNode] = value;
    }

    public void updateQvalue(int x, int a, int b)
    {
        double val = this.reversedQTable[x][a] * 10;
        this.reversedQTable[x][b] = val;
        
    }
    public void updateQvalue2(int a, int y, int b)
    {
        double val = this.reversedQTable[a][y] * 10;
        this.reversedQTable[b][y] = val;
        
    }


    public double getQmaxValue(int state, double budget, HashSet<Node> univisited, HashMap<Node, Double> shortestPaths)
    {
        double max = QTable[state][1];
        
        Node current = nodes.get(state);
        for(int i = 0; i<QTable.length; i++)
        {
            Node next = nodes.get(i);
            if(current==next)
            {
                continue;
            }
            if(network.isFeasible(current, next, budget, shortestPaths, univisited))
            {
                double val = QTable[state][i];
                if(val > max)
                {
                    //System.out.println("test " + val + " " + max + " " + state + " " + i);
                    max = val;
                }
            }     
        }
        return max;  
    }
    public double getReverseQmaxValue(int state, double budget, HashSet<Node> univisited, HashMap<Node, Double> shortestPaths)
    {
        double max = reversedQTable[state][1];
        
        Node current = nodes.get(state);
        for(int i = 0; i<reversedQTable.length; i++)
        {
            Node next = nodes.get(i);
            if(current==next)
            {
                continue;
            }
            if(network.isFeasible(current, next, budget, shortestPaths, univisited))
            {
                double val = reversedQTable[state][i];
                if(val > max)
                {
                    //System.out.println("test " + val + " " + max + " " + state + " " + i);
                    max = val;
                }
            }     
        }
        return max;  
    }

    public void updateQvalue(int state, int action, double budget, HashSet<Node> unvisited, HashMap<Node, Double> shortestPaths)
    {
        this.QTable[state][action] = (1-this.learningRate)*(getQvalue(state, action)) + learningRate * discountFactor* getQmaxValue(action, budget, unvisited,shortestPaths);
    }

    public void updateQvalue2(int state, int action, double budget, HashSet<Node> unvisited, HashMap<Node, Double> shortestPaths)
    {
        /* System.out.println("Learning rate: "+ learningRate);
        System.out.println("Current Q value "+ getQvalue(state, action));
        System.out.println("R value : " + getRvalue(state, action));
        System.out.println("Discount factor: " + discountFactor);
        System.out.println("MAx Q value : "+ getQmaxValue(action,budget,unvisited, shortestPaths)); */
        this.QTable[state][action] = (1-this.learningRate)*(getQvalue(state, action)) + learningRate * (getRvalue(state, action) + (discountFactor*(getQmaxValue(action,budget,unvisited, shortestPaths))));
        //System.out.println("Updated Q : " + getQvalue(state, action));
    }
    public void updateReverseQvalue2(int state, int action, double budget, HashSet<Node> unvisited, HashMap<Node, Double> shortestPaths)
    {
        /* System.out.println("Learning rate: "+ learningRate);
        System.out.println("Current Q value "+ getQvalue(state, action));
        System.out.println("R value : " + getRvalue(state, action));
        System.out.println("Discount factor: " + discountFactor);
        System.out.println("MAx Q value : "+ getQmaxValue(action,budget,unvisited, shortestPaths)); */
        this.reversedQTable[state][action] = (1-this.learningRate)*(getReverseQvalue(state, action)) + learningRate * (getRvalue(state, action) + (discountFactor*(getReverseQmaxValue(action,budget,unvisited, shortestPaths))));
        //System.out.println("Updated Q : " + getQvalue(state, action));
    }

   /*  public int getQmaxAction(int state)
    {
        double max = Double.NEGATIVE_INFINITY
        int action = 0;

        for(int i = 0; i<QTable.length; i++)
        {
            double val = QTable[state][i];
            if(val > max)
            {
                max = val;
                action = i;
            }
            
        }
        return action; 
    } */

    public Node getQMaxValueR(int state, double budget, HashSet<Node> univisited, HashMap<Node, Double> shortestPaths)
    {
        double max = Double.NEGATIVE_INFINITY;
        Node bestNode = null;
       

        for(int i = 0; i<reversedQTable.length; i++)
        {
            Node current = nodes.get(state);
            Node next = nodes.get(i);
            if(current==next)
            {
                continue;
            }

            if(network.isFeasible(current, next, budget, shortestPaths, univisited))
            {
                double val = reversedQTable[state][i];
                if(val > max)
                {
                    bestNode = nodes.get(i);
                    max = val;
                }
            }
        }
        return bestNode; 
    }
    public Node getQMaxValue(int state, double budget, HashSet<Node> univisited, HashMap<Node, Double> shortestPaths)
    {
        double max = Double.NEGATIVE_INFINITY;
        Node bestNode = null;
       

        for(int i = 0; i<QTable.length; i++)
        {
            Node current = nodes.get(state);
            Node next = nodes.get(i);
            if(current==next)
            {
                continue;
            }

            if(network.isFeasible(current, next, budget, shortestPaths, univisited))
            {
                double val = QTable[state][i];
                if(val > max)
                {
                    bestNode = nodes.get(i);
                    max = val;
                }
            }
        }
        return bestNode; 
    }

    

    public double getRvalue(int state, int action)
    {
        return RTable[state][action];
    }

    public void updateRValue(int state, int action, int maxPrize)
    {
        RTable[state][action] = getRvalue(state, action) + W/maxPrize;
    }

    // for PMARL, multiplicative increase
    public void updateRValue(int episode, int state, int action, int maxPrize)
    {
        RTable[state][action] = getRvalue(state, action) + (Wi*episode)/maxPrize;
    }

    public void printTableQ()
    {
        System.out.println("Q TABLE");
        for(int i = 0; i<this.QTable.length; i++)
        {
            for(int j = 0; j<this.QTable.length; j++)
            {
                System.out.print(this.QTable[i][j] + " ");
            }
            System.out.println();
        }
    }

    public void printTableR()
    {
        System.out.println("R TABLE");
        for(int i = 0; i<RTable.length; i++)
        {
            for(int j = 0; j<RTable.length; j++)
            {
                System.out.print(RTable[i][j] + " ");
            }
            System.out.println();
        }
    }

    public void printTableC()
    {
        System.out.println("C TABLE");
        for(int i = 0; i<CTable.length; i++)
        {
            for(int j = 0; j<CTable.length; j++)
            {
                System.out.print(CTable[i][j] + " ");
            }
            System.out.println();
        }
    }

    public void printTableRQ()
    {
        if(reversedQTable == null)
        {
            return;
        }
        System.out.println("RQ TABLE");
        for(int i = 0; i<this.reversedQTable.length; i++)
        {
            for(int j = 0; j<this.reversedQTable.length; j++)
            {
                System.out.print(this.reversedQTable[i][j] + " ");
            }
            System.out.println();
        }
    }
    
    public double[][] symmetricQUpdate(double leanring, double discountFactor) {
        int n = this.QTable.length;
        this.discountFactor = discountFactor;
        this.learningRate = leanring;
        
        this.reversedQTable = new double[n][n];
        for(int i = 0; i<QTable.length; i++)
        {
            for(int j = 0; j<QTable.length; j++)
            {
                this.reversedQTable[j][i] = this.QTable[i][j];
               
            }
        }

       return reversedQTable;
    }

    public void symmetreyCheck()
    {
        for(int i = 0; i<QTable.length; i++)
        {
            for(int j = 0; j<QTable.length; j++)
            {
                if(this.QTable[i][j] != this.reversedQTable[j][i])
                {
                    System.out.println("Q table not symmetric");
                    return;
                }
            }
        }

        System.out.println("Q table is symmetric");

    }

    public int action(int state)
    {
        int ans = 0; 
        double best =Double.NEGATIVE_INFINITY;
        double[] i = this.reversedQTable[state];

       
        for (int j = 0; j<i.length; j++)
        {
            if(i[j] > best)
            {
                ans = j;
                best = i[j];
            }
        }
        return ans;
    }
}

