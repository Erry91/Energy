import java.util.ArrayList;
import java.util.List;

/*
TO DO 
decide how to get the current position of the robot, so the starting point that is
needed for the inverse kinematics. 
*/
public class OnlinePlanner implements Runnable{

	private Target currPosition;
	private int targetsLength;
	private Target[] targets;
	private Robot robot;
	
	private double[][] link_length;
	private double[][] joint_limits;
	private double[] mass;
	private List<double[][]> inertia_tens;
	
	private double[][][] thetaM;
	
	private double acceleration;
	private double velocity;
	
	private double[][] r_ici; //it has to be structured as 6x3 matrix to work down in the dynamic analysis
	private double[][] r_c; //6x3 matrix as well. The reason is because in loops 1x3 vectors are needed and will be taken by having r_c[i] instructions
	
	
	public OnlinePlanner(Target[] targets, Robot robot)
	{
		
		targetsLength = copyTargets(targets);
		this.robot = robot;
		this.link_length = robot.getParameters("link_length");
		this.joint_limits = robot.getParameters("joint_limits");
		this.mass = robot.getLinkMasses();
		this.inertia_tens = robot.getInertiaTens();
		
		this.acceleration = 1.0;
		this.velocity = 0.2;		
		
		this.currPosition = targets[0];
	}
	
