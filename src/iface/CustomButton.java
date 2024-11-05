import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CustomButton extends JButton {
    private boolean pressed = false;

    public CustomButton(String text) {
        super(text);
        setContentAreaFilled(false); // Отключаем стандартную заливку фона
        setFocusPainted(false); // Отключаем рамку фокуса
        setBorderPainted(false); // Отключаем рамку
        setForeground(Color.WHITE); // Белый цвет текста
        setFont(new Font("Arial", Font.BOLD, 14)); // Явно заданный шрифт

        // Добавляем слушатель мыши для определения нажатия
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressed = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pressed = false;
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (pressed) {
            // Градиент при нажатии
            GradientPaint gradient = new GradientPaint(0, 0, new Color(0x257dcf), getWidth(), getHeight(), new Color(0x0773d9));
            g2d.setPaint(gradient);
            g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10); // Закругленные углы
        } else {
            // Прозрачный фон с белым контуром
            g2d.setColor(new Color(0, 0, 0, 0)); // Прозрачный фон
            g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10); // Закругленные углы
        }

        g2d.dispose();
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (!pressed) {
            // Белый контур толщины 2
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawRoundRect(4, 4, getWidth() - 8, getHeight() - 8, 10, 10); // Закругленные углы
        }

        g2d.dispose();
    }
}