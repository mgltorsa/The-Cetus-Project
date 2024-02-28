
/* Scalar Reduction
  The loop contains a scalar reduction operation.
*/

#include <stdio.h>

int main(){

  int a[10000], b[10000] ,sum;
  int i, n;
  n = 10000;
  
  for (i=1; i<n; i++) {
    sum = sum + a[i];
  }

  #pragma experimental llm_opt start
  for (i=1; i<n; i++) {
    sum = sum + a[i];
  }
  #pragma experimental llm_opt stop


	
   return 0;
}