	public void run(){
		System.out.println("The online planner has been started.");
		
		//Somebody has to decide them
		double x_sample = 1E-3;
		double t_sample = 0.01;
		
		// TODO get current position somehow
		
	for( int i = 0; i < targetsLength ; i++)
	{
		//Line to be removed
		if(currPosition == targets[i]){
			System.out.println("Current position is equal to the next target.");
			continue;
		}
		//
		
		Path path = new Path(currPosition, targets[i], t_sample, x_sample);
		path.SetMaxAcc(acceleration);
		path.SetMaxVel(velocity);
		path.Interpolate();
		
		//Trajectory is private so I can interrogate it directly
		Trajectory pathTrajectory = path.getTrajectory();
		int trajectoryLength = pathTrajectory.points.size();

		System.out.println("Interpolation over. Points size: " + trajectoryLength + " Time instants : " + pathTrajectory.timeInstants.size());
		
		
		thetaM = new double[6][8][trajectoryLength]; //trajectoryLength without +1 ?? 

		long time1 = System.nanoTime();
		int error = 0;
		for(int j = 0; j < trajectoryLength && error == 0; j++)
		{
			/*
			 * PART where inverse kinematics is performed
			 */
			
			error = onlineInvKinematics(pathTrajectory.points.get(j), j);
		
		}
		
		//displayThetaM(0);
		
		//Check if the configurations left can be followed by the robot
		int[] Solution_vector = new int[thetaM[0][0].length];
		ArrayList<int[]> Solutions = new ArrayList<int[]>();
		configIteration(thetaM, 0, 0, pathTrajectory.timeInstants,Solution_vector, Solutions);
		
		long time2 = System.nanoTime();
		int NumOfSolutions = Solutions.size();
		System.out.println("End of Path Planning. The number of solutions found is : " + NumOfSolutions);
		System.out.println("Time for the inverse Kinematics: " + (time2-time1)/1E9);
		
		if(NumOfSolutions == 0)
		{
			System.out.println("Impossible to reach that target since there is no solution to the inverse kinematic problem.");
			break;
		}
		//Once we have at least a series of configuration to the final target, the inverse dynamics
		//can be calculated.
		
		onlineInvDynamics(pathTrajectory, Solutions);
		
	}
		
		
	}
	
	
	private int copyTargets(Target[] targetsTOstore)
	{
		int length = targetsTOstore.length;
		this.targets = new Target[length];
		for(int i = 0; i<length; i++)
		{
			this.targets[i] = targetsTOstore[i];
		}
		return length;
	}
	
	
	private int onlineInvKinematics(Target Next_position, int point_i)
	{
		/*
		 * Need to get the coordinates of the WIRST center Pw
		 */
		
		double[] P = Next_position.getPosition();
		double a6 = link_length[5][0];
		double[] gw = {a6*Next_position.getRotation()[0][2], a6*Next_position.getRotation()[1][2], a6*Next_position.getRotation()[2][2]};
		double[] Pw = new double[3];
		Pw = Matrix.subtractVectors(P,gw);
		
		for(int i = 0; i < Pw.length; i++){Pw[i] = P[i] - gw[i];}
	
		
		double[] theta_1 = solveTheta1(Pw);
		
		for(int i = 0; i < theta_1.length; i++)
		{
			double[][] theta_23 = solveTheta23(Pw, theta_1[i], i);
			
			if(theta_23[0][0] == 1000 || theta_23[1][0] == 1000)
			{
				System.out.println("Trajectory impossible to track: theta_2 or theta_3 assume impossible values");
				System.out.println("hence, the whole sequence of points is impossible.");
				return -1; //will be used by the caller to stop the whole trajectory
			}
			
			for(int j = 0; j < theta_23.length; j++)
			{
				double theta2 = theta_23[0][j] - Math.PI/2;
				double theta3 = Math.PI/2 - theta_23[1][j];
				double[][] theta_456 = solveTheta456(Next_position, theta_1[i], theta2, theta3, point_i ); 
				
				if(theta3 < -Math.PI/2)
				{
					theta3 += 2*Math.PI; 
				}
				
				for(int k = 0; k < theta_456[0].length; k++)
				{
					int index  = (int) ((i)*Math.pow(2, 2*(i)) + (j)*2 + k + 1);
					index--;
					
					
				thetaM[0][index][point_i] = theta_1[i];
				thetaM[1][index][point_i] =	theta2;
				thetaM[2][index][point_i] =	theta3;
				thetaM[3][index][point_i] = theta_456[0][k];
				thetaM[4][index][point_i] =	-theta_456[1][k];
				thetaM[5][index][point_i] = theta_456[2][k];
					
				}
				
			}
		}
		
		//CHECK limitation
		thetaM = this.applyLimitation(thetaM, point_i);
		
		if(point_i == 0){displayThetaM( point_i );}
		if(point_i == (thetaM[0][0].length - 1)){displayThetaM( point_i );}
		
 		return 0;
	}
	
	
	private void onlineInvDynamics(Trajectory trajectory, ArrayList<int[]> Kin_Solutions)
	{
		int N = trajectory.points.size();
		double minEnergy = Double.MAX_VALUE;
		int[] optKinSolution;
		double[][] optDynSolution = new double[N][6];
		
		for(int[] solution : Kin_Solutions)
		{
			int first_conf = solution[0];
			double[] prev_theta = new double[6]; 
			double[] prev_dtheta = {0, 0 , 0, 0 , 0, 0};
			double energy = 0;
			double[][] dynSolutionVector = new double[N][6];
			
			for(int i = 0; i < prev_theta.length; i++){prev_theta[i] = thetaM[i][first_conf][0];} //initialization of the vector PREV_THETA
			
			
			for(int i = 0 ; i < N; i++)
			{
				//MAIN LOOP where the dynamic analysis is performed
				int index = solution[i+1];
				double[] theta = new double[6];
				for(int j = 0; j < 6; j++){ theta[j] = thetaM[j][index][i+1];}
				double[] dtheta = new double[6];
				double[] ddtheta = new double[6];
				
				for(int j = 0; j < 6; j++){ 
					dtheta[j] = (theta[j] - prev_theta[j])/trajectory.timeInstants.get(i);
					ddtheta[j] = (dtheta[j] - prev_dtheta[j])/trajectory.timeInstants.get(i);
				}
					
//	FROM MATLAB   T_ex=Ti(:,cont7);
//                f_ex=fi(:,cont7);
			
				//The following two lines will have to be substitued with the proper external forces
				//and torques when the interpolation has been changed accordingly
				
				double[] T_ext = {0,0,0};
				double[] f_ext = {0,0,0};
				double[] dynamicSol = new double[6];
				
				dynamicSol = dynamicAnalysis(theta, dtheta, ddtheta, T_ext, f_ext);
				dynSolutionVector[i] = dynamicSol;
				
				double power = powerCalculation(dynamicSol, dtheta);
				
				energy += power*trajectory.timeInstants.get(i);
				
				
				
				
				prev_theta = theta;
				prev_dtheta = dtheta;
			}
			
			/*
			 * I save all the data regarding the optimal trajecotry in case they are needed.
			 * For the 3Dmap only the energy consumption would be needed but here the planner
			 * has to give more information to the robot on how to actually follow the trajectory.
			 */
			if(energy < minEnergy){
				minEnergy = energy;
				optDynSolution = dynSolutionVector;
				optKinSolution = solution;
			}
		}
	}
	
