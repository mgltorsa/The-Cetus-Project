#include <stdio.h>
#include <stdlib.h>
#include <math.h>

#include "globals.h"
#include "../common/randdp.h"
#include "../common/timers.h"
#include "../common/print_results.h"

/* common / main_int_mem / */
static int colidx[NZ];
static int rowstr[NA+1];
static int iv[NA];
static int arow[NA];
static int acol[NAZ];

/* common / main_flt_mem / */
static double aelt[NAZ];
static double a[NZ];
static double x[NA+2];
static double z[NA+2];
static double p[NA+2];
static double q[NA+2];
static double r[NA+2];
static int naa;
static int nzz;
static int firstrow;
static int lastrow;
static int firstcol;
static int lastcol;
static double amult;
static double tran;
static logical timeron;
static void conj_grad(int colidx[], int rowstr[], double x[], double z[], double a[], double p[], double q[], double r[], double * rnorm);
static void makea(int n, int nz, double a[], int colidx[], int rowstr[], int firstrow, int lastrow, int firstcol, int lastcol, int arow[], int acol[][(21+1)], double aelt[][(21+1)], int iv[]);
static void sparse(double a[], int colidx[], int rowstr[], int n, int nz, int nozer, int arow[], int acol[][(21+1)], double aelt[][(21+1)], int firstrow, int lastrow, int nzloc[], double rcond, double shift);
static void sprnvc(int n, int nz, int nn1, double v[], int iv[]);
static int icnvrt(double x, int ipwr2);
static void vecset(int n, double v[], int iv[], int * nzv, int i, double val);
#include <sys/time.h>
float time_diff(struct timeval *start, struct timeval *end)
{
    return (end->tv_sec - start->tv_sec) + 1e-6*(end->tv_usec - start->tv_usec);
}


