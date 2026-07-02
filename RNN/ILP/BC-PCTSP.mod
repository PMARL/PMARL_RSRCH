/*********************************************
 * OPL 20.1.0.0 Model
 * Author: Chris
 * Creation Date: Aug 22, 2023 at 1:24:17 PM
 *********************************************/
int n = 10;

// Indexes for the stops
//different ranges used for problem formulation
range nodes = 1 .. n;
range node = 2 .. n;
range nod = 1 .. n-1;
range no = 2 .. n-1;

// Cost Matrix cij
//variables from PC_TSP.dat
float Cost[nodes][nodes] = ...;
float p[nodes] =...;



// Decision variables xij
dvar boolean x[nodes][nodes];

// Rank variable ui
dvar float+ u[nodes];

// Objective function
//minimize sum (i in nodes) sum(j in nodes) x[i][j] * Cost[i][j];
maximize sum(i in node) sum(j in nodes) p[i] * x[i][j];
// Constraints
subject to {
	constraint_self_travel:
    forall (i in nodes) x[i][i] == 0; // Easier than adding i != j everywhere

    /*rule_one_out:
    forall (i in nodes) sum (j in nodes) x[i][j] <= 1;
	
	
    rule_one_in:
    forall (j in nodes) sum (i in nodes) x[i][j] <= 1;
    
    in_equal_out:
    forall (j in nodes) sum(i in nodes:i!=j) x[i][j]== sum(k in nodes:k!=j ) x[j][k];*/
    
    contraint_1a:
    sum(j in node) x[1][j] ==1;
    constraint_1b:
    sum(i in node) x[i][1] ==1;
    
    constraint_2:
    forall (k in nodes){
      sum(i in nodes) x[i][k] == sum(j in nodes) x[k][j] ;
      sum(i in nodes) x[i][k] <= 1;
    } 
    constraint_3:
    sum (i in nodes) sum(j in nodes) x[i][j] * Cost[i][j]<=6000;
    //sum(i in node) sum(j in nodes) p[i] * x[i][j] >= 200;
    
    

    rule_no_subtour_1:
    forall(i in node) u[i]<=n;
    rule_no_subtour_2:
    forall(i in node) u[i]>=2;
    
    rule_no_subtour_3:
    forall(i,j in node) u[i]-u[j]+1 <= (n-1) * (1-x[i][j]);

    u[1] == 0; // Fixes the rank of the first node
}

// Print result
execute POSTPROCESS {
    var totalCost = 0; // Initialize the total cost variable
    var prizeCol =0;
    for (var i in nodes)
        for (var j in nodes) {
            if (x[i][j] > 0) {
                write(i, " -> ", j, "\n");
                totalCost += x[i][j] * Cost[i][j]; // Add the cost of the current edge to the total
                prizeCol+= p[i] * x[i][j];
            }
        }
    writeln("Total Cost: ", totalCost); // Print the total cost
    writeln("PrizeCol: ", prizeCol); // Print the total cost
}