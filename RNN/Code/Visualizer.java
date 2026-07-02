import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JPanel;
public class Visualizer extends JPanel implements Runnable {

    

    private List<Node> nodes;
    private double graphWidth;
    private double graphHeight;
    private int scaling = 25;
    private int ovalSize = 6;
    private List<Integer> algo;
    private List<Edge> yang;
    

    public Visualizer(List<Node> nodes, double width, double height, List<Integer> algo) {
        this.nodes = nodes;
        this.graphWidth = width;
        this.graphHeight = height;
        this.algo = algo;
        invalidate();
        repaint();
    }
    public Visualizer(List<Node> nodes, double width, double height) {
        this.nodes = nodes;
        this.graphWidth = width;
        this.graphHeight = height;
        this.algo = null;
        invalidate();
        repaint();
    }
    public Visualizer(List<Node> nodes,List<Edge> yang, double width, double height) {
        this.nodes = nodes;
        this.graphWidth = width;
        this.graphHeight = height;
        this.yang = yang;
        invalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double xScale = ((getWidth() - 5 * scaling) / (graphWidth));
        double yScale = ((getHeight() - 5 * scaling) / (graphHeight));

        List<Point> graphPoints = new ArrayList<Point>();
        for (Node node : this.nodes) {
            double x1 = (node.getX() * (xScale) + (2 * scaling));
            double y1 = ((graphHeight - node.getY()) * yScale + scaling);
            Point point = new Point();
            point.setLocation(x1, y1);
            graphPoints.add(point);
        }

        g2.setColor(Color.white);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // Draw ovals in blue
        g2.setColor(Color.black);
        for (int i = 0; i < graphPoints.size(); i++) {
            double x = graphPoints.get(i).x - ovalSize / 2;
            double y = graphPoints.get(i).y - ovalSize / 2;
            double ovalW = ovalSize*4;
            double ovalH = ovalSize*4;
            Ellipse2D.Double shape = new Ellipse2D.Double(x, y, ovalW, ovalH);
            g2.fill(shape);  
        }

        // Draw node IDs in red with increased spacing
        g2.setColor(Color.red);
        FontMetrics metrics = g2.getFontMetrics();
        int textHeight = metrics.getHeight();
        int textPadding = 30; // Adjust space between id and node 
        Font originalFont = g2.getFont();
        Font largerFont = originalFont.deriveFont(originalFont.getSize() + 20f); // adjust for font size
        g2.setFont(largerFont);

        for (int i = 0; i < graphPoints.size(); i++) {
            double x = graphPoints.get(i).x - (metrics.stringWidth("" + this.nodes.get(i).getID()) / 2.0);
            double y = graphPoints.get(i).y + ovalSize + textHeight + textPadding;
            g2.setColor(Color.red);
            g2.setFont(largerFont.deriveFont(Font.BOLD));
            g2.drawString(String.format("%d", this.nodes.get(i).getID()), (int) x, (int) y);
            
            // Calculate the x-coordinate for data packets to align them with the ID
            double xData = x + metrics.stringWidth("" + this.nodes.get(i).getID()) + 22; // Adjust this value as needed
            
            g2.setColor(Color.darkGray);
            g2.drawString(String.format("(%d)", this.nodes.get(i).getDataPackets()), (int) xData, (int) y);
        }
        
        Stroke stroke = new BasicStroke(2f);
        g2.setStroke(stroke);

        // Use for TSP,CSP and MARL
        if(algo != null)
        {
            g2.setColor(Color.green); 
            for (int j = 0; j < algo.size() - 1; j++) {
            int nodeIndex1 = algo.get(j);
            /* g2.setColor(Color.blue);
            Stroke dashed = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{9}, 0);
            g2.setStroke(dashed);
            double radius = 300;
            double diameter = radius * 2;
            double xCircle = graphPoints.get(nodeIndex1).x - radius;
            double yCircle = graphPoints.get(nodeIndex1).y - radius;
            g2.draw(new Ellipse2D.Double(xCircle, yCircle, diameter, diameter));
            g2.setStroke(stroke); // Reset to original stroke */
            int nodeIndex2 = algo.get(j + 1);
            Point point1 = graphPoints.get(nodeIndex1);
            Point point2 = graphPoints.get(nodeIndex2);
            g2.drawLine(point1.x, point1.y, point2.x, point2.y);
            }
        }
        

        // Use for Yangs
        /* g2.setColor(Color.RED);
            for (int j = 0; j < yang.size(); j++) {
            int nodeIndex1 = yang.get(j).start.getID();
            int nodeIndex2 = yang.get(j).end.getID();
            Point point1 = graphPoints.get(nodeIndex1);
            Point point2 = graphPoints.get(nodeIndex2);
            g2.drawLine(point1.x, point1.y, point2.x, point2.y);
        } */        
    }



    public void run() 
    {
        String graphName = "Sensor Network Graph";
        JFrame frame = new JFrame(graphName);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(this);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
