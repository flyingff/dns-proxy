package net.flyingff.ns.monitor;

import net.flyingff.ns.IMonitor;
import net.flyingff.ns.RequestEntry;
import net.flyingff.ns.SimpleDNSRouter;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MonitorWindow implements IMonitor<RequestEntry> {
    private JList<RequestEntry> list;
    private JPanel panel1;
    private JProgressBar progress;
    private JLabel label;
    private JComboBox<String> combo;
    private JProgressBar progress2;
    private StatisticView viewer;
    private ArrayListModel<RequestEntry> model = new ArrayListModel<>();

    private MonitorWindow() {
        modifyUIComponents();
    }
    private void createUIComponents() {
        String[] serverArray = SimpleDNSRouter.Companion
                .getROOT_SERVER_SADDR_MAP().keySet().toArray(new String[0]);
        Arrays.sort(serverArray);
        combo = new JComboBox<>(serverArray);
        combo.addItemListener(e -> {
            String item = (String) e.getItem();
            synchronized (SimpleDNSRouter.Companion) {
                SimpleDNSRouter.Companion.
                        setROOT_SERVER_SADDR(SimpleDNSRouter.Companion.getROOT_SERVER_SADDR_MAP().get(item));
            }
        });
        combo.setSelectedIndex(0);
        SimpleDNSRouter.Companion.
                setROOT_SERVER_SADDR(SimpleDNSRouter.Companion.getROOT_SERVER_SADDR_MAP().get(serverArray[0]));
    }
    private void modifyUIComponents() {
        list.setModel(model);
        progress.setPreferredSize(new Dimension(16, 222));
        progress.setBorder(BorderFactory.createLineBorder(Color.gray, 1));
        progress.setMaximum(10);
        progress2.setPreferredSize(new Dimension(16, 222));
        progress2.setBorder(BorderFactory.createLineBorder(Color.gray, 1));
        progress2.setMaximum(100);
        progress2.setValue(0);

        list.setPreferredSize(new Dimension(280, 180));
        viewer.setPreferredSize(new Dimension(280, 40));
        viewer.setBorder(BorderFactory.createLineBorder(Color.gray, 1));
        label.setPreferredSize(new Dimension(316, 20));
        try {
            label.setIcon(new ImageIcon(ImageIO.read(MonitorWindow.class.getClassLoader().getResourceAsStream("info.png"))));
        } catch (Exception e) { e.printStackTrace(); }
        setLabel("Initialized.");
    }

    @Override public void setProgress(int val) {
        int v = Math.min(val, progress.getMaximum());
        progress.setValue(v);
    }
    @Override public void setProgress2(int val) {
        int v = Math.min(val, progress2.getMaximum());
        progress2.setValue(v);
    }
    @Override public void setLabel(String text) {
        label.setText(text);
    }
    @Override public void addItem(RequestEntry item) {
        model.add(item);
    }
    @Override public void removeItem(RequestEntry item) {
        model.remove(item);
    }
    @Override public void refresh() {
        model.refresh();
    }
    @Override public void addCurrency(int bits) {
        viewer.accumulate(bits);
    }

    public static void main(String[] args) throws Exception{
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        MonitorWindow mw = new MonitorWindow();
        JDialog frame = new JDialog((JFrame) null, "DNS Monitor");
        frame.setContentPane(mw.panel1);
        frame.setAlwaysOnTop(true);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { frame.setVisible(false); }
        });

        BufferedImage icon = ImageIO.read(MonitorWindow.class.getClassLoader().getResourceAsStream("server.png"));
        frame.setIconImage(icon);
        frame.pack();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int taskHeight = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration()).bottom;
        frame.setLocation(screenSize.width - frame.getWidth(), screenSize.height - frame.getHeight() - taskHeight);

        frame.setVisible(false);

        TrayIcon trayIcon = new TrayIcon(icon, "DNS Monitor");
        PopupMenu menu = new PopupMenu("Menu");
        MenuItem item = new MenuItem("Exit");
        item.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(item);

        trayIcon.setPopupMenu(menu);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if(e.getButton() == MouseEvent.BUTTON1) {
                    frame.setVisible(!frame.isVisible());
                    mw.viewer.accumulate(80);
                }
            }
        });

        SystemTray.getSystemTray().add(trayIcon);
        try {
            new SimpleDNSRouter(mw).run();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
    class ArrayListModel<T> extends AbstractListModel<T> {
        private List<T> list = new ArrayList<>();
        @Override public int getSize() { synchronized (MonitorWindow.this) {return list.size();} }
        @Override
        public T getElementAt(int index) { synchronized (MonitorWindow.this) { return list.get(index);} }

        void add(T x) {
            list.add(x);
            fireContentsChanged(this, list.size() - 1, list.size() - 1);
        }
        void remove(T x) {
            int index = list.indexOf(x);
            if(index > -1) {
                list.remove(index);
                fireContentsChanged(this, index, list.size() - 1);
            }
        }
        void refresh() {
            fireContentsChanged(this, 0, list.size() - 1);
        }
    }

}
