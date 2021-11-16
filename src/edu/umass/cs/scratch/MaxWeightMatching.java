package edu.umass.cs.scratch;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MaxWeightMatching {
			
		static Integer[][] d = {
				{0,2,6,7,9,3},
				{3,0,2,1,5,0},
				{1,2,0,3,4,5},
				{5,4,3,0,2,1},
				{0,2,4,6,0,3},
				{6,4,2,0,6,0}
		};
		
		static int factorial(int n) {
			int f=1;
			for(int i=1; i<=n; i++)
				f*=i;
			return f;
		}
		
		// assumes in is a square matrix
		static Integer[][] perm(Set<Integer> in) {
			Integer[] array = (Integer[])in.toArray(new Integer[0]);
			Integer[][] out = new Integer[factorial(in.size())][in.size()];

			if(in.size()==1) {
				out[0][0] = array[0];
			}
			else if(in.size()==2) {
				Integer[][] out1 = {{array[0],array[1]},{array[1],array[0]}};
				out = out1;
			}
			else {
					for(int j=0; j<in.size();j++) {
						Set<Integer> inLower = new HashSet<Integer>(in);
						inLower.remove(array[j]);
						Integer[][] outLower = perm(inLower);
						for(int k=0; k<outLower.length; k++) {
							out[j*outLower.length+k][0] = array[j];
							for(int l=1; l<=inLower.size(); l++) {
								out[j*outLower.length+k][l] = outLower[k][l-1];
							}
						}
					}
				}
			
			return out;
			}
		
		// m is square
		static Integer[] maxWeight(Integer[][] m, int W) {
			Integer[] numbers = new Integer[m.length];
			for(int i=0; i<numbers.length; i++) numbers[i] =i;
			Integer[][] perms = perm(new HashSet<Integer>(Arrays.asList(numbers)));
			int maxWeight = 0;
			Integer[] maxWeightPerm = null;
			for(int i=0; i<perms.length; i++) {
				int weight=0;
				for(int j=0; j<perms[i].length; j++) {
					weight += Math.min(d[j][perms[i][j]], W);
				}
				if(weight >= maxWeight) {
					maxWeightPerm = perms[i];
					maxWeight = weight;
					System.out.println(i + " : " + Arrays.asList(perms[i]) + " : " + weight);
				}
			}
			return maxWeightPerm;
		}
		
		public static void test() {
			Integer[] inArray = {3,8,9,5};
			Set<Integer> in = new HashSet<Integer>();
			for(int i=0; i<inArray.length; i++) {
				in.add(inArray[i]);
			}
			Integer[][] out = perm(in);
			for(int i=0; i<out.length; i++)
				System.out.println(Arrays.asList(out[i]));
		}
		
		public static void main(String[] args) {
			System.out.println(Arrays.asList(maxWeight(d,3)));
		}

}
