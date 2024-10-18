import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class Main {
	public static void main(String[] args) {
		try {
            // Loading image
            BufferedImage image = ImageIO.read(new File("resources/input.jpg"));
            int width = image.getWidth();
            int height = image.getHeight();

            // Translate image into matrix of pixels
            Model myModel = new Model(image, height, width);
            myModel.translatePixelsToNumbers(height, width);

            // Show image and matrix
            View myView = new View("Your image", image, myModel.getImageMatrix());
            myView.showImage();
            myView.setTitle("Your matrix");
            myView.showMatrix();
            
    		BufferedImage image2 = ImageIO.read(new File("resources/mandelbrot1.png"));
            int width2 = image2.getWidth();
            int height2 = image2.getHeight();

            // Translate image into matrix of pixels
            Model myModel2 = new Model(image2, height2, width2);
            myModel2.translatePixelsToNumbers(height2, width2);

            // Show image and matrix
            View myView2 = new View("Your image", image2, myModel2.getImageMatrix());
            myView2.showImage();
            myView2.setTitle("Your matrix");
            myView2.showMatrix();

        } catch (IOException e) {
            e.printStackTrace();
        }
		
		SwingUtilities.invokeLater(() -> {
	        JFrame frame = new JFrame("Mandelbrot");
	        Mandelbrot mandelbrot = new Mandelbrot();
	        frame.add(mandelbrot);
	        frame.setSize(1024, 720);
	        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
	        frame.setVisible(true);

	        mandelbrot.generateImage();
	    });
	}
}