#include <stdio.h>
#include <iostream>
#include <iomanip>
#include <time.h>
#include <cstdlib>
#include <omp.h>
#include <algorithm>

using namespace std;

#define SYSTEMTIME clock_t

 
void OnMult(int m_ar, int m_br) 
{
    SYSTEMTIME Time1, Time2;
    
    char st[100];
    double temp;
    int i, j, k;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    Time1 = clock();

    for(i = 0; i < m_ar; i++)
    {
        for(j = 0; j < m_br; j++)
        {
            temp = 0;
            for(k = 0; k < m_ar; k++)
            {    
                temp += pha[i*m_ar + k] * phb[k*m_br + j];
            }
            phc[i*m_ar + j] = temp;
        }
    }

    Time2 = clock();
    snprintf(st, sizeof(st), "Time: %3.3f seconds\n",
         (double)(Time2 - Time1) / CLOCKS_PER_SEC);
    cout << st;

    cout << "Result matrix: " << endl;
    for(i = 0; i < 1; i++)
    {
        for(j = 0; j < min(10, m_br); j++)
            cout << phc[j] << " ";
    }
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}


// Basic matrix multiplication with parallelization Version 1 (parallelizing the outer loop)
void OnMultParallel1(int m_ar, int m_br, int nthreads)
{
    double Time1, Time2;
    char st[100];
    int i, j, k;
    double temp;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    omp_set_num_threads(nthreads);

    Time1 = omp_get_wtime();

    #pragma omp parallel for private(j, k, temp)
    for(i = 0; i < m_ar; i++)
    {
        for(j = 0; j < m_br; j++)
        {
            temp = 0.0;
            for(k = 0; k < m_ar; k++)
            {
                temp += pha[i*m_ar + k] * phb[k*m_br + j];
            }
            phc[i*m_br + j] = temp;
        }
    }

    Time2 = omp_get_wtime();

    snprintf(st, sizeof(st), "Time: %3.3f seconds\n", Time2 - Time1);
    cout << st;
    cout << "Threads: " << nthreads << endl;

    cout << "Result matrix: " << endl;
    for(j = 0; j < min(10, m_br); j++)
        cout << phc[j] << " ";
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}



// Basic matrix multiplication with parallelization Version 2 (parallelizing the innermost loop with reduction)
void OnMultParallel2(int m_ar, int m_br, int nthreads)
{
    double Time1, Time2;
    char st[100];
    int i, j, k;
    double temp;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    omp_set_num_threads(nthreads);

    Time1 = omp_get_wtime();

    for(i = 0; i < m_ar; i++)
    {
        for(j = 0; j < m_br; j++)
        {
            temp = 0.0;

            #pragma omp parallel for reduction(+:temp)
            for(k = 0; k < m_ar; k++)
            {
                temp += pha[i*m_ar + k] * phb[k*m_br + j];
            }

            phc[i*m_br + j] = temp;
        }
    }

    Time2 = omp_get_wtime();

    snprintf(st, sizeof(st), "Time: %3.3f seconds\n", Time2 - Time1);
    cout << st;
    cout << "Threads: " << nthreads << endl;

    cout << "Result matrix: " << endl;
    for(j = 0; j < min(10, m_br); j++)
        cout << phc[j] << " ";
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}


// Line-by-line matrix multiplication
void OnMultLine(int m_ar, int m_br)
{
SYSTEMTIME Time1, Time2;
    char st[100];
    int i, j, k;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    // Ensure the result matrix is initialized to 0
    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_br; j++)
            phc[i*m_ar + j] = 0.0;

    Time1 = clock();

    // i-k-j loop order for cache optimization
    for(i = 0; i < m_ar; i++)
    {
        for(k = 0; k < m_ar; k++)
        {
            for(j = 0; j < m_br; j++)
            {    
                phc[i*m_ar + j] += pha[i*m_ar + k] * phb[k*m_br + j];
            }
        }
    }

    Time2 = clock();
    snprintf(st, sizeof(st), "Time: %3.3f seconds\n",
         (double)(Time2 - Time1) / CLOCKS_PER_SEC);
    cout << st;

    cout << "Result matrix: " << endl;
    for(i = 0; i < 1; i++)
    {
        for(j = 0; j < min(10, m_br); j++)
            cout << phc[j] << " ";
    }
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}


// Line-by-line matrix multiplication with parallelization Version 1 (parallelizing the outer loop)
void OnMultLineParallel1(int m_ar, int m_br, int nthreads)
{
    double Time1, Time2;
    char st[100];
    int i, j, k;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_br; j++)
            phc[i*m_br + j] = 0.0;

    omp_set_num_threads(nthreads);

    Time1 = omp_get_wtime();

    #pragma omp parallel for private(j, k)
    for(i = 0; i < m_ar; i++)
    {
        for(k = 0; k < m_ar; k++)
        {
            for(j = 0; j < m_br; j++)
            {
                phc[i*m_br + j] += pha[i*m_ar + k] * phb[k*m_br + j];
            }
        }
    }

    Time2 = omp_get_wtime();

    snprintf(st, sizeof(st), "Time: %3.3f seconds\n", Time2 - Time1);
    cout << st;
    cout << "Threads: " << nthreads << endl;

    cout << "Result matrix: " << endl;
    for(j = 0; j < min(10, m_br); j++)
        cout << phc[j] << " ";
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}