	private double[] dynamicAnalysis(double[] theta, double[] dtheta, double[] ddtheta, double[] T, double[] f){
		double[] dynamic_solution = new double[6];
		
		/*
		 * alpha_1 		-> angular acceleration
		 * acc_endL 	-> acceleration of the end of the link
		 * acc_centerL 	-> acceleration of the center of the link 
		 */
		
		//double[] w_1, alpha_1, acc_centerL1, acc_endL1, w, acc_endL, acc_centerL, dw;
		double[] w_1 = {0,0,0}, alpha_1 = {0,0,0} , acc_centerL1 = {0,0,0}, acc_endL1 = {0,0,0};
		double[] z0 = {0,0,1}, g0 = {0,0,-9.81};
		double[] gi;
		double[][] w = new double[6][3], alpha = new double[6][3], dw = new double[6][3], acc_endL = new double[6][3];
		double[][] acc_centerL = new double[6][3];
		
 		double[] dw_1 = {0,0,0};
		double[][] rot_i;
		double[][] R0_i;
		
		//Forward Recursion
		for(int i = 0; i < 6; i++)
		{
			rot_i = (robot.Hto_from(i+1, i, theta)).getRotation();
			rot_i = Matrix.transpose(rot_i);
			double[] temp = Matrix.addMatrices(w_1, Matrix.multiplyScalMatr(dtheta[i], z0)); // (w_0 + z0*dtheta(i))
			w[i] = Matrix.multiplyMatrixVector(rot_i, temp); //R0_1' * (w_0 + z0*dtheta(i))
			
			temp = Matrix.crossProduct(w[i], Matrix.multiplyScalMatr(dtheta[i], z0));
			double[] temp1;
			
			for(int j = 0; j < 3; j++)
			{
				alpha[i][j] = alpha_1[j] + z0[j] * ddtheta[i] + temp[j];
				
				dw[i][j] = dw_1[j] + z0[j]*ddtheta[i] + temp[j];
			}
			alpha[i] = Matrix.multiplyMatrixVector(rot_i, alpha[i]);  
			
			
			
			
			/*
			 * Update the values for next iteration
			 */
		
			alpha_1 = alpha[i];
			w_1 = w[i];
			dw_1 = dw[i];
			acc_endL1 = acc_endL[i];
			acc_centerL1 = acc_centerL[i];
		}
		
		//Backward recursion
		
		
		/*
		 R0_i=translation_for_num(0,i,1,theta);
		 rot_i=translation_for_num(i,i+1,1,theta);
		 
		 f(:,7)=fi;
		 Tr(:,7)=Ti;
		 
		 r_ici is involved in cross products: care for dimensions!
		 */
		double[] force_i = f; //is the external force applied at the end effector. It will be used to calculate the next force in NE method.
		double[] force_next = new double[f.length];
		double[] torque_i = T;//is the external torque applied at the end effector. It will be used to calculate the next torque in NE method.
		double[] torque_next = new double[T.length];
		
		//double[][] inertia_i;
		double[] temp1, temp2, temp3, temp4;
		
		for(int i = 5; i >= 0 ; i--) //in matlab the loop is 6:1
		{
			
			
			rot_i = (robot.Hto_from(i+2, i+1, theta)).getRotation(); //the indixes of the transf matrices are the same as in matlab though
			R0_i = (robot.Hto_from(i+1, 0, theta)).getRotation();
			
			R0_i = Matrix.transpose(R0_i);
			
			gi = Matrix.multiplyMatrixVector(R0_i, g0);
		
			//force_next = Mass(i)*a_center(:,i)-mass(i)*gi + rot_i*f(:,i+1);
			for(int j = 0; j < force_next.length; j++)
			{
				force_next[j] = mass[i]*acc_centerL[i][j] - mass[i]*gi[j];
			}
			force_next = Matrix.addMatrices(force_next, Matrix.multiplyMatrixVector(rot_i, force_i));
			
			//intertia_i = inerti
			
			temp1 = Matrix.crossProduct(Matrix.multiplyMatrixVector(rot_i, force_i), r_ici[i]);
			temp2 = Matrix.crossProduct(force_i,r_c[i]);
			temp3 = Matrix.crossProduct(w[i], Matrix.multiplyMatrixVector(inertia_tens.get(i), w[i]));
			temp4 = Matrix.multiplyMatrixVector(inertia_tens.get(i), alpha[i]);
			torque_next = Matrix.multiplyMatrixVector(rot_i, torque_i);
			
			for(int j = 0; j < torque_next.length; j++)
			{
				torque_next[j] += temp1[j] - temp2[j] + temp3[j] + temp4[j];
			}
			
			//Trr(i)=Tr(:,i)'*[0;0;1];
			//This calculation (from MATLAB) basically extrapulates only the third element of the 1x3 vector
			dynamic_solution[i] = torque_next[2];
			
		}
		
		
		
		return dynamic_solution;
	}
	
