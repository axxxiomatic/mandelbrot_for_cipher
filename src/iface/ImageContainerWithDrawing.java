import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ImageContainerWithDrawing extends JLayeredPane {
    private List<Point> points = new ArrayList<>();
    private boolean drawingRectangle = false;
    private JPanel drawingPanel;

    public ImageContainerWithDrawing(ImageIcon myIcon) {
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.WHITE, 2),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)
        ));

        // Создаем панель для отображения изображения
        JPanel imagePanel = new JPanel(new BorderLayout());
        if (myIcon != null) {
            JLabel image = new JLabel(myIcon);
            image.setPreferredSize(new Dimension(1024, 720));
            imagePanel.add(image, BorderLayout.CENTER);
        } else {
            JLabel noImageLabel = new JLabel("Изображение не найдено");
            noImageLabel.setFont(new Font("Serif", Font.BOLD, 24));
            noImageLabel.setPreferredSize(new Dimension(1024, 720));
            noImageLabel.setForeground(Color.WHITE);
            noImageLabel.setHorizontalAlignment(JLabel.CENTER);
            noImageLabel.setVerticalAlignment(JLabel.CENTER);
            imagePanel.add(noImageLabel, BorderLayout.CENTER);
            imagePanel.setBackground(Color.BLACK);
        }
        imagePanel.setBounds(0, 0, 1024, 768);
        add(imagePanel, JLayeredPane.DEFAULT_LAYER);

        // Создаем панель для рисования
        drawingPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;

                if (points.size() == 2) {
                    Point p1 = points.get(0);
                    Point p2 = points.get(1);
                    int x = Math.min(p1.x, p2.x);
                    int y = Math.min(p1.y, p2.y);
                    int width = Math.abs(p1.x - p2.x);
                    int height = Math.abs(p1.y - p2.y);
                    g2d.setColor(new Color(0xeb3471));
                    g2d.drawRect(x, y, width, height);

                    // Рисуем отрезок между точками
                    g2d.setColor(new Color(0xeb3471));
                    g2d.drawLine(p1.x, p1.y, p2.x, p2.y);

                    // Вычисляем длину отрезка
                    int distance = (int) Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));

                    // Определяем позицию для текста
                    int textX = (p1.x + p2.x) / 2;
                    int textY = (p1.y + p2.y) / 2;

                    // Рисуем текст с белым цветом и черным контуром
                    g2d.setFont(new Font("Arial", Font.BOLD, 20));

                    g2d.setColor(Color.BLACK);
                    g2d.drawString("Диагональ: " + distance + " пикселей", textX - 2 - distance / 4, textY - 2 + 10);
                    g2d.drawString("Диагональ: " + distance + " пикселей", textX + 2 - distance / 4, textY + 2 + 10);
                    g2d.drawString("Диагональ: " + distance + " пикселей", textX - 2 - distance / 4, textY + 2 + 10);
                    g2d.drawString("Диагональ: " + distance + " пикселей", textX + 2 - distance / 4, textY - 2 + 10);

                    g2d.setColor(Color.WHITE);
                    g2d.drawString("Диагональ: " + distance + " пикселей", textX - distance / 4, textY + 10);
                }

                g2d.setColor(Color.RED);
                for (Point point : points) {
                    // Рисуем белый контур толщиной 2 пикселя
                    g2d.setColor(Color.WHITE);
                    g2d.fillOval(point.x - 5, point.y - 5, 10, 10);

                    // Рисуем красную точку внутри белого контура
                    g2d.setColor(Color.RED);
                    g2d.fillOval(point.x - 4, point.y - 4, 8, 8);
                }
            }
        };
        drawingPanel.setOpaque(false);
        drawingPanel.setBounds(0, 0, imagePanel.getWidth(), imagePanel.getHeight());
        add(drawingPanel, JLayeredPane.PALETTE_LAYER);

        // Добавляем слушатели событий мыши
        drawingPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (points.size() < 2) {
                        points.add(e.getPoint());
                        repaint();
                    }
                    if (points.size() == 2) {
                        drawingRectangle = true;
                        repaint();
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    points.clear();
                    drawingRectangle = false;
                    repaint();
                }
            }
        });
    }

    @Override
    public void doLayout() {
        super.doLayout();
        for (Component comp : getComponents()) {
            comp.setBounds(0, 0, getWidth(), getHeight());
        }
    }
}