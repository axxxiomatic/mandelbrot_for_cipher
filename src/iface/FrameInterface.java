import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.Random;
import java.io.File;

public class FrameInterface {

    private static CardLayout cardLayout;
    private static JPanel mainPanel;
    private static int screenWidth;
    private static int screenHeight;
    private static JProgressBar progressBar;

    private static final String[] IMAGE_PATHS = {
            "mandelbrot1.png", "mandelbrot2.png", "mandelbrot3.png",
            "mandelbrot4.png", "mandelbrot5.png", "mandelbrot6.png", "mandelbrot7.png"
    };

    private static final int NORTH_COL = 0x011324;
    private static final int SOUTH_COL = 0x011a30;
    private static final Font buttonFont = new Font("Arial", Font.BOLD, 16);
    private static final String RESOURCES_PATH = "C:/Users/8220920/IdeaProjects/MandelbrotIFace/resources/";
    private static final Dimension buttonSize = new Dimension(400, 40);
    private static final Dimension fieldSize = new Dimension(400, 30);

    public static void main(String[] args) {
        initializeScreenSize();
        createMainFrame();
    }

    private static void initializeScreenSize() {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        Dimension screenSize = toolkit.getScreenSize();
        screenWidth = screenSize.width;
        screenHeight = screenSize.height;
    }

    private static void createMainFrame() {
        JFrame mainFrame = new JFrame("Шифратор Мандельброта");
        mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainFrame.setSize(screenWidth, screenHeight);
        mainFrame.setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainFrame.add(mainPanel);
        mainFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        createLoadingScreen(mainFrame);
    }

