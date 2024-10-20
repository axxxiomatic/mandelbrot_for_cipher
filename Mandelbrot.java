import javax.swing.*;
import java.awt.image.BufferedImage;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Graphics;
import java.util.Random;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * ����� Mandelbrot ������������ ����� ����������� ��������� Swing, ������� ���������� ����������� ��������� ������������
 * � ��������� ������������ ��������� ��������������� ����������� �� ������� ����. ����� ���������� ��������������� ���
 * ��������� ��������� ����������� � �������� ��� ������������.
 *
 * @author dankotyt
 */
public class Mandelbrot extends JPanel {
    private int MAX_ITER = 150; // ������������ ���������� �������� ��� ��������� ��������
    private double ZOOM = 800; // ��������� ������� ���������������
    private double offsetX = 0; // �������� �� ��� X
    private double offsetY = 0; // �������� �� ��� Y
    private BufferedImage image; // �������� �����������
    private int numberSave = 0; // ����� ������������ �����������

    /**
     * ����������� ������ Mandelbrot.
     * ��������� ���������� ������� ���� ��� ��������� ��������� �����������.
     */
    public Mandelbrot() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isRightMouseButton(e)) { //��������� ���������
                    generateImage();
                }
            }
        });
    }

    /**
     * �������������� ����� paintComponent ��� ��������� ���������������� ����������� ��������� ������������.
     *
     * @param g ����������� �������� ��� ���������.
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null) {
            g.drawImage(image, 0, 0, null); // ������ ����������� �����������
        }
    }

    /**
     * ���������� ��������� �������� ��� ���������� MAX_ITER, offsetX, offsetY � ZOOM.
     */
    public void randomPositionOnPlenty() {
        Random random = new Random();

        MAX_ITER = 500 + (random.nextInt(91) * 10); // 91 ��� ��������� �� 0 �� 90, ����� �������� 300, 310 � �� 1200

        // ������������ offsetX � offsetY �� -0.9998 �� 0.9998
        offsetX = -0.9998 + (random.nextDouble() * (0.9998 - -0.9998));
        offsetY = -0.9998 + (random.nextDouble() * (0.9998 - -0.9998));
        ZOOM = 50000 + (random.nextInt(44) * 1000); /*��� ������ ��������� ���, ��� ������ ����� �������������� ��������
                                                        ������ ���������� ����������� ����� ����� ��-�� �������� ���-��
                                                        ��������� �������� */
        repaint();
    }

    /**
     * ���������� ����������� ��������� ������������ � ��������� ��� ������������.
     * ���� ����������� ������������� �������� ������������, ��� ������������ � ������������ ������������ ��������� ���.
     * ���� ������������ ������������, ������������ ����� �����������.
     */
    public void generateImage() {
        boolean validImage = false;
        int attempt = 0;

        while (!validImage) {
            attempt++;
            // ��������� �����������
            randomPositionOnPlenty();
            image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
            //���������� ��� ������� ��� ���������� ��������� �����������
            ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            for (int x = 0; x < getWidth(); x++) {
                executor.submit(new MandelbrotThread(x, getWidth(), getHeight(), ZOOM, MAX_ITER, offsetX, offsetY, image));
            }

            executor.shutdown();
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // �������� �� ������������ ��������
            validImage = checkImageDiversity(image);
            if (!validImage) {
                System.out.println("������� �" + attempt + ". ����������� �� ������������� ��������, ��������� ������������...");
            }
        }
        repaint();

        // ����������� ������������ � ���������� �����������
        int option = JOptionPane.showConfirmDialog(this, "������ ��������� ����������� �� ������� ����?", "��������� �����������", JOptionPane.YES_NO_OPTION);
        if (option == JOptionPane.YES_OPTION) {
            saveImageToDesktop(image);
        } else {
            generateImage(); // ���������� ����� �����������, ���� ������������ ���������
        }
    }

    /**
     * ��������� ������������ �������� � �����������.
     *
     * @param image ����������� ��� ��������.
     * @return true, ���� ����������� ������������� �������� ������������, ����� false.
     */
    private boolean checkImageDiversity(BufferedImage image) {
        Map<Integer, Integer> colorCount = new HashMap<>();
        int totalPixels = image.getWidth() * image.getHeight();

        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int color = image.getRGB(x, y);
                colorCount.put(color, colorCount.getOrDefault(color, 0) + 1);
            }
        }

        int uniqueColors = colorCount.size();
        int maxCount = colorCount.values().stream().max(Integer::compare).orElse(0);
        double percentage = (double) maxCount / totalPixels;

        return (uniqueColors > 500 && percentage < 0.25); // �������: ����� 20% � ����� 28% ������ �� ������ RGB ��� �������
    }

    /**
     * ��������� ����������� �� ������� ���� ������������.
     *
     * @param image ����������� ��� ����������.
     */
    private void saveImageToDesktop(BufferedImage image) {
        String desktopPath = "resources";
        numberSave++;
        File file = new File(desktopPath + File.separator + "mandelbrot" + numberSave +".png");

        try {
            ImageIO.write(image, "png", file);
            JOptionPane.showMessageDialog(this, "����������� ��������� �� ������� ����: " + file.getAbsolutePath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "������ ��� ���������� �����������: " + e.getMessage());
        }
    }
}