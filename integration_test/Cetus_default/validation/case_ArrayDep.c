/*
Copyright (C) 1991-2022 Free Software Foundation, Inc.
   This file is part of the GNU C Library.

   The GNU C Library is free software; you can redistribute it andor
   modify it under the terms of the GNU Lesser General Public
   License as published by the Free Software Foundation; either
   version 2.1 of the License, or (at your option) any later version.

   The GNU C Library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Lesser General Public License for more details.

   You should have received a copy of the GNU Lesser General Public
   License along with the GNU C Library; if not, see
   <https:www.gnu.org/licenses/>. 
*/
/*
This header is separate from features.h so that the compiler can
   include it implicitly at the start of every compilation.  It must
   not itself include <features.h> or any other header that includes
   <features.h> because the implicit include comes before any feature
   test macros that may be defined in a source file before it first
   explicitly includes a system header.  GCC knows the name of this
   header in order to preinclude it. 
*/
/*
glibc's intent is to support the IEC 559 math functionality, real
   and complex.  If the GCC (4.9 and later) predefined macros
   specifying compiler intent are available, use them to determine
   whether the overall intent is to support these features; otherwise,
   presume an older compiler has intent to support these features and
   define these macros by default. 
*/
/*
wchar_t uses Unicode 10.0.0.  Version 10.0 of the Unicode Standard is
   synchronized with ISOIEC 10646:2017, fifth edition, plus
   the following additions from Amendment 1 to the fifth edition:
   - 56 emoji characters
   - 285 hentaigana
   - 3 additional Zanabazar Square characters
*/
/*
Examples


*/
int main()
{
	int a[10], b[10], c[10], d[100000000][100000000];
	int k;
	int i;
	int j;
	int _ret_val_0;
	#pragma cetus private(k) 
	#pragma loop name main#0 
	#pragma cetus parallel 
	/*
	Disabled due to low profitability: #pragma omp parallel for private(k)
	*/
	for (k=0; k<10; k ++ )
	{
		a[k]=k;
		b[k]=(k-10);
		c[k]=1;
	}
	/* Flow dependence */
	#pragma cetus private(i) 
	#pragma loop name main#1 
	for (i=1; i<10000; i ++ )
	{
		a[i]=b[i];
		c[i]=a[i-1];
	}
	#pragma cetus private(i) 
	#pragma loop name main#2 
	#pragma cetus parallel 
	#pragma omp parallel for private(i)
	for (i=1; i<10000; i ++ )
	{
		a[i]=b[i];
		c[i]=(a[i]+b[i-1]);
	}
	/* Antidependence */
	#pragma cetus private(i) 
	#pragma loop name main#3 
	for (i=1; i<10000; i ++ )
	{
		a[i-1]=b[i];
		c[i]=a[i];
	}
	/* Output dependence */
	#pragma cetus private(i) 
	#pragma loop name main#4 
	for (i=1; i<10000; i ++ )
	{
		a[i]=b[i];
		a[i+1]=c[i];
	}
	#pragma cetus private(i, j) 
	#pragma loop name main#5 
	#pragma cetus parallel 
	#pragma omp parallel for private(i, j)
	for (i=0; i<10000; i ++ )
	{
		#pragma cetus private(j) 
		#pragma loop name main#5#0 
		for (j=0; j<10000; j ++ )
		{
			d[i][j]=(i+j);
		}
	}
	/* loop interchange */
	#pragma cetus private(i, j) 
	#pragma loop name main#6 
	for (i=0; i<10000; i ++ )
	{
		#pragma cetus private(j) 
		#pragma loop name main#6#0 
		#pragma cetus parallel 
		#pragma omp parallel for private(j)
		for (j=0; j<10000; j ++ )
		{
			d[i+1][j+2]=(d[i][j]+1);
		}
	}
	#pragma cetus private(i, j) 
	#pragma loop name main#7 
	#pragma cetus parallel 
	#pragma omp parallel for private(i, j)
	for (i=0; i<10000; i ++ )
	{
		#pragma cetus private(j) 
		#pragma loop name main#7#0 
		for (j=0; j<10000; j ++ )
		{
			d[i][j+2]=(d[i][j]+1);
		}
	}
	#pragma cetus private(i, j) 
	#pragma loop name main#8 
	for (i=0; i<10000; i ++ )
	{
		#pragma cetus private(j) 
		#pragma loop name main#8#0 
		#pragma cetus parallel 
		#pragma omp parallel for private(j)
		for (j=0; j<10000; j ++ )
		{
			d[i+1][j-2]=(d[i][j]+1);
		}
	}
	_ret_val_0=0;
	return _ret_val_0;
}
