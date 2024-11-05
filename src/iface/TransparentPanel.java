import javax.swing.*;
import java.awt.*;

class TransparentPanel extends JPanel {
    public TransparentPanel(LayoutManager layout) {
        super(layout);
        // Устанавливаем прозрачность фона
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Вызываем метод paintComponent суперкласса, чтобы нарисовать компоненты
        super.paintComponent(g);

        // Устанавливаем прозрачность фона
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.0f)); // 0.5f - полупрозрачность

        // Рисуем фон
        g2d.setColor(getBackground());
        g2d.fillRect(0, 0, getWidth(), getHeight());

        g2d.dispose();
    }
}