    private static void createLoadingScreen(JFrame mainFrame) {
        JPanel loadingPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        loadingPanel.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        setGridConstraints(gbc, 0, 0, -1, -1, GridBagConstraints.HORIZONTAL);
        gbc.insets = new Insets(0, 0, 20, 0); // Отступ снизу для надписи

        JLabel loadingLabel = initializeNewLabel("Mandelbrott", 96, 0);
        loadingLabel.setHorizontalAlignment(SwingConstants.CENTER);
        loadingPanel.add(loadingLabel, gbc);

        progressBar = new JProgressBar(0, 3000);
        progressBar.setIndeterminate(false);
        progressBar.setPreferredSize(new Dimension(screenWidth / 2, 32));
        progressBar.setUI(new GradientProgressBarUI());
        progressBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.WHITE, 1),
                BorderFactory.createEmptyBorder(1, 1, 1, 1)
        ));

        setGridConstraints(gbc, -1, 1, -1, -1, -1);
        gbc.insets = new Insets(0, 0, 0, 0);
        loadingPanel.add(progressBar, gbc);

        mainPanel.add(loadingPanel, "LoadingPanel");
        cardLayout.show(mainPanel, "LoadingPanel");
        mainFrame.setVisible(true);

        // Используем SwingWorker для имитации загрузки
        SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i <= 3000; i++) {
                    publish(i);
                    Thread.sleep(1); // Задержка для имитации загрузки
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                for (int progress : chunks) {
                    progressBar.setValue(progress);
                }
            }

            @Override
            protected void done() {
                createStartPanel();
                cardLayout.show(mainPanel, "StartPanel");
            }
        };

        worker.execute();
    }

    private static void createStartPanel() {
        JPanel startPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        startPanel.setLayout(new GridBagLayout());

        JButton encryptButton = initializeNewButton("Зашифровать изображение", buttonSize, buttonFont,
                e -> {createEncrypt1Panel();
                    cardLayout.show(mainPanel, "Encrypt1Panel"); });
        JButton decryptButton = initializeNewButton("Расшифровать изображение", buttonSize, buttonFont,
                e -> {createDecrypt1Panel();
                    cardLayout.show(mainPanel, "Decrypt1Panel"); });

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        setGridConstraints(constraints, 0, 0, -1, -1, -1);
        startPanel.add(encryptButton, constraints);

        setGridConstraints(constraints, -1, 1, -1, -1, -1);
        startPanel.add(decryptButton, constraints);

        mainPanel.add(startPanel, "StartPanel");
    }

    private static void createEncrypt1Panel() {
        JPanel encrypt1Panel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        encrypt1Panel.setLayout(new GridBagLayout());
        JLabel fileLabel = initializeNewLabel("Выберите изображение для шифрования:", 32, 0);

        JButton uploadButton = initializeNewButton("Загрузить изображение", buttonSize, buttonFont,
                e -> {createEncryptLoadPanel();
                    cardLayout.show(mainPanel, "EncryptLoadPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "StartPanel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        setGridConstraints(constraints, 0, 0, -1, GridBagConstraints.CENTER, -1);
        encrypt1Panel.add(fileLabel, constraints);

        setGridConstraints(constraints, -1, 1, -1, -1, -1);
        encrypt1Panel.add(uploadButton, constraints);

        setGridConstraints(constraints, -1, 2, -1, -1, -1);
        encrypt1Panel.add(backButton, constraints);

        mainPanel.add(encrypt1Panel, "Encrypt1Panel");
    }

    private static void createEncryptLoadPanel() {
        JPanel encryptLoadPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        encryptLoadPanel.setLayout(new GridBagLayout());
        JLabel imageLabel = initializeNewLabel("Загруженное для шифрования изображение:", 32, 0);

        ImageIcon imageIcon = loadImageIcon(RESOURCES_PATH + "input.jpg");
        JPanel imageContainer = initializeImageContainer(imageIcon);

        JButton regenerateButton = initializeNewButton("Продолжить шифрование", buttonSize, buttonFont,
                e -> {createEncrypt2Panel();
                    cardLayout.show(mainPanel, "Encrypt2Panel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Encrypt1Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        setGridConstraints(constraints, 1, 0, -1, GridBagConstraints.CENTER, -1);
        encryptLoadPanel.add(imageLabel, constraints);

        setGridConstraints(constraints, 1, 1, 1, -1, GridBagConstraints.BOTH);
        encryptLoadPanel.add(imageContainer, constraints);

        setGridConstraints(constraints, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.NONE);

        JPanel buttonPanel = new TransparentPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(5, 5, 5, 5);

        setGridConstraints(buttonConstraints, 0, 0, -1, -1, -1);
        buttonPanel.add(regenerateButton, buttonConstraints);

        setGridConstraints(buttonConstraints, -1, 1, -1, -1, -1);
        buttonPanel.add(backButton, buttonConstraints);

        encryptLoadPanel.add(buttonPanel, constraints);
        mainPanel.add(encryptLoadPanel, "EncryptLoadPanel");
    }

    private static void createEncrypt2Panel() {
        JPanel encrypt2Panel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        encrypt2Panel.setLayout(new GridBagLayout());

        JLabel fileLabel2 = initializeNewLabel("Выберите изображение-ключ:", 32, 0);
        JButton generateButton = initializeNewButton("Сгенерировать изображение-ключ", buttonSize, buttonFont,
                e -> {createEncrypt3Panel();
                    cardLayout.show(mainPanel, "Encrypt3Panel");});
        JButton manualButton = initializeNewButton("Ввести параметры ключа вручную", buttonSize, buttonFont,
                e -> {createManualEncryptionPanel();
                    cardLayout.show(mainPanel, "ManualEncryptionPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Encrypt1Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        setGridConstraints(constraints, 0, 0, -1, GridBagConstraints.CENTER, -1);
        encrypt2Panel.add(fileLabel2, constraints);

        setGridConstraints(constraints, -1, 1, -1, -1, -1);
        encrypt2Panel.add(generateButton, constraints);

        setGridConstraints(constraints, -1, 2, -1, -1, -1);
        encrypt2Panel.add(manualButton, constraints);

        setGridConstraints(constraints, -1, 3, -1, -1, -1);
        encrypt2Panel.add(backButton, constraints);

        mainPanel.add(encrypt2Panel, "Encrypt2Panel");
    }

    private static void createEncrypt3Panel() {
        JPanel encrypt3Panel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        encrypt3Panel.setLayout(new GridBagLayout());
        JLabel imageLabel = initializeNewLabel("Ваше изображение-ключ:", 32, 0);

        String imagePath = getRandomImagePath();
        ImageIcon imageIcon = loadImageIcon(imagePath);
        JPanel imageContainer = initializeImageContainer(imageIcon);

        JButton regenerateButton = initializeNewButton("Сгенерировать заново", buttonSize, buttonFont,
                e -> {
                    String newImagePath = getRandomImagePath();
                    ImageIcon newImageIcon = loadImageIcon(newImagePath);
                    if (newImageIcon != null) {
                        imageContainer.removeAll();
                        imageContainer.add(new JLabel(newImageIcon), BorderLayout.CENTER);
                        imageContainer.revalidate();
                        imageContainer.repaint();
                    }
                });
        JButton manualButton = initializeNewButton("Сгенерировать заново вручную", buttonSize, buttonFont,
                e -> {createManualEncryptionPanel();
                    cardLayout.show(mainPanel, "ManualEncryptionPanel");});
        JButton okayButton = initializeNewButton("Зашифровать изображение", buttonSize, buttonFont,
                e -> {createEncryptFinalPanel();
                    cardLayout.show(mainPanel, "EncryptFinalPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Encrypt2Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        setGridConstraints(constraints, 1, 0, -1, GridBagConstraints.CENTER, -1);
        encrypt3Panel.add(imageLabel, constraints);

        setGridConstraints(constraints, 1, 1, 1, -1, GridBagConstraints.BOTH);
        encrypt3Panel.add(imageContainer, constraints);

        setGridConstraints(constraints, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.NONE);

        JPanel buttonPanel = new TransparentPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(5, 5, 5, 5);

        setGridConstraints(buttonConstraints, 0, 0, -1, -1, -1);
        buttonPanel.add(regenerateButton, buttonConstraints);

        setGridConstraints(buttonConstraints, -1, 1, -1, -1, -1);
        buttonPanel.add(manualButton, buttonConstraints);

        setGridConstraints(buttonConstraints, -1, 2, -1, -1, -1);
        buttonPanel.add(okayButton, buttonConstraints);

        setGridConstraints(buttonConstraints, -1, 3, -1, -1, -1);
        buttonPanel.add(backButton, buttonConstraints);

        encrypt3Panel.add(buttonPanel, constraints);
        mainPanel.add(encrypt3Panel, "Encrypt3Panel");
    }

    private static void createEncryptFinalPanel() {
        JPanel encryptFinalPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        encryptFinalPanel.setLayout(new GridBagLayout());
        JLabel imageLabel = initializeNewLabel("Полученное зашифрованное изображение:", 32, 0);

        ImageIcon imageIcon = loadImageIcon(RESOURCES_PATH + "encrypted_image.jpg");
        JPanel imageContainer = initializeImageContainer(imageIcon);

        JButton regenerateButton = initializeNewButton("Сгенерировать новый ключ", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Encrypt2Panel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "StartPanel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        addComponent(encryptFinalPanel, imageLabel, constraints, 1, 0, -1, GridBagConstraints.CENTER, -1);
        addComponent(encryptFinalPanel, imageContainer, constraints, 1, 1, 1, -1, GridBagConstraints.BOTH);
        setGridConstraints(constraints, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.NONE);

        JPanel buttonPanel = new TransparentPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(5, 5, 5, 5);

        addComponent(buttonPanel, regenerateButton, buttonConstraints, 0, 0, -1, -1, -1);
        addComponent(buttonPanel, backButton, buttonConstraints, -1, 1, -1, -1, -1);
        encryptFinalPanel.add(buttonPanel, constraints);
        mainPanel.add(encryptFinalPanel, "EncryptFinalPanel");
    }

    private static void createManualEncryptionPanel() {
        JPanel manualEncryptPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        manualEncryptPanel.setLayout(new GridBagLayout());

        JLabel label = initializeNewLabel("Введите значения параметров:", 32, 0);
        JLabel zoomLabel = initializeNewLabel("Масштаб множества:", 20, 1);
        JTextField zoomField = initializeNewTextField(20, fieldSize);
        JLabel iterationsLabel = initializeNewLabel("Число итераций:", 20, 1);
        JTextField iterationsField = initializeNewTextField(20, fieldSize);
        JLabel xLabel = initializeNewLabel("Смещение по оси X:", 20, 1);
        JTextField xField = initializeNewTextField(20, fieldSize);
        JLabel yLabel = initializeNewLabel("Смещение по оси Y:", 20, 1);
        JTextField yField = initializeNewTextField(20, fieldSize);

        JButton saveButton = initializeNewButton("Сохранить сгенерированный ключ", buttonSize, buttonFont,
                e -> {createEncrypt3Panel();
                    cardLayout.show(mainPanel, "Encrypt3Panel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Encrypt2Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 10, 5, 10);

        addComponent(manualEncryptPanel, label, constraints, 0, 0, 2, -1, -1);
        addComponent(manualEncryptPanel, zoomLabel, constraints, -1, 1, 1, -1, -1);
        addComponent(manualEncryptPanel, zoomField, constraints, 1, -1, -1, -1, -1);
        addComponent(manualEncryptPanel, iterationsLabel, constraints, 0, 2, -1, -1, -1);
        addComponent(manualEncryptPanel, iterationsField, constraints, 1, -1, -1, -1, -1);
        addComponent(manualEncryptPanel, xLabel, constraints, 0, 3, -1, -1, -1);
        addComponent(manualEncryptPanel, xField, constraints, 1, -1, -1, -1, -1);
        addComponent(manualEncryptPanel, yLabel, constraints, 0, 4, -1, -1, -1);
        addComponent(manualEncryptPanel, yField, constraints, 1, -1, -1, -1, -1);

        addComponent(manualEncryptPanel, saveButton, constraints, 0, 5, 2, -1, -1);
        addComponent(manualEncryptPanel, backButton, constraints, -1, 6, -1, -1, -1);

        mainPanel.add(manualEncryptPanel, "ManualEncryptionPanel");
    }

    private static void createDecrypt1Panel() {
        JPanel decrypt1Panel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        decrypt1Panel.setLayout(new GridBagLayout());

        JLabel fileLabel = initializeNewLabel("Выберите изображение для расшифрования:", 32, 0);
        JButton uploadButton = initializeNewButton("Загрузить изображение из файла", buttonSize, buttonFont,
                e -> {createDecryptLoadPanel();
                    cardLayout.show(mainPanel, "DecryptLoadPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "StartPanel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        addComponent(decrypt1Panel, fileLabel, constraints, 0, 0, -1, -1, -1);
        addComponent(decrypt1Panel, uploadButton, constraints, -1, 1, 1, -1, -1);
        addComponent(decrypt1Panel, backButton, constraints, -1, 2, -1, -1, -1);

        mainPanel.add(decrypt1Panel, "Decrypt1Panel");
    }

    private static void createDecryptLoadPanel() {
        JPanel decryptLoadPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        decryptLoadPanel.setLayout(new GridBagLayout());
        JLabel imageLabel = initializeNewLabel("Загруженное для расшифровки изображение:", 32, 0);

        ImageIcon imageIcon = loadImageIcon(RESOURCES_PATH + "encrypted_image.jpg");
        JPanel imageContainer = initializeImageContainer(imageIcon);

        JButton regenerateButton = initializeNewButton("Продолжить расшифровку", buttonSize, buttonFont,
                e -> {createDecrypt2Panel();
                    cardLayout.show(mainPanel, "Decrypt2Panel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt1Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        addComponent(decryptLoadPanel, imageLabel, constraints, 1, 0, -1, GridBagConstraints.CENTER, -1);
        addComponent(decryptLoadPanel, imageContainer, constraints, 1, 1, 1, -1, GridBagConstraints.BOTH);
        setGridConstraints(constraints, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.NONE);

        JPanel buttonPanel = new TransparentPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(5, 5, 5, 5);

        addComponent(buttonPanel, regenerateButton, buttonConstraints, 0, 0, -1, -1, -1);
        addComponent(buttonPanel, backButton, buttonConstraints, -1, 1, -1, -1, -1);

        decryptLoadPanel.add(buttonPanel, constraints);
        mainPanel.add(decryptLoadPanel, "DecryptLoadPanel");
    }

    private static void createDecrypt2Panel() {
        JPanel decrypt2Panel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        decrypt2Panel.setLayout(new GridBagLayout());
        JLabel fileLabel2 = initializeNewLabel("Выберите изображение-ключ:", 32, 0);

        JButton uploadButton = initializeNewButton("Загрузить изображение-ключ из файла", buttonSize, buttonFont,
                e -> {createDecrypt3Panel();
                    cardLayout.show(mainPanel, "Decrypt3Panel");});
        JButton manualButton = initializeNewButton("Ввести параметры ключа вручную", buttonSize, buttonFont,
                e -> {createManualDecryptionPanel();
                    cardLayout.show(mainPanel, "ManualDecryptionPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt1Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        addComponent(decrypt2Panel, fileLabel2, constraints, 0, 0, -1, -1, -1);
        addComponent(decrypt2Panel, uploadButton, constraints, -1, 1, -1, -1, -1);
        addComponent(decrypt2Panel, manualButton, constraints, -1, 2, -1, -1, -1);
        addComponent(decrypt2Panel, backButton, constraints, -1, 3, -1, -1, -1);

        mainPanel.add(decrypt2Panel, "Decrypt2Panel");
    }

    private static void createDecrypt3Panel() {
        JPanel decrypt3Panel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        decrypt3Panel.setLayout(new GridBagLayout());
        JLabel imageLabel = initializeNewLabel("Ваше изображение-ключ:", 32, 0);

        String imagePath = getRandomImagePath();
        ImageIcon imageIcon = loadImageIcon(imagePath);
        JPanel imageContainer = initializeImageContainer(imageIcon);

        JButton regenerateButton = initializeNewButton("Загрузить другой ключ", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt2Panel");});
        JButton manualButton = initializeNewButton("Ввести другие параметры ключа", buttonSize, buttonFont,
                e -> {createManualDecryptionPanel();
                    cardLayout.show(mainPanel, "ManualDecryptionPanel");});
        JButton okayButton = initializeNewButton("Расшифровать изображение", buttonSize, buttonFont,
                e -> {createDecryptFinalPanel();
                    cardLayout.show(mainPanel, "DecryptFinalPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt2Panel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        addComponent(decrypt3Panel, imageLabel, constraints, 1, 0, -1, GridBagConstraints.CENTER, -1);
        addComponent(decrypt3Panel, imageContainer, constraints, 1, 1, 1, -1, GridBagConstraints.BOTH);
        setGridConstraints(constraints, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.NONE);

        JPanel buttonPanel = new TransparentPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(5, 5, 5, 5);

        addComponent(buttonPanel, regenerateButton, buttonConstraints, 0, 0, -1, -1, -1);
        addComponent(buttonPanel, manualButton, buttonConstraints, -1, 1, -1, -1, -1);
        addComponent(buttonPanel, okayButton, buttonConstraints, -1, 2, -1, -1, -1);
        addComponent(buttonPanel, backButton, buttonConstraints, -1, 3, -1, -1, -1);

        decrypt3Panel.add(buttonPanel, constraints);

        mainPanel.add(decrypt3Panel, "Decrypt3Panel");
    }

    private static void createDecryptFinalPanel() {
        JPanel decryptFinalPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        decryptFinalPanel.setLayout(new GridBagLayout());
        JLabel imageLabel = initializeNewLabel("Расшифрованное изображение:", 32, 0);

        ImageIcon imageIcon = loadImageIcon(RESOURCES_PATH + "decrypted_image.jpg");
        JPanel imageContainer = initializeImageContainer(imageIcon);

        JButton reloadButton = initializeNewButton("Загрузить другое изображение", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt1Panel");});
        JButton reloadKeyButton = initializeNewButton("Загрузить другой ключ", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt2Panel"); });
        JButton saveButton = initializeNewButton("Сохранить изображение", buttonSize, buttonFont,
                e -> {createEncryptFinalPanel();
                    cardLayout.show(mainPanel, "StartPanel");});
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "StartPanel");});

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(10, 10, 10, 10);

        addComponent(decryptFinalPanel, imageLabel, constraints, 1, 0, -1, GridBagConstraints.CENTER, -1);
        addComponent(decryptFinalPanel, imageContainer, constraints, 1, 1, 1, -1, GridBagConstraints.BOTH);
        setGridConstraints(constraints, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.NONE);

        JPanel buttonPanel = new TransparentPanel(new GridBagLayout());
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.insets = new Insets(5, 5, 5, 5);

        addComponent(buttonPanel, reloadButton, buttonConstraints, 0, 0, -1, -1, -1);
        addComponent(buttonPanel, reloadKeyButton, buttonConstraints, -1, 1, -1, -1, -1);
        addComponent(buttonPanel, saveButton, buttonConstraints, -1, 2, -1, -1, -1);
        addComponent(buttonPanel, backButton, buttonConstraints, -1, 3, -1, -1, -1);

        decryptFinalPanel.add(buttonPanel, constraints);
        mainPanel.add(decryptFinalPanel, "DecryptFinalPanel");
    }

    private static void createManualDecryptionPanel() {
        JPanel manualDecryptPanel = new GradientPanel(new Color(NORTH_COL), new Color(SOUTH_COL));
        manualDecryptPanel.setLayout(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 10, 5, 10);

        JLabel label = initializeNewLabel("Введите значения параметров ключа:", 32, 0);
        JLabel zoomLabel = initializeNewLabel("Масштаб множества:", 20, 1);
        JTextField zoomField = initializeNewTextField(20, fieldSize);
        JLabel iterationsLabel = initializeNewLabel("Количество итераций:", 20, 1);
        JTextField iterationsField = initializeNewTextField(20, fieldSize);
        JLabel xLabel = initializeNewLabel("Смещение по оси X:", 20, 1);
        JTextField xField = initializeNewTextField(20, fieldSize);
        JLabel yLabel = initializeNewLabel("Смещение по оси Y:", 20, 1);
        JTextField yField = initializeNewTextField(20, fieldSize);
        JButton saveButton = initializeNewButton("Сохранить сгенерированный ключ", buttonSize, buttonFont,
                e -> {createDecrypt3Panel();
                    cardLayout.show(mainPanel, "Decrypt3Panel"); });
        JButton backButton = initializeNewButton("Вернуться назад", buttonSize, buttonFont,
                e -> {cardLayout.show(mainPanel, "Decrypt2Panel"); });

        addComponent(manualDecryptPanel, label, constraints, 0, 0, 2, -1, -1);

        addComponent(manualDecryptPanel, zoomLabel, constraints, -1, 1, 1, -1, -1);
        addComponent(manualDecryptPanel, zoomField, constraints, 1, -1, -1, -1, -1);
        addComponent(manualDecryptPanel, iterationsLabel, constraints, 0, 2, -1, -1, -1);
        addComponent(manualDecryptPanel, iterationsField, constraints, 1, -1, -1, -1, -1);
        addComponent(manualDecryptPanel, xLabel, constraints, 0, 3, -1, -1, -1);
        addComponent(manualDecryptPanel, xField, constraints, 1, -1, -1, -1, -1);
        addComponent(manualDecryptPanel, yLabel, constraints, 0, 4, -1, -1, -1);
        addComponent(manualDecryptPanel, yField, constraints, 1, -1, -1, -1, -1);

        addComponent(manualDecryptPanel, saveButton, constraints, 0, 5, 2, -1, -1);
        addComponent(manualDecryptPanel, backButton, constraints, -1, 6, -1, -1, -1);

        mainPanel.add(manualDecryptPanel, "ManualDecryptionPanel");
    }

    private static JTextField initializeNewTextField(int my_columns, Dimension my_size) {
        JTextField myField = new JTextField(my_columns);
        myField.setPreferredSize(my_size);
        myField.setHorizontalAlignment(JTextField.CENTER);

        return myField;
    }

    private static JButton initializeNewButton(String my_text, Dimension my_size, Font my_font, ActionListener my_event) {
        JButton myButton = new CustomButton(my_text);
        myButton.setPreferredSize(my_size);
        myButton.setMinimumSize(my_size);
        myButton.setMaximumSize(my_size);
        myButton.setFont(my_font);

        myButton.addActionListener(e -> {
            my_event.actionPerformed(e);
        });

        return myButton;
    }

    private static JLabel initializeNewLabel(String my_text, int my_size, int my_flag) {
        JLabel myLabel = new JLabel(my_text);
        if (my_flag == 0)
            myLabel.setFont(new Font("Serif", Font.ITALIC + Font.BOLD, my_size));
        else if (my_flag == 1)
            myLabel.setFont(new Font("Serif", Font.PLAIN, my_size));

        myLabel.setForeground(Color.WHITE);
        return myLabel;
    }

    private static String getRandomImagePath() {
        Random random = new Random();
        String imageName = IMAGE_PATHS[random.nextInt(IMAGE_PATHS.length)];
        return RESOURCES_PATH + imageName; // Абсолютный путь к ресурсам
    }

    private static ImageIcon loadImageIcon(String path) {
        File imageFile = new File(path);
        if (!imageFile.exists()) {
            System.err.println("Не удалось найти изображение по пути: " + path);
            return null;
        }
        return new ImageIcon(path);
    }

    private static void setGridConstraints(GridBagConstraints gbc, int my_gridx, int my_gridy, int my_gridwidth, int my_anchor, int my_fill) {
        if (my_gridx != -1) {
            gbc.gridx = my_gridx;
        } if (my_gridy != -1) {
            gbc.gridy = my_gridy;
        } if (my_gridwidth != -1) {
            gbc.gridwidth = my_gridwidth;
        } if (my_anchor != -1) {
            gbc.anchor = my_anchor;
        } if (my_fill != -1) {
            gbc.fill = my_fill;
        }
    }

    private static void addComponent(JPanel my_panel, Component my_component, GridBagConstraints gbc,
                                     int my_gridx, int my_gridy, int my_gridwidth, int my_anchor, int my_fill) {
        if (my_gridx != -1) {
            gbc.gridx = my_gridx;
        } if (my_gridy != -1) {
            gbc.gridy = my_gridy;
        } if (my_gridwidth != -1) {
            gbc.gridwidth = my_gridwidth;
        } if (my_anchor != -1) {
            gbc.anchor = my_anchor;
        } if (my_fill != -1) {
            gbc.fill = my_fill;
        }
        my_panel.add(my_component, gbc);
    }

    private static JPanel initializeImageContainer(ImageIcon myIcon) {
        JPanel imageContainer = new JPanel(new BorderLayout());
        if (myIcon != null) {
            JLabel image = new JLabel(myIcon);
            imageContainer.add(image, BorderLayout.CENTER);
        } else {
            JLabel noImageLabel = new JLabel("Изображение не найдено");
            noImageLabel.setFont(new Font("Serif", Font.BOLD, 24));
            noImageLabel.setPreferredSize(new Dimension(1024, 720));
            noImageLabel.setForeground(Color.WHITE);
            noImageLabel.setHorizontalAlignment(JLabel.CENTER);
            noImageLabel.setVerticalAlignment(JLabel.CENTER);
            imageContainer.add(noImageLabel, BorderLayout.CENTER);
            imageContainer.setBackground(Color.BLACK);
        }

        imageContainer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.WHITE, 2),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)
        ));

        return imageContainer;
    }
}