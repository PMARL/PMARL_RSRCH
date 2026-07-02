// THIS IS THE MAIN FUNCTION WHERE YOU CAN RUN STUFF

import java.io.IOException;
import java.util.*;


public class Main
{
    public static void main(String[] args) throws IOException 
    {
        
        Network network = new Network();
        Algorithms algorithms = new Algorithms();
        NetworkFileManager file = new NetworkFileManager(); 
        Map<Node, Double> shortest = new HashMap<>();
        
        //----------------------------------------------------------------------VARIABLE SETUP -----------------------------------------------------------------------
        
        int length = 1000;
        int width = 1000;
        int numOfNodes = 30; // total num of nodes
        int tr = 0; // this is for how long every node should be placed compared to other node
        int range = 100; // robot/node range
        double B = 50; // budget
        int start = 0; // start location
        int end = 0; // end location / end depot
        boolean isEndEqualToStart = true;
        int episode =1000;
        int agents = 30;
        double learningRate = 0.1;
        double discountFactor = 0.7;
        double tradeoff = 0.5;
        /* int min =800; // min data packets (used for network longevity)
        int max = 1000; // max data packets (used for network longevity) */
        
        //-------------------------------------------------------------------LOAD NETWORK OR GENERATE RANDOM----------------------------------------------------------------
        //List<Node> nodes = file.loadNetwork("/E:/Research/Networks/Network1.txt"); // load from a file 
        List<Node> nodes =  network.generateNodes(length, width, numOfNodes, tr, isEndEqualToStart); // generate random network, end == true means that start and end are same 0,0; if it is false then start will be 0,0 and end will be width,length
        network.addEdges(nodes, range); // make the network aware of neighbors
        shortest = network.findShortestPaths(nodes, nodes.get(start)); // shortest path using dijkstras

        //--------------------------------------------------------------------PRINT NODE INFO-------------------------------------------------------------------------------
        for(Node node : nodes)
        {
            node.nodeInfo();
        }
        //prints shortest path to each node from the start
        for(Node node: shortest.keySet())
        {
            double cost = shortest.get(node);
            System.out.println("Shortest path to Node " + node.getID() +" from node " + start +  ": " + cost);
        }
        // prints the total data available to be collected
        network.totalDataAvailable(nodes);
        System.out.println("The budget for robots is " + B + "Wh");

        //-------------------------------------------------------------------------ALGORITHMS----------------------------------------------------------------------------
        List<Integer> tsp1 = algorithms.gTSP1(nodes, start, B); // Selects node with highest prize
       
        List<Integer> tsp2 =  algorithms.gTSP2(nodes, start, B, isEndEqualToStart); // selects node with highest prize cost ratio PCR
       
        List<Integer> csp1 = algorithms.greedy1CSP(nodes, start, B); // Covering salesman highest prize
       
        List<Integer> csp2 = algorithms.greedy2CSP(nodes, start, B); // Covering salesman highest prize cost ratio PCR

        List<Integer> marl = algorithms.MARL(nodes, start, end, isEndEqualToStart, B, episode,agents, learningRate, discountFactor, tradeoff);
       
        Pair cmarl = algorithms.CMARL(nodes,start, end, isEndEqualToStart, B, episode,agents, learningRate,discountFactor, tradeoff);
        
    
        /* long startSpanning = System.currentTimeMillis();
        List<Integer> sca = algorithms.SCA(nodes, start, B, length);
        long endSpanning = System.currentTimeMillis();
        System.out.println("SCA  time is :" + (endSpanning-startSpanning)); */
        
        /* long startY = System.currentTimeMillis();
        List<Edge> yang = algorithms.nYangs(nodes, 0, B); 
        long endY = System.currentTimeMillis();
        double timeY = (double)endY - startY ;
        System.out.println("Yang time is " + timeY); *

        /* long startTL = System.currentTimeMillis();
        algorithms.TL1( nodes1, cmarl);
        long endTL = System.currentTimeMillis();
        double timeTL = (double)endTL - startTL ;
        System.out.println("Transfer Learning time is " + timeTL);  */

        // ------------------------------------------------------------------VISUALIZING----------------------------------------------------------
       /*  Visualizer visualize1 = new Visualizer(nodes, 1000, 1000);
        visualize1.run(); */
        
        // visualizes the routes
        /* Visualizer visualize = new Visualizer(nodes,spanning, 1000, 1000);
        visualize.run(); */

        /* Visualizer visualize1 = new Visualizer(nodes, length, width, csp2);
        visualize1.run();
        Visualizer visualize = new Visualizer(nodes, length, width, sca);
        visualize.run();
 */
        
        // ----------------------------------------------------------------- Saving random network to txt file------------------------------------------------
        
        /* Scanner scanner = new Scanner(System.in);
        System.out.println("Do you want to save the network? 0/1");
        int ans = scanner.nextInt();
        if(ans==1)
        {
            System.out.println("Give the file number");
            int fileNumber = scanner.nextInt();
            file.saveNetwork(nodes, fileNumber);
            System.out.println("Network saved");
        }
        else
        {
            System.exit(0);
        } */
        
      
        
        //-------------------------------------------------ILP STUFF------------------------------------------------------
        /* ILP ilp = new ILP( nodes);

        ilp.print2DString();
        ilp.printPrize();
        ilp.printSize();
        

        for(Node n : nodes)
        {
            System.out.println(n.getX()+ " "+ n.getY());
        }
        for(Node n : nodes)
        {
            System.out.println(n.getID()+ "(" + n.getDataPackets() + ")");
        } */
        
        
        
        
        

        

        
    
      
      
    } 
    
}


