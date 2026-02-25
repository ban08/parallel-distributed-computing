import java.util.Scanner;

public class MatrixProduct {

    public static void onMult(int m_ar, int m_br) {
        long startTime, endTime;
        double temp;

        // Allocating 1D arrays to mimic contiguous memory allocation from C++
        double[] pha = new double[m_ar * m_ar];
        double[] phb = new double[m_ar * m_ar];
        double[] phc = new double[m_ar * m_ar];

        for (int i = 0; i < m_ar; i++) {
            for (int j = 0; j < m_ar; j++) {
                pha[i * m_ar + j] = 1.0;
            }
        }

        for (int i = 0; i < m_br; i++) {
            for (int j = 0; j < m_br; j++) {
                phb[i * m_br + j] = (double) (i + 1);
            }
        }

        startTime = System.currentTimeMillis();

        // The basic algorithm: line by column
        for (int i = 0; i < m_ar; i++) {
            for (int j = 0; j < m_br; j++) {
                temp = 0;
                for (int k = 0; k < m_ar; k++) {
                    temp += pha[i * m_ar + k] * phb[k * m_br + j];
                }
                phc[i * m_ar + j] = temp;
            }
        }

        endTime = System.currentTimeMillis();
        System.out.printf("Time: %3.3f seconds\n", (endTime - startTime) / 1000.0);

        System.out.println("Result matrix: ");
        for (int j = 0; j < Math.min(10, m_br); j++) {
            System.out.print(phc[j] + " ");
        }
        System.out.println();
    }

    public static void onMultLine(int m_ar, int m_br) {
        long startTime, endTime;

        double[] pha = new double[m_ar * m_ar];
        double[] phb = new double[m_ar * m_ar];
        double[] phc = new double[m_ar * m_ar];

        for (int i = 0; i < m_ar; i++) {
            for (int j = 0; j < m_ar; j++) {
                pha[i * m_ar + j] = 1.0;
            }
        }

        for (int i = 0; i < m_br; i++) {
            for (int j = 0; j < m_br; j++) {
                phb[i * m_br + j] = (double) (i + 1);
            }
        }
        
        // Initialize result array to 0
        for (int i = 0; i < m_ar * m_ar; i++) {
            phc[i] = 0.0;
        }

        startTime = System.currentTimeMillis();

        // Optimized Line-by-Line (i-k-j)
        for (int i = 0; i < m_ar; i++) {
            for (int k = 0; k < m_ar; k++) {
                for (int j = 0; j < m_br; j++) {
                    phc[i * m_ar + j] += pha[i * m_ar + k] * phb[k * m_br + j];
                }
            }
        }

        endTime = System.currentTimeMillis();
        System.out.printf("Time: %3.3f seconds\n", (endTime - startTime) / 1000.0);

        System.out.println("Result matrix: ");
        for (int j = 0; j < Math.min(10, m_br); j++) {
            System.out.print(phc[j] + " ");
        }
        System.out.println();
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int op, lin, col;

        do {
            System.out.println("\n1. Multiplication");
            System.out.println("2. Line Multiplication");
            System.out.println("0. Exit");
            System.out.print("Selection?: ");
            op = scanner.nextInt();

            if (op == 0) break;

            System.out.print("Dimensions: lins=cols ? ");
            lin = scanner.nextInt();
            col = lin;

            switch (op) {
                case 1:
                    onMult(lin, col);
                    break;
                case 2:
                    onMultLine(lin, col);
                    break;
            }

        } while (op != 0);
        
        scanner.close();
    }
}