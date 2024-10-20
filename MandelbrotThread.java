import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * ����� MandelbrotThread ��������� ��������� Runnable � ������������ ��� ��������� ����� ����������� ��������� ������������
 * � ��������� ������.
 */
public class MandelbrotThread implements Runnable {

    private int x; // ���������� X ��� ���������
    private int getWidth; // ������ �����������
    private int getHeight; // ������ �����������
    private double ZOOM; // ������� ���������������
    private int MAX_ITER; // ������������ ���������� ��������
    private double offsetX; // �������� �� ��� X
    private double offsetY; // �������� �� ��� Y
    private BufferedImage image; // ����������� ��� ������ �����������
    

    /**
     * ����������� ������ MandelbrotThread.
     *
     * @param x ���������� X ��� ���������.
     * @param getWidth ������ �����������.
     * @param getHeight ������ �����������.
     * @param ZOOM ������� ���������������.
     * @param MAX_ITER ������������ ���������� ��������.
     * @param offsetX �������� �� ��� X.
     * @param offsetY �������� �� ��� Y.
     * @param image ����������� ��� ������ �����������.
     */
    public MandelbrotThread(int x, int getWidth, int getHeight, double ZOOM, int MAX_ITER, double offsetX, double offsetY, BufferedImage image) {
        this.x = x;
        this.getWidth = getWidth;
        this.getHeight = getHeight;
        this.ZOOM = ZOOM;
        this.MAX_ITER = MAX_ITER;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.image = image;
    }

    /**
     * ����� run ��������� ��������� ����� ����������� ��������� ������������.
     */
    @Override
    public void run() {
        for (int y = 0; y < getHeight; y++) {
            double zx = 0, zy = 0;
            double cX = (x - getWidth / 1.75) / ZOOM + offsetX;
            double cY = (y - getHeight / 1.75) / ZOOM + offsetY;
            int i = MAX_ITER;
            while (zx * zx + zy * zy < 4 && i > 0) {
                double tmp = zx * zx - zy * zy + cX;
                zy = 2.0 * zx * zy + cY;
                zx = tmp;
                i--;
            }
            int color = i | (i << 10) | (i << 14);
            image.setRGB(x, y, i > 0 ? color : 0);
        }
    }
}