	double powerCalculation(double[] T, double[] dtheta){
		double P = 0;
		
		for(int i = 0; i < T.length; i++){
			P += Math.abs(T[i]*dtheta[i]);
		}
		
		return P;
	}
	
	
	private double[] solveTheta1(double[] Pw)
	{
		/*
		 * Input Pw: vector 3X1 of wirst coordinates
		 */
		
		//Instantiate the vector
		double[] theta_1 = new double[2];
		
		//calculate first theta1
		theta_1[0] = Math.atan2(Pw[1], Pw[0]);
		
		//calculate second theta1
		theta_1[1] = Math.atan2(-Pw[1], -Pw[0]);
		
		return theta_1;
	}
	
	/*
	 * Our notation is different form the paper.
	 * We adopt the iterative DH notation.
	 * If it's confronted with MATLAB module we have:
	 * MATLAB  ------   JAVA
	 * a1				a2
	 * a2				a4
	 * a3				a5
	 * a4				a6
	 * -------------------
	 * d1				d1
	 * d2				d3
	 * 
	 */
	
	private double[][] solveTheta23(double[] Pw, double theta_1 , int index)
	{
		
		double a2 = link_length[1][0];
		double a4 = link_length[3][0];
		double a5 = link_length[4][0];
		
		double d1 = link_length[0][1];
		double d3 = link_length[2][1];
		
		//standard values for initialization if target is out of reach
		double[] theta_3 = {1000 , 0}; 
		double[] theta_2 = {1000, 0};
		
		double[][] theta_23 = new double[2][2];
	
		double rr;
		double a;
		
		if(index == 0)
		{
			rr = Math.sqrt(Math.pow((Pw[0] - a2*Math.cos(theta_1)), 2) + Math.pow((Pw[1] - a2*Math.sin(theta_1)), 2));
		}
		else
		{
			rr = - Math.sqrt(Math.pow((Pw[0] - a2*Math.cos(theta_1)), 2) + Math.pow((Pw[1] - a2*Math.sin(theta_1)), 2));
		}
		//a is the cosine of THETA 3
		a = ((-(d3*d3) - (a4 + a5)*(a4 + a5) + (Pw[2] - d1)*(Pw[2] - d1) + rr*rr ))/(2*d3*(a4 + a5));
		
//		System.out.println(" Questa cazzo di 'rr' vale: " + rr );
//		System.out.println(" Questa cazzo di 'a' vale: " + a );
		
		if(a < -1 || a > 1 )
		{
			theta_23[0] = theta_2;
			theta_23[1] = theta_3;
			System.out.println("Impossible to reach that target.");
			return theta_23;
		}
		else
		{
			theta_3[0] = Math.atan2(Math.sqrt(1 - a*a), a);
			theta_3[1] = Math.atan2(-Math.sqrt(1 - a*a), a);
			
			theta_23[1] = theta_3;
		}
		for(int i = 0; i < theta_3.length; i++)
		{
			theta_2[i] = Math.atan2((Pw[2] - d1),rr) + Math.atan2((a4 + a5)*Math.sin(theta_3[i]), d3 + (a4 + a5)*Math.cos(theta_3[i])); 
		}
		
		theta_23[0] = theta_2;		
		return theta_23;
		
	}
	