int main(int argc, char * argv[])
{
	int i, j, k, it;
	double zeta;
	double rnorm;
	double norm_temp1, norm_temp2;
	double t, mflops, tmax;
	char Class;
	logical verified;
	double zeta_verify_value, epsilon, err;
	char * t_names[3];
	FILE * fp;
	#pragma event main#0 start
	
	struct timeval start_main_0, end_main_0;
	gettimeofday(&start_main_0, NULL);
	
	#pragma loop name main#0 
	for (i=0; i<3; i ++ )
	{
		timer_clear(i);
	}
	#pragma event main#0 stop
	
	gettimeofday(&end_main_0, NULL);
	printf("Time main_0 seconds %0.8f ", time_diff(&start_main_0, &end_main_0));
	
	if ((fp=fopen("timer.flag", "r"))!=((void * )0))
	{
		timeron=true;
		t_names[0]="init";
		t_names[1]="benchmk";
		t_names[2]="conjgd";
		fclose(fp);
	}
	else
	{
		timeron=false;
	}
	timer_start(0);
	firstrow=0;
	lastrow=(1500000-1);
	firstcol=0;
	lastcol=(1500000-1);
 if (NA == 1400 && NONZER == 7 && NITER == 15 && SHIFT == 10) {
    Class = 'S';
    zeta_verify_value = 8.5971775078648;
  } else if (NA == 7000 && NONZER == 8 && NITER == 15 && SHIFT == 12) {
    Class = 'W';
    zeta_verify_value = 10.362595087124;
  } else if (NA == 14000 && NONZER == 11 && NITER == 15 && SHIFT == 20) {
    Class = 'A';
    zeta_verify_value = 17.130235054029;
  } else if (NA == 75000 && NONZER == 13 && NITER == 75 && SHIFT == 60) {
    Class = 'B';
    zeta_verify_value = 22.712745482631;
  } else if (NA == 150000 && NONZER == 15 && NITER == 75 && SHIFT == 110) {
    Class = 'C';
    zeta_verify_value = 28.973605592845;
  } else if (NA == 1500000 && NONZER == 21 && NITER == 100 && SHIFT == 500) {
    Class = 'D';
    zeta_verify_value = 52.514532105794;
  } else if (NA == 9000000 && NONZER == 26 && NITER == 100 && SHIFT == 1500) {
    Class = 'E';
    zeta_verify_value = 77.522164599383;
  } else {
    Class = 'U';
  }
	printf("\n\n NAS Parallel Benchmarks (NPB3.3-SER-C) - CG Benchmark\n\n");
	printf(" Size: %11d\n", 1500000);
	printf(" Iterations: %5d\n", 100);
	printf("\n");
	naa=1500000;
	nzz=((1500000*(21+1))*(21+1));
	tran=3.14159265E8;
	amult=1.220703125E9;
	zeta=randlc( & tran, amult);
	makea(naa, nzz, a, colidx, rowstr, firstrow, lastrow, firstcol, lastcol, arow, (int (* )[(21+1)])((void * )acol), (double (* )[(21+1)])((void * )aelt), iv);
	#pragma event main#1 start
	
	struct timeval start_main_1, end_main_1;
	gettimeofday(&start_main_1, NULL);
	
	#pragma loop name main#1 
	for (j=0; j<((lastrow-firstrow)+1); j ++ )
	{
		#pragma loop name main#1#0 
		for (k=rowstr[j]; k<rowstr[j+1]; k ++ )
		{
			colidx[k]=(colidx[k]-firstcol);
		}
	}
	#pragma event main#1 stop
	
	gettimeofday(&end_main_1, NULL);
	printf("Time main_1 seconds %0.8f ", time_diff(&start_main_1, &end_main_1));
	
	#pragma event main#2 start
	
	struct timeval start_main_2, end_main_2;
	gettimeofday(&start_main_2, NULL);
	
	#pragma loop name main#2 
	for (i=0; i<(1500000+1); i ++ )
	{
		x[i]=1.0;
	}
	#pragma event main#2 stop
	
	gettimeofday(&end_main_2, NULL);
	printf("Time main_2 seconds %0.8f ", time_diff(&start_main_2, &end_main_2));
	
	#pragma event main#3 start
	
	struct timeval start_main_3, end_main_3;
	gettimeofday(&start_main_3, NULL);
	
	#pragma loop name main#3 
	for (j=0; j<((lastcol-firstcol)+1); j ++ )
	{
		q[j]=0.0;
		z[j]=0.0;
		r[j]=0.0;
		p[j]=0.0;
	}
	#pragma event main#3 stop
	
	gettimeofday(&end_main_3, NULL);
	printf("Time main_3 seconds %0.8f ", time_diff(&start_main_3, &end_main_3));
	
	zeta=0.0;
	#pragma event main#4 start
	
	struct timeval start_main_4, end_main_4;
	gettimeofday(&start_main_4, NULL);
	
	#pragma loop name main#4 
	for (it=1; it<=1; it ++ )
	{
		conj_grad(colidx, rowstr, x, z, a, p, q, r,  & rnorm);
		norm_temp1=0.0;
		norm_temp2=0.0;
		#pragma loop name main#4#0 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			norm_temp1=(norm_temp1+(x[j]*z[j]));
			norm_temp2=(norm_temp2+(z[j]*z[j]));
		}
		norm_temp2=(1.0/sqrt(norm_temp2));
		#pragma loop name main#4#1 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			x[j]=(norm_temp2*z[j]);
		}
	}
	#pragma event main#4 stop
	
	gettimeofday(&end_main_4, NULL);
	printf("Time main_4 seconds %0.8f ", time_diff(&start_main_4, &end_main_4));
	
	#pragma event main#5 start
	
	struct timeval start_main_5, end_main_5;
	gettimeofday(&start_main_5, NULL);
	
	#pragma loop name main#5 
	for (i=0; i<(1500000+1); i ++ )
	{
		x[i]=1.0;
	}
	#pragma event main#5 stop
	
	gettimeofday(&end_main_5, NULL);
	printf("Time main_5 seconds %0.8f ", time_diff(&start_main_5, &end_main_5));
	
	zeta=0.0;
	timer_stop(0);
	printf(" Initialization time = %15.3f seconds\n", timer_read(0));
	timer_start(1);
	#pragma event main#6 start
	
	struct timeval start_main_6, end_main_6;
	gettimeofday(&start_main_6, NULL);
	
	#pragma loop name main#6 
	for (it=1; it<=100; it ++ )
	{
		if (timeron)
		{
			timer_start(2);
		}
		conj_grad(colidx, rowstr, x, z, a, p, q, r,  & rnorm);
		if (timeron)
		{
			timer_stop(2);
		}
		norm_temp1=0.0;
		norm_temp2=0.0;
		#pragma loop name main#6#0 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			norm_temp1=(norm_temp1+(x[j]*z[j]));
			norm_temp2=(norm_temp2+(z[j]*z[j]));
		}
		norm_temp2=(1.0/sqrt(norm_temp2));
		zeta=(500.0+(1.0/norm_temp1));
		if (it==1)
		{
			printf("\n   iteration           ||r||                 zeta\n");
		}
		printf("    %5d       %20.14E%20.13f\n", it, rnorm, zeta);
		#pragma loop name main#6#1 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			x[j]=(norm_temp2*z[j]);
		}
	}
	#pragma event main#6 stop
	
	gettimeofday(&end_main_6, NULL);
	printf("Time main_6 seconds %0.8f ", time_diff(&start_main_6, &end_main_6));
	
	timer_stop(1);
	t=timer_read(1);
	printf(" Benchmark completed\n");
	epsilon=1.0E-10;
	if (Class!='U')
	{
		err=(fabs(zeta-zeta_verify_value)/zeta_verify_value);
		if (err<=epsilon)
		{
			verified=true;
			printf(" VERIFICATION SUCCESSFUL\n");
			printf(" Zeta is    %20.13E\n", zeta);
			printf(" Error is   %20.13E\n", err);
		}
		else
		{
			verified=false;
			printf(" VERIFICATION FAILED\n");
			printf(" Zeta                %20.13E\n", zeta);
			printf(" The correct zeta is %20.13E\n", zeta_verify_value);
		}
	}
	else
	{
		verified=false;
		printf(" Problem size unknown\n");
		printf(" NO VERIFICATION PERFORMED\n");
	}
	if (t!=0.0)
	{
		mflops=(((((double)((2*100)*1500000))*(((3.0+((double)(21*(21+1))))+(25.0*(5.0+((double)(21*(21+1))))))+3.0))/t)/1000000.0);
	}
	else
	{
		mflops=0.0;
	}
	print_results("CG", Class, 1500000, 0, 0, 100, t, mflops, "          floating point", verified, "3.3.1", "03 Mar 2020", "gcc", "$(CC)", "-lm", "-I../common", "-g -Wall -O3 -mcmodel=large", "-O3 -mcmodel=large", "randdp");
	if (timeron)
	{
		tmax=timer_read(1);
		if (tmax==0.0)
		{
			tmax=1.0;
		}
		printf("  SECTION   Time (secs)\n");
		#pragma event main#7 start
		
		struct timeval start_main_7, end_main_7;
		gettimeofday(&start_main_7, NULL);
		
		#pragma loop name main#7 
		for (i=0; i<3; i ++ )
		{
			t=timer_read(i);
			if (i==0)
			{
				printf("  %8s:%9.3f\n", t_names[i], t);
			}
			else
			{
				printf("  %8s:%9.3f  (%6.2f%%)\n", t_names[i], t, (t*100.0)/tmax);
				if (i==2)
				{
					t=(tmax-t);
					printf("    --> %8s:%9.3f  (%6.2f%%)\n", "rest", t, (t*100.0)/tmax);
				}
			}
		}
		#pragma event main#7 stop
		
		gettimeofday(&end_main_7, NULL);
		printf("Time main_7 seconds %0.8f ", time_diff(&start_main_7, &end_main_7));
		
	}
	return 0;
}