// Line-by-line matrix multiplication with parallelization Version 2 (parallelizing the innermost loop)
void OnMultLineParallel2(int m_ar, int m_br, int nthreads)
{
    double Time1, Time2;
    char st[100];
    int i, j, k;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_br; j++)
            phc[i*m_br + j] = 0.0;

    omp_set_num_threads(nthreads);

    Time1 = omp_get_wtime();

    for(i = 0; i < m_ar; i++)
    {
        for(k = 0; k < m_ar; k++)
        {
            #pragma omp parallel for
            for(j = 0; j < m_br; j++)
            {
                phc[i*m_br + j] += pha[i*m_ar + k] * phb[k*m_br + j];
            }
        }
    }

    Time2 = omp_get_wtime();

    snprintf(st, sizeof(st), "Time: %3.3f seconds\n", Time2 - Time1);
    cout << st;
    cout << "Threads: " << nthreads << endl;

    cout << "Result matrix: " << endl;
    for(j = 0; j < min(10, m_br); j++)
        cout << phc[j] << " ";
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}


// Block matrix multiplication
void OnMultBlock(int m_ar, int m_br, int bkSize)
{
SYSTEMTIME Time1, Time2;
    char st[100];
    int i, j, k, ii, jj, kk;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    // Initialize matrices
    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_ar; j++)
            pha[i*m_ar + j] = 1.0;

    for(i = 0; i < m_br; i++)
        for(j = 0; j < m_br; j++)
            phb[i*m_br + j] = (double)(i + 1);

    // Ensure the result matrix is initialized to 0
    for(i = 0; i < m_ar; i++)
        for(j = 0; j < m_br; j++)
            phc[i*m_ar + j] = 0.0;

    Time1 = clock();

    // Outer loops: iterating over the blocks
    for(ii = 0; ii < m_ar; ii += bkSize) {
        for(kk = 0; kk < m_ar; kk += bkSize) {
            for(jj = 0; jj < m_br; jj += bkSize) {
                
                // Inner loops: multiplying elements inside the current block
                // We use min() to prevent going out of bounds if the matrix size 
                // is not a perfect multiple of the block size.
                for(i = ii; i < min(ii + bkSize, m_ar); i++) {
                    for(k = kk; k < min(kk + bkSize, m_ar); k++) {
                        for(j = jj; j < min(jj + bkSize, m_br); j++) {
                            phc[i*m_ar + j] += pha[i*m_ar + k] * phb[k*m_br + j];
                        }
                    }
                }

            }
        }
    }

    Time2 = clock();
    snprintf(st, sizeof(st), "Time: %3.3f seconds\n",
         (double)(Time2 - Time1) / CLOCKS_PER_SEC);
    cout << st;

    cout << "Result matrix: " << endl;
    for(i = 0; i < 1; i++)
    {
        for(j = 0; j < min(10, m_br); j++)
            cout << phc[j] << " ";
    }
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}


int main(int argc, char *argv[])
{
    int lin, col, blockSize;
    int op;

    do {
        cout << endl << "1. Multiplication" << endl;
        cout << "2. Line Multiplication" << endl;
        cout << "3. Block Multiplication" << endl;
        cout << "4. Parallel Multiplication - Strategy 1" << endl;
        cout << "5. Parallel Multiplication - Strategy 2" << endl;
        cout << "6. Parallel Line Multiplication - Strategy 1" << endl;
        cout << "7. Parallel Line Multiplication - Strategy 2" << endl;
        cout << "0. Exit" << endl;
        cout << "Selection?: ";
        cin >> op;

        if (op == 0)
            break;

        cout << "Dimensions: lins=cols ? ";
        cin >> lin;
        col = lin;
        int nthreads = 1;

        switch (op) {
            case 1:
                OnMult(lin, col);
                break;
            case 2:
                OnMultLine(lin, col);
                break;
            case 3:
                cout << "Block Size? ";
                cin >> blockSize;
                OnMultBlock(lin, col, blockSize);
                break;
            case 4:
                cout << "Number of threads? ";
                cin >> nthreads;
                OnMultParallel1(lin, col, nthreads);
                break;
            case 5:
                cout << "Number of threads? ";
                cin >> nthreads;
                OnMultParallel2(lin, col, nthreads);
                break;
            case 6:
                cout << "Number of threads? ";
                cin >> nthreads;
                OnMultLineParallel1(lin, col, nthreads);
                break;
            case 7:
                cout << "Number of threads? ";
                cin >> nthreads;
                OnMultLineParallel2(lin, col, nthreads);
                break;
        }

    } while (op != 0);

    return 0;
}