	private double[][] solveTheta456(Target target_position, double theta_1, double theta_2, double theta_3, int point_i){
		
		/*
		 * TODO
		 * 1: get the target rotation
		 * 2: get from the robot the rotational matrix from 0 to TOOL
		 * 3: build the matrix from which the Euler's angles will be calculated
		 * 
		 * IMPORTANT: needed a way to read the joint angles of the previous configuration 
		 * HOW DO I GET THAT?
		 */
			

		
		double[][] R_zyz = new double[3][3];
		double[] theta_values = {theta_1, theta_2, theta_3, 0, 0 , 0};
		Target T0_3 = robot.Hto_from(3, 0, theta_values);
		double[][] R0_3 = T0_3.getRotation();
		double[][] R33 = {
				{0, 0, 1},
				{0, 1, 0},
				{-1, 0, 0}
				
		};
		double[][] R6tcp = {
				{1, 0, 0},
				{0, 1, 0},
				{0, 0, 1}
				
		};
		double[][] R0tcp = target_position.getRotation();
		
		
		//Rzyz=(R0_3*R3_3)'*R0_TCP*R6_TCP';
		R_zyz = Matrix.multiplyMatrices(R0tcp, Matrix.transpose(R6tcp));
		R_zyz = Matrix.multiplyMatrices(Matrix.transpose(Matrix.multiplyMatrices(R0_3, R33)), R_zyz);
				
		
		double theta_46;
		double[] theta_4 = new double[2];
		double[] theta_5 = new double[2];
		double[] theta_6 = new double[2];
		
		//double[][] theta_456;
		
 		double temp = Math.sqrt(R_zyz[2][0]*R_zyz[2][0] + R_zyz[2][1]*R_zyz[2][1]);
		
		theta_5[0] = Math.atan2(temp, R_zyz[2][2]);
		theta_5[1] = Math.atan2(-temp, R_zyz[2][2]);
		
		for(int i = 0; i < theta_5.length ; i++)
		{
			if(-0.0001 < theta_5[i] && theta_5[i] < 0.0001){
				//theta_5 is 0 and we're in a singularity case
				
				if(point_i == 0) {
					//previous_theta4_TEST = 0;
					theta_4[i] = 0;
					}
				else { 
					//previous_theta4_TEST = theta4Optimal(theta_5[i], theta_1, theta_2, theta_3, point_i);
					theta_4[i] = theta4Optimal(theta_5[i], theta_1, theta_2, theta_3, point_i);
				}
				
				//theta_4[i] = previous_theta4_TEST;
				theta_46 = Math.atan2(R_zyz[1][0], R_zyz[0][0]);
				
				theta_6[i] = theta_46 - theta_4[i];
				
			}
			else
			{//theta_5 is NOT 0 and we can calculate the other angles
				temp = Math.sin(theta_5[i]);
				theta_4[i] = Math.atan2(R_zyz[0][2]/temp, -R_zyz[1][2]/temp);
				theta_6[i] = Math.atan2(R_zyz[2][0]/temp, R_zyz[2][1]/temp);
			}
		}
		
		double[][] theta_456 = new double[3][2];
		theta_456[0] = theta_4;
		theta_456[1] = theta_5;
		theta_456[2] = theta_6;
		
		//DEBUG LINE
//		if(point_i == 0){
//			System.out.println("R_zyz :");
//			Matrix.displayMatrix(R_zyz);
//			System.out.println("R0_3 : ");
//			Matrix.displayMatrix(R0_3);
//			System.out.println("Theta1: " + theta_1 + "  theta2: " + theta_2 + "  theta3: " + theta_3);
//			System.out.println("Theta4: " + theta_4[0] + " or " + theta_4[1]);
//			System.out.println("Theta5: " + theta_5[0] + " or " + theta_5[1]);
//			System.out.println("Theta6: " + theta_6[0] + " or " + theta_6[1]);
//			
//		}
		
		
		
		return theta_456;
		/*
		 * REMINDER
		 * in case performance change significantly it could be possible to assign
		 * the theta values directly to the 3x2 matrix and then return it.
		 */
	}
	