static void conj_grad(int colidx[], int rowstr[], double x[], double z[], double a[], double p[], double q[], double r[], double * rnorm)
{
	int j, k;
	int cgit, cgitmax = 25;
	double d, sum, rho, rho0, alpha, beta;
	rho=0.0;
	#pragma event conj_grad#0 start
	
	struct timeval start_conj_grad_0, end_conj_grad_0;
	gettimeofday(&start_conj_grad_0, NULL);
	
	#pragma loop name conj_grad#0 
	for (j=0; j<(naa+1); j ++ )
	{
		q[j]=0.0;
		z[j]=0.0;
		r[j]=x[j];
		p[j]=r[j];
	}
	#pragma event conj_grad#0 stop
	
	gettimeofday(&end_conj_grad_0, NULL);
	printf("Time conj_grad_0 seconds %0.8f ", time_diff(&start_conj_grad_0, &end_conj_grad_0));
	
	#pragma event conj_grad#1 start
	
	struct timeval start_conj_grad_1, end_conj_grad_1;
	gettimeofday(&start_conj_grad_1, NULL);
	
	#pragma loop name conj_grad#1 
	for (j=0; j<((lastcol-firstcol)+1); j ++ )
	{
		rho=(rho+(r[j]*r[j]));
	}
	#pragma event conj_grad#1 stop
	
	gettimeofday(&end_conj_grad_1, NULL);
	printf("Time conj_grad_1 seconds %0.8f ", time_diff(&start_conj_grad_1, &end_conj_grad_1));
	
	#pragma event conj_grad#2 start
	
	struct timeval start_conj_grad_2, end_conj_grad_2;
	gettimeofday(&start_conj_grad_2, NULL);
	
	#pragma loop name conj_grad#2 
	for (cgit=1; cgit<=cgitmax; cgit ++ )
	{
		#pragma loop name conj_grad#2#0 
		for (j=0; j<((lastrow-firstrow)+1); j ++ )
		{
			sum=0.0;
			#pragma loop name conj_grad#2#0#0 
			for (k=rowstr[j]; k<rowstr[j+1]; k ++ )
			{
				sum=(sum+(a[k]*p[colidx[k]]));
			}
			q[j]=sum;
		}
		d=0.0;
		#pragma loop name conj_grad#2#1 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			d=(d+(p[j]*q[j]));
		}
		alpha=(rho/d);
		rho0=rho;
		rho=0.0;
		#pragma loop name conj_grad#2#2 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			z[j]=(z[j]+(alpha*p[j]));
			r[j]=(r[j]-(alpha*q[j]));
		}
		#pragma loop name conj_grad#2#3 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			rho=(rho+(r[j]*r[j]));
		}
		beta=(rho/rho0);
		#pragma loop name conj_grad#2#4 
		for (j=0; j<((lastcol-firstcol)+1); j ++ )
		{
			p[j]=(r[j]+(beta*p[j]));
		}
	}
	#pragma event conj_grad#2 stop
	
	gettimeofday(&end_conj_grad_2, NULL);
	printf("Time conj_grad_2 seconds %0.8f ", time_diff(&start_conj_grad_2, &end_conj_grad_2));
	
	sum=0.0;
	#pragma event conj_grad#3 start
	
	struct timeval start_conj_grad_3, end_conj_grad_3;
	gettimeofday(&start_conj_grad_3, NULL);
	
	#pragma loop name conj_grad#3 
	for (j=0; j<((lastrow-firstrow)+1); j ++ )
	{
		d=0.0;
		#pragma loop name conj_grad#3#0 
		for (k=rowstr[j]; k<rowstr[j+1]; k ++ )
		{
			d=(d+(a[k]*z[colidx[k]]));
		}
		r[j]=d;
	}
	#pragma event conj_grad#3 stop
	
	gettimeofday(&end_conj_grad_3, NULL);
	printf("Time conj_grad_3 seconds %0.8f ", time_diff(&start_conj_grad_3, &end_conj_grad_3));
	
	#pragma event conj_grad#4 start
	
	struct timeval start_conj_grad_4, end_conj_grad_4;
	gettimeofday(&start_conj_grad_4, NULL);
	
	#pragma loop name conj_grad#4 
	for (j=0; j<((lastcol-firstcol)+1); j ++ )
	{
		d=(x[j]-r[j]);
		sum=(sum+(d*d));
	}
	#pragma event conj_grad#4 stop
	
	gettimeofday(&end_conj_grad_4, NULL);
	printf("Time conj_grad_4 seconds %0.8f ", time_diff(&start_conj_grad_4, &end_conj_grad_4));
	
	( * rnorm)=sqrt(sum);
}