	private double theta4Optimal(double theta5, double theta1, double theta2, double theta3, int index){
		/*
		 * Theta4 of the previous point (i-1) could be any of the 8 previous configurations.
		 * Therefore it is chosen the angle belonging to the configuration that covers less space 
		 * between i-1 and i in terms of theta-1,2,3.
		 * 
		 * We ALREADY know that theta5 is a SINGULARITY (very close to zero)
		 */
		double minSum = Double.MAX_VALUE;
		double temp = 0;
		int configIndex = 0;
		
		if (theta5 < 0){
			for(int i = 0; i < thetaM[0].length; i++){
				if(thetaM[0][i][index-1] == 0){
					continue;
				}
				if(thetaM[4][i][index-1] < 0){
					//theta_sing=[theta_sing,abs(thetaM(1:3,i,t-1)-[theta1;theta2;theta3])];
					temp = Math.abs(thetaM[0][i][index-1] - theta1) + Math.abs(thetaM[1][i][index-1] - theta2) + Math.abs(thetaM[2][i][index-1] - theta3);
					if(temp < minSum){
						minSum = temp;
						configIndex = i;
					}
				}
					
				
			}
			
		} else {
			if (theta5 > 0){
				
				for(int i = 0; i < thetaM[0].length; i++){
					if(thetaM[0][i][index-1] == 0){
						continue;
					}
					if(thetaM[4][i][index-1] > 0){
						//theta_sing=[theta_sing,abs(thetaM(1:3,i,t-1)-[theta1;theta2;theta3])];
						temp = Math.abs(thetaM[0][i][index-1] - theta1) + Math.abs(thetaM[1][i][index-1] - theta2) + Math.abs(thetaM[2][i][index-1] - theta3);
						if(temp < minSum){
							minSum = temp;
							configIndex = i;
						}
					}
				}
				
			} else {
				
				for(int i = 0; i < thetaM[0].length; i++){
					if(thetaM[0][i][index-1] == 0){
						continue;
					}
					
						temp = Math.abs(thetaM[0][i][index-1] - theta1) + Math.abs(thetaM[1][i][index-1] - theta2) + Math.abs(thetaM[2][i][index-1] - theta3);
						if(temp < minSum){
							minSum = temp;
							configIndex = i;
						}
				}
			
			}
		
		}
		
		return thetaM[3][configIndex][index-1];
	}
	
	private double[][][] applyLimitation(double[][][] ThetaM, int point_i)
	{
		
		for(int i = 0; i < ThetaM[0].length ; i++)
		{
			for(int j = 0; j < ThetaM.length ; j++)
			{
				if(ThetaM[j][i][point_i] < this.joint_limits[j][0] || ThetaM[j][i][point_i] > this.joint_limits[j][1])
				{
					//IF a joint exceeds its limits, the all configuration is compromised, hence 
					//it will be put to zero.
					
					
					//System.out.println("Il giunto " + j + " oltrepassa i suoi limiti");
					
					for(int k = 0; k < ThetaM.length ; k++ )
					{
						ThetaM[k][i][point_i] = 0;
					}
					
					break; //no need to check the other joints in this very configuration.
				}
			}
		}
		return ThetaM;
	}
	
	
	private void configIteration(double[][][] ThetaM, int point_i, int prev_config, ArrayList<Double> trajectoryTimes, int[] current_solution, ArrayList<int[]> Solutions){
		/*
		 * This function should check if the path can still be followed even if it doesn't 
		 * exceed the joints limitations. The reason is because the path could be too fast for
		 * the robot, and in that case the sequence of configuration chosen has to be discarded.
		 */
		int N = ThetaM[0][0].length; //137
		int error = 0;
		
		
		for(int currentConfig = 0; currentConfig < ThetaM[0].length; currentConfig++)
		{
			if (point_i == 0)
			{
				error = 0;
			}
			else
			{
				if(ThetaM[0][currentConfig][point_i] == 0 && ThetaM[1][currentConfig][point_i] == 0)
				{
					//In this case the configuration has already been put to ZERO from Apply_limitation
					//so there's no reason to check it again
					error  = 1;
				}
				else{
				error = checkConfiguration(currentConfig, prev_config, point_i, ThetaM, trajectoryTimes);
				}
			}
				
			
			if(error == 0) 
			{
			//if the configuration checked was ok and with no problems then we can add it
			//to the solution vector.
				current_solution[point_i] = currentConfig;
				if (point_i < N-1 )
				{
					configIteration(ThetaM, point_i+1, currentConfig, trajectoryTimes, current_solution, Solutions);
				}
				else
				{
					int[] New_solution = current_solution;
					Solutions.add(New_solution);
					System.out.println("New solution found, the number of solutions is : " + Solutions.size());
					
				}
				
			}
			
		}
		
	}
	
	private int checkConfiguration(int current_config, int prev_config, int point_i, double[][][] ThetaM, ArrayList<Double> trajectoryTimes){
		int error = 0;
		//point_i will be for sure >= 1
		double dTime = trajectoryTimes.get(point_i) - trajectoryTimes.get(point_i -1);
		double[] w_max = {200.0, 200.0, 260.0, 360, 360, 450};
		double[] Th_max = new double[w_max.length];
 		
		for(int i = 0; i< w_max.length; i++){ 
			w_max[i] = w_max[i] * Math.PI/180;
			Th_max[i] = w_max[i] * dTime;
		}
		double[] Th_assump = Th_max;
		double delta_joint;
		
		for(int j = 0 ; j < ThetaM.length ; j++)
		{
			delta_joint = Math.abs(ThetaM[j][current_config][point_i] - ThetaM[j][prev_config][point_i-1]);
			
			if((j <= 2 || j == 4) && (delta_joint > Th_assump[j]))
			{
				return error = 1;
			}
		
			if((j == 3 || j == 5) && (delta_joint > Th_assump[j]))
			{
				double delta_joint4p = Math.abs(ThetaM[j][current_config][point_i] + 2*Math.PI - ThetaM[j][prev_config][point_i-1]);
				double delta_joint4m = Math.abs(ThetaM[j][current_config][point_i] - 2*Math.PI - ThetaM[j][prev_config][point_i-1]);
			
				if(delta_joint4p < Th_assump[j]) { ThetaM[j][current_config][point_i] += 2*Math.PI; }
				else
				{
					if(delta_joint4m < Th_assump[j]){ ThetaM[j][current_config][point_i] -= 2*Math.PI;}
					else
					{
						return error = 1;
					}
				}
			}	
		}	
		return error;
	}
	
	public void displayThetaM(int index){
		//System.out.println(thetaM.length + " " + thetaM[0].length + " " + thetaM[0][0].length);
		System.out.println("");
		System.out.println("ThetaM of index " + index);
		
		for(int i = 0; i < thetaM[0][0].length; i++){
			if(i != index){
				continue;
			}
			
			for(int j = 0; j < thetaM.length; j++){
				for(int k = 0; k < thetaM[0].length; k++){
					System.out.print(thetaM[j][k][i] + " ");
				}
				System.out.println(" ");
			}
		}
		System.out.println(" ");
	}
	
}