static void makea(int n, int nz, double a[], int colidx[], int rowstr[], int firstrow, int lastrow, int firstcol, int lastcol, int arow[], int acol[][(21+1)], double aelt[][(21+1)], int iv[])
{
	int iouter, ivelt, nzv, nn1;
	int ivc[(21+1)];
	double vc[(21+1)];
	nn1=1;
	do
	{
		nn1=(2*nn1);
	}while(nn1<n);
	
	#pragma event makea#0 start
	
	struct timeval start_makea_0, end_makea_0;
	gettimeofday(&start_makea_0, NULL);
	
	#pragma loop name makea#0 
	for (iouter=0; iouter<n; iouter ++ )
	{
		nzv=21;
		sprnvc(n, nzv, nn1, vc, ivc);
		vecset(n, vc, ivc,  & nzv, iouter+1, 0.5);
		arow[iouter]=nzv;
		#pragma loop name makea#0#0 
		for (ivelt=0; ivelt<nzv; ivelt ++ )
		{
			acol[iouter][ivelt]=(ivc[ivelt]-1);
			aelt[iouter][ivelt]=vc[ivelt];
		}
	}
	#pragma event makea#0 stop
	
	gettimeofday(&end_makea_0, NULL);
	printf("Time makea_0 seconds %0.8f ", time_diff(&start_makea_0, &end_makea_0));
	
	sparse(a, colidx, rowstr, n, nz, 21, arow, acol, aelt, firstrow, lastrow, iv, 0.1, 500.0);
}

static void sparse(double a[], int colidx[], int rowstr[], int n, int nz, int nozer, int arow[], int acol[][(21+1)], double aelt[][(21+1)], int firstrow, int lastrow, int nzloc[], double rcond, double shift)
{
	int nrows;
	int i, j, j1, j2, nza, k, kk, nzrow, jcol;
	double size, scale, ratio, va;
	logical cont40;
	nrows=((lastrow-firstrow)+1);
	#pragma event sparse#0 start
	
	struct timeval start_sparse_0, end_sparse_0;
	gettimeofday(&start_sparse_0, NULL);
	
	#pragma loop name sparse#0 
	for (j=0; j<(nrows+1); j ++ )
	{
		rowstr[j]=0;
	}
	#pragma event sparse#0 stop
	
	gettimeofday(&end_sparse_0, NULL);
	printf("Time sparse_0 seconds %0.8f ", time_diff(&start_sparse_0, &end_sparse_0));
	
	#pragma event sparse#1 start
	
	struct timeval start_sparse_1, end_sparse_1;
	gettimeofday(&start_sparse_1, NULL);
	
	#pragma loop name sparse#1 
	for (i=0; i<n; i ++ )
	{
		#pragma loop name sparse#1#0 
		for (nza=0; nza<arow[i]; nza ++ )
		{
			j=(acol[i][nza]+1);
			rowstr[j]=(rowstr[j]+arow[i]);
		}
	}
	#pragma event sparse#1 stop
	
	gettimeofday(&end_sparse_1, NULL);
	printf("Time sparse_1 seconds %0.8f ", time_diff(&start_sparse_1, &end_sparse_1));
	
	rowstr[0]=0;
	#pragma event sparse#2 start
	
	struct timeval start_sparse_2, end_sparse_2;
	gettimeofday(&start_sparse_2, NULL);
	
	#pragma loop name sparse#2 
	for (j=1; j<(nrows+1); j ++ )
	{
		rowstr[j]=(rowstr[j]+rowstr[j-1]);
	}
	#pragma event sparse#2 stop
	
	gettimeofday(&end_sparse_2, NULL);
	printf("Time sparse_2 seconds %0.8f ", time_diff(&start_sparse_2, &end_sparse_2));
	
	nza=(rowstr[nrows]-1);
	if (nza>nz)
	{
		printf("Space for matrix elements exceeded in sparse\n");
		printf("nza, nzmax = %d, %d\n", nza, nz);
		exit(1);
	}
	#pragma event sparse#3 start
	
	struct timeval start_sparse_3, end_sparse_3;
	gettimeofday(&start_sparse_3, NULL);
	
	#pragma loop name sparse#3 
	for (j=0; j<nrows; j ++ )
	{
		#pragma loop name sparse#3#0 
		for (k=rowstr[j]; k<rowstr[j+1]; k ++ )
		{
			a[k]=0.0;
			colidx[k]=( - 1);
		}
		nzloc[j]=0;
	}
	#pragma event sparse#3 stop
	
	gettimeofday(&end_sparse_3, NULL);
	printf("Time sparse_3 seconds %0.8f ", time_diff(&start_sparse_3, &end_sparse_3));
	
	size=1.0;
	ratio=pow(rcond, 1.0/((double)n));
	#pragma event sparse#4 start
	
	struct timeval start_sparse_4, end_sparse_4;
	gettimeofday(&start_sparse_4, NULL);
	
	#pragma loop name sparse#4 
	for (i=0; i<n; i ++ )
	{
		#pragma loop name sparse#4#0 
		for (nza=0; nza<arow[i]; nza ++ )
		{
			j=acol[i][nza];
			scale=(size*aelt[i][nza]);
			#pragma loop name sparse#4#0#0 
			for (nzrow=0; nzrow<arow[i]; nzrow ++ )
			{
				jcol=acol[i][nzrow];
				va=(aelt[i][nzrow]*scale);
				if ((jcol==j)&&(j==i))
				{
					va=((va+rcond)-shift);
				}
				cont40=false;
				#pragma loop name sparse#4#0#0#0 
				for (k=rowstr[j]; k<rowstr[j+1]; k ++ )
				{
					if (colidx[k]>jcol)
					{
						#pragma loop name sparse#4#0#0#0#0 
						for (kk=(rowstr[j+1]-2); kk>=k; kk -- )
						{
							if (colidx[kk]>( - 1))
							{
								a[kk+1]=a[kk];
								colidx[kk+1]=colidx[kk];
							}
						}
						colidx[k]=jcol;
						a[k]=0.0;
						cont40=true;
						break;
					}
					else
					{
						if (colidx[k]==( - 1))
						{
							colidx[k]=jcol;
							cont40=true;
							break;
						}
						else
						{
							if (colidx[k]==jcol)
							{
								nzloc[j]=(nzloc[j]+1);
								cont40=true;
								break;
							}
						}
					}
				}
				if (cont40==false)
				{
					printf("internal error in sparse: i=%d\n", i);
					exit(1);
				}
				a[k]=(a[k]+va);
			}
		}
		size=(size*ratio);
	}
	#pragma event sparse#4 stop
	
	gettimeofday(&end_sparse_4, NULL);
	printf("Time sparse_4 seconds %0.8f ", time_diff(&start_sparse_4, &end_sparse_4));
	
	#pragma event sparse#5 start
	
	struct timeval start_sparse_5, end_sparse_5;
	gettimeofday(&start_sparse_5, NULL);
	
	#pragma loop name sparse#5 
	for (j=1; j<nrows; j ++ )
	{
		nzloc[j]=(nzloc[j]+nzloc[j-1]);
	}
	#pragma event sparse#5 stop
	
	gettimeofday(&end_sparse_5, NULL);
	printf("Time sparse_5 seconds %0.8f ", time_diff(&start_sparse_5, &end_sparse_5));
	
	#pragma event sparse#6 start
	
	struct timeval start_sparse_6, end_sparse_6;
	gettimeofday(&start_sparse_6, NULL);
	
	#pragma loop name sparse#6 
	for (j=0; j<nrows; j ++ )
	{
		if (j>0)
		{
			j1=(rowstr[j]-nzloc[j-1]);
		}
		else
		{
			j1=0;
		}
		j2=(rowstr[j+1]-nzloc[j]);
		nza=rowstr[j];
		#pragma loop name sparse#6#0 
		for (k=j1; k<j2; k ++ )
		{
			a[k]=a[nza];
			colidx[k]=colidx[nza];
			nza=(nza+1);
		}
	}
	#pragma event sparse#6 stop
	
	gettimeofday(&end_sparse_6, NULL);
	printf("Time sparse_6 seconds %0.8f ", time_diff(&start_sparse_6, &end_sparse_6));
	
	#pragma event sparse#7 start
	
	struct timeval start_sparse_7, end_sparse_7;
	gettimeofday(&start_sparse_7, NULL);
	
	#pragma loop name sparse#7 
	for (j=1; j<(nrows+1); j ++ )
	{
		rowstr[j]=(rowstr[j]-nzloc[j-1]);
	}
	#pragma event sparse#7 stop
	
	gettimeofday(&end_sparse_7, NULL);
	printf("Time sparse_7 seconds %0.8f ", time_diff(&start_sparse_7, &end_sparse_7));
	
	nza=(rowstr[nrows]-1);
}

static void sprnvc(int n, int nz, int nn1, double v[], int iv[])
{
	int nzv, ii, i;
	double vecelt, vecloc;
	nzv=0;
	while (nzv<nz)
	{
		logical was_gen = false;
		vecelt=randlc( & tran, amult);
		vecloc=randlc( & tran, amult);
		i=(icnvrt(vecloc, nn1)+1);
		if (i>n)
		{
			continue;
		}
		#pragma event sprnvc#0 start
		
		struct timeval start_sprnvc_0, end_sprnvc_0;
		gettimeofday(&start_sprnvc_0, NULL);
		
		#pragma loop name sprnvc#0 
		for (ii=0; ii<nzv; ii ++ )
		{
			if (iv[ii]==i)
			{
				was_gen=true;
				break;
			}
		}
		#pragma event sprnvc#0 stop
		
		gettimeofday(&end_sprnvc_0, NULL);
		printf("Time sprnvc_0 seconds %0.8f ", time_diff(&start_sprnvc_0, &end_sprnvc_0));
		
		if (was_gen)
		{
			continue;
		}
		v[nzv]=vecelt;
		iv[nzv]=i;
		nzv=(nzv+1);
	}
}

static int icnvrt(double x, int ipwr2)
{
	return ((int)(ipwr2*x));
}

static void vecset(int n, double v[], int iv[], int * nzv, int i, double val)
{
	int k;
	logical set;
	set=false;
	#pragma event vecset#0 start
	
	struct timeval start_vecset_0, end_vecset_0;
	gettimeofday(&start_vecset_0, NULL);
	
	#pragma loop name vecset#0 
	for (k=0; k<( * nzv); k ++ )
	{
		if (iv[k]==i)
		{
			v[k]=val;
			set=true;
		}
	}
	#pragma event vecset#0 stop
	
	gettimeofday(&end_vecset_0, NULL);
	printf("Time vecset_0 seconds %0.8f ", time_diff(&start_vecset_0, &end_vecset_0));
	
	if (set==false)
	{
		v[ * nzv]=val;
		iv[ * nzv]=i;
		( * nzv)=(( * nzv)+1);
	}
}
